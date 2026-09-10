package eu.kanade.tachiyomi.animeextension.pt.aniture

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * STRP2P's current Tiktok delivery, audited against index-We2AAQnB.js.
 * The /v4/ delivery additionally needs renewable k/kx credentials and is deliberately not exposed.
 */
internal class StrpPlayer(private val client: OkHttpClient, private val headers: Headers) {
    private val hlsServer by lazy { AnitureM3u8Integration(client) }

    suspend fun videosFromUrl(frame: String): List<Video> {
        val url = frame.toHttpUrl()
        val id = url.fragment?.substringBefore('&')?.takeIf { it.isNotBlank() } ?: return emptyList()
        val origin = "${url.scheme}://${url.host}"
        val playerHeaders = headers.newBuilder().set("Referer", "$origin/").build()
        // Bootstrap the same public resources before asking for playback configuration.
        client.newCall(GET(frame, headers)).awaitSuccess().close()
        val infoUrl = "$origin/api/v1/info".toHttpUrl().newBuilder().addQueryParameter("id", id).build()
        client.newCall(GET(infoUrl, playerHeaders)).awaitSuccess().close()
        val api = "$origin/api/v1/video".toHttpUrl().newBuilder()
            .addQueryParameter("id", id)
            .addQueryParameter("w", "1280")
            .addQueryParameter("h", "720")
            .addQueryParameter("r", "aniture-pt.com.br")
            .build()
        val body = client.newCall(GET(api, playerHeaders)).awaitSuccess().bodyString()
        val data = decrypt(body).parseAs<Playback>()
        val config = data.streamingConfig.parseAs<StreamingConfig>()
        val adjustment = config.adjust["Tiktok"] ?: return emptyList()
        if ("Tiktok" !in config.order || adjustment.disabled || data.hlsVideoTiktok.isBlank()) return emptyList()
        val master = url.resolve(data.hlsVideoTiktok) ?: return emptyList()
        val adjusted = master.newBuilder().apply {
            if (adjustment.domain.isNotEmpty() && "/hls/" in master.encodedPath) {
                // Same /hls/ -> /hlsmod/<domain>/ rewrite performed by the site's Ae() function.
                require(adjustment.domain.matches(Regex("[a-zA-Z0-9.-]+")))
                encodedPath(master.encodedPath.replace("/hls/", "/hlsmod/${adjustment.domain}/"))
            }
            (adjustment.params as? JsonObject)?.forEach { (name, value) -> setQueryParameter(name, value.jsonPrimitive.content) }
        }.build()
        val videos = withContext(Dispatchers.IO) {
            PlaylistUtils(client, playerHeaders).extractFromHls(
                adjusted.toString(),
                masterHeaders = playerHeaders,
                videoHeaders = playerHeaders,
                videoNameGen = { "STRP2P - $it" },
            )
        }
        // A 200 master is insufficient: require a real media playlist and readable segment.
        val validated = videos.filter { video ->
            val variant = video.videoUrl?.toHttpUrl() ?: return@filter false
            try {
                val playlist = client.newCall(GET(variant, playerHeaders)).awaitSuccess().bodyString()
                if (!playlist.startsWith("#EXTM3U") || "#EXTINF:" !in playlist) return@filter false
                val segment = playlist.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith('#') }
                    ?.let(variant::resolve) ?: return@filter false
                client.newCall(GET(segment, playerHeaders.newBuilder().set("Range", "bytes=0-1023").build()))
                    .awaitSuccess().use { response ->
                        val readable = response.body.source().request(188)
                        Aniture.videoDebug("STRP2P variant=200 segment=${response.code} readable=$readable")
                        readable
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Aniture.videoDebug("STRP2P validation failed type=${e.javaClass.simpleName}")
                false
            }
        }
        // The CDN prepends PNG bytes to MPEG-TS; native players need clean segments.
        return if (validated.isEmpty()) emptyList() else hlsServer.processVideoList(validated)
    }

    private fun decrypt(body: String): String {
        val hex = body.trim()
        require(hex.length % 2 == 0 && hex.all { it.digitToIntOrNull(16) != null })
        val encrypted = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        // Public, static obfuscation constants generated by F()/J() in the site's JavaScript.
        // Playback tokens, media paths and queries always come from a fresh API response.
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec("kiemtienmua911ca".toByteArray(), "AES"),
            IvParameterSpec("1234567890oiuytr".toByteArray()),
        )
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    @Serializable
    private class Playback(val streamingConfig: String, val hlsVideoTiktok: String = "")

    @Serializable
    private class StreamingConfig(val order: List<String>, val adjust: Map<String, Adjustment>)

    @Serializable
    private class Adjustment(val disabled: Boolean = false, val domain: String = "", val params: JsonElement = JsonNull)
}
