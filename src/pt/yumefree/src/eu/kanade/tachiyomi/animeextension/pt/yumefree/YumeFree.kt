package eu.kanade.tachiyomi.animeextension.pt.yumefree

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.bloggerextractor.BloggerExtractor
import aniyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.animeextension.pt.yumefree.extractors.VidaraExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class YumeFree :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "YUMEFREE"

    override val baseUrl = "https://yumefree.online"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val preferences by getPreferencesLazy()

    private val dateFormatter by lazy {
        SimpleDateFormat("MMM. d, yyyy", Locale("en", "US"))
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/tvshows/page/$page/", headers)

    override fun popularAnimeSelector(): String = "div#archive-content article.item.tvshows"

    override fun popularAnimeNextPageSelector(): String = "div.pagination > span.current + a, div.resppages > a > span.fa-chevron-right"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("div.data h3 a, div.poster a")!!
        val img = element.selectFirst("div.poster img")!!
        setUrlWithoutDomain(link.attr("href"))
        title = link.text().ifEmpty { img.attr("alt") }.trim()
        thumbnail_url = img.getImageUrl()
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/episodes/page/$page/", headers)

    override fun latestUpdatesSelector(): String = "div#archive-content article.item.se.episodes"

    override fun latestUpdatesNextPageSelector(): String = "div.pagination > span.current + a, div.resppages > a > span.fa-chevron-right"

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(latestUpdatesSelector())
        val animeList = mutableListOf<SAnime>()
        val seenSlugs = mutableSetOf<String>()

        for (element in elements) {
            val link = element.selectFirst("div.data h3 a, div.poster a") ?: continue
            val img = element.selectFirst("div.poster img") ?: continue

            val epUrl = link.attr("href")
            val slug = epUrl.trimEnd('/').substringAfterLast('/')
            val animeSlug = REGEX_EPISODE_SLUG.matchEntire(slug)?.groupValues?.get(1) ?: slug

            if (seenSlugs.add(animeSlug)) {
                val rawTitle = img.attr("alt").ifEmpty { link.text() }
                val cleanTitle = cleanAnimeTitle(rawTitle).ifEmpty { link.text() }.trim()

                animeList.add(
                    SAnime.create().apply {
                        title = cleanTitle
                        thumbnail_url = img.getImageUrl()
                        setUrlWithoutDomain("/tvshows/$animeSlug/")
                    },
                )
            }
        }

        val hasNextPage = document.selectFirst(latestUpdatesNextPageSelector()) != null
        return AnimesPage(animeList, hasNextPage)
    }

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    private fun cleanAnimeTitle(rawTitle: String): String = rawTitle
        .replace(REGEX_EPISODE_TITLE_SUFFIX, "")
        .trim()

    private fun episodeUrlToAnimeUrl(epUrl: String): String {
        val slug = epUrl.trimEnd('/').substringAfterLast('/')
        val animeSlug = REGEX_EPISODE_SLUG.matchEntire(slug)?.groupValues?.get(1) ?: slug
        return "/tvshows/$animeSlug/"
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters

        val genreFilter = filterList.firstOrNull { it is GenreFilter && it.state != 0 } as? GenreFilter

        return when {
            query.isNotBlank() -> {
                val pagePath = if (page > 1) "page/$page/" else ""
                val url = baseUrl.toHttpUrl().newBuilder()
                    .addPathSegments(pagePath)
                    .addQueryParameter("s", query)
                    .build()
                GET(url.toString(), headers)
            }
            genreFilter != null -> {
                val genrePath = genreFilter.selectedValue()
                val pagePath = if (page > 1) "page/$page/" else ""
                GET("$baseUrl/$genrePath/$pagePath", headers)
            }
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeSelector(): String = "div.search-page article, div.result-item article, div#archive-content article"

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("div.title a, div.data h3 a, div.details a, a[href]")!!
        val img = element.selectFirst("div.image img, div.thumbnail img, div.poster img, img")!!
        val href = link.attr("href")

        url = if (href.contains("/episodes/")) episodeUrlToAnimeUrl(href) else href.substringAfter(baseUrl)
        title = link.text().ifEmpty { img.attr("alt") }.trim()
        thumbnail_url = img.getImageUrl()
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealAnimeDoc(document)
        val sheader = doc.selectFirst("div.sheader") ?: return fallbackDetails(doc)

        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            val img = sheader.selectFirst("div.poster > img")
            thumbnail_url = img?.getImageUrl()
            title = sheader.selectFirst("div.data > h1")?.text()
                ?: img?.attr("alt")?.ifEmpty { "" }
                ?: ""

            genre = sheader.select("div.data div.sgeneros > a")
                .eachText()
                .joinToString(", ")

            doc.selectFirst("div#info, div.wp-content")?.let { info ->
                description = info.select("p").eachText().joinToString("\n\n")
            }
            status = SAnime.UNKNOWN
        }
    }

    private fun fallbackDetails(doc: Document): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(doc.location())
        title = doc.selectFirst("h1")?.text() ?: ""
        thumbnail_url = doc.selectFirst("div.poster img, img")?.getImageUrl()
        description = doc.selectFirst("div.wp-content p, div#info p")?.text()
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = getRealAnimeDoc(response.asJsoup())
        val seasons = doc.select("div#seasons div.se-c")

        if (seasons.isEmpty()) {
            val episodes = doc.select(episodeListSelector()).map { episodeFromElement(it, "1") }
            return if (episodes.isNotEmpty()) {
                episodes.reversed()
            } else {
                listOf(
                    SEpisode.create().apply {
                        name = "Filme"
                        episode_number = 1F
                        setUrlWithoutDomain(doc.location())
                    },
                )
            }
        }

        val allEpisodes = mutableListOf<SEpisode>()
        for (season in seasons) {
            val seasonNum = season.selectFirst("span.se-t")?.text()?.trim() ?: "1"
            val seasonEps = season.select(episodeListSelector()).map {
                episodeFromElement(it, seasonNum)
            }
            allEpisodes.addAll(seasonEps)
        }

        return allEpisodes.reversed()
    }

    override fun episodeListSelector(): String = "div#seasons ul.episodios > li, ul.episodios > li"

    override fun episodeFromElement(element: Element): SEpisode = episodeFromElement(element, "1")

    private fun episodeFromElement(element: Element, seasonName: String): SEpisode = SEpisode.create().apply {
        val epNumStr = element.selectFirst("div.numerando")?.text()?.trim() ?: "0"
        val epNum = REGEX_EP_NUM.find(epNumStr)?.groupValues?.last()?.toFloatOrNull() ?: 0F
        val link = element.selectFirst("div.episodiotitle a[href*='/episodes/'], a[href*='/episodes/']")!!
        val epTitle = link.text().trim()
        val dateText = element.selectFirst("span.date")?.text()?.trim()

        val cleanEpNum = epNumStr.substringAfter("-").trim()
        episode_number = epNum
        name = "Temp. $seasonName - Ep. $cleanEpNum - $epTitle"
        date_upload = parseDate(dateText)
        setUrlWithoutDomain(link.attr("href"))
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return runCatching { dateFormatter.parse(dateStr)?.time }.getOrNull() ?: 0L
    }

    // ============================ Video Links =============================

    private val vidaraExtractor by lazy { VidaraExtractor(client, headers) }
    private val vidmolyExtractor by lazy { VidMolyExtractor(client, headers) }
    private val bloggerExtractor by lazy { BloggerExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        val players = document.select("ul#playeroptionsul li.dooplay_player_option")
        val episodeUrl = response.request.url.toString()

        return players.parallelCatchingFlatMapBlocking { player ->
            getPlayerVideos(player, episodeUrl)
        }.sortVideos()
    }

    private suspend fun getPlayerVideos(player: Element, episodeUrl: String): List<Video> {
        val playerTitle = player.selectFirst("span.title")?.text()?.trim() ?: "Player"
        if (playerTitle.contains("Telegram", ignoreCase = true)) return emptyList()

        val embedUrl = getPlayerEmbedUrl(player, episodeUrl).takeIf(String::isNotBlank)
            ?: return emptyList()

        val videos = mutableListOf<Video>()
        when {
            embedUrl.contains("vidara.to") || embedUrl.contains("morningmarkets.ink") -> {
                videos.addAll(vidaraExtractor.videosFromUrl(embedUrl, playerTitle))
            }
            embedUrl.contains("vidmoly") -> {
                videos.addAll(vidmolyExtractor.videosFromUrl(embedUrl))
            }
            embedUrl.contains("blogger.com") -> {
                videos.addAll(bloggerExtractor.videosFromUrl(embedUrl, headers))
            }
            else -> {
                videos.add(Video(embedUrl, playerTitle, embedUrl, headers))
            }
        }
        return videos
    }

    private suspend fun getPlayerEmbedUrl(player: Element, episodeUrl: String): String {
        val post = player.attr("data-post")
        val nume = player.attr("data-nume")
        val type = player.attr("data-type")

        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", post)
            .add("nume", nume)
            .add("type", type)
            .build()

        val ajaxHeaders = headers.newBuilder()
            .set("Referer", episodeUrl)
            .set("Origin", baseUrl)
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

        return runCatching {
            val response = client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", ajaxHeaders, body)).awaitSuccess()
            response.parseAs<DooPlayPlayerDto>().embedUrl
        }.getOrDefault("")
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Qualidade preferida"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080p", "720p", "480p", "360p")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filtros são ignorados na busca por texto"),
        GenreFilter(),
    )

    private class GenreFilter :
        AnimeFilter.Select<String>(
            "Gênero",
            GENRES.map { it.first }.toTypedArray(),
        ) {
        fun selectedValue(): String = GENRES[state].second
    }

    // ============================= Utilities ==============================

    private fun getRealAnimeDoc(document: Document): Document {
        val menu = document.selectFirst("div.pag_episodes div.item a[href*='/tvshows/']")
        if (menu != null) {
            val originalUrl = menu.attr("abs:href")
            if (originalUrl.startsWith("http://") || originalUrl.startsWith("https://")) {
                val req = client.newCall(GET(originalUrl, headers)).execute()
                return req.asJsoup()
            }
        }
        return document
    }

    private fun Element.getImageUrl(): String {
        val url = when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
        return url.replace(REGEX_IMAGE_SIZE_SUFFIX, "")
    }

    private fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending {
                    REGEX_QUALITY.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                },
        )
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"

        private val REGEX_EPISODE_SLUG by lazy {
            Regex("""^(.+?)-(?:episodio[_-]\d+.*?|\d+x\d+.*?)$""")
        }
        private val REGEX_EPISODE_TITLE_SUFFIX by lazy {
            Regex("""[-_ ]+Episodio[-_ ]+\d+.*$""", RegexOption.IGNORE_CASE)
        }
        private val REGEX_EP_NUM by lazy {
            Regex("""(\d+)$""")
        }
        private val REGEX_QUALITY by lazy {
            Regex("""(\d+)p""")
        }
        private val REGEX_IMAGE_SIZE_SUFFIX by lazy {
            Regex("""-\d+x\d+(?=\.[A-Za-z0-9]+$)""")
        }

        private val GENRES = arrayOf(
            Pair("Todos", ""),
            Pair("Animes", "genre/animes"),
            Pair("Animes Dublados", "genre/animes-dublado"),
            Pair("Animes Legendados", "genre/animes-legendado"),
            Pair("Donghuas", "genre/donghua"),
            Pair("Donghuas 2D", "genre/donghua-2d"),
            Pair("Donghuas 3D", "genre/donghua-3d"),
            Pair("Donghuas Dublado", "genre/donghua-dublado"),
            Pair("Donghuas Legendado", "genre/donghua-legendado"),
            Pair("Desenhos", "genre/desenho"),
            Pair("Desenhos Dublados", "genre/desenhos-dublado"),
            Pair("Desenhos Legendados", "genre/desenhos-legendado"),
            Pair("Filmes", "genre/filmes"),
            Pair("Filmes Dublados", "genre/filmes-dublados"),
            Pair("Filmes Legendados", "genre/filmes-legendados"),
            Pair("Nostalgia", "genre/nostalgia"),
            Pair("Nostalgia Dublado", "genre/nostalgia-dublado"),
            Pair("Nostalgia Legendado", "genre/nostalgia-legendado"),
        )
    }

    @Serializable
    data class DooPlayPlayerDto(
        @SerialName("embed_url") val embedUrl: String = "",
    )
}
