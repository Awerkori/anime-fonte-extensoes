package eu.kanade.tachiyomi.animeextension.pt.redecanais.lib

import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URLEncoder

internal class StreamServer(
    private val client: OkHttpClient,
) : NanoHTTPD(0) {
    @Volatile
    private var running = false

    override fun start() {
        super.start()
        running = true
    }

    override fun stop() {
        running = false
        super.stop()
    }

    fun isRunning(): Boolean = running

    override fun serve(session: IHTTPSession): Response = when {
        session.uri.startsWith(STREAM_PATH) -> handleStream(session)
        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
    }

    fun createStreamUrl(streamUrl: String): String {
        val encodedUrl = URLEncoder.encode(streamUrl, Charsets.UTF_8.name())
        return "http://localhost:$listeningPort$STREAM_PATH?$URL_PARAM=$encodedUrl"
    }

    private fun handleStream(session: IHTTPSession): Response {
        val url = session.parms[URL_PARAM]
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "")

        val request = Request.Builder()
            .url(url)
            .apply { session.streamHeaders().forEach(::header) }
            .build()

        val upstream = client.newCall(request).execute()
        if (!upstream.isSuccessful) {
            val status = Response.Status.lookup(upstream.code) ?: Response.Status.INTERNAL_ERROR
            upstream.close()
            return newFixedLengthResponse(status, MIME_PLAINTEXT, "")
        }

        val stream = ClosingInputStream(upstream.body.byteStream(), upstream)
        val isMp4 = url.contains(".mp4", ignoreCase = true)
        val mimeType = upstream.header("Content-Type")
            ?.takeUnless { isMp4 && it.equals("application/octet-stream", ignoreCase = true) }
            ?: if (isMp4) "video/mp4" else "application/octet-stream"
        val contentLength = upstream.header("Content-Length")?.toLongOrNull() ?: -1L
        val status = if (upstream.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val response = if (contentLength >= 0) {
            newFixedLengthResponse(status, mimeType, stream, contentLength)
        } else {
            newChunkedResponse(status, mimeType, stream)
        }

        upstream.header("Content-Range")?.let { response.addHeader("Content-Range", it) }
        upstream.header("Accept-Ranges")?.let { response.addHeader("Accept-Ranges", it) }
        response.setGzipEncoding(false)
        return response
    }

    private fun IHTTPSession.streamHeaders(): Map<String, String> = headers
        .filterKeys { it.lowercase() in STREAM_HEADER_NAMES }

    private class ClosingInputStream(
        input: InputStream,
        private val response: okhttp3.Response,
    ) : FilterInputStream(input) {
        override fun close() {
            try {
                super.close()
            } finally {
                response.close()
            }
        }
    }

    private companion object {
        const val STREAM_PATH = "/stream"
        const val URL_PARAM = "url"

        val STREAM_HEADER_NAMES = setOf(
            "user-agent",
            "referer",
            "origin",
            "accept",
            "accept-language",
            "accept-encoding",
            "connection",
            "cache-control",
            "pragma",
            "range",
            "sec-gpc",
            "upgrade-insecure-requests",
            "priority",
        )
    }
}
