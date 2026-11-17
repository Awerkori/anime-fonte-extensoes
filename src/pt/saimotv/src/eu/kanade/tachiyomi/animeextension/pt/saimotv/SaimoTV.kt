package eu.kanade.tachiyomi.animeextension.pt.saimotv

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

@Source
abstract class SaimoTV : ParsedAnimeHttpSource() {

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    companion object {
        const val PREFIX_PATH_SEARCH = "path:"
    }

    // ============================== Popular Anime ==============================
    override fun popularAnimeSelector(): String = "div.poster-grid a.poster-card, a.poster-card"

    override fun popularAnimeRequest(page: Int): Request = if (page == 1) {
        GET("$baseUrl/", headers)
    } else {
        GET("$baseUrl/filmes/pagina/$page", headers)
    }

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.attr("href")
        setUrlWithoutDomain(link)

        val img = element.selectFirst("span.poster img, img")
        val titleEl = element.selectFirst("p.poster-title")

        val rawTitle = titleEl?.text()?.ifBlank { null }
            ?: img?.attr("alt")?.removePrefix("Pôster de ")
            ?: ""
        title = rawTitle.trim()

        thumbnail_url = img?.attr("src")?.ifEmpty { img.attr("data-src") }
    }

    override fun popularAnimeNextPageSelector(): String = "nav.pager a[rel=next], section.hero"

    // ============================== Latest Updates =============================
    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesRequest(page: Int): Request = if (page == 1) {
        GET("$baseUrl/filmes", headers)
    } else {
        GET("$baseUrl/filmes/pagina/$page", headers)
    }

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = "nav.pager a[rel=next]"

    // ============================== Search Anime ===============================
    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = "nav.pager a[rel=next]"

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TipoFilter(),
        GeneroFilter(),
    )

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        val trimmed = query.trim()
        val url = trimmed.toHttpUrlOrNull()
        if (url != null) {
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            return client.newCall(GET(trimmed, headers))
                .awaitSuccess()
                .use { response ->
                    val anime = animeDetailsParse(response.asJsoup())
                    AnimesPage(listOf(anime), false)
                }
        }
        if (trimmed.startsWith(PREFIX_PATH_SEARCH)) {
            val path = trimmed.removePrefix(PREFIX_PATH_SEARCH)
            return client.newCall(GET(baseUrl + path, headers))
                .awaitSuccess()
                .use { response ->
                    val anime = animeDetailsParse(response.asJsoup())
                    AnimesPage(listOf(anime), false)
                }
        }
        return super.getSearchAnime(page, query, filters)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val trimmed = query.trim()
            if (trimmed.startsWith("/")) {
                return GET(baseUrl + trimmed, headers)
            }
        }

        var tipo = "filmes"
        var genero = ""

        filters.forEach { filter ->
            when (filter) {
                is TipoFilter -> tipo = filter.toPath()
                is GeneroFilter -> genero = filter.toSlug()
                else -> {}
            }
        }

        val url = buildString {
            append(baseUrl)
            append("/")
            append(tipo)
            if (genero.isNotBlank()) {
                append("/genero/")
                append(genero)
            }
            if (page > 1) {
                append("/pagina/")
                append(page)
            }
        }

        return GET(url, headers)
    }

    // ============================== Anime Details ==============================
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(document.location())

        var parsedJsonLd = false
        val jsonLd = document.selectFirst("script[type=application/ld+json]")?.data()
        if (!jsonLd.isNullOrBlank()) {
            try {
                val json = Json.parseToJsonElement(jsonLd).jsonObject
                val name = json["name"]?.jsonPrimitive?.content
                if (!name.isNullOrBlank()) {
                    title = name.trim()
                    thumbnail_url = json["image"]?.jsonPrimitive?.content
                    description = json["description"]?.jsonPrimitive?.content
                    genre = json["genre"]?.jsonArray?.joinToString(", ") { it.jsonPrimitive.content }
                    status = SAnime.COMPLETED
                    parsedJsonLd = true
                }
            } catch (_: Exception) {}
        }

        if (!parsedJsonLd) {
            val pageTitle = document.selectFirst("h1.page-title")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: ""
            title = pageTitle.trim()

            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("span.poster img, div.hero-poster img")?.attr("src")

            description = document.selectFirst("p.detail-text")?.text()?.trim()
                ?: document.selectFirst("meta[name=description]")?.attr("content")

            genre = document.select("p.detail-tags a.tag").eachText().joinToString(", ")
            status = SAnime.COMPLETED
        }

        document.selectFirst("dl.detail-credits dd")?.let {
            val credits = it.text().trim()
            author = credits
            artist = credits
        }
    }

    // ============================== Seasons ====================================
    override fun seasonListSelector(): String = "div.season"

    override fun seasonFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun seasonListParse(response: Response): List<SAnime> = emptyList()

    // ============================== Episodes ===================================
    override fun episodeListSelector(): String = "div.ep-grid a.ep"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val url = response.request.url.toString()

        if (url.contains("/filme/")) {
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(url)
                    name = "Filme Completo"
                    episode_number = 1F
                },
            )
        }

        val episodes = mutableListOf<SEpisode>()
        val seasons = document.select("div.season")

        if (seasons.isEmpty()) {
            document.select("a.ep").forEachIndexed { index, epEl ->
                val epHref = epEl.attr("href")
                if (epHref.isNotBlank()) {
                    val epText = epEl.selectFirst("span.ep-num")?.text()?.replace("<!-- -->", "")?.trim() ?: "E${index + 1}"
                    val verText = epEl.selectFirst("span.ep-ver")?.text()?.trim() ?: ""
                    val epNumber = Regex("""\d+""").find(epText)?.value?.toFloatOrNull() ?: (index + 1).toFloat()
                    val epName = if (verText.isNotBlank()) "$epText [$verText]" else epText

                    episodes.add(
                        SEpisode.create().apply {
                            setUrlWithoutDomain(epHref)
                            name = epName
                            episode_number = epNumber
                        },
                    )
                }
            }
        } else {
            var globalCounter = 1
            seasons.forEach { seasonEl ->
                val seasonTitle = seasonEl.selectFirst("h3.season-title")?.text()?.replace("<!-- -->", "")?.trim() ?: "Temporada"
                val seasonNum = Regex("""\d+""").find(seasonTitle)?.value?.toIntOrNull() ?: 1

                seasonEl.select("div.ep-grid a.ep").forEach { epEl ->
                    val epHref = epEl.attr("href")
                    if (epHref.isNotBlank()) {
                        val epText = epEl.selectFirst("span.ep-num")?.text()?.replace("<!-- -->", "")?.trim() ?: "E$globalCounter"
                        val verText = epEl.selectFirst("span.ep-ver")?.text()?.trim() ?: ""
                        val epNum = Regex("""\d+""").find(epText)?.value?.toIntOrNull() ?: globalCounter

                        val epName = buildString {
                            append(seasonTitle)
                            append(" Episódio ")
                            append(epNum)
                            if (verText.isNotBlank()) {
                                append(" [")
                                append(verText)
                                append("]")
                            }
                        }

                        episodes.add(
                            SEpisode.create().apply {
                                setUrlWithoutDomain(epHref)
                                name = epName
                                episode_number = (seasonNum * 1000 + epNum).toFloat()
                            },
                        )
                        globalCounter++
                    }
                }
            }
        }

        return episodes.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val link = element.attr("href")
        val epText = element.selectFirst("span.ep-num")?.text()?.replace("<!-- -->", "")?.trim() ?: "Episódio"
        val verText = element.selectFirst("span.ep-ver")?.text()?.trim() ?: ""
        val epNumber = Regex("""\d+""").find(epText)?.value?.toFloatOrNull() ?: 1F

        return SEpisode.create().apply {
            setUrlWithoutDomain(link)
            name = if (verText.isNotBlank()) "$epText [$verText]" else epText
            episode_number = epNumber
        }
    }

    // ============================== Hosters & Videos ===========================
    override fun hosterListParse(response: Response): List<Hoster> {
        val body = response.body.string()
        val hosters = mutableListOf<Hoster>()

        val dubUrls = extractStreamUrls(body, "dub")
        val legUrls = extractStreamUrls(body, "leg")

        dubUrls.forEachIndexed { i, streamUrl ->
            val serverNum = i + 1
            val formatTag = if (streamUrl.contains(".mp4")) {
                "MP4"
            } else if (streamUrl.contains("master.txt") || streamUrl.contains(".m3u8")) {
                "HLS"
            } else {
                "Stream"
            }
            val label = "Saimo TV - [Dublado] Servidor $serverNum"
            val video = Video(videoUrl = streamUrl, videoTitle = "$label ($formatTag)", headers = headers)
            hosters.add(
                Hoster(
                    hosterUrl = streamUrl,
                    hosterName = label,
                    videoList = listOf(video),
                    internalData = "",
                    lazy = false,
                ),
            )
        }

        legUrls.forEachIndexed { i, streamUrl ->
            val serverNum = i + 1
            val formatTag = if (streamUrl.contains(".mp4")) {
                "MP4"
            } else if (streamUrl.contains("master.txt") || streamUrl.contains(".m3u8")) {
                "HLS"
            } else {
                "Stream"
            }
            val label = "Saimo TV - [Legendado] Servidor $serverNum"
            val video = Video(videoUrl = streamUrl, videoTitle = "$label ($formatTag)", headers = headers)
            hosters.add(
                Hoster(
                    hosterUrl = streamUrl,
                    hosterName = label,
                    videoList = listOf(video),
                    internalData = "",
                    lazy = false,
                ),
            )
        }

        if (hosters.isEmpty()) {
            val document = Jsoup.parse(body, response.request.url.toString())
            document.select("div.vod-controls a[href*='.mp4'], div.seg a.ext").forEachIndexed { i, el ->
                val href = el.attr("href").trim()
                if (href.startsWith("http")) {
                    val label = "Saimo TV - Servidor ${i + 1}"
                    val video = Video(videoUrl = href, videoTitle = "$label (MP4)", headers = headers)
                    hosters.add(
                        Hoster(
                            hosterUrl = href,
                            hosterName = label,
                            videoList = listOf(video),
                            internalData = "",
                            lazy = false,
                        ),
                    )
                }
            }
        }

        return hosters
    }

    private fun extractStreamUrls(html: String, lang: String): List<String> {
        val pattern = Regex("""\\?"$lang\\?":\s*\[(.*?)\]""")
        val match = pattern.find(html) ?: return emptyList()
        val rawList = match.groupValues[1]
        val urlPattern = Regex("""https?://[^\\",\s]+""")
        return urlPattern.findAll(rawList).map { it.value }.toList()
    }

    override fun videoListParse(response: Response, hoster: Hoster): List<Video> = hoster.videoList ?: emptyList()

    override fun videoUrlParse(response: Response): String = response.request.url.toString()
}
