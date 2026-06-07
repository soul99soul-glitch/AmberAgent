package app.amber.feature.ui.components.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderManager
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.providers.openai.OpenAICodexAuthStore
import app.amber.ai.provider.providers.openai.OpenAICodexOAuthClient
import app.amber.ai.ui.UIMessagePart
import app.amber.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.Image02
import app.amber.agent.BuildConfig
import app.amber.agent.R
import app.amber.feature.runtime.SandboxActivityUiState
import app.amber.feature.runtime.ToolActivityStatus
import app.amber.core.ai.vision.ImageAttachmentValidator
import app.amber.core.context.CompactLifecycleState
import app.amber.core.settings.Settings
import app.amber.core.settings.findProvider
import app.amber.core.settings.findModelById
import app.amber.core.settings.getCurrentAssistant
import app.amber.core.files.FilesManager
import app.amber.core.model.Assistant
import app.amber.core.model.Conversation
import app.amber.core.usage.ProviderUsageClient
import app.amber.core.service.PendingUserMessageMode
import app.amber.feature.ui.components.ui.KeepScreenOn
import app.amber.feature.ui.components.ui.WorkspaceIconButton
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.permission.PermissionCamera
import app.amber.feature.ui.components.ui.permission.PermissionManager
import app.amber.feature.ui.components.ui.permission.rememberPermissionState
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.hooks.ChatInputState
import app.amber.core.utils.ChatSendTransitionTracker
import app.amber.core.utils.formatNumber
import org.koin.compose.koinInject
import okhttp3.OkHttpClient
import java.io.File
import kotlin.uuid.Uuid

private const val PostSendKeyboardHideDelayMillis = 96L

enum class ExpandState {
    Collapsed, Files,
}

/**
 * Pure toggle logic for the composer attach control (`+`→`×`/capsule): tapping a target toggles
 * it open, tapping the open one collapses it. Extracted for JVM testing (see ComposerLogicTest).
 */
fun nextExpandState(current: ExpandState, target: ExpandState): ExpandState =
    if (current == target) ExpandState.Collapsed else target

/**
 * The send button is active (fills accent / enabled) when there is draft text, and stays enabled
 * while streaming so it can show "stop". Pure + testable (see ComposerLogicTest) — the "draft 亮起".
 */
fun composerSendEnabled(isEmpty: Boolean, loading: Boolean): Boolean = loading || !isEmpty

