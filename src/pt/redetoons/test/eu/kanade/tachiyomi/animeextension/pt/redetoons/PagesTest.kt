package eu.kanade.tachiyomi.animeextension.pt.redetoons

import keiyoushi.utils.parseAs
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression for project-nox-requests#128: redetoons.my moved to redetoons.li and the site was rebuilt
 * (Laravel + Inertia). The fixtures are real responses captured on 2026-11-21, trimmed to the fields used.
 */
class PagesTest {

    @Before
    fun setUp() = registerTestJson()

    // /br/browse/trending: one series and one movie
    private val browseHtml = """<!DOCTYPE html><html lang="pt-BR"><head><title>RedeToons</title></head><body><div id="app" data-page=""></div><script id="su-page-data" type="application/json">{"component":"Titles/Browse","url":"/br/x","version":"v","props":{"titles":[{"id":5920,"slug":"the-mentalist","name":"The Mentalist","type":"tv","plot":"Patrick Jane, a former celebrity psychic medium, uses his razor sharp skills of observation and expertise at \"reading\" people to solve serious crimes with the California Bureau of Investigation.","images":[{"type":"poster","filename":"/tmdb/p/suf671a169610e0eecda4ee3f1a619dcbf.webp","original_url_field":"/img/tmdb/t/p/w185/suf671a169610e0eecda4ee3f1a619dcbf.webp"},{"type":"cover","filename":"/tmdb/c/su127214c5dc8b5ffe50755aeb041e305f.webp","original_url_field":"/img/tmdb/t/p/w300/su127214c5dc8b5ffe50755aeb041e305f.webp"}],"genres":[{"id":80,"name":"Crime","type":"all"},{"id":18,"name":"Drama","type":"all"},{"id":9648,"name":"Mistério","type":"all"}]},{"id":155,"slug":"the-dark-knight","name":"The Dark Knight","type":"movie","plot":"Batman raises the stakes in his war on crime. With the help of Lt. Jim Gordon and District Attorney Harvey Dent, Batman sets out to dismantle the remaining criminal organizations that plague the streets. The partnership proves to be effective, but they soon find themselves prey to a reign of chaos unleashed by a rising criminal mastermind known to the terrified citizens of Gotham as the Joker.","images":[{"type":"poster","filename":"/tmdb/p/4lj1ikfsSmMZNyfdi8R8Tv5tsgb.jpg","original_url_field":"/img/tmdb/t/p/w185/4lj1ikfsSmMZNyfdi8R8Tv5tsgb.jpg"},{"type":"cover","filename":"/tmdb/c/eswUSP61ZaS2rPZNIeuZsrGxvSb.jpg","original_url_field":"/img/tmdb/t/p/w300/eswUSP61ZaS2rPZNIeuZsrGxvSb.jpg"}],"genres":[{"id":28,"name":"Ação","type":"all"},{"id":53,"name":"Suspense","type":"all"},{"id":80,"name":"Crime","type":"all"}]}],"label":"Em alta"}}</script></body></html>"""

