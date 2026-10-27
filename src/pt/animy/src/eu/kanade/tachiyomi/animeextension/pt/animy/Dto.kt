package eu.kanade.tachiyomi.animeextension.pt.animy

import kotlinx.serialization.Serializable

@Serializable
data class AnimesResponseDto(
    val items: List<AnimeItemDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class AnimeItemDto(
    val id: Long? = null,
    val t: String,
    val slug: String? = null,
    val cover: String? = null,
    val desc: String? = null,
    val score: Double? = null,
    val views: Long? = null,
    val eps: Int? = null,
    val kind: String? = null,
    val audio: String? = null,
    val audios: List<String>? = null,
    val age: Int? = null,
    val g: List<String>? = null,
    val y: Int? = null,
    val status: String? = null,
)

@Serializable
data class AnimeDetailsDto(
    val id: Long? = null,
    val t: String,
    val slug: String? = null,
    val nativeTitle: String? = null,
    val cover: String? = null,
    val desc: String? = null,
    val score: Double? = null,
    val views: Long? = null,
    val eps: Int? = null,
    val kind: String? = null,
    val audio: String? = null,
    val audios: List<String>? = null,
    val age: Int? = null,
    val g: List<String>? = null,
    val y: Int? = null,
    val status: String? = null,
    val published: String? = null,
    val seasonOfYear: String? = null,
    val durationMin: Int? = null,
    val episodes: List<EpisodeItemDto>? = null,
)

@Serializable
data class EpisodeItemDto(
    val id: Long? = null,
    val season: Int = 1,
    val number: Float = 1f,
    val numberLabel: String? = null,
    val title: String? = null,
    val date: String? = null,
    val thumb: String? = null,
)

@Serializable
data class EpisodeDetailsDto(
    val id: Long? = null,
    val season: Int = 1,
    val number: Float = 1f,
    val numberLabel: String? = null,
    val title: String? = null,
    val date: String? = null,
    val thumb: String? = null,
    val maxThumb: String? = null,
    val streams: List<StreamDto> = emptyList(),
)

@Serializable
data class StreamDto(
    val id: Long? = null,
    val kind: String? = null,
    val url: String,
    val audio: String? = null,
)
