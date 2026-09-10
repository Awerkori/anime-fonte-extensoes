package eu.kanade.tachiyomi.animeextension.pt.animefire.extractors

import android.util.Log
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animeextension.pt.animefire.dto.Stream
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import fi.iki.elonen.NanoHTTPD
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.FilterInputStream
import java.net.URL
import java.util.UUID

/** SegmentTemplate DASH cannot use PlaylistUtils' BaseURL-only extractor. */
class AnimeFireExtractor(private val client: OkHttpClient) {
    fun videos(streams: List<Stream>, headers: Headers): List<Video> = streams.filterNot { it.offline }.flatMap { stream ->
        val url = stream.url ?: return@flatMap emptyList()
        try {
            val manifest = client.newCall(GET(url, headers)).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                response.body.string()
            }
            val language = when (stream.audio) {
                "dublado" -> "Dublado"
                "legendado" -> "Legendado"
                else -> stream.audio ?: "Idioma não informado"
            } + if (stream.machineTranslated) " (tradução automática)" else ""
            DashManifest.variants(manifest, url).map { (height, xml) ->
                val localUrl = DashServer.register(xml, client, headers)
                Video(url, "Akumast - $language - ${height?.let { "${it}p" } ?: "Unknown"}", localUrl, headers)
            }.also { debug("host=${URL(url).host} audio=$language videos=${it.size} format=DASH") }
        } catch (e: Exception) {
            // An unavailable audio/server must not discard another valid one.
            debug("host=${runCatching { URL(url).host }.getOrDefault("unknown")} failed=${e.javaClass.simpleName}")
            emptyList()
        }
    }
    private fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d("ANIMEFIRE_VIDEO", message)
    }
}

internal object DashManifest {
    fun variants(xml: String, sourceUrl: String): List<Pair<Int?, String>> {
        val document = Jsoup.parse(xml, sourceUrl, Parser.xmlParser())
        require(document.selectFirst("MPD") != null) { "Missing DASH manifest" }
        require(document.select("ContentProtection").isEmpty()) { "Protected DASH is not supported" }
        // The player receives a localhost manifest. Preserve the original segment base.
        document.select("SegmentTemplate").forEach { template ->
            for (attribute in listOf("media", "initialization")) {
                if (template.hasAttr(attribute)) template.attr(attribute, URL(URL(sourceUrl), template.attr(attribute)).toExternalForm())
            }
        }
        val representations = document.select("Representation").filter {
            it.attr("mimeType").startsWith("video/") || it.parent()?.attr("contentType") == "video"
        }
        return representations.map { selected ->
            val copy = document.clone()
            copy.select("Representation").filter {
                (it.attr("mimeType").startsWith("video/") || it.parent()?.attr("contentType") == "video") && it.attr("id") != selected.attr("id")
            }.forEach { it.remove() }
            copy.select("AdaptationSet").filter { it.select("Representation").isEmpty() }.forEach { it.remove() }
            selected.attr("height").toIntOrNull() to copy.outerHtml()
        }.sortedByDescending { it.first ?: 0 }
    }
}

/** Same loopback manifest-serving pattern used by the repository's playlist servers. */
private object DashServer : NanoHTTPD("127.0.0.1", 0) {
    private class Entry(val xml: String, val routes: Map<String, String>, val client: OkHttpClient, val headers: Headers)
    private val entries = object : LinkedHashMap<String, Entry>(256, 0.75F, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 256
    }

    @Synchronized fun register(xml: String, client: OkHttpClient, headers: Headers): String {
        if (!isAlive) start(SOCKET_READ_TIMEOUT, true)
        val id = UUID.randomUUID().toString()
        val base = "http://127.0.0.1:$listeningPort/$id/"
        val document = Jsoup.parse(xml, "", Parser.xmlParser())
        val routes = mutableMapOf<String, String>()
        document.select("SegmentTemplate").forEach { template ->
            for (attribute in listOf("media", "initialization")) {
                if (!template.hasAttr(attribute)) continue
                val url = template.attr(attribute)
                val prefixEnd = url.substringBefore('$').lastIndexOf('/') + 1
                val route = "s${routes.size}"
                routes[route] = url.substring(0, prefixEnd)
                template.attr(attribute, "$base$route/${url.substring(prefixEnd)}")
            }
        }
        entries[id] = Entry(document.outerHtml(), routes, client, headers)
        return "${base}manifest.mpd"
    }
    override fun serve(session: IHTTPSession): Response {
        val parts = session.uri.trimStart('/').split('/', limit = 3)
        val entry = synchronized(this) { entries[parts.firstOrNull()] }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Reload the video list.")
        if (parts.getOrNull(1) == "manifest.mpd") return newFixedLengthResponse(Response.Status.OK, "application/dash+xml", entry.xml)
        val prefix = entry.routes[parts.getOrNull(1)]
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Unknown segment")
        val suffix = parts.getOrNull(2)?.takeIf { !it.contains("..") }
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid segment")
        return try {
            val requestHeaders = entry.headers.newBuilder().apply {
                session.headers["range"]?.let { set("Range", it) }
            }.build()
            val upstream = entry.client.newCall(GET(prefix + suffix, requestHeaders)).execute()
            if (!upstream.isSuccessful) {
                val status = upstream.code
                upstream.close()
                if (BuildConfig.DEBUG) Log.d("ANIMEFIRE_VIDEO", "segment HTTP=$status")
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "Segment unavailable")
            }
            val input = object : FilterInputStream(upstream.body.byteStream()) {
                override fun close() {
                    upstream.close()
                }
            }
            newChunkedResponse(if (upstream.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK, "video/mp4", input).apply {
                upstream.header("Content-Range")?.let { addHeader("Content-Range", it) }
                addHeader("Accept-Ranges", "bytes")
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.d("ANIMEFIRE_VIDEO", "segment error=${e.javaClass.simpleName}")
            newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "Segment request failed")
        }
    }
}
