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
}
