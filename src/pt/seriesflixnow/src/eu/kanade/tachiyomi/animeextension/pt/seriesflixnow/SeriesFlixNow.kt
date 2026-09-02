package eu.kanade.tachiyomi.animeextension.pt.seriesflixnow

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class SeriesFlixNow : ParsedAnimeHttpLegacySource() {
    override val name = "SeriesFlixNow"
    override val baseUrl = "https://www.seriesflixnow.com"
    override val lang = "pt-BR"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    override fun popularAnimeRequest(page: Int): Request = GET(if (page == 1) "$baseUrl/series-online" else "$baseUrl/series-online/pagina/$page", headers)

    override fun popularAnimeSelector() = "a.serie-card[href]"
    override fun popularAnimeFromElement(element: Element) = animeFromCard(element)
    override fun popularAnimeNextPageSelector() = "link[rel=next], a[href*='/series-online/pagina/']"

    override fun latestUpdatesRequest(page: Int): Request = GET(if (page == 1) "$baseUrl/episodios-recentes" else "$baseUrl/episodios-recentes/pagina/$page", headers)

    override fun latestUpdatesSelector() = "a.serie-card[href], a[href^='/serie/'][href*='/temporada-']"
    override fun latestUpdatesFromElement(element: Element) = animeFromCard(element)
    override fun latestUpdatesNextPageSelector() = "link[rel=next], a[href*='/episodios-recentes/pagina/']"

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val seen = HashSet<String>()
        val animes = document.select(latestUpdatesSelector()).mapNotNull { card ->
            val href = card.attr("abs:href")
            val parent = if ("/temporada-" in href) {
                runCatching {
                    client.newCall(GET(href, headers)).execute().useAsJsoup()
                        .selectFirst("nav a[href^='/serie/'][href$='-online']")
                }.getOrNull()
            } else {
                null
            }
            val seriesUrl = parent?.attr("abs:href") ?: href
            seriesUrl.takeIf { it.isNotBlank() && seen.add(it) }?.let {
                animeFromCard(card).apply {
                    setUrlWithoutDomain(it)
                    parent?.text()?.trim()?.takeIf(String::isNotBlank)?.let { title = it }
                }
            }
        }
        return AnimesPage(animes, document.selectFirst(latestUpdatesNextPageSelector()) != null)
    }

    private fun animeFromCard(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        title = element.selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: element.selectFirst("h3,h2,.title")?.text()?.trim().orEmpty()
        thumbnail_url = element.selectFirst("img")?.attr("abs:src")
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: eu.kanade.tachiyomi.animesource.model.AnimeFilterList): Request = GET(baseUrl + "/buscar?q=" + java.net.URLEncoder.encode(query, "UTF-8") + "&pagina=$page", headers)
    override fun searchAnimeSelector() = "a.card[href]"
    override fun searchAnimeFromElement(element: Element) = animeFromCard(element)
    override fun searchAnimeNextPageSelector() = "link[rel=next], a[href*='pagina=']"

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        title = document.selectFirst(".filme-meta h1")?.text()?.substringBefore(" – Assistir")
            ?: document.selectFirst("h1")?.text().orEmpty()
        thumbnail_url = document.selectFirst("img.filme-poster")?.attr("abs:src")
        genre = document.select(".filme-meta .info-item a").eachText().joinToString()
        description = document.selectFirst(".sinopse, .descricao, .filme-description")?.text().orEmpty()
    }

    override fun episodeListRequest(anime: SAnime) = GET(baseUrl + anime.url, headers)
    override fun episodeListSelector() = ".temporada a.episodio-link[href]"
    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        name = element.selectFirst("h5")?.text()?.trim() ?: element.attr("title")
        episode_number = Regex("(?:temporada-|episodio-)(\\d+)").findAll(url).lastOrNull()?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        scanlator = element.selectFirst(".episodio-info p")?.text()?.trim()
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.useAsJsoup()
        Log.d(DEBUG_TAG, "episode=${response.request.url} channels=${doc.select("button.btn-canal[data-url]").size}")
        val result = mutableListOf<Video>()
        doc.select("button.btn-canal[data-url]").forEach { button ->
            val url = button.attr("data-url").trim()
            if (!url.startsWith("http")) return@forEach
            val parent = button.parent()?.id().orEmpty()
            val label = if (parent.contains("dublado")) "SeriesFlixNow - Dublado" else "SeriesFlixNow - Legendado"
            Log.d(DEBUG_TAG, "player=$label url=$url")
            if (url.contains("vidsrc-embed", true)) {
                val videos = runCatching {
                    playerExtractor.videosFromUrl(url, headers, label)
                }.getOrElse {
                    Log.d(DEBUG_TAG, "vidsrc failure=${it.javaClass.simpleName}:${it.message}")
                    emptyList()
                }
                Log.d(DEBUG_TAG, "vidsrc result=${videos.size}")
                result += videos
            } else {
                val videos = runCatching { playerExtractor.videosFromUrl(url, headers, label) }.getOrDefault(emptyList())
                Log.d(DEBUG_TAG, "universal result=${videos.size}")
                result += videos
            }
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

    private fun resolvePleno(url: String, label: String): Video? {
        val page = client.newCall(GET(url, headers)).execute().useAsJsoup()
        val id = Regex("DIRECT_EPISODE_ID\\s*=\\s*(\\d+)").find(page.html())?.groupValues?.get(1) ?: return null
        val body = FormBody.Builder().add("action", "getOptions").add("contentid", id).build()
        val options = client.newCall(POST("https://plenoflu.com/api", headers, body)).execute().body?.string().orEmpty()
        val videoId = Regex("\\\"ID\\\"\\s*:\\s*(\\d+)").find(options)?.groupValues?.get(1) ?: return null
        val playerBody = FormBody.Builder().add("action", "getPlayer").add("video_id", videoId).build()
        val player = client.newCall(POST("https://plenoflu.com/api", headers, playerBody)).execute().body?.string().orEmpty()
        val encoded = Regex("\\\"video_url\\\"\\s*:\\s*\\\"([^\"]+)").find(player)?.groupValues?.get(1) ?: return null
        val media = runCatching { String(Base64.decode(encoded, Base64.DEFAULT)) }.getOrNull() ?: return null
        return if (media.substringBefore('?').endsWith(".mp4", true) || media.substringBefore('?').endsWith(".m3u8", true)) Video(media, label, media, headers) else null
    }

    private companion object {
        const val DEBUG_TAG = "SERIESFLIX_VIDEO_DEBUG"
    }
}
