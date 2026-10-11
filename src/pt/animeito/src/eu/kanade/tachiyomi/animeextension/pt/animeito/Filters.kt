package eu.kanade.tachiyomi.animeextension.pt.animeito

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

open class UriPartFilter(
    displayName: String,
    private val vals: Array<Pair<String, String>>,
    defaultValue: Int = 0,
) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), defaultValue) {
    fun toUriPart() = vals[state].second
}

class OrderFilter :
    UriPartFilter(
        "Ordem",
        arrayOf(
            Pair("A–Z", ""),
            Pair("Z–A", "titlereverse"),
            Pair("Último episódio publicado", "episode"),
            Pair("Última Atualização", "update"),
            Pair("Mais Recentes", "latest"),
            Pair("Popular", "popular"),
            Pair("Avaliação", "rating"),
        ),
        5,
    )

class StatusFilter :
    UriPartFilter(
        "Status",
        arrayOf(
            Pair("Todos", ""),
            Pair("Lançamento", "ongoing"),
            Pair("Completo", "completed"),
            Pair("Em Breve", "upcoming"),
            Pair("Incompleto", "incomplete"),
            Pair("Hiato", "hiatus"),
        ),
    )

class TypeFilter :
    UriPartFilter(
        "Tipo",
        arrayOf(
            Pair("Todos", ""),
            Pair("Série de TV", "tv"),
            Pair("OVA", "ova"),
            Pair("Filme", "movie"),
            Pair("Live Action", "live action"),
            Pair("Especial", "special"),
            Pair("BD", "bd"),
            Pair("ONA", "ona"),
            Pair("Música", "music"),
        ),
    )

class SubFilter :
    UriPartFilter(
        "Idioma / Legenda",
        arrayOf(
            Pair("Todos", ""),
            Pair("Legendado", "sub"),
            Pair("Dublado", "dub"),
            Pair("RAW", "raw"),
        ),
    )

class Genre(name: String, val value: String) : AnimeFilter.CheckBox(name)

class GenresFilter(genres: List<Genre>) : AnimeFilter.Group<Genre>("Gêneros", genres)

val GENRES_LIST = listOf(
    Genre("+18", "18"),
    Genre("Ação", "action"),
    Genre("Ambiente de Trabalho", "workplace"),
    Genre("Animação", "animacao"),
    Genre("Antropomórfico", "anthropomorphic"),
    Genre("Artes Cênicas", "performing-arts"),
    Genre("Artes Marciais", "martial-arts"),
    Genre("Aventura", "adventure"),
    Genre("Boys Love", "boys-love"),
    Genre("CGDCT (Garotas Fofas Fazendo Coisas Fofas)", "cgdct"),
    Genre("Comédia", "comedy"),
    Genre("Corrida", "racing"),
    Genre("Crime Organizado", "organized-crime"),
    Genre("Crossdressing", "crossdressing"),
    Genre("Cuidado Infantil", "childcare"),
    Genre("Cultura Otaku", "otaku-culture"),
    Genre("Delinquentes", "delinquents"),
    Genre("Detetive", "detective"),
    Genre("Drama", "drama"),
    Genre("Ecchi", "ecchi"),
    Genre("Educational", "educational"),
    Genre("Elenco adulto", "adult-cast"),
    Genre("Erotica", "erotica"),
    Genre("Escolar", "school"),
    Genre("Espacial", "space"),
    Genre("Esportes", "sports"),
    Genre("Esportes de Combate", "combat-sports"),
    Genre("Esportes em Equipe", "team-sports"),
    Genre("Fantasia", "fantasy"),
    Genre("Fantasia Urbana", "urban-fantasy"),
    Genre("Girls Love", "girls-love"),
    Genre("Gore", "gore"),
    Genre("Gourmet", "gourmet"),
    Genre("Harem", "harem"),
    Genre("Harem Reverso", "harem-reverso"),
    Genre("Histórico", "historical"),
    Genre("Humor Pastelão", "gag-humor"),
    Genre("Idols (Female)", "idols-female"),
    Genre("Idols (Male)", "idols-male"),
    Genre("Infantil", "kids"),
    Genre("Isekai", "isekai"),
    Genre("Iyashikei", "iyashikei"),
    Genre("Jogo de Alto Risco", "high-stakes-game"),
    Genre("Jogo de Estratégia", "strategy-game"),
    Genre("Josei", "josei"),
    Genre("Mahou Shoujo", "mahou-shoujo"),
    Genre("Mecha", "mecha"),
    Genre("Médico", "medical"),
    Genre("Militar", "military"),
    Genre("Mistério", "mystery"),
    Genre("Mitologia", "mythology"),
    Genre("Música", "music"),
    Genre("Paródia", "parody"),
    Genre("Pets", "pets"),
    Genre("Polígono Amoroso", "love-polygon"),
    Genre("Premiado", "award-winning"),
    Genre("Psicológico", "psychological"),
    Genre("Reencarnação", "reincarnation"),
    Genre("Relacionamento Congelado", "love-status-quo"),
    Genre("Reverse Harem", "reverse-harem"),
    Genre("Romance", "romance"),
    Genre("Samurai", "samurai"),
    Genre("Sci-Fi (Ficção Científica)", "sci-fi"),
    Genre("Sci-Fi & Fantasy", "sci-fi-fantasy"),
    Genre("Seinen", "seinen"),
    Genre("Shoujo", "shoujo"),
    Genre("Shounen", "shounen"),
    Genre("Showbiz", "showbiz"),
    Genre("Slice of Life", "slice-of-life"),
    Genre("Sobrenatural", "supernatural"),
    Genre("Sobrevivência", "survival"),
    Genre("Superpoderes", "super-power"),
    Genre("Suspense", "suspense"),
    Genre("Terror", "horror"),
    Genre("Trocar Mágica de Sexo", "magical-sex-shift"),
    Genre("Vampiro", "vampire"),
    Genre("Vanguarda", "avant-garde"),
    Genre("Viagem no Tempo", "time-travel"),
    Genre("Video Game", "video-game"),
    Genre("Vilã", "villainess"),
    Genre("Visual Arts", "visual-arts"),
)
