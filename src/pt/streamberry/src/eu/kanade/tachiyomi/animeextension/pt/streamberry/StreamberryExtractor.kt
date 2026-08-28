package eu.kanade.tachiyomi.animeextension.pt.streamberry

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.utils.applicationContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class StreamberryExtractor(private val client: OkHttpClient) {
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    @Synchronized
    fun videosFromUrl(url: String, name: String, referer: String, playlistUtils: PlaylistUtils): List<Video> {
        val latch = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val cleaned = AtomicBoolean(false)
        val startedAt = SystemClock.elapsedRealtime()
        val server = name.substringBefore(" - ")
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
                        if (server.contains("Byse", ignoreCase = true)) {
                            Log.d(TAG, "BYSE_PAGE_FINISHED=true BYSE_MAIN_FRAME_STATUS=finished")
                        }
                        view?.evaluateJavascript("(function(){const p=document.querySelector('button,[aria-label*=Play i],[title*=Play i],.play,.vjs-big-play-button);if(p){p.click();return true;}document.querySelector('video')?.play();return false;})()") {
                            if (server.contains("Byse", ignoreCase = true)) {
                                Log.d(TAG, "BYSE_PLAY_BUTTON_FOUND=$it BYSE_PLAY_CLICKED=$it")
                            }
                        }
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val streamUrl = request.url.toString()
                        if (server.contains("Byse", ignoreCase = true) && VIDEO_REGEX.containsMatchIn(streamUrl)) {
                            Log.d(TAG, "BYSE_MEDIA_REQUEST_HOST=${Uri.parse(streamUrl).host.orEmpty()} BYSE_MEDIA_REQUEST_TYPE=${streamUrl.substringAfterLast('.', "").substringBefore('?')}")
                        }
                        if (VIDEO_REGEX.containsMatchIn(streamUrl) && !finished.get()) {
                            result = streamUrl
                            streamHeaders = Headers.Builder().apply {
                                request.requestHeaders.forEach { (key, value) -> add(key, value) }
                                if (get("Referer") == null) add("Referer", url)
                                if (get("Origin") == null) add("Origin", "${Uri.parse(url).scheme}://$iframeHost")
                            }.build()
                            Log.d(TAG, "STREAMBERRY_HLS_CAPTURED server=$server host=${Uri.parse(streamUrl).host.orEmpty()}")
                            if (server.contains("Byse", ignoreCase = true)) {
                                val type = streamUrl.substringAfterLast('.', "").substringBefore('?').uppercase()
                                Log.d(TAG, "BYSE_${type}_CAPTURED=${Uri.parse(streamUrl).host.orEmpty()}")
                            }
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
            if (server.contains("Byse", ignoreCase = true)) {
                Log.d(TAG, "BYSE_RESOLVE_MS=${SystemClock.elapsedRealtime() - startedAt}")
            }
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
            if (server.contains("Byse", ignoreCase = true)) {
                Log.d(TAG, "BYSE_RESOLVE_MS=${SystemClock.elapsedRealtime() - startedAt}")
            }
        } else if (finished.get()) {
            Log.d(TAG, "STREAMBERRY_RESOLVE_ERROR server=$server error=empty_video_list")
        }
        return videos
    }

    private fun finalHeaders(playlistUrl: String, iframeUrl: String, headers: Headers): Headers {
        val cookies = listOf(CookieManager.getInstance().getCookie(playlistUrl), CookieManager.getInstance().getCookie(iframeUrl))
            .filterNot { it.isNullOrBlank() }
            .joinToString("; ")
        return headers.newBuilder().apply {
            if (cookies.isNotBlank()) set("Cookie", cookies)
            Log.d(TAG, "STREAMBERRY_COOKIE_PRESENT=${cookies.isNotBlank()}")
        }.build()
    }

    private fun validateHls(url: String, headers: Headers, server: String): Boolean = runCatching {
        val master = client.newCall(eu.kanade.tachiyomi.network.GET(url, headers)).execute().use { response ->
            if (!response.isSuccessful) return@runCatching false
            response.body.string()
        }
        if (!master.contains("#EXTM3U")) return@runCatching false
        val audioUrls = AUDIO_URI_REGEX.findAll(master).mapNotNull { resolveUrl(it.groupValues[1], url) }.toList()
        audioUrls.forEach { audioUrl ->
            Log.d(TAG, "STREAMBERRY_AUDIO_PLAYLIST_HOST=${audioUrl.host}")
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
        client.newCall(eu.kanade.tachiyomi.network.GET(url, headers)).execute().use { response ->
            response.isSuccessful && response.body.string().contains("#EXTM3U")
        }
    }.getOrDefault(false)

    private fun resolveUrl(value: String, base: String) = value.toHttpUrlOrNull() ?: base.toHttpUrlOrNull()?.resolve(value)

    companion object {
        private const val TAG = "Streamberry"
        private val AUDIO_URI_REGEX = Regex("#EXT-X-MEDIA:[^\\n]*URI=\"([^\"]+)\"")
        private val VIDEO_REGEX = Regex(".*\\.(m3u8|mpd|mp4)(\\?.*)?$", RegexOption.IGNORE_CASE)
    }
}
