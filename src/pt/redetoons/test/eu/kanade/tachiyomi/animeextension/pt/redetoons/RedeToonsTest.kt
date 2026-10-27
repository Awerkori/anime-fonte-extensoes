package eu.kanade.tachiyomi.animeextension.pt.redetoons

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedeToonsTest {

    @Test
    fun testDefaultDomainPreferenceConstants() {
        assertEquals("https://redetoons.my/browse", RedeToons.PREF_DOMAIN_DEFAULT)
        assertEquals("https://redetoons.my", RedeToons.DEFAULT_BASE_URL)
        assertEquals("redetoons.my", RedeToons.DEFAULT_BASE_HOST)
        assertEquals("preferred_domain", RedeToons.PREF_DOMAIN_KEY)
        assertEquals("Domínio atual (requer reinicialização da app)", RedeToons.PREF_DOMAIN_TITLE)
    }

    @Test
    fun testToBaseUrlNormalization() {
        // Standard default preference with /browse
        assertEquals("https://redetoons.my", RedeToons.toBaseUrl("https://redetoons.my/browse"))
        assertEquals("https://redetoons.my", RedeToons.toBaseUrl("https://redetoons.my/browse/"))

        // Already base domain
        assertEquals("https://redetoons.my", RedeToons.toBaseUrl("https://redetoons.my"))
        assertEquals("https://redetoons.my", RedeToons.toBaseUrl("https://redetoons.my/"))

        // Previous and legacy domains
        assertEquals("https://redetoons.icu", RedeToons.toBaseUrl("https://redetoons.icu/browse"))
        assertEquals("https://redetoons.gay", RedeToons.toBaseUrl("https://redetoons.gay/browse"))
        assertEquals("https://redetoonstv.win", RedeToons.toBaseUrl("https://redetoonstv.win/"))
        assertEquals("https://redetoonstv.win", RedeToons.toBaseUrl("https://redetoonstv.win"))
        assertEquals("https://redetoons.win", RedeToons.toBaseUrl("https://redetoons.win/browse"))

        // Missing protocol prefix
        assertEquals("https://redetoons.my", RedeToons.toBaseUrl("redetoons.my/browse"))
        assertEquals("https://redetoons.my", RedeToons.toBaseUrl("redetoons.my"))

        // Reverse proxy at subpath
        assertEquals("https://proxy.example.com/sub", RedeToons.toBaseUrl("https://proxy.example.com/sub/browse"))
        assertEquals("https://proxy.example.com/sub", RedeToons.toBaseUrl("https://proxy.example.com/sub/browse/"))
        assertEquals("https://proxy.example.com/sub", RedeToons.toBaseUrl("https://proxy.example.com/sub"))

        // Custom port
        assertEquals("http://localhost:8080", RedeToons.toBaseUrl("http://localhost:8080/browse"))
        assertEquals("https://my-domain.org:8443", RedeToons.toBaseUrl("https://my-domain.org:8443/browse/"))

        // Query params and fragments stripped from base URL
        assertEquals("https://redetoons.my", RedeToons.toBaseUrl("https://redetoons.my/browse?source=app#main"))

        // Blank and null fallback to default
        assertEquals("https://redetoons.my", RedeToons.toBaseUrl(null))
        assertEquals("https://redetoons.my", RedeToons.toBaseUrl(""))
        assertEquals("https://redetoons.my", RedeToons.toBaseUrl("   "))
    }

    @Test
    fun testIsValidUrl() {
        assertTrue(RedeToons.isValidUrl("https://redetoons.my/browse"))
        assertTrue(RedeToons.isValidUrl("https://redetoons.my"))
        assertTrue(RedeToons.isValidUrl("https://redetoons.icu/browse"))
        assertTrue(RedeToons.isValidUrl("https://redetoons.gay/browse"))
        assertTrue(RedeToons.isValidUrl("http://redetoons.my"))
        assertTrue(RedeToons.isValidUrl("redetoons.my/browse"))
        assertTrue(RedeToons.isValidUrl("redetoons.my"))
        assertTrue(RedeToons.isValidUrl("https://redetoonstv.win/"))
        assertTrue(RedeToons.isValidUrl("https://proxy.example.com:8080/sub"))

        assertFalse(RedeToons.isValidUrl(""))
        assertFalse(RedeToons.isValidUrl("   "))
        assertFalse(RedeToons.isValidUrl(null))
        assertFalse(RedeToons.isValidUrl("ftp://redetoons.my"))
        assertFalse(RedeToons.isValidUrl("invalid url with spaces"))
        assertFalse(RedeToons.isValidUrl("://invalid"))
    }

    @Test
    fun testNormalizeInputUrl() {
        assertEquals("https://redetoons.my/browse", RedeToons.normalizeInputUrl("redetoons.my/browse"))
        assertEquals("https://redetoons.my/browse", RedeToons.normalizeInputUrl("https://redetoons.my/browse/"))
        assertEquals("https://redetoons.my", RedeToons.normalizeInputUrl("https://redetoons.my/"))
        assertEquals("https://redetoons.my", RedeToons.normalizeInputUrl("https://redetoons.my"))
        assertEquals("http://redetoons.my", RedeToons.normalizeInputUrl("http://redetoons.my/"))
    }

    @Test
    fun testLegacyHostRewriting() {
        val baseHost = "redetoons.my"
        assertEquals(
            "https://redetoons.my/api/shelves?shelf=popular",
            "https://redetoonstv.win/api/shelves?shelf=popular".toRedeToonsHost(baseHost),
        )
        assertEquals(
            "https://redetoons.my/serie/60572",
            "https://redetoons.win/serie/60572".toRedeToonsHost(baseHost),
        )
        assertEquals(
            "https://redetoons.my/browse",
            "https://www.redetoonstv.win/browse".toRedeToonsHost(baseHost),
        )
        assertEquals(
            "https://redetoons.my/api/play-link?contract=3",
            "https://redetoons.gay/api/play-link?contract=3".toRedeToonsHost(baseHost),
        )
        assertEquals(
            "https://redetoons.my/api/play-link?contract=3",
            "https://redetoons.icu/api/play-link?contract=3".toRedeToonsHost(baseHost),
        )
        assertEquals(
            "https://redetoons.my/browse",
            "https://redetoons.bond/browse".toRedeToonsHost(baseHost),
        )
        assertEquals(
            "https://redetoons.my/browse",
            "https://redetoons.beer/browse".toRedeToonsHost(baseHost),
        )

        // Rewriting to custom proxy
        val proxyHost = "myproxy.example.com"
        assertEquals(
            "https://myproxy.example.com/api/shelves",
            "https://redetoons.my/api/shelves".toRedeToonsHost(proxyHost),
        )
    }

    @Test
    fun testPreserveExternalHosts() {
        val baseHost = "redetoons.my"

        // TMDB images must never be rewritten
        assertEquals(
            "https://image.tmdb.org/t/p/w500/ck1nfqYxMkiGvBVztIuM6b7fHoC.jpg",
            "https://image.tmdb.org/t/p/w500/ck1nfqYxMkiGvBVztIuM6b7fHoC.jpg".toRedeToonsHost(baseHost),
        )

        // Video hosts must never be rewritten
        assertEquals(
            "https://cnn.radiogaucha.fun/stream/video.mp4",
            "https://cnn.radiogaucha.fun/stream/video.mp4".toRedeToonsHost(baseHost),
        )
        assertEquals(
            "https://redetoons.download/video/stream.m3u8",
            "https://redetoons.download/video/stream.m3u8".toRedeToonsHost(baseHost),
        )
        assertEquals(
            "https://caster.redetoons.download/stream.m3u8",
            "https://caster.redetoons.download/stream.m3u8".toRedeToonsHost(baseHost),
        )
        assertEquals(
            "https://rave.redetoons.download/hls/manifest.m3u8",
            "https://rave.redetoons.download/hls/manifest.m3u8".toRedeToonsHost(baseHost),
        )
        assertEquals(
            "https://shop.8r5v1p.shop/media/video.mp4",
            "https://shop.8r5v1p.shop/media/video.mp4".toRedeToonsHost(baseHost),
        )
    }

    @Test
    fun testResolvePosterUrl() {
        val baseHost = "redetoons.my"

        // Relative path starting with slash
        assertEquals(
            "https://image.tmdb.org/t/p/w500/ck1nfqYx.jpg",
            RedeToons.resolvePosterUrl("/ck1nfqYx.jpg", baseHost),
        )

        // Relative path without slash
        assertEquals(
            "https://image.tmdb.org/t/p/w500/ck1nfqYx.jpg",
            RedeToons.resolvePosterUrl("ck1nfqYx.jpg", baseHost),
        )

        // Absolute TMDB URL
        assertEquals(
            "https://image.tmdb.org/t/p/w500/caRQp2qs.jpg",
            RedeToons.resolvePosterUrl("https://image.tmdb.org/t/p/w500/caRQp2qs.jpg", baseHost),
        )

        // Old host poster rewritten
        assertEquals(
            "https://redetoons.my/images/poster.jpg",
            RedeToons.resolvePosterUrl("https://redetoonstv.win/images/poster.jpg", baseHost),
        )
    }

    @Test
    fun testResolveAnimeUrl() {
        val baseUrl = "https://redetoons.my"
        val baseHost = "redetoons.my"

        // Relative URL
        assertEquals("https://redetoons.my/tv/60572", RedeToons.resolveAnimeUrl("tv/60572", baseUrl, baseHost))
        assertEquals("https://redetoons.my/movie/436931", RedeToons.resolveAnimeUrl("/movie/436931", baseUrl, baseHost))

        // Absolute legacy URL rewritten
        assertEquals("https://redetoons.my/tv/60572", RedeToons.resolveAnimeUrl("https://redetoonstv.win/tv/60572", baseUrl, baseHost))
    }

    @Test
    fun testResolveAnimeDetailsEndpoint() {
        val baseUrl = "https://redetoons.my"
        val baseHost = "redetoons.my"

        // Relative TV / Movie path
        assertEquals(
            "https://redetoons.my/api/tmdb/tv/60572",
            RedeToons.resolveAnimeDetailsEndpoint("tv/60572", baseUrl, baseHost),
        )
        assertEquals(
            "https://redetoons.my/api/tmdb/movie/436931",
            RedeToons.resolveAnimeDetailsEndpoint("/movie/436931", baseUrl, baseHost),
        )

        // Legacy absolute URL
        assertEquals(
            "https://redetoons.my/api/tmdb/tv/60572",
            RedeToons.resolveAnimeDetailsEndpoint("https://redetoonstv.win/tv/60572", baseUrl, baseHost),
        )
        assertEquals(
            "https://redetoons.my/api/tmdb/movie/436931",
            RedeToons.resolveAnimeDetailsEndpoint("https://redetoons.win/api/tmdb/movie/436931", baseUrl, baseHost),
        )
    }

    @Test
    fun testVideoHeadersGeneration() {
        val dummyHeaders = Headers.Builder()
            .set("Referer", "https://redetoons.my/")
            .set("Origin", "https://redetoons.my")
            .set("Accept", "application/json")
            .build()
        val url = "https://redetoons.my/api/play-link?contract=3&tmdbId=317216&type=tv&season=1&episode=9".toHttpUrl()
        val headers = RedeToons.videoHeaders(url, dummyHeaders)
        // Must have trailing slash for Cloudflare CDN validation
        assertEquals("https://redetoons.my/", headers["Referer"])
        assertEquals("https://redetoons.my", headers["Origin"])
        assertNull(headers["Accept"])
    }

    @Test
    fun testVideoHeadersWithLegacyRedirectFallback() {
        // If request URL was legacy redetoons.icu, effective host must be canonical DEFAULT_BASE_HOST
        val dummyHeaders = Headers.Builder()
            .set("Referer", "https://redetoons.icu/")
            .set("Origin", "https://redetoons.icu")
            .set("Accept", "application/json")
            .build()
        val url = "https://redetoons.icu/api/play-link?contract=3&tmdbId=317216&type=tv&season=1&episode=9".toHttpUrl()
        val headers = RedeToons.videoHeaders(url, dummyHeaders)
        assertEquals("https://redetoons.my/", headers["Referer"])
        assertEquals("https://redetoons.my", headers["Origin"])
        assertNull(headers["Accept"])
    }

    @Test
    fun testIsExpiredSignedUrl() {
        val now = 1000000L
        val expiredUrl = "https://cnn.radiogaucha.fun/media.mp4?exp=999900&sig=abc"
        val validUrl = "https://cnn.radiogaucha.fun/media.mp4?exp=1003600&sig=abc"
        val unsignedUrl = "https://cnn.radiogaucha.fun/media.mp4"

        assertTrue(RedeToons.isExpiredSignedUrl(expiredUrl, now))
        assertFalse(RedeToons.isExpiredSignedUrl(validUrl, now))
        assertFalse(RedeToons.isExpiredSignedUrl(unsignedUrl, now))
    }

    @Test
    fun testBuildHosterUrl() {
        val tvUrl = RedeToons.buildHosterUrl("https://redetoons.my", "317216|1|9")
        assertEquals("https://redetoons.my/api/play-link?contract=3&tmdbId=317216&type=tv&season=1&episode=9", tvUrl)

        val movieUrl = RedeToons.buildHosterUrl("https://redetoons.my", "movie|532323")
        assertEquals("https://redetoons.my/api/play-link?contract=3&tmdbId=532323&type=movie", movieUrl)
    }
}
