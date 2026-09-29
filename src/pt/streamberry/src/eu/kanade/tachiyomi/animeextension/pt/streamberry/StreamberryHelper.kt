package eu.kanade.tachiyomi.animeextension.pt.streamberry

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Locale

object StreamberryHelper {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun fixImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return if (url.startsWith("http")) url else "https://image.tmdb.org/t/p/w500$url"
    }

    fun animeFromCatalogItem(item: CatalogItemDto): SAnime? {
        val slug = item.slug?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val isMovie = item.mediaType.equals("movie", ignoreCase = true) ||
            item.type.equals("movie", ignoreCase = true) ||
            (item.firstAirDate == null && item.releaseDate != null)
        val path = if (isMovie) "/filmes/$slug" else "/series/$slug"
        return SAnime.create().apply {
            title = item.title?.trim().orEmpty().ifBlank { slug }
            thumbnail_url = fixImageUrl(item.posterUrl?.takeIf { it.isNotBlank() } ?: item.backdropUrl)
            this.url = path
        }
    }

    fun parseCatalogPage(body: String): Pair<List<SAnime>, Boolean> {
        val data = json.decodeFromString<CatalogResponseDto>(body)
        val animes = data.items.mapNotNull(::animeFromCatalogItem).distinctBy { it.url }
        val hasNextPage = data.pagination != null && data.pagination.page < data.pagination.totalPages
        return Pair(animes, hasNextPage)
    }

    fun parseSectionPage(body: String): Pair<List<SAnime>, Boolean> {
        val data = json.decodeFromString<SectionResponseDto>(body)
        val animes = data.items.mapNotNull(::animeFromCatalogItem).distinctBy { it.url }
        val hasNextPage = data.pagination != null && data.pagination.page < data.pagination.totalPages
        return Pair(animes, hasNextPage)
    }

    fun parseMovieDetails(body: String): SAnime {
        val movie = json.decodeFromString<MovieDetailDto>(body)
        return SAnime.create().apply {
            title = movie.title?.trim().orEmpty()
            thumbnail_url = fixImageUrl(movie.posterUrl?.takeIf { it.isNotBlank() } ?: movie.backdropUrl)
            genre = movie.genres?.mapNotNull { it.name }?.joinToString(", ")
            status = SAnime.COMPLETED
            author = movie.director
            description = buildString {
                movie.synopsis?.takeIf { it.isNotBlank() }?.let { append(it.trim()) }
                movie.audioTags?.takeIf { it.isNotEmpty() }?.let {
                    if (isNotEmpty()) append("\n\n")
                    append("Áudio: ").append(it.joinToString(", "))
                }
                movie.quality?.takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append("\n")
                    append("Qualidade: ").append(it)
                }
                if ((movie.ratingTmdb ?: 0.0) > 0.0) {
                    if (isNotEmpty()) append("\n")
                    append("Nota TMDB: ").append(movie.ratingTmdb)
                }
            }
        }
    }

    fun parseSeriesDetails(body: String): SAnime {
        val series = json.decodeFromString<SeriesDetailDto>(body)
        return SAnime.create().apply {
            title = series.title?.trim().orEmpty()
            thumbnail_url = fixImageUrl(series.posterUrl?.takeIf { it.isNotBlank() } ?: series.backdropUrl)
            genre = series.genres?.mapNotNull { it.name }?.joinToString(", ")
            status = if (series.showStatus.equals("ended", ignoreCase = true)) SAnime.COMPLETED else SAnime.ONGOING
            author = series.creator
            description = buildString {
                series.synopsis?.takeIf { it.isNotBlank() }?.let { append(it.trim()) }
                series.audioTags?.takeIf { it.isNotEmpty() }?.let {
                    if (isNotEmpty()) append("\n\n")
                    append("Áudio: ").append(it.joinToString(", "))
                }
                if ((series.ratingTmdb ?: 0.0) > 0.0) {
                    if (isNotEmpty()) append("\n")
                    append("Nota TMDB: ").append(series.ratingTmdb)
                }
            }
        }
    }

    fun parseMovieEpisodes(body: String, url: String): List<SEpisode> {
        val movie = json.decodeFromString<MovieDetailDto>(body)
        val slug = movie.slug ?: url.substringAfterLast("/")
        return listOf(
            SEpisode.create().apply {
                name = "Filme"
                episode_number = 1F
                this.url = "/filmes/$slug"
            },
        )
    }

    fun parseSeriesEpisodes(body: String, url: String): List<SEpisode> {
        val series = json.decodeFromString<SeriesDetailDto>(body)
        val seriesSlug = series.slug ?: url.substringAfterLast("/")
        val episodes = series.seasons.orEmpty().flatMap { season ->
            val seasonNum = season.seasonNumber
            season.episodes.orEmpty().map { episode ->
                val epNum = episode.episodeNumber
                val epTitle = episode.title?.trim().orEmpty().ifBlank { "Episódio $epNum" }
                SEpisode.create().apply {
                    name = "T$seasonNum E$epNum - $epTitle"
                    episode_number = epNum.toFloat()
                    date_upload = parseDate(episode.airDate ?: episode.createdAt)
                    this.url = "/episodios/$seriesSlug-${seasonNum}x$epNum"
                }
            }
        }
        return episodes.reversed()
    }

    fun serverPriority(server: String): Int = when {
        server.contains("Byse", ignoreCase = true) -> 0
        server.contains("Vidara", ignoreCase = true) -> 1
        server.contains("EU PLAYER", ignoreCase = true) -> 2
        server.contains("Lulu", ignoreCase = true) -> 3
        server.contains("Loadvid", ignoreCase = true) -> 4
        else -> 5
    }

    fun solvePow(nonce: String, difficulty: Int): String {
        if (difficulty <= 0) return "0"
        var solution = 0
        while (true) {
            if (leadingZeroBits(powDigest("$nonce:$solution")) >= difficulty) return solution.toString()
            solution++
        }
    }

    fun powDigest(value: String): IntArray {
        val state = intArrayOf(1779033703, 3144134277L.toInt(), 1013904242, 2773480762L.toInt())
        value.toByteArray().forEach { byte ->
            state[0] += byte.toInt() and 0xff
            state[0] = Integer.rotateLeft(state[0], 7)
            powQuarterRound(state)
        }
        repeat(8) { powQuarterRound(state) }
        val memory = IntArray(512)
        memory.indices.forEach { index ->
            powQuarterRound(state)
            memory[index] = state[0] xor state[2]
        }
        repeat(2) {
            memory.indices.forEach { index ->
                val selected = memory[index] and 511
                var mixed = memory[index] + memory[selected]
                mixed = Integer.rotateLeft(mixed, 13)
                mixed = mixed xor (memory[(index + 1) and 511] * 2654435761L.toInt())
                memory[index] = mixed
                state[0] = state[0] xor mixed
                powQuarterRound(state)
            }
        }
        return IntArray(8) { block ->
            powQuarterRound(state)
            var mixed = state[0]
            repeat(64) { index ->
                val item = memory[block * 64 + index]
                mixed += item
                mixed = Integer.rotateLeft(mixed, 5)
                mixed = mixed xor (item * 2246822519L.toInt())
            }
            mixed xor state[2]
        }
    }

    fun powQuarterRound(state: IntArray) {
        state[0] += state[1]
        state[3] = Integer.rotateLeft(state[3] xor state[0], 16)
        state[2] += state[3]
        state[1] = Integer.rotateLeft(state[1] xor state[2], 12)
        state[0] += state[1]
        state[3] = Integer.rotateLeft(state[3] xor state[0], 8)
        state[2] += state[3]
        state[1] = Integer.rotateLeft(state[1] xor state[2], 7)
    }

    fun leadingZeroBits(values: IntArray): Int {
        var total = 0
        values.forEach { value ->
            if (value == 0) total += 32 else return total + Integer.numberOfLeadingZeros(value)
        }
        return total
    }

    fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return runCatching {
            if (dateStr.contains(" ")) {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(dateStr)?.time ?: 0L
            } else {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr)?.time ?: 0L
            }
        }.getOrDefault(0L)
    }

    // =============================== DTOs =================================

    @Serializable
    data class SectionResponseDto(
        val items: List<CatalogItemDto> = emptyList(),
        val pagination: PaginationDto? = null,
    )

    @Serializable
    data class CatalogResponseDto(
        val items: List<CatalogItemDto> = emptyList(),
        val pagination: PaginationDto? = null,
    )

    @Serializable
    data class CatalogItemDto(
        val id: Long? = null,
        val title: String? = null,
        val slug: String? = null,
        val synopsis: String? = null,
        val posterUrl: String? = null,
        val backdropUrl: String? = null,
        val mediaType: String? = null,
        val type: String? = null,
        val category: String? = null,
        val audioTags: List<String>? = null,
        val ratingTmdb: Double? = null,
        val firstAirDate: String? = null,
        val releaseDate: String? = null,
    )

    @Serializable
    data class PaginationDto(
        val page: Int = 1,
        val limit: Int = 24,
        val total: Int = 0,
        val totalPages: Int = 1,
    )

    @Serializable
    data class MovieDetailDto(
        val id: Long? = null,
        val title: String? = null,
        val originalTitle: String? = null,
        val slug: String? = null,
        val synopsis: String? = null,
        val posterUrl: String? = null,
        val backdropUrl: String? = null,
        val releaseDate: String? = null,
        val ratingTmdb: Double? = null,
        val ratingImdb: Double? = null,
        val quality: String? = null,
        val director: String? = null,
        val audioTags: List<String>? = null,
        val genres: List<GenreItemDto>? = null,
        val players: List<PlayerDto>? = null,
    )

    @Serializable
    data class SeriesDetailDto(
        val id: Long? = null,
        val title: String? = null,
        val originalTitle: String? = null,
        val slug: String? = null,
        val synopsis: String? = null,
        val posterUrl: String? = null,
        val backdropUrl: String? = null,
        val firstAirDate: String? = null,
        val showStatus: String? = null,
        val ratingTmdb: Double? = null,
        val ratingImdb: Double? = null,
        val creator: String? = null,
        val audioTags: List<String>? = null,
        val genres: List<GenreItemDto>? = null,
        val seasons: List<SeasonDto>? = null,
        val players: List<PlayerDto>? = null,
    )

    @Serializable
    data class SeasonDto(
        val id: Long? = null,
        val seasonNumber: Int = 1,
        val name: String? = null,
        val episodes: List<EpisodeDto>? = null,
    )

    @Serializable
    data class EpisodeDto(
        val id: Long? = null,
        val episodeNumber: Int = 1,
        val title: String? = null,
        val overview: String? = null,
        val thumbnailUrl: String? = null,
        val airDate: String? = null,
        val createdAt: String? = null,
        val players: List<PlayerDto>? = null,
    )

    @Serializable
    data class EpisodeDetailDto(
        val show: SeriesDetailDto? = null,
        val episode: EpisodeDto? = null,
        val players: List<PlayerDto>? = null,
    )

    @Serializable
    data class PlayerDto(
        val id: Long? = null,
        val serverName: String? = null,
        val embedUrl: String? = null,
        val audioType: String? = null,
        val quality: String? = null,
        val orderPriority: Int? = null,
        val isActive: Boolean = true,
    )

    @Serializable
    data class GenreItemDto(
        val id: Long? = null,
        val name: String? = null,
        val slug: String? = null,
    )
}
