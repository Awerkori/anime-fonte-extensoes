import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AniKuro"
    versionCode = 12
    contentWarning = ContentWarning.MIXED
    libVersion = "16"

    source {
        lang = "en"
        baseUrl {
            mirrors(
                "https://anikuro.to",
                "https://anikuro.ru",
            )
        }
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
