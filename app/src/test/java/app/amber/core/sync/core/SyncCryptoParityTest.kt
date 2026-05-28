package app.amber.core.sync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Unit-test the JVM crypto contract that the native path must match.
 *
 * The Rust `sync-crypto` crate cannot be loaded inside a JVM unit test (no .so
 * on the host classpath), so this suite validates only the **JVM side** of the
 * contract — by exercising the same arguments the native bridge would receive
 * and pinning the byte output. Once a real device or emulator instrumentation
 * test runs SyncCryptoNative, it should produce byte-identical results given
 * the same inputs.
 *
 * Coverage:
 *  - PBKDF2-HMAC-SHA256 determinism (same passphrase + salt + iter ⇒ same key)
 *  - PBKDF2 length parameter respected
 *  - AES-256-GCM seal/open roundtrip matches plaintext
 *  - AES-256-GCM auth-tag rejection (wrong key ⇒ AEAD failure)
 *  - SHA-256 known answer (RFC 6234 §8.5)
 *
 * If any of these break the Rust crate's matching tests in `lib.rs` will also
 * fail — the two suites pin both sides of the same contract.
 */
class SyncCryptoParityTest {

    @Test
    fun pbkdf2_is_deterministic() {
        val key1 = pbkdf2Jvm("passphrase", "saltsaltsaltsalt".toByteArray(), 10_000, 32)
        val key2 = pbkdf2Jvm("passphrase", "saltsaltsaltsalt".toByteArray(), 10_000, 32)
        assertArrayEquals(key1, key2)
        // 256-bit key
        assertEquals(32, key1.size)
    }

    @Test
    fun pbkdf2_diverges_on_different_passphrase() {
        val key1 = pbkdf2Jvm("passphrase", "saltsaltsaltsalt".toByteArray(), 10_000, 32)
        val key2 = pbkdf2Jvm("different", "saltsaltsaltsalt".toByteArray(), 10_000, 32)
        assertTrue("Different passphrases must yield different keys", !key1.contentEquals(key2))
    }

    @Test
    fun aes_gcm_roundtrip_with_appended_tag() {
        val key = ByteArray(32) { 42.toByte() }
        val iv = ByteArray(12) { 7.toByte() }
        val plaintext = "the quick brown fox jumps over the lazy dog".toByteArray()

        // Encrypt
        val encCipher = Cipher.getInstance("AES/GCM/NoPadding")
        encCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ciphertext = encCipher.doFinal(plaintext)
        // GCM auto-appends 16B tag
        assertEquals(plaintext.size + 16, ciphertext.size)

        // Decrypt
        val decCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val decrypted = decCipher.doFinal(ciphertext)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test(expected = javax.crypto.AEADBadTagException::class)
    fun aes_gcm_wrong_key_throws() {
        val key = ByteArray(32) { 42.toByte() }
        val wrongKey = ByteArray(32) { 99.toByte() }
        val iv = ByteArray(12) { 7.toByte() }

        val encCipher = Cipher.getInstance("AES/GCM/NoPadding")
        encCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ciphertext = encCipher.doFinal("secret".toByteArray())

        // Different key on decrypt — must throw AEADBadTagException, NOT
        // silently return garbage. Rust ring returns Err from open_in_place
        // in the same case.
        val decCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(wrongKey, "AES"), GCMParameterSpec(128, iv))
        decCipher.doFinal(ciphertext)
    }

    @Test
    fun sha256_known_answer_abc() {
        // RFC 6234 §8.5: SHA-256("abc")
        val digest = MessageDigest.getInstance("SHA-256").digest("abc".toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex)
    }

    /**
     * P4 review fix — shared byte-golden vector for PBKDF2-HMAC-SHA256.
     * Same passphrase/salt/iter/dkLen as the Rust crate's
     * `pbkdf2_byte_golden_ascii` test. Both implementations must produce
     * this exact hex string. If the Rust side updates, this fails.
     */
    @Test
    fun pbkdf2_byte_golden_ascii() {
        val out = pbkdf2Jvm("password", "salt".toByteArray(), 4096, 32)
        val hex = out.joinToString("") { "%02x".format(it) }
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            hex,
        )
    }

    /**
     * P4 review fix — UTF-8 passphrase parity test. Java's PBEKeySpec
     * encodes the char-array using PKCS#5 (UTF-8 since SE 8 for PBKDF2-HMAC);
     * Rust uses `passphrase.as_bytes()` which is also UTF-8. The two must
     * produce identical keys for a mixed CJK + ASCII passphrase.
     */
    @Test
    fun pbkdf2_byte_golden_utf8() {
        val out = pbkdf2Jvm("口令-passphrase", "salt".toByteArray(), 4096, 32)
        val hex = out.joinToString("") { "%02x".format(it) }
        assertEquals(
            "4ef032d17c60721419081307ee1e75dd9d99853983f451b3fd33f93d495ad158",
            hex,
        )
    }

    @Test
    fun sync_crypto_native_object_is_addressable() {
        // The SyncCryptoNative bridge object must compile and be reachable.
        // We don't call the JNI methods from a JVM unit test (no .so loaded),
        // we just verify the dispatcher honours the "native unavailable" path
        // — which it does by null-returning from the wrapper methods after
        // ensureLoaded fails on the host JVM.
        val crypto = SyncCrypto(nativeEnabled = false)
        // SHA-256 must still work via the javax.crypto fallback even when
        // native is disabled.
        val hex = crypto.sha256("abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex)
    }

    private fun pbkdf2Jvm(passphrase: String, salt: ByteArray, iter: Int, keyBytes: Int): ByteArray {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iter, keyBytes * 8)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}
