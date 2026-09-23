package eu.kanade.tachiyomi.animeextension.pt.animefire.extractors

import android.util.Log
import eu.kanade.tachiyomi.animeextension.pt.animefire.dto.Stream
import eu.kanade.tachiyomi.animeextension.pt.animefire.nativebridge.AnimeFireNative
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import fi.iki.elonen.NanoHTTPD
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

/** SegmentTemplate DASH cannot use PlaylistUtils' BaseURL-only extractor. */
class AnimeFireExtractor(private val client: OkHttpClient) {
    fun videos(streams: List<Stream>, headers: Headers): List<Video> = streams.filterNot { it.offline }.flatMap { stream ->
        val url = stream.url ?: return@flatMap emptyList()
        try {
            val manifest = client.newCall(GET(url, headers)).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                response.body!!.string()
            }
            val language = when (stream.audio) {
                "dublado" -> "Dublado"
                "legendado" -> "Legendado"
                else -> stream.audio ?: "Idioma não informado"
            } + if (stream.machineTranslated) " (tradução automática)" else ""
            when {
                manifest.startsWith("#EXTM3U") -> HlsMaster.variants(manifest, url)
                    .sortedWith(
                        compareByDescending<HlsVariant> { it.isH264 }
                            .thenBy { variant -> if (variant.isH264) -(variant.height ?: 0) else variant.height ?: Int.MAX_VALUE },
                    )
                    .map { variant ->
                        val codec = if (variant.isH264) {
                            "H.264"
                        } else if (variant.codecs.contains("av01", true)) {
                            "AV1"
                        } else {
                            variant.codecs
                        }
                        val localUrl = HlsServer.register(
                            variant.url,
                            client,
                            headers,
                            compatibilityTs = variant.isH264,
                            av1Compatibility = variant.codecs.contains("av01", true),
                        )
                        Log.d(TAG, "variant resolution=${variant.height} codec=$codec child=${variant.url} local=$localUrl result=CREATED")
                        // The host persists the selected source by Video.url. Using the
                        // master URL here made every rendition indistinguishable and could
                        // revive an earlier AV1 selection instead of the H.264 default.
                        Video(variant.url, "Akumast - $language - ${variant.height}p $codec", localUrl, headers)
                    }
                else -> DashManifest.variants(manifest, url).map { (height, xml) ->
                    val localUrl = DashServer.register(xml, client, headers)
                    Video(url, "Akumast - $language - ${height?.let { "${it}p" } ?: "Unknown"}", localUrl, headers)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "stream=$url result=REJECTED reason=${e.message}", e)
            // An unavailable audio/server must not discard another valid one.
            emptyList()
        }
    }

    private companion object {
        const val TAG = "ANIMEFIRE_VIDEO"
    }
}

internal data class HlsVariant(val height: Int?, val bandwidth: Long?, val codecs: String, val url: String) {
    val isH264 get() = codecs.contains("avc1", ignoreCase = true)
}

internal object HlsMaster {
    fun variants(master: String, sourceUrl: String): List<HlsVariant> {
        val lines = master.lineSequence().toList()
        return lines.mapIndexedNotNull { index, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF:")) return@mapIndexedNotNull null
            val child = lines.getOrNull(index + 1)?.takeIf { it.isNotBlank() && !it.startsWith("#") }
                ?: return@mapIndexedNotNull null
            HlsVariant(
                height = Regex("RESOLUTION=\\d+x(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull(),
                bandwidth = Regex("(?:^|,)BANDWIDTH=(\\d+)").find(line.substringAfter(':'))?.groupValues?.get(1)?.toLongOrNull(),
                codecs = Regex("CODECS=\"([^\"]+)\"").find(line)?.groupValues?.get(1).orEmpty(),
                url = URL(URL(sourceUrl), child).toExternalForm(),
            )
        }
    }
}

private object HlsServer : NanoHTTPD("127.0.0.1", 0) {
    private class Entry(
        val client: OkHttpClient,
        val headers: Headers,
        val compatibilityTs: Boolean,
        val av1Compatibility: Boolean,
    ) {
        data class Route(val url: String, val initUrl: String?)

        val initCache = mutableMapOf<String, ByteArray>()
        val routes = mutableMapOf<String, Route>()
        val tsCache = object : LinkedHashMap<String, ByteArray>(16, 0.75F, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 12
        }
        val pendingPrefetches = mutableSetOf<String>()
        val prefetcher = Executors.newSingleThreadExecutor()
        val av1TranscodeLock = Any()
        var diagnosticAv1Ts: ByteArray? = null
        var diagnosticAv1Error: String? = null
    }
    private val entries = object : LinkedHashMap<String, Entry>(256, 0.75F, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 256
    }

