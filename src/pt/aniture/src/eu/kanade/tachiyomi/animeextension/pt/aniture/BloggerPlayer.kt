package eu.kanade.tachiyomi.animeextension.pt.aniture

import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/** Reuses the project's Blogger stream/RPC extractor; no WebView is needed for the observed MP4s. */
internal class BloggerPlayer(client: OkHttpClient, headers: Headers) {
    private val extractor = BloggerExtractor(client)
    private val playbackHeaders = headers.newBuilder().set("Referer", "https://www.blogger.com/").build()

    suspend fun videosFromUrl(url: String): List<Video> = extractor.videosFromUrl(url, playbackHeaders)
        .mapNotNull { video ->
            val media = video.videoUrl?.toHttpUrlOrNull() ?: return@mapNotNull null
            if (!media.host.endsWith(".googlevideo.com") || media.encodedPath != "/videoplayback") return@mapNotNull null
            // Progressive MP4 itags contain both audio and video. Do not expose video-only DASH tracks.
            val quality = when (media.queryParameter("itag")) {
                "18" -> "360p"
                "22" -> "720p"
                "37" -> "1080p"
                "59", "78" -> "480p"
                null -> if (media.queryParameter("mime") == "video/mp4") "Unknown" else return@mapNotNull null
                else -> return@mapNotNull null
            }
            Video(video.videoUrl, "Blogger - $quality", video.videoUrl, playbackHeaders)
        }
        .distinctBy { it.videoUrl }
        .also { Aniture.videoDebug("Blogger qualities=${it.joinToString { video -> video.videoTitle }}") }
}
