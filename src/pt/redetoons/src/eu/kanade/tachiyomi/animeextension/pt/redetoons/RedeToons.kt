package eu.kanade.tachiyomi.animeextension.pt.redetoons

import android.widget.Toast
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class RedeToons :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    override val name = "RedeToons"
    override val baseUrl = "https://redetoons.win"
    override val lang = "pt-BR"
    override val supportsLatest = true
    private val preferences by getPreferencesLazy()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set("Accept", "application/json")

    override fun popularAnimeRequest(page: Int): Request = shelvesRequest("popular", page)
    override fun popularAnimeParse(response: Response): AnimesPage = error("Use getPopularAnime")
    override suspend fun getPopularAnime(page: Int): AnimesPage = catalogPage("popular", page)
    override fun latestUpdatesRequest(page: Int): Request = shelvesRequest("recent", page)
    override fun latestUpdatesParse(response: Response): AnimesPage = error("Use getLatestUpdates")
    override suspend fun getLatestUpdates(page: Int): AnimesPage = catalogPage("recent", page)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegment("api").addPathSegment("search")
            .addQueryParameter("q", query.trim()).addQueryParameter("cv", "c2052").build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = response.parseAs<SearchResponse>().let {
        AnimesPage(it.results.map { item -> item.toSAnime() }, false)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val allowed = allowedCategories(filters)
        if (query.isBlank()) {
            val shelves = client.newCall(shelvesRequest()).awaitSuccess().parseAs<ShelvesResponse>().payload
            return shelves.catalogItems("recent").filterBy(allowed).toPage(page)
        }

        val results = client.newCall(searchAnimeRequest(page, query, filters)).awaitSuccess()
            .parseAs<SearchResponse>().results
        return AnimesPage(results.filterBy(allowed).map { it.toSAnime() }, false)
    }

    override fun getFilterList() = AnimeFilterList(Filters.Type())

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        MultiSelectListPreference(screen.context).apply {
            key = CONTENT_FILTER_KEY
            title = "Filtro de conteúdo"
            entries = ContentCategory.entries.map { it.label }.toTypedArray()
            entryValues = ContentCategory.entries.map { it.key }.toTypedArray()
            setDefaultValue(ALL_CONTENT_KEYS)
            summary = contentSummary(selectedContentCategories())
            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                val selected = normalizeContentCategories(newValue as Set<String>)
                preferences.edit().putStringSet(CONTENT_FILTER_KEY, selected.mapTo(mutableSetOf()) { it.key }).apply()
                summary = contentSummary(selected)
                if (selected.size == ContentCategory.entries.size && (newValue as Set<*>).isEmpty()) {
                    Toast.makeText(screen.context, "Selecione ao menos um tipo. Todos foram restaurados.", Toast.LENGTH_SHORT).show()
                }
                false
            }
        }.also(screen::addPreference)
    }

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl/api/tmdb/${anime.url}", headers)
    override fun animeDetailsParse(response: Response): SAnime = response.parseAs<DetailsDto>().toSAnime(response.request.url.toString().substringAfterLast('/'))

    override fun episodeListRequest(anime: SAnime): Request = if (anime.url.startsWith("movie/")) {
        GET("$baseUrl/api/play-link?contract=3&tmdbId=${anime.url.substringAfter('/')}&type=movie", headers)
    } else {
        GET("$baseUrl/api/series-playable/${anime.url.substringAfter('/')}", headers)
    }
    override fun episodeListParse(response: Response): List<SEpisode> {
        if (response.request.url.queryParameter("type") == "movie") {
            val id = response.request.url.queryParameter("tmdbId") ?: return emptyList()
            val playable = response.parseAs<PlayLinkResponse>()
            if (playable.missing || (playable.variants.isEmpty() && playable.url.isNullOrBlank())) return emptyList()
            return listOf(
                SEpisode.create().apply {
                    url = "movie|$id"
                    name = "Filme"
                    episode_number = 1F
                },
            )
        }
        val id = response.request.url.pathSegments.last()
        return response.parseAs<EpisodesResponse>().episodes.map { episode ->
            SEpisode.create().apply {
                url = "$id|${episode.s}|${episode.e}"
                name = "T${episode.s}E${episode.e} - ${episode.name ?: "Episódio ${episode.e}"}"
                episode_number = episode.s + episode.e / 1000f
            }
        }.reversed()
    }

    override fun seasonListParse(response: Response): List<SAnime> = emptyList()

    override fun hosterListRequest(episode: SEpisode): Request {
        val parts = episode.url.split('|')
        val id = if (parts[0] == "movie") parts[1] else parts[0]
        val query = if (parts[0] == "movie") {
            "contract=3&tmdbId=$id&type=movie"
        } else {
            "contract=3&tmdbId=$id&type=tv&season=${parts[1]}&episode=${parts[2]}"
        }
        return GET("$baseUrl/api/play-link?$query", headers)
    }

    override fun hosterListParse(response: Response): List<Hoster> = listOf(
        Hoster(videoList = videos(response)),
    )

    private fun videos(response: Response): List<Video> {
        val data = response.parseAs<PlayLinkResponse>()
        if (data.missing) return emptyList()
        val variants = data.variants.ifEmpty { listOf(Variant("default", data.url)) }
        return variants.flatMap { variant ->
            val url = variant.url?.takeIf { it.startsWith("http") } ?: return@flatMap emptyList()
            val quality = variant.quality.orEmpty().replaceFirstChar { it.uppercase() }
            val urls = (listOf(url) + variant.mirrors).distinct()
            urls.map { stream -> Video(stream, "RedeToons - $quality", stream, headers) }
        }
    }

    override fun videoUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun shelvesRequest(shelf: String = "browse", page: Int = 1) = GET("$baseUrl/api/shelves?shelf=$shelf&page=$page", headers)
    private suspend fun catalogPage(kind: String, page: Int): AnimesPage {
        val shelves = client.newCall(shelvesRequest(kind, page)).awaitSuccess().parseAs<ShelvesResponse>().payload
        return shelves.catalogItems(kind).filterBy(selectedContentCategories()).toPage(page)
    }
    private fun CatalogItem.toSAnime() = SAnime.create().apply {
        url = "${media_type ?: "tv"}/$id"
        title = name ?: title ?: id.toString()
        thumbnail_url = poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
    }
    private fun ShelfItem.toSAnime() = SAnime.create().apply {
        url = "${media_type ?: "tv"}/$tmdb_id"
        title = title ?: tmdb_id.toString()
        thumbnail_url = poster_path
    }
    private fun List<CatalogItem>.toPage(page: Int): AnimesPage {
        if (page < 1) return AnimesPage(emptyList(), false)
        val pageItems = distinctBy { it.key }.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
        return AnimesPage(pageItems.map { it.toSAnime() }, pageItems.size == PAGE_SIZE)
    }
    private fun ShelvesPayload.catalogItems(kind: String): List<CatalogItem> = listOf(animes, filmes, series)
        .map { shelves ->
            shelves.filter { shelf ->
                (kind == "recent" && shelf.genre_slug == "__recent") ||
                    (kind == "popular" && shelf.genre_slug in POPULAR_SLUGS)
            }.flatMap { it.items }.map { it.toCatalogItem() }
        }
        .interleave()
    private fun ShelfItem.toCatalogItem() = CatalogItem(tmdb_id, tmdb_id, media_type, title, title, poster_path, year)
    private fun <T> List<List<T>>.interleave(): List<T> = buildList {
        val largest = this@interleave.maxOfOrNull { it.size } ?: 0
        repeat(largest) { index -> this@interleave.forEach { items -> items.getOrNull(index)?.let(::add) } }
    }
    private val CatalogItem.key get() = "${media_type ?: "tv"}/${tmdb_id ?: id}"
    private suspend fun List<CatalogItem>.filterBy(categories: Set<ContentCategory>): List<CatalogItem> {
        if (categories.size == ContentCategory.entries.size) return this
        return coroutineScope {
            val gate = Semaphore(6)
            map { item ->
                async {
                    gate.withPermit {
                        val type = item.media_type ?: "tv"
                        val details = runCatching {
                            client.newCall(GET("$baseUrl/api/tmdb/$type/${item.tmdb_id ?: item.id}", headers)).awaitSuccess().parseAs<DetailsDto>()
                        }.getOrNull() ?: return@withPermit null
                        if (categories.any { it.matches(type, details) }) item else null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }
    private fun DetailsDto.toSAnime(path: String) = SAnime.create().apply {
        url = path
        title = name ?: title ?: original_name ?: original_title ?: id.toString()
        thumbnail_url = poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
        description = overview
        genre = genres.mapNotNull { it.name }.joinToString(", ")
        status = when (state?.lowercase()) {
            "ended", "canceled" -> SAnime.COMPLETED
            else -> SAnime.ONGOING
        }
    }

    @kotlinx.serialization.Serializable data class SearchResponse(val results: List<CatalogItem> = emptyList())

    private enum class ContentCategory(val key: String, val label: String) {
        ANIMATION("animation", "Animação"),
        MOVIES("movies", "Filmes"),
        SERIES("series", "Séries"),
    }

    private fun ContentCategory.matches(mediaType: String, details: DetailsDto): Boolean {
        // This is RedeToons' own frontend rule: Animation plus Japanese
        // language or country is an Anime, regardless of movie/tv media type.
        val anime = details.genres.any { it.id == ANIMATION_GENRE_ID } &&
            (details.original_language.equals("ja", true) || details.origin_country.any { it.equals("JP", true) })
        return when (this) {
            ContentCategory.ANIMATION -> anime
            ContentCategory.MOVIES -> mediaType == "movie" && !anime
            ContentCategory.SERIES -> mediaType == "tv" && !anime
        }
    }

    private fun selectedContentCategories(): Set<ContentCategory> {
        val saved = preferences.getStringSet(CONTENT_FILTER_KEY, ALL_CONTENT_KEYS).orEmpty()
        val normalized = normalizeContentCategories(saved)
        if (normalized.map { it.key }.toSet() != saved) preferences.edit().putStringSet(CONTENT_FILTER_KEY, normalized.mapTo(mutableSetOf()) { it.key }).apply()
        return normalized
    }
    private fun normalizeContentCategories(values: Set<String>) = ContentCategory.entries.filterTo(mutableSetOf()) { it.key in values }
        .ifEmpty { ContentCategory.entries.toSet() }
    private fun allowedCategories(filters: AnimeFilterList): Set<ContentCategory> {
        val global = selectedContentCategories()
        val temporary = filters.firstInstanceOrNull<Filters.Type>()?.category
        return temporary?.let { global.intersect(setOf(it)) } ?: global
    }
    private fun contentSummary(categories: Set<ContentCategory>) = when (categories.size) {
        1 -> "Somente ${categories.single().label}"
        else -> categories.joinToString { it.label }
    }

    private object Filters {
        class Type : AnimeFilter.Select<String>("Tipo", arrayOf("Todos", "Animes", "Séries", "Filmes")) {
            val category get() = entries.getOrNull(state)

            private val entries = arrayOf<ContentCategory?>(null, ContentCategory.ANIMATION, ContentCategory.SERIES, ContentCategory.MOVIES)
        }
    }

    private companion object {
        const val ANIMATION_GENRE_ID = 16
        const val CONTENT_FILTER_KEY = "content_filter"
        const val PAGE_SIZE = 10
        val ALL_CONTENT_KEYS = ContentCategory.entries.mapTo(mutableSetOf()) { it.key }
        val POPULAR_SLUGS = setOf("__top10_a", "__top10_b")
    }
}
