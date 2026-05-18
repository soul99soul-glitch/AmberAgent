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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Globe02
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.agent.icloud.ICLOUD_CHINA_LOGIN_URL
import me.rerere.rikkahub.data.agent.icloud.ICLOUD_GLOBAL_LOGIN_URL
import me.rerere.rikkahub.data.agent.icloud.ICloudDriveManager
import me.rerere.rikkahub.data.agent.modelcouncil.DEFAULT_MODEL_COUNCIL_MAX_ROUNDS
import me.rerere.rikkahub.data.agent.modelcouncil.DEFAULT_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS
import me.rerere.rikkahub.data.agent.modelcouncil.DEFAULT_MODEL_COUNCIL_SEAT_TIMEOUT_MS
import me.rerere.rikkahub.data.agent.modelcouncil.EXTENDED_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS
import me.rerere.rikkahub.data.agent.modelcouncil.EXTENDED_MODEL_COUNCIL_SEAT_TIMEOUT_MS
import me.rerere.rikkahub.data.agent.modelcouncil.EXTENDED_MODEL_COUNCIL_TOTAL_TIMEOUT_MS
import me.rerere.rikkahub.data.agent.modelcouncil.MODEL_COUNCIL_EXTERNAL_MODEL_PLACEHOLDER
import me.rerere.rikkahub.data.agent.modelcouncil.ModelCouncilRolePresets
import me.rerere.rikkahub.data.agent.modelcouncil.ModelCouncilRuntimeSetting
import me.rerere.rikkahub.data.agent.modelcouncil.ModelCouncilSeat
import me.rerere.rikkahub.data.agent.modelcouncil.ModelCouncilSeatRunner
import me.rerere.rikkahub.data.agent.office.FeishuOfficeAnalysisTemplate
import me.rerere.rikkahub.data.agent.office.FeishuOfficeEnhancementManager
import me.rerere.rikkahub.data.agent.office.FeishuWorkProject
import me.rerere.rikkahub.data.agent.office.radar.DocRadar
import me.rerere.rikkahub.data.agent.prompts.AgentPromptConfigRepository
import me.rerere.rikkahub.data.agent.subagent.DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS
import me.rerere.rikkahub.data.agent.subagent.EXTENDED_SUB_AGENT_OUTPUT_BUDGET_CHARS
import me.rerere.rikkahub.data.agent.subagent.EXTENDED_SUB_AGENT_TIMEOUT_MS
import me.rerere.rikkahub.data.agent.subagent.SubAgentDefinition
import me.rerere.rikkahub.data.agent.subagent.SubAgentDefinitions
import me.rerere.rikkahub.data.agent.subagent.SubAgentMode
import me.rerere.rikkahub.data.agent.subagent.SubAgentOverride
import me.rerere.rikkahub.data.agent.subagent.SubAgentRuntimeSetting
import me.rerere.rikkahub.data.agent.subagent.applyOverride
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.db.dao.DocChangeLogDAO
import me.rerere.rikkahub.data.db.dao.DocSubscriptionDAO
import me.rerere.rikkahub.data.db.entity.DocSubscriptionEntity
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
                        onClick = { navController.navigate(Screen.SettingExperimentalWebMount) },
                        icon = { Icon(HugeIcons.Globe02, contentDescription = null) },
                        title = stringResource(R.string.setting_webmount_title),
                        description = stringResource(R.string.setting_webmount_desc),
                    )
                    ExperimentDivider()
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
                        onClick = { navController.navigate(Screen.SettingTodayBoard) },
                        icon = { Icon(HugeIcons.ServerStack01, contentDescription = null) },
                        title = "今日看板",
                        description = "Agent 主动整理每日信号，生成待办与关注项",
                    )
                    // Model Council top-level entry removed — it's now reachable from inside the
                    // SubAgent settings page as an "advanced" section (it's effectively a
                    // multi-model variant of @oracle). Route Screen.SettingExperimentalModelCouncil
                    // is preserved for the in-page jump button.
                }
            }
        }
    }
}

