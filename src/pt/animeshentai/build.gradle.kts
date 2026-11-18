import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Animes Hentai"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "pt-BR"
        baseUrl = "https://animeshentai.biz"
    }

    deeplink {
        path("/hentai/..*")
        path("/episodio/..*")
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
}
