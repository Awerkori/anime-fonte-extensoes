package eu.kanade.tachiyomi.animeextension.pt.tomato

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.tomato.dto.AnimeResultDto
import eu.kanade.tachiyomi.animeextension.pt.tomato.dto.EpisodeInfoDto
import eu.kanade.tachiyomi.animeextension.pt.tomato.dto.EpisodeStreamDto
import eu.kanade.tachiyomi.animeextension.pt.tomato.dto.EpisodesResultDto
import eu.kanade.tachiyomi.animeextension.pt.tomato.dto.SearchAnimeItemDto
import eu.kanade.tachiyomi.animeextension.pt.tomato.dto.SearchResultDto
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import rx.Observable
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import kotlin.time.Duration.Companion.seconds

private const val APP_USER_AGENT = "tomato-android"
private const val OFFICIAL_OKHTTP_USER_AGENT = "okhttp/4.11.0"
private const val OFFICIAL_APP_VERSION = "1.4.3"
private const val AXIOS_ACCEPT = "application/json, text/plain, */*"
private const val AXIOS_ACCEPT_ENCODING = "gzip, deflate"
private const val AXIOS_CONNECT_TIMEOUT_MS = 8_000
private const val LOGIN_REQUIRED_URL = "/tomato-login-required"
private const val FEED_PATH = "/v2/animes/feed"
private const val LATEST_SECTION_TYPE = 7
private const val EMPTY_FEED_ANIME_ID = 1062
private const val PAGE_SIZE = 25
private const val FEED_CACHE_TTL_MS = 3_000L
private const val DETAILS_CACHE_TTL_MS = 30_000L
private const val MAX_POST_INSPECTION_BYTES = 256L * 1024L
private const val MAX_ERROR_MESSAGE_LENGTH = 120
private const val SERVER_UNAVAILABLE_MESSAGE =
    "O servidor da Tomato está indisponível no momento. Tente novamente mais tarde."
private const val CONNECTION_ERROR_MESSAGE =
    "Não foi possível conectar ao servidor da Tomato. Verifique sua internet ou tente novamente mais tarde."
private const val RATE_LIMIT_MESSAGE =
    "A Tomato bloqueou temporariamente novas tentativas neste dispositivo/IP. Aguarde um tempo antes de tentar novamente."
private const val SESSION_EXPIRED_MESSAGE = "Sua sessão da Tomato expirou ou não é mais válida. Entre novamente."
private const val MAINTENANCE_MESSAGE = "A Tomato está em manutenção. Tente novamente mais tarde."
private const val UNEXPECTED_ERROR_MESSAGE = "A Tomato retornou um erro inesperado. Tente novamente mais tarde."
internal const val PREF_TOKEN = "tomato_official_session_token_v1"
private const val PREF_LANGUAGE = "preferred_language"
private const val PREF_ACCOUNT_ACTION = "tomato_account_action_v2"
private const val PREF_LOGOUT_ACTION = "tomato_logout_action_v2"
private const val LEGACY_LOGIN_ACTION = "tomato_login"
private const val LEGACY_LOGOUT_ACTION = "tomato_logout"
private const val LEGACY_MANUAL_TOKEN = "tomato_session_token"
private const val LEGACY_LANGUAGE = "pref_language"
private const val LEGACY_QUALITY = "preferred_quality"
private const val EXTENSION_PACKAGE = "eu.kanade.tachiyomi.animeextension.pt.tomato"
private val LEGACY_EPISODE_ID_REGEX = Regex(
    "(?:[?&])episode(?:%5B|\\[)([01])(?:%5D|\\])=(\\d+)",
    RegexOption.IGNORE_CASE,
)

private class Utf8JsonRequestBody(json: String) : RequestBody() {
    private val bytes = json.toByteArray(StandardCharsets.UTF_8)

    override fun contentType(): okhttp3.MediaType? = null
    override fun contentLength() = bytes.size.toLong()
    override fun writeTo(sink: BufferedSink) {
        sink.write(bytes)
    }
}

private enum class PostEnvelope { SEARCH, EPISODES }

