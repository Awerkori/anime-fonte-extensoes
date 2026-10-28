plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "UAKino"
    versionCode = 11
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "uk"
        baseUrl = "https://uakino.best"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
