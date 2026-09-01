package eu.kanade.tachiyomi.animeextension.pt.tomato

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class Tomato :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "Tomato"

    override val baseUrl = PROD_API_URL

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val supportsRelatedAnimes = false

    override val disableRelatedAnimesBySearch = true

    override val client by lazy {
        network.client.newBuilder()
            .rateLimit(3, 1.seconds)
            .addInterceptor { chain ->
                val officialRequest = chain.request().withOfficialClientHeaders(officialAppVersion)
                val requestChain = if (officialRequest.usesOfficialClientContract()) {
                    chain.withConnectTimeout(OFFICIAL_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } else {
                    chain
                }
                val fallbackRequest = officialRequest.fallbackRequest()
                val response = try {
                    requestChain.proceed(officialRequest)
                } catch (error: IOException) {
                    if (!error.isConnectionFailure() || fallbackRequest == null) throw error
                    return@addInterceptor requestChain.proceed(fallbackRequest)
                }

                if (response.code in 500..599 && fallbackRequest != null) {
                    response.close()
                    return@addInterceptor requestChain.proceed(fallbackRequest)
                }

                response
            }
            .addInterceptor { chain ->
                chain.proceed(chain.request()).decodeContentEncoding()
            }
            .build()
    }

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", "tomato-android")
        .set("Accept", "*/*")

    private val preferences by getPreferencesLazy()

    private val preferencesReady by lazy { migratePreferences() }

    private val userToken: String?
        get() {
            preferencesReady
            return preferences.getString(PREF_TOKEN, null)
                ?.trim()
                ?.removePrefix("Bearer ")
                ?.takeIf(String::isNotEmpty)
        }

    private val userName: String?
        get() {
            preferencesReady
            return preferences.getString(PREF_USER_NAME, null)?.trim()?.takeIf { it.isNotEmpty() }
        }

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private val titleCache = ConcurrentHashMap<Int, String>()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val serverConfigLock = Any()

    private val sessionLock = Any()

    @Volatile
    private var configuredApiUrl = PROD_API_URL

    @Volatile
    private var officialAppVersion = COMPATIBLE_APP_VERSION

    @Volatile
    private var captchaRequired = true

    @Volatile
    private var serverConfigLoaded = false

    @Volatile
    private var validatedToken: String? = null

    private var cachedDetails: AnimeDetailsContainerDto? = null

    private var detailsCachedAt = 0L

    private fun apiHeaders(token: String = requireValidToken()) = headers.withBearer(token)

    private fun requireValidToken(): String {
        val token = userToken
            ?: error("Login necessário. Abra as configurações da extensão Tomato e entre com sua conta.")
        if (validatedToken == token) return token

        return synchronized(sessionLock) {
            if (validatedToken != token) {
                check(validateSession(token)) {
                    "Sua sessão da Tomato expirou. Entre novamente."
                }
                validatedToken = token
            }
            token
        }
    }

    private fun markSessionValid(token: String) {
        validatedToken = token.trim().removePrefix("Bearer ").takeIf(String::isNotEmpty)
    }

    private fun migratePreferences() {
        val currentToken = preferences.getString(PREF_TOKEN, null)?.trim()?.takeIf(String::isNotEmpty)
        val legacyToken = preferences.getString(LEGACY_TOKEN, null)
            ?.trim()
            ?.removePrefix("Bearer ")
            ?.takeIf(String::isNotEmpty)

        preferences.edit().apply {
            if (currentToken == null && legacyToken != null) putString(PREF_TOKEN, legacyToken)
            remove(LEGACY_TOKEN)
            remove(SAVED_EMAIL)
            remove(SAVED_PASSWORD)
            remove(SAVED_USERNAME)
            apply()
        }
    }

    private fun cacheDetails(details: AnimeDetailsContainerDto) {
        cachedDetails = details
        detailsCachedAt = SystemClock.elapsedRealtime()
    }

    private fun cachedDetails(animeId: Int): AnimeDetailsContainerDto? = cachedDetails?.takeIf {
        it.animeDetails.animeId == animeId && SystemClock.elapsedRealtime() - detailsCachedAt <= DETAILS_CACHE_TTL_MS
    }

    private fun loginRequiredAnime(): SAnime = SAnime.create().apply {
        url = LOGIN_REQUIRED_URL
        title = LOGIN_REQUIRED_TITLE
        description = LOGIN_REQUIRED_DESCRIPTION
        status = SAnime.UNKNOWN
    }

    private fun hasValidSession(): Boolean = userToken != null && runCatching {
        requireValidToken()
    }.isSuccess

    private fun refreshServerConfig() {
        if (serverConfigLoaded) return

        synchronized(serverConfigLock) {
            if (serverConfigLoaded) return@synchronized

            val payload = CheckUpdateRequestDto(COMPATIBLE_APP_VERSION)
            val request = POST(
                "$PROD_API_URL/checkupdate/",
                headers.withNativeAuthHeaders(),
                payload.toJsonRequestBody(),
            )
            val config = client.newCall(request).execute().use { response ->
                response.requireSuccess()
                val resolvedApiUrl = response.request.url.let { "${it.scheme}://${it.host}" }
                resolvedApiUrl to response.parseAs<CheckUpdateResponseDto>()
            }

            val (resolvedApiUrl, response) = config
            if (response.statusCode != 4) {
                throw IOException("A Tomato não aceitou a configuração inicial.")
            }

            configuredApiUrl = resolvedApiUrl
            officialAppVersion = response.serverVersion
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: COMPATIBLE_APP_VERSION
            captchaRequired = response.requireCaptcha ?: true
            serverConfigLoaded = true
        }
    }

    private fun validateSession(token: String): Boolean {
        refreshServerConfig()
        val payload = TokenLoginRequestDto(
            token = token,
            fingerprint = Auth.deviceFingerprint,
        )
        val request = POST(
            "$configuredApiUrl/tokenlogin/",
            headers.withNativeAuthHeaders(),
            payload.toJsonRequestBody(),
        )

        val authRes = client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                null
            } else {
                response.requireSuccess().parseAs<TokenLoginResponseDto>()
            }
        }
        if (authRes?.statusCode != 4) {
            clearSession()
            return false
        }

        authRes.userName?.takeIf(String::isNotBlank)?.let { name ->
            preferences.edit().putString(PREF_USER_NAME, name).apply()
        }
        return true
    }

    private fun clearSession() {
        validatedToken = null
        preferences.edit()
            .remove(PREF_TOKEN)
            .remove(PREF_USER_NAME)
            .apply()
    }

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = if (hasValidSession()) super.getPopularAnime(page) else AnimesPage(listOf(loginRequiredAnime()), false)

    override fun popularAnimeRequest(page: Int): Request {
        val token = requireValidToken()
        return GET("$baseUrl/v2/animes/feed", apiHeaders(token))
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val feed = response.requireSuccess().parseAs<FeedResponseDto>()

        // Mapeia previamente todos os títulos disponíveis nas outras seções do mesmo feed
        for (section in feed.data) {
            for (item in section.data) {
                val id = item.animeId ?: item.epAnimeId
                val name = item.animeName ?: item.name ?: item.title
                if (id != null && !name.isNullOrBlank()) {
                    titleCache[id] = name
                }
            }
        }

        // Filtra estritamente a seção "Em alta" (type: 3)
        val emAltaSection = feed.data.firstOrNull { it.type == 3 || it.title.equals("Em alta", ignoreCase = true) }
            ?: return AnimesPage(emptyList(), false)

        val animeList = emAltaSection.data.mapNotNull { item ->
            val id = item.animeId ?: item.epAnimeId ?: return@mapNotNull null
            val title = titleCache[id] ?: item.animeName ?: item.name ?: item.title ?: "Anime #$id"
            SAnime.create().apply {
                this.title = title
                thumbnail_url = item.thumbnail ?: item.image ?: item.cover ?: item.banner
                url = "/v2/anime/$id"
            }
        }

        return AnimesPage(animeList, hasNextPage = false)
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage = if (hasValidSession()) super.getLatestUpdates(page) else AnimesPage(listOf(loginRequiredAnime()), false)

    override fun latestUpdatesRequest(page: Int): Request {
        val token = requireValidToken()
        return GET("$baseUrl/v2/animes/feed", apiHeaders(token))
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val feed = response.requireSuccess().parseAs<FeedResponseDto>()

        // Filtra a seção "Novos episódios" (type: 7)
        val latestSection = feed.data.firstOrNull { it.type == 7 || it.title.equals("Novos episódios", ignoreCase = true) }
            ?: return AnimesPage(emptyList(), false)

        val seenIds = mutableSetOf<Int>()
        val animeList = mutableListOf<SAnime>()

        for (item in latestSection.data) {
            val id = item.epAnimeId ?: item.animeId ?: continue
            if (seenIds.add(id)) {
                animeList.add(
                    SAnime.create().apply {
                        title = item.animeName ?: "Anime #$id"
                        thumbnail_url = item.thumbnail
                        url = "/v2/anime/$id"
                    },
                )
            }
        }

        return AnimesPage(animeList, hasNextPage = false)
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage = if (hasValidSession()) super.getSearchAnime(page, query, filters) else AnimesPage(listOf(loginRequiredAnime()), false)

    override fun getFilterList() = Filters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val token = requireValidToken()
        val genres = Filters.selectedGenres(filters)
        val requestDto = SearchRequestDto(
            token = token,
            search = query.trim(),
            contentType = "anime",
            page = page - 1,
            tags = genres.takeIf { it.isNotEmpty() },
        )
        return POST("https://edge.betomato.com/v2/content/search", apiHeaders(token), requestDto.toJsonRequestBody())
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val searchDto = response.requireSuccess().parseAs<SearchResponseDto>()
        val animeList = searchDto.result.map { it.toSAnime() }
        val hasNextPage = searchDto.result.size >= 50
        return AnimesPage(animeList, hasNextPage = hasNextPage)
    }

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> = emptyList()

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = if (anime.url == LOGIN_REQUIRED_URL) anime else super.getAnimeDetails(anime)

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", apiHeaders())

    override fun animeDetailsParse(response: Response): SAnime {
        val detailsContainer = response.requireSuccess().parseAs<AnimeDetailsContainerDto>()
        cacheDetails(detailsContainer)
        val details = detailsContainer.animeDetails
        titleCache[details.animeId] = details.animeName
        return detailsContainer.toSAnime()
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", apiHeaders())

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        if (anime.url == LOGIN_REQUIRED_URL) return emptyList()
        val animeId = anime.url.substringAfterLast('/').toIntOrNull()
            ?: throw IllegalArgumentException("ID de anime Tomato inválido")
        val details = cachedDetails(animeId) ?: client.newCall(episodeListRequest(anime)).awaitSuccess().use {
            it.parseAs<AnimeDetailsContainerDto>().also(::cacheDetails)
        }
        return loadEpisodes(details)
    }

    override fun episodeListParse(response: Response): List<SEpisode> = loadEpisodes(response.requireSuccess().parseAs())

    private fun loadEpisodes(animeDetails: AnimeDetailsContainerDto): List<SEpisode> {
        val seasons = animeDetails.animeSeasons
        val token = requireValidToken()
        val episodesByNumber = linkedMapOf<Pair<Int, Float>, SEpisode>()

        seasons.sortedBy { it.seasonNumber ?: 1 }.forEach { season ->
            var page = 0
            while (true) {
                val requestDto = SeasonEpisodesRequestDto(
                    token = token,
                    page = page,
                    order = "ASC",
                )
                val request = POST(
                    "$baseUrl/season/${season.seasonId}/episodes",
                    apiHeaders(token),
                    requestDto.toJsonRequestBody(),
                )

                val episodesRes = client.newCall(request).execute().use {
                    it.requireSuccess().parseAs<SeasonEpisodesResponseDto>()
                }
                val episodes = episodesRes.data
                if (episodes.isEmpty()) break

                episodes.forEach { ep ->
                    val seasonNumber = season.seasonNumber ?: 1
                    val isDubbed = season.seasonDubbed == 1 || ep.dubbed == true
                    val lang = if (isDubbed) "Dublado" else "Legendado"
                    val key = seasonNumber to ep.epNumber
                    val existing = episodesByNumber[key]
                    if (existing == null) {
                        episodesByNumber[key] = SEpisode.create().apply {
                            name = "T${seasonNumber}E${formatEpisode(ep.epNumber)} - ${ep.epName}"
                            episode_number = seasonNumber + ep.epNumber / 1000f
                            url = "/v2/anime/episode/${ep.epId}/stream"
                            scanlator = lang
                        }
                    } else if (lang !in existing.scanlator.orEmpty()) {
                        existing.url += "?alternate=${ep.epId}"
                        existing.scanlator = "${existing.scanlator} e $lang"
                    }
                }

                val totalCount = episodesRes.episodes ?: 0
                val loadedCount = (page * 25) + episodes.size
                if (episodes.size < 25 || (totalCount > 0 && loadedCount >= totalCount)) {
                    break
                }
                page++
            }
        }

        return episodesByNumber.values.sortedByDescending(SEpisode::episode_number)
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val primaryId = episode.url
            .substringBefore('?')
            .removeSuffix("/stream")
            .substringAfterLast('/')
            .toIntOrNull()
            ?: throw IllegalArgumentException("ID de episódio Tomato inválido")
        val alternateId = episode.url.substringAfter("alternate=", "").toIntOrNull()
        val languages = episode.scanlator.orEmpty().split(" e ")
        val streams = buildList {
            add(primaryId to languages.firstOrNull())
            alternateId?.let { add(it to languages.getOrNull(1)) }
        }

        return streams.flatMap { (episodeId, language) ->
            val request = GET("$baseUrl/v2/anime/episode/$episodeId/stream", apiHeaders())
            val info = client.newCall(request).awaitSuccess().use { it.parseAs<EpisodeInfoDto>() }
            info.toVideos(language)
        }.sortVideos()
    }

    override fun videoListRequest(episode: SEpisode): Request = GET("$baseUrl${episode.url}", apiHeaders())

    override fun videoListParse(response: Response): List<Video> {
        val info = response.requireSuccess().parseAs<EpisodeInfoDto>()
        return info.toVideos().sortVideos()
    }

    private fun EpisodeInfoDto.toVideos(language: String? = null): List<Video> {
        val videoList = mutableListOf<Video>()

        val streams = listOfNotNull(
            streams.fhd?.takeIf { it.isNotBlank() }?.let { it to "1080p" },
            streams.mhd?.takeIf { it.isNotBlank() }?.let { it to "720p" },
            streams.shd?.takeIf { it.isNotBlank() }?.let { it to "480p" },
        )

        for ((streamUrl, label) in streams) {
            val isHls = streamUrl.contains(".m3u8", ignoreCase = true)
            if (isHls) {
                val hlsVideos = runCatching {
                    playlistUtils.extractFromHls(
                        playlistUrl = streamUrl,
                        videoNameGen = { quality -> videoLabel(language, quality.ifBlank { label }) },
                    )
                }.getOrNull().orEmpty()

                if (hlsVideos.isNotEmpty()) {
                    videoList.addAll(hlsVideos)
                } else {
                    videoList.add(Video(streamUrl, videoLabel(language, label), streamUrl, headers = headers))
                }
            } else {
                videoList.add(Video(streamUrl, videoLabel(language, label), streamUrl, headers = headers))
            }
        }

        return videoList
    }

    private fun videoLabel(language: String?, quality: String) = listOfNotNull(language?.takeIf(String::isNotBlank), quality).joinToString(" - ")

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        return sortedWith(
            compareByDescending { it.videoTitle.contains(quality) },
        )
    }

    private fun formatEpisode(number: Float) = if (number % 1f == 0f) number.toInt().toString() else number.toString()

    // ============================= Auth Actions ===========================

    private fun prepareAuthentication(
        context: Context,
        onReady: (Boolean) -> Unit,
    ) {
        Thread {
            val result = runCatching {
                refreshServerConfig()
                captchaRequired
            }
            handler.post {
                result
                    .onSuccess(onReady)
                    .onFailure { error ->
                        Toast.makeText(
                            context,
                            "Não foi possível iniciar a autenticação: ${error.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
            }
        }.start()
    }

    private fun performLogin(
        email: String,
        pass: String,
        captchaToken: String,
        context: Context,
        accountStatusPref: EditTextPreference? = null,
    ) {
        val payload = LoginRequestDto(
            email = email,
            password = pass,
            verification = captchaToken,
            fingerprint = Auth.deviceFingerprint,
        )

        val request = POST(
            "$configuredApiUrl/login/",
            headers.withNativeAuthHeaders(),
            payload.toJsonRequestBody(),
        )

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handler.post {
                    Toast.makeText(context, "Erro de conexão ao entrar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val bodyStr = response.body.string()
                val authRes = runCatching { bodyStr.parseAs<AuthResponseDto>() }.getOrNull()
                handler.post {
                    if (authRes?.statusCode == 4 && !authRes.token.isNullOrBlank()) {
                        val name = authRes.userName ?: "Usuário"
                        preferences.edit()
                            .putString(PREF_TOKEN, authRes.token)
                            .putString(PREF_USER_NAME, name)
                            .apply()
                        markSessionValid(authRes.token)
                        accountStatusPref?.summary = "Conectado como: $name"
                        Toast.makeText(context, "Login realizado com sucesso! Bem-vindo(a), $name", Toast.LENGTH_LONG).show()
                    } else {
                        val msg = authRes?.message ?: "Falha na autenticação (Código: ${authRes?.statusCode})"
                        Toast.makeText(context, "Erro no login: $msg", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun performRegister(
        username: String,
        email: String,
        pass: String,
        captchaToken: String,
        context: Context,
        accountStatusPref: EditTextPreference? = null,
    ) {
        val payload = RegisterRequestDto(
            username = username,
            email = email,
            password = pass,
            verification = captchaToken,
            fingerprint = Auth.deviceFingerprint,
        )

        val request = POST(
            "$configuredApiUrl/register/",
            headers.withNativeAuthHeaders(),
            payload.toJsonRequestBody(),
        )

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handler.post {
                    Toast.makeText(context, "Erro de conexão ao cadastrar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val bodyStr = response.body.string()
                val authRes = runCatching { bodyStr.parseAs<AuthResponseDto>() }.getOrNull()
                handler.post {
                    if (authRes?.statusCode == 4 && !authRes.token.isNullOrBlank()) {
                        preferences.edit()
                            .putString(PREF_TOKEN, authRes.token)
                            .putString(PREF_USER_NAME, username)
                            .apply()
                        markSessionValid(authRes.token)
                        accountStatusPref?.summary = "Conectado como: $username"
                        Toast.makeText(context, "Conta criada e conectada com sucesso!", Toast.LENGTH_LONG).show()
                    } else {
                        val msg = authRes?.message ?: "Falha ao criar conta (Código: ${authRes?.statusCode})"
                        Toast.makeText(context, "Erro no cadastro: $msg", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun validateSavedSession(
        context: Context,
        accountStatusPref: EditTextPreference?,
    ) {
        val token = userToken ?: return
        Thread {
            val result = runCatching {
                refreshServerConfig()
                val payload = TokenLoginRequestDto(
                    token = token,
                    fingerprint = Auth.deviceFingerprint,
                )
                val request = POST(
                    "$configuredApiUrl/tokenlogin/",
                    headers.withNativeAuthHeaders(),
                    payload.toJsonRequestBody(),
                )

                client.newCall(request).execute().use { response ->
                    val sessionRejected = response.code == 401 || response.code == 403
                    val bodyStr = response.body.string()
                    val authRes = runCatching { bodyStr.parseAs<TokenLoginResponseDto>() }.getOrNull()
                    sessionRejected to authRes
                }
            }

            result.onSuccess { (sessionRejected, authRes) ->
                handler.post {
                    if (authRes?.statusCode == 4) {
                        markSessionValid(token)
                        val name = authRes.userName?.takeIf(String::isNotBlank) ?: userName ?: "Usuário"
                        preferences.edit().putString(PREF_USER_NAME, name).apply()
                        accountStatusPref?.summary = "Conectado como: $name"
                    } else if (sessionRejected || authRes?.statusCode == 1) {
                        clearSession()
                        accountStatusPref?.summary = "Sessão expirada — faça login novamente"
                    }
                }
            }
        }.start()
    }

    // ============================= Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        preferencesReady
        val context = screen.context

        // 0. Qualidade preferida
        ListPreference(context).apply {
            key = PREF_QUALITY
            title = "Qualidade preferida"
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                preferences.edit().putString(key, selected).apply()
                false
            }
        }.also(screen::addPreference)

        // 1. Status da Conta (Apenas leitura)
        val accountStatusPref = EditTextPreference(context).apply {
            key = PREF_ACCOUNT_STATUS
            title = "Status da Conta"
            summary = if (!userToken.isNullOrBlank()) {
                "Conectado como: ${userName ?: "Usuário"}"
            } else {
                "Não conectado — use a ação de login abaixo"
            }
            setEnabled(false)
        }
        screen.addPreference(accountStatusPref)

        // 2. Token de Acesso Manual (Opcional / Recuperação direta)
        val tokenPref = EditTextPreference(context).apply {
            key = PREF_TOKEN
            title = "Token de Autenticação (Manual)"
            summary = preferences.getString(PREF_TOKEN, "")?.takeIf { it.isNotBlank() }?.let { "${it.take(15)}..." } ?: "Toque para colar um token Bearer diretamente"
            setOnPreferenceChangeListener { _, newValue ->
                val token = (newValue as? String)?.trim()?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() }
                validatedToken = null
                if (token != null) {
                    preferences.edit().putString(PREF_TOKEN, token).apply()
                    summary = "${token.take(15)}..."
                    validateSavedSession(context, accountStatusPref)
                } else {
                    clearSession()
                    summary = "Toque para colar um token Bearer diretamente"
                }
                false
            }
        }
        screen.addPreference(tokenPref)

        if (!userToken.isNullOrBlank()) {
            validateSavedSession(context, accountStatusPref)
        }

        // 3. Menu de Ações (Login / Cadastro / Logout)
        val actionPref = ListPreference(context).apply {
            key = PREF_AUTH_ACTION
            title = "Ação"
            summary = "Toque para escolher uma ação"
            entries = arrayOf("<Selecione uma Ação>", "Fazer Login", "Registrar nova Conta", "Sair da conta")
            entryValues = arrayOf("none", "login", "register", "logout")
            setDefaultValue("none")
            setOnPreferenceChangeListener { _, newValue ->
                when (newValue as? String) {
                    "login" -> {
                        Auth.showLoginInputDialog(context) { email, pass ->
                            prepareAuthentication(context) { requireCaptcha ->
                                if (requireCaptcha) {
                                    Auth.showCaptchaDialog(context, handler) { captchaToken ->
                                        performLogin(email, pass, captchaToken, context, accountStatusPref)
                                    }
                                } else {
                                    performLogin(email, pass, "", context, accountStatusPref)
                                }
                            }
                        }
                    }
                    "register" -> {
                        Auth.showRegisterInputDialog(context) { username, email, pass ->
                            prepareAuthentication(context) { requireCaptcha ->
                                if (requireCaptcha) {
                                    Auth.showCaptchaDialog(context, handler) { captchaToken ->
                                        performRegister(username, email, pass, captchaToken, context, accountStatusPref)
                                    }
                                } else {
                                    performRegister(username, email, pass, "", context, accountStatusPref)
                                }
                            }
                        }
                    }
                    "logout" -> {
                        clearSession()
                        accountStatusPref.summary = "Não conectado — use a ação de login abaixo"
                        Toast.makeText(context, "Desconectado com sucesso", Toast.LENGTH_SHORT).show()
                    }
                }
                false
            }
        }
        screen.addPreference(actionPref)
    }

    companion object {
        private const val LOGIN_REQUIRED_URL = "/login-required"
        private const val LOGIN_REQUIRED_TITLE = "🔐 Login necessário"
        private const val LOGIN_REQUIRED_DESCRIPTION = "Entre na sua conta Tomato pelas configurações da extensão para acessar o catálogo. Configurações → Ação → Fazer Login"
        private const val COMPATIBLE_APP_VERSION = "1.4.3"
        private const val DETAILS_CACHE_TTL_MS = 30_000L
        private const val PREF_TOKEN = "pref_user_token"
        private const val PREF_USER_NAME = "pref_user_name"
        private const val PREF_ACCOUNT_STATUS = "pref_account_status"
        private const val PREF_AUTH_ACTION = "pref_auth_action"
        private const val PREF_QUALITY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private const val LEGACY_TOKEN = "tomato_official_session_token_v1"
        private const val SAVED_EMAIL = "pref_saved_email"
        private const val SAVED_PASSWORD = "pref_saved_password"
        private const val SAVED_USERNAME = "pref_saved_username"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p")
    }
}
