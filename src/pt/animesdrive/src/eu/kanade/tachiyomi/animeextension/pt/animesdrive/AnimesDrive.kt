package eu.kanade.tachiyomi.animeextension.pt.animesdrive

import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animeextension.pt.animesdrive.extractors.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors

class AnimesDrive :
    DooPlay(
        "pt-BR",
        "Animes Drive",
        "https://animesdrive.online",
    ) {

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/anime", headers)

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = "div.pagination > a.arrow_pag > i.fa-caret-right"

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters

        val orderByFilter = filterList.find { it is OrderByFilter } as? OrderByFilter
        val orderFilter = filterList.find { it is OrderFilter } as? OrderFilter

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            filterList.firstOrNull { it is UriPartFilter && it.state != 0 }?.let {
                val filter = it as UriPartFilter
                addEncodedPathSegments(filter.toUriPart())
            }

            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }

            addPathSegment("")
            addQueryParameter("s", query)

            // order (optional)
            if (orderByFilter != null) addQueryParameter("orderby", orderByFilter.selected)
            if (orderFilter != null) addQueryParameter("order", orderFilter.selected)
        }.build()

        return GET(url.toString(), headers)
    }

    // =========================== Anime Details ============================
    override val additionalInfoSelector = "div.wp-content"

    override fun Document.getDescription(): String = select("$additionalInfoSelector p")
        .first { !it.text().contains("Título Alternativo") }
        ?.let { it.text() + "\n" }
        ?: ""

    fun Document.getAlternativeTitle(): String = select("$additionalInfoSelector p")
        .first { it.text().contains("Título Alternativo") }
        ?.let { it.text() + "\n" }
        ?: ""

    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealAnimeDoc(document)
        val sheader = doc.selectFirst("div.sheader")
        if (sheader == null) {
            return SAnime.create().apply {
                setUrlWithoutDomain(doc.location())
                title = doc.selectFirst("h1")?.text()?.trim()
                    ?.ifEmpty { doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim().orEmpty() }
                    ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim().orEmpty()
                thumbnail_url = doc.selectFirst("meta[property='og:image']")?.attr("content")?.trim()
                description = doc.selectFirst("meta[name='description']")?.attr("content")?.trim().orEmpty()
            }
        }
        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            sheader.selectFirst("div.poster > img")!!.let {
                thumbnail_url = it.getImageUrl()
                title = it.attr("alt").ifEmpty {
                    sheader.selectFirst("div.data > h1")!!.text()
                }.trim()
            }

            genre = sheader.select("div.data div.sgeneros > a")
                .eachText()
                .joinToString()

            // description = doc.getDescription()
            doc.selectFirst("div#info")?.let { info ->
                description = buildString {
                    append(doc.getDescription())
                    append(doc.getAlternativeTitle())
                    additionalInfoItems.forEach {
                        info.getInfo(it)?.let(::append)
                    }
                }
            }
        }
    }

    override fun getRealAnimeDoc(document: Document): Document {
        val menu = document.selectFirst(animeMenuSelector)
        val explicitUrl = menu?.parent()?.takeIf { !it.hasClass("nonex") }?.attr("href")
            ?.takeIf { it.contains("/anime/") }
        val animeUrl = explicitUrl ?: document.select("div.pag_episodes a[href*='/episodio/']")
            .map { it.attr("href") }
            .firstOrNull { it.substringAfterLast('/').substringBeforeLast("-episodio-") == document.location().substringAfterLast('/').substringBeforeLast("-episodio-") }
            ?.let { episodeUrl ->
                val slug = episodeUrl.substringAfterLast('/').substringBeforeLast("-episodio-")
                "${baseUrl.trimEnd('/')}/anime/$slug"
            }

        if (animeUrl == null) {
            return document
        }

        return runCatching {
            client.newCall(GET(animeUrl, headers)).execute().use { response ->
                if (response.isSuccessful) response.asJsoup() else document
            }
        }.getOrElse { document }
    }

    // AnimesDrive still embeds episodes in the details HTML, but its current
    // season header is not consistently a span.se-t. Do not use the DooPlay
    // parser here: it force-unwraps that header and aborts the whole list.
    override fun episodeListParse(response: Response): List<SEpisode> {
        val initial = response.asJsoup()
        val doc = getRealAnimeDoc(initial)

        val seasons = doc.select("div#seasons > div.se-c")

        if (seasons.isEmpty()) {
            return parseOrphanEpisodes(initial)
        }

        val episodes = seasons.flatMap { season ->
            val seasonNumber = season.selectFirst("span.se-t")
                ?.text()
                ?.trim()
                .orEmpty()

            season.select("div.episodios-grid > div.episode-card").mapNotNull { element ->
                val href = element.selectFirst("a[href]")?.attr("href")?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val name = element.selectFirst("h3.episode-title")?.text()?.trim()
                    ?: element.attr("data-episode-title").trim()
                val number = element.attr("data-episode-number").toFloatOrNull()
                    ?: EPISODE_NUMBER_REGEX.find(name)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: return@mapNotNull null

                SEpisode.create().apply {
                    setUrlWithoutDomain(href)
                    episode_number = number
                    this.name = if (seasonNumber.isBlank()) name else "Temporada $seasonNumber - $name"
                }
            }
        }.reversed()

        return episodes
    }

    private fun parseOrphanEpisodes(initial: Document): List<SEpisode> {
        val result = mutableListOf<SEpisode>()
        val visited = mutableSetOf<String>()
        var document = initial
        var url = initial.location()
        var step = 0
        while (step < 50) {
            val series = orphanSeriesKey(document, url)
            if (!visited.add(url)) {
                break
            }
            val name = document.selectFirst("[data-animeq-player]")?.attr("data-post-episode")?.trim()
                ?: document.selectFirst("h1")?.text()?.trim()
                ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim().orEmpty()
            val number = EPISODE_NUMBER_REGEX.find(name)?.groupValues?.get(1)?.toFloatOrNull()
            if (number == null) {
                break
            }
            result += SEpisode.create().apply {
                setUrlWithoutDomain(url)
                episode_number = number
                this.name = name
            }

            val candidates = document.select("div.pag_episodes a[href]")
            var accepted: Pair<String, Document>? = null
            for (candidate in candidates) {
                val candidateUrl = candidate.attr("href")
                val currentHttpUrl = url.toHttpUrlOrNull()
                val resolvedUrl = currentHttpUrl?.resolve(candidateUrl)
                val sameHost = resolvedUrl?.host == currentHttpUrl?.host
                val candidateHrefValid = resolvedUrl != null && sameHost
                if (!candidate.text().contains("Anterior", ignoreCase = true) || !candidateHrefValid) {
                    continue
                }
                val nextUrl = resolvedUrl.toString()
                if (visited.contains(nextUrl) || nextUrl == url) {
                    break
                }
                val next = runCatching {
                    client.newCall(GET(nextUrl, headers)).execute().use { response ->
                        if (response.isSuccessful) response.asJsoup() else null
                    }
                }.getOrNull()
                if (next == null) {
                    continue
                }
                val nextSeries = orphanSeriesKey(next, nextUrl)
                val sameSeries = series == null || nextSeries == null || series == nextSeries
                if (sameSeries) {
                    accepted = nextUrl to next
                    break
                }
            }
            if (accepted == null) {
                break
            }
            url = accepted!!.first
            document = accepted!!.second
            step++
        }
        return result
    }

    private fun orphanSeriesKey(document: Document, url: String): String? = document.selectFirst("[data-animeq-player]")?.attr("data-post-series")?.trim()?.takeIf(String::isNotBlank)
        ?: url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.substringBeforeLast("-episodio-")

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        val players = document.select("ul#playeroptionsul li")
        if (players.isNotEmpty()) return players.parallelCatchingFlatMapBlocking(::getPlayerVideos)

        val sourceEntries = document.select("[data-animeq-player] [data-animeq-source]").map { source ->
            SourceEntry(source, response.request.url.toString())
        }
        val fastSources = sourceEntries.filter { it.sourceType() in FAST_SOURCE_TYPES }
        val slowSources = sourceEntries - fastSources.toSet()
        val fastVideos = fastSources.parallelCatchingFlatMapBlocking(::extractSourceVideos)

        if (fastVideos.isNotEmpty()) {
            return fastVideos
        }

        return extractFirstValidatedSlowSource(slowSources)
    }

    private suspend fun extractSourceVideos(entry: SourceEntry): List<Video> {
        val source = entry.element
        val episodeUrl = entry.episodeUrl
        val name = source.selectFirst("button[data-source-name]")?.attr("data-source-name")
            ?: source.selectFirst("[data-video-title]")?.attr("data-video-title")
            ?: source.selectFirst("iframe[title]")?.attr("title")
            ?: "Player"
        val videoUrl = source.selectFirst("source[src]")?.absUrl("src")
            ?: source.selectFirst("[data-video-src]")?.absUrl("data-video-src")
        val iframeUrl = source.selectFirst("iframe[data-lazy-src], iframe[src]")?.let {
            it.attr("data-lazy-src").ifEmpty { it.absUrl("src") }
        }

        val extracted = when {
            !videoUrl.isNullOrBlank() -> listOf(
                Video(
                    videoUrl,
                    name,
                    videoUrl,
                    directHeaders(episodeUrl)
                        .add("Accept", "*/*")
                        .build(),
                ),
            ).filter(::probeDirectVideo)
            !iframeUrl.isNullOrBlank() && "blogger.com" in iframeUrl ->
                runBlocking { bloggerExtractor.videosFromUrl(iframeUrl, headers) }
            !iframeUrl.isNullOrBlank() -> universalExtractor.videosFromUrl(iframeUrl, headers, name)
                .filter(::validateIframeVideo)
            else -> emptyList()
        }
        return extracted
    }

    private fun extractFirstValidatedSlowSource(entries: List<SourceEntry>): List<Video> {
        if (entries.isEmpty()) return emptyList()

        val executor = Executors.newFixedThreadPool(entries.size) { runnable ->
            Thread(runnable, "AnimesDrive-Slow").apply { isDaemon = true }
        }
        val completion = ExecutorCompletionService<SlowResult>(executor)
        val futures = entries.map { entry ->
            completion.submit(
                Callable {
                    SlowResult(runCatching { runBlocking { extractSourceVideos(entry) } }.getOrDefault(emptyList()))
                },
            )
        }

        try {
            repeat(entries.size) {
                val result = completion.take().get()
                if (result.videos.isNotEmpty()) {
                    futures.filter { !it.isDone }.forEach { it.cancel(true) }
                    return result.videos
                }
            }
            return emptyList()
        } finally {
            executor.shutdownNow()
        }
    }

    private fun directHeaders(episodeUrl: String) = headers.newBuilder().apply {
        episodeUrl.toHttpUrlOrNull()?.let { url ->
            add("Origin", "${url.scheme}://${url.host}")
            add("Referer", url.toString())
        }
    }

    private fun probeDirectVideo(video: Video): Boolean {
        val mediaUrl = video.videoUrl ?: return false
        val videoHeaders = video.headers ?: headers
        val rangeProbe = probeMedia(mediaUrl, videoHeaders, useRange = true)
        val result = if (rangeProbe.valid) rangeProbe else probeMedia(mediaUrl, videoHeaders, useRange = false)
        return result.valid
    }

    private fun probeMedia(url: String, videoHeaders: okhttp3.Headers, useRange: Boolean): MediaProbe {
        val request = Request.Builder().url(url).headers(videoHeaders).apply {
            if (useRange) addHeader("Range", "bytes=0-1")
        }.build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type").orEmpty().substringBefore(';').lowercase()
                val mediaType = contentType.startsWith("video/") || contentType in HLS_CONTENT_TYPES
                val hls = contentType in HLS_CONTENT_TYPES && response.body?.byteStream()?.bufferedReader()?.use { it.readLine() }?.contains("#EXTM3U") == true
                MediaProbe((response.code == 200 || response.code == 206) && (contentType.startsWith("video/") || hls || (mediaType && url.substringBefore('?').endsWith(".m3u8"))))
            }
        }.getOrElse { MediaProbe(false) }
    }

    private fun probeHlsVideo(video: Video): Boolean {
        val url = video.videoUrl ?: return false
        val videoHeaders = video.headers ?: headers
        val master = fetchHlsPlaylist(url, videoHeaders)
        val masterValid = master.isValidHls()
        val masterType = master.type()
        val variant = if (masterValid && masterType == HlsType.MASTER) {
            master.resolveUriAfter("#EXT-X-STREAM-INF")?.let { fetchHlsPlaylist(it, videoHeaders) }
        } else {
            null
        }
        val media = variant ?: master
        val mediaValid = media.isValidHls() && media.type() == HlsType.MEDIA
        val mapHttp = if (mediaValid) media.mapUri()?.let { probeHlsResource(it, videoHeaders) } ?: "NONE" else "SKIPPED"
        val segmentHttp = if (mediaValid) media.resolveUriAfter("#EXTINF")?.let { probeHlsResource(it, videoHeaders) } ?: "NONE" else "SKIPPED"
        val valid = masterValid && (variant == null || variant.isValidHls()) && mediaValid &&
            mapHttp in setOf("NONE", "200", "206") && segmentHttp in setOf("200", "206")
        return valid
    }

    private fun validateIframeVideo(video: Video): Boolean {
        val mediaUrl = video.videoUrl ?: return false
        return if (mediaUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
            probeHlsVideo(video)
        } else {
            true
        }
    }

    private fun fetchHlsPlaylist(url: String, videoHeaders: okhttp3.Headers): HlsProbe = runCatching {
        client.newCall(Request.Builder().url(url).headers(videoHeaders).build()).execute().use { response ->
            HlsProbe(
                http = response.code,
                finalUrl = response.request.url.toString(),
                playlist = response.body?.string().orEmpty(),
            )
        }
    }.getOrElse { HlsProbe("ERROR", "", "") }

    private fun probeHlsResource(url: String, videoHeaders: okhttp3.Headers): String = runCatching {
        client.newCall(
            Request.Builder().url(url).headers(videoHeaders).addHeader("Range", "bytes=0-1").build(),
        ).execute().use { response ->
            response.code.toString()
        }
    }.getOrElse { "ERROR" }

    private data class SourceEntry(val element: Element, val episodeUrl: String)

    private fun SourceEntry.sourceType(): String {
        val videoUrl = element.selectFirst("source[src],[data-video-src]")
        val iframeUrl = element.selectFirst("iframe[data-lazy-src], iframe[src]")?.let {
            it.attr("data-lazy-src").ifEmpty { it.absUrl("src") }
        }
        return when {
            videoUrl != null -> if (videoUrl.tagName() == "source") "MP4" else "DATA_VIDEO_SRC"
            !iframeUrl.isNullOrBlank() && "blogger.com" in iframeUrl -> "BLOGGER"
            !iframeUrl.isNullOrBlank() -> "IFRAME"
            else -> "OTHER"
        }
    }

    private data class MediaProbe(
        val valid: Boolean,
    )

    private data class HlsProbe(
        val http: Any,
        val finalUrl: String,
        val playlist: String,
    ) {
        fun isValidHls() = http == 200 && playlist.contains("#EXTM3U")

        fun type() = when {
            tagCount("#EXT-X-STREAM-INF") > 0 -> HlsType.MASTER
            tagCount("#EXTINF") > 0 -> HlsType.MEDIA
            else -> HlsType.OTHER
        }

        fun tagCount(tag: String) = playlist.lineSequence().count { it.trim().startsWith(tag) }

        fun resolveUriAfter(tag: String): String? = uriAfter(tag)?.let { uri ->
            finalUrl.toHttpUrlOrNull()?.resolve(uri)?.toString()
        }

        fun mapUri(): String? = playlist.lineSequence().map(String::trim)
            .firstOrNull { it.startsWith("#EXT-X-MAP") }
            ?.let { MAP_URI_REGEX.find(it)?.groupValues?.get(1) }
            ?.let { uri -> finalUrl.toHttpUrlOrNull()?.resolve(uri)?.toString() }

        private fun uriAfter(tag: String): String? {
            val lines = playlist.lineSequence().map(String::trim).toList()
            val index = lines.indexOfFirst { it.startsWith(tag) }
            if (index < 0) return null
            return lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        }
    }

    private enum class HlsType { MASTER, MEDIA, OTHER }

    private data class SlowResult(val videos: List<Video>)

    private val bloggerExtractor by lazy { BloggerExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private suspend fun getPlayerVideos(player: Element): List<Video> {
        val name = player.selectFirst("span.title")!!.text()
            .run {
                when (this.uppercase()) {
                    "SD" -> "360p"
                    "HD" -> "720p"
                    "SD/HD", "SD / HD" -> "720p"
                    "FHD", "FULLHD", "FULLHD / HLS" -> "1080p"
                    else -> this
                }
            }

        val url = getPlayerUrl(player)

        val videos = when {
            "blogger.com" in url -> bloggerExtractor.videosFromUrl(url, headers)

            "jwplayer?source=" in url -> {
                val videoUrl = url.toHttpUrl().queryParameter("source") ?: return emptyList()

                val videoHeaders = headers.newBuilder()
                    .add("Accept", "*/*")
                    .add("Host", videoUrl.toHttpUrl().host)
                    .add("Origin", "https://${url.toHttpUrl().host}")
                    .add("Referer", "https://${url.toHttpUrl().host}/")
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
        return videos
    }

    private suspend fun getPlayerUrl(player: Element): String {
        val type = player.attr("data-type")
        val id = player.attr("data-post")
        val num = player.attr("data-nume")
        return client.newCall(GET("$baseUrl/wp-json/dooplayer/v2/$id/$type/$num"))
            .awaitSuccess().bodyString()
            .substringAfter("\"embed_url\":\"")
            .substringBefore("\",")
            .replace("\\", "")
    }

    // ============================== Filters ===============================
    @Volatile
    private var hasFetchedGenresArray = false

    override val genreFilterHeader = "Apenas um tipo de filtro por vez"
    override fun genresListRequest() = GET("$baseUrl/wp-json/wp/v2/genres?per_page=100&_fields[]=name&_fields[]=link")

    override fun getFilterList(): AnimeFilterList = if (hasFetchedGenresArray) {
        AnimeFilterList(
            AnimeFilter.Header(genreFilterHeader),
            AudioFilter(),
            FetchedGenresFilter(genresListMessage, genresArray),
            AnimeFilter.Separator(),
            OrderByFilter(),
            OrderFilter(),
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

    private class AudioFilter :
        UriPartFilter(
            "Áudio",
            arrayOf(
                Pair("Todos", ""),
                Pair("Dublado", "tipo/dublado"),
                Pair("Legendado", "tipo/legendado"),
            ),
        )

    private abstract class SelectFilter(
        name: String,
        private val options: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        val selected
            get() = options[state].second
    }

    private class OrderByFilter :
        SelectFilter(
            "Ordenar Por",
            arrayOf(
                Pair("Data de Criação", "date"),
                Pair("Data de Modificação", "modified"),
                Pair("Título", "title"),
            ),
        )

    private class OrderFilter :
        SelectFilter(
            "Ordem",
            arrayOf(
                Pair("Descendente", "desc"),
                Pair("Ascendente", "asc"),
            ),
        )

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!

        return sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending {
                    REGEX_QUALITY.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
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
        private val EPISODE_NUMBER_REGEX = "(?:Epis[oó]dio\\s+)([0-9]+(?:\\.[0-9]+)?)".toRegex(RegexOption.IGNORE_CASE)
        private val FAST_SOURCE_TYPES = setOf("MP4", "DATA_VIDEO_SRC", "BLOGGER")
        private val HLS_CONTENT_TYPES = setOf("application/vnd.apple.mpegurl", "application/x-mpegurl")
        private val MAP_URI_REGEX = "URI=\"([^\"]+)\"".toRegex()
        private val REGEX_QUALITY by lazy { Regex("""(\d+)p""") }
        private val REGEX_IMAGE_SIZE_SUFFIX by lazy {
            Regex("""-\d+x\d+(?=\.[A-Za-z0-9]+$)""")
        }
    }
}
