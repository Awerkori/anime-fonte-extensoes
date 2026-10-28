plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "BetterAnimeIo"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "dooplay"

    source {
        lang = "pt-BR"
        baseUrl = "https://betteranime.io"
    }
}
