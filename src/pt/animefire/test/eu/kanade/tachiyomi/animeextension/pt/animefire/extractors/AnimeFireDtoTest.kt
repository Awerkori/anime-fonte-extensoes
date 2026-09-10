package eu.kanade.tachiyomi.animeextension.pt.animefire.extractors

import eu.kanade.tachiyomi.animeextension.pt.animefire.dto.AFResponse
import eu.kanade.tachiyomi.animeextension.pt.animefire.dto.AnimeDetails
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnimeFireDtoTest {
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
}
