package eu.kanade.tachiyomi.animeextension.pt.muitohentai

import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.tryParse
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class MuitoHentai : AnimeHttpLegacySource() {
    override val name = "Muito Hentai"

    override val baseUrl = "https://www.muitohentai.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/ranking-hentais/?paginacao=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("ul.ul_sidebar > li").map { element ->
            SAnime.create().apply {
                thumbnail_url = element.selectFirst("div.zeroleft > a > img")?.attr("abs:src")
                element.selectFirst("div.lefthentais > div > b:gt(0) > a.series")!!.run {
                    setUrlWithoutDomain(attr("href"))
                    title = text()
                }
            }
        }
        val hasNextPage = document.selectFirst("div.paginacao > a:contains(»)") != null
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("div.animation-2 > article:contains(Episódio)").map { element ->
            SAnime.create().apply {
                val slug = element.selectFirst("a")!!.attr("href")
                    .substringAfter("/episodios/")
                    .substringBefore("-episodio")
                url = "/info/$slug/"
                val img = element.selectFirst("img")!!
                title = img.attr("alt")
                thumbnail_url = img.attr("abs:src")
            }
        }
        return AnimesPage(animes, false)
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/buscar/${query.replace(" ", "+")}", headers)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("div#archive-content > article > div.poster").map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
                val img = element.selectFirst("img")!!
                title = img.attr("alt")
                thumbnail_url = img.attr("abs:src")
            }
        }
        return AnimesPage(animes, false)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            setUrlWithoutDomain(document.location())
            val data = document.selectFirst("div.sheader > div.data")!!
            title = data.selectFirst("h1")!!.text()
            genre = data.selectFirst("div.sgeneros")?.children()
                ?.filterNot { it.text().contains(title) }
                ?.joinToString { it.text() }
            description = data.selectFirst("div#info1 > div.wp-content > p")?.text()
            thumbnail_url = document.selectFirst("div.sheader > div.poster > img")?.attr("abs:src")
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select("article.item").map { element ->
            SEpisode.create().apply {
                val data = element.selectFirst("div.data")!!
                setUrlWithoutDomain(element.selectFirst("div.poster > div.season_m > a")!!.attr("href"))
                name = data.selectFirst("h3")!!.text().trim()
                date_upload = data.selectFirst("span")?.text()?.let(DATE_FORMATTER::tryParse) ?: 0L
            }
        }.reversed()
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        document.select("div.playex > div.play-box-iframe > iframe").forEach { iframe ->
            val url = iframe.attr("abs:src")

            if (url.contains("/players/p2/")) {
                client.newCall(GET(url, headers)).execute().use { iframeResponse ->
                    val iframeDoc = iframeResponse.asJsoup()
                    val sourceSrc = iframeDoc.selectFirst("source")?.attr("abs:src")
                    if (sourceSrc != null) {
                        val videoHeaders = headersBuilder()
                            .set("Referer", url)
                            .set("Cookie", "ageVerified=true")
                            .set("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
                            .set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
                            .build()
                        videoList.add(Video(sourceSrc, "Alternativo", sourceSrc, videoHeaders))
                    }
                }
            } else if (url.contains("hentaitube.online")) {
                client.newCall(GET(url, headers)).execute().use { iframeResponse ->
                    val iframeDoc = iframeResponse.asJsoup()
                    val bloggerIframe = iframeDoc.selectFirst("iframe[src*=\"blogger.com\"]")
                    if (bloggerIframe != null) {
                        val bloggerUrl = bloggerIframe.attr("abs:src")
                        runCatching {
                            runBlocking {
                                videoList.addAll(BloggerExtractor(client).videosFromUrl(bloggerUrl, headers))
                            }
                        }
                    }
                }
            }
        }

        return videoList
    }

    // ============================= Utilities ==============================
    companion object {
        private val DATE_FORMATTER = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    }
}
