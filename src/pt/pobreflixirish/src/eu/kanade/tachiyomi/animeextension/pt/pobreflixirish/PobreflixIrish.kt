package eu.kanade.tachiyomi.animeextension.pt.pobreflixirish

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class PobreflixIrish :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "Pobreflix Irish"

    override val baseUrl = "https://www.pobreflix.irish"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val playerExtractor by lazy { PobreflixPlayerExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client) }

    private val catalogPosterCache = ConcurrentHashMap<String, String>()

    private val dateFormatter by lazy {
        SimpleDateFormat("MMM. d, yyyy", Locale.US)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/trending/"
        } else {
            "$baseUrl/series/page/${page - 1}/"
        }
        return GET(url, headers)
    }

    override fun popularAnimeSelector() = "#archive-content article.item, .items article.item, article.item.movies, article.item.tvshows, article.item"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val a = element.selectFirst("a[href*='/series/'], a[href*='/filmes/']")
            ?: element.selectFirst("a")!!
        val href = a.attr("abs:href")
        setUrlWithoutDomain(href)

        val img = element.selectFirst("img")
        title = element.selectFirst("h3, .title, .data a")?.text()?.trim()
            ?: img?.attr("alt")?.trim()
            ?: ""

        val posterUrl = img?.let(::getImageUrl)
        thumbnail_url = posterUrl

        if (posterUrl != null && href.isNotBlank()) {
            val path = href.substringAfter(baseUrl).removeSuffix("/")
            catalogPosterCache[path] = posterUrl
            catalogPosterCache[href] = posterUrl
        }
    }

    override fun popularAnimeNextPageSelector() = "div.pagination i#nextpagination, div.pagination a.arrow_pag"

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select(popularAnimeSelector())
            .map(::popularAnimeFromElement)
            .distinctBy { it.url }

        val isTrendingPage = response.request.url.encodedPath.contains("trending")
        val hasNextPage = if (isTrendingPage) {
            true
        } else {
            doc.selectFirst(popularAnimeNextPageSelector()) != null
        }

        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val startPage = (page - 1) * PAGES_PER_FETCH + 1
        val url = if (startPage == 1) {
            "$baseUrl/episodios/"
        } else {
            "$baseUrl/episodios/page/$startPage/"
        }
        return GET(url, headers)
    }

    override fun latestUpdatesSelector() = "article.item.se.episodes"

    override fun latestUpdatesFromElement(element: Element): SAnime = SAnime.create().apply {
        val a = element.selectFirst("a[href*='/episodios/']") ?: element.selectFirst("a")!!
        val epHref = a.attr("abs:href")
        setUrlWithoutDomain(epHref)
        title = element.selectFirst("span.serie")?.text()?.trim() ?: ""
        // Never set episode frame directly!
        thumbnail_url = catalogPosterCache[title]
    }

    override fun latestUpdatesNextPageSelector() = "div.pagination i#nextpagination, div.pagination a.arrow_pag"

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val reqUrl = response.request.url.toString()
        val currentSitePage = Regex("""/page/(\d+)/""").find(reqUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val document = response.asJsoup()
        val allDocs = mutableListOf(document)

        // Concurrently fetch additional pages in this batch
        runBlocking(Dispatchers.IO) {
            val tasks = (currentSitePage + 1 until currentSitePage + PAGES_PER_FETCH).map { p ->
                async {
                    runCatching {
                        val pUrl = "$baseUrl/episodios/page/$p/"
                        client.newCall(GET(pUrl, headers)).execute().asJsoup()
                    }.getOrNull()
                }
            }
            allDocs.addAll(tasks.awaitAll().filterNotNull())
        }

        val allElements = allDocs.flatMap { it.select(latestUpdatesSelector()) }

        val distinctEpisodes = allElements.mapNotNull { element ->
            val epHref = element.selectFirst("a[href*='/episodios/']")?.attr("abs:href")
                ?: return@mapNotNull null
            val serieName = element.selectFirst("span.serie")?.text()?.trim().orEmpty()
            Triple(epHref, serieName, element)
        }.distinctBy { (epHref, serieName, _) ->
            serieName.ifBlank {
                epHref.substringBeforeLast("-episodio-").substringBeforeLast("-temporada-")
            }.lowercase().replace(Regex("[^a-z0-9]"), "")
        }

        val animes = runBlocking(Dispatchers.IO) {
            distinctEpisodes.map { (epHref, serieName, element) ->
                async {
                    resolveCanonicalSeries(epHref, serieName, element)
                }
            }.awaitAll().filterNotNull()
        }

        val lastDoc = allDocs.lastOrNull() ?: document
        val hasNextPage = lastDoc.selectFirst(latestUpdatesNextPageSelector()) != null

        return AnimesPage(animes, hasNextPage)
    }

    private fun resolveCanonicalSeries(epHref: String, fallbackTitle: String, cardElement: Element): SAnime? {
        return runCatching {
            // Priority 1: explicit /series/ link in card
            val cardSeriesHref = cardElement.selectFirst("a[href*='/series/']")?.attr("abs:href")
                ?.takeIf { it != "$baseUrl/series/" && it != "$baseUrl/series" }

            // Priority 2: metadata in card
            val dataSeriesHref = cardElement.attr("data-series-url").takeIf { it.isNotBlank() }

            var canonUrl = cardSeriesHref ?: dataSeriesHref
            var canonTitle: String? = null

            // Priority 3 & 4: fetch episode page to find series link
            if (canonUrl.isNullOrBlank()) {
                val epResp = client.newCall(GET(epHref, headers)).execute()
                val finalEpUrl = epResp.request.url.toString()
                if (!finalEpUrl.contains("/episodios/")) {
                    return null
                }
                val epDoc = epResp.asJsoup()

                val seriesLink = epDoc.selectFirst("div.pag_episodes div.item a[href*='/series/']")
                    ?: epDoc.selectFirst("a[href*='/series/'][title]")
                    ?: epDoc.selectFirst("div.breadcrumb a[href*='/series/']")
                    ?: epDoc.select("a[href*='/series/']").firstOrNull {
                        val h = it.attr("abs:href")
                        h != "$baseUrl/series/" && h != "$baseUrl/series"
                    }
                    ?: return null

                canonUrl = seriesLink.attr("abs:href")
                val rawTitle = seriesLink.attr("title").ifBlank { seriesLink.text().ifBlank { fallbackTitle } }
                canonTitle = cleanTitle(rawTitle)
            }

            if (canonUrl.isNullOrBlank()) return null
            if (canonTitle.isNullOrBlank()) {
                canonTitle = cleanTitle(fallbackTitle)
            }

            // Resolve canonical vertical poster (never episode frame)
            val cachedPoster = catalogPosterCache[canonUrl]
                ?: catalogPosterCache[canonTitle]
                ?: catalogPosterCache[fallbackTitle]

            val poster = if (!cachedPoster.isNullOrBlank()) {
                cachedPoster
            } else {
                val sDoc = client.newCall(GET(canonUrl, headers)).execute().asJsoup()
                val img = sDoc.selectFirst("div.poster > img, div.poster img")
                val resolvedPoster = img?.let(::getImageUrl)
                if (resolvedPoster != null) {
                    catalogPosterCache[canonTitle] = resolvedPoster
                    catalogPosterCache[canonUrl] = resolvedPoster
                    catalogPosterCache[fallbackTitle] = resolvedPoster
                }
                resolvedPoster
            }

            SAnime.create().apply {
                setUrlWithoutDomain(canonUrl)
                title = canonTitle
                thumbnail_url = poster
            }
        }.getOrNull()
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = if (page == 1) {
                "$baseUrl/?s=$encodedQuery"
            } else {
                "$baseUrl/page/$page/?s=$encodedQuery"
            }
            return GET(url, headers)
        }

        // Filter based browsing
        var typePart: String? = null
        var genrePart: String? = null
        var yearPart: String? = null

        for (filter in filters) {
            when (filter) {
                is PobreflixIrishFilters.TypeFilter -> {
                    val uri = filter.toUriPart()
                    if (uri.isNotBlank()) typePart = uri
                }
                is PobreflixIrishFilters.GenreFilter -> {
                    val uri = filter.toUriPart()
                    if (uri.isNotBlank()) genrePart = uri
                }
                is PobreflixIrishFilters.YearFilter -> {
                    val uri = filter.toUriPart()
                    if (uri.isNotBlank()) yearPart = uri
                }
                else -> {}
            }
        }

        val url = when {
            genrePart != null -> {
                if (page == 1) "$baseUrl/categorias/$genrePart/" else "$baseUrl/categorias/$genrePart/page/$page/"
            }
            yearPart != null -> {
                if (page == 1) "$baseUrl/lancamentos/$yearPart/" else "$baseUrl/lancamentos/$yearPart/page/$page/"
            }
            typePart != null -> {
                if (page == 1) "$baseUrl/$typePart/" else "$baseUrl/$typePart/page/$page/"
            }
            else -> {
                return popularAnimeRequest(page)
            }
        }

        return GET(url, headers)
    }

    override fun searchAnimeSelector() = "div.result-item, #archive-content article.item, .items article.item, article.item.movies, article.item.tvshows, article.item"

    override fun searchAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val a = element.selectFirst("div.details div.title a, a[href*='/series/'], a[href*='/filmes/'], a")!!
        val href = a.attr("abs:href")
        setUrlWithoutDomain(href)

        val img = element.selectFirst("img")
        title = cleanTitle(a.text().ifBlank { img?.attr("alt").orEmpty() })
        thumbnail_url = img?.let(::getImageUrl)
    }

    override fun searchAnimeNextPageSelector() = "div.pagination i#nextpagination, div.pagination a.arrow_pag"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        val rawTitle = document.selectFirst("div.data > h1, h1")?.text()?.trim().orEmpty()
        title = cleanTitle(rawTitle)

        val img = document.selectFirst("div.poster > img, div.poster img")
        thumbnail_url = img?.let(::getImageUrl)

        genre = document.select("div.sgeneros > a").eachText().joinToString()

        description = document.selectFirst("div.wp-content p")?.text()?.trim()
            ?: document.selectFirst("div.wp-content")?.text()?.trim()

        status = when {
            document.text().contains("Retornando", ignoreCase = true) -> SAnime.ONGOING
            document.text().contains("Finalizada", ignoreCase = true) -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "ul.episodios > li"

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException("Not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val seasonList = document.select("div#seasons > div.se-c")

        if (seasonList.isEmpty()) {
            // Movie: synthetic single episode
            val url = response.request.url.encodedPath
            return listOf(
                SEpisode.create().apply {
                    name = "Filme"
                    episode_number = 1F
                    setUrlWithoutDomain(url)
                },
            )
        }

        // Series with seasons
        val episodes = mutableListOf<SEpisode>()
        for (season in seasonList) {
            val seasonNumStr = season.selectFirst("span.se-t")?.text()?.trim().orEmpty()
            val seasonNum = seasonNumStr.filter { it.isDigit() }.toIntOrNull() ?: 1

            val epItems = season.select("ul.episodios > li, div.episodios-grid > div.episode-card, li")
            for (ep in epItems) {
                val a = ep.selectFirst("a[href*='/episodios/']") ?: ep.selectFirst("a") ?: continue
                val epUrl = a.attr("abs:href")
                if (!epUrl.contains("/episodios/")) continue

                val rawName = a.text().trim()
                val numerando = ep.selectFirst("div.numerando")?.text()?.trim()
                val epNum = numerando?.substringAfter("-")?.trim()?.toIntOrNull()
                    ?: Regex("""(?:episodio-|ep-|x)(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 1

                val epTitle = if (rawName.isNotBlank() && !rawName.startsWith("Episódio")) {
                    "T$seasonNum E$epNum - $rawName"
                } else {
                    "T$seasonNum E$epNum"
                }

                val dateStr = ep.selectFirst("span.date")?.text()?.trim()
                val dateUpload = dateStr?.let { parseDate(it) } ?: 0L

                episodes.add(
                    SEpisode.create().apply {
                        name = epTitle
                        episode_number = epNum.toFloat()
                        date_upload = dateUpload
                        setUrlWithoutDomain(epUrl)
                    },
                )
            }
        }

        return episodes.reversed() // Most recent first
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val response = client.newCall(videoListRequest(episode)).execute()
        return videoListParse(response).sortVideos()
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        // 1. Check iframes in pframe or metaframe
        val iframes = document.select("div.pframe iframe[src], iframe.metaframe[src], iframe[src]")
            .map { it.attr("abs:src") }
            .filter { it.isNotBlank() && !it.contains("youtube.com") && !it.contains("youtu.be") }
            .distinct()

        var accumulatedFailure: VideoResult? = null

        for (iframeUrl in iframes) {
            val result = runCatching {
                playerExtractor.extractVideosWithResult(iframeUrl, headers, "Pobreflix")
            }.getOrElse {
                VideoResult.ServersTemporarilyUnavailable
            }

            when (result) {
                is VideoResult.Success -> videoList.addAll(result.videos)
                else -> accumulatedFailure = prioritizeFailure(accumulatedFailure, result)
            }
        }

        // 2. Check direct video elements
        document.select("video > source[src], video[src]").forEach { v ->
            val src = v.attr("abs:src")
            if (src.isNotBlank()) {
                if (src.contains(".m3u8") || src.contains("/hls/")) {
                    runCatching {
                        videoList.addAll(
                            playlistUtils.extractFromHls(
                                src,
                                videoNameGen = { q -> PobreflixPlayerExtractor.formatVideoName("Dublado", q, "Direto") },
                            ),
                        )
                    }
                } else if (src.contains(".mp4")) {
                    videoList.add(Video(src, PobreflixPlayerExtractor.formatVideoName("Dublado", "MP4", "Direto"), src, headers))
                }
            }
        }

        if (videoList.isNotEmpty()) {
            return videoList.sortVideos()
        }

        val failure = accumulatedFailure
        if (failure != null) {
            throw failure.toException()
        }
        throw VideoResult.NoCompatibleStream.toException()
    }

    private fun prioritizeFailure(current: VideoResult?, newResult: VideoResult): VideoResult {
        if (current == null) return newResult
        if (current is VideoResult.NotReleased) return current
        if (newResult is VideoResult.NotReleased) return newResult
        if (current is VideoResult.BrowserVerificationRequired) return current
        if (newResult is VideoResult.BrowserVerificationRequired) return newResult
        if (current is VideoResult.ServersTemporarilyUnavailable) return current
        if (newResult is VideoResult.ServersTemporarilyUnavailable) return newResult
        return current
    }

    override fun videoListSelector() = throw UnsupportedOperationException("Not used")

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException("Not used")

    override fun videoUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    override fun List<Video>.sortVideos(): List<Video> {
        val audio = runCatching { preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) }.getOrNull() ?: PREF_AUDIO_DEFAULT
        val quality = runCatching { preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) }.getOrNull() ?: PREF_QUALITY_DEFAULT
        return sortVideos(this, audio, quality)
    }

    // ============================ Preferences =============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_AUDIO_KEY
            title = "Áudio preferido"
            entries = arrayOf("Automático", "Dublado", "Legendado")
            entryValues = arrayOf("auto", "dub", "sub")
            setDefaultValue(PREF_AUDIO_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                preferences.edit().putString(key, selected).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Qualidade preferida"
            entries = arrayOf("Melhor qualidade", "1080p", "720p", "480p", "360p")
            entryValues = arrayOf("best", "1080p", "720p", "480p", "360p")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                preferences.edit().putString(key, selected).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = PobreflixIrishFilters.getFilterList()

    // ============================== Helpers ===============================
    private fun getImageUrl(element: Element): String {
        val url = when {
            element.hasAttr("data-src") -> element.attr("abs:data-src")
            element.hasAttr("data-lazy-src") -> element.attr("abs:data-lazy-src")
            element.hasAttr("srcset") -> element.attr("abs:srcset").substringBefore(" ")
            else -> element.attr("abs:src")
        }
        val fullUrl = if (url.startsWith("//")) "https:$url" else url
        return fullUrl.replace(REGEX_IMAGE_SIZE_SUFFIX, "")
    }

    private fun cleanTitle(title: String): String = title
        .removePrefix("Assistir ")
        .removeSuffix(" Online")
        .removeSuffix(" Dublado")
        .trim()

    private fun parseDate(dateStr: String): Long = runCatching {
        dateFormatter.parse(dateStr)?.time ?: 0L
    }.getOrDefault(0L)

    companion object {
        private const val PAGES_PER_FETCH = 5
        private const val PREF_AUDIO_KEY = "preferred_audio"
        private const val PREF_AUDIO_DEFAULT = "auto"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "best"

        fun sortVideos(videos: List<Video>, preferredAudio: String, preferredQuality: String): List<Video> = videos.sortedWith(
            compareByDescending<Video> { video ->
                when (preferredAudio) {
                    "dub", "Dublado" -> if (video.videoTitle.contains("Dublado", ignoreCase = true)) 1 else 0
                    "sub", "Legendado" -> if (video.videoTitle.contains("Legendado", ignoreCase = true)) 1 else 0
                    else -> 0
                }
            }.thenByDescending { video ->
                if (preferredQuality != "best" && preferredQuality != PREF_QUALITY_DEFAULT) {
                    if (video.videoTitle.contains(preferredQuality, ignoreCase = true)) 1 else 0
                } else {
                    0
                }
            }.thenByDescending { video ->
                getVideoResolution(video.videoTitle)
            },
        )

        fun getVideoResolution(title: String): Int = Regex("""(\d+)p""").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (title.contains("1080", ignoreCase = true)) {
                1080
            } else if (title.contains("720", ignoreCase = true)) {
                720
            } else if (title.contains("480", ignoreCase = true)) {
                480
            } else if (title.contains("360", ignoreCase = true)) {
                360
            } else {
                0
            }

        private val REGEX_IMAGE_SIZE_SUFFIX by lazy {
            Regex("""-\d+x\d+(?=\.[A-Za-z0-9]+$)""")
        }
    }
}
