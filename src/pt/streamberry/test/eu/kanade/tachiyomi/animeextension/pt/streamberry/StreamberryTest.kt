package eu.kanade.tachiyomi.animeextension.pt.streamberry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamberryTest {

    @Test
    fun testParseSectionPagePopularAndLatest() {
        val mockJson = """
        {
            "items": [
                {
                    "id": 1,
                    "title": "Deadpool & Wolverine",
                    "slug": "deadpool-wolverine",
                    "posterUrl": "/8cdWjvZQUExUUTzyp4t6EDMubfO.jpg",
                    "mediaType": "movie",
                    "ratingTmdb": 7.8
                },
                {
                    "id": 2,
                    "title": "ONE PIECE: A Série",
                    "slug": "one-piece-a-serie",
                    "posterUrl": "https://image.tmdb.org/t/p/w500/r54N6B4c0Z.jpg",
                    "mediaType": "series",
                    "ratingTmdb": 8.5
                }
            ],
            "pagination": {
                "page": 1,
                "limit": 24,
                "total": 3600,
                "totalPages": 150
            }
        }
        """.trimIndent()

        val data = StreamberryHelper.json.decodeFromString<StreamberryHelper.SectionResponseDto>(mockJson)
        assertEquals(2, data.items.size)
        assertEquals(1, data.pagination?.page)
        assertEquals(150, data.pagination?.totalPages)

        val movie = data.items[0]
        assertEquals("Deadpool & Wolverine", movie.title)
        assertEquals("deadpool-wolverine", movie.slug)
        assertEquals("/8cdWjvZQUExUUTzyp4t6EDMubfO.jpg", movie.posterUrl)
        assertEquals("movie", movie.mediaType)

        val series = data.items[1]
        assertEquals("ONE PIECE: A Série", series.title)
        assertEquals("one-piece-a-serie", series.slug)
        assertEquals("series", series.mediaType)
    }

    @Test
    fun testFixImageUrl() {
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", StreamberryHelper.fixImageUrl("/poster.jpg"))
        assertEquals("https://custom.cdn/poster.jpg", StreamberryHelper.fixImageUrl("https://custom.cdn/poster.jpg"))
        assertEquals(null, StreamberryHelper.fixImageUrl(""))
        assertEquals(null, StreamberryHelper.fixImageUrl(null))
    }

    @Test
    fun testParseMovieDetailDto() {
        val mockMovieJson = """
        {
            "id": 100,
            "title": "Deadpool & Wolverine",
            "slug": "deadpool-wolverine",
            "synopsis": "Um homem irresponsável precisa salvar o multiverso.",
            "posterUrl": "/8cdWjvZQUExUUTzyp4t6EDMubfO.jpg",
            "releaseDate": "2024-07-24",
            "ratingTmdb": 7.8,
            "quality": "1080p",
            "director": "Shawn Levy",
            "audioTags": ["DUBLADO", "LEGENDADO"],
            "genres": [
                {"id": 28, "name": "Ação", "slug": "acao"},
                {"id": 35, "name": "Comédia", "slug": "comedia"}
            ]
        }
        """.trimIndent()

        val movie = StreamberryHelper.json.decodeFromString<StreamberryHelper.MovieDetailDto>(mockMovieJson)
        assertEquals("Deadpool & Wolverine", movie.title)
        assertEquals("Shawn Levy", movie.director)
        assertEquals(7.8, movie.ratingTmdb ?: 0.0, 0.001)
        assertEquals("1080p", movie.quality)
        assertEquals(2, movie.audioTags?.size)
        assertEquals(2, movie.genres?.size)
        assertEquals("Ação", movie.genres?.get(0)?.name)
        assertEquals("Comédia", movie.genres?.get(1)?.name)
    }

    @Test
    fun testParseSeriesDetailDto() {
        val mockSeriesJson = """
        {
            "id": 200,
            "title": "ONE PIECE: A Série",
            "slug": "one-piece-a-serie",
            "synopsis": "Monkey D. Luffy e sua tripulação partem em busca do maior tesouro.",
            "posterUrl": "/x2LSRK2CmqSZqhvtIP50BokvvZ0.jpg",
            "showStatus": "ended",
            "ratingTmdb": 8.6,
            "creator": "Matt Owens",
            "audioTags": ["DUBLADO", "LEGENDADO"],
            "genres": [
                {"id": 18, "name": "Ação", "slug": "acao"},
                {"id": 9648, "name": "Aventura", "slug": "aventura"}
            ],
            "seasons": [
                {
                    "id": 10,
                    "seasonNumber": 1,
                    "episodes": [
                        {
                            "id": 101,
                            "episodeNumber": 1,
                            "title": "O amanhecer de uma aventura",
                            "airDate": "2023-08-31"
                        },
                        {
                            "id": 102,
                            "episodeNumber": 2,
                            "title": "O homem do chapéu de palha",
                            "airDate": "2023-08-31"
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val series = StreamberryHelper.json.decodeFromString<StreamberryHelper.SeriesDetailDto>(mockSeriesJson)
        assertEquals("ONE PIECE: A Série", series.title)
        assertEquals("Matt Owens", series.creator)
        assertEquals("ended", series.showStatus)
        assertEquals(1, series.seasons?.size)

        val season = series.seasons?.get(0)
        assertEquals(1, season?.seasonNumber)
        assertEquals(2, season?.episodes?.size)
        assertEquals("O amanhecer de uma aventura", season?.episodes?.get(0)?.title)
        assertEquals(1, season?.episodes?.get(0)?.episodeNumber)
        assertEquals("O homem do chapéu de palha", season?.episodes?.get(1)?.title)
        assertEquals(2, season?.episodes?.get(1)?.episodeNumber)
    }

    @Test
    fun testParseDate() {
        val timestamp = StreamberryHelper.parseDate("2023-08-31")
        assertTrue(timestamp > 0L)

        val timestampWithTime = StreamberryHelper.parseDate("2023-08-31 15:30:00")
        assertTrue(timestampWithTime > 0L)

        assertEquals(0L, StreamberryHelper.parseDate(null))
        assertEquals(0L, StreamberryHelper.parseDate(""))
        assertEquals(0L, StreamberryHelper.parseDate("invalid-date"))
    }

    @Test
    fun testServerPriority() {
        assertTrue(StreamberryHelper.serverPriority("BYSE DUB") < StreamberryHelper.serverPriority("VIDARA DUB"))
        assertTrue(StreamberryHelper.serverPriority("VIDARA DUB") < StreamberryHelper.serverPriority("EU PLAYER"))
        assertTrue(StreamberryHelper.serverPriority("EU PLAYER") < StreamberryHelper.serverPriority("LULU DUB"))
        assertTrue(StreamberryHelper.serverPriority("LULU DUB") < StreamberryHelper.serverPriority("LOADVID DUB"))
        assertTrue(StreamberryHelper.serverPriority("LOADVID DUB") < StreamberryHelper.serverPriority("SERVIDOR DUB"))
    }

    @Test
    fun testSolvePowAlgorithm() {
        val nonce = "322ba29bacf9da87987dde80cc3a960c"
        val difficulty = 16
        val solution = StreamberryHelper.solvePow(nonce, difficulty)
        assertNotNull(solution)
        assertEquals("20242", solution)
    }
}
