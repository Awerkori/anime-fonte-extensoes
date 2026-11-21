package eu.kanade.tachiyomi.animeextension.pt.redetoons

import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

// keiyoushi.utils.parseAs reads its Json from Injekt, and a plain JVM has none: register the one the app has,
// as the core's own tests do (NextJsTest).
fun registerTestJson() {
    Injekt.addSingleton(Json { ignoreUnknownKeys = true })
}
