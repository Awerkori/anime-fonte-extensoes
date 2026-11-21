package eu.kanade.tachiyomi.animeextension.pt.redetoons

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

const val PREF_DOMAIN_KEY = "preferred_domain"
const val PREF_DOMAIN_TITLE = "Domínio atual (requer reinicialização da app)"
const val PREF_DOMAIN_DEFAULT = "https://redetoons.li"
const val DEFAULT_BASE_URL = "https://redetoons.li"

fun toBaseUrl(raw: String?): String {
    if (raw.isNullOrBlank()) return DEFAULT_BASE_URL
    val normalized = normalizeInputUrl(raw)
    val parsed = normalized.toHttpUrlOrNull() ?: return DEFAULT_BASE_URL

    val pathSegments = parsed.pathSegments.filter { it.isNotEmpty() }
    val newSegments = if (pathSegments.lastOrNull().equals("browse", ignoreCase = true)) {
        pathSegments.dropLast(1)
    } else {
        pathSegments
    }

    val scheme = parsed.scheme
    val host = parsed.host
    val port = if ((scheme == "http" && parsed.port == 80) || (scheme == "https" && parsed.port == 443)) {
        ""
    } else {
        ":${parsed.port}"
    }
    val path = if (newSegments.isEmpty()) "" else "/${newSegments.joinToString("/")}"
    return "$scheme://$host$port$path"
}

// The account is sent in the login form: only to an https site, even if the user typed an http domain.
fun isSecureUrl(url: String): Boolean = url.toHttpUrlOrNull()?.isHttps == true

fun isValidUrl(raw: String?): Boolean {
    if (raw.isNullOrBlank()) return false
    val trimmed = raw.trim()
    val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val parsed = withScheme.toHttpUrlOrNull() ?: return false
    return (parsed.scheme == "http" || parsed.scheme == "https") &&
        parsed.host.isNotBlank() &&
        !parsed.host.contains(" ")
}

fun normalizeInputUrl(raw: String): String {
    val trimmed = raw.trim()
    val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val parsed = withScheme.toHttpUrlOrNull() ?: return withScheme.trimEnd('/')
    return parsed.newBuilder().build().toString().trimEnd('/')
}

// redetoons.my (the old default) and redetoons.gay now redirect to or stand for the current site.
private val retiredHosts = setOf("redetoons.my", "www.redetoons.my", "redetoons.gay", "www.redetoons.gay")

// The stored domain the source should use: a blank preference or a retired domain falls back to the default.
fun resolveBaseUrl(stored: String?): String {
    val url = toBaseUrl(stored)
    val host = url.toHttpUrlOrNull()?.host.orEmpty().lowercase()
    return if (host in retiredHosts) DEFAULT_BASE_URL else url
}
