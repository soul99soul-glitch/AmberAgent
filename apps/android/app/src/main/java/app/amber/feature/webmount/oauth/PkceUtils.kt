package app.amber.feature.webmount.oauth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Tiny PKCE helper. RFC 7636: code_verifier is 43-128 chars of unreserved
 * URL-safe characters, code_challenge = BASE64URL(SHA256(code_verifier)),
 * code_challenge_method = "S256".
 *
 * 32 random bytes → 43-char base64url verifier.
 */
object PkceUtils {

    fun generateCodeVerifier(byteLength: Int = 32): String {
        val bytes = ByteArray(byteLength)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, BASE64URL_FLAGS)
    }

    fun s256Challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, BASE64URL_FLAGS)
    }

    fun randomState(): String = generateCodeVerifier(byteLength = 16)

    private const val BASE64URL_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
}
