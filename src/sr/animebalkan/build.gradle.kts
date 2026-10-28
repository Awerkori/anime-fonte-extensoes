plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeBalkan"
    versionCode = 16
    contentWarning = ContentWarning.SAFE
    libVersion = "14"
    theme = "animestream"

    source {
        lang = "sr"
        baseUrl = "https://animebalkan.org"
    }
}

dependencies {
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:googledriveextractor"))
}