    // /api/search?q=naruto, first two results
    private val searchJson = """{"results":[{"id":46260,"slug":"naruto","name":"Naruto","type":"tv","plot":"Momentos antes do nascimento de Naruto Uzumaki, um enorme demônio conhecido como o Kyuubi, a Raposa de Nove Caudas, atacou o vilarejo da folha oculta Konoha, causando destruição. Para pôr fim à devastação de Kyuubi, o líder da aldeia, o quarto Hokage, sacrificou sua vida e selou o monstruoso animal dentro do recém-nascido Naruto. Agora, Naruto é um ninja hiperativo e cabeça dura que ainda vivem em Konoha. Evitado pelos demais habitantes por causa da Kyuubi dentro dele, Naruto se esforça para encontrar seu lugar na aldeia, enquanto o seu ardente desejo de se tornar o Hokage de Konoha o leva a conhecer alguns grandes novos amigos, e também alguns inimigos mortais.","images":[{"type":"poster","filename":"/tmdb/p/ijxaQzjjX643N1gHiERaFAlwvJ5.jpg","original_url_field":"/img/tmdb/t/p/w185/ijxaQzjjX643N1gHiERaFAlwvJ5.jpg"},{"type":"cover","filename":"/tmdb/c/iWc6rzyWeUgW61JZNgIxBLD3nsG.jpg","original_url_field":"/img/tmdb/t/p/w300/iWc6rzyWeUgW61JZNgIxBLD3nsG.jpg"}],"genres":[{"id":16,"name":"Animação","type":"all"},{"id":10759,"name":"Ação e aventura","type":"all"},{"id":10765,"name":"Ficção científica e fantasia","type":"all"}]},{"id":31910,"slug":"naruto-shipp-den","name":"Naruto Shippuden","type":"tv","plot":"Naruto Shippuden ocorre 2 anos e meio após Naruto ter deixado a vila para treinar com Jiraiya. Após seu retorno, Naruto descobre que seus amigos  shinobi's o superaram na classificação, e ele caiu para trás. No  entanto, com apenas 6 meses para resgatar Sasuke, Naruto tem de  enfrentar inimigos ainda mais perigosos. O plano da Akatsuki se revela  lentamente e os perigos surgem mais do que nunca!","images":[{"type":"poster","filename":"/tmdb/p/4w6I4QFTpBmKVTGMDhVpm4uU5Rb.jpg","original_url_field":"/img/tmdb/t/p/w185/4w6I4QFTpBmKVTGMDhVpm4uU5Rb.jpg"},{"type":"cover","filename":"/tmdb/c/fsZrHyICFykVbuRfFg7N46rJ8vT.jpg","original_url_field":"/img/tmdb/t/p/w300/fsZrHyICFykVbuRfFg7N46rJ8vT.jpg"}],"genres":[{"id":16,"name":"Animação","type":"all"},{"id":10759,"name":"Ação e aventura","type":"all"},{"id":10765,"name":"Ficção científica e fantasia","type":"all"}]}],"total_pages":4,"total_results":61,"page":1}"""

    // /br/titles/46260-naruto: seasons 0..2, first season loaded with three episodes
    private val titleHtml = """<!DOCTYPE html><html lang="pt-BR"><head><title>RedeToons</title></head><body><div id="app" data-page=""></div><script id="su-page-data" type="application/json">{"component":"Titles/Title","url":"/br/x","version":"v","props":{"title":{"id":46260,"slug":"naruto","name":"Naruto","type":"tv","plot":"Momentos antes do nascimento de Naruto Uzumaki, um enorme demônio conhecido como o Kyuubi, a Raposa de Nove Caudas, atacou o vilarejo da folha oculta Konoha, causando destruição. Para pôr fim à devastação de Kyuubi, o líder da aldeia, o quarto Hokage, sacrificou sua vida e selou o monstruoso animal dentro do recém-nascido Naruto. Agora, Naruto é um ninja hiperativo e cabeça dura que ainda vivem em Konoha. Evitado pelos demais habitantes por causa da Kyuubi dentro dele, Naruto se esforça para encontrar seu lugar na aldeia, enquanto o seu ardente desejo de se tornar o Hokage de Konoha o leva a conhecer alguns grandes novos amigos, e também alguns inimigos mortais.","status":"Ended","original_language":"ja","images":[{"type":"poster","filename":"/tmdb/p/ijxaQzjjX643N1gHiERaFAlwvJ5.jpg","original_url_field":"/img/tmdb/t/p/w185/ijxaQzjjX643N1gHiERaFAlwvJ5.jpg"},{"type":"cover","filename":"/tmdb/c/iWc6rzyWeUgW61JZNgIxBLD3nsG.jpg","original_url_field":"/img/tmdb/t/p/w300/iWc6rzyWeUgW61JZNgIxBLD3nsG.jpg"}],"genres":[{"id":16,"name":"Animação","type":"tv"},{"id":10759,"name":"Action & Adventure","type":"tv"},{"id":10765,"name":"Sci-Fi & Fantasy","type":"tv"}],"seasons":[{"id":54574,"number":0,"name":"Especiais","episodes_count":6,"episodes":[]},{"id":54573,"number":1,"name":"Temporada 1","episodes_count":52,"episodes":[]},{"id":54567,"number":2,"name":"Temporada 2","episodes_count":52,"episodes":[]}]},"loadedSeason":{"id":54573,"number":1,"episodes_count":52,"episodes":[{"id":1059121,"number":1,"name":"Naruto Uzumaki Chegando!","air_date":"2002-10-03"},{"id":1059122,"number":2,"name":"Meu Nome é Konohamaru!","air_date":"2002-10-10"},{"id":1059123,"number":3,"name":"Sasuke e Sakura: Amigos ou Inimigos?","air_date":"2002-10-17"}]}}}</script></body></html>"""

