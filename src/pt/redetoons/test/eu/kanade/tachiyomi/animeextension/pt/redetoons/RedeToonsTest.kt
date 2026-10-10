package eu.kanade.tachiyomi.animeextension.pt.redetoons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedeToonsTest {

    @Test
    fun testDefaultDomainPreferenceConstants() {
        assertEquals("https://redetoons.gay/browse", RedeToons.PREF_DOMAIN_DEFAULT)
        assertEquals("https://redetoons.gay", RedeToons.DEFAULT_BASE_URL)
        assertEquals("redetoons.gay", RedeToons.DEFAULT_BASE_HOST)
        assertEquals("preferred_domain", RedeToons.PREF_DOMAIN_KEY)
        assertEquals("Domínio atual (requer reinicialização da app)", RedeToons.PREF_DOMAIN_TITLE)
    }

    @Test
    fun testToBaseUrlNormalization() {
        // Standard default preference with /browse
        assertEquals("https://redetoons.gay", RedeToons.toBaseUrl("https://redetoons.gay/browse"))
        assertEquals("https://redetoons.gay", RedeToons.toBaseUrl("https://redetoons.gay/browse/"))

        // Already base domain
        assertEquals("https://redetoons.gay", RedeToons.toBaseUrl("https://redetoons.gay"))
        assertEquals("https://redetoons.gay", RedeToons.toBaseUrl("https://redetoons.gay/"))

        // Legacy domains
        assertEquals("https://redetoonstv.win", RedeToons.toBaseUrl("https://redetoonstv.win/"))
        assertEquals("https://redetoonstv.win", RedeToons.toBaseUrl("https://redetoonstv.win"))
        assertEquals("https://redetoons.win", RedeToons.toBaseUrl("https://redetoons.win/browse"))

        // Missing protocol prefix
        assertEquals("https://redetoons.gay", RedeToons.toBaseUrl("redetoons.gay/browse"))
        assertEquals("https://redetoons.gay", RedeToons.toBaseUrl("redetoons.gay"))

        // Reverse proxy at subpath
        assertEquals("https://proxy.example.com/sub", RedeToons.toBaseUrl("https://proxy.example.com/sub/browse"))
        assertEquals("https://proxy.example.com/sub", RedeToons.toBaseUrl("https://proxy.example.com/sub/browse/"))
        assertEquals("https://proxy.example.com/sub", RedeToons.toBaseUrl("https://proxy.example.com/sub"))

        // Custom port
        assertEquals("http://localhost:8080", RedeToons.toBaseUrl("http://localhost:8080/browse"))
        assertEquals("https://my-domain.org:8443", RedeToons.toBaseUrl("https://my-domain.org:8443/browse/"))

        // Query params and fragments stripped from base URL
        assertEquals("https://redetoons.gay", RedeToons.toBaseUrl("https://redetoons.gay/browse?source=app#main"))

        // Blank and null fallback to default
        assertEquals("https://redetoons.gay", RedeToons.toBaseUrl(null))
        assertEquals("https://redetoons.gay", RedeToons.toBaseUrl(""))
        assertEquals("https://redetoons.gay", RedeToons.toBaseUrl("   "))
    }

    @Test
    fun testIsValidUrl() {
        assertTrue(RedeToons.isValidUrl("https://redetoons.gay/browse"))
        assertTrue(RedeToons.isValidUrl("https://redetoons.gay"))
        assertTrue(RedeToons.isValidUrl("http://redetoons.gay"))
        assertTrue(RedeToons.isValidUrl("redetoons.gay/browse"))
        assertTrue(RedeToons.isValidUrl("redetoons.gay"))
        assertTrue(RedeToons.isValidUrl("https://redetoonstv.win/"))
        assertTrue(RedeToons.isValidUrl("https://proxy.example.com:8080/sub"))

        assertFalse(RedeToons.isValidUrl(""))
        assertFalse(RedeToons.isValidUrl("   "))
        assertFalse(RedeToons.isValidUrl(null))
        assertFalse(RedeToons.isValidUrl("ftp://redetoons.gay"))
        assertFalse(RedeToons.isValidUrl("invalid url with spaces"))
        assertFalse(RedeToons.isValidUrl("://invalid"))
    }

    @Test
    fun testNormalizeInputUrl() {
        assertEquals("https://redetoons.gay/browse", RedeToons.normalizeInputUrl("redetoons.gay/browse"))
        assertEquals("https://redetoons.gay/browse", RedeToons.normalizeInputUrl("https://redetoons.gay/browse/"))
        assertEquals("https://redetoons.gay", RedeToons.normalizeInputUrl("https://redetoons.gay/"))
        assertEquals("https://redetoons.gay", RedeToons.normalizeInputUrl("https://redetoons.gay"))
        assertEquals("http://redetoons.gay", RedeToons.normalizeInputUrl("http://redetoons.gay/"))
    }

    @Test
    fun testLegacyHostRewriting() {
        val baseHost = "redetoons.gay"
        assertEquals(
            "https://redetoons.gay/api/shelves?shelf=popular",
            "https://redetoonstv.win/api/shelves?shelf=popular".toRedeToonsHost(baseHost),
        )
        assertEquals(
            "https://redetoons.gay/serie/60572",
            "https://redetoons.win/serie/60572".toRedeToonsHost(baseHost),
        )
        assertEquals(
            "https://redetoons.gay/browse",
            "https://www.redetoonstv.win/browse".toRedeToonsHost(baseHost),
        )

        // Rewriting to custom proxy
        val proxyHost = "myproxy.example.com"
        assertEquals(
            "https://myproxy.example.com/api/shelves",
            "https://redetoons.gay/api/shelves".toRedeToonsHost(proxyHost),
        )
    }

    @Test
    fun testPreserveExternalHosts() {
        val baseHost = "redetoons.gay"

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
        val baseHost = "redetoons.gay"

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
            "https://redetoons.gay/images/poster.jpg",
            RedeToons.resolvePosterUrl("https://redetoonstv.win/images/poster.jpg", baseHost),
        )
    }

    @Test
    fun testResolveAnimeUrl() {
        val baseUrl = "https://redetoons.gay"
        val baseHost = "redetoons.gay"

        // Relative URL
        assertEquals("https://redetoons.gay/tv/60572", RedeToons.resolveAnimeUrl("tv/60572", baseUrl, baseHost))
        assertEquals("https://redetoons.gay/movie/436931", RedeToons.resolveAnimeUrl("/movie/436931", baseUrl, baseHost))

        // Absolute legacy URL rewritten
        assertEquals("https://redetoons.gay/tv/60572", RedeToons.resolveAnimeUrl("https://redetoonstv.win/tv/60572", baseUrl, baseHost))
    }

    @Test
    fun testResolveAnimeDetailsEndpoint() {
        val baseUrl = "https://redetoons.gay"
        val baseHost = "redetoons.gay"

        // Relative TV / Movie path
        assertEquals(
            "https://redetoons.gay/api/tmdb/tv/60572",
            RedeToons.resolveAnimeDetailsEndpoint("tv/60572", baseUrl, baseHost),
        )
        assertEquals(
            "https://redetoons.gay/api/tmdb/movie/436931",
            RedeToons.resolveAnimeDetailsEndpoint("/movie/436931", baseUrl, baseHost),
        )

        // Legacy absolute URL
        assertEquals(
            "https://redetoons.gay/api/tmdb/tv/60572",
            RedeToons.resolveAnimeDetailsEndpoint("https://redetoonstv.win/tv/60572", baseUrl, baseHost),
        )
        assertEquals(
            "https://redetoons.gay/api/tmdb/movie/436931",
            RedeToons.resolveAnimeDetailsEndpoint("https://redetoons.win/api/tmdb/movie/436931", baseUrl, baseHost),
        )
    }
}
