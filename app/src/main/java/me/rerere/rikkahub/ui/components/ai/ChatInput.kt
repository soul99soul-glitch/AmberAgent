package me.rerere.rikkahub.ui.components.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.OpenAIAuthMode
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.openai.OpenAICodexAuthStore
import me.rerere.ai.provider.providers.openai.OpenAICodexOAuthClient
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.agent.SandboxActivityUiState
import me.rerere.rikkahub.data.agent.ToolActivityStatus
import me.rerere.rikkahub.data.ai.vision.ImageAttachmentValidator
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.usage.ProviderUsageClient
import me.rerere.rikkahub.service.PendingUserMessageMode
import me.rerere.rikkahub.ui.components.ui.KeepScreenOn
import me.rerere.rikkahub.ui.components.ui.WorkspaceIconButton
import me.rerere.rikkahub.ui.components.ui.WorkspaceTone
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.formatNumber
import org.koin.compose.koinInject
import okhttp3.OkHttpClient
import java.io.File
import kotlin.uuid.Uuid

enum class ExpandState {
    Collapsed, Files,
}

@Composable
fun ChatInput(
    state: ChatInputState,
    loading: Boolean,
    conversation: Conversation,
    contextCompacts: List<me.rerere.rikkahub.data.context.ConversationCompact> = emptyList(),
    pendingQueueCount: Int = 0,
    settings: Settings,
    hazeState: HazeState,
    timelineScrolling: Boolean = false,
    enableSearch: Boolean,
    onToggleSearch: (Boolean) -> Unit,
    sandboxActivity: SandboxActivityUiState? = null,
    suggestionFillPulseKey: Int = 0,
    onOpenSandbox: () -> Unit = {},
    onCancelSandbox: (() -> Unit)? = null,
    onPreviousSandbox: (() -> Unit)? = null,
    onNextSandbox: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onUpdateChatModel: (Model) -> Unit,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    onCancelClick: () -> Unit,
    onSendClick: (PendingUserMessageMode) -> Unit,
    onLongSendClick: (PendingUserMessageMode) -> Unit,
    onOpenQueue: () -> Unit = {},
    onCompactContext: () -> Unit = {},
) {
    val toaster = LocalToaster.current
    val providerManager = koinInject<ProviderManager>()
    val assistant = settings.getCurrentAssistant()
    val coroutineScope = rememberCoroutineScope()
    val workspace = workspaceColors()
    val hazeTintColor = workspace.paper
    // Plan B redesign: bumped from 8dp to 22dp so the composer reads as a
    // "floating card" and the bottom corners no longer collide visually with
    // phones that have a heavily rounded display edge.
    val composerShape = RoundedCornerShape(22.dp)
    val useComposerBlur = settings.displaySetting.enableBlurEffect && !BuildConfig.NOTION_LIKE && !timelineScrolling
    val suggestionFillPulse = remember(conversation.id) { Animatable(0f) }

    LaunchedEffect(conversation.id, suggestionFillPulseKey) {
        if (suggestionFillPulseKey > 0) {
            suggestionFillPulse.snapTo(1f)
            suggestionFillPulse.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 220),
            )
        } else {
            suggestionFillPulse.snapTo(0f)
        }
    }

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun sendMessage() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (loading && state.isEmpty()) {
            onCancelClick()
        } else {
            coroutineScope.launch {
                val blockingIssue = withContext(Dispatchers.IO) {
                    ImageAttachmentValidator.firstBlockingIssueForSend(
                        parts = state.getContents(),
                        settings = settings,
                        providerManager = providerManager,
                    )
                }
                if (blockingIssue != null) {
                    toaster.show(blockingIssue.message, type = ToastType.Error)
                } else {
                    onSendClick(PendingUserMessageMode.FOLLOWUP)
                }
            }
        }
    }

    fun sendMessageWithoutAnswer() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (loading && state.isEmpty()) {
            onCancelClick()
        } else {
            coroutineScope.launch {
                val blockingIssue = withContext(Dispatchers.IO) {
                    ImageAttachmentValidator.firstBlockingIssueForSend(
                        parts = state.getContents(),
                        settings = settings,
                        providerManager = providerManager,
                    )
                }
                if (blockingIssue != null) {
                    toaster.show(blockingIssue.message, type = ToastType.Error)
                } else {
                    onLongSendClick(if (loading) PendingUserMessageMode.STEER else PendingUserMessageMode.FOLLOWUP)
                }
            }
        }
    }

    var expand by remember { mutableStateOf(ExpandState.Collapsed) }
    var showUsageSheet by remember { mutableStateOf(false) }
    var usageStatus by remember { mutableStateOf(ComposerUsageStatus()) }
    var usageLoading by remember { mutableStateOf(false) }
    var usageError by remember { mutableStateOf<String?>(null) }
    fun dismissExpand() {
        expand = ExpandState.Collapsed
    }

    fun expandToggle(type: ExpandState) {
        if (expand == type) {
            dismissExpand()
        } else {
            expand = type
        }
    }

    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val httpClient: OkHttpClient = koinInject()
    val usageClient = remember(context, httpClient) {
        OpenAICodexOAuthClient(httpClient, OpenAICodexAuthStore(context))
    }
    val providerUsageClient = remember(httpClient) {
        ProviderUsageClient(httpClient)
    }
    val scope = rememberCoroutineScope()
    val selectedChatModelId = assistant.chatModelId ?: settings.chatModelId
    val chatModel = remember(settings.providers, selectedChatModelId) {
        settings.providers.findModelById(selectedChatModelId)
    }
    val chatProvider = remember(settings.providers, chatModel) {
        chatModel?.findProvider(settings.providers)
    }

    suspend fun refreshUsage() {
        val openAIProvider = chatProvider as? ProviderSetting.OpenAI
        if (openAIProvider == null) {
            usageStatus = ComposerUsageStatus()
            usageError = context.getString(R.string.chat_input_usage_openai_compatible_required)
            return
        }

        usageLoading = true
        usageError = null
        runCatching {
            if (openAIProvider.authMode == OpenAIAuthMode.CODEX_OAUTH) {
                usageClient.fetchUsage(openAIProvider.id).toComposerUsageStatus(context)
            } else {
                providerUsageClient.fetchUsage(openAIProvider, chatModel).toComposerUsageStatus()
            }
        }.onSuccess {
            usageStatus = it
        }.onFailure {
            usageStatus = ComposerUsageStatus()
            usageError = it.message ?: it.toString()
        }
        usageLoading = false
    }

    // Camera launcher
    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }
    val (_, launchCameraCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            state.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            dismissExpand()
        },
        onCleanup = {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    )
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captureSuccessful ->
        if (captureSuccessful && cameraOutputUri != null) {
            if (settings.displaySetting.skipCropImage) {
                state.addImages(filesManager.createChatFilesByContents(listOf(cameraOutputUri!!)))
                cameraOutputFile?.delete()
                cameraOutputFile = null
                cameraOutputUri = null
                dismissExpand()
            } else {
                launchCameraCrop(cameraOutputUri!!)
            }
        } else {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    }
    val onLaunchCamera: () -> Unit = {
        cameraOutputFile = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
        cameraOutputUri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", cameraOutputFile!!
        )
        cameraLauncher.launch(cameraOutputUri!!)
    }

    // Image picker launcher
    var preCropTempFile by remember { mutableStateOf<File?>(null) }
    val (_, launchImageCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            state.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            dismissExpand()
        },
        onCleanup = {
            preCropTempFile?.delete()
            preCropTempFile = null
        }
    )
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                Log.d("ImagePickButton", "Selected URIs: $selectedUris")
                if (settings.displaySetting.skipCropImage) {
                    state.addImages(filesManager.createChatFilesByContents(selectedUris))
                    dismissExpand()
                } else {
                    if (selectedUris.size == 1) {
                        val tempFile = File(context.appTempFolder, "pick_temp_${System.currentTimeMillis()}.jpg")
                        runCatching {
                            context.contentResolver.openInputStream(selectedUris.first())?.use { input ->
                                tempFile.outputStream().use { output -> input.copyTo(output) }
                            }
                            preCropTempFile = tempFile
                            launchImageCrop(tempFile.toUri())
                        }.onFailure {
                            Log.e("ImagePickButton", "Failed to copy image to temp, falling back", it)
                            launchImageCrop(selectedUris.first())
                        }
                    } else {
                        state.addImages(filesManager.createChatFilesByContents(selectedUris))
                        dismissExpand()
                    }
                }
            } else {
                Log.d("ImagePickButton", "No images selected")
            }
        }

    // Video picker launcher
    val videoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                state.addVideos(filesManager.createChatFilesByContents(selectedUris))
                dismissExpand()
            }
        }

    // Audio picker launcher
    val audioPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                // Capture display names from the SAF URIs *before* the copy mangles them
                // into UUID-prefixed cache files; without this the chat-message Audio chip
                // would only have the UUID to show.
                val originalNames = selectedUris.map {
                    filesManager.getFileNameFromUri(it) ?: it.lastPathSegment.orEmpty()
                }
                state.addAudios(filesManager.createChatFilesByContents(selectedUris), originalNames)
                dismissExpand()
            }
        }

    // File picker launcher
    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                val documents = uris.mapNotNull { uri ->
                    val fileName = filesManager.getFileNameFromUri(uri) ?: "file"
                    val mime = filesManager.getFileMimeType(uri) ?: "application/octet-stream"
                    val localUri = filesManager.createChatFilesByContents(listOf(uri)).firstOrNull()
                    if (localUri != null) {
                        UIMessagePart.Document(url = localUri.toString(), fileName = fileName, mime = mime)
                    } else {
                        toaster.show(
                            context.getString(R.string.chat_input_file_upload_failed, fileName),
                            type = ToastType.Error
                        )
                        null
                    }
                }
                if (documents.isNotEmpty()) {
                    state.addFiles(documents)
                    dismissExpand()
                }
            }
        }

    // Collapse when ime is visible
    val imeVisile = WindowInsets.isImeVisible
    LaunchedEffect(imeVisile, showUsageSheet) {
        if (imeVisile && !showUsageSheet) {
            dismissExpand()
        }
    }

    if (showUsageSheet) {
        LaunchedEffect(showUsageSheet, chatProvider.providerRoutingKey()) {
            refreshUsage()
        }
        ComposerUsageSheet(
            status = usageStatus,
            loading = usageLoading,
            error = usageError,
            onRefresh = {
                scope.launch {
                    refreshUsage()
                }
            },
            onDismissRequest = { showUsageSheet = false },
        )
    }

    Surface(
        color = Color.Transparent,
    ) {
        Column(
            modifier = modifier
                .imePadding()
                .navigationBarsPadding()
                // 6dp breathing room below the gesture/nav inset — keeps the
                // 22dp rounded corners clear of devices with aggressive screen
                // rounding so the card never looks "bitten" at the bottom.
                .padding(bottom = 6.dp)
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sandboxActivity?.let { activity ->
                SandboxPeekBar(
                    activity = activity,
                    onOpen = onOpenSandbox,
                    onCancel = onCancelSandbox?.takeIf { activity.canCancel },
                    onPrevious = onPreviousSandbox,
                    onNext = onNextSandbox,
                    modifier = Modifier.align(Alignment.Start),
                )
            }

            val pulseFraction = suggestionFillPulse.value
            val composerBorderColor = lerp(
                start = workspace.hairline,
                stop = workspace.blue.copy(alpha = 0.42f),
                fraction = pulseFraction,
            )
            val composerContainerColor = if (useComposerBlur) {
                Color.Transparent
            } else {
                lerp(
                    start = hazeTintColor,
                    stop = workspace.blueContainer,
                    fraction = pulseFraction * 0.22f,
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(composerShape)
                    .then(
                        if (useComposerBlur) Modifier.hazeEffect(
                            state = hazeState,
                            style = HazeMaterials.ultraThin(containerColor = hazeTintColor)
                        )
                        else Modifier
                    ),
                shape = composerShape,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = BorderStroke(1.dp, composerBorderColor),
                color = composerContainerColor,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    if (state.messageContent.isNotEmpty()) {
                        MediaFileInputRow(state = state)
                    }

                    TextInputRow(
                        state = state,
                        onSendMessage = { sendMessage() },
                        onUsageClick = { showUsageSheet = true },
                        onCompactContext = onCompactContext,
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(ComposerModelGroupHeight),
                            contentAlignment = Alignment.BottomStart,
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                horizontalAlignment = Alignment.Start,
                            ) {
                                ContextUsageIndicator(
                                    conversation = conversation,
                                    contextCompacts = contextCompacts,
                                    model = chatModel,
                                    modifier = Modifier.padding(start = 3.dp),
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Bottom,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    // Plan A core fix: weight(1f, fill=false) lets the
                                    // model chip take whatever leftover width remains
                                    // after the reasoning chip claims its natural size,
                                    // so the reasoning chip is NEVER clipped — and long
                                    // model names ellipsize via the chip's existing
                                    // maxLines=1 + Ellipsis overflow rule.
                                    ModelSelector(
                                        modelId = assistant.chatModelId ?: settings.chatModelId,
                                        providers = settings.providers,
                                        onSelect = {
                                            onUpdateChatModel(it)
                                            dismissExpand()
                                        },
                                        type = ModelType.CHAT,
                                        compact = true,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    ReasoningLevelChip(
                                        reasoningLevel = assistant.reasoningLevel,
                                        model = chatModel,
                                        provider = chatProvider,
                                        onUpdateReasoningLevel = {
                                            onUpdateAssistant(assistant.copy(reasoningLevel = it))
                                        },
                                    )
                                }
                            }
                        }

                        ActionIconButton(
                            onClick = {
                                expandToggle(ExpandState.Files)
                            },
                            accent = expand == ExpandState.Files,
                        ) {
                            Icon(
                                imageVector = if (expand == ExpandState.Files) HugeIcons.Cancel01 else HugeIcons.Add01,
                                contentDescription = stringResource(R.string.more_options),
                                modifier = Modifier.size(ComposerButtonIconSize),
                            )
                        }

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(ComposerButtonSize)
                                .clip(RoundedCornerShape(8.dp))
                                .combinedClickable(
                                    enabled = loading || !state.isEmpty(),
                                    onClick = {
                                        dismissExpand()
                                        sendMessage()
                                    }, onLongClick = {
                                        dismissExpand()
                                        sendMessageWithoutAnswer()
                                    }
                                )
                        ) {
                            val isQueueSend = loading && !state.isEmpty()
                            // Pull empty state down to nearly-transparent + heavily-faded
                            // icon so the send button visibly recedes when there is
                            // nothing to send. Active states (blue when ready / blue
                            // when queueing / amber when streaming) keep full
                            // saturation, so the transition into "go" reads as a
                            // distinct state change instead of a faint hairline tweak.
                            val targetContainer = when {
                                isQueueSend -> workspace.blue
                                loading -> workspace.amberContainer
                                state.isEmpty() -> Color.Transparent
                                else -> workspace.blue
                            }
                            val targetContent = when {
                                isQueueSend -> Color.White
                                loading -> workspace.amber
                                state.isEmpty() -> workspace.faint.copy(alpha = 0.55f)
                                else -> Color.White
                            }
                            val targetBorder = if (state.isEmpty()) workspace.hairline else Color.Transparent
                            val containerColor by animateColorAsState(targetContainer, label = "sendContainer")
                            val contentColor by animateColorAsState(targetContent, label = "sendContent")
                            val borderColor by animateColorAsState(targetBorder, label = "sendBorder")
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                shape = RoundedCornerShape(8.dp),
                                color = containerColor,
                                border = BorderStroke(1.dp, borderColor),
                                content = {})
                            if (loading && state.isEmpty()) {
                                KeepScreenOn()
                                Icon(
                                    imageVector = HugeIcons.Cancel01,
                                    contentDescription = stringResource(R.string.stop),
                                    tint = contentColor,
                                    modifier = Modifier.size(ComposerButtonIconSize),
                                )
                            } else {
                                Icon(
                                    imageVector = HugeIcons.ArrowUp02,
                                    contentDescription = stringResource(R.string.send),
                                    tint = contentColor,
                                    modifier = Modifier.size(ComposerButtonIconSize),
                                )
                            }
                        }
                    }
                }
            }

            // Expanded content
            Box(
                modifier = Modifier
                    .animateContentSize()
                    .fillMaxWidth()
            ) {
                BackHandler(
                    enabled = expand != ExpandState.Collapsed,
                ) {
                    dismissExpand()
                }
                if (expand == ExpandState.Files) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .then(
                                if (useComposerBlur) Modifier.hazeEffect(
                                    state = hazeState,
                                    style = HazeMaterials.ultraThin()
                                )
                                else Modifier
                        ),
                        shape = RoundedCornerShape(8.dp),
                        tonalElevation = 0.dp,
                        border = BorderStroke(1.dp, workspace.hairline),
                        color = if (useComposerBlur) Color.Transparent else hazeTintColor,
                    ) {
                        FilesPicker(
                            onTakePic = onLaunchCamera,
                            onPickImage = { imagePickerLauncher.launch("image/*") },
                            onPickVideo = { videoPickerLauncher.launch("video/*") },
                            onPickAudio = { audioPickerLauncher.launch("audio/*") },
                            onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
                        )
                    }
                }
            }
        }
    }
}

private val ComposerButtonSize = 44.dp
private val ComposerButtonIconSize = 28.dp
private val ComposerModelGroupHeight = 48.dp

@Composable
private fun ActionIconButton(
    onClick: () -> Unit,
    accent: Boolean = false,
    content: @Composable () -> Unit,
) {
    val workspace = workspaceColors()
    Surface(
        onClick = onClick,
        modifier = Modifier.size(ComposerButtonSize),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        color = if (accent) workspace.blueContainer else workspace.paper,
        contentColor = if (accent) workspace.blue else workspace.ink,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

