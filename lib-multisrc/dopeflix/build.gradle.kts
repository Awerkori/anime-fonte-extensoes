plugins {
    alias(kei.plugins.multisrc)
}

dependencies {
    api(project(":lib:dopeflixextractor"))
}

yuzono {
    baseVersionCode = 28
    libVersion = "14"
}
