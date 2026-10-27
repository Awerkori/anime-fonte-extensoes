package eu.kanade.tachiyomi.animeextension.pt.berryanimes

import androidx.preference.PreferenceScreen
import aniyomi.lib.bloggerextractor.BloggerExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.parseAs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.jsoup.nodes.Document

class BerryAnimes :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {
    override val name = "Berry Animes"
    override val baseUrl = "https://berryanimes.com"
    override val lang = "pt-BR"

    // Home rails are editorial recommendations, not a chronological update feed.
    override val supportsLatest = false
    override val supportsRelatedAnimes = false
    private val account by lazy { BerryAccount(client, headers) }

    override fun headersBuilder() = super.headersBuilder().set("Referer", "$baseUrl/pt-br/")

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/pt-br/discover", headers)
    override fun popularAnimeParse(response: Response): AnimesPage {
        val home = response.extractNextJs<Home>() ?: error("Catálogo Berry indisponível")
        val rail = home.initialRails.firstOrNull { it.title == "Populares no Brasil" }
            ?: error("A seção Populares no Brasil não está disponível")
        return AnimesPage(rail.items.map(::toAnime), false)
    }

    override fun latestUpdatesRequest(page: Int) = error("O site não fornece uma lista cronológica verificada")
    override fun latestUpdatesParse(response: Response): AnimesPage = error("Recentes indisponível")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = GET(
        "$baseUrl/api/animes".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("take", "24")
            .addQueryParameter("locale", "pt-br")
            .addQueryParameter("q", query)
            .addQueryParameter("type", filters.filterIsInstance<TypeFilter>().firstOrNull()?.value.orEmpty())
            .build(),
        headers,
    )

    override fun searchAnimeParse(response: Response): AnimesPage {
        val catalog = response.parseAs<Catalog>()
        return AnimesPage(catalog.items.distinctBy { it.detailsHref }.map(::toAnime), catalog.hasMore)
    }

    private fun toAnime(card: Card) = SAnime.create().apply {
        url = localized(card.detailsHref)
        title = card.title
        thumbnail_url = absolute(card.coverUrl)
    }

    private fun localized(path: String) = if (path.startsWith("/pt-br/")) path else "/pt-br/${path.trimStart('/')}"
    private fun absolute(path: String) = baseUrl.toHttpUrl().resolve(path)!!.toString()