@Composable
fun SettingExperimentalSubAgentPage(
    vm: SettingVM = koinViewModel(),
    promptConfigRepository: AgentPromptConfigRepository = koinInject(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val subAgent = settings.agentRuntime.subAgent
    val council = settings.agentRuntime.modelCouncil
    val navController = LocalNavController.current
    val builtIns = remember { SubAgentDefinitions.builtIns }
    // Survives rotation/process death — users hate losing the open card.
    var expandedRoleId by rememberSaveable { mutableStateOf<String?>(null) }

    val concurrencyOptions = listOf(1, 2, 3, 4, 5)
    val turnOptions = listOf(2, 4, 6, 8)
    val timeoutOptions = listOf(60_000L, 180_000L, DEFAULT_SUB_AGENT_TIMEOUT_MS, 600_000L, EXTENDED_SUB_AGENT_TIMEOUT_MS)
    val budgetOptions = listOf(8_000, DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS, 20_000, 40_000, EXTENDED_SUB_AGENT_OUTPUT_BUDGET_CHARS)
    val modeOptions = SubAgentMode.entries
    // null sentinel = "follow main assistant"; otherwise pick a specific reasoning level.
    val reasoningOptions: List<ReasoningLevel?> = listOf(null) + ReasoningLevel.entries
    val scope = rememberCoroutineScope()

    fun update(block: (SubAgentRuntimeSetting) -> SubAgentRuntimeSetting) {
        val nextSubAgent = block(subAgent)
        vm.updateSettings(
            settings.copy(
                agentRuntime = settings.agentRuntime.copy(
                    subAgent = nextSubAgent
                )
            )
        )
        scope.launch {
            promptConfigRepository.writeSubAgentMarkdown(nextSubAgent)
        }
    }

    fun mutateOverride(roleId: String, mutate: (SubAgentOverride) -> SubAgentOverride) {
        update { current ->
            val cur = current.overrides[roleId] ?: SubAgentOverride()
            val next = mutate(cur)
            // Drop empty overrides so storage stays clean and "reset" is just `current.overrides - id`.
            current.copy(
                overrides = if (next == SubAgentOverride()) current.overrides - roleId
                else current.overrides + (roleId to next)
            )
        }
    }

    ExperimentalSettingsScaffold(
        title = stringResource(R.string.setting_subagent_title),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ExperimentHeroCard(
                    icon = { Icon(HugeIcons.File02, contentDescription = null) },
                    title = stringResource(R.string.setting_subagent_title),
                    description = stringResource(R.string.setting_subagent_desc),
                    trailing = {
                        Switch(
                            checked = subAgent.enabled,
                            onCheckedChange = { checked -> update { it.copy(enabled = checked) } },
                        )
                    },
                )
            }

            item {
                ExperimentSectionCard(title = stringResource(R.string.setting_subagent_section_runtime)) {
                    SubAgentSelectRow(
                        label = stringResource(R.string.setting_subagent_mode),
                        options = modeOptions,
                        selected = subAgent.mode,
                        onSelected = { value ->
                            update { current ->
                                current.copy(
                                    mode = value,
                                    allowDynamicSubAgents = if (value == SubAgentMode.SMART_DYNAMIC) true
                                    else current.allowDynamicSubAgents,
                                )
                            }
                        },
                        optionToString = { value ->
                            when (value) {
                                SubAgentMode.ROSTER -> stringResource(R.string.setting_subagent_mode_roster)
                                SubAgentMode.SMART_DYNAMIC -> stringResource(R.string.setting_subagent_mode_smart)
                            }
                        },
                    )
                    if (subAgent.mode == SubAgentMode.SMART_DYNAMIC) {
                        ExperimentNote(text = stringResource(R.string.setting_subagent_mode_smart_desc))
                    }
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
                                text = stringResource(R.string.setting_subagent_dynamic),
                                style = MaterialTheme.typography.bodyMedium,
                                color = workspaceColors().ink,
                            )
                            Text(
                                text = stringResource(R.string.setting_subagent_dynamic_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = workspaceColors().muted,
                            )
                        }
                        Switch(
                            checked = subAgent.mode == SubAgentMode.SMART_DYNAMIC || subAgent.allowDynamicSubAgents,
                            onCheckedChange = { checked -> update { it.copy(allowDynamicSubAgents = checked) } },
                            enabled = subAgent.mode != SubAgentMode.SMART_DYNAMIC,
                        )
                    }
                }
            }

            item {
                ExperimentSectionCard(title = stringResource(R.string.setting_subagent_section_limits)) {
                    SubAgentSelectRow(
                        label = stringResource(R.string.setting_subagent_max_concurrent),
                        options = concurrencyOptions,
                        selected = subAgent.maxConcurrentRuns.coerceIn(1, 5),
                        onSelected = { value -> update { it.copy(maxConcurrentRuns = value) } },
                        optionToString = { it.toString() },
                    )
                    SubAgentSelectRow(
                        label = stringResource(R.string.setting_subagent_max_turns),
                        options = turnOptions,
                        selected = subAgent.maxTurns.coerceIn(2, 8),
                        onSelected = { value -> update { it.copy(maxTurns = value) } },
                        optionToString = { it.toString() },
                    )
                    SubAgentSelectRow(
                        label = stringResource(R.string.setting_subagent_timeout),
                        options = timeoutOptions,
                        selected = timeoutOptions.minBy { kotlin.math.abs(it - subAgent.timeoutMs) },
                        onSelected = { value -> update { it.copy(timeoutMs = value) } },
                        optionToString = { "${it / 60_000} min" },
                    )
                    SubAgentSelectRow(
                        label = stringResource(R.string.setting_subagent_output_budget),
                        options = budgetOptions,
                        selected = budgetOptions.minBy { kotlin.math.abs(it - subAgent.outputBudgetChars) },
                        onSelected = { value ->
                            update { current ->
                                current.copy(
                                    outputBudgetChars = value,
                                    timeoutMs = if (value >= EXTENDED_SUB_AGENT_OUTPUT_BUDGET_CHARS) {
                                        maxOf(current.timeoutMs, EXTENDED_SUB_AGENT_TIMEOUT_MS)
                                    } else {
                                        current.timeoutMs
                                    },
                                )
                            }
                        },
                        optionToString = { "${it / 1000}k" },
                    )
                }
            }

            item {
                ExperimentSectionCard(title = stringResource(R.string.setting_subagent_section_roles)) {
                    if (subAgent.mode == SubAgentMode.SMART_DYNAMIC) {
                        ExperimentNote(text = stringResource(R.string.setting_subagent_smart_roles_hidden))
                    } else {
                        Text(
                            text = stringResource(R.string.setting_subagent_roles_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = workspaceColors().muted,
                        )
                        builtIns.forEach { def ->
                            SubAgentBuiltInRow(
                                def = def,
                                override = subAgent.overrides[def.id],
                                providers = settings.providers,
                                expanded = expandedRoleId == def.id,
                                onToggleExpand = {
                                    expandedRoleId = if (expandedRoleId == def.id) null else def.id
                                },
                                onMutateOverride = { mutate -> mutateOverride(def.id) { mutate(it) } },
                                onReset = {
                                    update { current -> current.copy(overrides = current.overrides - def.id) }
                                },
                                reasoningOptions = reasoningOptions,
                            )
                        }
                    }
                }
            }

            if (subAgent.customDefinitions.isNotEmpty()) {
                item {
                    ExperimentSectionCard(title = stringResource(R.string.setting_subagent_section_custom)) {
                        subAgent.customDefinitions.forEach { def ->
                            SubAgentCustomRow(
                                def = def,
                                onDelete = {
                                    update { current ->
                                        current.copy(
                                            customDefinitions = current.customDefinitions.filterNot { it.id == def.id }
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // Advanced: Model Council — folded in as a "multi-model variant of @oracle".
            // Top-level entry was removed; users get here through the SubAgent settings page.
            item {
                ExperimentSectionCard(title = stringResource(R.string.setting_subagent_section_advanced)) {
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
                                text = stringResource(R.string.setting_subagent_council_title),
                                style = MaterialTheme.typography.bodyMedium,
                                color = workspaceColors().ink,
                            )
                            Text(
                                text = stringResource(R.string.setting_subagent_council_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = workspaceColors().muted,
                            )
                        }
                        Switch(
                            checked = council.enabled,
                            onCheckedChange = { checked ->
                                vm.updateSettings(
                                    settings.copy(
                                        agentRuntime = settings.agentRuntime.copy(
                                            modelCouncil = council.copy(enabled = checked),
                                        )
                                    )
                                )
                            },
                        )
                    }
                    ExperimentActionRow {
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_subagent_council_configure),
                            enabled = true,
                            onClick = {
                                navController.navigate(Screen.SettingExperimentalModelCouncil)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> SubAgentSelectRow(
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
private fun SubAgentBuiltInRow(
    def: SubAgentDefinition,
    override: SubAgentOverride?,
    providers: List<ProviderSetting>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onMutateOverride: ((SubAgentOverride) -> SubAgentOverride) -> Unit,
    onReset: () -> Unit,
    reasoningOptions: List<ReasoningLevel?>,
) {
    val ws = workspaceColors()
    val effective = def.applyOverride(override)
    val effectiveModel = effective.modelId?.let { providers.findModelById(it) }
    val hasPromptOverride = !override?.systemPrompt.isNullOrBlank()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = ws.row,
        border = BorderStroke(1.dp, ws.hairline),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = def.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = ws.ink,
                    )
                    Text(
                        text = def.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = ws.muted,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (def.supportsModelOverride) {
                        val modelLabel = effectiveModel?.let { it.displayName.ifBlank { it.modelId } }
                            ?: stringResource(R.string.setting_subagent_value_inherit)
                        val reasoningLabel = (effective.reasoningLevel ?: ReasoningLevel.AUTO).name.lowercase()
                        Text(
                            text = stringResource(R.string.setting_subagent_role_summary, modelLabel, reasoningLabel) +
                                if (hasPromptOverride) " · 提示词：自定义" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = ws.faint,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.setting_subagent_role_no_model_override),
                            style = MaterialTheme.typography.labelSmall,
                            color = ws.faint,
                        )
                    }
                }
                Text(
                    text = if (expanded) "−" else "›",
                    style = MaterialTheme.typography.titleMedium,
                    color = ws.muted,
                )
            }
            if (expanded) {
                if (def.supportsModelOverride) {
                    Text(
                        text = stringResource(R.string.setting_subagent_role_model),
                        style = MaterialTheme.typography.labelMedium,
                        color = ws.faint,
                    )
                    ModelSelector(
                        modelId = effective.modelId,
                        providers = providers,
                        type = ModelType.CHAT,
                        compact = true,
                        modifier = Modifier.fillMaxWidth(),
                        onSelect = { model -> onMutateOverride { it.copy(modelId = model.id) } },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.setting_subagent_role_reasoning),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ws.ink,
                        )
                        Select(
                            options = reasoningOptions,
                            selectedOption = override?.reasoningLevel,
                            onOptionSelected = { value -> onMutateOverride { it.copy(reasoningLevel = value) } },
                            optionToString = { value ->
                                value?.name?.lowercase()
                                    ?: stringResource(R.string.setting_subagent_value_inherit)
                            },
                            modifier = Modifier.width(140.dp),
                        )
                    }
                }
                Text(
                    text = "角色提示词",
                    style = MaterialTheme.typography.labelMedium,
                    color = ws.faint,
                )
                OutlinedTextField(
                    value = override?.systemPrompt ?: def.systemPrompt,
                    onValueChange = { value ->
                        val normalized = value.take(8_000)
                        onMutateOverride {
                            it.copy(
                                systemPrompt = normalized
                                    .takeIf { prompt -> prompt.isNotBlank() && prompt != def.systemPrompt }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 10,
                    textStyle = MaterialTheme.typography.bodySmall,
                    placeholder = {
                        Text(
                            text = "描述这个 SubAgent 的身份、边界、可用工具和输出格式。",
                            style = MaterialTheme.typography.bodySmall,
                            color = ws.faint,
                        )
                    },
                    supportingText = {
                        Text(
                            text = "${(override?.systemPrompt ?: def.systemPrompt).length.coerceAtMost(8_000)} / 8000 · 留空会恢复默认提示词",
                            style = MaterialTheme.typography.labelSmall,
                            color = ws.faint,
                        )
                    },
                )
                Text(
                    text = stringResource(R.string.setting_subagent_role_routing),
                    style = MaterialTheme.typography.labelMedium,
                    color = ws.faint,
                )
                Text(
                    text = def.routingHint.ifBlank { def.description },
                    style = MaterialTheme.typography.bodySmall,
                    color = ws.muted,
                )
                ExperimentActionRow {
                    if (hasPromptOverride) {
                        ExperimentActionButton(
                            text = "恢复默认提示词",
                            enabled = true,
                            onClick = { onMutateOverride { it.copy(systemPrompt = null) } },
                        )
                    }
                    if (override != null) {
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_subagent_role_reset),
                            enabled = true,
                            onClick = onReset,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubAgentCustomRow(
    def: SubAgentDefinition,
    onDelete: () -> Unit,
) {
    val ws = workspaceColors()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = ws.row,
        border = BorderStroke(1.dp, ws.hairline),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = def.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = ws.ink,
                    modifier = Modifier.weight(1f),
                )
                ExperimentActionButton(
                    text = stringResource(R.string.delete),
                    enabled = true,
                    onClick = onDelete,
                )
            }
            Text(
                text = def.description,
                style = MaterialTheme.typography.bodySmall,
                color = ws.muted,
            )
        }
    }
}

@Composable
fun SettingExperimentalModelCouncilPage(
    vm: SettingVM = koinViewModel(),
    promptConfigRepository: AgentPromptConfigRepository = koinInject(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val council = settings.agentRuntime.modelCouncil
    val chatModels = remember(settings.providers) {
        settings.providers
            .flatMap { provider -> provider.models }
            .filter { model -> model.type == ModelType.CHAT }
    }
    // Bumped to fit the new "3 core seats + up to 5 lens" model. Old code clamped at 4 which
    // truncated user lens picks the moment they touched this row after upgrading.
    val maxSeatOptions = listOf(3, 5, 6, 8)
    val roundOptions = listOf(1, 2, 3, 4, 5)
    val timeoutOptions = listOf(60_000L, DEFAULT_MODEL_COUNCIL_SEAT_TIMEOUT_MS, 480_000L, EXTENDED_MODEL_COUNCIL_SEAT_TIMEOUT_MS)
    val budgetOptions = listOf(8_000, DEFAULT_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS, 20_000, 40_000, EXTENDED_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS)
    val scope = rememberCoroutineScope()

    fun update(block: (ModelCouncilRuntimeSetting) -> ModelCouncilRuntimeSetting) {
        val nextCouncil = block(council)
        vm.updateSettings(
            settings.copy(
                agentRuntime = settings.agentRuntime.copy(
                    modelCouncil = nextCouncil
                )
            )
        )
        scope.launch {
            promptConfigRepository.writeModelCouncilMarkdown(nextCouncil)
        }
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

    fun addSeat(runnerType: ModelCouncilSeatRunner = ModelCouncilSeatRunner.PROVIDER_MODEL) {
        val model = chatModels.firstOrNull()
        if (runnerType == ModelCouncilSeatRunner.PROVIDER_MODEL && model == null) return
        // Pick a lens preset first — core seats (supporter/opponent/judge) are auto-injected
        // at runtime, so users don't need to manually add them. Skip lenses already in use.
        val usedRoleIds = council.defaultSeats.map {
            ModelCouncilRolePresets.byName(it.role)?.id ?: it.role
        }.toSet()
        val preset = ModelCouncilRolePresets.lensPresets.firstOrNull { it.id !in usedRoleIds }
        val customIndex = council.defaultSeats.size + 1
        val seat = ModelCouncilSeat(
            seatId = Uuid.random().toString(),
            name = preset?.name ?: if (runnerType == ModelCouncilSeatRunner.EXTERNAL_CLI) {
                "Gemini CLI 席位"
            } else {
                "自定义席位 $customIndex"
            },
            role = preset?.id ?: "custom-$customIndex",  // store canonical id; byName tolerates legacy aliases
            modelId = model?.id ?: Uuid.parse(MODEL_COUNCIL_EXTERNAL_MODEL_PLACEHOLDER),
            runnerType = runnerType,
            systemPrompt = preset?.prompt ?: "请从这个席位的独特视角评估议题，给出清晰、可验证、不过度发散的判断。",
            outputBudgetChars = council.outputBudgetChars,
            externalTool = if (runnerType == ModelCouncilSeatRunner.EXTERNAL_CLI) "gemini_cli" else "",
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
                    ExperimentNote(text = stringResource(R.string.setting_model_council_seats_explainer))
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
                            onSeatChanged = { next ->
                                updateSeat(seat.seatId) { next }
                            },
                            onPresetSelected = { preset ->
                                updateSeat(seat.seatId) {
                                    it.copy(
                                        name = preset.name,
                                        role = preset.id,  // canonical id (M1: was preset.name → bypassed core dedup)
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
                            enabled = chatModels.isNotEmpty() &&
                                council.defaultSeats.size < council.maxSeats,
                            onClick = { addSeat(ModelCouncilSeatRunner.PROVIDER_MODEL) },
                        )
                        ExperimentActionButton(
                            text = "添加 Gemini CLI 席位",
                            enabled = chatModels.isNotEmpty() &&
                                council.defaultSeats.size < council.maxSeats,
                            onClick = { addSeat(ModelCouncilSeatRunner.EXTERNAL_CLI) },
                        )
                    }
                    ExperimentNote(text = "Gemini CLI 席位只会在本轮议会工具调用显式允许外部 CLI 时参与；启动本地终端进程前会要求人工确认。")
                }
            }
            item {
                ExperimentSectionCard(title = stringResource(R.string.setting_model_council_limits_section)) {
                    ModelCouncilSelectRow(
                        label = stringResource(R.string.setting_model_council_max_seats),
                        options = maxSeatOptions,
                        selected = council.maxSeats.coerceIn(3, 8),
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
                        selected = council.defaultRounds.coerceIn(1, council.maxRounds.coerceAtLeast(DEFAULT_MODEL_COUNCIL_MAX_ROUNDS)),
                        onSelected = { value ->
                            update { current ->
                                current.copy(
                                    defaultRounds = value,
                                    maxRounds = maxOf(current.maxRounds, value, DEFAULT_MODEL_COUNCIL_MAX_ROUNDS),
                                )
                            }
                        },
                        optionToString = { it.toString() },
                    )
                    ModelCouncilSelectRow(
                        label = stringResource(R.string.setting_model_council_timeout),
                        options = timeoutOptions,
                        selected = timeoutOptions.minBy { kotlin.math.abs(it - council.seatTimeoutMs) },
                        onSelected = { value ->
                            update { current ->
                                current.copy(
                                    seatTimeoutMs = value,
                                    totalTimeoutMs = if (value >= EXTENDED_MODEL_COUNCIL_SEAT_TIMEOUT_MS) {
                                        maxOf(current.totalTimeoutMs, EXTENDED_MODEL_COUNCIL_TOTAL_TIMEOUT_MS)
                                    } else {
                                        current.totalTimeoutMs
                                    },
                                )
                            }
                        },
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
                                    seatTimeoutMs = if (value >= EXTENDED_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS) {
                                        maxOf(current.seatTimeoutMs, EXTENDED_MODEL_COUNCIL_SEAT_TIMEOUT_MS)
                                    } else {
                                        current.seatTimeoutMs
                                    },
                                    totalTimeoutMs = if (value >= EXTENDED_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS) {
                                        maxOf(current.totalTimeoutMs, EXTENDED_MODEL_COUNCIL_TOTAL_TIMEOUT_MS)
                                    } else {
                                        current.totalTimeoutMs
                                    },
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
    onSeatChanged: (ModelCouncilSeat) -> Unit,
    onPresetSelected: (me.rerere.rikkahub.data.agent.modelcouncil.ModelCouncilRolePreset) -> Unit,
    onDelete: () -> Unit,
) {
    val workspace = workspaceColors()
    val runnerOptions = listOf(ModelCouncilSeatRunner.PROVIDER_MODEL, ModelCouncilSeatRunner.EXTERNAL_CLI)
    val reasoningOptions = listOf<ReasoningLevel?>(null) + ReasoningLevel.entries
    val runtimeOptions = listOf("", "builtin_alpine", "android_shell", "termux_external")
    val selectedRuntime = seat.externalRuntime.takeIf { it in runtimeOptions }.orEmpty()
    val firstChatModel = remember(settingsProviders) {
        settingsProviders.flatMap { it.models }.firstOrNull { it.type == ModelType.CHAT }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = workspace.paper,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = workspace.row,
                    contentColor = workspace.faint,
                    border = BorderStroke(1.dp, workspace.hairline),
                ) {
                    Text(
                        text = "#${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = seat.name.ifBlank { "未命名席位" },
                        style = MaterialTheme.typography.titleSmall,
                        color = workspace.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (seat.runnerType == ModelCouncilSeatRunner.EXTERNAL_CLI) {
                            "外部 CLI · ${seat.externalTool.ifBlank { "gemini_cli" }}"
                        } else {
                            "模型席位 · ${ModelCouncilRolePresets.byName(seat.role)?.name ?: seat.role}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = workspace.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ExperimentActionButton(
                    text = stringResource(R.string.delete),
                    enabled = true,
                    onClick = onDelete,
                )
            }

            OutlinedTextField(
                value = seat.name,
                onValueChange = { value -> onSeatChanged(seat.copy(name = value.take(40))) },
                label = { Text("席位名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            ModelCouncilPropertyRow(label = "席位类型") {
                Select(
                    options = runnerOptions,
                    selectedOption = seat.runnerType,
                    onOptionSelected = { runner ->
                        onSeatChanged(
                            seat.copy(
                                runnerType = runner,
                                modelId = if (
                                    runner == ModelCouncilSeatRunner.PROVIDER_MODEL &&
                                    settingsProviders.flatMap { it.models }.none { it.id == seat.modelId && it.type == ModelType.CHAT }
                                ) {
                                    firstChatModel?.id ?: seat.modelId
                                } else {
                                    seat.modelId
                                },
                                externalTool = if (runner == ModelCouncilSeatRunner.EXTERNAL_CLI) {
                                    seat.externalTool.ifBlank { "gemini_cli" }
                                } else {
                                    ""
                                },
                            )
                        )
                    },
                    optionToString = {
                        when (it) {
                            ModelCouncilSeatRunner.PROVIDER_MODEL -> "模型"
                            ModelCouncilSeatRunner.EXTERNAL_CLI -> "Gemini CLI"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ModelCouncilPropertyRow(label = "角色预设") {
                Select(
                    options = ModelCouncilRolePresets.presets,
                    selectedOption = ModelCouncilRolePresets.byName(seat.role) ?: ModelCouncilRolePresets.presets.first(),
                    onOptionSelected = onPresetSelected,
                    modifier = Modifier.fillMaxWidth(),
                    optionToString = { it.name },
                )
            }

            if (seat.runnerType == ModelCouncilSeatRunner.PROVIDER_MODEL) {
                ModelCouncilPropertyRow(label = "模型") {
                    ModelSelector(
                        modelId = seat.modelId,
                        providers = settingsProviders,
                        type = ModelType.CHAT,
                        compact = true,
                        modifier = Modifier.fillMaxWidth(),
                        onSelect = { model -> onSeatChanged(seat.copy(modelId = model.id)) },
                    )
                }
                ModelCouncilPropertyRow(label = "思考档位") {
                    Select(
                        options = reasoningOptions,
                        selectedOption = seat.reasoningLevel,
                        onOptionSelected = { level -> onSeatChanged(seat.copy(reasoningLevel = level)) },
                        optionToString = { level -> level?.name?.lowercase() ?: "默认" },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                ModelCouncilPropertyRow(label = "运行时") {
                    Select(
                        options = runtimeOptions,
                        selectedOption = selectedRuntime,
                        onOptionSelected = { runtime -> onSeatChanged(seat.copy(externalRuntime = runtime)) },
                        optionToString = {
                            when (it) {
                                "" -> "跟随终端设置"
                                "builtin_alpine" -> "内置 Alpine"
                                "android_shell" -> "Android Shell"
                                "termux_external" -> "Termux"
                                else -> it
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = seat.externalModel,
                    onValueChange = { value -> onSeatChanged(seat.copy(externalModel = value.take(120))) },
                    label = { Text("Gemini CLI 模型参数（可选）") },
                    placeholder = { Text("例如 gemini-2.5-pro") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ExperimentNote(text = "外部 CLI 席位会在终端运行时中隔离执行。手机上需要对应运行时能找到 gemini 命令。")
            }

            OutlinedTextField(
                value = seat.systemPrompt,
                onValueChange = { value -> onSeatChanged(seat.copy(systemPrompt = value.take(2_000))) },
                label = { Text("席位提示词") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ModelCouncilPropertyRow(
    label: String,
    content: @Composable () -> Unit,
) {
    val workspace = workspaceColors()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = workspace.faint,
        )
        content()
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
    val iCloudLoginSnapshot = remember(iCloudState.updatedAtMillis, iCloudBusy, showICloudLogin) {
        iCloudDriveManager.loginSnapshot()
    }

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
                    ExperimentStatusRow(
                        label = stringResource(R.string.setting_icloud_endpoint_hint),
                        value = iCloudLoginSnapshot.endpointHint?.displayName ?: stringResource(R.string.setting_icloud_endpoint_unknown),
                    )
                    ExperimentStatusRow(
                        label = stringResource(R.string.setting_icloud_login_detected),
                        value = if (iCloudLoginSnapshot.loginDetected) {
                            stringResource(R.string.setting_icloud_login_detected_yes)
                        } else {
                            stringResource(R.string.setting_icloud_login_detected_no)
                        },
                    )
                    ExperimentStatusRow(
                        label = stringResource(R.string.setting_icloud_next_action),
                        value = iCloudDriveManager.nextAction(iCloudState),
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
    docRadar: DocRadar = koinInject(),
    docSubscriptionDao: DocSubscriptionDAO = koinInject(),
    docChangeLogDao: DocChangeLogDAO = koinInject(),
) {
    val officeState by feishuOfficeManager.state.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var officeBusy by remember { mutableStateOf(false) }
    var officePackageInput by remember(officeState.targetPackage) { mutableStateOf(officeState.targetPackage) }
    var officeCandidates by remember { mutableStateOf("") }
    val officeSavedToast = stringResource(R.string.setting_officepro_saved)
    val officeDetectNoneToast = stringResource(R.string.setting_officepro_detect_none)
    var docRadarNotify by remember { mutableStateOf(true) }
    var subscriptions by remember { mutableStateOf<List<DocSubscriptionEntity>>(emptyList()) }
    var showWatchDialog by remember { mutableStateOf(false) }
    var checkBusy by remember { mutableStateOf(false) }

    LaunchedEffect(officeState.enabled) {
        if (officeState.enabled) {
            subscriptions = docSubscriptionDao.getEnabled()
        }
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
            // Simplified: 2-toggles instead of the old 5-switch detail panel
            item {
                ExperimentSectionCard(title = stringResource(R.string.setting_officepro_capture_section)) {
                    OfficeProSwitchRow(
                        text = stringResource(R.string.setting_officepro_local_signals),
                        checked = officeState.includeNotificationsByDefault || officeState.includeUsageByDefault,
                        enabled = officeState.enabled,
                        onCheckedChange = { checked ->
                            feishuOfficeManager.setIncludeNotificationsByDefault(checked)
                            feishuOfficeManager.setIncludeUsageByDefault(checked)
                        },
                    )
                    ExperimentNote(text = stringResource(R.string.setting_officepro_local_signals_note))
                    ExperimentDivider()
                    OfficeProSwitchRow(
                        text = stringResource(R.string.setting_officepro_mcp_enhance),
                        checked = officeState.includeMcpHintsByDefault,
                        enabled = officeState.enabled,
                        onCheckedChange = feishuOfficeManager::setIncludeMcpHintsByDefault,
                    )
                    ExperimentNote(text = stringResource(R.string.setting_officepro_mcp_note))
                }
            }
            // New: Document Radar
            item {
                ExperimentSectionCard(title = stringResource(R.string.setting_officepro_doc_radar_section)) {
                    ExperimentNote(text = stringResource(R.string.setting_officepro_doc_radar_desc))
                    ExperimentActionRow {
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_officepro_watch_doc),
                            primary = true,
                            enabled = officeState.enabled,
                            onClick = { showWatchDialog = true },
                        )
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_officepro_check_now),
                            enabled = officeState.enabled && !checkBusy,
                            onClick = {
                                checkBusy = true
                                scope.launch {
                                    try {
                                        docRadar.runOnce()
                                        subscriptions = docSubscriptionDao.getEnabled()
                                        toaster.show(officeSavedToast)
                                    } catch (e: Exception) {
                                        toaster.show(e.message ?: "检查失败")
                                    }
                                    checkBusy = false
                                }
                            },
                        )
                    }
                    if (subscriptions.isNotEmpty()) {
                        subscriptions.forEach { doc ->
                            ExperimentDivider()
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
                                        text = doc.docTitle,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    ExperimentActionButton(
                                        text = stringResource(R.string.setting_officepro_doc_unsubscribe),
                                        enabled = officeState.enabled,
                                        onClick = {
                                            scope.launch {
                                                docRadar.unsubscribe(doc.id)
                                                subscriptions = docSubscriptionDao.getEnabled()
                                            }
                                        },
                                    )
                                }
                                if (!doc.hasBaseline) {
                                    Text(
                                        text = "⚠ 基线未建立",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = workspaceColors().faint,
                                    )
                                }
                                doc.lastCheckedAt?.let { lastCheckedAt ->
                                    Text(
                                        text = stringResource(
                                            R.string.setting_officepro_doc_last_checked,
                                            formatOfficeProjectUpdatedAt(lastCheckedAt),
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = workspaceColors().faint,
                                    )
                                }
                                doc.lastChangedAt?.let { lastChangedAt ->
                                    Text(
                                        text = stringResource(
                                            R.string.setting_officepro_doc_last_changed,
                                            formatOfficeProjectUpdatedAt(lastChangedAt),
                                            doc.threshold,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = workspaceColors().faint,
                                    )
                                } ?: run {
                                    Text(
                                        text = stringResource(R.string.setting_officepro_doc_no_change),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = workspaceColors().faint,
                                    )
                                }
                            }
                        }
                    } else {
                        ExperimentNote(text = stringResource(R.string.setting_officepro_doc_empty))
                    }
                    ExperimentDivider()
                    ExperimentStatusRow(
                        label = stringResource(R.string.setting_officepro_check_interval_label),
                        value = stringResource(R.string.setting_officepro_interval_value),
                    )
                    OfficeProSwitchRow(
                        text = stringResource(R.string.setting_officepro_change_notification),
                        checked = docRadarNotify,
                        enabled = officeState.enabled,
                        onCheckedChange = { docRadarNotify = it },
                    )
                }
            }
        }
    }
    if (showWatchDialog) {
        WatchDocDialog(
            onDismiss = { showWatchDialog = false },
            onSave = { title, url, threshold, interval, notify ->
                scope.launch {
                    val error = docRadar.subscribe(
                        url = url,
                        title = title,
                        threshold = threshold,
                        notifyEnabled = notify,
                    )
                    if (error != null) {
                        toaster.show(error)
                    } else {
                        toaster.show(officeSavedToast)
                    }
                    subscriptions = docSubscriptionDao.getEnabled()
                    showWatchDialog = false
                }
            },
            onToast = toaster::show,
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
private fun WatchDocDialog(
    onDismiss: () -> Unit,
    onSave: (title: String, url: String, threshold: Int, interval: Int, notify: Boolean) -> Unit,
    onToast: (String) -> Unit,
) {
    var titleInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var thresholdInput by remember { mutableStateOf("500") }
    var intervalInput by remember { mutableStateOf("90") }
    var notifyEnabled by remember { mutableStateOf(true) }
    val workspace = workspaceColors()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = workspace.paper,
            border = BorderStroke(1.dp, workspace.hairline),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.setting_officepro_watch_doc),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(HugeIcons.Cancel01, contentDescription = null)
                    }
                }
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("飞书文档 URL") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("文档标题") },
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = thresholdInput,
                        onValueChange = { thresholdInput = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("变更阈值（字）") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = intervalInput,
                        onValueChange = { intervalInput = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("间隔（分钟）") },
                        singleLine = true,
                    )
                }
                OfficeProSwitchRow(
                    text = stringResource(R.string.setting_officepro_change_notification),
                    checked = notifyEnabled,
                    enabled = true,
                    onCheckedChange = { notifyEnabled = it },
                )
                ExperimentActionRow {
                    ExperimentActionButton(
                        text = stringResource(R.string.setting_officepro_save_project),
                        primary = true,
                        enabled = urlInput.isNotBlank() && titleInput.isNotBlank(),
                        onClick = {
                            val url = urlInput.trim()
                            val title = titleInput.trim()
                            val threshold = thresholdInput.trim().toIntOrNull() ?: 500
                            val interval = intervalInput.trim().toIntOrNull() ?: 90
                            if (url.isBlank() || title.isBlank()) {
                                onToast("请填写文档 URL 和标题")
                            } else {
                                onSave(title, url, threshold, interval, notifyEnabled)
                            }
                        },
                    )
                    ExperimentActionButton(
                        text = stringResource(R.string.cancel),
                        enabled = true,
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
internal fun ExperimentSectionCard(
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
internal fun ExperimentHeroCard(
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
internal fun ExperimentDivider() {
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
internal fun ExperimentActionRow(
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
internal fun ExperimentActionButton(
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
internal fun ExperimentStatusRow(
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
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (ready) workspace.blue.copy(alpha = 0.1f) else workspace.row,
        contentColor = if (ready) workspace.blue else workspace.muted,
        border = BorderStroke(1.dp, if (ready) workspace.blue.copy(alpha = 0.22f) else workspace.hairline),
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
internal fun ExperimentNote(
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
internal fun ExperimentalSettingsScaffold(
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
