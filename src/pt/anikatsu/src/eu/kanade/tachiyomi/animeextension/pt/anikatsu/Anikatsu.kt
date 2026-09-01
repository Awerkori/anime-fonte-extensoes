package eu.kanade.tachiyomi.animeextension.pt.anikatsu

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animeextension.pt.anikatsu.extractors.ByseExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.bodyString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class Anikatsu :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "Anikatsu"

    override val baseUrl = "https://anikatsu.top"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val byseExtractor by lazy { ByseExtractor(client, headers, baseUrl) }

    private var nonce: String? = null
    private val animeMap = mutableMapOf<String, AnimeMeta>()
    private var isMapLoaded = false

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page > 1) "$baseUrl/anime/page/$page/" else "$baseUrl/anime/"
        return GET(url, headers)
    }

    override fun popularAnimeSelector(): String = "div#archive-content article.item, div.items article.item.tvshows, div.items article.item.movies, div.items article.item"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val link = element.selectFirst("a[href*='/anime/'], a[href*='/filmes/'], a[href]")!!
        anime.setUrlWithoutDomain(link.attr("href"))
        val img = element.selectFirst("div.poster img, img")
        anime.title = img?.attr("alt")?.ifEmpty { element.selectFirst("div.data h3, h3")?.text() }?.trim().orEmpty()
        anime.thumbnail_url = img?.attr("abs:src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() }
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination a.arrow_pag i.fa-caret-right, div.resppages a span.fa-chevron-right, a.next.page-numbers"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page > 1) "$baseUrl/episodio/page/$page/" else "$baseUrl/episodio/"
        return GET(url, headers)
    }

    override fun latestUpdatesSelector(): String = "div.items article.item.episodes, div.items article.item.se.episodes, article.item.episodes"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val epLink = element.selectFirst("a[href*='/episodio/'], a[href]")?.attr("href").orEmpty()
        val epSlug = epLink.removeSuffix("/").substringAfterLast("/")

        val serieName = element.selectFirst("span.serie")?.text()?.trim()
            ?: element.selectFirst("div.data h3")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.trim().orEmpty()

        val meta = findAnimeMeta(serieName, epSlug)

        if (meta != null) {
            anime.setUrlWithoutDomain(meta.url)
            anime.title = meta.title
            anime.thumbnail_url = meta.poster
        } else {
            val animeSlug = epSlug
                .replace(Regex("-s\\d+-episodio-\\d+.*$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("-episodio-\\d+.*$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("-\\d+x\\d+.*$", RegexOption.IGNORE_CASE), "")

            anime.url = "/anime/$animeSlug/"
            anime.title = cleanAnimeTitle(serieName)
            val img = element.selectFirst("div.poster img, img")
            anime.thumbnail_url = img?.attr("abs:src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() }
        }

        return anime
    }

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        ensureAnimeMapLoaded(document)

        val elements = document.select(latestUpdatesSelector())
        val animeList = mutableListOf<SAnime>()
        val seenUrls = mutableSetOf<String>()

        for (element in elements) {
            val anime = latestUpdatesFromElement(element)
            if (anime.url.isNotBlank() && seenUrls.add(anime.url)) {
                animeList.add(anime)
            }
        }

        val hasNextPage = document.selectFirst(latestUpdatesNextPageSelector()) != null
        return AnimesPage(animeList, hasNextPage)
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            if (page > 1) "$baseUrl/page/$page/?s=$query" else "$baseUrl/?s=$query"
        } else {
            val filter = filters.filterIsInstance<GenreFilter>().firstOrNull()
            val genrePath = filter?.selectedValue().orEmpty()
            if (genrePath.isNotBlank()) {
                if (page > 1) "$baseUrl/$genrePath/page/$page/" else "$baseUrl/$genrePath/"
            } else {
                if (page > 1) "$baseUrl/anime/page/$page/" else "$baseUrl/anime/"
            }
        }
        return GET(url, headers)
    }

    override fun searchAnimeSelector(): String = "div.search-page div.result-item article, div#archive-content article.item, div.items article.item"

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val link = element.selectFirst("div.image a, div.poster a, a[href*='/anime/'], a[href*='/filmes/'], a[href]")!!
        anime.setUrlWithoutDomain(link.attr("href"))
        val img = element.selectFirst("img")
        val title = element.selectFirst("div.details div.title a, div.data h3, h3")?.text()?.trim()
            ?: img?.attr("alt")?.trim().orEmpty()
        anime.title = title
        anime.thumbnail_url = img?.attr("abs:src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() }
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealAnimeDoc(document)
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(doc.location())

        val sheader = doc.selectFirst("div.sheader")
        val title = sheader?.selectFirst("div.data h1")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim().orEmpty()
        anime.title = title

        val posterImg = sheader?.selectFirst("div.poster img")
            ?: doc.selectFirst("div.poster img, img[itemprop='image']")
        anime.thumbnail_url = posterImg?.attr("abs:src")?.takeIf { it.isNotBlank() }
            ?: posterImg?.attr("src")?.takeIf { it.isNotBlank() }

        val genres = sheader?.select("div.sgeneros a")?.map { it.text().trim() }
            ?: doc.select("div.sgeneros a, a[href*='/genero/']").map { it.text().trim() }
        if (genres.isNotEmpty()) {
            anime.genre = genres.distinct().joinToString(", ")
        }

        val synopsis = doc.selectFirst("div#info div.wp-content, div.wp-content")?.text()?.trim()

        val customFields = doc.select("div.custom_fields")
        val metaList = mutableListOf<String>()

        for (field in customFields) {
            val key = field.selectFirst("b.variante")?.text()?.trim()?.lowercase().orEmpty()
            val value = field.selectFirst("span.valor")?.text()?.trim().orEmpty()
            if (value.isBlank()) continue

            when {
                "original" in key -> metaList.add("Título original: $value")
                "tmdb" in key || "rating" in key -> metaList.add("★ TMDb: $value")
                "first" in key || "air date" in key -> metaList.add("Lançamento: $value")
                "duration" in key || "duração" in key -> metaList.add("Duração: $value")
                "temporadas" in key -> metaList.add("Temporadas: $value")
                "episódios" in key || "episodios" in key -> metaList.add("Episódios: $value")
            }
        }

        val descriptionParts = mutableListOf<String>()
        if (!synopsis.isNullOrBlank()) {
            descriptionParts.add(synopsis)
        }
        if (metaList.isNotEmpty()) {
            descriptionParts.add(metaList.joinToString(" • "))
        }

        anime.description = descriptionParts.joinToString("\n\n")
        anime.status = SAnime.UNKNOWN

        return anime
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String = "div#seasons div.se-c"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = getRealAnimeDoc(response.asJsoup())
        val seasons = doc.select(episodeListSelector())

        if (seasons.isEmpty()) {
            val directEpisodes = doc.select("ul.episodios > li")
            if (directEpisodes.isNotEmpty()) {
                return directEpisodes.mapIndexed { index, el ->
                    val ep = SEpisode.create()
                    val link = el.selectFirst("div.episodiotitle a[href]") ?: el.selectFirst("a[href]")!!
                    ep.setUrlWithoutDomain(link.attr("href"))
                    val epTitle = link.ownText().trim().ifEmpty { link.text().trim() }
                    val epNumText = el.selectFirst("div.numerando")?.text()?.trim()
                    ep.name = if (!epNumText.isNullOrBlank()) "$epNumText - $epTitle" else epTitle
                    ep.episode_number = epNumText?.substringAfterLast("-")?.trim()?.toFloatOrNull() ?: (index + 1).toFloat()
                    ep.date_upload = parseEpisodeDate(el.selectFirst("span.date")?.text())
                    ep
                }.reversed()
            }

            val ep = SEpisode.create()
            ep.setUrlWithoutDomain(doc.location())
            ep.name = "Filme"
            ep.episode_number = 1F
            return listOf(ep)
        }

        val episodeList = mutableListOf<SEpisode>()

        for (season in seasons) {
            val seasonTitle = season.selectFirst("div.se-q span.title")?.text()?.trim()
                ?: season.selectFirst("span.se-t")?.text()?.trim()
                ?: "Temporada"
            val seasonClean = seasonTitle.substringBefore("Oct.").substringBefore("Jan.").substringBefore("Feb.")
                .substringBefore("Mar.").substringBefore("Apr.").substringBefore("May.").substringBefore("Jun.")
                .substringBefore("Jul.").substringBefore("Aug.").substringBefore("Sep.").substringBefore("Nov.")
                .substringBefore("Dec.").trim()

            val eps = season.select("ul.episodios > li")
            for ((index, el) in eps.withIndex()) {
                val ep = SEpisode.create()
                val link = el.selectFirst("div.episodiotitle a[href*='/episodio/']")
                    ?: el.selectFirst("div.episodiotitle a[href]")
                    ?: el.selectFirst("a[href]")
                    ?: continue

                val href = link.attr("href")
                if (!href.startsWith("http://") && !href.startsWith("https://") && !href.startsWith("/")) {
                    continue
                }
                ep.setUrlWithoutDomain(href)

                val epTitle = link.ownText().trim().ifEmpty { link.text().trim() }
                val numText = el.selectFirst("div.numerando")?.text()?.trim().orEmpty()

                ep.name = buildString {
                    if (seasonClean.isNotBlank()) {
                        append(seasonClean)
                        append(" - ")
                    }
                    if (numText.isNotBlank()) {
                        append("Ep. ")
                        append(numText.substringAfterLast("-").trim())
                        if (epTitle.isNotBlank() && !epTitle.startsWith("Episódio", ignoreCase = true)) {
                            append(" - ")
                            append(epTitle)
                        }
                    } else {
                        append(epTitle)
                    }
                }

                ep.episode_number = numText.substringAfterLast("-").trim().toFloatOrNull() ?: (index + 1).toFloat()
                ep.date_upload = parseEpisodeDate(el.selectFirst("span.date")?.text())
                episodeList.add(ep)
            }
        }

        return episodeList.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val episodeUrl = if (episode.url.startsWith("http")) episode.url else baseUrl + episode.url
        val doc = client.newCall(GET(episodeUrl, headers)).awaitSuccess().asJsoup()

        val playerOptions = doc.select("ul#playeroptionsul li.dooplay_player_option, li.dooplay_player_option")
        val videoList = mutableListOf<Video>()

        for (option in playerOptions) {
            val postId = option.attr("data-post")
            val nume = option.attr("data-nume")
            val type = option.attr("data-type")
            val optionTitle = option.selectFirst("span.title")?.text()?.trim().orEmpty()

            if (postId.isBlank() || nume.isBlank() || type.isBlank()) continue

            val formBody = FormBody.Builder()
                .add("action", "doo_player_ajax")
                .add("post", postId)
                .add("nume", nume)
                .add("type", type)
                .build()

            val ajaxHeaders = headers.newBuilder()
                .set("Referer", episodeUrl)
                .set("X-Requested-With", "XMLHttpRequest")
                .build()

            val ajaxReq = Request.Builder()
                .url("$baseUrl/wp-admin/admin-ajax.php")
                .post(formBody)
                .headers(ajaxHeaders)
                .build()

            val response = runCatching { client.newCall(ajaxReq).execute() }.getOrNull() ?: continue
            val body = response.bodyString()
            val playerDto = runCatching { JSON.decodeFromString<DooPlayerDto>(body) }.getOrNull() ?: continue
            val embedUrl = playerDto.embedUrl.orEmpty()

            if (embedUrl.isBlank() || embedUrl.contains("Erro no upload", ignoreCase = true)) {
                continue
            }

            videoList.addAll(extractVideosFromEmbed(embedUrl, optionTitle, episodeUrl))
        }

        return videoList
    }

    private suspend fun extractVideosFromEmbed(embedUrl: String, serverName: String, episodeUrl: String): List<Video> {
        val fullUrl = if (embedUrl.startsWith("//")) "https:$embedUrl" else embedUrl
        val namePrefix = if (serverName.isNotBlank()) serverName else "Player"

        return when {
            "voe.sx" in fullUrl || "voe-network" in fullUrl || ("/e/" in fullUrl && ("voe" in fullUrl || "delivery" in fullUrl)) -> {
                voeExtractor.videosFromUrl(fullUrl, "$namePrefix - VOE")
            }
            "byse" in fullUrl || "bysezejataos.com" in fullUrl -> {
                byseExtractor.videosFromUrl(fullUrl)
            }
            else -> emptyList()
        }
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareByDescending { it.videoTitle.contains(quality) },
        )
    }

    // ============================== Settings ==============================
    @Suppress("UNCHECKED_CAST")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Qualidade padrão"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080p", "720p", "480p", "360p")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filtro por Gênero (ignorado na busca por texto)"),
        GenreFilter(),
    )

    private class GenreFilter :
        UriSelectFilter(
            "Gênero",
            arrayOf(
                Pair("Todos", ""),
                Pair("Ação", "genero/acao"),
                Pair("Aventura", "genero/aventura"),
                Pair("Comédia", "genero/comedia"),
                Pair("Drama", "genero/drama"),
                Pair("Fantasia", "genero/fantasia"),
                Pair("Ficção científica", "genero/ficcao-cientifica"),
                Pair("Mistério", "genero/misterio"),
                Pair("Romance", "genero/romance"),
                Pair("Sobrenatural", "genero/sobrenatural"),
                Pair("Terror", "genero/terror"),
            ),
        )

    private open class UriSelectFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun selectedValue(): String = vals[state].second
    }

    // ============================= Utilities ==============================
    private fun ensureAnimeMapLoaded(document: Document) {
        if (isMapLoaded && animeMap.isNotEmpty()) return

        val scriptNonce = document.select("script:containsData(dtGonza)").firstOrNull()?.data()
            ?.let { NONCE_REGEX.find(it)?.groupValues?.get(1) }
        val currentNonce = scriptNonce ?: nonce ?: fetchNonce()
        if (currentNonce.isNullOrBlank()) return
        nonce = currentNonce

        val glossaryTerms = listOf("09", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z")

        for (term in glossaryTerms) {
            val glossaryUrl = "$baseUrl/wp-json/dooplay/glossary/?term=$term&nonce=$currentNonce&type=all"
            runCatching {
                val res = client.newCall(GET(glossaryUrl, headers)).execute().bodyString()
                val jsonObject = JSON.decodeFromString<JsonObject>(res)
                for ((_, elem) in jsonObject) {
                    val dto = runCatching { JSON.decodeFromJsonElement<GlossaryItemDto>(elem.jsonObject) }.getOrNull() ?: continue
                    if (!dto.url.isNullOrBlank() && !dto.title.isNullOrBlank()) {
                        val slug = dto.url.removeSuffix("/").substringAfterLast("/")
                        animeMap[slug] = AnimeMeta(
                            url = dto.url,
                            title = dto.title.trim(),
                            poster = dto.img.orEmpty().trim(),
                        )
                    }
                }
            }
        }
        isMapLoaded = true
    }

    private fun fetchNonce(): String? = runCatching {
        val html = client.newCall(GET(baseUrl, headers)).execute().bodyString()
        NONCE_REGEX.find(html)?.groupValues?.get(1)
    }.getOrNull()

    private fun findAnimeMeta(serieName: String, epSlug: String): AnimeMeta? {
        val derivedSlug = epSlug
            .replace(Regex("-s\\d+-episodio-\\d+.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("-episodio-\\d+.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("-\\d+x\\d+.*$", RegexOption.IGNORE_CASE), "")

        animeMap[derivedSlug]?.let { return it }

        val normSerie = normalize(serieName)
        for ((_, meta) in animeMap) {
            val normTitle = normalize(meta.title)
            if (normSerie.isNotBlank() && (normSerie in normTitle || normTitle in normSerie)) {
                return meta
            }
        }

        val firstWord = serieName.split(" ", "-", "_").firstOrNull { it.length > 2 }
        if (!firstWord.isNullOrBlank() && !nonce.isNullOrBlank()) {
            val searchUrl = "$baseUrl/wp-json/dooplay/search/?keyword=$firstWord&nonce=$nonce"
            runCatching {
                val res = client.newCall(GET(searchUrl, headers)).execute().bodyString()
                val jsonObject = JSON.decodeFromString<JsonObject>(res)
                val firstItem = jsonObject.values.firstOrNull()?.jsonObject
                if (firstItem != null) {
                    val dto = JSON.decodeFromJsonElement<GlossaryItemDto>(firstItem)
                    if (!dto.url.isNullOrBlank() && !dto.title.isNullOrBlank()) {
                        val meta = AnimeMeta(
                            url = dto.url,
                            title = dto.title.trim(),
                            poster = dto.img.orEmpty().trim(),
                        )
                        val slug = dto.url.removeSuffix("/").substringAfterLast("/")
                        animeMap[slug] = meta
                        return meta
                    }
                }
            }
        }

        return null
    }

    private fun normalize(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun getRealAnimeDoc(document: Document): Document {
        val menu = document.selectFirst("div.pag_episodes div.item a[href*='/anime/']")
        return if (menu != null) {
            val originalUrl = menu.attr("href")
            if (originalUrl.startsWith("http://") || originalUrl.startsWith("https://")) {
                runCatching { client.newCall(GET(originalUrl, headers)).execute().asJsoup() }.getOrNull() ?: document
            } else {
                document
            }
        } else {
            document
        }
    }

    private fun parseEpisodeDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return runCatching {
            DATE_FORMAT.parse(dateStr.trim())?.time ?: 0L
        }.getOrDefault(0L)
    }

    private fun cleanAnimeTitle(title: String): String = title
        .replace(Regex("[-_ ]+S\\d+.*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[-_ ]+Episodio[-_ ]+\\d+.*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[-_ ]+Ep[-_ ]+\\d+.*$", RegexOption.IGNORE_CASE), "")
        .trim()

    private data class AnimeMeta(
        val url: String,
        val title: String,
        val poster: String,
    )

    @Serializable
    private data class GlossaryItemDto(
        val title: String? = null,
        val url: String? = null,
        val img: String? = null,
    )

    @Serializable
    private data class DooPlayerDto(
        val embed_url: String? = null,
        val type: String? = null,
    ) {
        val embedUrl: String? get() = embed_url
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val DATE_FORMAT by lazy { SimpleDateFormat("MMM. dd, yyyy", Locale.ENGLISH) }
        private val NONCE_REGEX by lazy { Regex("\"nonce\"\\s*:\\s*\"([^\"]+)\"") }
        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
