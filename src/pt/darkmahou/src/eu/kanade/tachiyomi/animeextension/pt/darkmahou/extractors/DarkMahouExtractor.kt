package eu.kanade.tachiyomi.animeextension.pt.darkmahou.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class DarkMahouExtractor(private val client: OkHttpClient, private val headers: Headers) {
    fun videosFromUrl(url: String): List<Video> {
        val doc = client.newCall(GET(url, headers)).execute().asJsoup()

        val fragment = url.toHttpUrl().fragment

        val soraddl = doc.select("div.mctnx div.soraddl .sorattl h3").find {
            it.text() == fragment
        }?.closest(".soraddl") ?: return emptyList()

        val videos = mutableListOf<Video>()

        // 1. Padrão antigo: divs com a classe .soraurl
        soraddl.select(".soraurl").forEach {
            val prefix = if (it.text().lowercase().contains("dublado")) {
                "Dublado"
            } else {
                "Legendado"
            }
            it.select(".slink a").forEach { link ->
                var videoUrl = link.attr("href")
                if (videoUrl.startsWith("magnet:") && !videoUrl.contains("&index=")) {
                    videoUrl += "&index=0"
                }
                val quality = link.text().trim()
                videos.add(Video(videoUrl, "$prefix - $quality", videoUrl))
            }
        }

        // 2. Novo padrão: tabelas (muito usado para magnets e batches longos)
        soraddl.select(".content table tr").forEach { row ->
            val prefix = row.selectFirst(".reso .res")?.text()?.trim()?.removeSuffix(">>") ?: ""
            row.select(".slink a").forEach { link ->
                var videoUrl = link.attr("href")
                if (videoUrl.startsWith("magnet:") && !videoUrl.contains("&index=")) {
                    videoUrl += "&index=0"
                }
                val quality = link.text().trim()
                val name = if (prefix.isNotEmpty()) "$prefix - $quality" else quality
                videos.add(Video(videoUrl, name, videoUrl))
            }
        }

        return videos
    }
}
