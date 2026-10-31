package app.amber.feature.ui.pages.setting

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.amber.agent.R
import app.amber.core.settings.ssh.SshProfileStore
import app.amber.feature.terminal.SshHostTrust
import app.amber.feature.terminal.SshKey
import app.amber.feature.terminal.SshKeyGenType
import app.amber.feature.terminal.SshKeyPairs
import app.amber.feature.terminal.SshKeysState
import app.amber.feature.terminal.SshProfile
import app.amber.feature.terminal.SshProfilesState
import app.amber.feature.terminal.SshTrustPolicy
import app.amber.feature.terminal.TerminalJobSnapshot
import app.amber.feature.terminal.TerminalRuntime
import app.amber.feature.terminal.TerminalRuntimeKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal class SshProfilesScreenSession(
    private val context: android.content.Context,
    private val sshProfileStore: SshProfileStore,
    private val terminalRuntime: TerminalRuntime,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val onNotice: (String, Boolean) -> Unit,
) {
    private fun notice(message: String, error: Boolean = false) = onNotice(message, error)
    val profileLoadFlow = run {
        sshProfileStore.state
            .map<SshProfilesState, SshProfilesLoadState> { SshProfilesLoadState.Ready(it) }
            .catch { error ->
                if (error is CancellationException) throw error
                emit(SshProfilesLoadState.Failed(error))
            }
    }
    val keysFlow = sshProfileStore.keyStore.state
    private val savedText get() = context.getString(R.string.setting_sandbox_ssh_saved)
    private val deletedText get() = context.getString(R.string.setting_sandbox_ssh_deleted)
    private val defaultSavedText get() = context.getString(R.string.setting_sandbox_ssh_default_saved)
    private val hostKeySavedText get() = context.getString(R.string.setting_sandbox_ssh_host_key_saved)
    private val profileMissingText get() = context.getString(R.string.setting_sandbox_ssh_profile_missing)
    private val untrustedText get() = context.getString(R.string.setting_sandbox_ssh_untrusted_execute)
    private val noCommandText get() = context.getString(R.string.setting_sandbox_ssh_no_command)

    var operationBusy by mutableStateOf(false)
    var editorRequest by mutableStateOf<SshProfileEditorRequest?>(null)
    var showKeyGenerator by mutableStateOf(false)
    var recentlyGeneratedKey by mutableStateOf<SshKey?>(null)
    var pendingDelete by mutableStateOf<String?>(null)
    var pendingTrust by mutableStateOf<PendingHostKeyTrust?>(null)
    var selectedProfileId by mutableStateOf<String?>(null)
    var command by mutableStateOf("")
    var jobActionBusy by mutableStateOf(false)
    var jobSnapshot by mutableStateOf<TerminalJobSnapshot?>(null)

    fun reconcileSelection(profiles: List<SshProfile>, defaultId: String?) {
        if (profiles.none { it.id == selectedProfileId }) {
            selectedProfileId = defaultId?.takeIf { id -> profiles.any { it.id == id } }
                ?: profiles.firstOrNull()?.id
        }
    }
    suspend fun pollJob(jobId: String?) {
        val id = jobId ?: return
        while (true) {
            val latest = try {
                terminalRuntime.readJob(id)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                break
            }
            jobSnapshot = latest
            if (!latest.running) break
            delay(500L)
        }
    }
    fun showError(error: Throwable, probe: Boolean = false) {
        val message = context.getString(
            if (probe) R.string.setting_sandbox_ssh_probe_failed else R.string.setting_sandbox_ssh_error,
            error.message ?: error::class.java.simpleName,
        )
        notice(message, error = true)
    }

    fun makeDefault(profile: SshProfile) {
        operationBusy = true
        scope.launch {
            try {
                sshProfileStore.selectDefault(profile.id)
                selectedProfileId = profile.id
                notice(defaultSavedText)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                showError(error)
            } finally {
                operationBusy = false
            }
        }
    }

    fun probe(profile: SshProfile) {
        operationBusy = true
        scope.launch {
            try {
                val probe = terminalRuntime.probeSshHostKey(profile.id)
                val trust = SshTrustPolicy.evaluate(probe.profile, probe.fingerprint)
                if (trust is SshHostTrust.Trusted) {
                    notice(
                        context.getString(
                            R.string.setting_sandbox_ssh_probe_trusted_toast,
                            displayFingerprintSafely(probe.fingerprint),
                        ),
                    )
                } else {
                    pendingTrust = PendingHostKeyTrust(probe, trust)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                showError(error, probe = true)
            } finally {
                operationBusy = false
            }
        }
    }

    fun runCommand(profiles: List<SshProfile>, selectedProfile: SshProfile?) {
        if (selectedProfile != null && !jobActionBusy) {
            jobActionBusy = true
            scope.launch {
                try {
                    val current = profiles.firstOrNull { it.id == selectedProfile.id }
                        ?: error(profileMissingText)
                    check(
                        SshTrustPolicy.evaluate(
                            current,
                            current.acceptedHostKeyFingerprint,
                        ) is SshHostTrust.Trusted,
                    ) { untrustedText }
                    check(command.isNotBlank()) { noCommandText }
                    jobSnapshot = terminalRuntime.startJob(
                        command = command.trim(),
                        runtime = TerminalRuntimeKind.REMOTE_SSH,
                        sshProfileId = current.id,
                        timeoutMillis = SSH_COMMAND_TIMEOUT_MS,
                    )
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    showError(error)
                } finally {
                    jobActionBusy = false
                }
            }
        }
    }

    fun stopJob() {
        val id = jobSnapshot?.jobId
        if (id != null && jobSnapshot?.running == true && !jobActionBusy) {
            jobActionBusy = true
            scope.launch {
                try {
                    jobSnapshot = terminalRuntime.stopJob(id)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    showError(error)
                } finally {
                    jobActionBusy = false
                }
            }
        }
    }

    fun save(profile: SshProfile, password: String?, privateKey: String?, passphrase: String?) {
        operationBusy = true
        scope.launch {
            try {
                sshProfileStore.save(profile, password, privateKey, passphrase)
                editorRequest = null
                notice(savedText)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                showError(error)
            } finally {
                operationBusy = false
            }
        }
    }

    fun delete(id: String) {
        if (!operationBusy) {
            operationBusy = true
            scope.launch {
                try {
                    sshProfileStore.delete(id)
                    if (selectedProfileId == id) selectedProfileId = null
                    pendingDelete = null
                    notice(deletedText)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    showError(error)
                } finally {
                    operationBusy = false
                }
            }
        }
    }

    fun acceptTrust(pending: PendingHostKeyTrust) {
        if (!operationBusy) {
            operationBusy = true
            scope.launch {
                try {
                    val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
                    check(keyguard != null && !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked) { "Unlock the device before trusting an SSH host." }
                    sshProfileStore.acceptHostKey(pending.probe.profile, pending.probe.fingerprint, pending.probe.route)
                    pendingTrust = null
                    notice(hostKeySavedText)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    showError(error, probe = true)
                } finally {
                    operationBusy = false
                }
            }
        }
    }

    fun generateKey(name: String, type: SshKeyGenType) {
        if (!operationBusy) {
            operationBusy = true
            scope.launch {
                try {
                    val generated = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        SshKeyPairs.generate(name = name, type = type)
                    }
                    val persisted = sshProfileStore.keyStore.save(
                        key = generated.key,
                        privateKey = generated.privateKeyOpenSsh,
                        passphrase = null,
                    )
                    recentlyGeneratedKey = persisted
                    notice("SSH 密钥已生成：${persisted.name}")
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    showError(error)
                } finally {
                    operationBusy = false
                }
            }
        }
    }

    fun deleteKey(key: SshKey) {
        if (!operationBusy) {
            operationBusy = true
            scope.launch {
                try {
                    sshProfileStore.keyStore.delete(key.id)
                    notice("已删除密钥：${key.name}")
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    showError(error)
                } finally {
                    operationBusy = false
                }
            }
        }
    }

    fun notifyPublicKeyCopied(key: SshKey) {
        notice("已复制公钥 (${key.name}) 到剪贴板")
    }
}

internal sealed interface SshProfilesLoadState {
    data object Loading : SshProfilesLoadState
    data class Ready(val state: SshProfilesState) : SshProfilesLoadState
    data class Failed(val error: Throwable) : SshProfilesLoadState
}

private const val SSH_COMMAND_TIMEOUT_MS = 15 * 60_000L
