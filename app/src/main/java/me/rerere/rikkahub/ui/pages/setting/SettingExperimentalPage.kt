package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.agent.icloud.ICLOUD_CHINA_LOGIN_URL
import me.rerere.rikkahub.data.agent.icloud.ICLOUD_GLOBAL_LOGIN_URL
import me.rerere.rikkahub.data.agent.icloud.ICloudDriveManager
import me.rerere.rikkahub.data.agent.modelcouncil.DEFAULT_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS
import me.rerere.rikkahub.data.agent.modelcouncil.DEFAULT_MODEL_COUNCIL_SEAT_TIMEOUT_MS
import me.rerere.rikkahub.data.agent.modelcouncil.ModelCouncilRolePresets
import me.rerere.rikkahub.data.agent.modelcouncil.ModelCouncilRuntimeSetting
import me.rerere.rikkahub.data.agent.modelcouncil.ModelCouncilSeat
import me.rerere.rikkahub.data.agent.office.FeishuOfficeAnalysisTemplate
import me.rerere.rikkahub.data.agent.office.FeishuOfficeEnhancementManager
import me.rerere.rikkahub.data.agent.office.FeishuWorkProject
import me.rerere.rikkahub.data.agent.subagent.DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS
import me.rerere.rikkahub.data.agent.subagent.DEFAULT_SUB_AGENT_TIMEOUT_MS
import me.rerere.rikkahub.data.agent.workspace.WorkspaceManager
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.uuid.Uuid

