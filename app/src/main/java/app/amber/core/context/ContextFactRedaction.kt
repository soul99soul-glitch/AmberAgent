package app.amber.core.context

/**
 * Minimal credential-shape filter for facts that are copied verbatim into a compact handoff
 * (`must_not_lose`) and, from there, into the Room summary row.
 *
 * Scope is deliberately narrow: only shapes that are credentials by construction are masked, so
 * ordinary facts (paths, commands, numbers, decisions) survive untouched. A generic
 * "redact anything sensitive" pass would also eat `budget=180000ms` or a commit id, and those are
 * exactly the facts the next turn must still see.
 */
internal object ContextFactRedaction {
    private const val MASK = "******"

    /** Provider key prefixes that carry the secret in the token itself. */
    private val PREFIXED_KEY = Regex(
        """\b(sk-[A-Za-z0-9_\-*]{8,}|ghp_[A-Za-z0-9*]{16,}|gh[ousr]_[A-Za-z0-9*]{16,}|AKIA[0-9A-Z*]{12,}|xox[baprs]-[A-Za-z0-9\-*]{10,})"""
    )

    /** `Authorization: Bearer <token>` style values. */
    private val BEARER_TOKEN = Regex("""((?i:bearer)\s+)[A-Za-z0-9._\-+/=*]{12,}""")

    /** `password=…`, `api_key: …` style assignments. */
    private val KEY_VALUE_SECRET = Regex(
        """((?i:password|passwd|passphrase|api[\s_-]?key|access[\s_-]?token|refresh[\s_-]?token|client[\s_]?secret|authorization|secret[\s_-]?key)(?:\s*[:=]\s*|\s+))((?!--)[^\s"',;，；]{6,})"""
    )

    /** OTP / verification codes: only when a code keyword is adjacent, never bare digits. */
    private val SCOPED_OTP = Regex(
        """((?i:验证码|校验码|动态码|安全码|一次性密码|otp|2fa|verification\s+code|security\s+code|passcode)[^\d\n]{0,12}?)(\d{4,8})(?!\d)"""
    )

    /** Bare long hex blobs, unless the marker shows they are content hashes worth keeping. */
    private val LONG_HEX = Regex("""(?<!sha256:)(?<!sha1:)(?<!md5:)(?<!blake3:)(?<!blake2:)\b[0-9a-fA-F]{32,}\b""")

    /** Base64 / base64url blobs, requiring mixed case plus a digit to avoid matching identifiers. */
    private val LONG_BASE64 = Regex(
        """(?<![A-Za-z0-9+/_\-])(?=[A-Za-z0-9+/_\-]*[0-9])(?=[A-Za-z0-9+/_\-]*[A-Z])(?=[A-Za-z0-9+/_\-]*[a-z])[A-Za-z0-9+/_\-]{40,}={0,2}(?![A-Za-z0-9+/_\-=])"""
    )

    fun redact(text: String): String {
        if (text.isBlank()) return text
        var result = PREFIXED_KEY.replace(text) { match ->
            val token = match.value
            val marker = when {
                token.startsWith("sk-") -> "sk-"
                token.startsWith("gh") -> token.substringBefore('_').plus("_")
                token.startsWith("AKIA") -> "AKIA"
                else -> token.substringBefore('-').plus("-")
            }
            marker + MASK
        }
        result = BEARER_TOKEN.replace(result) { match -> match.groupValues[1] + MASK }
        result = KEY_VALUE_SECRET.replace(result) { match ->
            val value = match.groupValues[2]
            if (value.all { it == '*' } || looksLikePlaceholder(value)) match.value
            else match.groupValues[1] + MASK
        }
        result = SCOPED_OTP.replace(result) { match -> match.groupValues[1] + MASK }
        result = LONG_HEX.replace(result, MASK)
        result = LONG_BASE64.replace(result, MASK)
        return result
    }

    /** Keep already-masked values and obvious placeholders idempotent. */
    private fun looksLikePlaceholder(value: String): Boolean =
        value.startsWith("$") || value.startsWith("<") || value.startsWith("{{")
}
