package eu.kanade.tachiyomi.animeextension.pt.animexhd

import android.util.Log
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class AnimeXHD : DooPlay("pt-BR", "AnimeXHD", "https://animexhd.xyz") {
    override val fetchGenres = false
    override val prefQualityKey = "quality_selection"
    override val prefQualityDefault = "best"
    override val prefQualityValues = arrayOf("best", "1080p", "720p", "480p", "360p")
    override val prefQualityEntries = arrayOf("Maior disponível", "1080p", "720p", "480p", "360p")
    override fun headersBuilder() = super.headersBuilder().set("Referer", "$baseUrl/")

    override fun popularAnimeRequest(page: Int) = GET(pageUrl("trending", page), headers)
    override fun popularAnimeSelector() = "div.content article.item.tvshows > .poster, div.content article.item.movies > .poster"
    override fun popularAnimeNextPageSelector() = ".pagination a:has(#nextpagination), .resppages a:has(.fa-chevron-right)"
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()
    override fun latestUpdatesSelector() = ".result-item .image a:has(.tvshows), .result-item .image a:has(.movies)"
    override fun latestUpdatesRequest(page: Int) = catalogRequest(page)

    override fun popularAnimeParse(response: Response) = super.popularAnimeParse(response).also {
        debug("popular items=${it.animes.size} next=${it.hasNextPage}")
    }
    override fun latestUpdatesParse(response: Response) = super.latestUpdatesParse(response).also {
        debug("latest items=${it.animes.size} next=${it.hasNextPage}")
    }

    private fun pageUrl(path: String, page: Int) = "$baseUrl/${path.trim('/').let { if (it.isEmpty()) "" else "$it/" }}${if (page > 1) "page/$page/" else ""}"
    private fun catalogRequest(page: Int, query: String = "") = GET(
        pageUrl("", page).toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .addQueryParameter("post_type[]", "tvshows")
            .addQueryParameter("post_type[]", "movies")
            .addQueryParameter("orderby", "date")
            .addQueryParameter("order", "DESC").build(),
        headers,
    )

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://") || query.startsWith(PREFIX_SEARCH)) {
            val url = if (query.startsWith(PREFIX_SEARCH)) "$baseUrl/${query.removePrefix(PREFIX_SEARCH)}" else query
            require(url.toHttpUrl().host == baseUrl.toHttpUrl().host)
            val anime = SAnime.create().apply { setUrlWithoutDomain(url) }
            return AnimesPage(listOf(getAnimeDetails(anime)), false)
        }
        return super.getSearchAnime(page, query, filters)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): okhttp3.Request {
        if (query.isNotBlank()) return catalogRequest(page, query)
        val path = filters.firstInstanceOrNull<ListingFilter>()?.toUriPart().orEmpty()
        return if (path.isBlank()) catalogRequest(page) else GET(pageUrl(path, page), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val selector = if (response.request.url.queryParameter("s") != null) latestUpdatesSelector() else popularAnimeSelector()
        return AnimesPage(doc.select(selector).map(::popularAnimeFromElement).distinctBy { it.url }, doc.selectFirst(popularAnimeNextPageSelector()) != null)
            .also { debug("search items=${it.animes.size} next=${it.hasNextPage}") }
    }

    @Volatile private var terms: List<Term> = emptyList()
    private val termLock = Mutex()
    private suspend fun loadTerms() = termLock.withLock {
        if (terms.isNotEmpty()) return@withLock
        try {
            terms = coroutineScope {
                listOf("genres", "dtyear").map { taxonomy ->
                    async {
                        client.newCall(GET("$baseUrl/wp-json/wp/v2/$taxonomy?per_page=100&_fields=name,link,taxonomy", headers))
                            .awaitSuccess().parseAs<List<Term>>()
                    }
                }.flatMap { it.await() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            debug("filters unavailable type=${e.javaClass.simpleName}")
        }
    }
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        loadTerms()
        return super.getPopularAnime(page)
    }
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        loadTerms()
        return super.getLatestUpdates(page)
    }
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("A busca por nome ignora a listagem selecionada."),
        ListingFilter(
            arrayOf(
                "Todos" to "",
                "Animes" to "animes",
                "Animes dublados" to "tag/animes-dublado",
                "Animes legendados" to "tag/animes-legendado",
                "OVAs e especiais" to "tag/ovas-e-especiais",
                "Filmes" to "filmes",
                "Filmes dublados" to "tag/filmes-dublado",
                "Filmes legendados" to "tag/filmes-legendado",
            ) + terms.map { "${if (it.taxonomy == "dtyear") "Ano" else "Gênero"}: ${Jsoup.parse(it.name).text()}" to it.link.toHttpUrl().encodedPath },
        ),
    )
    private class ListingFilter(values: Array<Pair<String, String>>) : UriPartFilter("Listagem", values)

    override fun animeDetailsRequest(anime: SAnime): okhttp3.Request {
        val url = baseUrl.toHttpUrl().resolve(anime.url)!!
        val type = if (url.pathSegments.first() == "filmes") "movies" else "tvshows"
        return GET(
            "$baseUrl/wp-json/wp/v2/$type".toHttpUrl().newBuilder()
                .addQueryParameter("slug", url.pathSegments.filter(String::isNotEmpty).last())
                .addQueryParameter("_embed", "wp:term,wp:featuredmedia")
                .addQueryParameter("_fields", "link,title,content,_links,_embedded").build(),
            headers,
        )
    }
    override fun animeDetailsParse(response: Response): SAnime {
        val entry = response.parseAs<List<Entry>>().firstOrNull() ?: error("Obra não encontrada na API")
        val metadata = entry.embedded.terms.flatten()
        return SAnime.create().apply {
            setUrlWithoutDomain(entry.link)
            title = Jsoup.parse(entry.title.rendered).text()
            thumbnail_url = entry.embedded.media.firstOrNull()?.sourceUrl
            genre = metadata.filter { it.taxonomy == "genres" || it.taxonomy == "post_tag" }.joinToString { Jsoup.parse(it.name).text() }
            description = Jsoup.parse(entry.content.rendered).select("p").eachText().joinToString("\n\n")
            metadata.firstOrNull { it.taxonomy == "dtyear" }?.let { description += "\n\nAno: ${it.name}" }
            status = SAnime.UNKNOWN
            debug("details cover=${thumbnail_url != null} terms=${metadata.size}")
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        if (response.request.url.pathSegments.first() == "filmes") {
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(doc.location())
                    name = "Filme"
                    episode_number = 1F
                    date_upload = parseDate(doc.selectFirst(".sheader .date")?.text())
                },
            )
        }
        val seasons = doc.select("#seasons > .se-c")
        return seasons.flatMap { season ->
            val number = season.selectFirst(".se-q .se-t")?.text().orEmpty()
            season.select("ul.episodios > li:has(.episodiotitle a)").map { episodeFromElement(it, number) }
        }.distinctBy { it.url }.reversed().also { debug("seasons=${seasons.size} episodes=${it.size}") }
    }
    override fun episodeFromElement(element: Element, seasonName: String) = SEpisode.create().apply {
        val link = element.selectFirst(".episodiotitle a")!!
        val number = element.selectFirst(".numerando")?.text()?.substringAfterLast('-')?.trim()
        setUrlWithoutDomain(link.absUrl("href"))
        name = "T$seasonName E${number.orEmpty()} - ${link.text()}"
        episode_number = number?.toFloatOrNull() ?: -1F
        date_upload = parseDate(element.selectFirst(".date")?.text())
    }
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/Sao_Paulo") }
    private fun parseDate(text: String?) = synchronized(dateFormat) { dateFormat.tryParse(text?.replace(".", "")) }

    private val myEmbed by lazy { MyEmbedExtractor(client, headers) }
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val frames = doc.select(".source-box iframe[src]").map { it.absUrl("src") }.distinct()
        videoDebug("iframes=${frames.size}")
        val videos = frames.flatMap { frame -> runBlocking { myEmbed.videosFromUrl(frame) } }
        if (videos.isEmpty()) error("MyEmbed não ofereceu vídeo reproduzível. O servidor desta obra pode estar indisponível.")
        return videos.distinctBy { it.videoUrl }.sortVideos()
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val preferred = preferences.getString(prefQualityKey, prefQualityDefault).orEmpty()
        return sortedWith(
            compareByDescending<Video> { preferred != "best" && resolution(it.videoTitle) == resolution(preferred) }
                .thenByDescending { resolution(it.videoTitle) }
                .thenBy { !it.videoTitle.contains("Dublado") }
                .thenBy { !it.videoTitle.contains("Blogger") },
        ).also { videoDebug("quality order=${it.joinToString { video -> video.videoTitle }}") }
    }
    private fun resolution(label: String) = Regex("(\\d+)p").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    @Serializable private class Rendered(val rendered: String = "")

    @Serializable private class Term(val name: String, val link: String, val taxonomy: String = "")

    @Serializable private class Media(@SerialName("source_url") val sourceUrl: String)

    @Serializable private class Embedded(
        @SerialName("wp:term") val terms: List<List<Term>> = emptyList(),
        @SerialName("wp:featuredmedia") val media: List<Media> = emptyList(),
    )

    @Serializable private class Entry(val link: String, val title: Rendered, val content: Rendered, @SerialName("_embedded") val embedded: Embedded = Embedded())

    companion object {
        private fun debug(message: String) {
            if (BuildConfig.DEBUG) Log.d("ANIMEXHD_DEBUG", message)
        }
        internal fun videoDebug(message: String) {
            if (BuildConfig.DEBUG) Log.d("ANIMEXHD_VIDEO", message)
        }
    }
}
