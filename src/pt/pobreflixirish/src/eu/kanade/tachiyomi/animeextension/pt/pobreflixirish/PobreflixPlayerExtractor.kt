package eu.kanade.tachiyomi.animeextension.pt.pobreflixirish

import android.util.Base64
import android.util.Log
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.lib.jsunpacker.JsUnpacker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

enum class ServerStatus {
    PLAYABLE,
    NO_STREAM,
    NOT_FOUND,
    BROWSER_VERIFICATION,
    TEMPORARY_ERROR,
}

sealed class VideoResult {
    data class Success(val videos: List<Video>) : VideoResult()

    data class NotReleased(
        val airDate: String?,
        val airDateFmt: String?,
        val remainingDays: Long?,
        val tvShowTitle: String? = null,
        val epTitle: String? = null,
    ) : VideoResult()

    object BrowserVerificationRequired : VideoResult()
    object NoCompatibleStream : VideoResult()
    object ServersTemporarilyUnavailable : VideoResult()

    fun toException(): Exception = when (this) {
        is Success -> IllegalStateException("Success cannot be converted to Exception")
        is NotReleased -> Exception(formatNotReleasedMessage(airDate, airDateFmt, remainingDays))
        is BrowserVerificationRequired -> Exception(
            "🌐 Este episódio está disponível, mas o servidor atual exige verificação no navegador. Abra na WebView para assistir.",
        )
        is NoCompatibleStream -> Exception(
            "⚠️ Nenhum vídeo funcional encontrado para este episódio no momento.",
        )
        is ServersTemporarilyUnavailable -> Exception(
            "🔌 Os servidores deste episódio estão temporariamente indisponíveis. Tente novamente mais tarde.",
        )
    }

    companion object {
        fun formatNotReleasedMessage(airDate: String?, airDateFmt: String?, remainingDays: Long?): String {
            val dateShort = if (!airDateFmt.isNullOrBlank() && airDateFmt.contains("/")) {
                airDateFmt.substringBeforeLast("/")
            } else if (!airDate.isNullOrBlank() && airDate.contains("-")) {
                val parts = airDate.split("-")
                if (parts.size == 3) "${parts[2]}/${parts[1]}" else airDate
            } else {
                airDateFmt.orEmpty()
            }

            return when {
                remainingDays == null -> {
                    if (dateShort.isNotBlank()) {
                        "⏳ Este episódio ainda não foi lançado. Disponível em ($dateShort). Abra na WebView para acompanhar."
                    } else {
                        "⏳ Este episódio ainda não foi lançado no site oficial. Abra na WebView para acompanhar."
                    }
                }
                remainingDays > 1 -> "⏳ Este episódio ainda não foi lançado. Disponível em $remainingDays dias ($dateShort). Abra na WebView para acompanhar."
                remainingDays == 1L -> "⏳ Este episódio ainda não foi lançado. Disponível amanhã ($dateShort). Abra na WebView para acompanhar."
                else -> "⏳ Este episódio ainda não está disponível. O lançamento está previsto para hoje. Tente novamente mais tarde."
            }
        }
    }
}

class PobreflixPlayerExtractor(private val client: OkHttpClient) {

