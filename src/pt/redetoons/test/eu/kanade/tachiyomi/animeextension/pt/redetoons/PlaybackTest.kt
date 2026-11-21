package eu.kanade.tachiyomi.animeextension.pt.redetoons

import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Playback of redetoons.li (project-nox-requests#128): log in, open /br/watch/... for its embedUrl, then
 * GET /api/play/{p} for the signed MP4. Fixtures are real responses (2026-11-21) with tokens, e-mail and
 * names replaced by fake values.
 */
class PlaybackTest {

    @Before
    fun setUp() = registerTestJson()

    private fun doc(html: String) = Jsoup.parse(html)

    // /br/watch/46260?e=1059121&type=tv&s=1, logged in
    private val watchTv = """<!DOCTYPE html><html lang="pt-BR"><head><title>RedeToons</title></head><body><div id="app" data-page=""></div><script id="su-page-data" type="application/json">{"component":"Titles/Watch","url":"/br/x","version":"v1","props":{"title":{"id":46260,"name":"Naruto","type":"tv"},"embedUrl":"/br/iframe/_su.html?eid=1059121&ename=Naruto+Uzumaki+Chegando%21&enum=1&next=1&p=FAKE-P-TOKEN-tv-0123456789abcdefghijklmnop&snum=1&tid=46260&title=Naruto+-+Naruto+Uzumaki+Chegando%21&v=0123456789","episode":{"id":1059121,"number":1,"name":"Naruto Uzumaki Chegando!"},"nextEpisode":{"id":1059122,"number":2,"name":"Meu Nome é Konohamaru!"},"referer":null,"minute":null}}</script></body></html>"""

    // /br/watch/603?type=movie, logged in
    private val watchMovie = """<!DOCTYPE html><html lang="pt-BR"><head><title>RedeToons</title></head><body><div id="app" data-page=""></div><script id="su-page-data" type="application/json">{"component":"Titles/Watch","url":"/br/x","version":"v1","props":{"title":{"id":603,"name":"Matrix","type":"movie"},"embedUrl":"/br/iframe/_su.html?p=FAKE-P-TOKEN-movie-0123456789abcdefghij&tid=603&title=Matrix&v=0123456789","episode":null,"nextEpisode":null,"referer":null,"minute":null}}</script></body></html>"""

    // what /br/watch/... answers without a session: the login page
    private val loginPage = """<!DOCTYPE html><html lang="pt-BR"><head><title>RedeToons</title></head><body><div id="app" data-page=""></div><script id="su-page-data" type="application/json">{"component":"@core/Auth/Login","url":"/br/x","version":"bcc1d2551896dad34c81df966342515e","props":{"auth":{"user":null,"permissions":[],"role":null,"notifications":[],"favourites":[]},"errors":{},"flash":{"success":null,"error":null}}}</script></body></html>"""

    // /br with a session
    private val homeLoggedIn = """<!DOCTYPE html><html lang="pt-BR"><head><title>RedeToons</title></head><body><div id="app" data-page=""></div><script id="su-page-data" type="application/json">{"component":"Home","url":"/br/x","version":"bcc1d2551896dad34c81df966342515e","props":{"auth":{"user":{"avatar_html":"<i></i>","badge":null,"created_at_diff":"há 1 dia","email":"teste@example.com","id":1,"name":"Teste"},"permissions":[],"role":null,"notifications":[],"favourites":[]}}}</script></body></html>"""

    // /api/play/{p}
    private val playJson = """{"src":"https://cnn.radiogaucha.fun/video/exemplo.mp4?exp=1795000000&sig=fakesig","sources":[{"audio":"DUB","label":"PT-BR · DUB","src":"https://cnn.radiogaucha.fun/video/exemplo.mp4?exp=1795000000&sig=fakesig","crop":null}],"grant":"fake.grant.token"}"""

    // /api/play/renovar
    private val renewJson = """{"p":"FAKE-P-TOKEN-renewed-0123456789abcdefghij"}"""

    private fun season(vararg ids: Long) = SiteSeason(number = 2, episodes = ids.mapIndexed { i, id -> SiteEpisode(id, 100 + i) })

    @Test
    fun theLoginFormCarriesTheFieldsTheSiteExpects() {
        val body = LoginForm("user@example.com", "pa\"ss", remember = false, recaptchaToken = "").toJsonString()
        val form = body.parseAs<LoginForm>()

        assertEquals("user@example.com", form.username)
        assertEquals("pa\"ss", form.password)
        assertFalse(form.remember)
        assertEquals("", form.recaptchaToken)
        assertTrue(body.contains("\"recaptcha_token\""))
    }

    @Test
    fun readsTheInertiaVersionTheLoginPostMustEcho() {
        assertEquals("bcc1d2551896dad34c81df966342515e", inertiaVersion(doc(loginPage)))
    }

    @Test
    fun onlyAPageWithAUserMeansTheSessionIsActive() {
        assertTrue(isLoggedIn(doc(homeLoggedIn)))
        assertFalse(isLoggedIn(doc(loginPage)))
    }

    @Test
    fun theWatchPageGivesTheEmbedUrlAndTheLoginPageGivesNone() {
        assertNotNull(watchEmbedUrl(doc(watchTv)))
        assertTrue(watchEmbedUrl(doc(watchMovie))!!.startsWith("/br/iframe/_su.html?"))
        assertNull(watchEmbedUrl(doc(loginPage)))
    }

    @Test
    fun theTokenToAskForTheVideoIsTheEmbedUrlPParameter() {
        assertEquals("FAKE-P-TOKEN-tv-0123456789abcdefghijklmnop", playToken(watchEmbedUrl(doc(watchTv))!!))
        assertEquals("FAKE-P-TOKEN-movie-0123456789abcdefghij", playToken(watchEmbedUrl(doc(watchMovie))!!))
        assertNull(playToken("/br/iframe/_su.html?tid=1"))
    }

    @Test
    fun readsTheSignedMp4AndItsAudioLabel() {
        val sources = playSources(playJson.parseAs<PlayResponse>())

        assertEquals(1, sources.size)
        assertEquals("DUB", sources[0].audio)
        assertTrue(sources[0].src.startsWith("https://") && ".mp4?" in sources[0].src)
        assertEquals("RedeToons - PT-BR · DUB", sourceTitle(sources[0]))
    }

    @Test
    fun fallsBackToTheMainSrcWhenThereIsNoSourceList() {
        val sources = playSources("""{"src":"https://cdn.example/v.mp4?exp=1&sig=x"}""".parseAs<PlayResponse>())

        assertEquals(1, sources.size)
        assertEquals("https://cdn.example/v.mp4?exp=1&sig=x", sources[0].src)
        assertEquals("RedeToons - Padrão", sourceTitle(sources[0]))
    }

    @Test
    fun renewsAnExpiredTokenWithTheBodyTheSiteExpects() {
        assertEquals("""{"p":"abc\"d"}""", RenewRequest("abc\"d").toJsonString())
        assertEquals("FAKE-P-TOKEN-renewed-0123456789abcdefghij", renewJson.parseAs<RenewResponse>().p)
    }

    @Test
    fun readsTheEpisodeUrlsKeptSinceTheOldSource() {
        val tv = episodeRef("46260|2|5")!!
        assertEquals("tv", tv.type)
        assertEquals(46260, tv.id)
        assertEquals(2, tv.season)
        assertEquals(5, tv.position)

        val movie = episodeRef("movie|603")!!
        assertEquals("movie", movie.type)
        assertEquals(603, movie.id)

        assertNull(episodeRef("lixo"))
        assertNull(episodeRef("46260|x|5"))
    }

    @Test
    fun picksTheEpisodeIdByItsPositionInTheSeason() {
        val s = season(900, 901, 902)

        assertEquals(900L, episodeIdAt(s, 1))
        assertEquals(902L, episodeIdAt(s, 3))
        assertNull(episodeIdAt(s, 4))
        assertNull(episodeIdAt(s, 0))
        assertNull(episodeIdAt(null, 1))
    }

    @Test
    fun buildsTheWatchPathForSeriesAndMovies() {
        assertEquals("/br/watch/46260?e=1059121&type=tv&s=1", watchPath(episodeRef("46260|1|1")!!, 1059121))
        assertEquals("/br/watch/603?type=movie", watchPath(episodeRef("movie|603")!!, null))
    }
}