    // /br/titles/46260/season-2: the second season loaded
    private val seasonHtml = """<!DOCTYPE html><html lang="pt-BR"><head><title>RedeToons</title></head><body><div id="app" data-page=""></div><script id="su-page-data" type="application/json">{"component":"Titles/Title","url":"/br/x","version":"v","props":{"title":{"id":46260,"slug":"naruto","name":"Naruto","type":"tv","plot":"Momentos antes do nascimento de Naruto Uzumaki, um enorme demônio conhecido como o Kyuubi, a Raposa de Nove Caudas, atacou o vilarejo da folha oculta Konoha, causando destruição. Para pôr fim à devastação de Kyuubi, o líder da aldeia, o quarto Hokage, sacrificou sua vida e selou o monstruoso animal dentro do recém-nascido Naruto. Agora, Naruto é um ninja hiperativo e cabeça dura que ainda vivem em Konoha. Evitado pelos demais habitantes por causa da Kyuubi dentro dele, Naruto se esforça para encontrar seu lugar na aldeia, enquanto o seu ardente desejo de se tornar o Hokage de Konoha o leva a conhecer alguns grandes novos amigos, e também alguns inimigos mortais.","status":"Ended","original_language":"ja","images":[{"type":"poster","filename":"/tmdb/p/ijxaQzjjX643N1gHiERaFAlwvJ5.jpg","original_url_field":"/img/tmdb/t/p/w185/ijxaQzjjX643N1gHiERaFAlwvJ5.jpg"},{"type":"cover","filename":"/tmdb/c/iWc6rzyWeUgW61JZNgIxBLD3nsG.jpg","original_url_field":"/img/tmdb/t/p/w300/iWc6rzyWeUgW61JZNgIxBLD3nsG.jpg"}],"genres":[{"id":16,"name":"Animação","type":"tv"},{"id":10759,"name":"Action & Adventure","type":"tv"},{"id":10765,"name":"Sci-Fi & Fantasy","type":"tv"}],"seasons":[{"id":54574,"number":0,"name":"Especiais","episodes_count":6,"episodes":[]},{"id":54573,"number":1,"name":"Temporada 1","episodes_count":52,"episodes":[]},{"id":54567,"number":2,"name":"Temporada 2","episodes_count":52,"episodes":[]}]},"loadedSeason":{"id":54567,"number":2,"episodes_count":52,"episodes":[{"id":908117,"number":53,"name":"Após Muito Tempo: Jiraiya Retorna!","air_date":"2003-10-08"},{"id":908118,"number":54,"name":"Jutsu de Invocação: A Sabedoria do Sábio Sapo!!","air_date":"2003-10-15"}]}}}</script></body></html>"""

