package app.amber.feature.runtime

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.amber.ai.core.Tool
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.secret.SecretCipher
import app.amber.core.settings.secret.SecretStore
import app.amber.core.settings.secret.SecretStoreBackend
import app.amber.core.settings.ssh.SshProfileStore
import app.amber.feature.terminal.SshAuthMethod
import app.amber.feature.terminal.SshProfile
import app.amber.feature.terminal.TerminalRuntimeKind
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SshApprovalTargetGuardTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `dispatcher refuses changed jump route and missing approved snapshot before execution`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val data = mutableMapOf<String, String>()
            val secrets = SecretStore(object : SecretStoreBackend {
                override fun get(key: String) = data[key]
                override fun put(key: String, value: String) { data[key] = value }
                override fun remove(key: String) { data.remove(key) }
                override fun keys() = data.keys.toSet()
            }, object : SecretCipher {
                override fun encrypt(plaintext: String) = plaintext
                override fun decrypt(stored: String) = stored
            })
            val store = SshProfileStore(PreferenceDataStoreFactory.create(scope = scope) {
                File(temp.root, "ssh.preferences_pb")
            }, secrets)
            store.save(profile("jump"), password = "jump-secret")
            store.save(profile("target").copy(jumpProfileId = "jump"), password = "target-secret")
            val guard = SshApprovalTargetGuard(store) { TerminalRuntimeKind.REMOTE_SSH }
            val call = UIMessagePart.Tool("c", "terminal_execute", """{"profile_id":"target","command":"echo ok"}""")
            val bound = guard.capture(call).copy(approvalState = ToolApprovalState.Approved)
            assertTrue(guard.isCurrent(bound))
            assertFalse(bound.metadata.toString().contains("secret"))
            store.save(store.resolve("jump").copy(username = "another-user"))
            assertFalse(guard.isCurrent(bound))
            var executed = false
            val dispatcher = AgentToolDispatcher(Json, PermissionDecisionResolver(), sshApprovalTargetGuard = guard)
            val definition = Tool("terminal_execute", "", execute = { executed = true; emptyList() })
            val result = dispatcher.execute(bound, definition)
            assertFalse(executed)
            assertTrue(result!!.output.toString().contains("approvedTargetChanged"))
            assertTrue(result.output.toString().contains("\"status\":\"failed\""))
            assertFalse(guard.isCurrent(call.copy(approvalState = ToolApprovalState.Approved)))

            // Default runtime changes cannot turn an approved local job into an SSH job.
            var runtime = TerminalRuntimeKind.BUILTIN_ALPINE
            val defaultGuard = SshApprovalTargetGuard(store) { runtime }
            val job = defaultGuard.capture(call.copy(toolName = "terminal_job_start", input = """{"command":"id"}"""))
            runtime = TerminalRuntimeKind.REMOTE_SSH
            assertFalse(defaultGuard.isCurrent(job.copy(approvalState = ToolApprovalState.Approved)))
        } finally { scope.cancel() }
    }

    private fun profile(id: String) = SshProfile(id, id, "localhost", username = "amber",
        authMethod = SshAuthMethod.PASSWORD, createdAtMs = 1L, updatedAtMs = 1L)
}
