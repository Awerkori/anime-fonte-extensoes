package eu.kanade.tachiyomi.animeextension.pt.animeplay

import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animeextension.pt.animeplay.extractors.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimePlay :
    DooPlay(
        "pt-BR",
        "Anime Play",
        "https://animeplay.cloud",
    ) {
    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/anime", headers)

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = "div.pagination > a.arrow_pag > i.fa-caret-right"

    override fun latestUpdatesFromElement(element: Element): SAnime = super.latestUpdatesFromElement(element).apply {
        url = episodeUrlToAnimeUrl(url)
    }

    private fun episodeUrlToAnimeUrl(url: String): String {
        val path = url.substringAfter(baseUrl).trim('/')
        val slug = REGEX_EPISODE_URL.matchEntire(path)?.groupValues?.get(1)
            ?: return url

        return "/anime/$slug/"
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters

        val orderByFilter = filterList.find { it is OrderByFilter } as? OrderByFilter
        val orderFilter = filterList.find { it is OrderFilter } as? OrderFilter

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            filterList.firstOrNull { it is UriPartFilter && it.state != 0 }?.let {
                val filter = it as UriPartFilter
                addEncodedPathSegments(filter.toUriPart())
            }

            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }

            addPathSegment("")
            addQueryParameter("s", query)

            // order (optional)
            if (orderByFilter != null) addQueryParameter("orderby", orderByFilter.selected)
            if (orderFilter != null) addQueryParameter("order", orderFilter.selected)
        }.build()

        return GET(url.toString(), headers)
    }

    // =========================== Anime Details ============================
    override val additionalInfoSelector = "div.wp-content"

    override fun Document.getDescription(): String = select("$additionalInfoSelector p")
        .firstOrNull { !it.text().contains("Título Alternativo") }
        ?.let { it.text() + "\n" }
        ?: ""

    fun Document.getAlternativeTitle(): String = select("$additionalInfoSelector p")
        .firstOrNull { it.text().contains("Título Alternativo") }
        ?.let { it.text() + "\n" }
        ?: ""

    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealAnimeDoc(document)
        val sheader = doc.selectFirst("div.sheader")!!
        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            sheader.selectFirst("div.poster > img")!!.let {
                thumbnail_url = it.getImageUrl()
                title = it.attr("alt").ifEmpty {
                    sheader.selectFirst("div.data > h1")!!.text()
                }.trim()
            }

            genre = sheader.select("div.data div.sgeneros > a")
                .eachText()
                .joinToString()

            // description = doc.getDescription()
            doc.selectFirst("div#info")?.let { info ->
                description = buildString {
                    append(doc.getDescription())
                    append(doc.getAlternativeTitle())
                    additionalInfoItems.forEach {
                        info.getInfo(it)?.let(::append)
                    }
                }
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.episodios-grid > div.episode-card"

    override fun episodeFromElement(element: Element, seasonName: String): SEpisode = SEpisode.create().apply {
        val epNum = element.attr("data-episode-number").trim()
        val href = element.selectFirst("a[href]")!!
        val episodeName = element.attr("data-episode-title").trim()
        episode_number = epNum.toFloatOrNull() ?: 0F
        name = "$episodeSeasonPrefix $seasonName x $epNum - $episodeName"
        setUrlWithoutDomain(href.attr("href"))
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        val players = document.select("ul#playeroptionsul li")
        val episodeUrl = response.request.url.toString()
        return players.parallelCatchingFlatMapBlocking { getPlayerVideos(it, episodeUrl) }
    }

    private val bloggerExtractor by lazy { BloggerExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private suspend fun getPlayerVideos(player: Element, episodeUrl: String): List<Video> {
        val name = player.selectFirst("span.title")!!.text()
            .run {
                when (this.uppercase()) {
                    "SD" -> "360p"
                    "HD" -> "720p"
                    "SD/HD", "SD / HD" -> "720p"
                    "FHD", "FULLHD", "FULLHD / HLS" -> "1080p"
                    else -> this
                }
            }

        val playerUrl = getPlayerUrl(player, episodeUrl).takeIf(String::isNotBlank)
            ?: return emptyList()
        val requestHeaders = headersWithReferer(episodeUrl)

        val videos = when {
            "blogger.com" in playerUrl -> bloggerExtractor.videosFromUrl(playerUrl, requestHeaders)
            playerUrl.contains("jwplayer", ignoreCase = true) -> videosFromJwPlayer(playerUrl, episodeUrl, name)
            else -> emptyList()
        }

        if (videos.isEmpty()) {
            return universalExtractor.videosFromUrl(playerUrl, requestHeaders, name)
        }
        return videos
    }

    private suspend fun getPlayerUrl(player: Element, episodeUrl: String): String {
        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", player.attr("data-post"))
            .add("nume", player.attr("data-nume"))
            .add("type", player.attr("data-type"))
            .build()

        return client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", ajaxHeaders(episodeUrl), body))
            .awaitSuccess()
            .parseAs<PlayerDto>()
            .embedUrl
            .toAbsoluteUrl()
    }

    private suspend fun videosFromJwPlayer(playerUrl: String, episodeUrl: String, name: String): List<Video> {
        val videoUrl = playerUrl.toHttpUrlOrNull()?.queryParameter("source")
            ?: getJwPlayerFile(playerUrl, episodeUrl)
            ?: return emptyList()

        return listOf(Video(videoUrl, name, videoUrl, videoHeaders(playerUrl, videoUrl)))
    }

    private suspend fun getJwPlayerFile(playerUrl: String, episodeUrl: String): String? {
        val body = client.newCall(GET(playerUrl, headersWithReferer(episodeUrl)))
            .awaitSuccess()
            .bodyString()

        val jwConfig = REGEX_JW_CONFIG.find(body)?.groupValues?.get(1)
            ?: return null

        return jwConfig.parseAs<JwConfigDto>().file.takeIf(String::isNotBlank)
    }

    private fun ajaxHeaders(episodeUrl: String): Headers = headers.newBuilder()
        .set("Accept", "*/*")
        .set("Origin", baseUrl)
        .set("Referer", episodeUrl)
        .set("X-Requested-With", "XMLHttpRequest")
        .build()

    private fun headersWithReferer(referer: String): Headers = headers.newBuilder()
        .set("Referer", referer)
        .build()

    private fun videoHeaders(playerUrl: String, videoUrl: String): Headers {
        val playerOrigin = playerUrl.toHttpUrlOrNull()
            ?.let { "${it.scheme}://${it.host}" }
            ?: baseUrl
        val videoHost = videoUrl.toHttpUrlOrNull()?.host

        return headers.newBuilder()
            .set("Accept", "*/*")
            .set("Origin", playerOrigin)
            .set("Referer", playerUrl)
            .apply {
                if (videoHost != null) {
                    set("Host", videoHost)
                }
            }
            .build()
    }

    private fun String.toAbsoluteUrl(): String = when {
        startsWith("//") -> "https:$this"
        startsWith("/") -> "$baseUrl$this"
        else -> this
    }

    // ============================== Filters ===============================
    @Volatile
    private var hasFetchedGenresArray = false

    override val genreFilterHeader = "Apenas um tipo de filtro por vez"
    override fun genresListRequest() = GET("$baseUrl/wp-json/wp/v2/genres?per_page=100&_fields[]=name&_fields[]=link")

    override fun getFilterList(): AnimeFilterList = if (hasFetchedGenresArray) {
        AnimeFilterList(
            AnimeFilter.Header(genreFilterHeader),
            AudioFilter(),
            FetchedGenresFilter(genresListMessage, genresArray),
            AnimeFilter.Separator(),
            OrderByFilter(),
            OrderFilter(),
        )
    } else if (fetchGenres) {
        AnimeFilterList(AnimeFilter.Header(genresMissingWarning))
    } else {
        AnimeFilterList()
    }

    @Synchronized
    override fun fetchGenresList() {
        if (hasFetchedGenresArray || !fetchGenres) return

        runCatching {
            client.newCall(genresListRequest())
                .execute()
                .parseAs<List<GenreDto>>()
                .let(::genresListParse)
                .let { items ->
                    if (items.isNotEmpty()) {
                        genresArray = items
                        hasFetchedGenresArray = true
                    }
                }
        }.onFailure { it.printStackTrace() }
    }

    fun genresListParse(genres: List<GenreDto>): Array<Pair<String, String>> {
        val items = genres.map {
            val name = it.name
            val value = it.link.substringAfter("$baseUrl/").removeSuffix("/")
            Pair(name, value)
        }.toTypedArray()

        return if (items.isEmpty()) {
            items
        } else {
            arrayOf(Pair(selectFilterText, "")) + items
        }
    }

    private class AudioFilter :
        UriPartFilter(
            "Áudio",
            arrayOf(
                Pair("Todos", ""),
                Pair("Dublado", "tipo/dublado"),
                Pair("Legendado", "tipo/legendado"),
            ),
        )

    private abstract class SelectFilter(
        name: String,
        private val options: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        val selected
            get() = options[state].second
    }

    private class OrderByFilter :
        SelectFilter(
            "Ordenar Por",
            arrayOf(
                Pair("Data de Criação", "date"),
                Pair("Data de Modificação", "modified"),
                Pair("Título", "title"),
            ),
        )

    private class OrderFilter :
        SelectFilter(
            "Ordem",
            arrayOf(
                Pair("Descendente", "desc"),
                Pair("Ascendente", "asc"),
            ),
        )

    // ============================= Utilities ==============================
    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(quality) }
                .thenByDescending {
                    REGEX_QUALITY.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                },
        )
    }

    override fun Element.getImageUrl(): String {
        val url = when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }

        // Remove the "-<width>x<height>" suffix before the file extension:
        // ex: ".../file-200x300.jpg" -> ".../file.jpg"
        return url.replace(REGEX_IMAGE_SIZE_SUFFIX, "")
    }

    @Serializable
    data class PlayerDto(
        @SerialName("embed_url") val embedUrl: String = "",
    )

    @Serializable
    data class JwConfigDto(
        val file: String = "",
    )

    @Serializable
    data class GenreDto(
        val name: String,
        val link: String,
    )

    companion object {
        private val REGEX_EPISODE_URL by lazy {
            Regex("""episodio/(.+)-episodio-\d+""")
        }
        private val REGEX_JW_CONFIG by lazy {
            Regex("""var\s+jw\s*=\s*(\{.*?})\s*</script>""", RegexOption.DOT_MATCHES_ALL)
        }
        private val REGEX_QUALITY by lazy { Regex("""(\d+)p""") }
        private val REGEX_IMAGE_SIZE_SUFFIX by lazy {
            Regex("""-\d+x\d+(?=\.[A-Za-z0-9]+$)""")
        }
    }
}
