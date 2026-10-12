package eu.kanade.tachiyomi.animeextension.pt.dattebayobr

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.tryParse
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class DattebayoBR : AnimeHttpLegacySource() {
    override val name = "Dattebayo BR"
    override val baseUrl = "https://www.dattebayo-br.com"
    override val lang = "pt-BR"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")

    // ============================== Popular ==============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animes/page/$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val animes = document.select("div.ultimosAnimesHomeItem").mapNotNull(::animeFromElement)
        val hasNextPage = document.selectFirst("div.letterBox a:contains(»)") != null
        return AnimesPage(animes, hasNextPage)
    }

    // ============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val animes = document.select("div.ultimosAnimesHomeItem").mapNotNull(::animeFromElement)
        return AnimesPage(animes, hasNextPage = false)
    }

    // ============================== Search ===============================
    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        if (query.isNotBlank()) {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            return GET("$baseUrl/busca?busca=$encodedQuery&page=$page", headers)
        }

        var letterPath = ""
        var categoryPath = "animes"

        for (filter in filters) {
            when (filter) {
                is LetterFilter -> {
                    val value = filter.toPath()
                    if (value.isNotBlank()) {
                        letterPath = value
                    }
                }
                is CategoryFilter -> {
                    categoryPath = filter.toPath()
                }
                else -> {}
            }
        }

        val url = if (letterPath.isNotBlank()) {
            "$baseUrl/animes/letra/$letterPath/page/$page"
        } else {
            "$baseUrl/$categoryPath/page/$page"
        }

        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Details ==============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()

        return SAnime.create().apply {
            title = document.selectFirst(".tituloPage h1")
                ?.text()
                ?.trim()
                ?: "Sem título"

            thumbnail_url = document
                .selectFirst(".aniInfosSingleCapa img")
                ?.attr("abs:src")

            description = document
                .selectFirst(".aniInfosSingleSinopse p")
                ?.text()
                ?.trim()

            genre = document
                .select(".aniInfosSingleGeneros span")
                .joinToString { it.text() }

            author = document
                .selectFirst(".aniInfosSingleInfoItem:has(b:contains(Autor)) span")
                ?.text()
                ?.trim()

            artist = document
                .selectFirst(".aniInfosSingleInfoItem:has(b:contains(Estúdio)) span")
                ?.text()
                ?.trim()

            status = when {
                document.selectFirst(".anime_status span")?.text()?.contains("Completo", ignoreCase = true) == true -> SAnime.COMPLETED
                else -> SAnime.ONGOING
            }

            initialized = true
        }
    }

    // ============================== Episodes =============================
    private fun parseEpisodeElement(element: Element): SEpisode? {
        try {
            val anchor = element.selectFirst("a") ?: return null
            val episodeNumText = element.selectFirst(".ultimosEpisodiosHomeItemInfosNum")
                ?.text()
                ?.replace("Episódio", "", ignoreCase = true)
                ?.trim()
                ?: return null

            val episodeNumber = episodeNumText
                .replace(",", ".")
                .toFloatOrNull()

            val episodeName = element.selectFirst(".ultimosEpisodiosHomeItemInfosNome")
                ?.text()
                ?.trim()
                ?: "Episódio $episodeNumText"

            return SEpisode.create().apply {
                setUrlWithoutDomain(anchor.attr("href"))
                name = episodeName
                episode_number = episodeNumber ?: 0f
                date_upload = element.selectFirst(".lancaster_episodio_info_data")?.text().let(dateFormatter::tryParse)
            }
        } catch (_: Exception) {
            return null
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val seenUrls = mutableSetOf<String>()

        val baseAnimeUrl = response.request.url.toString()
            .substringBefore("/page/")
            .removeSuffix("/")

        var currentPage = 1

        while (true) {
            val pageUrl = if (currentPage == 1) baseAnimeUrl else "$baseAnimeUrl/page/$currentPage"
            val document = if (currentPage == 1) {
                response.useAsJsoup()
            } else {
                client.newCall(GET(pageUrl, headers)).execute().useAsJsoup()
            }

            val pageEpisodes = document.select("div.ultimosEpisodiosHomeItem")
            if (pageEpisodes.isEmpty()) break

            var addedAny = false
            for (element in pageEpisodes) {
                val episode = parseEpisodeElement(element) ?: continue
                if (seenUrls.add(episode.url)) {
                    episodes.add(episode)
                    addedAny = true
                }
            }

            if (!addedAny) break

            val hasNextPage = document.selectFirst("div.letterBox a:contains(»)") != null
            if (!hasNextPage) break

            currentPage++
        }

        if (episodes.isEmpty()) return emptyList()

        return episodes
            .sortedByDescending { ep ->
                ep.episode_number.takeIf { it > 0f } ?: Float.MAX_VALUE
            }
            .mapIndexed { index, ep ->
                ep.apply {
                    if (episode_number <= 0f) {
                        episode_number = (episodes.size - index).toFloat()
                    }
                }
            }
    }

    // =============================== Videos ==============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        val episodeUrl = response.request.url.toString()

        val activeAbas = document.select("div.AbasBox div.Aba")

        val playerTargets = if (activeAbas.isNotEmpty()) {
            activeAbas.mapNotNull { aba ->
                val abaType = aba.attr("aba-type").ifBlank { aba.attr("data-target") }
                val qualityName = aba.text().trim().ifBlank { "Padrão" }
                val container = document.getElementById(abaType)
                if (container != null) Pair(qualityName, container) else null
            }
        } else {
            document.select("div.playerContainer").mapIndexed { index, container ->
                Pair("Servidor ${index + 1}", container)
            }
        }

        val candidates = playerTargets.parallelCatchingFlatMapBlocking { (qualityName, container) ->
            val scriptData = container.selectFirst("script")?.data() ?: ""
            val vidRegex = """var vid\s*=\s*['"](.*?)['"]""".toRegex()
            val urlQualidade = vidRegex.find(scriptData)?.groupValues?.get(1)
                ?: return@parallelCatchingFlatMapBlocking emptyList()

            val encodedUrl = java.net.URLEncoder.encode(urlQualidade, "UTF-8")
            val adUrl = "https://ads.animeyabu.net?url=$encodedUrl"

            val adHeaders = headers.newBuilder()
                .set("Referer", episodeUrl)
                .set("Origin", "https://www.dattebayo-br.com")
                .build()

            val adRequest = Request.Builder().url(adUrl).headers(adHeaders).build()
            val adResponse = client.newCall(adRequest).awaitSuccess()
            val adBody = adResponse.bodyString()

            if (adBody.contains("publicidade")) {
                val jsonArray = JSONArray(adBody)
                if (jsonArray.length() > 0) {
                    val obj = jsonArray.getJSONObject(0)
                    val assinatura = obj.optString("publicidade", "")

                    if (assinatura.isNotBlank()) {
                        val urlFinal = urlQualidade + assinatura
                        val videoHeaders = headers.newBuilder()
                            .set("Referer", episodeUrl)
                            .build()

                        val probeRequest = Request.Builder()
                            .url(urlFinal)
                            .headers(videoHeaders)
                            .header("Range", "bytes=0-0")
                            .build()

                        val isPlayable = runCatching {
                            client.newCall(probeRequest).execute().use { probeResponse ->
                                probeResponse.isSuccessful
                            }
                        }.getOrDefault(false)

                        return@parallelCatchingFlatMapBlocking listOf(
                            Pair(Video(urlFinal, qualityName, urlFinal, headers = videoHeaders), isPlayable),
                        )
                    }
                }
            }
            emptyList()
        }

        val playable = candidates.filter { it.second }.map { it.first }
        val streamsToUse = if (playable.isNotEmpty()) playable else candidates.map { it.first }

        return streamsToUse
            .sortedByDescending { qualityPriority(it.videoTitle) }
            .distinctBy { it.videoUrl }
    }

    private fun qualityPriority(quality: String): Int {
        val q = quality.uppercase()
        return when {
            q.contains("FULL") || q.contains("1080") -> 100
            q.contains("HD") || q.contains("720") -> 80
            q.contains("SD") || q.contains("480") || q.contains("360") -> 50
            else -> 10
        }
    }

    // =============================== Filters =============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        CategoryFilter(),
        LetterFilter(),
    )

    private class CategoryFilter :
        AnimeFilter.Select<String>(
            "Categoria",
            CATEGORIES.map { it.first }.toTypedArray(),
        ) {
        fun toPath(): String = CATEGORIES[state].second
    }

    private class LetterFilter :
        AnimeFilter.Select<String>(
            "Letra inicial",
            LETTERS.map { it.first }.toTypedArray(),
        ) {
        fun toPath(): String = LETTERS[state].second
    }

    // =============================== Helpers =============================
    private fun animeFromElement(element: Element): SAnime? {
        val anchor = element.selectFirst("a") ?: return null
        val title = element.selectFirst(".ultimosAnimesHomeItemInfosNome")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return SAnime.create().apply {
            this.title = title
            setUrlWithoutDomain(anchor.attr("href"))
            thumbnail_url = element
                .selectFirst(".ultimosAnimesHomeItemImg img")
                ?.attr("abs:src")
        }
    }

    private val dateFormatter by lazy { SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", Locale("pt", "BR")) }

    companion object {
        private val CATEGORIES = arrayOf(
            Pair("Todos", "animes"),
            Pair("Animes Dublados", "anime-dublado"),
            Pair("Tokusatsus", "tokusatsus"),
            Pair("Doramas", "doramas"),
            Pair("Donghuas", "donghua"),
        )

        private val LETTERS = arrayOf(
            Pair("Todas", ""),
            Pair("0-9", "numeros"),
            Pair("A", "a"),
            Pair("B", "b"),
            Pair("C", "c"),
            Pair("D", "d"),
            Pair("E", "e"),
            Pair("F", "f"),
            Pair("G", "g"),
            Pair("H", "h"),
            Pair("I", "i"),
            Pair("J", "j"),
            Pair("K", "k"),
            Pair("L", "l"),
            Pair("M", "m"),
            Pair("N", "n"),
            Pair("O", "o"),
            Pair("P", "p"),
            Pair("Q", "q"),
            Pair("R", "r"),
            Pair("S", "s"),
            Pair("T", "t"),
            Pair("U", "u"),
            Pair("V", "v"),
            Pair("W", "w"),
            Pair("X", "x"),
            Pair("Y", "y"),
            Pair("Z", "z"),
        )
    }
}
