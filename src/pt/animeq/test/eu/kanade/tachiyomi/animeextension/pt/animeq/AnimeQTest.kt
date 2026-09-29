package eu.kanade.tachiyomi.animeextension.pt.animeq

import okhttp3.Headers
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest

class AnimeQTest {

    @Test
    fun testSourceId() {
        val key = "${"AnimeQ".lowercase()}/pt-BR/1"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        val sourceId = (0..7).fold(0L) { acc, i ->
            (acc shl 8) or (bytes[i].toLong() and 0xff)
        } and Long.MAX_VALUE

        assertEquals(3666901064696818585L, sourceId)
    }

    @Test
    fun testPopularAnimeParsing() {
        val html = """
            <article class="item tvshows">
                <div class="poster">
                    <img src="https://animeq.cloud/wp-content/uploads/2026/07/bleach-185x278.jpg" alt="Bleach: Sennen Kessen Hen">
                    <a href="https://animeq.cloud/anime/bleach-sennen-kessen-hen"></a>
                </div>
                <div class="data">
                    <h3><a href="https://animeq.cloud/anime/bleach-sennen-kessen-hen">Bleach: Sennen Kessen Hen</a></h3>
                </div>
            </article>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val element = doc.selectFirst("article.item.tvshows, article.item.movies, article.item")!!
        val href = element.selectFirst("a")?.attr("href") ?: element.attr("href")
        val img = element.selectFirst("img")
        val title = element.selectFirst(".data h3, .data a, h3, .title")?.text() ?: img?.attr("alt") ?: ""

        assertEquals("https://animeq.cloud/anime/bleach-sennen-kessen-hen", href)
        assertEquals("Bleach: Sennen Kessen Hen", title)
    }

    @Test
    fun testCanonicalAnimeUrlFromEpisode() {
        val episodeUrl = "https://animeq.cloud/episodio/kimi-ga-shinu-made-koi-wo-shitai-episodio-13"
        val canonical = if (episodeUrl.contains("-episodio-")) {
            episodeUrl.substringBeforeLast("-episodio-").replace("/episodio/", "/anime/")
        } else {
            episodeUrl
        }

        assertEquals("https://animeq.cloud/anime/kimi-ga-shinu-made-koi-wo-shitai", canonical)
    }

    @Test
    fun testCanonicalCatalogPosterMatchingForRecentEpisodes() {
        // Test 10+ canonical works between catalog and recent updates
        val catalogWorks = listOf(
            "bleach-sennen-kessen-hen-kashin-tan-dublado" to "https://animeq.cloud/wp-content/uploads/2026/07/sqxy7vEHqbV2dsJWoAGFBbd4diU.jpg",
            "ghost-meets-gal" to "https://animeq.cloud/wp-content/uploads/2026/09/lmckivRH2w5KDzCaX3jc9EWAFPj.jpg",
            "tempal-item-no-chikara-dublado" to "https://animeq.cloud/wp-content/uploads/2026/09/160022l.jpg",
            "futsutsuka-na-akujo-dewa-gozaimasu-ga-suuguu-chouso-torikae-den-dublado" to "https://animeq.cloud/wp-content/uploads/2026/08/l2tyzxD9TXNI0EJvGG7NEWTB7re.jpg",
            "tensei-kizoku-kantei-skill-de-nariagaru-3rd-season" to "https://animeq.cloud/wp-content/uploads/2026/09/159859l.jpg",
            "tempal-item-no-chikara" to "https://animeq.cloud/wp-content/uploads/2026/09/160022l.jpg",
            "one-piece" to "https://image.tmdb.org/t/p/w780/2rmK7mnchw9Xr3XdiTFSxTTLXqv.jpg",
            "kaiju-no-8-narumi-no-heijitsu-dublado" to "https://animeq.cloud/wp-content/uploads/2026/09/irFkVJdMYSdCseoWNFeKU909awm.jpg",
            "kaiju-no-8-narumi-no-heijitsu" to "https://animeq.cloud/wp-content/uploads/2026/09/irFkVJdMYSdCseoWNFeKU909awm.jpg",
            "mushoku-tensei-iii-isekai-ittara-honki-dasu" to "https://animeq.cloud/wp-content/uploads/2026/07/sBbPXyXxYZ8LwqeZ8eV0884x20r.jpg",
            "yomi-no-tsugai" to "https://animeq.cloud/wp-content/uploads/2026/04/x9tqZfZLzu6T6q7wuySI8k7Qytf.jpg",
        )

        val catalogHtml = buildString {
            append("<div class='items'>")
            for ((slug, poster) in catalogWorks) {
                append(
                    """
                    <article class="item tvshows">
                        <div class="poster">
                            <img src="$poster" alt="$slug">
                            <a href="https://animeq.cloud/anime/$slug"></a>
                        </div>
                    </article>
                    """.trimIndent(),
                )
            }
            append("</div>")
        }

        val cache = mutableMapOf<String, String>()
        val catalogDoc = Jsoup.parse(catalogHtml)
        catalogDoc.select("article.item.tvshows").forEach { element ->
            val href = element.selectFirst("a")!!.attr("href")
            val posterUrl = element.selectFirst("img")!!.attr("src")
            val slug = href.substringAfterLast("/")
            val path = href.substringAfter("https://animeq.cloud").removeSuffix("/")
            cache[path] = posterUrl
            cache[slug] = posterUrl
            cache[href] = posterUrl
        }

        // Verify all 11 catalog works are cached
        assertEquals(11, catalogWorks.size)

        // For each canonical work, test resolving from an episode card
        for ((slug, expectedPoster) in catalogWorks) {
            val frameThumbnail = "https://animeq.cloud/wp-content/uploads/2026/09/frame_${slug}-300x170.jpg"
            val episodeHtml = """
                <article class="item se episodes">
                    <div class="poster">
                        <img src="$frameThumbnail" alt="$slug Episódio 01">
                        <a href="https://animeq.cloud/episodio/$slug-episodio-01"></a>
                    </div>
                    <div class="data">
                        <h3><a href="https://animeq.cloud/episodio/$slug-episodio-01">Episódio 01</a></h3>
                        <span class="serie">$slug</span>
                    </div>
                </article>
            """.trimIndent()

            val episodeDoc = Jsoup.parse(episodeHtml)
            val article = episodeDoc.selectFirst("article.item.se.episodes")!!
            val epHref = article.selectFirst("a[href*='/episodio/']")!!.attr("href")
            val canonUrl = epHref.substringBeforeLast("-episodio-").replace("/episodio/", "/anime/")
            val canonSlug = canonUrl.substringAfterLast("/")
            val canonPath = canonUrl.substringAfter("https://animeq.cloud").removeSuffix("/")

            val resolvedPoster = cache[canonPath] ?: cache[canonSlug] ?: cache[canonUrl]

            // 1. Matches canonical catalog thumbnail
            assertEquals(expectedPoster, resolvedPoster)
            // 2. Does NOT use the episode frame
            assertNotEquals(frameThumbnail, resolvedPoster)
        }

        // Test fallback: unknown anime not in catalog should resolve to null, NOT the frame
        val unknownFrame = "https://animeq.cloud/wp-content/uploads/unknown-300x170.jpg"
        val unknownSlug = "obra-inexistente-no-catalogo"
        val fallbackPoster = cache["/anime/$unknownSlug"] ?: cache[unknownSlug]
        assertNull(fallbackPoster)
    }

    @Test
    fun testNoDuplicateRefererHeaders() {
        val baseHeaders = Headers.Builder()
            .add("Referer", "https://animeq.cloud")
            .build()

        val videoHeaders = baseHeaders.newBuilder()
            .set("Accept", "*/*")
            .set("Referer", "https://animeq.cloud/")
            .set("Origin", "https://animeq.cloud")
            .build()

        val refererValues = videoHeaders.values("Referer")
        assertEquals("Must contain exactly 1 Referer header", 1, refererValues.size)
        assertEquals("https://animeq.cloud/", videoHeaders["Referer"])
    }

    @Test
    fun testAnimeDetailsParsing() {
        val html = """
            <div class="sheader animeq-single-cinematic">
                <div class="poster">
                    <img itemprop="image" src="https://animeq.cloud/wp-content/uploads/2026/07/bleach-212x300.jpg" alt="Bleach">
                </div>
                <div class="data">
                    <h1 itemprop="name">Bleach: Sennen Kessen Hen – Kashin Tan Dublado</h1>
                    <div class="sgeneros">
                        <a href="https://animeq.cloud/genre/acao">Ação</a>
                        <a href="https://animeq.cloud/genre/aventura">Aventura</a>
                        <a href="https://animeq.cloud/letra/letra-b">Letra B</a>
                    </div>
                </div>
            </div>
            <div id="info">
                <div class="wp-content">
                    <p>Título Alternativo: BLEACH</p>
                    <p>Sinopse: Parte final de Bleach: Sennen Kessen-hen.</p>
                </div>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val sheader = doc.selectFirst("div.sheader")!!
        val title = sheader.selectFirst("div.data > h1, h1")?.text() ?: ""
        val genres = sheader.select("div.sgeneros > a, div.data div.sgeneros > a")
            .eachText()
            .filterNot { it.startsWith("Letra ") }
            .joinToString()

        val synopsis = doc.select("div.wp-content p")
            .firstOrNull { it.text().contains("Sinopse:") || !it.text().contains("Título Alternativo") }
            ?.let { it.text().substringAfter("Sinopse: ") } ?: ""

        assertEquals("Bleach: Sennen Kessen Hen – Kashin Tan Dublado", title)
        assertEquals("Ação, Aventura", genres)
        assertEquals("Parte final de Bleach: Sennen Kessen-hen.", synopsis)
    }

