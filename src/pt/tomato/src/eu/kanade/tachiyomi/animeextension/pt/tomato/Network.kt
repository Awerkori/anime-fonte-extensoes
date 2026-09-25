package eu.kanade.tachiyomi.animeextension.pt.tomato

import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

internal const val PROD_API_URL = "https://prod-api.tomatoanimes.com"
internal const val OFFICIAL_CONNECT_TIMEOUT_MS = 8_000
private const val EDGE_API_HOST = "edge.betomato.com"
private const val PROD_API_HOST = "prod-api.tomatoanimes.com"
private const val OFFICIAL_USER_AGENT = "okhttp/4.11.0"
private const val OFFICIAL_ACCEPT = "application/json, text/plain, */*"
private const val OFFICIAL_ACCEPT_ENCODING = "gzip, deflate"
private const val MAX_ERROR_BODY_BYTES = 256L * 1024L
private val NATIVE_USER_AGENT = System.getProperty("http.agent")
    ?.filter { it.code in 0x20..0x7e }
    ?.takeIf(String::isNotBlank)
    ?: "Dalvik/2.1.0"

internal fun Headers.withBearer(token: String?): Headers = newBuilder()
    .set("Accept", "application/json")
    .apply {
        token?.trim()?.removePrefix("Bearer ")?.takeIf(String::isNotEmpty)?.let {
            set("Authorization", "Bearer $it")
        }
    }
    .build()

internal fun Headers.withNativeAuthHeaders(): Headers = newBuilder()
    .set("Accept", "application/json")
    .set("User-Agent", NATIVE_USER_AGENT)
    .removeAll("Authorization")
    .build()

internal fun Request.withOfficialClientHeaders(appVersion: String): Request {
    if (!usesOfficialClientContract()) return this

    return newBuilder()
        .header("Accept", OFFICIAL_ACCEPT)
        .header("Accept-Encoding", OFFICIAL_ACCEPT_ENCODING)
        .header("User-Agent", OFFICIAL_USER_AGENT)
        .header("request-time", System.currentTimeMillis().toString())
        .apply {
            if (url.encodedPath == "/v2/animes/feed") {
                header("x-app", appVersion)
            } else {
                removeHeader("x-app")
            }
        }
        .build()
}

internal fun Request.usesOfficialClientContract(): Boolean {
    if (url.host != PROD_API_HOST && url.host != EDGE_API_HOST) return false

    val path = url.encodedPath
    return path == "/v2/animes/feed" ||
        path == "/v2/content/search" ||
        (path.startsWith("/v2/anime/") && !path.endsWith("/stream")) ||
        (path.startsWith("/season/") && path.endsWith("/episodes"))
}

internal fun Request.fallbackRequest(): Request? {
    if (url.host !in setOf(PROD_API_HOST, EDGE_API_HOST) || !canRetry()) return null
    val fallbackHost = if (url.host == PROD_API_HOST) EDGE_API_HOST else PROD_API_HOST
    return newBuilder().url(url.newBuilder().host(fallbackHost).build()).build()
}

internal fun IOException.isConnectionFailure() = this is UnknownHostException || this is ConnectException || this is SocketTimeoutException

internal fun Response.decodeContentEncoding(): Response {
    val encoding = header("Content-Encoding")?.substringBefore(',')?.trim()?.lowercase()
    val contentType = body.contentType()
    val decoded = when (encoding) {
        "gzip" -> GZIPInputStream(body.byteStream()).use { it.readBytes() }
        "deflate" -> InflaterInputStream(body.byteStream()).use { it.readBytes() }
        else -> return this
    }

    return newBuilder()
        .removeHeader("Content-Encoding")
        .removeHeader("Content-Length")
        .body(decoded.toResponseBody(contentType))
        .build()
}

internal fun Response.requireSuccess(): Response {
    if (isSuccessful) return this

    val apiMessage = runCatching {
        val body = JSONObject(peekBody(MAX_ERROR_BODY_BYTES).string())
        sequenceOf("message", "status", "error", "detail")
            .mapNotNull { body.opt(it) as? String }
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)
    }.getOrNull()

    val message = when {
        code == 401 || code == 403 -> "Sua sessão da Tomato expirou. Entre novamente."
        code == 429 -> "Muitas solicitações à Tomato. Tente novamente em instantes."
        code in 500..599 -> "Servidor Tomato indisponível. Tente novamente mais tarde."
        !apiMessage.isNullOrBlank() -> apiMessage.replace(Regex("[\\r\\n]+"), " ").take(300)
        else -> "A Tomato retornou um erro inesperado (HTTP $code)."
    }
    close()
    throw IOException(message)
}

private fun Request.canRetry(): Boolean {
    if (method == "GET") return true
    val path = url.encodedPath
    return path == "/v2/content/search" ||
        (path.startsWith("/season/") && path.endsWith("/episodes")) ||
        path == "/tokenlogin/" ||
        path == "/checkupdate/"
}
