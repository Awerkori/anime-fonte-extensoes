package eu.kanade.tachiyomi.animeextension.pt.animefire.extractors

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashManifestTest {
    private val xml = """
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period>
          <AdaptationSet contentType="video">
            <SegmentTemplate media="/video/${'$'}RepresentationID${'$'}/${'$'}Number${'$'}.jpg" initialization="init/${'$'}RepresentationID${'$'}.jpg"/>
            <Representation id="low" mimeType="video/mp4" height="480"/>
            <Representation id="high" mimeType="video/mp4" height="1080"/>
          </AdaptationSet>
          <AdaptationSet contentType="audio">
            <SegmentTemplate media="audio/${'$'}Number${'$'}.jpg"/>
            <Representation id="audio" mimeType="audio/mp4"/>
          </AdaptationSet>
        </Period></MPD>
    """.trimIndent()

    @Test
    fun eachQualityKeepsAudioAndAbsoluteSegmentTemplates() {
        val variants = DashManifest.variants(xml, "https://media.example/i/session/m.jpg")
        assertEquals(listOf(1080, 480), variants.map { it.first })
        variants.forEachIndexed { index, (_, manifest) ->
            val doc = Jsoup.parse(manifest, "", Parser.xmlParser())
            assertEquals(listOf(if (index == 0) "high" else "low", "audio"), doc.select("Representation").eachAttr("id"))
            val templates = doc.select("SegmentTemplate")
            assertEquals("https://media.example/video/${'$'}RepresentationID${'$'}/${'$'}Number${'$'}.jpg", templates[0].attr("media"))
            assertEquals("https://media.example/i/session/init/${'$'}RepresentationID${'$'}.jpg", templates[0].attr("initialization"))
            assertEquals("https://media.example/i/session/audio/${'$'}Number${'$'}.jpg", templates[1].attr("media"))
            assertTrue(doc.select("ContentProtection").isEmpty())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnErrorPageInsteadOfReturningBrokenVideos() {
        DashManifest.variants("<html>Unavailable</html>", "https://media.example/m.jpg")
    }
}
