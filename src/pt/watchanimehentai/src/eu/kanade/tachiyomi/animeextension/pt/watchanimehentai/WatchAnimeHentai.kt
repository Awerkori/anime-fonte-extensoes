package eu.kanade.tachiyomi.animeextension.pt.watchanimehentai

import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

@Source
abstract class WatchAnimeHentai : ParsedAnimeHttpSource() {

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    companion object {
        const val PREFIX_PATH_SEARCH = "path:"
        private val EPISODE_NAME_REGEX = Regex("""(?i)epis[oó]dio\s*(\d+(?:\.\d+)?)""")
        private val EPISODE_URL_REGEX = Regex("""(?i)epis[oó]dio-(\d+(?:\.\d+)?)""")
        private val NUMBER_FALLBACK_REGEX = Regex("""(\d+(?:\.\d+)?)""")
    }

    // ============================== Popular Anime ==============================
    override fun popularAnimeSelector(): String = "article.item.tvshows, article.item"

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page <= 1) {
            "$baseUrl/pt/lista-de-hentai/"
        } else {
            "$baseUrl/pt/lista-de-hentai/$page/"
        }
        return GET(url, headers)
    }

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("div.poster a, div.data h3 a, a")!!
        val img = element.selectFirst("div.poster img, img")!!
        setUrlWithoutDomain(link.absUrl("href").ifEmpty { link.attr("href") })
        title = img.attr("alt").ifEmpty { img.attr("title") }.ifEmpty { link.text() }.trim()
        thumbnail_url = img.attr("src").ifEmpty { img.attr("data-src") }
    }

    override fun popularAnimeNextPageSelector(): String = "div.paginacao a:contains(»)"

    // ============================== Latest Updates =============================
    override fun latestUpdatesSelector(): String = "article.item.se.episodes, article.item"

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page <= 1) {
            "$baseUrl/pt/ultimos-episodios-adicionados/"
        } else {
            "$baseUrl/pt/ultimos-episodios-adicionados/$page/"
        }
        return GET(url, headers)
    }

    override fun latestUpdatesFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a[href]")!!
        val img = element.selectFirst("div.poster img, img")!!
        setUrlWithoutDomain(link.absUrl("href").ifEmpty { link.attr("href") })
        title = img.attr("alt").ifEmpty { img.attr("title") }.ifEmpty { link.text() }.trim()
        thumbnail_url = img.attr("src").ifEmpty { img.attr("data-src") }
    }

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== Search Anime ===============================
    override fun searchAnimeSelector(): String = "article.search-result, article.item.tvshows, article.item"

    override fun searchAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("h2.search-result-title a, a.search-result-poster, div.poster a, div.data h3 a, a")!!
        val img = element.selectFirst("img")!!
        setUrlWithoutDomain(link.absUrl("href").ifEmpty { link.attr("href") })
        title = img.attr("alt").ifEmpty { img.attr("title") }.ifEmpty { link.text() }.trim()
        thumbnail_url = img.attr("src").ifEmpty { img.attr("data-src") }
    }

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
        if (httpUrl != null && httpUrl.host.contains("watchanimehentai.com")) {
            val doc = client.newCall(GET(httpUrl.toString(), headers)).awaitSuccess().asJsoup()
            return AnimesPage(listOf(animeDetailsParse(doc)), false)
        }

        return super.getSearchAnime(page, query, filters)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val cleanQuery = query.trim().replace(" ", "+")
            val url = if (page <= 1) {
                "$baseUrl/pt/busca/$cleanQuery/"
            } else {
                "$baseUrl/pt/busca/$cleanQuery/?pagination=$page"
            }
            return GET(url, headers)
        }

        val genre = filters.filterIsInstance<GeneroFilter>().firstOrNull()?.toSlug() ?: ""
        if (genre.isNotBlank()) {
            val url = if (page <= 1) {
                "$baseUrl/pt/genero/$genre/"
            } else {
                "$baseUrl/pt/genero/$genre/$page/"
            }
            return GET(url, headers)
        }

        return popularAnimeRequest(page)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(GeneroFilter())

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

            genre = sheader.select("div.data div.sgeneros a, div.sgeneros a")
                .eachText()
                .distinct()
                .joinToString(", ")

            description = doc.selectFirst("div.wp-content p")?.text()?.trim()

            val studio = doc.selectFirst("div.custom_fields:has(b:contains(Produtora)) div.sgeneros a")?.text()?.trim()
            if (!studio.isNullOrBlank()) {
                author = studio
                artist = studio
            }

            status = SAnime.COMPLETED
        }
    }

    private fun getRealAnimeDoc(document: Document): Document {
        val menu = document.selectFirst("div.pag_episodes div.item a[href*='/info/']")
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
    override fun episodeListSelector(): String = "article.item.se.episodes, div#seasons ul.episodios > li"

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
        val link = element.selectFirst("a[href]")!!
        val epUrl = link.absUrl("href").ifEmpty { link.attr("href") }
        val epTitle = element.selectFirst("span.c, div.data h3, div.episodiotitle a, a")?.text()?.trim()
            ?.takeIf(String::isNotBlank)
            ?: "Episódio"
        val epNumber = EPISODE_NAME_REGEX.find(epTitle)?.groupValues?.getOrNull(1)?.toFloatOrNull()
            ?: EPISODE_URL_REGEX.find(epUrl)?.groupValues?.getOrNull(1)?.toFloatOrNull()
            ?: NUMBER_FALLBACK_REGEX.find(epTitle)?.value?.toFloatOrNull()
            ?: 1F

        return SEpisode.create().apply {
            setUrlWithoutDomain(epUrl)
            name = epTitle
            episode_number = epNumber
        }
    }

    // ============================== Hosters & Videos ===========================
    private val bloggerExtractor by lazy { BloggerExtractor(client) }

    override fun hosterListParse(response: Response): List<Hoster> {
        val document = response.asJsoup()
        val hosters = mutableListOf<Hoster>()

        val optionLabels = document.select("ul.sourceslist li a").associate {
            val targetId = it.attr("href").removePrefix("#")
            val label = it.text().trim()
            targetId to label
        }

        document.select("div.playex div.play-box-iframe").forEach { box ->
            val boxId = box.id()
            val label = optionLabels[boxId] ?: "Player"
            val iframe = box.selectFirst("iframe") ?: return@forEach
            val iframeSrc = iframe.attr("src").trim()
            val dataInfo = iframe.attr("data-infoplayer").trim()
            val displayName = if (dataInfo.isNotBlank()) dataInfo else label

            if (iframeSrc.contains("padrao=") || iframeSrc.contains("/players/p2/")) {
                val token = iframeSrc.substringAfter("padrao=", "")
                if (token.isNotBlank()) {
                    hosters.add(
                        Hoster(
                            hosterUrl = "$baseUrl/players/p2/p2.php?id=$token",
                            hosterName = "Watch Anime Hentai - $displayName",
                            internalData = "p2|$displayName",
                        ),
                    )
                }
            } else if (iframeSrc.contains("hentaitube.online") || iframeSrc.contains("blogger.com")) {
                hosters.add(
                    Hoster(
                        hosterUrl = iframeSrc,
                        hosterName = "Blogger ($displayName)",
                        internalData = "blogger|$displayName",
                    ),
                )
            }
        }

        if (hosters.isEmpty()) {
            document.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src").trim()
                if (src.contains("padrao=")) {
                    val token = src.substringAfter("padrao=", "")
                    if (token.isNotBlank()) {
                        hosters.add(
                            Hoster(
                                hosterUrl = "$baseUrl/players/p2/p2.php?id=$token",
                                hosterName = "Watch Anime Hentai",
                                internalData = "p2|",
                            ),
                        )
                    }
                } else if (src.contains("hentaitube.online") || src.contains("blogger.com")) {
                    hosters.add(
                        Hoster(
                            hosterUrl = src,
                            hosterName = "Blogger",
                            internalData = "blogger|",
                        ),
                    )
                }
            }
        }

        return hosters
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val type = hoster.internalData.substringBefore('|')
        val label = hoster.internalData.substringAfter('|', "")
        return when (type) {
            "p2" -> {
                val videoHeaders = headersBuilder().set("Referer", "$baseUrl/").build()
                val finalUrl = resolveRedirectUrl(hoster.hosterUrl, videoHeaders)
                val title = if (label.isNotBlank()) {
                    "Watch Anime Hentai ($label - MP4)"
                } else {
                    "Watch Anime Hentai (MP4)"
                }
                listOf(Video(videoUrl = finalUrl, videoTitle = title, headers = videoHeaders))
            }
            "blogger" -> extractBloggerVideos(hoster.hosterUrl, label)
            else -> hoster.videoList.orEmpty()
        }
    }

    private suspend fun resolveRedirectUrl(url: String, requestHeaders: Headers): String = runCatching {
        val headReq = Request.Builder()
            .url(url)
            .head()
            .headers(requestHeaders)
            .build()
        val noRedirectClient = client.newBuilder().followRedirects(false).build()
        noRedirectClient.newCall(headReq).await().use { headRes ->
            headRes.header("Location")?.takeIf(String::isNotBlank) ?: url
        }
    }.getOrElse { url }

    private suspend fun extractBloggerVideos(url: String, prefix: String): List<Video> = runCatching {
        val targetUrl = if (url.contains("hentaitube.online")) {
            val res = client.newCall(GET(url, headersBuilder().set("Referer", "$baseUrl/").build())).awaitSuccess()
            val doc = res.asJsoup()
            doc.selectFirst("iframe[src*=blogger.com]")?.attr("src") ?: url
        } else {
            url
        }
        if (targetUrl.contains("blogger.com")) {
            bloggerExtractor.videosFromUrl(targetUrl, headers, prefix)
        } else {
            emptyList()
        }
    }.getOrElse { emptyList() }

    override fun videoListParse(response: Response, hoster: Hoster): List<Video> = throw UnsupportedOperationException()

    override fun videoUrlParse(response: Response): String = response.request.url.toString()
}
