package eu.kanade.tachiyomi.animeextension.pt.animeq

import android.util.Log
import aniyomi.lib.bloggerextractor.BloggerExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.pt.animeq.extractors.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

class AnimeQ :
    DooPlay(
        "pt-BR",
        "AnimeQ",
        "https://animeq.cloud",
    ) {
    // =============================== Cache ================================
    private val catalogPosterCache = ConcurrentHashMap<String, String>()

    internal fun cacheCatalogDoc(doc: Document) {
        doc.select("article.item.tvshows, article.item.movies, article.item").forEach { element ->
            val href = element.selectFirst("a")?.attr("href") ?: element.attr("href")
            val posterUrl = element.selectFirst("img")?.getImageUrl()
            if (href.isNotBlank() && !posterUrl.isNullOrBlank()) {
                val path = href.substringAfter(baseUrl).removeSuffix("/")
                val slug = path.substringAfterLast("/")
                catalogPosterCache[path] = posterUrl
                catalogPosterCache[slug] = posterUrl
                catalogPosterCache[href] = posterUrl
            }
        }
    }

    private fun ensureCatalogPosterCache() {
        if (catalogPosterCache.isNotEmpty()) return
        runCatching {
            val viewsDoc = client.newCall(popularAnimeRequest(1)).execute().asJsoup()
            cacheCatalogDoc(viewsDoc)
            val recentDoc = client.newCall(GET("$baseUrl/anime/", headers)).execute().asJsoup()
            cacheCatalogDoc(recentDoc)
        }
    }

    private fun fetchCanonicalAnimeInfo(canonUrl: String): Pair<String, String>? {
        return runCatching {
            val resp = client.newCall(GET(canonUrl, headers)).execute()
            val (realUrl, doc) = if (resp.code == 404 && canonUrl.contains("-dublado")) {
                val fallbackUrl = canonUrl.replace("-dublado", "")
                val fallbackResp = client.newCall(GET(fallbackUrl, headers)).execute()
                fallbackUrl to fallbackResp.asJsoup()
            } else {
                canonUrl to resp.asJsoup()
            }

            val sheader = doc.selectFirst("div.sheader")
            val img = sheader?.selectFirst("div.poster > img, div.poster img")
                ?: doc.selectFirst("div.poster > img, div.poster img")
            val posterUrl = img?.getImageUrl() ?: return null
            realUrl to posterUrl
        }.getOrNull()
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "article.item.tvshows, article.item.movies, article.item"

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/anime/?catalog_order=views"
        } else {
            "$baseUrl/anime/page/$page/?catalog_order=views"
        }
        return GET(url, headers)
    }

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val href = element.selectFirst("a")?.attr("href") ?: element.attr("href")
        setUrlWithoutDomain(href)
        val img = element.selectFirst("img")
        title = element.selectFirst(".data h3, .data a, h3, .title")?.text()
            ?: img?.attr("alt")
            ?: ""
        thumbnail_url = img?.getImageUrl()
    }

    override fun popularAnimeNextPageSelector() = "div.pagination i#nextpagination"

    override fun popularAnimeParse(response: Response): AnimesPage {
        fetchGenresList()
        val doc = response.asJsoup()
        cacheCatalogDoc(doc)
        val animes = doc.select(popularAnimeSelector()).map(::popularAnimeFromElement)
        val hasNextPage = doc.selectFirst(popularAnimeNextPageSelector()) != null
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesSelector() = "article.item.se.episodes"

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/episodio"
        } else {
            "$baseUrl/episodio/page/$page"
        }
        return GET(url, headers)
    }

    override fun latestUpdatesFromElement(element: Element): SAnime = SAnime.create().apply {
        val epHref = element.selectFirst("a[href*='/episodio/']")?.attr("href")
            ?: element.selectFirst("a")?.attr("href")
            ?: ""
        val animeUrl = if (epHref.contains("-episodio-")) {
            epHref.substringBeforeLast("-episodio-").replace("/episodio/", "/anime/")
        } else {
            epHref
        }
        val path = animeUrl.substringAfter(baseUrl).removeSuffix("/")
        val slug = path.substringAfterLast("/")

        setUrlWithoutDomain(animeUrl)
        title = element.selectFirst("span.serie")?.text()
            ?: element.selectFirst("img")?.attr("alt")?.substringBefore(" Episódio")
            ?: ""
        // Only canonical poster from cache, NEVER the episode frame
        thumbnail_url = catalogPosterCache[path]
            ?: catalogPosterCache[slug]
            ?: catalogPosterCache[animeUrl]
    }

    override fun latestUpdatesNextPageSelector() = "div.pagination i#nextpagination"

    override fun latestUpdatesParse(response: Response): AnimesPage {
        fetchGenresList()
        ensureCatalogPosterCache()

        val document = response.asJsoup()
        val rawAnimes = document.select(latestUpdatesSelector()).mapNotNull { element ->
            runCatching {
                val epHref = element.selectFirst("a[href*='/episodio/']")?.attr("href")
                    ?: element.selectFirst("a")?.attr("href")
                    ?: return@mapNotNull null

                val animeUrl = if (epHref.contains("-episodio-")) {
                    epHref.substringBeforeLast("-episodio-").replace("/episodio/", "/anime/")
                } else {
                    epHref
                }

                val title = element.selectFirst("span.serie")?.text()
                    ?: element.selectFirst("img")?.attr("alt")?.substringBefore(" Episódio")
                    ?: ""

                SAnime.create().apply {
                    setUrlWithoutDomain(animeUrl)
                    this.title = title
                    // Note: thumbnail_url is NOT set from episode element!
                }
            }.getOrNull()
        }

        val distinctAnimes = rawAnimes.distinctBy { it.url }

        // Concurrently resolve canonical posters: cache hit -> 0 requests; cache miss -> 1 GET of canonical anime page
        runBlocking(Dispatchers.IO) {
            distinctAnimes.map { anime ->
                async {
                    val canonUrl = if (anime.url.startsWith("http")) anime.url else "$baseUrl${anime.url}"
                    val slug = canonUrl.substringAfterLast("/")

                    val cachedPoster = catalogPosterCache[anime.url]
                        ?: catalogPosterCache[slug]
                        ?: catalogPosterCache[canonUrl]

                    if (!cachedPoster.isNullOrBlank()) {
                        anime.thumbnail_url = cachedPoster
                    } else {
                        val info = fetchCanonicalAnimeInfo(canonUrl)
                        if (info != null) {
                            val (realUrl, poster) = info
                            anime.setUrlWithoutDomain(realUrl)
                            val realSlug = realUrl.substringAfterLast("/")
                            catalogPosterCache[anime.url] = poster
                            catalogPosterCache[slug] = poster
                            catalogPosterCache[realSlug] = poster
                            catalogPosterCache[canonUrl] = poster
                            catalogPosterCache[realUrl] = poster
                            anime.thumbnail_url = poster
                        }
                    }

                    Log.d("ANIMEQ_LATEST", "title=${anime.title} canonicalUrl=${anime.url} finalThumbnail=${anime.thumbnail_url}")
                }
            }.awaitAll()
        }

        val hasNextPage = document.selectFirst(latestUpdatesNextPageSelector()) != null
        return AnimesPage(distinctAnimes, hasNextPage)
    }

    // =============================== Search ===============================
    override fun searchAnimeSelector() = "div.result-item div.image a"

    override fun searchAnimeNextPageSelector() = "div.pagination i#nextpagination"

    override fun searchAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val img = element.selectFirst("img")
        val detailsTitle = element.closest("div.result-item")?.selectFirst("div.details div.title a")?.text()
        title = detailsTitle ?: img?.attr("alt") ?: ""
        thumbnail_url = img?.getImageUrl()
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val filterPart = filterList.firstOrNull { it is UriPartFilter && it.state != 0 } as? UriPartFilter

        if (query.isBlank() && filterPart == null) {
            return popularAnimeRequest(page)
        }

        val params = AnimeQFilters.getSearchParameters(filterList)
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (filterPart != null) {
                addEncodedPathSegments(filterPart.toUriPart())
            }

            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }

            if (query.isNotBlank()) {
                addQueryParameter("s", query)
            }

            params.orderBy?.let { addQueryParameter("orderby", it) }
            params.order?.let { addQueryParameter("order", it) }
        }.build()

        return GET(url.toString(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val isTextSearch = response.request.url.queryParameter("s")?.isNotBlank() == true

        val animes = if (isTextSearch) {
            document.select(searchAnimeSelector()).map(::searchAnimeFromElement)
        } else {
            document.select(popularAnimeSelector()).map(::popularAnimeFromElement)
        }

        val hasNextPage = document.selectFirst(searchAnimeNextPageSelector()) != null
        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================
    override fun getRealAnimeDoc(document: Document): Document {
        val breadcrumbLink = document.selectFirst("div.breadcrumb a[href*='/anime/']")
            ?: document.selectFirst(animeMenuSelector)
        return if (breadcrumbLink != null) {
            val originalUrl = breadcrumbLink.attr("abs:href")
            val req = client.newCall(GET(originalUrl, headers)).execute()
            req.asJsoup()
        } else {
            document
        }
    }

    override val additionalInfoSelector = "div.wp-content"

    override fun Document.getDescription(): String = select("$additionalInfoSelector p")
        .firstOrNull { it.text().contains("Sinopse:") || !it.text().contains("Título Alternativo") }
        ?.let { it.text().substringAfter("Sinopse: ") + "\n" }
        ?: ""

    fun Document.getAlternativeTitle(): String = select("$additionalInfoSelector p")
        .firstOrNull { it.text().contains("Título Alternativo") }
        ?.let { it.text() + "\n" }
        ?: ""

    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealAnimeDoc(document)
        val sheader = doc.selectFirst("div.sheader") ?: return super.animeDetailsParse(doc)
        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            val posterImg = sheader.selectFirst("div.poster > img, div.poster img")
            thumbnail_url = posterImg?.getImageUrl()
            title = sheader.selectFirst("div.data > h1, h1")?.text()?.ifEmpty {
                posterImg?.attr("alt") ?: ""
            }?.trim() ?: ""

            genre = sheader.select("div.sgeneros > a, div.data div.sgeneros > a")
                .eachText()
                .filterNot { it.startsWith("Letra ") }
                .joinToString()

            val info = doc.selectFirst("div#info") ?: return@apply
            description = buildString {
                append(doc.getDescription())
                appendLine()
                append(doc.getAlternativeTitle())
                additionalInfoItems.forEach { item ->
                    info.getInfo(item)?.let(::append)
                }
            }.trim()
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.episodios-grid > div.episode-card"

    override fun episodeFromElement(element: Element, seasonName: String): SEpisode = SEpisode.create().apply {
        val epNum = element.attr("data-episode-number").trim()
        val href = element.selectFirst("a[href]")!!
        val episodeTitle = element.attr("data-episode-title").trim().ifEmpty {
            element.selectFirst("h3.episode-title")?.text()?.trim() ?: ""
        }
        episode_number = epNum.toFloatOrNull() ?: 0F
        name = if (episodeTitle.isNotEmpty()) {
            "$episodeSeasonPrefix $seasonName x $epNum - $episodeTitle"
        } else {
            "$episodeSeasonPrefix $seasonName x $epNum"
        }
        setUrlWithoutDomain(href.attr("href"))
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()

        // 1. Movie / DooPlay player options (e.g. /filme/...)
        val players = document.select("ul#playeroptionsul li:not(#player-option-trailer)")
        if (players.isNotEmpty()) {
            return players.parallelCatchingFlatMapBlocking(::getPlayerVideos)
        }

        // 2. Series Episode player (animeq-player)
        val serverButtons = document.select("button.animeq-player__server")
        val serverNames = serverButtons.associate { btn ->
            val switchId = btn.attr("data-animeq-switch")
            val name = btn.attr("data-source-name").ifBlank { btn.text() }
            switchId to name
        }

        val sources = document.select("div.animeq-player__source")
        if (sources.isNotEmpty()) {
            val playlistUtils by lazy { PlaylistUtils(client, headers) }

            return sources.flatMap { source ->
                runCatching {
                    val switchIndex = source.attr("data-animeq-source")
                    val serverName = serverNames[switchIndex]?.ifBlank { null }
                        ?: source.attr("data-source-name").ifBlank { null }
                        ?: "Player"

                    val videoUrl = source.selectFirst("source[src]")?.absUrl("src")
                        ?.ifBlank { null }
                        ?: source.selectFirst("[data-video-src]")?.let {
                            val src = it.attr("data-video-src")
                            if (src.startsWith("http")) src else it.attr("abs:data-video-src")
                        }?.ifBlank { null }
                        ?: source.selectFirst("video[src]")?.absUrl("src")
                            ?.ifBlank { null }

                    val iframeUrl = source.selectFirst("iframe[src], iframe[data-lazy-src]")?.let { iframe ->
                        iframe.attr("data-lazy-src").ifEmpty { iframe.absUrl("src") }
                    }?.ifBlank { null }

                    val videoHeaders = headers.newBuilder()
                        .set("Accept", "*/*")
                        .set("Referer", "$baseUrl/")
                        .set("Origin", baseUrl)
                        .build()

                    when {
                        !videoUrl.isNullOrBlank() -> {
                            if (videoUrl.contains(".m3u8")) {
                                val hlsVideos = playlistUtils.extractFromHls(
                                    videoUrl,
                                    "$baseUrl/",
                                    videoNameGen = { quality -> "$serverName: $quality" },
                                )
                                hlsVideos.ifEmpty {
                                    listOf(Video(videoUrl, serverName, videoUrl, videoHeaders))
                                }
                            } else {
                                listOf(
                                    Video(videoUrl, serverName, videoUrl, videoHeaders),
                                )
                            }
                        }

                        !iframeUrl.isNullOrBlank() -> {
                            when {
                                "blogger.com" in iframeUrl -> {
                                    runBlocking {
                                        bloggerExtractor.videosFromUrl(iframeUrl, headers, suffix = "($serverName)")
                                    }
                                }
                                else -> {
                                    universalExtractor.videosFromUrl(iframeUrl, headers, serverName)
                                }
                            }
                        }

                        else -> emptyList()
                    }
                }.getOrElse { emptyList() }
            }
        }

        return emptyList()
    }

    private val bloggerExtractor by lazy { BloggerExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private suspend fun getPlayerVideos(player: Element): List<Video> = runCatching {
        val nume = player.attr("data-nume")
        if (nume == "trailer" || player.id().contains("trailer")) return emptyList()

        val name = player.selectFirst("span.title")?.text()
            ?.run {
                when (this.uppercase()) {
                    "SD" -> "360p"
                    "HD" -> "720p"
                    "SD/HD", "SD / HD" -> "720p"
                    "FHD", "FULLHD", "FULLHD / HLS" -> "1080p"
                    else -> this
                }
            } ?: "Player"

        val url = getPlayerUrl(player)
        if (url.isEmpty() || !url.startsWith("http")) return emptyList()

        val videos = when {
            "blogger.com" in url -> bloggerExtractor.videosFromUrl(url, headers)
            "jwplayer?source=" in url -> {
                val videoUrl = url.toHttpUrl().queryParameter("source") ?: return emptyList()

                val videoHeaders = headers.newBuilder()
                    .set("Accept", "*/*")
                    .set("Host", videoUrl.toHttpUrl().host)
                    .set("Origin", "https://${url.toHttpUrl().host}")
                    .set("Referer", "https://${url.toHttpUrl().host}/")
                    .build()

                return listOf(
                    Video(videoUrl, name, videoUrl, videoHeaders),
                )
            }

            else -> emptyList()
        }

        if (videos.isEmpty()) {
            return universalExtractor.videosFromUrl(url, headers, name)
        }
        videos
    }.getOrElse { emptyList() }

    private suspend fun getPlayerUrl(player: Element): String {
        val type = player.attr("data-type")
        val id = player.attr("data-post")
        val num = player.attr("data-nume")
        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", id)
            .add("nume", num)
            .add("type", type)
            .build()

        val postResult = runCatching {
            client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", headers, body))
                .awaitSuccess().bodyString()
                .substringAfter("\"embed_url\":\"")
                .substringBefore("\"")
                .replace("\\/", "/")
                .replace("\\", "")
        }.getOrNull()

        if (!postResult.isNullOrBlank() && postResult.startsWith("http")) {
            return postResult
        }

        return runCatching {
            client.newCall(GET("$baseUrl/wp-json/dooplayer/v2/$id/$type/$num", headers))
                .awaitSuccess().bodyString()
                .substringAfter("\"embed_url\":\"")
                .substringBefore("\"")
                .replace("\\/", "/")
                .replace("\\", "")
        }.getOrDefault("")
    }

    // ============================== Filters ===============================
    @Volatile
    private var hasFetchedGenresArray = false

    override val genreFilterHeader = "Apenas um tipo de filtro por vez"
    override fun genresListRequest() = GET("$baseUrl/wp-json/wp/v2/genres?per_page=100&_fields[]=name&_fields[]=link")

    override fun getFilterList(): AnimeFilterList = if (hasFetchedGenresArray) {
        AnimeFilterList(
            AnimeFilter.Header(genreFilterHeader),
            AnimeQFilters.AudioFilter(),
            FetchedGenresFilter(genresListMessage, genresArray),
            AnimeFilter.Separator(),
            AnimeQFilters.OrderByFilter(),
            AnimeQFilters.OrderFilter(),
        )
    } else if (fetchGenres) {
        AnimeFilterList(AnimeFilter.Header(genresMissingWarning))
    } else {
        AnimeFilterList()
    }

    @Synchronized
    override fun fetchGenresList() {
        if (hasFetchedGenresArray || !fetchGenres) return

        runCatching {
            client.newCall(genresListRequest())
                .execute()
                .parseAs<List<GenreDto>>()
                .let(::genresListParse)
                .let { items ->
                    if (items.isNotEmpty()) {
                        genresArray = items
                        hasFetchedGenresArray = true
                    }
                }
        }.onFailure { it.printStackTrace() }
    }

    fun genresListParse(genres: List<GenreDto>): Array<Pair<String, String>> {
        val items = genres.map {
            val name = it.name
            val value = it.link.substringAfter("$baseUrl/").removeSuffix("/")
            Pair(name, value)
        }.toTypedArray()

        return if (items.isEmpty()) {
            items
        } else {
            arrayOf(Pair(selectFilterText, "")) + items
        }
    }

    // ============================= Utilities ==============================
    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(quality) }
                .thenByDescending {
                    REGEX_QUALITY.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull()
                        ?: when {
                            "FHD" in it.videoTitle.uppercase() -> 1080
                            "HD" in it.videoTitle.uppercase() -> 720
                            "SD" in it.videoTitle.uppercase() -> 360
                            else -> 0
                        }
                },
        )
    }

    override fun Element.getImageUrl(): String {
        val url = when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }

        // Remove the "-<width>x<height>" suffix before the file extension:
        // ex: ".../file-200x300.jpg" -> ".../file.jpg"
        return url.replace(REGEX_IMAGE_SIZE_SUFFIX, "")
    }

    @Serializable
    data class GenreDto(
        val name: String,
        val link: String,
    )

    companion object {
        private val REGEX_QUALITY by lazy { Regex("""(\d+)p""") }
        private val REGEX_IMAGE_SIZE_SUFFIX by lazy {
            Regex("""-\d+x\d+(?=\.[A-Za-z0-9]+$)""")
        }
    }
}
