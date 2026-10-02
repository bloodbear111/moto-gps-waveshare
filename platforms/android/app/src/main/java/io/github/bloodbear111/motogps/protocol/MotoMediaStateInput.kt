package io.github.bloodbear111.motogps.protocol

/**
 * Media state for the round display's music page.
 *
 * Strings are UTF-8 byte arrays, like the snapshot's road name and instruction,
 * because the shared encoder measures **byte** lengths: the v1 budgets are 31
 * bytes for the source, 63 for the title and 47 for the artist, and one
 * over-long value makes the encoder reject the whole message - which on the
 * device looks like a music page that never receives anything. Callers must
 * truncate on a character boundary rather than let that happen.
 */
class MotoMediaStateInput {

    /** Bit set from [MotoMediaFlags]. */
    var flags: Int = 0

    /**
     * Association token for the current track, so the device can tell a repeat
     * of the same track from a new one without comparing the text.
     */
    var trackToken: Int = 0

    var positionS: Int = 0
    var durationS: Int = 0
    var sourceNameUtf8: ByteArray = ByteArray(0)
    var trackTitleUtf8: ByteArray = ByteArray(0)
    var artistNameUtf8: ByteArray = ByteArray(0)
}

/** `kKnownMediaFlags` in `shared/ble_protocol`. */
object MotoMediaFlags {
    const val CONNECTED = 1 shl 0
    const val PLAYING = 1 shl 1
    const val LIKE_AVAILABLE = 1 shl 2
    const val LIKED = 1 shl 3

    /** v1 byte budgets, mirrored from `ble_protocol.cpp`. */
    const val MAX_SOURCE_BYTES = 31
    const val MAX_TITLE_BYTES = 63
    const val MAX_ARTIST_BYTES = 47
}

/**
 * Truncates to a UTF-8 byte budget without splitting a character in half.
 *
 * A split multi-byte character is invalid UTF-8 and the device renders it as
 * garbage, so the value is cut on a character boundary at or below the budget.
 */
fun String.toUtf8Budgeted(maxBytes: Int): ByteArray {
    val bytes = toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return bytes
    var end = maxBytes
    // Back off while the byte at `end` is a continuation byte (10xxxxxx).
    while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end -= 1
    return bytes.copyOf(end)
}
