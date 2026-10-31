package app.amber.feature.terminal

import app.amber.core.settings.ssh.SshCredentials
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SshProxyJumpClientTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val runningProcesses = mutableListOf<Process>()

    @After
    fun tearDown() {
        runningProcesses.forEach { proc ->
            runCatching {
                proc.destroy()
                if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                }
            }
        }
        runningProcesses.clear()
    }

    @Test
    fun `executeRoute rejects untrusted hop in pre-flight before opening network connection`() = runBlocking {
        val jump = SshProfile(
            id = "jump1",
            name = "jump1",
            host = "127.0.0.1",
            port = 1,
            username = "nobody",
            authMethod = SshAuthMethod.PRIVATE_KEY,
            acceptedHostKeyFingerprint = "aa".repeat(32),
            acceptedHostKeyHost = "127.0.0.1",
            acceptedHostKeyPort = 1,
            acceptedHostKeyViaJump = null,
            createdAtMs = 1L,
            updatedAtMs = 1L,
        )
        val target = SshProfile(
            id = "target",
            name = "target",
            host = "10.0.0.9",
            port = 22,
            username = "nobody",
            authMethod = SshAuthMethod.PRIVATE_KEY,
            jumpProfileId = "jump1",
            acceptedHostKeyFingerprint = null,
            createdAtMs = 1L,
            updatedAtMs = 1L,
        )

        val err = runCatching {
            SshClient().command().executeRoute(
                route = listOf(jump, target),
                credentialsForHop = { SshCredentials() },
                command = "echo hi",
                timeoutMillis = 2_000L,
                onOutput = {},
            )
        }.exceptionOrNull() as? SshHopException

        assertEquals(2, err?.hopIndex)
        assertEquals("target", err?.hopProfileId)
        assertEquals("host_key_untrusted", err?.code)
    }

    @Test
    fun `multi-hop ProxyJump over ForceCommand false jump hosts with generated Ed25519 key`() = runBlocking {
        assumeTrue("Local /usr/sbin/sshd required", File("/usr/sbin/sshd").canExecute())
        val user = System.getProperty("user.name")?.takeIf { it.isNotBlank() }
        assumeTrue("Valid system user.name required", user != null)

        val generatedKey = SshKeyPairs.generate(name = "proxyjump-test", type = SshKeyGenType.ED25519)
        val targetDaemon = startSshd(
            name = "target",
            authorizedPublicKey = generatedKey.key.publicKeyOpenSsh,
            ed25519HostKeyOnly = true,
            forceCommandFalse = false,
            permitOpenSpecs = emptyList(),
        )
        val jump2Daemon = startSshd(
            name = "jump2",
            authorizedPublicKey = generatedKey.key.publicKeyOpenSsh,
            ed25519HostKeyOnly = true,
            forceCommandFalse = true,
            permitOpenSpecs = listOf("127.0.0.1:${targetDaemon.port}"),
        )
        val jump1Daemon = startSshd(
            name = "jump1",
            authorizedPublicKey = generatedKey.key.publicKeyOpenSsh,
            ed25519HostKeyOnly = false,
            forceCommandFalse = true,
            permitOpenSpecs = listOf("127.0.0.1:${jump2Daemon.port}", "127.0.0.1:${targetDaemon.port}"),
        )

        val jump1Untrusted = SshProfile(
            id = "jump1",
            name = "Bastion 1",
            host = "127.0.0.1",
            port = jump1Daemon.port,
            username = user!!,
            authMethod = SshAuthMethod.PRIVATE_KEY,
            createdAtMs = 1L,
            updatedAtMs = 1L,
        )
        val jump2Untrusted = SshProfile(
            id = "jump2",
            name = "Bastion 2",
            host = "127.0.0.1",
            port = jump2Daemon.port,
            username = user,
            authMethod = SshAuthMethod.PRIVATE_KEY,
            jumpProfileId = "jump1",
            createdAtMs = 1L,
            updatedAtMs = 1L,
        )
        val targetUntrusted = SshProfile(
            id = "target",
            name = "Inner App",
            host = "127.0.0.1",
            port = targetDaemon.port,
            username = user,
            authMethod = SshAuthMethod.PRIVATE_KEY,
            jumpProfileId = "jump2",
            createdAtMs = 1L,
            updatedAtMs = 1L,
        )

        val client = SshClient()
        val creds: (SshProfile) -> SshCredentials = {
            SshCredentials(privateKey = generatedKey.privateKeyOpenSsh)
        }

        // Step 1: Probe route when hop 1 is untrusted -> returns probe for hop 1
        val probe1 = client.probeRoute(listOf(jump1Untrusted, jump2Untrusted, targetUntrusted), creds)
        assertEquals(1, probe1.hopIndex)
        assertEquals(3, probe1.hopCount)
        assertEquals("jump1", probe1.profile.id)

        val jump1Trusted = jump1Untrusted.copy(
            acceptedHostKeyFingerprint = probe1.fingerprint,
            acceptedHostKeyHost = jump1Untrusted.host,
            acceptedHostKeyPort = jump1Untrusted.port,
            acceptedHostKeyViaJump = null,
        )

        // Step 2: Probe route when hop 1 is trusted and hop 2 is untrusted -> connects hop 1, tunnels to hop 2, probes hop 2!
        val probe2 = client.probeRoute(listOf(jump1Trusted, jump2Untrusted, targetUntrusted), creds)
        assertEquals(2, probe2.hopIndex)
        assertEquals("jump2", probe2.profile.id)
        assertEquals("ssh-ed25519", probe2.algorithm)

        val jump2Trusted = jump2Untrusted.copy(
            acceptedHostKeyFingerprint = probe2.fingerprint,
            acceptedHostKeyHost = jump2Untrusted.host,
            acceptedHostKeyPort = jump2Untrusted.port,
            acceptedHostKeyViaJump = "jump1",
        )

        // Step 3: Probe route when hop 1 & 2 are trusted -> tunnels through hop 1 & hop 2 to probe hop 3 (target)!
        val probe3 = client.probeRoute(listOf(jump1Trusted, jump2Trusted, targetUntrusted), creds)
        assertEquals(3, probe3.hopIndex)
        assertEquals("target", probe3.profile.id)
        assertEquals("ssh-ed25519", probe3.algorithm)

        val targetTrusted = targetUntrusted.copy(
            acceptedHostKeyFingerprint = probe3.fingerprint,
            acceptedHostKeyHost = targetUntrusted.host,
            acceptedHostKeyPort = targetUntrusted.port,
            acceptedHostKeyViaJump = "jump2",
        )

        // Step 4: Execute command across 2 jump hosts (3 hops total) and verify multi-hop secret redaction!
        val jump1Secret = "jump1-super-secret-passphrase"
        val output = StringBuffer()
        val outcome = client.command().executeRoute(
            route = listOf(jump1Trusted, jump2Trusted, targetTrusted),
            credentialsForHop = { hop ->
                SshCredentials(
                    privateKey = generatedKey.privateKeyOpenSsh,
                    password = if (hop.id == "jump1") jump1Secret else null,
                )
            },
            command = "printf 'hop3-ok %s\\n' '$jump1Secret'; exit 0",
            timeoutMillis = 10_000L,
            onOutput = { chunk -> synchronized(output) { output.append(chunk) } },
        )

        assertEquals(0, outcome.exitCode)
        assertNull(outcome.error)
        assertTrue(output.toString().contains("hop3-ok [REDACTED]"))
        assertFalse(output.toString().contains(jump1Secret))

        // Step 5: Tamper with hop 2's pinned fingerprint -> verify hop 2 TOFU mismatch blocks before hop 2 auth and never contacts hop 3!
        val jump2Tampered = jump2Trusted.copy(acceptedHostKeyFingerprint = "00".repeat(32))
        val markerFile = File(tempFolder.root, "target-must-not-be-touched")
        val mismatchErr = runCatching {
            client.command().executeRoute(
                route = listOf(jump1Trusted, jump2Tampered, targetTrusted),
                credentialsForHop = creds,
                command = "touch ${markerFile.absolutePath}",
                timeoutMillis = 10_000L,
                onOutput = {},
            )
        }.exceptionOrNull() as? SshHopException

        assertEquals(2, mismatchErr?.hopIndex)
        assertEquals("jump2", mismatchErr?.hopProfileId)
        assertEquals("host_key_changed", mismatchErr?.code)
        assertFalse("Target hop must never be reached when hop 2 host key mismatches", markerFile.exists())

        // Step 6: Verify PermitOpen denial on jump2 maps to jump_forward_denied
        val forbiddenTarget = targetTrusted.copy(
            port = jump1Daemon.port, // jump2 PermitOpen only allows targetDaemon.port, not jump1Daemon.port
            acceptedHostKeyFingerprint = probe1.fingerprint,
            acceptedHostKeyPort = jump1Daemon.port,
        )
        val forwardErr = runCatching {
            client.command().executeRoute(
                route = listOf(jump1Trusted, jump2Trusted, forbiddenTarget),
                credentialsForHop = creds,
                command = "echo should-not-forward",
                timeoutMillis = 10_000L,
                onOutput = {},
            )
        }.exceptionOrNull() as? SshHopException
        assertEquals("jump_forward_denied", forwardErr?.code)
        assertEquals(2, forwardErr?.hopIndex)
        assertEquals("jump2", forwardErr?.hopProfileId)
    }

    private data class RunningSshd(
        val port: Int,
        val process: Process,
    )

    private fun startSshd(
        name: String,
        authorizedPublicKey: String,
        ed25519HostKeyOnly: Boolean,
        forceCommandFalse: Boolean,
        permitOpenSpecs: List<String>,
    ): RunningSshd {
        val dir = tempFolder.newFolder("sshd-$name-${System.nanoTime()}")
        val hostEd25519 = File(dir, "ssh_host_ed25519_key")
        val hostEcdsa = File(dir, "ssh_host_ecdsa_key")
        runCommand(listOf("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", hostEd25519.absolutePath))
        if (!ed25519HostKeyOnly) {
            runCommand(listOf("ssh-keygen", "-q", "-t", "ecdsa", "-b", "256", "-N", "", "-f", hostEcdsa.absolutePath))
        }
        val authKeys = File(dir, "authorized_keys")
        authKeys.writeText("$authorizedPublicKey\n")
        authKeys.setReadable(false, false)
        authKeys.setReadable(true, true)

        val port = ServerSocket(0).use { it.localPort }
        val pidFile = File(dir, "sshd.pid")
        val configFile = File(dir, "sshd_config")
        val user = System.getProperty("user.name")

        val configLines = buildList {
            add("Port $port")
            add("ListenAddress 127.0.0.1")
            add("PidFile ${pidFile.absolutePath}")
            add("HostKey ${hostEd25519.absolutePath}")
            if (!ed25519HostKeyOnly) {
                add("HostKey ${hostEcdsa.absolutePath}")
            }
            add("AuthorizedKeysFile ${authKeys.absolutePath}")
            add("PubkeyAuthentication yes")
            add("PasswordAuthentication no")
            add("KbdInteractiveAuthentication no")
            add("ChallengeResponseAuthentication no")
            add("UsePAM no")
            add("PermitRootLogin no")
            add("StrictModes no")
            add("AllowUsers $user")
            add("LogLevel QUIET")
            if (permitOpenSpecs.isNotEmpty()) {
                add("AllowTcpForwarding local")
                add("PermitOpen ${permitOpenSpecs.joinToString(" ")}")
            } else {
                add("AllowTcpForwarding no")
            }
            if (forceCommandFalse) {
                add("ForceCommand /usr/bin/false")
            }
        }
        configFile.writeText(configLines.joinToString("\n") + "\n")

        val process = ProcessBuilder("/usr/sbin/sshd", "-D", "-f", configFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        runningProcesses += process
        waitForPort(port)
        return RunningSshd(port = port, process = process)
    }

    private fun waitForPort(port: Int) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val connected = runCatching {
                java.net.Socket("127.0.0.1", port).use { true }
            }.getOrDefault(false)
            if (connected) return
            Thread.sleep(40L)
        }
        error("Timed out waiting for local sshd on port $port")
    }

    private fun runCommand(cmd: List<String>) {
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor(10, TimeUnit.SECONDS) && proc.exitValue() == 0) {
            "Command failed (${cmd.joinToString(" ")}): $output"
        }
    }
}
