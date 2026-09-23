package eu.kanade.tachiyomi.animeextension.pt.animefire.nativebridge

import java.io.File

internal object AnimeFireNative {
    private val loaded by lazy {
        val application = Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as android.app.Application
        val extensionContext = application.createPackageContext(
            "eu.kanade.tachiyomi.animeextension.pt.animefire",
            0,
        )
        System.load(File(extensionContext.applicationInfo.nativeLibraryDir, "libanimefire_ffmpeg.so").absolutePath)
        true
    }

    fun ensureLoaded() = check(loaded)

    external fun ffmpegVersion(): String

    external fun transmuxToMpegTs(init: ByteArray, fragment: ByteArray): ByteArray

    /** Decodes one AV1 fMP4 fragment and emits H.264/AAC MPEG-TS. */
    external fun transcodeAv1FragmentToMpegTs(init: ByteArray, fragment: ByteArray): ByteArray
}
