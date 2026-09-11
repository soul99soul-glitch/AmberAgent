package app.amber.feature.terminal

import app.amber.core.settings.ssh.SshCredentials
import com.jcraft.jsch.HostKeyRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshClientTest {
    @Test
    fun `fingerprint is lowercase sha256 of the ssh host key blob`() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            sshSha256Fingerprint("hello".toByteArray()),
        )
    }

    @Test
    fun `pinned repository returns changed for a different host key`() {
        val acceptedKey = "accepted-host-key".toByteArray()
        val repository = PinnedHostKeyRepository(sshSha256Fingerprint(acceptedKey))

        assertEquals(HostKeyRepository.OK, repository.check("example", acceptedKey))
        assertEquals(HostKeyRepository.OK, repository.result)
        assertEquals(HostKeyRepository.CHANGED, repository.check("example", "other-key".toByteArray()))
        assertEquals(HostKeyRepository.CHANGED, repository.result)
    }

    @Test
    fun `streaming redaction hides a secret split across reads`() {
        val redactor = StreamingSecretRedactor(listOf("secret", "pass phrase"))
        val output = buildString {
            append(redactor.append("before sec"))
            append(redactor.append("ret and pass"))
            append(redactor.append(" phrase after"))
            append(redactor.flush())
        }

        assertFalse(output.contains("secret"))
        assertFalse(output.contains("pass phrase"))
        assertTrue(output.contains("[REDACTED]"))
        assertTrue(output.contains("before"))
        assertTrue(output.contains("after"))
    }

    @Test
    fun `streaming redaction does not hold unbounded output when no secret exists`() {
        val redactor = StreamingSecretRedactor(emptyList())

        assertEquals("hello", redactor.append("hello"))
        assertEquals(" world", redactor.append(" world"))
        assertEquals("", redactor.flush())
    }

    @Test
    fun `private key does not delay ordinary command progress output`() {
        val redactor = StreamingSecretRedactor(listOf("-----BEGIN PRIVATE KEY-----\n" + "key".repeat(500)))
        assertEquals("ready\n", redactor.append("ready\n"))
        assertEquals("", redactor.flush())
    }

    @Test
    fun `every chunk boundary preserves text and redacts complete and partial secrets`() {
        val input = "before secret and pass phrase after"
        for (chunkSize in 1..input.length) {
            val redactor = StreamingSecretRedactor(listOf("secret", "pass phrase"))
            val output = input.chunked(chunkSize).joinToString("") { redactor.append(it) } + redactor.flush()
            assertEquals("chunk size $chunkSize", "before [REDACTED] and [REDACTED] after", output)
        }
        val redactor = StreamingSecretRedactor(listOf("secret"))
        assertEquals("before ", redactor.append("before sec"))
        assertEquals("[REDACTED]", redactor.flush())
    }

    @Test
    fun `close before execute prevents credentials from being read`() {
        val credentialsRead = AtomicBoolean(false)
        val connection = SshCommandConnection()
        connection.close()

        val failure = runCatching {
            runBlocking {
                connection.execute(
                    profile(),
                    credentials = {
                        credentialsRead.set(true)
                        SshCredentials(password = "secret", privateKey = null, passphrase = null)
                    },
                    command = "true",
                    timeoutMillis = 1_000L,
                    onOutput = {},
                )
            }
        }.exceptionOrNull()

        assertTrue(failure is SshClientException)
        assertEquals("SSH command connection is closed.", failure?.message)
        assertFalse(credentialsRead.get())
    }

    @Test
    fun `untrusted profile is rejected before credentials are read`() {
        val credentialsRead = AtomicBoolean(false)
        val connection = SshClient().command()

        val failure = runCatching {
            runBlocking {
                connection.execute(
                    profile(fingerprint = null),
                    credentials = {
                        credentialsRead.set(true)
                        SshCredentials(password = "secret", privateKey = null, passphrase = null)
                    },
                    command = "true",
                    timeoutMillis = 1_000L,
                    onOutput = {},
                )
            }
        }.exceptionOrNull()

        assertTrue(failure is SshClientException)
        assertEquals(
            "SSH host key is not trusted. Probe and confirm this server before running a command.",
            failure?.message,
        )
        assertFalse(credentialsRead.get())
    }

    private fun profile(fingerprint: String? = "ab".repeat(32)) = SshProfile(
        id = "test-profile",
        name = "Test profile",
        host = "127.0.0.1",
        port = 22,
        username = "tester",
        authMethod = SshAuthMethod.PASSWORD,
        acceptedHostKeyFingerprint = fingerprint,
        createdAtMs = 0L,
        updatedAtMs = 0L,
    )
}
