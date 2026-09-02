package eu.kanade.tachiyomi.animeextension.pt.newreiwa

import android.util.Log
import aniyomi.lib.googledriveepisodes.GoogleDriveEpisodes
import aniyomi.lib.googledriveextractor.GoogleDriveExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.AnimeHttpLegacySource
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale

class NewReiwa : AnimeHttpLegacySource() {
    override val name = "New Reiwa"
    override val baseUrl = "https://newreiwa.com"
    override val lang = "pt-BR"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/kamen-rider/" + if (page > 1) "page/$page/" else "", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = AnimesPage(parseDirectory(response.use { it.asJsoup() }), false)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/" + if (page > 1) "page/$page/" else "", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.use { it.asJsoup() }
        val result = linkedMapOf<String, SAnime>()
        document.select("article.blog-entry").forEach { card ->
            val postUrl = card.selectFirst("h2.entry-title a")?.absUrl("href") ?: return@forEach
            val parentUrl = runCatching {
                client.newCall(GET(postUrl, headers)).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.asJsoup()
                }
                    ?.select("article[id^=post-] > .entry-content a[href]")
                    ?.firstOrNull { a -> a.text().contains("PÁGINA DE DOWNLOAD", true) && isSeriesUrl(a.absUrl("href")) }
                    ?.absUrl("href")
            }.getOrNull() ?: return@forEach
            val anime = runCatching {
                client.newCall(GET(parentUrl, headers)).execute().use { response ->
                    if (!response.isSuccessful) error("parent HTTP ${response.code}")
                    animeFromDocument(response.asJsoup(), parentUrl)
                }
            }.getOrElse {
                SAnime.create().apply {
                    title = parentUrl.substringAfterLast('/')
                    setUrlWithoutDomain(parentUrl)
                }
            }
            if (!result.containsKey(anime.url)) {
                result[anime.url] = anime
                debugAnime("LATEST", anime)
            }
        }
        val hasNext = document.selectFirst("a.next, .next.page-numbers") != null
        return AnimesPage(result.values.toList(), hasNext)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}&paged=$page", headers)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.use { it.asJsoup() }
        val entries = document.select("h2.search-entry-title a").mapNotNull { link ->
            val url = link.absUrl("href")
            if (!isSeriesUrl(url)) return@mapNotNull null
            SAnime.create().apply {
                title = link.text()
                setUrlWithoutDomain(url)
                thumbnail_url = link.closest("article")?.selectFirst("img")?.let(::imageUrl)
                    ?: runCatching {
                        client.newCall(GET(url, headers)).execute().use { detail ->
                            if (detail.isSuccessful) imageFromDocument(detail.asJsoup()) else null
                        }
                    }.getOrNull()
            }.also { debugAnime("SEARCH", it) }
        }
        return AnimesPage(entries, document.selectFirst("a.next, .next.page-numbers") != null)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.use { it.asJsoup() }
        return SAnime.create().apply {
            title = document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.selectFirst("h1, h2.page-title")?.text()
                ?: document.title().substringBefore(" –")
            setUrlWithoutDomain(document.location())
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            description = document.selectFirst("meta[property=og:description]")?.attr("content")
            genre = document.select("meta[property='article:section']").eachAttr("content").distinct().joinToString()
            status = when {
                document.text().contains("EM LANÇAMENTO", true) -> SAnime.ONGOING
                document.text().contains("COMPLETO", true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            initialized = true
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.use { it.asJsoup() }
        val episodes = mutableListOf<SEpisode>()
        document.select(".entry a[href]").forEach { link ->
            val url = link.absUrl("href")
            if (!url.startsWith("$baseUrl/") || !isEpisodeLink(url, link.text())) return@forEach
            val context = link.parent()?.text().orEmpty()
            val number = Regex("(?i)epis[oó]dio\\s*([0-9]+)").find(context)?.groupValues?.get(1)?.toFloatOrNull() ?: 0F
            val quality = link.text().trim().ifEmpty { "Drive" }
            episodes += SEpisode.create().apply {
                setUrlWithoutDomain(url)
                name = "Episódio ${number.toInt().toString().padStart(2, '0')} - $quality"
                episode_number = number
            }
        }
        return episodes.distinctBy { it.url }.sortedByDescending { it.episode_number }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val resolved = client.newCall(GET(baseUrl + episode.url, headers)).awaitSuccess().use { it.request.url.toString() }
        FOLDER_ID.find(resolved)?.groupValues?.get(1)?.let { folderId ->
            val file = runCatching {
                GoogleDriveEpisodes(client, headers).getEpisodesFromFolder(folderId, "", 1, false)
                    .minByOrNull { kotlin.math.abs(it.episode_number - episode.episode_number) }
            }.getOrNull()
            val fileId = file?.url?.substringAfter("?id=") ?: return emptyList()
            return GoogleDriveExtractor(client, headers).videosFromUrl(fileId, videoName = "New Reiwa - Google Drive")
        }
        val id = DRIVE_ID.find(resolved)?.value ?: DRIVE_ID.find(episode.url)?.value ?: return emptyList()
        return GoogleDriveExtractor(client, headers).videosFromUrl(
            id,
            videoName = "New Reiwa - Google Drive",
        )
    }

    private fun parseDirectory(document: Document): List<SAnime> = document.select("article.single-page-article > .entry a[href]")
        .mapNotNull { link ->
            val url = link.absUrl("href")
            if (!isSeriesUrl(url)) return@mapNotNull null
            animeFromElement(link)
        }.distinctBy { it.url }

    private fun animeFromElement(element: Element): SAnime = SAnime.create().apply {
        title = sequenceOf(
            element.closest("figure")?.selectFirst("figcaption")?.text(),
            element.attr("title"),
            element.closest("[data-title]")?.attr("data-title"),
            element.selectFirst("img")?.attr("alt"),
            element.text(),
        ).mapNotNull { it?.trim()?.takeIf { value -> value.isNotEmpty() && !value.equals("null", true) } }
            .firstOrNull().orEmpty()
        setUrlWithoutDomain(element.absUrl("href"))
        thumbnail_url = element.selectFirst("img")?.let { image -> image.absUrl("data-lazy-src").ifEmpty { image.absUrl("src") } }
    }

    private fun isSeriesUrl(url: String): Boolean {
        val path = runCatching { url.substringAfter("$baseUrl/").substringBefore('?').trim('/') }.getOrDefault("")
        return url.startsWith("$baseUrl/") && path.isNotEmpty() && !path.startsWith("wp-") &&
            !path.contains("/20") && !path.contains("feed") && !path.contains("#") &&
            !path.matches(Regex("(?i)(series|anime|dorama|kamen-rider|super-sentai|ultraman|garo|metal-hero|outros-herois|filmes|j-drama-filmes|anime-filmes|mvs|pvs|mangas|chou-eiyuu-sai|informacoes|historia|contato|qc-publico)"))
    }

    private fun isEpisodeLink(url: String, label: String): Boolean = label.contains("drive", true) ||
        url.substringAfterLast('/').matches(Regex("(?i).*(sd|hd|fhd|gdrive|drive|sdv2|fhdv2)$"))

    companion object {
        private val DRIVE_ID = Regex("[\\w-]{25,}")
        private val FOLDER_ID = Regex("/drive/folders/([\\w-]+)")
    }

    private fun animeFromDocument(document: Document, url: String): SAnime = SAnime.create().apply {
        title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.selectFirst("h1, h2.page-title")?.text()
            ?: url.substringAfterLast('/').replace('-', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
        setUrlWithoutDomain(url)
        thumbnail_url = imageFromDocument(document)
        description = document.selectFirst("meta[property=og:description]")?.attr("content")
    }

    private fun imageUrl(image: Element): String? = sequenceOf(
        image.absUrl("data-lazy-src"),
        image.absUrl("data-src"),
        image.absUrl("src"),
    ).map { url ->
        when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://imgur.com/") -> url.replace("http://imgur.com/", "https://i.imgur.com/")
            url.startsWith("https://imgur.com/") -> url.replace("https://imgur.com/", "https://i.imgur.com/")
            else -> url
        }
    }.firstOrNull { url ->
        url.startsWith("http") && !url.startsWith("data:") &&
            !url.contains("/avatar/", true) && !url.contains("logotipo", true) && !url.contains("cropped-logo", true)
    }

    private fun imageFromDocument(document: Document): String? = document
        .select("article.single-page-article .entry img, article[id^=post-] .entry-content img")
        .asSequence()
        .mapNotNull(::imageUrl)
        .firstOrNull()

    private fun debugAnime(type: String, anime: SAnime) {
        Log.d("NEWREIWA_THUMB_DEBUG", "TYPE=$type TITLE=${anime.title} ANIME_URL=${anime.url} THUMBNAIL_FINAL=${anime.thumbnail_url}")
    }
}
