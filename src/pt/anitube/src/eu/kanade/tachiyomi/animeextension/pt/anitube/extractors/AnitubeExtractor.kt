package eu.kanade.tachiyomi.animeextension.pt.anitube.extractors

import android.content.SharedPreferences
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class AnitubeExtractor(
    private val headers: Headers,
    private val client: OkHttpClient,
    private val preferences: SharedPreferences,
) {

    private val tag by lazy { javaClass.simpleName }

    private fun debug(message: String) = Log.d(DEBUG_TAG, message)

    private fun debugError(message: String, error: Throwable? = null) {
        Log.e(DEBUG_TAG, message, error)
    }

    private fun mask(value: String?): String = value?.let {
        if (it.length <= 16) "***" else "${it.take(8)}...${it.takeLast(8)}"
    } ?: "null"

    private fun validateFinalMedia(finalUrl: String, quality: String): Boolean {
        val mediaHeaders = headers.newBuilder()
            .set("Range", "bytes=0-1023")
            .build()
        debug(
            "media headers quality=$quality " +
                "userAgent=${mask(mediaHeaders["User-Agent"])} " +
                "referer=${mask(mediaHeaders["Referer"])} " +
                "origin=${mask(mediaHeaders["Origin"])} " +
                "range=${mediaHeaders["Range"]} accept=${mask(mediaHeaders["Accept"])}",
        )

        return runCatching {
            client.newCall(GET(finalUrl, headers = mediaHeaders)).execute().use { response ->
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                val statusValid = response.code == 200 || response.code == 206
                val mediaValid = contentType.startsWith("video/") ||
                    contentType.contains("mp4") || contentType.contains("mpegurl")
                debug(
                    "final media quality=$quality http=${response.code} " +
                        "contentType=${response.header("Content-Type")} " +
                        "contentRange=${response.header("Content-Range")} " +
                        "contentLength=${response.header("Content-Length")} " +
                        "redirectUrl=${mask(response.request.url.toString())}",
                )
                statusValid && mediaValid
            }
        }.onFailure { error ->
            debugError("final media quality=$quality validation exception=${error.message}", error)
        }.getOrDefault(false)
    }

    // Cache for the host-wide ADS widget content, shared across concurrent calls.
    // A CompletableDeferred is used so that only one request per adsUrl is made
    // (computeIfAbsent atomically creates the placeholder) while callers await the
    // same in-flight result.
    private val adsContentCache = ConcurrentHashMap<String, CompletableDeferred<String>>()

    @Volatile private var cachedAdblockUrl: String? = null
    private val adsClient by lazy {
        client.newBuilder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    private data class PlayerInfo(
        val playerUrl: String,
        val referer: String,
        val videoUrl: String,
    )

    class ExtractionSession internal constructor() {
        internal val media = ConcurrentHashMap<String, CompletableDeferred<Video?>>()
    }

    fun newSession() = ExtractionSession()

    private fun buildApiHeaders(referer: String): Headers = headers.newBuilder()
        .set("Referer", "https://${referer.toHttpUrl().host}/")
        .add("Accept", "*/*")
        .add("Cache-Control", "no-cache")
        .add("Pragma", "no-cache")
        .add("Connection", "keep-alive")
        .add("Sec-Fetch-Dest", "empty")
        .add("Sec-Fetch-Mode", "cors")
        .add("Sec-Fetch-Site", "same-site")
        .build()

    private fun extractPublicidadeCode(response: String): String = response
        .substringAfter("\"publicidade\"", "")
        .substringAfter('"')
        .substringBefore('"')

    private fun normalizeLink(link: String): String = if (link.startsWith("//")) "https:$link" else link

    private suspend fun fetchPlayerInfo(
        link: String,
        linkHeaders: Headers,
    ): PlayerInfo {
        val finalLink = normalizeLink(link)
        debug("fetchPlayerInfo link=${mask(finalLink)}")
        val response = client.newCall(GET(finalLink, headers = linkHeaders)).awaitSuccess()
        debug("player response http=${response.code} url=${mask(response.request.url.toString())}")
        val docLink = response.useAsJsoup()

        // Handle meta refresh redirect
        val refresh = docLink.selectFirst("meta[http-equiv=refresh]")?.attr("content")
        if (!refresh.isNullOrBlank()) {
            val newLink = refresh.substringAfter("=")
            val newHeaders = linkHeaders.newBuilder().set("Referer", finalLink).build()
            debug("meta refresh redirect to=${mask(newLink)}")
            return fetchPlayerInfo(newLink, newHeaders)
        }

        // Handle JavaScript redirect
        val jsData = docLink.data()
        if (jsData.contains("window.location.href = redirectUrl")) {
            val newLink = jsData
                .substringAfter("redirectUrl = `")
                .substringBefore("`")
                .replace("\${token}", finalLink.toHttpUrl().queryParameter("t") ?: "")
            val newHeaders = linkHeaders.newBuilder().set("Referer", finalLink).build()
            debug("javascript redirect to=${mask(newLink)}")
            return fetchPlayerInfo(newLink, newHeaders)
        }

        val novoEndereco = docLink.selectFirst("p:contains(Novo endereço)")
        if (novoEndereco != null) {
            val newLink = novoEndereco.selectFirst("strong")?.text()

            if (newLink?.startsWith("http") == true) {
                preferences.edit().putString("preferred_domain", newLink).apply()
                throw Exception("Configurado novo domínio, por favor reinicie o aplicativo")
            }

            throw Exception("Configure para o novo domínio: $newLink")
        }

        val referer = docLink.location()
        debug("final redirect url=${mask(referer)}")

        val playerUrl = docLink.selectFirst("iframe")?.attr("src")
            ?: run {
                debug("iframe/playerUrl is null; returning failure")
                throw IllegalStateException("Player iframe not found")
            }

        debug("iframe playerUrl=${mask(playerUrl)} referer=${mask(referer)}")

        val videoUrl = playerUrl.toHttpUrl().queryParameter("url")
            ?: run {
                debug("player url query parameter url is null")
                throw IllegalStateException("Player video url not found")
            }
        debug("videoUrl=${mask(videoUrl)} host=${videoUrl.toHttpUrl().host}")

        return PlayerInfo(
            playerUrl = playerUrl,
            referer = referer,
            videoUrl = videoUrl,
        )
    }

    private suspend fun fetchVideoToken(playerInfo: PlayerInfo, videoUrlOverride: String? = null): String? {
        debug("fetchVideoToken start playerUrl=${mask(playerInfo.playerUrl)}")

        val (adsUrl, adblockUrl) = try {
            val newHeaders = headers.newBuilder()
                .set("Referer", "https://${playerInfo.referer.toHttpUrl().host}/")
                .build()

            val adsResponse = client.newCall(
                GET(
                    playerInfo.playerUrl,
                    headers = newHeaders,
                ),
            ).awaitSuccess()
            debug("player page http=${adsResponse.code} url=${mask(adsResponse.request.url.toString())}")
            val body = adsResponse.bodyString()

            val ads = ADS_URL_REGEX.find(body)?.groups?.get(1)?.value
                ?.takeIf { it.startsWith("http") }
                ?: throw IllegalStateException("No valid ADS URL found")

            val adblock = body.substringAfter("$.post", "")
                .substringAfter("'")
                .substringBefore("'")
                .takeIf { it.startsWith("http") }
                ?: throw IllegalStateException("No valid ADBLOCK URL found")

            ads to adblock
        } catch (e: Exception) {
            debugError("failed to get ADS/ADBLOCK URL: ${e.message}", e)
            "https://widgets.outbrain.com/outbrain.js" to "https://ads.anitube.vip/adblock2.php"
        }
        debug("token endpoints ads=${mask(adsUrl)} adblock=${mask(adblockUrl)}")
        cachedAdblockUrl = adblockUrl

        val videoUrl = videoUrlOverride ?: playerInfo.playerUrl.toHttpUrl().queryParameter("url")!!
        val apiHeaders = buildApiHeaders(playerInfo.referer)
        var adsEndpoint = adsUrl
        repeat(2) { attempt ->
            val adsStartedAt = System.currentTimeMillis()
            val (adsContent, cacheState) = runCatching { fetchAdsContent(adsEndpoint) to "HIT" }
                .getOrElse { error ->
                    if (adsEndpoint != FALLBACK_ADS_URL) {
                        debugError("dynamic ADS failed; using fallback: ${error.message}", error)
                        adsEndpoint = FALLBACK_ADS_URL
                        fetchAdsContent(adsEndpoint) to "MISS"
                    } else {
                        throw error
                    }
                }
            debug("adsCache=$cacheState adsDownloadMs=${System.currentTimeMillis() - adsStartedAt} source=${mask(adsEndpoint)}")
            val body = FormBody.Builder()
                .add("category", "client")
                .add("type", "premium")
                .add("ad", adsContent)
                .add("url", videoUrl)
                .build()
            val postStartedAt = System.currentTimeMillis()
            val postResponse = client.newCall(POST(adblockUrl, headers = apiHeaders, body = body)).awaitSuccess()
            val token = extractPublicidadeCode(postResponse.bodyString()).ifBlank { "undefined" }
            debug("adblock POST http=${postResponse.code} authToken=${token != "undefined"} adblockPostMs=${System.currentTimeMillis() - postStartedAt}")
            if (token == "undefined") {
                adsContentCache.remove(adsEndpoint)
                if (attempt == 0) {
                    debug("adsCache=STALE; refreshing ADS and retrying once")
                    return@repeat
                }
                return ""
            }
            return try {
                val tokenStartedAt = System.currentTimeMillis()
                val tokenResponse = client.newCall(GET("$adblockUrl?token=$token&url=$videoUrl", headers = apiHeaders)).awaitSuccess()
                val videoToken = extractPublicidadeCode(tokenResponse.bodyString())
                debug("token GET http=${tokenResponse.code} videoToken=${videoToken.startsWith("?")} tokenGetMs=${System.currentTimeMillis() - tokenStartedAt}")
                if (videoToken.startsWith("?")) {
                    persistAdsCache(adsEndpoint, adblockUrl, adsContent)
                    videoToken
                } else {
                    ""
                }
            } catch (e: Exception) {
                debugError("token GET exception: ${e.message}", e)
                ""
            }
        }
        return ""
    }

    private suspend fun fetchAdsContent(adsUrl: String): String {
        adsContentCache[adsUrl]?.let { return it.await() }

        var owner = false
        val deferred = synchronized(adsContentCache) {
            adsContentCache[adsUrl] ?: CompletableDeferred<String>().also {
                adsContentCache[adsUrl] = it
                owner = true
            }
        }

        if (owner) {
            try {
                val content = adsClient.newCall(GET(adsUrl)).awaitSuccess().use { response ->
                    debug("ads endpoint http=${response.code} url=${mask(response.request.url.toString())}")
                    response.bodyString().takeIf { it.isNotBlank() }
                        ?: throw IllegalStateException("ADS response body is empty")
                }
                deferred.complete(content)
            } catch (error: Throwable) {
                adsContentCache.remove(adsUrl, deferred)
                deferred.completeExceptionally(error)
            }
        }

        return deferred.await()
    }

    suspend fun getDirectVideos(episodeId: String): List<Video> {
        val memoryAds = adsContentCache.values.firstOrNull()?.let { runCatching { it.await() }.getOrNull() }
        val persistedAds = if (memoryAds == null) loadPersistedAds() else null
        val ads = memoryAds ?: persistedAds?.first
        val adblockUrl = cachedAdblockUrl ?: persistedAds?.second
        debug("memoryCache=${if (memoryAds != null) "HIT" else "MISS"} persistedCache=${if (persistedAds != null) "HIT" else "MISS"} adsHash=${ads?.let(::hashAds)} adblockHost=${adblockUrl?.let { runCatching { it.toHttpUrl().host }.getOrNull() }}")
        if (ads == null || adblockUrl == null) return emptyList()
        val qualities = listOf("480p" to "ziphonec", "720p" to "z333", "1080p" to "zful")
        return kotlinx.coroutines.coroutineScope {
            qualities.map { (quality, path) ->
                async(kotlinx.coroutines.Dispatchers.IO) {
                    val baseUrl = "https://$R2_HOST/$path/$episodeId.mp4"
                    val token = fetchDirectVideoToken(baseUrl, ads, adblockUrl) ?: return@async null
                    val finalUrl = baseUrl + token
                    val startedAt = System.currentTimeMillis()
                    if (!validateFinalMedia(finalUrl, quality)) {
                        debug("fastPath quality=$quality mediaHttp=invalid mediaValidationMs=${System.currentTimeMillis() - startedAt}")
                        return@async null
                    }
                    debug("fastPath quality=$quality tokenReady=true mediaValidationMs=${System.currentTimeMillis() - startedAt}")
                    Video(finalUrl, quality, finalUrl, headers = headers)
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun fetchDirectVideoToken(videoUrl: String, ads: String, adblockUrl: String): String? {
        val apiHeaders = buildApiHeaders(headers["Referer"] ?: "https://www.anitube.vip/")
        val form = FormBody.Builder()
            .add("category", "client")
            .add("type", "premium")
            .add("ad", ads)
            .add("url", videoUrl)
            .build()
        return runCatching {
            val auth = client.newCall(POST(adblockUrl, headers = apiHeaders, body = form)).awaitSuccess().bodyString()
                .let(::extractPublicidadeCode)
            if (auth.isBlank() || auth == "undefined") {
                invalidatePersistedAds()
                return@runCatching null
            }
            val token = client.newCall(GET("$adblockUrl?token=$auth&url=$videoUrl", headers = apiHeaders))
                .awaitSuccess().bodyString().let(::extractPublicidadeCode)
            token.takeIf { it.startsWith("?") }
        }.onFailure { error -> debugError("direct token failed: ${error.message}", error) }.getOrNull()
    }

    private fun persistAdsCache(adsUrl: String, adblockUrl: String, content: String) {
        preferences.edit()
            .putString(PREF_ADS_CONTENT, content)
            .putString(PREF_ADS_URL, adsUrl)
            .putString(PREF_ADBLOCK_URL, adblockUrl)
            .putLong(PREF_ADS_TIMESTAMP, System.currentTimeMillis())
            .putString(PREF_ADS_HASH, hashAds(content))
            .apply()
    }

    private fun loadPersistedAds(): Pair<String, String>? {
        val content = preferences.getString(PREF_ADS_CONTENT, null)
        val adblock = preferences.getString(PREF_ADBLOCK_URL, null)
        if (content.isNullOrBlank() || adblock.isNullOrBlank()) return null
        return content to adblock
    }

    private fun invalidatePersistedAds() {
        preferences.edit()
            .remove(PREF_ADS_CONTENT).remove(PREF_ADS_URL).remove(PREF_ADBLOCK_URL)
            .remove(PREF_ADS_TIMESTAMP).remove(PREF_ADS_HASH).apply()
        debug("persistedCache=STALE")
    }

    private fun hashAds(content: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun getVideosFromUrl(
        url: String,
        quality: String,
        session: ExtractionSession = newSession(),
    ): List<Video> {
        val startedAt = System.currentTimeMillis()
        debug("getVideosFromUrl url=${mask(url)} quality=$quality")
        return try {
            val playerInfo = fetchPlayerInfo(url, headers)
            debug("interstitial/player resolved in ${System.currentTimeMillis() - startedAt}ms")
            val deferred = CompletableDeferred<Video?>()
            val existing = session.media.putIfAbsent(playerInfo.videoUrl, deferred)
            if (existing != null) {
                debug("deduplicated videoUrl=${mask(playerInfo.videoUrl)}")
                return existing.await()?.let(::listOf) ?: emptyList()
            }

            val videoToken = fetchVideoToken(playerInfo)
            debug("token ready in ${System.currentTimeMillis() - startedAt}ms")
            val video = if (!videoToken.isNullOrBlank()) {
                val finalUrl = playerInfo.videoUrl + videoToken
                debug("finalUrl=${mask(finalUrl)} host=${finalUrl.toHttpUrl().host}")
                if (validateFinalMedia(finalUrl, quality)) {
                    debug("media validated quality=$quality in ${System.currentTimeMillis() - startedAt}ms")
                    Video(finalUrl, "Anitube", finalUrl, headers = headers)
                } else {
                    debug("media invalid quality=$quality; returning videos=0")
                    null
                }
            } else {
                debug("video token empty; returning videos=0")
                null
            }
            deferred.complete(video)
            video?.let(::listOf) ?: emptyList()
        } catch (e: Exception) {
            debugError("extractor exception; returning videos=0: ${e.message}", e)
            throw e
        }
    }

    companion object {
        private const val DEBUG_TAG = "ANITUBE_DEBUG"
        private const val ADBLOCK_URL = "https://ads.animeyabu.net/adblock2.php"
        private const val R2_HOST = "cd1c1111f6fed1da46e5ac0dec430c9e.r2.cloudflarestorage.com"
        private const val PREF_ADS_CONTENT = "anitube_ads_content"
        private const val PREF_ADS_URL = "anitube_ads_url"
        private const val PREF_ADBLOCK_URL = "anitube_adblock_url"
        private const val PREF_ADS_TIMESTAMP = "anitube_ads_timestamp"
        private const val PREF_ADS_HASH = "anitube_ads_hash"
        private const val FALLBACK_ADS_URL = "https://widgets.outbrain.com/outbrain.js"
        private val ADS_URL_REGEX = Regex("""(?:urlToFetch|ADS_URL)\s*=\s*['"]([^'"]+)['"]""")
    }
}
