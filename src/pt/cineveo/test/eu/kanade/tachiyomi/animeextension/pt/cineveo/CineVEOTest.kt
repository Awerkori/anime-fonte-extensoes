package eu.kanade.tachiyomi.animeextension.pt.cineveo

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for project-nox-requests#138: cineveo.cfd replaced the old HTML/player flow with a JSON API.
 * The fixtures below are real responses (captured 2026-11-21), trimmed only where noted.
 */
class CineVEOTest {

    private val json = Json { ignoreUnknownKeys = true }

    // /api?type=series&page=1, first two items
    private val catalogJson = """{"type":"series","total":32,"page":1,"totalPages":1,"limit":32,"offset":0,"items":[{"id":332839,"internal_id":86139,"tmdb_id":332839,"slug":"a-lei-dos-monstros-18o86l9","type":"series","name":"A Lei dos Monstros","original_name":"A Lei dos Monstros","poster":"https://image.tmdb.org/t/p/w185/xH8G1Knsus1dTqbSmdIOqHJ0nSu.jpg","poster_large":"https://image.tmdb.org/t/p/w185/xH8G1Knsus1dTqbSmdIOqHJ0nSu.jpg","backdrop":"https://image.tmdb.org/t/p/original/iMvGSFcxzvwLWyHDOLqwDX9JQ27.jpg","plot":"Quando o caso de um assassino de meninos ressurge depois de 25 anos como um problema jurídico e social não solucionado, duas antigas amigas são forçadas a confrontar as escolhas que levaram ao fim da amizade e moldaram suas vidas.","genre":"Crime, Drama","year":"2026","rating":0,"audio":"DUB","added":"2026-11-19 02:48:59","category_id":"Crime","duration":0,"extension":"mp4","hidden":false},{"id":213375,"internal_id":74593,"tmdb_id":213375,"slug":"visionquest-dublado","type":"series","name":"VisionQuest","original_name":"VisionQuest","poster":"https://image.tmdb.org/t/p/w185/WGyAyBPncfuu8MZhLY9RtfZPM0.jpg","poster_large":"https://image.tmdb.org/t/p/w185/WGyAyBPncfuu8MZhLY9RtfZPM0.jpg","backdrop":"https://image.tmdb.org/t/p/w780/v50p9hearMiu6BlYfp4O7FACmXl.jpg","plot":"Um Visão repaginado tem se escondido daqueles que buscam usá-lo como arma. Enquanto permanece na clandestinidade, ele consulta as personalidades de IA embutidas em sua programação, incluindo o infame Ultron, para encontrar seu verdadeiro propósito. Essa vida tranquila de autorreflexão chega ao fim quando uma recompensa por sua cabeça o força a fugir ao lado de Thomas Shepard, um garoto misterioso que pode ser a reencarnação de seu filho.","genre":"Sci-Fi & Fantasy, Drama","year":"2026","rating":9.4,"audio":"DUB","added":"2026-11-16 14:18:30","category_id":"Sci-Fi & Fantasy","duration":0,"extension":"mp4","hidden":false}]}"""

    // /api?action=details&type=series&id=332839
    private val seriesJson = """{"type":"series","item":{"id":332839,"internal_id":86139,"tmdb_id":332839,"slug":"a-lei-dos-monstros-18o86l9","type":"series","name":"A Lei dos Monstros","original_name":"A Lei dos Monstros","poster":"https://image.tmdb.org/t/p/w185/xH8G1Knsus1dTqbSmdIOqHJ0nSu.jpg","poster_large":"https://image.tmdb.org/t/p/w185/xH8G1Knsus1dTqbSmdIOqHJ0nSu.jpg","backdrop":"https://image.tmdb.org/t/p/original/iMvGSFcxzvwLWyHDOLqwDX9JQ27.jpg","plot":"Quando o caso de um assassino de meninos ressurge depois de 25 anos como um problema jurídico e social não solucionado, duas antigas amigas são forçadas a confrontar as escolhas que levaram ao fim da amizade e moldaram suas vidas.","genre":"Crime, Drama","year":"2026","rating":0,"audio":"DUB","added":"2026-11-19 02:48:59","category_id":"Crime","duration":0,"extension":"mp4","hidden":false},"episodes":[{"id":1514127,"tmdb_id":332839,"season":1,"episode":1,"title":"Episódio 1","plot":"","poster":"/api?action=episodeImage&episode_id=1514127","extension":"mp4","available":true,"quality":"","audio":"dub"},{"id":1514128,"tmdb_id":332839,"season":1,"episode":2,"title":"Episódio 2","plot":"","poster":"/api?action=episodeImage&episode_id=1514128","extension":"mp4","available":true,"quality":"","audio":"dub"},{"id":1514129,"tmdb_id":332839,"season":1,"episode":3,"title":"Episódio 3","plot":"","poster":"/api?action=episodeImage&episode_id=1514129","extension":"mp4","available":true,"quality":"","audio":"dub"},{"id":1514130,"tmdb_id":332839,"season":1,"episode":4,"title":"Episódio 4","plot":"","poster":"/api?action=episodeImage&episode_id=1514130","extension":"mp4","available":true,"quality":"","audio":"dub"}],"seasons":[{"season":1,"name":"Temporada 1"}]}"""

