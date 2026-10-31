package app.amber.feature.terminal

import app.amber.core.settings.ssh.SshCredentials
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Docker Compose integration test for `tools/ssh-it/compose.yml`.
 *
 * Run with:
 *   docker compose -f tools/ssh-it/compose.yml up -d --build
 *   AMBER_SSH_IT=1 ./gradlew :feature:terminal:testDebugUnitTest --tests "app.amber.feature.terminal.SshProxyJumpDockerTest"
 */
class SshProxyJumpDockerTest {
    @Test
    fun `1-hop and 2-hop ProxyJump to isolated Docker target with TOFU and PermitOpen verification`() = runBlocking {
        assumeTrue(
            "Set AMBER_SSH_IT=1 with tools/ssh-it/compose.yml running to execute Docker SSH IT",
            System.getenv("AMBER_SSH_IT") == "1",
        )

        val client = SshClient()
        val creds: (SshProfile) -> SshCredentials = {
            SshCredentials(password = "amber-it-pass")
        }

        val jump1Untrusted = SshProfile(
            id = "jump1",
            name = "jump1",
            host = "127.0.0.1",
            port = 2221,
            username = "testuser",
            authMethod = SshAuthMethod.PASSWORD,
            createdAtMs = 1L,
            updatedAtMs = 1L,
        )
        val jump2Untrusted = SshProfile(
            id = "jump2",
            name = "jump2",
            host = "jump2",
            port = 22,
            username = "testuser",
            authMethod = SshAuthMethod.PASSWORD,
            jumpProfileId = "jump1",
            createdAtMs = 1L,
            updatedAtMs = 1L,
        )
        val targetViaJump2Untrusted = SshProfile(
            id = "target",
            name = "target",
            host = "target",
            port = 22,
            username = "testuser",
            authMethod = SshAuthMethod.PASSWORD,
            jumpProfileId = "jump2",
            createdAtMs = 1L,
            updatedAtMs = 1L,
        )

        // Probe hop 1
        val probe1 = client.probeRoute(listOf(jump1Untrusted, jump2Untrusted, targetViaJump2Untrusted), creds)
        assertEquals(1, probe1.hopIndex)
        val jump1Trusted = jump1Untrusted.copy(
            acceptedHostKeyFingerprint = probe1.fingerprint,
            acceptedHostKeyHost = jump1Untrusted.host,
            acceptedHostKeyPort = jump1Untrusted.port,
        )

        // Probe hop 2 through hop 1
        val probe2 = client.probeRoute(listOf(jump1Trusted, jump2Untrusted, targetViaJump2Untrusted), creds)
        assertEquals(2, probe2.hopIndex)
        val jump2Trusted = jump2Untrusted.copy(
            acceptedHostKeyFingerprint = probe2.fingerprint,
            acceptedHostKeyHost = jump2Untrusted.host,
            acceptedHostKeyPort = jump2Untrusted.port,
            acceptedHostKeyViaJump = "jump1",
        )

        // Probe hop 3 (Ed25519-only target) through hop 1 -> hop 2
        val probe3 = client.probeRoute(listOf(jump1Trusted, jump2Trusted, targetViaJump2Untrusted), creds)
        assertEquals(3, probe3.hopIndex)
        assertEquals("ssh-ed25519", probe3.algorithm)
        val targetTrusted = targetViaJump2Untrusted.copy(
            acceptedHostKeyFingerprint = probe3.fingerprint,
            acceptedHostKeyHost = targetViaJump2Untrusted.host,
            acceptedHostKeyPort = targetViaJump2Untrusted.port,
            acceptedHostKeyViaJump = "jump2",
        )

        // Execute command on target across 2 jump hosts
        val output = StringBuffer()
        val outcome = client.command().executeRoute(
            route = listOf(jump1Trusted, jump2Trusted, targetTrusted),
            credentialsForHop = creds,
            command = "hostname && uname -s",
            timeoutMillis = 10_000L,
            onOutput = { chunk -> synchronized(output) { output.append(chunk) } },
        )
        assertEquals(0, outcome.exitCode)
        assertNull(outcome.error)
        assertTrue(output.toString().contains("target"))

        // Verify PermitOpen denial when attempting to forward to a forbidden port (e.g. target:8080)
        val forbiddenTarget = targetTrusted.copy(
            port = 8080,
            acceptedHostKeyPort = 8080,
        )
        val deniedErr = runCatching {
            client.command().executeRoute(
                route = listOf(jump1Trusted, jump2Trusted, forbiddenTarget),
                credentialsForHop = creds,
                command = "true",
                timeoutMillis = 5_000L,
                onOutput = {},
            )
        }.exceptionOrNull() as? SshHopException
        assertEquals("jump_forward_denied", deniedErr?.code)
    }
}
