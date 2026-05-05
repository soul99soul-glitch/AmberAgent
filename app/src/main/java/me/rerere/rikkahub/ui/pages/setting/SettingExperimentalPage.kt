package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.agent.icloud.ICLOUD_LOGIN_URL
import me.rerere.rikkahub.data.agent.icloud.ICloudDriveManager
import me.rerere.rikkahub.data.agent.office.FeishuOfficeAnalysisTemplate
import me.rerere.rikkahub.data.agent.office.FeishuOfficeEnhancementManager
import me.rerere.rikkahub.data.agent.subagent.DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS
import me.rerere.rikkahub.data.agent.subagent.DEFAULT_SUB_AGENT_TIMEOUT_MS
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingExperimentalPage() {
    val navController = LocalNavController.current

    ExperimentalSettingsScaffold(
        title = stringResource(R.string.setting_experimental_page_title),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_experimental_page_title)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingExperimentalICloud) },
                        leadingContent = { Icon(HugeIcons.ServerStack01, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.setting_icloud_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_icloud_desc)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingExperimentalOfficePro) },
                        leadingContent = { Icon(HugeIcons.File02, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.setting_officepro_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_officepro_desc)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingExperimentalSubAgent) },
                        leadingContent = { Icon(HugeIcons.File02, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.setting_subagent_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_subagent_desc)) },
                    )
                }
            }
        }
    }
}

