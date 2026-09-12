package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Trash2
import app.amber.agent.R
import app.amber.core.settings.ssh.SshProfileStore
import app.amber.feature.terminal.SshAuthMethod
import app.amber.feature.terminal.SshHostKeyProbe
import app.amber.feature.terminal.SshHostTrust
import app.amber.feature.terminal.SshProfile
import app.amber.feature.terminal.SshProfilesState
import app.amber.feature.terminal.SshTrustPolicy
import app.amber.feature.terminal.TerminalJobSnapshot
import app.amber.feature.terminal.TerminalJobStatus
import app.amber.feature.terminal.TerminalRuntime
import app.amber.feature.terminal.TerminalRuntimeKind
import app.amber.feature.ui.components.ui.CardGroup
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ui.Select
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.theme.CustomColors
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid
import org.koin.compose.koinInject

@Composable
fun SettingSshProfilesSection(
    terminalRuntime: TerminalRuntime = koinInject(),
    sshProfileStore: SshProfileStore = koinInject(),
) {
    val profileLoadFlow = remember(sshProfileStore) {
        sshProfileStore.state
            .map<SshProfilesState, SshProfilesLoadState> { SshProfilesLoadState.Ready(it) }
            .catch { error ->
                if (error is CancellationException) throw error
                emit(SshProfilesLoadState.Failed(error))
            }
    }
    val profileLoad by profileLoadFlow.collectAsStateWithLifecycle(
        initialValue = SshProfilesLoadState.Loading,
    )
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val state = (profileLoad as? SshProfilesLoadState.Ready)?.state
    val loadError = (profileLoad as? SshProfilesLoadState.Failed)?.error
    val profiles = state?.profiles.orEmpty()
    val defaultId = state?.defaultProfileId
    val ready = state != null
    val savedText = context.getString(R.string.setting_sandbox_ssh_saved)
    val deletedText = context.getString(R.string.setting_sandbox_ssh_deleted)
    val defaultSavedText = context.getString(R.string.setting_sandbox_ssh_default_saved)
    val hostKeySavedText = context.getString(R.string.setting_sandbox_ssh_host_key_saved)
    val profileMissingText = context.getString(R.string.setting_sandbox_ssh_profile_missing)
    val untrustedText = context.getString(R.string.setting_sandbox_ssh_untrusted_execute)
    val noCommandText = context.getString(R.string.setting_sandbox_ssh_no_command)

    var operationBusy by remember { mutableStateOf(false) }
    var editorRequest by remember { mutableStateOf<SshProfileEditorRequest?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var pendingTrust by remember { mutableStateOf<PendingHostKeyTrust?>(null) }
    var selectedProfileId by remember { mutableStateOf<String?>(null) }
    var command by remember { mutableStateOf("") }
    var jobActionBusy by remember { mutableStateOf(false) }
    var jobSnapshot by remember { mutableStateOf<TerminalJobSnapshot?>(null) }

    val selectedProfile = profiles.firstOrNull { it.id == selectedProfileId }
    LaunchedEffect(profiles, defaultId) {
        if (profiles.none { it.id == selectedProfileId }) {
            selectedProfileId = defaultId?.takeIf { id -> profiles.any { it.id == id } }
                ?: profiles.firstOrNull()?.id
        }
    }
    LaunchedEffect(jobSnapshot?.jobId) {
        val id = jobSnapshot?.jobId ?: return@LaunchedEffect
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
        toaster.show(message, type = ToastType.Error)
    }

    LaunchedEffect(loadError) {
        loadError?.let { showError(it) }
    }

    CardGroup(title = { SectionLabel(stringResource(R.string.setting_sandbox_ssh_section)) }) {
        rawItem {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_sandbox_ssh_desc),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!ready) {
                    Text(
                        text = if (loadError == null) {
                            stringResource(R.string.setting_sandbox_ssh_loading)
                        } else {
                            stringResource(
                                R.string.setting_sandbox_ssh_load_failed,
                                loadError.message ?: loadError::class.java.simpleName,
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (loadError == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                } else if (profiles.isEmpty()) {
                    Text(
                        text = stringResource(R.string.setting_sandbox_ssh_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { editorRequest = SshProfileEditorRequest.new() },
                    enabled = ready && !operationBusy,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Icon(Lucide.Plus, contentDescription = null)
                    Text(
                        text = stringResource(R.string.setting_sandbox_ssh_add),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        profiles.forEach { profile ->
            rawItem {
                SshProfileCard(
                    profile = profile,
                    isDefault = profile.id == defaultId,
                    isSelected = profile.id == selectedProfileId,
                    enabled = ready && !operationBusy,
                    onSelect = { selectedProfileId = profile.id },
                    onEdit = { editorRequest = SshProfileEditorRequest.existing(profile) },
                    onDelete = { pendingDelete = profile.id },
                    onMakeDefault = {
                        operationBusy = true
                        scope.launch {
                            try {
                                sshProfileStore.selectDefault(profile.id)
                                selectedProfileId = profile.id
                                toaster.show(defaultSavedText)
                            } catch (error: Throwable) {
                                if (error is CancellationException) throw error
                                showError(error)
                            } finally {
                                operationBusy = false
                            }
                        }
                    },
                    onProbe = {
                        operationBusy = true
                        scope.launch {
                            try {
                                val probe = terminalRuntime.probeSshHostKey(profile.id)
                                val trust = SshTrustPolicy.evaluate(probe.profile, probe.fingerprint)
                                if (trust is SshHostTrust.Trusted) {
                                    toaster.show(
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
                    },
                )
            }
        }
    }

    if (ready && selectedProfile != null) {
        SshCommandPanel(
            profiles = profiles,
            selectedProfile = selectedProfile,
            command = command,
            snapshot = jobSnapshot,
            actionBusy = jobActionBusy,
            onProfileSelected = { selectedProfileId = it.id },
            onCommandChanged = { command = it },
            onRun = {
                if (!jobActionBusy) {
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
            },
            onStop = {
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
            },
        )
    }

    editorRequest?.let { request ->
        SshProfileEditorDialog(
            request = request,
            saving = operationBusy,
            onDismiss = { if (!operationBusy) editorRequest = null },
            onSave = { profile, password, privateKey, passphrase ->
                operationBusy = true
                scope.launch {
                    try {
                        sshProfileStore.save(profile, password, privateKey, passphrase)
                        editorRequest = null
                        toaster.show(savedText)
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        showError(error)
                    } finally {
                        operationBusy = false
                    }
                }
            },
        )
    }

    pendingDelete?.let { id ->
        val profile = profiles.firstOrNull { it.id == id }
        if (profile == null) {
            pendingDelete = null
        } else {
            AlertDialog(
                onDismissRequest = { if (!operationBusy) pendingDelete = null },
                title = { Text(stringResource(R.string.setting_sandbox_ssh_delete_title)) },
                text = { Text(stringResource(R.string.setting_sandbox_ssh_delete_message, profile.name)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (!operationBusy) {
                                operationBusy = true
                                scope.launch {
                                    try {
                                        sshProfileStore.delete(id)
                                        if (selectedProfileId == id) selectedProfileId = null
                                        pendingDelete = null
                                        toaster.show(deletedText)
                                    } catch (error: Throwable) {
                                        if (error is CancellationException) throw error
                                        showError(error)
                                    } finally {
                                        operationBusy = false
                                    }
                                }
                            }
                        },
                        enabled = !operationBusy,
                    ) { Text(stringResource(R.string.setting_sandbox_ssh_delete_confirm)) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { pendingDelete = null },
                        enabled = !operationBusy,
                    ) { Text(stringResource(R.string.setting_sandbox_ssh_cancel)) }
                },
            )
        }
    }

    pendingTrust?.let { pending ->
        HostKeyTrustDialog(
            pending = pending,
            accepting = operationBusy,
            onDismiss = { if (!operationBusy) pendingTrust = null },
            onAccept = {
                if (!operationBusy) {
                    operationBusy = true
                    scope.launch {
                        try {
                            sshProfileStore.acceptHostKey(pending.probe.profile, pending.probe.fingerprint)
                            pendingTrust = null
                            toaster.show(hostKeySavedText)
                        } catch (error: Throwable) {
                            if (error is CancellationException) throw error
                            showError(error, probe = true)
                        } finally {
                            operationBusy = false
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun SshProfileCard(
    profile: SshProfile,
    isDefault: Boolean,
    isSelected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMakeDefault: () -> Unit,
    onProbe: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(enabled = enabled, onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${profile.username}@${profile.host}:${profile.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isDefault) {
                    AssistChip(
                        onClick = onSelect,
                        enabled = enabled,
                        label = { Text(stringResource(R.string.setting_sandbox_ssh_default)) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val trusted = SshTrustPolicy.evaluate(
                    profile,
                    profile.acceptedHostKeyFingerprint,
                ) is SshHostTrust.Trusted
                Icon(
                    imageVector = if (trusted) Lucide.CircleCheck else Lucide.CircleAlert,
                    contentDescription = null,
                    tint = if (trusted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(
                        if (trusted) R.string.setting_sandbox_ssh_host_key_trusted
                        else R.string.setting_sandbox_ssh_host_key_untrusted,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        if (profile.authMethod == SshAuthMethod.PASSWORD) {
                            R.string.setting_sandbox_ssh_auth_password
                        } else {
                            R.string.setting_sandbox_ssh_auth_private_key
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedButton(
                    onClick = onProbe,
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(Lucide.RefreshCw, contentDescription = null)
                    Text(
                        stringResource(R.string.setting_sandbox_ssh_probe),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                if (!isDefault) {
                    TextButton(onClick = onMakeDefault, enabled = enabled) {
                        Text(stringResource(R.string.setting_sandbox_ssh_make_default))
                    }
                }
                IconButton(onClick = onEdit, enabled = enabled) {
                    Icon(Lucide.Pencil, stringResource(R.string.setting_sandbox_ssh_edit))
                }
                IconButton(onClick = onDelete, enabled = enabled) {
                    Icon(Lucide.Trash2, stringResource(R.string.setting_sandbox_ssh_delete))
                }
            }
            if (isSelected) {
                Text(
                    text = stringResource(R.string.setting_sandbox_ssh_selected),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SshCommandPanel(
    profiles: List<SshProfile>,
    selectedProfile: SshProfile,
    command: String,
    snapshot: TerminalJobSnapshot?,
    actionBusy: Boolean,
    onProfileSelected: (SshProfile) -> Unit,
    onCommandChanged: (String) -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
) {
    CardGroup(title = { SectionLabel(stringResource(R.string.setting_sandbox_ssh_command_title)) }) {
        rawItem {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_sandbox_ssh_command_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Select(
                    options = profiles,
                    selectedOption = selectedProfile,
                    onOptionSelected = onProfileSelected,
                    modifier = Modifier.fillMaxWidth(),
                    optionToString = { "${it.name} (${it.host}:${it.port})" },
                    leading = { Icon(Lucide.Server, contentDescription = null) },
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = onCommandChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.setting_sandbox_ssh_command_label)) },
                    placeholder = { Text(stringResource(R.string.setting_sandbox_ssh_command_hint)) },
                    singleLine = true,
                    enabled = !actionBusy,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onRun,
                        enabled = command.isNotBlank() && !actionBusy && snapshot?.running != true,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Icon(Lucide.Play, contentDescription = null)
                        Text(stringResource(R.string.setting_sandbox_ssh_run), Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(
                        onClick = onStop,
                        enabled = snapshot?.running == true && !actionBusy,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Icon(Lucide.Square, contentDescription = null)
                        Text(stringResource(R.string.setting_sandbox_ssh_stop), Modifier.padding(start = 6.dp))
                    }
                }
                snapshot?.let { SshJobOutput(it) }
            }
        }
    }
}

@Composable
private fun SshJobOutput(snapshot: TerminalJobSnapshot) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.setting_sandbox_ssh_job_status, jobStatusLabel(snapshot.status)),
                style = MaterialTheme.typography.labelMedium,
            )
            snapshot.error?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                snapshot.outputTail.ifBlank { stringResource(R.string.setting_sandbox_ssh_job_no_output) },
                modifier = Modifier
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun HostKeyTrustDialog(
    pending: PendingHostKeyTrust,
    accepting: Boolean,
    onDismiss: () -> Unit,
    onAccept: () -> Unit,
) {
    val mismatch = pending.trust as? SshHostTrust.Mismatch
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (mismatch == null) Lucide.CircleAlert else Lucide.Server, null) },
        title = {
            Text(
                stringResource(
                    if (mismatch == null) R.string.setting_sandbox_ssh_probe_title
                    else R.string.setting_sandbox_ssh_probe_mismatch_title,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.setting_sandbox_ssh_probe_endpoint,
                        pending.probe.profile.host,
                        pending.probe.profile.port,
                    ),
                )
                Text(stringResource(R.string.setting_sandbox_ssh_probe_algorithm, pending.probe.algorithm))
                if (mismatch == null) {
                    Text(stringResource(R.string.setting_sandbox_ssh_probe_untrusted))
                } else {
                    Text(stringResource(R.string.setting_sandbox_ssh_probe_mismatch_desc))
                    Text(
                        stringResource(
                            R.string.setting_sandbox_ssh_probe_old_fingerprint,
                            displayFingerprintSafely(mismatch.acceptedFingerprint),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    stringResource(
                        R.string.setting_sandbox_ssh_probe_new_fingerprint,
                        displayFingerprintSafely(pending.probe.fingerprint),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            Button(onClick = onAccept, enabled = !accepting) {
                Text(
                    stringResource(
                        if (mismatch == null) R.string.setting_sandbox_ssh_probe_accept
                        else R.string.setting_sandbox_ssh_probe_accept_new,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !accepting) {
                Text(stringResource(R.string.setting_sandbox_ssh_cancel))
            }
        },
    )
}

@Composable
private fun SshProfileEditorDialog(
    request: SshProfileEditorRequest,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (SshProfile, String?, String?, String?) -> Unit,
) {
    val existing = request.profile
    var name by remember(request.id) { mutableStateOf(existing?.name.orEmpty()) }
    var host by remember(request.id) { mutableStateOf(existing?.host.orEmpty()) }
    var port by remember(request.id) { mutableStateOf(existing?.port?.toString() ?: "22") }
    var username by remember(request.id) { mutableStateOf(existing?.username.orEmpty()) }
    var authMethod by remember(request.id) { mutableStateOf(existing?.authMethod ?: SshAuthMethod.PASSWORD) }
    // Credentials stay in ordinary remember state and are cleared on either button path.
    var password by remember(request.id) { mutableStateOf("") }
    var privateKey by remember(request.id) { mutableStateOf("") }
    var passphrase by remember(request.id) { mutableStateOf("") }
    var clearPassphrase by remember(request.id) { mutableStateOf(false) }
    var showPassword by remember(request.id) { mutableStateOf(false) }
    var showPassphrase by remember(request.id) { mutableStateOf(false) }
    var formError by remember(request.id) { mutableStateOf<String?>(null) }
    val invalidProfileMessage = stringResource(R.string.setting_sandbox_ssh_invalid_profile)
    val parsedPort = port.toIntOrNull()
    val authChanged = existing != null && existing.authMethod != authMethod
    val secretRequired = existing == null || authChanged
    val validationMessage = when {
        name.isBlank() -> stringResource(R.string.setting_sandbox_ssh_name_required)
        host.isBlank() -> stringResource(R.string.setting_sandbox_ssh_host_required)
        username.isBlank() -> stringResource(R.string.setting_sandbox_ssh_username_required)
        parsedPort == null || parsedPort !in 1..65535 -> stringResource(R.string.setting_sandbox_ssh_port_invalid)
        authMethod == SshAuthMethod.PASSWORD && secretRequired && password.isBlank() ->
            stringResource(R.string.setting_sandbox_ssh_password_required)
        authMethod == SshAuthMethod.PRIVATE_KEY && secretRequired && privateKey.isBlank() ->
            stringResource(R.string.setting_sandbox_ssh_private_key_required)
        else -> null
    }

    fun clearSecrets() {
        password = ""
        privateKey = ""
        passphrase = ""
        clearPassphrase = false
        showPassword = false
        showPassphrase = false
    }

    AlertDialog(
        onDismissRequest = {
            if (!saving) {
                clearSecrets()
                onDismiss()
            }
        },
        title = {
            Text(
                stringResource(
                    if (existing == null) R.string.setting_sandbox_ssh_editor_new
                    else R.string.setting_sandbox_ssh_editor_edit,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.setting_sandbox_ssh_name)) },
                    singleLine = true,
                    enabled = !saving,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.setting_sandbox_ssh_host)) },
                    singleLine = true,
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { value -> if (value.all(Char::isDigit)) port = value },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.setting_sandbox_ssh_port)) },
                    singleLine = true,
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.setting_sandbox_ssh_username)) },
                    singleLine = true,
                    enabled = !saving,
                )
                Text(stringResource(R.string.setting_sandbox_ssh_auth_method), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = authMethod == SshAuthMethod.PASSWORD,
                        onClick = { authMethod = SshAuthMethod.PASSWORD },
                        enabled = !saving,
                        label = { Text(stringResource(R.string.setting_sandbox_ssh_auth_password)) },
                    )
                    FilterChip(
                        selected = authMethod == SshAuthMethod.PRIVATE_KEY,
                        onClick = { authMethod = SshAuthMethod.PRIVATE_KEY },
                        enabled = !saving,
                        label = { Text(stringResource(R.string.setting_sandbox_ssh_auth_private_key)) },
                    )
                }
                if (authMethod == SshAuthMethod.PASSWORD) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.setting_sandbox_ssh_password)) },
                        supportingText = { Text(stringResource(R.string.setting_sandbox_ssh_secret_hint)) },
                        singleLine = true,
                        enabled = !saving,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Lucide.EyeOff else Lucide.Eye,
                                    stringResource(if (showPassword) R.string.setting_sandbox_ssh_hide_secret else R.string.setting_sandbox_ssh_show_secret),
                                )
                            }
                        },
                    )
                } else {
                    OutlinedTextField(
                        value = privateKey,
                        onValueChange = { privateKey = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        label = { Text(stringResource(R.string.setting_sandbox_ssh_private_key)) },
                        supportingText = { Text(stringResource(R.string.setting_sandbox_ssh_private_key_hint)) },
                        enabled = !saving,
                        minLines = 4,
                        maxLines = 8,
                    )
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.setting_sandbox_ssh_passphrase)) },
                        supportingText = { Text(stringResource(R.string.setting_sandbox_ssh_passphrase_hint)) },
                        singleLine = true,
                        enabled = !saving && !clearPassphrase,
                        visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showPassphrase = !showPassphrase }) {
                                Icon(
                                    if (showPassphrase) Lucide.EyeOff else Lucide.Eye,
                                    stringResource(if (showPassphrase) R.string.setting_sandbox_ssh_hide_secret else R.string.setting_sandbox_ssh_show_secret),
                                )
                            }
                        },
                    )
                    if (existing != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = clearPassphrase,
                                onCheckedChange = {
                                    clearPassphrase = it
                                    if (it) passphrase = ""
                                },
                                enabled = !saving,
                            )
                            Text(stringResource(R.string.setting_sandbox_ssh_passphrase_clear))
                        }
                    }
                }
                (validationMessage ?: formError)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val portNumber = parsedPort ?: return@Button
                    val now = System.currentTimeMillis()
                    val endpointChanged = existing != null &&
                        (existing.host != host.trim() || existing.port != portNumber)
                    val saved = runCatching { (existing ?: SshProfile(
                        id = request.id,
                        name = name.trim(),
                        host = host.trim(),
                        port = portNumber,
                        username = username.trim(),
                        authMethod = authMethod,
                        createdAtMs = now,
                        updatedAtMs = now,
                    )).copy(
                        name = name.trim(),
                        host = host.trim(),
                        port = portNumber,
                        username = username.trim(),
                        authMethod = authMethod,
                        acceptedHostKeyFingerprint = if (endpointChanged) null else existing?.acceptedHostKeyFingerprint,
                        updatedAtMs = now,
                    )
                    }.getOrElse {
                        formError = invalidProfileMessage
                        return@Button
                    }
                    val newPassword = password.takeIf { it.isNotEmpty() }
                    val newPrivateKey = privateKey.takeIf { it.isNotEmpty() }
                    val newPassphrase = when {
                        clearPassphrase -> ""
                        passphrase.isNotEmpty() -> passphrase
                        else -> null
                    }
                    clearSecrets()
                    onSave(saved, newPassword, newPrivateKey, newPassphrase)
                },
                enabled = validationMessage == null && !saving,
            ) { Text(stringResource(R.string.setting_sandbox_ssh_save)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (!saving) {
                        clearSecrets()
                        onDismiss()
                    }
                },
                enabled = !saving,
            ) { Text(stringResource(R.string.setting_sandbox_ssh_cancel)) }
        },
    )
}

