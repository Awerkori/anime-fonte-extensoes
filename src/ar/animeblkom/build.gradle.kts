plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anime Blkom"
    versionCode = 37
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        name = "أنمي بالكوم"
        lang = "ar"
        baseUrl {
            mirrors(
                "https://animeblkom.net",
                "https://animeblkom.tv",
                "https://blkom.com",
            )
        }
    }
}

dependencies {
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:okruextractor"))
}
