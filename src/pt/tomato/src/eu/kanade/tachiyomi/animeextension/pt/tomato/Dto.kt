package eu.kanade.tachiyomi.animeextension.pt.tomato

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class CheckUpdateRequestDto(
    @SerialName("app_version") val appVersion: String,
)

@Serializable
class CheckUpdateResponseDto(
    @SerialName("status_code") val statusCode: Int? = null,
    @SerialName("server_version") val serverVersion: String? = null,
    @SerialName("require_captcha") val requireCaptcha: Boolean? = null,
    @SerialName("sync_playheads") val syncPlayheads: Boolean? = null,
    @SerialName("basic_token") val basicToken: String? = null,
)

@Serializable
class LoginRequestDto(
    val email: String,
    val password: String,
    val verification: String,
    val fingerprint: String,
)

@Serializable
class RegisterRequestDto(
    val username: String,
    val email: String,
    val password: String,
    val verification: String,
    val fingerprint: String,
)

@Serializable
class AuthResponseDto(
    @SerialName("status_code") val statusCode: Int? = null,
    val token: String? = null,
    @SerialName("user_id") val userId: Int? = null,
    @SerialName("user_name") val userName: String? = null,
    @SerialName("user_email") val userEmail: String? = null,
    val message: String? = null,
)

@Serializable
data class FeedResponseDto(
    @SerialName("status_code") val statusCode: Int? = null,
    val status: Boolean? = null,
    val data: List<FeedSectionDto> = emptyList(),
)

@Serializable
data class FeedSectionDto(
    val type: Int? = null,
    val title: String? = null,
    val data: List<FeedAnimeItemDto> = emptyList(),
)

@Serializable
data class FeedAnimeItemDto(
    @SerialName("anime_id") val animeId: Int? = null,
    @SerialName("ep_anime_id") val epAnimeId: Int? = null,
    @SerialName("anime_name") val animeName: String? = null,
    val name: String? = null,
    val title: String? = null,
    val thumbnail: String? = null,
    val image: String? = null,
    val cover: String? = null,
    val banner: String? = null,
    @SerialName("anime_cover_url") val animeCoverUrl: String? = null,
)

@Serializable
data class AnimeDetailsContainerDto(
    @SerialName("anime_details") val animeDetails: AnimeDetailsDto,
    @SerialName("anime_seasons") val animeSeasons: List<AnimeSeasonDto> = emptyList(),
)

@Serializable
data class AnimeDetailsDto(
    @SerialName("anime_id") val animeId: Int,
    @SerialName("anime_name") val animeName: String,
    @SerialName("anime_description") val animeDescription: String? = null,
    @SerialName("anime_cover_url") val animeCoverUrl: String? = null,
    @SerialName("anime_cape_url") val animeCapeUrl: String? = null,
    @SerialName("anime_banner_url") val animeBannerUrl: String? = null,
    @SerialName("anime_genre") val animeGenre: String? = null,
    @SerialName("anime_date") val animeDate: String? = null,
    @SerialName("anime_episodes") val animeEpisodes: Int? = null,
)

@Serializable
data class AnimeSeasonDto(
    @SerialName("season_id") val seasonId: Int,
    @SerialName("season_name") val seasonName: String,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("season_dubbed") val seasonDubbed: Int? = null,
)

fun AnimeDetailsContainerDto.toSAnime(): SAnime = SAnime.create().apply {
    val details = animeDetails
    url = "/v2/anime/${details.animeId}"
    title = details.animeName
    description = details.animeDescription
    genre = details.animeGenre
    thumbnail_url = details.animeCapeUrl ?: details.animeCoverUrl ?: details.animeBannerUrl
    status = SAnime.UNKNOWN
}

@Serializable
data class SearchRequestDto(
    val token: String,
    val search: String,
    @SerialName("content_type") val contentType: String,
    val page: Int,
    val tags: List<String>,
)

@Serializable
data class SearchAnimeItemDto(
    val id: Int,
    val name: String,
    val image: String? = null,
    val episodes: Int? = null,
    val type: String? = null,
    val date: String? = null,
    val tags: String? = null,
)

@Serializable
data class SearchResponseDto(
    @SerialName("status_code") val statusCode: Int? = null,
    val result: List<SearchAnimeItemDto> = emptyList(),
)

fun SearchAnimeItemDto.toSAnime(): SAnime = SAnime.create().apply {
    url = "/v2/anime/$id"
    title = name
    thumbnail_url = image
}

@Serializable
data class SeasonEpisodesRequestDto(
    val token: String,
    val page: Int,
    val order: String,
)

@Serializable
data class SeasonEpisodesResponseDto(
    val status: Boolean? = null,
    val episodes: Int? = null,
    val data: List<EpisodeItemDto> = emptyList(),
)

@Serializable
data class EpisodeItemDto(
    @SerialName("ep_id") val epId: Int,
    @SerialName("ep_name") val epName: String,
    @SerialName("ep_number") val epNumber: Float = 0f,
    @SerialName("ep_thumbnail") val epThumbnail: String? = null,
    @SerialName("ep_lenght_minutes") val epLengthMinutes: Int? = null,
    val dubbed: Boolean? = null,
)

@Serializable
data class EpisodeInfoDto(
    val streams: EpisodeStreamDto,
    @SerialName("episodeNumber") val episodeNumber: Float? = null,
    @SerialName("episodeName") val episodeName: String? = null,
)

@Serializable
data class EpisodeStreamDto(
    val shd: String? = null,
    val mhd: String? = null,
    val fhd: String? = null,
)

@Serializable
data class TokenLoginRequestDto(
    val token: String,
    val fingerprint: String,
)

@Serializable
data class TokenLoginResponseDto(
    val status: Boolean? = null,
    @SerialName("status_code") val statusCode: Int? = null,
    val message: String? = null,
    val uuid: String? = null,
    @SerialName("user_email") val userEmail: String? = null,
    @SerialName("user_name") val userName: String? = null,
)
