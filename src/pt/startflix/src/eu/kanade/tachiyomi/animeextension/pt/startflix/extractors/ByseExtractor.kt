package eu.kanade.tachiyomi.animeextension.pt.startflix.extractors

import android.util.Base64
import android.util.Log
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.POST
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ByseExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(parentUrl: String, label: String = "Byse"): List<Video> = runCatching {
        val parent = parentUrl.toHttpUrlOrNull() ?: return@runCatching emptyList()
        val pathSegments = parent.pathSegments
        val codeIndex = pathSegments.indexOf("e")
        val code = if (codeIndex != -1 && codeIndex + 1 < pathSegments.size) {
            pathSegments[codeIndex + 1]
        } else {
            pathSegments.firstOrNull { it.length >= 8 && it.all { c -> c.isLetterOrDigit() } } ?: pathSegments.getOrNull(1)
        } ?: return@runCatching emptyList()

        val apiBase = "${parent.scheme}://${parent.host}/api/videos/$code/embed"
        val embedHeaders = headers.newBuilder()
            .set("Accept", "application/json")
            .set("Content-Type", "application/json")
            .set("Referer", parentUrl)
            .set("Origin", "${parent.scheme}://${parent.host}")
            .set("X-Embed-Origin", "www.painel-aso.sbs")
            .set("X-Embed-Referer", "https://www.startflix.biz/")
            .set("X-Embed-Parent", parentUrl)
            .build()

        val challenge = postJson("$apiBase/captcha", JSONObject(), embedHeaders)
        val nonce = challenge.getString("pow_nonce")
        val difficulty = challenge.getInt("pow_difficulty")
        val token = challenge.getString("pow_token")

        val solution = solvePow(nonce, difficulty)

        val verifyBody = JSONObject()
            .put("pow_token", token)
            .put("solution", solution)

        val verification = postJson("$apiBase/captcha/verify", verifyBody, embedHeaders)
        val captchaToken = verification.optString("token")
        if (captchaToken.isBlank()) return@runCatching emptyList()

        val playbackHeaders = embedHeaders.newBuilder()
            .set("X-Captcha-Token", captchaToken)
            .build()

        val playbackResponse = postJson(
            "$apiBase/playback",
            JSONObject().put("fingerprint", JSONObject()),
            playbackHeaders,
        )
        val playback = playbackResponse.getJSONObject("playback")
        val clearConfig = decryptPlayback(playback)
        val sources = clearConfig.optJSONArray("sources") ?: return@runCatching emptyList()

        val mediaHeaders = headers.newBuilder()
            .set("Referer", parentUrl)
            .set("Origin", "${parent.scheme}://${parent.host}")
            .build()

        val videoList = mutableListOf<Video>()
        for (i in 0 until sources.length()) {
            val source = sources.getJSONObject(i)
            val sourceUrl = source.optString("url")
            if (sourceUrl.isBlank() || !sourceUrl.startsWith("http")) continue
            val quality = source.optString("label").ifBlank { source.optString("quality", "1080p") }
            val videoName = if (label.isNotBlank()) "$label - $quality" else quality

            if (sourceUrl.contains(".m3u8", ignoreCase = true) || source.optString("mime_type").contains("mpegurl", ignoreCase = true)) {
                val hlsVideos = runCatching {
                    playlistUtils.extractFromHls(
                        playlistUrl = sourceUrl,
                        referer = parentUrl,
                        masterHeaders = mediaHeaders,
                        videoHeaders = mediaHeaders,
                        videoNameGen = { "$label - $it" },
                    )
                }.getOrDefault(emptyList())

                if (hlsVideos.isNotEmpty()) {
                    videoList.addAll(hlsVideos)
                } else {
                    videoList.add(Video(sourceUrl, videoName, sourceUrl, mediaHeaders))
                }
            } else {
                videoList.add(Video(sourceUrl, videoName, sourceUrl, mediaHeaders))
            }
        }
        videoList
    }.getOrElse { error ->
        Log.e("ByseExtractor", "Error extracting Byse: ${error.message}", error)
        emptyList()
    }

    private fun postJson(url: String, body: JSONObject, headers: Headers): JSONObject {
        val requestBody = body.toString().toRequestBody(JSON_MEDIA_TYPE)
        return client.newCall(POST(url, headers = headers, body = requestBody)).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}: ${response.message}" }
            JSONObject(response.body.string())
        }
    }

    private fun solvePow(nonce: String, difficulty: Int): String {
        if (difficulty <= 0) return "0"
        val prefix = "$nonce:"
        var solution = 0
        while (true) {
            val hash = powDigest(prefix + solution)
            if (leadingZeroBits(hash) >= difficulty) {
                return solution.toString()
            }
            solution++
        }
    }

    private fun powDigest(value: String): IntArray {
        val state = intArrayOf(1779033703, 3144134277L.toInt(), 1013904242, 2773480762L.toInt())
        value.toByteArray().forEach { byte ->
            state[0] += byte.toInt() and 0xff
            state[0] = Integer.rotateLeft(state[0], 7)
            powQuarterRound(state)
        }
        repeat(8) { powQuarterRound(state) }
        val memory = IntArray(512)
        memory.indices.forEach { index ->
            powQuarterRound(state)
            memory[index] = state[0] xor state[2]
        }
        repeat(2) {
            memory.indices.forEach { index ->
                val selected = memory[index] and 511
                var mixed = memory[index] + memory[selected]
                mixed = Integer.rotateLeft(mixed, 13)
                mixed = mixed xor (memory[(index + 1) and 511] * 2654435761L.toInt())
                memory[index] = mixed
                state[0] = state[0] xor mixed
                powQuarterRound(state)
            }
        }
        return IntArray(8) { block ->
            powQuarterRound(state)
            var mixed = state[0]
            repeat(64) { index ->
                val item = memory[block * 64 + index]
                mixed += item
                mixed = Integer.rotateLeft(mixed, 5)
                mixed = mixed xor (item * 2246822519L.toInt())
            }
            mixed xor state[2]
        }
    }

    private fun powQuarterRound(state: IntArray) {
        state[0] += state[1]
        state[3] = Integer.rotateLeft(state[3] xor state[0], 16)
        state[2] += state[3]
        state[1] = Integer.rotateLeft(state[1] xor state[2], 12)
        state[0] += state[1]
        state[3] = Integer.rotateLeft(state[3] xor state[0], 8)
        state[2] += state[3]
        state[1] = Integer.rotateLeft(state[1] xor state[2], 7)
    }

    private fun leadingZeroBits(values: IntArray): Int {
        var total = 0
        values.forEach { value ->
            if (value == 0) total += 32 else return total + Integer.numberOfLeadingZeros(value)
        }
        return total
    }

    private fun decryptPlayback(encrypted: JSONObject): JSONObject {
        val parts = encrypted.getJSONArray("key_parts")
        val version = encrypted.getString("version").toInt()
        val indexes = listOf(version, 31 - version).filter { it in 1..parts.length() }
        val selected = if (indexes.size == 2) indexes else (1..parts.length()).toList()
        val keyBytes = ByteArrayOutputStream().apply {
            selected.forEach { index -> write(decodeBase64Url(parts.getString(index - 1))) }
        }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            GCMParameterSpec(128, decodeBase64Url(encrypted.getString("iv"))),
        )
        val clear = cipher.doFinal(decodeBase64Url(encrypted.getString("payload")))
        return JSONObject(clear.toString(Charsets.UTF_8))
    }

    private fun decodeBase64Url(value: String): ByteArray = Base64.decode(
        value,
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
