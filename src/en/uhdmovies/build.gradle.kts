plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "UHD Movies"
    versionCode = 24
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "en"
        baseUrl = "https://uhdmovies.vip"
    }
}
