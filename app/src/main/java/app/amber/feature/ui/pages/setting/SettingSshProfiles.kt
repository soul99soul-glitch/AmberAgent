package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import app.amber.agent.R
import app.amber.core.settings.ssh.SshProfileStore
import app.amber.feature.terminal.SshKeysState
import app.amber.feature.terminal.TerminalRuntime
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberType
import com.dokar.sonner.ToastType
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingSshProfilesSection(
    terminalRuntime: TerminalRuntime = koinInject(),
    sshProfileStore: SshProfileStore = koinInject(),
    moreBusy: Boolean = false,
    moreSettings: @Composable ColumnScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val session = remember(sshProfileStore, terminalRuntime, context, scope, toaster) {
        SshProfilesScreenSession(context, sshProfileStore, terminalRuntime, scope) { message, error ->
            if (error) toaster.show(message, type = ToastType.Error) else toaster.show(message)
        }
    }
    val profileLoad by session.profileLoadFlow.collectAsStateWithLifecycle(initialValue = SshProfilesLoadState.Loading)
    val keysState by session.keysFlow.collectAsStateWithLifecycle(initialValue = SshKeysState())
    val keys = keysState.keys
    val state = (profileLoad as? SshProfilesLoadState.Ready)?.state
    val loadError = (profileLoad as? SshProfilesLoadState.Failed)?.error
    val profiles = state?.profiles.orEmpty()
    val defaultId = state?.defaultProfileId
    val ready = state != null
    val selectedProfile = profiles.firstOrNull { it.id == session.selectedProfileId }
    var showMoreSettings by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(profiles, defaultId) { session.reconcileSelection(profiles, defaultId) }
    LaunchedEffect(session.jobSnapshot?.jobId) { session.pollJob(session.jobSnapshot?.jobId) }
    LaunchedEffect(loadError) { loadError?.let { session.showError(it) } }

    val colors = workspaceColors()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SandboxCard {
            SandboxSettingsRow(
                title = stringResource(R.string.setting_sandbox_ssh_section),
                trailing = {
                    SandboxAction(
                        label = stringResource(R.string.setting_sandbox_ssh_add),
                        onClick = { session.editorRequest = SshProfileEditorRequest.new() },
                        enabled = ready && !session.operationBusy,
                        primary = true,
                        compact = true,
                        icon = Lucide.Plus,
                    )
                },
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp),
                color = colors.hairline.copy(alpha = 0.55f),
            )
            if (!ready || profiles.isEmpty()) {
                Text(
                    text = if (!ready) {
                        if (loadError == null) {
                            stringResource(R.string.setting_sandbox_ssh_loading)
                        } else {
                            stringResource(
                                R.string.setting_sandbox_ssh_load_failed,
                                loadError.message ?: loadError::class.java.simpleName,
                            )
                        }
                    } else {
                        stringResource(R.string.setting_sandbox_ssh_empty)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    style = LocalAmberType.current.secondary,
                    color = if (loadError != null) colors.red else colors.muted,
                )
            }
            profiles.forEachIndexed { index, profile ->
                SshProfileCard(
                    profile = profile,
                    allProfiles = profiles,
                    isDefault = profile.id == defaultId,
                    isSelected = profile.id == session.selectedProfileId,
                    enabled = ready && !session.operationBusy,
                    onSelect = { session.selectedProfileId = profile.id },
                    onEdit = { session.editorRequest = SshProfileEditorRequest.existing(profile) },
                    onDelete = { session.pendingDelete = profile.id },
                    onMakeDefault = { session.makeDefault(profile) },
                    onProbe = { session.probe(profile) },
                )
                if (index != profiles.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = colors.hairline.copy(alpha = 0.55f),
                    )
                }
            }
        }

        SshKeysCard(
            keys = keys,
            profiles = profiles,
            busy = session.operationBusy,
            onGenerateClick = {
                session.recentlyGeneratedKey = null
                session.showKeyGenerator = true
            },
            onDeleteKey = { key -> session.deleteKey(key) },
            onCopiedPublicKey = { key -> session.notifyPublicKeyCopied(key) },
        )

        TextButton(
            onClick = { showMoreSettings = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.setting_sandbox_more_settings), style = LocalAmberType.current.body)
            if (session.jobSnapshot?.running == true || session.jobActionBusy || moreBusy) {
                CircularProgressIndicator(Modifier.padding(start = 8.dp).size(14.dp), strokeWidth = 1.5.dp)
            } else {
                Icon(Lucide.ChevronRight, null, Modifier.padding(start = 6.dp).size(16.dp))
            }
        }
    }

    LaunchedEffect(session.jobSnapshot?.running, session.jobActionBusy, moreBusy) {
        if (session.jobSnapshot?.running == true || session.jobActionBusy || moreBusy) showMoreSettings = true
    }
    if (showMoreSettings) {
        ModalBottomSheet(
            onDismissRequest = { showMoreSettings = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.canvas,
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.setting_sandbox_more_settings),
                    style = LocalAmberType.current.screenTitle,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                if (selectedProfile != null || session.jobSnapshot?.running == true || session.jobActionBusy) {
                    SshCommandPanel(
                        profiles = profiles,
                        selectedProfile = selectedProfile,
                        command = session.command,
                        snapshot = session.jobSnapshot,
                        actionBusy = session.jobActionBusy,
                        onProfileSelected = { session.selectedProfileId = it.id },
                        onCommandChanged = { session.command = it },
                        onRun = { session.runCommand(profiles, selectedProfile) },
                        onStop = { session.stopJob() },
                    )
                }
                moreSettings()
            }
        }
    }

    session.editorRequest?.let { request ->
        SshProfileEditorDialog(
            request = request,
            saving = session.operationBusy,
            availableKeys = keys,
            availableJumpProfiles = profiles,
            onDismiss = { if (!session.operationBusy) session.editorRequest = null },
            onSave = { profile, password, privateKey, passphrase -> session.save(profile, password, privateKey, passphrase) },
        )
    }

    if (session.showKeyGenerator) {
        SshKeyGenerateDialog(
            busy = session.operationBusy,
            generatedKey = session.recentlyGeneratedKey,
            onDismiss = {
                session.showKeyGenerator = false
                session.recentlyGeneratedKey = null
            },
            onGenerate = { name, type -> session.generateKey(name, type) },
            onCopyGeneratedKey = { key -> session.notifyPublicKeyCopied(key) },
        )
    }

    session.pendingDelete?.let { id ->
        val profile = profiles.firstOrNull { it.id == id }
        if (profile == null) {
            session.pendingDelete = null
        } else {
            AlertDialog(
                onDismissRequest = { if (!session.operationBusy) session.pendingDelete = null },
                title = { Text(stringResource(R.string.setting_sandbox_ssh_delete_title)) },
                text = { Text(stringResource(R.string.setting_sandbox_ssh_delete_message, profile.name)) },
                confirmButton = {
                    SandboxAction(
                        label = stringResource(R.string.setting_sandbox_ssh_delete_confirm),
                        onClick = { session.delete(id) },
                        enabled = !session.operationBusy,
                        icon = Lucide.Trash2,
                    )
                },
                dismissButton = {
                    SandboxAction(
                        label = stringResource(R.string.setting_sandbox_ssh_cancel),
                        onClick = { session.pendingDelete = null },
                        enabled = !session.operationBusy,
                    )
                },
            )
        }
    }

    session.pendingTrust?.let { pending ->
        HostKeyTrustDialog(
            pending = pending,
            accepting = session.operationBusy,
            onDismiss = { if (!session.operationBusy) session.pendingTrust = null },
            onAccept = { session.acceptTrust(pending) },
        )
    }
}
