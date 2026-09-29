package eu.kanade.tachiyomi.animeextension.pt.anikyuu

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnikyuuFilters {

    open class SelectFilter(
        displayName: String,
        private val vals: Array<String>,
    ) : AnimeFilter.Select<String>(displayName, vals) {
        val selected: String
            get() = vals[state]
    }

    class GenreFilter(genres: Array<String>) : SelectFilter("Gênero", genres)
    class ThemeFilter(themes: Array<String>) : SelectFilter("Tema", themes)
    class ExplicitGenreFilter(explicits: Array<String>) : SelectFilter("Gêneros Explícitos", explicits)
    class DemographicFilter(demographics: Array<String>) : SelectFilter("Demografia", demographics)
    class AudioFilter : SelectFilter("Áudio", arrayOf("Todos", "Legendado", "Dublado"))

    fun getFilterList(
        genres: List<String>,
        themes: List<String>,
        explicits: List<String>,
        demographics: List<String>,
    ): AnimeFilterList {
        val filters = mutableListOf<AnimeFilter<*>>()
        filters.add(AudioFilter())
        val gList = if (genres.isNotEmpty()) genres else DEFAULT_GENRES
        filters.add(GenreFilter(arrayOf("Todos") + gList.toTypedArray()))
        val tList = if (themes.isNotEmpty()) themes else DEFAULT_THEMES
        filters.add(ThemeFilter(arrayOf("Todos") + tList.toTypedArray()))
        val eList = if (explicits.isNotEmpty()) explicits else DEFAULT_EXPLICITS
        filters.add(ExplicitGenreFilter(arrayOf("Todos") + eList.toTypedArray()))
        val dList = if (demographics.isNotEmpty()) demographics else DEFAULT_DEMOGRAPHICS
        filters.add(DemographicFilter(arrayOf("Todos") + dList.toTypedArray()))
        return AnimeFilterList(filters)
    }

    val DEFAULT_GENRES = listOf(
        "Ação", "Aventura", "Avant-garde", "Comédia", "Drama", "Esportes",
        "Fantasia", "Ficção científica", "Gourmet", "Horror", "Mistério",
        "Romance", "Slice of Life", "Sobrenatural", "Suspense",
    )

    val DEFAULT_THEMES = listOf(
        "Amizade", "Artes Marciais", "Escola", "Espaço", "Gore", "Harém",
        "Histórico", "Isekai", "Magia", "Mecha", "Militares", "Mitologia",
        "Música", "Paródia", "Psicológico", "Reencarnação", "Samurai",
        "Sobrevivência", "Superpoder", "Vampiro", "Viagem no Tempo", "Videogame",
    )

    val DEFAULT_EXPLICITS = listOf("Ecchi", "H3nt4i")

    val DEFAULT_DEMOGRAPHICS = listOf(
        "Crianças",
        "Infanto-juvenil",
        "Josei",
        "Jovem Adulto",
        "Seinen",
        "Shoujo",
        "Shounen",
    )
}
