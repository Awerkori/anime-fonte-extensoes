package eu.kanade.tachiyomi.animeextension.pt.redetoons

import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

// Watching needs a session on redetoons.li: log in, open /br/watch/... for its embedUrl, then ask /api/play/{p} for the MP4.
@Serializable
class LoginForm(
    val username: String,
    val password: String,
    val remember: Boolean,
    @SerialName("recaptcha_token") val recaptchaToken: String,
)

@Serializable
class PageHead(val component: String, val version: String? = null)

@Serializable
class AuthProps(val auth: SiteAuth? = null)

@Serializable
class SiteAuth(val user: JsonElement? = null)

@Serializable
class WatchProps(val embedUrl: String? = null)

@Serializable
class PlaySource(val audio: String? = null, val label: String? = null, val src: String)

@Serializable
class PlayResponse(val src: String, val sources: List<PlaySource> = emptyList())

@Serializable
class RenewRequest(val p: String)

@Serializable
class RenewResponse(val p: String)

class EpisodeRef(val type: String, val id: Int, val season: Int, val position: Int)

private const val WATCH_COMPONENT = "Titles/Watch"

// The login POST must echo the page version, or the site answers with a full reload.
fun inertiaVersion(document: Document): String? = runCatching { pageData(document).parseAs<PageHead>().version }.getOrNull()

fun isLoggedIn(document: Document): Boolean = runCatching {
    val user = pageData(document).parseAs<SitePage<AuthProps>>().props.auth?.user
    user != null && user !is JsonNull
}.getOrDefault(false)

// Without a session the site answers the watch url with its login page, which has no embedUrl.
fun watchEmbedUrl(document: Document): String? = runCatching {
    val data = pageData(document)
    if (data.parseAs<PageHead>().component != WATCH_COMPONENT) return null
    data.parseAs<SitePage<WatchProps>>().props.embedUrl
}.getOrNull()

fun playToken(embedUrl: String): String? = "https://redetoons.li".toHttpUrl().resolve(embedUrl)?.queryParameter("p")

fun playSources(response: PlayResponse): List<PlaySource> = response.sources.ifEmpty { listOf(PlaySource(src = response.src)) }

fun sourceTitle(source: PlaySource): String = "RedeToons - ${source.label ?: source.audio ?: "Padrão"}"

private val tvEpisodeUrl = Regex("""^(\d+)\|(\d+)\|(\d+)$""")
private val movieEpisodeUrl = Regex("""^movie\|(\d+)$""")

// Episode urls keep the formats the old source saved: "<id>|<season>|<episode>" and "movie|<id>".
fun episodeRef(url: String): EpisodeRef? {
    movieEpisodeUrl.find(url)?.let { return EpisodeRef("movie", it.groupValues[1].toInt(), 0, 1) }
    return tvEpisodeUrl.find(url)?.let { EpisodeRef("tv", it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
}

fun episodeIdAt(season: SiteSeason?, position: Int): Long? = season?.episodes?.getOrNull(position - 1)?.id

fun watchPath(ref: EpisodeRef, episodeId: Long?): String = if (ref.type == "movie") {
    "/br/watch/${ref.id}?type=movie"
} else {
    "/br/watch/${ref.id}?e=${episodeId!!}&type=tv&s=${ref.season}"
}
