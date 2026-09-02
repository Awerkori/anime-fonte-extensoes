package eu.kanade.tachiyomi.animeextension.pt.redecanais.htmlproxy

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animeextension.pt.redecanais.destroyHeadless
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import org.json.JSONObject
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class HtmlProxy(
    baseUrl: String,
    private val userAgentProvider: () -> String,
) : Interceptor {

    private val baseHost = baseUrl.toHttpUrl().host
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val captureHtmlScript by lazy {
        javaClass.getResource("/assets/capture-html.js")?.readText()
            ?: throw Exception("error_capture_html_script_not_found")
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val protocol = chain.connection()?.protocol() ?: Protocol.HTTP_1_1

        if (!request.shouldProxyPageHtml(baseHost)) return chain.proceed(request)

        return when (val result = loadPageHtml(request.url.toString(), request.header("Referer"))) {
            is HtmlProxyResult.Success -> result.toResponse(request, protocol)
            is HtmlProxyResult.Error -> throw IOException(result.message)
            null -> throw IOException(OPEN_WEBVIEW_MESSAGE)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun loadPageHtml(url: String, referer: String?): HtmlProxyResult? {
        val htmlLatch = CountDownLatch(1)
        val pageDoneLatch = CountDownLatch(1)
        val bridge = HtmlProxyBridge(htmlLatch, pageDoneLatch, baseHost)
        val webViewRef = AtomicReference<WebView?>()

        handler.post {
            try {
                CookieManager.getInstance().setAcceptCookie(true)

                val view = WebView(context)
                webViewRef.set(view)

                with(view.settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    blockNetworkImage = true
                    loadsImagesAutomatically = false
                    userAgentString = userAgentProvider()
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

                val bootstrapUrl = referer?.takeIf { candidate ->
                    url.isLegacyVideoPage() && candidate.isRedeCanaisPage(baseHost)
                }
                view.addJavascriptInterface(bridge, HTML_BRIDGE_INTERFACE)
                view.webViewClient = HtmlProxyWebViewClient(url, bootstrapUrl, bridge)
                view.loadUrl(bootstrapUrl ?: url)
            } catch (_: Throwable) {
                htmlLatch.countDown()
                pageDoneLatch.countDown()
            }
        }

        return awaitResult(htmlLatch, pageDoneLatch, bridge, webViewRef)
    }

    private fun awaitResult(
        htmlLatch: CountDownLatch,
        pageDoneLatch: CountDownLatch,
        bridge: HtmlProxyBridge,
        webViewRef: AtomicReference<WebView?>,
    ): HtmlProxyResult? {
        htmlLatch.await(HTML_PROXY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val result = bridge.html

        if (result == null) {
            handler.post {
                webViewRef.getAndSet(null)?.destroyHeadless(HTML_BRIDGE_INTERFACE)
            }
        } else {
            thread(name = "RedeCanaisHtmlProxyCleanup", isDaemon = true) {
                pageDoneLatch.await(HTML_PROXY_CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                bridge.finishPendingImages()
                handler.post {
                    webViewRef.getAndSet(null)?.destroyHeadless(HTML_BRIDGE_INTERFACE)
                }
            }
        }

        return result
    }

    private fun scheduleHtmlCapture(
        view: WebView,
        bridge: HtmlProxyBridge,
        delayMillis: Long,
    ) {
        handler.postDelayed(
            {
                if (bridge.hasFinished()) return@postDelayed
                view.evaluateJavascript(captureHtmlScript, null)
            },
            delayMillis,
        )
    }

    private fun scheduleListingCaptureWhenReady(
        view: WebView,
        bridge: HtmlProxyBridge,
        attempt: Int = 1,
    ) {
        handler.postDelayed(
            {
                if (bridge.hasFinished()) return@postDelayed

                view.evaluateJavascript(LISTING_READY_SCRIPT) { value ->
                    if (bridge.hasFinished()) return@evaluateJavascript

                    val count = value?.trim('"')?.toIntOrNull() ?: 0
                    if (count >= LISTING_READY_ITEM_COUNT) {
                        scheduleHtmlCapture(view, bridge, 0L)
                    } else if (attempt < LISTING_READY_MAX_ATTEMPTS) {
                        scheduleListingCaptureWhenReady(view, bridge, attempt + 1)
                    }
                }
            },
            LISTING_READY_POLL_DELAY_MS,
        )
    }

    private inner class HtmlProxyWebViewClient(
        private val pageUrl: String,
        private val bootstrapUrl: String?,
        private val bridge: HtmlProxyBridge,
    ) : WebViewClient() {
        private var restoredCookies = false
        private var waitingCookieReload = false
        private var waitingBootstrap = bootstrapUrl != null

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? = request.blockedResponse(baseHost, pageUrl) ?: super.shouldInterceptRequest(view, request)

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (restoredCookies) waitingCookieReload = false
            if (url.isFastListingPageUrl()) {
                scheduleListingCaptureWhenReady(view, bridge)
            }
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            super.onReceivedHttpError(view, request, errorResponse)
            if (!request.isForMainFrame) return

            val statusCode = errorResponse.statusCode
            when {
                statusCode == 403 -> {
                    view.stopLoading()
                    bridge.passError(OPEN_WEBVIEW_MESSAGE)
                }
                statusCode in COOKIE_RELOAD_STATUS_CODES && !restoredCookies -> {
                    restoredCookies = true
                    waitingCookieReload = true
                    view.stopLoading()
                    view.post { view.loadUrl(pageUrl, webViewCookieHeaders(pageUrl)) }
                }
                statusCode in COOKIE_RELOAD_STATUS_CODES -> {
                    view.stopLoading()
                    bridge.passError(OPEN_WEBVIEW_MESSAGE)
                }
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            if (bridge.hasFinished()) return
            if (waitingBootstrap && url == bootstrapUrl) {
                waitingBootstrap = false
                view.evaluateJavascript("location.href=${JSONObject.quote(pageUrl)}", null)
                return
            }
            if (waitingCookieReload) return
            scheduleHtmlCapture(view, bridge, PAGE_FINISHED_HTML_CAPTURE_DELAY_MS)
        }
    }

    private fun webViewCookieHeaders(url: String): Map<String, String> {
        val cookies = CookieManager.getInstance().getCookie(url).orEmpty()
        return if (cookies.isEmpty()) emptyMap() else mapOf("Cookie" to cookies)
    }

    private fun String.isLegacyVideoPage(): Boolean = toHttpUrl().encodedPath.equals("/musicvideo.php", ignoreCase = true)

    private fun String.isRedeCanaisPage(baseHost: String): Boolean = runCatching {
        toHttpUrl().run { host == baseHost && encodedPath.endsWith(".html", ignoreCase = true) }
    }.getOrDefault(false)
}
