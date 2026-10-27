package eu.kanade.tachiyomi.animeextension.pt.animy

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimyTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val baseUrl = "https://animy.net"

    @Test
    fun testPopularRequestUrl() {
        val url1 = Animy.buildPopularUrl(baseUrl, 1)
        assertEquals("https://animy.net/api/v1/animes?sort=popular&limit=24&offset=0", url1)

        val url2 = Animy.buildPopularUrl(baseUrl, 2)
        assertEquals("https://animy.net/api/v1/animes?sort=popular&limit=24&offset=24", url2)

        val url3 = Animy.buildPopularUrl(baseUrl, 3)
        assertEquals("https://animy.net/api/v1/animes?sort=popular&limit=24&offset=48", url3)
    }

    @Test
    fun testLatestUpdatesRequestUrl() {
        val url1 = Animy.buildLatestUrl(baseUrl, 1)
        assertEquals("https://animy.net/api/v1/animes?sort=newest&limit=24&offset=0", url1)

        val url2 = Animy.buildLatestUrl(baseUrl, 2)
        assertEquals("https://animy.net/api/v1/animes?sort=newest&limit=24&offset=24", url2)
    }

    @Test
    fun testSearchAnimeRequestWithFilters() {
        // 1. Text only
        val urlText = Animy.buildSearchUrl(baseUrl, 1, "Naruto").toHttpUrl()
        assertEquals("Naruto", urlText.queryParameter("search"))
        assertEquals("popular", urlText.queryParameter("sort"))
        assertEquals("24", urlText.queryParameter("limit"))
        assertEquals("0", urlText.queryParameter("offset"))

        // 2. Text + Audio + Genre
        val urlFiltered = Animy.buildSearchUrl(
            baseUrl = baseUrl,
            page = 2,
            query = "Naruto",
            audio = "dublado",
            genre = "Ação",
        ).toHttpUrl()
        assertEquals("Naruto", urlFiltered.queryParameter("search"))
        assertEquals("dublado", urlFiltered.queryParameter("audio"))
        assertEquals("Ação", urlFiltered.queryParameter("genre"))
        assertEquals("24", urlFiltered.queryParameter("offset"))

        // 3. Kind + Status + Sort
        val urlKind = Animy.buildSearchUrl(
            baseUrl = baseUrl,
            page = 1,
            query = "",
            sort = "newest",
            kind = "movie",
            status = "airing",
        ).toHttpUrl()
        assertEquals("movie", urlKind.queryParameter("kind"))
        assertEquals("airing", urlKind.queryParameter("status"))
        assertEquals("newest", urlKind.queryParameter("sort"))
        assertNull(urlKind.queryParameter("search"))
    }

    @Test
    fun testAnimeDetailsEndpoints() {
        assertEquals("/api/v1/animes/slug/black-clover", Animy.buildAnimeDetailsEndpoint("/anime/black-clover/"))
        assertEquals("/api/v1/animes/slug/black-clover", Animy.buildAnimeDetailsEndpoint("anime/black-clover"))
        assertEquals("/api/v1/animes/493", Animy.buildAnimeDetailsEndpoint("/anime/493/"))
        assertEquals("/api/v1/animes/493", Animy.buildAnimeDetailsEndpoint("493"))
    }

    @Test
    fun testEpisodeStreamEndpoints() {
        assertEquals("/animes/493/episodes/1/1", Animy.buildEpisodeStreamEndpoint(493L, 1, 1f))
        assertEquals("/animes/493/episodes/2/12", Animy.buildEpisodeStreamEndpoint(493L, 2, 12f))
        assertEquals("/animes/650/episodes/1/1", Animy.buildEpisodeStreamEndpoint(650L, 1, 1f))
    }

    @Test
    fun testEpisodeNameFormatting() {
        assertEquals("1º Episódio - Asta E Yuno", Animy.formatEpisodeName(1, 1f, "1º Episódio", "Asta E Yuno"))
        assertEquals("T2 Episódio 5 - O Retorno", Animy.formatEpisodeName(2, 5f, null, "O Retorno"))
        assertEquals("Filme", Animy.formatEpisodeName(1, 1f, "Filme", "Filme"))
        assertEquals("Episódio 10", Animy.formatEpisodeName(1, 10f, null, null))
    }

    @Test
    fun testCatalogResponseParsing() {
        val jsonStr = """
            {
              "items": [
                {
                  "id": 585,
                  "t": "Koori no Jouheki",
                  "slug": "koori-no-jouheki",
                  "cover": "https://feralstream.animy.net/files/cover.jpg",
                  "score": 8.1,
                  "views": 16,
                  "eps": 12,
                  "kind": "tv",
                  "audio": "legendado",
                  "g": ["Drama", "Escolar", "Romance"],
                  "y": 2026,
                  "status": "airing",
                  "desc": "Uma história escolar emocionante."
                }
              ],
              "total": 644
            }
        """.trimIndent()

        val resp = json.decodeFromString<AnimesResponseDto>(jsonStr)
        assertEquals(1, resp.items.size)
        assertEquals(644, resp.total)

        val item = resp.items.first()
        assertEquals(585L, item.id)
        assertEquals("Koori no Jouheki", item.t)
        assertEquals("koori-no-jouheki", item.slug)
        assertEquals("https://feralstream.animy.net/files/cover.jpg", item.cover)
        assertEquals("airing", item.status)
        assertEquals(listOf("Drama", "Escolar", "Romance"), item.g)

        val desc = Animy.formatDescription(
            desc = item.desc,
            nativeTitle = null,
            score = item.score,
            year = item.y,
            kind = item.kind,
            audio = item.audio,
            age = item.age,
        )
        assertTrue(desc.contains("Uma história escolar emocionante."))
        assertTrue(desc.contains("Nota: 8.1 / 10"))
        assertTrue(desc.contains("Ano: 2026"))
        assertTrue(desc.contains("Tipo: TV"))
        assertTrue(desc.contains("Áudio: Legendado"))
    }

    @Test
    fun testAnimeDetailsAndEpisodeListDto() {
        val jsonDetails = """
            {
              "id": 493,
              "slug": "black-clover",
              "t": "Black Clover",
              "nativeTitle": "ブラッククローバー",
              "y": 2017,
              "g": ["Aventura", "Ação", "Fantasia"],
              "age": 14,
              "cover": "https://feralstream.animy.net/files/cover.jpg",
              "desc": "Num mundo onde magia é tudo...",
              "status": "completed",
              "kind": "tv",
              "score": 8.5,
              "audio": "legendado",
              "episodes": [
                {
                  "id": 32879,
                  "season": 1,
                  "number": 1,
                  "numberLabel": "1º Episódio",
                  "title": "Asta E Yuno",
                  "date": "2026-10-14",
                  "thumb": "https://feralstream.animy.net/files/ep1.jpg"
                },
                {
                  "id": 32880,
                  "season": 1,
                  "number": 2,
                  "numberLabel": "2º Episódio",
                  "title": "O Juramento dos Rapazes",
                  "date": "2026-10-15",
                  "thumb": "https://feralstream.animy.net/files/ep2.jpg"
                }
              ]
            }
        """.trimIndent()

        val details = json.decodeFromString<AnimeDetailsDto>(jsonDetails)
        assertEquals(493L, details.id)
        assertEquals("Black Clover", details.t)
        assertEquals("black-clover", details.slug)
        assertEquals("ブラッククローバー", details.nativeTitle)
        assertEquals(2017, details.y)
        assertEquals(14, details.age)
        assertEquals(listOf("Aventura", "Ação", "Fantasia"), details.g)
        assertEquals(2, details.episodes?.size)

        val ep1 = details.episodes!![0]
        assertEquals(32879L, ep1.id)
        assertEquals(1, ep1.season)
        assertEquals(1f, ep1.number)
        assertEquals("1º Episódio", ep1.numberLabel)
        assertEquals("Asta E Yuno", ep1.title)
        assertEquals("2026-10-14", ep1.date)
        assertEquals("https://feralstream.animy.net/files/ep1.jpg", ep1.thumb)

        val formattedEp1Name = Animy.formatEpisodeName(ep1.season, ep1.number, ep1.numberLabel, ep1.title)
        assertEquals("1º Episódio - Asta E Yuno", formattedEp1Name)
    }

    @Test
    fun testEpisodeStreamsParsingDto() {
        val jsonStreams = """
            {
              "id": 32879,
              "season": 1,
              "number": 1,
              "numberLabel": "1º Episódio",
              "title": "Asta E Yuno",
              "date": "2026-10-14",
              "streams": [
                {
                  "id": 17004,
                  "kind": "mp4",
                  "url": "https://feralstream.animy.net/files/video.mp4",
                  "audio": "legendado"
                },
                {
                  "id": 17005,
                  "kind": "hls",
                  "url": "https://feralstream.animy.net/files/master.m3u8",
                  "audio": "legendado"
                }
              ]
            }
        """.trimIndent()

        val episodeDetails = json.decodeFromString<EpisodeDetailsDto>(jsonStreams)
        assertEquals(32879L, episodeDetails.id)
        assertEquals(2, episodeDetails.streams.size)

        val mp4Stream = episodeDetails.streams[0]
        assertEquals("mp4", mp4Stream.kind)
        assertEquals("https://feralstream.animy.net/files/video.mp4", mp4Stream.url)
        assertEquals("legendado", mp4Stream.audio)

        val hlsStream = episodeDetails.streams[1]
        assertEquals("hls", hlsStream.kind)
        assertEquals("https://feralstream.animy.net/files/master.m3u8", hlsStream.url)
    }

    @Test
    fun testDateParsingHelper() {
        val time1 = Animy.parseDate("2026-10-14")
        assertTrue(time1 > 0L)

        val time2 = Animy.parseDate("2026-10-27T15:30:00Z")
        assertTrue(time2 > 0L)

        val timeInvalid = Animy.parseDate("invalid-date")
        assertEquals(0L, timeInvalid)
    }
}
