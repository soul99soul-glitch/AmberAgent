package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelRegistry
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.FileZip
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.MessageMultiple01
import me.rerere.hugeicons.stroke.Notebook01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.View
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.vision.VisionModelHealthChecker
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.resolveVisionRecognitionPrompt
import me.rerere.rikkahub.data.datastore.DEFAULT_AUTO_MODEL_ID
import me.rerere.rikkahub.data.datastore.ModelGroupSessionDefault
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.resolveTaskChatModel
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ai.hasUsableAuth
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.WorkspaceDivider
import me.rerere.rikkahub.ui.components.ui.WorkspaceLeadingIcon
import me.rerere.rikkahub.ui.components.ui.WorkspaceStatusPill
import me.rerere.rikkahub.ui.components.ui.WorkspaceTextButton
import me.rerere.rikkahub.ui.components.ui.WorkspaceTone
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingModelPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val workspace = workspaceColors()
    var showGroupDefaults by remember { mutableStateOf(false) }

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
                ModelSection(
                    title = { Text(stringResource(R.string.setting_model_page_chat_section)) },
                ) {
                    DefaultChatModelSetting(
                        settings = settings,
                        vm = vm,
                    )
                }
            }

            item("assistantTasks") {
                ModelSection(
                    title = { Text(stringResource(R.string.setting_model_page_auxiliary_section)) },
                ) {
                    DefaultTitleModelSetting(settings = settings, vm = vm)
                    ModelSectionDivider()
                    DefaultSuggestionModelSetting(settings = settings, vm = vm)
                    ModelSectionDivider()
                    DefaultTranslationModelSetting(settings = settings, vm = vm)
                    ModelSectionDivider()
                    DefaultImageGenerationModelSetting(settings = settings, vm = vm)
                    ModelSectionDivider()
                    DefaultOcrModelSetting(settings = settings, vm = vm)
                    ModelSectionDivider()
                    DefaultCompressModelSetting(settings = settings, vm = vm)
                    ModelSectionDivider()
                    DefaultMemoryWorkerModelSetting(settings = settings, vm = vm)
                    ModelSectionDivider()
                    DefaultDaydreamModelSetting(settings = settings, vm = vm)
                }
            }

            item("advanced") {
                ModelSection(
                    title = { Text(stringResource(R.string.setting_model_page_advanced_section)) },
                ) {
                    GroupDefaultsEntry(
                        onClick = { showGroupDefaults = true },
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
private fun DefaultChatModelSetting(
    settings: Settings,
    vm: SettingVM,
) {
    SettingModelRow(
        title = stringResource(R.string.setting_model_page_chat_model),
        description = stringResource(R.string.setting_model_page_chat_model_desc),
        icon = HugeIcons.Message01,
        tone = WorkspaceTone.Accent,
    ) {
        ModelPickerRow(
            description = null,
            modelId = settings.chatModelId,
            providers = settings.providers,
            onSelect = {
                vm.updateSettings(settings.copy(chatModelId = it.id))
            },
        )
    }
}

@Composable
private fun DefaultImageGenerationModelSetting(
    settings: Settings,
    vm: SettingVM,
) {
    SettingModelRow(
        title = stringResource(R.string.setting_model_page_image_gen_model),
        description = stringResource(R.string.setting_model_page_image_gen_model_desc),
        icon = HugeIcons.MagicWand01,
    ) {
        ModelPickerRow(
            description = null,
            modelId = settings.imageGenerationModelId,
            // Hide auto-seeded image models from providers the user hasn't
            // actually configured yet — without this filter, fresh installs
            // see "Nano Banana 2" / "gpt-image-2" as pickable options even
            // when the corresponding Gemini / OpenAI provider has no API
            // key wired up, which would 401 on every generation.
            providers = settings.providers.filter { it.hasUsableAuth() },
            // Filter by IMAGE so the picker only shows gpt-image-2 / Nano
            // Banana / Codex Image — not chat models. Keep allowClear so the
            // user can opt out globally (assistants still resolve via their
            // per-assistant override, fallback to no tool if both are null).
            allowClear = true,
            modelType = ModelType.IMAGE,
            emptyLabel = stringResource(R.string.setting_model_page_image_gen_model_empty),
            clearContentDescription = stringResource(R.string.setting_model_page_image_gen_model_clear),
            onClear = {
                vm.updateSettings(settings.copy(imageGenerationModelId = DEFAULT_AUTO_MODEL_ID))
            },
            onSelect = {
                vm.updateSettings(settings.copy(imageGenerationModelId = it.id))
            },
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
        followsChatModel = settings.findModelById(settings.titleModelId) == null,
        fallbackModel = settings.resolveTaskChatModel(settings.titleModelId),
        onSelect = {
            vm.updateSettings(settings.copy(titleModelId = it.id))
        },
        onClear = {
            vm.updateSettings(settings.copy(titleModelId = DEFAULT_AUTO_MODEL_ID))
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
        followsChatModel = settings.findModelById(settings.suggestionModelId) == null,
        fallbackModel = settings.resolveTaskChatModel(settings.suggestionModelId),
        onSelect = {
            vm.updateSettings(settings.copy(suggestionModelId = it.id))
        },
        onClear = {
            vm.updateSettings(settings.copy(suggestionModelId = DEFAULT_AUTO_MODEL_ID))
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
    val providerManager = koinInject<ProviderManager>()
    val health by produceState(
        initialValue = VisionModelHealthChecker.checking(),
        key1 = settings.ocrModelId,
        key2 = settings.providers,
    ) {
        value = withContext(Dispatchers.IO) {
            VisionModelHealthChecker.probe(settings, providerManager)
        }
    }
    ModelTaskSetting(
        title = stringResource(R.string.setting_model_page_ocr_model),
        description = "${stringResource(R.string.setting_model_page_ocr_model_desc)} · ${health.label}",
        icon = HugeIcons.View,
        modelId = settings.ocrModelId,
        providers = settings.providers,
        preferredInputModality = Modality.IMAGE,
        onSelect = {
            vm.updateSettings(settings.copy(ocrModelId = it.id))
        },
        onOpenParams = { showModal = true },
    )
    if (showModal) {
        ModelPromptSheet(
            title = stringResource(R.string.setting_model_page_ocr_model),
            variableHint = stringResource(R.string.setting_model_page_ocr_prompt_vars),
            prompt = resolveVisionRecognitionPrompt(settings.ocrPrompt),
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
        allowClear = true,
        followsChatModel = settings.findModelById(settings.compressModelId) == null,
        fallbackModel = settings.resolveTaskChatModel(settings.compressModelId),
        onSelect = {
            vm.updateSettings(settings.copy(compressModelId = it.id))
        },
        onClear = {
            vm.updateSettings(settings.copy(compressModelId = DEFAULT_AUTO_MODEL_ID))
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
private fun DefaultMemoryWorkerModelSetting(
    settings: Settings,
    vm: SettingVM,
) {
    val worker = settings.agentRuntime.memoryWorker
    val followsCompress = worker.followCompressModel && settings.findModelById(worker.modelId) == null
    val fallbackModel = settings.resolveTaskChatModel(settings.compressModelId)
        ?: settings.resolveTaskChatModel(settings.chatModelId)
    SettingModelRow(
        title = "记忆提取模型",
        description = "用于对话结束后提取候选记忆，轻量稳定即可",
        icon = HugeIcons.Settings03,
    ) {
        ModelPickerRow(
            description = if (followsCompress) {
                "当前跟随压缩模型：${fallbackModel?.displayName ?: "不可用"}"
            } else {
                null
            },
            modelId = worker.modelId,
            providers = settings.providers,
            allowClear = true,
            emptyLabel = if (followsCompress) "跟随压缩模型" else null,
            clearContentDescription = "恢复跟随压缩模型",
            onClear = {
                vm.updateSettings(
                    settings.copy(
                        agentRuntime = settings.agentRuntime.copy(
                            memoryWorker = worker.copy(
                                modelId = DEFAULT_AUTO_MODEL_ID,
                                followCompressModel = true,
                            )
                        )
                    )
                )
            },
            onSelect = {
                vm.updateSettings(
                    settings.copy(
                        agentRuntime = settings.agentRuntime.copy(
                            memoryWorker = worker.copy(
                                modelId = it.id,
                                followCompressModel = false,
                            )
                        )
                    )
                )
            },
        )
    }
}

@Composable
private fun DefaultDaydreamModelSetting(
    settings: Settings,
    vm: SettingVM,
) {
    val worker = settings.agentRuntime.memoryWorker
    val followsCompress = worker.daydreamFollowCompressModel && settings.findModelById(worker.daydreamModelId) == null
    val fallbackModel = settings.resolveTaskChatModel(settings.compressModelId)
        ?: settings.resolveTaskChatModel(settings.chatModelId)
    SettingModelRow(
        title = "Daydream 模型",
        description = "用于后台整理、合并和审查记忆，建议选择推理更强的模型",
        icon = HugeIcons.Brain02,
    ) {
        ModelPickerRow(
            description = if (followsCompress) {
                "当前跟随压缩模型：${fallbackModel?.displayName ?: "不可用"}"
            } else {
                null
            },
            modelId = worker.daydreamModelId,
            providers = settings.providers,
            allowClear = true,
            emptyLabel = if (followsCompress) "跟随压缩模型" else null,
            clearContentDescription = "恢复跟随压缩模型",
            onClear = {
                vm.updateSettings(
                    settings.copy(
                        agentRuntime = settings.agentRuntime.copy(
                            memoryWorker = worker.copy(
                                daydreamModelId = DEFAULT_AUTO_MODEL_ID,
                                daydreamFollowCompressModel = true,
                            )
                        )
                    )
                )
            },
            onSelect = {
                vm.updateSettings(
                    settings.copy(
                        agentRuntime = settings.agentRuntime.copy(
                            memoryWorker = worker.copy(
                                daydreamModelId = it.id,
                                daydreamFollowCompressModel = false,
                            )
                        )
                    )
                )
            },
        )
    }
}

@Composable
private fun ModelTaskSetting(
    title: String,
    description: String,
    icon: ImageVector,
    modelId: Uuid?,
    providers: List<ProviderSetting>,
    allowClear: Boolean = false,
    followsChatModel: Boolean = false,
    fallbackModel: Model? = null,
    preferredInputModality: Modality? = null,
    onSelect: (Model) -> Unit,
    onClear: (() -> Unit)? = null,
    onOpenParams: () -> Unit,
) {
    val fallbackModelName = fallbackModel?.displayName
        ?: stringResource(R.string.setting_model_page_follow_chat_model_unavailable)
    SettingModelRow(
        title = title,
        description = description,
        icon = icon,
    ) {
        ModelPickerRow(
            description = if (followsChatModel) {
                stringResource(R.string.setting_model_page_follow_chat_model_desc, fallbackModelName)
            } else {
                null
            },
            modelId = modelId,
            providers = providers,
            allowClear = allowClear,
            emptyLabel = if (followsChatModel) {
                stringResource(R.string.setting_model_page_follow_chat_model)
            } else {
                null
            },
            clearContentDescription = stringResource(R.string.setting_model_page_restore_follow_chat_model),
            preferredInputModality = preferredInputModality,
            onClear = onClear,
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
    providers: List<ProviderSetting>,
    allowClear: Boolean = false,
    emptyLabel: String? = null,
    clearContentDescription: String? = null,
    preferredInputModality: Modality? = null,
    // Default keeps every existing call site picking CHAT models. Image-gen
    // global default (the only IMAGE-type row on this page so far) overrides
    // to filter the dropdown so only image-output models appear.
    modelType: ModelType = ModelType.CHAT,
    onSelect: (Model) -> Unit,
    onClear: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (description != null) {
            Text(
                text = description,
                modifier = Modifier.padding(start = 42.dp),
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
                    type = modelType,
                    onSelect = onSelect,
                    providers = providers,
                    allowClear = allowClear,
                    emptyLabel = emptyLabel,
                    clearContentDescription = clearContentDescription,
                    preferredInputModality = preferredInputModality,
                    onClear = onClear,
                    modifier = Modifier.fillMaxWidth(),
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
                    shape = RoundedCornerShape(10.dp),
                    color = workspace.note,
                    border = BorderStroke(1.dp, workspace.hairline),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.assistant_page_thinking_budget),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        NotionReasoningSelector(
                            reasoningLevel = reasoningLevel,
                            onReasoningLevelChange = onReasoningChange,
                        )
                    }
                }
            }

            TextField(
                value = prompt,
                onValueChange = onPromptChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp),
                minLines = 5,
                maxLines = 10,
                shape = RoundedCornerShape(10.dp),
                label = {
                    Text(stringResource(R.string.setting_model_page_prompt))
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = workspace.note,
                    unfocusedContainerColor = workspace.note,
                    focusedIndicatorColor = workspace.hairline,
                    unfocusedIndicatorColor = workspace.hairline,
                    focusedTextColor = workspace.ink,
                    unfocusedTextColor = workspace.ink,
                    focusedLabelColor = workspace.blue,
                    unfocusedLabelColor = workspace.muted,
                ),
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
                    shape = RoundedCornerShape(10.dp),
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
                        NotionReasoningSelector(
                            reasoningLevel = current.reasoningLevel,
                            onReasoningLevelChange = { level ->
                                updateDefault(group.id) {
                                    it.copy(reasoningLevel = level)
                                }
                            },
                        )
                        TextField(
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
                            shape = RoundedCornerShape(8.dp),
                            label = {
                                Text(stringResource(R.string.assistant_page_context_message_size))
                            },
                            placeholder = {
                                Text(stringResource(R.string.assistant_page_context_message_unlimited))
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = workspace.paper,
                                unfocusedContainerColor = workspace.paper,
                                focusedIndicatorColor = workspace.hairline,
                                unfocusedIndicatorColor = workspace.hairline,
                                focusedTextColor = workspace.ink,
                                unfocusedTextColor = workspace.ink,
                                focusedLabelColor = workspace.blue,
                                unfocusedLabelColor = workspace.muted,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelSection(
    title: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val workspace = workspaceColors()
    Column {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides workspace.muted,
        ) {
            androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.titleSmall) {
                Box(modifier = Modifier.padding(start = 2.dp, top = 8.dp, bottom = 8.dp)) {
                    title()
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = workspace.paper,
            border = BorderStroke(1.dp, workspace.hairline),
        ) {
            Column(
                modifier = Modifier.padding(vertical = 4.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun ModelSectionDivider() {
    WorkspaceDivider(modifier = Modifier.padding(start = 56.dp))
}

@Composable
private fun NotionReasoningSelector(
    reasoningLevel: ReasoningLevel,
    onReasoningLevelChange: (ReasoningLevel) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ReasoningLevel.entries.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { level ->
                    NotionReasoningChip(
                        level = level,
                        selected = level == reasoningLevel,
                        onClick = { onReasoningLevelChange(level) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun NotionReasoningChip(
    level: ReasoningLevel,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = workspaceColors()
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(7.dp),
        color = if (selected) workspace.blueContainer else workspace.paper,
        contentColor = if (selected) workspace.blue else workspace.ink,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) workspace.blue.copy(alpha = 0.22f) else workspace.hairline,
        ),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = level.settingLabel(),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ReasoningLevel.settingLabel(): String = when (this) {
    ReasoningLevel.OFF -> stringResource(R.string.reasoning_off)
    ReasoningLevel.AUTO -> stringResource(R.string.reasoning_auto)
    ReasoningLevel.LOW -> stringResource(R.string.reasoning_light)
    ReasoningLevel.MEDIUM -> stringResource(R.string.reasoning_medium)
    ReasoningLevel.HIGH -> stringResource(R.string.reasoning_heavy)
    ReasoningLevel.XHIGH -> stringResource(R.string.reasoning_xhigh)
    ReasoningLevel.MAX -> stringResource(R.string.reasoning_max)
}

@Composable
private fun GroupDefaultsEntry(
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = workspaceColors().paper,
        contentColor = workspaceColors().ink,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingModelLeadingIcon(HugeIcons.Settings03)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_model_page_group_session_defaults),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.setting_model_page_group_session_defaults_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = workspaceColors().muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            WorkspaceStatusPill(
                text = stringResource(R.string.setting_model_page_configure),
                tone = WorkspaceTone.Accent,
            )
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
