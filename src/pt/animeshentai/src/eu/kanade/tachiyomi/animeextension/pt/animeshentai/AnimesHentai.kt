package eu.kanade.tachiyomi.animeextension.pt.animeshentai

import aniyomi.lib.bloggerextractor.BloggerExtractor
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
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

@Source
abstract class AnimesHentai : ParsedAnimeHttpSource() {

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    companion object {
        const val PREFIX_PATH_SEARCH = "path:"
        private val EPISODE_NAME_REGEX = Regex("""(?i)\bep(?:is[oó]dio)?\.?\s*(\d+(?:[.,]\d+)?)""")
        private val EPISODE_URL_REGEX = Regex("""(?i)epis[oó]dio-(\d+(?:-\d+)?)""")
        private val TRAILING_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)\s*$""")
    }

    // ============================== Popular Anime ==============================
    override fun popularAnimeSelector(): String = "article.item.tvshows, article.item.movies"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/hentai/page/$page/", headers)

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("div.poster a, div.data h3 a, a[href]")!!
        val img = element.selectFirst("div.poster img, img")!!
        setUrlWithoutDomain(link.absUrl("href").ifEmpty { link.attr("href") })
        title = img.attr("alt").ifEmpty { link.text() }.trim()
        thumbnail_url = img.attr("src").ifEmpty { img.attr("data-src") }
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination a:contains(Proxima)"

    // ============================== Latest Updates =============================
    override fun latestUpdatesSelector(): String = "article.item.episodes, article.item.se.episodes"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/episodio/page/$page/", headers)

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== Search Anime ===============================
    override fun searchAnimeSelector(): String = "article.item.tvshows, article.item.movies, article.item.se"

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        if (query.startsWith(PREFIX_PATH_SEARCH)) {
            val path = query.removePrefix(PREFIX_PATH_SEARCH).trim()
            val fullUrl = if (path.startsWith("http")) path else "$baseUrl$path"
            val doc = client.newCall(GET(fullUrl, headers)).awaitSuccess().asJsoup()
            return AnimesPage(listOf(animeDetailsParse(doc)), false)
        }

        val httpUrl = query.trim().toHttpUrlOrNull()
        if (httpUrl != null && httpUrl.host.contains("animeshentai.biz")) {
            val doc = client.newCall(GET(httpUrl.toString(), headers)).awaitSuccess().asJsoup()
            return AnimesPage(listOf(animeDetailsParse(doc)), false)
        }

        return super.getSearchAnime(page, query, filters)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) {
                addPathSegment("search")
                addPathSegment(query.trim())
                if (page > 1) {
                    addPathSegment("page")
                    addPathSegment(page.toString())
                }
                addPathSegment("")
            } else {
                addPathSegment("hentai")
                if (page > 1) {
                    addPathSegment("page")
                    addPathSegment(page.toString())
                }
                addPathSegment("")
            }
        }.build()

        return GET(url.toString(), headers)
    }

    // ============================== Anime Details ==============================
    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealAnimeDoc(document)
        val sheader = doc.selectFirst("div.sheader") ?: doc

        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())

            sheader.selectFirst("div.poster img")?.let { img ->
                thumbnail_url = img.attr("src").ifEmpty { img.attr("data-src") }
                title = img.attr("alt").ifEmpty {
                    sheader.selectFirst("div.data h1")?.text() ?: ""
                }.replace("Todos os Episodios Online", "").trim()
            }

            if (title.isBlank()) {
                title = sheader.selectFirst("div.data h1")?.text()?.trim() ?: ""
            }

            genre = sheader.select("div.data div.sgeneros a")
                .eachText()
                .joinToString(", ")

            description = doc.selectFirst("div.wp-content p")?.text()?.trim()
            status = SAnime.COMPLETED
        }
    }

    private fun getRealAnimeDoc(document: Document): Document {
        val menu = document.selectFirst("div.pag_episodes div.item a[href*='/hentai/'], div.pag_episodes div.item a[href]:has(i.icon-bars-eps)")
        return if (menu != null) {
            val originalUrl = menu.absUrl("href").ifEmpty { menu.attr("href") }
            val fullUrl = if (originalUrl.startsWith("http")) originalUrl else "$baseUrl$originalUrl"
            client.newCall(GET(fullUrl, headers)).execute().asJsoup()
        } else {
            document
        }
    }

    // ============================== Seasons ====================================
    override fun seasonListSelector(): String = "div#seasons"

    override fun seasonFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun seasonListParse(response: Response): List<SAnime> = emptyList()

    // ============================== Episodes ===================================
    override fun episodeListSelector(): String = "div#seasons ul.episodios > li"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = getRealAnimeDoc(response.asJsoup())
        val episodes = document.select(episodeListSelector())
        return if (episodes.isEmpty()) {
            listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(document.location())
                    name = "Episódio 1"
                    episode_number = 1F
                },
            )
        } else {
            episodes.map { episodeFromElement(it) }.reversed()
        }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val titleLink = element.selectFirst("div.episodiotitle a[href]")
        val link = titleLink ?: element.selectFirst("a[href]")!!
        val epUrl = link.absUrl("href").ifEmpty { link.attr("href") }
        val rawTitle = titleLink?.text()?.trim().orEmpty()
            .ifBlank { link.text().trim() }
            .ifBlank { link.attr("title").trim() }

        val numberStr = EPISODE_NAME_REGEX.find(rawTitle)?.groupValues?.getOrNull(1)?.replace(',', '.')
            ?: EPISODE_URL_REGEX.find(epUrl)?.groupValues?.getOrNull(1)?.replace('-', '.')
            ?: TRAILING_NUMBER_REGEX.find(rawTitle)?.groupValues?.getOrNull(1)
        val epNumber = numberStr?.toFloatOrNull()

        val displayNumber = if (epNumber != null) {
            if (epNumber % 1f == 0f) epNumber.toInt().toString() else numberStr
        } else {
            null
        }

        return SEpisode.create().apply {
            setUrlWithoutDomain(epUrl)
            name = if (displayNumber != null) {
                "Episódio $displayNumber"
            } else {
                rawTitle.ifBlank { "Episódio 1" }
            }
            episode_number = epNumber ?: 1F
        }
    }

    // ============================== Hosters & Videos ===========================
    private val bloggerExtractor by lazy { BloggerExtractor(client) }

    override fun hosterListParse(response: Response): List<Hoster> {
        val document = response.asJsoup()
        val hosterList = mutableListOf<Hoster>()

        val optionLabels = document.select("ul.sourceslist li a").associate {
            val targetId = it.attr("href").removePrefix("#")
            val label = it.text().trim()
            targetId to label
        }

        document.select("div.playex div.play-box-iframe").forEach { box ->
            val boxId = box.id()
            val qualityPrefix = optionLabels[boxId] ?: "Player"
            val iframeSrc = box.selectFirst("iframe")?.attr("src")?.trim() ?: return@forEach

            if ("blogger.com" in iframeSrc) {
                hosterList.add(
                    Hoster(
                        hosterUrl = iframeSrc,
                        hosterName = "Blogger ($qualityPrefix)",
                        internalData = qualityPrefix,
                    ),
                )
            }
        }

        if (hosterList.isEmpty()) {
            document.select("div.playex iframe[src], iframe[src*='blogger.com']").forEachIndexed { index, iframe ->
                val src = iframe.attr("src").trim()
                if ("blogger.com" in src) {
                    val label = "Player ${index + 1}"
                    hosterList.add(
                        Hoster(
                            hosterUrl = src,
                            hosterName = "Blogger ($label)",
                            internalData = label,
                        ),
                    )
                }
            }
        }

        return hosterList
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = bloggerExtractor.videosFromUrl(hoster.hosterUrl, headers, hoster.internalData)

    override fun videoListParse(response: Response, hoster: Hoster): List<Video> = throw UnsupportedOperationException()

    override fun videoUrlParse(response: Response): String = response.request.url.toString()
}