    // /br/titles/603-the-matrix
    private val movieHtml = """<!DOCTYPE html><html lang="pt-BR"><head><title>RedeToons</title></head><body><div id="app" data-page=""></div><script id="su-page-data" type="application/json">{"component":"Titles/Title","url":"/br/x","version":"v","props":{"title":{"id":603,"slug":"the-matrix","name":"Matrix","type":"movie","plot":"O jovem programador Thomas Anderson é atormentado por estranhos pesadelos em que está sempre conectado por cabos a um imenso sistema de computadores do futuro. À medida que o sonho se repete, ele começa a desconfiar da realidade. Thomas conhece os misteriosos Morpheus e Trinity e descobre que é vítima de um sistema inteligente e artificial chamado Matrix, que manipula a mente das pessoas e cria a ilusão de um mundo real enquanto usa os cérebros e corpos dos indivíduos para produzir energia.","status":"Released","original_language":"en","images":[{"type":"poster","filename":"/tmdb/p/lDqMDI3xpbB9UQRyeXfei0MXhqb.jpg","original_url_field":"/img/tmdb/t/p/w185/lDqMDI3xpbB9UQRyeXfei0MXhqb.jpg"},{"type":"cover","filename":"/tmdb/c/6xHUanD4V2hd8EJhFuybppqWgSt.jpg","original_url_field":"/img/tmdb/t/p/w300/6xHUanD4V2hd8EJhFuybppqWgSt.jpg"}],"genres":[{"id":28,"name":"Ação","type":"movie"},{"id":878,"name":"Ficção científica","type":"movie"}],"seasons":[]}}}</script></body></html>"""

    private fun title(id: Int, type: String, genres: List<Int>, language: String? = null) = SiteTitle(
        id = id,
        name = "Obra",
        type = type,
        originalLanguage = language,
        genres = genres.map { SiteGenre(it, "g$it") },
    )

    @Test
    fun readsTheTitlesOfABrowsePage() {
        val titles = browseTitles(Jsoup.parse(browseHtml))

        assertEquals(listOf(5920, 155), titles.map { it.id })
        assertEquals(listOf("tv", "movie"), titles.map { it.type })
        assertEquals("The Mentalist", titles[0].name)
    }

    @Test
    fun buildsAbsolutePosterUrlsFromTheSiteImagePath() {
        val poster = posterUrl("https://redetoons.li", browseTitles(Jsoup.parse(browseHtml))[0])

        assertNotNull(poster)
        assertTrue(poster!!.startsWith("https://redetoons.li/img/tmdb/"))
    }

    @Test
    fun onlyAFullBrowsePageHasANextOne() {
        assertTrue(hasNextBrowsePage(BROWSE_PAGE_SIZE))
        assertFalse(hasNextBrowsePage(10)) // top10 always returns the same ten
        assertFalse(hasNextBrowsePage(0))
    }

    @Test
    fun readsSearchResultsAndPagingFromTheApi() {
        val page = searchJson.parseAs<SearchPage>()

        assertEquals(2, page.results.size)
        assertEquals("Naruto", page.results[0].name)
        assertEquals(4, page.totalPages)
        assertTrue(page.hasNext)
    }

    @Test
    fun readsTitleDetailsAndTheLoadedSeason() {
        val page = titlePage(Jsoup.parse(titleHtml))

        assertEquals("Naruto", page.title.name)
        assertEquals(listOf(0, 1, 2), page.title.seasons.map { it.number })
        assertEquals(1, page.loadedSeason!!.number)
        assertEquals("Naruto Uzumaki Chegando!", page.loadedSeason!!.episodes.first().name)
    }

    @Test
    fun aMovieHasNoSeasons() {
        val page = titlePage(Jsoup.parse(movieHtml))

        assertEquals("movie", page.title.type)
        assertTrue(page.title.seasons.isEmpty())
        assertNull(page.loadedSeason)
    }

