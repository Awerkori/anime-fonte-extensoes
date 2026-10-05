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
    override val baseUrl = "https://cineveo20.lat"
    override val lang = "pt-BR"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")

    override fun popularAnimeRequest(page: Int) = GET(
        "$baseUrl/api/get_home_content.php?sort=popular&limit=30&page=$page",
        headers,
    )

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<HomeResponse>()
        return AnimesPage(data.results.map(::animeFromItem).distinctBy { it.url }, data.results.isNotEmpty())
    }

    override fun latestUpdatesRequest(page: Int): Request = GET(
        "$baseUrl/api/get_home_content.php?sort=latest&limit=30&page=$page",
        headers,
    )

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val data = response.parseAs<HomeResponse>()
        return AnimesPage(data.results.map(::animeFromItem).distinctBy { it.url }, data.results.isNotEmpty())
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            return GET(
                "$baseUrl/search.php".toHttpUrl().newBuilder().addQueryParameter("q", query)
                    .addQueryParameter("page", page.toString()).build(),
                headers,
            )
        }
        return categoryRequest(filters.firstInstanceOrNull<CategoryFilter>()?.value ?: "tv", page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val bodyStr = response.body.string()
        if (bodyStr.trimStart().startsWith("{")) {
            val data = runCatching { json.decodeFromString<SearchResponse>(bodyStr) }
                .getOrElse {
                    val home = json.decodeFromString<HomeResponse>(bodyStr)
                    SearchResponse(home.results)
                }
            return AnimesPage(data.results.map(::animeFromItem).distinctBy { it.url }, data.results.isNotEmpty())
        }
        val document = org.jsoup.Jsoup.parse(bodyStr, response.request.url.toString())
        val items = document.select(".item.poster, .item").mapNotNull { card ->
            val link = card.selectFirst("a[href*='watch/']") ?: return@mapNotNull null
            val href = link.absUrl("href")
            val title = card.selectFirst("h6, .poster-img")?.text()?.takeIf { it.isNotBlank() }
                ?: card.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: link.text().removePrefix("Assistir ")
            val poster = card.selectFirst("img")?.absUrl("src")
                ?: Regex("""background(?:-image)?:\s*url\(['"]?([^'")]+)""").find(card.html())?.groupValues?.get(1)
            SAnime.create().apply {
                this.title = title
                this.thumbnail_url = poster
                setUrlWithoutDomain(href)
            }
        }.distinctBy { it.url }
        return AnimesPage(items, false)
    }

    private fun categoryRequest(type: String, page: Int) = GET(
        "$baseUrl/api/get_home_content.php?type=$type&sort=latest&limit=30&page=$page",
        headers,
    )

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("A listagem preserva DUB/LEG exibidos pelo site."),
        CategoryFilter(),
    )

    private class CategoryFilter : AnimeFilter.Select<String>("Categoria", arrayOf("Séries", "Animes", "Filmes", "Doramas")) {
        val value get() = arrayOf("tv", "anime", "movie", "dorama")[state]
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

    private fun detailUrl(slug: String, type: String = "", audio: String = "") = "/watch/$slug"

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            setUrlWithoutDomain(document.location())
            title = document.selectFirst("#title, h1")?.text()
                ?: document.selectFirst("meta[property='og:title']")?.attr("content")
                    ?.removePrefix("Assistir ")
                    ?.substringBefore(" - ")
                ?: document.title().substringBefore(" - CineVEO")

            val posterBg = document.selectFirst("#poster, .poster")?.attr("style")
            val backBg = document.selectFirst("#backImage, .backImage")?.attr("style")
            val bgUrl = Regex("""url\(['"]?([^'")]+)""").find(posterBg ?: "")?.groupValues?.get(1)
                ?: Regex("""url\(['"]?([^'")]+)""").find(backBg ?: "")?.groupValues?.get(1)

            thumbnail_url = bgUrl?.takeIf { it.isNotBlank() }
                ?: document.selectFirst(".series-hero-v2__poster img, .movie-hero-v2__poster img, img[alt^=Capa], img.poster-img")?.absUrl("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")

            val synopsis = document.selectFirst("#synopsis, .series-hero-v2__description, .movie-hero-v2__description")?.text()
                ?: document.selectFirst("meta[name=description]")?.attr("content")

            val genresList = document.select("#genres span, a[href*='genero']").eachText().filter { it.isNotBlank() }.distinct()
            genre = genresList.joinToString()

            val logSpans = document.select(".log span, .series-hero-v2__chip, .movie-hero-v2__chip").eachText()
            val extra = mutableListOf<String>()
            val year = document.selectFirst("#year")?.text()?.takeIf { it.isNotBlank() }
            if (year != null) extra.add(year)
            val quality = document.selectFirst("#quality")?.text()?.takeIf { it.isNotBlank() }
            if (quality != null) extra.add(quality)
            val imdb = document.selectFirst("#imdb")?.text()?.takeIf { it.isNotBlank() }
            if (imdb != null) extra.add("IMDb $imdb")
            val runtime = document.selectFirst("#runtime")?.text()?.takeIf { it.isNotBlank() }
            if (runtime != null) extra.add(runtime)

            for (chip in logSpans) {
                if (chip.contains("Dublado", true) || chip.contains("Legendado", true)) {
                    if (chip !in extra) extra.add(chip)
                }
            }

            status = when {
                logSpans.any { it.contains("em andamento", true) } -> SAnime.ONGOING
                logSpans.any { it.contains("completo", true) } -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }

            description = if (extra.isNotEmpty()) {
                listOfNotNull(synopsis, extra.joinToString(" • ")).joinToString("\n\n")
            } else {
                synopsis
            }
            initialized = true
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val html = document.html()

        val moviePlayLink = document.selectFirst("a[href*='/m/'], a#btn-play[href*='/m/']")
        if (moviePlayLink != null || (!html.contains("allSeasons") && !html.contains("series-section"))) {
            val movieUrl = moviePlayLink?.absUrl("href") ?: document.location()
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(movieUrl)
                    name = "Filme"
                    episode_number = 1F
                },
            )
        }

        val allSeasonsJson = Regex("""const\s+allSeasons\s*=\s*(\{[\s\S]*?\});""").find(html)?.groupValues?.get(1)
        val tmdbId = Regex("""currentTmdbId\s*=\s*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""id_tmdb["']?\s*:\s*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: 0

        if (!allSeasonsJson.isNullOrBlank()) {
            val seasonsMap = runCatching { json.decodeFromString<Map<String, List<SeasonEpisode>>>(allSeasonsJson) }.getOrNull()
            if (!seasonsMap.isNullOrEmpty()) {
                val episodes = mutableListOf<SEpisode>()
                for ((seasonStr, epList) in seasonsMap) {
                    val sNum = seasonStr.toIntOrNull() ?: 1
                    for (ep in epList) {
                        val eNum = ep.episodio
                        val salt = (tmdbId * 37 + sNum * 19 + eNum * 41 + 41) % 100
                        val saltStr = salt.toString().padStart(2, '0')
                        val epCode = ep.playerSlug?.takeIf { it.isNotBlank() } ?: "$tmdbId$sNum$eNum$saltStr"
                        val playerUrl = "https://watch.cineveo20.lat/s/$epCode"
                        val epTitle = ep.nome?.takeIf { it.isNotBlank() } ?: "Episódio ${sNum}x$eNum"
                        episodes.add(
                            SEpisode.create().apply {
                                setUrlWithoutDomain(playerUrl)
                                name = "T$sNum E$eNum - $epTitle"
                                episode_number = eNum.toFloat()
                            },
                        )
                    }
                }
                if (episodes.isNotEmpty()) {
                    return episodes.distinctBy { it.url }.reversed()
                }
            }
        }

        val epElements = document.select(".ep, div[id^=ep-], a[data-episode-card]")
        if (epElements.isNotEmpty()) {
            return epElements.mapNotNull { epEl ->
                val link = epEl.selectFirst("a[href*='watch.cineveo20.lat/s/'], a[href*='/s/'], a[data-episode-card]") ?: return@mapNotNull null
                val href = link.absUrl("href")
                val title = epEl.selectFirst("h5, .episode-card-v2__title")?.text().orEmpty()
                val numStr = epEl.selectFirst("p[number]")?.text()
                    ?: epEl.attr("data-episode").takeIf { it.isNotBlank() }
                    ?: Regex("""\d+x(\d+)""").find(title)?.groupValues?.get(1).orEmpty()
                val eNum = numStr.toFloatOrNull() ?: 1F
                SEpisode.create().apply {
                    setUrlWithoutDomain(href)
                    name = title.ifBlank { "Episódio ${eNum.toInt()}" }
                    episode_number = eNum
                }
            }.distinctBy { it.url }.reversed()
        }

        return emptyList()
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val fullUrl = when {
            episode.url.startsWith("http://") || episode.url.startsWith("https://") -> episode.url
            episode.url.startsWith("/s/") || episode.url.startsWith("/m/") -> "https://watch.cineveo20.lat" + episode.url
            else -> baseUrl + episode.url
        }
        return GET(fullUrl, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val episodeUrl = response.request.url.toString()
        val document = response.asJsoup()
        val html = document.html()
        val videos = mutableListOf<Video>()

        val jwFile = Regex("""file\s*:\s*["'](https?://[^"']+)["']""").find(html)?.groupValues?.get(1)
        if (!jwFile.isNullOrBlank()) {
            val jwTitle = Regex("""title\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?.substringBefore(" - CineVEO")
                ?.takeIf { it.isNotBlank() }
                ?: "CineVEO"
            val videoHeaders = headers.newBuilder()
                .set("Referer", episodeUrl)
                .build()
            videos.add(Video(jwFile, "$jwTitle - Principal", jwFile, videoHeaders))
        }

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

                    val configJson = Regex("""window\.__RF_INITIAL_CONFIG\s*=\s*(\{.+?\});""", RegexOption.DOT_MATCHES_ALL)
                        .find(playerHtml)?.groupValues?.get(1)

                    val config = configJson?.let { runCatching { json.decodeFromString<RfInitialConfig>(it) }.getOrNull() }
                    val fileUrl = config?.file?.takeIf { it.isNotBlank() }
                        ?: Regex(""""file"\s*:\s*"([^"]+)"""").find(playerHtml)?.groupValues?.get(1)
                            ?.replace("\\/", "/")
                            ?.replace("\\u0026", "&")

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

    @Serializable
    private class SeasonEpisode(
        val id: Long = 0,
        @SerialName("id_tmdb") val idTmdb: Long = 0,
        val temporada: Int = 1,
        val episodio: Int = 1,
        val nome: String? = null,
        val sinopse: String? = null,
        val imagem: String? = null,
        @SerialName("url_video") val urlVideo: String? = null,
        @SerialName("player_slug") val playerSlug: String? = null,
    )

    companion object {
        private fun videoDebug(message: String) {
            if (BuildConfig.DEBUG) Log.d("CINEVEO_VIDEO", message)
        }
    }
}
