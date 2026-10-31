plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "UHD Movies"
    versionCode = 20
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "en"
        baseUrl = "https://uhdmovies.vip"
    }
}
