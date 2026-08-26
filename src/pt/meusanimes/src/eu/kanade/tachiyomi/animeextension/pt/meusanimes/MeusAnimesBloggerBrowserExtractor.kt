package eu.kanade.tachiyomi.animeextension.pt.meusanimes

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Resolves Blogger's current player in the same browser context that requests its media. */
class MeusAnimesBloggerBrowserExtractor(
    private val client: OkHttpClient,
    private val fallbackHeaders: Headers,
) {
    private val context by injectLazy<android.app.Application>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @SuppressLint("SetJavaScriptEnabled")
    fun videosFromUrl(url: String): List<Video> {
        val startedAt = SystemClock.elapsedRealtime()
        val latch = CountDownLatch(1)
        val mediaUrl = AtomicReference<String?>()
        val mediaHeaders = AtomicReference<Headers>()
        val webView = AtomicReference<WebView?>()
        val clickAt = AtomicReference<Long>()
        val interactionStarted = AtomicBoolean(false)

        handler.post {
            val view = WebView(context)
            webView.set(view)
            with(view.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = fallbackHeaders["User-Agent"] ?: DEFAULT_USER_AGENT
            }
            view.measure(
                View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 1280, 720)
            view.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, loadedUrl: String?, favicon: android.graphics.Bitmap?) {
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    if (interactionStarted.compareAndSet(false, true)) {
                        handler.postDelayed({ performInteraction(webView, mediaUrl, clickAt) }, INITIAL_INTERACTION_DELAY_MS)
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val requestUrl = request.url
                    if (requestUrl.host?.endsWith(".googlevideo.com") == true && requestUrl.encodedPath == "/videoplayback") {
                        if (mediaUrl.compareAndSet(null, requestUrl.toString())) {
                            mediaHeaders.set(requestHeaders(request.requestHeaders))
                            latch.countDown()
                            handler.post { webView.get()?.stopLoading() }
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
            view.loadUrl(url, fallbackHeaders.toMultimap().mapValues { it.value.firstOrNull().orEmpty() })
        }

        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        handler.post { webView.getAndSet(null)?.destroy() }

        val resolvedUrl = mediaUrl.get() ?: return emptyList()
        val resolvedHeaders = mediaHeaders.get() ?: fallbackHeaders
        val mediaHttp = runCatching {
            client.newCall(
                Request.Builder()
                    .url(resolvedUrl)
                    .headers(resolvedHeaders)
                    .header("Range", "bytes=0-1")
                    .build(),
            ).execute().use { response ->
                response.code
            }
        }.getOrElse { -1 }
        return if (mediaHttp == 200 || mediaHttp == 206) {
            listOf(Video(resolvedUrl, "Blogger Browser", resolvedUrl, resolvedHeaders))
        } else {
            emptyList()
        }
    }

    private fun requestHeaders(values: Map<String, String>): Headers = Headers.Builder().apply {
        values.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) add(name, value)
        }
    }.build()

    private fun performInteraction(
        webView: AtomicReference<WebView?>,
        mediaUrl: AtomicReference<String?>,
        clickAt: AtomicReference<Long>,
    ) {
        if (mediaUrl.get() != null) return
        val activeView = webView.get() ?: return
        clickAt.compareAndSet(null, SystemClock.elapsedRealtime())
        activeView.evaluateJavascript("document.body ? (document.body.click(), 'clicked') : 'no-body'") { }
        activeView.evaluateJavascript("document.querySelector('video')?.play?.();") { }
        val x = activeView.measuredWidth / 2f
        val y = activeView.measuredHeight / 2f
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0)
        activeView.dispatchTouchEvent(down)
        activeView.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
        if (mediaUrl.get() == null && SystemClock.elapsedRealtime() - (clickAt.get() ?: 0L) < MAX_INTERACTION_WINDOW_MS) {
            handler.postDelayed({ performInteraction(webView, mediaUrl, clickAt) }, RETRY_INTERVAL_MS)
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 20L
        private const val INITIAL_INTERACTION_DELAY_MS = 200L
        private const val RETRY_INTERVAL_MS = 300L
        private const val MAX_INTERACTION_WINDOW_MS = 4_500L
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
    }
}