@Composable
fun SettingExperimentalPage() {
    val navController = LocalNavController.current

    ExperimentalSettingsScaffold(
        title = stringResource(R.string.setting_experimental_page_title),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ExperimentSectionCard(
                    title = stringResource(R.string.setting_experimental_page_title),
                ) {
                    ExperimentFeatureRow(
                        onClick = { navController.navigate(Screen.SettingExperimentalICloud) },
                        icon = { Icon(HugeIcons.ServerStack01, contentDescription = null) },
                        title = stringResource(R.string.setting_icloud_title),
                        description = stringResource(R.string.setting_icloud_desc),
                    )
                    ExperimentDivider()
                    ExperimentFeatureRow(
                        onClick = { navController.navigate(Screen.SettingExperimentalOfficePro) },
                        icon = { Icon(HugeIcons.File02, contentDescription = null) },
                        title = stringResource(R.string.setting_officepro_title),
                        description = stringResource(R.string.setting_officepro_desc),
                    )
                    ExperimentDivider()
                    ExperimentFeatureRow(
                        onClick = { navController.navigate(Screen.SettingExperimentalSubAgent) },
                        icon = { Icon(HugeIcons.File02, contentDescription = null) },
                        title = stringResource(R.string.setting_subagent_title),
                        description = stringResource(R.string.setting_subagent_desc),
                    )
                    ExperimentDivider()
                    ExperimentFeatureRow(
                        onClick = { navController.navigate(Screen.SettingExperimentalModelCouncil) },
                        icon = { Icon(HugeIcons.File02, contentDescription = null) },
                        title = stringResource(R.string.setting_model_council_title),
                        description = stringResource(R.string.setting_model_council_desc),
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
fun SettingExperimentalModelCouncilPage(
    vm: SettingVM = koinViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val council = settings.agentRuntime.modelCouncil
    val chatModels = remember(settings.providers) {
        settings.providers
            .flatMap { provider -> provider.models }
            .filter { model -> model.type == ModelType.CHAT }
    }
    val maxSeatOptions = listOf(2, 3, 4)
    val roundOptions = listOf(1, 2, 3)
    val timeoutOptions = listOf(60_000L, DEFAULT_MODEL_COUNCIL_SEAT_TIMEOUT_MS, 480_000L)
    val budgetOptions = listOf(8_000, DEFAULT_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS, 20_000, 40_000)

    fun update(block: (ModelCouncilRuntimeSetting) -> ModelCouncilRuntimeSetting) {
        vm.updateSettings(
            settings.copy(
                agentRuntime = settings.agentRuntime.copy(
                    modelCouncil = block(council)
                )
            )
        )
    }

    fun updateSeat(seatId: String, block: (ModelCouncilSeat) -> ModelCouncilSeat) {
        update { current ->
            current.copy(
                defaultSeats = current.defaultSeats.map { seat ->
                    if (seat.seatId == seatId) block(seat) else seat
                }
            )
        }
    }

    fun addSeat() {
        val model = chatModels.firstOrNull() ?: return
        val preset = ModelCouncilRolePresets.presets.getOrElse(council.defaultSeats.size) {
            ModelCouncilRolePresets.presets.first()
        }
        val seat = ModelCouncilSeat(
            seatId = Uuid.random().toString(),
            name = preset.name,
            role = preset.name,
            modelId = model.id,
            systemPrompt = preset.prompt,
            outputBudgetChars = council.outputBudgetChars,
        )
        update { current -> current.copy(defaultSeats = current.defaultSeats + seat) }
    }

    ExperimentalSettingsScaffold(
        title = stringResource(R.string.setting_model_council_title),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ExperimentHeroCard(
                    icon = { Icon(HugeIcons.File02, contentDescription = null) },
                    title = stringResource(R.string.setting_model_council_title),
                    description = stringResource(R.string.setting_model_council_desc),
                    trailing = {
                        Switch(
                            checked = council.enabled,
                            onCheckedChange = { checked -> update { it.copy(enabled = checked) } },
                        )
                    },
                )
            }
            item {
                ExperimentSectionCard(title = stringResource(R.string.setting_model_council_runtime_section)) {
                    Text(
                        text = stringResource(R.string.setting_model_council_runtime_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = workspaceColors().muted,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.setting_model_council_show_seat_outputs),
                                style = MaterialTheme.typography.bodyMedium,
                                color = workspaceColors().ink,
                            )
                            Text(
                                text = stringResource(R.string.setting_model_council_show_seat_outputs_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = workspaceColors().muted,
                            )
                        }
                        Switch(
                            checked = council.showSeatOutputs,
                            onCheckedChange = { checked -> update { it.copy(showSeatOutputs = checked) } },
                        )
                    }
                    Text(
                        text = stringResource(R.string.setting_model_council_synthesis_model),
                        style = MaterialTheme.typography.labelMedium,
                        color = workspaceColors().faint,
                    )
                    ModelSelector(
                        modelId = council.synthesisModelId ?: settings.chatModelId,
                        providers = settings.providers,
                        type = ModelType.CHAT,
                        compact = true,
                        modifier = Modifier.fillMaxWidth(),
                        onSelect = { model -> update { it.copy(synthesisModelId = model.id) } },
                    )
                }
            }
            item {
                ExperimentSectionCard(title = stringResource(R.string.setting_model_council_seats_section)) {
                    if (chatModels.isEmpty()) {
                        ExperimentNote(text = stringResource(R.string.setting_model_council_no_models), error = true)
                    } else if (council.defaultSeats.isEmpty()) {
                        ExperimentNote(text = stringResource(R.string.setting_model_council_empty_seats))
                    }
                    council.defaultSeats.forEachIndexed { index, seat ->
                        ModelCouncilSeatEditor(
                            index = index,
                            seat = seat,
                            settingsProviders = settings.providers,
                            onModelSelected = { model -> updateSeat(seat.seatId) { it.copy(modelId = model.id) } },
                            onPresetSelected = { preset ->
                                updateSeat(seat.seatId) {
                                    it.copy(
                                        name = preset.name,
                                        role = preset.name,
                                        systemPrompt = preset.prompt,
                                    )
                                }
                            },
                            onDelete = {
                                update { current ->
                                    current.copy(defaultSeats = current.defaultSeats.filterNot { it.seatId == seat.seatId })
                                }
                            },
                        )
                    }
                    ExperimentActionRow {
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_model_council_add_seat),
                            primary = council.defaultSeats.isEmpty(),
                            enabled = chatModels.isNotEmpty() && council.defaultSeats.size < council.maxSeats,
                            onClick = { addSeat() },
                        )
                    }
                }
            }
            item {
                ExperimentSectionCard(title = stringResource(R.string.setting_model_council_limits_section)) {
                    ModelCouncilSelectRow(
                        label = stringResource(R.string.setting_model_council_max_seats),
                        options = maxSeatOptions,
                        selected = council.maxSeats.coerceIn(2, 4),
                        onSelected = { value ->
                            update { current ->
                                current.copy(
                                    maxSeats = value,
                                    defaultSeats = current.defaultSeats.take(value),
                                )
                            }
                        },
                        optionToString = { it.toString() },
                    )
                    ModelCouncilSelectRow(
                        label = stringResource(R.string.setting_model_council_default_rounds),
                        options = roundOptions,
                        selected = council.defaultRounds.coerceIn(1, council.maxRounds.coerceAtLeast(1)),
                        onSelected = { value -> update { it.copy(defaultRounds = value) } },
                        optionToString = { it.toString() },
                    )
                    ModelCouncilSelectRow(
                        label = stringResource(R.string.setting_model_council_timeout),
                        options = timeoutOptions,
                        selected = timeoutOptions.minBy { kotlin.math.abs(it - council.seatTimeoutMs) },
                        onSelected = { value -> update { it.copy(seatTimeoutMs = value) } },
                        optionToString = { "${it / 60_000} min" },
                    )
                    ModelCouncilSelectRow(
                        label = stringResource(R.string.setting_model_council_output_budget),
                        options = budgetOptions,
                        selected = budgetOptions.minBy { kotlin.math.abs(it - council.outputBudgetChars) },
                        onSelected = { value ->
                            update { current ->
                                current.copy(
                                    outputBudgetChars = value,
                                    defaultSeats = current.defaultSeats.map { seat ->
                                        seat.copy(outputBudgetChars = value)
                                    },
                                )
                            }
                        },
                        optionToString = { "${it / 1000}k" },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelCouncilSeatEditor(
    index: Int,
    seat: ModelCouncilSeat,
    settingsProviders: List<me.rerere.ai.provider.ProviderSetting>,
    onModelSelected: (Model) -> Unit,
    onPresetSelected: (me.rerere.rikkahub.data.agent.modelcouncil.ModelCouncilRolePreset) -> Unit,
    onDelete: () -> Unit,
) {
    val workspace = workspaceColors()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = workspace.row,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_model_council_seat_label, index + 1, seat.name),
                    style = MaterialTheme.typography.labelLarge,
                    color = workspace.ink,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ExperimentActionButton(
                    text = stringResource(R.string.delete),
                    enabled = true,
                    onClick = onDelete,
                )
            }
            ModelSelector(
                modelId = seat.modelId,
                providers = settingsProviders,
                type = ModelType.CHAT,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
                onSelect = onModelSelected,
            )
            Select(
                options = ModelCouncilRolePresets.presets,
                selectedOption = ModelCouncilRolePresets.byName(seat.role) ?: ModelCouncilRolePresets.presets.first(),
                onOptionSelected = onPresetSelected,
                modifier = Modifier.fillMaxWidth(),
                optionToString = { it.name },
            )
        }
    }
}

@Composable
private fun <T> ModelCouncilSelectRow(
    label: String,
    options: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    optionToString: @Composable (T) -> String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = workspaceColors().ink,
        )
        Select(
            options = options,
            selectedOption = selected,
            onOptionSelected = onSelected,
            optionToString = optionToString,
            modifier = Modifier.width(112.dp),
        )
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
    var iCloudLoginUrl by remember { mutableStateOf(ICLOUD_GLOBAL_LOGIN_URL) }
    var iCloudBusy by remember { mutableStateOf(false) }
    var iCloudVaultInput by remember(iCloudState.vaultPath) { mutableStateOf(iCloudState.vaultPath) }
    val iCloudSavedToast = stringResource(R.string.setting_icloud_saved)

    ExperimentalSettingsScaffold(
        title = stringResource(R.string.setting_icloud_title),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ExperimentHeroCard(
                    icon = { Icon(HugeIcons.ServerStack01, contentDescription = null) },
                    title = stringResource(R.string.setting_icloud_title),
                    description = stringResource(R.string.setting_icloud_desc),
                    trailing = {
                        Switch(
                            checked = iCloudState.enabled,
                            onCheckedChange = { iCloudDriveManager.setEnabled(it) },
                        )
                    },
                )
            }
            item {
                ExperimentSectionCard(
                    title = stringResource(R.string.setting_icloud_vault_path),
                ) {
                    OutlinedTextField(
                        value = iCloudVaultInput,
                        onValueChange = { iCloudVaultInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = iCloudState.enabled,
                        singleLine = true,
                        label = { Text(stringResource(R.string.setting_icloud_vault_path)) },
                        supportingText = { Text(stringResource(R.string.setting_icloud_vault_path_desc)) },
                    )
                    ExperimentActionRow {
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_icloud_save_path),
                            primary = true,
                            enabled = iCloudState.enabled && !iCloudBusy,
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
                        )
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_icloud_login),
                            enabled = iCloudState.enabled,
                            onClick = {
                                iCloudLoginUrl = ICLOUD_GLOBAL_LOGIN_URL
                                showICloudLogin = true
                            },
                        )
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_icloud_login_china),
                            enabled = iCloudState.enabled,
                            onClick = {
                                iCloudLoginUrl = ICLOUD_CHINA_LOGIN_URL
                                showICloudLogin = true
                            },
                        )
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_icloud_probe),
                            enabled = iCloudState.enabled && !iCloudBusy,
                            onClick = {
                                iCloudBusy = true
                                scope.launch {
                                    iCloudDriveManager.probe()
                                    iCloudBusy = false
                                }
                            },
                        )
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_icloud_write_probe),
                            enabled = iCloudState.enabled && !iCloudBusy,
                            onClick = {
                                iCloudBusy = true
                                scope.launch {
                                    iCloudDriveManager.runWriteProbe()
                                    iCloudBusy = false
                                }
                            },
                        )
                    }
                }
            }
            item {
                ExperimentSectionCard(title = stringResource(R.string.setting_experimental_status_section)) {
                    ExperimentStatusRow(
                        label = stringResource(R.string.setting_experimental_status),
                        value = iCloudState.status.wireName,
                    )
                    ExperimentStatusRow(
                        label = stringResource(R.string.setting_experimental_capability),
                        value = iCloudState.capability.wireName,
                    )
                    iCloudState.message?.takeIf { it.isNotBlank() }?.let { message ->
                        ExperimentNote(text = message)
                    }
                }
            }
        }
    }
    if (showICloudLogin && iCloudState.enabled) {
        ICloudLoginDialog(
            url = iCloudLoginUrl,
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
    workspaceManager: WorkspaceManager = koinInject(),
) {
    val officeState by feishuOfficeManager.state.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    var officeBusy by remember { mutableStateOf(false) }
    var officePackageInput by remember(officeState.targetPackage) { mutableStateOf(officeState.targetPackage) }
    var officeOutputDirInput by remember(officeState.defaultOutputDir) { mutableStateOf(officeState.defaultOutputDir) }
    var officeProjectKeywordsInput by remember(officeState.workDashboard.defaultProjectKeywords) {
        mutableStateOf(officeState.workDashboard.defaultProjectKeywords.joinToString("，"))
    }
    var officeCandidates by remember { mutableStateOf("") }
    var editingProject by remember { mutableStateOf<FeishuWorkProject?>(null) }
    var creatingProject by remember { mutableStateOf(false) }
    val officeSavedToast = stringResource(R.string.setting_officepro_saved)
    val officeDetectNoneToast = stringResource(R.string.setting_officepro_detect_none)
    val workflowEntries = remember(officeState.workDashboard.projects) {
        officeProWorkflowEntries(officeState.workDashboard.projects)
    }

    ExperimentalSettingsScaffold(
        title = stringResource(R.string.setting_officepro_title),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ExperimentHeroCard(
                    icon = { Icon(HugeIcons.File02, contentDescription = null) },
                    title = stringResource(R.string.setting_officepro_title),
                    description = stringResource(R.string.setting_officepro_desc),
                    trailing = {
                        Switch(
                            checked = officeState.enabled,
                            onCheckedChange = { feishuOfficeManager.setEnabled(it) },
                        )
                    },
                )
            }
            item {
                ExperimentSectionCard(title = stringResource(R.string.setting_officepro_connection_section)) {
                    OutlinedTextField(
                        value = officePackageInput,
                        onValueChange = { officePackageInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = officeState.enabled,
                        singleLine = true,
                        label = { Text(stringResource(R.string.setting_officepro_package)) },
                        supportingText = { Text(stringResource(R.string.setting_officepro_package_desc)) },
                    )
                    ExperimentActionRow {
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_officepro_save_package),
                            primary = true,
                            enabled = officeState.enabled,
                            onClick = {
                                feishuOfficeManager.setTargetPackage(officePackageInput)
                                toaster.show(officeSavedToast)
                            },
                        )
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_officepro_detect),
                            enabled = officeState.enabled && !officeBusy,
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
                        )
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_officepro_open),
                            enabled = officeState.enabled && officeState.launchable,
                            onClick = { feishuOfficeManager.openTargetApp() },
                        )
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_officepro_refresh),
                            enabled = officeState.enabled,
                            onClick = { feishuOfficeManager.refresh() },
                        )
                    }
                    ExperimentDivider()
                    ExperimentStatusRow(stringResource(R.string.setting_experimental_capability), officeState.capability.wireName)
                    ExperimentStatusRow(stringResource(R.string.setting_experimental_package), officeState.targetPackage)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ExperimentBooleanPill(stringResource(R.string.setting_experimental_installed), officeState.installed)
                        ExperimentBooleanPill(stringResource(R.string.setting_experimental_accessibility), officeState.accessibilityReady)
                        ExperimentBooleanPill(stringResource(R.string.setting_experimental_notifications), officeState.notificationReady)
                        ExperimentBooleanPill(stringResource(R.string.setting_experimental_usage), officeState.usageReady)
                    }
                    ExperimentNote(text = stringResource(R.string.setting_officepro_mcp_note))
                    officeState.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                        ExperimentNote(text = error, error = true)
                    }
                    officeCandidates.takeIf { it.isNotBlank() }?.let { candidates ->
                        ExperimentNote(
                            text = stringResource(R.string.setting_officepro_candidates, candidates),
                        )
                    }
                }
            }
            item {
                ExperimentSectionCard(title = stringResource(R.string.setting_officepro_capture_section)) {
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
                    OutlinedTextField(
                        value = officeProjectKeywordsInput,
                        onValueChange = { officeProjectKeywordsInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = officeState.enabled,
                        minLines = 2,
                        maxLines = 4,
                        label = { Text(stringResource(R.string.setting_officepro_project_keywords)) },
                        supportingText = { Text(stringResource(R.string.setting_officepro_project_keywords_desc)) },
                    )
                    ExperimentActionRow {
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_officepro_save_dashboard),
                            primary = true,
                            enabled = officeState.enabled,
                            onClick = {
                                feishuOfficeManager.setDefaultOutputDir(officeOutputDirInput)
                                feishuOfficeManager.setWorkDashboardProjectKeywords(officeProjectKeywordsInput)
                                toaster.show(officeSavedToast)
                            },
                        )
                    }
                    ExperimentDivider()
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
                    OfficeProSwitchRow(
                        text = stringResource(R.string.setting_officepro_include_model_council),
                        checked = officeState.workDashboard.includeModelCouncil,
                        enabled = officeState.enabled,
                        onCheckedChange = feishuOfficeManager::setWorkDashboardIncludeModelCouncil,
                    )
                    ExperimentNote(text = stringResource(R.string.setting_officepro_workos_note))
                }
            }
            item {
                ExperimentSectionCard(title = stringResource(R.string.setting_officepro_project_pack_section)) {
                    ExperimentNote(text = stringResource(R.string.setting_officepro_project_pack_desc))
                    ExperimentActionRow {
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_officepro_add_project),
                            primary = true,
                            enabled = officeState.enabled,
                            onClick = {
                                creatingProject = true
                                editingProject = null
                            },
                        )
                    }
                    officeState.workDashboard.projects.forEach { project ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = project.name,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                ExperimentActionButton(
                                    text = stringResource(R.string.setting_officepro_edit_project),
                                    enabled = officeState.enabled,
                                    onClick = {
                                        editingProject = project
                                        creatingProject = false
                                    },
                                )
                            }
                            Text(
                                text = project.keywords.joinToString(" / "),
                                style = MaterialTheme.typography.bodySmall,
                                color = workspaceColors().muted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            project.currentGoal.takeIf { it.isNotBlank() }?.let { goal ->
                                Text(
                                    text = goal,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = workspaceColors().faint,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (project.sourceRefs.isNotEmpty()) {
                                Text(
                                    text = stringResource(
                                        R.string.setting_officepro_project_sources_count,
                                        project.sourceRefs.size,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = workspaceColors().faint,
                                )
                            }
                            if (project.updatedAtMs > 0L) {
                                Text(
                                    text = stringResource(
                                        R.string.setting_officepro_project_updated_at,
                                        formatOfficeProjectUpdatedAt(project.updatedAtMs),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = workspaceColors().faint,
                                )
                            }
                        }
                        if (project != officeState.workDashboard.projects.last()) {
                            ExperimentDivider()
                        }
                    }
                    ExperimentActionRow {
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_officepro_restore_projects),
                            enabled = officeState.enabled,
                            onClick = {
                                feishuOfficeManager.restoreDefaultProjects()
                                toaster.show(officeSavedToast)
                            },
                        )
                    }
                }
            }
            item {
                ExperimentSectionCard(title = stringResource(R.string.setting_officepro_usage_section)) {
                    ExperimentNote(text = stringResource(R.string.setting_officepro_usage_intro))
                    workflowEntries.forEach { entry ->
                        OfficeProWorkflowCard(
                            entry = entry,
                            onSend = {
                                navigateToChatPage(
                                    navigator = navController,
                                    initText = entry.promptTemplate.base64Encode(),
                                )
                            },
                        )
                        if (entry != workflowEntries.last()) {
                            ExperimentDivider()
                        }
                    }
                }
            }
        }
    }
    if (creatingProject || editingProject != null) {
        OfficeProjectEditorDialog(
            project = editingProject,
            workspaceManager = workspaceManager,
            context = context,
            onDismiss = {
                creatingProject = false
                editingProject = null
            },
            onSave = { project ->
                scope.launch {
                    feishuOfficeManager.saveProject(project)
                    toaster.show(officeSavedToast)
                    creatingProject = false
                    editingProject = null
                }
            },
            onToast = toaster::show,
        )
    }
}

