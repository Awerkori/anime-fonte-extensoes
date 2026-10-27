package eu.kanade.tachiyomi.animeextension.pt.berryanimes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal class Catalog(val items: List<Card>, val hasMore: Boolean)

@Serializable
internal class Card(val title: String, val detailsHref: String, val coverUrl: String)

@Serializable
internal class Home(val initialRails: List<Rail>)

@Serializable
internal class Rail(val title: String, val items: List<Card>)

@Serializable
internal class EpisodeGrid(val episodes: List<EpisodeCard>)

@Serializable
internal class EpisodeCard(
    val href: String,
    val title: String,
    val meta: String? = null,
    val hasSubtitles: Boolean? = null,
    val subtitlesLabel: String? = null,
    val hasDub: Boolean? = null,
    val dubLabel: String? = null,
    val season: Int? = null,
    val number: Float? = null,
    val duration: String? = null,
    val thumbnailUrl: String? = null,
    val synopsis: String? = null,
    val ageRating: String? = null,
    val releaseDate: String? = null,
    val releaseDateIso: String? = null,
)

@Serializable
internal class Watch(val player: Player? = null, val ads: Ads? = null, val error: String? = null)

@Serializable
internal class Ads(
    val preRoll: Boolean? = null,
    val postRoll: Boolean? = null,
    val adFree: AdFree? = null,
    val required: Boolean? = null,
    val mandatory: Boolean? = null,
    val blocking: Boolean? = null,
    val enabled: Boolean? = null,
)

@Serializable
internal class AdFree(val active: Boolean? = null)

@Serializable
internal class Player(
    val provider: String? = null,
    @SerialName("embed_url") val embedUrl: String? = null,
    val externalEmbedUrl: String? = null,
    val bloggerUrl: String? = null,
    val fallbackBloggerUrl: String? = null,
    val streamUrl: String? = null,
    val mimeType: String? = null,
    val streamProviderHeader: String = "x-berry-provider",
    val streamProviderValue: String = "berryanimes",
    val streamContextHeader: String = "x-berry-context",
    val streamContextToken: String? = null,
    @SerialName("codex_subtitle_tracks") val subtitles: List<Subtitle> = emptyList(),
)

@Serializable
internal class Subtitle(val url: String, val label: String)

@Serializable
internal class StructuredData(@SerialName("@graph") val graph: List<Metadata>)

@Serializable
internal class Metadata(
    @SerialName("@type") val type: String,
    val name: String = "",
    val alternateName: String? = null,
    val description: String? = null,
    val image: String? = null,
    val genre: List<String> = emptyList(),
    val datePublished: String? = null,
    val contentRating: String? = null,
    val numberOfSeasons: Int? = null,
    val numberOfEpisodes: Int? = null,
)
