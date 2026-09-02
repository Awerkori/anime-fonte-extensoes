package eu.kanade.tachiyomi.animeextension.pt.redecanais.videolist

import eu.kanade.tachiyomi.animeextension.pt.redecanais.htmlproxy.toRedeCanaisHost
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun String.toAbsoluteRedeCanaisUrl(baseUrl: String, baseHost: String): String = when {
    startsWith("//") -> "https:$this"
    startsWith("/") -> "$baseUrl$this"
    startsWith("http", ignoreCase = true) -> toRedeCanaisHost(baseHost)
    else -> "$baseUrl/${trimStart('/')}"
}

internal fun String.toAbsoluteVideoUrl(baseUrl: String): String = when {
    startsWith("//") -> "https:$this"
    startsWith("http", ignoreCase = true) -> this
    startsWith("/") -> "$baseUrl$this"
    else -> "$baseUrl/${trimStart('/')}"
}

internal fun String.toOkHttpCompatibleVideoUrl(): String {
    if (toHttpUrlOrNull() != null) return this
    val authorityStart = indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return this
    val authorityEnd = indexOf('/', authorityStart).takeIf { it >= 0 } ?: length
    val authority = substring(authorityStart, authorityEnd)
    val host = authority.substringBeforeLast(':')
    if (!host.endsWith(NULL_NULL_HOST_SUFFIX)) return this

    val port = authority.removePrefix(host)
    return replaceRange(authorityStart, authorityEnd, "$NULL_NULL_SAFE_HOST$port")
}

private const val NULL_NULL_HOST_SUFFIX = ".null-null.shop"
private const val NULL_NULL_SAFE_HOST = "rc.null-null.shop"
