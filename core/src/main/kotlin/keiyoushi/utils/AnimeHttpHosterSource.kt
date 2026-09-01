package keiyoushi.utils

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import okhttp3.Response

abstract class AnimeHttpHosterSource : AnimeHttpSource() {
    open suspend fun getVideoList(episode: SEpisode): List<Video> = getHosterList(episode)
        .parallelCatchingFlatMapBlocking(::getVideoList)

    override fun seasonListParse(response: Response) = throw UnsupportedOperationException()

    protected fun legacyHoster(
        hosterUrl: String = "",
        hosterName: String = "",
        videoList: List<Video>? = null,
        internalData: String = "",
    ) = try {
        Hoster(hosterUrl, hosterName, videoList, internalData, lazy = false)
    } catch (_: Throwable) {
        Hoster(hosterUrl, hosterName, videoList, internalData)
    }
}
