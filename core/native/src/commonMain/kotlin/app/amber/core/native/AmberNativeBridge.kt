package app.amber.core.native

/**
 * Platform bridge to the Amber Rust native layer (amber-ffi).
 *
 * On Android/JVM: delegates to existing JNI bridges (stubs for now —
 * the Android side keeps its own JNI wiring).
 *
 * On iOS: calls C-ABI functions via Kotlin/Native cinterop.
 */
expect object AmberNativeBridge {

    // ── JSON Expression ─────────────────────────────────────────────────────

    /** Evaluate a JSON expression. Returns null on error. */
    fun jsonExprEvaluate(rootJson: String, expr: String): String?

    /** Check if a JSON expression is syntactically valid. */
    fun jsonExprIsValid(expr: String): Boolean

    // ── Regex Transformer ───────────────────────────────────────────────────

    /**
     * Apply a pipeline of regex find-replace rules.
     * [findPatterns] and [replacements] must have the same length.
     * Returns null on error.
     */
    fun regexApply(input: String, findPatterns: Array<String>, replacements: Array<String>): String?

    // ── Sync Crypto ─────────────────────────────────────────────────────────

    /** PBKDF2-HMAC-SHA256 key derivation. Returns null on error. */
    fun pbkdf2HmacSha256(
        passphrase: String,
        salt: ByteArray,
        iterations: Int,
        keySizeBytes: Int,
    ): ByteArray?

    /** AES-256-GCM encrypt. Returns ciphertext || 16-byte tag, or null on error. */
    fun aesGcmEncrypt(plaintext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray?

    /** AES-256-GCM decrypt. Returns plaintext, or null on auth failure / error. */
    fun aesGcmDecrypt(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray?

    /** SHA-256 hash. Returns 32 bytes, or null on error. */
    fun sha256(data: ByteArray): ByteArray?

    /** HMAC-SHA256. Returns 32 bytes, or null on error. */
    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray?

    // ── Tokenizer ───────────────────────────────────────────────────────────

    /** Count tokens. Returns -1 on error. */
    fun tokenizerCount(tokenizerId: String, text: String): Int

    // ── Markdown ────────────────────────────────────────────────────────────

    /** Parse markdown to HTML. Returns null on error. */
    fun markdownToHtml(text: String): String?

    /** Preprocess markdown (LaTeX, linkify). Returns null on error. */
    fun markdownPreprocess(input: String): String?

    // ── Highlight ───────────────────────────────────────────────────────────

    /** Get supported highlight languages as newline-separated string. */
    fun highlightSupportedLanguages(): String?

    // ── HTML Diff Normalizer ────────────────────────────────────────────────

    /** Normalize HTML for diff comparison. Returns null on error. */
    fun htmlDiffNormalize(html: String): String?

    // ── Reader Extractor ────────────────────────────────────────────────────

    /** Extract readable article from HTML. Returns JSON string or null on error. */
    fun readerExtract(html: String, baseUrl: String): String?

    // ── Office Parsers ──────────────────────────────────────────────────────

    /** Parse DOCX file to markdown. Returns null on error. */
    fun parseDocx(path: String): String?

    /** Parse PPTX file to markdown. Returns null on error. */
    fun parsePptx(path: String): String?

    /** Parse EPUB file to markdown. Returns null on error. */
    fun parseEpub(path: String): String?

    /** Parse XLSX file to markdown. Returns null on error. */
    fun parseXlsx(path: String): String?
}
