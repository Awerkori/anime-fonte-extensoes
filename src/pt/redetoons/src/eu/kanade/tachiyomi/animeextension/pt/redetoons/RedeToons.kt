package eu.kanade.tachiyomi.animeextension.pt.redetoons

import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class RedeToons :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    override val name = "RedeToons"
    override val baseUrl: String
        get() = resolveBaseUrl(preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT))
    override val lang = "pt-BR"
    override val supportsLatest = true
    private val preferences by getPreferencesLazy()

    // Pages live under a locale prefix ("/br/..."); the JSON API lives at the root.
    private val siteUrl get() = baseUrl.removeSuffix("/br")
    private val localeUrl get() = "$siteUrl/br"

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // The site answers JSON on the API paths when asked for it.
    private val jsonHeaders get() = headersBuilder().set("Accept", "application/json").build()

    // ============================== Popular / Latest ==============================
    override fun popularAnimeRequest(page: Int): Request = GET(browseUrl("trending", page), headers)
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override suspend fun getPopularAnime(page: Int): AnimesPage = browsePage("trending", page, selectedContentCategories())
    override fun latestUpdatesRequest(page: Int): Request = GET(browseUrl("latest", page), headers)
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override suspend fun getLatestUpdates(page: Int): AnimesPage = browsePage("latest", page, selectedContentCategories())

    private fun browseUrl(slider: String, page: Int): HttpUrl = "$localeUrl/browse/$slider".toHttpUrl().newBuilder()
        .addQueryParameter("page", page.toString())
        .build()

    private suspend fun browsePage(slider: String, page: Int, categories: Set<ContentCategory>): AnimesPage {
        val titles = browseTitles(client.get(browseUrl(slider, page), headers).asJsoup())
        return AnimesPage(titles.filterBy(categories).map { it.toSAnime() }, hasNextBrowsePage(titles.size))
    }

    // ============================== Search ==============================
    private fun searchUrl(page: Int, query: String): HttpUrl = "$siteUrl/api/search".toHttpUrl().newBuilder()
        .addQueryParameter("q", query.trim())
        .addQueryParameter("page", page.toString())
        .build()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET(searchUrl(page, query), jsonHeaders)

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val allowed = allowedCategories(filters)
        if (query.isBlank()) return browsePage("latest", page, allowed)

        val result = client.get(searchUrl(page, query), jsonHeaders).parseAs<SearchPage>()
        return AnimesPage(result.results.filterBy(allowed).map { it.toSAnime() }, result.hasNext)
    }

    override fun getFilterList() = AnimeFilterList(Filters.Type())

    // ============================== Details ==============================
    override fun getAnimeUrl(anime: SAnime): String = titleUrl(anime)

    override fun animeDetailsRequest(anime: SAnime): Request = GET(titleUrl(anime), headers)

    override fun animeDetailsParse(response: Response): SAnime = titlePage(response.asJsoup()).title.toSAnimeDetails()

    private fun titleUrl(anime: SAnime): String = "$localeUrl/titles/${animeRef(anime.url)!!.id}"

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val ref = animeRef(anime.url)!!
        val first = titlePage(client.get("$localeUrl/titles/${ref.id}", headers).asJsoup())
        val loaded = first.loadedSeason
        // The title page carries only one season's episodes; the others come from their own page.
        val others = first.title.seasons.filter { it.number != loaded?.number && it.episodesCount > 0 }
        val fetched = coroutineScope {
            val gate = Semaphore(4)
            others.map { season ->
                async {
                    gate.withPermit {
                        titlePage(client.get("$localeUrl/titles/${ref.id}/season-${season.number}", headers).asJsoup()).loadedSeason
                    }
                }
            }.awaitAll().filterNotNull()
        }
        return episodeEntries(first.title.type, ref.id, listOfNotNull(loaded) + fetched).map {
            SEpisode.create().apply {
                url = it.url
                name = it.name
                episode_number = it.number
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================== Video ==============================
    // Watching needs a session: log in with the account saved in the settings, open the watch page for its
    // player url, then ask the site's play API for the signed MP4. No session or no video is an empty list.
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val ref = episodeRef(episode.url) ?: return emptyList()
        val episodeId = if (ref.type == "movie") null else episodeIdOf(ref) ?: return emptyList()
        val path = watchPath(ref, episodeId)

        repeat(2) { attempt ->
            val token = watchEmbed(path)?.let(::playToken) ?: return emptyList()
            val play = playResponse(token)
            if (play != null) {
                val videos = playSources(play).map {
                    Video(videoUrl = it.src, videoTitle = sourceTitle(it), headers = headers)
                }
                return listOf(Hoster(hosterName = "RedeToons", videoList = videos))
            }
            // 401: the session ended; sign in again once and retry.
            if (attempt == 0 && !login()) return emptyList()
        }
        return emptyList()
    }

    override fun hosterListRequest(episode: SEpisode): Request = throw UnsupportedOperationException()
    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()
    override fun videoUrlParse(response: Response): String = throw UnsupportedOperationException()

    private suspend fun episodeIdOf(ref: EpisodeRef): Long? {
        val season = titlePage(client.get("$localeUrl/titles/${ref.id}/season-${ref.season}", headers).asJsoup()).loadedSeason
        return episodeIdAt(season, ref.position)
    }

    // The watch page of someone without a session is the login page, so no player url: sign in and ask again.
    private suspend fun watchEmbed(path: String): String? {
        repeat(2) { attempt ->
            watchEmbedUrl(client.get(siteUrl + path, headers, FRESH).asJsoup())?.let { return it }
            if (attempt == 0 && !login()) return null
        }
        return null
    }

    // Null when the play API says the session is gone (401).
    private suspend fun playResponse(firstToken: String): PlayResponse? {
        var token = firstToken
        var renewed = false
        while (true) {
            val url = "$siteUrl/api/play".toHttpUrl().newBuilder().addPathSegment(token).build()
            val response = client.get(url, jsonHeaders, FRESH, ensureSuccess = false)
            when {
                response.isSuccessful -> return response.parseAs<PlayResponse>()
                response.code == 401 -> {
                    response.close()
                    return null
                }
                // The token is short-lived; the site renews it once.
                response.code == 403 && !renewed -> {
                    response.close()
                    renewed = true
                    token = client.post("$siteUrl/api/play/renovar", jsonHeaders, RenewRequest(token).toJsonRequestBody())
                        .parseAs<RenewResponse>().p
                }
                else -> {
                    response.close()
                    throw HttpException(response.code)
                }
            }
        }
    }

    // False when there is no account saved, the login page is not the expected one, or the site refused the account.
    private suspend fun login(): Boolean {
        if (!isSecureUrl(siteUrl)) return false
        val user = preferences.getString(PREF_USER_KEY, "").orEmpty().trim()
        val password = preferences.getString(PREF_PASS_KEY, "").orEmpty()
        if (user.isEmpty() || password.isEmpty()) return false

        val version = inertiaVersion(client.get("$siteUrl/login", headers, FRESH).asJsoup()) ?: return false
        val loginHeaders = headersBuilder()
            .set("Accept", "text/html, application/xhtml+xml")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("X-Inertia", "true")
            .set("X-Inertia-Version", version)
            .set("Origin", siteUrl)
            .set("Referer", "$siteUrl/login")
            .build()
        // The first login attempt needs no captcha: the site only asks for one after failures.
        // It answers 409 (an Inertia redirect) when the sign-in works, so the status is not the check.
        client.post("$siteUrl/login", loginHeaders, LoginForm(user, password, remember = false, recaptchaToken = "").toJsonRequestBody(), ensureSuccess = false).close()

        return isLoggedIn(client.get(localeUrl, headers, FRESH).asJsoup())
    }

    // ============================== Mapping ==============================
    private fun SiteTitle.toSAnime() = SAnime.create().apply {
        url = "$type/$id"
        title = name
        thumbnail_url = posterUrl(siteUrl, this@toSAnime)
    }

    private fun SiteTitle.toSAnimeDetails() = toSAnime().apply {
        description = plot
        genre = genres.joinToString { it.name }
        status = when {
            type == "movie" -> SAnime.COMPLETED
            this@toSAnimeDetails.status.equals("Ended", true) || this@toSAnimeDetails.status.equals("Canceled", true) -> SAnime.COMPLETED
            else -> SAnime.ONGOING
        }
    }

    // ============================== Content filter ==============================
    private suspend fun List<SiteTitle>.filterBy(categories: Set<ContentCategory>): List<SiteTitle> {
        if (categories.size == ContentCategory.entries.size) return this
        return coroutineScope {
            val gate = Semaphore(6)
            map { title ->
                async {
                    gate.withPermit {
                        // Lists do not say whether a title is Japanese; its preview does.
                        val preview = runCatching {
                            client.get("$siteUrl/api/titles/preview/${title.id}", headers).parseAs<SiteTitle>()
                        }.getOrNull() ?: return@withPermit null
                        if (categories.any { matchesCategory(it, title.type, preview) }) title else null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun selectedContentCategories(): Set<ContentCategory> {
        val saved = preferences.getStringSet(CONTENT_FILTER_KEY, ALL_CONTENT_KEYS).orEmpty()
        val normalized = normalizeContentCategories(saved)
        if (normalized.map { it.key }.toSet() != saved) {
            preferences.edit().putStringSet(CONTENT_FILTER_KEY, normalized.mapTo(mutableSetOf()) { it.key }).apply()
        }
        return normalized
    }

    private fun normalizeContentCategories(values: Set<String>) = ContentCategory.entries.filterTo(mutableSetOf()) { it.key in values }
        .ifEmpty { ContentCategory.entries.toSet() }

    private fun allowedCategories(filters: AnimeFilterList): Set<ContentCategory> {
        val global = selectedContentCategories()
        val temporary = filters.firstInstanceOrNull<Filters.Type>()?.category
        return temporary?.let { global.intersect(setOf(it)) } ?: global
    }

    private fun contentSummary(categories: Set<ContentCategory>) = when (categories.size) {
        1 -> "Somente ${categories.single().label}"
        else -> categories.joinToString { it.label }
    }

    private object Filters {
        class Type : AnimeFilter.Select<String>("Tipo", arrayOf("Todos", "Animes", "Séries", "Filmes")) {
            val category get() = entries.getOrNull(state)

            private val entries = arrayOf<ContentCategory?>(null, ContentCategory.ANIMATION, ContentCategory.SERIES, ContentCategory.MOVIES)
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
                    val raw = (newValue as String).trim()
                    if (raw.isBlank()) {
                        preferences.edit().putString(key, PREF_DOMAIN_DEFAULT).commit()
                        summary = PREF_DOMAIN_DEFAULT
                        text = PREF_DOMAIN_DEFAULT
                        false
                    } else if (isValidUrl(raw)) {
                        val normalized = normalizeInputUrl(raw)
                        preferences.edit().putString(key, normalized).commit()
                        summary = normalized
                        text = normalized
                        false
                    } else {
                        Toast.makeText(
                            screen.context,
                            "URL inválida. Configuração anterior mantida.",
                            Toast.LENGTH_SHORT,
                        ).show()
                        false
                    }
                }.getOrDefault(false)
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_USER_KEY
            title = "E-mail ou usuário do RedeToons"
            summary = "O site só libera o vídeo para quem tem conta."
            setDefaultValue("")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_PASS_KEY
            title = "Senha do RedeToons"
            summary = "Usada só para entrar no RedeToons. Limpar o campo desconecta."
            setDefaultValue("")
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = CONTENT_FILTER_KEY
            title = "Filtro de conteúdo"
            entries = ContentCategory.entries.map { it.label }.toTypedArray()
            entryValues = ContentCategory.entries.map { it.key }.toTypedArray()
            setDefaultValue(ALL_CONTENT_KEYS)
            summary = contentSummary(selectedContentCategories())
            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                val selected = normalizeContentCategories(newValue as Set<String>)
                preferences.edit().putStringSet(CONTENT_FILTER_KEY, selected.mapTo(mutableSetOf()) { it.key }).apply()
                summary = contentSummary(selected)
                if (selected.size == ContentCategory.entries.size && (newValue as Set<*>).isEmpty()) {
                    Toast.makeText(screen.context, "Selecione ao menos um tipo. Todos foram restaurados.", Toast.LENGTH_SHORT).show()
                }
                false
            }
        }.also(screen::addPreference)
    }

    private fun getDomainPrefSummary(): String = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)?.ifBlank { PREF_DOMAIN_DEFAULT } ?: PREF_DOMAIN_DEFAULT

    companion object {
        private const val PREF_USER_KEY = "username"
        private const val PREF_PASS_KEY = "password"
        private val FRESH = CacheControl.FORCE_NETWORK
        private const val CONTENT_FILTER_KEY = "content_filter"
        private val ALL_CONTENT_KEYS = ContentCategory.entries.mapTo(mutableSetOf()) { it.key }
    }
}
