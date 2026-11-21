package eu.kanade.tachiyomi.animeextension.pt.redetoons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedeToonsTest {

    @Test
    fun testDefaultDomainPreferenceConstants() {
        assertEquals("https://redetoons.li", PREF_DOMAIN_DEFAULT)
        assertEquals("https://redetoons.li", DEFAULT_BASE_URL)
        assertEquals("preferred_domain", PREF_DOMAIN_KEY)
        assertEquals("Domínio atual (requer reinicialização da app)", PREF_DOMAIN_TITLE)
    }

    @Test
    fun testToBaseUrlNormalization() {
        // Standard default preference with /browse
        assertEquals("https://redetoons.my", toBaseUrl("https://redetoons.my/browse"))
        assertEquals("https://redetoons.my", toBaseUrl("https://redetoons.my/browse/"))

        // Already base domain
        assertEquals("https://redetoons.my", toBaseUrl("https://redetoons.my"))
        assertEquals("https://redetoons.my", toBaseUrl("https://redetoons.my/"))

        // Previous and legacy domains
        assertEquals("https://redetoons.icu", toBaseUrl("https://redetoons.icu/browse"))
        assertEquals("https://redetoons.gay", toBaseUrl("https://redetoons.gay/browse"))
        assertEquals("https://redetoonstv.win", toBaseUrl("https://redetoonstv.win/"))
        assertEquals("https://redetoonstv.win", toBaseUrl("https://redetoonstv.win"))
        assertEquals("https://redetoons.win", toBaseUrl("https://redetoons.win/browse"))

        // Missing protocol prefix
        assertEquals("https://redetoons.my", toBaseUrl("redetoons.my/browse"))
        assertEquals("https://redetoons.my", toBaseUrl("redetoons.my"))

        // Reverse proxy at subpath
        assertEquals("https://proxy.example.com/sub", toBaseUrl("https://proxy.example.com/sub/browse"))
        assertEquals("https://proxy.example.com/sub", toBaseUrl("https://proxy.example.com/sub/browse/"))
        assertEquals("https://proxy.example.com/sub", toBaseUrl("https://proxy.example.com/sub"))

        // Custom port
        assertEquals("http://localhost:8080", toBaseUrl("http://localhost:8080/browse"))
        assertEquals("https://my-domain.org:8443", toBaseUrl("https://my-domain.org:8443/browse/"))

        // Query params and fragments stripped from base URL
        assertEquals("https://redetoons.my", toBaseUrl("https://redetoons.my/browse?source=app#main"))

        // Blank and null fallback to default
        assertEquals("https://redetoons.li", toBaseUrl(null))
        assertEquals("https://redetoons.li", toBaseUrl(""))
        assertEquals("https://redetoons.li", toBaseUrl("   "))
    }

    @Test
    fun testIsValidUrl() {
        assertTrue(isValidUrl("https://redetoons.my/browse"))
        assertTrue(isValidUrl("https://redetoons.my"))
        assertTrue(isValidUrl("https://redetoons.icu/browse"))
        assertTrue(isValidUrl("https://redetoons.gay/browse"))
        assertTrue(isValidUrl("http://redetoons.my"))
        assertTrue(isValidUrl("redetoons.my/browse"))
        assertTrue(isValidUrl("redetoons.my"))
        assertTrue(isValidUrl("https://redetoonstv.win/"))
        assertTrue(isValidUrl("https://proxy.example.com:8080/sub"))

        assertFalse(isValidUrl(""))
        assertFalse(isValidUrl("   "))
        assertFalse(isValidUrl(null))
        assertFalse(isValidUrl("ftp://redetoons.my"))
        assertFalse(isValidUrl("invalid url with spaces"))
        assertFalse(isValidUrl("://invalid"))
    }

    @Test
    fun testNormalizeInputUrl() {
        assertEquals("https://redetoons.my/browse", normalizeInputUrl("redetoons.my/browse"))
        assertEquals("https://redetoons.my/browse", normalizeInputUrl("https://redetoons.my/browse/"))
        assertEquals("https://redetoons.my", normalizeInputUrl("https://redetoons.my/"))
        assertEquals("https://redetoons.my", normalizeInputUrl("https://redetoons.my"))
        assertEquals("http://redetoons.my", normalizeInputUrl("http://redetoons.my/"))
    }

    @Test
    fun testResolveBaseUrlFallsBackFromRetiredDomains() {
        assertEquals("https://redetoons.li", resolveBaseUrl(null))
        assertEquals("https://redetoons.li", resolveBaseUrl("  "))
        assertEquals("https://redetoons.li", resolveBaseUrl("https://redetoons.my/browse"))
        assertEquals("https://redetoons.li", resolveBaseUrl("redetoons.my"))
        assertEquals("https://redetoons.li", resolveBaseUrl("https://www.redetoons.my"))
        assertEquals("https://redetoons.li", resolveBaseUrl("https://redetoons.gay/browse"))
    }

    // Every domain the extension ever used is dead or someone else's by now: the account must not follow a stored one.
    @Test
    fun everyDomainTheExtensionUsedBeforeFallsBackToTheDefault() {
        for (host in listOf("redetoons.my", "redetoons.gay", "redetoons.win", "redetoonstv.win", "redetoons.icu", "redetoons.bond", "redetoons.beer")) {
            assertEquals(host, "https://redetoons.li", resolveBaseUrl("https://$host"))
            assertEquals(host, "https://redetoons.li", resolveBaseUrl("www.$host/browse"))
            assertEquals(host, "https://redetoons.li", resolveBaseUrl("https://sub.$host"))
        }
    }

    @Test
    fun testResolveBaseUrlKeepsADomainTheUserChose() {
        assertEquals("https://redetoons.li", resolveBaseUrl("https://redetoons.li/browse"))
        assertEquals("https://proxy.example.com/sub", resolveBaseUrl("https://proxy.example.com/sub/browse"))
        assertEquals("https://mirror.example.org", resolveBaseUrl("https://mirror.example.org"))
    }

    // the account goes in the login form: never over plain http, even if the user typed an http domain
    @Test
    fun onlyHttpsMayReceiveTheAccount() {
        assertTrue(isSecureUrl("https://redetoons.li"))
        assertTrue(isSecureUrl("HTTPS://redetoons.li/br"))
        assertFalse(isSecureUrl("http://redetoons.li"))
        assertFalse(isSecureUrl("redetoons.li"))
        assertFalse(isSecureUrl(""))
    }
}
