package eu.kanade.tachiyomi.animeextension.pt.anikyuu.extractors

import aniyomi.lib.m3u8server.M3u8ServerManager
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class EmTurbovidExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    // Must outlive getVideoList(): the player fetches the playlist and each segment later.
    private val m3u8ServerManager by lazy { M3u8ServerManager(client) }

    suspend fun videosFromUrl(
        url: String,
        quality: String = "EmTurbovid",
        optimisticDirectHls: Boolean = false,
    ): List<Video> {
        val embedUrl = url.toHttpUrlOrNull()
        if (embedUrl == null) {
            return emptyList()
        }
        val embedOrigin = "${embedUrl.scheme}://${embedUrl.host}"
        val pageHeaders = headers.newBuilder().set("Referer", "$embedOrigin/").build()
        return try {
            val page = client.newCall(GET(url, pageHeaders)).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                PageResponse(
                    body = response.body.string(),
                )
            }
            val videoUrl = extractUrlPlay(page.body)
            val hlsHeaders = headers.newBuilder()
                .set("Referer", url)
                .set("Origin", embedOrigin)
                .build()
            val urlPlayHls = videoUrl?.let { urlPlay -> resolveUrlPlay(urlPlay, hlsHeaders) }
            val directHls = if (urlPlayHls == null) {
                findDirectHls(
                    body = page.body,
                    hlsHeaders = hlsHeaders,
                    optimisticSingleCandidate = optimisticDirectHls,
                )
            } else {
                null
            }
            val realHls = urlPlayHls ?: directHls
                ?: return emptyList()
            val remoteManifestValid = when {
                realHls.skipPreValidation || realHls.validated -> true
                else -> validateManifest(realHls.url, hlsHeaders)
            }
            if (!remoteManifestValid) return emptyList()

            try {
                m3u8ServerManager.startServer()
            } catch (_: Exception) {
                return emptyList()
            }
            if (!m3u8ServerManager.isRunning()) return emptyList()
            val localUrl = m3u8ServerManager.processM3u8Url(
                realHls.url,
                hlsHeaders["Referer"],
                hlsHeaders["User-Agent"],
            ) ?: return emptyList()
            listOf(
                Video(
                    videoUrl = localUrl,
                    url = realHls.url,
                    quality = quality,
                    headers = hlsHeaders,
                ),
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun resolveUrlPlay(videoUrl: String, hlsHeaders: Headers): HlsCandidate? {
        val wrapperBody = client.newCall(GET(videoUrl, hlsHeaders)).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body.string()
        }
        val nestedUrl = M3U8_REGEX.findAll(wrapperBody)
            .map { it.groupValues[1].replace("\\/", "/") }
            .firstOrNull { it != videoUrl }
        return when {
            nestedUrl != null -> HlsCandidate(nestedUrl, validated = false)
            wrapperBody.contains("#EXTM3U") -> HlsCandidate(videoUrl, validated = true)
            videoUrl.substringBefore('?').endsWith(".m3u8", true) -> HlsCandidate(videoUrl, validated = false)
            else -> null
        }
    }

    private fun findDirectHls(
        body: String,
        hlsHeaders: Headers,
        optimisticSingleCandidate: Boolean,
    ): HlsCandidate? {
        val candidates = DIRECT_M3U8_REGEX.findAll(body.normalizeUrlEscapes())
            .map { it.value.trimEnd('.', ',', ';', ')', ']', '}') }
            .distinct()
            .toList()
        if (optimisticSingleCandidate && candidates.size == 1 && candidates.single().toHttpUrlOrNull() != null) {
            return HlsCandidate(candidates.single(), skipPreValidation = true)
        }
        for (candidate in candidates) {
            if (candidate.toHttpUrlOrNull() == null) continue
            val valid = validateManifest(candidate, hlsHeaders)
            if (valid) {
                return HlsCandidate(candidate, validated = true)
            }
        }
        return null
    }

    private fun validateManifest(url: String, requestHeaders: Headers): Boolean = client.newCall(GET(url, requestHeaders)).execute().use { response ->
        response.isSuccessful && response.body.string().contains("#EXTM3U")
    }

    private fun String.normalizeUrlEscapes(): String = replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")

    private fun extractUrlPlay(body: String): String? {
        var nameIndex = body.indexOf("urlPlay")
        while (nameIndex >= 0) {
            val equalsIndex = body.indexOf('=', nameIndex + "urlPlay".length)
            if (equalsIndex < 0) return null
            var valueIndex = equalsIndex + 1
            while (valueIndex < body.length && body[valueIndex].isWhitespace()) valueIndex++
            val quote = body.getOrNull(valueIndex)
            if (quote == '\'' || quote == '"' || quote == '`') {
                val valueStart = ++valueIndex
                var escaped = false
                while (valueIndex < body.length) {
                    val current = body[valueIndex]
                    if (current == quote && !escaped) {
                        return body.substring(valueStart, valueIndex)
                            .replace("\\/", "/")
                            .replace("\\u002F", "/")
                            .trim()
                            .takeIf(String::isNotBlank)
                    }
                    escaped = current == '\\' && !escaped
                    if (current != '\\') escaped = false
                    valueIndex++
                }
            }
            nameIndex = body.indexOf("urlPlay", nameIndex + "urlPlay".length)
        }
        return null
    }

    private data class PageResponse(
        val body: String,
    )

    private data class HlsCandidate(
        val url: String,
        val validated: Boolean = false,
        val skipPreValidation: Boolean = false,
    )

    companion object {
        private val M3U8_REGEX = Regex("""(https?://[^\"'\s<>]+\.m3u8(?:\?[^\"'\s<>]*)?)""", RegexOption.IGNORE_CASE)
        private val DIRECT_M3U8_REGEX = Regex("""https?://[^\s\"'<>\\]+\.m3u8(?:\?[^\s\"'<>\\]+)?""", RegexOption.IGNORE_CASE)
    }
}
