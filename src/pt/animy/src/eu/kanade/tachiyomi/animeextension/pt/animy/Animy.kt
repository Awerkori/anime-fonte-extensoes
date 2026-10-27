package eu.kanade.tachiyomi.animeextension.pt.animy

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

open class Animy :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "Animy"
    override val baseUrl = "https://animy.net"
    override val lang = "pt-BR"
    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/animes/inicio/")
        .set("Origin", baseUrl)
        .set("Accept", "application/json")

    private val preferences by getPreferencesLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(buildPopularUrl(baseUrl, page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val dto = response.parseAs<AnimesResponseDto>()
        val offset = response.request.url.queryParameter("offset")?.toIntOrNull() ?: 0
        val animes = dto.items.map { it.toSAnime() }
        val hasNextPage = (offset + dto.items.size) < dto.total
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(buildLatestUrl(baseUrl, page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        var sort = "popular"
        var kind = ""
        var audio = ""
        var status = ""
        var season = ""
        var genre = ""

        for (filter in filters) {
            when (filter) {
                is SortFilter -> if (filter.selected.isNotBlank()) sort = filter.selected
                is KindFilter -> if (filter.selected.isNotBlank()) kind = filter.selected
                is AudioFilter -> if (filter.selected.isNotBlank()) audio = filter.selected
                is StatusFilter -> if (filter.selected.isNotBlank()) status = filter.selected
                is SeasonFilter -> if (filter.selected.isNotBlank()) season = filter.selected
                is GenreFilter -> if (filter.selected.isNotBlank()) genre = filter.selected
                else -> {}
            }
        }

        return GET(buildSearchUrl(baseUrl, page, query, sort, kind, audio, status, season, genre), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Details ==============================

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl${anime.url}"

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + buildAnimeDetailsEndpoint(anime.url), headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val dto = response.parseAs<AnimeDetailsDto>()
        return SAnime.create().apply {
            val identifier = dto.slug?.ifBlank { dto.id?.toString() } ?: dto.id?.toString() ?: ""
            url = "/anime/$identifier/"
            title = dto.t
            thumbnail_url = dto.cover
            status = when (dto.status?.lowercase()) {
                "airing" -> SAnime.ONGOING
                "completed" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            genre = dto.g?.joinToString(", ")
            description = formatDescription(
                desc = dto.desc,
                nativeTitle = dto.nativeTitle,
                score = dto.score,
                year = dto.y,
                kind = dto.kind,
                audio = dto.audio,
                age = dto.age,
            )
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val dto = response.parseAs<AnimeDetailsDto>()
        val animeId = dto.id ?: return emptyList()

        return dto.episodes.orEmpty().map { ep ->
            SEpisode.create().apply {
                val s = ep.season
                val epNum = ep.number
                url = buildEpisodeStreamEndpoint(animeId, s, epNum)
                name = formatEpisodeName(s, epNum, ep.numberLabel, ep.title)
                episode_number = epNum
                date_upload = ep.date?.let { parseDate(it) } ?: 0L
            }
        }.reversed()
    }

    // =============================== Videos ===============================

    override fun videoListRequest(episode: SEpisode): Request {
        val endpoint = if (episode.url.startsWith("/api/v1")) episode.url else "/api/v1${episode.url}"
        return GET("$baseUrl$endpoint", headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val dto = response.parseAs<EpisodeDetailsDto>()
        val videoList = mutableListOf<Video>()
        val videoHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .removeAll("Accept")
            .build()

        for (stream in dto.streams) {
            val audioLabel = when (stream.audio?.lowercase()) {
                "dublado" -> "Dublado"
                "legendado" -> "Legendado"
                else -> stream.audio.orEmpty().replaceFirstChar { it.uppercase() }
            }
            val prefix = if (audioLabel.isNotBlank()) "Animy ($audioLabel)" else "Animy"

            if (stream.kind == "hls" || stream.url.contains(".m3u8")) {
                runCatching {
                    val hlsVideos = playlistUtils.extractFromHls(
                        playlistUrl = stream.url,
                        referer = "$baseUrl/",
                        videoHeadersGen = { _, _, _ -> videoHeaders },
                        videoNameGen = { quality -> "$prefix - $quality" },
                    )
                    videoList.addAll(hlsVideos)
                }.onFailure {
                    videoList.add(Video(stream.url, "$prefix - HLS", stream.url, videoHeaders))
                }
            } else {
                val quality = if (stream.url.contains("720p")) "720p" else "Vídeo"
                videoList.add(Video(stream.url, "$prefix ($quality)", stream.url, videoHeaders))
            }
        }
        return videoList
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val audio = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT)!!

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(audio, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(quality) },
        )
    }

    // ============================== Filters ===============================

    override fun getFilterList() = AnimeFilterList(
        SortFilter(),
        KindFilter(),
        AudioFilter(),
        StatusFilter(),
        SeasonFilter(),
        GenreFilter(),
    )

    // ============================ Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Qualidade preferida"
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_AUDIO_KEY
            title = "Áudio preferido"
            entries = PREF_AUDIO_ENTRIES
            entryValues = PREF_AUDIO_VALUES
            setDefaultValue(PREF_AUDIO_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        const val PAGE_LIMIT = 24
        const val DEFAULT_BASE_URL = "https://animy.net"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = arrayOf("1080p", "720p", "480p", "360p")

        private const val PREF_AUDIO_KEY = "preferred_audio"
        private const val PREF_AUDIO_DEFAULT = "Legendado"
        private val PREF_AUDIO_ENTRIES = arrayOf("Legendado", "Dublado")
        private val PREF_AUDIO_VALUES = arrayOf("Legendado", "Dublado")

        fun buildPopularUrl(baseUrl: String, page: Int): String {
            val offset = (page - 1) * PAGE_LIMIT
            return "$baseUrl/api/v1/animes".toHttpUrl().newBuilder()
                .addQueryParameter("sort", "popular")
                .addQueryParameter("limit", PAGE_LIMIT.toString())
                .addQueryParameter("offset", offset.toString())
                .build()
                .toString()
        }

        fun buildLatestUrl(baseUrl: String, page: Int): String {
            val offset = (page - 1) * PAGE_LIMIT
            return "$baseUrl/api/v1/animes".toHttpUrl().newBuilder()
                .addQueryParameter("sort", "newest")
                .addQueryParameter("limit", PAGE_LIMIT.toString())
                .addQueryParameter("offset", offset.toString())
                .build()
                .toString()
        }

        fun buildSearchUrl(
            baseUrl: String,
            page: Int,
            query: String,
            sort: String = "popular",
            kind: String = "",
            audio: String = "",
            status: String = "",
            season: String = "",
            genre: String = "",
        ): String {
            val offset = (page - 1) * PAGE_LIMIT
            val url = "$baseUrl/api/v1/animes".toHttpUrl().newBuilder()
                .addQueryParameter("limit", PAGE_LIMIT.toString())
                .addQueryParameter("offset", offset.toString())

            if (query.isNotBlank()) {
                url.addQueryParameter("search", query.trim())
            }
            if (sort.isNotBlank()) {
                url.addQueryParameter("sort", sort)
            } else {
                url.addQueryParameter("sort", "popular")
            }
            if (kind.isNotBlank()) url.addQueryParameter("kind", kind)
            if (audio.isNotBlank()) url.addQueryParameter("audio", audio)
            if (status.isNotBlank()) url.addQueryParameter("status", status)
            if (season.isNotBlank()) url.addQueryParameter("season", season)
            if (genre.isNotBlank()) url.addQueryParameter("genre", genre)

            return url.build().toString()
        }

        fun buildAnimeDetailsEndpoint(animeUrl: String): String {
            val identifier = animeUrl.trim('/').removePrefix("anime/").removeSuffix("/")
            return if (identifier.all { it.isDigit() }) {
                "/api/v1/animes/$identifier"
            } else {
                "/api/v1/animes/slug/$identifier"
            }
        }

        fun buildEpisodeStreamEndpoint(animeId: Long, season: Int, number: Float): String {
            val epNumStr = if (number % 1f == 0f) number.toInt().toString() else number.toString()
            return "/animes/$animeId/episodes/$season/$epNumStr"
        }

        fun formatEpisodeName(season: Int, number: Float, numberLabel: String?, title: String?): String {
            val epNumStr = if (number % 1f == 0f) number.toInt().toString() else number.toString()
            val sPrefix = if (season > 1) "T$season " else ""
            val epLabel = numberLabel?.ifBlank { null } ?: "Episódio $epNumStr"
            val epTitle = title?.takeIf { it.isNotBlank() && it != epLabel }
            return if (epTitle != null) "$sPrefix$epLabel - $epTitle" else "$sPrefix$epLabel"
        }

        fun formatDescription(
            desc: String?,
            nativeTitle: String?,
            score: Double?,
            year: Int?,
            kind: String?,
            audio: String?,
            age: Int?,
        ): String = buildString {
            desc?.takeIf { it.isNotBlank() }?.let { append(it).append("\n\n") }
            nativeTitle?.takeIf { it.isNotBlank() }?.let { append("Título original: ").append(it).append("\n") }
            score?.let { append("Nota: ").append(it).append(" / 10\n") }
            year?.let { append("Ano: ").append(it).append("\n") }
            kind?.let { append("Tipo: ").append(it.uppercase()).append("\n") }
            audio?.let { append("Áudio: ").append(it.replaceFirstChar { c -> c.uppercase() }).append("\n") }
            age?.takeIf { it > 0 }?.let { append("Classificação: +").append(it).append("\n") }
        }.trim()

        fun parseDate(dateStr: String): Long = runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(dateStr.substringBefore("T"))?.time ?: 0L
        }.getOrDefault(0L)

        fun AnimeItemDto.toSAnime(): SAnime = SAnime.create().apply {
            val identifier = slug?.ifBlank { id?.toString() } ?: id?.toString() ?: ""
            url = "/anime/$identifier/"
            title = t
            thumbnail_url = cover
            status = when (this@toSAnime.status?.lowercase()) {
                "airing" -> SAnime.ONGOING
                "completed" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            genre = g?.joinToString(", ")
            description = formatDescription(
                desc = desc,
                nativeTitle = null,
                score = score,
                year = y,
                kind = kind,
                audio = audio,
                age = age,
            )
        }
    }
}
