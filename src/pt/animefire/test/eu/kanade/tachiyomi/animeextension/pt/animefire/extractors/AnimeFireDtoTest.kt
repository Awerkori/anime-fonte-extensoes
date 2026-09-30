package eu.kanade.tachiyomi.animeextension.pt.animefire.extractors

import eu.kanade.tachiyomi.animeextension.pt.animefire.dto.AFResponse
import eu.kanade.tachiyomi.animeextension.pt.animefire.dto.AnimeDetails
import eu.kanade.tachiyomi.animeextension.pt.animefire.dto.Card
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnimeFireDtoTest {
    @Test
    fun cardUsesCurrentTitlesSchema() {
        val response = Json.decodeFromString<AFResponse<List<Card>>>(
            """{"data":[{"id":"eU7t5IvcNKU","titles":{"BR":"Naruto"},"poster_src":"https://image.tmdb.org/poster.jpg"}]}""",
        )

        assertEquals("Naruto", response.data.single().titles["BR"])
    }

    @Test
    fun movieAcceptsFormattedRuntimeAndUntitledUnseasonedEpisode() {
        val response = Json.decodeFromString<AFResponse<AnimeDetails>>(
            """{"data":{"hero":{"id":"PiR9Tl0ey6L","titles":{"BR":"One Piece: Z","JP":"ONE PIECE FILM: Z"},"runtime":"1h 48min"},"seasons":[],"episodes":[{"id":"dKY9Quwc_Xe","title":null,"season":null,"number":1,"audio":"Dublado & Legendado"}]}}""",
        )
        assertEquals("1h 48min", response.data.hero.runtime)
        assertEquals(1, response.data.episodes.size)
        assertNull(response.data.episodes.single().title)
        assertNull(response.data.episodes.single().season)
    }

    @Test
    fun newEpisodesWithEpisodeFrameResolvesToCanonicalAnimePoster() {
        val homeJson = """{
            "data": {
                "carousels": [
                    {
                        "key": "new-episodes",
                        "items": [
                            {
                                "id": "ep_1",
                                "titles": {"BR": "Clevatess"},
                                "poster_src": "https://image.tmdb.org/t/p/original/episode_frame_1.jpg",
                                "still_src": "https://image.tmdb.org/t/p/original/episode_frame_1.jpg"
                            }
                        ]
                    },
                    {
                        "key": "most-liked",
                        "items": [
                            {
                                "id": "anime_clevatess",
                                "titles": {"BR": "Clevatess"},
                                "poster_src": "https://image.tmdb.org/t/p/original/canonical_clevatess.jpg"
                            }
                        ]
                    }
                ]
            }
        }"""
        val episodeJson = """{
            "data": {
                "id": "ep_1",
                "anime": {
                    "id": "anime_clevatess",
                    "titles": {"BR": "Clevatess"},
                    "poster_src": "https://image.tmdb.org/t/p/original/canonical_clevatess.jpg"
                },
                "streams": []
            }
        }"""

        val home = Json { ignoreUnknownKeys = true }.decodeFromString<AFResponse<eu.kanade.tachiyomi.animeextension.pt.animefire.dto.Home>>(homeJson).data
        val epDetails = Json { ignoreUnknownKeys = true }.decodeFromString<AFResponse<eu.kanade.tachiyomi.animeextension.pt.animefire.dto.EpisodeDetails>>(episodeJson).data

        val canonicalCovers = mutableMapOf<String, String>()
        home.carousels.filterNot { it.key == "new-episodes" }.forEach { c ->
            c.items.forEach { card -> card.poster?.let { canonicalCovers[card.id] = it } }
        }

        val episodeCard = home.carousels.first { it.key == "new-episodes" }.items.first()
        val animeCard = epDetails.anime

        // Verify that the episode item holds the frame
        assertEquals("https://image.tmdb.org/t/p/original/episode_frame_1.jpg", episodeCard.poster)

        // Verify that the resolved poster chooses the canonical poster, not the frame
        val resolvedPoster = canonicalCovers[animeCard.id] ?: animeCard.poster
        assertEquals("https://image.tmdb.org/t/p/original/canonical_clevatess.jpg", resolvedPoster)
        org.junit.Assert.assertNotEquals(episodeCard.poster, resolvedPoster)
    }

    @Test
    fun thirtySixEpisodesDeduplicateCorrectlyWithoutDuplicates() {
        val episodesList = (1..36).map { i ->
            val animeIdx = ((i - 1) % 27) + 1
            val isDuplicate = i > 27
            val posterUrl = if (isDuplicate) "https://image.tmdb.org/t/p/original/frame_$i.jpg" else "https://image.tmdb.org/t/p/original/poster_$animeIdx.jpg"
            Card(
                id = "ep_$i",
                titles = mapOf("BR" to "Anime $animeIdx"),
                poster = posterUrl,
            )
        }

        assertEquals(36, episodesList.size)

        // Deduplication by titles
        val distinctEpisodes = episodesList.distinctBy { it.titles["BR"] ?: it.id }
        assertEquals(27, distinctEpisodes.size)

        // Verify no duplicate animes exist
        val titlesSet = distinctEpisodes.map { it.titles["BR"] }.toSet()
        assertEquals(27, titlesSet.size)
    }
}
