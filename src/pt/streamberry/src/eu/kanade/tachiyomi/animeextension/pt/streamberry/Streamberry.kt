package eu.kanade.tachiyomi.animeextension.pt.streamberry

import android.os.SystemClock
import android.util.Log
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class Streamberry : DooPlay("pt-BR", "Streamberry", "https://streamberry.com.br") {
    @Volatile private var lastSuccessfulServer: String? = null
    private val json: Json by injectLazy()
    private val extractor by lazy { StreamberryExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularAnimeSelector() = "#featured-titles article.item div.poster"

    override fun latestUpdatesRequest(page: Int): Request = GET(
        if (page == 1) "$baseUrl/episodios/" else "$baseUrl/episodios/page/$page/",
        headers,
    )

    override fun latestUpdatesSelector() = "#archive-content article.item.se.episodes"

    override fun latestUpdatesNextPageSelector() = "div.pagination a[href*='/episodios/page/']"

    override fun latestUpdatesParse(response: Response): AnimesPage {
        fetchGenresList()
        val document = response.asJsoup()
        val works = LinkedHashMap<String, Element>()
        fun collect(pageDocument: Document) {
            pageDocument.select(latestUpdatesSelector()).forEach { element ->
                val episodeUrl = element.selectFirst("a[href]")?.attr("abs:href") ?: return@forEach
                val slug = episodeUrl.substringBeforeLast('/').substringAfterLast('/')
                    .replace(Regex("-\\d+x\\d+$"), "")
                val url = "$baseUrl/series/$slug/"
                if (!works.containsKey(url)) works[url] = element
            }
        }
        collect(document)
        var page = 1
        while (works.size < 15 && page < 5 && document.selectFirst("div.pagination a[href*='/episodios/page/${page + 1}/']") != null) {
            page++
            client.newCall(latestUpdatesRequest(page)).execute().use { collect(it.asJsoup()) }
        }
        val items = works.map { (url, element) ->
            val canonical = runCatching {
                client.newCall(GET(url, headers)).execute().use { it.asJsoup() }
            }.getOrNull()
            val poster = canonical?.selectFirst("div.sheader div.poster > img")
            SAnime.create().apply {
                setUrlWithoutDomain(url)
                title = canonical?.selectFirst("div.sheader div.data > h1")?.text()?.trim()
                    .takeUnless { it.isNullOrBlank() }
                    ?: element.selectFirst(".serie")?.text()?.trim().orEmpty()
                thumbnail_url = poster?.let {
                    it.attr("abs:data-lazy-src").ifEmpty {
                        it.attr("abs:data-src").ifEmpty { it.attr("abs:src") }
                    }
                }
            }
        }
        return AnimesPage(items, document.selectFirst(latestUpdatesNextPageSelector()) != null)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = if (query.isBlank()) {
        super.searchAnimeRequest(page, query, filters)
    } else {
        GET("$baseUrl/page/$page/?s=${query.trim().replace(" ", "+")}", headers)
    }

    override fun animeDetailsParse(document: Document): SAnime = super.animeDetailsParse(document).apply {
        val header = document.selectFirst("div.sheader")
        val audio = header?.select(".poster-audio-tags .post-tag-audio")?.eachText()?.joinToString(" ")
        if (!audio.isNullOrBlank()) description = (description.orEmpty() + "\nÁudio: $audio").trim()
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = getRealAnimeDoc(response.asJsoup())
        val seasonList = document.select("div#seasons > div")
        return if (seasonList.isEmpty()) {
            listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(document.location())
                    episode_number = 1F
                    name = "Filme"
                },
            )
        } else {
            seasonList.flatMap { season ->
                val seasonName = season.selectFirst("span.se-t")?.text() ?: "1"
                season.select("ul.episodios > li").mapNotNull { element ->
                    val link = element.selectFirst(".episodiotitle a") ?: return@mapNotNull null
                    val number = element.selectFirst(".numerando")?.text()?.substringAfterLast("-")?.trim()?.toFloatOrNull() ?: 0F
                    SEpisode.create().apply {
                        setUrlWithoutDomain(link.attr("href"))
                        episode_number = number
                        name = "Temporada $seasonName x $number - ${link.ownText()}"
                        date_upload = element.selectFirst(".date")?.text()?.let { parseDate(it) } ?: 0L
                    }
                }
            }.reversed()
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val startedAt = SystemClock.elapsedRealtime()
        val type = if (document.location().contains("/episodios/")) "tv" else "movie"
        val language = document.selectFirst(".sb-lang-tab-btn.active")?.text()?.trim() ?: "Vídeo"
        val options = document.select("li.dooplay_player_option[data-post][data-nume]")
            .sortedBy { serverPriority(serverName(it)) }
        var attempts = 0
        val seenOptionIds = HashSet<String>()
        val seenPlayers = HashSet<String>()
        for (option in options) {
            val server = serverName(option)
            val optionId = "${option.attr("data-post")}:${option.attr("data-nume")}"
            if (!seenOptionIds.add(optionId)) continue
            attempts++
            val embed = runCatching { getEmbed(option, type, document.location()) }.getOrNull() ?: continue
            val embedHost = android.net.Uri.parse(embed).host.orEmpty()
            val embedHash = Integer.toHexString(embed.hashCode())
            Log.d("Streamberry", "SERVER_LABEL=$server IFRAME_HOST=$embedHost IFRAME_URL_HASH=$embedHash AUDIO_LABEL=$language PLAYER_OPTION_ID=$optionId")
            if (!seenPlayers.add("$optionId|$embed")) continue
            val videos = runCatching {
                getVideos(embed, server, document.location(), language)
            }.getOrDefault(emptyList())
            if (videos.isNotEmpty()) {
                lastSuccessfulServer = server
                Log.d("Streamberry", "TOTAL_RESOLVE_MS=${SystemClock.elapsedRealtime() - startedAt} SERVER_ATTEMPTS=$attempts SUCCESSFUL_SERVER=$server VIDEO_COUNT=${videos.size}")
                return videos
            }
        }
        Log.d("Streamberry", "TOTAL_RESOLVE_MS=${SystemClock.elapsedRealtime() - startedAt} SERVER_ATTEMPTS=$attempts VIDEO_COUNT=0")
        return emptyList()
    }

    private fun serverName(option: Element): String = option.selectFirst(".sb-player-title")?.text()?.trim()?.ifEmpty { null } ?: "Servidor"

    private fun serverPriority(server: String): Int = when {
        server.contains("Vidara", ignoreCase = true) -> 0
        server.contains("EU PLAYER", ignoreCase = true) -> 1
        server == lastSuccessfulServer -> 2
        server.contains("Lulu", ignoreCase = true) -> 3
        server.contains("Byse", ignoreCase = true) -> 4
        server.contains("Loadvid", ignoreCase = true) -> 5
        else -> 6
    }

    private fun getVideos(embed: String, server: String, episodeUrl: String, language: String): List<Video> {
        val name = "$server - $language"
        return extractor.videosFromUrl(embed, name, episodeUrl, playlistUtils)
    }

    private fun getEmbed(option: Element, type: String, episodeUrl: String): String {
        val body = "action=doo_player_ajax&post=${option.attr("data-post")}&nume=${option.attr("data-nume")}&type=$type"
            .toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType())
        val requestHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("Origin", baseUrl)
            .set("Referer", episodeUrl)
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
        return client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", body = body, headers = requestHeaders))
            .execute().use { json.decodeFromString<Embed>(it.body.string()).embedUrl }
            .replace("\\/", "/")
            .replace(Regex("^//"), "https://")
    }

    override fun genresListRequest() = GET(baseUrl, headers)

    override fun genresListParse(document: Document): Array<Pair<String, String>> = document.select("a[href*='/genre/']")
        .map { it.text() to it.attr("href").substringAfter("$baseUrl/") }
        .distinctBy { it.second }.toTypedArray()

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Filtros por gênero e áudio"),
        AudioFilter(),
        super.getFilterList().firstOrNull() ?: AnimeFilter.Separator(),
    )

    private class AudioFilter : UriPartFilter("Áudio", arrayOf("Todos" to "", "Dublado" to "tipo/dublado", "Legendado" to "tipo/legendado"))

    @Serializable
    private data class Embed(@kotlinx.serialization.SerialName("embed_url") val embedUrl: String)

    private fun parseDate(value: String) = runCatching { java.text.SimpleDateFormat("MMMM. dd, yyyy", java.util.Locale.ENGLISH).parse(value)?.time ?: 0L }.getOrDefault(0L)
}
