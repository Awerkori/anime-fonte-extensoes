package eu.kanade.tachiyomi.animeextension.pt.startflix.extractors

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WebPlayerExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val context: Application by lazy { Injekt.get() }
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    @SuppressLint("SetJavaScriptEnabled")
    fun videosFromUrl(url: String, label: String, referer: String = "https://www.painel-aso.sbs/"): List<Video> {
        val latch = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        var resultUrl = ""
        var streamHeaders = headers.newBuilder()
            .set("Referer", url)
            .build()
        var webView: WebView? = null

        handler.post {
            try {
                val view = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.userAgentString = headers["User-Agent"]
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            if (finished.get()) return
                            view?.evaluateJavascript(
                                """
                                (function() {
                                    try {
                                        var p = document.querySelector('#overlay, #playback, button.play, .play-icon, .vjs-big-play-button, button, [aria-label*=Play i], [title*=Play i], media-play-button, svg');
                                        if (p) p.click();
                                        var v = document.querySelector('video');
                                        if (v) v.play();
                                    } catch(e) {}
                                })();
                                """.trimIndent(),
                                null,
                            )
                        }

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            val reqUrl = request.url.toString()
                            if (VIDEO_REGEX.containsMatchIn(reqUrl) && finished.compareAndSet(false, true)) {
                                resultUrl = reqUrl
                                val builder = headers.newBuilder()
                                request.requestHeaders.forEach { (k, v) -> builder.set(k, v) }
                                if (builder.get("Referer") == null) builder.set("Referer", url)
                                val host = url.toHttpUrlOrNull()?.host
                                if (builder.get("Origin") == null && host != null) {
                                    builder.set("Origin", "https://$host")
                                }
                                streamHeaders = builder.build()
                                latch.countDown()
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }
                }
                webView = view
                val loadHeaders = mapOf(
                    "Referer" to referer,
                    "User-Agent" to (headers["User-Agent"] ?: ""),
                )
                view.loadUrl(url, loadHeaders)
            } catch (e: Exception) {
                Log.e("WebPlayerExtractor", "Failed to initialize WebView", e)
                finished.set(true)
                latch.countDown()
            }
        }

        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

        handler.post {
            try {
                webView?.stopLoading()
                webView?.destroy()
                webView = null
            } catch (ignored: Exception) {}
        }

        if (resultUrl.isBlank()) return emptyList()

        return when {
            resultUrl.contains(".m3u8", ignoreCase = true) -> {
                playlistUtils.extractFromHls(
                    playlistUrl = resultUrl,
                    referer = url,
                    masterHeaders = streamHeaders,
                    videoHeaders = streamHeaders,
                    videoNameGen = { "$label - $it" },
                ).ifEmpty {
                    listOf(Video(resultUrl, label, resultUrl, streamHeaders))
                }
            }
            resultUrl.contains(".mp4", ignoreCase = true) -> {
                listOf(Video(resultUrl, "$label - MP4", resultUrl, streamHeaders))
            }
            else -> emptyList()
        }
    }

    companion object {
        private const val TIMEOUT_SEC: Long = 4
        private val VIDEO_REGEX by lazy { Regex("(.*\\.(mp4|m3u8)(\\?.*)?$|/hls/|master\\.m3u8)", RegexOption.IGNORE_CASE) }
    }
}
