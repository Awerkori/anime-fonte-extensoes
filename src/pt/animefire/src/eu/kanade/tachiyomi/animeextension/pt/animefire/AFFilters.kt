package eu.kanade.tachiyomi.animeextension.pt.animefire

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AFFilters {
    class Type : AnimeFilter.Select<String>("Catálogo", arrayOf("Animes", "Filmes"))
    class Audio : AnimeFilter.Select<String>("Áudio", arrayOf("Todos", "Dublado", "Legendado"))
    class Genre(genres: List<String>) : AnimeFilter.Select<String>("Gênero", (listOf("Todos") + genres).toTypedArray()) {
        val selected get() = if (state == 0) "" else values[state]
    }
    fun list(genres: List<String>) = AnimeFilterList(Type(), Audio(), Genre(genres))
}
