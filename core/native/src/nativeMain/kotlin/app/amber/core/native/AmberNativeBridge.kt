@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package app.amber.core.native

import app.amber.core.native.ffi.*
import kotlinx.cinterop.*
import platform.posix.*

actual object AmberNativeBridge {

    // ── JSON Expression ─────────────────────────────────────────────────────

    actual fun jsonExprEvaluate(rootJson: String, expr: String): String? {
        val result = amber_json_expr_evaluate(rootJson, expr)
        return readAndFreeString(result)
    }

    actual fun jsonExprIsValid(expr: String): Boolean {
        return amber_json_expr_is_valid(expr) != 0
    }

    // ── Regex Transformer ───────────────────────────────────────────────────

    actual fun regexApply(
        input: String,
        findPatterns: Array<String>,
        replacements: Array<String>,
    ): String? = memScoped {
        if (findPatterns.size != replacements.size) return@memScoped null
        if (findPatterns.isEmpty()) return@memScoped input

        // Build null-terminated C string arrays
        val cFinds = allocArray<CPointerVar<ByteVar>>(findPatterns.size + 1)
        val cReps = allocArray<CPointerVar<ByteVar>>(replacements.size + 1)
        findPatterns.forEachIndexed { i, s -> cFinds[i] = s.cstr.ptr }
        cFinds[findPatterns.size] = null
        replacements.forEachIndexed { i, s -> cReps[i] = s.cstr.ptr }
        cReps[replacements.size] = null

        val result = amber_regex_apply(input, cFinds, cReps)
        readAndFreeString(result)
    }

    // ── Sync Crypto ─────────────────────────────────────────────────────────

    actual fun pbkdf2HmacSha256(
        passphrase: String,
        salt: ByteArray,
        iterations: Int,
        keySizeBytes: Int,
    ): ByteArray? = memScoped {
        val outLen = alloc<ULongVar>()
        val result = salt.usePinned { pinnedSalt ->
            amber_pbkdf2_hmac_sha256(
                passphrase,
                if (salt.isEmpty()) null else pinnedSalt.addressOf(0).reinterpret(),
                salt.size.toULong(),
                iterations.toUInt(),
                keySizeBytes.toULong(),
                outLen.ptr,
            )
        }
        readAndFreeBytes(result, outLen.value)
    }

    actual fun aesGcmEncrypt(plaintext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? =
        memScoped {
            val outLen = alloc<ULongVar>()
            val result = plaintext.usePinned { ptPin ->
                key.usePinned { keyPin ->
                    iv.usePinned { ivPin ->
                        amber_aes_gcm_encrypt(
                            if (plaintext.isEmpty()) null else ptPin.addressOf(0).reinterpret(),
                            plaintext.size.toULong(),
                            if (key.isEmpty()) null else keyPin.addressOf(0).reinterpret(),
                            key.size.toULong(),
                            if (iv.isEmpty()) null else ivPin.addressOf(0).reinterpret(),
                            iv.size.toULong(),
                            outLen.ptr,
                        )
                    }
                }
            }
            readAndFreeBytes(result, outLen.value)
        }

    actual fun aesGcmDecrypt(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? =
        memScoped {
            val outLen = alloc<ULongVar>()
            val result = ciphertext.usePinned { ctPin ->
                key.usePinned { keyPin ->
                    iv.usePinned { ivPin ->
                        amber_aes_gcm_decrypt(
                            if (ciphertext.isEmpty()) null else ctPin.addressOf(0).reinterpret(),
                            ciphertext.size.toULong(),
                            if (key.isEmpty()) null else keyPin.addressOf(0).reinterpret(),
                            key.size.toULong(),
                            if (iv.isEmpty()) null else ivPin.addressOf(0).reinterpret(),
                            iv.size.toULong(),
                            outLen.ptr,
                        )
                    }
                }
            }
            readAndFreeBytes(result, outLen.value)
        }

    actual fun sha256(data: ByteArray): ByteArray? = memScoped {
        val outLen = alloc<ULongVar>()
        val result = data.usePinned { pinned ->
            amber_sha256(
                if (data.isEmpty()) null else pinned.addressOf(0).reinterpret(),
                data.size.toULong(),
                outLen.ptr,
            )
        }
        readAndFreeBytes(result, outLen.value)
    }

    actual fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray? = memScoped {
        val outLen = alloc<ULongVar>()
        val result = key.usePinned { keyPin ->
            message.usePinned { msgPin ->
                amber_hmac_sha256(
                    if (key.isEmpty()) null else keyPin.addressOf(0).reinterpret(),
                    key.size.toULong(),
                    if (message.isEmpty()) null else msgPin.addressOf(0).reinterpret(),
                    message.size.toULong(),
                    outLen.ptr,
                )
            }
        }
        readAndFreeBytes(result, outLen.value)
    }

    // ── Tokenizer ───────────────────────────────────────────────────────────

    actual fun tokenizerCount(tokenizerId: String, text: String): Int {
        val result = amber_tokenizer_count(tokenizerId, text)
        // amber_tokenizer_count returns int64_t → Long; -1 on error
        return if (result >= 0) result.toInt() else -1
    }

    // ── Markdown ────────────────────────────────────────────────────────────

    actual fun markdownToHtml(text: String): String? {
        return readAndFreeString(amber_markdown_to_html(text))
    }

    actual fun markdownPreprocess(input: String): String? {
        return readAndFreeString(amber_markdown_preprocess(input))
    }

    // ── Highlight ───────────────────────────────────────────────────────────

    actual fun highlightSupportedLanguages(): String? {
        return readAndFreeString(amber_highlight_supported_languages())
    }

    // ── HTML Diff Normalizer ────────────────────────────────────────────────

    actual fun htmlDiffNormalize(html: String): String? {
        return readAndFreeString(amber_html_diff_normalize(html))
    }

    // ── Reader Extractor ────────────────────────────────────────────────────

    actual fun readerExtract(html: String, baseUrl: String): String? {
        return readAndFreeString(amber_reader_extract(html, baseUrl))
    }

    // ── Office Parsers ──────────────────────────────────────────────────────

    actual fun parseDocx(path: String): String? {
        return readAndFreeString(amber_parse_docx(path))
    }

    actual fun parsePptx(path: String): String? {
        return readAndFreeString(amber_parse_pptx(path))
    }

    actual fun parseEpub(path: String): String? {
        return readAndFreeString(amber_parse_epub(path))
    }

    actual fun parseXlsx(path: String): String? {
        return readAndFreeString(amber_parse_xlsx(path))
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private fun readAndFreeString(ptr: CPointer<ByteVar>?): String? {
        if (ptr == null) return null
        val str = ptr.toKString()
        amber_free_string(ptr)
        return str
    }

    private fun readAndFreeBytes(ptr: CPointer<UByteVar>?, len: ULong): ByteArray? {
        if (ptr == null || len == 0UL) return null
        val bytes = ptr.readBytes(len.toInt())
        amber_free_bytes(ptr, len)
        return bytes
    }
}