@Composable
fun SettingExperimentalSubAgentPage(
    vm: SettingVM = koinViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val subAgent = settings.agentRuntime.subAgent
    val concurrencyOptions = listOf(1, 2, 3)
    val turnOptions = listOf(2, 4, 6, 8)
    val timeoutOptions = listOf(60_000L, 180_000L, DEFAULT_SUB_AGENT_TIMEOUT_MS, 600_000L)
    val budgetOptions = listOf(8_000, DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS, 20_000, 40_000)

    fun update(block: (me.rerere.rikkahub.data.agent.subagent.SubAgentRuntimeSetting) -> me.rerere.rikkahub.data.agent.subagent.SubAgentRuntimeSetting) {
        vm.updateSettings(
            settings.copy(
                agentRuntime = settings.agentRuntime.copy(
                    subAgent = block(subAgent)
                )
            )
        )
    }

    ExperimentalSettingsScaffold(
        title = stringResource(R.string.setting_subagent_title),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_subagent_section_runtime)) },
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.File02, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.setting_subagent_enabled)) },
                        supportingContent = { Text(stringResource(R.string.setting_subagent_enabled_desc)) },
                        trailingContent = {
                            Switch(
                                checked = subAgent.enabled,
                                onCheckedChange = { checked -> update { it.copy(enabled = checked) } },
                            )
                        },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.File02, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.setting_subagent_dynamic)) },
                        supportingContent = { Text(stringResource(R.string.setting_subagent_dynamic_desc)) },
                        trailingContent = {
                            Switch(
                                checked = subAgent.allowDynamicSubAgents,
                                onCheckedChange = { checked -> update { it.copy(allowDynamicSubAgents = checked) } },
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_subagent_section_limits)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_subagent_max_concurrent)) },
                        supportingContent = { Text(stringResource(R.string.setting_subagent_max_concurrent_desc)) },
                        trailingContent = {
                            Select(
                                options = concurrencyOptions,
                                selectedOption = subAgent.maxConcurrentRuns.coerceIn(1, 3),
                                onOptionSelected = { value -> update { it.copy(maxConcurrentRuns = value) } },
                                optionToString = { it.toString() },
                                modifier = Modifier.width(96.dp),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_subagent_max_turns)) },
                        supportingContent = { Text(stringResource(R.string.setting_subagent_max_turns_desc)) },
                        trailingContent = {
                            Select(
                                options = turnOptions,
                                selectedOption = subAgent.maxTurns.coerceIn(2, 8),
                                onOptionSelected = { value -> update { it.copy(maxTurns = value) } },
                                optionToString = { it.toString() },
                                modifier = Modifier.width(96.dp),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_subagent_timeout)) },
                        supportingContent = { Text(stringResource(R.string.setting_subagent_timeout_desc)) },
                        trailingContent = {
                            Select(
                                options = timeoutOptions,
                                selectedOption = timeoutOptions.minBy { kotlin.math.abs(it - subAgent.timeoutMs) },
                                onOptionSelected = { value -> update { it.copy(timeoutMs = value) } },
                                optionToString = { "${it / 60_000} min" },
                                modifier = Modifier.width(96.dp),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_subagent_output_budget)) },
                        supportingContent = { Text(stringResource(R.string.setting_subagent_output_budget_desc)) },
                        trailingContent = {
                            Select(
                                options = budgetOptions,
                                selectedOption = budgetOptions.minBy { kotlin.math.abs(it - subAgent.outputBudgetChars) },
                                onOptionSelected = { value -> update { it.copy(outputBudgetChars = value) } },
                                optionToString = { "${it / 1000}k" },
                                modifier = Modifier.width(96.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun SettingExperimentalICloudPage(
    iCloudDriveManager: ICloudDriveManager = koinInject(),
) {
    val iCloudState by iCloudDriveManager.state.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var showICloudLogin by remember { mutableStateOf(false) }
    var iCloudBusy by remember { mutableStateOf(false) }
    var iCloudVaultInput by remember(iCloudState.vaultPath) { mutableStateOf(iCloudState.vaultPath) }
    val iCloudSavedToast = stringResource(R.string.setting_icloud_saved)

    ExperimentalSettingsScaffold(
        title = stringResource(R.string.setting_icloud_title),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_icloud_section)) },
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.ServerStack01, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.setting_icloud_title)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(stringResource(R.string.setting_icloud_desc))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.setting_icloud_enabled),
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(
                                        checked = iCloudState.enabled,
                                        onCheckedChange = { iCloudDriveManager.setEnabled(it) },
                                    )
                                }
                                OutlinedTextField(
                                    value = iCloudVaultInput,
                                    onValueChange = { iCloudVaultInput = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = iCloudState.enabled,
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.setting_icloud_vault_path)) },
                                    supportingText = { Text(stringResource(R.string.setting_icloud_vault_path_desc)) },
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = {
                                            iCloudBusy = true
                                            scope.launch {
                                                runCatching {
                                                    iCloudDriveManager.setVaultPath(iCloudVaultInput)
                                                    toaster.show(iCloudSavedToast)
                                                    iCloudDriveManager.probe()
                                                }.onFailure { error ->
                                                    toaster.show(error.message ?: error.toString())
                                                }
                                                iCloudBusy = false
                                            }
                                        },
                                        enabled = iCloudState.enabled && !iCloudBusy,
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                    ) {
                                        Text(stringResource(R.string.setting_icloud_save_path), maxLines = 1)
                                    }
                                    OutlinedButton(
                                        onClick = { showICloudLogin = !showICloudLogin },
                                        enabled = iCloudState.enabled,
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                    ) {
                                        Text(stringResource(R.string.setting_icloud_login), maxLines = 1)
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = {
                                            iCloudBusy = true
                                            scope.launch {
                                                iCloudDriveManager.probe()
                                                iCloudBusy = false
                                            }
                                        },
                                        enabled = iCloudState.enabled && !iCloudBusy,
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                    ) {
                                        Text(stringResource(R.string.setting_icloud_probe), maxLines = 1)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            iCloudBusy = true
                                            scope.launch {
                                                iCloudDriveManager.runWriteProbe()
                                                iCloudBusy = false
                                            }
                                        },
                                        enabled = iCloudState.enabled && !iCloudBusy,
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                    ) {
                                        Text(stringResource(R.string.setting_icloud_write_probe), maxLines = 1)
                                    }
                                }
                                Text(
                                    text = stringResource(
                                        R.string.setting_icloud_status,
                                        iCloudState.status.wireName,
                                        iCloudState.capability.wireName,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                iCloudState.message?.takeIf { it.isNotBlank() }?.let { message ->
                                    Text(
                                        text = message,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
    if (showICloudLogin && iCloudState.enabled) {
        ICloudLoginDialog(
            onDismiss = {
                showICloudLogin = false
                iCloudBusy = true
                scope.launch {
                    iCloudDriveManager.probe()
                    iCloudBusy = false
                }
            },
        )
    }
}

@Composable
fun SettingExperimentalOfficeProPage(
    feishuOfficeManager: FeishuOfficeEnhancementManager = koinInject(),
) {
    val officeState by feishuOfficeManager.state.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var officeBusy by remember { mutableStateOf(false) }
    var officePackageInput by remember(officeState.targetPackage) { mutableStateOf(officeState.targetPackage) }
    var officeOutputDirInput by remember(officeState.defaultOutputDir) { mutableStateOf(officeState.defaultOutputDir) }
    var officeCandidates by remember { mutableStateOf("") }
    val officeSavedToast = stringResource(R.string.setting_officepro_saved)
    val officeDetectNoneToast = stringResource(R.string.setting_officepro_detect_none)

    ExperimentalSettingsScaffold(
        title = stringResource(R.string.setting_officepro_title),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_officepro_section)) },
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.File02, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.setting_officepro_title)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(stringResource(R.string.setting_officepro_desc))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.setting_officepro_enabled),
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(
                                        checked = officeState.enabled,
                                        onCheckedChange = { feishuOfficeManager.setEnabled(it) },
                                    )
                                }
                                OutlinedTextField(
                                    value = officePackageInput,
                                    onValueChange = { officePackageInput = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = officeState.enabled,
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.setting_officepro_package)) },
                                    supportingText = { Text(stringResource(R.string.setting_officepro_package_desc)) },
                                )
                                Select(
                                    options = FeishuOfficeAnalysisTemplate.entries,
                                    selectedOption = officeState.defaultTemplate,
                                    onOptionSelected = { feishuOfficeManager.setDefaultTemplate(it) },
                                    modifier = Modifier.fillMaxWidth(),
                                    optionToString = { it.zhLabel },
                                )
                                OutlinedTextField(
                                    value = officeOutputDirInput,
                                    onValueChange = { officeOutputDirInput = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = officeState.enabled,
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.setting_officepro_output_dir)) },
                                    supportingText = { Text(stringResource(R.string.setting_officepro_output_dir_desc)) },
                                )
                                Button(
                                    onClick = {
                                        feishuOfficeManager.setDefaultOutputDir(officeOutputDirInput)
                                        toaster.show(officeSavedToast)
                                    },
                                    enabled = officeState.enabled,
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                ) {
                                    Text(stringResource(R.string.setting_officepro_save_dashboard), maxLines = 1)
                                }
                                Text(
                                    text = stringResource(R.string.setting_officepro_dashboard_section),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                OfficeProSwitchRow(
                                    text = stringResource(R.string.setting_officepro_include_notifications),
                                    checked = officeState.includeNotificationsByDefault,
                                    enabled = officeState.enabled,
                                    onCheckedChange = feishuOfficeManager::setIncludeNotificationsByDefault,
                                )
                                OfficeProSwitchRow(
                                    text = stringResource(R.string.setting_officepro_include_usage),
                                    checked = officeState.includeUsageByDefault,
                                    enabled = officeState.enabled,
                                    onCheckedChange = feishuOfficeManager::setIncludeUsageByDefault,
                                )
                                OfficeProSwitchRow(
                                    text = stringResource(R.string.setting_officepro_include_screen),
                                    checked = officeState.includeCurrentScreenByDefault,
                                    enabled = officeState.enabled,
                                    onCheckedChange = feishuOfficeManager::setIncludeCurrentScreenByDefault,
                                )
                                OfficeProSwitchRow(
                                    text = stringResource(R.string.setting_officepro_include_mcp_hints),
                                    checked = officeState.includeMcpHintsByDefault,
                                    enabled = officeState.enabled,
                                    onCheckedChange = feishuOfficeManager::setIncludeMcpHintsByDefault,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = {
                                            feishuOfficeManager.setTargetPackage(officePackageInput)
                                            toaster.show(officeSavedToast)
                                        },
                                        enabled = officeState.enabled,
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                    ) {
                                        Text(stringResource(R.string.setting_officepro_save_package), maxLines = 1)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            officeBusy = true
                                            scope.launch {
                                                val candidates = feishuOfficeManager.detectPackages()
                                                val selected = feishuOfficeManager.chooseAndSaveBestPackage(candidates)
                                                officeCandidates = candidates.joinToString("\n") {
                                                    "${it.label} · ${it.packageName}"
                                                }
                                                if (selected == null) {
                                                    toaster.show(officeDetectNoneToast)
                                                } else {
                                                    officePackageInput = selected.packageName
                                                    toaster.show(officeSavedToast)
                                                }
                                                feishuOfficeManager.refresh()
                                                officeBusy = false
                                            }
                                        },
                                        enabled = officeState.enabled && !officeBusy,
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                    ) {
                                        Text(stringResource(R.string.setting_officepro_detect), maxLines = 1)
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = { feishuOfficeManager.openTargetApp() },
                                        enabled = officeState.enabled && officeState.launchable,
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                    ) {
                                        Text(stringResource(R.string.setting_officepro_open), maxLines = 1)
                                    }
                                    OutlinedButton(
                                        onClick = { feishuOfficeManager.refresh() },
                                        enabled = officeState.enabled,
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                    ) {
                                        Text(stringResource(R.string.setting_officepro_refresh), maxLines = 1)
                                    }
                                }
                                Text(
                                    text = stringResource(
                                        R.string.setting_officepro_status,
                                        officeState.capability.wireName,
                                        officeState.targetPackage,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = listOf(
                                        stringResource(R.string.setting_officepro_permission_installed, officeState.installed),
                                        stringResource(R.string.setting_officepro_permission_accessibility, officeState.accessibilityReady),
                                        stringResource(R.string.setting_officepro_permission_notification, officeState.notificationReady),
                                        stringResource(R.string.setting_officepro_permission_usage, officeState.usageReady),
                                    ).joinToString("\n"),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                officeState.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                                    Text(
                                        text = error,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                officeCandidates.takeIf { it.isNotBlank() }?.let { candidates ->
                                    Text(
                                        text = stringResource(R.string.setting_officepro_candidates, candidates),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OfficeProSwitchRow(
    text: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun ExperimentalSettingsScaffold(
    title: String,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        content(innerPadding)
    }
}

@Composable
private fun ICloudLoginDialog(
    onDismiss: () -> Unit,
) {
    val state = rememberWebViewState(
        url = ICLOUD_LOGIN_URL,
        settings = {
            domStorageEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = ICLOUD_WEBVIEW_USER_AGENT
        },
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, top = 12.dp, end = 12.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.setting_icloud_login),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = HugeIcons.Cancel01,
                                contentDescription = stringResource(R.string.update_card_close),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    WebView(
                        state = state,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                }
            }
        }
    }
}

private const val ICLOUD_WEBVIEW_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3.1 Safari/605.1.15"
