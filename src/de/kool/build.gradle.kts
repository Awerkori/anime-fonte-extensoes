plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Kool"
    versionCode = 34
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "de"
        baseUrl = "https://www.kool.to"
    }
}

dependencies {
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:voeextractor"))
}
