package eu.kanade.tachiyomi.animeextension.pt.redecanais

import android.app.Application
import android.content.SharedPreferences
import android.webkit.WebSettings
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.redecanais.detailsproxy.DetailsProxy
import eu.kanade.tachiyomi.animeextension.pt.redecanais.htmlproxy.HtmlProxy
import eu.kanade.tachiyomi.animeextension.pt.redecanais.htmlproxy.ImageProxy
import eu.kanade.tachiyomi.animeextension.pt.redecanais.htmlproxy.SearchMapCache
import eu.kanade.tachiyomi.animeextension.pt.redecanais.htmlproxy.toRedeCanaisHost
import eu.kanade.tachiyomi.animeextension.pt.redecanais.lib.StreamContext
import eu.kanade.tachiyomi.animeextension.pt.redecanais.lib.StreamProxy
import eu.kanade.tachiyomi.animeextension.pt.redecanais.searchthumbproxy.SearchThumbProxy
import eu.kanade.tachiyomi.animeextension.pt.redecanais.videolist.PlayerApiSniffer
import eu.kanade.tachiyomi.animeextension.pt.redecanais.videolist.VideoListBootstrapInterceptor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import uy.kohesive.injekt.injectLazy
import java.text.Normalizer

class RedeCanais :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "Rede Canais"

    override val baseUrl get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!

    override val lang = "pt-BR"

    override val supportsLatest = true

    private val context: Application by injectLazy()
    private val webViewUserAgent: String by lazy { WebSettings.getDefaultUserAgent(context) }
    private val baseHost by lazy { baseUrl.toHttpUrl().host }

    @Volatile
    private var searchEntriesCache: List<SearchEntry>? = null
    private val searchEntriesMutex = Mutex()
    private val playerApiSniffer by lazy { PlayerApiSniffer(baseUrl, ::browserUserAgent) }
    private val videoProxy by lazy { StreamProxy(network.client) }
    private val preferences: SharedPreferences by getPreferencesLazy()
    private val audioOptionsCache = mutableMapOf<String, List<AudioOption>>()

    override val client by lazy {
        network.client.newBuilder()
            .addInterceptor(VideoListBootstrapInterceptor())
            .addInterceptor(DetailsProxy(baseUrl, ::browserUserAgent))
            .addInterceptor(HtmlProxy(baseUrl, ::browserUserAgent))
            .addInterceptor(ImageProxy(baseUrl))
            .addInterceptor(SearchThumbProxy(baseUrl, ::browserUserAgent))
            .build()
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", browserUserAgent())
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .set("Accept-Language", ACCEPT_LANGUAGE)
        .set("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(listUrl("topvideos.html", page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseListing(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(listUrl("newvideos.html", page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseListing(response)

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val normalizedQuery = query.normalizeForSearch()
        if (normalizedQuery.isEmpty()) return AnimesPage(emptyList(), false)

        val entries = searchEntries()
            .filter { entry ->
                when (normalizedQuery) {
                    "mapa completo" -> entry.origin == "final_mapa.txt"
                    "mapa filmes completo" -> entry.origin == "final_mapafilmes.txt"
                    else -> entry.normalizedTitle.contains(normalizedQuery)
                }
            }

        val fromIndex = (page - 1) * SEARCH_PAGE_SIZE
        if (fromIndex >= entries.size) return AnimesPage(emptyList(), false)

        val pageEntries = entries.drop(fromIndex).take(SEARCH_PAGE_SIZE)
        return AnimesPage(
            pageEntries.map { entry ->
                SAnime.create().apply {
                    title = entry.title
                    thumbnail_url = searchThumbUrl(entry.url)
                    setUrlWithoutDomain(entry.url)
                }
            },
            fromIndex + SEARCH_PAGE_SIZE < entries.size,
        )
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/search.php".toHttpUrl().newBuilder()
            .addQueryParameter("keywords", query)
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseListing(response)

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(anime.absoluteAnimeUrl(), headers)

    override fun animeDetailsParse(response: Response): SAnime = SAnime.create().apply {
        val document = response.asJsoup()
        title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.selectFirst("h1")?.text()
            ?: document.title()
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("abs:content")?.toRedeCanaisHost(baseHost)
        description = document.selectFirst("meta[name=description]")?.attr("content")
        setUrlWithoutDomain(document.location())
        initialized = true
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val containers = document.select(DESCRIPTION_SELECTOR)
        val episodes = linkedMapOf<String, EpisodeEntry>()
        var season = ""
        var episode = ""
        var episodeTitle = ""

        fun addEpisode(link: Element) {
            val label = link.text().cleanEpisodeText()
            val normalizedLabel = label.normalizeForSearch()
            if (episode.isEmpty() || (normalizedLabel != "assistir" && !EPISODE_AUDIO_REGEX.matches(label))) return

            val href = link.absUrl("href").toRedeCanaisHost(baseHost).takeIf { it.isNotEmpty() } ?: return
            val audio = if (EPISODE_AUDIO_REGEX.matches(label)) label.audioLabel() else href.audioLabel(episodeTitle)

            val key = "$season|$episode"
            val entry = episodes.getOrPut(key) {
                EpisodeEntry(
                    season = season,
                    episode = episode,
                    episodeTitle = episodeTitle,
                    episodeNumber = episode.entryNumberOr(0F),
                )
            }
            entry.episodeTitle = entry.episodeTitle.ifEmpty { episodeTitle }
            entry.links[audio] = href
        }

        fun visit(node: Node) {
            when (node) {
                is TextNode -> {
                    val text = node.text().cleanEpisodeText()
                    val normalized = text.normalizeForSearch()
                    when {
                        text.isEmpty() -> Unit
                        LIST_ENTRY_REGEX.containsMatchIn(normalized) -> {
                            episode = text
                            episodeTitle = ""
                        }
                        episode.isNotEmpty() -> episodeTitle = text
                    }
                }
                is Element -> when (node.tagName()) {
                    "a" -> addEpisode(node)
                    "strong", "span" -> {
                        val text = node.text().cleanEpisodeText()
                        val normalized = text.normalizeForSearch()
                        when {
                            "temporada" in normalized || "minisserie" in normalized -> season = text
                            LIST_ENTRY_REGEX.containsMatchIn(normalized) ||
                                text.contains("Episódio", ignoreCase = true) -> {
                                episode = text
                                episodeTitle = ""
                            }
                            else -> node.childNodes().forEach(::visit)
                        }
                    }
                    else -> node.childNodes().forEach(::visit)
                }
            }
        }

        containers.forEach { container -> container.childNodes().forEach(::visit) }
        return episodes.values.mapNotNull { it.toSEpisode() }.ifEmpty {
            if (containers.any { it.hasListEntries() }) emptyList() else document.singleEpisodeOrEmpty(response)
        }
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request = GET(
        episode.absoluteEpisodeUrl(),
        headers.newBuilder()
            .set(VideoListBootstrapInterceptor.HEADER, "1")
            .build(),
    )

    override fun videoListParse(response: Response): List<Video> {
        val requestUrl = response.originalRequestUrl()
        val options = requestUrl.audioOptions()
        val iframeUrl = response.playerIframeUrl()
        val responsePageUrl = requestUrl.cleanAudioUrl()
        val iframeUrls = mutableMapOf(responsePageUrl to iframeUrl)
        val snifferInputs = options.map { option ->
            val optionPageUrl = option.url.cleanAudioUrl()
            PlayerApiSniffer.Input(
                parentUrl = option.url,
                iframeUrl = iframeUrls.getOrPut(optionPageUrl) {
                    runCatching {
                        client.newCall(GET(optionPageUrl, headers)).execute().use { it.playerIframeUrl() }
                    }.getOrNull()
                },
            )
        }
        val videos = playerApiSniffer.sniffAll(snifferInputs)

        return options.mapNotNull { option ->
            val video = videos[option.url] ?: return@mapNotNull null
            val streamUrl = videoProxy.proxiedUrl(
                StreamContext(
                    url = video.url,
                    userAgent = video.userAgent,
                    referer = video.referer,
                    origin = video.referer.origin(),
                    cookie = video.cookie.takeIf { it.isNotBlank() },
                ),
            )
            Video(
                video.url,
                "RedeCanais - ${option.label}",
                streamUrl,
                Headers.headersOf("User-Agent", browserUserAgent()),
            )
        }
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = PREF_DOMAIN_TITLE
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = getDomainPrefSummary()

            setOnPreferenceChangeListener { _, newValue ->
                runCatching {
                    val value = (newValue as String).trim().ifBlank { PREF_DOMAIN_DEFAULT }
                    preferences.edit().putString(key, value).commit().also {
                        summary = getDomainPrefSummary()
                    }
                }.getOrDefault(false)
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREFERRED_AUDIO_KEY
            title = PREFERRED_AUDIO_TITLE
            entries = PREFERRED_AUDIO_VALUES
            entryValues = PREFERRED_AUDIO_VALUES
            setDefaultValue(PREFERRED_AUDIO_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================

    private fun browserUserAgent(): String = webViewUserAgent

    private fun SAnime.absoluteAnimeUrl(): String = when {
        url.startsWith("http", ignoreCase = true) -> url.toRedeCanaisHost(baseHost)
        url.startsWith("/") -> "$baseUrl$url"
        else -> "$baseUrl/$url"
    }

    private fun SEpisode.absoluteEpisodeUrl(): String = when {
        url.startsWith("http", ignoreCase = true) -> url.toRedeCanaisHost(baseHost)
        url.startsWith("/") -> "$baseUrl$url"
        else -> "$baseUrl/$url"
    }

    private fun EpisodeEntry.toSEpisode(): SEpisode? {
        val primaryAudio = preferredAudio().takeIf { it in links } ?: links.keys.firstOrNull() ?: return null
        val primaryUrl = links[primaryAudio] ?: return null
        val name = listOf(season, episode, episodeTitle, primaryAudio)
            .filter(String::isNotEmpty)
            .joinToString(" - ")
        cacheAudioOptions(links)

        return SEpisode.create().apply {
            this.name = name
            episode_number = episodeNumber
            setUrlWithoutDomain(primaryUrl.withAudioOptions(links))
        }
    }

    private fun String.withAudioOptions(links: Map<String, String>): String {
        val url = toHttpUrlOrNull() ?: return this
        val builder = url.newBuilder()
            .removeAllQueryParameters(DUBBED_AUDIO_PARAM)
            .removeAllQueryParameters(SUBBED_AUDIO_PARAM)

        links[DUBBED_AUDIO]?.let { builder.setQueryParameter(DUBBED_AUDIO_PARAM, it) }
        links[SUBBED_AUDIO]?.let { builder.setQueryParameter(SUBBED_AUDIO_PARAM, it) }

        return builder.build().toString()
    }

    private fun String.audioOptions(): List<AudioOption> {
        val url = toHttpUrlOrNull() ?: return listOf(AudioOption("RedeCanais", this))
        val cleanUrl = url.newBuilder()
            .removeAllQueryParameters(DUBBED_AUDIO_PARAM)
            .removeAllQueryParameters(SUBBED_AUDIO_PARAM)
            .build()
            .toString()

        val options = listOfNotNull(
            url.queryParameter(DUBBED_AUDIO_PARAM)?.let { AudioOption(DUBBED_AUDIO, it) },
            url.queryParameter(SUBBED_AUDIO_PARAM)?.let { AudioOption(SUBBED_AUDIO, it) },
        ).distinctBy { it.url }

        return options.ifEmpty { cachedAudioOptions(cleanUrl) ?: listOf(AudioOption("RedeCanais", cleanUrl)) }
            .preferredFirst()
    }

    @Synchronized
    private fun cacheAudioOptions(links: Map<String, String>) {
        val options = links.map { (label, url) -> AudioOption(label, url) }
        options.forEach { option -> audioOptionsCache[option.url.cleanAudioUrl()] = options }
    }

    @Synchronized
    private fun cachedAudioOptions(url: String): List<AudioOption>? = audioOptionsCache[url.cleanAudioUrl()]

    private fun List<AudioOption>.preferredFirst(): List<AudioOption> {
        val preferredAudio = preferredAudio()
        return sortedBy { if (it.label == preferredAudio) 0 else 1 }
    }

    private fun preferredAudio(): String = preferences.getString(PREFERRED_AUDIO_KEY, PREFERRED_AUDIO_DEFAULT) ?: PREFERRED_AUDIO_DEFAULT

    private fun String.cleanAudioUrl(): String {
        val url = toHttpUrlOrNull() ?: return this
        return url.newBuilder()
            .removeAllQueryParameters(DUBBED_AUDIO_PARAM)
            .removeAllQueryParameters(SUBBED_AUDIO_PARAM)
            .build()
            .toString()
    }

    private fun getDomainPrefSummary(): String = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!

    private fun String.origin(): String {
        val url = toHttpUrlOrNull() ?: return baseUrl
        val port = when {
            url.scheme == "http" && url.port == 80 -> ""
            url.scheme == "https" && url.port == 443 -> ""
            else -> ":${url.port}"
        }
        return "${url.scheme}://${url.host}$port"
    }

    private fun Response.originalRequestUrl(): String {
        var current = this
        while (current.priorResponse != null) {
            current = current.priorResponse!!
        }
        return current.request.url.toString()
    }

    private fun Response.playerIframeUrl(): String? {
        val document = Jsoup.parse(peekBody(PLAYER_HTML_PEEK_BYTES).string(), request.url.toString())
        val iframe = document.selectFirst("#video-wrapper iframe[src], iframe[name=Player][src], iframe[src*=/player3/server.php]")
            ?: return null
        return iframe.absUrl("src").takeIf { it.isNotBlank() }?.toRedeCanaisHost(baseHost)
    }

    private fun parseListing(response: Response): AnimesPage {
        val document = response.asJsoup()
        val listingItems = document.select("ul.pm-ul-browse-videos > li")
        return AnimesPage(listingItems.mapNotNull { it.toSAnime() }, document.hasNextPage())
    }

    private suspend fun searchEntries(): List<SearchEntry> = searchEntriesCache ?: searchEntriesMutex.withLock {
        searchEntriesCache ?: run {
            if (!SearchMapCache.isReady()) {
                client.newCall(GET("$baseUrl/search.php", headers))
                    .awaitSuccess()
                    .use { }
            }

            SearchMapCache.snapshot()
                .flatMap { (file, text) -> parseSearchMap(text, file) }
                .distinctBy { it.url }
                .sortedBy { it.normalizedTitle }
                .also { if (SearchMapCache.isReady()) searchEntriesCache = it }
        }
    }

    private fun Element.toSAnime(): SAnime? {
        val anchor = selectFirst(".caption h3 a[href]") ?: selectFirst(".pm-video-thumb > a[href]")
        val href = anchor?.absUrl("href")?.takeIf { it.isNotEmpty() }?.toRedeCanaisHost(baseHost) ?: return null
        val title = anchor.attr("title").ifEmpty { anchor.text() }.takeIf { it.isNotEmpty() } ?: return null
        val image = selectFirst(".pm-video-thumb img")

        return SAnime.create().apply {
            this.title = title
            thumbnail_url = image?.run { attr("abs:data-echo").ifEmpty { attr("abs:src") }.toRedeCanaisHost(baseHost) }
            setUrlWithoutDomain(href)
        }
    }

    private fun org.jsoup.nodes.Document.hasNextPage(): Boolean = select("ul.pagination a[href*=page=]").any { link ->
        link.text() == "\u00bb" ||
            (link.text().toIntOrNull() != null && link.parent()?.hasClass("active") == false)
    }

    private fun listUrl(path: String, page: Int): String = when (page) {
        1 -> "$baseUrl/$path"
        else -> "$baseUrl/$path?&page=$page"
    }

    private fun searchThumbUrl(detailsUrl: String): String = "$baseUrl${SearchThumbProxy.SEARCH_THUMB_PATH}".toHttpUrl().newBuilder()
        .addQueryParameter(SearchThumbProxy.SEARCH_THUMB_URL_PARAM, detailsUrl)
        .build()
        .toString()

    private fun parseSearchMap(text: String, origin: String): List<SearchEntry> = text.lineSequence()
        .mapNotNull { line ->
            val match = SEARCH_LINE_REGEX.find(line) ?: return@mapNotNull null
            val title = Jsoup.parseBodyFragment(
                match.groupValues[1]
                    .replace(BOLD_TAG_REGEX, "")
                    .replace(TRAILING_DASH_REGEX, "")
                    .trim(),
            ).text()
            val url = match.groupValues[2].trim().toRedeCanaisHost(baseHost)
            if (title.isEmpty() || url.isEmpty()) return@mapNotNull null

            SearchEntry(
                title = title,
                normalizedTitle = title.normalizeForSearch(),
                url = url,
                origin = origin,
            )
        }
        .toList()

    private fun String.normalizeForSearch(): String {
        val normalized = Normalizer.normalize(lowercase(), Normalizer.Form.NFD)
        return COMBINING_MARKS_REGEX.replace(normalized, "")
    }

    private fun String.cleanEpisodeText(): String = replace(WHITESPACE_REGEX, " ")
        .trim()
        .trim('-', '/', ' ')

    private fun Document.singleEpisodeOrEmpty(response: Response): List<SEpisode> {
        if (select(DESCRIPTION_SELECTOR).any { it.hasListEntries() } || !hasDirectPlayer()) return emptyList()

        val title = selectFirst("h1[itemprop=name]")?.text()
            ?: selectFirst("meta[property=og:title]")?.attr("content")
            ?: title()
        val url = location().ifEmpty { response.request.url.toString() }.toRedeCanaisHost(baseHost)
        val audio = url.audioLabel(title)
        val cleanTitle = title.cleanEpisodeText()

        return listOf(
            SEpisode.create().apply {
                name = if (cleanTitle.contains(audio, ignoreCase = true)) cleanTitle else "$cleanTitle - $audio"
                scanlator = audio
                episode_number = cleanTitle.entryNumberOr(1F)
                setUrlWithoutDomain(url)
            },
        )
    }

    private fun Element.hasListEntries(): Boolean = LIST_ENTRY_REGEX.containsMatchIn(text().normalizeForSearch()) && selectFirst("a[href]") != null

    private fun String.entryNumberOr(default: Float): Float = LIST_ENTRY_REGEX.find(normalizeForSearch())?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: default

    private fun Document.hasDirectPlayer(): Boolean = selectFirst("iframe[src*=/player3/]") != null

    private fun String.audioLabel(title: String = ""): String = if (contains("legendado", ignoreCase = true) || title.contains("Legendado", ignoreCase = true)) SUBBED_AUDIO else DUBBED_AUDIO

    private class EpisodeEntry(
        val season: String,
        val episode: String,
        var episodeTitle: String,
        val episodeNumber: Float,
        val links: LinkedHashMap<String, String> = linkedMapOf(),
    )

    private data class AudioOption(
        val label: String,
        val url: String,
    )

    private data class SearchEntry(
        val title: String,
        val normalizedTitle: String,
        val url: String,
        val origin: String,
    )

    companion object {
        private const val SEARCH_PAGE_SIZE = 40
        private const val DUBBED_AUDIO = "Dublado"
        private const val SUBBED_AUDIO = "Legendado"
        private const val PREFERRED_AUDIO_KEY = "preferred_audio"
        private const val PREFERRED_AUDIO_TITLE = "Áudio preferido"
        private const val PREFERRED_AUDIO_DEFAULT = SUBBED_AUDIO
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_TITLE = "Domínio atual (requer reinicialização da app)"
        private const val PREF_DOMAIN_DEFAULT = "https://redecanais.plus"
        private const val PLAYER_HTML_PEEK_BYTES = 2L * 1024L * 1024L
        private const val DUBBED_AUDIO_PARAM = "rc_dublado"
        private const val SUBBED_AUDIO_PARAM = "rc_legendado"
        private const val ACCEPT_LANGUAGE = "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"
        private const val DESCRIPTION_SELECTOR = "div[itemprop=description], div.pm-category-description"

        private val SEARCH_LINE_REGEX = Regex("^(.*?)<a href=\"(.*?)\"", RegexOption.IGNORE_CASE)
        private val BOLD_TAG_REGEX = Regex("""</?b[^>]*>""", RegexOption.IGNORE_CASE)
        private val TRAILING_DASH_REGEX = Regex("""-\s*$""")
        private val COMBINING_MARKS_REGEX = Regex("""\p{Mn}+""")
        private val WHITESPACE_REGEX = Regex("""\s+""")
        private val EPISODE_AUDIO_REGEX = Regex("""Dublado|Legendado""", RegexOption.IGNORE_CASE)
        private val LIST_ENTRY_REGEX = Regex("""(?:episodio|filme)\s+(\d+)""")
        private val PREFERRED_AUDIO_VALUES = arrayOf(DUBBED_AUDIO, SUBBED_AUDIO)
    }
}
