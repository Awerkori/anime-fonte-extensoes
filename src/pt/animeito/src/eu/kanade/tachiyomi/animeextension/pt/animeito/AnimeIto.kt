package eu.kanade.tachiyomi.animeextension.pt.animeito

import android.util.Log
import eu.kanade.tachiyomi.animeextension.pt.animeito.extractors.AnimeItoExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeIto :
    AnimeStream(
        "pt-BR",
        "Animeito",
        "https://animesonline.io",
    ) {
    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    // ============================ Filters =================================
    override val fetchFilters = false

    override fun getFilterList() = AnimeFilterList(
        OrderFilter(),
        StatusFilter(),
        TypeFilter(),
        SubFilter(),
        GenresFilter(GENRES_LIST),
    )

    // ============================ Requests ================================
    override fun popularAnimeRequest(page: Int) = GET("$animeListUrl/?order=popular&page=$page", headers)

    override fun latestUpdatesRequest(page: Int) = GET("$animeListUrl/?order=update&page=$page", headers)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            return GET("$baseUrl/page/$page/?s=$query", headers)
        }

        val url = "$animeListUrl/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        for (filter in filters) {
            when (filter) {
                is OrderFilter -> {
                    val order = filter.toUriPart()
                    if (order.isNotBlank()) url.addQueryParameter("order", order)
                }
                is StatusFilter -> {
                    val status = filter.toUriPart()
                    if (status.isNotBlank()) url.addQueryParameter("status", status)
                }
                is TypeFilter -> {
                    val type = filter.toUriPart()
                    if (type.isNotBlank()) url.addQueryParameter("type", type)
                }
                is SubFilter -> {
                    val sub = filter.toUriPart()
                    if (sub.isNotBlank()) url.addQueryParameter("sub", sub)
                }
                is GenresFilter -> {
                    filter.state.filter { it.state }.forEach {
                        url.addQueryParameter("genre[]", it.value)
                    }
                }
                else -> {}
            }
        }

        return GET(url.build(), headers)
    }

    // ============================ Selectors ===============================
    override fun searchAnimeSelector() = "div.catalog-grid article.catalog-card a, article.catalog-card a, div.listupd article a.tip"

    override fun searchAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        title = element.selectFirst("div.catalog-card-info h2, h2, div.tt, div.ttl")?.text()
            ?: element.attr("title")
        thumbnail_url = element.selectFirst("img")?.getImageUrl()
    }

    override fun searchAnimeNextPageSelector(): String = "nav.catalog-pagination a[rel=next], a[rel=next], div.pagination a.next, div.hpage > a.r"

    // ========================== Anime Details =============================
    override fun animeDetailsParse(document: Document): SAnime {
        val ficha = document.selectFirst("div.clean-anime-ficha")
            ?: return super.animeDetailsParse(document)

        return SAnime.create().apply {
            setUrlWithoutDomain(document.location())
            title = document.selectFirst(animeTitleSelector)?.text().orEmpty()
            thumbnail_url = document.selectFirst("div.clean-anime-poster img, $animeThumbnailSelector")?.getImageUrl()
            genre = ficha.select(animeGenresSelector).eachText().joinToString()

            val statusText = ficha.selectFirst(".clean-anime-status-label")?.text()
            status = parseStatus(statusText)

            artist = ficha.selectFirst("dl.clean-anime-facts dt:contains(Estúdio) + dd, dl.clean-anime-facts div:has(dt:contains(Estúdio)) dd")?.text()
            author = ficha.selectFirst("details.clean-anime-details dt:contains(Produtores) + dd, details.clean-anime-details div:has(dt:contains(Produtores)) dd")?.text()

            description = buildString {
                getAnimeDescription(document)?.also {
                    append("$it\n\n")
                }

                ficha.selectFirst(".clean-anime-alternative")?.ownText()
                    ?.takeIf(String::isNotBlank)
                    ?.also { append("$animeAltNamePrefix$it\n") }

                ficha.select("dl.clean-anime-facts > div, details.clean-anime-details dl > div").forEach { item ->
                    val label = item.selectFirst("dt")?.text()
                    val value = item.selectFirst("dd")?.text()
                    if (!label.isNullOrBlank() && !value.isNullOrBlank()) {
                        append("$label: $value\n")
                    }
                }
            }
        }
    }

    // ============================ Video Links =============================
    override val prefQualityValues = listOf("1080p", "720p", "480p", "360p", "240p")

    override fun videoListSelector() = "ul.tabs_videos li"

    override suspend fun getHosterUrl(element: Element): String {
        val encodedData = element.attr("value")

        return getHosterUrl(encodedData)
    }

    private val animeitoExtractor by lazy { AnimeItoExtractor(client, headers) }

    override fun videoListParse(response: Response): List<Video> {
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
