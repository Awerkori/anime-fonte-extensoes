package eu.kanade.tachiyomi.animeextension.pt.redetoons

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class CatalogResponse(val items: List<CatalogItem> = emptyList(), val hasMore: Boolean = false)

@Serializable data class CatalogItem(val id: Int, val tmdb_id: Int? = null, val media_type: String? = null, val title: String? = null, val name: String? = null, val poster_path: String? = null, val year: String? = null)

@Serializable data class ShelvesResponse(val payload: ShelvesPayload = ShelvesPayload())

@Serializable data class ShelvesPayload(val animes: List<Shelf> = emptyList())

@Serializable data class Shelf(val title: String? = null, val genre_slug: String? = null, val items: List<ShelfItem> = emptyList())

@Serializable data class ShelfItem(val tmdb_id: Int, val media_type: String? = null, val title: String? = null, val poster_path: String? = null, val year: String? = null)

@Serializable data class Genre(val name: String? = null)

@Serializable data class DetailsDto(
    val id: Int,
    val name: String? = null,
    val title: String? = null,
    val original_name: String? = null,
    val original_title: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val first_air_date: String? = null,
    val release_date: String? = null,
    val genres: List<Genre> = emptyList(),
    @SerialName("status") val state: String? = null,
    val number_of_seasons: Int? = null,
)

@Serializable data class EpisodesResponse(val episodes: List<PlayableEpisode> = emptyList())

@Serializable data class PlayableEpisode(val s: Int, val e: Int, val name: String? = null)

@Serializable data class PlayLinkResponse(val contract: Int? = null, val url: String? = null, val variants: List<Variant> = emptyList(), val missing: Boolean = false)

@Serializable data class Variant(val quality: String? = null, val url: String? = null, val mirrors: List<String> = emptyList())
