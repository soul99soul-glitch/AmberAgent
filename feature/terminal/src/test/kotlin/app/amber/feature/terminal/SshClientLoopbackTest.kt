package app.amber.feature.terminal

import app.amber.core.settings.ssh.SshCredentials
import java.io.File
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in integration coverage for the real JSch-backed client and a temporary local sshd.
 *
 * The fixture is created by scripts/test-ssh-loopback.py. Keeping this test opt-in means the
 * normal JVM test suite never needs an ssh daemon, a private key, or a user's SSH configuration.
 * Password authentication is intentionally not exercised: this fixture never changes or reads
 * the local account password, and its sshd disables password authentication.
 */
class SshClientLoopbackTest {
    @Test
    fun `probe then explicit trust executes stdout stderr and exit seven`() = runBlocking {
        val fixture = fixture()
        val untrusted = fixture.profile("probe-exec")
        val probe = SshClient().probe(untrusted)

        assertEquals("127.0.0.1", probe.profile.host)
        assertEquals("ecdsa-sha2-nistp256", probe.algorithm)
        assertTrue(probe.fingerprint.matches(Regex("[0-9a-f]{64}")))

        val output = StringBuffer()
        val outcome = execute(
            profile = untrusted.copy(acceptedHostKeyFingerprint = probe.fingerprint),
            privateKey = fixture.clientPrivateKey,
            command = "printf 'ssh-loopback-stdout\\n'; printf 'ssh-loopback-stderr\\n' >&2; exit 7",
            output = output,
        )

        assertEquals(7, outcome.exitCode)
        assertNull(outcome.error)
        assertTrue(output.toString().contains("ssh-loopback-stdout"))
        assertTrue(output.toString().contains("ssh-loopback-stderr"))
    }

    @Test
    fun `host key mismatch blocks command`() = runBlocking {
        val fixture = fixture()
        val profile = fixture.profile("host-key-mismatch")
            .copy(acceptedHostKeyFingerprint = "00".repeat(32))
        val output = StringBuffer()

        val failure = runCatching {
            execute(
                profile = profile,
                privateKey = fixture.clientPrivateKey,
                command = "printf 'must-not-run\\n'",
                output = output,
            )
        }.exceptionOrNull()

        assertTrue(failure is SshClientException)
        assertEquals("SSH host key changed; authentication was blocked.", failure?.message)
        assertFalse(output.toString().contains("must-not-run"))
    }

    @Test
    fun `wrong private key fails authentication after a trusted probe`() = runBlocking {
        val fixture = fixture()
        val probe = SshClient().probe(fixture.profile("wrong-key-probe"))

        val failure = runCatching {
            execute(
                profile = fixture.profile("wrong-key").copy(
                    acceptedHostKeyFingerprint = probe.fingerprint,
                ),
                privateKey = fixture.wrongPrivateKey,
                command = "printf 'must-not-run\\n'",
                output = StringBuffer(),
            )
        }.exceptionOrNull()

        assertTrue(failure is SshClientException)
        assertEquals("SSH authentication failed.", failure?.message)
    }

    @Test
    fun `invalid private key is reported without exposing its contents`() = runBlocking {
        val fixture = fixture()
        val probe = SshClient().probe(fixture.profile("invalid-key-probe"))
        val invalidKey = "-----BEGIN PRIVATE KEY-----\ninvalid-loopback-key\n-----END PRIVATE KEY-----"

        val failure = runCatching {
            execute(
                profile = fixture.profile("invalid-key").copy(
                    acceptedHostKeyFingerprint = probe.fingerprint,
                ),
                privateKey = invalidKey,
                command = "true",
                output = StringBuffer(),
            )
        }.exceptionOrNull()

        assertTrue(failure is SshClientException)
        assertEquals("SSH private key is invalid or unsupported.", failure?.message)
        assertFalse(failure?.message.orEmpty().contains("invalid-loopback-key"))
    }

    @Test
    fun `encrypted private key accepts passphrase and redacts it from output`() = runBlocking {
        val fixture = fixture()
        val probe = SshClient().probe(fixture.profile("encrypted-key-probe"))
        val output = StringBuffer()
        val quotedPassphrase = shellQuote(fixture.passphrase)

        val outcome = execute(
            profile = fixture.profile("encrypted-key").copy(
                acceptedHostKeyFingerprint = probe.fingerprint,
            ),
            privateKey = fixture.encryptedPrivateKey,
            passphrase = fixture.passphrase,
            command = "printf 'before %s after\\n' $quotedPassphrase; exit 0",
            output = output,
        )

        assertEquals(0, outcome.exitCode)
        assertNull(outcome.error)
        assertTrue(output.toString().contains("before [REDACTED] after"))
        assertFalse(output.toString().contains(fixture.passphrase))
    }

    @Test
    fun `timeout returns unknown remote status`() = runBlocking {
        val fixture = fixture()
        val probe = SshClient().probe(fixture.profile("timeout-probe"))
        val output = StringBuffer()

        val outcome = execute(
            profile = fixture.profile("timeout").copy(
                acceptedHostKeyFingerprint = probe.fingerprint,
            ),
            privateKey = fixture.clientPrivateKey,
            command = "sleep 30; printf 'after-timeout\\n'",
            timeoutMillis = 750L,
            output = output,
        )

        assertNull(outcome.exitCode)
        assertNotNull(outcome.error)
        assertTrue(outcome.error.orEmpty().contains("timed out"))
        assertTrue(outcome.error.orEmpty().contains("unknown"))
        assertFalse(output.toString().contains("after-timeout"))
    }

