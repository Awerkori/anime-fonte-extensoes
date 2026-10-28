plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Meus Animes"
    versionCode = 8
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        lang = "pt-BR"
        baseUrl = "https://meusanimes.vip"
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
}
