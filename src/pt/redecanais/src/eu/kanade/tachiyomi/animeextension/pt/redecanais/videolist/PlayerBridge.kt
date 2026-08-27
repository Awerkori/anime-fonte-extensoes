package eu.kanade.tachiyomi.animeextension.pt.redecanais.videolist

import android.webkit.JavascriptInterface
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class PlayerBridge(
    private val baseUrl: String,
    private val baseHost: String,
    private val onResult: (PlayerApiSniffer.Result) -> Unit,
) {
    private val finished = AtomicBoolean(false)
    private val token = AtomicInteger(0)

    @JavascriptInterface
    @Suppress("UNUSED")
    fun passResult(token: Int, iframeUrl: String, videoUrl: String) {
        if (token != this.token.get() || videoUrl.isBlank()) return

        val absoluteVideoUrl = videoUrl.toAbsoluteVideoUrl(baseUrl)
        val parsedVideoUrl = absoluteVideoUrl.toHttpUrlOrNull() ?: return
        if (parsedVideoUrl.scheme != "http" && parsedVideoUrl.scheme != "https") return
        if (!finished.compareAndSet(false, true)) return

        val referer = iframeUrl.toAbsoluteRedeCanaisUrl(baseUrl, baseHost)
        onResult(PlayerApiSniffer.Result(parsedVideoUrl.toString(), referer))
    }

    fun isActive(token: Int): Boolean = this.token.get() == token && !finished.get()

    fun reset(token: Int) {
        this.token.set(token)
        finished.set(false)
    }
}
