package eu.kanade.tachiyomi.animeextension.pt.seriesflixnow

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.useAsJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class SeriesFlixNow : ParsedAnimeHttpLegacySource() {
    override val name = "SeriesFlixNow"
    override val baseUrl = "https://www.seriesflixnet.com"
    override val lang = "pt-BR"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    override fun popularAnimeRequest(page: Int): Request = GET(if (page == 1) "$baseUrl/series-online" else "$baseUrl/series-online/pagina/$page", headers)

    override fun popularAnimeSelector() = "a.card[href], a.serie-card[href]"
    override fun popularAnimeFromElement(element: Element) = animeFromCard(element)
    override fun popularAnimeNextPageSelector() = "link[rel=next], a[href*='/series-online/pagina/']"

    override fun latestUpdatesRequest(page: Int): Request = GET(if (page == 1) "$baseUrl/episodios-recentes" else "$baseUrl/episodios-recentes/pagina/$page", headers)

    override fun latestUpdatesSelector() = "a.serie-card[href], a.card[href], a[href^='/serie/'][href*='/temporada-']"
    override fun latestUpdatesFromElement(element: Element) = animeFromCard(element)
    override fun latestUpdatesNextPageSelector() = "link[rel=next], a[href*='/episodios-recentes/pagina/']"

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val seen = HashSet<String>()
        val animes = document.select(latestUpdatesSelector()).mapNotNull { card ->
            val href = card.attr("abs:href")
            val seriesUrl = Regex("/serie/([^/]+)/temporada-").find(href)?.let {
                "$baseUrl/serie/${it.groupValues[1]}-online"
            } ?: href
            seriesUrl.takeIf { it.isNotBlank() && seen.add(it) }?.let {
                animeFromCard(card).apply {
                    setUrlWithoutDomain(it)
                }
            }
        }
        return AnimesPage(animes, document.selectFirst(latestUpdatesNextPageSelector()) != null)
    }

    private fun animeFromCard(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        title = element.selectFirst("img")?.attr("alt")?.substringBefore(" —")?.substringBefore(" - T")?.ifBlank { null }
            ?: element.selectFirst("h3,h2,.title")?.text()?.trim().orEmpty()
        thumbnail_url = element.selectFirst("img")?.attr("abs:src")
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET(
        if (page == 1) {
            "$baseUrl/buscar?q=" + URLEncoder.encode(query, "UTF-8")
        } else {
            "$baseUrl/buscar?q=" + URLEncoder.encode(query, "UTF-8") + "&pagina=$page"
        },
        headers,
    )

    override fun searchAnimeSelector() = "a.card[href], a.serie-card[href]"
    override fun searchAnimeFromElement(element: Element) = animeFromCard(element)
    override fun searchAnimeNextPageSelector() = "link[rel=next], a[href*='pagina=']"

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        title = document.selectFirst(".filme-meta h1")?.text()?.substringBefore(" – Assistir")
            ?: document.selectFirst("h1")?.text().orEmpty()
        thumbnail_url = document.selectFirst("img.filme-poster, .filme-hero img, .poster")?.attr("abs:src")
        genre = document.select(".filme-meta .info-item a").eachText().joinToString()

        status = when {
            document.selectFirst(".info-box p:contains(Status)")?.text()?.contains("Finalizada", true) == true -> SAnime.COMPLETED
            document.selectFirst(".info-box p:contains(Status)")?.text()?.contains("Em exibição", true) == true -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }

        author = document.selectFirst(".info-box .info-item:contains(Criador) + ul li span")?.text()
            ?: document.selectFirst(".info-box p:contains(Criador)")?.text()?.substringAfter(":")?.trim()

        val synopsis = document.selectFirst(".sinopse, .descricao, .filme-description, [itemprop=description]")?.text()?.trim()
        val structuredDesc = runCatching {
            val jsonLd = document.selectFirst("script[type=application/ld+json]:containsData(TVSeries)")?.data().orEmpty()
            Regex("\"description\"\\s*:\\s*\"([^\"]+)\"").find(jsonLd)?.groupValues?.get(1)?.trim()?.ifBlank { null }
        }.getOrNull()

        val seoContent = document.select(".seo-content p, .seo-conteudo p").eachText()
            .filter { it.isNotBlank() && !it.contains("SERIESFLIX", true) }
            .joinToString("\n\n")
            .ifBlank { null }

        val metaDesc = document.selectFirst("meta[name=description]")?.attr("content")?.trim()?.ifBlank { null }

        val mainDesc = synopsis?.ifBlank { null } ?: structuredDesc ?: seoContent ?: metaDesc.orEmpty()

        val extraInfo = mutableListOf<String>()
        document.selectFirst(".rating-badge")?.text()?.trim()?.takeIf { it.isNotBlank() }?.let {
            extraInfo.add("Avaliação: $it")
        }
        document.selectFirst(".info-box p:contains(Status)")?.text()?.trim()?.takeIf { it.isNotBlank() }?.let {
            extraInfo.add(it)
        }
        document.selectFirst(".info-box p:contains(Idioma)")?.text()?.trim()?.takeIf { it.isNotBlank() }?.let {
            extraInfo.add(it)
        }
        document.selectFirst(".info-box p:contains(Temporadas)")?.text()?.trim()?.takeIf { it.isNotBlank() }?.let {
            extraInfo.add(it)
        }
        document.select(".info-box .info-item:contains(Elenco) + ul li span").eachText().takeIf { it.isNotEmpty() }?.let {
            extraInfo.add("Elenco: ${it.joinToString()}")
        }

        description = buildString {
            if (mainDesc.isNotBlank()) {
                append(mainDesc)
            }
            if (extraInfo.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(extraInfo.joinToString("\n"))
            }
        }.trim()
    }

    override fun episodeListRequest(anime: SAnime) = GET(baseUrl + anime.url, headers)
    override fun episodeListSelector() = ".temporada a.episodio-link[href]"
    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        name = element.selectFirst("h5")?.text()?.trim() ?: element.attr("title")
        episode_number = Regex("(?:temporada-|episodio-)(\\d+)").findAll(url).lastOrNull()?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        date_upload = parseDate(element.selectFirst(".episodio-info p")?.text()?.trim())
        scanlator = null
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val episodes = document.select(episodeListSelector()).map { episodeFromElement(it) }
        if (episodes.isEmpty() && document.selectFirst("button.btn-canal[data-url]") != null) {
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(response.request.url.encodedPath)
                    name = "Filme"
                    episode_number = 1f
                    date_upload = parseDate(
                        document.selectFirst(".filme-meta p:contains(Estreia), .info-box p:contains(Lançamento)")
                            ?.text()?.substringAfter(":")?.trim(),
                    )
                    scanlator = null
                },
            )
        }
        return episodes
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.useAsJsoup()
        val buttons = doc.select("button.btn-canal[data-url]")
        Log.d(DEBUG_TAG, "episode=${response.request.url} channels=${buttons.size}")
        val result = mutableListOf<Video>()
        buttons.forEach { button ->
            val url = button.attr("data-url").trim()
            if (!url.startsWith("http")) return@forEach
            val parent = button.closest(".canal-opcoes")?.id().orEmpty()
            val isDublado = parent.contains("dublado", true) || button.text().contains("dublado", true) || button.text().contains("canal 1", true)
            val label = if (isDublado) "SeriesFlixNow - Dublado" else "SeriesFlixNow - Legendado"
            Log.d(DEBUG_TAG, "player=$label url=$url")
            val videos = runCatching {
                playerExtractor.videosFromUrl(url, headers, label)
            }.getOrElse {
                Log.d(DEBUG_TAG, "extractor failure=${it.javaClass.simpleName}:${it.message}")
                emptyList()
            }
            Log.d(DEBUG_TAG, "extractor result=${videos.size}")
            result += videos
        }

        // When PlenoFlu / vaiquecol provides dual-audio streams but secondary channel is unavailable,
        // duplicate with Legendado label so users with subbed audio preference can play immediately.
        if (result.none { it.videoTitle.contains("Legendado", true) } && result.isNotEmpty()) {
            val legVideos = result.map { v ->
                Video(
                    v.videoUrl,
                    v.videoTitle.replace("Dublado", "Legendado"),
                    v.videoUrl,
                    v.headers,
                    v.subtitleTracks,
                    v.audioTracks,
                )
            }
            result += legVideos
        }

        Log.d(DEBUG_TAG, "videos=${result.size}")
        return result
    }

    override fun videoListSelector() = "#player-iframe[src]"

    override fun videoFromElement(element: Element) = Video(
        element.attr("abs:src"),
        "SeriesFlixNow",
        element.attr("abs:src"),
        headers,
    )

    override fun videoUrlParse(response: Response) = response.useAsJsoup()
        .selectFirst("video source[src], video[src]")?.attr("abs:src").orEmpty()

    private val playerExtractor by lazy { SeriesFlixPlayerExtractor(client) }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return runCatching {
            SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).parse(dateStr)?.time
        }.getOrNull() ?: 0L
    }

    private companion object {
        const val DEBUG_TAG = "SERIESFLIX_VIDEO_DEBUG"
    }
}
