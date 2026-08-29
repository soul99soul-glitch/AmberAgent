package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import app.amber.feature.ui.components.ui.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.ProviderSetting
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.FileText
import app.amber.agent.R
import app.amber.feature.modelcouncil.DEFAULT_MODEL_COUNCIL_MAX_ROUNDS
import app.amber.feature.modelcouncil.DEFAULT_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS
import app.amber.feature.modelcouncil.DEFAULT_MODEL_COUNCIL_SEAT_TIMEOUT_MS
import app.amber.feature.modelcouncil.EXTENDED_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS
import app.amber.feature.modelcouncil.EXTENDED_MODEL_COUNCIL_SEAT_TIMEOUT_MS
import app.amber.feature.modelcouncil.EXTENDED_MODEL_COUNCIL_TOTAL_TIMEOUT_MS
import app.amber.feature.modelcouncil.EXTERNAL_CLI_DEFAULT_TOOL_ID
import app.amber.feature.modelcouncil.ExternalCliToolRegistry
import app.amber.feature.modelcouncil.CouncilPowerMode
import app.amber.feature.modelcouncil.MODEL_COUNCIL_EXTERNAL_MODEL_PLACEHOLDER
import app.amber.feature.modelcouncil.ModelCouncilRolePresets
import app.amber.feature.modelcouncil.ModelCouncilRuntimeSetting
import app.amber.feature.modelcouncil.ModelCouncilSeat
import app.amber.feature.modelcouncil.ModelCouncilSeatRunner
import app.amber.feature.prompts.AgentPromptConfigRepository
import app.amber.feature.ui.components.ai.ModelSelector
import app.amber.feature.ui.components.ui.Select
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.core.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun SettingExperimentalModelCouncilPage(
    vm: SettingVM = koinViewModel(),
    promptConfigRepository: AgentPromptConfigRepository = koinInject(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val council = settings.agentRuntime.modelCouncil
    val chatModels = remember(settings.providers) {
        settings.providers
            .flatMap { provider -> provider.models }
            .filter { model -> model.type == ModelType.CHAT }
    }
    // Bumped to fit the new "3 core seats + up to 5 lens" model. Old code clamped at 4 which
    // truncated user lens picks the moment they touched this row after upgrading.
    val maxSeatOptions = listOf(3, 5, 6, 8)
    val roundOptions = listOf(2, 3, 4, 5, 6)
    // Host reasoning effort: null = defer to model default, then every level.
    val reasoningLevelOptions = listOf<ReasoningLevel?>(null) + ReasoningLevel.entries
    val powerModeOptions = CouncilPowerMode.entries
    val timeoutOptions = listOf(60_000L, DEFAULT_MODEL_COUNCIL_SEAT_TIMEOUT_MS, 480_000L, EXTENDED_MODEL_COUNCIL_SEAT_TIMEOUT_MS)
    val budgetOptions = listOf(8_000, DEFAULT_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS, 20_000, 40_000, EXTENDED_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS)
    val scope = rememberCoroutineScope()
    val councilPowerModeLabel = stringResource(R.string.setting_model_council_power_mode)
    val councilPowerModeStandardLabel = stringResource(R.string.setting_model_council_power_mode_standard)
    val councilPowerModeFullLabel = stringResource(R.string.setting_model_council_power_mode_full)
    val councilPowerModeStandardDescription = stringResource(R.string.setting_model_council_power_mode_standard_description)
    val councilPowerModeFullDescription = stringResource(R.string.setting_model_council_power_mode_full_description)
    val councilHostModelLabel = stringResource(R.string.setting_model_council_host_model)
    val councilHostReasoningLabel = stringResource(R.string.setting_model_council_host_reasoning)
    val councilHostReasoningDefaultLabel = stringResource(R.string.setting_model_council_host_reasoning_default)
    val councilHostPromptLabel = stringResource(R.string.setting_model_council_host_prompt_label)
    val councilHostPromptPlaceholder = stringResource(R.string.setting_model_council_host_prompt_placeholder)
    val councilHostNote = stringResource(R.string.setting_model_council_host_note)
    val addExternalSeatLabel = stringResource(R.string.setting_model_council_add_external_seat)
    val externalSeatNote = stringResource(R.string.setting_model_council_external_seat_note)
    val councilPowerModeDescription = when (council.councilPowerMode) {
        CouncilPowerMode.STANDARD -> councilPowerModeStandardDescription
        CouncilPowerMode.FULL -> councilPowerModeFullDescription
    }

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
                context.getString(R.string.setting_model_council_external_cli)
            } else {
                "${context.getString(R.string.setting_model_council_unnamed_seat)} $customIndex"
            },
            role = preset?.id ?: "custom-$customIndex",  // store canonical id; byName tolerates legacy aliases
            modelId = model?.id ?: Uuid.parse(MODEL_COUNCIL_EXTERNAL_MODEL_PLACEHOLDER),
            runnerType = runnerType,
            systemPrompt = preset?.prompt ?: "From this seat's unique perspective, evaluate the topic with a clear, testable, and focused judgment.",
            outputBudgetChars = council.outputBudgetChars,
            externalTool = if (runnerType == ModelCouncilSeatRunner.EXTERNAL_CLI) EXTERNAL_CLI_DEFAULT_TOOL_ID else "",
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
                    icon = { Icon(Lucide.FileText, contentDescription = null) },
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
                    ModelCouncilPropertyRow(label = councilPowerModeLabel) {
                        Select(
                            options = powerModeOptions,
                            selectedOption = council.councilPowerMode,
                            onOptionSelected = { mode ->
                                update { it.copy(councilPowerMode = mode) }
                            },
                            optionToString = { mode ->
                                when (mode) {
                                    CouncilPowerMode.STANDARD -> councilPowerModeStandardLabel
                                    CouncilPowerMode.FULL -> councilPowerModeFullLabel
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        text = councilPowerModeDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = workspaceColors().muted,
                    )
                    Text(
                        text = councilHostModelLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = workspaceColors().faint,
                    )
                    ModelSelector(
                        modelId = council.hostModelId ?: settings.chatModelId,
                        providers = settings.providers,
                        type = ModelType.CHAT,
                        compact = true,
                        modifier = Modifier.fillMaxWidth(),
                        onSelect = { model -> update { it.copy(hostModelId = model.id) } },
                    )
                    ModelCouncilPropertyRow(label = councilHostReasoningLabel) {
                        Select(
                            options = reasoningLevelOptions,
                            selectedOption = council.hostReasoningLevel,
                            onOptionSelected = { level ->
                                update { it.copy(hostReasoningLevel = level) }
                            },
                            optionToString = { level -> level?.name?.lowercase() ?: councilHostReasoningDefaultLabel },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        value = council.hostSystemPrompt,
                        onValueChange = { value -> update { it.copy(hostSystemPrompt = value.take(2_000)) } },
                        label = { Text(councilHostPromptLabel) },
                        placeholder = { Text(councilHostPromptPlaceholder) },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = councilHostNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = workspaceColors().muted,
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
                            text = addExternalSeatLabel,
                            enabled = chatModels.isNotEmpty() &&
                                council.defaultSeats.size < council.maxSeats,
                            onClick = { addSeat(ModelCouncilSeatRunner.EXTERNAL_CLI) },
                        )
                    }
                    ExperimentNote(text = externalSeatNote)
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
                        selected = council.defaultRounds.coerceIn(2, 6),
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
    settingsProviders: List<app.amber.ai.provider.ProviderSetting>,
    onSeatChanged: (ModelCouncilSeat) -> Unit,
    onPresetSelected: (app.amber.feature.modelcouncil.ModelCouncilRolePreset) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val workspace = workspaceColors()
    val runnerOptions = listOf(ModelCouncilSeatRunner.PROVIDER_MODEL, ModelCouncilSeatRunner.EXTERNAL_CLI)
    val reasoningOptions = listOf<ReasoningLevel?>(null) + ReasoningLevel.entries
    val runtimeOptions = listOf("", "builtin_alpine", "android_shell", "termux_external")
    val selectedRuntime = seat.externalRuntime.takeIf { it in runtimeOptions }.orEmpty()
    val externalToolOptions = ExternalCliToolRegistry.tools
    val selectedExternalTool = externalToolOptions.firstOrNull {
        it.id == ExternalCliToolRegistry.normalizeToolId(seat.externalTool)
    } ?: externalToolOptions.first()
    val unnamedSeatLabel = stringResource(R.string.setting_model_council_unnamed_seat)
    val externalSeatSummary = stringResource(
        R.string.setting_model_council_external_seat_summary,
        selectedExternalTool.displayName,
    )
    val modelSeatSummary = stringResource(
        R.string.setting_model_council_model_seat_summary,
        ModelCouncilRolePresets.byName(seat.role)?.name ?: seat.role,
    )
    val seatNameLabel = stringResource(R.string.setting_model_council_seat_name)
    val seatTypeLabel = stringResource(R.string.setting_model_council_seat_type)
    val externalCliLabel = stringResource(R.string.setting_model_council_external_cli)
    val rolePresetLabel = stringResource(R.string.setting_model_council_role_preset)
    val modelLabel = stringResource(R.string.setting_model_council_model)
    val reasoningLabel = stringResource(R.string.setting_model_council_reasoning)
    val reasoningDefaultLabel = stringResource(R.string.setting_model_council_reasoning_default)
    val cliTypeLabel = stringResource(R.string.setting_model_council_cli_type)
    val runtimeLabel = stringResource(R.string.setting_model_council_runtime)
    val runtimeFollowTerminalLabel = stringResource(R.string.setting_model_council_runtime_follow_terminal)
    val runtimeBuiltinAlpineLabel = stringResource(R.string.setting_model_council_runtime_builtin_alpine)
    val cliModelParameterLabel = stringResource(R.string.setting_model_council_cli_model_parameter)
    val externalRuntimeNote = stringResource(
        R.string.setting_model_council_external_runtime_note,
        selectedExternalTool.binary,
    )
    val seatPromptLabel = stringResource(R.string.setting_model_council_seat_prompt)
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
                        text = seat.name.ifBlank { unnamedSeatLabel },
                        style = MaterialTheme.typography.titleSmall,
                        color = workspace.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (seat.runnerType == ModelCouncilSeatRunner.EXTERNAL_CLI) externalSeatSummary else modelSeatSummary,
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
                label = { Text(seatNameLabel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            ModelCouncilPropertyRow(label = seatTypeLabel) {
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
                                    seat.externalTool.ifBlank { EXTERNAL_CLI_DEFAULT_TOOL_ID }
                                } else {
                                    ""
                                },
                            )
                        )
                    },
                    optionToString = {
                        when (it) {
                            ModelCouncilSeatRunner.PROVIDER_MODEL -> modelLabel
                            ModelCouncilSeatRunner.EXTERNAL_CLI -> externalCliLabel
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ModelCouncilPropertyRow(label = rolePresetLabel) {
                Select(
                    options = ModelCouncilRolePresets.presets,
                    selectedOption = ModelCouncilRolePresets.byName(seat.role) ?: ModelCouncilRolePresets.presets.first(),
                    onOptionSelected = onPresetSelected,
                    modifier = Modifier.fillMaxWidth(),
                    optionToString = { it.name },
                )
            }

            if (seat.runnerType == ModelCouncilSeatRunner.PROVIDER_MODEL) {
                ModelCouncilPropertyRow(label = modelLabel) {
                    ModelSelector(
                        modelId = seat.modelId,
                        providers = settingsProviders,
                        type = ModelType.CHAT,
                        compact = true,
                        modifier = Modifier.fillMaxWidth(),
                        onSelect = { model -> onSeatChanged(seat.copy(modelId = model.id)) },
                    )
                }
                ModelCouncilPropertyRow(label = reasoningLabel) {
                    Select(
                        options = reasoningOptions,
                        selectedOption = seat.reasoningLevel,
                        onOptionSelected = { level -> onSeatChanged(seat.copy(reasoningLevel = level)) },
                        optionToString = { level -> level?.name?.lowercase() ?: reasoningDefaultLabel },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                ModelCouncilPropertyRow(label = cliTypeLabel) {
                    Select(
                        options = externalToolOptions,
                        selectedOption = selectedExternalTool,
                        onOptionSelected = { tool ->
                            onSeatChanged(
                                seat.copy(
                                    externalTool = tool.id,
                                    name = if (
                                        seat.name.isBlank() ||
                                        seat.name == "Gemini CLI 席位" ||
                                        seat.name == "外部 CLI 席位" ||
                                        seat.name == context.getString(R.string.setting_model_council_external_cli)
                                    ) {
                                        context.getString(
                                            R.string.setting_model_council_external_seat_summary,
                                            tool.displayName,
                                        )
                                    } else {
                                        seat.name
                                    },
                                )
                            )
                        },
                        optionToString = { it.displayName },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ModelCouncilPropertyRow(label = runtimeLabel) {
                    Select(
                        options = runtimeOptions,
                        selectedOption = selectedRuntime,
                        onOptionSelected = { runtime -> onSeatChanged(seat.copy(externalRuntime = runtime)) },
                        optionToString = {
                            when (it) {
                                "" -> runtimeFollowTerminalLabel
                                "builtin_alpine" -> runtimeBuiltinAlpineLabel
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
                    label = { Text(cliModelParameterLabel) },
                    placeholder = { Text(selectedExternalTool.modelPlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ExperimentNote(text = externalRuntimeNote)
            }

            OutlinedTextField(
                value = seat.systemPrompt,
                onValueChange = { value -> onSeatChanged(seat.copy(systemPrompt = value.take(2_000))) },
                label = { Text(seatPromptLabel) },
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
            // V3 ValueChip 内容自适应,
        )
    }
}
