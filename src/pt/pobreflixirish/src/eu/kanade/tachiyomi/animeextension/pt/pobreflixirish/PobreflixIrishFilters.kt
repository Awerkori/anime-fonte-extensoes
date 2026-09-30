package eu.kanade.tachiyomi.animeextension.pt.pobreflixirish

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object PobreflixIrishFilters {

    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
        defaultValue: Int = 0,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
        defaultValue,
    ) {
        fun toUriPart() = vals[state].second
    }

    class TypeFilter : UriPartFilter("Tipo de Conteúdo", TYPE_LIST)
    class GenreFilter : UriPartFilter("Gênero / Categoria", GENRE_LIST)
    class YearFilter : UriPartFilter("Ano de Lançamento", YEAR_LIST)

    fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filtros de Catálogo (ignorados em busca com texto)"),
        TypeFilter(),
        GenreFilter(),
        YearFilter(),
    )

    private val TYPE_LIST = arrayOf(
        Pair("Todos", ""),
        Pair("Filmes", "filmes"),
        Pair("Séries", "series"),
    )

    private val GENRE_LIST = arrayOf(
        Pair("Todos", ""),
        Pair("Ação", "filmes-de-acao-1"),
        Pair("Action & Adventure", "action-adventure"),
        Pair("Animação", "animacao"),
        Pair("Aventura", "aventura"),
        Pair("Cinema TV", "cinema-tv"),
        Pair("Comédia", "comedia"),
        Pair("Crime", "crime"),
        Pair("Documentário", "documentario"),
        Pair("Drama", "drama"),
        Pair("Família", "familia"),
        Pair("Fantasia", "fantasia"),
        Pair("Faroeste", "faroeste"),
        Pair("Ficção Científica", "ficcao-cientifica"),
        Pair("Guerra", "guerra"),
        Pair("História", "historia"),
        Pair("Kids", "kids"),
        Pair("Mistério", "misterio"),
        Pair("Música", "musica"),
        Pair("Reality", "reality"),
        Pair("Romance", "romance"),
        Pair("Sci-Fi & Fantasy", "sci-fi-fantasy"),
        Pair("Soap", "soap"),
        Pair("Terror", "terror"),
        Pair("Thriller", "thriller"),
        Pair("War & Politics", "war-politics"),
    )

    private val YEAR_LIST = arrayOf(
        Pair("Todos", ""),
        Pair("2026", "2026"),
        Pair("2025", "2025"),
        Pair("2024", "2024"),
        Pair("2023", "2023"),
        Pair("2022", "2022"),
        Pair("2021", "2021"),
        Pair("2020", "2020"),
        Pair("2019", "2019"),
    )
}
