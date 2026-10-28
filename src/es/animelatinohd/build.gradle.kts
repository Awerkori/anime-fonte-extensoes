plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeLatinoHD"
    versionCode = 63
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "es"
        baseUrl = "https://www.animelatinohd.com"
    }
}

dependencies {
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:streamwishextractor"))
}
