plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AniWave (Unoriginal)"
    versionCode = 7
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
    theme = "anikototheme"

    source {
        lang = "en"
        baseUrl {
            mirrors(
                "https://animewave.to",
                "https://aniwave.cz",
            )
        }
    }
}
