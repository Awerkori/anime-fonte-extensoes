package eu.kanade.tachiyomi.animeextension.pt.berryanimes

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

internal data class ParsedEpisodeInfo(
    val url: String,
    val name: String,
    val episodeNumber: Float,
    val scanlator: String?,
)

internal object BerryAnimesHelper {

    fun parseCardInfo(card: EpisodeCard): ParsedEpisodeInfo {
        val metaParts = card.meta?.split(" | ")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val seasonFromMeta = metaParts.firstOrNull { it.startsWith("T") }
        val epFromMeta = metaParts.firstOrNull { it.startsWith("E") }?.removePrefix("E")?.toFloatOrNull()
        val dubLegFromMeta = metaParts.filter { it == "Dub" || it == "Leg" }.joinToString(" / ") {
            if (it == "Dub") "Dublado" else "Legendado"
        }.ifEmpty { null }

        val matchHref = Regex("""/(\d+)-(\d+)-(?:episodio|episode)""", RegexOption.IGNORE_CASE).find(card.href)
            ?: Regex("""(?:^|/)(\d+)-(\d+)""").find(card.href.substringAfterLast("/"))
        val seasonFromHref = matchHref?.groupValues?.get(1)
        val epFromHref = matchHref?.groupValues?.get(2)?.toFloatOrNull()
        val epFromTitle = Regex("""^(\d+(?:\.\d+)?)\.""").find(card.title.trim())?.groupValues?.get(1)?.toFloatOrNull()

        val season = when {
            seasonFromMeta != null -> seasonFromMeta
            card.season != null -> "T${card.season}"
            !seasonFromHref.isNullOrBlank() -> "T$seasonFromHref"
            else -> ""
        }

        val epNum = epFromMeta
            ?: card.number
            ?: epFromHref
            ?: epFromTitle
            ?: -1F

        val scanlator = dubLegFromMeta ?: when {
            card.hasDub == true && (card.hasSubtitles == true || card.subtitlesLabel?.isNotBlank() == true) -> "Dublado / Legendado"
            card.hasDub == true || card.dubLabel?.contains("Dub", ignoreCase = true) == true -> "Dublado"
            card.hasSubtitles == true || card.subtitlesLabel?.contains("Leg", ignoreCase = true) == true -> "Legendado"
            else -> null
        }

        val name = if (season.isNotEmpty()) "$season ${card.title}".trim() else card.title.trim()

        return ParsedEpisodeInfo(
            url = card.href,
            name = name,
            episodeNumber = epNum,
            scanlator = scanlator,
        )
    }

    fun parseSeasonsFromHtml(documentHtml: String, currentSeasonNumber: Int): List<Int> {
        val doc = Jsoup.parse(documentHtml)
        val otherSeasons = mutableSetOf<Int>()

        doc.select("button[role=option], button.AnimeDetail_seasonTrigger__MXufI, [aria-label=Temporada] button").forEach { btn ->
            val num = Regex("""Temporada\s+(\d+)""", RegexOption.IGNORE_CASE).find(btn.text())?.groupValues?.get(1)?.toIntOrNull()
            if (num != null && num != currentSeasonNumber) {
                otherSeasons.add(num)
            }
        }

        doc.select("a[href*=temporada=]").forEach { link ->
            val num = link.absUrl("href").toHttpUrlOrNull()?.queryParameter("temporada")?.toIntOrNull()
                ?: Regex("""[?&]temporada=(\d+)""").find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            if (num != null && num != currentSeasonNumber) {
                otherSeasons.add(num)
            }
        }

        return otherSeasons.sorted()
    }

    fun checkPlaybackAuthorization(watch: Watch) {
        check(watch.error == null) { "O site recusou a reprodução: ${watch.error}" }
        val ads = watch.ads
        if (ads?.adFree?.active != true) {
            check(ads?.required != true && ads?.mandatory != true && ads?.blocking != true) {
                "Este episódio exige anúncio no player oficial. A extensão não contorna essa etapa."
            }
        }
        check(watch.player != null) { "O site não disponibilizou o player para este episódio" }
    }
}
