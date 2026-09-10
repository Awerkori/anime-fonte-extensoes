package eu.kanade.tachiyomi.animeextension.pt.animexhd

import aniyomi.lib.bloggerextractor.BloggerExtractor
import aniyomi.lib.fireplayerextractor.FireplayerExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parseAs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

internal class MyEmbedExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val blogger = BloggerExtractor(client)
    private val fireplayer = FireplayerExtractor(client)

    suspend fun videosFromUrl(frame: String): List<Video> {
        if (frame.toHttpUrlOrNull()?.host != "myembed.biz") return emptyList()
        return try {
            resolve(frame)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AnimeXHD.videoDebug("MyEmbed failed type=${e.javaClass.simpleName}")
            emptyList()
        }
    }

    private suspend fun resolve(frame: String): List<Video> {
        // Without the embedding site's Referer, MyEmbed serves an unrelated landing page.
        val doc = client.newCall(GET(frame, headers)).awaitSuccess().use { it.asJsoup() }
        val nested = doc.selectFirst("iframe#video-player[src]")?.absUrl("src")?.toHttpUrlOrNull() ?: return emptyList()
        val playerHeaders = headers.newBuilder().set("Referer", frame).build()
        client.newCall(GET(nested, playerHeaders)).awaitSuccess().close()
        val path = nested.pathSegments.filter(String::isNotEmpty)
        val type = when (path.firstOrNull()) {
            "serie" -> "tv"
            "filme" -> "movie"
            else -> return emptyList()
        }
        val id = path.getOrNull(1) ?: return emptyList()
        val api = nested.resolve("/inc/Ajax.php")!!.newBuilder()
            .addQueryParameter("type", type).addQueryParameter("id", id).apply {
                if (type == "tv") {
                    addQueryParameter("season", path.getOrNull(2) ?: return emptyList())
                    addQueryParameter("episode", path.getOrNull(3) ?: return emptyList())
                }
            }.build()
        val apiHeaders = headers.newBuilder().set("Referer", nested.toString())
            .set("X-Requested-With", "XMLHttpRequest").build()
        val response = client.newCall(GET(api, apiHeaders)).awaitSuccess().parseAs<PlayerResponse>()
        if (!response.status) return emptyList()
        val options = response.data?.options.orEmpty()
        AnimeXHD.videoDebug("options=${options.size} hosts=${options.mapNotNull { it.embed.toHttpUrlOrNull()?.host }.distinct().joinToString()}")
        val videos = mutableListOf<Video>()
        // Prefer progressive Blogger MP4, then the shared Fireplayer HLS extractor.
        for (option in options.sortedBy { it.embed.toHttpUrlOrNull()?.host != "www.blogger.com" }) {
            val url = option.embed.toHttpUrlOrNull() ?: continue
            val language = when (option.lang.lowercase()) {
                "pt-br" -> "Dublado"
                "en-us" -> "Legendado" // Same mapping used by Playerflix's renderOptions().
                else -> "Idioma não informado"
            }
            try {
                val resolved = withTimeoutOrNull(12_000) {
                    when (url.host) {
                        "www.blogger.com", "blogger.com" -> {
                            val mediaHeaders = headers.newBuilder().set("Referer", "https://www.blogger.com/").build()
                            blogger.videosFromUrl(option.embed, mediaHeaders).mapNotNull { video ->
                                val media = video.videoUrl?.toHttpUrlOrNull() ?: return@mapNotNull null
                                if (!media.host.endsWith(".googlevideo.com") || media.encodedPath != "/videoplayback") return@mapNotNull null
                                val quality = when (media.queryParameter("itag")) {
                                    "18" -> "360p"
                                    "22" -> "720p"
                                    "37" -> "1080p"
                                    "59", "78" -> "480p"
                                    null -> if (media.queryParameter("mime") == "video/mp4") "Unknown" else return@mapNotNull null
                                    else -> return@mapNotNull null
                                }
                                Video(video.videoUrl, "MyEmbed - $language - $quality (Blogger)", video.videoUrl, mediaHeaders)
                            }
                        }
                        "embedplayer2.xyz" -> withContext(Dispatchers.IO) {
                            fireplayer.videosFromUrl(option.embed, videoNameGen = { "MyEmbed - $language - $it (VIP)" })
                        }
                        else -> emptyList() // Superflix currently presents a Cloudflare block, including in the browser.
                    }
                }.orEmpty()
                videos += resolved
                AnimeXHD.videoDebug("server=${url.host} language=$language videos=${resolved.size}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AnimeXHD.videoDebug("server=${url.host} failed type=${e.javaClass.simpleName}")
            }
        }
        return videos
    }

    @Serializable private class PlayerResponse(val status: Boolean = false, val data: PlayerData? = null)

    @Serializable private class PlayerData(val options: List<Option> = emptyList())

    @Serializable private class Option(val embed: String, val lang: String = "")
}
