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
    const val COMPONENT_NAME: String = "sync_crypto"

    interface Config {
        fun enabled(): Boolean
        fun onLoadFailure(error: Throwable)
        fun onNativePanic(stage: String, error: Throwable?)
    }

    object DisabledConfig : Config {
        override fun enabled(): Boolean = false
        override fun onLoadFailure(error: Throwable) {}
        override fun onNativePanic(stage: String, error: Throwable?) {}
    }

    @Volatile
    var config: Config = DisabledConfig

    private val loaded = AtomicBoolean(false)
    private val loadFailureReported = AtomicBoolean(false)
    @Volatile private var loadAttempted = false

    val available: Boolean
        get() {
            val cfg = config
            if (!cfg.enabled()) return false
            return checkAvailability(cfg)
        }

    private fun checkAvailability(cfg: Config): Boolean {
        ensureLoaded(cfg)
        return loaded.get()
    }

    private fun ensureLoaded(cfg: Config) {
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
                if (loadFailureReported.compareAndSet(false, true)) {
                    cfg.onLoadFailure(t)
                }
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
        return callNative("pbkdf2") {
            pbkdf2HmacSha256Native(passphrase, salt, iterations, keySizeBytes)
        }
    }

    /** AES-256-GCM encrypt. Returns ciphertext || 16B tag, or null on failure. */
    fun aesGcmEncrypt(plaintext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        return callNative("aes_gcm_encrypt") {
            aesGcmEncryptNative(plaintext, key, iv)
        }
    }

    /** AES-256-GCM decrypt + verify. Returns plaintext, or null on auth fail / panic. */
    fun aesGcmDecrypt(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        return callNative("aes_gcm_decrypt") {
            aesGcmDecryptNative(ciphertext, key, iv)
        }
    }

    /** SHA-256 → 32 raw bytes (caller hex-encodes if needed). */
    fun sha256(bytes: ByteArray): ByteArray? {
        return callNative("sha256") {
            sha256Native(bytes)
        }
    }

    /** HMAC-SHA256 → 32 raw bytes. */
    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray? {
        return callNative("hmac_sha256") {
            hmacSha256Native(key, message)
        }
    }

    private inline fun callNative(stage: String, block: () -> ByteArray?): ByteArray? {
        val cfg = config
        if (!cfg.enabled() || !checkAvailability(cfg)) return null
        return try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "native $stage threw — falling back to JVM", t)
            cfg.onNativePanic(stage, t)
            null
        }
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
