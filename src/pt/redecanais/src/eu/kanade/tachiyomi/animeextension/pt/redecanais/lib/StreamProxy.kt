package eu.kanade.tachiyomi.animeextension.pt.redecanais.lib

import android.util.Log
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.UnknownHostException

internal data class StreamContext(
    val url: String,
    val userAgent: String,
    val referer: String,
    val origin: String,
    val cookie: String?,
)

internal fun StreamContext.buildMediaRequest(range: String): Request {
    val originalUrl = url.toHttpUrl()
    val needsTlsHostAlias = originalUrl.host == MEDIA_EDGE_DOMAIN || originalUrl.host.endsWith(".$MEDIA_EDGE_DOMAIN")
    val requestUrl = if (needsTlsHostAlias) originalUrl.newBuilder().host(MEDIA_EDGE_DOMAIN).build() else originalUrl

    return Request.Builder()
        .url(requestUrl)
        .header("User-Agent", userAgent)
        .header("Referer", referer)
        .header("Origin", origin)
        .header("Accept", "*/*")
        .header("Accept-Language", MEDIA_ACCEPT_LANGUAGE)
        .header("Accept-Encoding", "identity")
        .apply {
            if (needsTlsHostAlias) header("Host", originalUrl.host)
            cookie?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) }
            range.takeIf { it.isNotBlank() }?.let { header("Range", it) }
        }
        .build()
}

internal class StreamProxy(
    client: OkHttpClient,
) {
    private val mediaClient = client.newBuilder()
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .cookieJar(CookieJar.NO_COOKIES)
        .dns(MediaDns)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private var server: StreamServer? = null

    @Synchronized
    fun proxiedUrl(context: StreamContext): String {
        val current = server?.takeIf { it.isRunning() } ?: StreamServer(mediaClient).also {
            it.start()
            server = it
        }

        return current.createStreamUrl(context)
    }
}

private const val MEDIA_ACCEPT_LANGUAGE = "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"
private const val MEDIA_EDGE_DOMAIN = "null-null.shop"

private object MediaDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> = try {
        Dns.SYSTEM.lookup(hostname)
    } catch (error: UnknownHostException) {
        if (hostname != MEDIA_EDGE_DOMAIN && !hostname.endsWith(".$MEDIA_EDGE_DOMAIN")) throw error

        CLOUDFLARE_EDGE_ADDRESSES.map { address -> InetAddress.getByAddress(hostname, address) }.also {
            Log.d("RedeCanaisStream", "UPSTREAM_DNS_FALLBACK=$hostname -> ${it.joinToString { address -> address.hostAddress.orEmpty() }}")
        }
    }

    private val CLOUDFLARE_EDGE_ADDRESSES = listOf(
        byteArrayOf(104, 18, 20, 4),
        byteArrayOf(104, 18, 21, 4),
    )
}
