package eu.kanade.tachiyomi.animeextension.pt.anikatsu.extractors

import android.util.Base64
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.bodyString
import keiyoushi.utils.flatMapCatching
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ByseExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val baseUrl: String,
) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    suspend fun videosFromUrl(url: String): List<Video> {
        val originalUrl = url.toHttpUrl()
        val id = originalUrl.pathSegments.getOrNull(1) ?: return emptyList()
        val userAgent = "Mozilla/5.0 (Linux; Android 10; TX6s) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
        val detailsHeaders = headers.newBuilder()
            .set("User-Agent", userAgent)
            .build()
        val base = "${originalUrl.scheme}://${originalUrl.host}"
        val direct = client.newCall(GET("$base/api/videos/$id/details", detailsHeaders)).execute()
        val isEmbedFlow = direct.code == 404
        val detailsResponse = if (isEmbedFlow) client.newCall(GET("$base/api/videos/$id/embed/details", detailsHeaders)).execute() else direct
        if (!detailsResponse.isSuccessful) return emptyList()
        val details = detailsResponse.bodyString().parseAs<DetailsDto>(JSON)
        val embedUrl = details.embedFrameUrl.orEmpty()

        if (embedUrl.isBlank()) {
            return emptyList()
        }

        val embedHttpUrl = embedUrl.toHttpUrl()
        val embedBase = "${embedHttpUrl.scheme}://${embedHttpUrl.host}"
        val prefix = if (isEmbedFlow) "embed/" else ""
        val settingsUrl = "$embedBase/api/videos/$id/${prefix}settings"
        val settings = client.newCall(GET(settingsUrl, detailsHeaders.newBuilder().set("Referer", embedUrl).set("Origin", embedBase).build())).execute()
        val settingsBody = settings.body?.string().orEmpty()
        val captcha = runCatching { settingsBody.parseAs<SettingsDto>(JSON).captchaRequired }.getOrDefault(false)
        if (captcha) {
            return emptyList()
        }
        val fingerprint = createFingerprint()
        val playbackUrl = "$embedBase/api/videos/$id/${prefix}playback"
        val playbackHeader = Headers.Builder().apply {
            set("User-Agent", userAgent)
            set("Accept", "*/*")
            set("Referer", embedUrl)
            set("Origin", embedBase)
            set("X-Embed-Parent", url)
        }.build()
        val playbackResponse = client.newCall(POST(playbackUrl, playbackHeader, JSON.encodeToString(FingerprintRequest(fingerprint)).toJsonRequestBody())).execute()
        if (!playbackResponse.isSuccessful) return emptyList()
        val response = playbackResponse.parseAs<PlaybackResponseDto>(JSON)
        val sources = response.sources ?: response.playback?.let {
            decrypt(it).parseAs<InnerResponseDto>(JSON).sources
        } ?: emptyList()
        return sources.flatMapCatching { source ->
            val videoUrl = (source.url ?: source.file).orEmpty().takeIf(String::isNotBlank)
                ?: return@flatMapCatching emptyList()
            playlistUtils.extractFromHls(videoUrl, videoNameGen = { "Byse - $it" })
        }
    }

    private fun createFingerprint(): FingerprintDto {
        val viewerId = randomHex()
        val deviceId = randomHex()
        val confidence = ((0.83 + SecureRandom().nextDouble() * 0.11) * 100).toInt() / 100.0
        val now = System.currentTimeMillis() / 1000
        val compact = "{\"viewer_id\":\"$viewerId\",\"device_id\":\"$deviceId\",\"confidence\":$confidence,\"iat\":$now,\"exp\":${now + 600}}"
        val data = Base64.encodeToString(compact.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val signature = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(data.toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return FingerprintDto(viewerId, deviceId, confidence, "$data.$signature")
    }

    private fun randomHex(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    private fun decrypt(input: PlaybackDto): String {
        val parts = input.key_parts.let { keys ->
            val version = input.version?.toIntOrNull()
            if (version != null && version in 1..keys.size) listOf(keys[version - 1], keys[keys.size - version]) else keys
        }
        val keyBytes = parts
            .map { decodeBase64Url(it) }
            .fold(ByteArray(0)) { acc, bytes -> acc + bytes }

        val ivBytes = decodeBase64Url(input.iv)
        val payloadBytes = decodeBase64Url(input.payload)

        val secretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, ivBytes)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        val decryptedBytes = cipher.doFinal(payloadBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    private fun decodeBase64Url(input: String): ByteArray {
        val base64 = input
            .replace('-', '+')
            .replace('_', '/')
        val padding = when (base64.length % 4) {
            2 -> "=="
            3 -> "="
            else -> ""
        }
        return Base64.decode(base64 + padding, Base64.DEFAULT)
    }

    companion object {
        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    @Serializable
    private data class DetailsDto(
        @SerialName("embed_frame_url") val embedFrameUrl: String? = null,
    )

    @Serializable
    private data class SettingsDto(
        @SerialName("captcha_required") val captchaRequired: Boolean = false,
    )

    @Serializable
    private data class FingerprintRequest(val fingerprint: FingerprintDto)

    @Serializable
    private data class FingerprintDto(
        @SerialName("viewer_id") val viewerId: String,
        @SerialName("device_id") val deviceId: String,
        val confidence: Double,
        val token: String,
    )

    @Serializable
    private data class PlaybackResponseDto(
        val sources: List<SourceDto>? = null,
        val playback: PlaybackDto? = null,
    )

    @Serializable
    private data class InnerResponseDto(val sources: List<SourceDto> = emptyList())

    @Serializable
    private data class SourceDto(val url: String? = null, val file: String? = null)

    @Serializable
    private data class PlaybackDto(
        val algorithm: String,
        val iv: String,
        val payload: String,
        val key_parts: List<String>,
        val version: String? = null,
    )
}

fun String.encodeUrlPath(): String {
    val uri = URI(this)

    val encodedPath = uri.rawPath
        .split("/")
        .joinToString("/") { segment ->
            if (segment.isEmpty()) {
                ""
            } else {
                URLEncoder.encode(segment, StandardCharsets.UTF_8.toString())
                    .replace("+", "%20")
            }
        }

    return URI(
        uri.scheme,
        uri.rawAuthority,
        encodedPath,
        uri.rawQuery,
        uri.rawFragment,
    ).toString()
}
