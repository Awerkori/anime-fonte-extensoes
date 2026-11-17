import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Watch Anime Hentai"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "pt-BR"
        baseUrl = "https://www.watchanimehentai.com"
    }

    deeplink {
        host("www.watchanimehentai.com")
        host("watchanimehentai.com")
        path("/pt/info/..*")
        path("/pt/episodios/..*")
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
}
