package eu.kanade.tachiyomi.animeextension.pt.animeplayer

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimePlayer :
    DooPlay(
        "pt-BR",
        "AnimePlayer",
        "https://animeplayer.com.br",
    ) {

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div#archive-content article div.poster"

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/animes/", headers)

    override fun popularAnimeNextPageSelector() = "a > i#nextpagination"

    // =============================== Latest ===============================
    override val latestUpdatesPath = "episodios"

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val doc = getRealAnimeDoc(document)
        val content = doc.selectFirst("div#contenedor > div.data")!!

        title = content.selectFirst("div.data > h1")!!.text()
        thumbnail_url = doc.selectFirst("div.sheader div.poster > img")!!.getImageUrl()
        genre = content.select("div.sgeneros > a")
            .eachText()
            .joinToString()
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response) = super.episodeListParse(response).reversed()

    override fun getSeasonEpisodes(season: Element): List<SEpisode> {
        val seasonName = season.selectFirst("span.title")!!.text()
        return season.select(episodeListSelector()).map { element ->
            episodeFromElement(element, seasonName)
        }
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element, seasonName: String) = SEpisode.create().apply {
        val epNum = element.selectFirst("div.episodiotitle p")!!.text()
            .let(episodeNumberRegex::find)!!
            .groupValues
            .last()
        val href = element.selectFirst("a[href]")!!

        episode_number = epNum.toFloat()
        name = "$seasonName x Episodio $epNum"
        setUrlWithoutDomain(href.absUrl("href"))
    }

    // ============================ Video Links =============================
    override val prefQualityDefault = "FHD"
    override val prefQualityValues = arrayOf("FHD", "720p", "360p")
    override val prefQualityEntries = prefQualityValues

    override fun List<Video>.sortVideos(): List<Video> = this

    override suspend fun getVideoList(episode: SEpisode): List<Video> = client.newCall(videoListRequest(episode)).awaitSuccess().use { response ->
        val document = response.asJsoup()
        val quality = document.selectFirst("span.qualityx")!!.text()
        val videos = mutableListOf<Video>()

        for ((index, element) in document.select(".player-placeholder[data-src]").withIndex()) {
            val playerUrl = element.absUrl("data-src")
            val videoUrl = webViewExtractor.extract(playerUrl) ?: continue
            videos += videoFromPlayer(
                playerUrl = playerUrl,
                title = "Player ${index + 1} - $quality",
                videoUrl = videoUrl,
            )
        }

        videos
    }

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    private val webViewExtractor by lazy { WebViewVideoExtractor(headers.withReferer("$baseUrl/")) }

    private fun videoFromPlayer(playerUrl: String, title: String, videoUrl: String) = Video(
        url = playerUrl,
        quality = title,
        videoUrl = videoUrl,
        headers = headers.withReferer(playerUrl.toRootUrl()),
    )

    private fun String.toRootUrl() = toHttpUrl().newBuilder()
        .encodedPath("/")
        .query(null)
        .fragment(null)
        .build()
        .toString()

    private fun Headers.withReferer(referer: String) = newBuilder()
        .set("Referer", referer)
        .build()

    // ============================== Filters ===============================
    override fun genresListSelector() = "ul.genres a"

    // ============================= Utilities ==============================
    override val animeMenuSelector = "div.pag_episodes div.item a[href] i.icon-bars"
}
