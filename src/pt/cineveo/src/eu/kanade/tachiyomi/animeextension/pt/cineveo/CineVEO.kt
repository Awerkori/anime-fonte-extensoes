package eu.kanade.tachiyomi.animeextension.pt.cineveo

import android.util.Log
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CineVEO : AnimeHttpLegacySource() {
    override val name = "CineVEO"
    override val baseUrl = "https://cineveo.club"
    override val lang = "pt-BR"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")

    override fun popularAnimeRequest(page: Int) = GET(
        "$baseUrl/api/get_home_content.php?type=tv&sort=popular&limit=30&page=$page",
        headers,
    )

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<HomeResponse>()
        return AnimesPage(data.results.map(::animeFromItem).distinctBy { it.url }, data.page < data.totalPages)
    }

    override fun latestUpdatesRequest(page: Int): Request = if (page == 1) GET(baseUrl, headers) else categoryRequest("series", page)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        if (response.request.url.encodedPath == "/") {
            val document = response.asJsoup()
            val sections = document.select("section.home-v3-shelf")
                .filter { it.selectFirst("h2")?.text()?.contains("Atualizadas", true) == true }
            val animes = sections.flatMap { it.select("a.home-v3-card__link") }.map(::animeFromCard)
                .distinctBy { it.title.lowercase() }
            return AnimesPage(animes, true)
        }
        return categoryParse(response)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            return GET(
                "$baseUrl/search.php".toHttpUrl().newBuilder().addQueryParameter("ajax_search", "1")
                    .addQueryParameter("q", query).addQueryParameter("page", page.toString()).build(),
                headers,
            )
        }
        return categoryRequest(filters.firstInstanceOrNull<CategoryFilter>()?.value ?: "series", page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = if (response.request.url.encodedPath == "/search.php") {
        val data = response.parseAs<SearchResponse>()
        AnimesPage(data.results.map(::animeFromItem).distinctBy { it.url }, data.pagination.currentPage < data.pagination.totalPages)
    } else {
        categoryParse(response)
    }

    private fun categoryRequest(type: String, page: Int) = GET(
        "$baseUrl/category.php?fetch_mode=1&type=$type&page=$page",
        headers.newBuilder().set("Accept", "application/json").build(),
    )

    private fun categoryParse(response: Response): AnimesPage {
        val data = response.parseAs<CategoryResponse>()
        return AnimesPage(data.results.map(::animeFromItem).distinctBy { it.url }, data.currentPage < data.totalPages)
    }

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("A listagem preserva DUB/LEG exibidos pelo site."),
        CategoryFilter(),
    )

    private class CategoryFilter : AnimeFilter.Select<String>("Categoria", arrayOf("Séries", "Animes", "Filmes", "Doramas")) {
        val value get() = arrayOf("series", "anime", "movie", "dorama")[state]
    }

    private fun animeFromItem(item: CatalogItem) = SAnime.create().apply {
        title = item.title
        thumbnail_url = item.posterPath?.let { if (it.startsWith("http")) it else "https://image.tmdb.org/t/p/w500$it" }
        setUrlWithoutDomain(detailUrl(item.slug, item.mediaType, item.audioVariant))
    }

    private fun animeFromCard(card: Element) = SAnime.create().apply {
        title = card.attr("aria-label").removePrefix("Abrir ").replace(Regex("\\s+(?:HD|FHD|\\d{3,4}p)$"), "")
        thumbnail_url = card.selectFirst("img")?.absUrl("src")
        setUrlWithoutDomain(card.absUrl("href"))
    }

    private fun detailUrl(slug: String, type: String, audio: String) = if (type == "movie") {
        "/filme/$slug.html"
    } else {
        "/series/$slug-lista-de-episodios.html" + if (audio.isBlank()) "" else "?audio=$audio"
    }

    private fun heroElement(document: org.jsoup.nodes.Document): Element = document.selectFirst(".series-hero-v2, .movie-hero-v2") ?: document

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val hero = heroElement(document)
        return SAnime.create().apply {
            setUrlWithoutDomain(document.location())
            title = hero.selectFirst("h1")?.text() ?: document.title().substringBefore(" - CineVEO")
            thumbnail_url = hero.selectFirst(".series-hero-v2__poster img, .movie-hero-v2__poster img, img[alt^=Capa]")?.absUrl("src")
            description = hero.selectFirst(".series-hero-v2__description, .movie-hero-v2__description")?.text()
                ?: document.selectFirst("meta[name=description]")?.attr("content")
            val chips = hero.select(".series-hero-v2__chip, .movie-hero-v2__chip").eachText()
            genre = document.select("a[href*='genero']").eachText().distinct().joinToString()
            status = when {
                chips.any { it.contains("em andamento", true) } -> SAnime.ONGOING
                chips.any { it.contains("completo", true) } -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            val extra = chips.filter { it.matches(Regex("\\d{4}", RegexOption.IGNORE_CASE)) || it.contains("Dublado", true) || it.contains("Legendado", true) }
            if (extra.isNotEmpty()) description = listOfNotNull(description, extra.joinToString(" • ")).joinToString("\n\n")
            initialized = true
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        if (document.location().contains("/filme/")) {
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(document.location())
                    name = "Filme"
                    episode_number = 1F
                },
            )
        }
        return document.select("a[data-episode-card]").map { card ->
            val label = card.selectFirst(".episode-card-v2__badge")?.text().orEmpty()
            val season = Regex("T(\\d+)").find(label)?.groupValues?.get(1).orEmpty()
            val number = card.attr("data-episode").toFloatOrNull() ?: 0F
            SEpisode.create().apply {
                setUrlWithoutDomain(card.absUrl("href"))
                name = "T$season E${number.toInt()} - ${card.selectFirst(".episode-card-v2__title")?.text().orEmpty()}"
                episode_number = number
            }
        }.distinctBy { it.url }.reversed()
    }

    override fun videoListRequest(episode: SEpisode) = GET(baseUrl + episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val episodeUrl = response.request.url.toString()
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()

        val redeFlixFrame = document.selectFirst("#player-iframe, iframe[src*='redeflixapi.store']")
            ?.absUrl("src")
            ?.takeIf { it.isNotBlank() }

        if (redeFlixFrame != null) {
            runCatching {
                videos.addAll(extractRedeFlix(redeFlixFrame, episodeUrl))
            }.onFailure {
                videoDebug("redeFlix error: ${it.message}")
            }
        }

        if (videos.isEmpty() && !episodeUrl.contains("server=opcao2")) {
            runCatching {
                val opcao2Url = "$episodeUrl${if (episodeUrl.contains('?')) '&' else '?'}server=opcao2"
                val optionDoc = client.newCall(GET(opcao2Url, headers)).execute().use { it.asJsoup() }
                val optionFrame = optionDoc.selectFirst("#player-iframe, iframe[src*='redeflixapi.store']")
                    ?.absUrl("src")
                    ?.takeIf { it.isNotBlank() }
                if (optionFrame != null) {
                    videos.addAll(extractRedeFlix(optionFrame, opcao2Url))
                }
            }.onFailure {
                videoDebug("opcao2 error: ${it.message}")
            }
        }

        if (videos.isEmpty()) {
            runCatching { principal(document, episodeUrl) }
                .onFailure { videoDebug("principal=${it.javaClass.simpleName}") }
                .getOrNull()?.let(videos::add)
        }

        videoDebug("videos=${videos.size}")
        return videos.distinctBy { it.videoUrl }.sortVideos()
    }

    private fun extractRedeFlix(frameUrl: String, episodeUrl: String): List<Video> {
        val frameRequest = GET(frameUrl, headers.newBuilder().set("Referer", episodeUrl).build())
        val html = client.newCall(frameRequest).execute().use { it.body.string() }

        val initialTicket = Regex("""(?:var|let|const)?\s*playbackResolveTicket\s*=\s*['"]([^'"]+)['"]""")
            .find(html)?.groupValues?.get(1) ?: return emptyList()

        val serverRegex = Regex("""startSelectedServer\(['"]([^'"]+)['"]\)[^>]*>.*?<span class="server-pill-label">([^<]+)</span>""", RegexOption.DOT_MATCHES_ALL)
        val pillMatches = serverRegex.findAll(html).map {
            it.groupValues[1].trim() to it.groupValues[2].trim()
        }.toList()

        val servers = if (pillMatches.isNotEmpty()) pillMatches else listOf("default" to "Dublado")
        val videos = mutableListOf<Video>()
        var currentTicket = initialTicket

        val streamHeaders = headers.newBuilder()
            .set("Referer", "https://redeflixapi.store/")
            .build()

        for ((serverKey, serverLabel) in servers) {
            try {
                val resolverHeaders = headers.newBuilder()
                    .set("Referer", frameUrl)
                    .set("Origin", "https://redeflixapi.store")
                    .set("X-Requested-With", "RedeFlixPlayer")
                    .set("Accept", "application/json")
                    .build()

                val requestBody = """{"ticket":"$currentTicket","server":"$serverKey"}"""
                    .toRequestBody("application/json".toMediaType())

                val resolveResponse = client.newCall(
                    POST("https://redeflixapi.store/playback-resolve.php", resolverHeaders, requestBody),
                ).execute().use { it.parseAs<ResolveResponse>(json) }

                if (resolveResponse.nextTicket.isNotBlank()) {
                    currentTicket = resolveResponse.nextTicket
                }

                val subtitleList = mutableListOf<Track>()
                if (resolveResponse.subtitle.isNotBlank()) {
                    subtitleList.add(Track(resolveResponse.subtitle, "Português"))
                }

                val videoTitle = "CineVEO - $serverLabel"

                if (resolveResponse.launchTicket.isNotBlank()) {
                    val playerUrl = "https://redeflixapi.store/playerkys/index.php".toHttpUrl().newBuilder()
                        .addQueryParameter("launch", resolveResponse.launchTicket)
                        .addQueryParameter("autostart", "1")
                        .build()
                        .toString()

                    val playerHtml = client.newCall(
                        GET(playerUrl, headers.newBuilder().set("Referer", frameUrl).build()),
                    ).execute().use { it.body.string() }

                    val configJson = Regex("""window\.__RF_INITIAL_CONFIG\s*=\s*(\{.+?\});""")
                        .find(playerHtml)?.groupValues?.get(1)

                    val config = configJson?.let { runCatching { json.decodeFromString<RfInitialConfig>(it) }.getOrNull() }
                    val fileUrl = config?.file?.takeIf { it.isNotBlank() }
                        ?: Regex(""""file"\s*:\s*"([^"]+)"""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/")

                    if (!fileUrl.isNullOrBlank()) {
                        val subUrl = config?.subtitle?.takeIf { it.isNotBlank() }
                        if (!subUrl.isNullOrBlank() && subtitleList.none { it.url == subUrl }) {
                            subtitleList.add(Track(subUrl, "Português"))
                        }
                        videos.add(
                            Video(
                                url = fileUrl,
                                quality = videoTitle,
                                videoUrl = fileUrl,
                                headers = streamHeaders,
                                subtitleTracks = subtitleList,
                            ),
                        )
                    } else {
                        val doc = org.jsoup.Jsoup.parse(playerHtml, playerUrl)
                        doc.selectFirst("video source[src], video[src]")?.absUrl("src")?.takeIf { it.isNotBlank() }?.let { srcUrl ->
                            videos.add(
                                Video(
                                    url = srcUrl,
                                    quality = videoTitle,
                                    videoUrl = srcUrl,
                                    headers = streamHeaders,
                                    subtitleTracks = subtitleList,
                                ),
                            )
                        }
                    }
                } else if (resolveResponse.url.isNotBlank()) {
                    videos.add(
                        Video(
                            url = resolveResponse.url,
                            quality = videoTitle,
                            videoUrl = resolveResponse.url,
                            headers = streamHeaders,
                            subtitleTracks = subtitleList,
                        ),
                    )
                }
            } catch (e: Exception) {
                videoDebug("Error resolving $serverKey: ${e.message}")
            }
        }

        return videos
    }

    private fun principal(document: Document, referer: String): Video? {
        val frame = document.selectFirst("#player-iframe[src*='player/index.php']")?.absUrl("src") ?: return null
        val player = client.newCall(GET(frame, headers.newBuilder().set("Referer", referer).build())).execute().use { it.asJsoup() }
        val source = player.selectFirst("video source[src], video[src]") ?: return null
        val label = Regex("\\\"label\\\"\\s*:\\s*\\\"([^\\\"]+)").find(player.html())?.groupValues?.get(1) ?: "Unknown"
        val sourceUrl = source.absUrl("src")
        if (sourceUrl.toHttpUrl().host == baseUrl.toHttpUrl().host && sourceUrl.substringAfterLast('/').startsWith("aHR0c")) {
            videoDebug("principal=encoded_source")
            return null
        }
        return Video(sourceUrl, "CineVEO Principal - $label", sourceUrl, headers)
    }

    override fun List<Video>.sortVideos() = sortedByDescending { Regex("(\\d+)p").find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }

    private val json: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    @Serializable private class CatalogItem(
        val title: String = "",
        val slug: String = "",
        @SerialName("media_type")
        val mediaType: String = "tv",
        @SerialName("poster_path")
        val posterPath: String? = null,
        @SerialName("audio_variant")
        val audioVariant: String = "",
    )

    @Serializable private class CategoryResponse(
        val results: List<CatalogItem> = emptyList(),
        @SerialName("current_page") val currentPage: Int = 1,
        @SerialName("total_pages") val totalPages: Int = 1,
    )

    @Serializable private class HomeResponse(val results: List<CatalogItem> = emptyList(), val page: Int = 1, val totalPages: Int = 1)

    @Serializable private class SearchResponse(val results: List<CatalogItem> = emptyList(), val pagination: Pagination = Pagination())

    @Serializable private class Pagination(
        @SerialName("current_page") val currentPage: Int = 1,
        @SerialName("total_pages") val totalPages: Int = 1,
    )

    @Serializable private class ResolveResponse(
        @SerialName("launchTicket") val launchTicket: String = "",
        @SerialName("nextTicket") val nextTicket: String = "",
        val url: String = "",
        val subtitle: String = "",
    )

    @Serializable private class RfInitialConfig(
        val file: String = "",
        val subtitle: String = "",
        val title: String = "",
    )

    companion object {
        private fun videoDebug(message: String) {
            if (BuildConfig.DEBUG) Log.d("CINEVEO_VIDEO", message)
        }
    }
}
