package app.amber.feature.ui.pages.setting

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.Modality
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.registry.ModelRegistry
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.FileArchive
import com.composables.icons.lucide.WandSparkles
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.MessagesSquare
import com.composables.icons.lucide.NotebookTabs
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.X
import app.amber.agent.R
import app.amber.core.ai.vision.VisionModelHealthChecker
import app.amber.core.ai.vision.VisionModelHealthStrings
import app.amber.core.ai.prompts.DEFAULT_COMPRESS_PROMPT
import app.amber.core.ai.prompts.DEFAULT_OCR_PROMPT
import app.amber.core.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import app.amber.core.ai.prompts.DEFAULT_TITLE_PROMPT
import app.amber.core.ai.prompts.resolveVisionRecognitionPrompt
import app.amber.feature.prompts.AgentPromptConfigRepository
import app.amber.feature.prompts.DEFAULT_IMAGE_NEGATIVE_PROMPT_INJECTION
import app.amber.feature.prompts.DEFAULT_IMAGE_PROMPT_INJECTION
import app.amber.feature.prompts.ImagePromptInjectionConfig
import app.amber.core.settings.DEFAULT_AUTO_MODEL_ID
import app.amber.core.settings.DEFAULT_AMBER_SYSTEM_PROMPT
import app.amber.core.settings.ModelGroupSessionDefault
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import app.amber.core.settings.resolveTaskChatModel
import app.amber.feature.ui.components.ai.ModelSelector
import app.amber.ai.provider.hasUsableAuth
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.Hairline
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.core.utils.plus
import app.amber.feature.ui.pages.setting.components.ProviderFieldLabel
import app.amber.feature.ui.pages.setting.components.ProviderCommandButton
import app.amber.feature.ui.pages.setting.components.ProviderGhostButton
import app.amber.feature.ui.pages.setting.components.ProviderSheetGrabber
import app.amber.feature.ui.pages.setting.components.ProviderTextField
import app.amber.feature.ui.pages.setting.components.ProviderToggle
import app.amber.feature.ui.pages.setting.components.providerSlugLabel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingModelPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showGroupDefaults by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier
            .amberCanvas()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.setting_model_page_title),
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = Color.Transparent,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("chat") {
                ModelSection(
                    title = { SectionLabel(stringResource(R.string.setting_model_page_chat_section)) },
                ) {
                    DefaultChatModelSetting(
                        settings = settings,
                        vm = vm,
                    )
                }
            }

            item("assistantTasks") {
                ModelSection(
                    title = { SectionLabel(stringResource(R.string.setting_model_page_auxiliary_section)) },
                ) {
                    DefaultTitleModelSetting(settings = settings, vm = vm)
                    ModelSectionDivider()
                    DefaultSuggestionModelSetting(settings = settings, vm = vm)
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
                    title = { SectionLabel(stringResource(R.string.setting_model_page_advanced_section)) },
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
    var showParams by remember { mutableStateOf(false) }
    var draftPrompt by remember { mutableStateOf(settings.systemPrompt) }
    var draftReasoningLevel by remember { mutableStateOf(settings.reasoningLevel) }
    SettingModelRow(
        title = stringResource(R.string.setting_model_page_chat_model),
        description = stringResource(R.string.setting_model_page_chat_model_desc),
        icon = Lucide.MessageCircle,
        trailing = {
            ModelParameterButton(
                text = stringResource(R.string.setting_model_page_parameters),
                onClick = {
                    draftPrompt = settings.systemPrompt
                    draftReasoningLevel = settings.reasoningLevel
                    showParams = true
                },
            )
        },
    ) {
        ModelPickerRow(
            description = null,
            modelId = settings.chatModelId,
            providers = settings.providers,
            onSelect = { vm.updateSettings(settings.copy(chatModelId = it.id)) },
        )
    }
    if (showParams) {
        ModelPromptSheet(
            title = "${stringResource(R.string.setting_model_page_parameters)} · ${stringResource(R.string.setting_model_page_chat_model)}",
            variableHint = settings.findModelById(settings.chatModelId)?.modelId
                ?: stringResource(R.string.model_list_select_model),
            prompt = draftPrompt,
            onPromptChange = { draftPrompt = it },
            onReset = {
                draftPrompt = DEFAULT_AMBER_SYSTEM_PROMPT
                draftReasoningLevel = ReasoningLevel.AUTO
            },
            onDismissRequest = { showParams = false },
            onSave = {
                val draft = ChatModelParamsDraft(
                    systemPrompt = draftPrompt,
                    reasoningLevel = draftReasoningLevel,
                )
                vm.updateSettings { current -> current.applyChatModelParams(draft) }
            },
            reasoningLevel = draftReasoningLevel,
            onReasoningChange = { draftReasoningLevel = it },
        )
    }
}

@Composable
private fun DefaultImageGenerationModelSetting(
    settings: Settings,
    vm: SettingVM,
) {
    var showPromptSheet by remember { mutableStateOf(false) }
    // Managed-OAuth providers need their durable token state to count as
    // usable; the stores are synchronous Keystore reads.
    val grokAuthStore = koinInject<app.amber.ai.provider.providers.grok.GrokAuthStore>()
    val antigravityAuthStore = koinInject<app.amber.ai.provider.providers.google.AntigravityAuthStore>()
    SettingModelRow(
        title = stringResource(R.string.setting_model_page_image_gen_model),
        description = stringResource(R.string.setting_model_page_image_gen_model_desc),
        icon = Lucide.WandSparkles,
        trailing = {
            ModelParameterButton(
                text = stringResource(R.string.setting_model_page_prompt),
                onClick = { showPromptSheet = true },
            )
        },
    ) {
        ModelPickerRow(
            description = null,
            modelId = settings.imageGenerationModelId,
            // Hide auto-seeded image models from providers the user hasn't
            // actually configured yet — without this filter, fresh installs
            // see "Nano Banana 2" / "gpt-image-2" as pickable options even
            // when the corresponding Gemini / OpenAI provider has no API
            // key wired up, which would 401 on every generation.
            providers = settings.providers.filter {
                it.hasUsableAuth(oauthUsable = it.managedOAuthUsable(grokAuthStore, antigravityAuthStore))
            },
            // Filter by IMAGE so the picker only shows gpt-image-2 / Nano
            // Banana / Codex Image — not chat models. Keep allowClear so the
            // user can clear the explicit choice and return to authenticated auto-selection.
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
    if (showPromptSheet) {
        ImagePromptInjectionSheet(
            onDismissRequest = { showPromptSheet = false },
        )
    }
}

@Composable
private fun ImagePromptInjectionSheet(
    promptConfigRepository: AgentPromptConfigRepository = koinInject(),
    onDismissRequest: () -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val scope = rememberCoroutineScope()
    val loaded by produceState<ImagePromptInjectionConfig?>(initialValue = null) {
        value = promptConfigRepository.readImageConfig()
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = t.raised,
        contentColor = t.ink,
        dragHandle = { ProviderSheetGrabber() },
    ) {
        val config = loaded
        if (config == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.setting_model_page_image_prompt_loading),
                    style = type.secondary,
                    color = t.ink3,
                )
            }
            return@ModalBottomSheet
        }
        var enabled by remember(config) { mutableStateOf(config.enabled) }
        var prompt by remember(config) { mutableStateOf(config.defaultPrompt) }
        var negativePrompt by remember(config) { mutableStateOf(config.negativePrompt) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_model_page_image_prompt_title),
                    style = type.screenTitle,
                    color = t.ink,
                )
                Text(
                    text = stringResource(R.string.setting_model_page_image_prompt_desc),
                    style = type.secondary,
                    color = t.ink3,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.setting_model_page_image_prompt_enable),
                        style = type.body,
                        color = t.ink,
                    )
                    Text(
                        text = stringResource(R.string.setting_model_page_image_prompt_enable_desc),
                        style = type.secondary,
                        color = t.ink3,
                    )
                }
                ProviderToggle(checked = enabled, onCheckedChange = { enabled = it })
            }
            ProviderFieldLabel(stringResource(R.string.setting_model_page_image_prompt_default_label))
            ProviderTextField(
                value = prompt,
                onValueChange = { prompt = it.take(4_000) },
                singleLine = false,
                minHeight = 126.dp,
            )
            ProviderFieldLabel(stringResource(R.string.setting_model_page_image_prompt_negative_label))
            ProviderTextField(
                value = negativePrompt,
                onValueChange = { negativePrompt = it.take(4_000) },
                singleLine = false,
                minHeight = 126.dp,
            )
            ModelPromptActionBar(
                resetText = stringResource(R.string.setting_model_page_reset_to_default),
                onReset = {
                    enabled = true
                    prompt = DEFAULT_IMAGE_PROMPT_INJECTION
                    negativePrompt = DEFAULT_IMAGE_NEGATIVE_PROMPT_INJECTION
                },
                cancelText = stringResource(R.string.cancel),
                onCancel = onDismissRequest,
                confirmText = stringResource(R.string.common_save),
                onConfirm = {
                    scope.launch {
                        promptConfigRepository.writeImageConfig(
                            ImagePromptInjectionConfig(
                                enabled = enabled,
                                defaultPrompt = prompt,
                                negativePrompt = negativePrompt,
                            )
                        )
                        onDismissRequest()
                    }
                },
            )
        }
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
        icon = Lucide.NotebookTabs,
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
        icon = Lucide.MessagesSquare,
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
private fun DefaultOcrModelSetting(
    settings: Settings,
    vm: SettingVM,
) {
    var showModal by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val healthStrings = remember(context) { VisionModelHealthStrings.from(context) }
    val providerCatalog = koinInject<ProviderCatalog>()
    // Managed-OAuth providers need their durable token state to count as
    // usable; the stores are synchronous Keystore reads.
    val grokAuthStore = koinInject<app.amber.ai.provider.providers.grok.GrokAuthStore>()
    val antigravityAuthStore = koinInject<app.amber.ai.provider.providers.google.AntigravityAuthStore>()
    val health by produceState(
        initialValue = VisionModelHealthChecker.checking(healthStrings),
        key1 = context,
        key2 = settings.ocrModelId,
        key3 = settings.providers,
    ) {
        value = withContext(Dispatchers.IO) {
            VisionModelHealthChecker.probe(settings, providerCatalog, healthStrings)
        }
    }
    ModelTaskSetting(
        title = stringResource(R.string.setting_model_page_ocr_model),
        description = "${stringResource(R.string.setting_model_page_ocr_model_desc)} · ${health.label}",
        icon = Lucide.Eye,
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
        icon = Lucide.FileArchive,
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
    val fallbackModelName = fallbackModel?.displayName
        ?: stringResource(R.string.setting_model_page_follow_compress_model_unavailable)
    SettingModelRow(
        title = stringResource(R.string.setting_model_page_memory_worker_title),
        description = stringResource(R.string.setting_model_page_memory_worker_desc),
        icon = Lucide.Settings,
    ) {
        ModelPickerRow(
            description = if (followsCompress) {
                stringResource(R.string.setting_model_page_follow_compress_model_desc, fallbackModelName)
            } else {
                null
            },
            modelId = worker.modelId,
            providers = settings.providers,
            allowClear = true,
            emptyLabel = if (followsCompress) {
                stringResource(R.string.setting_model_page_follow_compress_model)
            } else {
                null
            },
            clearContentDescription = stringResource(R.string.setting_model_page_restore_follow_compress_model),
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
    val fallbackModelName = fallbackModel?.displayName
        ?: stringResource(R.string.setting_model_page_follow_compress_model_unavailable)
    SettingModelRow(
        title = stringResource(R.string.setting_model_page_daydream_model_title),
        description = stringResource(R.string.setting_model_page_daydream_model_desc),
        icon = Lucide.Brain,
    ) {
        ModelPickerRow(
            description = if (followsCompress) {
                stringResource(R.string.setting_model_page_follow_compress_model_desc, fallbackModelName)
            } else {
                null
            },
            modelId = worker.daydreamModelId,
            providers = settings.providers,
            allowClear = true,
            emptyLabel = if (followsCompress) {
                stringResource(R.string.setting_model_page_follow_compress_model)
            } else {
                null
            },
            clearContentDescription = stringResource(R.string.setting_model_page_restore_follow_compress_model),
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
        trailing = {
            ModelParameterButton(
                text = stringResource(R.string.setting_model_page_parameters),
                onClick = onOpenParams,
            )
        },
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
        )
    }
}

@Composable
private fun ModelParameterButton(
    text: String,
    onClick: () -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Box(
        modifier = Modifier
            .widthIn(min = 40.dp)
            .heightIn(min = 40.dp)
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = type.meta.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            color = t.accent,
            maxLines = 1,
        )
    }
}

@Composable
private fun SettingModelRow(
    title: String,
    description: String,
    icon: ImageVector,
    tone: WorkspaceTone = WorkspaceTone.Neutral,
    // Header-right slot for the per-task "参数" button (and anything similar).
    // Lives on the title/description row so that the model-picker row below
    // has a uniform structure across all tasks — without this, rows missing
    // a trailing button (翻译 / 生图) caused the model-picker chip to shift
    // its position relative to rows that did have one.
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = type.body,
            color = t.ink,
            modifier = Modifier.weight(0.82f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(modifier = Modifier.weight(1.18f)) {
            content()
        }
        trailing?.invoke()
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
) {
    // Compact single-line trigger from models-prompts.html. Descriptions remain
    // available to callers for fallback states but do not inflate the row.
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val selectedModel = remember(providers, modelId, modelType) {
        modelId?.let { id ->
            providers.asSequence()
                .flatMap { provider -> provider.models.asSequence() }
                .firstOrNull { model -> model.id == id && model.type == modelType }
        }
    }
    val selectedProvider = remember(providers, selectedModel) {
        selectedModel?.let { model ->
            providers.firstOrNull { provider -> provider.models.any { it.id == model.id } }
        }
    }
    val selectedLabel = selectedModel?.modelId ?: emptyLabel
        ?: stringResource(R.string.model_list_select_model)
    val followSelection = selectedModel == null && !emptyLabel.isNullOrBlank()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
    ) {
        ModelSelector(
            modelId = modelId,
            type = modelType,
            onSelect = onSelect,
            providers = providers,
            inline = true,
            allowClear = allowClear,
            emptyLabel = emptyLabel,
            clearContentDescription = clearContentDescription,
            preferredInputModality = preferredInputModality,
            onClear = onClear,
            customTrigger = { openPicker ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressable(onClick = openPicker)
                        .heightIn(min = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .clip(CircleShape)
                            .background(t.surface2)
                            .padding(start = 13.dp, end = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Text(
                            text = selectedLabel,
                            style = type.meta.copy(
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                fontStyle = if (followSelection) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                            ),
                            color = if (followSelection) t.ink3 else t.ink,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        selectedProvider?.let { provider ->
                            Box(
                                modifier = Modifier
                                    .size(3.dp)
                                    .clip(CircleShape)
                                    .background(t.ink4),
                            )
                            Text(
                                text = provider.providerSlugLabel(),
                                style = type.meta.copy(fontSize = 11.5.sp),
                                color = t.ink3,
                                maxLines = 1,
                            )
                        }
                        Icon(
                            imageVector = Lucide.ArrowDown,
                            contentDescription = null,
                            tint = t.ink3,
                            modifier = Modifier.size(14.dp),
                        )
                        if (allowClear && selectedModel != null) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .pressable(onClick = { onClear?.invoke() ?: onSelect(Model()) }),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Lucide.X,
                                    contentDescription = clearContentDescription
                                        ?: stringResource(R.string.clear),
                                    tint = t.ink4,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            },
        )
    }
}

/** Draft boundary for the chat-model parameter sheet. The UI edits this value locally and
 * writes both fields together only from the explicit Save action. */
internal data class ChatModelParamsDraft(
    val systemPrompt: String,
    val reasoningLevel: ReasoningLevel,
)

internal fun Settings.applyChatModelParams(draft: ChatModelParamsDraft): Settings = copy(
    systemPrompt = draft.systemPrompt,
    reasoningLevel = draft.reasoningLevel,
)

@Composable
private fun ModelPromptSheet(
    title: String,
    variableHint: String,
    prompt: String,
    onPromptChange: (String) -> Unit,
    onReset: () -> Unit,
    onDismissRequest: () -> Unit,
    onSave: (() -> Unit)? = null,
    reasoningLevel: ReasoningLevel? = null,
    onReasoningChange: ((ReasoningLevel) -> Unit)? = null,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = t.raised,
        contentColor = t.ink,
        dragHandle = { ProviderSheetGrabber() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = title,
                        style = type.screenTitle,
                        color = t.ink,
                    )
                    Text(
                        text = variableHint,
                        style = type.secondary,
                        color = t.ink3,
                    )
                }

                if (reasoningLevel != null && onReasoningChange != null) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = t.surface2,
                        border = BorderStroke(1.dp, t.line.copy(alpha = 0.42f)),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.model_settings_thinking_budget),
                                style = type.body,
                                color = t.ink,
                            )
                            NotionReasoningSelector(
                                reasoningLevel = reasoningLevel,
                                onReasoningLevelChange = onReasoningChange,
                            )
                        }
                    }
                }

                ProviderFieldLabel(stringResource(R.string.setting_model_page_prompt))
                ProviderTextField(
                    value = prompt,
                    onValueChange = onPromptChange,
                    singleLine = false,
                    minHeight = 150.dp,
                )
            }
            ProviderGhostButton(
                text = stringResource(R.string.setting_model_page_reset_to_default),
                onClick = onReset,
                accent = false,
                modifier = Modifier.align(Alignment.End),
            )
            ModelPromptActionBar(
                resetText = stringResource(R.string.setting_model_page_reset_to_default),
                onReset = onReset,
                cancelText = stringResource(R.string.cancel),
                onCancel = onDismissRequest,
                confirmText = if (onSave == null) {
                    stringResource(R.string.confirm)
                } else {
                    stringResource(R.string.common_save)
                },
                onConfirm = {
                    onSave?.invoke()
                    onDismissRequest()
                },
            )
        }
    }
}

