plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "SushiAnimes"
    versionCode = 5
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        name = "Sushi Animes"
        lang = "pt-BR"
        baseUrl = "https://sushianimes.com.br"
    }

    deeplink {
        path("/anime/..*")
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
}
