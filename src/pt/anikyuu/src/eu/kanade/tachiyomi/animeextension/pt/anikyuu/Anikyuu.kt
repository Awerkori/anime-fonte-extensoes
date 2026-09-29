package eu.kanade.tachiyomi.animeextension.pt.anikyuu

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.bloggerextractor.BloggerExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.googledriveplayerextractor.GoogleDrivePlayerExtractor
import eu.kanade.tachiyomi.animeextension.pt.anikyuu.extractors.ByseExtractor
import eu.kanade.tachiyomi.animeextension.pt.anikyuu.extractors.EmTurbovidExtractor
import eu.kanade.tachiyomi.animeextension.pt.anikyuu.extractors.StrmupExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class Anikyuu :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {
    override val name = "Anikyuu"
    override val baseUrl = "https://j-s-an.github.io"
    override val lang = "pt-BR"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)

    // ============================ Extractors ============================
    private val bloggerExtractor by lazy { BloggerExtractor(client) }
    private val gdriveExtractor by lazy { GoogleDrivePlayerExtractor(client, headers) }
    private val byseExtractor by lazy { ByseExtractor(client, headers, baseUrl) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val strmupExtractor by lazy { StrmupExtractor(client, headers) }
    private val emTurbovidExtractor by lazy { EmTurbovidExtractor(client, headers) }

    // ============================ In-memory Cache ============================
    @Volatile private var catalogCache: List<CatalogEntry>? = null

    @Volatile private var catalogCacheTime: Long = 0L

    @Volatile private var genresList: List<String> = emptyList()

    @Volatile private var themesList: List<String> = emptyList()

    @Volatile private var explicitsList: List<String> = emptyList()

    @Volatile private var demographicsList: List<String> = emptyList()

    private suspend fun getCatalog(): List<CatalogEntry> {
        val now = System.currentTimeMillis()
        val cached = catalogCache
        if (cached != null && now - catalogCacheTime < CACHE_TTL_MS) {
            return cached
        }
        val response = client.newCall(GET(CATALOG_CSV_URL, headers)).awaitSuccess()
        val text = response.bodyString()
        val entries = AnikyuuHelper.parseCatalogCsv(text)
        catalogCache = entries
        catalogCacheTime = now
        updateDynamicFilters(entries)
        return entries
    }

    private fun updateDynamicFilters(entries: List<CatalogEntry>) {
        val gSet = mutableSetOf<String>()
        val tSet = mutableSetOf<String>()
        val eSet = mutableSetOf<String>()
        val dSet = mutableSetOf<String>()
        for (entry in entries) {
            entry.genres.split(",").map(String::trim).filter(String::isNotEmpty).forEach { gSet.add(it) }
            entry.themes.split(",").map(String::trim).filter(String::isNotEmpty).forEach { tSet.add(it) }
            entry.explicitGenres.split(",").map(String::trim).filter(String::isNotEmpty).forEach { eSet.add(it) }
            entry.demographics.split(",").map(String::trim).filter(String::isNotEmpty).forEach { dSet.add(it) }
        }
        genresList = gSet.sorted()
        themesList = tSet.sorted()
        explicitsList = eSet.sorted()
        demographicsList = dSet.sorted()
    }

    // ============================ Popular Anime ============================
    override fun popularAnimeRequest(page: Int) = GET(CATALOG_CSV_URL, headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val entries = AnikyuuHelper.parseCatalogCsv(response.bodyString()).asReversed()
        val pageSize = 24
        val pageItems = entries.take(pageSize)
        val hasNext = pageSize < entries.size
        return AnimesPage(pageItems.map(::toAnime), hasNext)
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val entries = getCatalog().asReversed()
        val pageSize = 24
        val start = (page - 1) * pageSize
        val pageItems = entries.drop(start).take(pageSize)
        val hasNext = start + pageSize < entries.size
        return AnimesPage(pageItems.map(::toAnime), hasNext)
    }

    // ============================ Latest Updates ============================
    override fun latestUpdatesRequest(page: Int) = GET(LANCAMENTO_JSON_URL, headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val catalog = runBlocking { getCatalog() }
        return parseLancamentoPage(response.bodyString(), 1, catalog)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val catalog = getCatalog()
        val text = client.newCall(latestUpdatesRequest(page)).awaitSuccess().bodyString()
        return parseLancamentoPage(text, page, catalog)
    }

    private fun parseLancamentoPage(text: String, page: Int, catalog: List<CatalogEntry>): AnimesPage {
        val entries = AnikyuuHelper.parseLancamentos(text)
        val cards = AnikyuuHelper.resolveLancamentosToCards(entries, catalog, baseUrl)
        val animes = cards.map { card ->
            SAnime.create().apply {
                title = card.title
                thumbnail_url = card.cover
                url = card.url
            }
        }

        val pageSize = 24
        val start = (page - 1) * pageSize
        val pageItems = animes.drop(start).take(pageSize)
        val hasNext = start + pageSize < animes.size
        return AnimesPage(pageItems, hasNext)
    }

    // ============================ Search ============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = GET(CATALOG_CSV_URL, headers)

    override fun searchAnimeParse(response: Response): AnimesPage = error("Use getSearchAnime")

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val entries = getCatalog().asReversed()
        val qNorm = AnikyuuHelper.normalize(query.trim())

        val selectedGenre = filters.filterIsInstance<AnikyuuFilters.GenreFilter>().firstOrNull()?.selected ?: "Todos"
        val selectedTheme = filters.filterIsInstance<AnikyuuFilters.ThemeFilter>().firstOrNull()?.selected ?: "Todos"
        val selectedExplicit = filters.filterIsInstance<AnikyuuFilters.ExplicitGenreFilter>().firstOrNull()?.selected ?: "Todos"
        val selectedDemographic = filters.filterIsInstance<AnikyuuFilters.DemographicFilter>().firstOrNull()?.selected ?: "Todos"

        val filtered = entries.filter { entry ->
            val matchQuery = if (qNorm.isEmpty()) {
                true
            } else {
                AnikyuuHelper.normalize(entry.name).contains(qNorm) || AnikyuuHelper.normalize(entry.altName).contains(qNorm)
            }
            if (!matchQuery) return@filter false

            if (selectedGenre != "Todos" && !entry.genres.contains(selectedGenre, ignoreCase = true)) {
                return@filter false
            }
            if (selectedTheme != "Todos" && !entry.themes.contains(selectedTheme, ignoreCase = true)) {
                return@filter false
            }
            if (selectedExplicit != "Todos" && !entry.explicitGenres.contains(selectedExplicit, ignoreCase = true)) {
                return@filter false
            }
            if (selectedDemographic != "Todos" && !entry.demographics.contains(selectedDemographic, ignoreCase = true)) {
                return@filter false
            }
            true
        }

        val pageSize = 24
        val start = (page - 1) * pageSize
        val pageItems = filtered.drop(start).take(pageSize)
        val hasNext = start + pageSize < filtered.size
        return AnimesPage(pageItems.map(::toAnime), hasNext)
    }

    // ============================ Anime Details ============================
    override fun animeDetailsRequest(anime: SAnime) = GET(
        if (anime.url.startsWith("http")) anime.url else "$baseUrl${anime.url}",
        headers,
    )

    override fun animeDetailsParse(response: Response): SAnime = error("Use getAnimeDetails")

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val catalog = getCatalog()
        val entry = AnikyuuHelper.findInCatalog(anime.url, anime.title, catalog)
        if (entry != null) {
            anime.title = entry.name
            anime.thumbnail_url = entry.cover
            anime.genre = listOf(entry.genres, entry.themes, entry.demographics, entry.explicitGenres)
                .filter(String::isNotBlank)
                .joinToString(", ")
            anime.description = buildString {
                if (entry.altName.isNotBlank()) append("Nome alternativo: ${entry.altName}\n")
                if (entry.themes.isNotBlank()) append("Tema: ${entry.themes}\n")
                if (entry.demographics.isNotBlank()) append("Demografia: ${entry.demographics}\n")
                if (entry.explicitGenres.isNotBlank()) append("Gênero explícito: ${entry.explicitGenres}\n")
            }.trim()
            anime.status = SAnime.COMPLETED
            return anime
        }

        try {
            val doc = client.newCall(animeDetailsRequest(anime)).awaitSuccess().asJsoup()
            val headerTitle = doc.select(".header span, .header .titulo, title").firstOrNull()?.text()
            if (!headerTitle.isNullOrBlank() && anime.title.isBlank()) {
                anime.title = headerTitle.substringBefore(" - ").substringBefore(" | ").trim()
            }
            val img = doc.select("meta[property=og:image]").attr("content")
            if (img.isNotBlank() && anime.thumbnail_url.isNullOrBlank()) {
                anime.thumbnail_url = img
            }
        } catch (_: Exception) {}

        return anime
    }

    // ============================ Episodes ============================
    override fun episodeListRequest(anime: SAnime) = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> = error("Use getEpisodeList")

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = coroutineScope {
        val (slug, season) = AnikyuuHelper.extractSlugAndSeason(anime.url)
        val episodes = mutableListOf<SEpisode>()

        val audioPref = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT
        val audios = when (audioPref) {
            "Legendado" -> listOf("legendado")
            "Dublado" -> listOf("dublado")
            else -> listOf("legendado", "dublado")
        }

        val menuJobs = audios.map { audio ->
            async {
                fetchEpisodesForAudio(slug, season, audio, anime.url)
            }
        }
        val results = menuJobs.awaitAll()
        results.forEach { episodes.addAll(it) }

        if (episodes.isEmpty()) {
            val directEpisodes = fetchDirectEpisodes(anime.url)
            val filteredDirect = when (audioPref) {
                "Legendado" -> directEpisodes.filter { it.scanlator.equals("Legendado", ignoreCase = true) }
                "Dublado" -> directEpisodes.filter { it.scanlator.equals("Dublado", ignoreCase = true) }
                else -> directEpisodes
            }
            episodes.addAll(if (filteredDirect.isNotEmpty()) filteredDirect else directEpisodes)
        }

        episodes.sortedWith(
            compareByDescending<SEpisode> { it.episode_number }
                .thenBy { it.scanlator }
                .thenBy { it.name },
        )
    }

    private suspend fun fetchEpisodesForAudio(
        slug: String,
        season: String,
        audio: String,
        originalUrl: String,
    ): List<SEpisode> {
        val candidates = AnikyuuHelper.buildMenuCandidates(slug, season, audio, originalUrl, baseUrl)
        var menuDoc: Document? = null
        var menuUrl: String? = null

        for (cand in candidates) {
            try {
                val resp = client.newCall(GET(cand, headers)).await()
                if (resp.isSuccessful) {
                    menuDoc = resp.asJsoup()
                    menuUrl = cand
                    break
                }
            } catch (_: Exception) {}
        }

        if (menuDoc == null || menuUrl == null) return emptyList()

        return parseMenuEpisodes(menuDoc, menuUrl, audio)
    }

    private suspend fun parseMenuEpisodes(
        doc: Document,
        menuUrl: String,
        audio: String,
    ): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val audioScanlator = if (audio.equals("dublado", ignoreCase = true)) "Dublado" else "Legendado"

        val subSeasonLinks = doc.select(".episodes a[href*=temporada-], a.botao[href*=temporada-]")
        if (subSeasonLinks.isNotEmpty()) {
            for (subLink in subSeasonLinks) {
                val subUrl = subLink.absUrl("href").ifBlank {
                    menuUrl.toHttpUrl().resolve(subLink.attr("href"))?.toString()
                } ?: continue
                try {
                    val subDoc = client.newCall(GET(subUrl, headers)).awaitSuccess().asJsoup()
                    val subEpisodes = parseMenuEpisodes(subDoc, subUrl, audio)
                    episodes.addAll(subEpisodes)
                } catch (_: Exception) {}
            }
            if (episodes.isNotEmpty()) return episodes
        }

        doc.select(".episode").forEach { epEl ->
            val linkEl = epEl.selectFirst("a[href*=\"episodio-\"], a[href$=\"html\"]") ?: return@forEach
            val href = linkEl.attr("href")
            if (href.contains("menu.html") || href.contains("temporada")) return@forEach

            val titleEl = epEl.selectFirst(".episode-title")
            val rawTitle = titleEl?.text()?.trim() ?: linkEl.text().trim()
            val epNum = AnikyuuHelper.extractEpisodeNumber(rawTitle, href)
            val fullUrl = linkEl.absUrl("href").ifBlank {
                menuUrl.toHttpUrl().resolve(href)?.toString() ?: ""
            }
            if (fullUrl.isBlank()) return@forEach

            val finalTitle = AnikyuuHelper.cleanEpisodeTitle(rawTitle, epNum)

            episodes.add(
                SEpisode.create().apply {
                    url = fullUrl
                    name = finalTitle
                    episode_number = epNum
                    scanlator = audioScanlator
                    date_upload = 0L
                },
            )
        }

        if (episodes.isEmpty()) {
            doc.select("a[href*=\"episodio-\"]").forEach { linkEl ->
                val href = linkEl.attr("href")
                if (href.contains("menu.html")) return@forEach
                val epNum = AnikyuuHelper.extractEpisodeNumber(linkEl.text(), href)
                val fullUrl = linkEl.absUrl("href").ifBlank {
                    menuUrl.toHttpUrl().resolve(href)?.toString() ?: ""
                }
                if (fullUrl.isBlank()) return@forEach

                val finalTitle = AnikyuuHelper.cleanEpisodeTitle(linkEl.text().trim(), epNum)
                episodes.add(
                    SEpisode.create().apply {
                        url = fullUrl
                        name = finalTitle
                        episode_number = epNum
                        scanlator = audioScanlator
                        date_upload = 0L
                    },
                )
            }
        }

        return episodes
    }

    private suspend fun fetchDirectEpisodes(url: String): List<SEpisode> {
        val targetUrl = if (url.startsWith("http")) url else "$baseUrl$url"
        return try {
            val doc = client.newCall(GET(targetUrl, headers)).awaitSuccess().asJsoup()
            val menuLink = doc.select("a[href*=menu.html]").firstOrNull()?.absUrl("href")
            if (!menuLink.isNullOrBlank()) {
                val audio = if (menuLink.contains("/dublado/")) "dublado" else "legendado"
                parseMenuEpisodes(client.newCall(GET(menuLink, headers)).awaitSuccess().asJsoup(), menuLink, audio)
            } else {
                val epNum = AnikyuuHelper.extractEpisodeNumber(doc.title(), targetUrl)
                val rawTitle = doc.title().ifBlank { "Episódio $epNum" }
                val cleanTitle = AnikyuuHelper.cleanEpisodeTitle(rawTitle, epNum)
                val audio = if (targetUrl.contains("dublado", ignoreCase = true)) "Dublado" else "Legendado"
                listOf(
                    SEpisode.create().apply {
                        this.url = targetUrl
                        name = cleanTitle
                        episode_number = epNum
                        scanlator = audio
                        date_upload = 0L
                    },
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ============================ Video Links ============================
    override fun videoListRequest(episode: SEpisode) = GET(episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val html = response.bodyString()
        return extractVideosFromHtml(html, response.request.url.toString())
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val response = client.newCall(videoListRequest(episode)).awaitSuccess()
        return videoListParse(response).sortVideos()
    }

    private fun extractVideosFromHtml(html: String, pageUrl: String): List<Video> = runBlocking {
        val doc = Jsoup.parse(html, pageUrl)
        val playerUrls = mutableListOf<String>()

        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank() && !src.startsWith("about:") && !src.contains("a-ads.com") && !src.contains("acceptable.")) {
                playerUrls.add(src)
            }
        }

        val scriptElements = doc.select("script:containsData(players)")
        for (script in scriptElements) {
            val scriptContent = script.html()
            val regex = Regex("""src:\s*['"](https?://[^'"]+)['"]""")
            regex.findAll(scriptContent).forEach { match ->
                playerUrls.add(match.groupValues[1].trim())
            }
        }

        val videos = mutableListOf<Video>()
        for (rawUrl in playerUrls.distinct()) {
            val url = rawUrl.replace("&amp;", "&")
            try {
                when {
                    url.contains("blogger.com/video.g") || url.contains("blogger.com") -> {
                        val bloggerVideos = bloggerExtractor.videosFromUrl(url, headers)
                        videos.addAll(bloggerVideos)
                    }
                    url.contains("drive.google.com") -> {
                        val driveVideos = gdriveExtractor.videosFromUrl(url)
                        videos.addAll(driveVideos)
                        val fileId = Regex("/file/d/([a-zA-Z0-9_-]+)").find(url)?.groupValues?.get(1)
                            ?: url.toHttpUrlOrNull()?.queryParameter("id")
                        if (fileId != null) {
                            val directUrl = "https://drive.google.com/uc?id=$fileId&export=download"
                            videos.add(Video(directUrl, "Google Drive Direct (Download)", directUrl, headers))
                        }
                    }
                    url.contains("turbovidhls.com") || url.contains("emturbovid.com") -> {
                        videos.addAll(emTurbovidExtractor.videosFromUrl(url))
                    }
                    url.contains("filemoon") -> {
                        videos.addAll(filemoonExtractor.videosFromUrl(url))
                    }
                    url.contains("byselapuix.com") -> {
                        videos.addAll(byseExtractor.videosFromUrl(url))
                    }
                    url.contains("strmup.to") -> {
                        videos.addAll(strmupExtractor.videosFromUrl(url))
                    }
                    url.endsWith(".mp4") || url.endsWith(".m3u8") -> {
                        videos.add(Video(url, "Direct Player", url, headers))
                    }
                }
            } catch (_: Exception) {}
        }

        videos.distinctBy { it.videoUrl }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val preferred = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
        return sortedWith(
            compareByDescending<Video> { preferred != PREF_QUALITY_DEFAULT && resolution(it.videoTitle) == resolution(preferred.orEmpty()) }
                .thenByDescending { resolution(it.videoTitle) },
        )
    }

    private fun resolution(label: String) = Regex("""(\d+)p""").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    // ============================ Preferences & Filters ============================
    override fun getFilterList() = AnikyuuFilters.getFilterList(
        genresList,
        themesList,
        explicitsList,
        demographicsList,
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Qualidade preferida"
            entries = arrayOf("Maior disponível", "1080p", "720p", "480p", "360p", "240p")
            entryValues = arrayOf("best", "1080p", "720p", "480p", "360p", "240p")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_AUDIO_KEY
            title = "Áudio dos episódios"
            entries = arrayOf("Todos", "Legendado", "Dublado")
            entryValues = arrayOf("Todos", "Legendado", "Dublado")
            setDefaultValue(PREF_AUDIO_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================ Utilities ============================
    private fun toAnime(entry: CatalogEntry) = SAnime.create().apply {
        title = entry.name
        thumbnail_url = entry.cover
        url = entry.link
        genre = listOf(entry.genres, entry.themes, entry.demographics, entry.explicitGenres)
            .filter(String::isNotBlank)
            .joinToString(", ")
        status = SAnime.COMPLETED
    }

    companion object {
        private const val CATALOG_CSV_URL =
            "https://docs.google.com/spreadsheets/d/e/2PACX-1vSioAlFGQbJ-9pSAM0oux7B6ppVzWjBmJU2meX0fK2f7pSIyhr3Ymw0yC5SXyqORNok9mr_oVw7hBhg/pub?gid=0&single=true&output=csv"
        private const val LANCAMENTO_JSON_URL =
            "https://docs.google.com/spreadsheets/d/1QPSqwuXb-2De4SvQ3WuJVbQbWMtPsv3SxjTeGtpuTVo/gviz/tq?tqx=out:json&sheet=lancamento"

        private const val CACHE_TTL_MS = 15 * 60 * 1000L
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "best"
        private const val PREF_AUDIO_KEY = "preferred_audio"
        private const val PREF_AUDIO_DEFAULT = "Todos"
    }
}