@Composable
private fun ModelPromptActionBar(
    resetText: String,
    onReset: () -> Unit,
    cancelText: String,
    onCancel: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderGhostButton(
            text = resetText,
            onClick = onReset,
            accent = false,
        )
        Spacer(Modifier.weight(1f))
        ProviderGhostButton(
            text = cancelText,
            onClick = onCancel,
            accent = false,
        )
        ProviderCommandButton(
            text = confirmText,
            onClick = onConfirm,
            accent = true,
        )
    }
}

@Composable
private fun ModelGroupSessionDefaultsSheet(
    settings: Settings,
    vm: SettingVM,
    onDismissRequest: () -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    var contextDrafts by remember {
        mutableStateOf(
            ModelRegistry.SESSION_DEFAULT_GROUPS.associate { group ->
                val current = settings.modelGroupSessionDefaults.firstOrNull { it.groupId == group.id }
                group.id to (current?.contextMessageSize?.takeIf { it > 0 }?.toString().orEmpty())
            }
        )
    }

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
        containerColor = t.raised,
        contentColor = t.ink,
        dragHandle = { ProviderSheetGrabber() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_model_page_group_session_defaults),
                    style = type.screenTitle,
                    color = t.ink,
                )
                Text(
                    text = stringResource(R.string.setting_model_page_group_session_defaults_desc),
                    style = type.secondary,
                    color = t.ink3,
                )
            }

            ModelRegistry.SESSION_DEFAULT_GROUPS.forEach { group ->
                val current = settings.modelGroupSessionDefaults.firstOrNull { it.groupId == group.id }
                    ?: ModelGroupSessionDefault(groupId = group.id)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = t.surface,
                    border = BorderStroke(1.dp, t.line.copy(alpha = 0.42f)),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = group.label,
                                style = type.body.copy(fontWeight = FontWeight.SemiBold),
                                color = t.ink,
                            )
                            Text(
                                text = stringResource(R.string.setting_model_page_group_session_defaults_group_desc),
                                style = type.secondary,
                                color = t.ink3,
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
                            value = contextDrafts[group.id].orEmpty(),
                            onValueChange = { text ->
                                contextDrafts = contextDrafts + (group.id to text)
                                val size = text.toIntOrNull()
                                if (text.isEmpty() || size != null) {
                                    updateDefault(group.id) {
                                        // Zero remains the existing unlimited-context value.
                                        it.copy(contextMessageSize = size?.coerceAtLeast(0) ?: 0)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            // Graphite §3: the context-message count is a machine-fact → MONO.
                            textStyle = LocalAmberType.current.meta,
                            label = {
                                Text(stringResource(R.string.model_settings_context_message_size))
                            },
                            placeholder = {
                                Text(stringResource(R.string.model_settings_context_message_unlimited))
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = t.surface2,
                                unfocusedContainerColor = t.surface2,
                                focusedIndicatorColor = t.line2,
                                unfocusedIndicatorColor = t.line,
                                focusedTextColor = t.ink,
                                unfocusedTextColor = t.ink,
                                focusedLabelColor = t.accent,
                                unfocusedLabelColor = t.ink3,
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
    val t = LocalAmberTokens.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 2.dp, top = 2.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            title()
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(t.line),
            )
        }
        AmberCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.background(t.surface),
                content = content,
            )
        }
    }
}

