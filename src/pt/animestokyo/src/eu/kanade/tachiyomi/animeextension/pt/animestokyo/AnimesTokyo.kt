package eu.kanade.tachiyomi.animeextension.pt.animestokyo

import android.util.Base64
import aniyomi.lib.bloggerextractor.BloggerExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.toJsonRequestBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup

class AnimesTokyo : AnimeHttpSource() {

    override val name = "Animes Tokyo"
    override val baseUrl = "https://animes.tokyo"
    override val lang = "pt-BR"
    override val supportsLatest = true
    override val supportsRelatedAnimes = false

    override fun popularAnimeRequest(page: Int) = GET(baseUrl, headers)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/assistir/${if (page == 1) "" else "page/$page/"}", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("[data-tab-content=day] li").mapNotNull(::parseAnimeCard)
        return AnimesPage(animes, false)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val cards = doc.select(".grid-episode-auto > a[href*='/assistir/']").mapNotNull { card ->
            val episodeUrl = card.absUrl("href")
            val title = card.selectFirst("[data-nt-title]")?.text()
                ?.ifBlank { null }
                ?: card.selectFirst("[data-en-title]")?.text().orEmpty()
            if (episodeUrl.isBlank() || title.isBlank()) {
                null
            } else {
                LatestCard(title, card.selectFirst("img")?.absUrl("src"), episodeUrl)
            }
        }
        val animes = cards.parallelCatchingFlatMapBlocking { card ->
            client.newCall(GET(card.episodeUrl, headers)).execute().use { episodeResponse ->
                val animeUrl = episodeResponse.asJsoup()
                    .selectFirst(".episode-anime-info h4 a[href*='/anime/']")
                    ?.absUrl("href")
                    .orEmpty()
                if (animeUrl.isBlank()) {
                    emptyList()
                } else {
                    listOf(
                        SAnime.create().apply {
                            title = card.title
                            thumbnail_url = card.thumbnail
                            setUrlWithoutDomain(animeUrl)
                        },
                    )
                }
            }
        }.distinctBy { it.url }
        val currentPage = doc.selectFirst("ul.page-numbers .current")?.text()?.toIntOrNull() ?: 1
        val lastPage = doc.select("ul.page-numbers a.page-numbers")
            .mapNotNull { it.text().toIntOrNull() }
            .maxOrNull()
            ?: currentPage
        val hasNextPage = currentPage < lastPage
        return AnimesPage(animes, hasNextPage)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val body = """
            {"keyword":${jsonString(query)},"query":${jsonString(query)},"single":{"paged":$page,"orderby":"date","meta_key":null,"order":"desc"},"tax":[]}
        """.trimIndent()
        return searchRequest(body, page)
    }

    override fun searchAnimeParse(response: Response) = catalogParse(response)

    private fun searchRequest(body: String, page: Int): Request = POST(
        "$baseUrl/wp-json/kiranime/v1/anime/advancedsearch?_locale=user&page=$page",
        headers,
        body.toJsonRequestBody(),
    )

    private fun catalogParse(response: Response): AnimesPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val data = JSONObject(response.body.string())
        val html = Jsoup.parseBodyFragment(data.optString("data"))
        val animes = html.select("div.w-full:has(div.kira-anime)").mapNotNull { card ->
            val link = card.selectFirst("h3 a[href]") ?: return@mapNotNull null
            SAnime.create().apply {
                title = link.text().ifBlank { card.selectFirst("img")?.attr("alt").orEmpty() }
                if (title.isBlank()) return@mapNotNull null
                thumbnail_url = card.selectFirst("img")?.let { it.absUrl("src").ifBlank { it.attr("src") } }
                setUrlWithoutDomain(link.absUrl("href").ifBlank { link.attr("href") })
            }
        }.distinctBy { it.url }
        return AnimesPage(animes, page < data.optInt("pages"))
    }

    private fun parseAnimeCard(card: org.jsoup.nodes.Element): SAnime? {
        val link = card.selectFirst("h3 a[href*='/anime/']") ?: return null
        val title = link.selectFirst("[data-nt-title]")?.text()?.ifBlank { null } ?: link.text()
        if (title.isBlank()) return null
        return SAnime.create().apply {
            this.title = title
            thumbnail_url = card.selectFirst("img")?.absUrl("src")
            setUrlWithoutDomain(link.absUrl("href"))
        }
    }

    private data class LatestCard(val title: String, val thumbnail: String?, val episodeUrl: String)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1 .show.anime, h1 .anime, h1")?.text().orEmpty()
                .ifBlank { doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" – Animes Tokyo").orEmpty() }
            thumbnail_url = doc.selectFirst(".anime-featured img, meta[property=og:image]")?.let {
                it.absUrl("src").ifBlank { it.attr("src").ifBlank { it.attr("content") } }
            }
            description = doc.selectFirst(".anime-synopsis, .synopsis-content, .anime-description")?.text()
                ?.takeIf(String::isNotBlank)
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            genre = doc.select("span.leading-6 a[href*='/genre/']")
                .map { it.text() }
                .filter(String::isNotBlank)
                .distinct()
                .joinToString()
                .ifBlank { null }
            status = when {
                doc.text().contains("Concluído", true) -> SAnime.COMPLETED
                doc.text().contains("Em andamento", true) -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val links = doc.select(".swiper-episode-anime .swiper-slide > a[href*='/assistir/']")
        return links
            .mapNotNull { link ->
                val numberText = link.selectFirst(".w-percentile")?.text()
                    ?.takeIf(String::isNotBlank)
                    ?: Regex("""(?i)(?:episódio|ep)\.?\s*(\d+(?:[,.]\d+)?)""").find(link.text())?.value
                    ?: return@mapNotNull null
                val number = Regex("""\d+(?:[,.]\d+)?""").find(numberText)?.value
                    ?.replace(',', '.')
                    ?.toFloatOrNull()
                    ?: return@mapNotNull null
                val url = link.absUrl("href").ifBlank { link.attr("href") }
                if (url.isBlank()) return@mapNotNull null
                SEpisode.create().apply {
                    setUrlWithoutDomain(url)
                    name = numberText
                    episode_number = number
                }
            }
            .distinctBy { it.url }
            .sortedByDescending { it.episode_number }
    }

    private val bloggerExtractor by lazy { BloggerExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val sources = doc.select("[data-embed-id]").mapNotNull { element ->
            val encoded = element.attr("data-embed-id")
            val parts = encoded.split(':', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val label = decode(parts[0]) ?: "Servidor"
            val payload = decode(parts[1])
            PlayerSource(label, payload, element.text())
        }.distinctBy { it.label to it.payload }

        val playerHeaders = headers.newBuilder()
            .set("Referer", response.request.url.toString())
            .build()
        val preparedSources = sources.map(::prepareSource)
        val fastSources = preparedSources.filter { it.type == SourceType.BLOGGER }
        val slowSources = preparedSources.filter { it.type in setOf(SourceType.IFRAME, SourceType.URL) }
        val fastVideos = extractSources(fastSources, playerHeaders).distinctBy { it.videoUrl }
        if (fastVideos.isNotEmpty()) {
            return fastVideos
        }

        val slowVideos = extractSources(slowSources, playerHeaders).distinctBy { it.videoUrl }
        if (slowVideos.isEmpty() && isPlaceholder(preparedSources)) {
            throw Exception("Episódio ainda não liberado. Volte em breve!")
        }
        return slowVideos
    }

    private data class PlayerSource(val label: String, val payload: String?, val visibleText: String)

    private data class PreparedSource(
        val source: PlayerSource,
        val payload: String,
        val kind: PayloadKind,
        val embedUrl: String?,
        val type: SourceType,
    )

    private enum class PayloadKind { URL, IFRAME_HTML, HTML_OTHER, JSON, EMPTY, UNKNOWN }

    private enum class SourceType { BLOGGER, IFRAME, URL, OTHER }

    private fun prepareSource(source: PlayerSource): PreparedSource {
        val payload = source.payload?.let(::normalizePayload).orEmpty()
        val kind = payloadKind(payload)
        val embedUrl = extractEmbedUrl(payload, kind)
        val type = when {
            isBloggerUrl(embedUrl) -> SourceType.BLOGGER
            kind == PayloadKind.IFRAME_HTML -> SourceType.IFRAME
            kind == PayloadKind.URL -> SourceType.URL
            else -> SourceType.OTHER
        }
        return PreparedSource(source, payload, kind, embedUrl, type)
    }

    private fun isPlaceholder(sources: List<PreparedSource>): Boolean = sources.any {
        it.kind == PayloadKind.EMPTY && it.source.visibleText.contains("em breve", true)
    } || sources.any {
        it.kind == PayloadKind.HTML_OTHER && isImagePlaceholder(it.payload)
    } || sources.any {
        it.source.visibleText.contains("em breve", true) || it.payload.contains("episódio em breve", true)
    }

    private fun isImagePlaceholder(payload: String): Boolean {
        val image = Jsoup.parseBodyFragment(payload).selectFirst("img") ?: return false
        return listOf(image.attr("alt"), image.attr("title"), image.attr("src"))
            .any { it.contains("em breve", true) || it.contains("embreve", true) }
    }

    private fun extractSources(sources: List<PreparedSource>, playerHeaders: Headers): List<Video> = sources.parallelCatchingFlatMapBlocking { prepared ->
        val result = when (prepared.type) {
            SourceType.BLOGGER -> bloggerExtractor.videosFromUrl(prepared.embedUrl.orEmpty(), playerHeaders).map { video ->
                Video(video.url, "${prepared.source.label} - ${video.quality}", video.videoUrl, video.headers ?: Headers.headersOf())
            }
            SourceType.IFRAME,
            SourceType.URL,
            -> universalExtractor.videosFromUrl(prepared.embedUrl.orEmpty(), playerHeaders, prepared.source.label)
            SourceType.OTHER -> emptyList()
        }
        result
    }

    private fun normalizePayload(payload: String) = Jsoup.parseBodyFragment(payload).text().trim().ifBlank { payload.trim() }

    private fun payloadKind(payload: String): PayloadKind = when {
        payload.isBlank() -> PayloadKind.EMPTY
        Jsoup.parseBodyFragment(payload).selectFirst("iframe[src]") != null -> PayloadKind.IFRAME_HTML
        payload.startsWith("http://", true) || payload.startsWith("https://", true) || payload.startsWith("//") -> PayloadKind.URL
        payload.startsWith("{") || payload.startsWith("[") -> PayloadKind.JSON
        payload.startsWith("<") -> PayloadKind.HTML_OTHER
        else -> PayloadKind.UNKNOWN
    }

    private fun extractEmbedUrl(payload: String, kind: PayloadKind): String? = when (kind) {
        PayloadKind.IFRAME_HTML -> Jsoup.parseBodyFragment(payload).selectFirst("iframe[src]")?.attr("src")?.let(::normalizeUrl)
        PayloadKind.URL -> normalizeUrl(payload)
        else -> null
    }

    private fun normalizeUrl(url: String) = if (url.startsWith("//")) "https:$url" else url

    private fun isBloggerUrl(url: String?) = url?.contains("blogger.com/video", true) == true

    private fun decode(value: String): String? = runCatching {
        String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()

    private fun jsonString(value: String) = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }
}
