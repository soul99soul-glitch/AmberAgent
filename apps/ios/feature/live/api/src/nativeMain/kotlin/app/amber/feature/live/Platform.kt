package app.amber.feature.live

internal actual fun sha256Hex(data: ByteArray): String {
    // FNV-1a hash for change detection (not cryptographic)
    var hash = 0xcbf29ce484222325UL
    for (byte in data) {
        hash = hash xor (byte.toULong() and 0xFFUL)
        hash *= 0x100000001b3UL
    }
    return hash.toString(16).padStart(16, '0').take(16)
}
