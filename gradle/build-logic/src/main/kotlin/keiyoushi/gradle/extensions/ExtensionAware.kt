package keiyoushi.gradle.extensions

import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.extra

var ExtensionAware.baseVersionCode: Int
    get() = extensions.findByType(KeiyoushiMultisrcExtension::class.java)?.baseVersionCode?.orNull
        ?: (extra.get("baseVersionCode") as Int)
    set(value) {
        extra.set("baseVersionCode", value)
        extensions.findByType(KeiyoushiMultisrcExtension::class.java)?.baseVersionCode?.set(value)
    }
