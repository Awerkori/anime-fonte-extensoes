package eu.kanade.tachiyomi.animeextension.pt.animesonlineblue

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class AnimesOnlineBlue :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AnimesOnline Blue"

    override val baseUrl = "https://animesonline.blue"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client

    private val preferences by getPreferencesLazy()

    private val json: Json by injectLazy()

    private val bloggerExtractor by lazy {
        BloggerExtractor(client)
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    private fun rscHeaders(): Headers = headersBuilder()
        .add("RSC", "1")
        .build()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = if (page == 1) {
        GET("$baseUrl/?_rsc=1", rscHeaders())
    } else {
        GET("$baseUrl/animes?page=$page&_rsc=1", rscHeaders())
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val body = response.body.string()
        val url = response.request.url.toString()

        if (url.contains("/animes?page=")) {
            return parseCatalogPage(body)
        }

        // On page 1, extract popular items from "buckets" in homepage RSC
        val animeList = mutableListOf<SAnime>()
        val bucketsIdx = body.indexOf("\"buckets\":")
        if (bucketsIdx != -1) {
            val bucketsJson = extractBalancedJson(body, bucketsIdx + 10, '{', '}')
            if (bucketsJson.isNotEmpty()) {
                val buckets = runCatching { json.decodeFromString<BucketsDto>(bucketsJson) }.getOrNull()
                buckets?.day?.forEach { item ->
                    animeList.add(
                        SAnime.create().apply {
                            title = item.title
                            thumbnail_url = item.cover
                            setUrlWithoutDomain("/anime/${item.slug}")
                        },
                    )
                }
            }
        }

        // Fallback to catalog items if buckets are empty
        if (animeList.isEmpty()) {
            return parseCatalogPage(body)
        }

        return AnimesPage(animeList, hasNextPage = true)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = if (page == 1) {
        GET("$baseUrl/?_rsc=1", rscHeaders())
    } else {
        GET("$baseUrl/animes?page=$page&_rsc=1", rscHeaders())
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val body = response.body.string()
        val url = response.request.url.toString()

        if (url.contains("/animes?page=")) {
            return parseCatalogPage(body)
        }

        // Extract anime covers map from the same homepage payload (Animes Adicionados, schedule, buckets)
        val animeMap = mutableMapOf<String, String>()
        val animeCoverRegex = Regex("""\{"id":(\d+),"slug":"([^"]+)","title":"([^"]+)","cover":"([^"]+)"""")
        for (m in animeCoverRegex.findAll(body)) {
            val id = m.groupValues[1]
            val slug = m.groupValues[2]
            val cover = m.groupValues[4].replace("\\/", "/")
            animeMap[id] = cover
            animeMap[slug] = cover
        }

        // Extract latest episodes on page 1
        val animeList = mutableListOf<SAnime>()
        val episodesIdx = body.indexOf("\"episodes\":[")
        if (episodesIdx != -1) {
            val episodesJson = extractBalancedJson(body, episodesIdx + 11, '[', ']')
            if (episodesJson.isNotEmpty()) {
                val latestList = runCatching { json.decodeFromString<List<LatestEpisodeDto>>(episodesJson) }.getOrNull()
                val seenSlugs = mutableSetOf<String>()
                latestList?.forEach { ep ->
                    if (seenSlugs.add(ep.animeSlug)) {
                        val animeIdStr = ep.animeId?.toString()
                        val animeCover = animeIdStr?.let { animeMap[it] } ?: animeMap[ep.animeSlug]
                        animeList.add(
                            SAnime.create().apply {
                                title = ep.animeTitle
                                thumbnail_url = animeCover ?: ep.cover
                                setUrlWithoutDomain("/anime/${ep.animeSlug}")
                            },
                        )
                    }
                }
            }
        }

        if (animeList.isEmpty()) {
            return parseCatalogPage(body)
        }

        return AnimesPage(animeList, hasNextPage = true)
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            return GET("$baseUrl/api/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}", headers)
        }

        var path = "/animes"
        for (filter in filters) {
            when (filter) {
                is TypeFilter -> {
                    val value = filter.selectedValue()
                    if (value.isNotEmpty()) path = "/$value"
                }
                is GenreFilter -> {
                    val value = filter.selectedValue()
                    if (value.isNotEmpty()) path = "/genero/$value"
                }
                is LetterFilter -> {
                    val value = filter.selectedValue()
                    if (value.isNotEmpty()) path = "/letra/$value"
                }
                else -> {}
            }
        }

        val pageParam = if (page > 1) {
            if (path.contains("?")) "&page=$page" else "?page=$page"
        } else {
            ""
        }
        val delimiter = if (path.contains("?") || pageParam.contains("?")) "&" else "?"

        return GET("$baseUrl$path$pageParam${delimiter}_rsc=1", rscHeaders())
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val body = response.body.string()
        val url = response.request.url.toString()

        if (url.contains("/api/search")) {
            val searchDto = runCatching { json.decodeFromString<SearchResponseDto>(body) }.getOrNull()
            val animeList = searchDto?.results?.map { item ->
                SAnime.create().apply {
                    title = item.title
                    thumbnail_url = item.cover
                    setUrlWithoutDomain("/anime/${item.slug}")
                }
            } ?: emptyList()

            return AnimesPage(animeList, hasNextPage = false)
        }

        return parseCatalogPage(body)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val path = anime.url
        val delimiter = if (path.contains("?")) "&" else "?"
        return GET("$baseUrl$path${delimiter}_rsc=1", rscHeaders())
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val body = response.body.string()
        return SAnime.create().apply {
            val animeIdx = body.indexOf("{\"anime\":{")
            if (animeIdx != -1) {
                val animeJson = extractBalancedJson(body, animeIdx + 9, '{', '}')
                val animeDto = runCatching { json.decodeFromString<AnimeDetailDto>(animeJson) }.getOrNull()
                if (animeDto != null) {
                    title = animeDto.title
                    thumbnail_url = animeDto.cover
                    description = animeDto.synopsis
                    genre = animeDto.genres.mapNotNull { it.name }.joinToString(", ")
                    status = SAnime.UNKNOWN
                    return@apply
                }
            }

            // Fallback: regex extraction
            title = REGEX_TITLE.find(body)?.groupValues?.get(1) ?: ""
            thumbnail_url = REGEX_COVER.find(body)?.groupValues?.get(1)
            description = REGEX_SYNOPSIS.find(body)?.groupValues?.get(1)
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val path = anime.url
        val delimiter = if (path.contains("?")) "&" else "?"
        return GET("$baseUrl$path${delimiter}_rsc=1", rscHeaders())
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val body = response.body.string()
        val episodes = mutableListOf<SEpisode>()

        val tempIdx = body.indexOf("\"temporadas\":[")
        if (tempIdx != -1) {
            val tempJson = extractBalancedJson(body, tempIdx + 13, '[', ']')
            if (tempJson.isNotEmpty()) {
                val temporadas = runCatching { json.decodeFromString<List<TemporadaDto>>(tempJson) }.getOrNull()
                temporadas?.forEach { season ->
                    val seasonName = season.nome?.takeIf { it.isNotBlank() }
                        ?: "Temporada ${season.numero}"

                    season.eps.forEach { epItem ->
                        val epNum = epItem.num

                        // Add Subbed episode if present
                        epItem.leg?.let { leg ->
                            episodes.add(
                                SEpisode.create().apply {
                                    name = "$seasonName - Ep. $epNum (Legendado)"
                                    episode_number = epNum.toFloat()
                                    url = "/api/episodio/${leg.id}"
                                },
                            )
                        }

                        // Add Dubbed episode if present
                        epItem.dub?.let { dub ->
                            episodes.add(
                                SEpisode.create().apply {
                                    name = "$seasonName - Ep. $epNum (Dublado)"
                                    episode_number = epNum.toFloat()
                                    url = "/api/episodio/${dub.id}"
                                },
                            )
                        }
                    }
                }
            }
        }

        return episodes.reversed()
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val response = client.newCall(GET("$baseUrl${episode.url}", headers)).awaitSuccess()
        val body = response.body.string()

        val episodeDto = runCatching { json.decodeFromString<EpisodePlayerDto>(body) }.getOrNull()
            ?: return emptyList()

        val videoList = mutableListOf<Video>()
        val playerUrl = episodeDto.playerUrl

        if (!playerUrl.isNullOrBlank()) {
            if (playerUrl.contains("blogger.com")) {
                val bloggerVideos = bloggerExtractor.videosFromUrl(playerUrl, headers)
                videoList.addAll(bloggerVideos)
            } else {
                videoList.add(Video(playerUrl, "Player - ${episodeDto.tipo ?: "Padrão"}", playerUrl, headers))
            }
        }

        return sortVideos(videoList)
    }

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filtros são ignorados na busca por texto"),
        TypeFilter(),
        GenreFilter(),
        LetterFilter(),
    )

    private class TypeFilter :
        AnimeFilter.Select<String>(
            "Tipo / Áudio",
            arrayOf(
                "Todos",
                "Dublados",
                "Legendados",
            ),
        ) {
        fun selectedValue(): String = when (state) {
            1 -> "dublado"
            2 -> "legendado"
            else -> ""
        }
    }

    private class GenreFilter :
        AnimeFilter.Select<String>(
            "Gênero",
            GENRES.map { it.first }.toTypedArray(),
        ) {
        fun selectedValue(): String = GENRES[state].second
    }

    private class LetterFilter :
        AnimeFilter.Select<String>(
            "Letra (A-Z)",
            arrayOf("Todas") + ('a'..'z').map { it.uppercase() }.toTypedArray(),
        ) {
        fun selectedValue(): String = if (state > 0) ('a'..'z').toList()[state - 1].toString() else ""
    }

    // ============================= Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Qualidade padrão"
            entries = arrayOf("720p", "360p", "240p")
            entryValues = arrayOf("720p", "360p", "240p")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================

    private fun parseCatalogPage(body: String): AnimesPage {
        val animeList = mutableListOf<SAnime>()
        val regex = Regex("""\{"id":(\d+),"slug":"([^"]+)","title":"([^"]+)","cover":"([^"]+)"""")
        val matches = regex.findAll(body)
        val seenSlugs = mutableSetOf<String>()

        for (match in matches) {
            val slug = match.groupValues[2]
            val rawTitle = match.groupValues[3]
            val cover = match.groupValues[4].replace("\\/", "/")

            if (seenSlugs.add(slug)) {
                animeList.add(
                    SAnime.create().apply {
                        title = unescapeJson(rawTitle)
                        thumbnail_url = cover
                        setUrlWithoutDomain("/anime/$slug")
                    },
                )
            }
        }

        val hasNextPage = body.contains("page=") || animeList.size >= 20
        return AnimesPage(animeList, hasNextPage = hasNextPage)
    }

    private fun extractBalancedJson(text: String, startIdx: Int, openChar: Char, closeChar: Char): String {
        if (startIdx < 0 || startIdx >= text.length) return ""
        var depth = 0
        var foundStart = false
        var start = -1
        for (i in startIdx until text.length) {
            val c = text[i]
            if (c == openChar) {
                if (!foundStart) {
                    foundStart = true
                    start = i
                }
                depth++
            } else if (c == closeChar && foundStart) {
                depth--
                if (depth == 0) {
                    return text.substring(start, i + 1)
                }
            }
        }
        return ""
    }

    private fun unescapeJson(text: String): String = text
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\/", "/")

    private fun sortVideos(videos: List<Video>): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return videos.sortedWith(
            compareByDescending { it.quality.contains(quality) },
        )
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720p"

        private val REGEX_TITLE = Regex(""""title":"([^"]+)"""")
        private val REGEX_COVER = Regex(""""cover":"([^"]+)"""")
        private val REGEX_SYNOPSIS = Regex(""""synopsis":"([^"]*)"""")

        private val GENRES = arrayOf(
            Pair("Todos", ""),
            Pair("Artes Maciais", "artes-maciais"),
            Pair("Artes Marciais", "artes-marciais"),
            Pair("Aventura", "aventura"),
            Pair("Ação", "acao"),
            Pair("Boys Love", "boys-love"),
            Pair("CGI", "cgi"),
            Pair("Comédia", "comedia"),
            Pair("Crianças", "criancas"),
            Pair("Demencia", "demencia"),
            Pair("Demônio", "demonio"),
            Pair("Demônios", "demonios"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Escolar", "escolar"),
            Pair("Espaço", "espaco"),
            Pair("Esporte", "esporte"),
            Pair("Esportes", "esportes"),
            Pair("Fantasia", "fantasia"),
            Pair("Fantasy", "fantasy"),
            Pair("Ficção Científica", "ficcao-cientifica"),
            Pair("Girls Love", "girls-love"),
            Pair("Gore", "gore"),
            Pair("Gourmet", "gourmet"),
            Pair("Harém", "harem-2"),
            Pair("Harém Reverso", "harem-reverso"),
            Pair("Harém‎", "harem"),
            Pair("Hentai", "hentai"),
            Pair("Histórico", "historico"),
            Pair("Horror", "horror"),
            Pair("Idol", "idol"),
            Pair("Infantil", "infantil"),
            Pair("Isekai", "isekai"),
            Pair("Jogos", "jogos"),
            Pair("Josei", "josei"),
            Pair("Kodomo", "kodomo"),
            Pair("Live Action", "live-action"),
            Pair("Magia", "magia"),
            Pair("Mahou Shoujo", "mahou-shoujo"),
            Pair("Mecha", "mecha"),
            Pair("Militar", "militar"),
            Pair("Mistério", "misterio"),
            Pair("Mitologia", "mitologia"),
            Pair("Mundo Virtual", "mundo-virtual"),
            Pair("Musical", "musical"),
            Pair("Paródia", "parodia"),
            Pair("Policial", "policial"),
            Pair("Psicológico", "psicologico"),
            Pair("Pós-Apocalíptico", "pos-apocaliptico"),
            Pair("Robô", "robo"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Sem categoria", "sem-categoria"),
            Pair("Shoujo", "shoujo"),
            Pair("Shoujo Ai", "shoujo-ai"),
            Pair("Shoujo-ai", "shoujo-ai-2"),
            Pair("Shounen", "shounen"),
            Pair("Shounen-ai", "shounen-ai"),
            Pair("Sitcom", "sitcom"),
            Pair("Slice Of Life", "slice-of-life"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Super Herói", "super-heroi"),
            Pair("Super Poder", "super-poder"),
            Pair("Super Poderes", "super-poderes"),
            Pair("Superpoder", "superpoder"),
            Pair("Superpoderes", "superpoderes"),
            Pair("Suspense", "suspense"),
            Pair("Terror", "terror"),
            Pair("Tokuatsu", "tokuatsu"),
            Pair("Tokusatsu", "tokusatsu"),
            Pair("Tragédia", "tragedia"),
            Pair("Vampiro", "vampiro"),
            Pair("Vampiros", "vampiros"),
            Pair("Vida Cotidiana", "vida-cotidiana"),
            Pair("Vida Escolar", "vida-escolar"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        )
    }
}

// =============================== DTO Models ===============================

@Serializable
data class SearchResponseDto(
    val results: List<SearchAnimeItemDto> = emptyList(),
)

@Serializable
data class SearchAnimeItemDto(
    val slug: String,
    val title: String,
    val cover: String,
    val tipo: String? = null,
    val year: String? = null,
    val episodeCount: Int? = null,
)

@Serializable
data class BucketsDto(
    val day: List<SearchAnimeItemDto> = emptyList(),
)

@Serializable
data class LatestEpisodeDto(
    val episodeId: Long,
    val num: Int,
    val tipo: String? = null,
    val tempo: String? = null,
    val cover: String? = null,
    val animeId: Long? = null,
    val animeSlug: String,
    val animeTitle: String,
)

@Serializable
data class AnimeDetailDto(
    val id: Long? = null,
    val slug: String? = null,
    val title: String,
    val cover: String? = null,
    val tipo: String? = null,
    val year: String? = null,
    val synopsis: String? = null,
    val genres: List<GenreItemDto> = emptyList(),
)

@Serializable
data class GenreItemDto(
    val id: Long? = null,
    val name: String? = null,
    val slug: String? = null,
)

@Serializable
data class TemporadaDto(
    val numero: Int? = null,
    val nome: String? = null,
    val eps: List<EpItemDto> = emptyList(),
)

@Serializable
data class EpItemDto(
    val num: Int,
    val leg: EpLangDto? = null,
    val dub: EpLangDto? = null,
)

@Serializable
data class EpLangDto(
    val id: Long,
    val tempo: String? = null,
    val capa: String? = null,
)

@Serializable
data class EpisodePlayerDto(
    val id: Long,
    val num: Int? = null,
    val tipo: String? = null,
    val tempo: String? = null,
    val capa: String? = null,
    val title: String? = null,
    val playerUrl: String? = null,
    val embed: String? = null,
)
