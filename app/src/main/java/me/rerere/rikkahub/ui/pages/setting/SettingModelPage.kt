package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.uuid.Uuid
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.registry.ModelRegistry
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.FileZip
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.MessageMultiple01
import me.rerere.hugeicons.stroke.Notebook01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.View
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.datastore.ModelGroupSessionDefault
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.WorkspaceLeadingIcon
import me.rerere.rikkahub.ui.components.ui.WorkspaceStatusPill
import me.rerere.rikkahub.ui.components.ui.WorkspaceTextButton
import me.rerere.rikkahub.ui.components.ui.WorkspaceTone
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingModelPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val workspace = workspaceColors()
    var showGroupDefaults by remember { mutableStateOf(false) }
    val rowColors = ListItemDefaults.colors(
        containerColor = workspace.paper,
        headlineColor = workspace.ink,
        supportingColor = workspace.muted,
        leadingIconColor = workspace.muted,
        trailingIconColor = workspace.muted,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.setting_model_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = workspace.paper,
                    scrolledContainerColor = workspace.paper,
                    titleContentColor = workspace.ink,
                    navigationIconContentColor = workspace.muted,
                ),
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = workspace.canvas,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item("chat") {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_model_page_chat_section)) },
                    colors = rowColors,
                ) {
                    item(
                        leadingContent = {
                            SettingModelLeadingIcon(HugeIcons.Message01, tone = WorkspaceTone.Accent)
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_model_page_chat_model))
                        },
                        supportingContent = {
                            ModelPickerRow(
                                description = stringResource(R.string.setting_model_page_chat_model_desc),
                                modelId = settings.chatModelId,
                                providers = settings.providers,
                                onSelect = {
                                    vm.updateSettings(settings.copy(chatModelId = it.id))
                                },
                            )
                        },
                    )
                }
            }

            item("assistantTasks") {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_model_page_auxiliary_section)) },
                    colors = rowColors,
                ) {
                    item {
                        DefaultTitleModelSetting(settings = settings, vm = vm)
                    }
                    item {
                        DefaultSuggestionModelSetting(settings = settings, vm = vm)
                    }
                    item {
                        DefaultTranslationModelSetting(settings = settings, vm = vm)
                    }
                    item {
                        DefaultOcrModelSetting(settings = settings, vm = vm)
                    }
                    item {
                        DefaultCompressModelSetting(settings = settings, vm = vm)
                    }
                }
            }

            item("advanced") {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_model_page_advanced_section)) },
                    colors = rowColors,
                ) {
                    item(
                        onClick = { showGroupDefaults = true },
                        leadingContent = {
                            SettingModelLeadingIcon(HugeIcons.Settings03)
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_model_page_group_session_defaults))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_model_page_group_session_defaults_desc))
                        },
                        trailingContent = {
                            WorkspaceStatusPill(
                                text = stringResource(R.string.setting_model_page_configure),
                                tone = WorkspaceTone.Accent,
                            )
                        },
                    )
                }
            }
        }
    }

    if (showGroupDefaults) {
        ModelGroupSessionDefaultsSheet(
            settings = settings,
            vm = vm,
            onDismissRequest = { showGroupDefaults = false },
        )
    }
}

@Composable
private fun DefaultTitleModelSetting(
    settings: Settings,
    vm: SettingVM,
) {
    var showModal by remember { mutableStateOf(false) }
    ModelTaskSetting(
        title = stringResource(R.string.setting_model_page_title_model),
        description = stringResource(R.string.setting_model_page_title_model_desc),
        icon = HugeIcons.Notebook01,
        modelId = settings.titleModelId,
        providers = settings.providers,
        allowClear = true,
        onSelect = {
            vm.updateSettings(settings.copy(titleModelId = it.id))
        },
        onOpenParams = { showModal = true },
    )
    if (showModal) {
        ModelPromptSheet(
            title = stringResource(R.string.setting_model_page_title_model),
            variableHint = stringResource(R.string.setting_model_page_suggestion_prompt_vars),
            prompt = settings.titlePrompt,
            onPromptChange = {
                vm.updateSettings(settings.copy(titlePrompt = it))
            },
            onReset = {
                vm.updateSettings(settings.copy(titlePrompt = DEFAULT_TITLE_PROMPT))
            },
            onDismissRequest = { showModal = false },
        )
    }
}

