package eu.kanade.tachiyomi.animeextension.pt.animesonlinecloud

import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animeextension.pt.animesonlinecloud.extractors.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimesOnlineCloud :
    DooPlay(
        "pt-BR",
        "Animes Online Cloud",
        "https://animesonline.cloud",
    ) {
    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/anime", headers)

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = "div.pagination > a.arrow_pag > i.fa-caret-right"

    override fun latestUpdatesParse(response: Response): AnimesPage {
        fetchGenresList()
        val document = response.useAsJsoup()
        val animes = document.select(latestUpdatesSelector()).mapNotNull(::latestAnimeFromElement)
        val hasNextPage = document.selectFirst(latestUpdatesNextPageSelector()) != null
        return AnimesPage(animes, hasNextPage)
    }

    private fun latestAnimeFromElement(element: Element): SAnime? {
        val episodeUrl = element.selectFirst("a[href]")?.attr("abs:href")?.takeIf(String::isNotBlank)
            ?: return null

        return runCatching {
            client.newCall(GET(episodeUrl, headers)).execute().use { episodeResponse ->
                if (!episodeResponse.isSuccessful) return@use null
                animeDetailsParse(episodeResponse.useAsJsoup()).apply {
                    element.selectFirst("img")?.attr("abs:src")
                        ?.takeIf(String::isNotBlank)
                        ?.let { thumbnail_url = it }
                }
            }
        }.getOrNull()
    }

    override fun getRealAnimeDoc(document: Document): Document {
        val parentUrl = document.selectFirst("div.pag_episodes a[href*='/anime/']")
            ?.attr("abs:href")
            ?.takeIf(String::isNotBlank)
            ?: document.location()
                .takeIf { "/episodio/" in it }
                ?.substringBeforeLast('/')
                ?.substringAfterLast('/')
                ?.substringBefore("-episodio-")
                ?.takeIf(String::isNotBlank)
                ?.let { "$baseUrl/anime/$it" }
            ?: return document

        return runCatching {
            client.newCall(GET(parentUrl, headers)).execute().use { response ->
                if (response.isSuccessful) response.useAsJsoup() else document
            }
        }.getOrElse { document }
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters

        val orderByFilter = filterList.find { it is OrderByFilter } as? OrderByFilter
        val orderFilter = filterList.find { it is OrderFilter } as? OrderFilter

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            filterList.firstOrNull { it is UriPartFilter && it.state != 0 }?.let {
                val filter = it as UriPartFilter
                addEncodedPathSegments(filter.toUriPart())
            }

            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }

            addPathSegment("")
            addQueryParameter("s", query)

            // order (optional)
            if (orderByFilter != null) addQueryParameter("orderby", orderByFilter.selected)
            if (orderFilter != null) addQueryParameter("order", orderFilter.selected)
        }.build()

        return GET(url.toString(), headers)
    }

    // =========================== Anime Details ============================
    override fun episodeListRequest(anime: SAnime): Request = GET(resolveAnimeUrl(anime), headers)

    private fun resolveAnimeUrl(anime: SAnime): String {
        val base = baseUrl.toHttpUrl()
        val rawUrl = anime.url.trim()
        val absoluteUrl = rawUrl.toHttpUrlOrNull()

        return when {
            absoluteUrl?.host == base.host -> absoluteUrl.toString()
            else -> base.resolve(rawUrl)?.toString() ?: base.toString()
        }
    }

    override val additionalInfoSelector = "div.wp-content"

    override fun Document.getDescription(): String = select("$additionalInfoSelector p")
        .first { !it.text().contains("Título Alternativo") }
        ?.let { it.text() + "\n" }
        ?: ""

    fun Document.getAlternativeTitle(): String = select("$additionalInfoSelector p")
        .first { it.text().contains("Título Alternativo") }
        ?.let { it.text() + "\n" }
        ?: ""

    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealAnimeDoc(document)
        val sheader = doc.selectFirst("div.sheader")!!
        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            sheader.selectFirst("div.poster > img")!!.let {
                thumbnail_url = it.getImageUrl()
                title = it.attr("alt").ifEmpty {
                    sheader.selectFirst("div.data > h1")!!.text()
                }.trim()
            }

            genre = sheader.select("div.data div.sgeneros > a")
                .eachText()
                .joinToString()

            // description = doc.getDescription()
            doc.selectFirst("div#info")?.let { info ->
                description = buildString {
                    append(doc.getDescription())
                    append(doc.getAlternativeTitle())
                    additionalInfoItems.forEach {
                        info.getInfo(it)?.let(::append)
                    }
                }
            }
        }
    }

    // The current site uses episode cards instead of the legacy DooPlay list.
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val episodes = document.select("div#seasons > div.se-c div.episodios-grid > div.episode-card")

        return episodes.mapNotNull { element ->
            val href = element.selectFirst("a[href]")?.attr("href")?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val title = element.attr("data-episode-title").trim()
            val number = element.attr("data-episode-number").toFloatOrNull()
                ?: EPISODE_NUMBER_REGEX.find(title)?.groupValues?.get(1)?.replace('-', '.')?.toFloatOrNull()

            SEpisode.create().apply {
                setUrlWithoutDomain(href)
                name = title.ifEmpty { element.selectFirst("h3.episode-title")?.text().orEmpty() }
                episode_number = number ?: 0F
            }
        }.reversed()
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = runCatching { response.useAsJsoup() }
            .onFailure { it.printStackTrace() }
            .getOrElse { return emptyList() }
        val players = document.select("section.animeq-player div.animeq-player__source")
        return players.parallelCatchingFlatMapBlocking { player ->
            runCatching { getPlayerVideos(player) }
                .onFailure { it.printStackTrace() }
                .getOrDefault(emptyList())
        }
    }

    private val bloggerExtractor by lazy { BloggerExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private suspend fun getPlayerVideos(player: Element): List<Video> {
        val sourceNumber = player.attr("data-animeq-source")
        val name = player.ownerDocument()
            ?.selectFirst("[data-animeq-switch='$sourceNumber']")
            ?.attr("data-source-name")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: player.selectFirst("[data-video-title], iframe[title]")
                ?.let { it.attr("data-video-title").ifEmpty { it.attr("title") } }
                ?.trim()
                ?.takeIf(String::isNotEmpty)
            ?: "Fonte $sourceNumber"

        val url = player.selectFirst("source[src]")?.attr("abs:src")
            ?.takeIf(String::isNotBlank)
            ?: player.selectFirst("[data-video-src]")?.attr("abs:data-video-src")
                ?.takeIf(String::isNotBlank)
            ?: player.selectFirst("iframe[src]")?.attr("abs:src")
                ?.takeIf(String::isNotBlank)
            ?: return emptyList()

        if (!url.isHttpUrl()) return emptyList()

        val directVideo = url.substringBefore('?').let { path ->
            path.endsWith(".mp4", ignoreCase = true) || path.endsWith(".m3u8", ignoreCase = true)
        }
        if (directVideo) return listOf(Video(url, name, url, videoHeaders))

        // Playmogo is currently returning 403 and UniversalExtractor waits for
        // its WebView timeout. Skip this known dead source immediately.
        if ("playmogo.com" in url) return emptyList()

        return runCatching {
            if ("blogger.com" in url) {
                bloggerExtractor.videosFromUrl(url, headers, name)
            } else {
                universalExtractor.videosFromUrl(url, headers, name)
            }
        }.onFailure { it.printStackTrace() }.getOrDefault(emptyList())
    }

    private val videoHeaders by lazy {
        headers.newBuilder()
            .set("Accept", "*/*")
            .set("Referer", baseUrl)
            .build()
    }

    private fun String.isHttpUrl(): Boolean = startsWith("https://") || startsWith("http://")

    // ============================== Filters ===============================
    @Volatile
    private var hasFetchedGenresArray = false

    override val genreFilterHeader = "Apenas um tipo de filtro por vez"
    override fun genresListRequest() = GET("$baseUrl/wp-json/wp/v2/genres?per_page=100&_fields[]=name&_fields[]=link")

    override fun getFilterList(): AnimeFilterList = if (hasFetchedGenresArray) {
        AnimeFilterList(
            AnimeFilter.Header(genreFilterHeader),
            AudioFilter(),
            FetchedGenresFilter(genresListMessage, genresArray),
            AnimeFilter.Separator(),
            OrderByFilter(),
            OrderFilter(),
        )
    } else if (fetchGenres) {
        AnimeFilterList(AnimeFilter.Header(genresMissingWarning))
    } else {
        AnimeFilterList()
    }

    @Synchronized
    override fun fetchGenresList() {
        if (hasFetchedGenresArray || !fetchGenres) return

        runCatching {
            client.newCall(genresListRequest())
                .execute()
                .parseAs<List<GenreDto>>()
                .let(::genresListParse)
                .let { items ->
                    if (items.isNotEmpty()) {
                        genresArray = items
                        hasFetchedGenresArray = true
                    }
                }
        }.onFailure { it.printStackTrace() }
    }

    fun genresListParse(genres: List<GenreDto>): Array<Pair<String, String>> {
        val items = genres.map {
            val name = it.name
            val value = it.link.substringAfter("$baseUrl/").removeSuffix("/")
            Pair(name, value)
        }.toTypedArray()

        return if (items.isEmpty()) {
            items
        } else {
            arrayOf(Pair(selectFilterText, "")) + items
        }
    }

    private class AudioFilter :
        UriPartFilter(
            "Áudio",
            arrayOf(
                Pair("Todos", ""),
                Pair("Dublado", "tipo/dublado"),
                Pair("Legendado", "tipo/legendado"),
            ),
        )

    private abstract class SelectFilter(
        name: String,
        private val options: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        val selected
            get() = options[state].second
    }

    private class OrderByFilter :
        SelectFilter(
            "Ordenar Por",
            arrayOf(
                Pair("Data de Criação", "date"),
                Pair("Data de Modificação", "modified"),
                Pair("Título", "title"),
            ),
        )

    private class OrderFilter :
        SelectFilter(
            "Ordem",
            arrayOf(
                Pair("Descendente", "desc"),
                Pair("Ascendente", "asc"),
            ),
        )

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!

        return sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending {
                    REGEX_QUALITY.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                },
        )
    }

    override fun Element.getImageUrl(): String {
        val url = when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }

        // Remove the "-<width>x<height>" suffix before the file extension:
        // ex: ".../file-200x300.jpg" -> ".../file.jpg"
        return url.replace(REGEX_IMAGE_SIZE_SUFFIX, "")
    }

    @Serializable
    data class GenreDto(
        val name: String,
        val link: String,
    )

    companion object {
        private val EPISODE_NUMBER_REGEX = Regex("(?:Episódio|Ep\\.?)\\s+([0-9]+(?:[.-][0-9]+)?)", RegexOption.IGNORE_CASE)
        private val REGEX_QUALITY by lazy { Regex("""(\d+)p""") }
        private val REGEX_IMAGE_SIZE_SUFFIX by lazy {
            Regex("""-\d+x\d+(?=\.[A-Za-z0-9]+$)""")
        }
    }
}
