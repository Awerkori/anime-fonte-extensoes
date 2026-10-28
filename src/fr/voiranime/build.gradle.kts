plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Voiranime"
    versionCode = 8
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "fr"
        baseUrl = "https://voir-anime.to"
    }
}

dependencies {
    implementation(project(":lib:universalextractor"))
}
