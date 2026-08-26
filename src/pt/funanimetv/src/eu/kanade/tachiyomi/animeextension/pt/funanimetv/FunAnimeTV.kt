package eu.kanade.tachiyomi.animeextension.pt.funanimetv

import android.os.Build
import android.text.Html
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animeextension.pt.funanimetv.dto.CategoryFullItemDto
import eu.kanade.tachiyomi.animeextension.pt.funanimetv.dto.GetAppDetailsResponse
import eu.kanade.tachiyomi.animeextension.pt.funanimetv.dto.GetHomeVideosResponse
import eu.kanade.tachiyomi.animeextension.pt.funanimetv.dto.SearchVideoItemDto
import eu.kanade.tachiyomi.animeextension.pt.funanimetv.dto.SeasonDto
import eu.kanade.tachiyomi.animeextension.pt.funanimetv.dto.SingleVideoItemDto
import eu.kanade.tachiyomi.animeextension.pt.funanimetv.dto.VideoByCatItemDto
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.seconds

class FunAnimeTV :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    override val name = "Fun Anime TV"
    override val baseUrl = "https://funanimetv.cyou"
    private val apiUrl = "$baseUrl/api_impar.php"
    private val bootstrapUrl = "$baseUrl/valid_g_impar.php"
    override val lang = "pt-BR"
    override val supportsLatest = true
    private val preferences by getPreferencesLazy()
    private val seasonCache = mutableMapOf<String, List<SeasonDto>>()
    private val categoryCoverCache = ConcurrentHashMap<String, String>()

    @Volatile private var categoryCoverCacheLoaded = false
    private val legacyIdToRelVid = ConcurrentHashMap<String, String>()
    private val bloggerExtractor by lazy { BloggerExtractor(client) }

    @Volatile private var pendingBloggerFallback: String? = null

    override fun headersBuilder() = super.headersBuilder().apply {
        set("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 16; M2007J20CG Build/BP3A.250905.014)")
    }

    override val client by lazy {
        network.client.newBuilder()
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = chain.request().body
                if (body is SignedRequestBody && !body.retried &&
                    response.peekBody(1024 * 1024).string().contains("invalid sign salt", ignoreCase = true)
                ) {
                    response.close()
                    synchronized(stateLock) { cachedState = null }
                    return@addInterceptor chain.proceed(
                        chain.request().newBuilder()
                            .method("POST", body.copy(retried = true))
                            .build(),
                    )
                }
                response
            }
            .rateLimit(5, 1.seconds)
            .retryOnConnectionFailure(true)
            .build()
    }

    override fun popularAnimeRequest(page: Int) = POST(apiUrl, headers, createRequestJson("get_home_videos"))

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = response.getByArrayKey<GetHomeVideosResponse>()
        return AnimesPage(data.mostViewed.map { it.toAnime() }, false)
    }

    override fun latestUpdatesRequest(page: Int) = POST(apiUrl, headers, createRequestJson("get_home_videos"))

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val data = response.getByArrayKey<GetHomeVideosResponse>()
        val categoryCovers = loadCategoryCovers()
        val categories = data.allVideoCat.associateBy { it.categoryName }
        val items = (data.latestVideo + data.latestVideoDub).map { video ->
            val category = categories[video.categoryName]
            SAnime.create().apply {
                title = video.categoryName
                description = category?.sinopse ?: video.videoTitle
                thumbnail_url = categoryCovers[video.catId]
                    ?: category?.categoryImage
                    ?: video.videoThumbnailB
                url = baseUrl.toHttpUrl().newBuilder().apply {
                    addQueryParameter("id", video.id)
                    category?.let {
                        addQueryParameter("cid", it.cid)
                        addQueryParameter("tid", it.tid)
                    }
                }.build().toString()
            }
        }.distinctBy { it.url }
        return AnimesPage(items, false)
    }

    private fun loadCategoryCovers(): Map<String, String> {
        if (!categoryCoverCacheLoaded) {
            synchronized(categoryCoverCache) {
                if (!categoryCoverCacheLoaded) {
                    runCatching {
                        client.newCall(
                            POST(apiUrl, headers, createRequestJson("get_category_full")),
                        ).execute().getByArrayKey<List<CategoryFullItemDto>>()
                            .forEach { category ->
                                val cover = category.categoryImage.ifBlank { category.categoryImageThumb }
                                if (category.cid.isNotBlank() && cover.isNotBlank()) {
                                    categoryCoverCache[category.cid] = cover
                                }
                            }
                    }
                    categoryCoverCacheLoaded = true
                }
            }
        }
        return categoryCoverCache
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = FunAnimeTVFilters.getSearchParameters(filters)
        val data = buildJsonObject {
            put("search_text", query)
            put("sort", "recent")
            put("limit", 60)
            if (params.genre.isNotBlank()) put("genre", params.genre)
        }
        return POST(apiUrl, headers, createRequestJson("search_unified", data))
    }

    override fun searchAnimeParse(response: Response): AnimesPage = AnimesPage(
        response.getByArrayKey<List<SearchVideoItemDto>>().map { it.toAnime() },
        false,
    )

    override fun getFilterList() = FunAnimeTVFilters.FILTER_LIST
    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val url = anime.url.toHttpUrl()
        if (!url.queryParameter("cid").isNullOrBlank()) {
            anime.description?.takeIf(String::isNotBlank)?.let { anime.description = it.decodeHtml() }
            return anime
        }
        val id = url.queryParameter("id") ?: return anime
        val data = client.newCall(
            POST(
                apiUrl,
                headers,
                createRequestJson(
                    "get_single_video",
                    buildJsonObject {
                        put("video_id", id)
                    },
                ),
            ),
        ).execute().getByArrayKey<List<SingleVideoItemDto>>().firstOrNull() ?: return anime
        anime.url = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("cid", data.catId)
            .addQueryParameter("tid", data.tempId)
            .addQueryParameter("id", data.videoId.ifBlank { id })
            .build().toString()
        if (anime.title.isBlank()) anime.title = data.categoryName.ifBlank { data.videoTitle }
        if (anime.thumbnail_url.isNullOrBlank()) anime.thumbnail_url = data.tempImage.ifBlank { data.videoThumbnailB }
        if (data.videoDescription.isNotBlank()) anime.description = data.videoDescription.decodeHtml()
        return anime
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val url = runBlocking { getAnimeDetails(anime).url.toHttpUrl() }
        val cid = url.queryParameter("cid").orEmpty()
        val tid = url.queryParameter("tid").orEmpty()
        ensureSeasonCache(url, cid, tid)
        return POST(
            apiUrl,
            headers,
            createRequestJson(
                "get_video_temp",
                buildJsonObject {
                    put("temp_id", tid)
                    if (cid.isNotBlank()) put("cat_id", cid)
                },
            ),
        )
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val current = response.getByArrayKey<List<VideoByCatItemDto>>()
        val currentTempId = current.firstOrNull()?.tempId.orEmpty()
        val cid = current.firstOrNull()?.catId.orEmpty()
        val seasons = seasonCache[cid].orEmpty()
            .filter { it.tempId.isNotBlank() }
            .distinctBy { it.tempId }
        val seasonIndex = seasons.mapIndexed { index, season -> season.tempId to index + 1 }.toMap()
        val ambiguousSeasonLabels = seasons
            .mapNotNull { Regex("T(\\d+)", RegexOption.IGNORE_CASE).find(it.tempName)?.groupValues?.get(1) }
            .groupingBy { it }
            .eachCount()
            .values
            .any { it > 1 }
        val allEpisodes = buildList {
            addAll(current.map { it to (seasonIndex[it.tempId] ?: 1) })
            seasons.filter { it.tempId != currentTempId }.forEach { season ->
                fetchEpisodes(season.tempId, cid).forEach { add(it to (seasonIndex[season.tempId] ?: 1)) }
            }
        }
        return allEpisodes
            .mapNotNull { (episode, seasonNumber) ->
                val id = episode.relVid.numericId() ?: episode.id.numericId()
                if (id.isNullOrBlank()) return@mapNotNull null
                episode.videoId
                    .takeIf { it.looksLikeBloggerUrl() }
                    ?.let { legacyIdToRelVid[it.normalizeLegacyUrl()] = id }
                val episodeUrl = baseUrl.toHttpUrl().newBuilder().addQueryParameter("id", id).build().toString()
                val episodeNumber = episode.videoEp.filter { it.isDigit() }.toFloatOrNull() ?: 1F
                val displayName = if (ambiguousSeasonLabels) {
                    "${episode.tempName.ifBlank { "Temporada %02d".format(seasonNumber) }} - ${episode.videoTitle.ifBlank { episode.videoEp }}"
                } else {
                    episode.videoTitle.ifBlank { episode.videoEp }
                }
                SEpisode.create().apply {
                    url = episodeUrl
                    name = displayName
                    episode_number = if (ambiguousSeasonLabels) seasonNumber + episodeNumber / 1000F else episodeNumber
                    scanlator = episode.videoEp
                }
            }.distinctBy { it.url }.sortedBy { it.episode_number }
    }

    private fun ensureSeasonCache(url: okhttp3.HttpUrl, cid: String, tid: String) {
        if (cid.isBlank() || seasonCache.containsKey(cid)) return
        val videoId = url.queryParameter("id") ?: run {
            val current = client.newCall(
                POST(
                    apiUrl,
                    headers,
                    createRequestJson(
                        "get_video_temp",
                        buildJsonObject {
                            put("temp_id", tid)
                            put("cat_id", cid)
                        },
                    ),
                ),
            ).execute().getByArrayKey<List<VideoByCatItemDto>>()
            current.firstOrNull()?.relVid?.takeIf { it.isNotBlank() && it != "0" }
        } ?: return
        val details = client.newCall(
            POST(
                apiUrl,
                headers,
                createRequestJson(
                    "get_single_video",
                    buildJsonObject {
                        put("video_id", videoId)
                    },
                ),
            ),
        ).execute().getByArrayKey<List<SingleVideoItemDto>>().firstOrNull()
        seasonCache[cid] = details?.seasons.orEmpty()
    }

    private fun fetchEpisodes(tempId: String, catId: String): List<VideoByCatItemDto> = client.newCall(
        POST(
            apiUrl,
            headers,
            createRequestJson(
                "get_video_temp",
                buildJsonObject {
                    put("temp_id", tempId)
                    if (catId.isNotBlank()) put("cat_id", catId)
                },
            ),
        ),
    ).execute().getByArrayKey()

    override fun videoListRequest(episode: SEpisode): Request {
        val episodeUrl = episode.url
        val id = episodeUrl.toHttpUrl().queryParameter("id").orEmpty()
        val numericId = id.numericId()
        val normalizedLegacyId = id.takeUnless { numericId != null }
            ?.takeIf { it.looksLikeBloggerUrl() }
            ?.normalizeLegacyUrl()
        val resolvedId = numericId ?: normalizedLegacyId?.let { legacyIdToRelVid[it] }
        pendingBloggerFallback = null
        if (numericId == null && resolvedId == null && id.looksLikeBloggerUrl()) {
            pendingBloggerFallback = id
            return GET(id, headers)
        }
        return POST(
            apiUrl,
            headers,
            createRequestJson(
                "get_single_video",
                buildJsonObject {
                    put("video_id", resolvedId.orEmpty())
                },
            ),
        )
    }

    override fun videoListParse(response: Response): List<Video> {
        pendingBloggerFallback?.let { bloggerUrl ->
            pendingBloggerFallback = null
            return runBlocking { bloggerExtractor.videosFromUrl(bloggerUrl, headers) }
        }
        val data = response.getByArrayKey<List<SingleVideoItemDto>>().firstOrNull() ?: return emptyList()
        fun candidate(value: String, quality: String): Video? = value.validVideo()?.let { Video(it, quality, it) }
        val videos = listOfNotNull(
            candidate(data.videoUrlFhd, "1080p"),
            candidate(data.videoUrl, "720p"),
            candidate(data.videoUrlSd, "480p"),
        ).distinctBy { it.videoUrl }.sort()
        return videos
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = PREF_LANGUAGE_TITLE
            entries = PREF_LANGUAGE_VALUES
            entryValues = PREF_LANGUAGE_VALUES
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT).orEmpty()
        val language = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT).orEmpty()
        return sortedWith(
            compareByDescending<Video> { it.quality.contains(language) }
                .thenByDescending { it.quality.contains(quality) }
                .thenByDescending { REGEX_QUALITY.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
        )
    }

    private fun createRequestJson(methodName: String, additionalParams: JsonObject? = null): RequestBody = SignedRequestBody(methodName, additionalParams)

    private inner class SignedRequestBody(
        val methodName: String,
        val additionalParams: JsonObject?,
        val retried: Boolean = false,
    ) : RequestBody() {
        fun copy(retried: Boolean) = SignedRequestBody(methodName, additionalParams, retried)
        override fun contentType() = "application/x-www-form-urlencoded".toMediaType()

        override fun writeTo(sink: BufferedSink) {
            val state = bootstrapState()
            val salt = (0 until 900).random().toString()
            val json = buildJsonObject {
                put("salt", salt)
                put("sign", hmacSha256(state.signSalt, methodName + ":" + salt))
                put("sign_version", "2")
                put("method_name", methodName)
                additionalParams?.forEach { (key, value) -> put(key, value) }
            }.toString()
            FormBody.Builder()
                .add("data", Base64.encodeToString(json.toByteArray(StandardCharsets.UTF_8), Base64.DEFAULT))
                .build()
                .writeTo(sink)
        }
    }

    private fun bootstrapState(): Constants {
        synchronized(stateLock) {
            cachedState?.let { return it }
            val salt = (0 until 900).random().toString()
            val json = buildJsonObject {
                put("salt", salt)
                put("sign", hmacSha256(INITIAL_HMAC_KEY, "get_app_details:" + salt))
                put("sign_version", "2")
                put("method_name", "get_app_details")
            }.toString()
            val body = FormBody.Builder()
                .add("data", Base64.encodeToString(json.toByteArray(StandardCharsets.UTF_8), Base64.DEFAULT))
                .build()
            val config = client.newCall(POST(bootstrapUrl, headers, body)).execute()
                .parseAs<Map<String, List<GetAppDetailsResponse>>>()
                .values.firstOrNull()?.firstOrNull()
                ?: error("Bootstrap sem configuração")
            check(config.singsalt.isNotBlank() && config.arrayPadrao.isNotBlank()) { "Bootstrap incompleto" }
            return Constants(config.singsalt, config.arrayPadrao).also { cachedState = it }
        }
    }

    private inline fun <reified T> Response.getByArrayKey(): T {
        val state = bootstrapState()
        return parseAs<Map<String, T>>()[state.arrayPadrao]
            ?: error("Chave de resposta ausente: " + state.arrayPadrao)
    }

    private fun hmacSha256(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun String.validVideo() = takeIf { startsWith("http://") || startsWith("https://") }

    private fun String.numericId(): String? = trim().takeIf { value ->
        value.toLongOrNull()?.let { it > 0 } == true
    }

    private fun String.looksLikeBloggerUrl() = contains("blogger.com/video", ignoreCase = true)

    private fun String.normalizeLegacyUrl(): String = trim().let { value ->
        val withScheme = if (value.startsWith("www.", ignoreCase = true)) "https://$value" else value
        runCatching { withScheme.toHttpUrl().toString() }.getOrDefault(value)
    }

    private fun String.decodeHtml(): String = if (Build.VERSION.SDK_INT >= 24) {
        Html.fromHtml(this, Html.FROM_HTML_MODE_LEGACY)
    } else {
        @Suppress("DEPRECATION")
        Html.fromHtml(this)
    }
        .toString()
        .trim()

    private fun GetHomeVideosResponse.MostViewed.toAnime() = SAnime.create().apply {
        url = baseUrl.toHttpUrl().newBuilder().apply {
            addQueryParameter("cid", cid)
            if (tid.isNotBlank()) addQueryParameter("tid", tid)
        }.build().toString()
        title = categoryName
        description = sinopse
        genre = genero
        thumbnail_url = categoryImage
    }

    private fun SearchVideoItemDto.toAnime() = SAnime.create().apply {
        url = baseUrl.toHttpUrl().newBuilder().apply {
            addQueryParameter("cid", cid)
            if (tid.isNotBlank()) addQueryParameter("tid", tid)
        }.build().toString()
        title = if (isTemporada && tempName.isNotBlank()) categoryName + " | " + tempName else categoryName
        description = sinopse
        genre = genero
        thumbnail_url = categoryImage.ifBlank { categoryImageThumb }
    }

    private data class Constants(val signSalt: String, val arrayPadrao: String)

    companion object {
        private const val INITIAL_HMAC_KEY = "3fa6b856afeb4d2bde3ac9e10d3fd619b8b641455ea74476"
        private val stateLock = Any()

        @Volatile private var cachedState: Constants? = null
        private val REGEX_QUALITY = Regex("(\\d+)p")
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("480p", "720p", "1080p")
        private const val PREF_LANGUAGE_KEY = "pref_language"
        private const val PREF_LANGUAGE_DEFAULT = "Legendado"
        private const val PREF_LANGUAGE_TITLE = "Língua/tipo preferido"
        private val PREF_LANGUAGE_VALUES = arrayOf("Legendado", "Dublado")
    }
}
