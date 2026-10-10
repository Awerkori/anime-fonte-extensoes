import keiyoushi.gradle.extensions.baseVersionCode

plugins {
    alias(kei.plugins.multisrc)
}

baseVersionCode = 28

dependencies {
    api(project(":lib:dopeflixextractor"))
}