private data class OfficeProWorkflowEntry(
    val id: String,
    val title: String,
    val description: String,
    val inputs: String,
    val output: String,
    val promptTemplate: String,
    val requiresApproval: Boolean,
)

private fun officeProWorkflowEntries(projects: List<FeishuWorkProject>): List<OfficeProWorkflowEntry> {
    val project = projects.firstOrNull()
    val projectName = project?.name ?: "Q 代"
    val sourceHint = if (project?.sourceRefs.orEmpty().isEmpty()) {
        "如果项目还没有资料源，请先提示我导入 PDF、Word 或 Markdown。"
    } else {
        "请优先读取该项目知识包里已经导入的 workspace 资料源。"
    }
    return listOf(
        OfficeProWorkflowEntry(
            id = "daily_radar",
            title = "今日飞书雷达",
            description = "把通知、使用情况、当前屏幕和 MCP 线索整理成今天该先处理什么。",
            inputs = "办公通知 / 使用情况 / MCP hints / workspace 资料",
            output = "今日优先级、待跟进事项、建议动作",
            promptTemplate = "请基于我的飞书办公增强模式，调用 officepro_daily_radar 生成今日飞书雷达。请说明使用了哪些来源；如果飞书 MCP 不可用，就用通知、使用情况、当前屏幕和 workspace 资料降级生成。",
            requiresApproval = true,
        ),
        OfficeProWorkflowEntry(
            id = "project_briefing",
            title = "项目 Briefing",
            description = "围绕一个项目知识包，生成评审前 10 分钟能读完的 briefing。",
            inputs = "$projectName 项目知识包 / 已导入资料 / MCP hints",
            output = "项目目标、卖点、风险、待确认和评审建议",
            promptTemplate = "请基于「$projectName」项目知识包，调用 officepro_project_briefing 和必要的 officepro_project_context，生成 10 分钟评审前 Briefing。$sourceHint 如果需要写入 workspace 报告，请先请求审批。",
            requiresApproval = true,
        ),
        OfficeProWorkflowEntry(
            id = "document_warroom",
            title = "文档作战室",
            description = "读取当前小米办公 Pro 可见文档，结合项目知识包做白皮书/卖点/竞品评审。",
            inputs = "当前可见文档 / 项目知识包 / workspace 文档 / Model Council 可选",
            output = "必改项、风险项、证据缺口、评论草稿、修订建议",
            promptTemplate = "请读取当前小米办公 Pro 可见文档，并结合「$projectName」项目知识包和已导入 workspace 资料，调用 officepro_document_warroom 做文档作战室分析。请输出必改项、可选优化、风险与待确认、证据缺口、可复制评论草稿；不要自动发送或写回飞书。",
            requiresApproval = true,
        ),
    )
}

