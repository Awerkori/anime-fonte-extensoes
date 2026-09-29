package eu.kanade.tachiyomi.animeextension.pt.streamberry

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.firstInstanceOrNull
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

open class Streamberry : AnimeHttpLegacySource() {
    override val name = "Streamberry"
    override val baseUrl = "https://streamberry.com.br"
    override val lang = "pt-BR"
    override val supportsLatest = true

    private val extractor by lazy { StreamberryExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept", "application/json, text/plain, */*")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api/v1/home/sections/recentes?page=$page&limit=24&sort=popular_desc", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val (animes, hasNextPage) = StreamberryHelper.parseSectionPage(response.body.string())
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/v1/home/sections/recentes?page=$page&limit=24&sort=published_desc", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val typeFilter = filters.firstInstanceOrNull<TypeFilter>()
        val genreFilter = filters.firstInstanceOrNull<GenreFilter>()
        val sortFilter = filters.firstInstanceOrNull<SortFilter>()
        val sort = sortFilter?.selected ?: "popular_desc"
        val genre = genreFilter?.selected?.takeIf { it.isNotBlank() }

        if (query.isNotBlank()) {
            val q = query.trim()
            val encodedQ = URLEncoder.encode(q, "UTF-8")
            return when (typeFilter?.state) {
                1 -> GET("$baseUrl/api/v1/movies?q=$encodedQ&page=$page&limit=24&sort=$sort${genre?.let { "&genre=$it" }.orEmpty()}", headers)
                2 -> GET("$baseUrl/api/v1/series?q=$encodedQ&page=$page&limit=24&sort=$sort${genre?.let { "&genre=$it" }.orEmpty()}", headers)
                3 -> GET("$baseUrl/api/v1/series?q=$encodedQ&category=dorama&page=$page&limit=24&sort=$sort${genre?.let { "&genre=$it" }.orEmpty()}", headers)
                4 -> GET("$baseUrl/api/v1/series?q=$encodedQ&category=reality&page=$page&limit=24&sort=$sort${genre?.let { "&genre=$it" }.orEmpty()}", headers)
                5 -> GET("$baseUrl/api/v1/series?q=$encodedQ&category=documentary&page=$page&limit=24&sort=$sort${genre?.let { "&genre=$it" }.orEmpty()}", headers)
                else -> GET("$baseUrl/api/v1/movies?q=$encodedQ&page=$page&limit=14&sort=$sort${genre?.let { "&genre=$it" }.orEmpty()}&_combined=1", headers)
            }
        }

        return when (typeFilter?.state) {
            1 -> GET("$baseUrl/api/v1/movies?page=$page&limit=24&sort=$sort${genre?.let { "&genre=$it" }.orEmpty()}", headers)
            2 -> GET("$baseUrl/api/v1/series?page=$page&limit=24&sort=$sort${genre?.let { "&genre=$it" }.orEmpty()}", headers)
            3 -> GET("$baseUrl/api/v1/series?category=dorama&page=$page&limit=24&sort=$sort${genre?.let { "&genre=$it" }.orEmpty()}", headers)
            4 -> GET("$baseUrl/api/v1/series?category=reality&page=$page&limit=24&sort=$sort${genre?.let { "&genre=$it" }.orEmpty()}", headers)
            5 -> GET("$baseUrl/api/v1/series?category=documentary&page=$page&limit=24&sort=$sort${genre?.let { "&genre=$it" }.orEmpty()}", headers)
            else -> {
                if (genre != null) {
                    GET("$baseUrl/api/v1/movies?page=$page&limit=14&sort=$sort&genre=$genre&_combined=1", headers)
                } else {
                    GET("$baseUrl/api/v1/home/sections/recentes?page=$page&limit=24&sort=$sort", headers)
                }
            }
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val url = response.request.url
        if (url.encodedPath.contains("/home/sections/recentes")) {
            return popularAnimeParse(response)
        }
        val isCombined = url.queryParameter("_combined") == "1"
        val body = response.body.string()
        if (!isCombined) {
            val (animes, hasNextPage) = StreamberryHelper.parseCatalogPage(body)
            return AnimesPage(animes, hasNextPage)
        }

        val firstData = StreamberryHelper.json.decodeFromString<StreamberryHelper.CatalogResponseDto>(body)
        val seriesUrl = url.newBuilder()
            .encodedPath("/api/v1/series")
            .removeAllQueryParameters("_combined")
            .build()
        val seriesData = runCatching {
            client.newCall(GET(seriesUrl, headers)).execute().use { resp ->
                StreamberryHelper.json.decodeFromString<StreamberryHelper.CatalogResponseDto>(resp.body.string())
            }
        }.getOrNull()

        val firstItems = firstData.items.mapNotNull(StreamberryHelper::animeFromCatalogItem)
        val seriesItems = seriesData?.items?.mapNotNull(StreamberryHelper::animeFromCatalogItem).orEmpty()
        val combined = (firstItems + seriesItems).distinctBy { it.url }
        val hasNextPage = (firstData.pagination != null && firstData.pagination.page < firstData.pagination.totalPages) ||
            (seriesData?.pagination != null && seriesData.pagination.page < seriesData.pagination.totalPages)
        return AnimesPage(combined, hasNextPage)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val path = anime.url.removePrefix("/")
        return if (path.startsWith("filmes/")) {
            val slug = path.substringAfter("filmes/").removeSuffix("/")
            GET("$baseUrl/api/v1/movies/$slug", headers)
        } else {
            val slug = path.substringAfter("series/").removeSuffix("/")
            GET("$baseUrl/api/v1/series/$slug", headers)
        }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val url = response.request.url.encodedPath
        val body = response.body.string()
        return if (url.contains("/movies/")) {
            StreamberryHelper.parseMovieDetails(body)
        } else {
            StreamberryHelper.parseSeriesDetails(body)
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.encodedPath
        val body = response.body.string()
        return if (url.contains("/movies/")) {
            StreamberryHelper.parseMovieEpisodes(body, url)
        } else {
            StreamberryHelper.parseSeriesEpisodes(body, url)
        }
    }

    // ============================ Video List ==============================

    override fun videoListRequest(episode: SEpisode): Request {
        val path = episode.url.removePrefix("/")
        return when {
            path.startsWith("filmes/") -> {
                val slug = path.substringAfter("filmes/").removeSuffix("/")
                GET("$baseUrl/api/v1/movies/$slug", headers)
            }
            path.startsWith("episodios/") || path.startsWith("episodio/") -> {
                val slug = path.substringAfterLast("/").ifEmpty { path.removeSuffix("/").substringAfterLast("/") }
                GET("$baseUrl/api/v1/series/episode-by-slug/$slug", headers)
            }
            path.startsWith("series/") -> {
                val slug = path.substringAfter("series/").removeSuffix("/")
                GET("$baseUrl/api/v1/series/$slug", headers)
            }
            else -> GET("$baseUrl/api/v1/movies/$path", headers)
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url.encodedPath
        val body = response.body.string()
        val players: List<StreamberryHelper.PlayerDto> = when {
            url.contains("/movies/") -> StreamberryHelper.json.decodeFromString<StreamberryHelper.MovieDetailDto>(body).players.orEmpty()
            url.contains("/episode-by-slug/") -> StreamberryHelper.json.decodeFromString<StreamberryHelper.EpisodeDetailDto>(body).players.orEmpty()
            url.contains("/series/") -> {
                val series = StreamberryHelper.json.decodeFromString<StreamberryHelper.SeriesDetailDto>(body)
                series.seasons.orEmpty().firstOrNull()?.episodes.orEmpty().firstOrNull()?.players.orEmpty()
            }
            else -> emptyList()
        }

        val activePlayers = players.filter { it.isActive && !it.embedUrl.isNullOrBlank() }
        if (activePlayers.isEmpty()) return emptyList()

        val grouped = activePlayers.groupBy { it.audioType?.trim()?.uppercase() ?: "PRINCIPAL" }
        val videoList = mutableListOf<Video>()

        for ((audioType, playerList) in grouped) {
            val sorted = playerList.sortedBy { StreamberryHelper.serverPriority(it.serverName.orEmpty()) }
            for (player in sorted) {
                val embedUrl = player.embedUrl ?: continue
                val serverName = player.serverName?.trim().orEmpty().ifBlank { "Servidor" }
                val label = "$serverName - $audioType"
                val videos = runCatching {
                    extractor.videosFromUrl(embedUrl, label, baseUrl, playlistUtils)
                }.getOrDefault(emptyList())

                if (videos.isNotEmpty()) {
                    videoList.addAll(videos)
                    break
                }
            }
        }
        return videoList
    }

    // ============================= Filters ================================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TypeFilter(),
        GenreFilter(),
        SortFilter(),
    )

    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
        defaultValue: Int = 0,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
        defaultValue,
    ) {
        val selected get() = vals[state].second
    }

    private class TypeFilter :
        UriPartFilter(
            "Tipo",
            arrayOf(
                "Todos" to "",
                "Filmes" to "movies",
                "Séries" to "series",
                "Doramas" to "dorama",
                "Reality Shows" to "reality",
                "Documentários" to "documentary",
            ),
        )

    private class GenreFilter :
        UriPartFilter(
            "Gênero",
            arrayOf(
                "Todos" to "",
                "Ação" to "acao",
                "Action & Adventure" to "action-adventure",
                "Animação" to "animacao",
                "Aventura" to "aventura",
                "Cinema TV" to "cinema-tv",
                "Comédia" to "comedia",
                "Crime" to "crime",
                "Documentário" to "documentario",
                "Dorama" to "dorama",
                "Drama" to "drama",
                "Família" to "familia",
                "Fantasia" to "fantasia",
                "Faroeste" to "faroeste",
                "Ficção científica" to "ficcao-cientifica",
                "Guerra" to "guerra",
                "História" to "historia",
                "Kids" to "kids",
                "Mistério" to "misterio",
                "Música" to "musica",
                "Reality" to "reality",
                "Romance" to "romance",
                "Sci-Fi & Fantasy" to "sci-fi-fantasy",
                "Soap" to "soap",
                "Talk" to "talk",
                "Terror" to "terror",
                "Thriller" to "thriller",
                "War & Politics" to "war-politics",
            ),
        )

    private class SortFilter :
        UriPartFilter(
            "Ordenar por",
            arrayOf(
                "Popularidade" to "popular_desc",
                "Mais Recentes" to "published_desc",
                "Data de Lançamento" to "date_desc",
                "Nota (Avaliação)" to "rating_desc",
                "Título (A - Z)" to "title_asc",
                "Título (Z - A)" to "title_desc",
            ),
        )
}
