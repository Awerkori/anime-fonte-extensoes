package eu.kanade.tachiyomi.animeextension.pt.cineveo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CineVEOTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private class RfInitialConfig(
        val file: String = "",
        val subtitle: String = "",
        val title: String = "",
    )

    @Test
    fun testTicketExtractionRegex() {
        val varHtml = """<script>var playbackResolveTicket = "v1.token.12345";</script>"""
        val letHtml = """<script>let playbackResolveTicket = "v1.token.67890";</script>"""
        val constHtml = """<script>const playbackResolveTicket = "v1.token.abcde";</script>"""

        val regex = Regex("""(?:var|let|const)?\s*playbackResolveTicket\s*=\s*['"]([^'"]+)['"]""")

        assertEquals("v1.token.12345", regex.find(varHtml)?.groupValues?.get(1))
        assertEquals("v1.token.67890", regex.find(letHtml)?.groupValues?.get(1))
        assertEquals("v1.token.abcde", regex.find(constHtml)?.groupValues?.get(1))
    }

    @Test
    fun testServerPillsExtractionRegex() {
        val html = """
            <button class="server-pill active" onclick="startSelectedServer('default')">
                <span class="server-pill-icon"><svg>...</svg></span>
                <span class="server-pill-label">Dublado</span>
            </button>
            <button class="server-pill" onclick="startSelectedServer('legendado')">
                <span class="server-pill-icon"><svg>...</svg></span>
                <span class="server-pill-label">Legendado</span>
            </button>
        """.trimIndent()

        val serverRegex = Regex("""startSelectedServer\(['"]([^'"]+)['"]\)[^>]*>.*?<span class="server-pill-label">([^<]+)</span>""", RegexOption.DOT_MATCHES_ALL)
        val pills = serverRegex.findAll(html).map {
            it.groupValues[1].trim() to it.groupValues[2].trim()
        }.toList()

        assertEquals(2, pills.size)
        assertEquals("default" to "Dublado", pills[0])
        assertEquals("legendado" to "Legendado", pills[1])
    }

    @Test
    fun testRfInitialConfigJsonParsingWithUnicode() {
        val rawJson = """{"file":"https://r2.cloudflarestorage.com/videos/deadpool.mp4?X-Amz-Algorithm=AWS4-HMAC-SHA256\u0026X-Amz-Credential=test","subtitle":"","thumbnail":"","title":"RedeFlixApi","embed":"","poster":"","autostart":true}"""
        val config = json.decodeFromString<RfInitialConfig>(rawJson)

        assertEquals("https://r2.cloudflarestorage.com/videos/deadpool.mp4?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=test", config.file)
        assertEquals("", config.subtitle)
        assertEquals("RedeFlixApi", config.title)
    }

    @Test
    fun testRfInitialConfigRegexFromHtml() {
        val playerHtml = """
            <!DOCTYPE html>
            <html>
            <head><title>Player</title></head>
            <body>
            <div id="player"></div>
            <script>window.__RF_INITIAL_CONFIG = {"file":"https://hubby-dmca.com/movie/test.mp4","subtitle":"https://subs.com/pt.vtt","thumbnail":"","title":"RedeFlixApi","embed":"","poster":"","autostart":true};</script>
            <script>var x = 1;</script>
            </body>
            </html>
        """.trimIndent()

        val configJson = Regex("""window\.__RF_INITIAL_CONFIG\s*=\s*(\{.+?\});""").find(playerHtml)?.groupValues?.get(1)
        assertNotNull(configJson)

        val config = json.decodeFromString<RfInitialConfig>(configJson!!)
        assertEquals("https://hubby-dmca.com/movie/test.mp4", config.file)
        assertEquals("https://subs.com/pt.vtt", config.subtitle)
    }

    @Test
    fun testMovieTitleFallbackCleaning() {
        val rawTitle = "Assistir Naruto to Boruto: The Live 2019 1080p Online - RedeCanais"
        val cleaned = rawTitle
            .removePrefix("Assistir ")
            .substringBefore(" - ")
            .replace(Regex("""\s+(?:HD|FHD|\d{3,4}p)(?:\s+Online)?$""", RegexOption.IGNORE_CASE), "")
        assertEquals("Naruto to Boruto: The Live 2019", cleaned)
    }

    @Test
    fun testMultilineRfInitialConfig() {
        val playerHtml = """
            <script>
            window.__RF_INITIAL_CONFIG = {
                "file": "https://cdn.example.com/video.mp4?a=1\u0026b=2",
                "subtitle": "",
                "autostart": true
            };
            </script>
        """.trimIndent()

        val configJson = Regex("""window\.__RF_INITIAL_CONFIG\s*=\s*(\{.+?\});""", RegexOption.DOT_MATCHES_ALL)
            .find(playerHtml)?.groupValues?.get(1)
        assertNotNull(configJson)

        val config = json.decodeFromString<RfInitialConfig>(configJson!!)
        assertEquals("https://cdn.example.com/video.mp4?a=1&b=2", config.file)
    }

    @Test
    fun testFallbackRegexUnescape() {
        val playerHtml = """window.__RF_INITIAL_CONFIG = {"file":"https:\/\/cdn.example.com\/v\/test.mp4?token=abc\u0026exp=123"};"""
        val fallback = Regex(""""file"\s*:\s*"([^"]+)"""").find(playerHtml)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.replace("\\u0026", "&")

        assertEquals("https://cdn.example.com/v/test.mp4?token=abc&exp=123", fallback)
    }

    @Serializable
    private class SeasonEpisode(
        val id: Long = 0,
        @SerialName("id_tmdb") val idTmdb: Long = 0,
        val temporada: Int = 1,
        val episodio: Int = 1,
        val nome: String? = null,
        val sinopse: String? = null,
        val imagem: String? = null,
        @SerialName("url_video") val urlVideo: String? = null,
        @SerialName("player_slug") val playerSlug: String? = null,
    )

    @Test
    fun testDetailUrlRouting() {
        val seriesSlug = "reacher-108978"
        val movieSlug = "jack-reacher-o-ltimo-tiro-svasn"
        val seriesUrl = "/watch/$seriesSlug"
        val movieUrl = "/watch/$movieSlug"
        assertEquals("/watch/reacher-108978", seriesUrl)
        assertEquals("/watch/jack-reacher-o-ltimo-tiro-svasn", movieUrl)
    }

    @Test
    fun testEpCodeSaltAlgorithm() {
        val tmdbId = 108978
        // Season 1 Episode 1
        val s1 = 1
        val e1 = 1
        val salt1 = (tmdbId * 37 + s1 * 19 + e1 * 41 + 41) % 100
        val saltStr1 = salt1.toString().padStart(2, '0')
        val epCode1 = "$tmdbId$s1$e1$saltStr1"
        assertEquals("1089781187", epCode1)

        // Season 1 Episode 2
        val s2 = 1
        val e2 = 2
        val salt2 = (tmdbId * 37 + s2 * 19 + e2 * 41 + 41) % 100
        val saltStr2 = salt2.toString().padStart(2, '0')
        val epCode2 = "$tmdbId$s2$e2$saltStr2"
        assertEquals("1089781228", epCode2)
    }

    @Test
    fun testJwPlayerExtraction() {
        val html = """
            <script type="text/javascript">
                const playerInstance = jwplayer("player").setup({
                    file: "https://nixplay.lat/series/cinevs-vods/wrcmBDcf4/108978/1/1.mp4",
                    type: "mp4",
                    title: "Reacher - T1 E1 - CineVEO",
                    width: "100%",
                    height: "100%",
                    autostart: true
                });
            </script>
        """.trimIndent()

        val file = Regex("""file\s*:\s*["'](https?://[^"']+)["']""").find(html)?.groupValues?.get(1)
        val title = Regex("""title\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?.substringBefore(" - CineVEO")

        assertEquals("https://nixplay.lat/series/cinevs-vods/wrcmBDcf4/108978/1/1.mp4", file)
        assertEquals("Reacher - T1 E1", title)
    }

    @Test
    fun testAllSeasonsParsing() {
        val jsonStr = """
            {
                "1": [
                    {"id": 1147559, "id_tmdb": 108978, "temporada": 1, "episodio": 1, "nome": "Bem-vindo a Margrave"},
                    {"id": 1147560, "id_tmdb": 108978, "temporada": 1, "episodio": 2, "nome": "Primeira Dança"}
                ],
                "2": [
                    {"id": 1147569, "id_tmdb": 108978, "temporada": 2, "episodio": 1, "nome": "Caixa Eletrônico"}
                ]
            }
        """.trimIndent()

        val seasonsMap = json.decodeFromString<Map<String, List<SeasonEpisode>>>(jsonStr)
        assertEquals(2, seasonsMap.size)
        assertEquals(2, seasonsMap["1"]?.size)
        assertEquals(1, seasonsMap["2"]?.size)
        assertEquals("Bem-vindo a Margrave", seasonsMap["1"]?.get(0)?.nome)
    }

    @Test
    fun testRealReacherAllSeasons() {
        val sampleJson = """
            {
                "1": [
                    {"id": 1147559, "id_tmdb": 108978, "temporada": 1, "episodio": 1, "nome": "Bem-vindo a Margrave", "sinopse": "Reacher é acusado...", "imagem": "https://image.tmdb.org/t/p/w300/15xLLZN3LLAdvoiHnYL5DcXlsYu.jpg", "url_video": null, "player_slug": null},
                    {"id": 1147560, "id_tmdb": 108978, "temporada": 1, "episodio": 2, "nome": "Primeira Dança", "sinopse": "Quando mais vítimas...", "imagem": "https://image.tmdb.org/t/p/w300/4nfnpqWMX8dHI1bYZcdxLEvy2c9.jpg", "url_video": null, "player_slug": null}
                ],
                "2": [
                    {"id": 1147569, "id_tmdb": 108978, "temporada": 2, "episodio": 1, "nome": "Caixa Eletrônico", "sinopse": "Reacher e Neagley...", "imagem": "https://image.tmdb.org/t/p/w300/j7hYdtMCzXsRtk8oeoynzcB1skZ.jpg", "url_video": null, "player_slug": null}
                ]
            }
        """.trimIndent()
        val seasonsMap = json.decodeFromString<Map<String, List<SeasonEpisode>>>(sampleJson)
        assertEquals(2, seasonsMap.size)
        assertEquals(2, seasonsMap["1"]?.size)
        assertEquals(1, seasonsMap["2"]?.size)
        assertEquals(108978L, seasonsMap["1"]?.get(0)?.idTmdb)
    }
}
