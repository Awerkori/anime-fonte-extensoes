package eu.kanade.tachiyomi.animeextension.pt.animy

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

class SortFilter :
    AnimeFilter.Select<String>(
        "Ordenar por",
        SORT_LIST.map { it.first }.toTypedArray(),
    ) {
    val selected get() = SORT_LIST[state].second

    companion object {
        private val SORT_LIST = arrayOf(
            Pair("Mais Populares", "popular"),
            Pair("Mais Recentes", "newest"),
            Pair("Melhor Nota", "score"),
            Pair("Mais Favoritados", "favorites"),
        )
    }
}

class KindFilter :
    AnimeFilter.Select<String>(
        "Tipo",
        KIND_LIST.map { it.first }.toTypedArray(),
    ) {
    val selected get() = KIND_LIST[state].second

    companion object {
        private val KIND_LIST = arrayOf(
            Pair("Todos", ""),
            Pair("Série (TV)", "tv"),
            Pair("Filme", "movie"),
            Pair("ONA", "ona"),
        )
    }
}

class AudioFilter :
    AnimeFilter.Select<String>(
        "Áudio",
        AUDIO_LIST.map { it.first }.toTypedArray(),
    ) {
    val selected get() = AUDIO_LIST[state].second

    companion object {
        private val AUDIO_LIST = arrayOf(
            Pair("Todos", ""),
            Pair("Legendado", "legendado"),
            Pair("Dublado", "dublado"),
        )
    }
}

class StatusFilter :
    AnimeFilter.Select<String>(
        "Status",
        STATUS_LIST.map { it.first }.toTypedArray(),
    ) {
    val selected get() = STATUS_LIST[state].second

    companion object {
        private val STATUS_LIST = arrayOf(
            Pair("Todos", ""),
            Pair("Em Lançamento", "airing"),
            Pair("Completo", "completed"),
        )
    }
}

class SeasonFilter :
    AnimeFilter.Select<String>(
        "Temporada",
        SEASON_LIST.map { it.first }.toTypedArray(),
    ) {
    val selected get() = SEASON_LIST[state].second

    companion object {
        private val SEASON_LIST = arrayOf(
            Pair("Todas", ""),
            Pair("Inverno", "inverno"),
            Pair("Primavera", "primavera"),
            Pair("Verão", "verão"),
            Pair("Outono", "outono"),
        )
    }
}

class GenreFilter :
    AnimeFilter.Select<String>(
        "Gênero",
        GENRE_LIST.map { it.first }.toTypedArray(),
    ) {
    val selected get() = GENRE_LIST[state].second

    companion object {
        private val GENRE_LIST = arrayOf(
            Pair("Todos", ""),
            Pair("Animação", "Animação"),
            Pair("Artes Marciais", "Artes Marciais"),
            Pair("Aventura", "Aventura"),
            Pair("Ação", "Ação"),
            Pair("Comédia", "Comédia"),
            Pair("Crime", "Crime"),
            Pair("Demônios", "Demônios"),
            Pair("Drama", "Drama"),
            Pair("Dublado", "Dublado"),
            Pair("Ecchi", "Ecchi"),
            Pair("Escolar", "Escolar"),
            Pair("Espaço", "Espaço"),
            Pair("Esporte", "Esporte"),
            Pair("Família", "Família"),
            Pair("Fantasia", "Fantasia"),
            Pair("Gore", "Gore"),
            Pair("Guerra", "Guerra"),
            Pair("Harém", "Harém"),
            Pair("História", "História"),
            Pair("Horror", "Horror"),
            Pair("Infantíl", "Infantíl"),
            Pair("Jogos", "Jogos"),
            Pair("Magia", "Magia"),
            Pair("Mecha", "Mecha"),
            Pair("Militar", "Militar"),
            Pair("Mistério", "Mistério"),
            Pair("Musical", "Musical"),
            Pair("Policial", "Policial"),
            Pair("Psicológico", "Psicológico"),
            Pair("Pós-Apocalíptico", "Pós-Apocalíptico"),
            Pair("Romance", "Romance"),
            Pair("Sci-Fi", "Sci-Fi"),
            Pair("Seinen", "Seinen"),
            Pair("Shoujo", "Shoujo"),
            Pair("Shōnen", "Shōnen"),
            Pair("Slice of Life", "Slice of Life"),
            Pair("Sobrenatural", "Sobrenatural"),
            Pair("Studio Ghibli", "Studio Ghibli"),
            Pair("Super Poder", "Super Poder"),
            Pair("Suspense", "Suspense"),
            Pair("Terror", "Terror"),
            Pair("Thriller", "Thriller"),
            Pair("Tragédia", "Tragédia"),
            Pair("Vampiros", "Vampiros"),
            Pair("Yaoi", "Yaoi"),
            Pair("Yuri", "Yuri"),
            Pair("isekai", "isekai"),
        )
    }
}
