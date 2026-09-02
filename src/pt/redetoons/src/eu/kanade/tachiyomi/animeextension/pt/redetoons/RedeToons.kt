package eu.kanade.tachiyomi.animeextension.pt.redetoons

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class RedeToons : AnimeHttpLegacySource() {
    override val name = "RedeToons"
    override val baseUrl = "https://redetoons.win"
    override val lang = "pt-BR"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set("Accept", "application/json")

    override fun popularAnimeRequest(page: Int): Request = shelvesRequest("popular", page)
    override fun popularAnimeParse(response: Response): AnimesPage = response.parseAs<ShelvesResponse>().toPage(response, "popular")
    override fun latestUpdatesRequest(page: Int): Request = shelvesRequest("recent", page)
    override fun latestUpdatesParse(response: Response): AnimesPage = response.parseAs<ShelvesResponse>().toPage(response, "recent")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegment("api").addPathSegment("search")
            .addQueryParameter("q", query.trim()).addQueryParameter("cv", "c2052").build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = response.parseAs<SearchResponse>().let {
        AnimesPage(it.results.map { item -> item.toSAnime() }, false)
    }

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl/api/tmdb/${anime.url}", headers)
    override fun animeDetailsParse(response: Response): SAnime = response.parseAs<DetailsDto>().toSAnime(response.request.url.toString().substringAfterLast('/'))

    override fun episodeListRequest(anime: SAnime): Request = if (anime.url.startsWith("movie/")) {
        GET("$baseUrl/api/play-link?contract=3&tmdbId=${anime.url.substringAfter('/')}&type=movie", headers)
    } else {
        GET("$baseUrl/api/series-playable/${anime.url.substringAfter('/')}", headers)
    }
    override fun episodeListParse(response: Response): List<SEpisode> {
        if (response.request.url.queryParameter("type") == "movie") {
            val id = response.request.url.queryParameter("tmdbId") ?: return emptyList()
            val playable = response.parseAs<PlayLinkResponse>()
            if (playable.missing || (playable.variants.isEmpty() && playable.url.isNullOrBlank())) return emptyList()
            return listOf(
                SEpisode.create().apply {
                    url = "movie|$id"
                    name = "Filme"
                    episode_number = 1F
                },
            )
        }
        val id = response.request.url.pathSegments.last()
        return response.parseAs<EpisodesResponse>().episodes.map { episode ->
            SEpisode.create().apply {
                url = "$id|${episode.s}|${episode.e}"
                name = "T${episode.s}E${episode.e} - ${episode.name ?: "Episódio ${episode.e}"}"
                episode_number = episode.s + episode.e / 1000f
            }
        }.reversed()
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val parts = episode.url.split('|')
        val id = if (parts[0] == "movie") parts[1] else parts[0]
        val query = if (parts[0] == "movie") {
            "contract=3&tmdbId=$id&type=movie"
        } else {
            "contract=3&tmdbId=$id&type=tv&season=${parts[1]}&episode=${parts[2]}"
        }
        return GET("$baseUrl/api/play-link?$query", headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val data = response.parseAs<PlayLinkResponse>()
        if (data.missing) return emptyList()
        val variants = data.variants.ifEmpty { listOf(Variant("default", data.url)) }
        return variants.flatMap { variant ->
            val url = variant.url?.takeIf { it.startsWith("http") } ?: return@flatMap emptyList()
            val quality = variant.quality.orEmpty().replaceFirstChar { it.uppercase() }
            val urls = (listOf(url) + variant.mirrors).distinct()
            urls.map { stream -> Video(stream, "RedeToons - $quality", stream, headers) }
        }
    }

    override fun videoUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun shelvesRequest(shelf: String, page: Int) = GET("$baseUrl/api/shelves?shelf=$shelf&page=$page", headers)
    private fun ShelvesResponse.toPage(response: Response, kind: String): AnimesPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val items = payload.animes
            .filter { shelf ->
                (kind == "recent" && shelf.genre_slug == "__recent") ||
                    (kind == "popular" && shelf.genre_slug in setOf("__top10_a", "__top10_b"))
            }
            .sortedBy { if (it.genre_slug == "__top10_b") 0 else 1 }
            .flatMap { it.items }
            .distinctBy { it.tmdb_id }
            .map { item ->
                SAnime.create().apply {
                    url = "${item.media_type ?: "tv"}/${item.tmdb_id}"
                    title = item.title ?: item.tmdb_id.toString()
                    thumbnail_url = item.poster_path
                }
            }
        val pageItems = items.drop((page - 1) * 10).take(10)
        return AnimesPage(pageItems, pageItems.size == 10 && page * 10 < items.size)
    }
    private fun CatalogItem.toSAnime() = SAnime.create().apply {
        url = "${media_type ?: "tv"}/$id"
        title = name ?: title ?: id.toString()
        thumbnail_url = poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
    }
    private fun DetailsDto.toSAnime(path: String) = SAnime.create().apply {
        url = path
        title = name ?: title ?: original_name ?: original_title ?: id.toString()
        thumbnail_url = poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
        description = overview
        genre = genres.mapNotNull { it.name }.joinToString(", ")
        status = when (state?.lowercase()) {
            "ended", "canceled" -> SAnime.COMPLETED
            else -> SAnime.ONGOING
        }
    }

    @kotlinx.serialization.Serializable data class SearchResponse(val results: List<CatalogItem> = emptyList())
}