    @Synchronized fun register(
        url: String,
        client: OkHttpClient,
        headers: Headers,
        compatibilityTs: Boolean,
        av1Compatibility: Boolean,
    ): String {
        if (!isAlive) start(SOCKET_READ_TIMEOUT, true)
        val id = UUID.randomUUID().toString()
        entries[id] = Entry(client, headers, compatibilityTs, av1Compatibility)
        Log.i("ANIMEFIRE_NATIVE", "HLS_REGISTER id=$id compatibilityTs=$compatibilityTs av1Compatibility=$av1Compatibility child=$url")
        return "http://127.0.0.1:$listeningPort/$id/playlist.m3u8?url=${url.encode()}"
    }

    override fun serve(session: IHTTPSession): Response {
        val parts = session.uri.trimStart('/').split('/', limit = 3)
        val entry = synchronized(this) { entries[parts.firstOrNull()] }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Reload the video list.")
        if (parts.getOrNull(1) == "diagnostic.ts") {
            val bytes = synchronized(entry) { entry.diagnosticAv1Ts }
            return if (bytes != null) {
                newFixedLengthResponse(Response.Status.OK, "video/mp2t", ByteArrayInputStream(bytes), bytes.size.toLong())
            } else {
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, synchronized(entry) { entry.diagnosticAv1Error } ?: "AV1 diagnostic has not run")
            }
        }
        val routeKey = parts.getOrNull(2)?.substringBefore('.')
        val route = routeKey?.let { synchronized(entry) { entry.routes[it] } }
        val url = route?.url ?: session.parms["url"]
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing URL")
        val localUrl = "http://127.0.0.1:$listeningPort${session.uri}?${session.queryParameterString}"
        Log.d("ANIMEFIRE_VIDEO", "REQUEST ts=${System.currentTimeMillis()} method=${session.method} local=$localUrl upstream=$url Range=${session.headers["range"]}")
        return try {
            val started = System.nanoTime()
            val requestHeaders = entry.headers.newBuilder()
                .removeAll("Range")
                .removeAll("If-Range")
                .set("Accept-Encoding", "identity")
                .build()
            val resource = HlsResource.fetch(entry.client, url, requestHeaders)
            val initUrl = route?.initUrl ?: session.parms["init"]
            val useCompatibilityTs = entry.compatibilityTs || entry.av1Compatibility
            val mediaBytes = if (initUrl != null && useCompatibilityTs) {
                synchronized(entry) { entry.tsCache[url] } ?: run {
                    if (entry.av1Compatibility) {
                        entry.transcodeAv1(url, initUrl, requestHeaders, resource.bytes)
                    } else {
                        val init = synchronized(entry) {
                            entry.initCache[initUrl] ?: HlsResource.fetch(entry.client, initUrl, requestHeaders).bytes.also { entry.initCache[initUrl] = it }
                        }
                        val startedTransmux = System.nanoTime()
                        AnimeFireNative.ensureLoaded()
                        AnimeFireNative.transmuxToMpegTs(init, resource.bytes).also { ts ->
                            synchronized(entry) { entry.tsCache[url] = ts }
                            Log.d("ANIMEFIRE_NATIVE", "TRANSMUX bytesIn=${init.size + resource.bytes.size} bytesOut=${ts.size} ms=${(System.nanoTime() - startedTransmux) / 1_000_000} url=$url")
                        }
                    }
                }
            } else {
                resource.bytes
            }
            if (initUrl != null && entry.av1Compatibility) {
                entry.prefetchNext(url, initUrl, requestHeaders)
            }
            var localLength = resource.bytes.size
            val response = if (resource.contentType.contains("mpegurl", true) || resource.bytes.copyOfRange(0, minOf(7, resource.bytes.size)).toString(Charsets.UTF_8) == "#EXTM3U") {
                val playlist = rewrite(resource.bytes.toString(Charsets.UTF_8), resource.url, parts.first(), entry)
                localLength = playlist.toByteArray(Charsets.UTF_8).size
                newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", playlist)
            } else {
                localLength = mediaBytes.size
                newFixedLengthResponse(Response.Status.OK, if (initUrl != null && useCompatibilityTs) "video/mp2t" else "video/mp4", ByteArrayInputStream(mediaBytes), mediaBytes.size.toLong())
            }
            Log.d(
                "ANIMEFIRE_VIDEO",
                "RESPONSE ts=${System.currentTimeMillis()} method=${session.method} local=$localUrl requested=$url resolved=${resource.url} Range=${session.headers["range"]} upstreamStatus=${resource.status} upstreamType=${resource.contentType} upstreamLength=${resource.length} upstreamRange=${resource.contentRange} bodySize=${resource.bytes.size} sha256=${resource.bytes.sha256()} status=200 Content-Length=$localLength Content-Range=${response.getHeader("Content-Range")} ms=${(System.nanoTime() - started) / 1_000_000}",
            )
            response
        } catch (e: Exception) {
            Log.e("ANIMEFIRE_VIDEO", "SEGMENT FAILED url=$url", e)
            newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "HLS request failed")
        }
    }

    private fun rewrite(playlist: String, sourceUrl: String, id: String, entry: Entry): String {
        val compatibilityTs = entry.compatibilityTs || entry.av1Compatibility
        val base = "http://127.0.0.1:$listeningPort/$id/resource?url="
        fun absolute(url: String) = URL(URL(sourceUrl), url).toExternalForm()
        fun local(url: String, init: String? = null): String {
            val upstreamUrl = absolute(url)
            val upstreamInit = init?.let(::absolute)
            if (!compatibilityTs) return base + upstreamUrl.encode() + upstreamInit?.let { "&init=${it.encode()}" }.orEmpty()

            // Do not expose an upstream .jpg in the local URL. libavformat 61
            // examines query strings too and otherwise selects image2/MJPEG for a
            // perfectly valid MPEG-TS response.
            val key = UUID.randomUUID().toString()
            synchronized(entry) { entry.routes[key] = Entry.Route(upstreamUrl, upstreamInit) }
            return "http://127.0.0.1:$listeningPort/$id/segment/$key.ts"
        }
        var initUrl: String? = null
        val lines = if (playlist.contains("#EXT-X-STREAM-INF") && playlist.contains("avc1", true)) {
            val filtered = mutableListOf<String>()
            var skipVariantUrl = false
            playlist.lineSequence().forEach { line ->
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    skipVariantUrl = line.contains("av01", true)
                    if (!skipVariantUrl) filtered += line
                } else if (skipVariantUrl && line.isNotBlank() && !line.startsWith("#")) {
                    skipVariantUrl = false
                } else if (!skipVariantUrl) {
                    filtered += line
                }
            }
            filtered.asSequence()
        } else {
            playlist.lineSequence()
        }
        return lines.mapNotNull { line ->
            when {
                line.startsWith("#EXT-X-MAP:") && compatibilityTs -> {
                    initUrl = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                    null
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    initUrl = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                    line.replace(Regex("URI=\"([^\"]+)\"")) { match -> "URI=\"${local(match.groupValues[1])}\"" }
                }
                line.startsWith("#") -> line.replace(Regex("URI=\"([^\"]+)\"")) { match -> "URI=\"${local(match.groupValues[1])}\"" }
                line.isBlank() -> line
                else -> local(line, initUrl)
            }
        }.joinToString("\n")
    }

    private fun Entry.prefetchNext(url: String, initUrl: String, requestHeaders: Headers) {
        val match = Regex("/(\\d+)\\.jpg(?=\\?|$)").find(url) ?: return
        val segment = match.groupValues[1].toIntOrNull() ?: return
        // A single conversion ahead prevents the MediaCodec queue from
        // delaying a user-initiated seek to a distant segment.
        val nextUrl = url.replaceRange(match.range, "/${segment + 1}.jpg")
        val queued = synchronized(this) {
            nextUrl !in tsCache && pendingPrefetches.add(nextUrl)
        }
        if (!queued) return
        prefetcher.execute {
            try {
                val fragment = HlsResource.fetch(client, nextUrl, requestHeaders).bytes
                val ts = transcodeAv1(nextUrl, initUrl, requestHeaders, fragment)
                Log.d("ANIMEFIRE_NATIVE", "AV1_PREFETCH_OK url=$nextUrl bytes=${ts.size}")
            } catch (e: Exception) {
                Log.w("ANIMEFIRE_NATIVE", "AV1_PREFETCH_FAILED url=$nextUrl", e)
            } finally {
                synchronized(this) { pendingPrefetches.remove(nextUrl) }
            }
        }
    }

    private fun Entry.transcodeAv1(url: String, initUrl: String, requestHeaders: Headers, fragment: ByteArray): ByteArray = synchronized(av1TranscodeLock) {
        synchronized(this) { tsCache[url] } ?: run {
            val init = synchronized(this) {
                initCache[initUrl] ?: HlsResource.fetch(client, initUrl, requestHeaders).bytes.also { initCache[initUrl] = it }
            }
            val started = System.nanoTime()
            AnimeFireNative.ensureLoaded()
            AnimeFireNative.transcodeAv1FragmentToMpegTs(init, fragment).also { ts ->
                synchronized(this) { tsCache[url] = ts }
                Log.d("ANIMEFIRE_NATIVE", "AV1_TRANSCODE bytesIn=${init.size + fragment.size} bytesOut=${ts.size} ms=${(System.nanoTime() - started) / 1_000_000} url=$url")
            }
        }
    }

    private fun String.encode() = URLEncoder.encode(this, Charsets.UTF_8.name())
}

private fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun HlsResource.isPlaylist() = contentType.contains("mpegurl", true) || bytes.copyOfRange(0, minOf(7, bytes.size)).toString(Charsets.UTF_8) == "#EXTM3U"

internal class HlsResource(
    val bytes: ByteArray,
    val contentType: String,
    val url: String,
    val status: Int,
    val length: String?,
    val contentRange: String?,
) {
    companion object {
        fun fetch(client: OkHttpClient, url: String, headers: Headers): HlsResource {
            val buffer = ByteArrayOutputStream()
            var total: Long? = null
            var resolvedUrl = url
            while (true) {
                val requestHeaders = headers.newBuilder().removeAll("Range").removeAll("If-Range").apply {
                    // Reconstruct unsolicited partial responses, independently of player ranges.
                    if (total != null) set("Range", "bytes=${buffer.size()}-")
                }.build()
                client.newCall(GET(resolvedUrl, requestHeaders)).execute().use { response ->
                    check(response.isSuccessful) { "Upstream HTTP ${response.code}" }
                    resolvedUrl = response.request.url.toString()
                    val contentType = response.header("Content-Type").orEmpty()
                    val bytes = response.body!!.bytes()
                    if (response.code == 200) {
                        return HlsResource(
                            bytes,
                            contentType,
                            resolvedUrl,
                            response.code,
                            response.header("Content-Length"),
                            response.header("Content-Range"),
                        )
                    }
                    check(response.code == 206) { "Unexpected HTTP ${response.code}" }
                    val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                        ?: error("Missing or invalid Content-Range")
                    val start = range.groupValues[1].toLong()
                    val end = range.groupValues[2].toLong()
                    val length = range.groupValues[3].toLong()
                    check(start == buffer.size().toLong() && end >= start && end < length && end - start + 1 == bytes.size.toLong()) { "Incomplete or inconsistent upstream range" }
                    check(length <= Int.MAX_VALUE && (total == null || total == length)) { "Invalid or changed resource length" }
                    if (total == null && bytes.size.toLong() == length) {
                        return HlsResource(
                            bytes,
                            contentType,
                            resolvedUrl,
                            response.code,
                            response.header("Content-Length"),
                            response.header("Content-Range"),
                        )
                    }
                    total = length
                    buffer.write(bytes)
                    if (buffer.size().toLong() == length) {
                        return HlsResource(
                            buffer.toByteArray(),
                            contentType,
                            resolvedUrl,
                            response.code,
                            response.header("Content-Length"),
                            response.header("Content-Range"),
                        )
                    }
                }
            }
        }
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
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "Segment unavailable")
            }
            val input = object : FilterInputStream(upstream.body!!.byteStream()) {
                override fun close() {
                    upstream.close()
                }
            }
            newFixedLengthResponse(if (upstream.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK, "video/mp4", input, upstream.body!!.contentLength()).apply {
                upstream.header("Content-Range")?.let { addHeader("Content-Range", it) }
                addHeader("Accept-Ranges", "bytes")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "Segment request failed")
        }
    }
}