    override fun animeDetailsRequest(anime: SAnime) = GET(absolute(anime.url), headers)
    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val data = doc.selectFirst("script[type=application/ld+json]")?.data()
            ?.parseAs<StructuredData>()?.graph?.firstOrNull { it.type in listOf("TVSeries", "Movie") }
            ?: error("Detalhes Berry indisponíveis")
        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            title = data.name
            thumbnail_url = data.image?.let(::absolute)
            genre = data.genre.joinToString()
            description = buildString {
                append(data.description.orEmpty())
                data.alternateName?.let { append("\n\nTítulo alternativo: $it") }
                data.datePublished?.let { append("\nAno: $it") }
                data.contentRating?.let { append("\nClassificação: $it") }
                data.numberOfSeasons?.let { append("\nTemporadas: $it") }
                data.numberOfEpisodes?.let { append("\nEpisódios: $it") }
            }
            val badges = doc.select(".detail-mini-badge").eachText()
            status = when {
                "Em lançamento" in badges -> SAnime.ONGOING
                "Completo" in badges -> SAnime.COMPLETED
                "Em pausa" in badges -> SAnime.ON_HIATUS
                else -> SAnime.UNKNOWN
            }
            initialized = true
        }
    }

    override fun episodeListRequest(anime: SAnime) = animeDetailsRequest(anime)
    override fun episodeListParse(response: Response): List<SEpisode> = parseEpisodes(response.asJsoup())

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val doc = client.newCall(episodeListRequest(anime)).awaitSuccess().asJsoup()
        val episodes = parseEpisodes(doc).toMutableList()

        val currentSeasonNumber = episodes.firstOrNull()?.name?.substringAfter("T")?.substringBefore(" ")?.toIntOrNull()
            ?: doc.selectFirst("button[role=option][aria-selected=true]")?.text()?.let {
                Regex("""Temporada\s+(\d+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull()
            }
            ?: doc.selectFirst("a.episode-card")?.selectFirst(".episode-card-meta")?.text()
                ?.substringBefore(" | ")?.removePrefix("T")?.toIntOrNull()
            ?: 1

        val otherSeasons = mutableSetOf<Int>()

        // 1. Season buttons in HTML (current Next.js):
        doc.select("button[role=option], button.AnimeDetail_seasonTrigger__MXufI, [aria-label=Temporada] button").forEach { btn ->
            val num = Regex("""Temporada\s+(\d+)""", RegexOption.IGNORE_CASE).find(btn.text())?.groupValues?.get(1)?.toIntOrNull()
            if (num != null && num != currentSeasonNumber) {
                otherSeasons.add(num)
            }
        }

        // 2. Legacy season links:
        doc.select("a[href*=temporada=]").forEach { link ->
            val num = link.absUrl("href").toHttpUrl().queryParameter("temporada")?.toIntOrNull()
            if (num != null && num != currentSeasonNumber) {
                otherSeasons.add(num)
            }
        }

        // 3. StructuredData JSON-LD numberOfSeasons:
        val numSeasonsLd = doc.selectFirst("script[type=application/ld+json]")?.data()
            ?.let { runCatching { it.parseAs<StructuredData>() }.getOrNull() }
            ?.graph?.firstOrNull { it.type in listOf("TVSeries", "Movie") }?.numberOfSeasons
        if (numSeasonsLd != null && numSeasonsLd > 1) {
            for (s in 1..numSeasonsLd) {
                if (s != currentSeasonNumber) {
                    otherSeasons.add(s)
                }
            }
        }

        for (season in otherSeasons.sorted()) {
            val seasonUrl = absolute(anime.url).toHttpUrl().newBuilder()
                .setQueryParameter("temporada", season.toString())
                .build()
            try {
                val seasonDoc = client.newCall(GET(seasonUrl, headers)).awaitSuccess().asJsoup()
                episodes += parseEpisodes(seasonDoc)
            } catch (_: Exception) {
            }
        }

        return episodes.distinctBy { it.url }.sortedWith(
            compareByDescending<SEpisode> { it.name.substringAfter("T").substringBefore(" ").toIntOrNull() ?: 0 }
                .thenByDescending { it.episode_number },
        )
    }

    private fun parseEpisodes(doc: Document): List<SEpisode> {
        // EpisodeGrid initially renders only 24 cards. Its RSC props contain the complete season.
        val grid = doc.extractNextJs<EpisodeGrid> {
            val episodes = (it as? JsonObject)?.get("episodes") as? JsonArray
            (episodes?.firstOrNull() as? JsonObject)?.containsKey("href") == true
        }
        if (grid == null) {
            val movie = doc.selectFirst("script[type=application/ld+json]")?.data()
                ?.parseAs<StructuredData>()?.graph?.firstOrNull { it.type == "Movie" }
            val watch = doc.selectFirst("a.watch-primary-button[href*=/watch/]")
            if (movie != null && watch != null) {
                return listOf(
                    SEpisode.create().apply {
                        setUrlWithoutDomain(watch.absUrl("href"))
                        name = movie.name
                        episode_number = 1F
                    },
                )
            }
            val htmlCards = doc.select("a.episode-card[href*=/watch/]")
            if (htmlCards.isNotEmpty()) {
                return htmlCards.map { card ->
                    val href = card.attr("href")
                    val title = card.selectFirst("h3")?.text()?.trim().orEmpty()
                    val metaText = card.selectFirst(".episode-card-meta")?.text()?.trim().orEmpty()
                    val hasSub = card.selectFirst(".lucide-captions, svg[aria-label*=Legendas]") != null
                    val hasDub = metaText.contains("Dub", ignoreCase = true)
                    parseEpisodeCard(
                        EpisodeCard(
                            href = href,
                            title = title,
                            meta = metaText.takeIf { it.contains("|") },
                            hasSubtitles = hasSub,
                            hasDub = hasDub,
                        ),
                    )
                }
            }
            error("A lista completa de episódios não está disponível")
        }
        return grid.episodes.map(::parseEpisodeCard)
    }

    internal fun parseEpisodeCard(card: EpisodeCard): SEpisode {
        val info = BerryAnimesHelper.parseCardInfo(card)
        return SEpisode.create().apply {
            url = info.url
            name = info.name
            episode_number = info.episodeNumber
            this.scanlator = info.scanlator
        }
    }

    override fun videoListRequest(episode: SEpisode): okhttp3.Request {
        val path = absolute(episode.url).toHttpUrl().encodedPath.substringAfter("/watch/")
        return GET("$baseUrl/api/watch/$path", headers)
    }
    override fun videoListParse(response: Response): List<Video> = runBlocking { resolveVideos(response) }

    private suspend fun resolveVideos(response: Response): List<Video> = response.use {
        if (it.request.url.host == "sso.berryanimes.com" || it.code == 401) {
            error("Entre na conta Berry Animes nas configurações da fonte")
        }
        check(it.isSuccessful) { "Playback Berry indisponível (HTTP ${it.code})" }
        val watch = it.parseAs<Watch>()
        BerryAnimesHelper.checkPlaybackAuthorization(watch)
        val player = watch.player!!
        if (player.provider == "blogger" || player.streamUrl.isNullOrBlank()) {
            val embeds = listOfNotNull(player.externalEmbedUrl, player.bloggerUrl, player.embedUrl, player.fallbackBloggerUrl)
                .mapNotNull { it.toHttpUrlOrNull() }
                .filter { it.isHttps && it.host in listOf("www.blogger.com", "blogger.com") && it.encodedPath == "/video.g" }
                .distinct()
            check(embeds.isNotEmpty()) { "O site não disponibilizou um stream ou player suportado" }
            // Blogger needs its own Referer, never the authenticated Berry cookie/context headers.
            val bloggerHeaders = headers.newBuilder().set("Referer", "https://www.blogger.com/").build()
            val extractor = BloggerExtractor(client)
            val videos = mutableListOf<Video>()
            for (embed in embeds) {
                try {
                    videos += extractor.videosFromUrl(embed.toString(), bloggerHeaders).filter { video ->
                        val url = video.videoUrl?.toHttpUrlOrNull()
                        url != null && url.host.endsWith(".googlevideo.com") && url.encodedPath == "/videoplayback" &&
                            (
                                url.queryParameter("itag") in listOf("18", "22", "37", "59", "78") ||
                                    (url.queryParameter("itag") == null && url.queryParameter("mime") == "video/mp4")
                                )
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // One failed alternate embed must not discard a working video.
                }
            }
            check(videos.isNotEmpty()) { "O Blogger não disponibilizou um MP4 reproduzível para este episódio" }
            return@use videos.distinctBy { it.videoUrl }.sortVideos()
        }
        val stream = player.streamUrl?.toHttpUrl() ?: error("O site não disponibilizou o stream")
        check(stream.isHttps && (stream.host == "berryanimes.com" || stream.host.endsWith(".berryanimes.com"))) {
            "Servidor Berry ainda não suportado"
        }
        val playbackHeaders = headers.newBuilder().set("Origin", baseUrl).apply {
            set(player.streamProviderHeader, player.streamProviderValue)
            player.streamContextToken?.takeIf(String::isNotBlank)?.let { set(player.streamContextHeader, it) }
            val cookies = client.cookieJar.loadForRequest(stream).joinToString("; ") { "${it.name}=${it.value}" }
            if (cookies.isNotEmpty()) set("Cookie", cookies)
        }.build()
        val subtitles = player.subtitles.sortedBy { if (it.label == "pt-BR") 0 else 1 }.map { Track(absolute(it.url), it.label) }
        check(stream.encodedPath.endsWith(".m3u8") || player.mimeType.orEmpty().contains("mpegurl", true)) { "Formato Berry ainda não suportado" }
        PlaylistUtils(client).extractFromHls(
            stream.toString(),
            masterHeaders = playbackHeaders,
            videoHeaders = playbackHeaders,
            subtitleList = subtitles,
        ).flatMap { video ->
            val audio = video.audioTracks.filter { it.lang in listOf("pt-BR", "ja-JP") }
            if (audio.isEmpty()) {
                listOf(Video(video.videoUrl, "Berry - ${video.videoTitle}", video.videoUrl, playbackHeaders, video.subtitleTracks, video.audioTracks))
            } else {
                audio.map { track ->
                    val language = if (track.lang == "pt-BR") "Dublado" else "Legendado"
                    Video(video.videoUrl, "Berry - $language - ${video.videoTitle}", video.videoUrl, playbackHeaders, video.subtitleTracks, listOf(track))
                }
            }
        }.sortVideos()
    }

    override fun List<Video>.sortVideos() = sortedByDescending { Regex("(\\d{3,4})p").find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }

    override fun videoUrlParse(response: Response): String = error("Use videoListParse")
    override fun setupPreferenceScreen(screen: PreferenceScreen) = account.addPreferences(screen)
    override fun getFilterList() = AnimeFilterList(TypeFilter())

    private class TypeFilter : AnimeFilter.Select<String>("Tipo", arrayOf("Todos", "Séries", "Filmes", "OVAs", "Especial")) {
        val value get() = arrayOf("", "SERIES", "MOVIE", "OVA", "SPECIAL")[state]
    }
}
