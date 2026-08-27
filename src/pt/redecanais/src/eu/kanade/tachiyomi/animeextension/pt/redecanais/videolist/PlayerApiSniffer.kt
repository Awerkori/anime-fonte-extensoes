package eu.kanade.tachiyomi.animeextension.pt.redecanais.videolist

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animeextension.pt.redecanais.destroyHeadless
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PlayerApiSniffer(
    private val baseUrl: String,
    private val userAgentProvider: () -> String,
) {

    private val baseHost = baseUrl.toHttpUrl().host
    private val context: Application by injectLazy()
    private val tag by lazy { javaClass.simpleName }
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val staticResolverScript by lazy {
        javaClass.getResource("/assets/resolve-player-static.js")?.readText()
            ?: throw Exception("error_static_player_resolver_script_not_found")
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    fun sniffAll(inputs: List<Input>): Map<String, Result> {
        if (inputs.isEmpty()) return emptyMap()

        val latch = CountDownLatch(1)
        val results = linkedMapOf<String, Result>()
        val webViewRef = AtomicReference<WebView?>()

        handler.post {
            WebView.setWebContentsDebuggingEnabled(false)
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            val resolverUserAgent = userAgentProvider()

            val view = WebView(context)
            webViewRef.set(view)
            view.onResume()
            view.resumeTimers()

            with(view.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                blockNetworkImage = false
                loadsImagesAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = resolverUserAgent
            }
            cookieManager.setAcceptThirdPartyCookies(view, true)

            val activeToken = AtomicInteger(0)
            val activeInput = AtomicReference<Input?>()
            val resolving = AtomicBoolean(false)
            val loggedApiUrls = ConcurrentHashMap.newKeySet<String>()
            var inputIndex = 0
            var startedAt = 0L
            lateinit var bridge: PlayerBridge
            lateinit var startInput: () -> Unit

            fun resolverTime(): Long = SystemClock.elapsedRealtime() - startedAt

            fun finishInput(result: Result?) {
                if (!resolving.compareAndSet(true, false)) return
                val input = activeInput.get() ?: return
                if (result != null) {
                    results[input.parentUrl] = result
                    Log.d(tag, "resolver success time=${resolverTime()}ms")
                } else {
                    Log.d(tag, "resolver fail time=${resolverTime()}ms")
                }

                inputIndex++
                if (inputIndex >= inputs.size) {
                    latch.countDown()
                } else {
                    startInput()
                }
            }

            bridge = PlayerBridge(baseUrl, baseHost) { result ->
                handler.post {
                    val mediaCookie = cookieManager.getCookie(result.url).orEmpty()
                    val refererCookie = cookieManager.getCookie(result.referer).orEmpty()
                    finishInput(
                        result.copy(
                            userAgent = resolverUserAgent,
                            cookie = mergeCookies(refererCookie, mediaCookie),
                        ),
                    )
                }
            }
            view.addJavascriptInterface(bridge, PLAYER_BRIDGE_INTERFACE)

            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    if (!resolving.get()) return super.shouldInterceptRequest(view, request)
                    val url = request.url.toString()

                    if (url.isUsableApiUrl() && loggedApiUrls.add(url)) {
                        Log.d(tag, "api found ${url.shortLogUrl()}")
                    }

                    if (url.isMediaUrl()) {
                        val input = activeInput.get()
                        val referer = request.requestHeaders.referer()
                            .ifBlank { input?.iframeUrl.orEmpty() }
                        Log.d(tag, "media found ${url.shortLogUrl()}")
                        bridge.passResult(activeToken.get(), referer, url)
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    val input = activeInput.get() ?: return
                    val token = activeToken.get()
                    if (!resolving.get() || !bridge.isActive(token)) return
                    if (!url.samePlayerDocument(input.iframeUrl)) return

                    val prefix = "window.__rcStaticResolverRunToken=$token;"
                    view.evaluateJavascript(prefix + staticResolverScript, null)
                }
            }

            startInput = start@{
                val input = inputs[inputIndex]
                val iframeUrl = input.iframeUrl
                if (iframeUrl.isNullOrBlank()) {
                    activeInput.set(input)
                    resolving.set(true)
                    startedAt = SystemClock.elapsedRealtime()
                    finishInput(null)
                    return@start
                }

                val token = activeToken.incrementAndGet()
                activeInput.set(input)
                resolving.set(true)
                loggedApiUrls.clear()
                startedAt = SystemClock.elapsedRealtime()
                bridge.reset(token)
                view.stopLoading()
                Log.d(tag, "resolver static")
                view.loadUrl(iframeUrl, mapOf("Referer" to input.parentUrl))

                handler.postDelayed(
                    {
                        if (activeToken.get() == token && bridge.isActive(token)) {
                            finishInput(null)
                        }
                    },
                    STATIC_RESOLVER_TIMEOUT_MS,
                )
            }

            startInput()
        }

        val completed = latch.await(RESOLVER_TIMEOUT_SECONDS * inputs.size, TimeUnit.SECONDS)
        if (!completed) Log.d(tag, "resolver fail timeout")

        handler.post {
            webViewRef.getAndSet(null)?.run {
                onPause()
                destroyHeadless(PLAYER_BRIDGE_INTERFACE)
            }
        }

        return results
    }

    private fun String?.samePlayerDocument(expected: String?): Boolean {
        val actualUrl = this?.toHttpUrlOrNull() ?: return false
        val expectedUrl = expected?.toHttpUrlOrNull() ?: return false
        return actualUrl.host == expectedUrl.host &&
            actualUrl.encodedPath.equals(expectedUrl.encodedPath, ignoreCase = true) &&
            actualUrl.queryParameter("vid") == expectedUrl.queryParameter("vid")
    }

    private fun mergeCookies(vararg cookieHeaders: String): String {
        val cookies = linkedMapOf<String, String>()
        cookieHeaders.forEach { header ->
            header.split(';').forEach { part ->
                val cookie = part.trim()
                val name = cookie.substringBefore('=').trim()
                if (name.isNotEmpty() && '=' in cookie) cookies[name] = cookie
            }
        }
        return cookies.values.joinToString("; ")
    }

    private fun String.isUsableApiUrl(): Boolean {
        val lower = lowercase()
        return ".api" in lower && API_IGNORE_MARKERS.none(lower::contains)
    }

    private fun String.isMediaUrl(): Boolean {
        val lower = lowercase()
        if (MEDIA_IGNORE_MARKERS.any(lower::contains)) return false
        if (MEDIA_MARKERS.none(lower::contains)) return false
        val url = toHttpUrlOrNull() ?: return false
        return url.scheme == "http" || url.scheme == "https"
    }

    private fun Map<String, String>.referer(): String = entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value.orEmpty()

    private fun String.shortLogUrl(): String {
        val url = toHttpUrlOrNull() ?: return takeLast(100)
        return "${url.host}${url.encodedPath}"
    }

    data class Input(
        val parentUrl: String,
        val iframeUrl: String?,
    )

    data class Result(
        val url: String,
        val referer: String,
        val userAgent: String = "",
        val cookie: String = "",
    )

    private companion object {
        const val PLAYER_BRIDGE_INTERFACE = "PlayerApiSniffer"
        const val STATIC_RESOLVER_TIMEOUT_MS = 8_000L
        const val RESOLVER_TIMEOUT_SECONDS = 12L
        val API_IGNORE_MARKERS = listOf("/player3/dt.api", "videojs.thumbnails.api")
        val MEDIA_MARKERS = listOf(".m3u8", ".mp4")
        val MEDIA_IGNORE_MARKERS = listOf(
            "advert",
            "/ads/",
            "doubleclick",
            "googlesyndication",
            "thumbnail",
            "thumb/",
            "sprite",
            "poster",
            "preview",
        )
    }
}
