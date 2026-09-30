package eu.kanade.tachiyomi.animeextension.pt.animefire

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
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
import keiyoushi.utils.tryParse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class AnimeFire :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    override val name = "Anime Fire"
    override val baseUrl = "https://animefire.one"
    override val lang = "pt-BR"
    override val supportsLatest = true
    private val api = "https://api.animefire.one"
    private val preferences by getPreferencesLazy()
    private val extractor by lazy { AnimeFireExtractor(client) }

    @Volatile private var genres = emptyList<String>()

    private val animePosterCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/").set("Origin", baseUrl)

    override fun popularAnimeRequest(page: Int) = GET("$api/home", headers)
    override fun popularAnimeParse(response: Response): AnimesPage {
        val cards = response.parseAs<AFResponse<Home>>().data.carousels.first { it.key == "most-liked" }.items
        cards.forEach { card -> card.poster?.let { animePosterCache[card.id] = it } }
        return AnimesPage(cards.map(::toAnime), false).also { debug("popular=${cards.size}") }
    }

    override fun latestUpdatesRequest(page: Int) = GET("$api/home", headers)
    override fun latestUpdatesParse(response: Response): AnimesPage = error("Use getLatestUpdates")
    override suspend fun getLatestUpdates(page: Int): AnimesPage = coroutineScope {
        if (page > 1) return@coroutineScope AnimesPage(emptyList(), false)
        val home = client.newCall(latestUpdatesRequest(page)).awaitSuccess().parseAs<AFResponse<Home>>().data

        val homeCanonicalPosters = mutableMapOf<String, String>()
        home.carousels.filterNot { it.key == "new-episodes" }.forEach { carousel ->
            carousel.items.forEach { card ->
                card.poster?.let {
                    homeCanonicalPosters[card.id] = it
                    animePosterCache[card.id] = it
                }
            }
        }

        val rawEpisodes = home.carousels.firstOrNull { it.key == "new-episodes" }?.items ?: emptyList()
        val distinctEpisodes = rawEpisodes.distinctBy { ep ->
            ep.titles["BR"] ?: ep.titles.values.firstOrNull() ?: ep.id
        }

        val gate = Semaphore(4)
        val cards = distinctEpisodes.map { episode ->
            async {
                gate.withPermit {
                    try {
                        val epDetails = client.newCall(GET("$api/episode/${episode.id}", headers)).awaitSuccess()
                            .parseAs<AFResponse<EpisodeDetails>>().data
                        val animeCard = epDetails.anime
                        val animeId = animeCard.id

                        val knownPoster = animePosterCache[animeId] ?: homeCanonicalPosters[animeId]
                        val posterUrl = if (!knownPoster.isNullOrBlank()) {
                            knownPoster
                        } else if (!animeCard.poster.isNullOrBlank() && !isEpisodeFrame(animeCard.poster, episode)) {
                            animeCard.poster
                        } else {
                            try {
                                client.newCall(GET("$api/anime/$animeId", headers)).awaitSuccess()
                                    .parseAs<AFResponse<AnimeDetails>>().data.hero.poster
                            } catch (e: Exception) {
                                null
                            } ?: animeCard.poster ?: episode.poster
                        }
                        if (!posterUrl.isNullOrBlank()) {
                            animePosterCache[animeId] = posterUrl
                        }

                        Card(
                            id = animeId,
                            titles = animeCard.titles.ifEmpty { episode.titles },
                            poster = posterUrl,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        debug("latest episode failed=${e.javaClass.simpleName}")
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull().distinctBy { it.id }

        check(cards.isNotEmpty() || rawEpisodes.isEmpty()) { "Não foi possível carregar os animes dos novos episódios." }
        AnimesPage(cards.map(::toAnime), false).also { debug("latest raw=${rawEpisodes.size} distinctEps=${distinctEpisodes.size} anime=${cards.size}") }
    }

    private fun isEpisodeFrame(url: String, episode: Card): Boolean {
        if (episode.still != null && url == episode.still) return true
        if (episode.thumbnail != null && url == episode.thumbnail) return true
        if (url.contains("/still/", ignoreCase = true) || url.contains("/stills/", ignoreCase = true)) return true
        return false
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
        result.data.forEach { card -> card.poster?.let { animePosterCache[card.id] = it } }
        return AnimesPage(result.data.distinctBy { it.id }.map(::toAnime), result.meta?.let { it.currentPage < it.lastPage } ?: false)
            .also { debug("search=${it.animes.size} next=${it.hasNextPage}") }
    }
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val queryId = animeIdFromQuery(query)
        val id = when {
            query.startsWith(PREFIX_SEARCH) -> query.removePrefix(PREFIX_SEARCH)
            queryId != null -> queryId
            else -> return super.getSearchAnime(page, query, filters)
        }
        require(id.matches(Regex("[A-Za-z0-9_-]+"))) { "Identificador inválido" }
        return AnimesPage(listOf(client.newCall(GET("$api/anime/$id", headers)).awaitSuccess().let(::animeDetailsParse)), false)
    }
    private fun toAnime(card: Card) = SAnime.create().apply {
        url = "/anime/${card.id}"
        title = card.titles["BR"] ?: card.titles.values.firstOrNull() ?: card.id
        thumbnail_url = card.poster
    }
    private fun animeId(anime: SAnime) = animeIdFromUrl(anime.url.toHttpUrlOrNull() ?: "$baseUrl${anime.url}".toHttpUrl())
    private fun animeIdFromUrl(url: okhttp3.HttpUrl): String {
        require(url.host in supportedHosts) { "Endereço de anime não suportado" }
        val path = url.pathSegments.filter(String::isNotEmpty)
        require(path.size == 2 && path.first() == "anime" && path.last().matches(idPattern)) { "Identificador inválido" }
        return path.last()
    }
    private fun animeIdFromQuery(query: String): String? {
        val url = query.toHttpUrlOrNull() ?: return null
        val path = url.pathSegments.filter(String::isNotEmpty)
        return if (url.host in supportedHosts && path.size == 2 && path.first() == "anime" && path.last().matches(idPattern)) animeIdFromUrl(url) else null
    }
    override fun animeDetailsRequest(anime: SAnime) = GET("$api/anime/${animeId(anime)}", headers)
    override fun animeDetailsParse(response: Response): SAnime {
        val details = response.parseAs<AFResponse<AnimeDetails>>().data
        val hero = details.hero
        hero.poster?.let { animePosterCache[hero.id] = it }
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
    override fun seasonListParse(response: Response): List<SAnime> = emptyList()
    override fun hosterListRequest(episode: SEpisode) = GET("$api/episode/${episodeId(episode)}", headers)
    override fun hosterListParse(response: Response): List<Hoster> {
        val details = response.parseAs<AFResponse<EpisodeDetails>>().data
        val activeStreams = details.streams.filterNot { it.offline }
        if (activeStreams.isEmpty()) {
            val msg = "Anime Fire: este episódio não possui servidores de vídeo disponíveis no momento."
            notifyUser(msg)
            throw Exception(msg)
        }
        return activeStreams.mapIndexed { index, stream ->
            val audioLabel = when (stream.audio?.lowercase()) {
                "dublado" -> "Dublado"
                "legendado" -> "Legendado"
                null -> "Padrão"
                else -> stream.audio.replaceFirstChar { it.uppercase() }
            } + if (stream.machineTranslated) " (MTL)" else ""
            Hoster(
                hosterName = "Anime Fire ($audioLabel)",
                hosterUrl = "$api/episode/${details.id}",
                internalData = "${details.id}|$index|${stream.audio.orEmpty()}",
            )
        }
    }

    override fun videoListRequest(hoster: Hoster): okhttp3.Request {
        val internalParts = hoster.internalData.split('|')
        val episodeId = internalParts.getOrNull(0)?.takeIf { it.isNotBlank() }
            ?: hoster.hosterUrl.substringAfterLast('/')
        return GET("$api/episode/$episodeId", headers)
    }

    override fun videoListParse(response: Response, hoster: Hoster): List<Video> {
        val details = response.parseAs<AFResponse<EpisodeDetails>>().data
        val internalParts = hoster.internalData.split('|')
        val streamIndex = internalParts.getOrNull(1)?.toIntOrNull()
        val expectedAudio = internalParts.getOrNull(2)

        val activeStreams = details.streams.filterNot { it.offline }
        if (activeStreams.isEmpty()) {
            val msg = "Anime Fire: este episódio não possui vídeos ativos no servidor."
            notifyUser(msg)
            throw Exception(msg)
        }

        val targetStream = (if (streamIndex != null) activeStreams.getOrNull(streamIndex) else null)
            ?: activeStreams.firstOrNull { it.audio.equals(expectedAudio, ignoreCase = true) }
            ?: activeStreams.first()

        val videos = extractor.videos(listOf(targetStream), headers).sort()
        if (videos.isEmpty()) {
            val audioLabel = when (targetStream.audio?.lowercase()) {
                "dublado" -> "Dublado"
                "legendado" -> "Legendado"
                else -> targetStream.audio.orEmpty()
            }
            val targetName = if (audioLabel.isNotBlank()) " ($audioLabel)" else ""
            val message = "Anime Fire: o vídeo deste episódio$targetName foi removido ou está indisponível no servidor (404 Not Found)."
            notifyUser(message)
            throw Exception(message)
        }
        return videos
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val response = client.newCall(videoListRequest(hoster)).awaitSuccess()
        return videoListParse(response, hoster)
    }

    private fun notifyUser(message: String) {
        try {
            val app = Injekt.get<Application>()
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(app, message, Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {
            // Ignore in environments where Injekt/Application is not initialized
        }
    }

    private fun List<Video>.sort(): List<Video> {
        val preferred = preferences.getString("quality_selection", "best")
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains("H.264", ignoreCase = true) }
                .thenByDescending { preferred != "best" && resolution(it.videoTitle) == resolution(preferred.orEmpty()) }
                .thenByDescending { resolution(it.videoTitle) },
        )
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
        private val supportedHosts = setOf("animefire.one", "animefire.io", "animefire.plus")
        private val idPattern = Regex("[A-Za-z0-9_-]+")
        fun debug(message: String) {
            if (BuildConfig.DEBUG) Log.d("ANIMEFIRE_DEBUG", message)
        }
    }

    private fun episodeId(episode: SEpisode): String {
        val url = episode.url.toHttpUrlOrNull() ?: "$baseUrl${episode.url}".toHttpUrl()
        require(url.host in supportedHosts) { "Endereço de episódio não suportado" }
        val path = url.pathSegments.filter(String::isNotEmpty)
        require(path.size == 2 && path.first() == "episode" && path.last().matches(idPattern)) { "Identificador inválido" }
        return path.last()
    }
}
