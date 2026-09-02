package eu.kanade.tachiyomi.animeextension.pt.seriesflixnow

import android.util.Base64
import android.util.Log
import aniyomi.lib.fireplayerextractor.FireplayerExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient

class SeriesFlixPlayerExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, headers: Headers, label: String): List<Video> {
        if (!url.contains("plenoflu.com", true)) {
            Log.d(TAG, "provider unsupported url=${url.substringBefore('?')}")
            return emptyList()
        }

        val plenoHeaders = headers.newBuilder()
            .set("Referer", "https://www.seriesflixnow.com/")
            .set("Origin", "https://www.seriesflixnow.com")
            .build()
        return runCatching {
            val page = client.newCall(GET(url, plenoHeaders)).execute().use { it.body?.string().orEmpty() }
            val contentId = Regex("DIRECT_EPISODE_ID\\s*=\\s*(\\d+)").find(page)?.groupValues?.get(1)
                ?: error("DIRECT_EPISODE_ID missing")
            val options = client.newCall(
                POST(
                    "https://plenoflu.com/api",
                    plenoHeaders,
                    FormBody.Builder().add("action", "getOptions").add("contentid", contentId).build(),
                ),
            ).execute().use { it.body?.string().orEmpty() }
            val playerIds = Regex("\"ID\"\\s*:\\s*(\\d+)").findAll(options).map { it.groupValues[1] }.toList()
            Log.d(TAG, "pleno api options=${playerIds.size}")
            playerIds.flatMap { playerId ->
                runCatching {
                    val player = client.newCall(
                        POST(
                            "https://plenoflu.com/api",
                            plenoHeaders,
                            FormBody.Builder().add("action", "getPlayer").add("video_id", playerId).build(),
                        ),
                    ).execute().use { it.body?.string().orEmpty() }
                    val encoded = Regex("\"video_url\"\\s*:\\s*\"([^\"]+)").find(player)?.groupValues?.get(1)
                        ?: return@runCatching emptyList()
                    val embed = String(Base64.decode(encoded, Base64.DEFAULT))
                    if (!embed.contains("vaiquecol.com", true)) {
                        Log.d(TAG, "pleno provider ignored id=$playerId")
                        return@runCatching emptyList()
                    }
                    Log.d(TAG, "pleno embed=${embed.substringBefore('?')}")
                    FireplayerExtractor(client).videosFromUrl(
                        embed,
                        videoNameGen = { quality -> "$label - PlenoFlu - $quality" },
                        videoHost = "https://vaiquecol.com",
                    )
                }.getOrElse {
                    Log.d(TAG, "pleno provider failure=${it.javaClass.simpleName}")
                    emptyList()
                }
            }
        }.getOrElse {
            Log.d(TAG, "pleno failure=${it.javaClass.simpleName}:${it.message}")
            emptyList()
        }
    }

    private companion object {
        const val TAG = "SERIESFLIX_VIDEO_DEBUG"
    }
}
