package eu.kanade.tachiyomi.animeextension.pt.tomato

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Filters {
    private class Genre(name: String, val value: String) : AnimeFilter.CheckBox(name, false)

    private class GenresFilter :
        AnimeFilter.Group<Genre>(
            "Gêneros",
            GENRES.map { (name, value) -> Genre(name, value) },
        )

    val FILTER_LIST
        get() = AnimeFilterList(
            GenresFilter(),
        )

    internal fun selectedGenres(filters: AnimeFilterList): List<String> = filters
        .filterIsInstance<GenresFilter>()
        .firstOrNull()
        ?.state
        ?.filter(Genre::state)
        ?.map(Genre::value)
        .orEmpty()

    private val GENRES = arrayOf(
        "Ação" to "Ação",
        "Aventura" to "Aventura",
        "Comédia" to "Comédia",
        "Drama" to "Drama",
        "Dublado" to "Dublado",
        "Ecchi" to "Ecchi",
        "Escolar" to "Escolar",
        "Fantasia" to "Fantasia",
        "Romance" to "Romance",
        "Sci-Fi" to "Sci-Fi",
        "Slice Of Life" to "Slice Of Life",
        "Sobrenatural" to "Sobrenatural",
        "Shounen" to "Shounen",
    )
}
