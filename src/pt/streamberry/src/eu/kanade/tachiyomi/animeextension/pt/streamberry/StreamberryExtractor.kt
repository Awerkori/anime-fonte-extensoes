package eu.kanade.tachiyomi.animeextension.pt.streamberry

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.applicationContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class StreamberryExtractor(private val client: OkHttpClient) {
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    @Synchronized
    fun videosFromUrl(url: String, name: String, referer: String, playlistUtils: PlaylistUtils): List<Video> {
        val server = name.substringBefore(" - ")
        if (server.contains("Byse", ignoreCase = true)) {
            return videosFromByse(url, name, referer, playlistUtils)
        }

        val latch = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val cleaned = AtomicBoolean(false)
        val startedAt = SystemClock.elapsedRealtime()
        val iframeHost = Uri.parse(url).host.orEmpty()
        val timeout = if (server.contains("Vidara", ignoreCase = true)) 6L else 4L
        var result = ""
        var streamHeaders = Headers.Builder().add("Referer", url).build()
        var view: WebView? = null
        Log.d(TAG, "STREAMBERRY_RESOLVE_START server=$server host=$iframeHost")

        fun finish() {
            if (finished.compareAndSet(false, true)) latch.countDown()
        }

        fun finishError(error: String) {
            Log.d(TAG, "STREAMBERRY_RESOLVE_ERROR server=$server error=$error")
            finish()
        }

        handler.post {
            view = WebView(applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (finished.get()) return
                        view?.evaluateJavascript("(function(){const p=document.querySelector('button,[aria-label*=Play i],[title*=Play i],.play,.vjs-big-play-button);if(p){p.click();return true;}document.querySelector('video')?.play();return false;})()", null)
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val streamUrl = request.url.toString()
                        if (VIDEO_REGEX.containsMatchIn(streamUrl) && !finished.get()) {
                            result = streamUrl
                            streamHeaders = Headers.Builder().apply {
                                request.requestHeaders.forEach { (key, value) -> add(key, value) }
                                if (get("Referer") == null) add("Referer", url)
                                if (get("Origin") == null) add("Origin", "${Uri.parse(url).scheme}://$iframeHost")
                            }.build()
                            Log.d(TAG, "STREAMBERRY_HLS_CAPTURED server=$server host=${Uri.parse(streamUrl).host.orEmpty()}")
                            finish()
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) finishError("WEB_ERROR")
                        super.onReceivedError(view, request, error)
                    }

                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                        Log.d(TAG, "STREAMBERRY_SSL_IGNORED_SUBRESOURCE server=$server host=$iframeHost")
                        handler.proceed()
                    }
                }
                loadUrl(url, mapOf("Referer" to referer))
            }
        }
        if (!latch.await(timeout, TimeUnit.SECONDS) && finished.compareAndSet(false, true)) {
            Log.d(TAG, "STREAMBERRY_RESOLVE_TIMEOUT server=$server elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
        }
        val videos = when {
            result.contains(".m3u8") -> {
                streamHeaders = finalHeaders(result, url, streamHeaders)
                if (validateHls(result, streamHeaders, server)) {
                    Log.d(TAG, "STREAMBERRY_HLS_VALIDATION_OK server=$server")
                    playlistUtils.extractFromHls(
                        playlistUrl = result,
                        referer = url,
                        masterHeaders = streamHeaders,
                        videoHeaders = streamHeaders,
                        videoNameGen = { "$name - $it" },
                    )
                } else {
                    Log.d(TAG, "STREAMBERRY_HLS_VALIDATION_FAIL server=$server")
                    emptyList()
                }
            }
            result.contains(".mpd") -> playlistUtils.extractFromDash(result, { "$name - $it" }, referer = url)
            result.contains(".mp4") -> listOf(Video(result, "$name - MP4", result, streamHeaders))
            else -> emptyList()
        }
        handler.post {
            if (cleaned.compareAndSet(false, true)) {
                view?.stopLoading()
                view?.destroy()
                view = null
            }
        }
        if (videos.isNotEmpty()) {
            Log.d(TAG, "STREAMBERRY_RESOLVE_SUCCESS server=$server elapsedMs=${SystemClock.elapsedRealtime() - startedAt} finalHost=${Uri.parse(result).host.orEmpty()}")
        } else if (finished.get()) {
            Log.d(TAG, "STREAMBERRY_RESOLVE_ERROR server=$server error=empty_video_list")
        }
        return videos
    }

    private fun videosFromByse(parentUrl: String, name: String, referer: String, playlistUtils: PlaylistUtils): List<Video> = runCatching {
        val startedAt = SystemClock.elapsedRealtime()
        val parent = parentUrl.toHttpUrlOrNull() ?: return@runCatching emptyList()
        val code = parent.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@runCatching emptyList()
        val apiBase = "${parent.scheme}://${parent.host}/api/videos/$code/embed"
        val userAgent = runCatching { WebSettings.getDefaultUserAgent(applicationContext) }
            .getOrDefault("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        val embedHeaders = Headers.Builder().apply {
            add("Accept", "application/json")
            add("User-Agent", userAgent)
            add("Referer", parentUrl)
            add("Origin", "${parent.scheme}://${parent.host}")
            Uri.parse(referer).host?.let { add("X-Embed-Origin", it) }
            add("X-Embed-Referer", referer)
            add("X-Embed-Parent", parentUrl)
        }.build()

        Log.d(TAG, "BYSE_HTTP_START codeHash=${Integer.toHexString(code.hashCode())}")
        val challenge = postJson("$apiBase/captcha", JSONObject(), embedHeaders)
        val solution = solvePow(challenge.getString("pow_nonce"), challenge.getInt("pow_difficulty"))
        val verifyBody = JSONObject()
            .put("pow_token", challenge.getString("pow_token"))
            .put("solution", solution)
        val verification = postJson("$apiBase/captcha/verify", verifyBody, embedHeaders)
        check(verification.optString("status") == "ok") { "Byse PoW verification failed" }

        val playbackHeaders = embedHeaders.newBuilder()
            .add("X-Captcha-Token", verification.getString("token"))
            .build()
        val playback = postJson(
            "$apiBase/playback",
            JSONObject().put("fingerprint", JSONObject()),
            playbackHeaders,
        ).getJSONObject("playback")
        val clearConfig = decryptPlayback(playback)
        val sources = clearConfig.getJSONArray("sources")
        val videos = buildList {
            for (index in 0 until sources.length()) {
                val source = sources.getJSONObject(index)
                val sourceUrl = source.optString("url").takeIf { it.startsWith("http") } ?: continue
                val label = source.optString("label").ifBlank { source.optString("quality", "Byse") }
                val mediaHeaders = finalHeaders(
                    sourceUrl,
                    parentUrl,
                    Headers.Builder().apply {
                        add("User-Agent", userAgent)
                        add("Referer", parentUrl)
                        add("Origin", "${parent.scheme}://${parent.host}")
                    }.build(),
                )
                when {
                    source.optString("mime_type").contains("mpegurl", ignoreCase = true) || sourceUrl.contains(".m3u8", ignoreCase = true) -> {
                        if (!validateHls(sourceUrl, mediaHeaders, "Byse")) continue
                        addAll(
                            playlistUtils.extractFromHls(
                                playlistUrl = sourceUrl,
                                referer = parentUrl,
                                masterHeaders = mediaHeaders,
                                videoHeaders = mediaHeaders,
                                videoNameGen = { "$name - $label - $it" },
                            ),
                        )
                    }
                    source.optString("mime_type").equals("video/mp4", ignoreCase = true) || sourceUrl.contains(".mp4", ignoreCase = true) -> {
                        val validation = validateMp4(sourceUrl, mediaHeaders) ?: continue
                        if (validation.valid) add(Video(validation.finalUrl, "$name - $label", validation.finalUrl, mediaHeaders))
                    }
                }
            }
        }
        Log.d(TAG, "BYSE_HTTP_SUCCESS elapsedMs=${SystemClock.elapsedRealtime() - startedAt} VIDEO_COUNT=${videos.size}")
        videos
    }.getOrElse { error ->
        Log.d(TAG, "BYSE_HTTP_ERROR class=${error.javaClass.simpleName} message=${error.message?.take(120).orEmpty()}")
        emptyList()
    }

    private fun postJson(url: String, body: JSONObject, headers: Headers): JSONObject {
        val requestBody = body.toString().toRequestBody(JSON_MEDIA_TYPE)
        return client.newCall(POST(url, headers = headers, body = requestBody)).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            JSONObject(response.body.string())
        }
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

    private fun decodeBase64Url(value: String): ByteArray = try {
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    } catch (_: Throwable) {
        java.util.Base64.getUrlDecoder().decode(value)
    }

    private fun solvePow(nonce: String, difficulty: Int): String = StreamberryHelper.solvePow(nonce, difficulty)

    private fun finalHeaders(playlistUrl: String, iframeUrl: String, headers: Headers): Headers {
        val cookies = listOf(CookieManager.getInstance().getCookie(playlistUrl), CookieManager.getInstance().getCookie(iframeUrl))
            .filterNot { it.isNullOrBlank() }
            .joinToString("; ")
        return headers.newBuilder().apply {
            if (cookies.isNotBlank()) set("Cookie", cookies)
        }.build()
    }

    private fun validateMp4(url: String, headers: Headers): Mp4Validation? = runCatching {
        val validationHeaders = headers.newBuilder().set("Range", "bytes=0-4095").build()
        client.newCall(GET(url, headers = validationHeaders)).execute().use { response ->
            val contentType = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
            val bytes = response.body.source().readByteArray(4096)
            val hasFtyp = bytes.size >= 8 && bytes.copyOfRange(4, 8).contentEquals(FTYP)
            Mp4Validation(
                valid = (response.code == 200 || response.code == 206) && (contentType == "video/mp4" || hasFtyp),
                finalUrl = response.request.url.toString(),
            )
        }
    }.getOrNull()

    private fun validateHls(url: String, headers: Headers, server: String): Boolean = runCatching {
        val master = client.newCall(GET(url, headers)).execute().use { response ->
            if (!response.isSuccessful) return@runCatching false
            response.body.string()
        }
        if (!master.contains("#EXTM3U")) return@runCatching false
        val audioUrls = AUDIO_URI_REGEX.findAll(master).mapNotNull { resolveUrl(it.groupValues[1], url) }.toList()
        audioUrls.forEach { audioUrl ->
            if (!playlistIsValid(audioUrl.toString(), headers)) return@runCatching false
        }
        val variantUrl = master.lineSequence().zipWithNext().mapNotNull { (line, next) ->
            if (line.startsWith("#EXT-X-STREAM-INF")) resolveUrl(next.trim(), url)?.toString() else null
        }.firstOrNull()
        variantUrl == null || playlistIsValid(variantUrl, headers)
    }.getOrElse {
        Log.d(TAG, "STREAMBERRY_HLS_VALIDATION_FAIL server=$server")
        false
    }

    private fun playlistIsValid(url: String, headers: Headers): Boolean = runCatching {
        client.newCall(GET(url, headers)).execute().use { response ->
            response.isSuccessful && response.body.string().contains("#EXTM3U")
        }
    }.getOrDefault(false)

    private fun resolveUrl(value: String, base: String) = value.toHttpUrlOrNull() ?: base.toHttpUrlOrNull()?.resolve(value)

    companion object {
        private const val TAG = "Streamberry"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val AUDIO_URI_REGEX = Regex("#EXT-X-MEDIA:[^\\n]*URI=\"([^\"]+)\"")
        private val FTYP = "ftyp".toByteArray()
        private val VIDEO_REGEX = Regex(".*\\.(m3u8|mpd|mp4)(\\?.*)?$", RegexOption.IGNORE_CASE)
    }

    private data class Mp4Validation(
        val valid: Boolean,
        val finalUrl: String,
    )
}