    @Test
    fun listsEpisodesNewestFirstKeepingTheOldEpisodeUrls() {
        val seasons = listOf(titlePage(Jsoup.parse(titleHtml)).loadedSeason!!, titlePage(Jsoup.parse(seasonHtml)).loadedSeason!!)

        val episodes = episodeEntries("tv", 46260, seasons)

        assertEquals("46260|2|2", episodes.first().url)
        assertEquals(2.002f, episodes.first().number, 0.0001f)
        assertEquals("46260|1|1", episodes.last().url)
        assertEquals("T1E1 - Naruto Uzumaki Chegando!", episodes.last().name)
        assertEquals(1.001f, episodes.last().number, 0.0001f)
    }

    @Test
    fun aMovieIsASingleEpisodeKeepingTheOldUrl() {
        val episodes = episodeEntries("movie", 603, emptyList())

        assertEquals(1, episodes.size)
        assertEquals("movie|603", episodes[0].url)
        assertEquals("Filme", episodes[0].name)
    }

    @Test
    fun keepsOpeningAnimeUrlsAlreadySavedInTheLibrary() {
        assertEquals(46260, animeRef("tv/46260")!!.id)
        assertEquals("tv", animeRef("/tv/46260")!!.type)
        assertEquals(603, animeRef("https://redetoons.my/movie/603")!!.id)
        assertEquals("movie", animeRef("movie/603")!!.type)
        assertEquals(46260, animeRef("https://redetoons.li/br/titles/46260-naruto")!!.id)
        assertNull(animeRef("https://redetoons.li/br/titles/46260-naruto")!!.type)
        assertNull(animeRef("nada"))
    }

    @Test
    fun anAnimeIsAnimationFromJapanAndWesternCartoonsStaySeries() {
        val naruto = title(46260, "tv", listOf(16), "ja")
        val cartoon = title(1, "tv", listOf(16), "en")
        val film = title(603, "movie", listOf(28), "en")

        assertTrue(matchesCategory(ContentCategory.ANIMATION, "tv", naruto))
        assertFalse(matchesCategory(ContentCategory.SERIES, "tv", naruto))
        assertFalse(matchesCategory(ContentCategory.ANIMATION, "tv", cartoon))
        assertTrue(matchesCategory(ContentCategory.SERIES, "tv", cartoon))
        assertTrue(matchesCategory(ContentCategory.MOVIES, "movie", film))
    }

    // /api/titles/preview/46260: the language and genres the lists leave out
    private val previewJson = """{"id":46260,"slug":"naruto","name":"Naruto","plot":"Momentos antes do nascimento de Naruto Uzumaki, um enorme demônio conhecido como o Kyuubi, a Raposa de Nove Caudas, atacou o vilarejo da folha oculta Konoha, causando destruição. Para pôr fim à devastação de Kyuubi, o líder da aldeia, o quarto Hokage, sacrificou sua vida e selou o monstruoso animal dentro do recém-nascido Naruto. Agora, Naruto é um ninja hiperativo e cabeça dura que ainda vivem em Konoha. Evitado pelos demais habitantes por causa da Kyuubi dentro dele, Naruto se esforça para encontrar seu lugar na aldeia, enquanto o seu ardente desejo de se tornar o Hokage de Konoha o leva a conhecer alguns grandes novos amigos, e também alguns inimigos mortais.","type":"tv","status":"Ended","original_language":"ja","genres":[{"id":16,"name":"Animação","type":"tv"},{"id":10759,"name":"Ação e aventura","type":"tv"},{"id":10765,"name":"Ficção científica e fantasia","type":"tv"}]}"""

    @Test
    fun theTitlePreviewCarriesTheLanguageNeededToTellAnimeApart() {
        val preview = previewJson.parseAs<SiteTitle>()

        assertEquals("ja", preview.originalLanguage)
        assertTrue(matchesCategory(ContentCategory.ANIMATION, preview.type, preview))
    }
}
