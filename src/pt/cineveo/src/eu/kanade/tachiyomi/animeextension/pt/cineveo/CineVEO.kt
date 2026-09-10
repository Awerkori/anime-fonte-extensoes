package eu.kanade.tachiyomi.animeextension.pt.cineveo

import android.util.Log
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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

    override fun headersBuilder() = super.headersBuilder().set("Referer", "$baseUrl/")

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
        runCatching { principal(document, episodeUrl) }.onFailure { videoDebug("principal=${it.javaClass.simpleName}") }.getOrNull()?.let(videos::add)
        runCatching { option2(episodeUrl) }.onFailure { videoDebug("option2=${it.javaClass.simpleName}") }.getOrNull()?.let(videos::add)
        videoDebug("videos=${videos.size}")
        return videos.distinctBy { it.videoUrl }.sortVideos()
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

    private fun option2(episodeUrl: String): Video? {
        val option = client.newCall(GET("$episodeUrl${if (episodeUrl.contains('?')) '&' else '?'}server=opcao2", headers)).execute().use { it.asJsoup() }
        val frame = option.selectFirst("#player-iframe[src*='redeflixapi.store']")?.absUrl("src") ?: return null
        val html = client.newCall(GET(frame, headers.newBuilder().set("Referer", episodeUrl).build())).execute().use { it.body.string() }
        val ticket = Regex("let playbackResolveTicket = \\\"([^\\\"]+)").find(html)?.groupValues?.get(1) ?: return null
        val resolverHeaders = headers.newBuilder().set("Referer", frame).set("Origin", "https://redeflixapi.store")
            .set("X-Requested-With", "RedeFlixPlayer").set("Accept", "application/json").build()
        val payload = client.newCall(
            POST(
                "https://redeflixapi.store/playback-resolve.php",
                resolverHeaders,
                "{\"ticket\":\"$ticket\",\"server\":\"default\"}".toRequestBody("application/json".toMediaType()),
            ),
        ).execute().use { it.parseAs<ResolveResponse>() }
        if (payload.launchTicket.isBlank() && payload.url.isNotBlank()) {
            val directHeaders = headers.newBuilder().set("Referer", "https://redeflixapi.store/").build()
            return Video(payload.url, "CineVEO Opção 2 - Unknown", payload.url, directHeaders)
        }
        val launch = payload.launchTicket.takeIf { it.isNotBlank() } ?: return null
        val playerUrl = "https://redeflixapi.store/cineveo_player/index.php".toHttpUrl().newBuilder()
            .addQueryParameter("launch", launch).addQueryParameter("autostart", "1").build().toString()
        val player = client.newCall(GET(playerUrl, headers.newBuilder().set("Referer", frame).build())).execute().use { it.asJsoup() }
        val source = player.selectFirst("video source[src], video[src]") ?: return null
        val sourceUrl = source.absUrl("src")
        val streamHeaders = headers.newBuilder().set("Referer", "https://redeflixapi.store/").build()
        return Video(sourceUrl, "CineVEO Opção 2 - HD", sourceUrl, streamHeaders)
    }

    override fun List<Video>.sortVideos() = sortedByDescending { Regex("(\\d+)p").find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }

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
        val url: String = "",
    )

    companion object {
        private fun videoDebug(message: String) {
            if (BuildConfig.DEBUG) Log.d("CINEVEO_VIDEO", message)
        }
    }
}
