package eu.kanade.tachiyomi.animeextension.pt.animesdrive.extractors

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.utils.applicationContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UniversalExtractor(private val client: OkHttpClient) {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @SuppressLint("SetJavaScriptEnabled")
    fun videosFromUrl(origRequestUrl: String, origRequestHeader: Headers, name: String?): List<Video> {
        val httpUrl = origRequestUrl.toHttpUrlOrNull() ?: return emptyList()
        val host = httpUrl.host.removePrefix("www.").substringBefore(".").proper()
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        var resultUrl = ""
        var resultHeaders = origRequestHeader
        var masterRequest: ObservedRequest? = null
        var variantRequest: ObservedRequest? = null
        val headers = origRequestHeader.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }.toMutableMap()

        handler.post {
            val newView = WebView(applicationContext)
            webView = newView
            with(newView.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                userAgentString = origRequestHeader["User-Agent"]
            }
            newView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(CHECK_SCRIPT) {}
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    if (VIDEO_REGEX.containsMatchIn(url)) {
                        val observed = ObservedRequest(
                            url = url,
                            headers = request.toHeaders(origRequestHeader),
                        )
                        if (url.contains(".m3u8", ignoreCase = true) && masterRequest == null && url.contains("master", ignoreCase = true)) {
                            masterRequest = observed
                        } else {
                            if (masterRequest != null && variantRequest == null && url != masterRequest?.url) {
                                variantRequest = observed
                            }
                            val selected = variantRequest ?: observed
                            resultUrl = selected.url
                            resultHeaders = selected.headers
                            latch.countDown()
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            val loadUrl = httpUrl.newBuilder().addQueryParameter("dl", "1").build().toString()
            webView?.loadUrl(loadUrl, headers)
        }

        try {
            latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)
        } finally {
            handler.post {
                webView?.stopLoading()
                webView?.destroy()
                webView = null
            }
        }

        if (resultUrl.isBlank()) {
            masterRequest?.let { master ->
                resultUrl = master.url
                resultHeaders = master.headers
            }
        }

        val prefix = name ?: host
        return when {
            "m3u8" in resultUrl -> {
                Video(
                    resultUrl,
                    "$prefix: HLS",
                    resultUrl,
                    resultHeaders,
                ).let(::listOf)
            }
            "mpd" in resultUrl -> {
                aniyomi.lib.playlistutils.PlaylistUtils(client, resultHeaders)
                    .extractFromDash(resultUrl, { it -> "$prefix: $it" }, referer = origRequestUrl)
            }
            "mp4" in resultUrl -> {
                Video(
                    resultUrl,
                    "$prefix: MP4",
                    resultUrl,
                    Headers.headersOf("referer", origRequestUrl),
                ).let(::listOf)
            }
            else -> emptyList()
        }
    }

    private fun String.proper(): String = this.replaceFirstChar {
        if (it.isLowerCase()) {
            it.titlecase(
                Locale.getDefault(),
            )
        } else {
            it.toString()
        }
    }

    private fun WebResourceRequest.toHeaders(baseHeaders: Headers): Headers = baseHeaders.newBuilder().apply {
        requestHeaders.forEach { (name, value) ->
            if (name?.lowercase() in FORWARDED_HEADERS) set(name, value)
        }
        CookieManager.getInstance().getCookie(url.toString())?.takeIf(String::isNotBlank)?.let { set("Cookie", it) }
    }.build()

    private data class ObservedRequest(val url: String, val headers: Headers)

    companion object {
        const val TIMEOUT_SEC: Long = 10
        private val FORWARDED_HEADERS = setOf("accept", "origin", "referer", "user-agent")
        private val VIDEO_REGEX by lazy { Regex(".*\\.(mp4|m3u8|mpd)(\\?.*)?$", RegexOption.IGNORE_CASE) }
        private val CHECK_SCRIPT by lazy {
            """
            setInterval(() => {
                var playButton = document.getElementById('player-button-container')
                if (playButton) {
                    playButton.click()
                }
                var downloadButton = document.querySelector(".downloader-button")
                if (downloadButton) {
                    if (downloadButton.href) {
                        location.href = downloadButton.href
                    } else {
                        downloadButton.click()
                    }
                }
                // Default jwplayer instance
                try { jwplayer(0).play(); } catch {}
            }, 2500)
            """.trimIndent()
        }
    }
}
