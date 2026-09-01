package eu.kanade.tachiyomi.animeextension.pt.animesonlinesnet

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.animesonlinesnet.extractors.AniDriveExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimesOnlinesNet :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "AnimesOnlines Net"

    override val baseUrl = "https://animesonlines.net"

    override val lang = "pt-BR"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    private val aniDriveExtractor by lazy { AniDriveExtractor(client, headers) }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = "$baseUrl/lista-de-animes/".toHttpUrl().newBuilder().apply {
            addQueryParameter("ordem", "score")
            if (page > 1) {
                addQueryParameter("pagina", page.toString())
            }
        }.build().toString()
        return GET(url, headers)
    }

    override fun popularAnimeSelector(): String = "div.sc-catalog-grid a.sc-card, div.catalog-grid a.catalog-card"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        val titleEl = element.selectFirst("p.sc-overlay-name, span.sc-card-title, span.catalog-title")
        anime.title = titleEl?.text()?.trim() ?: element.attr("title").trim()
        val imgEl = element.selectFirst("div.sc-card-poster img, div.catalog-poster img, img")
        anime.thumbnail_url = imgEl?.attr("abs:src")?.takeIf { it.isNotBlank() }
            ?: imgEl?.attr("src")?.takeIf { it.isNotBlank() }
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.page-numbers:contains(Próximo), a.page-numbers:contains(Next), a.next.page-numbers"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page > 1) "$baseUrl/page/$page/" else "$baseUrl/"
        return GET(url, headers)
    }

    override fun latestUpdatesSelector(): String = "div.ep-grid a.ep-card, div.sc-catalog-grid a.sc-card"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val href = element.attr("href")

        if (href.contains("/episodio/")) {
            val epSlug = href.removeSuffix("/").substringAfterLast("/")
            val animeSlug = epSlug
                .replace(Regex("-episodio-\\d+.*$"), "")
                .replace(Regex("-\\d+x\\d+.*$"), "")
            anime.url = "/anime/$animeSlug/"

            val title = element.selectFirst("p.ep-anime-name")?.text()?.trim()
                ?: element.selectFirst("img")?.attr("alt")?.trim().orEmpty()
            anime.title = cleanAnimeTitle(title)

            val imgEl = element.selectFirst("div.ep-thumb img, img")
            anime.thumbnail_url = imgEl?.attr("abs:src")?.takeIf { it.isNotBlank() }
                ?: imgEl?.attr("src")?.takeIf { it.isNotBlank() }
        } else {
            anime.setUrlWithoutDomain(href)
            val titleEl = element.selectFirst("p.sc-overlay-name, span.sc-card-title, span.catalog-title")
            anime.title = titleEl?.text()?.trim() ?: element.attr("title").trim()
            val imgEl = element.selectFirst("div.sc-card-poster img, img")
            anime.thumbnail_url = imgEl?.attr("abs:src")?.takeIf { it.isNotBlank() }
                ?: imgEl?.attr("src")?.takeIf { it.isNotBlank() }
        }

        return anime
    }

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(latestUpdatesSelector())

        val animeList = mutableListOf<SAnime>()
        val seenUrls = mutableSetOf<String>()

        for (element in elements) {
            val anime = latestUpdatesFromElement(element)
            if (anime.url.isNotBlank() && seenUrls.add(anime.url)) {
                animeList.add(anime)
            }
        }

        val hasNextPage = document.selectFirst(latestUpdatesNextPageSelector()) != null
        return AnimesPage(animeList, hasNextPage)
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            baseUrl.toHttpUrl().newBuilder().apply {
                addQueryParameter("s", query)
                addQueryParameter("post_type", "anime")
                if (page > 1) {
                    addQueryParameter("paged", page.toString())
                }
            }.build().toString()
        } else {
            val urlBuilder = "$baseUrl/lista-de-animes/".toHttpUrl().newBuilder()
            if (page > 1) {
                urlBuilder.addQueryParameter("pagina", page.toString())
            }

            for (filter in filters) {
                when (filter) {
                    is TypeFilter -> {
                        if (filter.selectedValue().isNotEmpty()) {
                            urlBuilder.addQueryParameter("tipo", filter.selectedValue())
                        }
                    }
                    is StatusFilter -> {
                        if (filter.selectedValue().isNotEmpty()) {
                            urlBuilder.addQueryParameter("status", filter.selectedValue())
                        }
                    }
                    is AudioFilter -> {
                        if (filter.selectedValue().isNotEmpty()) {
                            urlBuilder.addQueryParameter("audio", filter.selectedValue())
                        }
                    }
                    is OrderFilter -> {
                        if (filter.selectedValue().isNotEmpty()) {
                            urlBuilder.addQueryParameter("ordem", filter.selectedValue())
                        }
                    }
                    else -> {}
                }
            }
            urlBuilder.build().toString()
        }

        return GET(url, headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()

        val title = document.selectFirst("div.anime-hero-info h1, h1")?.text()?.trim().orEmpty()
        anime.title = title

        val posterImg = document.selectFirst("div.anime-hero-poster img, div.anime-poster img")
        anime.thumbnail_url = posterImg?.attr("abs:src")?.takeIf { it.isNotBlank() }
            ?: posterImg?.attr("src")?.takeIf { it.isNotBlank() }

        val synopsis = document.selectFirst("div.anime-synopsis div.synopsis-text, div.synopsis-text")?.text()?.trim()
        val infoItems = document.select("div.anime-info-box div.info-item")
        val infoList = mutableListOf<String>()

        var studio: String? = null
        var status: String? = null

        for (item in infoItems) {
            val label = item.selectFirst("label")?.text()?.trim()?.lowercase().orEmpty()
            val value = item.selectFirst("span")?.text()?.trim().orEmpty()
            if (value.isBlank()) continue

            when {
                "estudio" in label || "estúdio" in label -> {
                    studio = value
                }
                "status" in label -> {
                    status = value
                }
                "nota" in label -> {
                    infoList.add("★ Nota MAL: $value")
                }
                "tipo" in label -> {
                    infoList.add("Tipo: $value")
                }
                "ano" in label -> {
                    infoList.add("Ano: $value")
                }
                "eps" in label -> {
                    infoList.add("Episódios: $value")
                }
            }
        }

        anime.author = studio
        anime.status = when {
            status?.contains("Em exibição", ignoreCase = true) == true -> SAnime.ONGOING
            status?.contains("Finalizado", ignoreCase = true) == true -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }

        val genres = document.select("a[href*='/genero/']").map { it.text().trim() }.distinct()
        if (genres.isNotEmpty()) {
            anime.genre = genres.joinToString(", ")
        }

        val descriptionParts = mutableListOf<String>()
        if (!synopsis.isNullOrBlank()) {
            descriptionParts.add(synopsis)
        }
        if (infoList.isNotEmpty()) {
            descriptionParts.add(infoList.joinToString(" • "))
        }

        anime.description = descriptionParts.joinToString("\n\n")

        return anime
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String = "div.season-block"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val seasonBlocks = document.select(episodeListSelector())

        if (seasonBlocks.isEmpty()) {
            // Fallback for single grid or direct episode cards
            val cards = document.select("div.episodes-grid a.episode-card, a.episode-card")
            return cards.mapIndexed { index, card ->
                val ep = SEpisode.create()
                ep.setUrlWithoutDomain(card.attr("href"))
                val epNumText = card.selectFirst("span.ep-num")?.text()?.trim() ?: "Ep. ${index + 1}"
                ep.name = epNumText
                ep.episode_number = epNumText.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: (index + 1).toFloat()
                ep
            }.reversed()
        }

        val episodeList = mutableListOf<SEpisode>()

        for (block in seasonBlocks) {
            val badge = block.selectFirst("span.shc-badge")?.text()?.trim()?.uppercase().orEmpty()
            val seasonTitle = block.selectFirst("span.shc-title")?.text()?.trim().orEmpty()
            val prefix = when {
                "DUB" in badge -> "[DUB]"
                "LEG" in badge -> "[LEG]"
                else -> ""
            }

            val cards = block.select("div.episodes-grid a.episode-card, a.episode-card")
            for ((index, card) in cards.withIndex()) {
                val ep = SEpisode.create()
                ep.setUrlWithoutDomain(card.attr("href"))
                val epNumText = card.selectFirst("span.ep-num")?.text()?.trim() ?: "Ep. ${index + 1}"

                ep.name = if (prefix.isNotEmpty()) {
                    "$prefix $epNumText"
                } else {
                    epNumText
                }

                ep.episode_number = epNumText.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: (index + 1).toFloat()
                episodeList.add(ep)
            }
        }

        return episodeList.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val doc = client.newCall(GET(baseUrl + episode.url, headers)).awaitSuccess().asJsoup()
        val iframeUrl = doc.selectFirst("div.anime-player-wrap iframe, iframe[src*='anidrive']")?.attr("src")
            ?: doc.selectFirst("iframe")?.attr("src")
            ?: return emptyList()

        val fullIframeUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl

        return when {
            "anidrive.click" in fullIframeUrl -> aniDriveExtractor.videosFromUrl(fullIframeUrl)
            else -> emptyList()
        }
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareByDescending { it.videoTitle.contains(quality) },
        )
    }

    // ============================== Settings ==============================
    @Suppress("UNCHECKED_CAST")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Qualidade padrão"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080p", "720p", "480p", "360p")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filtros da Lista de Animes (ignorados se buscar por texto)"),
        TypeFilter(),
        StatusFilter(),
        AudioFilter(),
        OrderFilter(),
    )

    private class TypeFilter :
        UriSelectFilter(
            "Tipo",
            arrayOf(
                Pair("Todos", ""),
                Pair("TV", "TV"),
                Pair("Filme", "Filme"),
                Pair("OVA", "OVA"),
                Pair("ONA", "ONA"),
                Pair("Especial", "Especial"),
            ),
        )

    private class StatusFilter :
        UriSelectFilter(
            "Status",
            arrayOf(
                Pair("Todos", ""),
                Pair("Em exibição", "Em exibição"),
                Pair("Finalizado", "Finalizado"),
                Pair("Anunciado", "Anunciado"),
            ),
        )

    private class AudioFilter :
        UriSelectFilter(
            "Áudio",
            arrayOf(
                Pair("Todos", ""),
                Pair("Legendado", "Legendado"),
                Pair("Dublado", "Dublado"),
            ),
        )

    private class OrderFilter :
        UriSelectFilter(
            "Ordem",
            arrayOf(
                Pair("Mais Recente", "date"),
                Pair("Melhor Nota", "score"),
                Pair("A–Z", "title"),
                Pair("Ano", "year"),
            ),
        )

    private open class UriSelectFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun selectedValue(): String = vals[state].second
    }

    private fun cleanAnimeTitle(title: String): String = title
        .replace(Regex("[-_ ]+Episodio[-_ ]+\\d+.*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[-_ ]+Ep[-_ ]+\\d+.*$", RegexOption.IGNORE_CASE), "")
        .trim()

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
    }
}
