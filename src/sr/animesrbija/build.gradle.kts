plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anime Srbija"
    versionCode = 31
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "sr"
        baseUrl = "https://www.animesrbija.com"
    }

    deeplink {
        path("/anime/..*")
    }
}

dependencies {
    implementation(project(":lib:filemoonextractor"))
}
