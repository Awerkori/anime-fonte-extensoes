package eu.kanade.tachiyomi.animeextension.pt.tomato

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import android.util.Log
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import kotlin.time.Duration.Companion.seconds

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
    private val json: Json by injectLazy()
    private var loginResultReceiver: ResultReceiver? = null
    private val requestSequence = AtomicLong(0)
    private val feedCacheLock = Any()
    private val popularTitleCache = ConcurrentHashMap<Int, String>()

    @Volatile
    private var feedSnapshot: FeedSnapshot? = null

    @Volatile
    private var detailsSnapshot: DetailsSnapshot? = null

    override fun headersBuilder() = super.headersBuilder().apply {
        set("Accept", "application/json")
        set("User-Agent", APP_USER_AGENT)
        sessionToken()?.let { set("Authorization", "Bearer $it") }
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
                            Log.d(TAG, "TOMATO_DEBUG REQUEST path=$FEED_PATH source=memory-cache")
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
            .addInterceptor { chain ->
                val request = chain.request()
                val sequence = requestSequence.incrementAndGet()
                Log.d(
                    TAG,
                    "TOMATO_DEBUG REQUEST seq=$sequence host=${request.url.host.serverLabel()} " +
                        "method=${request.method} path=${request.url.encodedPath}",
                )
                val response = chain.proceed(request)
                Log.d(
                    TAG,
                    "TOMATO_DEBUG RESPONSE seq=$sequence HTTP=${response.code} contentType=${response.body.contentType()} " +
                        "retryAfter=${response.header("Retry-After") ?: "none"} message=${response.safeErrorMessage()}",
                )
                if (response.code == 401 || response.code == 403) {
                    preferences.edit().remove(PREF_TOKEN).apply()
                    feedSnapshot = null
                    detailsSnapshot = null
                    Log.d(TAG, "TOMATO_DEBUG AUTH session_invalid=true HTTP=${response.code}")
                }
                if (request.url.encodedPath == FEED_PATH) {
                    Log.d(TAG, "TOMATO_DEBUG FEED method=${request.method} HTTP=${response.code}")
                }
                response
            }
            // Axios explicitly supplies Accept-Encoding, so OkHttp does not perform
            // its transparent decompression. Decode the two encodings requested by
            // the official client before the JSON parsers see the response.
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
        var feedTitles = 0
        var cachedTitles = 0
        var hydratedTitles = 0
        val animes = cards.mapIndexedNotNull { index, element ->
            val card = element.jsonObject
            val id = card.intOrNull("anime_id")?.takeIf { it > 0 } ?: return@mapIndexedNotNull null
            if (id == EMPTY_FEED_ANIME_ID) return@mapIndexedNotNull null
            val thumbnail = card.stringOrNull("thumbnail") ?: return@mapIndexedNotNull null
            val feedTitle = titleByAnimeId[id]
            val cachedTitle = popularTitleCache[id]
            val title = feedTitle ?: cachedTitle ?: hydratePopularTitle(id)
            when {
                feedTitle != null -> feedTitles++
                cachedTitle != null -> cachedTitles++
                title != null -> hydratedTitles++
            }
            val valid = !title.isNullOrBlank() && thumbnail.isNotBlank()
            if (index < POPULAR_VALIDATION_COUNT) {
                Log.d(
                    TAG,
                    "TOMATO_DEBUG POPULAR index=$index animeId=$id titlePresent=${!title.isNullOrBlank()} " +
                        "coverPresent=${thumbnail.isNotBlank()} source=${when {
                            feedTitle != null -> "feed"
                            cachedTitle != null -> "cache"
                            title != null -> "details"
                            else -> "missing"
                        }} valid=$valid",
                )
            }
            if (!valid) return@mapIndexedNotNull null
            requiredSAnime("/v2/anime/$id", requireNotNull(title)) {
                thumbnail_url = thumbnail
            }
        }
        Log.d(
            TAG,
            "TOMATO_DEBUG FEED section=popular raw=${cards.size} valid=${animes.size} skipped=${cards.size - animes.size} " +
                "titlesFeed=$feedTitles titlesCache=$cachedTitles titlesDetails=$hydratedTitles " +
                "firstId=${cards.firstOrNull()?.jsonObject?.get("anime_id")?.jsonPrimitive?.content ?: "none"} " +
                "firstKeys=${cards.firstOrNull()?.jsonObject?.keys?.sorted()?.joinToString(",") ?: "none"}",
        )
        return animes.validatedPage("popular")
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
        val animes = cards.mapIndexedNotNull { index, element ->
            val card = element.jsonObject
            val animeId = card.intOrNull("ep_anime_id")
            val episodeId = card.intOrNull("ep_id")
            val title = card.stringOrNull("anime_name")
            val episodeName = card.stringOrNull("ep_name")
            val thumbnail = card.stringOrNull("thumbnail")
            val valid = animeId != null && animeId > 0 && episodeId != null && episodeId > 0 &&
                !title.isNullOrBlank() && !thumbnail.isNullOrBlank()
            if (index < LATEST_VALIDATION_COUNT) {
                Log.d(
                    TAG,
                    "TOMATO_DEBUG LATEST index=$index animeId=${animeId ?: "none"} " +
                        "episodeId=${episodeId ?: "none"} titlePresent=${!title.isNullOrBlank()} " +
                        "coverPresent=${!thumbnail.isNullOrBlank()} valid=$valid",
                )
            }
            if (!valid) return@mapIndexedNotNull null
            requiredSAnime("/v2/anime/$animeId", title) {
                thumbnail_url = thumbnail
                description = episodeName?.let { "Último episódio: $it" }
            }
        }
        animes.take(LATEST_VALIDATION_COUNT).forEachIndexed { index, anime ->
            Log.d(
                TAG,
                "TOMATO_DEBUG LATEST mappedIndex=$index animeId=${anime.url.substringAfterLast('/')} source=feed",
            )
        }
        Log.d(
            TAG,
            "TOMATO_DEBUG FEED section=$LATEST_SECTION_TYPE meaning=new-episodes raw=${cards.size} " +
                "valid=${animes.size} skipped=${cards.size - animes.size} order=server",
        )
        return animes.validatedPage("latest")
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = TomatoFilters.getSearchParameters(filters)
        return authenticatedPost("/v2/content/search") {
            put("token", requireToken())
            put("search", query)
            put("content_type", "anime")
            put("page", page - 1)
            if (params.genres.isNotEmpty()) putJsonArray("tags") { params.genres.forEach(::add) }
        }
    }

    override fun searchAnimeParse(response: Response) = response.parseAs<SearchResultDto>()
        .result
        .map { it.toSAnime() }
        .validatedPage("search")

    // Tomato does not expose a related-anime API compatible with Aniyomi. Explicitly
    // returning an empty list prevents the host fallback from issuing several searches
    // every time a details page opens.
    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> = emptyList()

    override fun animeDetailsRequest(anime: SAnime): Request {
        val animeId = anime.url.substringAfterLast('/').toIntOrNull()
            ?: error("ID de anime Tomato inválido")
        Log.d(TAG, "TOMATO_DEBUG DETAILS animeId=$animeId")
        return authenticatedGet("/v2/anime/$animeId")
    }
    override fun animeDetailsParse(response: Response): SAnime {
        Log.d(TAG, "TOMATO_DEBUG DETAILS HTTP=${response.code}")
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
        Log.d(TAG, "TOMATO_DEBUG EPISODES animeId=$animeId detailsSource=${if (cachedDetails == null) "network" else "memory-cache"}")
        return loadEpisodesBySeasons(details)
    }

    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> = Observable.fromCallable { runBlocking { getEpisodeList(anime) } }

    public override fun episodeListParse(response: Response): List<SEpisode> {
        val details = response.parseAs<AnimeResultDto>()
        val animeId = details.animeDetails.animeId
        detailsSnapshot = DetailsSnapshot(animeId, SystemClock.elapsedRealtime(), details)
        Log.d(TAG, "TOMATO_DEBUG EPISODES animeId=$animeId detailsSource=legacy-response")
        return runBlocking { loadEpisodesBySeasons(details) }
    }

    private suspend fun loadEpisodesBySeasons(details: AnimeResultDto): List<SEpisode> {
        val merged = linkedMapOf<Pair<Int, Float>, SEpisode>()
        details.animeSeasons.sortedBy { it.seasonNumber }.forEach { season ->
            var page = 0
            while (true) {
                Log.d(TAG, "TOMATO_DEBUG EPISODES seasonId=${season.seasonId} page=$page")
                val result = client.newCall(
                    authenticatedPost("/season/${season.seasonId}/episodes") {
                        put("token", requireToken())
                        put("page", page)
                        put("order", "ASC")
                    },
                ).awaitSuccess().use { it.parseAs<EpisodesResultDto>() }
                result.data.forEach { item ->
                    val key = season.seasonNumber to item.epNumber
                    val existing = merged[key]
                    if (existing == null) {
                        val episodeUrl = episodeUrl(item.epId)
                        Log.d(
                            TAG,
                            "TOMATO_DEBUG EPISODE epId=${item.epId} number=${item.epNumber} urlShape=/v2/anime/episode/{ep_id}/stream",
                        )
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

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        Log.d(TAG, "TOMATO_DEBUG VIDEO entry")
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
        Log.d(
            TAG,
            "TOMATO_DEBUG VIDEO parsed primary=${primaryId ?: "invalid"} alternate=${alternateId ?: "none"} " +
                "legacy=${legacyIds.isNotEmpty()}",
        )
        val videos = streams.flatMap { (language, episodeId) ->
            Log.d(TAG, "TOMATO_DEBUG VIDEO episodeId=$episodeId")
            Log.d(TAG, "TOMATO_DEBUG DOWNLOAD episodeId=$episodeId episodeNumber=${episode.episode_number}")
            val info = client.newCall(streamRequest(episodeId)).awaitSuccess().use { it.parseAs<EpisodeInfoDto>() }
            Log.d(TAG, "TOMATO_DEBUG VIDEO streams shd=${!info.streams.shd.isNullOrBlank()} mhd=${!info.streams.mhd.isNullOrBlank()} fhd=${!info.streams.fhd.isNullOrBlank()}")
            Log.d(
                TAG,
                "TOMATO_DEBUG DOWNLOAD qualities shd=${!info.streams.shd.isNullOrBlank()} " +
                    "mhd=${!info.streams.mhd.isNullOrBlank()} fhd=${!info.streams.fhd.isNullOrBlank()}",
            )
            info.streams.toVideos(language)
        }
            .sortedWith(compareByDescending<Video> { it.quality.contains(preferredLanguage(), true) }.thenByDescending { qualityNumber(it.quality) })
        videos.firstOrNull()?.let {
            Log.d(
                TAG,
                "TOMATO_DEBUG DOWNLOAD selectedQuality=${it.quality} streamType=${it.videoUrl?.streamType() ?: "OTHER"} " +
                    "headers authPresent=${requestHeaders()["Authorization"] != null}",
            )
        }
        return videos
    }

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> = Observable.fromCallable { runBlocking { getVideoList(episode) } }

    public override fun videoListParse(response: Response): List<Video> {
        Log.d(TAG, "TOMATO_DEBUG VIDEO source=legacy-response")
        val info = response.parseAs<EpisodeInfoDto>()
        return info.streams.toVideos(preferredLanguage())
            .sortedWith(compareByDescending<Video> { qualityNumber(it.quality) })
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
                Log.d(TAG, "TOMATO_DEBUG AUTH session_received=true")
                preferences.edit().putString(PREF_TOKEN, token).apply()
                feedSnapshot = null
                detailsSnapshot = null
                Log.d(TAG, "TOMATO_DEBUG AUTH session_saved=true")
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
                        host.onFailure {
                            Toast.makeText(context, "Servidor Tomato temporariamente indisponível.", Toast.LENGTH_LONG).show()
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
                Log.d(TAG, "TOMATO_DEBUG AUTH logout")
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

    private fun loginRequiredPage() = listOf(loginRequiredAnime()).validatedPage("login-required")
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
    private fun List<SAnime>.validatedPage(source: String): AnimesPage {
        forEachIndexed { index, _ ->
            Log.d(TAG, "TOMATO_DEBUG SANIME source=$source index=$index titleSet=true urlSet=true")
        }
        return AnimesPage(this, false)
    }

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

    private fun String.streamType(): String {
        val path = substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") -> "HLS"
            path.endsWith(".mp4") -> "MP4"
            else -> "OTHER"
        }
    }

    private fun authenticatedGet(path: String): Request {
        requireToken()
        return GET("${selectedApiBaseUrl()}${path.takeIf { it.startsWith('/') } ?: "/$path"}", requestHeaders())
    }

    // Matches the first direct official-extension implementation: same authenticated
    // GET builder used by the original ep_id -> /stream path, with no extra headers.
    private fun streamRequest(episodeId: Int): Request = authenticatedGet("/v2/anime/episode/$episodeId/stream")
    private fun authenticatedPost(path: String, build: JsonObjectBuilder.() -> Unit): Request = POST(
        "${selectedApiBaseUrl()}$path",
        requestHeaders(),
        json.encodeToString(JsonObject.serializer(), buildJsonObject(build)).toRequestBody(JSON_MEDIA_TYPE),
    )
    private fun EpisodeStreamDto.toVideos(language: String) = listOfNotNull(
        shd?.let { Video(it, "$language - 480p", videoUrl = it) },
        mhd?.let { Video(it, "$language - 720p", videoUrl = it) },
        fhd?.let { Video(it, "$language - 1080p", videoUrl = it) },
    )
    private fun requestHeaders() = headersBuilder().build()
    private fun selectedApiBaseUrl() = serverBootstrap.selectedHost()
    private fun String.serverLabel() = when (this) {
        "prod-api.tomatoanimes.com" -> "prod"
        "edge.betomato.com" -> "edge"
        else -> "other"
    }
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
    private fun Response.safeErrorMessage(): String {
        if (isSuccessful) return "none"
        return runCatching { peekBody(MAX_ERROR_LOG_BYTES).string() }
            .getOrDefault("")
            .replace(Regex("\\s+"), " ")
            .take(MAX_ERROR_MESSAGE_LENGTH)
            .ifBlank { "none" }
    }

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

    companion object {
        private const val TAG = "Tomato"
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
        private const val POPULAR_VALIDATION_COUNT = 15
        private const val LATEST_VALIDATION_COUNT = 10
        private const val FEED_CACHE_TTL_MS = 3_000L
        private const val DETAILS_CACHE_TTL_MS = 30_000L
        private const val MAX_ERROR_LOG_BYTES = 512L
        private const val MAX_ERROR_MESSAGE_LENGTH = 120
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
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
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
