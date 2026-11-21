package eu.kanade.tachiyomi.animeextension.pt.cineveo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// cineveo.cfd serves a JSON API at /api; the HTML pages only render what it returns.
@Serializable
class ApiItem(
    val id: Long,
    val type: String,
    val name: String,
    val poster: String? = null,
    @SerialName("poster_large") val posterLarge: String? = null,
    val plot: String? = null,
    val genre: String? = null,
    val extension: String? = null,
)

@Serializable
class ApiCatalog(val items: List<ApiItem> = emptyList())

@Serializable
class ApiEpisode(
    val id: Long,
    val season: Int,
    val episode: Int,
    val title: String = "",
    val extension: String? = null,
    val audio: String? = null,
)

@Serializable
class ApiDetails(
    val item: ApiItem,
    val episodes: List<ApiEpisode> = emptyList(),
)

class EpisodeEntry(val name: String, val url: String, val number: Float, val audio: String?)

// The API returns 32 items per page; page 1 reports a wrong totalPages, so only a full page proves there is more.
const val PAGE_SIZE = 32

fun detailsPath(type: String, id: Long): String = "/api?action=details&type=$type&id=$id"

fun streamPath(type: String, id: Long, extension: String?): String = "/api?action=stream&type=$type&id=$id&ext=${extension ?: "mp4"}"

fun episodeEntries(details: ApiDetails): List<EpisodeEntry> {
    val item = details.item
    if (item.type == "movie") {
        return listOf(EpisodeEntry("Filme", streamPath("movie", item.id, item.extension), 1f, null))
    }
    // Number across seasons in play order so "next episode" works when a new season starts at E1.
    return details.episodes
        .sortedWith(compareBy({ it.season }, { it.episode }))
        .mapIndexed { index, ep ->
            val label = "T${ep.season} E${ep.episode}"
            EpisodeEntry(
                name = if (ep.title.isBlank()) label else "$label - ${ep.title}",
                url = streamPath("series", ep.id, ep.extension),
                number = (index + 1).toFloat(),
                audio = ep.audio,
            )
        }
        .reversed()
}

fun hasNextPage(itemCount: Int): Boolean = itemCount >= PAGE_SIZE

// A 200 text/html here is the site's login/error page, not a stream.
fun isPlayable(code: Int, contentType: String?): Boolean = code in 200..299 && contentType?.startsWith("video/") == true

fun audioLabel(audio: String?): String? = when (audio?.lowercase()) {
    "dub" -> "Dublado"
    "leg" -> "Legendado"
    else -> null
}