    // /api?action=details&type=movie&id=1804941 (one server per language, token redacted)
    private val movieJson = """{"type":"movie","item":{"id":1804941,"internal_id":81570,"tmdb_id":1804941,"slug":"mike-tyson-return-of-the-mike","type":"movie","name":"Mike Tyson: Return of the Mike","original_name":"Mike Tyson: Return of the Mike","poster":"http://image.tmdb.org/t/p/w500/uaeL5BLgmGhIZXfViBsLSnvYB4i.jpg","poster_large":"http://image.tmdb.org/t/p/w500/uaeL5BLgmGhIZXfViBsLSnvYB4i.jpg","backdrop":"http://image.tmdb.org/t/p/w500/uaeL5BLgmGhIZXfViBsLSnvYB4i.jpg","plot":"O ex-campeão dos pesos pesados ​​retorna ao palco para um show solo cru, transformando suas histórias de vida mais selvagens em comédia de ouro no Seminole Hard Rock Hotel and Casino.","genre":"","year":"2026","rating":0,"audio":"DUB","added":"2026-11-05 00:04:46","category_id":"","duration":0,"extension":"mp4","hidden":false},"stream":"api?action=stream&type=movie&id=1804941","servers":{"dub":[],"leg":[{"id":"cdd5179d2d6aa49c46cc4cca70a5d80e","name":"Player Principal (HD)","quality":"HD","url":"https://cineveo.cfd/api/vod-resolve?token=TOKEN","type":"mp4","audio":"leg","download":"https://cineveo.cfd/api/vod-resolve?token=TOKEN"}]}}"""

    private fun episode(id: Long, season: Int, number: Int) =
        ApiEpisode(id = id, season = season, episode = number, title = "Episódio $number", extension = "mp4", audio = "dub")

    private fun item(type: String, id: Long) = ApiItem(id = id, type = type, name = "Obra", extension = "mp4")

    @Test
    fun decodesTheRealCatalogWithNumericIds() {
        val catalog = json.decodeFromString<ApiCatalog>(catalogJson)

        assertEquals(2, catalog.items.size)
        assertEquals("A Lei dos Monstros", catalog.items[0].name)
        assertEquals(332839L, catalog.items[0].id)
        assertEquals("series", catalog.items[0].type)
    }

    @Test
    fun buildsDetailsAndStreamPathsFromTypeAndId() {
        assertEquals("/api?action=details&type=series&id=332839", detailsPath("series", 332839))
        assertEquals("/api?action=stream&type=series&id=1514127&ext=mp4", streamPath("series", 1514127, "mp4"))
        assertEquals("/api?action=stream&type=movie&id=1804941&ext=mp4", streamPath("movie", 1804941, null))
    }

    @Test
    fun listsEverySeriesEpisodeNewestFirstWithStreamUrls() {
        val entries = episodeEntries(json.decodeFromString<ApiDetails>(seriesJson))

        assertEquals(4, entries.size)
        assertEquals("T1 E4 - Episódio 4", entries.first().name)
        assertEquals("T1 E1 - Episódio 1", entries.last().name)
        assertEquals("/api?action=stream&type=series&id=1514127&ext=mp4", entries.last().url)
        assertEquals(listOf(4f, 3f, 2f, 1f), entries.map { it.number })
    }

    @Test
    fun numbersEpisodesAcrossSeasonsEvenWhenTheApiReturnsThemOutOfOrder() {
        val details = ApiDetails(
            item = item("series", 1),
            episodes = listOf(episode(30, 2, 1), episode(20, 1, 2), episode(10, 1, 1)),
        )

        val entries = episodeEntries(details)

        assertEquals(listOf("T2 E1 - Episódio 1", "T1 E2 - Episódio 2", "T1 E1 - Episódio 1"), entries.map { it.name })
        assertEquals(listOf(3f, 2f, 1f), entries.map { it.number })
    }

    @Test
    fun aMovieIsASingleEpisodeThatStreamsTheMovieId() {
        val entries = episodeEntries(json.decodeFromString<ApiDetails>(movieJson))

        assertEquals(1, entries.size)
        assertEquals("Filme", entries[0].name)
        assertEquals("/api?action=stream&type=movie&id=1804941&ext=mp4", entries[0].url)
    }

    @Test
    fun aFullPageMeansThereIsAnotherOne() {
        assertTrue(hasNextPage(PAGE_SIZE))
        assertFalse(hasNextPage(PAGE_SIZE - 1))
        assertFalse(hasNextPage(0))
    }

    @Test
    fun onlyAVideoResponseCountsAsPlayable() {
        assertTrue(isPlayable(206, "video/mp4"))
        assertTrue(isPlayable(200, "video/mp4"))
        assertFalse(isPlayable(200, "text/html; charset=UTF-8"))
        assertFalse(isPlayable(403, "text/plain"))
        assertFalse(isPlayable(206, null))
    }

    @Test
    fun labelsTheAudioTheSiteReportsAndIgnoresAnythingElse() {
        assertEquals("Dublado", audioLabel("dub"))
        assertEquals("Legendado", audioLabel("LEG"))
        assertEquals(null, audioLabel("xyz"))
        assertEquals(null, audioLabel(null))
    }
}
