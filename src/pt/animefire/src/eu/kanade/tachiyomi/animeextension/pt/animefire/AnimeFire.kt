package eu.kanade.tachiyomi.animeextension.pt.animefire

import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animeextension.pt.animefire.dto.AFResponse
import eu.kanade.tachiyomi.animeextension.pt.animefire.dto.AnimeDetails
import eu.kanade.tachiyomi.animeextension.pt.animefire.dto.Card
import eu.kanade.tachiyomi.animeextension.pt.animefire.dto.EpisodeDetails
import eu.kanade.tachiyomi.animeextension.pt.animefire.dto.Home
import eu.kanade.tachiyomi.animeextension.pt.animefire.extractors.AnimeFireExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class AnimeFire :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {
    override val name = "Anime Fire"
    override val baseUrl = "https://animefire.io"
    override val lang = "pt-BR"
    override val supportsLatest = true
    private val api = "https://api.animefire.io"
    private val preferences by getPreferencesLazy()
    private val extractor by lazy { AnimeFireExtractor(client) }

    @Volatile private var genres = emptyList<String>()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/").set("Origin", baseUrl)

    override fun popularAnimeRequest(page: Int) = GET("$api/home", headers)
    override fun popularAnimeParse(response: Response): AnimesPage {
        val cards = response.parseAs<AFResponse<Home>>().data.carousels.first { it.key == "most-liked" }.items
        return AnimesPage(cards.map(::toAnime), false).also { debug("popular=${cards.size}") }
    }

    override fun latestUpdatesRequest(page: Int) = GET("$api/home", headers)
    override fun latestUpdatesParse(response: Response): AnimesPage = error("Use getLatestUpdates")
    override suspend fun getLatestUpdates(page: Int): AnimesPage = coroutineScope {
        if (page > 1) return@coroutineScope AnimesPage(emptyList(), false)
        val home = client.newCall(latestUpdatesRequest(page)).awaitSuccess().parseAs<AFResponse<Home>>().data
        val episodes = home.carousels.first { it.key == "new-episodes" }.items.distinctBy { it.id }
        val gate = Semaphore(4)
        val cards = episodes.map { episode ->
            async {
                gate.withPermit {
                    try {
                        client.newCall(GET("$api/episode/${episode.id}", headers)).awaitSuccess()
                            .parseAs<AFResponse<EpisodeDetails>>().data.anime
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        debug("latest episode failed=${e.javaClass.simpleName}")
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull().distinctBy { it.id }
        check(cards.isNotEmpty() || episodes.isEmpty()) { "Não foi possível carregar os animes dos novos episódios." }
        AnimesPage(cards.map(::toAnime), false).also { debug("latest episodes=${episodes.size} anime=${cards.size}") }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): okhttp3.Request {
        val type = filters.firstInstanceOrNull<AFFilters.Type>()?.state ?: 0
        val path = if (query.isNotBlank()) {
            "/animes/pesquisar"
        } else if (type == 1) {
            "/animes/filmes"
        } else {
            "/animes"
        }
        val url = "$api$path".toHttpUrl().newBuilder().addQueryParameter("page", page.toString())
        if (query.isNotBlank()) url.addQueryParameter("q", query.trim())
        filters.firstInstanceOrNull<AFFilters.Genre>()?.selected?.takeIf { it.isNotBlank() }?.let { url.addQueryParameter("genre", it) }
        when (filters.firstInstanceOrNull<AFFilters.Audio>()?.state) {
            1 -> url.addQueryParameter("audio", "dublado")
            2 -> url.addQueryParameter("audio", "legendado")
        }
        return GET(url.build(), headers)
    }
    override fun searchAnimeParse(response: Response): AnimesPage {
        val result = response.parseAs<AFResponse<List<Card>>>()
        result.meta?.genres?.takeIf { it.isNotEmpty() }?.let { genres = it }
        return AnimesPage(result.data.distinctBy { it.id }.map(::toAnime), result.meta?.let { it.currentPage < it.lastPage } ?: false)
            .also { debug("search=${it.animes.size} next=${it.hasNextPage}") }
    }
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val id = when {
            query.startsWith(PREFIX_SEARCH) -> query.removePrefix(PREFIX_SEARCH)
            query.startsWith("$baseUrl/anime/") -> query.toHttpUrl().pathSegments.last()
            else -> return super.getSearchAnime(page, query, filters)
        }
        require(id.matches(Regex("[A-Za-z0-9_-]+"))) { "Identificador inválido" }
        return AnimesPage(listOf(client.newCall(GET("$api/anime/$id", headers)).awaitSuccess().let(::animeDetailsParse)), false)
    }
    private fun toAnime(card: Card) = SAnime.create().apply {
        url = "/anime/${card.id}"
        title = card.title
        thumbnail_url = card.poster
    }
    private fun animeId(anime: SAnime): String {
        val path = (if (anime.url.startsWith("http")) anime.url else "$baseUrl${anime.url}").toHttpUrl().pathSegments
        require(path.size == 2 && path.first() == "anime") { "Endereço da versão antiga. Localize esta obra pela busca e migre para o novo cadastro." }
        return path.last()
    }
    override fun animeDetailsRequest(anime: SAnime) = GET("$api/anime/${animeId(anime)}", headers)
    override fun animeDetailsParse(response: Response): SAnime {
        val details = response.parseAs<AFResponse<AnimeDetails>>().data
        val hero = details.hero
        return SAnime.create().apply {
            url = "/anime/${hero.id}"
            title = hero.titles["BR"] ?: hero.titles.values.first()
            thumbnail_url = hero.poster
            genre = hero.genres.joinToString()
            status = when (hero.status) {
                "airing" -> SAnime.ONGOING
                "completed" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            description = buildString {
                append(hero.synopsis.orEmpty())
                hero.titles.values.distinct().filter { it != title }.takeIf { it.isNotEmpty() }?.let { append("\n\nTítulos alternativos: ${it.joinToString()}") }
                hero.publishedAt?.take(4)?.toIntOrNull()?.let { append("\nAno: $it") }
                hero.audio?.let { append("\nÁudio: $it") }
                hero.ageRating?.let { append("\nClassificação: $it") }
                hero.runtime?.let { append("\nDuração: $it") }
            }
            initialized = true
            debug("details seasons=${details.seasons.size} episodes=${details.episodes.size}")
        }
    }
    override fun episodeListRequest(anime: SAnime) = animeDetailsRequest(anime)
    override fun episodeListParse(response: Response): List<SEpisode> {
        val details = response.parseAs<AFResponse<AnimeDetails>>().data
        return details.episodes.distinctBy { it.id }.sortedWith(compareByDescending<eu.kanade.tachiyomi.animeextension.pt.animefire.dto.Episode> { it.season ?: 0 }.thenByDescending { it.number }).map { ep ->
            SEpisode.create().apply {
                url = "/episode/${ep.id}"
                val number = if (ep.number % 1F == 0F) ep.number.toInt().toString() else ep.number.toString()
                name = if (ep.season == null) ep.title ?: details.hero.titles["BR"] ?: details.hero.titles.values.first() else "T${ep.season} E$number" + ep.title?.let { " - $it" }.orEmpty()
                episode_number = ep.number
                scanlator = ep.audio
                date_upload = synchronized(dateFormat) { dateFormat.tryParse(ep.createdAt?.replace(Regex("\\.\\d+Z$"), "Z")) }
            }
        }.also { debug("episodes=${it.size}") }
    }
    override fun videoListRequest(episode: SEpisode) = GET("$api${episode.url}", headers)
    override fun videoListParse(response: Response): List<Video> = extractor.videos(response.parseAs<AFResponse<EpisodeDetails>>().data.streams, headers).sortVideos()
    override fun videoUrlParse(response: Response): String = error("Use videoListParse")

    override fun List<Video>.sortVideos(): List<Video> {
        val preferred = preferences.getString("quality_selection", "best")
        return sortedWith(compareByDescending<Video> { preferred != "best" && resolution(it.videoTitle) == resolution(preferred.orEmpty()) }.thenByDescending { resolution(it.videoTitle) })
    }
    private fun resolution(label: String) = Regex("(\\d+)p").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    override fun getFilterList() = AFFilters.list(genres)
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "quality_selection"
            title = "Qualidade preferida"
            entries = arrayOf("Maior disponível", "1080p", "720p", "480p", "360p")
            entryValues = arrayOf("best", "1080p", "720p", "480p", "360p")
            setDefaultValue("best")
            summary = "%s"
        }.also(screen::addPreference)
    }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    companion object {
        const val PREFIX_SEARCH = "id:"
        fun debug(message: String) {
            if (BuildConfig.DEBUG) Log.d("ANIMEFIRE_DEBUG", message)
        }
    }
}
