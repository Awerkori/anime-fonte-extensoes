package eu.kanade.tachiyomi.animeextension.pt.animesdigital

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animeextension.pt.animesdigital.extractors.ProtectorExtractor
import eu.kanade.tachiyomi.animeextension.pt.animesdigital.extractors.ScriptExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

class AnimesDigital :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "Animes Digital"

    override val baseUrl = "https://animesdigital.org"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    private val preferences by getPreferencesLazy()

    private val animesDigitalFilters by lazy { AnimesDigitalFilters(baseUrl, client) }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        animesDigitalFilters.fetchFilters()
        return super.getPopularAnime(page)
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/home", headers = headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val animes = document.select(ANIME_LIST_SELECTOR).map(::animeFromElement)
        return AnimesPage(animes, false)
    }

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        animesDigitalFilters.fetchFilters()
        return super.getLatestUpdates(page)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/lancamentos/page/$page", headers = headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val animes = document.select(ANIME_LIST_SELECTOR).map(::animeFromElement)
        val hasNextPage = document.selectFirst("ul > li.next") != null
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Search ===============================
    override fun getFilterList(): AnimeFilterList = animesDigitalFilters.getFilterList()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        animesDigitalFilters.fetchFilters()
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val id = url.pathSegments.getOrNull(2)
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "${PREFIX_SEARCH}$id", filters)
        }

        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            return client.newCall(GET("$baseUrl/anime/a/$id", headers = headers))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        }

        return super.getSearchAnime(page, query, filters)
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    private val searchToken by lazy {
        client.newCall(GET("$baseUrl/animes-legendados-online", headers = headers)).execute().useAsJsoup()
            .selectFirst("div.menu_filter_box")!!
            .attr("data-secury")
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = animesDigitalFilters.getSearchParameters(filters)
        val body = FormBody.Builder().apply {
            add("type", "lista")
            add("limit", "30")
            add("token", searchToken)
            if (query.isNotEmpty()) {
                add("search", query)
            }
            add("pagina", "$page")
            val filterData = baseUrl.toHttpUrl().newBuilder().apply {
                addQueryParameter("type_url", params.type)
                addQueryParameter("filter_audio", params.audio)
                addQueryParameter("filter_letter", params.initialLetter)
                addQueryParameter("filter_order", params.orderBy)
            }.build().encodedQuery.orEmpty()

            val genres = params.genres.joinToString { "\"$it\"" }
            val delgenres = params.deleted_genres.joinToString { "\"$it\"" }

            add(
                "filters",
                """{"filter_data": "$filterData", "filter_genre_add": [$genres], "filter_genre_del": [$delgenres]}""",
            )
        }.build()

        return POST("$baseUrl/func/listanime", body = body, headers = headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = runCatching {
        val data = response.parseAs<SearchResponseDto>()
        val animes = data.results.map { Jsoup.parseBodyFragment(it, baseUrl) }
            .mapNotNull { it.selectFirst(SEARCH_SELECTOR) }
            .map(::animeFromElement)
        val hasNext = data.totalPage > data.page
        AnimesPage(animes, hasNext)
    }.getOrElse { AnimesPage(emptyList(), false) }

    @Serializable
    class SearchResponseDto(
        val results: List<String>,
        val page: Int,
        @SerialName("total_page")
        val totalPage: Int,
    )

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = getRealDoc(response.useAsJsoup())
        return SAnime.create().apply {
            setUrlWithoutDomain(document.location())
            thumbnail_url = document.selectFirst("div.poster > img")?.attr("data-lazy-src")
            status = when (document.selectFirst("div.clw > div.playon")?.text()) {
                "Em Lançamento" -> SAnime.ONGOING
                "Completo" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }

            with(document.selectFirst("div.crw > div.dados")!!) {
                artist = getInfo("Estúdio")
                author = getInfo("Autor") ?: getInfo("Diretor")

                title = selectFirst("h1")!!.text()
                genre = select("div.genre a").eachText().joinToString()

                description = selectFirst("div.sinopse")?.text()
            }
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = getRealDoc(response.useAsJsoup())
        val episodes = mutableListOf<SEpisode>()
        episodes += doc.select(EPISODE_SELECTOR).map(::episodeFromElement)
        val lastPage =
            doc.selectFirst("ul.content-pagination > li:nth-last-child(2) > a")?.text()
                ?.toIntOrNull()
        episodes += lastPage?.let { 2..it }
            ?.parallelCatchingFlatMapBlocking { i ->
                val request = GET(doc.location() + "/page/$i", headers)
                val res = client.newCall(request).awaitSuccess()
                val pageDoc = res.useAsJsoup()
                pageDoc.select(EPISODE_SELECTOR).map(::episodeFromElement)
            } ?: emptyList()
        return episodes
    }

    private fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.selectFirst("div.title_anime")!!.text()
        episode_number = name.substringAfterLast(" ").toFloatOrNull() ?: 1F
        date_upload = element.selectFirst("div.date")?.text()?.let { parseDate(it) } ?: 0L
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val response = client.newCall(
            GET("$baseUrl${episode.url}", headers = headers),
        ).awaitSuccess()
        val player = response.useAsJsoup().selectFirst("div#player")!!
        return player.select("div.tab-video").parallelCatchingFlatMapBlocking { div ->
            div.select(VIDEO_SELECTOR).parallelCatchingFlatMap { element ->
                videosFromElement(element)
            }
        }
    }

    private val protectorExtractor by lazy { ProtectorExtractor(client) }
    private val bloggerExtractor by lazy { BloggerExtractor(client) }

    private suspend fun videosFromElement(element: Element): List<Video> = when (element.tagName()) {
        "iframe" -> {
            val url = element.absUrl("data-lazy-src").ifEmpty { element.absUrl("src") }
            when {
                "blogger.com" in url -> bloggerExtractor.videosFromUrl(url, headers)
                else -> {
                    client.newCall(GET(url, headers)).awaitSuccess()
                        .useAsJsoup()
                        .select(VIDEO_SELECTOR)
                        .parallelCatchingFlatMap(::videosFromElement)
                }
            }
        }

        "script" -> ScriptExtractor.videosFromScript(element.data(), headers)

        "a" -> protectorExtractor.videosFromUrl(element.attr("href"))

        else -> emptyList()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy { it.videoTitle.contains(quality) },
        ).reversed()
    }

    private fun animeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        thumbnail_url = element.selectFirst("img")?.let {
            it.attr("data-lazy-src").ifEmpty { it.attr("src") }
        }
        title = element.selectFirst("span.title_anime")!!.text()
    }

    private fun getRealDoc(document: Document): Document = document.selectFirst("div.subitem > a:contains(menu)")?.let { link ->
        client.newCall(GET(link.absUrl("href"), headers))
            .execute()
            .useAsJsoup()
    } ?: document

    private fun Element.getInfo(key: String): String? = selectFirst("div.info:has(span:containsOwn($key))")?.run {
        ownText().takeUnless { it.isEmpty() || it == "?" }
    }

    private fun parseDate(date: String): Long {
        return runCatching {
            val normalized = date.lowercase()

            val match = RELATIVE_DATE_REGEX.find(normalized) ?: return 0L

            val amount = match.groupValues[1].toLongOrNull() ?: return 0L
            val unit = match.groupValues[2]

            val millis = when {
                unit.startsWith("dia") -> TimeUnit.DAYS.toMillis(amount)
                unit.startsWith("semana") -> TimeUnit.DAYS.toMillis(amount * 7)
                unit.startsWith("mes") || unit.startsWith("mês") -> TimeUnit.DAYS.toMillis(amount * 30)
                unit.startsWith("ano") -> TimeUnit.DAYS.toMillis(amount * 365)
                else -> 0L
            }

            System.currentTimeMillis() - millis
        }.getOrDefault(0L)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val ANIME_LIST_SELECTOR = "div.b_flex > div.itemE > a"
        private const val SEARCH_SELECTOR = "div.itemA > a"
        private const val EPISODE_SELECTOR = "div.item_ep > a"
        private val SCRIPT_SELECTORS = listOf("eval", "player.src", "this.src", "sources:")
            .joinToString { "script:containsData($it):not(:containsData(/bg.mp4))" }
        private const val VIDEO_SELECTOR_BASE = "iframe"
        private val VIDEO_SELECTOR = "$VIDEO_SELECTOR_BASE, $SCRIPT_SELECTORS"

        private val RELATIVE_DATE_REGEX = Regex("""(\d+)\s+(\S+)""")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("360p", "480p", "720p")
    }
}
