plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "HentaiMama"
    versionCode = 13
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://hentaimama.io"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
