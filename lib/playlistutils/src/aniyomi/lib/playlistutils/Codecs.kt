package aniyomi.lib.playlistutils

/** Converts comma-separated RFC 6381 codec identifiers into readable stream labels. */
fun formatCodecs(codecs: String): String = codecs.split(',').map { codec ->
    when (codec.trim().substringBefore('.')) {
        "avc1", "avc3" -> "H.264"
        "hev1", "hvc1" -> "HEVC"
        "vp09", "vp9" -> "VP9"
        "av01" -> "AV1"
        "dvhe", "dvh1", "dvav", "dva1" -> "Dolby Vision"
        "mp4a" -> "AAC"
        "opus" -> "Opus"
        "vorbis" -> "Vorbis"
        "ac-3" -> "AC-3"
        "ec-3" -> "E-AC-3"
        "flac" -> "FLAC"
        "alac" -> "ALAC"
        else -> codec.trim()
    }
}.distinct().joinToString(" + ")
