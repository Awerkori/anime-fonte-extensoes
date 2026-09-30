package eu.kanade.tachiyomi.animeextension.pt.seriesflixnow

import android.util.Base64
import android.util.Log
import aniyomi.lib.fireplayerextractor.FireplayerExtractor
import aniyomi.lib.vidsrcextractor.VidsrcExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient

class SeriesFlixPlayerExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, headers: Headers, label: String): List<Video> {
        if (url.contains("vidsrc", true)) {
            val vidsrcVideos = runCatching {
                VidsrcExtractor(client, headers).videosFromUrl(url, hosterName = label)
            }.getOrElse {
                Log.d(TAG, "vidsrc failure=${it.javaClass.simpleName}:${it.message}")
                emptyList()
            }
            if (vidsrcVideos.isNotEmpty()) {
                return vidsrcVideos
            }
        }

        if (url.contains("vaiquecol.com", true)) {
            val vaiVideos = runCatching {
                FireplayerExtractor(client).videosFromUrl(
                    url,
                    videoNameGen = { quality -> "$label - FirePlayer - $quality" },
                    videoHost = "https://vaiquecol.com",
                )
            }.getOrElse {
                Log.d(TAG, "vaiquecol failure=${it.javaClass.simpleName}:${it.message}")
                emptyList()
            }
            if (vaiVideos.isNotEmpty()) {
                return vaiVideos
            }
        }

        if (!url.contains("plenoflu.com", true)) {
            Log.d(TAG, "provider unsupported url=${url.substringBefore('?')}")
            return emptyList()
        }

        val plenoHeaders = headers.newBuilder()
            .set("Referer", "https://www.seriesflixnet.com/")
            .set("Origin", "https://www.seriesflixnet.com")
            .build()
        try {
            val page = client.newCall(GET(url, plenoHeaders)).execute().use { it.body.string() }
            val contentId = Regex("DIRECT_EPISODE_ID\\s*=\\s*(\\d+)").find(page)?.groupValues?.get(1)
            val playerIds = if (contentId != null) {
                val options = client.newCall(
                    POST(
                        "https://plenoflu.com/api",
                        plenoHeaders,
                        FormBody.Builder().add("action", "getOptions").add("contentid", contentId).build(),
                    ),
                ).execute().use { it.body.string() }
                Regex("\"ID\"\\s*:\\s*(\\d+)").findAll(options).map { it.groupValues[1] }.toList()
            } else {
                Regex("data-id=[\"'](\\d+)[\"']").findAll(page).map { it.groupValues[1] }.toList()
            }
            Log.d(TAG, "pleno api options=${playerIds.size}")
            for (playerId in playerIds) {
                val videos = runCatching {
                    val player = client.newCall(
                        POST(
                            "https://plenoflu.com/api",
                            plenoHeaders,
                            FormBody.Builder().add("action", "getPlayer").add("video_id", playerId).build(),
                        ),
                    ).execute().use { it.body.string() }
                    val encoded = Regex("\"video_url\"\\s*:\\s*\"([^\"]+)").find(player)?.groupValues?.get(1)
                        ?: return@runCatching emptyList()
                    val embed = String(Base64.decode(encoded, Base64.DEFAULT))
                    if (!embed.contains("vaiquecol.com", true)) {
                        Log.d(TAG, "pleno provider ignored id=$playerId embed=${embed.substringBefore('?')}")
                        return@runCatching emptyList()
                    }
                    Log.d(TAG, "pleno embed=${embed.substringBefore('?')}")
                    FireplayerExtractor(client).videosFromUrl(
                        embed,
                        videoNameGen = { quality -> "$label - PlenoFlu - $quality" },
                        videoHost = "https://vaiquecol.com",
                    )
                }.getOrElse {
                    Log.d(TAG, "pleno provider failure=${it.javaClass.simpleName}:${it.message}")
                    emptyList()
                }
                if (videos.isNotEmpty()) {
                    return videos
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "pleno failure=${e.javaClass.simpleName}:${e.message}")
        }
        return emptyList()
    }

    private companion object {
        const val TAG = "SERIESFLIX_VIDEO_DEBUG"
    }
}
