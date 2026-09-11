package app.amber.core.sync.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRestoreVerificationTest {

    @Test
    fun `verification is single use and cleanup removes plaintext`() {
        val root = Files.createTempDirectory("amber-restore-verification-").toFile()
        try {
            val encrypted = File(root, "payload.enc").apply { writeText("ciphertext") }
            val payload = File(root, "payload.zip").apply { writeText("plaintext") }
            val verification = SyncRestoreVerification(
                archiveFile = File(root, "source.amberbackup"),
                encryptedPayloadFile = encrypted,
                payloadFile = payload,
                preview = SyncPreview(testManifest()),
                payloadPreview = SyncPayloadPreview(),
            )

            assertTrue(verification.consume())
            assertFalse("a verification object cannot be applied twice", verification.consume())
            verification.cleanup()
            assertFalse(encrypted.exists())
            assertFalse(payload.exists())
            assertFalse(verification.consume())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun testManifest() = SyncManifest(
        appVersionName = "test",
        appVersionCode = 1L,
        createdAt = 1L,
        deviceId = "test-device",
        mode = SyncMode.FULL,
        kdf = SyncKdfInfo(iterations = 1, saltBase64 = "salt"),
        cipher = SyncCipherInfo(ivBase64 = "iv"),
        payloadSha256 = "sha256",
    )
}
