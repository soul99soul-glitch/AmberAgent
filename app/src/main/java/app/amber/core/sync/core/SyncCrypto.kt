package app.amber.core.sync.core

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32
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

    fun encrypt(
        inputFile: File,
        outputFile: File,
        passphrase: String,
        params: SyncEncryptionParams,
    ): EncryptResult {
        val cipher = cipher(Cipher.ENCRYPT_MODE, passphrase, params.kdf, params.cipher)
        outputFile.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        var size = 0L
        inputFile.inputStream().buffered().use { rawIn ->
            CipherInputStream(rawIn, cipher).use { cipherIn ->
                outputFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = cipherIn.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        crc.update(buffer, 0, read)
                        size += read
                    }
                }
            }
        }
        return EncryptResult(
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            crc32 = crc.value,
            sizeBytes = size,
        )
    }

    fun decrypt(bytes: ByteArray, passphrase: String, manifest: SyncManifest): ByteArray {
        val cipher = cipher(Cipher.DECRYPT_MODE, passphrase, manifest.kdf, manifest.cipher)
        return CipherInputStream(ByteArrayInputStream(bytes), cipher).use { input ->
            input.readBytes()
        }
    }

    fun decrypt(inputFile: File, outputFile: File, passphrase: String, manifest: SyncManifest) {
        val cipher = cipher(Cipher.DECRYPT_MODE, passphrase, manifest.kdf, manifest.cipher)
        outputFile.parentFile?.mkdirs()
        inputFile.inputStream().buffered().use { input ->
            CipherInputStream(input, cipher).use { cipherIn ->
                outputFile.outputStream().buffered().use { output ->
                    cipherIn.copyTo(output)
                }
            }
        }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

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

data class EncryptResult(
    val sha256: String,
    val crc32: Long,
    val sizeBytes: Long,
)

internal fun ByteArray.toBase64(): String =
    Base64.encodeToString(this, Base64.NO_WRAP)

internal fun String.fromBase64(): ByteArray =
    Base64.decode(this, Base64.NO_WRAP)
