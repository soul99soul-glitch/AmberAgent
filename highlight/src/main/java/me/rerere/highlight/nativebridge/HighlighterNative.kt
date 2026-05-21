package me.rerere.highlight.nativebridge

import android.util.Log
import me.rerere.highlight.HighlightToken
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridge to the `highlight-parser` Rust crate (tree-sitter based).
 *
 * Spike-phase contract: NOT wired into [me.rerere.highlight.Highlighter] —
 * benchmarks call directly. See SPIKE_PLAN §5.
 *
 * The native side returns a packed binary token stream; we decode to
 * [HighlightToken] so the existing Compose renderer ([HighlightText]) can
 * consume it unchanged.
 */
internal object HighlighterNative {

    private const val TAG = "HighlighterNative"
    private const val LIB_NAME = "highlight_parser"
    /** Wire-format version we know how to read. Bump when wire format changes. */
    private const val SUPPORTED_WIRE_VERSION = 1

    private val loaded = AtomicBoolean(false)
    @Volatile private var loadError: Throwable? = null

    /**
     * tree-sitter scope name → Prism CSS class name. Mirror of the table
     * documented in `native/highlight-parser/src/scope_mapping.rs`.
     *
     * Keep in lockstep with the `HIGHLIGHT_NAMES` array in
     * `native/highlight-parser/src/grammars.rs`.
     */
    private val SCOPE_TO_PRISM: Map<String, String> = mapOf(
        "attribute" to "attr-name",
        "comment" to "comment",
        "constant" to "constant",
        "constant.builtin" to "builtin",
        "constructor" to "class-name",
        "function" to "function",
        "function.builtin" to "builtin",
        "function.macro" to "macro",
        "function.method" to "method",
        "keyword" to "keyword",
        "label" to "symbol",
        "module" to "namespace",
        "number" to "number",
        "operator" to "operator",
        "property" to "property",
        "punctuation" to "punctuation",
        "punctuation.bracket" to "punctuation",
        "punctuation.delimiter" to "punctuation",
        "string" to "string",
        "string.special" to "string",
        "tag" to "tag",
        "type" to "class-name",
        "type.builtin" to "builtin",
        "variable" to "variable",
        "variable.builtin" to "builtin",
        "variable.parameter" to "parameter",
    )

    val available: Boolean
        get() {
            ensureLoaded()
            return loaded.get()
        }

    private fun ensureLoaded() {
        if (loaded.get() || loadError != null) return
        synchronized(this) {
            if (loaded.get() || loadError != null) return
            try {
                System.loadLibrary(LIB_NAME)
                loaded.set(true)
                Log.i(TAG, "loaded native library: $LIB_NAME")
            } catch (t: Throwable) {
                loadError = t
                Log.w(TAG, "failed to load native library $LIB_NAME — will fall back to JVM", t)
            }
        }
    }

    /**
     * Returns the list of language identifiers the native crate accepts, or
     * `null` if native is unavailable (P3 sweep — unifies `T?` sentinel
     * across all entry points).
     */
    fun supportedLanguages(): List<String>? {
        ensureLoaded()
        if (!loaded.get()) return null
        return supportedLanguagesNative().toList()
    }

    /**
     * Run highlight; returns a flat list of [HighlightToken]s that the existing
     * Compose renderer accepts. Returns null when native is unavailable so the
     * caller can fall back to JVM Prism+QuickJS.
     */
    fun highlight(code: String, language: String): List<HighlightToken>? {
        ensureLoaded()
        if (!loaded.get()) return null
        val blob = highlightNative(code, language)
        if (blob.size < 8) return null
        return decode(blob, code)
    }

    private fun decode(blob: ByteArray, originalCode: String): List<HighlightToken> {
        // Header: 'PHLT' + u8 ver + u8 flags + u16 reserved
        if (blob[0] != 'P'.code.toByte() || blob[1] != 'H'.code.toByte()
            || blob[2] != 'L'.code.toByte() || blob[3] != 'T'.code.toByte()
        ) return emptyList()
        // Reject wire-format versions we don't know how to read so a future
        // v2 .so with a Kotlin client on this version silently falls back
        // instead of mis-decoding (cross-component review P2 fix; mirrors the
        // PackedAstReader.isValid check in MarkdownParserNative).
        val rawVersion = blob[4].toInt() and 0xFF
        if (rawVersion != SUPPORTED_WIRE_VERSION) return emptyList()
        var cursor = 8

        // Cache the UTF-8 byte representation **once** per decode call so
        // per-token sub-string slicing doesn't re-encode the entire code
        // body (review P1 fix; was O(N×codeLen) before).
        val codeBytes = originalCode.toByteArray(Charsets.UTF_8)

        // Type pool: count + [length, utf8 bytes] × N
        val (typeCount, c1) = readVarint(blob, cursor); cursor = c1
        val typePool = ArrayList<String>(typeCount.toInt())
        repeat(typeCount.toInt()) {
            val (len, c2) = readVarint(blob, cursor); cursor = c2
            val s = String(blob, cursor, len.toInt(), Charsets.UTF_8)
            cursor += len.toInt()
            typePool.add(s)
        }

        // Tokens
        val (tokenCount, c3) = readVarint(blob, cursor); cursor = c3
        val tokens = ArrayList<HighlightToken>(tokenCount.toInt())

        repeat(tokenCount.toInt()) {
            val kind = blob[cursor].toInt() and 0xFF; cursor++
            val (start, c4) = readVarint(blob, cursor); cursor = c4
            val (length, c5) = readVarint(blob, cursor); cursor = c5

            when (kind) {
                0 -> {
                    val content = sliceUtf8(codeBytes, start.toInt(), length.toInt())
                    tokens.add(HighlightToken.Plain(content))
                }
                1 -> {
                    val (typeRef, c6) = readVarint(blob, cursor); cursor = c6
                    val scope = typePool.getOrNull(typeRef.toInt()) ?: ""
                    val prismClass = SCOPE_TO_PRISM[scope] ?: scope
                    val content = sliceUtf8(codeBytes, start.toInt(), length.toInt())
                    tokens.add(
                        HighlightToken.Token.StringContent(
                            content = content,
                            type = prismClass,
                            length = length.toInt(),
                        )
                    )
                }
                else -> {
                    // Unknown kind — emit content as plain to fail safe.
                    val content = sliceUtf8(codeBytes, start.toInt(), length.toInt())
                    tokens.add(HighlightToken.Plain(content))
                }
            }
        }
        return tokens
    }

    /** Slice a pre-encoded UTF-8 byte array. Byte offsets come straight from
     *  tree-sitter, so no char-index conversion needed. */
    private fun sliceUtf8(bytes: ByteArray, byteStart: Int, byteLen: Int): String {
        if (byteStart < 0 || byteLen < 0 || byteStart + byteLen > bytes.size) {
            return ""
        }
        return String(bytes, byteStart, byteLen, Charsets.UTF_8)
    }

    private fun readVarint(blob: ByteArray, start: Int): Pair<Long, Int> {
        var value = 0L
        var shift = 0
        var cursor = start
        while (true) {
            val byte = blob[cursor].toInt() and 0xFF
            value = value or ((byte and 0x7F).toLong() shl shift)
            cursor++
            if (byte and 0x80 == 0) return value to cursor
            shift += 7
            if (shift > 63) error("varint too long at offset $start")
        }
    }

    @JvmStatic
    private external fun highlightNative(code: String, language: String): ByteArray

    @JvmStatic
    private external fun supportedLanguagesNative(): Array<String>
}
