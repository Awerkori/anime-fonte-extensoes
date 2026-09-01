package eu.kanade.tachiyomi.animeextension.pt.animesroll

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.animesroll.extractors.AnimesROLLExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.tryParse
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class AnimesROLL :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "AnimesROLL"

    override val baseUrl = "https://anroll.io"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val preferences by getPreferencesLazy()

    private val animesrollExtractor by lazy { AnimesROLLExtractor(client, headers) }

    private val dateFormatter by lazy {
        SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR"))
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime/?pg=$page&order=popular", headers)

    override fun popularAnimeSelector(): String = "div.anime-grid article.anime-card"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a")!!
        setUrlWithoutDomain(link.attr("abs:href"))
        title = link.selectFirst("span.card-title")?.text()?.trim() ?: link.text().trim()
        thumbnail_url = link.selectFirst("img")?.getImageUrl()
    }

    override fun popularAnimeNextPageSelector(): String = "div.ep-pagination-nav a.ep-pg-arrow"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = if (page == 1) {
        GET("$baseUrl/lancamentos/", headers)
    } else {
        GET("$baseUrl/lancamentos/?pg=$page", headers)
    }

    override fun latestUpdatesSelector(): String = "div.anime-grid article.anime-card"

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = "div.ep-pagination-nav a.ep-pg-arrow"

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("URL não suportada")
            }
            val path = url.pathSegments.takeIf { it.isNotEmpty() }?.joinToString("/")
                ?: throw Exception("URL não suportada")
            return client.newCall(GET("$baseUrl/$path", headers))
                .awaitSuccess()
                .use(::searchAnimeByPathParse)
        }

        return super.getSearchAnime(page, query, filters)
    }

    private fun searchAnimeByPathParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.useAsJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/anime/".toHttpUrl().newBuilder()

        if (page > 1) {
            url.addQueryParameter("pg", page.toString())
        }

        if (query.isNotBlank()) {
            url.addQueryParameter("q", query.trim())
        }

        filters.forEach { filter ->
            when (filter) {
                is OrderFilter -> {
                    if (filter.toUriPart().isNotEmpty()) {
                        url.addQueryParameter("order", filter.toUriPart())
                    }
                }
                is AudioFilter -> {
                    if (filter.toUriPart().isNotEmpty()) {
                        url.addQueryParameter("audio[]", filter.toUriPart())
                    }
                }
                is StatusFilter -> {
                    if (filter.toUriPart().isNotEmpty()) {
                        url.addQueryParameter("status[]", filter.toUriPart())
                    }
                }
                is TypeFilter -> {
                    if (filter.toUriPart().isNotEmpty()) {
                        url.addQueryParameter("type[]", filter.toUriPart())
                    }
                }
                is GenreFilter -> {
                    filter.state
                        .filter { it.state }
                        .forEach { genre ->
                            url.addQueryParameter("genre[]", genre.id)
                        }
                }
                else -> {}
            }
        }

        return GET(url.build().toString(), headers)
    }

    override fun searchAnimeSelector(): String = "div.anime-grid article.anime-card"

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = "div.ep-pagination-nav a.ep-pg-arrow"

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(document.location())

        val titleText = document.selectFirst("h1.anime-page-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: ""
        title = titleText

        thumbnail_url = document.selectFirst("div.anime-poster img")?.getImageUrl()
            ?: document.selectFirst("section.anime-hero img.anime-hero-bg-img")?.getImageUrl()

        val metaGrid = document.selectFirst("div.anime-meta-grid")
        val statusText = metaGrid?.selectFirst("div.meta-item:has(span.meta-label:contains(Status)) span.meta-value")?.text()
            ?: document.selectFirst("span.badge-status")?.text()
        status = parseStatus(statusText)

        val studio = metaGrid?.selectFirst("div.meta-item:has(span.meta-label:contains(Estúdio)) span.meta-value")?.text()
        artist = studio

        val genres = document.select("div.anime-genres a.genre-tag").eachText()
        genre = genres.joinToString(", ")

        val altTitle = document.selectFirst("p.anime-alt-title")?.text()?.trim()

        val synopsis = document.selectFirst("div.anime-synopsis")?.text()?.trim()

        val extraInfo = buildList {
            metaGrid?.select("div.meta-item")?.forEach { item ->
                val label = item.selectFirst("span.meta-label")?.text()?.trim() ?: return@forEach
                val value = item.selectFirst("span.meta-value")?.text()?.trim() ?: return@forEach
                if (label !in listOf("Status", "Estúdio")) {
                    add("$label: $value")
                }
            }
        }

        description = buildString {
            if (!synopsis.isNullOrBlank()) {
                append(synopsis)
                append("\n\n")
            }
            if (!altTitle.isNullOrBlank()) {
                append("Título alternativo: $altTitle\n")
            }
            if (extraInfo.isNotEmpty()) {
                append(extraInfo.joinToString("\n"))
            }
        }.trim()
    }

    private fun parseStatus(statusString: String?): Int = when (statusString?.trim()?.lowercase()) {
        "completo", "completed" -> SAnime.COMPLETED
        "lançamento", "lancamento", "ongoing" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val episodeElements = document.select("div#ep-text-list a.ep-text-item, div.ep-text-list a.ep-text-item")

        if (episodeElements.isNotEmpty()) {
            return episodeElements.map(::episodeFromElement).reversed()
        }

        val sidebarEpisodes = document.select("div#ep-sidebar-list a.ep-sidebar-item, div.ep-sidebar-list a.ep-sidebar-item")
        if (sidebarEpisodes.isNotEmpty()) {
            return sidebarEpisodes.map { element ->
                SEpisode.create().apply {
                    setUrlWithoutDomain(element.attr("abs:href"))
                    val epNumberText = element.selectFirst("span.ep-sn")?.text()?.trim().orEmpty()
                    val titleText = element.selectFirst("span.ep-st")?.text()?.trim()
                    name = if (!titleText.isNullOrBlank()) titleText else "Episódio $epNumberText"
                    episode_number = epNumberText.toFloatOrNull() ?: 0F
                }
            }.reversed()
        }

        return emptyList()
    }

    override fun episodeListSelector(): String = "div#ep-text-list a.ep-text-item"

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))

        val numBadge = element.selectFirst("span.ep-num-badge")?.text()?.trim().orEmpty()
        val titleText = element.selectFirst("span.ep-text-title")?.text()?.trim()
        val dateText = element.selectFirst("span.ep-date")?.text()?.trim()

        name = if (!titleText.isNullOrBlank()) titleText else "Episódio $numBadge"
        episode_number = numBadge.toFloatOrNull() ?: 0F
        date_upload = dateText?.let { dateFormatter.tryParse(it) } ?: 0L
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        val iframes = document.select("div#pembed iframe[src], div.ep-player-inner iframe[src], iframe[src]")

        val iframeUrls = iframes.mapNotNull { it.attr("abs:src").takeIf(String::isNotBlank) }.distinct()

        return iframeUrls.parallelCatchingFlatMapBlocking { iframeUrl ->
            when {
                "anidrive.click" in iframeUrl -> animesrollExtractor.videosFromUrl(iframeUrl)
                else -> emptyList()
            }
        }
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filtros do AnimesROLL"),
        OrderFilter(),
        AudioFilter(),
        StatusFilter(),
        TypeFilter(),
        AnimeFilter.Separator(),
        GenreFilter(getGenresList()),
    )

    private class OrderFilter :
        AnimeFilter.Select<String>(
            "Ordenar por",
            arrayOf(
                "Padrão (A-Z)",
                "Z-A",
                "Mais Novo",
                "Mais Antigo",
                "Atualizado",
                "Popular",
            ),
        ) {
        fun toUriPart() = when (state) {
            1 -> "titlez"
            2 -> "new"
            3 -> "old"
            4 -> "update"
            5 -> "popular"
            else -> ""
        }
    }

    private class AudioFilter :
        AnimeFilter.Select<String>(
            "Áudio",
            arrayOf("Todos", "Dublado", "Legendado"),
        ) {
        fun toUriPart() = when (state) {
            1 -> "Dublado"
            2 -> "Legendado"
            else -> ""
        }
    }

    private class StatusFilter :
        AnimeFilter.Select<String>(
            "Status",
            arrayOf("Todos", "Lançamento", "Completo", "Em breve", "Hiato"),
        ) {
        fun toUriPart() = when (state) {
            1 -> "Ongoing"
            2 -> "Completed"
            3 -> "Upcoming"
            4 -> "Hiatus"
            else -> ""
        }
    }

    private class TypeFilter :
        AnimeFilter.Select<String>(
            "Formato",
            arrayOf("Todos", "TV", "Filme", "OVA", "ONA", "Especial"),
        ) {
        fun toUriPart() = when (state) {
            1 -> "TV"
            2 -> "Movie"
            3 -> "OVA"
            4 -> "ONA"
            5 -> "Special"
            else -> ""
        }
    }

    private class Genre(name: String, val id: String) : AnimeFilter.CheckBox(name)

    private class GenreFilter(genres: List<Genre>) : AnimeFilter.Group<Genre>("Gêneros", genres)

    private fun getGenresList() = listOf(
        Genre("+18", "18"),
        Genre("Ação", "action"),
        Genre("Ambiente de Trabalho", "workplace"),
        Genre("Antropomórfico", "anthropomorphic"),
        Genre("Artes Cênicas", "performing-arts"),
        Genre("Artes Marciais", "martial-arts"),
        Genre("Aventura", "adventure"),
        Genre("Boys Love", "boys-love"),
        Genre("CGDCT", "cgdct"),
        Genre("Comédia", "comedy"),
        Genre("Corrida", "racing"),
        Genre("Crime Organizado", "organized-crime"),
        Genre("Crossdressing", "crossdressing"),
        Genre("Cuidado Infantil", "childcare"),
        Genre("Cultura Otaku", "otaku-culture"),
        Genre("Delinquentes", "delinquents"),
        Genre("Detetive", "detective"),
        Genre("Drama", "drama"),
        Genre("Ecchi", "ecchi"),
        Genre("Educacional", "educational"),
        Genre("Elenco Adulto", "adult-cast"),
        Genre("Erótica", "erotica"),
        Genre("Escolar", "school"),
        Genre("Espacial", "space"),
        Genre("Esportes", "sports"),
        Genre("Esportes de Combate", "combat-sports"),
        Genre("Esportes em Equipe", "team-sports"),
        Genre("Fantasia", "fantasy"),
        Genre("Fantasia Urbana", "urban-fantasy"),
        Genre("Girls Love", "girls-love"),
        Genre("Gore", "gore"),
        Genre("Gourmet", "gourmet"),
        Genre("Harem", "harem"),
        Genre("Harem Reverso", "harem-reverso"),
        Genre("Histórico", "historical"),
        Genre("Humor Pastelão", "gag-humor"),
        Genre("Idols Femininos", "idols-female"),
        Genre("Idols Masculinos", "idols-male"),
        Genre("Infantil", "kids"),
        Genre("Isekai", "isekai"),
        Genre("Iyashikei", "iyashikei"),
        Genre("Jogo de Alto Risco", "high-stakes-game"),
        Genre("Jogo de Estratégia", "strategy-game"),
        Genre("Josei", "josei"),
        Genre("Mahou Shoujo", "mahou-shoujo"),
        Genre("Mecha", "mecha"),
        Genre("Médico", "medical"),
        Genre("Militar", "military"),
        Genre("Mistério", "mystery"),
        Genre("Mitologia", "mythology"),
        Genre("Música", "music"),
        Genre("Paródia", "parody"),
        Genre("Pets", "pets"),
        Genre("Polígono Amoroso", "love-polygon"),
        Genre("Premiados", "award-winning"),
        Genre("Psicológico", "psychological"),
        Genre("Reencarnação", "reincarnation"),
        Genre("Romance", "romance"),
        Genre("Samurai", "samurai"),
        Genre("Sci-Fi", "sci-fi"),
        Genre("Seinen", "seinen"),
        Genre("Shoujo", "shoujo"),
        Genre("Shounen", "shounen"),
        Genre("Showbiz", "showbiz"),
        Genre("Slice of Life", "slice-of-life"),
        Genre("Sobrenatural", "supernatural"),
        Genre("Sobrevivência", "survival"),
        Genre("Super Poderes", "super-power"),
        Genre("Suspense", "suspense"),
        Genre("Terror / Horror", "horror"),
        Genre("Troca de Gênero Mágica", "magical-sex-shift"),
        Genre("Vampiros", "vampire"),
        Genre("Vanguarda", "avant-garde"),
        Genre("Viagem no Tempo", "time-travel"),
        Genre("Vídeo Game", "video-game"),
        Genre("Vilã", "villainess"),
        Genre("Artes Visuais", "visual-arts"),
    )

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val prefQuality = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Qualidade preferida"
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entries[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(prefQuality)
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        return sortedWith(
            compareBy { it.videoTitle.contains(quality, true) },
        ).reversed()
    }

    // ============================= Utilities ==============================

    private fun Element.getImageUrl(): String? = when {
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
        else -> attr("abs:src")
    }.substringBefore("?resize")

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "240p")
        private val PREF_QUALITY_VALUES = arrayOf("1080p", "720p", "480p", "360p", "240p")
    }
}
