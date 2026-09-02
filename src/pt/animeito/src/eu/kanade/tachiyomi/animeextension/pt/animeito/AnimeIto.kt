package eu.kanade.tachiyomi.animeextension.pt.animeito

import android.util.Log
import eu.kanade.tachiyomi.animeextension.pt.animeito.extractors.AnimeItoExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import org.jsoup.nodes.Element

class AnimeIto :
    AnimeStream(
        "pt-BR",
        "Animeito",
        "https://animesonline.io",
    ) {
    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    // ============================ Video Links =============================
    override val prefQualityValues = listOf("1080p", "720p", "480p", "360p", "240p")

    // ============================ Video Links =============================

    override fun videoListSelector() = "ul.tabs_videos li"

    override suspend fun getHosterUrl(element: Element): String {
        val encodedData = element.attr("value")

        return getHosterUrl(encodedData)
    }

    private val animeitoExtractor by lazy { AnimeItoExtractor(client, headers) }

    override fun videoListParse(response: okhttp3.Response): List<Video> {
        val items = response.useAsJsoup().select(videoListSelector())
        Log.d(VIDEO_DEBUG_TAG, "episode=${response.request.url} hosters=${items.size}")
        return items.parallelCatchingFlatMapBlocking { element ->
            val name = element.text()
            val url = getHosterUrl(element)
            Log.d(VIDEO_DEBUG_TAG, "hoster=${name.trim()} url=${url.substringBefore('?')}")
            runCatching {
                getVideoList(url, name, response.request.url.toString())
            }.getOrElse { error ->
                Log.w(VIDEO_DEBUG_TAG, "provider failed=${error::class.simpleName}:${error.message}")
                emptyList()
            }
        }.also { Log.d(VIDEO_DEBUG_TAG, "videos=${it.size}") }
    }

    private suspend fun getVideoList(url: String, name: String, referer: String): List<Video> = when {
        "anidrive.click" in url -> animeitoExtractor.videosFromUrl(url, name.trim(), referer)
        else -> {
            Log.d(VIDEO_DEBUG_TAG, "unsupported provider=${url.substringBefore('/')}")
            emptyList()
        }
    }

    companion object {
        private const val VIDEO_DEBUG_TAG = "ANIMEITO_VIDEO_DEBUG"
    }
}
