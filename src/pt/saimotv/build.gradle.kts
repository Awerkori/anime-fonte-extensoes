import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Saimo TV"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "pt-BR"
        baseUrl = "https://saimotv.com"
    }

    deeplink {
        path("/filme/..*")
        path("/serie/..*")
        path("/animes/..*")
        path("/doramas/..*")
    }
}