@Composable
private fun OfficeProWorkflowCard(
    entry: OfficeProWorkflowEntry,
    onSend: () -> Unit,
) {
    val workspace = workspaceColors()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = workspace.row,
        contentColor = workspace.ink,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmall,
                color = workspace.ink,
            )
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = workspace.muted,
            )
            OfficeProWorkflowMetaItem(
                label = stringResource(R.string.setting_officepro_workflow_inputs),
                value = entry.inputs,
            )
            OfficeProWorkflowMetaItem(
                label = stringResource(R.string.setting_officepro_workflow_output),
                value = entry.output,
            )
            OfficeProWorkflowMetaItem(
                label = stringResource(R.string.setting_officepro_workflow_approval),
                value = if (entry.requiresApproval) {
                    stringResource(R.string.setting_officepro_workflow_approval_required)
                } else {
                    stringResource(R.string.setting_officepro_workflow_approval_not_required)
                },
            )
            ExperimentActionRow {
                ExperimentActionButton(
                    text = stringResource(R.string.setting_officepro_send_to_chat),
                    primary = true,
                    enabled = true,
                    onClick = onSend,
                )
            }
        }
    }
}

@Composable
private fun OfficeProWorkflowMetaItem(
    label: String,
    value: String,
) {
    val workspace = workspaceColors()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = workspace.faint,
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = workspace.muted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OfficeProjectEditorDialog(
    project: FeishuWorkProject?,
    workspaceManager: WorkspaceManager,
    context: Context,
    onDismiss: () -> Unit,
    onSave: (FeishuWorkProject) -> Unit,
    onToast: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var nameInput by remember(project?.id) { mutableStateOf(project?.name.orEmpty()) }
    var keywordsInput by remember(project?.id) { mutableStateOf(project?.keywords.orEmpty().joinToString("\n")) }
    var currentGoalInput by remember(project?.id) { mutableStateOf(project?.currentGoal.orEmpty()) }
    var coreSellingPointsInput by remember(project?.id) { mutableStateOf(project?.coreSellingPoints.orEmpty().joinToString("\n")) }
    var risksInput by remember(project?.id) { mutableStateOf(project?.risks.orEmpty().joinToString("\n")) }
    var openQuestionsInput by remember(project?.id) { mutableStateOf(project?.openQuestions.orEmpty().joinToString("\n")) }
    var keyDecisionsInput by remember(project?.id) { mutableStateOf(project?.keyDecisions.orEmpty().joinToString("\n")) }
    var recentChangesInput by remember(project?.id) { mutableStateOf(project?.recentChanges.orEmpty().joinToString("\n")) }
    var sourceRefsInput by remember(project?.id) { mutableStateOf(project?.sourceRefs.orEmpty().joinToString("\n")) }
    var importing by remember { mutableStateOf(false) }
    val importSuccess = stringResource(R.string.setting_officepro_import_source_success)
    val blankNameError = stringResource(R.string.setting_officepro_project_name_required)
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            runCatching {
                importOfficeProjectSource(
                    context = context,
                    workspaceManager = workspaceManager,
                    uri = uri,
                    projectName = nameInput.ifBlank { project?.name ?: "officepro-project" },
                )
            }.onSuccess { path ->
                sourceRefsInput = appendSourceRef(sourceRefsInput, path)
                onToast(importSuccess.format(path))
            }.onFailure { error ->
                onToast(error.message ?: error.toString())
            }
            importing = false
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = workspaceColors().paper,
            border = BorderStroke(1.dp, workspaceColors().hairline),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.setting_officepro_project_editor_title),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        color = workspaceColors().ink,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(HugeIcons.Cancel01, contentDescription = null)
                    }
                }
                ExperimentNote(text = stringResource(R.string.setting_officepro_project_editor_desc))
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.setting_officepro_project_name)) },
                            singleLine = true,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = keywordsInput,
                            onValueChange = { keywordsInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.setting_officepro_project_keywords)) },
                            supportingText = { Text(stringResource(R.string.setting_officepro_project_list_field_desc)) },
                            minLines = 2,
                            maxLines = 5,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = currentGoalInput,
                            onValueChange = { currentGoalInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.setting_officepro_project_goal)) },
                            minLines = 2,
                            maxLines = 5,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = sourceRefsInput,
                            onValueChange = { sourceRefsInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.setting_officepro_project_sources)) },
                            supportingText = { Text(stringResource(R.string.setting_officepro_project_sources_desc)) },
                            minLines = 3,
                            maxLines = 7,
                        )
                    }
                    item {
                        ExperimentActionRow {
                            ExperimentActionButton(
                                text = stringResource(R.string.setting_officepro_import_source),
                                enabled = !importing,
                                onClick = { importLauncher.launch(arrayOf("*/*")) },
                            )
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = coreSellingPointsInput,
                            onValueChange = { coreSellingPointsInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.setting_officepro_project_selling_points)) },
                            supportingText = { Text(stringResource(R.string.setting_officepro_project_list_field_desc)) },
                            minLines = 2,
                            maxLines = 5,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = risksInput,
                            onValueChange = { risksInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.setting_officepro_project_risks)) },
                            supportingText = { Text(stringResource(R.string.setting_officepro_project_list_field_desc)) },
                            minLines = 2,
                            maxLines = 5,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = openQuestionsInput,
                            onValueChange = { openQuestionsInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.setting_officepro_project_open_questions)) },
                            supportingText = { Text(stringResource(R.string.setting_officepro_project_list_field_desc)) },
                            minLines = 2,
                            maxLines = 5,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = keyDecisionsInput,
                            onValueChange = { keyDecisionsInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.setting_officepro_project_key_decisions)) },
                            supportingText = { Text(stringResource(R.string.setting_officepro_project_list_field_desc)) },
                            minLines = 2,
                            maxLines = 5,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = recentChangesInput,
                            onValueChange = { recentChangesInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.setting_officepro_project_recent_changes)) },
                            supportingText = { Text(stringResource(R.string.setting_officepro_project_list_field_desc)) },
                            minLines = 2,
                            maxLines = 5,
                        )
                    }
                }
                ExperimentActionRow {
                    ExperimentActionButton(
                        text = stringResource(R.string.setting_officepro_save_project),
                        primary = true,
                        enabled = !importing,
                        onClick = {
                            val cleanName = nameInput.trim()
                            if (cleanName.isBlank()) {
                                onToast(blankNameError)
                            } else {
                                onSave(
                                    FeishuWorkProject(
                                        id = project?.id ?: cleanName.toOfficeProjectId(),
                                        name = cleanName.take(80),
                                        keywords = parseProjectList(keywordsInput).ifEmpty { listOf(cleanName.take(80)) },
                                        currentGoal = currentGoalInput.trim().take(500),
                                        coreSellingPoints = parseProjectList(coreSellingPointsInput),
                                        risks = parseProjectList(risksInput),
                                        openQuestions = parseProjectList(openQuestionsInput),
                                        keyDecisions = parseProjectList(keyDecisionsInput),
                                        recentChanges = parseProjectList(recentChangesInput),
                                        sourceRefs = parseProjectSourceRefs(sourceRefsInput),
                                        updatedAtMs = System.currentTimeMillis(),
                                    )
                                )
                            }
                        },
                    )
                    ExperimentActionButton(
                        text = stringResource(R.string.cancel),
                        enabled = !importing,
                        onClick = onDismiss,
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
    val workspace = workspaceColors()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) workspace.ink else workspace.faint,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun ExperimentSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val workspace = workspaceColors()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = workspace.faint,
            modifier = Modifier.padding(start = 4.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = workspace.paper,
            contentColor = workspace.ink,
            border = BorderStroke(1.dp, workspace.hairline),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun ExperimentHeroCard(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    trailing: @Composable () -> Unit,
) {
    val workspace = workspaceColors()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = workspace.paper,
        contentColor = workspace.ink,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(34.dp),
                shape = RoundedCornerShape(8.dp),
                color = workspace.row,
                contentColor = workspace.muted,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    icon()
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = workspace.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = workspace.muted,
                )
            }
            trailing()
        }
    }
}