private data class SshProfileEditorRequest(val id: String, val profile: SshProfile?) {
    companion object {
        fun new() = SshProfileEditorRequest(Uuid.random().toString(), null)
        fun existing(profile: SshProfile) = SshProfileEditorRequest(profile.id, profile)
    }
}

private sealed interface SshProfilesLoadState {
    data object Loading : SshProfilesLoadState
    data class Ready(val state: SshProfilesState) : SshProfilesLoadState
    data class Failed(val error: Throwable) : SshProfilesLoadState
}

private data class PendingHostKeyTrust(val probe: SshHostKeyProbe, val trust: SshHostTrust)

@Composable
private fun jobStatusLabel(status: TerminalJobStatus): String = when (status) {
    TerminalJobStatus.QUEUED -> stringResource(R.string.setting_sandbox_ssh_status_queued)
    TerminalJobStatus.RUNNING -> stringResource(R.string.setting_sandbox_ssh_status_running)
    TerminalJobStatus.COMPLETED -> stringResource(R.string.setting_sandbox_ssh_status_completed)
    TerminalJobStatus.FAILED -> stringResource(R.string.setting_sandbox_ssh_status_failed)
    TerminalJobStatus.CANCELLED -> stringResource(R.string.setting_sandbox_ssh_status_cancelled)
    TerminalJobStatus.TIMED_OUT -> stringResource(R.string.setting_sandbox_ssh_status_timed_out)
    TerminalJobStatus.INTERRUPTED -> stringResource(R.string.setting_sandbox_ssh_status_interrupted)
}

private const val SSH_COMMAND_TIMEOUT_MS = 15 * 60_000L

private fun displayFingerprintSafely(raw: String): String =
    runCatching { SshTrustPolicy.displayFingerprint(raw) }.getOrDefault(raw)
