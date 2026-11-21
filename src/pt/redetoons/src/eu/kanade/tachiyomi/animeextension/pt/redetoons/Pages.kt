package eu.kanade.tachiyomi.animeextension.pt.redetoons

import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document

// redetoons.li is a Laravel + Inertia site: every page carries its data as JSON in <script id="su-page-data">.
@Serializable
class SitePage<T>(val props: T)

@Serializable
class BrowseProps(val titles: List<SiteTitle> = emptyList())

@Serializable
class TitleProps(val title: SiteTitle, val loadedSeason: SiteSeason? = null)

@Serializable
class SiteTitle(
    val id: Int,
    val slug: String? = null,
    val name: String,
    val type: String,
    val plot: String? = null,
    val status: String? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
    val images: List<SiteImage> = emptyList(),
    val genres: List<SiteGenre> = emptyList(),
    val seasons: List<SiteSeason> = emptyList(),
)

@Serializable
class SiteImage(val type: String, @SerialName("original_url_field") val url: String? = null)

@Serializable
class SiteGenre(val id: Int, val name: String)

@Serializable
class SiteSeason(
    val number: Int,
    @SerialName("episodes_count") val episodesCount: Int = 0,
    val episodes: List<SiteEpisode> = emptyList(),
)

@Serializable
class SiteEpisode(val id: Long, val number: Int, val name: String? = null)

@Serializable
class SearchPage(
    val results: List<SiteTitle> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 1,
    val page: Int = 1,
)

class AnimeRef(val type: String?, val id: Int)

class EpisodeEntry(val url: String, val name: String, val number: Float)

enum class ContentCategory(val key: String, val label: String) {
    ANIMATION("animation", "Animação"),
    MOVIES("movies", "Filmes"),
    SERIES("series", "Séries"),
}

const val BROWSE_PAGE_SIZE = 60

internal fun pageData(document: Document): String = document.selectFirst("script#su-page-data")!!.data()

fun browseTitles(document: Document): List<SiteTitle> = pageData(document).parseAs<SitePage<BrowseProps>>().props.titles

fun titlePage(document: Document): TitleProps = pageData(document).parseAs<SitePage<TitleProps>>().props

val SearchPage.hasNext: Boolean get() = page < totalPages

// "top10" always answers the same ten titles, so only a full page can have a next one.
fun hasNextBrowsePage(itemCount: Int): Boolean = itemCount >= BROWSE_PAGE_SIZE

fun posterUrl(baseUrl: String, title: SiteTitle): String? = title.images.firstOrNull { it.type == "poster" }?.url?.let { if (it.startsWith("http")) it else baseUrl + it }

private val savedAnimeUrl = Regex("""^/?(tv|movie)/(\d+)/?$""")
private val titlePath = Regex("""/titles/(\d+)""")

// Reads the url saved in a library: "tv/46260", "/movie/603", an old absolute url, or a /br/titles/<id>-<slug> path.
fun animeRef(url: String): AnimeRef? {
    val clean = url.trim().substringBefore('?')
    val path = if ("://" in clean) clean.toHttpUrlOrNull()?.encodedPath ?: return null else clean
    savedAnimeUrl.find(path)?.let { return AnimeRef(it.groupValues[1], it.groupValues[2].toInt()) }
    return titlePath.find(path)?.let { AnimeRef(null, it.groupValues[1].toInt()) }
}

// Keeps the url formats the old source saved ("<id>|<season>|<episode>", "movie|<id>"), so watched marks survive.
fun episodeEntries(type: String, id: Int, seasons: List<SiteSeason>): List<EpisodeEntry> {
    if (type == "movie") return listOf(EpisodeEntry("movie|$id", "Filme", 1f))
    return seasons.sortedBy { it.number }.flatMap { season ->
        season.episodes.mapIndexed { index, episode ->
            val number = index + 1
            EpisodeEntry(
                url = "$id|${season.number}|$number",
                name = "T${season.number}E$number - ${episode.name ?: "Episódio $number"}",
                number = season.number + number / 1000f,
            )
        }
    }.reversed()
}

private const val ANIMATION_GENRE_ID = 16

fun matchesCategory(category: ContentCategory, type: String, title: SiteTitle): Boolean {
    val anime = title.genres.any { it.id == ANIMATION_GENRE_ID } && title.originalLanguage.equals("ja", ignoreCase = true)
    return when (category) {
        ContentCategory.ANIMATION -> anime
        ContentCategory.MOVIES -> type == "movie" && !anime
        ContentCategory.SERIES -> type == "tv" && !anime
    }
}
