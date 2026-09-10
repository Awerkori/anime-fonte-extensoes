package eu.kanade.tachiyomi.animeextension.pt.aniture

import aniyomi.lib.m3u8server.M3u8ServerManager
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.OkHttpClient

internal class AnitureM3u8Integration(client: OkHttpClient) {
    private val manager = M3u8ServerManager(client)

    fun processVideoList(videos: List<Video>): List<Video> {
        if (!manager.isRunning()) runCatching { manager.startServer() }
        return videos.map { video ->
            if (!Regex("\\.m3u8($|\\?|#)", RegexOption.IGNORE_CASE).containsMatchIn(video.videoUrl)) return@map video
            val headers = video.headers
            val processed = manager.processM3u8Url(video.videoUrl, headers?.get("Referer"), headers?.get("User-Agent"))
            Video(
                videoUrl = processed ?: video.videoUrl,
                videoTitle = video.videoTitle,
                subtitleTracks = video.subtitleTracks,
                audioTracks = video.audioTracks,
                headers = headers,
            )
        }
    }
}
