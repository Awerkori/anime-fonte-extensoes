package eu.kanade.tachiyomi.animeextension.pt.meusanimes

import aniyomi.lib.bloggerextractor.BloggerExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class MeusAnimes : AnimeHttpSource() {

    override val name = "Meus Animes"
    override val baseUrl = "https://meusanimes.blog"
    override val lang = "pt-BR"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", baseUrl)

    // Requests: Popular anime request
    override fun popularAnimeRequest(page: Int): Request = GET(
        if (page == 1) "$baseUrl/a/" else "$baseUrl/a/page/$page/",
        headers,
    )

    // Search anime request
    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val q = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/?s=$q", headers)
    }

    // Parse Lists: Parse popular anime list
    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val animes = document.select("article.item.tvshows").mapNotNull { element ->
            val link = element.selectFirst("div.poster a[href*=\"/a/\"], div.data h3 a[href]") ?: return@mapNotNull null
            val image = element.selectFirst("div.poster img") ?: return@mapNotNull null
            SAnime.create().apply {
                title = element.selectFirst("div.data h3")?.text()?.trim().orEmpty().ifBlank { image.attr("alt") }
                setUrlWithoutDomain(link.attr("abs:href"))
                thumbnail_url = image.attr("abs:src")
            }
        }.distinctBy(SAnime::url)

        val hasNextPage = document.selectFirst("a.next, .pagination a[href*='/page/']") != null

        return AnimesPage(animes, hasNextPage)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val animes = document.select("article .image a[href*=\"/a/\"]").map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                title = element.selectFirst("img")?.attr("alt").orEmpty()
                thumbnail_url = element.selectFirst("img")?.attr("abs:src").orEmpty()
                initialized = true
            }
        }.distinctBy(SAnime::url)
        return AnimesPage(animes, false)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET(
        if (page == 1) "$baseUrl/e/" else "$baseUrl/e/page/$page/",
        headers,
    )

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val episodeUrls = document.select("article.item.se.episodes div.data a[href]")
            .map { it.attr("abs:href") }
            .filter(String::isNotBlank)
            .distinct()

        val animes = episodeUrls.parallelCatchingFlatMapBlocking { episodeUrl ->
            latestAnimeFromEpisode(episodeUrl)?.let { listOf(it) }.orEmpty()
        }.distinctBy(SAnime::url)

        val hasNextPage = document.selectFirst(".pagination a[href*='/e/page/'], .pagination a.next") != null
        return AnimesPage(animes, hasNextPage)
    }

    private val latestEpisodeToAnime = ConcurrentHashMap<String, String>()
    private val latestAnimeCache = ConcurrentHashMap<String, SAnime>()

    private fun latestAnimeFromEpisode(episodeUrl: String): SAnime? {
        val animeUrl = latestEpisodeToAnime[episodeUrl] ?: runCatching {
            client.newCall(GET(episodeUrl, headers)).execute().use { episodeResponse ->
                if (!episodeResponse.isSuccessful) return@use null
                val episodeDocument = episodeResponse.useAsJsoup()
                episodeDocument.selectFirst("div.areaserie a[href*='/a/']")?.attr("abs:href")
            }
        }.getOrNull()?.also { latestEpisodeToAnime[episodeUrl] = it } ?: return null

        latestAnimeCache[animeUrl]?.let { return it }
        val animeDocument = runCatching {
            client.newCall(GET(animeUrl, headers)).execute().use { it.useAsJsoup() }
        }.getOrNull() ?: return null
        val header = animeDocument.selectFirst("div.dtsingle div.sheader") ?: return null
        val title = header.selectFirst("div.data h1")?.text()?.trim().orEmpty()
        val thumbnail = header.selectFirst("div.poster img")?.attr("abs:src").orEmpty()
        if (title.isBlank() || thumbnail.isBlank()) return null
        val anime = SAnime.create().apply {
            setUrlWithoutDomain(animeUrl)
            this.title = title
            thumbnail_url = thumbnail
            initialized = true
        }
        latestAnimeCache[animeUrl] = anime
        return anime
    }

    // No filters implemented
    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    // Anime Details: Extract anime data from script tag in page
    private fun extractAnimeData(document: Document): JSONObject? {
        return runCatching {
            val scriptContent = document.select("script")
                .map { it.data() }
                .firstOrNull { it.contains("animeData") }
                ?: return null

            val startToken = "\\\"animeData\\\":{"
            val startIdx = scriptContent.indexOf(startToken)
            if (startIdx == -1) return null

            val jsonStart = startIdx + startToken.length - 1
            val endIdx = scriptContent.indexOf("]}", jsonStart) + 2

            val fragment = scriptContent.substring(jsonStart, endIdx)

            val cleanedJson = fragment
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")

            JSONObject(cleanedJson)
        }.getOrNull()
    }

    // Parse core anime data from JSON
    private fun parseAnimeCore(json: JSONObject): CoreAnimeData {
        val title = json.optString("name")

        val altTitle = json.optString("nameOriginal")
            .takeIf { it.isNotBlank() }

        val description = json.optString("sinopse")
            .trim()
            .ifBlank { "Sinopse não disponível." }

        val status = when {
            json.optString("diaLancamento").isNotBlank() ->
                SAnime.ONGOING
            json.optInt("episodios") > 0 ->
                SAnime.COMPLETED
            else ->
                SAnime.UNKNOWN
        }

        return CoreAnimeData(
            title = title,
            altTitle = altTitle,
            description = description,
            status = status,
        )
    }

    // Fallback: parse anime details from meta tags
    private fun parseAnimeFromMeta(document: Document): SAnime = SAnime.create().apply {
        title = document.select("meta[property=og:title]").attr("content")
        description = document.select("meta[name=description]").attr("content")
        thumbnail_url = document.select("meta[property=og:image]").attr("content")
        initialized = true
    }

    // Data class for core anime information
    private data class CoreAnimeData(
        val title: String,
        val altTitle: String?,
        val description: String,
        val status: Int,
    )

    // Main anime details parser
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()
        document.selectFirst("div.dtsingle div.sheader")?.let { header ->
            return SAnime.create().apply {
                setUrlWithoutDomain(document.location())
                title = header.selectFirst("div.data h1")?.text()?.trim().orEmpty()
                thumbnail_url = header.selectFirst("div.poster img")?.attr("abs:src").orEmpty()
                genre = header.select("div.data .sgeneros a").eachText().joinToString()
                description = document.selectFirst("div#info div.wp-content")?.text().orEmpty()
                status = when {
                    header.selectFirst(".status") != null -> SAnime.ONGOING
                    else -> SAnime.UNKNOWN
                }
                initialized = true
            }
        }
        val json = extractAnimeData(document)
            ?: return parseAnimeFromMeta(document)

        val core = parseAnimeCore(json)

        return SAnime.create().apply {
            title = core.title
            artist = core.altTitle
            description = core.description
            status = core.status

            thumbnail_url = json.optString("poster")
                .takeIf { it.isNotBlank() }
                ?.let { "https://image.tmdb.org/t/p/w500$it" }
                ?: document.select("meta[property=og:image]").attr("content")

            initialized = true
        }
    }

    // Alternative JSON parser (not used in current implementation)
    private fun parseAnimeFromJson(
        json: JSONObject,
        document: Document,
    ): SAnime = SAnime.create().apply {
        title = json.optString("name")

        // Studio
        author = json.optJSONObject("Studio")
            ?.optString("name")

        // Original title goes to "artist" field (Tachiyomi standard)
        artist = json.optString("nameOriginal").takeIf { it.isNotBlank() }

        val year = json.optInt("ano").takeIf { it > 0 }
        val synopsis = json.optString("sinopse")

        description = buildString {
            if (year != null) append("Ano: $year\n\n")
            append(synopsis)
        }

        genre = json.optJSONArray("Animegenero")
            ?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.getJSONObject(i)
                        .optJSONObject("Genero")
                        ?.optString("name")
                }.joinToString(", ")
            }

        status = when (json.optString("status").lowercase()) {
            "ended", "finalizado", "completo" -> SAnime.COMPLETED
            "releasing", "em lançamento", "andamento" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }

        thumbnail_url = json.optString("poster")
            .takeIf { it.isNotBlank() }
            ?.let { "https://image.tmdb.org/t/p/w500$it" }
            ?: document.select("meta[property=og:image]").attr("content")

        initialized = true
    }

    // Episodes: Parse episode list from JSON data
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val currentEpisodes = document.select("div#seasons div.se-c ul.episodios > li")
        if (currentEpisodes.isNotEmpty()) {
            return currentEpisodes.mapNotNull { element ->
                val link = element.selectFirst("div.episodiotitle a[href]") ?: return@mapNotNull null
                val number = element.selectFirst("div.numerando")?.text()
                    ?.substringAfterLast("-")?.trim()?.toFloatOrNull()
                    ?: link.text().filter { it.isDigit() }.toFloatOrNull()
                    ?: 0F
                SEpisode.create().apply {
                    name = link.text().trim()
                    episode_number = number
                    url = link.attr("abs:href").removePrefix(baseUrl)
                }
            }.sortedByDescending { it.episode_number }
        }
        val json = extractAnimeData(document) ?: return emptyList()
        val episodes = json.optJSONArray("Episode") ?: return emptyList()

        return (0 until episodes.length())
            .map { i ->
                val obj = episodes.getJSONObject(i)
                SEpisode.create().apply {
                    name = obj.optString("name")
                    episode_number = obj.optDouble("episodeNumber").toFloat()
                    url = "/episodio/${obj.optString("slug")}"
                }
            }
            .sortedByDescending { it.episode_number }
    }

    // Videos: Video list request
    override fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

    // Parse video list from episode page
    override fun videoListParse(response: Response): List<Video> {
        val html = response.bodyString()
        val document = Jsoup.parse(html, response.request.url.toString())
        val iframeUrls = document.select("div.play-box-iframe iframe[src]")
            .mapNotNull { it.attr("abs:src").takeIf(String::isNotBlank) }
        if (iframeUrls.isNotEmpty()) {
            val videos = iframeUrls.distinct().parallelCatchingFlatMapBlocking { url ->
                val resolved = when {
                    "blogger.com" in url -> runBlocking { bloggerExtractor.videosFromUrl(url, headers) }
                    "serv01.meusdoramas.club" in url -> MeusAnimesPlayerExtractor(client, headers)
                        .videosFromServ01(url)
                    else -> universalExtractor.videosFromUrl(url, headers, prefix = "Meus Animes")
                }
                resolved
            }
            return videos
        }
        val videoList = mutableListOf<Video>()

        // 1. Clean HTML: remove JSON escapes for cleaner URLs
        val cleanHtml = html.replace("\\/", "/")
            .replace("\\u0026", "&")

        val browserUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:146.0) Gecko/20100101 Firefox/146.0"
        val googleHeaders = headersBuilder()
            .set("User-Agent", browserUserAgent)
            .set("Referer", "https://youtube.googleapis.com/")
            .build()

        // 2. Enhanced regex to capture links anywhere in JSON
        val legRegex = """player_leg"\s*:\s*"([^"]+)""".toRegex()
        val dubRegex = """player_dub"\s*:\s*"([^"]+)""".toRegex()

        val legMatch = legRegex.find(cleanHtml)?.groupValues?.get(1)
        val dubMatch = dubRegex.find(cleanHtml)?.groupValues?.get(1)

        // Helper function to add videos from URL
        fun addVideos(url: String, prefix: String) {
            if (url.isEmpty() || !url.contains("blogger.com")) return

            runCatching {
                runBlocking { bloggerExtractor.videosFromUrl(url, googleHeaders) }
                    .forEach { video ->
                        videoList.add(
                            Video(
                                video.url,
                                "$prefix: ${video.quality}",
                                video.videoUrl,
                                googleHeaders,
                            ),
                        )
                    }
            }
        }

        // Process Legendado (subtitled) and Dublado (dubbed) streams
        legMatch?.let { addVideos(it, "Legendado") }
        dubMatch?.let { addVideos(it, "Dublado") }

        // 3. Fallback: if keys change, try to capture any loose blogger links
        if (videoList.isEmpty()) {
            val fallbackRegex = """https?://www\.blogger\.com/video\.g\?token=[a-zA-Z0-9_-]+""".toRegex()
            fallbackRegex.findAll(cleanHtml)
                .map { it.value }
                .distinct()
                .take(2)
                .forEach { addVideos(it, "Player") }
        }

        return videoList
    }

    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val bloggerExtractor by lazy { BloggerExtractor(client) }
}
