package eu.kanade.tachiyomi.animeextension.pt.anikyuu

import aniyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.animeextension.pt.anikyuu.extractors.ByseExtractor
import eu.kanade.tachiyomi.animeextension.pt.anikyuu.extractors.EmTurbovidExtractor
import eu.kanade.tachiyomi.animeextension.pt.anikyuu.extractors.StrmupExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response

class Anikyuu :
    AnimeStream(
        "pt-BR",
        "Anikyuu",
        "https://anikyuu.to",
    ) {
    private val tag by lazy { javaClass.simpleName }

    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    // ============================ Video Links =============================
    override val prefQualityValues = listOf("1080p", "720p", "480p", "360p", "240p")

    // ============================ Video Links =============================

    private val byseExtractor by lazy { ByseExtractor(client, headers, baseUrl) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val strmupExtractor by lazy { StrmupExtractor(client, headers) }
    private val emTurbovidExtractor by lazy { EmTurbovidExtractor(client, headers) }

    override fun videoListParse(response: Response): List<Video> {
        val mirrors = response.useAsJsoup()
            .select(videoListSelector())
            .parallelCatchingFlatMapBlocking { element ->
                listOf(HosterMirror(getHosterUrl(element), element.text()))
            }
        val (turboMirrors, fallbackMirrors) = mirrors.partition { mirror ->
            mirror.url.toHttpUrlOrNull()?.host.orEmpty().isTurboHost()
        }
        if (turboMirrors.isNotEmpty()) {
            val videos = turboMirrors.parallelCatchingFlatMapBlocking { mirror ->
                getVideoList(mirror.url, mirror.name)
            }
            if (videos.isNotEmpty()) return videos
        }
        return fallbackMirrors.parallelCatchingFlatMapBlocking { mirror ->
            getVideoList(mirror.url, mirror.name)
        }
    }

    override suspend fun getVideoList(url: String, name: String): List<Video> {
        val parsed = url.toHttpUrlOrNull()
        val host = parsed?.host.orEmpty()
        return try {
            val result = when {
                host == "turbovidhls.com" || host.endsWith(".turbovidhls.com") -> turboVidHlsVideos(url)
                host == "emturbovid.com" || host.endsWith(".emturbovid.com") -> emTurbovidVideos(url)
                host == "byselapuix.com" || host.endsWith(".byselapuix.com") -> byseExtractor.videosFromUrl(url)
                "filemoon" in host -> filemoonExtractor.videosFromUrl(url)
                host == "strmup.to" || host.endsWith(".strmup.to") -> strmupExtractor.videosFromUrl(url)
                else -> emptyList()
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun emTurbovidVideos(url: String): List<Video> {
        val canonicalUrl = url.toHttpUrlOrNull()
            ?.newBuilder()
            ?.host("turbovidhls.com")
            ?.build()
            ?.toString()
        if (canonicalUrl != null) {
            val canonicalVideos = turboVidHlsVideos(canonicalUrl)
            if (canonicalVideos.isNotEmpty()) return canonicalVideos
        }
        return try {
            emTurbovidExtractor.videosFromUrl(url)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private suspend fun turboVidHlsVideos(url: String): List<Video> = try {
        emTurbovidExtractor.videosFromUrl(url, "TurboVidHLS", optimisticDirectHls = true)
    } catch (_: Throwable) {
        emptyList()
    }

    private fun String.isTurboHost(): Boolean = this == "turbovidhls.com" || endsWith(".turbovidhls.com") ||
        this == "emturbovid.com" || endsWith(".emturbovid.com")

    private data class HosterMirror(
        val url: String,
        val name: String,
    )
}