@Composable
private fun DefaultSuggestionModelSetting(
    settings: Settings,
    vm: SettingVM,
) {
    var showModal by remember { mutableStateOf(false) }
    ModelTaskSetting(
        title = stringResource(R.string.setting_model_page_suggestion_model),
        description = stringResource(R.string.setting_model_page_suggestion_model_desc),
        icon = HugeIcons.MessageMultiple01,
        modelId = settings.suggestionModelId,
        providers = settings.providers,
        allowClear = true,
        onSelect = {
            vm.updateSettings(settings.copy(suggestionModelId = it.id))
        },
        onOpenParams = { showModal = true },
    )
    if (showModal) {
        ModelPromptSheet(
            title = stringResource(R.string.setting_model_page_suggestion_model),
            variableHint = stringResource(R.string.setting_model_page_suggestion_prompt_vars),
            prompt = settings.suggestionPrompt,
            onPromptChange = {
                vm.updateSettings(settings.copy(suggestionPrompt = it))
            },
            onReset = {
                vm.updateSettings(settings.copy(suggestionPrompt = DEFAULT_SUGGESTION_PROMPT))
            },
            onDismissRequest = { showModal = false },
        )
    }
}

@Composable
private fun DefaultTranslationModelSetting(
    settings: Settings,
    vm: SettingVM,
) {
    var showModal by remember { mutableStateOf(false) }
    ModelTaskSetting(
        title = stringResource(R.string.setting_model_page_translate_model),
        description = stringResource(R.string.setting_model_page_translate_model_desc),
        icon = HugeIcons.Earth,
        modelId = settings.translateModeId,
        providers = settings.providers,
        onSelect = {
            vm.updateSettings(settings.copy(translateModeId = it.id))
        },
        onOpenParams = { showModal = true },
    )
    if (showModal) {
        ModelPromptSheet(
            title = stringResource(R.string.setting_model_page_translate_model),
            variableHint = stringResource(R.string.setting_model_page_translate_prompt_vars),
            prompt = settings.translatePrompt,
            reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
            onReasoningChange = {
                vm.updateSettings(settings.copy(translateThinkingBudget = it.budgetTokens))
            },
            onPromptChange = {
                vm.updateSettings(settings.copy(translatePrompt = it))
            },
            onReset = {
                vm.updateSettings(settings.copy(translatePrompt = DEFAULT_TRANSLATION_PROMPT))
            },
            onDismissRequest = { showModal = false },
        )
    }
}

@Composable
private fun DefaultOcrModelSetting(
    settings: Settings,
    vm: SettingVM,
) {
    var showModal by remember { mutableStateOf(false) }
    ModelTaskSetting(
        title = stringResource(R.string.setting_model_page_ocr_model),
        description = stringResource(R.string.setting_model_page_ocr_model_desc),
        icon = HugeIcons.View,
        modelId = settings.ocrModelId,
        providers = settings.providers,
        onSelect = {
            vm.updateSettings(settings.copy(ocrModelId = it.id))
        },
        onOpenParams = { showModal = true },
    )
    if (showModal) {
        ModelPromptSheet(
            title = stringResource(R.string.setting_model_page_ocr_model),
            variableHint = stringResource(R.string.setting_model_page_ocr_prompt_vars),
            prompt = settings.ocrPrompt,
            onPromptChange = {
                vm.updateSettings(settings.copy(ocrPrompt = it))
            },
            onReset = {
                vm.updateSettings(settings.copy(ocrPrompt = DEFAULT_OCR_PROMPT))
            },
            onDismissRequest = { showModal = false },
        )
    }
}

@Composable
private fun DefaultCompressModelSetting(
    settings: Settings,
    vm: SettingVM,
) {
    var showModal by remember { mutableStateOf(false) }
    ModelTaskSetting(
        title = stringResource(R.string.setting_model_page_compress_model),
        description = stringResource(R.string.setting_model_page_compress_model_desc),
        icon = HugeIcons.FileZip,
        modelId = settings.compressModelId,
        providers = settings.providers,
        onSelect = {
            vm.updateSettings(settings.copy(compressModelId = it.id))
        },
        onOpenParams = { showModal = true },
    )
    if (showModal) {
        ModelPromptSheet(
            title = stringResource(R.string.setting_model_page_compress_model),
            variableHint = stringResource(R.string.setting_model_page_compress_prompt_vars),
            prompt = settings.compressPrompt,
            onPromptChange = {
                vm.updateSettings(settings.copy(compressPrompt = it))
            },
            onReset = {
                vm.updateSettings(settings.copy(compressPrompt = DEFAULT_COMPRESS_PROMPT))
            },
            onDismissRequest = { showModal = false },
        )
    }
}

