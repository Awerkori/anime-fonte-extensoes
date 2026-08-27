package eu.kanade.tachiyomi.animeextension.pt.yumefree.extractors

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class VidaraExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    suspend fun videosFromUrl(url: String, prefix: String = "Vidara"): List<Video> {
        val filecode = url.trimEnd('/').substringAfterLast('/')
        if (filecode.isBlank()) return emptyList()

        val hosts = listOf("https://vidara.to", "https://morningmarkets.ink")
        for (host in hosts) {
            val videos = extractFromHost(host, filecode, prefix)
            if (videos.isNotEmpty()) return videos
        }
        return emptyList()
    }

    private suspend fun extractFromHost(host: String, filecode: String, prefix: String): List<Video> = runCatching {
        val jsonBody = """{"filecode":"$filecode","referrer":"https://yumefree.online/","parent_domain":"yumefree.online","device":"web"}"""
        val reqBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

        val apiHeaders = headers.newBuilder()
            .set("Referer", "$host/e/$filecode")
            .set("Origin", host)
            .set("Accept", "application/json, text/plain, */*")
            .build()

        val response = client.newCall(POST("$host/api/stream", apiHeaders, reqBody)).awaitSuccess()
        val streamDto = response.parseAs<VidaraStreamDto>()
        val streamingUrl = streamDto.streamingUrl

        if (streamingUrl.isNotBlank() && streamingUrl.contains(".m3u8")) {
            playlistUtils.extractFromHls(
                streamingUrl,
                referer = host,
                videoNameGen = { q: String -> "$prefix - $q" },
            )
        } else {
            emptyList()
        }
    }.getOrDefault(emptyList())

    @Serializable
    data class VidaraStreamDto(
        @SerialName("streaming_url") val streamingUrl: String = "",
        val filecode: String? = null,
        val thumbnail: String? = null,
    )
}
