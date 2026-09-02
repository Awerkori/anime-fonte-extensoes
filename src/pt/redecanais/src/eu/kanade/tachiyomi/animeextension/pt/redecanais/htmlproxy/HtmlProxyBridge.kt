package eu.kanade.tachiyomi.animeextension.pt.redecanais.htmlproxy

import android.util.Base64
import android.webkit.JavascriptInterface
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

internal class HtmlProxyBridge(
    private val htmlLatch: CountDownLatch,
    private val pageDoneLatch: CountDownLatch,
    private val baseHost: String,
) {
    private val imageBuffers = ConcurrentHashMap<String, ImageBuffer>()
    private val pendingImages = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    @Volatile
    var html: HtmlProxyResult? = null
        private set

    @Volatile
    private var pageDone = false

    @JavascriptInterface
    @Suppress("UNUSED")
    fun passHtml(statusCode: Int, contentType: String, body: String) {
        if (html != null) return
        if (body.isEmpty()) {
            fail(OPEN_WEBVIEW_MESSAGE)
            return
        }

        finishHtml(
            HtmlProxyResult.Success(
                originStatusCode = statusCode,
                contentType = contentType.substringBefore(";").ifEmpty { "text/html" },
                body = body,
            ),
        )
    }

    @JavascriptInterface
    @Suppress("UNUSED")
    fun passError(message: String) {
        fail(message)
    }

    @JavascriptInterface
    @Suppress("UNUSED")
    fun passDone() {
        finishPage()
    }

    @JavascriptInterface
    @Suppress("UNUSED")
    fun expectImage(url: String) {
        imageUrls(url).forEach { imageUrl ->
            ImageCache.expect(imageUrl)
            pendingImages.add(imageUrl)
        }
    }

    @JavascriptInterface
    @Suppress("UNUSED")
    fun startImage(url: String, contentType: String) {
        imageBuffers[url] = ImageBuffer(contentType)
    }

    @JavascriptInterface
    @Suppress("UNUSED")
    fun passImageChunk(url: String, chunk: String) {
        imageBuffers[url]?.chunks?.append(chunk)
    }

    @JavascriptInterface
    @Suppress("UNUSED")
    fun finishImage(url: String) {
        val buffer = imageBuffers.remove(url) ?: return
        val bytes = Base64.decode(buffer.chunks.toString(), Base64.DEFAULT)
        imageUrls(url).forEach { imageUrl ->
            ImageCache.put(imageUrl, buffer.contentType, bytes)
            pendingImages.remove(imageUrl)
        }
    }

    @JavascriptInterface
    @Suppress("UNUSED")
    fun passImageError(url: String) {
        imageBuffers.remove(url)
        imageUrls(url).forEach { imageUrl ->
            ImageCache.finish(imageUrl)
            pendingImages.remove(imageUrl)
        }
    }

    @JavascriptInterface
    @Suppress("UNUSED")
    fun passSearchMap(file: String, text: String) {
        SearchMapCache.put(file, text)
    }

    fun hasFinished(): Boolean = html != null

    fun finishPendingImages() {
        pendingImages.forEach(ImageCache::finish)
        pendingImages.clear()
        imageBuffers.clear()
    }

    private fun finishHtml(result: HtmlProxyResult) {
        if (html != null) return
        html = result
        htmlLatch.countDown()
    }

    private fun fail(message: String) {
        if (html != null) return
        finishHtml(HtmlProxyResult.Error(message))
        finishPage()
    }

    private fun finishPage() {
        if (pageDone) return
        pageDone = true
        pageDoneLatch.countDown()
    }

    private fun imageUrls(url: String): List<String> {
        val normalizedUrl = url.toRedeCanaisHost(baseHost)
        return if (normalizedUrl == url) listOf(url) else listOf(url, normalizedUrl)
    }

    private class ImageBuffer(
        val contentType: String,
        val chunks: StringBuilder = StringBuilder(),
    )
}

internal sealed class HtmlProxyResult {
    class Success(
        val originStatusCode: Int,
        val contentType: String,
        val body: String,
    ) : HtmlProxyResult() {
        fun toResponse(request: Request, protocol: Protocol): Response = Response.Builder()
            .request(request)
            .protocol(protocol)
            .code(200)
            .message("OK")
            .headers(
                Headers.headersOf(
                    "Content-Type",
                    contentType,
                    "X-RedeCanais-Proxy-Status",
                    originStatusCode.toString(),
                ),
            )
            .body(body.toResponseBody(contentType.toMediaTypeOrNull()))
            .build()
    }

    class Error(val message: String) : HtmlProxyResult()
}
