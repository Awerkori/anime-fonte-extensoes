package eu.kanade.tachiyomi.animeextension.pt.berryanimes

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BerryAnimesTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun testDeserializeModernEpisodeCardWithoutMeta() {
        val modernJson = """
        {
            "id": 12345,
            "href": "/pt-br/watch/sample-token/1-1-episodio-1",
            "title": "1. O Começo",
            "thumbnailUrl": "https://img.berryanimes.com/thumb.webp",
            "duration": "24 min",
            "releaseDate": "03 de out. de 2002",
            "releaseDateIso": "2002-10-03T00:00:00.000Z",
            "hasSubtitles": true,
            "subtitlesLabel": "Legendas em Português",
            "ageRating": "14"
        }
        """.trimIndent()

        // Verifies fix for: MissingFieldException: Field 'meta' is required for type with serial name '...EpisodeCard'
        val card = json.decodeFromString<EpisodeCard>(modernJson)
        assertEquals("/pt-br/watch/sample-token/1-1-episodio-1", card.href)
        assertEquals("1. O Começo", card.title)
        assertNull(card.meta)
        assertEquals(true, card.hasSubtitles)
        assertEquals("Legendas em Português", card.subtitlesLabel)
    }

    @Test
    fun testDeserializeLegacyEpisodeCardWithMeta() {
        val legacyJson = """
        {
            "href": "/pt-br/watch/sample-token/1-2-episodio-2",
            "title": "2. Meu Nome é Konohamaru",
            "meta": "T1 | E2 | Leg"
        }
        """.trimIndent()

        val card = json.decodeFromString<EpisodeCard>(legacyJson)
        assertEquals("/pt-br/watch/sample-token/1-2-episodio-2", card.href)
        assertEquals("2. Meu Nome é Konohamaru", card.title)
        assertEquals("T1 | E2 | Leg", card.meta)
    }

    @Test
    fun testParseModernEpisodeCardWithoutMeta() {
        val card = EpisodeCard(
            href = "/pt-br/watch/token-abc/1-1-episodio-1",
            title = "1. O Começo",
            meta = null,
            hasSubtitles = true,
            hasDub = false,
        )

        val episode = BerryAnimesHelper.parseCardInfo(card)
        assertEquals("/pt-br/watch/token-abc/1-1-episodio-1", episode.url)
        assertEquals("T1 1. O Começo", episode.name)
        assertEquals(1.0f, episode.episodeNumber, 0.001f)
        assertEquals("Legendado", episode.scanlator)
    }

    @Test
    fun testParseModernEpisodeCardWithDubAndSub() {
        val card = EpisodeCard(
            href = "/pt-br/watch/token-abc/2-5-episodio-5",
            title = "5. Batalha Decisiva",
            meta = null,
            hasSubtitles = true,
            hasDub = true,
        )

        val episode = BerryAnimesHelper.parseCardInfo(card)
        assertEquals("/pt-br/watch/token-abc/2-5-episodio-5", episode.url)
        assertEquals("T2 5. Batalha Decisiva", episode.name)
        assertEquals(5.0f, episode.episodeNumber, 0.001f)
        assertEquals("Dublado / Legendado", episode.scanlator)
    }

    @Test
    fun testParseModernEpisodeCardDubOnly() {
        val card = EpisodeCard(
            href = "/pt-br/watch/token-abc/1-10-episodio-10",
            title = "10. Inimigo Poderoso",
            meta = null,
            hasSubtitles = false,
            hasDub = true,
        )

        val episode = BerryAnimesHelper.parseCardInfo(card)
        assertEquals("/pt-br/watch/token-abc/1-10-episodio-10", episode.url)
        assertEquals("T1 10. Inimigo Poderoso", episode.name)
        assertEquals(10.0f, episode.episodeNumber, 0.001f)
        assertEquals("Dublado", episode.scanlator)
    }

    @Test
    fun testParseModernEpisodeCardWithAudioLabels() {
        val cardSub = EpisodeCard(
            href = "/pt-br/watch/token-abc/1-3-episodio-3",
            title = "3. O Treino",
            subtitlesLabel = "Legendas em Português",
        )
        val epSub = BerryAnimesHelper.parseCardInfo(cardSub)
        assertEquals("Legendado", epSub.scanlator)

        val cardDub = EpisodeCard(
            href = "/pt-br/watch/token-abc/1-4-episodio-4",
            title = "4. O Desafio",
            dubLabel = "Áudio Dublado",
        )
        val epDub = BerryAnimesHelper.parseCardInfo(cardDub)
        assertEquals("Dublado", epDub.scanlator)
    }

    @Test
    fun testParseLegacyEpisodeCardWithFullMeta() {
        val card = EpisodeCard(
            href = "/pt-br/watch/token-abc/1-1-episodio-1",
            title = "O Começo",
            meta = "T1 | E1 | Leg | Dub",
        )

        val episode = BerryAnimesHelper.parseCardInfo(card)
        assertEquals("/pt-br/watch/token-abc/1-1-episodio-1", episode.url)
        assertEquals("T1 O Começo", episode.name)
        assertEquals(1.0f, episode.episodeNumber, 0.001f)
        assertEquals("Legendado / Dublado", episode.scanlator)
    }

    @Test
    fun testParseLegacyEpisodeCardWithDubOnly() {
        val card = EpisodeCard(
            href = "/pt-br/watch/token-abc/1-1-episodio-1",
            title = "O Começo",
            meta = "T1 | E1 | Dub",
        )

        val episode = BerryAnimesHelper.parseCardInfo(card)
        assertEquals("Dublado", episode.scanlator)
        assertEquals(1.0f, episode.episodeNumber, 0.001f)
    }

    @Test
    fun testParseEpisodeCardHighEpisodeCount() {
        val card = EpisodeCard(
            href = "/pt-br/watch/naruto-classico/1-219-episodio-219",
            title = "219. A Partida",
            meta = null,
            hasSubtitles = true,
        )

        val episode = BerryAnimesHelper.parseCardInfo(card)
        assertEquals("/pt-br/watch/naruto-classico/1-219-episodio-219", episode.url)
        assertEquals("T1 219. A Partida", episode.name)
        assertEquals(219.0f, episode.episodeNumber, 0.001f)
        assertEquals("Legendado", episode.scanlator)
    }

    @Test
    fun testParseEpisodeCardEmptyMetaAndFallback() {
        val card = EpisodeCard(
            href = "/pt-br/watch/token-xyz/2-15-episodio-15",
            title = "15. A Promessa",
            meta = "",
            hasSubtitles = null,
            hasDub = null,
        )

        val episode = BerryAnimesHelper.parseCardInfo(card)
        assertEquals("T2 15. A Promessa", episode.name)
        assertEquals(15.0f, episode.episodeNumber, 0.001f)
        assertNull(episode.scanlator)
    }

    @Test
    fun testEpisodeGridDeserializationOver24Episodes() {
        val episodeCount = 219
        val episodesJson = (1..episodeCount).joinToString(",") { i ->
            """
            {
                "id": $i,
                "href": "/pt-br/watch/naruto-token/1-$i-episodio-$i",
                "title": "$i. Episódio $i",
                "thumbnailUrl": "https://img.berryanimes.com/$i.webp",
                "hasSubtitles": true
            }
            """.trimIndent()
        }

        val gridJson = """{"episodes": [$episodesJson]}"""
        val grid = json.decodeFromString<EpisodeGrid>(gridJson)

        assertEquals(219, grid.episodes.size)
        val first = grid.episodes.first()
        val last = grid.episodes.last()

        val ep1 = BerryAnimesHelper.parseCardInfo(first)
        val ep219 = BerryAnimesHelper.parseCardInfo(last)

        assertEquals("T1 1. Episódio 1", ep1.name)
        assertEquals(1.0f, ep1.episodeNumber, 0.001f)

        assertEquals("T1 219. Episódio 219", ep219.name)
        assertEquals(219.0f, ep219.episodeNumber, 0.001f)
    }

    @Test
    fun testMultiSeasonEpisodeSorting() {
        val s1e1 = EpisodeCard(href = "/pt-br/watch/hero/1-1-episodio-1", title = "1. S1E1")
        val s1e2 = EpisodeCard(href = "/pt-br/watch/hero/1-2-episodio-2", title = "2. S1E2")
        val s2e1 = EpisodeCard(href = "/pt-br/watch/hero/2-1-episodio-1", title = "1. S2E1")
        val s2e2 = EpisodeCard(href = "/pt-br/watch/hero/2-2-episodio-2", title = "2. S2E2")

        val episodes = listOf(s1e1, s1e2, s2e1, s2e2).map(BerryAnimesHelper::parseCardInfo)

        val sorted = episodes.sortedWith(
            compareByDescending<ParsedEpisodeInfo> {
                it.name.substringAfter("T").substringBefore(" ").toIntOrNull() ?: 0
            }.thenByDescending { it.episodeNumber },
        )

        assertEquals("T2 2. S2E2", sorted[0].name)
        assertEquals("T2 1. S2E1", sorted[1].name)
        assertEquals("T1 2. S1E2", sorted[2].name)
        assertEquals("T1 1. S1E1", sorted[3].name)
    }

    @Test
    fun testParseSeasonsFromHtmlButtons() {
        val html = """
            <div>
                <button role="option" aria-selected="true">Temporada 1</button>
                <button role="option" aria-selected="false">Temporada 2</button>
                <button role="option" aria-selected="false">Temporada 3</button>
            </div>
        """.trimIndent()

        val otherSeasons = BerryAnimesHelper.parseSeasonsFromHtml(html, currentSeasonNumber = 1)
        assertEquals(listOf(2, 3), otherSeasons)
    }

    // === PLAYBACK & ADS AUTHORIZATION TESTS ===

    @Test
    fun testPlaybackAuthorizedStandardUserWithPreRollSlot() {
        // Real API payload for standard non-VIP user with client-side ad slot configuration:
        // Previously this caused false positive rejection ("Este episódio exige anúncio no player oficial...")
        val watchJson = """
        {
            "player": {
                "provider": "hls",
                "streamUrl": "https://media-cdn.berryanimes.com/hls/test/master.m3u8",
                "mimeType": "application/x-mpegURL",
                "streamProviderHeader": "x-berry-provider",
                "streamProviderValue": "berryanimes",
                "streamContextHeader": "x-berry-context",
                "streamContextToken": "ctx-token-12345",
                "codex_subtitle_tracks": [
                    {"url": "/subtitles/pt-BR.vtt", "label": "pt-BR"}
                ]
            },
            "ads": {
                "preRoll": true,
                "postRoll": false,
                "adFree": {
                    "active": false
                }
            }
        }
        """.trimIndent()

        val watch = json.decodeFromString<Watch>(watchJson)
        // Must authorize successfully without throwing!
        BerryAnimesHelper.checkPlaybackAuthorization(watch)
        assertNotNull(watch.player)
        assertEquals("https://media-cdn.berryanimes.com/hls/test/master.m3u8", watch.player?.streamUrl)
        assertEquals(1, watch.player?.subtitles?.size)
    }

    @Test
    fun testPlaybackAuthorizedWithMissingPreRollAndPostRoll() {
        // API response where preRoll and postRoll fields are omitted:
        // Previously defaulted to true, causing false positive rejection.
        val watchJson = """
        {
            "player": {
                "provider": "hls",
                "streamUrl": "https://media-cdn.berryanimes.com/hls/sample/master.m3u8"
            },
            "ads": {
                "adFree": {
                    "active": false
                }
            }
        }
        """.trimIndent()

        val watch = json.decodeFromString<Watch>(watchJson)
        assertNull(watch.ads?.preRoll)
        assertNull(watch.ads?.postRoll)
        BerryAnimesHelper.checkPlaybackAuthorization(watch)
    }

    @Test
    fun testPlaybackAuthorizedWithNullOrMissingAds() {
        // API response where ads object is null or missing:
        val watchJson = """
        {
            "player": {
                "streamUrl": "https://media-cdn.berryanimes.com/hls/sample/master.m3u8"
            }
        }
        """.trimIndent()

        val watch = json.decodeFromString<Watch>(watchJson)
        assertNull(watch.ads)
        BerryAnimesHelper.checkPlaybackAuthorization(watch)
    }

    @Test
    fun testPlaybackAuthorizedWithEmptyAdsObject() {
        val watchJson = """
        {
            "player": {
                "streamUrl": "https://media-cdn.berryanimes.com/hls/sample/master.m3u8"
            },
            "ads": {}
        }
        """.trimIndent()

        val watch = json.decodeFromString<Watch>(watchJson)
        assertNotNull(watch.ads)
        BerryAnimesHelper.checkPlaybackAuthorization(watch)
    }

    @Test
    fun testPlaybackBloggerProviderAuthorized() {
        // Real Blogger episode response (like One Piece):
        val watchJson = """
        {
            "player": {
                "provider": "blogger",
                "bloggerUrl": "https://www.blogger.com/video.g?token=blogger-token-xyz",
                "externalEmbedUrl": "https://www.blogger.com/video.g?token=blogger-token-xyz"
            },
            "ads": {
                "preRoll": false,
                "postRoll": false,
                "adFree": {
                    "active": false
                }
            }
        }
        """.trimIndent()

        val watch = json.decodeFromString<Watch>(watchJson)
        BerryAnimesHelper.checkPlaybackAuthorization(watch)
        assertEquals("blogger", watch.player?.provider)
        assertEquals("https://www.blogger.com/video.g?token=blogger-token-xyz", watch.player?.bloggerUrl)
    }

    @Test
    fun testPlaybackRejectedWhenAdExplicitlyRequired() {
        // When the site explicitly signals a mandatory/blocking ad wall:
        val watchJson = """
        {
            "player": {
                "streamUrl": "https://media-cdn.berryanimes.com/hls/sample/master.m3u8"
            },
            "ads": {
                "required": true,
                "adFree": {
                    "active": false
                }
            }
        }
        """.trimIndent()

        val watch = json.decodeFromString<Watch>(watchJson)
        try {
            BerryAnimesHelper.checkPlaybackAuthorization(watch)
            fail("Expected exception for explicitly required ad")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Este episódio exige anúncio no player oficial") == true)
        }
    }

    @Test
    fun testPlaybackAuthorizedWhenAdRequiredButUserIsAdFree() {
        // If site requires ad, but user has adFree active:
        val watchJson = """
        {
            "player": {
                "streamUrl": "https://media-cdn.berryanimes.com/hls/sample/master.m3u8"
            },
            "ads": {
                "required": true,
                "adFree": {
                    "active": true
                }
            }
        }
        """.trimIndent()

        val watch = json.decodeFromString<Watch>(watchJson)
        BerryAnimesHelper.checkPlaybackAuthorization(watch)
    }

    @Test
    fun testPlaybackRejectedWhenServerError() {
        val watchJson = """
        {
            "error": "Sessão expirada. Faça login novamente.",
            "player": null,
            "ads": null
        }
        """.trimIndent()

        val watch = json.decodeFromString<Watch>(watchJson)
        try {
            BerryAnimesHelper.checkPlaybackAuthorization(watch)
            fail("Expected exception for server error")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("O site recusou a reprodução") == true)
        }
    }

    @Test
    fun testPlaybackRejectedWhenPlayerMissing() {
        val watchJson = """
        {
            "player": null,
            "ads": {
                "adFree": {
                    "active": false
                }
            }
        }
        """.trimIndent()

        val watch = json.decodeFromString<Watch>(watchJson)
        try {
            BerryAnimesHelper.checkPlaybackAuthorization(watch)
            fail("Expected exception when player is missing")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("O site não disponibilizou o player") == true)
        }
    }
}
