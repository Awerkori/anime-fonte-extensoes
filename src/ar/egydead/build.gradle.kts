plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Egy Dead"
    versionCode = 36
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "ar"
        baseUrl = "https://egydead.space"
    }
}

dependencies {
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:streamwishextractor"))
}
