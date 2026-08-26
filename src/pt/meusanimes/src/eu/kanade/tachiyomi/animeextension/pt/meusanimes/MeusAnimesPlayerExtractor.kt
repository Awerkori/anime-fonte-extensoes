package eu.kanade.tachiyomi.animeextension.pt.meusanimes

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/** Resolves the public serv01 SPA API without sending it through the generic extractor. */
class MeusAnimesPlayerExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val browserExtractor by lazy { MeusAnimesBloggerBrowserExtractor(client, headers) }

    fun videosFromServ01(iframeUrl: String): List<Video> {
        val match = Regex("#/video/([^/]+)/([^/]+)/([^/?#]+)").find(iframeUrl) ?: return emptyList()
        val (tmdb, season, episode) = match.destructured
        val apiUrl = "https://serv01.meusdoramas.club/posts/get-video.php".toHttpUrl().newBuilder()
            .addQueryParameter("episode_number", episode)
            .addQueryParameter("season_number", season)
            .addQueryParameter("tmdb", tmdb)
            .build()

        val body = client.newCall(GET(apiUrl.toString(), headers)).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string().orEmpty()
        }
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        if (!root.optBoolean("success")) return emptyList()
        val value = root.opt("videoUrl")
        val sources = when (value) {
            is String -> listOf("Player" to value)
            is JSONArray -> (0 until value.length()).mapNotNull { index ->
                val item = value.optJSONObject(index) ?: return@mapNotNull null
                val url = item.optString("url").ifBlank { item.optString("videoUrl") }
                url.takeIf(String::isNotBlank) to item.optString("label").ifBlank { "Player" }
            }.mapNotNull { (url, label) -> url?.let { label to it } }
            else -> emptyList()
        }

        return sources.distinctBy { it.second }.flatMap { (label, url) ->
            if ("blogger.com" in url) {
                val browserVideos = browserExtractor.videosFromUrl(url)
                browserVideos.map { video ->
                    Video(video.url, "$label: ${video.quality}", video.videoUrl, video.headers ?: headers)
                }
            } else if (url.startsWith("https://") || url.startsWith("http://")) {
                listOf(Video(url, label, url, headers))
            } else {
                emptyList()
            }
        }
    }
}
