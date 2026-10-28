plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeGO.studio"
    versionCode = 6
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "ru"
        baseUrl = "https://animego.studio"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
