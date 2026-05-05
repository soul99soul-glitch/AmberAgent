package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.agent.terminal.AlpineRuntimeInstaller
import me.rerere.rikkahub.data.agent.terminal.InstallStatus
import me.rerere.rikkahub.data.agent.terminal.TerminalRuntime
import me.rerere.rikkahub.data.agent.terminal.TerminalRuntimeKind
import me.rerere.rikkahub.data.agent.terminal.TermuxRuntimeStatus
import me.rerere.rikkahub.data.agent.workspace.WorkspaceManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
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
    val workspaceSavedToast = stringResource(R.string.setting_files_page_workspace_saved)
    val workspaceClearedToast = stringResource(R.string.setting_files_page_workspace_cleared)
    val installStatus by produceState<InstallStatus?>(initialValue = null) {
        value = alpineRuntimeInstaller.ensureInstalled()
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
        if (uri != null) {
            workspaceManager.setWorkspace(uri)
            toaster.show(workspaceSavedToast)
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_sandbox_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_sandbox_workspace_section)) },
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.Database02, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.setting_files_page_workspace_title)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.setting_files_page_workspace_desc))
                                Text(
                                    text = if (workspaceState.configured) {
                                        "${stringResource(R.string.setting_files_page_workspace_selected)}: ${workspaceState.displayName.orEmpty()}"
                                    } else {
                                        stringResource(R.string.setting_files_page_workspace_not_set)
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = { workspaceLauncher.launch(null) },
                                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.setting_files_page_workspace_choose),
                                            maxLines = 1,
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            workspaceManager.clearWorkspace()
                                            toaster.show(workspaceClearedToast)
                                        },
                                        enabled = workspaceState.configured,
                                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.setting_files_page_workspace_clear),
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.ServerStack01, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.setting_sandbox_mirror_title)) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.setting_sandbox_mirror_desc,
                                    workspaceManager.mirrorDir.absolutePath,
                                ),
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_sandbox_runtime_section)) },
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.Code, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.setting_sandbox_alpine_title)) },
                        supportingContent = {
                            Text(
                                installStatus?.let { status ->
                                    if (status.success) {
                                        stringResource(R.string.setting_sandbox_alpine_ready, status.prefix)
                                    } else {
                                        stringResource(R.string.setting_sandbox_alpine_failed, status.message)
                                    }
                                } ?: stringResource(R.string.calculating),
                            )
                        },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.Code, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.setting_sandbox_session_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_sandbox_session_desc)) },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.Code, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.setting_sandbox_terminal_runtime_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_sandbox_terminal_runtime_desc)) },
                        trailingContent = {
                            Select(
                                options = runtimeOptions,
                                selectedOption = settings.agentRuntime.terminalDefaultRuntime,
                                onOptionSelected = { runtime ->
                                    vm.updateSettings(
                                        settings.copy(
                                            agentRuntime = settings.agentRuntime.copy(
                                                terminalDefaultRuntime = runtime
                                            )
                                        )
                                    )
                                },
                                optionToString = { runtime ->
                                    when (runtime) {
                                        TerminalRuntimeKind.BUILTIN_ALPINE ->
                                            stringResource(R.string.setting_sandbox_terminal_runtime_builtin)

                                        TerminalRuntimeKind.ANDROID_SHELL ->
                                            stringResource(R.string.setting_sandbox_terminal_runtime_android_shell)

                                        TerminalRuntimeKind.TERMUX_EXTERNAL ->
                                            stringResource(R.string.setting_sandbox_terminal_runtime_termux)
                                    }
                                },
                                modifier = Modifier.width(156.dp),
                            )
                        },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.ServerStack01, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.setting_sandbox_terminal_jobs_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_sandbox_terminal_jobs_desc)) },
                        trailingContent = {
                            Select(
                                options = concurrentJobOptions,
                                selectedOption = settings.agentRuntime.terminalMaxConcurrentJobs.coerceIn(1, 4),
                                onOptionSelected = { count ->
                                    vm.updateSettings(
                                        settings.copy(
                                            agentRuntime = settings.agentRuntime.copy(
                                                terminalMaxConcurrentJobs = count
                                            )
                                        )
                                    )
                                },
                                optionToString = { stringResource(R.string.setting_sandbox_terminal_jobs_value, it) },
                                modifier = Modifier.width(108.dp),
                            )
                        },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.Code, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.setting_sandbox_terminal_output_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_sandbox_terminal_output_desc)) },
                        trailingContent = {
                            Select(
                                options = outputTailOptions,
                                selectedOption = settings.agentRuntime.terminalOutputTailChars,
                                onOptionSelected = { chars ->
                                    vm.updateSettings(
                                        settings.copy(
                                            agentRuntime = settings.agentRuntime.copy(
                                                terminalOutputTailChars = chars
                                            )
                                        )
                                    )
                                },
                                optionToString = {
                                    stringResource(R.string.setting_sandbox_terminal_output_value, it / 1024)
                                },
                                modifier = Modifier.width(116.dp),
                            )
                        },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.Code, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.setting_sandbox_terminal_install_timeout_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_sandbox_terminal_install_timeout_desc)) },
                        trailingContent = {
                            Select(
                                options = installTimeoutOptions,
                                selectedOption = settings.agentRuntime.terminalInstallTimeoutMs,
                                onOptionSelected = { timeout ->
                                    vm.updateSettings(
                                        settings.copy(
                                            agentRuntime = settings.agentRuntime.copy(
                                                terminalInstallTimeoutMs = timeout
                                            )
                                        )
                                    )
                                },
                                optionToString = {
                                    stringResource(R.string.setting_sandbox_terminal_install_timeout_value, it / 60_000L)
                                },
                                modifier = Modifier.width(116.dp),
                            )
                        },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.ServerStack01, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.setting_sandbox_termux_title)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(termuxStatus?.message ?: stringResource(R.string.calculating))
                                OutlinedButton(onClick = { termuxProbeKey++ }) {
                                    Text(stringResource(R.string.setting_sandbox_termux_probe))
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