/** API mapped from com.tomatos.clientapp in the official tomato.apk. */
class Tomato :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    override val name = "Tomato"

    // The official SplashActivity selects the API host before normal requests.
    // This getter is side-effect free; authenticated builders perform bootstrap.
    override val baseUrl get() = serverBootstrap.persistedHost()
    override val lang = "pt-BR"
    override val supportsLatest = true

    // Tomato's official client exposes no related/recommendations endpoint. Without this,
    // AnimeHttpSource falls back to the Popular request for Anikku's Suggestions block.
    override val supportsRelatedAnimes = false
    override val disableRelatedAnimesBySearch = true

    private val preferences: SharedPreferences by getPreferencesLazy()
    private val serverBootstrap by lazy { TomatoServerBootstrap(preferences) }
    private var loginResultReceiver: ResultReceiver? = null
    private val feedCacheLock = Any()
    private val popularTitleCache = ConcurrentHashMap<Int, String>()

    @Volatile
    private var feedSnapshot: FeedSnapshot? = null

    @Volatile
    private var detailsSnapshot: DetailsSnapshot? = null

    override fun headersBuilder() = super.headersBuilder().apply {
        // Keep source-level headers safe for media clients. Some hosts merge these
        // into Video.headers even when the Video supplies its own headers.
        set("Accept", "*/*")
        set("User-Agent", APP_USER_AGENT)
    }

    override val client by lazy {
        network.client.newBuilder()
            .rateLimit(5, 1.seconds)
            // The React-Native Axios client used by the official application has a
            // different request contract from its native Volley Player. Keep the
            // already validated stream request untouched and apply Axios headers only
            // to the API endpoints implemented by React Native.
            .addInterceptor { chain ->
                val original = chain.request()
                if (original.url.encodedPath.endsWith("/stream")) {
                    return@addInterceptor chain.proceed(original)
                }
                val request = original.newBuilder()
                    .header("Accept", AXIOS_ACCEPT)
                    .header("Accept-Encoding", AXIOS_ACCEPT_ENCODING)
                    .header("User-Agent", OFFICIAL_OKHTTP_USER_AGENT)
                    .header("request-time", System.currentTimeMillis().toString())
                    .apply {
                        if (original.url.encodedPath == FEED_PATH) {
                            header("x-app", OFFICIAL_APP_VERSION)
                        } else {
                            removeHeader("x-app")
                        }
                    }
                    .build()
                chain.withConnectTimeout(AXIOS_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS).proceed(request)
            }
            // Popular and Recentes consume the same response with independent
            // parsers. A very short, session-scoped snapshot removes the duplicate
            // simultaneous GET without changing either mapping.
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.method != "GET" || request.url.encodedPath != FEED_PATH) {
                    return@addInterceptor chain.proceed(request)
                }
                synchronized(feedCacheLock) {
                    val now = SystemClock.elapsedRealtime()
                    val key = request.feedCacheKey()
                    feedSnapshot
                        ?.takeIf { it.key == key && now - it.storedAtMs <= FEED_CACHE_TTL_MS }
                        ?.let {
                            return@synchronized it.toResponse(request)
                        }

                    val response = chain.proceed(request)
                    if (!response.isSuccessful) return@synchronized response
                    val contentType = response.body.contentType()
                    val bytes = response.body.bytes()
                    feedSnapshot = FeedSnapshot(
                        key = key,
                        storedAtMs = SystemClock.elapsedRealtime(),
                        code = response.code,
                        message = response.message,
                        protocol = response.protocol,
                        headers = response.headers,
                        body = bytes,
                    )
                    response.newBuilder().body(bytes.toResponseBody(contentType)).build()
                }
            }
            // These requests target only the Tomato API. Media URLs are handed to
            // the host through Video and never pass through this error mapper.
            .addInterceptor { chain ->
                val response = try {
                    chain.proceed(chain.request())
                } catch (error: UnknownHostException) {
                    throw IOException(CONNECTION_ERROR_MESSAGE, error)
                } catch (error: SocketTimeoutException) {
                    throw IOException(SERVER_UNAVAILABLE_MESSAGE, error)
                } catch (error: ConnectException) {
                    throw IOException(SERVER_UNAVAILABLE_MESSAGE, error)
                }
                if (response.isSuccessful) {
                    response
                } else {
                    val message = response.tomatoApiErrorMessage()
                    response.close()
                    throw IOException(message)
                }
            }
            // Axios explicitly supplies Accept-Encoding, so OkHttp does not perform
            // its transparent decompression. Decode before the outer error mapper or
            // JSON parsers inspect a response body.
            .addInterceptor { chain -> chain.proceed(chain.request()).decodeContentEncoding() }
            .build()
    }

    // Preserve each screen's independent parser. The short response snapshot above
    // only prevents two consumers from downloading the identical Feed concurrently.
    override suspend fun getPopularAnime(page: Int): AnimesPage = if (sessionToken() == null) loginRequiredPage() else super.getPopularAnime(page)

    override suspend fun getLatestUpdates(page: Int): AnimesPage = if (sessionToken() == null) loginRequiredPage() else super.getLatestUpdates(page)

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage = if (sessionToken() == null) loginRequiredPage() else super.getSearchAnime(page, query, filters)

    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> = if (sessionToken() == null) Observable.just(loginRequiredPage()) else super.fetchPopularAnime(page)

    override fun fetchLatestUpdates(page: Int): Observable<AnimesPage> = if (sessionToken() == null) Observable.just(loginRequiredPage()) else super.fetchLatestUpdates(page)

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> = if (sessionToken() == null) Observable.just(loginRequiredPage()) else super.fetchSearchAnime(page, query, filters)

    override fun popularAnimeRequest(page: Int) = authenticatedGet("/v2/animes/feed")

    override fun popularAnimeParse(response: Response) = popularPage(response.parseAs())

    private fun popularPage(feed: JsonObject): AnimesPage {
        val shelf = feed["data"]?.jsonArray.orEmpty().firstOrNull {
            it.jsonObject["title"]?.jsonPrimitive?.content.orEmpty().contains("curtidos", true)
        }
        val cards = shelf?.jsonObject?.get("data")?.jsonArray.orEmpty()
        val titleByAnimeId = feed.animeTitles()
        val animes = cards.mapNotNull { element ->
            val card = element.jsonObject
            val id = card.intOrNull("anime_id")?.takeIf { it > 0 } ?: return@mapNotNull null
            if (id == EMPTY_FEED_ANIME_ID) return@mapNotNull null
            val thumbnail = card.stringOrNull("thumbnail") ?: return@mapNotNull null
            val title = titleByAnimeId[id] ?: popularTitleCache[id] ?: hydratePopularTitle(id)
            val valid = !title.isNullOrBlank() && thumbnail.isNotBlank()
            if (!valid) return@mapNotNull null
            requiredSAnime("/v2/anime/$id", requireNotNull(title)) {
                thumbnail_url = thumbnail
            }
        }
        return animes.validatedPage()
    }

    override fun latestUpdatesRequest(page: Int) = authenticatedGet(FEED_PATH)

    override fun latestUpdatesParse(response: Response) = latestPage(response.parseAs())

    private fun latestPage(feed: JsonObject): AnimesPage {
        val cards = feed["data"]?.jsonArray.orEmpty()
            .firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content?.toIntOrNull() == LATEST_SECTION_TYPE }
            ?.jsonObject
            ?.get("data")
            ?.jsonArray
            .orEmpty()
        // SectionNewEpisodes/ItemNewEpisodes in the official APK renders these
        // fields directly. It performs no Details hydration, deduplication or local
        // sorting; the server response is already the official new-episode order.
        val animes = cards.mapNotNull { element ->
            val card = element.jsonObject
            val animeId = card.intOrNull("ep_anime_id")
            val episodeId = card.intOrNull("ep_id")
            val title = card.stringOrNull("anime_name")
            val episodeName = card.stringOrNull("ep_name")
            val thumbnail = card.stringOrNull("thumbnail")
            val valid = animeId != null && animeId > 0 && episodeId != null && episodeId > 0 &&
                !title.isNullOrBlank() && !thumbnail.isNullOrBlank()
            if (!valid) return@mapNotNull null
            requiredSAnime("/v2/anime/$animeId", title) {
                thumbnail_url = thumbnail
                description = episodeName?.let { "Último episódio: $it" }
            }
        }
        return animes.validatedPage()
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = TomatoFilters.getSearchParameters(filters)
        return authenticatedPost("/v2/content/search") {
            put("token", requireToken())
            put("search", query)
            put("content_type", "anime")
            put("page", page - 1)
            if (params.genres.isNotEmpty()) put("tags", JSONArray(params.genres))
        }
    }

    override fun searchAnimeParse(response: Response) = response
        .normalizePostEnvelope(PostEnvelope.SEARCH)
        .parseAs<SearchResultDto>()
        .result
        .map { it.toSAnime() }
        .validatedPage()

    // Tomato does not expose a related-anime API compatible with Aniyomi. Explicitly
    // returning an empty list prevents the host fallback from issuing several searches
    // every time a details page opens.
    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> = emptyList()

    override fun animeDetailsRequest(anime: SAnime): Request {
        val animeId = anime.url.substringAfterLast('/').toIntOrNull()
            ?: error("ID de anime Tomato inválido")
        return authenticatedGet("/v2/anime/$animeId")
    }
    override fun animeDetailsParse(response: Response): SAnime {
        val details = response.parseAs<AnimeResultDto>()
        detailsSnapshot = DetailsSnapshot(details.animeDetails.animeId, SystemClock.elapsedRealtime(), details)
        return details.toSAnime()
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = if (anime.url == LOGIN_REQUIRED_URL) anime else super.getAnimeDetails(anime)

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        if (anime.url == LOGIN_REQUIRED_URL) return emptyList()
        val animeId = anime.url.substringAfterLast('/').toIntOrNull()
            ?: error("ID de anime Tomato inválido")
        val now = SystemClock.elapsedRealtime()
        val cachedDetails = detailsSnapshot?.takeIf {
            it.animeId == animeId && now - it.storedAtMs <= DETAILS_CACHE_TTL_MS
        }
        val details = cachedDetails?.details ?: client.newCall(animeDetailsRequest(anime)).awaitSuccess().use {
            it.parseAs<AnimeResultDto>().also { parsed ->
                detailsSnapshot = DetailsSnapshot(animeId, SystemClock.elapsedRealtime(), parsed)
            }
        }
        val merged = linkedMapOf<Pair<Int, Float>, SEpisode>()
        details.animeSeasons.sortedBy { it.seasonNumber }.forEach { season ->
            var page = 0
            while (true) {
                val result = client.newCall(
                    authenticatedPost("/season/${season.seasonId}/episodes") {
                        put("token", requireToken())
                        put("page", page)
                        put("order", "ASC")
                    },
                ).awaitSuccess().use {
                    it.normalizePostEnvelope(PostEnvelope.EPISODES).parseAs<EpisodesResultDto>()
                }
                result.data.forEach { item ->
                    val key = season.seasonNumber to item.epNumber
                    val existing = merged[key]
                    if (existing == null) {
                        val episodeUrl = episodeUrl(item.epId)
                        merged[key] = SEpisode.create().apply {
                            episode_number = season.seasonNumber + item.epNumber / 1000f
                            name = "T${season.seasonNumber}E${formatEpisode(item.epNumber)} - ${item.epName}"
                            url = episodeUrl
                            scanlator = if (season.seasonDubbed == 1) "Dublado" else "Legendado"
                        }
                    } else {
                        existing.url += "?alternate=${item.epId}"
                        existing.scanlator = "Legendado e Dublado"
                    }
                }
                val loaded = (page * PAGE_SIZE) + result.data.size
                if (result.data.isEmpty() || result.data.size < PAGE_SIZE || (result.episodes > 0 && loaded >= result.episodes)) break
                page++
            }
        }
        return merged.values.sortedBy { it.episode_number }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = error("Tomato loads episodes by season")

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val primaryId = episodeIdFromUrl(episode.url)
        val alternateId = episode.url.substringAfter("alternate=", "").toIntOrNull()
        val legacyIds = legacyEpisodeIds(episode.url)
        val streams = if (legacyIds.isNotEmpty()) {
            legacyIds
        } else {
            buildList {
                primaryId?.let { add(episode.scanlator.orEmpty().substringBefore(" e ") to it) }
                alternateId?.let { add("Dublado" to it) }
            }
        }
        return streams.flatMap { (language, episodeId) ->
            val info = client.newCall(streamRequest(episodeId)).awaitSuccess().use { it.parseAs<EpisodeInfoDto>() }
            info.streams.toVideos(language)
        }
            .sortedWith(compareByDescending<Video> { it.quality.contains(preferredLanguage(), true) }.thenByDescending { qualityNumber(it.quality) })
    }

    override fun getFilterList() = TomatoFilters.FILTER_LIST

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        migratePreferences()
        lateinit var accountPreference: EditTextPreference
        lateinit var logoutPreference: EditTextPreference

        fun refreshAccountState() {
            val connected = sessionToken() != null
            accountPreference.summary = if (connected) "Conectado" else "Não conectado — toque para entrar"
            logoutPreference.setEnabled(connected)
        }

        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (resultCode != TomatoLoginActivity.RESULT_LOGIN_SUCCESS) return
                val token = resultData?.getString(TomatoLoginActivity.EXTRA_SESSION_TOKEN)
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?: return
                preferences.edit().putString(PREF_TOKEN, token).apply()
                feedSnapshot = null
                detailsSnapshot = null
                refreshAccountState()
            }
        }

        accountPreference = EditTextPreference(screen.context).apply {
            key = PREF_ACCOUNT_ACTION
            title = "Conta Tomato"
            setOnPreferenceClickListener {
                // Keep this receiver alive until the extension Activity returns the session.
                loginResultReceiver = receiver
                val context = screen.context
                Thread {
                    val host = runCatching(::selectedApiBaseUrl)
                    Handler(Looper.getMainLooper()).post {
                        host.onFailure { error ->
                            val message = when (error) {
                                is UnknownHostException -> CONNECTION_ERROR_MESSAGE
                                else -> SERVER_UNAVAILABLE_MESSAGE
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            return@post
                        }
                        context.startActivity(
                            Intent().setComponent(
                                ComponentName(EXTENSION_PACKAGE, TomatoLoginActivity::class.java.name),
                            )
                                .putExtra(TomatoLoginActivity.EXTRA_RESULT_RECEIVER, receiver)
                                .putExtra(TomatoLoginActivity.EXTRA_API_HOST, host.getOrThrow())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }.start()
                true
            }
        }
        accountPreference.also(screen::addPreference)
        logoutPreference = EditTextPreference(screen.context).apply {
            key = PREF_LOGOUT_ACTION
            title = "Sair"
            summary = "Apagar sessão da conta Tomato"
            setOnPreferenceClickListener {
                preferences.edit().remove(PREF_TOKEN).apply()
                feedSnapshot = null
                detailsSnapshot = null
                refreshAccountState()
                true
            }
        }
        logoutPreference.also(screen::addPreference)
        refreshAccountState()
        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE
            title = "Idioma preferido"
            entries = arrayOf("Dublado", "Legendado")
            entryValues = entries
            setDefaultValue("Dublado")
            summary = "%s"
        }.also(screen::addPreference)
    }

    private fun loginRequiredPage() = listOf(loginRequiredAnime()).validatedPage()
    private fun loginRequiredAnime() = requiredSAnime(LOGIN_REQUIRED_URL, "Login necessário") {
        description = "Abra as configurações da extensão Tomato e entre com sua conta."
    }
    private fun AnimeResultDto.toSAnime() = SAnime.create().apply {
        setUrlWithoutDomain("/v2/anime/${animeDetails.animeId}")
        title = animeDetails.animeName
        description = animeDetails.animeDescription
        genre = animeDetails.animeGenre
        thumbnail_url = animeDetails.animeCoverUrl
    }
    private fun SearchAnimeItemDto.toSAnime() = requiredSAnime("/v2/anime/$id", name) {
        thumbnail_url = image
        genre = tags
    }

    // Popular's shelf contains only anime_id and thumbnail. Names found in another
    // Feed shelf are reused locally; only missing names are resolved once by Details.
    private fun hydratePopularTitle(animeId: Int): String? {
        val title = runCatching {
            client.newCall(authenticatedGet("/v2/anime/$animeId")).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.parseAs<AnimeResultDto>().animeDetails.animeName.trim().takeIf(String::isNotEmpty)
            }
        }.getOrNull()
        title?.let { popularTitleCache[animeId] = it }
        return title
    }

    private fun JsonObject.animeTitles(): Map<Int, String> = buildMap {
        fun add(card: JsonObject) {
            val id = card.intOrNull("anime_id") ?: card.intOrNull("ep_anime_id") ?: return
            val title = card.stringOrNull("anime_name") ?: card.stringOrNull("title") ?: return
            put(id, title)
        }

        this@animeTitles["data"]?.jsonArray.orEmpty().forEach { sectionElement ->
            val section = sectionElement.jsonObject
            add(section)
            runCatching { section["data"]?.jsonArray.orEmpty() }
                .getOrDefault(emptyList())
                .forEach { add(it.jsonObject) }
        }
    }

    private inline fun requiredSAnime(url: String, title: String, configure: SAnime.() -> Unit = {}): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(url)
        this.title = title
        configure()
    }

    // Validation is structural: every item reaching this helper was built through
    // requiredSAnime, whose non-null parameters and assignments initialize both
    // lateinit fields. Do not read either property here on compatibility hosts.
    private fun List<SAnime>.validatedPage() = AnimesPage(this, false)

    // This is the URL format emitted by SESSION-BRIDGE-FIX. Keep producer and
    // consumer together: its final path segment is "stream", not the episode ID.
    private fun episodeUrl(episodeId: Int) = "/v2/anime/episode/$episodeId/stream"

    private fun episodeIdFromUrl(url: String): Int? {
        val path = url.substringBefore('?').trimEnd('/')
        return path.substringBeforeLast("/stream").substringAfterLast('/').toIntOrNull()
            ?: path.substringAfterLast('/').toIntOrNull()
    }

    private fun legacyEpisodeIds(url: String): List<Pair<String, Int>> = LEGACY_EPISODE_ID_REGEX
        .findAll(url)
        .mapNotNull { match ->
            val language = if (match.groupValues[1] == "1") "Dublado" else "Legendado"
            match.groupValues[2].toIntOrNull()?.let { language to it }
        }
        .toList()

    private fun authenticatedGet(path: String): Request {
        requireToken()
        return GET("${selectedApiBaseUrl()}${path.takeIf { it.startsWith('/') } ?: "/$path"}", requestHeaders())
    }

    // Matches the first direct official-extension implementation: same authenticated
    // GET builder used by the original ep_id -> /stream path, with no extra headers.
    private fun streamRequest(episodeId: Int): Request = authenticatedGet("/v2/anime/episode/$episodeId/stream")
    private fun authenticatedPost(path: String, build: JSONObject.() -> Unit): Request {
        requireToken()
        val payload = JSONObject().apply(build)
        val headers = requestHeaders().newBuilder()
            .set("Content-Type", "application/json; charset=utf-8")
            .build()
        return POST("${selectedApiBaseUrl()}$path", headers, Utf8JsonRequestBody(payload.toString()))
    }
    private fun Response.normalizePostEnvelope(source: PostEnvelope): Response {
        val root = runCatching { JSONObject(peekBody(MAX_POST_INSPECTION_BYTES).string()) }
            .getOrElse { return this }
        val objects = root.possibleEnvelopes()
        val statusCode = objects.firstString("status_code")
        val message = objects.firstString("message", "error", "detail")
        if (statusCode == "31") throw IOException(RATE_LIMIT_MESSAGE)
        val list = when (source) {
            PostEnvelope.SEARCH -> objects.firstArray("result")
                ?: objects.firstArrayWithObjectKey("data", "id")
            PostEnvelope.EPISODES -> objects.firstArray("data")
                ?: objects.firstArrayWithObjectKey("episodes", "ep_id")
                ?: objects.firstArrayWithObjectKey("result", "ep_id")
        }
        check(list != null) {
            "Resposta Tomato inesperada em $source" +
                (message.safeLogicalMessage()?.let { ": $it" } ?: ".")
        }
        val normalized = when (source) {
            PostEnvelope.SEARCH -> JSONObject().put("result", list)
            PostEnvelope.EPISODES -> JSONObject()
                .put("episodes", objects.firstInt("episodes") ?: 0)
                .put("data", list)
        }
        return newBuilder()
            .body(normalized.toString().toResponseBody(body.contentType()))
            .build()
    }
    private fun JSONObject.possibleEnvelopes(): List<JSONObject> = buildList {
        fun addDistinct(value: JSONObject?) {
            if (value != null && none { it === value }) add(value)
        }

        addDistinct(this@possibleEnvelopes)
        listOf("data", "result", "response", "payload").forEach { addDistinct(optJSONObject(it)) }
        toList().forEach { envelope ->
            listOf("data", "result", "response", "payload").forEach { addDistinct(envelope.optJSONObject(it)) }
        }
    }
    private fun JSONObject.stringValue(key: String) = opt(key)
        ?.takeUnless { it === JSONObject.NULL }
        ?.toString()
        ?.trim()
        ?.takeIf(String::isNotEmpty)
    private fun JSONObject.intValue(key: String) = opt(key)
        ?.takeUnless { it === JSONObject.NULL }
        ?.toString()
        ?.toIntOrNull()
    private fun List<JSONObject>.firstString(vararg keys: String): String? {
        for (envelope in this) {
            for (key in keys) envelope.stringValue(key)?.let { return it }
        }
        return null
    }
    private fun List<JSONObject>.firstInt(key: String): Int? {
        for (envelope in this) envelope.intValue(key)?.let { return it }
        return null
    }
    private fun List<JSONObject>.firstArray(key: String): JSONArray? {
        for (envelope in this) envelope.optJSONArray(key)?.let { return it }
        return null
    }
    private fun List<JSONObject>.firstArrayWithObjectKey(key: String, objectKey: String): JSONArray? {
        for (envelope in this) {
            envelope.optJSONArray(key)?.takeIf { it.hasObjectKey(objectKey) }?.let { return it }
        }
        return null
    }
    private fun JSONArray.hasObjectKey(key: String) = length() == 0 || optJSONObject(0)?.has(key) == true
    private fun String?.safeLogicalMessage() = this
        ?.replace(Regex("[\\r\\n]+"), " ")
        ?.take(MAX_ERROR_MESSAGE_LENGTH)
    private fun EpisodeStreamDto.toVideos(language: String) = listOfNotNull(
        shd?.let { it.toVideo("$language - 480p") },
        mhd?.let { it.toVideo("$language - 720p") },
        fhd?.let { it.toVideo("$language - 1080p") },
    )

    // Stream URLs are already authorized by their query parameters. Explicit media
    // headers prevent hosts and FFmpeg from inheriting the API's Bearer token, while
    // applying the same safe headers to HLS playlists, segments and redirected URLs.
    private fun String.toVideo(quality: String) = Video(this, quality, videoUrl = this, headers = headers)
    private fun requestHeaders() = headers.newBuilder()
        .set("Accept", "application/json")
        .apply { sessionToken()?.let { set("Authorization", "Bearer $it") } }
        .build()
    private fun selectedApiBaseUrl() = serverBootstrap.selectedHost()
    private fun sessionToken() = preferences.getString(PREF_TOKEN, null)?.trim()?.removePrefix("Bearer ")?.takeIf(String::isNotEmpty)
    private fun requireToken() = sessionToken() ?: error("Login necessário. Abra as configurações da extensão Tomato e entre com sua conta.")
    private fun preferredLanguage() = preferences.getString(PREF_LANGUAGE, "Dublado") ?: "Dublado"
    private fun qualityNumber(label: String) = Regex("(\\d+)p").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    private fun formatEpisode(number: Float) = if (number % 1f == 0f) number.toInt().toString() else number.toString()
    private fun JsonObject.stringOrNull(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
    private fun JsonObject.intOrNull(key: String) = this[key]?.jsonPrimitive?.intOrNull
    private fun Request.feedCacheKey() = "${url.scheme}://${url.host}:${url.port}:${header("Authorization").hashCode()}"
    private fun Response.decodeContentEncoding(): Response {
        val encoding = header("Content-Encoding")?.substringBefore(',')?.trim()?.lowercase() ?: return this
        if (encoding != "gzip" && encoding != "deflate") return this
        val contentType = body.contentType()
        val decoded = body.byteStream().use { input ->
            val decodedInput = if (encoding == "gzip") GZIPInputStream(input) else InflaterInputStream(input)
            decodedInput.use { stream ->
                ByteArrayOutputStream().use { output ->
                    stream.copyTo(output)
                    output.toByteArray()
                }
            }
        }
        return newBuilder()
            .removeHeader("Content-Encoding")
            .removeHeader("Content-Length")
            .body(decoded.toResponseBody(contentType))
            .build()
    }
    private fun Response.tomatoApiErrorMessage(): String {
        val apiMessage = runCatching {
            val body = JSONObject(peekBody(MAX_POST_INSPECTION_BYTES).string())
            sequenceOf("message", "status", "error", "detail")
                .mapNotNull { body.opt(it) as? String }
                .map(String::trim)
                .firstOrNull(String::isNotEmpty)
        }.getOrNull()
        return when {
            code == 429 -> RATE_LIMIT_MESSAGE
            code == 401 || code == 403 -> SESSION_EXPIRED_MESSAGE
            apiMessage.isMaintenanceMessage() -> MAINTENANCE_MESSAGE
            code in 500..599 -> SERVER_UNAVAILABLE_MESSAGE
            !apiMessage.isNullOrBlank() -> apiMessage.replace(Regex("[\\r\\n]+"), " ").take(300)
            else -> UNEXPECTED_ERROR_MESSAGE
        }
    }

    private fun String?.isMaintenanceMessage(): Boolean = this?.let {
        it.contains("maintenance", ignoreCase = true) || it.contains("manuten", ignoreCase = true)
    } == true

    private fun migratePreferences() {
        val stored = preferences.all
        val editor = preferences.edit()
        var changed = false

        fun remove(key: String) {
            if (key in stored) {
                editor.remove(key)
                changed = true
            }
        }

        // These were action controls, never user data. Older builds stored them as booleans.
        remove(LEGACY_LOGIN_ACTION)
        remove(LEGACY_LOGOUT_ACTION)

        // The manual-session preference must not become the official login session.
        remove(LEGACY_MANUAL_TOKEN)

        // Preserve the old language choice only when its legacy value has the expected type.
        (stored[LEGACY_LANGUAGE] as? String)?.takeIf { PREF_LANGUAGE !in stored }?.let {
            editor.putString(PREF_LANGUAGE, it)
            changed = true
        }
        remove(LEGACY_LANGUAGE)
        remove(LEGACY_QUALITY)

        // Defensive cleanup for values written with an incompatible type by prior builds.
        if (stored[PREF_LANGUAGE] != null && stored[PREF_LANGUAGE] !is String) remove(PREF_LANGUAGE)
        if (stored[PREF_TOKEN] != null && stored[PREF_TOKEN] !is String) remove(PREF_TOKEN)

        if (changed) editor.apply()
    }

    private data class FeedSnapshot(
        val key: String,
        val storedAtMs: Long,
        val code: Int,
        val message: String,
        val protocol: Protocol,
        val headers: okhttp3.Headers,
        val body: ByteArray,
    ) {
        fun toResponse(request: Request) = Response.Builder()
            .request(request)
            .protocol(protocol)
            .code(code)
            .message(message)
            .headers(headers)
            .body(body.toResponseBody(headers["Content-Type"]?.toMediaTypeOrNull()))
            .build()
    }

    private data class DetailsSnapshot(
        val animeId: Int,
        val storedAtMs: Long,
        val details: AnimeResultDto,
    )
}
