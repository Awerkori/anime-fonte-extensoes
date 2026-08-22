# hCaptcha/AndroidX bring Kotlin runtime classes into the extension APK. These types
# are part of the public suspend ABI used by Aniyomi and Anikku and must retain the
# same descriptors as the host API after R8.
-keep class kotlin.** { *; }

# The host invokes both the suspend and legacy/Rx entry points reflectively/across
# its extension class loader. Keep their JVM names and descriptors in Release.
-keep class eu.kanade.tachiyomi.animeextension.pt.tomato.Tomato {
    public *;
    protected *;
}
