package eu.kanade.tachiyomi.animeextension.pt.cineveo

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class CineVEO : AnimeHttpSource() {
    override val name = "CineVEO"
    override val baseUrl = "https://cineveo.cfd"
    override val lang = "pt-BR"
    override val supportsLatest = true

    // The site rejects requests without a Referer from its own host.
    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    private fun catalogRequest(type: String, sort: String, page: Int, search: String? = null): Request {
        val url = "$baseUrl/api".toHttpUrl().newBuilder()
            .addQueryParameter("type", type)
            .addQueryParameter("sort", sort)
            .addQueryParameter("page", page.toString())
            .apply { if (!search.isNullOrBlank()) addQueryParameter("search", search) }
            .build()
        return GET(url, headers)
    }

    override fun popularAnimeRequest(page: Int) = catalogRequest("series", "rating", page)

    override fun latestUpdatesRequest(page: Int) = catalogRequest("series", "recent", page)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = catalogRequest(filters.firstInstanceOrNull<TypeFilter>()?.value ?: "series", "recent", page, query)

    override fun popularAnimeParse(response: Response) = catalogParse(response)

    override fun latestUpdatesParse(response: Response) = catalogParse(response)

    override fun searchAnimeParse(response: Response) = catalogParse(response)

    private fun catalogParse(response: Response): AnimesPage {
        val items = response.parseAs<ApiCatalog>().items
        return AnimesPage(items.map(::animeFromItem), hasNextPage(items.size))
    }

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Populares e recentes mostram séries. Para filmes, escolha o tipo e busque (busca em branco lista o tipo)."),
        TypeFilter(),
    )

    private class TypeFilter : AnimeFilter.Select<String>("Tipo", arrayOf("Séries", "Filmes")) {
        val value get() = arrayOf("series", "movie")[state]
    }

    private fun animeFromItem(item: ApiItem) = SAnime.create().apply {
        title = item.name
        thumbnail_url = item.posterLarge ?: item.poster
        url = detailsPath(item.type, item.id)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val item = response.parseAs<ApiDetails>().item // the body can be read only once
        return animeFromItem(item).apply {
            description = item.plot
            genre = item.genre
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = episodeEntries(response.parseAs<ApiDetails>()).map {
        SEpisode.create().apply {
            name = it.name
            url = it.url
            episode_number = it.number
            scanlator = audioLabel(it.audio)
        }
    }

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // Asks for one byte: the stream URL redirects to a signed MP4, and the final URL is what the player needs.
    override fun hosterListRequest(episode: SEpisode) = GET(baseUrl + episode.url, headersBuilder().set("Range", "bytes=0-0").build())

    override fun hosterListParse(response: Response): List<Hoster> {
        if (!isPlayable(response.code, response.header("Content-Type"))) return emptyList()
        val video = Video(videoUrl = response.request.url.toString(), videoTitle = "CineVEO", headers = headers)
        return listOf(Hoster(hosterName = "CineVEO", videoList = listOf(video)))
    }

    override fun videoUrlParse(response: Response): String = throw UnsupportedOperationException()
}