    private val playlistUtils by lazy { PlaylistUtils(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    fun videosFromUrl(url: String, headers: Headers, label: String = "Pobreflix"): List<Video> {
        val result = extractVideosWithResult(url, headers, label)
        return (result as? VideoResult.Success)?.videos ?: emptyList()
    }

    fun extractVideosWithResult(url: String, headers: Headers, label: String = "Pobreflix"): VideoResult {
        val fullUrl = if (url.startsWith("http")) url else "https://${url.removePrefix("//")}"

        if ("plenoflu.com" in fullUrl) {
            return extractFromPlenoFluWithResult(fullUrl, headers, label)
        }

        if (fullUrl.contains(".m3u8") || fullUrl.contains("/hls/")) {
            val videos = runCatching {
                playlistUtils.extractFromHls(fullUrl, videoNameGen = { q -> formatVideoName("Dublado", q, label) })
            }.getOrDefault(emptyList())
            return if (videos.isNotEmpty()) VideoResult.Success(videos) else VideoResult.NoCompatibleStream
        }

        if (fullUrl.contains(".mp4")) {
            val videoHeaders = headers.newBuilder()
                .set("Referer", fullUrl)
                .set("Origin", "https://${fullUrl.toHttpUrl().host}")
                .build()
            return VideoResult.Success(listOf(Video(fullUrl, formatVideoName("Dublado", "MP4", label), fullUrl, videoHeaders)))
        }

        if (fullUrl.contains("vaiquecol.com", ignoreCase = true)) {
            val videos = extractFromVaiQueCol(fullUrl, "Dublado")
            return if (videos.isNotEmpty()) VideoResult.Success(videos) else VideoResult.NoCompatibleStream
        }

        // Generic embed fallback via UniversalExtractor
        val videos = runCatching {
            universalExtractor.videosFromUrl(fullUrl, headers, prefix = label).map { v ->
                val q = extractQuality(v.videoTitle)
                Video(v.videoUrl, formatVideoName("Dublado", q, label), v.videoUrl, v.headers)
            }
        }.getOrDefault(emptyList())

        return if (videos.isNotEmpty()) VideoResult.Success(videos) else VideoResult.NoCompatibleStream
    }

    fun extractFromPlenoFluWithResult(plenoUrl: String, headers: Headers, baseLabel: String): VideoResult {
        val t0 = System.currentTimeMillis()
        val plenoHeaders = headers.newBuilder()
            .set("Referer", "https://www.pobreflix.irish/")
            .set("Origin", "https://www.pobreflix.irish")
            .build()

        val response = runCatching {
            client.newCall(GET(plenoUrl, plenoHeaders)).execute()
        }.getOrNull()

        if (response == null || !response.isSuccessful) {
            val elapsed = System.currentTimeMillis() - t0
            Log.d(
                "POBREFLIX_PLAYER",
                "classification=ServersTemporarilyUnavailable vaiquecol=NOT_FOUND vidsrc=NOT_FOUND streambetter=NOT_FOUND superflix=NOT_FOUND elapsed=${elapsed}ms",
            )
            return VideoResult.ServersTemporarilyUnavailable
        }

        val html = response.body.string()
        val doc = org.jsoup.Jsoup.parse(html, plenoUrl)

        val apiHeaders = headers.newBuilder()
            .set("Referer", plenoUrl)
            .set("Origin", "https://plenoflu.com")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .build()

        val isTvShow = plenoUrl.contains("/tvshow/")
        val playerOptions = mutableListOf<PlayerOption>()

        if (isTvShow) {
            val directEpisodeId = REGEX_DIRECT_EPISODE_ID.find(html)?.groupValues?.get(1)
            if (!directEpisodeId.isNullOrBlank()) {
                val formBody = FormBody.Builder()
                    .add("action", "getOptions")
                    .add("contentid", directEpisodeId)
                    .build()

                val optResp = runCatching {
                    client.newCall(POST("https://plenoflu.com/api", apiHeaders, formBody)).execute()
                }.getOrNull()

                if (optResp != null) {
                    val optJson = runCatching { optResp.body.string() }.getOrDefault("")
                    val notReleased = parseNotReleasedJson(optJson)
                    if (notReleased != null) {
                        val elapsed = System.currentTimeMillis() - t0
                        Log.d(
                            "POBREFLIX_PLAYER",
                            "classification=NotReleased airDate=${notReleased.airDate} airDateFmt=${notReleased.airDateFmt} remainingDays=${notReleased.remainingDays} elapsed=${elapsed}ms",
                        )
                        return notReleased
                    }

                    runCatching {
                        val jsonObj = JSONObject(optJson)
                        val dataObj = jsonObj.optJSONObject("data")
                        val optionsArray = dataObj?.optJSONArray("options")
                        if (optionsArray != null) {
                            for (i in 0 until optionsArray.length()) {
                                val item = optionsArray.getJSONObject(i)
                                val id = if (item.has("ID")) item.getString("ID") else item.optString("id")
                                val type = item.optString("type")
                                val lang = if (type == "2") "Legendado" else "Dublado"
                                if (id.isNotBlank()) {
                                    playerOptions.add(PlayerOption(id, lang, "Canal ${i + 1}"))
                                }
                            }
                        }
                    }
                } else {
                    val elapsed = System.currentTimeMillis() - t0
                    Log.d(
                        "POBREFLIX_PLAYER",
                        "classification=ServersTemporarilyUnavailable vaiquecol=NOT_FOUND vidsrc=NOT_FOUND streambetter=NOT_FOUND superflix=NOT_FOUND elapsed=${elapsed}ms",
                    )
                    return VideoResult.ServersTemporarilyUnavailable
                }
            }
        } else {
            // Movie: read direct player select items from HTML
            doc.select("div.players_select_items").forEach { container ->
                val target = container.attr("data-target")
                val lang = if (target == "2") "Legendado" else "Dublado"

                container.select("div.player_select_item[data-id]").forEach { item ->
                    val id = item.attr("data-id")
                    val name = item.selectFirst(".player_select_name")?.text()?.trim() ?: "Canal"
                    if (id.isNotBlank()) {
                        playerOptions.add(PlayerOption(id, lang, name))
                    }
                }
            }
        }

        // Fallback for direct data-id in HTML if list is empty
        if (playerOptions.isEmpty()) {
            doc.select(".player_select_item[data-id]").forEach { item ->
                val id = item.attr("data-id")
                if (id.isNotBlank()) {
                    playerOptions.add(PlayerOption(id, "Dublado", "Canal"))
                }
            }
        }

        Log.d("POBREFLIX_PLAYER", "episode=$plenoUrl plenofluOptions=${playerOptions.size}")

        if (playerOptions.isEmpty()) {
            val elapsed = System.currentTimeMillis() - t0
            Log.d(
                "POBREFLIX_PLAYER",
                "classification=NoCompatibleStream vaiquecol=NOT_FOUND vidsrc=NOT_FOUND streambetter=NOT_FOUND superflix=NOT_FOUND elapsed=${elapsed}ms",
            )
            return VideoResult.NoCompatibleStream
        }

        // Step 2: Fetch video URLs for each option in parallel (pure HTTP API)
        val resolvedTargets = runBlocking(Dispatchers.IO) {
            playerOptions.map { opt ->
                async {
                    val videoUrl = fetchOptionVideoUrl(opt, apiHeaders)
                    val host = detectHost(videoUrl, baseLabel)
                    ResolvedTarget(opt, videoUrl, host)
                }
            }.awaitAll()
        }

        // Step 3: Tier 1 - Fast pure HTTP extraction in parallel (VaiQueCol, MP4, direct HLS)
        val tier1List = resolvedTargets.filter { r ->
            val u = r.videoUrl.lowercase()
            u.contains("vaiquecol.com") || u.contains(".m3u8") || u.contains("/hls/") || u.contains(".mp4")
        }

        val tier1Videos = runBlocking(Dispatchers.IO) {
            tier1List.map { target ->
                async {
                    val tItem = System.currentTimeMillis()
                    val videos = resolveFastVideo(target.opt, target.videoUrl, headers, target.host)
                    val elapsedItem = System.currentTimeMillis() - tItem
                    if (videos.isNotEmpty()) {
                        Log.d("POBREFLIX_PLAYER", "option=${target.opt.lang} host=${target.host} elapsed=${elapsedItem}ms videos=${videos.size}")
                    } else {
                        Log.d("POBREFLIX_PLAYER", "option=${target.opt.lang} host=${target.host} elapsed=${elapsedItem}ms videos=0 reason=No stream available")
                    }
                    videos
                }
            }.awaitAll().flatten()
        }

        // Priority 1: IF TIER 1 FOUND VIDEOS, RETURN IMMEDIATELY!
        if (tier1Videos.isNotEmpty()) {
            val elapsed = System.currentTimeMillis() - t0
            Log.d("POBREFLIX_PLAYER", "classification=Playable totalElapsed=${elapsed}ms videos=${tier1Videos.size}")
            return VideoResult.Success(tier1Videos)
        }

        // Step 4: Tier 1 returned 0 videos. Fast parallel check of all remaining providers!
        val providerStatuses = ConcurrentHashMap<String, ServerStatus>()

        tier1List.forEach { target ->
            val key = target.host.lowercase()
            providerStatuses[key] = ServerStatus.NO_STREAM
        }

        val nonTier1 = resolvedTargets.filter { it !in tier1List && it.videoUrl.isNotBlank() }

        runBlocking(Dispatchers.IO) {
            nonTier1.map { target ->
                async {
                    val url = target.videoUrl
                    val key = target.host.lowercase()
                    when {
                        url.contains("superflix", ignoreCase = true) -> {
                            val reqBrowser = checkSuperflixRequiresBrowser(url, headers)
                            if (reqBrowser) {
                                providerStatuses["superflix"] = ServerStatus.BROWSER_VERIFICATION
                            } else {
                                providerStatuses["superflix"] = ServerStatus.NO_STREAM
                            }
                        }
                        url.contains("vidsrc", ignoreCase = true) -> {
                            val available = checkVidSrcAvailability(url)
                            if (!available) {
                                providerStatuses["vidsrc"] = ServerStatus.NOT_FOUND
                            } else {
                                providerStatuses["vidsrc"] = ServerStatus.NO_STREAM
                            }
                        }
                        url.contains("streambetter", ignoreCase = true) -> {
                            providerStatuses["streambetter"] = ServerStatus.NOT_FOUND
                        }
                        else -> {
                            providerStatuses[key] = ServerStatus.NO_STREAM
                        }
                    }
                }
            }.awaitAll()
        }

        // If Superflix doesn't require browser verification, try fallback extraction via UniversalExtractor
        val superflixNeedsBrowser = providerStatuses["superflix"] == ServerStatus.BROWSER_VERIFICATION
        if (!superflixNeedsBrowser) {
            for (target in nonTier1) {
                if (target.videoUrl.contains("streambetter", ignoreCase = true)) continue
                if (target.videoUrl.contains("vidsrc", ignoreCase = true) && !checkVidSrcAvailability(target.videoUrl)) continue

                val videos = runCatching {
                    universalExtractor.videosFromUrl(target.videoUrl, headers, prefix = target.host).map { v ->
                        val q = extractQuality(v.videoTitle)
                        Video(v.videoUrl, formatVideoName(target.opt.lang, q, target.host), v.videoUrl, v.headers)
                    }
                }.getOrDefault(emptyList())

                if (videos.isNotEmpty()) {
                    val elapsed = System.currentTimeMillis() - t0
                    Log.d("POBREFLIX_PLAYER", "classification=Playable totalElapsed=${elapsed}ms videos=${videos.size}")
                    return VideoResult.Success(videos)
                }
            }
        }

        val elapsed = System.currentTimeMillis() - t0
        val classification = classifyFailure(providerStatuses)

        val vqStatus = providerStatuses["vaiquecol"] ?: ServerStatus.NOT_FOUND
        val vsStatus = providerStatuses["vidsrc"] ?: ServerStatus.NOT_FOUND
        val sbStatus = providerStatuses["streambetter"] ?: ServerStatus.NOT_FOUND
        val sfStatus = providerStatuses["superflix"] ?: ServerStatus.NOT_FOUND

        Log.d(
            "POBREFLIX_PLAYER",
            "classification=${classification.javaClass.simpleName} vaiquecol=$vqStatus vidsrc=$vsStatus streambetter=$sbStatus superflix=$sfStatus elapsed=${elapsed}ms",
        )

        return classification
    }

    fun checkSuperflixRequiresBrowser(url: String, headers: Headers): Boolean = runCatching {
        val cleanUrl = url.substringBefore("#")
        val req = GET(
            cleanUrl,
            headers.newBuilder()
                .set("Referer", "https://plenoflu.com/")
                .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build(),
        )
        val resp = client.newCall(req).execute()
        val body = resp.body.string()
        isBrowserVerificationHtml(body)
    }.getOrDefault(false)

    private fun fetchOptionVideoUrl(opt: PlayerOption, apiHeaders: Headers): String {
        return runCatching {
            val formBody = FormBody.Builder()
                .add("action", "getPlayer")
                .add("video_id", opt.id)
                .build()

            val resJson = client.newCall(POST("https://plenoflu.com/api", apiHeaders, formBody))
                .execute().body.string()

            val encodedUrl = REGEX_VIDEO_URL.find(resJson)?.groupValues?.get(1)?.trim()
                ?: return ""

            val sanitized = encodedUrl.replace("\\/", "/").replace("\\", "").trim()
            if (sanitized.startsWith("http://") || sanitized.startsWith("https://")) {
                sanitized
            } else {
                runCatching {
                    String(Base64.decode(sanitized, Base64.DEFAULT)).trim()
                }.getOrDefault("")
            }
        }.getOrDefault("")
    }

    private fun resolveFastVideo(
        opt: PlayerOption,
        videoUrl: String,
        headers: Headers,
        host: String,
    ): List<Video> = runCatching {
        when {
            videoUrl.contains("vaiquecol.com", ignoreCase = true) -> {
                extractFromVaiQueCol(videoUrl, opt.lang)
            }

            videoUrl.contains(".m3u8") || videoUrl.contains("/hls/") -> {
                playlistUtils.extractFromHls(
                    videoUrl,
                    videoNameGen = { q -> formatVideoName(opt.lang, q, host) },
                )
            }

            videoUrl.contains(".mp4") -> {
                val vHeaders = headers.newBuilder()
                    .set("Referer", videoUrl)
                    .set("Origin", "https://${videoUrl.toHttpUrl().host}")
                    .build()
                listOf(Video(videoUrl, formatVideoName(opt.lang, "MP4", host), videoUrl, vHeaders))
            }

            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    fun checkVidSrcAvailability(url: String): Boolean {
        return runCatching {
            val tvMatch = REGEX_VIDSRC_TV.find(url)
            if (tvMatch != null) {
                val tmdb = tvMatch.groupValues[1]
                val season = tvMatch.groupValues[2]
                val ep = tvMatch.groupValues[3]
                val checkUrl = "https://data.vidsrc.sh/api.php?type=tv&tmdb=$tmdb&season=$season&episode=$ep"
                val resp = client.newCall(GET(checkUrl)).execute().body.string()
                return !resp.contains("status_code\":404") && !resp.contains("status_code\":\"404\"")
            }

            val movieMatch = REGEX_VIDSRC_MOVIE.find(url)
            if (movieMatch != null) {
                val id = movieMatch.groupValues[1]
                val checkUrl = "https://data.vidsrc.sh/api.php?type=movie&tmdb=$id"
                val resp = client.newCall(GET(checkUrl)).execute().body.string()
                return !resp.contains("status_code\":404") && !resp.contains("status_code\":\"404\"")
            }

            true
        }.getOrDefault(true)
    }

    fun extractFromVaiQueCol(url: String, lang: String): List<Video> {
        return runCatching {
            val vqHeaders = Headers.Builder()
                .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .set("Referer", "https://plenoflu.com/")
                .build()

            val host = "https://${url.toHttpUrl().host}"
            var hash = url.substringAfterLast("/").trim()

            if (hash.length < 32 || hash.contains("-")) {
                val resp = client.newCall(GET(url, vqHeaders)).execute()
                val html = resp.body.string()

                if (html.contains("<title>Error</title>", ignoreCase = true) ||
                    (!html.contains("p,a,c,k,e") && !html.contains("FirePlayer"))
                ) {
                    return emptyList()
                }

                val doc = org.jsoup.Jsoup.parse(html)
                val rawScript = doc.selectFirst("script:containsData(eval):containsData(p,a,c,k,e)")?.data()
                val unpacked = rawScript?.replace(Regex("[\\u00E0-\\u00FC]"), "-")?.let(JsUnpacker::unpackAndCombine)
                    ?: doc.selectFirst("script:containsData(FirePlayer)")?.data()
                    ?: html

                hash = if (unpacked.contains("FirePlayer(")) {
                    unpacked.substringAfter("FirePlayer(\"").substringBefore('"')
                } else {
                    Regex("""FirePlayer\(["']([a-f0-9]{32})["']\)""").find(unpacked)?.groupValues?.get(1)
                        ?: Regex("""[a-f0-9]{32}""").find(html)?.value ?: ""
                }
            }

            if (hash.length != 32) return emptyList()

            val postUrl = "$host/player/index.php?data=$hash&do=getVideo"
            val postHeaders = Headers.Builder()
                .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .set("Referer", url)
                .set("Origin", host)
                .set("X-Requested-With", "XMLHttpRequest")
                .build()
            val body = FormBody.Builder()
                .add("hash", hash)
                .add("r", "")
                .build()

            val postResp = client.newCall(POST(postUrl, postHeaders, body)).execute()
            val resStr = postResp.body.string()
            val securedLink = resStr.substringAfter("securedLink\":\"", "").substringBefore('"').replace("\\", "")
            if (securedLink.isBlank()) return emptyList()

            val hlsHeaders = Headers.Builder()
                .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .set("Referer", host)
                .set("Origin", host)
                .build()

            playlistUtils.extractFromHls(
                playlistUrl = securedLink,
                referer = host,
                masterHeaders = hlsHeaders,
                videoHeaders = hlsHeaders,
                videoNameGen = { q -> formatVideoName(lang, q, "VaiQueCol") },
            )
        }.getOrDefault(emptyList())
    }

    private data class PlayerOption(
        val id: String,
        val lang: String,
        val name: String,
    )

    private data class ResolvedTarget(
        val opt: PlayerOption,
        val videoUrl: String,
        val host: String,
    )

    companion object {
        fun isBrowserVerificationHtml(html: String): Boolean = html.contains("<title>Verificação</title>", ignoreCase = true) ||
            html.contains("class=\"captcha-", ignoreCase = true) ||
            html.contains("captcha-box", ignoreCase = true) ||
            html.contains("cf-turnstile", ignoreCase = true) ||
            html.contains("challenges.cloudflare.com/turnstile", ignoreCase = true) ||
            html.contains("Just a moment...", ignoreCase = true)

        fun calculateRemainingDays(airDateStr: String?, referenceDate: Date? = null): Long? {
            if (airDateStr.isNullOrBlank()) return null
            return runCatching {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("America/Sao_Paulo")
                }
                val airDate = sdf.parse(airDateStr.trim()) ?: return null
                val airCal = Calendar.getInstance(TimeZone.getTimeZone("America/Sao_Paulo")).apply {
                    time = airDate
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val nowCal = Calendar.getInstance(TimeZone.getTimeZone("America/Sao_Paulo")).apply {
                    if (referenceDate != null) time = referenceDate
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val diffMillis = airCal.timeInMillis - nowCal.timeInMillis
                java.util.concurrent.TimeUnit.MILLISECONDS.toDays(diffMillis)
            }.getOrNull()
        }

        fun parseNotReleasedJson(jsonStr: String, referenceDate: Date? = null): VideoResult.NotReleased? = runCatching {
            if (!jsonStr.contains("not_released", ignoreCase = true) && !jsonStr.contains("NÃO_LANÇADO", ignoreCase = true)) {
                return null
            }
            fun extractField(key: String): String? {
                val pattern = Regex("""\"$key\"\s*:\s*\"([^\"]*)\"""")
                return pattern.find(jsonStr)?.groupValues?.get(1)?.replace("\\/", "/")
            }
            val airDate = extractField("air_date")
            val airDateFmt = extractField("air_date_fmt")
            val tvTitle = extractField("tvshow_title")
            val epTitle = extractField("title")
            val days = calculateRemainingDays(airDate, referenceDate)
            VideoResult.NotReleased(
                airDate = airDate?.takeIf { it.isNotBlank() },
                airDateFmt = airDateFmt?.takeIf { it.isNotBlank() },
                remainingDays = days,
                tvShowTitle = tvTitle?.takeIf { it.isNotBlank() },
                epTitle = epTitle?.takeIf { it.isNotBlank() },
            )
        }.getOrNull()

        fun classifyFailure(providerStatuses: Map<String, ServerStatus>): VideoResult {
            val superflix = providerStatuses["superflix"]
            if (superflix == ServerStatus.BROWSER_VERIFICATION) {
                return VideoResult.BrowserVerificationRequired
            }
            val allTemporary = providerStatuses.isNotEmpty() && providerStatuses.values.all {
                it == ServerStatus.TEMPORARY_ERROR
            }
            if (allTemporary) {
                return VideoResult.ServersTemporarilyUnavailable
            }
            return VideoResult.NoCompatibleStream
        }

        fun detectHost(url: String, defaultName: String): String = when {
            url.contains("vaiquecol", ignoreCase = true) -> "VaiQueCol"
            url.contains("superflix", ignoreCase = true) -> "Superflix"
            url.contains("streambetter", ignoreCase = true) -> "StreamBetter"
            url.contains("vidsrc", ignoreCase = true) -> "VidSrc"
            url.contains("embedplayapi", ignoreCase = true) -> "EmbedPlay"
            url.contains("myembed", ignoreCase = true) -> "MyEmbed"
            url.isBlank() -> defaultName
            else -> runCatching {
                url.toHttpUrl().host.substringBefore(".").replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                }
            }.getOrDefault(defaultName)
        }

        fun formatVideoName(audio: String, quality: String, server: String): String {
            val cleanAudio = when {
                audio.contains("legendado", ignoreCase = true) -> "Legendado"
                audio.contains("dublado", ignoreCase = true) -> "Dublado"
                else -> "Dublado"
            }
            val cleanQuality = when {
                quality.contains("1080") -> "1080p"
                quality.contains("720") -> "720p"
                quality.contains("480") -> "480p"
                quality.contains("360") -> "360p"
                quality.contains("240") -> "240p"
                quality.isNotBlank() && !quality.equals("mirror", ignoreCase = true) && !quality.equals("default", ignoreCase = true) -> quality.trim()
                else -> "HD"
            }
            val cleanServer = when {
                server.contains("vaiquecol", ignoreCase = true) -> "VaiQueCol"
                server.contains("superflix", ignoreCase = true) -> "Superflix"
                server.contains("streambetter", ignoreCase = true) -> "StreamBetter"
                server.contains("vidsrc", ignoreCase = true) -> "VidSrc"
                server.contains("embedplay", ignoreCase = true) -> "EmbedPlay"
                server.contains("myembed", ignoreCase = true) -> "MyEmbed"
                server.contains("fireplayer", ignoreCase = true) -> "VaiQueCol"
                server.isNotBlank() -> server.trim()
                else -> "Pobreflix"
            }
            return "$cleanAudio - $cleanQuality - $cleanServer"
        }

        fun extractQuality(text: String): String {
            val match = Regex("""(\d{3,4}p)""").find(text)
            return match?.groupValues?.get(1) ?: "HD"
        }

        private val REGEX_DIRECT_EPISODE_ID by lazy {
            Regex("""DIRECT_EPISODE_ID\s*=\s*(\d+)""")
        }
        private val REGEX_VIDEO_URL by lazy {
            Regex(""""video_url"\s*:\s*"([^"]+)"""")
        }
        private val REGEX_VIDSRC_TV by lazy {
            Regex("""/tv/([^/?]+)/(\d+)/(\d+)""")
        }
        private val REGEX_VIDSRC_MOVIE by lazy {
            Regex("""/movie/([^/?]+)""")
        }
    }
}