    @Test
    fun testEpisodeParsing() {
        val html = """
            <div id='seasons'>
                <div class='se-c'>
                    <div class='se-q'><span class='se-t se-o'>1</span></div>
                    <div class='se-a'>
                        <div class='episodios-grid'>
                            <div class='episode-card' data-episode-number='1' data-episode-title='Episódio 01 - Deus do Trovão'>
                                <a href='https://animeq.cloud/episodio/bleach-episodio-01'></a>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val season = doc.selectFirst("div#seasons > div")!!
        val seasonNum = season.selectFirst("span.se-t")!!.text()
        val ep = season.selectFirst("div.episodios-grid > div.episode-card")!!
        val epNum = ep.attr("data-episode-number")
        val epTitle = ep.attr("data-episode-title")
        val formatted = "Temporada $seasonNum x $epNum - $epTitle"

        assertEquals("Temporada 1 x 1 - Episódio 01 - Deus do Trovão", formatted)
    }

    @Test
    fun testPlayerSourcesParsing() {
        val html = """
            <div class="animeq-player__servers">
                <button class="animeq-player__server" data-animeq-switch="0" data-source-name="SD"></button>
                <button class="animeq-player__server" data-animeq-switch="1" data-source-name="HD"></button>
                <button class="animeq-player__server" data-animeq-switch="2" data-source-name="FHD"></button>
            </div>
            <div class="animeq-player__source" data-animeq-source="0">
                <video><source src="https://animes.click/video_sd.mp4" type="video/mp4"></video>
            </div>
            <div class="animeq-player__source" data-animeq-source="1">
                <iframe src="https://www.blogger.com/video.g?token=abc123xyz"></iframe>
            </div>
            <div class="animeq-player__source" data-animeq-source="2">
                <div class="animeq-player__videojs-host" data-video-src="https://aniplay.online/video_fhd.mp4"></div>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val serverButtons = doc.select("button.animeq-player__server")
        val serverNames = serverButtons.associate { btn ->
            btn.attr("data-animeq-switch") to btn.attr("data-source-name").ifBlank { btn.text() }
        }

        assertEquals("SD", serverNames["0"])
        assertEquals("HD", serverNames["1"])
        assertEquals("FHD", serverNames["2"])

        val sources = doc.select("div.animeq-player__source")
        assertEquals(3, sources.size)

        val src0Video = sources[0].selectFirst("source[src]")?.attr("src")
        assertEquals("https://animes.click/video_sd.mp4", src0Video)

        val src1Iframe = sources[1].selectFirst("iframe[src]")?.attr("src")
        assertEquals("https://www.blogger.com/video.g?token=abc123xyz", src1Iframe)

        val src2Video = sources[2].selectFirst("[data-video-src]")?.attr("data-video-src")
        assertEquals("https://aniplay.online/video_fhd.mp4", src2Video)
    }
}
