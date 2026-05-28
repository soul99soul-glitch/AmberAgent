package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import me.rerere.rikkahub.ui.components.ui.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import app.amber.feature.prompts.AgentPromptConfigRepository
import app.amber.feature.subagent.DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS
import app.amber.feature.subagent.DEFAULT_SUB_AGENT_TIMEOUT_MS
import app.amber.feature.subagent.EXTENDED_SUB_AGENT_OUTPUT_BUDGET_CHARS
import app.amber.feature.subagent.EXTENDED_SUB_AGENT_TIMEOUT_MS
import app.amber.feature.subagent.SubAgentDefinition
import app.amber.feature.subagent.SubAgentDefinitions
import app.amber.feature.subagent.SubAgentMode
import app.amber.feature.subagent.SubAgentOverride
import app.amber.feature.subagent.SubAgentRuntimeSetting
import app.amber.feature.subagent.applyOverride
import app.amber.core.settings.findModelById
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.context.LocalNavController
import app.amber.core.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

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
            // V3: 去掉 width(112dp) 硬约束. 之前 Select 内容只占 ~60dp, 在 112dp 容器内左对齐
            // 导致看着没"顶到右". 改 wrap content + weight(1f) 让 Select 自动靠 row 右端.
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
