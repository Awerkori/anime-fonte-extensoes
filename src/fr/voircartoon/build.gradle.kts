plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "VoirCartoon"
    versionCode = 21
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    theme = "dooplay"

    source {
        lang = "fr"
        baseUrl = "https://voircartoon.com"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
