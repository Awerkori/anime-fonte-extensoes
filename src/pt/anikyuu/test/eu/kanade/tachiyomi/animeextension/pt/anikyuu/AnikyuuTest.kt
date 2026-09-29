package eu.kanade.tachiyomi.animeextension.pt.anikyuu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnikyuuTest {

    @Test
    fun testParseCatalogCsv() {
        val sampleCsv = """
nome,link,capa,gênero,tema,gêneros_explícitos,demografia,nome_alternativo
Maou Gakuin no Futekigousha,https://sites.google.com/view/jsy-anime/anime/Maou-Gakuin-no-Futekigousha/TV-1,https://myanimelist.net/cover.jpg,"Ação, Fantasia",Reencarnação,,Shounen,The Misfit of Demon King Academy
Sword Art Online,https://sites.google.com/view/jsy-anime/anime/Sword-Art-Online/TV-1,https://myanimelist.net/sao.jpg,"Ação, Romance",Isekai,,Shounen,SAO
""".trimIndent()

        val entries = AnikyuuHelper.parseCatalogCsv(sampleCsv)
        assertEquals(2, entries.size)

        val first = entries[0]
        assertEquals("Maou Gakuin no Futekigousha", first.name)
        assertEquals("https://sites.google.com/view/jsy-anime/anime/Maou-Gakuin-no-Futekigousha/TV-1", first.link)
        assertEquals("https://myanimelist.net/cover.jpg", first.cover)
        assertEquals("Ação, Fantasia", first.genres)
        assertEquals("Reencarnação", first.themes)
        assertEquals("", first.explicitGenres)
        assertEquals("Shounen", first.demographics)
        assertEquals("The Misfit of Demon King Academy", first.altName)

        val second = entries[1]
        assertEquals("Sword Art Online", second.name)
        assertEquals("SAO", second.altName)
    }

    @Test
    fun testParseLancamentosGviz() {
        val sampleGviz = """
/*O_o*/
google.visualization.Query.setResponse({
  "version": "0.6",
  "status": "ok",
  "sig": "12345",
  "table": {
    "cols": [
      {"id": "A", "label": "Foto", "type": "string"},
      {"id": "B", "label": "Nome", "type": "string"},
      {"id": "C", "label": "Episódio", "type": "string"},
      {"id": "D", "label": "Idioma", "type": "string"},
      {"id": "E", "label": "Link", "type": "string"},
      {"id": "F", "label": "Exibir", "type": "string"},
      {"id": "G", "label": "Qualidade", "type": "string"}
    ],
    "rows": [
      {
        "c": [
          {"v": "https://img.com/norm.jpg"},
          {"v": "Anime Normal"},
          {"v": "Episódio 01"},
          {"v": "Legendado"},
          {"v": "https://j-s-an.github.io/anime/Anime-Normal/tv-1/legendado/episodio-1.html"},
          {"v": "sim"},
          {"v": "HD"}
        ]
      },
      {
        "c": [
          {"v": "https://img.com/fixo.jpg"},
          {"v": "Anime Destaque Fixo"},
          {"v": "Episódio 12"},
          {"v": "Dublado"},
          {"v": "https://j-s-an.github.io/anime/Anime-Fixo/tv-1/dublado/episodio-12.html"},
          {"v": "sim"},
          {"v": "FIXO HD"}
        ]
      }
    ]
  }
});
""".trimIndent()

        val entries = AnikyuuHelper.parseLancamentos(sampleGviz)
        assertEquals(2, entries.size)

        // Fixos should come first
        val first = entries[0]
        assertEquals("Anime Destaque Fixo", first.title)
        assertTrue(first.isFixo)
        assertEquals("Dublado", first.audio)

        val second = entries[1]
        assertEquals("Anime Normal", second.title)
        assertFalse(second.isFixo)
        assertEquals("Legendado", second.audio)
    }

    @Test
    fun testExtractSlugAndSeason() {
        val (slug1, season1) = AnikyuuHelper.extractSlugAndSeason("https://j-s-an.github.io/anime/Isekai-Nonbiri/tv-2/legendado/menu.html")
        assertEquals("Isekai-Nonbiri", slug1)
        assertEquals("tv-2", season1)

        val (slug2, season2) = AnikyuuHelper.extractSlugAndSeason("https://sites.google.com/view/jsy-anime/anime/Maou-Gakuin-no-Futekigousha/TV-1")
        assertEquals("Maou-Gakuin-no-Futekigousha", slug2)
        assertEquals("TV-1", season2)

        val (slug3, season3) = AnikyuuHelper.extractSlugAndSeason("/anime/Sword-Art-Online/tv-1")
        assertEquals("Sword-Art-Online", slug3)
        assertEquals("tv-1", season3)
    }

    @Test
    fun testGetSlugVariantsWithJapaneseParticles() {
        val variants = AnikyuuHelper.getSlugVariants("maou-gakuin-no-futekigousha")
        assertTrue("Should preserve particle 'no' in title case variant", variants.contains("Maou-Gakuin-no-Futekigousha"))
        assertTrue("Should contain lowercase", variants.contains("maou-gakuin-no-futekigousha"))
        assertTrue("Should contain capitalized", variants.contains("Maou-Gakuin-No-Futekigousha"))
    }

    @Test
    fun testBuildMenuCandidates() {
        val candidates = AnikyuuHelper.buildMenuCandidates(
            slug = "Isekai-Nonbiri",
            season = "tv-2",
            audio = "legendado",
            originalUrl = "https://j-s-an.github.io/anime/Isekai-Nonbiri/tv-2/legendado/menu.html",
        )
        assertFalse(candidates.isEmpty())
        assertTrue("Candidate list should include original URL", candidates.contains("https://j-s-an.github.io/anime/Isekai-Nonbiri/tv-2/legendado/menu.html"))
        assertTrue("Candidate list should include tv-2 lowercase", candidates.any { it.contains("/tv-2/legendado/menu.html") })
    }

    @Test
    fun testExtractEpisodeNumber() {
        assertEquals(12f, AnikyuuHelper.extractEpisodeNumber("Episódio 12 Final", "episodio-12.html"))
        assertEquals(1f, AnikyuuHelper.extractEpisodeNumber("Episódio 01", "episodio-1.html"))
        assertEquals(5.5f, AnikyuuHelper.extractEpisodeNumber("Episódio 5.5 Recapitulação", "episodio-5.5.html"))
        assertEquals(24f, AnikyuuHelper.extractEpisodeNumber("Ep. 24", "menu.html"))
    }

    @Test
    fun testNormalize() {
        assertEquals("maou gakuin", AnikyuuHelper.normalize("Maou Gakuin"))
        assertEquals("acao e fantasia", AnikyuuHelper.normalize("Ação e Fantasia"))
        assertEquals("isekai nonbiri nouka", AnikyuuHelper.normalize("  Isekai Nonbiri Nōuka  "))
    }

    @Test
    fun testCleanEpisodeTitle() {
        assertEquals("Episódio 12 Final", AnikyuuHelper.cleanEpisodeTitle("Episódio 12 Final", 12f))
        assertEquals("Episódio 12 Final", AnikyuuHelper.cleanEpisodeTitle("Episódio 12 Final (Dublado)", 12f))
        assertEquals("Episódio 12 Final", AnikyuuHelper.cleanEpisodeTitle("Episódio 12 Final (Legendado)", 12f))
        assertEquals("Episódio 11", AnikyuuHelper.cleanEpisodeTitle("Episódio 11 - Dublado", 11f))
        assertEquals("Episódio 01", AnikyuuHelper.cleanEpisodeTitle("Episódio 01", 1f))
        assertEquals("Episódio 1", AnikyuuHelper.cleanEpisodeTitle("1", 1f))
        assertEquals("Episódio 12", AnikyuuHelper.cleanEpisodeTitle("12", 12f))
        assertEquals("Episódio 1 - O Início", AnikyuuHelper.cleanEpisodeTitle("O Início", 1f))
        assertEquals("Episódio 5.5 Recapitulação", AnikyuuHelper.cleanEpisodeTitle("Episódio 5.5 Recapitulação", 5.5f))
    }

    @Test
    fun testFindInCatalog() {
        val catalog = listOf(
            CatalogEntry(
                name = "Hyouken no Majutsushi ga Sekai wo Suberu",
                link = "https://sites.google.com/view/jsy-anime/anime/hyouken-no-majutsushi/TV-1",
                cover = "https://cdn.myanimelist.net/images/anime/1049/131580l.jpg",
                genres = "Ação, Fantasia",
                themes = "Escolar",
                explicitGenres = "",
                demographics = "Shounen",
                altName = "The Iceblade Sorcerer Shall Rule the World",
            ),
            CatalogEntry(
                name = "Sword Art Online",
                link = "https://sites.google.com/view/jsy-anime/anime/SWORD-ART-ONLINE/TV-1",
                cover = "https://cdn.myanimelist.net/images/anime/11/39717l.jpg",
                genres = "Ação, Romance",
                themes = "Isekai",
                explicitGenres = "",
                demographics = "Shounen",
                altName = "SAO",
            ),
        )

        // 1. By slug and season
        val matchBySlug = AnikyuuHelper.findInCatalog(
            "https://j-s-an.github.io/anime/Hyouken-no-Majutsushi/tv-1/legendado/episodio-1.html",
            "Hyouken no Majutsushi ga Sekai wo Suberu",
            catalog,
        )
        assertNotNull(matchBySlug)
        assertEquals("Hyouken no Majutsushi ga Sekai wo Suberu", matchBySlug!!.name)
        assertEquals("https://cdn.myanimelist.net/images/anime/1049/131580l.jpg", matchBySlug.cover)

        // 2. By alt name
        val matchByAlt = AnikyuuHelper.findInCatalog(
            "https://unknown-link.com",
            "The Iceblade Sorcerer Shall Rule the World",
            catalog,
        )
        assertNotNull(matchByAlt)
        assertEquals("Hyouken no Majutsushi ga Sekai wo Suberu", matchByAlt!!.name)

        // 3. By cleaned title with Episódio suffix
        val matchClean = AnikyuuHelper.findInCatalog(
            "https://unknown-link.com",
            "Sword Art Online Episódio 1",
            catalog,
        )
        assertNotNull(matchClean)
        assertEquals("Sword Art Online", matchClean!!.name)
    }

    @Test
    fun testLatestUpdatesUsesCanonicalCatalogCover() {
        val sampleCatalog = (1..12).map { i ->
            val name = when (i) {
                1 -> "Hyouken no Majutsushi ga Sekai wo Suberu"
                2 -> "Sword Art Online"
                3 -> "Maou Gakuin no Futekigousha"
                else -> "Anime Title $i"
            }
            val slug = when (i) {
                1 -> "hyouken-no-majutsushi"
                2 -> "sword-art-online"
                3 -> "maou-gakuin-no-futekigousha"
                else -> "anime-title-$i"
            }
            CatalogEntry(
                name = name,
                link = "https://sites.google.com/view/jsy-anime/anime/$slug/TV-1",
                cover = "https://cdn.myanimelist.net/covers/anime_$i.jpg",
                genres = "Ação",
                themes = "",
                explicitGenres = "",
                demographics = "",
                altName = "Alt Title $i",
            )
        }

        val rowsJson = buildString {
            append("""{"c": [{"v": "https://img.com/episode_hyouken_dub.webp"}, {"v": "Hyouken no Majutsushi ga Sekai wo Suberu"}, {"v": "Episódio 12 Final"}, {"v": "Dublado"}, {"v": "https://j-s-an.github.io/anime/Hyouken-no-Majutsushi/tv-1/dublado/episodio-12.html"}, {"v": "sim"}, {"v": "HD"}]},""")
            append("""{"c": [{"v": "https://img.com/episode_hyouken_sub.webp"}, {"v": "Hyouken no Majutsushi ga Sekai wo Suberu"}, {"v": "Episódio 12 Final"}, {"v": "Legendado"}, {"v": "https://j-s-an.github.io/anime/Hyouken-no-Majutsushi/tv-1/legendado/episodio-12.html"}, {"v": "sim"}, {"v": "HD"}]},""")
            for (i in 2..12) {
                val slug = if (i == 2) "sword-art-online" else if (i == 3) "maou-gakuin-no-futekigousha" else "anime-title-$i"
                val name = if (i == 2) "Sword Art Online" else if (i == 3) "Maou Gakuin no Futekigousha" else "Anime Title $i"
                append("""{"c": [{"v": "https://img.com/ep_thumb_$i.webp"}, {"v": "$name"}, {"v": "Episódio 1"}, {"v": "Legendado"}, {"v": "https://j-s-an.github.io/anime/$slug/tv-1/legendado/episodio-1.html"}, {"v": "sim"}, {"v": "HD"}]}${if (i < 12) "," else "" }""")
            }
        }
        val gvizJson = """/*O_o*/
google.visualization.Query.setResponse({
  "version": "0.6",
  "status": "ok",
  "table": {
    "cols": [],
    "rows": [$rowsJson]
  }
});"""

        val entries = AnikyuuHelper.parseLancamentos(gvizJson)
        val cards = AnikyuuHelper.resolveLancamentosToCards(entries, sampleCatalog)

        // 1. Should deduplicate Hyouken: 12 unique animes total
        assertEquals(12, cards.size)

        // 2. Check Hyouken canonical cover and title
        val hyoukenCard = cards.first { it.title == "Hyouken no Majutsushi ga Sekai wo Suberu" }
        assertEquals("https://cdn.myanimelist.net/covers/anime_1.jpg", hyoukenCard.cover)
        assertFalse(hyoukenCard.cover.contains("episode_hyouken"))
        assertEquals("https://sites.google.com/view/jsy-anime/anime/hyouken-no-majutsushi/TV-1", hyoukenCard.url)

        // 3. Verify all 12 items have card.cover == catalogEntry.cover
        for (card in cards) {
            val matchingCatalog = sampleCatalog.first { it.name == card.title }
            assertEquals(matchingCatalog.cover, card.cover)
            assertEquals(matchingCatalog.link, card.url)
            assertFalse(
                "Cover must not be episode screenshot",
                card.cover.contains("ep_thumb") || card.cover.contains("episode_"),
            )
        }
    }
}
