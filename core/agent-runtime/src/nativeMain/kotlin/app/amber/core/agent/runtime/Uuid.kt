package app.amber.core.agent.runtime

import kotlin.random.Random

internal actual fun generateUuidString(): String {
    val bytes = Random.nextBytes(16)
    // Set version 4 bits
    bytes[6] = (bytes[6].toInt() and 0x0F or 0x40).toByte()
    // Set variant 2 bits
    bytes[8] = (bytes[8].toInt() and 0x3F or 0x80).toByte()
    val hex = buildString {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(HEX_CHARS[v shr 4])
            append(HEX_CHARS[v and 0x0F])
        }
    }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}

private const val HEX_CHARS = "0123456789abcdef"
