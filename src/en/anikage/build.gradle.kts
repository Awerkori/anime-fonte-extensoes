plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anikage"
    versionCode = 13
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "en"
        baseUrl = "https://anikage.cc"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
