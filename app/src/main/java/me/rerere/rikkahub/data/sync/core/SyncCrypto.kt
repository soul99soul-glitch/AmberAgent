package me.rerere.rikkahub.data.sync.core

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class SyncCrypto {
    fun newEncryptionParams(): SyncEncryptionParams {
        val salt = ByteArray(SALT_BYTES)
        val iv = ByteArray(IV_BYTES)
        secureRandom.nextBytes(salt)
        secureRandom.nextBytes(iv)
        return SyncEncryptionParams(
            kdf = SyncKdfInfo(
                iterations = PBKDF2_ITERATIONS,
                saltBase64 = salt.toBase64(),
            ),
            cipher = SyncCipherInfo(
                ivBase64 = iv.toBase64(),
            )
        )
    }

    fun encrypt(bytes: ByteArray, passphrase: String, params: SyncEncryptionParams): ByteArray {
        val cipher = cipher(Cipher.ENCRYPT_MODE, passphrase, params.kdf, params.cipher)
        return ByteArrayOutputStream().use { output ->
            CipherOutputStream(output, cipher).use { cipherOut ->
                ByteArrayInputStream(bytes).use { input ->
                    input.copyTo(cipherOut)
                }
            }
            output.toByteArray()
        }
    }

    fun decrypt(bytes: ByteArray, passphrase: String, manifest: SyncManifest): ByteArray {
        val cipher = cipher(Cipher.DECRYPT_MODE, passphrase, manifest.kdf, manifest.cipher)
        return CipherInputStream(ByteArrayInputStream(bytes), cipher).use { input ->
            input.readBytes()
        }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun cipher(
        mode: Int,
        passphrase: String,
        kdf: SyncKdfInfo,
        cipherInfo: SyncCipherInfo,
    ): Cipher {
        require(passphrase.isNotBlank()) { "同步口令不能为空" }
        require(kdf.name == "PBKDF2WithHmacSHA256") { "Unsupported KDF: ${kdf.name}" }
        require(cipherInfo.name == "AES/GCM/NoPadding") { "Unsupported cipher: ${cipherInfo.name}" }
        val key = deriveKey(passphrase, kdf)
        val cipher = Cipher.getInstance(cipherInfo.name)
        cipher.init(
            mode,
            key,
            GCMParameterSpec(cipherInfo.tagSizeBits, cipherInfo.ivBase64.fromBase64())
        )
        return cipher
    }

    private fun deriveKey(passphrase: String, kdf: SyncKdfInfo): SecretKeySpec {
        val spec = PBEKeySpec(
            passphrase.toCharArray(),
            kdf.saltBase64.fromBase64(),
            kdf.iterations,
            kdf.keySizeBits,
        )
        val bytes = SecretKeyFactory.getInstance(kdf.name).generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    companion object {
        const val PBKDF2_ITERATIONS = 210_000
        private const val SALT_BYTES = 16
        private const val IV_BYTES = 12
        private val secureRandom = SecureRandom()
    }
}

data class SyncEncryptionParams(
    val kdf: SyncKdfInfo,
    val cipher: SyncCipherInfo,
)

internal fun ByteArray.toBase64(): String =
    Base64.encodeToString(this, Base64.NO_WRAP)

internal fun String.fromBase64(): ByteArray =
    Base64.decode(this, Base64.NO_WRAP)
