package eu.kanade.tachiyomi.animeextension.pt.tomato.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class SearchAnimeItemDto(val id: Int, val name: String, val image: String, val tags: String = "")

@Serializable data class SearchResultDto(val result: List<SearchAnimeItemDto> = emptyList())

@Serializable data class AnimeDetailsDto(
    @SerialName("anime_id") val animeId: Int,
    @SerialName("anime_name") val animeName: String,
    @SerialName("anime_description") val animeDescription: String = "",
    @SerialName("anime_cover_url") val animeCoverUrl: String? = null,
    @SerialName("anime_genre") val animeGenre: String = "",
)

@Serializable data class AnimeSeasonDto(
    @SerialName("season_id") val seasonId: Int,
    @SerialName("season_name") val seasonName: String = "",
    @SerialName("season_number") val seasonNumber: Int,
    @SerialName("season_dubbed") val seasonDubbed: Int = 0,
)

@Serializable data class AnimeResultDto(
    @SerialName("anime_details") val animeDetails: AnimeDetailsDto,
    @SerialName("anime_seasons") val animeSeasons: List<AnimeSeasonDto> = emptyList(),
)

@Serializable data class EpisodesItemDto(
    @SerialName("ep_id") val epId: Int,
    @SerialName("ep_name") val epName: String = "Episódio",
    @SerialName("ep_number") val epNumber: Float = 0f,
    @SerialName("ep_thumbnail") val epThumbnail: String? = null,
)

@Serializable data class EpisodesResultDto(val episodes: Int = 0, val data: List<EpisodesItemDto> = emptyList())

@Serializable data class EpisodeStreamDto(val shd: String? = null, val mhd: String? = null, val fhd: String? = null)

@Serializable data class EpisodeInfoDto(val streams: EpisodeStreamDto)