@Composable
private fun ExperimentFeatureRow(
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    title: String,
    description: String,
) {
    val workspace = workspaceColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(34.dp),
            shape = RoundedCornerShape(8.dp),
            color = workspace.row,
            contentColor = workspace.muted,
        ) {
            Box(contentAlignment = Alignment.Center) {
                icon()
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = workspace.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = workspace.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ExperimentDivider() {
    val workspace = workspaceColors()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 46.dp)
            .height(1.dp),
        color = workspace.hairline,
    ) {}
}

@Composable
private fun ExperimentActionRow(
    content: @Composable () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun ExperimentActionButton(
    text: String,
    enabled: Boolean,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val workspace = workspaceColors()
    val container = when {
        !enabled -> workspace.row
        primary -> workspace.blue
        else -> workspace.paper
    }
    val contentColor = when {
        !enabled -> workspace.faint
        primary -> MaterialTheme.colorScheme.onPrimary
        else -> workspace.ink
    }
    Surface(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = container,
        contentColor = contentColor,
        border = if (primary || !enabled) null else BorderStroke(1.dp, workspace.hairline),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun ExperimentStatusRow(
    label: String,
    value: String,
) {
    val workspace = workspaceColors()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = workspace.faint,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = workspace.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ExperimentBooleanPill(
    label: String,
    ready: Boolean,
) {
    val workspace = workspaceColors()
    val scheme = MaterialTheme.colorScheme
    // Pulse Phase C HIGH: was rendering chartreuse-on-pale-chartreuse
    // (workspace.blue at 10% alpha + workspace.blue content) — text
    // functionally invisible. Pair primaryContainer with
    // onPrimaryContainer (ink in light, chartreuse in dark) for the
    // ready state so the label stays readable in both schemes.
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (ready) scheme.primaryContainer else workspace.row,
        contentColor = if (ready) scheme.onPrimaryContainer else workspace.muted,
        border = BorderStroke(
            1.dp,
            if (ready) scheme.onPrimaryContainer.copy(alpha = 0.22f) else workspace.hairline,
        ),
    ) {
        Text(
            text = "$label ${if (ready) stringResource(R.string.setting_experimental_ready) else stringResource(R.string.setting_experimental_missing)}",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun ExperimentNote(
    text: String,
    error: Boolean = false,
) {
    val workspace = workspaceColors()
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (error) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f) else workspace.row,
        contentColor = if (error) MaterialTheme.colorScheme.error else workspace.muted,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ExperimentalSettingsScaffold(
    title: String,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val workspace = workspaceColors()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = workspace.paper,
                    scrolledContainerColor = workspace.paper,
                    titleContentColor = workspace.ink,
                    navigationIconContentColor = workspace.muted,
                    actionIconContentColor = workspace.blue,
                ),
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = workspace.canvas,
    ) { innerPadding ->
        content(innerPadding)
    }
}

@Composable
private fun ICloudLoginDialog(
    url: String,
    onDismiss: () -> Unit,
) {
    val state = rememberWebViewState(
        url = url,
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

private const val OFFICE_PROJECT_IMPORT_MAX_BYTES = 50L * 1024L * 1024L

private fun parseProjectList(raw: String): List<String> =
    raw.split('\n', ',', '，', ';', '；')
        .map { it.trim().take(500) }
        .filter { it.isNotBlank() }
        .distinct()
        .take(24)

private fun parseProjectSourceRefs(raw: String): List<String> =
    raw.lineSequence()
        .flatMap { it.split('，', ';', '；').asSequence() }
        .map { it.trim().take(500) }
        .filter { it.isNotBlank() }
        .distinct()
        .take(32)
        .toList()

private fun appendSourceRef(existing: String, path: String): String =
    (parseProjectSourceRefs(existing) + path)
        .distinct()
        .joinToString("\n")

private suspend fun importOfficeProjectSource(
    context: Context,
    workspaceManager: WorkspaceManager,
    uri: Uri,
    projectName: String,
): String = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    var displayName: String? = null
    var sizeBytes: Long? = null
    resolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0) {
                displayName = cursor.getString(nameIndex)
            }
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                sizeBytes = cursor.getLong(sizeIndex)
            }
        }
    }
    val knownSize = sizeBytes
    require(knownSize == null || knownSize <= OFFICE_PROJECT_IMPORT_MAX_BYTES) {
        "文件超过 50MB，先放入 workspace 后再把路径写入来源引用。"
    }
    val safeName = (displayName ?: "source-${System.currentTimeMillis()}")
        .toSafeWorkspaceFileName()
    val bytes = resolver.openInputStream(uri)?.use { input ->
        input.readBytes()
    } ?: error("无法读取选择的文件")
    require(bytes.size.toLong() <= OFFICE_PROJECT_IMPORT_MAX_BYTES) {
        "文件超过 50MB，先放入 workspace 后再把路径写入来源引用。"
    }
    val mimeType = resolver.getType(uri) ?: "application/octet-stream"
    val projectDir = projectName.toOfficeProjectId()
    val relativePath = "officepro/knowledge/$projectDir/$safeName"
    workspaceManager.writeBytes(relativePath, bytes, mimeType).path
}

private fun String.toOfficeProjectId(): String {
    val ascii = lowercase()
        .map { char ->
            when {
                char in 'a'..'z' || char in '0'..'9' -> char
                char.isWhitespace() || char in listOf('-', '_', '.', '/', '，', ',', '；', ';') -> '-'
                else -> '-'
            }
        }
        .joinToString("")
        .trim('-')
        .replace(Regex("-+"), "-")
    return ascii.ifBlank { "custom-project" }.take(64)
}

private fun String.toSafeWorkspaceFileName(): String =
    replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
        .trim()
        .take(120)
        .ifBlank { "source-${System.currentTimeMillis()}" }

private fun formatOfficeProjectUpdatedAt(updatedAtMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(updatedAtMs))
