package app.amber.core.native

/**
 * JVM/Android actual for [AmberNativeBridge].
 *
 * All functions return stub values. The Android app uses its own JNI bridge
 * objects (SyncCryptoNative, RegexTransformerNative, etc.) directly from
 * the `:app` module. This actual exists solely so the KMP module compiles
 * on JVM. When the Android-side consumers migrate to depend on this module,
 * these stubs will delegate to the existing JNI bridges.
 */
actual object AmberNativeBridge {

    actual fun jsonExprEvaluate(rootJson: String, expr: String): String? = null

    actual fun jsonExprIsValid(expr: String): Boolean = false

    actual fun regexApply(
        input: String,
        findPatterns: Array<String>,
        replacements: Array<String>,
    ): String? = null

    actual fun pbkdf2HmacSha256(
        passphrase: String,
        salt: ByteArray,
        iterations: Int,
        keySizeBytes: Int,
    ): ByteArray? = null

    actual fun aesGcmEncrypt(plaintext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? = null

    actual fun aesGcmDecrypt(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? = null

    actual fun sha256(data: ByteArray): ByteArray? = null

    actual fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray? = null

    actual fun tokenizerCount(tokenizerId: String, text: String): Int = -1

    actual fun markdownToHtml(text: String): String? = null

    actual fun markdownPreprocess(input: String): String? = null

    actual fun highlightSupportedLanguages(): String? = null

    actual fun htmlDiffNormalize(html: String): String? = null

    actual fun readerExtract(html: String, baseUrl: String): String? = null

    actual fun parseDocx(path: String): String? = null

    actual fun parsePptx(path: String): String? = null

    actual fun parseEpub(path: String): String? = null

    actual fun parseXlsx(path: String): String? = null
}