@Composable
fun ChatInput(
    state: ChatInputState,
    loading: Boolean,
    conversation: Conversation,
    contextCompacts: List<app.amber.core.context.ConversationCompact> = emptyList(),
    compactLifecycleState: CompactLifecycleState = CompactLifecycleState.idle(),
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
    val imeVisible = WindowInsets.isImeVisible
    var expand by remember { mutableStateOf(ExpandState.Collapsed) }
    var keepPlaceholderHiddenDuringAttachmentExit by remember { mutableStateOf(false) }

    LaunchedEffect(expand) {
        if (expand == ExpandState.Files) {
            keepPlaceholderHiddenDuringAttachmentExit = true
        } else {
            delay(190)
            keepPlaceholderHiddenDuringAttachmentExit = false
        }
    }

    fun hideKeyboardAfterSend() {
        coroutineScope.launch {
            // Let the sent message, input clear, and bottom-follow layout commit
            // before IME starts its own inset animation. Starting both in the
            // same frame makes the keyboard close look low-FPS on real devices.
            delay(PostSendKeyboardHideDelayMillis)
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }

    fun sendMessage() {
        if (loading && state.isEmpty()) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            onCancelClick()
        } else {
            ChatSendTransitionTracker.start(
                conversationId = conversation.id.toString(),
                preSendLatestMessageId = conversation.currentMessages.lastOrNull()?.id?.toString(),
            )
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
                    hideKeyboardAfterSend()
                }
            }
        }
    }

    fun sendMessageWithoutAnswer() {
        if (loading && state.isEmpty()) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            onCancelClick()
        } else {
            val queueMode = if (loading) PendingUserMessageMode.STEER else PendingUserMessageMode.FOLLOWUP
            ChatSendTransitionTracker.start(
                conversationId = conversation.id.toString(),
                preSendLatestMessageId = conversation.currentMessages.lastOrNull()?.id?.toString(),
            )
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
                    onLongSendClick(queueMode)
                    hideKeyboardAfterSend()
                }
            }
        }
    }

    var showUsageSheet by remember { mutableStateOf(false) }
    var usageStatus by remember { mutableStateOf(ComposerUsageStatus()) }
    var usageLoading by remember { mutableStateOf(false) }
    var usageError by remember { mutableStateOf<String?>(null) }
    fun dismissExpand() {
        expand = ExpandState.Collapsed
    }

    fun expandToggle(type: ExpandState) {
        if (nextExpandState(expand, type) == ExpandState.Collapsed) {
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

    fun addImagesFromUris(uris: List<Uri>, onComplete: () -> Unit = {}) {
        scope.launch {
            state.addImages(filesManager.createChatFilesByContents(uris))
            onComplete()
        }
    }

    fun deleteTempFileAsync(file: File?) {
        if (file == null) return
        scope.launch(Dispatchers.IO) {
            file.delete()
        }
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
            addImagesFromUris(listOf(croppedUri)) {
                dismissExpand()
            }
        },
        onCleanup = {
            deleteTempFileAsync(cameraOutputFile)
            cameraOutputFile = null
            cameraOutputUri = null
        }
    )
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captureSuccessful ->
        if (captureSuccessful && cameraOutputUri != null) {
            if (settings.displaySetting.skipCropImage) {
                val capturedUri = cameraOutputUri!!
                val capturedFile = cameraOutputFile
                addImagesFromUris(listOf(capturedUri)) {
                    deleteTempFileAsync(capturedFile)
                    if (cameraOutputFile == capturedFile) {
                        cameraOutputFile = null
                    }
                    if (cameraOutputUri == capturedUri) {
                        cameraOutputUri = null
                    }
                    dismissExpand()
                }
            } else {
                launchCameraCrop(cameraOutputUri!!)
            }
        } else {
            deleteTempFileAsync(cameraOutputFile)
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
            addImagesFromUris(listOf(croppedUri)) {
                dismissExpand()
            }
        },
        onCleanup = {
            deleteTempFileAsync(preCropTempFile)
            preCropTempFile = null
        }
    )
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                Log.d("ImagePickButton", "Selected URIs: $selectedUris")
                if (settings.displaySetting.skipCropImage) {
                    addImagesFromUris(selectedUris) {
                        dismissExpand()
                    }
                } else {
                    if (selectedUris.size == 1) {
                        val tempFile = File(context.appTempFolder, "pick_temp_${System.currentTimeMillis()}.jpg")
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    context.contentResolver.openInputStream(selectedUris.first())?.use { input ->
                                        tempFile.outputStream().use { output -> input.copyTo(output) }
                                    }
                                }
                                preCropTempFile = tempFile
                                launchImageCrop(tempFile.toUri())
                            }.onFailure {
                                Log.e("ImagePickButton", "Failed to copy image to temp, falling back", it)
                                launchImageCrop(selectedUris.first())
                            }
                        }
                    } else {
                        addImagesFromUris(selectedUris) {
                            dismissExpand()
                        }
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
                scope.launch {
                    state.addVideos(filesManager.createChatFilesByContents(selectedUris))
                    dismissExpand()
                }
            }
        }

    // Audio picker launcher
    val audioPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                // Capture display names from the SAF URIs *before* the copy mangles them
                // into UUID-prefixed cache files; without this the chat-message Audio chip
                // would only have the UUID to show.
                scope.launch {
                    val originalNames = withContext(Dispatchers.IO) {
                        selectedUris.map {
                            filesManager.getFileNameFromUri(it) ?: it.lastPathSegment.orEmpty()
                        }
                    }
                    state.addAudios(filesManager.createChatFilesByContents(selectedUris), originalNames)
                    dismissExpand()
                }
            }
        }

    // File picker launcher
    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                scope.launch {
                    val failedNames = mutableListOf<String>()
                    val documents = uris.mapNotNull { uri ->
                        val fileName = withContext(Dispatchers.IO) {
                            filesManager.getFileNameFromUri(uri) ?: "file"
                        }
                        val mime = withContext(Dispatchers.IO) {
                            filesManager.getFileMimeType(uri) ?: "application/octet-stream"
                        }
                        val localUri = filesManager.createChatFilesByContents(listOf(uri)).firstOrNull()
                        if (localUri != null) {
                            UIMessagePart.Document(url = localUri.toString(), fileName = fileName, mime = mime)
                        } else {
                            failedNames.add(fileName)
                            null
                        }
                    }
                    failedNames.forEach { fileName ->
                        toaster.show(
                            context.getString(R.string.chat_input_file_upload_failed, fileName),
                            type = ToastType.Error
                        )
                    }
                    if (documents.isNotEmpty()) {
                        state.addFiles(documents)
                        dismissExpand()
                    }
                }
            }
        }

    // Collapse when ime is visible
    LaunchedEffect(imeVisible, showUsageSheet) {
        if (imeVisible && !showUsageSheet) {
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

    val tokens = LocalAmberTokens.current
    // Graphite §7.4 "immersive bottom": the composer sits on a full-bleed tray = `surface`
    // fill that continues to the screen edges (and under the nav inset). Flat — no shadow,
    // and no top divider: the surface/bg color difference alone separates it from content.
    Surface(
        color = tokens.surface,
    ) {
        Column(
            modifier = modifier
                .imePadding()
                .navigationBarsPadding()
                // breathing room below the gesture/nav inset
                .padding(bottom = 6.dp)
                // 调整: composer 左右 8→12dp 离屏幕边线稍远一些 (不再"贴边")
                .padding(horizontal = 12.dp)
                .padding(top = 10.dp),
            // spacedBy 控制 SandboxPeekBar 与 composer pill 之间的间距。
            // 设计稿是预览卡紧贴输入框，2dp 足够留一条 hair 缝
            verticalArrangement = Arrangement.spacedBy(2.dp)
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
            val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
            // Graphite §6.2 / §7.4 composer: three separate `surface-2` surfaces on the tray,
            // separated by gaps — circular [+] · pill input · circular send. Flat & hairline
            // only (no shadow / glow on any of them). The suggestion-fill pulse now tints the
            // input pill's hairline border (resting = `line`).
            val attachmentsExpanded = expand == ExpandState.Files
            val hideComposerPlaceholder = attachmentsExpanded || keepPlaceholderHiddenDuringAttachmentExit
            val addRotation by animateFloatAsState(
                targetValue = if (attachmentsExpanded) 45f else 0f,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                label = "composerAttachmentToggleRotation",
            )
            val pillBorder = lerp(
                start = tokens.line,
                stop = chatTheme.accent.copy(alpha = 0.42f),
                fraction = pulseFraction,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                // ── attach chip — `surface-2` circle that morphs into the [× · image · file]
                //    capsule (animated width via animateContentSize; + rotates to ×).
                Row(
                    modifier = Modifier
                        .height(46.dp)
                        .clip(CircleShape)
                        .background(tokens.surface2)
                        .animateContentSize(animationSpec = tween(220, easing = FastOutSlowInEasing)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .clickable { expandToggle(ExpandState.Files) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = HugeIcons.Add01,
                            contentDescription = stringResource(R.string.more_options),
                            // 与发送按钮图标同色（浅灰 ink3），不再用更深的 ink2
                            tint = if (attachmentsExpanded) chatTheme.accent else tokens.ink3,
                            modifier = Modifier
                                .size(24.dp)
                                .graphicsLayer {
                                    rotationZ = addRotation
                                },
                        )
                    }

                    AnimatedVisibility(
                        visible = attachmentsExpanded,
                        enter = fadeIn(animationSpec = tween(160)) + scaleIn(
                            initialScale = 0.92f,
                            animationSpec = tween(220, easing = FastOutSlowInEasing),
                        ),
                        exit = fadeOut(animationSpec = tween(120)) + scaleOut(
                            targetScale = 0.94f,
                            animationSpec = tween(160, easing = FastOutSlowInEasing),
                        ),
                    ) {
                        InlineAttachmentActions(
                            onTakePic = onLaunchCamera,
                            onPickImage = { imagePickerLauncher.launch("image/*") },
                            onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }

                // ── center input pill — `surface-2`, 26dp radius, 1dp hairline, flat.
                val pillShape = RoundedCornerShape(26.dp)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp)
                        .clip(pillShape)
                        .background(tokens.surface2)
                        .border(BorderStroke(1.dp, pillBorder), pillShape)
                        .padding(start = 4.dp, end = 6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        TextInputRow(
                            state = state,
                            onSendMessage = { sendMessage() },
                            onUsageClick = { showUsageSheet = true },
                            onCompactContext = onCompactContext,
                            modifier = Modifier.fillMaxWidth(),
                            minimalChrome = true,
                            hidePlaceholder = hideComposerPlaceholder,
                            onUpdateAssistant = onUpdateAssistant,
                        )
                    }
                }

                if (loading && state.isEmpty()) {
                    KeepScreenOn()
                }
                // Graphite §6.2 composer: a FLAT circular send button (no halo/glow/shadow).
                // Fills with accent when there is a draft (!isEmpty); neutral surface2 when
                // empty. Stop-state (loading & empty) keeps the cancel affordance like before.
                // pressable only exposes onClick, but send needs both onClick (send) and
                // onLongClick (send-without-answer) — so we drive press feedback (scale .975,
                // design §5) from a shared MutableInteractionSource that also feeds
                // combinedClickable, rather than stacking pressable + combinedClickable.
                val sendEmpty = state.isEmpty()
                val sendEnabled = composerSendEnabled(sendEmpty, loading)
                val sendStopState = loading && sendEmpty
                val sendFill by animateColorAsState(
                    targetValue = if (sendEmpty && !loading) tokens.surface2 else tokens.accent,
                    label = "sendButtonFill",
                )
                val sendIconTint by animateColorAsState(
                    targetValue = if (sendEmpty && !loading) tokens.ink3 else tokens.accentInk,
                    label = "sendButtonIconTint",
                )
                val sendInteraction = remember { MutableInteractionSource() }
                val sendPressed by sendInteraction.collectIsPressedAsState()
                val sendScale by animateFloatAsState(
                    targetValue = if (sendPressed) 0.975f else 1f,
                    label = "sendButtonPress",
                )
                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = sendScale
                            scaleY = sendScale
                        }
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(sendFill)
                        .combinedClickable(
                            interactionSource = sendInteraction,
                            indication = null,
                            enabled = sendEnabled,
                            onClick = {
                                dismissExpand()
                                sendMessage()
                            },
                            onLongClick = {
                                dismissExpand()
                                sendMessageWithoutAnswer()
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (sendStopState) HugeIcons.Cancel01 else HugeIcons.ArrowUp02,
                        contentDescription = if (sendStopState) "stop" else "send",
                        tint = sendIconTint,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            if (state.messageContent.isNotEmpty()) {
                MediaFileInputRow(state = state)
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
            }
        }
    }
}

@Composable
private fun InlineAttachmentActions(
    onTakePic: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cameraPermission = rememberPermissionState(PermissionCamera)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        PermissionManager(permissionState = cameraPermission) {
            InlineAttachmentIcon(
                icon = HugeIcons.Camera01,
                contentDescription = stringResource(R.string.take_picture),
                onClick = {
                    if (cameraPermission.allRequiredPermissionsGranted) {
                        onTakePic()
                    } else {
                        cameraPermission.requestPermissions()
                    }
                },
            )
        }
        InlineAttachmentIcon(
            icon = HugeIcons.Image02,
            contentDescription = stringResource(R.string.photo),
            onClick = onPickImage,
        )
        InlineAttachmentIcon(
            icon = HugeIcons.Files02,
            contentDescription = stringResource(R.string.upload_file),
            onClick = onPickFile,
        )
    }
}

@Composable
private fun InlineAttachmentIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = chatTheme.inkSoft,
            modifier = Modifier.size(23.dp),
        )
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
        // V3 review P3 #8: action icon button 切 chatTheme.accent 跟主题
        color = if (accent) app.amber.feature.ui.pages.chat.LocalChatTheme.current.accentSoft else workspace.paper,
        contentColor = if (accent) app.amber.feature.ui.pages.chat.LocalChatTheme.current.accent else workspace.ink,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}
