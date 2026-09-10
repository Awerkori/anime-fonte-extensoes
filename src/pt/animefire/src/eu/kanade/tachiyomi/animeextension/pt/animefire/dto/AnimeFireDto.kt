package eu.kanade.tachiyomi.animeextension.pt.animefire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class AFResponse<T>(val data: T, val meta: PageMeta? = null)

@Serializable
class PageMeta(
    @SerialName("current_page") val currentPage: Int = 1,
    @SerialName("last_page") val lastPage: Int = 1,
    val genres: List<String> = emptyList(),
)

@Serializable
class Home(val carousels: List<Carousel>)

@Serializable
class Carousel(val key: String, val items: List<Card>)

@Serializable
class Card(
    val id: String,
    val title: String,
    @SerialName("poster_src") val poster: String? = null,
)

@Serializable
class AnimeDetails(
    val hero: Hero,
    val seasons: List<Season> = emptyList(),
    val episodes: List<Episode> = emptyList(),
)

@Serializable
class Hero(
    val id: String,
    val titles: Map<String, String>,
    val synopsis: String? = null,
    @SerialName("poster_src") val poster: String? = null,
    val status: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("age_rating") val ageRating: String? = null,
    val audio: String? = null,
    val runtime: String? = null,
    val genres: List<String> = emptyList(),
)

@Serializable
class Season(val number: Int, val title: String? = null)

@Serializable
class Episode(
    val id: String,
    val title: String? = null,
    val season: Int? = null,
    val number: Float,
    val audio: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
class EpisodeDetails(
    val id: String,
    val anime: Card,
    val streams: List<Stream> = emptyList(),
)

@Serializable
class Stream(
    val audio: String? = null,
    val url: String? = null,
    @SerialName("is_offline") val offline: Boolean = false,
    @SerialName("is_mtl") val machineTranslated: Boolean = false,
)
