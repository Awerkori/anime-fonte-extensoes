package eu.kanade.tachiyomi.animeextension.pt.aniture

import android.util.Log
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
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
import keiyoushi.utils.tryParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class Aniture : DooPlay("pt-BR", "Aniture PT", "https://aniture-pt.com.br") {
    override val fetchGenres = false

    override fun headersBuilder() = super.headersBuilder().set("Referer", "$baseUrl/")

    override fun popularAnimeRequest(page: Int) = GET(pageUrl("trending", page), headers)

    override fun popularAnimeSelector() = "div.content article.item.tvshows > .poster, div.content article.item.movies > .poster"

    override fun popularAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    override fun popularAnimeParse(response: Response): AnimesPage = super.popularAnimeParse(response).also {
        debug("popular items=${it.animes.size}")
    }

    override fun latestUpdatesRequest(page: Int) = catalogRequest(page)

    override fun latestUpdatesSelector() = ".result-item .image a:has(.tvshows), .result-item .image a:has(.movies)"

    override fun latestUpdatesNextPageSelector() = ".pagination a:has(#nextpagination), .resppages a:has(.fa-chevron-right)"

    override fun latestUpdatesParse(response: Response): AnimesPage = super.latestUpdatesParse(response).also {
        debug("latest items=${it.animes.size} next=${it.hasNextPage}")
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = pageUrl("", page).toHttpUrl().newBuilder().addQueryParameter("s", query).build()
            return GET(url, headers)
        }
        val category = filters.firstInstanceOrNull<CategoryFilter>()?.toUriPart().orEmpty()
        return if (category.isEmpty()) catalogRequest(page) else GET(pageUrl(category, page), headers)
    }

    // WordPress's public search includes both post types and supports real date ordering.
    // The TV archive and the home widgets are cached/limited independently of this listing.
    private fun catalogRequest(page: Int) = GET(
        pageUrl("", page).toHttpUrl().newBuilder()
            .addQueryParameter("s", "")
            .addQueryParameter("post_type[]", "tvshows")
            .addQueryParameter("post_type[]", "movies")
            .addQueryParameter("orderby", "date")
            .addQueryParameter("order", "DESC")
            .build(),
        headers,
    )

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val selector = if (response.request.url.queryParameter("s") != null) {
            latestUpdatesSelector()
        } else {
            popularAnimeSelector()
        }
        val entries = document.select(selector).map(::popularAnimeFromElement).distinctBy { it.url }
        return AnimesPage(entries, document.selectFirst(latestUpdatesNextPageSelector()) != null).also {
            debug("search items=${it.animes.size} next=${it.hasNextPage}")
        }
    }

    private fun pageUrl(path: String, page: Int) = buildString {
        append("$baseUrl/")
        if (path.isNotEmpty()) append("${path.trim('/')}/")
        if (page > 1) append("page/$page/")
    }

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("A pesquisa por nome ignora a categoria."),
        CategoryFilter(),
    )

    private class CategoryFilter :
        UriPartFilter(
            "Categoria",
            arrayOf(
                "Todos" to "",
                "Lançamentos" to "genero/lancamentos",
                "Animes Legendados" to "genero/animes-legendados",
                "Animes e Desenhos Dublados" to "genero/animes-e-desenhos-dublados",
                "Filmes" to "genero/filmes",
            ) + ('A'..'Z').map { "Letra - $it" to "genero/letra-${it.lowercaseChar()}" },
        )

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        val header = document.selectFirst(".sheader") ?: error("Detalhes do anime não encontrados")
        setUrlWithoutDomain(document.location())
        title = header.selectFirst("h1")!!.text()
        thumbnail_url = header.selectFirst(".poster img")?.getImageUrl()
        genre = header.select(".sgeneros a").eachText().joinToString()
        description = document.select(".sbox .wp-content p").eachText().joinToString("\n\n")
        // The categories and update schedule are not an airing status.
        val statusText = header.select(".custom_fields").firstOrNull {
            it.selectFirst("b")?.text()?.trimEnd(':')?.lowercase() == "status"
        }?.selectFirst("span")?.text()?.lowercase()
        status = when (statusText) {
            "em exibição", "em andamento" -> SAnime.ONGOING
            "completo", "concluído", "finalizado" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        debug("details cover=${!thumbnail_url.isNullOrEmpty()} description=${!description.isNullOrEmpty()} status=$status")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val seasons = document.select("#seasons > .se-c")
        val episodes = seasons.flatMap { season ->
            val seasonNumber = season.selectFirst(".se-q .se-t")?.text().orEmpty()
            season.select("ul.episodios > li:has(.episodiotitle a[href])").map { episodeFromElement(it, seasonNumber) }
        }.distinctBy { it.url }.reversed()
        debug("episodes seasons=${seasons.size} items=${episodes.size}")
        if (episodes.isNotEmpty()) return episodes
        // Only actual movie pages become a single episode, never an empty series.
        if (response.request.url.pathSegments.firstOrNull() == "filmes") {
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(document.location())
                    name = "Filme"
                    episode_number = 1F
                    date_upload = parseDate(document.selectFirst(".sheader .date")?.text())
                },
            )
        }
        return emptyList()
    }

    override fun episodeFromElement(element: Element, seasonName: String): SEpisode = SEpisode.create().apply {
        val link = element.selectFirst(".episodiotitle a")!!
        setUrlWithoutDomain(link.absUrl("href"))
        val number = element.selectFirst(".numerando")?.text()?.substringAfterLast('-')?.trim()
        episode_number = number?.toFloatOrNull() ?: -1F
        name = if (seasonName.isNotEmpty()) "Temporada $seasonName - ${link.text()}" else link.text()
        date_upload = parseDate(element.selectFirst(".date")?.text())
    }

    private val portugueseDate = SimpleDateFormat("dd MMM yyyy", Locale("pt", "BR")).apply {
        timeZone = TimeZone.getTimeZone("America/Sao_Paulo")
    }

    private fun parseDate(value: String?): Long = synchronized(portugueseDate) {
        portugueseDate.tryParse(value?.replace(".", ""))
    }

    private val blogger by lazy { BloggerPlayer(client, headers) }
    private val mixdrop by lazy { AnitureMixDropExtractor(client) }
    private val strp by lazy { StrpPlayer(client, headers) }

    private fun List<Video>.sort(): List<Video> {
        val preferred = preferences.getString(prefQualityKey, prefQualityDefault).orEmpty()
        return sortedWith(
            compareBy<Video> {
                when {
                    it.videoTitle.startsWith("Blogger") -> 0
                    it.videoTitle.startsWith("STRP2P") -> 1
                    else -> 2
                }
            }.thenByDescending { preferred in it.videoTitle }
                .thenByDescending { Regex("(\\d+)p").find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)
        screen.addPreference(
            SwitchPreferenceCompat(screen.context).apply {
                key = "alternate_servers"
                title = "Mostrar servidores alternativos"
                summary = "Busca também STRP2P quando Blogger está disponível. Pode demorar mais."
                setDefaultValue(false)
            },
        )
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val document = client.newCall(videoListRequest(episode)).awaitSuccess().use { it.asJsoup() }
        val frames = document.select(".source-box:not(#source-player-trailer) .pframe iframe[src]")
            .mapNotNull { it.absUrl("src").toHttpUrlOrNull() }
            .distinct()
        videoDebug("iframes=${frames.size} hosts=${frames.map { it.host }.distinct().joinToString()}")
        val videos = mutableListOf<Video>()
        for (frame in frames.filter { it.host == "www.blogger.com" || it.host == "blogger.com" }) {
            try {
                videos += blogger.videosFromUrl(frame.toString())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never log exception messages: HTTP exceptions may include signed URLs.
                videoDebug("Blogger failed type=${e.javaClass.simpleName}")
            }
        }
        videoDebug("Blogger videos=${videos.size}")
        if (videos.isNotEmpty() && !preferences.getBoolean("alternate_servers", false)) return videos.distinctBy { it.videoUrl }.sort()

        for (frame in frames.filter { it.host == "animes.strp2p.com" }) {
            try {
                videos += withTimeoutOrNull(15_000) { strp.videosFromUrl(frame.toString()) }.orEmpty()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                videoDebug("STRP2P failed type=${e.javaClass.simpleName}")
            }
        }
        if (videos.isNotEmpty()) return videos.distinctBy { it.videoUrl }.sort()

        // Resolve fallback hosts only when Blogger has no usable media.
        // This keeps slow or broken secondary servers out of Blogger's critical path.
        for (frame in frames.filter { it.host == "miixdrop.top" }) {
            try {
                val resolved = withContext(Dispatchers.IO) {
                    mixdrop.videosFromUrl(frame.toString(), referer = "${frame.scheme}://${frame.host}/")
                }
                for (video in resolved) {
                    val url = video.videoUrl?.toHttpUrlOrNull() ?: continue
                    val mediaHeaders = (video.headers ?: headers).newBuilder().set("Range", "bytes=0-1023").build()
                    withContext(Dispatchers.IO) {
                        client.newCall(GET(url, mediaHeaders)).execute().use { response ->
                            videoDebug("MixDrop media status=${response.code} type=${response.header("Content-Type")}")
                            check(response.isSuccessful && response.header("Content-Type").orEmpty().startsWith("video/"))
                        }
                    }
                    videos += video
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                videoDebug("MixDrop failed type=${e.javaClass.simpleName}")
            }
        }
        videoDebug("fallback videos=${videos.size}")
        return videos.distinctBy { it.videoUrl }.sort()
    }

    companion object {
        private fun debug(message: String) {
            if (BuildConfig.DEBUG) Log.d("ANITURE_DEBUG", message)
        }

        internal fun videoDebug(message: String) {
            if (BuildConfig.DEBUG) Log.d("ANITURE_VIDEO", message)
        }
    }
}
