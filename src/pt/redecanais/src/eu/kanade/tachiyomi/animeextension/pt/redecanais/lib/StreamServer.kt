package eu.kanade.tachiyomi.animeextension.pt.redecanais.lib

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.LinkedHashMap
import java.util.UUID

internal class StreamServer(
    private val mediaClient: OkHttpClient,
) : NanoHTTPD(0) {
    private val contexts = object : LinkedHashMap<String, StreamContext>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StreamContext>?): Boolean = size > MAX_STREAM_CONTEXTS
    }

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

    override fun serve(session: IHTTPSession): Response = try {
        Log.d(TAG, "PROXY_REQUEST_URL=${session.proxyRequestUrl()}")
        Log.d(TAG, "METHOD=${session.method}")
        Log.d(TAG, "RANGE_RECEIVED=${session.rangeHeader()}")
        when {
            session.uri.startsWith(STREAM_PATH) -> handleStream(session)
            else -> fixedResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
        }
    } catch (error: Throwable) {
        Log.e(TAG, "EXCEPTION=${error.stackTraceToString()}", error)
        val status = when (error) {
            is IOException -> Response.Status.SERVICE_UNAVAILABLE
            is IllegalArgumentException -> Response.Status.BAD_REQUEST
            else -> Response.Status.INTERNAL_ERROR
        }
        fixedResponse(status, MIME_PLAINTEXT, "")
    }

    fun createStreamUrl(context: StreamContext): String {
        val id = UUID.randomUUID().toString()
        synchronized(contexts) { contexts[id] = context }
        return "http://127.0.0.1:$listeningPort$STREAM_PATH$id"
    }

    private fun handleStream(session: IHTTPSession): Response {
        val id = session.uri.removePrefix(STREAM_PATH).substringBefore('/')
        val context = synchronized(contexts) { contexts[id] }
            ?: return fixedResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
        val range = session.rangeHeader()

        val request = context.buildMediaRequest(range)
        Log.d(TAG, "UPSTREAM_URL=${context.url}")
        Log.d(TAG, "UPSTREAM_CONNECTION_URL=${request.url}")
        Log.d(TAG, "UPSTREAM_REQUEST_HEADERS=${request.headers.singleLine()}")
        val upstream = mediaClient.newCall(request).execute()
        val upstreamContentType = upstream.header("Content-Type").orEmpty()
        val upstreamContentLength = upstream.body.contentLength()
        val upstreamContentRange = upstream.header("Content-Range").orEmpty()
        Log.d(TAG, "UPSTREAM_STATUS=${upstream.code}")
        Log.d(TAG, "UPSTREAM_CONTENT_TYPE=$upstreamContentType")
        Log.d(TAG, "UPSTREAM_CONTENT_LENGTH=$upstreamContentLength")
        Log.d(TAG, "UPSTREAM_CONTENT_RANGE=$upstreamContentRange")
        Log.d(TAG, "UPSTREAM_FINAL_URL=${upstream.request.url}")
        Log.d(TAG, "UPSTREAM_RESPONSE_HEADERS=${upstream.headers.singleLine()}")

        if (!upstream.isSuccessful) {
            val status = upstream.toProxyStatus()
            val mimeType = upstreamContentType.ifBlank { MIME_PLAINTEXT }
            val stream = ProxyInputStream(upstream.body.byteStream(), upstream)
            return streamResponse(status, mimeType, stream, upstreamContentLength, upstream)
        }

        val body = upstream.body
        val prefix = try {
            body.source().peek().readByteArray(MAGIC_BYTES.toLong())
        } catch (_: java.io.EOFException) {
            ByteArray(0)
        }
        val isMp4 = context.url.contains(".mp4", ignoreCase = true) || prefix.hasMp4Magic()
        val mimeType = upstreamContentType.toProxyMimeType(isMp4)
        Log.d(TAG, "STREAM_PROXY_MIME=$mimeType")

        val stream = ProxyInputStream(body.byteStream(), upstream)
        return streamResponse(upstream.toProxyStatus(), mimeType, stream, upstreamContentLength, upstream)
    }

    private fun streamResponse(
        status: Response.IStatus,
        mimeType: String,
        stream: InputStream,
        contentLength: Long,
        upstream: okhttp3.Response,
    ): Response {
        val response = if (contentLength >= 0L) {
            newFixedLengthResponse(status, mimeType, stream, contentLength)
        } else {
            newChunkedResponse(status, mimeType, stream)
        }
        val responseHeaders = linkedMapOf("Content-Type" to mimeType)
        if (contentLength >= 0L) responseHeaders["Content-Length"] = contentLength.toString()
        RESPONSE_HEADERS.forEach { header ->
            upstream.header(header)?.let {
                response.addHeader(header, it)
                responseHeaders[header] = it
            }
        }
        response.setGzipEncoding(false)
        Log.d(TAG, "PROXY_RESPONSE_STATUS=${status.requestStatus}")
        Log.d(TAG, "PROXY_RESPONSE_HEADERS=${responseHeaders.singleLine()}")
        return response
    }

    private fun fixedResponse(status: Response.IStatus, mimeType: String, body: String): Response {
        val response = newFixedLengthResponse(status, mimeType, body)
        Log.d(TAG, "PROXY_RESPONSE_STATUS=${status.requestStatus}")
        Log.d(TAG, "PROXY_RESPONSE_HEADERS=Content-Type=$mimeType, Content-Length=${body.toByteArray().size}")
        return response
    }

    private fun IHTTPSession.proxyRequestUrl(): String {
        val query = queryParameterString?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
        return "http://127.0.0.1:$listeningPort$uri$query"
    }

    private fun IHTTPSession.rangeHeader(): String = headers.entries
        .firstOrNull { it.key.equals("Range", ignoreCase = true) }
        ?.value
        .orEmpty()

    private fun okhttp3.Headers.singleLine(): String = joinToString(", ") { (name, value) -> "$name=$value" }

    private fun Map<String, String>.singleLine(): String = entries.joinToString(", ") { (name, value) -> "$name=$value" }

    private fun okhttp3.Response.toProxyStatus(): Response.IStatus = Response.Status.lookup(code) ?: UpstreamStatus(code, message)

    private fun ByteArray.hasMp4Magic(): Boolean = size >= MAGIC_BYTES && copyOfRange(4, 8).decodeToString() == "ftyp"

    private fun String.toProxyMimeType(isMp4: Boolean): String {
        val normalized = substringBefore(';').trim().lowercase()
        return when {
            isMp4 -> "video/mp4"
            normalized == "application/x-mpegurl" -> "application/vnd.apple.mpegurl"
            normalized == "application/vnd.apple.mpegurl" -> "application/vnd.apple.mpegurl"
            isNotBlank() -> this
            else -> "application/octet-stream"
        }
    }

    private class ProxyInputStream(
        input: InputStream,
        private val response: okhttp3.Response,
    ) : FilterInputStream(input) {
        override fun read(): Int = try {
            super.read()
        } catch (error: IOException) {
            Log.e(TAG, "EXCEPTION=${error.stackTraceToString()}", error)
            throw error
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
            super.read(buffer, offset, length)
        } catch (error: IOException) {
            Log.e(TAG, "EXCEPTION=${error.stackTraceToString()}", error)
            throw error
        }

        override fun close() {
            try {
                super.close()
            } finally {
                response.close()
            }
        }
    }

    private data class UpstreamStatus(
        private val code: Int,
        private val reason: String,
    ) : Response.IStatus {
        override fun getRequestStatus(): Int = code

        override fun getDescription(): String = "$code ${reason.ifBlank { "Upstream Error" }}"
    }

    private companion object {
        const val TAG = "RedeCanaisStream"
        const val STREAM_PATH = "/stream/"
        const val MAX_STREAM_CONTEXTS = 64
        const val MAGIC_BYTES = 8
        val RESPONSE_HEADERS = listOf(
            "Content-Range",
            "Accept-Ranges",
            "ETag",
            "Last-Modified",
        )
    }
}