@Composable
private fun ModelTaskSetting(
    title: String,
    description: String,
    icon: ImageVector,
    modelId: Uuid?,
    providers: List<me.rerere.ai.provider.ProviderSetting>,
    allowClear: Boolean = false,
    onSelect: (Model) -> Unit,
    onOpenParams: () -> Unit,
) {
    SettingModelRow(
        title = title,
        description = description,
        icon = icon,
    ) {
        ModelPickerRow(
            description = null,
            modelId = modelId,
            providers = providers,
            allowClear = allowClear,
            onSelect = onSelect,
            trailingContent = {
                WorkspaceTextButton(
                    text = stringResource(R.string.setting_model_page_parameters),
                    onClick = onOpenParams,
                    tone = WorkspaceTone.Accent,
                )
            },
        )
    }
}

@Composable
private fun SettingModelRow(
    title: String,
    description: String,
    icon: ImageVector,
    tone: WorkspaceTone = WorkspaceTone.Neutral,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingModelLeadingIcon(icon = icon, tone = tone)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = workspaceColors().muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        content()
    }
}

@Composable
private fun ModelPickerRow(
    description: String?,
    modelId: Uuid?,
    providers: List<me.rerere.ai.provider.ProviderSetting>,
    allowClear: Boolean = false,
    onSelect: (Model) -> Unit,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = workspaceColors().muted,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                ModelSelector(
                    modelId = modelId,
                    type = ModelType.CHAT,
                    onSelect = onSelect,
                    providers = providers,
                    allowClear = allowClear,
                    modifier = Modifier.width(210.dp),
                )
            }
            trailingContent?.invoke()
        }
    }
}

@Composable
private fun ModelPromptSheet(
    title: String,
    variableHint: String,
    prompt: String,
    onPromptChange: (String) -> Unit,
    onReset: () -> Unit,
    onDismissRequest: () -> Unit,
    reasoningLevel: ReasoningLevel? = null,
    onReasoningChange: ((ReasoningLevel) -> Unit)? = null,
) {
    val workspace = workspaceColors()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = workspace.paper,
        contentColor = workspace.ink,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = variableHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = workspace.muted,
                )
            }

            if (reasoningLevel != null && onReasoningChange != null) {
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    color = workspace.note,
                    border = BorderStroke(1.dp, workspace.hairline),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.assistant_page_thinking_budget),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        ReasoningButton(
                            reasoningLevel = reasoningLevel,
                            onUpdateReasoningLevel = onReasoningChange,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
                maxLines = 10,
                label = {
                    Text(stringResource(R.string.setting_model_page_prompt))
                },
            )
            TextButton(
                onClick = onReset,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.setting_model_page_reset_to_default))
            }
        }
    }
}

@Composable
private fun ModelGroupSessionDefaultsSheet(
    settings: Settings,
    vm: SettingVM,
    onDismissRequest: () -> Unit,
) {
    val workspace = workspaceColors()

    fun updateDefault(groupId: String, block: (ModelGroupSessionDefault) -> ModelGroupSessionDefault) {
        val existing = settings.modelGroupSessionDefaults.firstOrNull { it.groupId == groupId }
            ?: ModelGroupSessionDefault(groupId = groupId)
        val updated = block(existing)
        vm.updateSettings(
            settings.copy(
                modelGroupSessionDefaults = settings.modelGroupSessionDefaults
                    .filterNot { it.groupId == groupId } + updated
            )
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = workspace.paper,
        contentColor = workspace.ink,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.setting_model_page_group_session_defaults),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.setting_model_page_group_session_defaults_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = workspace.muted,
                )
            }

            ModelRegistry.SESSION_DEFAULT_GROUPS.forEach { group ->
                val current = settings.modelGroupSessionDefaults.firstOrNull { it.groupId == group.id }
                    ?: ModelGroupSessionDefault(groupId = group.id)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    color = workspace.note,
                    border = BorderStroke(1.dp, workspace.hairline),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = group.label,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(R.string.setting_model_page_group_session_defaults_group_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = workspace.muted,
                            )
                        }
                        ReasoningButton(
                            reasoningLevel = current.reasoningLevel,
                            onUpdateReasoningLevel = { level ->
                                updateDefault(group.id) {
                                    it.copy(reasoningLevel = level)
                                }
                            },
                        )
                        OutlinedTextField(
                            value = current.contextMessageSize.takeIf { it > 0 }?.toString().orEmpty(),
                            onValueChange = { text ->
                                updateDefault(group.id) {
                                    it.copy(
                                        contextMessageSize = text.toIntOrNull()
                                            ?.takeIf { size -> size > 0 }
                                            ?: 0
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Text(stringResource(R.string.assistant_page_context_message_size))
                            },
                            placeholder = {
                                Text(stringResource(R.string.assistant_page_context_message_unlimited))
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingModelLeadingIcon(
    icon: ImageVector,
    tone: WorkspaceTone = WorkspaceTone.Neutral,
) {
    WorkspaceLeadingIcon(
        icon = icon,
        size = 30.dp,
        iconSize = 15.dp,
        tone = tone,
    )
}
