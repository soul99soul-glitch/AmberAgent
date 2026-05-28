package app.amber.core.sync.core

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridge to the `sync-crypto` Rust crate (PBKDF2-HMAC-SHA256 + AES-256-GCM
 * + SHA-256 + HMAC-SHA256).
 *
 * Loaded lazily on first call. Failure to load (e.g. missing .so on host
 * JVM unit tests) is captured in [loadError]; [available] returns false and
 * the caller [SyncCryptoDispatcher] falls back to the javax.crypto path in
 * [SyncCrypto].
 *
 * ## Wire format compatibility
 *
 * The 5 native primitives produce bytes that are **byte-identical** to
 * javax.crypto outputs given the same inputs:
 *
 * - **PBKDF2**: standard RFC 8018 PBKDF2-HMAC-SHA256. Same passphrase /
 *   salt / iter / keyLen → same derived bytes across both paths. Verified
 *   by [SyncCryptoParityTest].
 * - **AES-256-GCM**: ring's `seal_in_place_append_tag` produces
 *   `ciphertext || tag` (16-byte tag at the end). javax.crypto's
 *   `Cipher.GCM` does the same — single concatenated buffer. The Kotlin
 *   adapter [SyncCrypto] reads/writes via `CipherInputStream`/
 *   `CipherOutputStream` which abstracts this; the bytes on disk are
 *   identical.
 * - **SHA-256 / HMAC-SHA256**: standard 32-byte digest / tag. Same bytes.
 *
 * No on-disk format change. Existing backup files produced by the JVM path
 * remain decryptable with the native path (and vice versa).
 *
 * ## Per ADR-0004 HARD GATE
 *
 * - feature flag: [NativePathPrefs.sampleRate] for gradual rollout +
 *   `NATIVE_PATH_SYNC_CRYPTO` per-component opt-out
 * - one-flip revert: `native_path_kill_switch` master flag (existing)
 * - panic safety: every JNI entry wraps work in `catch_unwind`; any panic
 *   logs to logcat (Crashlytics auto-collects) and returns null → JVM
 *   fallback kicks in.
 */
internal object SyncCryptoNative {

    private const val TAG = "SyncCryptoNative"
    private const val LIB_NAME = "sync_crypto"

    private val loaded = AtomicBoolean(false)
    @Volatile private var loadAttempted = false

    val available: Boolean
        get() {
            ensureLoaded()
            return loaded.get()
        }

    private fun ensureLoaded() {
        if (loaded.get() || loadAttempted) return
        synchronized(this) {
            if (loaded.get() || loadAttempted) return
            loadAttempted = true
            try {
                System.loadLibrary(LIB_NAME)
                loaded.set(true)
                Log.i(TAG, "loaded native library: $LIB_NAME")
            } catch (t: Throwable) {
                Log.w(TAG, "failed to load native library $LIB_NAME — will fall back to JVM", t)
            }
        }
    }

    /** PBKDF2-HMAC-SHA256. Returns null on JNI / panic / unavailable. */
    fun pbkdf2HmacSha256(
        passphrase: String,
        salt: ByteArray,
        iterations: Int,
        keySizeBytes: Int,
    ): ByteArray? {
        ensureLoaded()
        if (!loaded.get()) return null
        return pbkdf2HmacSha256Native(passphrase, salt, iterations, keySizeBytes)
    }

    /** AES-256-GCM encrypt. Returns ciphertext || 16B tag, or null on failure. */
    fun aesGcmEncrypt(plaintext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        ensureLoaded()
        if (!loaded.get()) return null
        return aesGcmEncryptNative(plaintext, key, iv)
    }

    /** AES-256-GCM decrypt + verify. Returns plaintext, or null on auth fail / panic. */
    fun aesGcmDecrypt(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        ensureLoaded()
        if (!loaded.get()) return null
        return aesGcmDecryptNative(ciphertext, key, iv)
    }

    /** SHA-256 → 32 raw bytes (caller hex-encodes if needed). */
    fun sha256(bytes: ByteArray): ByteArray? {
        ensureLoaded()
        if (!loaded.get()) return null
        return sha256Native(bytes)
    }

    /** HMAC-SHA256 → 32 raw bytes. */
    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray? {
        ensureLoaded()
        if (!loaded.get()) return null
        return hmacSha256Native(key, message)
    }

    @JvmStatic
    private external fun pbkdf2HmacSha256Native(
        passphrase: String,
        salt: ByteArray,
        iterations: Int,
        keySizeBytes: Int,
    ): ByteArray?

    @JvmStatic
    private external fun aesGcmEncryptNative(
        plaintext: ByteArray,
        key: ByteArray,
        iv: ByteArray,
    ): ByteArray?

    @JvmStatic
    private external fun aesGcmDecryptNative(
        ciphertext: ByteArray,
        key: ByteArray,
        iv: ByteArray,
    ): ByteArray?

    @JvmStatic
    private external fun sha256Native(bytes: ByteArray): ByteArray?

    @JvmStatic
    private external fun hmacSha256Native(key: ByteArray, message: ByteArray): ByteArray?
}
