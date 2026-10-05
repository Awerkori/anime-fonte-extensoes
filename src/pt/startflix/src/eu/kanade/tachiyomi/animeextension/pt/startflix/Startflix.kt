package eu.kanade.tachiyomi.animeextension.pt.startflix

import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.animeextension.pt.startflix.extractors.ByseExtractor
import eu.kanade.tachiyomi.animeextension.pt.startflix.extractors.WebPlayerExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Startflix : DooPlay("pt-BR", "Startflix", "https://www.startflix.biz") {

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().set("Referer", "$baseUrl/")

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val byseExtractor by lazy { ByseExtractor(client, headers) }
    private val webPlayerExtractor by lazy { WebPlayerExtractor(client, headers) }

    // ============================== Listings ==============================

    private fun animeFromElement(element: Element): SAnime = SAnime.create().apply {
        val titleText = element.selectFirst("div.data h3 a, div.details div.title a, div.data h3, div.details div.title")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: element.selectFirst("div.poster img, div.image img, img")
                ?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: element.selectFirst("div.poster img, div.image img, img")
                ?.attr("title")?.trim()?.takeIf { it.isNotBlank() }
            ?: element.selectFirst("a[title]")
                ?.attr("title")?.trim().orEmpty()

        title = titleText

        val anchor = element.selectFirst("div.data h3 a, div.details div.title a")
            ?: element.selectFirst("div.poster a, div.image a, a[href]")
            ?: throw Exception("Could not find anime link")

        setUrlWithoutDomain(anchor.attr("abs:href"))

        val img = element.selectFirst("div.poster img, div.image img, img")
        thumbnail_url = img?.let {
            it.attr("abs:data-src").ifEmpty {
                it.attr("abs:data-lazy-src").ifEmpty {
                    it.attr("abs:src")
                }
            }
        }
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(
        if (page == 1) "$baseUrl/generos/animacao/" else "$baseUrl/generos/animacao/page/$page/",
        headers,
    )

    override fun popularAnimeSelector(): String = "#archive-content article.item, div.items article.item"

    override fun popularAnimeNextPageSelector(): String = "div.pagination a.arrow_pag, div.pagination a:has(i#nextpagination), div.pagination span.current + a"

    override fun popularAnimeFromElement(element: Element): SAnime = animeFromElement(element)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(
        if (page == 1) "$baseUrl/series/" else "$baseUrl/series/page/$page/",
        headers,
    )

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = animeFromElement(element)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val searchUrl = if (page == 1) {
                "$baseUrl/?s=$encodedQuery"
            } else {
                "$baseUrl/page/$page/?s=$encodedQuery"
            }
            return GET(searchUrl, headers)
        }

        val categoryFilter = filters.firstInstanceOrNull<CategoryFilter>()
        val genreFilter = filters.firstInstanceOrNull<GenreFilter>()

        val filterPath = when {
            genreFilter != null && genreFilter.selected.isNotBlank() -> "generos/${genreFilter.selected}"
            categoryFilter != null && categoryFilter.selected.isNotBlank() -> categoryFilter.selected
            else -> "generos/animacao"
        }

        val url = if (page == 1) "$baseUrl/$filterPath/" else "$baseUrl/$filterPath/page/$page/"
        return GET(url, headers)
    }

    override fun searchAnimeSelector(): String = "div.search-page article, #archive-content article.item, div.items article.item"

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = animeFromElement(element)

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        val doc = getRealAnimeDoc(document)
        val sheader = doc.selectFirst("div.sheader")
        val titleElement = sheader?.selectFirst("div.data h1") ?: doc.selectFirst("h1")
        title = titleElement?.text()?.trim().orEmpty()

        val img = sheader?.selectFirst("div.poster img") ?: doc.selectFirst("div.poster img, div#info img")
        thumbnail_url = img?.let {
            it.attr("abs:src").ifEmpty {
                it.attr("abs:data-src").ifEmpty { it.attr("abs:data-lazy-src") }
            }
        }

        genre = doc.select("div.sgeneros a, div.sheader div.sgeneros a")
            .eachText()
            .distinct()
            .joinToString()

        val rawSynopsis = doc.selectFirst("div[itemprop=description] p, div.wp-content p, div#info .wp-content p")
            ?.text()
            ?.trim()
            .orEmpty()
        val cleanSynopsis = cleanSynopsis(rawSynopsis)

        val year = doc.selectFirst("div.sheader span.date, div.extra span.date")
            ?.text()
            ?.trim()
            ?.let { YEAR_REGEX.find(it)?.groupValues?.get(1) }

        val extraInfo = buildList {
            if (!year.isNullOrBlank()) add("Ano: $year")
        }

        description = buildString {
            if (cleanSynopsis.isNotBlank()) append(cleanSynopsis)
            if (extraInfo.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(extraInfo.joinToString("\n"))
            }
        }.trim()

        status = if (doc.location().contains("/filmes/")) {
            SAnime.COMPLETED
        } else {
            SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val pageUrl = response.request.url.toString()

        // 1. Movie handling
        if (pageUrl.contains("/filmes/")) {
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(pageUrl)
                    name = "Filme"
                    episode_number = 1F
                },
            )
        }

        // 2. Native DooPlay seasons fallback if present
        val nativeSeasons = document.select(seasonListSelector)
        if (nativeSeasons.isNotEmpty()) {
            return super.episodeListParse(response)
        }

        // 3. Series handling via painel-aso embed
        val iframeUrl = document.selectFirst("iframe[src*='painel-aso'], iframe[src*='embed'], div#info iframe, p iframe")
            ?.attr("abs:src")
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()

        val panelHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()
        val panelHtml = client.newCall(GET(iframeUrl, panelHeaders)).execute().use { it.body.string() }
        val panelDoc = Jsoup.parse(panelHtml, iframeUrl)

        val seasonMap = mutableMapOf<String, String>()
        panelDoc.select("ul.header-navigation li[data-season-id]").forEach { li ->
            val seasonId = li.attr("data-season-id")
            val seasonNum = li.attr("data-season-number").ifEmpty {
                NUMBER_REGEX.find(li.text())?.groupValues?.get(1) ?: "1"
            }
            seasonMap[seasonId] = seasonNum
        }

        val episodes = mutableListOf<SEpisode>()
        val cards = panelDoc.select("div.cards div.card")

        if (cards.isNotEmpty()) {
            for (card in cards) {
                val audioType = card.selectFirst("h2.card-title")?.text()?.trim().orEmpty()
                val audioLabel = when {
                    audioType.contains("Dublado", ignoreCase = true) -> "Dublado"
                    audioType.contains("Legendado", ignoreCase = true) -> "Legendado"
                    else -> audioType
                }

                val epElements = card.select("li[data-episode-id]")
                for (epEl in epElements) {
                    val episodeId = epEl.attr("data-episode-id")
                    if (episodeId.isBlank()) continue
                    val seasonId = epEl.attr("data-season-id")
                    val seasonNum = seasonMap[seasonId] ?: "1"
                    val epText = epEl.selectFirst("a")?.text()?.trim().orEmpty()
                    val epNum = NUMBER_REGEX.find(epText)?.groupValues?.get(1)?.toFloatOrNull() ?: 1F
                    val epTitle = epText.ifEmpty { "Episódio ${epNum.toInt()}" }

                    val nameParts = buildList {
                        add("Temp. $seasonNum")
                        add(epTitle)
                        if (audioLabel.isNotBlank()) add("($audioLabel)")
                    }

                    episodes.add(
                        SEpisode.create().apply {
                            name = nameParts.joinToString(" - ")
                            episode_number = epNum
                            url = "https://www.painel-aso.sbs/episodio/$episodeId"
                        },
                    )
                }
            }
        } else {
            val epElements = panelDoc.select("li[data-episode-id]")
            for (epEl in epElements) {
                val episodeId = epEl.attr("data-episode-id")
                if (episodeId.isBlank()) continue
                val seasonId = epEl.attr("data-season-id")
                val seasonNum = seasonMap[seasonId] ?: "1"
                val epText = epEl.selectFirst("a")?.text()?.trim().orEmpty()
                val epNum = NUMBER_REGEX.find(epText)?.groupValues?.get(1)?.toFloatOrNull() ?: 1F
                val epTitle = epText.ifEmpty { "Episódio ${epNum.toInt()}" }

                episodes.add(
                    SEpisode.create().apply {
                        name = "Temp. $seasonNum - $epTitle"
                        episode_number = epNum
                        url = "https://www.painel-aso.sbs/episodio/$episodeId"
                    },
                )
            }
        }

        return episodes.distinctBy { it.url }.reversed()
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        val fullUrl = if (episode.url.startsWith("http")) {
            episode.url
        } else if (episode.url.startsWith("/episodio/") || episode.url.startsWith("/filme/")) {
            "https://www.painel-aso.sbs" + episode.url
        } else {
            baseUrl + episode.url
        }
        return GET(fullUrl, headers.newBuilder().set("Referer", "$baseUrl/").build())
    }

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val request = videoListRequest(episode)
        val response = client.newCall(request).awaitSuccess()
        val requestUrl = response.request.url.toString()
        val panelDoc: Document = if (requestUrl.contains("startflix.biz")) {
            val startflixDoc = response.use { it.asJsoup() }
            val iframeUrl = startflixDoc.selectFirst("iframe[src*='painel-aso'], iframe[src*='filme'], iframe[src*='embed'], div#info iframe, p iframe")
                ?.attr("abs:src")
                ?: return emptyList()

            val panelHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()
            val html = client.newCall(GET(iframeUrl, panelHeaders)).awaitSuccess().use { it.body.string() }
            Jsoup.parse(html, iframeUrl)
        } else {
            response.use { it.asJsoup() }
        }

        val buttons = panelDoc.select("#players button[data-source], button[data-show-player=true][data-source], button[data-source]")
        val videos = mutableListOf<Video>()

        val sortedButtons = buttons.sortedByDescending { btn ->
            val src = btn.attr("data-source").lowercase()
            when {
                src.contains("byse") || src.contains("embedplay") || src.contains(".top/e/") -> 100
                src.contains(".m3u8") || src.contains(".mp4") -> 90
                src.contains("streamwish") || src.contains("wishembed") || src.contains("swish") -> 80
                src.contains("filemoon") || src.contains("moonplayer") -> 70
                src.contains("dood") -> 60
                else -> 10
            }
        }

        for (button in sortedButtons) {
            val rawSource = button.attr("data-source").trim()
            if (rawSource.isBlank()) continue

            val isFallbackWeb = !rawSource.contains("byse", true) &&
                !rawSource.contains("embedplay", true) &&
                !rawSource.contains(".top/e/", true) &&
                !rawSource.contains(".m3u8", true) &&
                !rawSource.contains(".mp4", true) &&
                !rawSource.contains("streamwish", true) &&
                !rawSource.contains("wishembed", true) &&
                !rawSource.contains("swish", true) &&
                !rawSource.contains("filemoon", true) &&
                !rawSource.contains("moonplayer", true) &&
                !rawSource.contains("dood", true)

            if (isFallbackWeb && videos.isNotEmpty()) {
                continue
            }

            val label = button.text().trim().replace(WHITESPACE_REGEX, " ")
            videos.addAll(extractVideosFromPlayer(rawSource, label))
        }

        if (videos.isEmpty()) {
            error("Nenhum servidor compatível com reprodução nativa disponível. Abra o episódio na WebView para assistir pelo player do site.")
        }
        return videos.distinctBy { it.videoUrl }.sortVideos()
    }

    private suspend fun extractVideosFromPlayer(sourceUrl: String, label: String): List<Video> = runCatching {
        when {
            // 1. Byse / EmbedPlay
            sourceUrl.contains("byse", ignoreCase = true) ||
                sourceUrl.contains("embedplay", ignoreCase = true) ||
                sourceUrl.contains(".top/e/", ignoreCase = true) -> {
                val byseVideos = byseExtractor.videosFromUrl(sourceUrl, label)
                if (byseVideos.isNotEmpty()) {
                    byseVideos
                } else {
                    webPlayerExtractor.videosFromUrl(sourceUrl, label)
                }
            }

            // 2. StreamWish
            sourceUrl.contains("streamwish", ignoreCase = true) ||
                sourceUrl.contains("wishembed", ignoreCase = true) ||
                sourceUrl.contains("swish", ignoreCase = true) -> {
                runCatching {
                    streamWishExtractor.videosFromUrl(sourceUrl, videoNameGen = { "$label - $it" })
                }.getOrElse { emptyList() }
            }

            // 3. DoodStream
            sourceUrl.contains("dood", ignoreCase = true) -> {
                runCatching {
                    doodExtractor.videosFromUrl(sourceUrl, quality = label)
                }.getOrElse { emptyList() }
            }

            // 4. Filemoon
            sourceUrl.contains("filemoon", ignoreCase = true) ||
                sourceUrl.contains("moonplayer", ignoreCase = true) -> {
                runCatching {
                    filemoonExtractor.videosFromUrl(sourceUrl, prefix = "$label - ")
                }.getOrElse { emptyList() }
            }

            // 5. Direct HLS
            sourceUrl.contains(".m3u8", ignoreCase = true) -> {
                runCatching {
                    playlistUtils.extractFromHls(
                        sourceUrl,
                        referer = "https://www.painel-aso.sbs/",
                        videoNameGen = { "$label - $it" },
                    )
                }.getOrElse { emptyList() }
            }

            // 6. Direct MP4
            sourceUrl.contains(".mp4", ignoreCase = true) -> {
                listOf(Video(sourceUrl, label, sourceUrl, headers))
            }

            // 7. Known incompatible hosts (Abyss/playembedapi relies on client-side ServiceWorker chunk assembly,
            // UPNS files deleted 404, VidSrc protected by Cloudflare Turnstile).
            sourceUrl.contains("playembedapi", ignoreCase = true) ||
                sourceUrl.contains("abyss", ignoreCase = true) ||
                sourceUrl.contains("upns", ignoreCase = true) ||
                sourceUrl.contains("vidsrc", ignoreCase = true) -> {
                emptyList()
            }

            // 8. General Web Player
            else -> {
                webPlayerExtractor.videosFromUrl(sourceUrl, label)
            }
        }
    }.getOrElse { emptyList() }

    // ============================== Filters ===============================

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Filtros de navegação (ignorados na busca por texto)"),
        CategoryFilter(),
        GenreFilter(),
    )

    private class CategoryFilter :
        AnimeFilter.Select<String>(
            "Tipo",
            arrayOf("Animação", "Séries", "Filmes"),
        ) {
        val selected: String
            get() = when (state) {
                1 -> "series"
                2 -> "filmes"
                else -> "generos/animacao"
            }
    }

    private class GenreFilter :
        AnimeFilter.Select<String>(
            "Gêneros",
            GENRES.map { it.first }.toTypedArray(),
        ) {
        val selected: String
            get() = GENRES[state].second

        companion object {
            val GENRES = listOf(
                "Todos" to "",
                "Ação" to "acao",
                "Action & Adventure" to "action-adventure",
                "Animação" to "animacao",
                "Aventura" to "aventura",
                "Cinema TV" to "cinema-tv",
                "Comédia" to "comedia",
                "Crime" to "crime",
                "Documentário" to "documentario",
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
                "News" to "news",
                "Reality" to "reality",
                "Romance" to "romance",
                "Sci-Fi & Fantasy" to "sci-fi-fantasy",
                "Soap" to "soap",
                "Talk" to "talk",
                "Terror" to "terror",
                "Thriller" to "thriller",
                "War & Politics" to "war-politics",
            )
        }
    }

    // ============================= Utilities ==============================

    private fun cleanSynopsis(text: String): String = text
        .replace(SEO_PREFIX_REGEX, "")
        .replace(STARTFLIX_NAME_REGEX, "")
        .trim()

    companion object {
        private val NUMBER_REGEX = Regex("(\\d+)")
        private val YEAR_REGEX = Regex("(\\d{4})")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val SEO_PREFIX_REGEX = Regex("^(?i)Assistir\\s+[^,:]+(?:Online|Startflix)[^,]*,?\\s*")
        private val STARTFLIX_NAME_REGEX = Regex("(?i)startflix")
    }
}