@Composable
private fun ModelSectionDivider() {
    Hairline(modifier = Modifier.padding(start = 14.dp))
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
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = if (selected) t.accent.copy(alpha = 0.14f) else t.surface2,
        contentColor = if (selected) t.accent else t.ink2,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) t.accent.copy(alpha = 0.28f) else t.line.copy(alpha = 0.42f),
        ),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = level.settingLabel(),
                style = type.meta.copy(fontSize = 11.sp),
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
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        contentColor = t.ink,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingModelLeadingIcon(Lucide.Settings)
            Text(
                text = stringResource(R.string.setting_model_page_group_session_defaults),
                modifier = Modifier.weight(1f),
                style = type.body,
                color = t.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Lucide.ChevronRight,
                contentDescription = null,
                tint = t.ink3,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SettingModelLeadingIcon(
    icon: ImageVector,
    tone: WorkspaceTone = WorkspaceTone.Neutral,
) {
    val t = LocalAmberTokens.current
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(t.surface2),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (tone == WorkspaceTone.Accent) t.accent else t.ink2,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Phase 5 review fix: managed OAuth modes only count as usable when the
 * durable store really holds a refreshable token; API-key / other modes are
 * unaffected (null keeps the legacy pure-data judgement).
 */
private fun app.amber.ai.provider.ProviderSetting.managedOAuthUsable(
    grokAuthStore: app.amber.ai.provider.providers.grok.GrokAuthStore,
    antigravityAuthStore: app.amber.ai.provider.providers.google.AntigravityAuthStore,
): Boolean? = when (this) {
    is app.amber.ai.provider.ProviderSetting.OpenAI ->
        if (authMode == app.amber.ai.provider.OpenAIAuthMode.GROK_OAUTH) {
            app.amber.ai.provider.providers.grok.GrokAuthStatus
                .from(grokAuthStore.get(id), System.currentTimeMillis()).usable
        } else {
            null
        }
    is app.amber.ai.provider.ProviderSetting.Google ->
        when (authMode) {
            app.amber.ai.provider.GoogleAuthMode.ANTIGRAVITY_OAUTH ->
                app.amber.ai.provider.providers.google.AntigravityAuthStatus
                    .from(antigravityAuthStore.get(id), System.currentTimeMillis()).usable
            else -> null
        }
    else -> null
}
