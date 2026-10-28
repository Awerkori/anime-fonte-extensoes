plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Samehadaku"
    versionCode = 13
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "id"
        baseUrl = "https://v2.samehadaku.how"
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
}
