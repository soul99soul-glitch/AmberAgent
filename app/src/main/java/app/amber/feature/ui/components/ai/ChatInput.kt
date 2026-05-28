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
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.expandHorizontally
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
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
import kotlinx.coroutines.delay
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
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.Image02
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
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
import me.rerere.rikkahub.data.usage.ProviderUsageClient
import app.amber.core.service.PendingUserMessageMode
import app.amber.feature.ui.components.ui.KeepScreenOn
import app.amber.feature.ui.components.ui.WorkspaceIconButton
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.permission.PermissionCamera
import app.amber.feature.ui.components.ui.permission.PermissionManager
import app.amber.feature.ui.components.ui.permission.rememberPermissionState
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.context.LocalToaster
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
    val hazeTintColor = workspace.paper
    // SendOrb 自身 76dp, 顶着 Row 实际高度 = max(60, 76) = 76dp.
    // corner 38dp = 76/2 = 单行/空状态时完美半圆胶囊.
    // 多行扩高 N>76dp 时, 38dp < N/2, 两端不再完美半圆 (长方圆角). 这正是用户要的.
    val composerShape = RoundedCornerShape(38.dp)
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
                // 调整: composer 左右 8→12dp 离屏幕边线稍远一些 (不再"贴边")
                .padding(horizontal = 12.dp),
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
            // V3 review P3 #8: suggestion pulse 用 chatTheme.accent (跟 4 主题色协调)
            val chatThemeForPulse = app.amber.feature.ui.pages.chat.LocalChatTheme.current
            val composerBorderColor = lerp(
                start = workspace.hairline,
                stop = chatThemeForPulse.accent.copy(alpha = 0.42f),
                fraction = pulseFraction,
            )
            val composerContainerColor = if (useComposerBlur) {
                Color.Transparent
            } else {
                lerp(
                    start = hazeTintColor,
                    stop = chatThemeForPulse.accentSoft,
                    fraction = pulseFraction * 0.22f,
                )
            }

            // V3 composer pill 阴影策略：
            //   浅色主题 (Whisper/Plain/Paper)：Material shadow 12dp + chatTheme.composerShadow
            //     的墨灰/暖棕投影，在浅底上"浮"出 pill 感
            //   深色主题 (Midnight)：Material shadow ambient/spot 改 Color.Black 强制黑投影，
            //     避免 default 白色 shadow 在 Midnight #0B0E14 上变成"白晕". border 调成顶轻底重
            //     (5% 白 → 16% 白) 模拟 pill "落"在背景上 (下沉感) 而非顶部 rim light.
            val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
            val composerFill = chatTheme.surface
            val composerBorder: androidx.compose.ui.graphics.Brush = if (chatTheme.isDark) {
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(
                        Color(0x0DE8EAEF),  // 顶 ~5% 白 (细)
                        Color(0x29E8EAEF),  // 底 ~16% 白 (粗) - 落地感
                    )
                )
            } else {
                androidx.compose.ui.graphics.SolidColor(chatTheme.surfaceEdge)
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    // Bug fix: 之前 fixed height(68.dp) 把 TextField maxHeightInLines=5 给框死了,
                    // 用户输入多行时被遮挡. 改 heightIn(min=60dp) 让 composer 跟随 TextField 高度扩展.
                    // 60dp 比之前 68dp 略矮一些, 单行胶囊看着不那么"高大".
                    .heightIn(min = 60.dp)
                    .shadow(
                        elevation = 12.dp,
                        shape = composerShape,
                        clip = false,
                        // 深色模式下 ambient/spot 必须强制 Color.Black, 否则 default 白色 shadow
                        // 在 Midnight 上扩散成"白晕"看着像反向高光. 浅色用 chatTheme.composerShadow
                        // (墨灰/暖棕) 保持原效果.
                        ambientColor = if (chatTheme.isDark) Color.Black else chatTheme.composerShadow,
                        spotColor = if (chatTheme.isDark) Color.Black else chatTheme.composerShadow,
                    )
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
                border = BorderStroke(1.dp, composerBorder),
                color = if (useComposerBlur) Color.Transparent else composerFill,
            ) {
                Row(
                    modifier = Modifier
                        // Bug fix: fillMaxSize() 在 Surface heightIn(min=68dp) 无 max 约束时会
                        // 反过来撑爆 Surface 到屏幕最大. 改 fillMaxWidth() 高度跟随子内容.
                        .fillMaxWidth()
                        // Bug fix: 用户反复反馈光标位置仍偏右. 继续压缩:
                        //   Row start 12 → 8dp / Row spacedBy 4 → 0dp / TextField offset(-8dp)
                        //   抵消 M3 TextField 内部 fixed 16dp content padding.
                        // (TextField 用 absoluteOffset 视觉左移, 不影响布局尺寸, 不会跟旁边元素重叠.)
                        .padding(start = 8.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    val attachmentsExpanded = expand == ExpandState.Files
                    val hideComposerPlaceholder = attachmentsExpanded || keepPlaceholderHiddenDuringAttachmentExit
                    val addRotation by animateFloatAsState(
                        targetValue = if (attachmentsExpanded) 45f else 0f,
                        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                        label = "composerAttachmentToggleRotation",
                    )

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable { expandToggle(ExpandState.Files) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = HugeIcons.Add01,
                                contentDescription = stringResource(R.string.more_options),
                                tint = if (attachmentsExpanded) chatTheme.accent else chatTheme.ink,
                                modifier = Modifier
                                    .size(24.dp)
                                    .graphicsLayer {
                                        rotationZ = addRotation
                                    },
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = attachmentsExpanded,
                        enter = expandHorizontally(
                            animationSpec = tween(240, easing = FastOutSlowInEasing),
                            expandFrom = Alignment.Start,
                        ) + fadeIn(animationSpec = tween(160)) + scaleIn(
                            initialScale = 0.92f,
                            animationSpec = tween(220, easing = FastOutSlowInEasing),
                        ),
                        exit = shrinkHorizontally(
                            animationSpec = tween(180, easing = FastOutSlowInEasing),
                            shrinkTowards = Alignment.Start,
                        ) + fadeOut(animationSpec = tween(120)) + scaleOut(
                            targetScale = 0.94f,
                            animationSpec = tween(160, easing = FastOutSlowInEasing),
                        ),
                    ) {
                        InlineAttachmentActions(
                            onTakePic = onLaunchCamera,
                            onPickImage = { imagePickerLauncher.launch("image/*") },
                            onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.padding(start = 2.dp, end = 4.dp),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(20.dp)
                            .background(chatTheme.hair),
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .absoluteOffset(x = (-8).dp),
                    ) {
                        TextInputRow(
                            state = state,
                            onSendMessage = { sendMessage() },
                            onUsageClick = { showUsageSheet = true },
                            onCompactContext = onCompactContext,
                            // 用 absoluteOffset 把 TextField 整体视觉左移 8dp, 抵消 M3 TextField
                            // 不可直接修改的 16dp leading content padding. 不影响 layout 尺寸,
                            // 故不会跟 / 按钮 / send orb 重叠.
                            modifier = Modifier.fillMaxWidth(),
                            minimalChrome = true,
                            hidePlaceholder = hideComposerPlaceholder,
                            onUpdateAssistant = onUpdateAssistant,
                        )
                    }

                    // V3: 用 Text("/") 字符替代 Canvas line, 跟 SlashCommandLeadingMark
                    // (TextField leadingIcon 内的 "/" 字符) 用同一字体, 两个斜杠角度一致.
                    // toggle 语义: 仅在文本为空 / 已 "/" 开头 (即 panel 已显示) 时 toggle.
                    // 用户已输普通文本时点击 = no-op, 避免吞掉/污染用户内容 (review P2 #1 修复).
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable {
                                val cur = state.textContent.text.toString()
                                when {
                                    cur.isEmpty() -> state.setMessageText("/")
                                    cur == "/" -> state.setMessageText("")  // 仅 toggle 关闭
                                    cur.startsWith("/") -> {
                                        // 已 "/" 开头 + 有查询字符 → 收起 panel, 保留用户输入 (去 "/")
                                        state.setMessageText(cur.removePrefix("/"))
                                    }
                                    // 用户已输普通文本 → no-op (避免污染)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.Text(
                            text = "/",
                            fontSize = 22.sp,
                            color = workspace.muted,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }

                    if (loading && state.isEmpty()) {
                        KeepScreenOn()
                    }
                    app.amber.feature.ui.pages.chat.SendOrb(
                        isEmpty = state.isEmpty(),
                        loading = loading,
                        onClick = {
                            dismissExpand()
                            sendMessage()
                        },
                        onLongClick = {
                            dismissExpand()
                            sendMessageWithoutAnswer()
                        },
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
