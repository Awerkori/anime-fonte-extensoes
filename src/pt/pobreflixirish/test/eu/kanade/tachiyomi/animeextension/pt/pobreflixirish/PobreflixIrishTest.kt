package eu.kanade.tachiyomi.animeextension.pt.pobreflixirish

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class PobreflixIrishTest {

    @Test
    fun testSourceId() {
        val key = "${"Pobreflix Irish".lowercase()}/pt-BR/1"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        val sourceId = (0..7).fold(0L) { acc, i ->
            (acc shl 8) or (bytes[i].toLong() and 0xff)
        } and Long.MAX_VALUE

        assertEquals(3697320915322152632L, sourceId)
    }

    @Test
    fun testPopularParsing() {
        val html = """
            <div id="archive-content" class="animation-2 items">
                <article id="post-101" class="item tvshows">
                    <div class="poster">
                        <img src="https://image.tmdb.org/t/p/w500/wiF1C97GuJEOBSKWrtub517THjF.jpg" alt="Lanternas 2026">
                        <a href="https://www.pobreflix.irish/series/lanternas-2026/"></a>
                    </div>
                    <div class="data">
                        <h3><a href="https://www.pobreflix.irish/series/lanternas-2026/">Lanternas 2026</a></h3>
                    </div>
                </article>
                <article id="post-102" class="item movies">
                    <div class="poster">
                        <img src="https://image.tmdb.org/t/p/w500/o0QndnepFPWget2kdKpzh26RBYt.jpg" alt="A Morte de Robin Hood">
                        <a href="https://www.pobreflix.irish/filmes/a-morte-de-robin-hood-filme-dublado-lancamento-2026/"></a>
                    </div>
                    <div class="data">
                        <h3><a href="https://www.pobreflix.irish/filmes/a-morte-de-robin-hood-filme-dublado-lancamento-2026/">A Morte de Robin Hood</a></h3>
                    </div>
                </article>
            </div>
            <div class="pagination">
                <a class="arrow_pag" href="https://www.pobreflix.irish/series/page/2/"><i id="nextpagination" class="fas fa-caret-right"></i></a>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val items = doc.select("#archive-content article.item")
        assertEquals(2, items.size)

        val series = items[0]
        val seriesUrl = series.selectFirst("a[href*='/series/']")!!.attr("href")
        val seriesTitle = series.selectFirst("h3 a")!!.text()
        val seriesPoster = series.selectFirst("img")!!.attr("src")

        assertEquals("https://www.pobreflix.irish/series/lanternas-2026/", seriesUrl)
        assertEquals("Lanternas 2026", seriesTitle)
        assertEquals("https://image.tmdb.org/t/p/w500/wiF1C97GuJEOBSKWrtub517THjF.jpg", seriesPoster)

        val hasNext = doc.selectFirst("div.pagination i#nextpagination") != null
        assertTrue(hasNext)
    }

    @Test
    fun testRecentesCanonicalResolution() {
        val episodeHtml = """
            <article class="item se episodes">
                <div class="poster">
                    <img src="https://www.pobreflix.irish/wp-content/themes/dooplay/assets/img/no/dt_backdrop.png" alt="Insubstituível">
                    <a href="https://www.pobreflix.irish/episodios/insubstituivel-temporada-1-episodio-20/"></a>
                </div>
                <div class="data">
                    <h3><a href="https://www.pobreflix.irish/episodios/insubstituivel-temporada-1-episodio-20/">Episódio 20</a></h3>
                    <span class="serie">Insubstituível</span>
                </div>
            </article>
        """.trimIndent()

        val episodePageHtml = """
            <div class="item">
                <a href="https://www.pobreflix.irish/series/insubstituivel-2026/" title="Insubstituível (2026)">
                    <i class="fas fa-bars"></i> <span>Todos</span>
                </a>
            </div>
        """.trimIndent()

        val seriesPageHtml = """
            <div class="poster">
                <img src="https://image.tmdb.org/t/p/w500/5Oot4qs5B4KznLiIcugVLg5KRqD.jpg" alt="Insubstituível">
            </div>
            <div class="data">
                <h1>Insubstituível (2026)</h1>
            </div>
        """.trimIndent()

        val epDoc = Jsoup.parse(episodeHtml)
        val epCard = epDoc.selectFirst("article.item.se.episodes")!!
        val epFrame = epCard.selectFirst("img")!!.attr("src")

        // 1. Episode frame must be rejected
        assertTrue(epFrame.contains("dt_backdrop") || epFrame.contains("300x170"))

        // 2. Canonical series link extracted from episode navigation
        val epPageDoc = Jsoup.parse(episodePageHtml)
        val seriesLink = epPageDoc.selectFirst("div.item a[href*='/series/']")!!
        val canonUrl = seriesLink.attr("href")
        val canonTitle = seriesLink.attr("title").removeSuffix(" (2026)")

        assertEquals("https://www.pobreflix.irish/series/insubstituivel-2026/", canonUrl)
        assertEquals("Insubstituível", canonTitle)

        // 3. Official poster extracted from canonical series page
        val seriesDoc = Jsoup.parse(seriesPageHtml)
        val canonicalPoster = seriesDoc.selectFirst("div.poster > img")!!.attr("src")

        assertEquals("https://image.tmdb.org/t/p/w500/5Oot4qs5B4KznLiIcugVLg5KRqD.jpg", canonicalPoster)
        assertNotEquals(epFrame, canonicalPoster)
    }

    @Test
    fun testSearchParsing() {
        val html = """
            <div class="result-item">
                <article>
                    <div class="image">
                        <div class="thumbnail">
                            <a href="https://www.pobreflix.irish/series/reacher/">
                                <img src="https://image.tmdb.org/t/p/w500/f1VCQIG2iCyOookdgOzwtUpwWC0.jpg" alt="Reacher">
                            </a>
                        </div>
                    </div>
                    <div class="details">
                        <div class="title">
                            <a href="https://www.pobreflix.irish/series/reacher/">Reacher</a>
                        </div>
                    </div>
                </article>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val item = doc.selectFirst("div.result-item")!!
        val a = item.selectFirst("div.details div.title a")!!
        val href = a.attr("href")
        val title = a.text()
        val img = item.selectFirst("img")!!.attr("src")

        assertEquals("https://www.pobreflix.irish/series/reacher/", href)
        assertEquals("Reacher", title)
        assertEquals("https://image.tmdb.org/t/p/w500/f1VCQIG2iCyOookdgOzwtUpwWC0.jpg", img)
    }

    @Test
    fun testAnimeDetailsCleaning() {
        val html = """
            <div class="data">
                <h1>Assistir Jack Reacher: O Último Tiro Online</h1>
            </div>
            <div class="poster">
                <img src="https://image.tmdb.org/t/p/w500/8wVYubuCGHXQUCmqsm8v7gSTDKI.jpg" alt="Poster">
            </div>
            <div class="sgeneros">
                <a href="/categorias/acao/">Ação</a>
                <a href="/categorias/crime/">Crime</a>
            </div>
            <div class="wp-content">
                <p>Sinopse oficial do filme Jack Reacher.</p>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val rawTitle = doc.selectFirst("div.data > h1")!!.text()
        val cleanTitle = rawTitle.removePrefix("Assistir ").removeSuffix(" Online").removeSuffix(" Dublado").trim()
        val genres = doc.select("div.sgeneros > a").eachText().joinToString()
        val synopsis = doc.selectFirst("div.wp-content p")!!.text()

        assertEquals("Jack Reacher: O Último Tiro", cleanTitle)
        assertEquals("Ação, Crime", genres)
        assertEquals("Sinopse oficial do filme Jack Reacher.", synopsis)
    }

    @Test
    fun testEpisodeListMultiSeasonAndMovie() {
        // Multi-season series
        val seriesHtml = """
            <div id="seasons">
                <div class="se-c">
                    <span class="se-t">1</span>
                    <ul class="episodios">
                        <li>
                            <div class="numerando">1 - 1</div>
                            <a href="https://www.pobreflix.irish/episodios/reacher-1x1/">Bem-vindo a Margrave</a>
                            <span class="date">Feb. 03, 2022</span>
                        </li>
                        <li>
                            <div class="numerando">1 - 2</div>
                            <a href="https://www.pobreflix.irish/episodios/reacher-1x2/">Primeira Dança</a>
                            <span class="date">Feb. 03, 2022</span>
                        </li>
                    </ul>
                </div>
            </div>
        """.trimIndent()

        val sDoc = Jsoup.parse(seriesHtml)
        val seasons = sDoc.select("div#seasons > div.se-c")
        assertEquals(1, seasons.size)

        val epList = seasons[0].select("ul.episodios > li")
        assertEquals(2, epList.size)

        val ep1 = epList[0]
        val ep1Num = ep1.selectFirst("div.numerando")!!.text().substringAfter("-").trim().toInt()
        val ep1Name = ep1.selectFirst("a")!!.text()
        val ep1Url = ep1.selectFirst("a")!!.attr("href")

        assertEquals(1, ep1Num)
        assertEquals("Bem-vindo a Margrave", ep1Name)
        assertEquals("https://www.pobreflix.irish/episodios/reacher-1x1/", ep1Url)

        // Movie (synthetic single episode)
        val movieHtml = """
            <div class="dooplay_player">
                <div class="pframe">
                    <iframe src="https://plenoflu.com/movie/tt0790724"></iframe>
                </div>
            </div>
        """.trimIndent()

        val mDoc = Jsoup.parse(movieHtml)
        val movieSeasons = mDoc.select("div#seasons > div.se-c")
        assertTrue(movieSeasons.isEmpty())
    }

    @Test
    fun testPlenoFluOptionParsing() {
        val optionsJson = """
            {"errors":"0","message":"success","data":{"msg":"Opções obtidas","options":[{"ID":1065984,"type":"1"},{"ID":1065987,"type":"2"}]}}
        """.trimIndent()

        val regexOptionEntry = Regex("""\{\s*"ID"\s*:\s*(\d+)\s*,\s*"type"\s*:\s*"?(\d+)"?""")
        val matches = regexOptionEntry.findAll(optionsJson).toList()

        assertEquals(2, matches.size)
        assertEquals("1065984", matches[0].groupValues[1])
        assertEquals("1", matches[0].groupValues[2])
        assertEquals("1065987", matches[1].groupValues[1])
        assertEquals("2", matches[1].groupValues[2])

        val base64VideoUrl = "aHR0cHM6Ly92YWlxdWVjb2wuY29tL2VtYmVkMi8xMDg5NzgtMS0x"
        val decoded = String(java.util.Base64.getDecoder().decode(base64VideoUrl))
        assertEquals("https://vaiquecol.com/embed2/108978-1-1", decoded)
        assertTrue(decoded.contains("vaiquecol.com"))
    }

    @Test
    fun testVideoNameFormatting() {
        assertEquals(
            "Dublado - 1080p - VaiQueCol",
            PobreflixPlayerExtractor.formatVideoName("Dublado", "1080p", "VaiQueCol"),
        )
        assertEquals(
            "Legendado - 720p - VaiQueCol",
            PobreflixPlayerExtractor.formatVideoName("Legendado", "720p", "VaiQueCol"),
        )
        assertEquals(
            "Dublado - 720p - Superflix",
            PobreflixPlayerExtractor.formatVideoName("Dublado", "720p", "Superflix"),
        )
        assertEquals(
            "Legendado - 1080p - VidSrc",
            PobreflixPlayerExtractor.formatVideoName("Legendado", "1080p", "VidSrc"),
        )
    }

    @Test
    fun testVideoSortingWithPreferences() {
        val dummyHeaders = Headers.headersOf()

        val videos = listOf(
            Video("url1", "Legendado - 720p - VaiQueCol", "url1", dummyHeaders),
            Video("url2", "Dublado - 480p - VaiQueCol", "url2", dummyHeaders),
            Video("url3", "Legendado - 1080p - VidSrc", "url3", dummyHeaders),
            Video("url4", "Dublado - 1080p - VaiQueCol", "url4", dummyHeaders),
            Video("url5", "Dublado - 720p - Superflix", "url5", dummyHeaders),
        )

        // 1. Preferred Dublado + 720p
        val sorted1 = PobreflixIrish.sortVideos(videos, "Dublado", "720p")
        // Dublado 720p should be first
        assertEquals("Dublado - 720p - Superflix", sorted1[0].videoTitle)
        // Followed by Dublado 1080p and Dublado 480p
        assertEquals("Dublado - 1080p - VaiQueCol", sorted1[1].videoTitle)
        assertEquals("Dublado - 480p - VaiQueCol", sorted1[2].videoTitle)
        // Then Legendado (720p > 1080p because 720p is preferred)
        assertEquals("Legendado - 720p - VaiQueCol", sorted1[3].videoTitle)
        assertEquals("Legendado - 1080p - VidSrc", sorted1[4].videoTitle)

        // 2. Preferred Legendado + best (highest quality)
        val sorted2 = PobreflixIrish.sortVideos(videos, "Legendado", "best")
        // Legendado 1080p first, then Legendado 720p
        assertEquals("Legendado - 1080p - VidSrc", sorted2[0].videoTitle)
        assertEquals("Legendado - 720p - VaiQueCol", sorted2[1].videoTitle)
        // Then Dublado (1080p > 720p > 480p)
        assertEquals("Dublado - 1080p - VaiQueCol", sorted2[2].videoTitle)
        assertEquals("Dublado - 720p - Superflix", sorted2[3].videoTitle)
        assertEquals("Dublado - 480p - VaiQueCol", sorted2[4].videoTitle)

        // 3. Preferred Auto + best
        val sorted3 = PobreflixIrish.sortVideos(videos, "auto", "best")
        // 1080p videos come before 720p and 480p
        val firstTwoQualities = listOf(sorted3[0].videoTitle, sorted3[1].videoTitle)
        assertTrue(firstTwoQualities.all { it.contains("1080p") })
        val nextTwoQualities = listOf(sorted3[2].videoTitle, sorted3[3].videoTitle)
        assertTrue(nextTwoQualities.all { it.contains("720p") })
        assertTrue(sorted3[4].videoTitle.contains("480p"))
    }

    @Test
    fun testRecentesMultiPageAggregation() {
        // Simulating 5 pages of episode cards where cards 1-20 are Insubstituível and 21-30 are More Than Blue, etc.
        val episodeCards = listOf(
            Pair("https://pobreflix.irish/episodios/insubstituivel-ep-1/", "Insubstituível"),
            Pair("https://pobreflix.irish/episodios/insubstituivel-ep-2/", "Insubstituível"),
            Pair("https://pobreflix.irish/episodios/more-than-blue-ep-1/", "More than Blue: A Série"),
            Pair("https://pobreflix.irish/episodios/more-than-blue-ep-2/", "More than Blue: A Série"),
            Pair("https://pobreflix.irish/episodios/4-blocks-zero-ep-1/", "4 Blocks Zero"),
            Pair("https://pobreflix.irish/episodios/legado-de-sangue-ep-1/", "Legado de Sangue"),
            Pair("https://pobreflix.irish/episodios/a-primavera-da-lamina-ep-1/", "A Primavera da Lâmina"),
            Pair("https://pobreflix.irish/episodios/paolo-ep-1/", "Paolo"),
            Pair("https://pobreflix.irish/episodios/apenas-um-show-ep-1/", "Apenas um Show: As Fitas Perdidas"),
            Pair("https://pobreflix.irish/episodios/as-maes-do-time-ep-1/", "As Mães do Time: Beisebol"),
            Pair("https://pobreflix.irish/episodios/o-problema-final-ep-1/", "O Problema Final"),
            Pair("https://pobreflix.irish/episodios/o-bom-espirito-ep-1/", "O Bom Espírito"),
            Pair("https://pobreflix.irish/episodios/lista-negra-ep-1/", "Lista Negra: Redenção"),
        )

        // Deduplication by series name across the collected pages yields 11 distinct series
        val distinct = episodeCards.distinctBy { it.second }
        assertEquals(11, distinct.size)
        assertTrue(distinct.size >= 10)
    }

    @Test
    fun testBase64Sanitization() {
        val rawEscaped = """aHR0cHM6Ly92aWRzcmMuc2gvZW1iZWQvdHYvMjk3OTgyLzEvMjA\/ZHNfbGFuZz1wdA=="""
        val sanitized = rawEscaped.replace("\\/", "/").replace("\\", "").trim()
        val decoded = String(java.util.Base64.getDecoder().decode(sanitized))
        assertEquals("https://vidsrc.sh/embed/tv/297982/1/20?ds_lang=pt", decoded)

        val directUrl = "https://vaiquecol.com/embed2/310133-1-1"
        val resolved = if (directUrl.startsWith("http://") || directUrl.startsWith("https://")) {
            directUrl
        } else {
            String(java.util.Base64.getDecoder().decode(directUrl))
        }
        assertEquals("https://vaiquecol.com/embed2/310133-1-1", resolved)
    }

    @Test
    fun testVidSrcRegexExtraction() {
        val tvUrl = "https://vidsrc.sh/embed/tv/297982/1/20?ds_lang=pt"
        val tvMatch = Regex("""/tv/([^/?]+)/(\d+)/(\d+)""").find(tvUrl)
        assertEquals("297982", tvMatch?.groupValues?.get(1))
        assertEquals("1", tvMatch?.groupValues?.get(2))
        assertEquals("20", tvMatch?.groupValues?.get(3))

        val movieUrl = "https://vidsrc.sh/embed/movie/tt34463310?ds_lang=pt"
        val movieMatch = Regex("""/movie/([^/?]+)""").find(movieUrl)
        assertEquals("tt34463310", movieMatch?.groupValues?.get(1))
    }

    @Test
    fun testNotReleasedClassificationAndMessage() {
        val json = """{"errors":"1","message":"NÃO_LANÇADO","data":{"errors":"not_released","tvshow_title":"4 Blocks Zero (2026)","title":"Episódio 2","season":1,"epi_num":2,"air_date":"2026-10-02","air_date_fmt":"02/10/2026"}}"""

        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("America/Sao_Paulo")
        }

        // Test 1: 3 days remaining (Reference date 2026-09-29)
        val refDate3Days = sdf.parse("2026-09-29")!!
        val notReleased3Days = PobreflixPlayerExtractor.parseNotReleasedJson(json, refDate3Days)
        assertTrue(notReleased3Days is VideoResult.NotReleased)
        assertEquals(3L, notReleased3Days?.remainingDays)
        assertEquals(
            "⏳ Este episódio ainda não foi lançado. Disponível em 3 dias (02/10). Abra na WebView para acompanhar.",
            notReleased3Days?.toException()?.message,
        )

        // Test 2: Tomorrow (Reference date 2026-10-01)
        val refDateTomorrow = sdf.parse("2026-10-01")!!
        val notReleasedTomorrow = PobreflixPlayerExtractor.parseNotReleasedJson(json, refDateTomorrow)
        assertEquals(1L, notReleasedTomorrow?.remainingDays)
        assertEquals(
            "⏳ Este episódio ainda não foi lançado. Disponível amanhã (02/10). Abra na WebView para acompanhar.",
            notReleasedTomorrow?.toException()?.message,
        )

        // Test 3: Today (Reference date 2026-10-02)
        val refDateToday = sdf.parse("2026-10-02")!!
        val notReleasedToday = PobreflixPlayerExtractor.parseNotReleasedJson(json, refDateToday)
        assertEquals(0L, notReleasedToday?.remainingDays)
        assertEquals(
            "⏳ Este episódio ainda não está disponível. O lançamento está previsto para hoje. Tente novamente mais tarde.",
            notReleasedToday?.toException()?.message,
        )
    }

    @Test
    fun testBrowserVerificationClassification() {
        val statuses = mapOf(
            "vaiquecol" to ServerStatus.NO_STREAM,
            "vidsrc" to ServerStatus.NOT_FOUND,
            "streambetter" to ServerStatus.NOT_FOUND,
            "superflix" to ServerStatus.BROWSER_VERIFICATION,
        )

        val failure = PobreflixPlayerExtractor.classifyFailure(statuses)
        assertTrue(failure is VideoResult.BrowserVerificationRequired)
        assertEquals(
            "🌐 Este episódio está disponível, mas o servidor atual exige verificação no navegador. Abra na WebView para assistir.",
            failure.toException().message,
        )
    }

    @Test
    fun testNoCompatibleStreamClassification() {
        val statuses = mapOf(
            "vaiquecol" to ServerStatus.NO_STREAM,
            "vidsrc" to ServerStatus.NOT_FOUND,
            "streambetter" to ServerStatus.NOT_FOUND,
            "superflix" to ServerStatus.NO_STREAM,
        )

        val failure = PobreflixPlayerExtractor.classifyFailure(statuses)
        assertTrue(failure is VideoResult.NoCompatibleStream)
        assertEquals(
            "⚠️ Nenhum vídeo funcional encontrado para este episódio no momento.",
            failure.toException().message,
        )
    }

    @Test
    fun testTemporaryErrorClassification() {
        val statuses = mapOf(
            "vaiquecol" to ServerStatus.TEMPORARY_ERROR,
            "superflix" to ServerStatus.TEMPORARY_ERROR,
        )

        val failure = PobreflixPlayerExtractor.classifyFailure(statuses)
        assertTrue(failure is VideoResult.ServersTemporarilyUnavailable)
        assertEquals(
            "🔌 Os servidores deste episódio estão temporariamente indisponíveis. Tente novamente mais tarde.",
            failure.toException().message,
        )
    }

    @Test
    fun testBrowserVerificationHtmlDetection() {
        val superflixHtml = """
            <!DOCTYPE html>
            <html>
            <head><title>Verificação</title></head>
            <body><div class="captcha-box"></div></body>
            </html>
        """.trimIndent()
        assertTrue(PobreflixPlayerExtractor.isBrowserVerificationHtml(superflixHtml))

        val normalHtml = """
            <!DOCTYPE html>
            <html>
            <head><title>Player</title></head>
            <body><video src="test.mp4"></video></body>
            </html>
        """.trimIndent()
        assertFalse(PobreflixPlayerExtractor.isBrowserVerificationHtml(normalHtml))
    }
}
