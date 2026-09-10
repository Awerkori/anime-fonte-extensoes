package eu.kanade.tachiyomi.animeextension.pt.aniture

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.autoUnpacker
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.net.URLDecoder

internal class AnitureMixDropExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(
        url: String,
        lang: String = "",
        prefix: String = "",
        externalSubs: List<Track> = emptyList(),
        referer: String = "https://mixdrop.co/",
    ): List<Video> {
        val headers = Headers.headersOf(
            "Referer",
            referer,
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/129.0.0.0 Safari/537.36",
        )
        val script = client.newCall(GET(url, headers)).execute().asJsoup()
            .selectFirst("script:containsData(eval):containsData(MDCore)")?.data()
            ?: return emptyList()
        val unpacked = autoUnpacker(script) ?: return emptyList()
        val media = unpacked.substringAfter("Core.wurl=\"", "").substringBefore('"')
        val videoUrl = (if (media.startsWith("//")) "https:$media" else media)
            .toHttpUrlOrNull()?.toString() ?: return emptyList()
        val subs = unpacked.substringAfter("Core.remotesub=\"", "").substringBefore('"')
            .takeIf(String::isNotBlank)
            ?.let { listOf(Track(URLDecoder.decode(it, "utf-8"), "sub")) }
            ?: emptyList()
        val quality = buildString {
            append("${prefix}MixDrop")
            if (lang.isNotBlank()) append("($lang)")
        }
        return listOf(Video(videoUrl, quality, videoUrl, headers = headers, subtitleTracks = subs + externalSubs))
    }
}
