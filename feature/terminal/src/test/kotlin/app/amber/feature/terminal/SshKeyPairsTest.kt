package app.amber.feature.terminal

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeyPairsTest {
    @get:org.junit.Rule val temp = org.junit.rules.TemporaryFolder()

    @Test
    fun `generate Ed25519 produces valid OpenSSH public key and SHA256 fingerprint`() {
        val generated = SshKeyPairs.generate(name = "work", type = SshKeyGenType.ED25519)

        assertEquals("ssh-ed25519", generated.key.algorithm)
        assertTrue(generated.key.publicKeyOpenSsh.startsWith("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5"))
        assertTrue(generated.key.publicKeyOpenSsh.endsWith(" amber-work"))
        assertTrue(generated.key.fingerprintSha256.startsWith("SHA256:"))
        assertFalse(generated.toString().contains("BEGIN"))
        assertTrue(generated.toString().contains("<redacted>"))

        val inspected = SshKeyPairs.parseImportedKey(
            name = "work",
            privateKeyText = generated.privateKeyOpenSsh,
            passphrase = null,
        )
        assertEquals("ssh-ed25519", inspected.algorithm)
        assertEquals(generated.key.fingerprintSha256, inspected.fingerprintSha256)
        assertEquals(generated.key.publicKeyOpenSsh, inspected.publicKeyOpenSsh)
        assertFalse(inspected.hasPassphrase)
        val publicFile = temp.newFile("generated.pub").apply { writeText(generated.key.publicKeyOpenSsh) }
        val inspect = ProcessBuilder("ssh-keygen", "-lf", publicFile.absolutePath).redirectErrorStream(true).start()
        val fingerprintOutput = inspect.inputStream.bufferedReader().readText()
        assertEquals(0, inspect.waitFor())
        assertTrue(fingerprintOutput.contains(generated.key.fingerprintSha256))
    }

    @Test
    fun `generate ECDSA P-256 produces valid OpenSSH public key and SHA256 fingerprint`() {
        val generated = SshKeyPairs.generate(name = "ecdsa", type = SshKeyGenType.ECDSA_P256)

        assertEquals("ecdsa-sha2-nistp256", generated.key.algorithm)
        assertTrue(generated.key.publicKeyOpenSsh.startsWith("ecdsa-sha2-nistp256 "))
        assertTrue(generated.key.publicKeyOpenSsh.endsWith(" amber-ecdsa"))
        assertTrue(generated.key.fingerprintSha256.startsWith("SHA256:"))

        val inspected = SshKeyPairs.parseImportedKey(
            name = "ecdsa",
            privateKeyText = generated.privateKeyOpenSsh,
            passphrase = null,
        )
        assertEquals("ecdsa-sha2-nistp256", inspected.algorithm)
        assertEquals(generated.key.fingerprintSha256, inspected.fingerprintSha256)
    }

    @Test
    fun `parseImportedKey supports encrypted key with passphrase and rejects wrong passphrase`() {
        val keyFile = java.io.File(temp.root, "encrypted")
        val create = ProcessBuilder("ssh-keygen", "-q", "-t", "ed25519", "-N", "correct-passphrase", "-f", keyFile.absolutePath)
            .redirectErrorStream(true).start()
        assertEquals(0, create.waitFor())
        val encryptedPem = keyFile.readText()

        // Missing passphrase rejected
        val missingErr = runCatching {
            SshKeyPairs.parseImportedKey(name = "enc", privateKeyText = encryptedPem, passphrase = null)
        }.exceptionOrNull()
        assertTrue(missingErr is IllegalArgumentException)
        assertTrue(missingErr?.message.orEmpty().contains("passphrase"))

        // Wrong passphrase rejected
        val wrongErr = runCatching {
            SshKeyPairs.parseImportedKey(name = "enc", privateKeyText = encryptedPem, passphrase = "wrong-passphrase")
        }.exceptionOrNull()
        assertTrue(wrongErr is IllegalArgumentException)
        assertTrue(wrongErr?.message.orEmpty().contains("passphrase"))

        // Correct passphrase succeeds
        val inspected = SshKeyPairs.parseImportedKey(
            name = "enc",
            privateKeyText = encryptedPem,
            passphrase = "correct-passphrase",
        )
        assertEquals("ssh-ed25519", inspected.algorithm)
        assertTrue(inspected.hasPassphrase)
        assertTrue(inspected.fingerprintSha256.startsWith("SHA256:"))
    }

    @Test
    fun `parseImportedKey rejects malformed and oversized private keys without leaking key text`() {
        val secretSnippet = "super-secret-malformed-key-body"
        val malformed = "-----BEGIN OPENSSH PRIVATE KEY-----\n$secretSnippet\n-----END OPENSSH PRIVATE KEY-----"
        val err = runCatching {
            SshKeyPairs.parseImportedKey(name = "bad", privateKeyText = malformed, passphrase = null)
        }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException)
        assertFalse(err?.message.orEmpty().contains(secretSnippet))

        val oversized = "x".repeat(64 * 1024 + 10)
        val sizeErr = runCatching {
            SshKeyPairs.parseImportedKey(name = "oversized", privateKeyText = oversized, passphrase = null)
        }.exceptionOrNull()
        assertTrue(sizeErr is IllegalArgumentException)
    }
}