    @Test
    fun `closing a running command reports interruption with unknown status`() = runBlocking {
        val fixture = fixture()
        val probe = SshClient().probe(fixture.profile("close-probe"))
        val profile = fixture.profile("close").copy(
            acceptedHostKeyFingerprint = probe.fingerprint,
        )
        val connection = SshClient().command()
        val output = StringBuffer()
        val started = CountDownLatch(1)

        val deferred = async(Dispatchers.IO) {
            connection.execute(
                profile = profile,
                credentials = {
                    SshCredentials(
                        password = null,
                        privateKey = fixture.clientPrivateKey,
                        passphrase = null,
                    )
                },
                command = "printf 'started-close\\n'; sleep 30; printf 'completed-close\\n'",
                timeoutMillis = 10_000L,
                onOutput = { chunk ->
                    synchronized(output) { output.append(chunk) }
                    if (output.toString().contains("started-close")) started.countDown()
                },
            )
        }

        assertTrue(started.await(5, TimeUnit.SECONDS))
        connection.close()
        val outcome = withTimeout(5_000L) { deferred.await() }

        assertNull(outcome.exitCode)
        assertTrue(outcome.error.orEmpty().contains("interrupted"))
        assertTrue(outcome.error.orEmpty().contains("unknown"))
        assertFalse(output.toString().contains("completed-close"))
    }

    @Test
    fun `parallel profiles keep valid and wrong identities isolated`() = runBlocking {
        val fixture = fixture()
        val probe = SshClient().probe(fixture.profile("parallel-probe"))
        val fingerprint = probe.fingerprint
        val validOutput = StringBuffer()

        val valid = async(Dispatchers.IO) {
            runCatching {
                execute(
                    profile = fixture.profile("parallel-valid").copy(
                        acceptedHostKeyFingerprint = fingerprint,
                    ),
                    privateKey = fixture.clientPrivateKey,
                    command = "printf 'parallel-valid\\n'; exit 0",
                    output = validOutput,
                )
            }
        }
        val wrong = async(Dispatchers.IO) {
            runCatching {
                execute(
                    profile = fixture.profile("parallel-wrong").copy(
                        acceptedHostKeyFingerprint = fingerprint,
                    ),
                    privateKey = fixture.wrongPrivateKey,
                    command = "printf 'parallel-wrong\\n'; exit 0",
                    output = StringBuffer(),
                )
            }
        }

        val validResult = valid.await()
        val wrongResult = wrong.await()
        assertTrue(validResult.isSuccess)
        assertEquals(0, validResult.getOrThrow().exitCode)
        assertTrue(validOutput.toString().contains("parallel-valid"))
        assertTrue(wrongResult.exceptionOrNull() is SshClientException)
        assertEquals("SSH authentication failed.", wrongResult.exceptionOrNull()?.message)
    }

    private suspend fun execute(
        profile: SshProfile,
        privateKey: String,
        passphrase: String? = null,
        command: String,
        timeoutMillis: Long = 10_000L,
        output: StringBuffer,
    ): SshCommandOutcome = SshClient().command().execute(
        profile = profile,
        credentials = {
            SshCredentials(
                privateKey = privateKey,
                passphrase = passphrase,
            )
        },
        command = command,
        timeoutMillis = timeoutMillis,
        onOutput = { chunk -> synchronized(output) { output.append(chunk) } },
    )

    private fun fixture(): LoopbackFixture {
        val rawPath = System.getenv(FIXTURE_ENV)
            ?.takeIf { it.isNotBlank() }
            ?: System.getProperty(FIXTURE_PROPERTY)?.takeIf { it.isNotBlank() }
        assumeTrue(
            "Set $FIXTURE_ENV or -D$FIXTURE_PROPERTY to run the sshd loopback integration test",
            rawPath != null,
        )
        val file = File(requireNotNull(rawPath))
        assumeTrue("SSH loopback fixture does not exist: $file", file.isFile)
        return LoopbackFixture(file)
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private class LoopbackFixture(file: File) {
        private val properties = Properties().apply {
            file.inputStream().use(::load)
        }

        val host: String = required("host")
        private val port: Int = required("port").toInt()
        private val username: String = required("username")
        val clientPrivateKey: String = File(required("client_private_key")).readText()
        val wrongPrivateKey: String = File(required("wrong_private_key")).readText()
        val encryptedPrivateKey: String = File(required("encrypted_private_key")).readText()
        val passphrase: String = required("encrypted_passphrase")

        fun profile(id: String): SshProfile = SshProfile(
            id = id,
            name = id,
            host = host,
            port = port,
            username = username,
            authMethod = SshAuthMethod.PRIVATE_KEY,
            acceptedHostKeyFingerprint = null,
            createdAtMs = 0L,
            updatedAtMs = 0L,
        )

        private fun required(key: String): String =
            properties.getProperty(key)?.takeIf { it.isNotBlank() }
                ?: error("SSH loopback fixture is missing property: $key")
    }

    private companion object {
        const val FIXTURE_ENV = "AMBER_SSH_LOOPBACK_FIXTURE"
        const val FIXTURE_PROPERTY = "amber.ssh.loopback.fixture"
    }
}
