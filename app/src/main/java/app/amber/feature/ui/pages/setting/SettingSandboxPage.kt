package app.amber.feature.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.R
import app.amber.core.utils.plus
import app.amber.feature.terminal.AlpineRuntimeInstaller
import app.amber.feature.terminal.InstallStatus
import app.amber.feature.terminal.TerminalRuntime
import app.amber.feature.terminal.TerminalRuntimeKind
import app.amber.feature.terminal.TermuxRuntimeStatus
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.feature.workspace.WorkspaceManager
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CodeXml
import com.composables.icons.lucide.DatabaseZap
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Lucide
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingSandboxPage(
    vm: SettingVM = koinViewModel(),
    workspaceManager: WorkspaceManager = koinInject(),
    alpineRuntimeInstaller: AlpineRuntimeInstaller = koinInject(),
    terminalRuntime: TerminalRuntime = koinInject(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val workspaceState by workspaceManager.state.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val workspaceSavedToast = stringResource(R.string.setting_files_page_workspace_saved)
    val workspaceClearedToast = stringResource(R.string.setting_files_page_workspace_cleared)
    val workspaceUpdateFailed = stringResource(R.string.setting_files_page_workspace_update_failed)
    var workspaceMutationRunning by remember { mutableStateOf(false) }
    var installStatus by remember { mutableStateOf<InstallStatus?>(null) }
    var installingRuntime by remember { mutableStateOf(false) }
    LaunchedEffect(alpineRuntimeInstaller) {
        installStatus = alpineRuntimeInstaller.getInstallStatus()
    }
    var termuxProbeKey by remember { mutableIntStateOf(0) }
    val termuxStatus by produceState<TermuxRuntimeStatus?>(initialValue = null, termuxProbeKey) {
        value = terminalRuntime.probeTermuxRuntime()
    }
    val runtimeOptions = remember { TerminalRuntimeKind.entries }
    val concurrentJobOptions = remember { listOf(1, 2, 3, 4) }
    val outputTailOptions = remember { listOf(64, 128, 256, 512).map { it * 1024 } }
    val installTimeoutOptions = remember { listOf(5, 15, 30).map { it * 60_000L } }
    val workspaceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            workspaceMutationRunning = false
        } else {
            workspaceMutationRunning = true
            scope.launch {
                try {
                    workspaceManager.setWorkspace(uri)
                    toaster.show(workspaceSavedToast)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    toaster.show(
                        workspaceUpdateFailed.format(error.message ?: error::class.java.simpleName),
                        type = ToastType.Error,
                    )
                } finally {
                    workspaceMutationRunning = false
                }
            }
        }
    }

    var workspaceMenu by remember { mutableStateOf(false) }
    fun chooseWorkspace() {
        workspaceMutationRunning = true
        workspaceLauncher.launch(null)
    }
    fun clearWorkspace() {
        workspaceMutationRunning = true
        scope.launch {
            try {
                workspaceManager.clearWorkspace()
                toaster.show(workspaceClearedToast)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                toaster.show(workspaceUpdateFailed.format(error.message ?: error::class.java.simpleName), type = ToastType.Error)
            } finally {
                workspaceMutationRunning = false
            }
        }
    }
    val type = LocalAmberType.current
    val colors = workspaceColors()
    val runtimeStatus = installStatus?.let { status ->
        if (status.success) stringResource(R.string.setting_sandbox_alpine_ready)
        else stringResource(R.string.setting_sandbox_alpine_failed, status.message)
    } ?: stringResource(R.string.calculating)
    val runtimeSummary = installStatus?.let { status ->
        "Alpine · " + stringResource(if (status.success) R.string.setting_experimental_ready else R.string.setting_experimental_missing)
    } ?: stringResource(R.string.calculating)

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.setting_sandbox_page_title),
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection).amberCanvas(),
        containerColor = Color.Transparent,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = SettingPageHorizontalInset, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SandboxCard {
                    SandboxSettingsRow(
                        title = stringResource(R.string.setting_sandbox_workspace_section),
                        summary = if (workspaceState.configured) {
                            workspaceState.displayName?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.setting_files_page_workspace_selected)
                        } else stringResource(R.string.setting_files_page_workspace_not_set),
                        leading = Lucide.DatabaseZap,
                        onClick = ::chooseWorkspace,
                        enabled = !workspaceMutationRunning,
                        trailing = {
                            if (workspaceState.configured) {
                                Box {
                                    IconButton(onClick = { workspaceMenu = true }, enabled = !workspaceMutationRunning) {
                                        Icon(Lucide.EllipsisVertical, stringResource(R.string.skills_page_more_actions), Modifier.size(18.dp))
                                    }
                                    DropdownMenu(expanded = workspaceMenu, onDismissRequest = { workspaceMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.setting_files_page_workspace_choose), style = type.body) },
                                            onClick = { workspaceMenu = false; chooseWorkspace() },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.setting_files_page_workspace_clear), style = type.body) },
                                            onClick = { workspaceMenu = false; clearWorkspace() },
                                        )
                                    }
                                }
                            } else {
                                Icon(Lucide.ChevronRight, null, Modifier.size(16.dp), tint = colors.muted)
                            }
                        },
                    )
                }
            }
            item {
                SandboxCard {
                    SandboxSettingsRow(
                        title = stringResource(R.string.setting_sandbox_terminal_runtime_title),
                        leading = Lucide.CodeXml,
                        trailing = {
                            SandboxSelect(
                                options = runtimeOptions,
                                selectedOption = settings.agentRuntime.terminalDefaultRuntime,
                                onOptionSelected = { runtime ->
                                    vm.updateSettings { current -> current.copy(agentRuntime = current.agentRuntime.copy(terminalDefaultRuntime = runtime)) }
                                },
                                optionToString = { sandboxRuntimeName(it) },
                            )
                        },
                    )
                }
            }
            item {
                SettingSshProfilesSection(
                    terminalRuntime = terminalRuntime,
                    moreBusy = installingRuntime,
                ) {
                    SandboxDisclosure(
                        title = stringResource(R.string.setting_chat_storage_maintenance),
                        summary = if (installingRuntime) stringResource(R.string.setting_sandbox_runtime_installing) else runtimeSummary,
                        forceExpanded = installingRuntime,
                    ) {
                        RuntimeStatusBlock(
                            statusText = runtimeStatus,
                            failed = installStatus?.success == false,
                            installing = installingRuntime,
                            onRefresh = { scope.launch { installStatus = alpineRuntimeInstaller.getInstallStatus() } },
                            onInstallOrRepair = {
                                scope.launch {
                                    installingRuntime = true
                                    try { installStatus = alpineRuntimeInstaller.installOrRepair() }
                                    finally { installingRuntime = false }
                                }
                            },
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = colors.hairline.copy(alpha = 0.55f))
                        SandboxSettingsRow(
                            title = stringResource(R.string.setting_sandbox_termux_title),
                            trailing = {
                                SandboxAction(
                                    label = stringResource(R.string.setting_sandbox_termux_probe),
                                    onClick = { termuxProbeKey++ },
                                )
                            },
                        )
                        Text(
                            termuxStatus?.message ?: stringResource(R.string.calculating),
                            style = type.secondary,
                            color = colors.muted,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                        )
                    }

                    SandboxDisclosure(
                        title = stringResource(R.string.setting_provider_page_advanced_settings),
                        summary = "${stringResource(R.string.setting_sandbox_terminal_jobs_title)} ${settings.agentRuntime.terminalMaxConcurrentJobs} · " +
                            stringResource(R.string.setting_sandbox_terminal_output_value, settings.agentRuntime.terminalOutputTailChars / 1024),
                    ) {
                        SandboxSettingsRow(
                            title = stringResource(R.string.setting_sandbox_terminal_jobs_title),
                            trailing = {
                                SandboxSelect(
                                    options = concurrentJobOptions,
                                    selectedOption = settings.agentRuntime.terminalMaxConcurrentJobs.coerceIn(1, 4),
                                    onOptionSelected = { count ->
                                        vm.updateSettings { current -> current.copy(agentRuntime = current.agentRuntime.copy(terminalMaxConcurrentJobs = count)) }
                                    },
                                    optionToString = { stringResource(R.string.setting_sandbox_terminal_jobs_value, it) },
                                )
                            },
                        )
                        SandboxSettingsRow(
                            title = stringResource(R.string.setting_sandbox_terminal_output_title),
                            trailing = {
                                SandboxSelect(
                                    options = outputTailOptions,
                                    selectedOption = settings.agentRuntime.terminalOutputTailChars,
                                    onOptionSelected = { count ->
                                        vm.updateSettings { current -> current.copy(agentRuntime = current.agentRuntime.copy(terminalOutputTailChars = count)) }
                                    },
                                    optionToString = { stringResource(R.string.setting_sandbox_terminal_output_value, it / 1024) },
                                )
                            },
                        )
                        SandboxSettingsRow(
                            title = stringResource(R.string.setting_sandbox_terminal_install_timeout_title),
                            trailing = {
                                SandboxSelect(
                                    options = installTimeoutOptions,
                                    selectedOption = settings.agentRuntime.terminalInstallTimeoutMs,
                                    onOptionSelected = { timeout ->
                                        vm.updateSettings { current -> current.copy(agentRuntime = current.agentRuntime.copy(terminalInstallTimeoutMs = timeout)) }
                                    },
                                    optionToString = { stringResource(R.string.setting_sandbox_terminal_install_timeout_value, it / 60_000L) },
                                )
                            },
                        )
                        Text(
                            stringResource(R.string.setting_sandbox_terminal_runtime_desc) + "\n" +
                                stringResource(R.string.setting_sandbox_terminal_jobs_desc) + "\n" +
                                stringResource(R.string.setting_sandbox_terminal_output_desc),
                            style = type.secondary,
                            color = colors.muted,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun sandboxRuntimeName(runtime: TerminalRuntimeKind): String = stringResource(
    when (runtime) {
        TerminalRuntimeKind.BUILTIN_ALPINE -> R.string.setting_sandbox_terminal_runtime_builtin
        TerminalRuntimeKind.ANDROID_SHELL -> R.string.setting_sandbox_terminal_runtime_android_shell
        TerminalRuntimeKind.TERMUX_EXTERNAL -> R.string.setting_sandbox_terminal_runtime_termux
        TerminalRuntimeKind.REMOTE_SSH -> R.string.setting_sandbox_terminal_runtime_ssh
    },
)

/** Maintenance details stay behind one disclosure; repair remains button-driven. */
@Composable
private fun RuntimeStatusBlock(
    statusText: String,
    failed: Boolean,
    installing: Boolean,
    onRefresh: () -> Unit,
    onInstallOrRepair: () -> Unit,
) {
    val colors = workspaceColors()
    Column(
        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Alpine", style = LocalAmberType.current.body)
        if (failed) Text(statusText, style = LocalAmberType.current.secondary, color = colors.red)
        Text(
            stringResource(R.string.setting_sandbox_runtime_repair_note),
            style = LocalAmberType.current.secondary,
            color = colors.muted,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SandboxAction(stringResource(R.string.setting_sandbox_runtime_recheck), onRefresh, enabled = !installing)
            SandboxAction(
                label = stringResource(if (installing) R.string.setting_sandbox_runtime_installing else R.string.setting_sandbox_runtime_install_repair),
                onClick = onInstallOrRepair,
                enabled = !installing,
                primary = true,
            )
        }
    }
}
