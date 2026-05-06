package me.rerere.rikkahub.ui.components.ai

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Log
import android.webkit.WebView as AndroidWebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import com.dokar.sonner.ToastType
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.UCropActivity
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.FullScreen
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Video01
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.agent.SandboxActivityUiState
import me.rerere.rikkahub.data.agent.ToolActivityStatus
import me.rerere.rikkahub.data.agent.webview.WebViewLink
import me.rerere.rikkahub.data.agent.webview.WebViewOperationStore
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.getQuickMessagesOfAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.ui.components.ui.KeepScreenOn
import me.rerere.rikkahub.ui.components.ui.WorkspaceIconButton
import me.rerere.rikkahub.ui.components.ui.WorkspaceTone
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.formatNumber
import org.koin.compose.koinInject
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import kotlin.uuid.Uuid

enum class ExpandState {
    Collapsed, Files,
}

@Composable
fun ChatInput(
    state: ChatInputState,
    loading: Boolean,
    conversation: Conversation,
    settings: Settings,
    hazeState: HazeState,
    enableSearch: Boolean,
    onToggleSearch: (Boolean) -> Unit,
    sandboxActivity: SandboxActivityUiState? = null,
    onOpenSandbox: () -> Unit = {},
    onCancelSandbox: (() -> Unit)? = null,
    onPreviousSandbox: (() -> Unit)? = null,
    onNextSandbox: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onUpdateChatModel: (Model) -> Unit,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    onCancelClick: () -> Unit,
    onSendClick: () -> Unit,
    onLongSendClick: () -> Unit,
) {
    val toaster = LocalToaster.current
    val assistant = settings.getCurrentAssistant()
    val workspace = workspaceColors()
    val hazeTintColor = workspace.paper
    val composerShape = RoundedCornerShape(8.dp)
    val useComposerBlur = settings.displaySetting.enableBlurEffect && !BuildConfig.NOTION_LIKE

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun sendMessage() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (loading) onCancelClick() else onSendClick()
    }

    fun sendMessageWithoutAnswer() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (loading) onCancelClick() else onLongSendClick()
    }

    var expand by remember { mutableStateOf(ExpandState.Collapsed) }
    var showUsageSheet by remember { mutableStateOf(false) }
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
                state.addAudios(filesManager.createChatFilesByContents(selectedUris))
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
        ComposerUsageSheet(
            status = ComposerUsageStatus(),
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
                border = BorderStroke(1.dp, workspace.hairline),
                color = if (useComposerBlur) Color.Transparent else hazeTintColor,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    if (state.messageContent.isNotEmpty()) {
                        MediaFileInputRow(state = state)
                    }

                    val selectedChatModelId = assistant.chatModelId ?: settings.chatModelId
                    val chatModel = remember(settings.providers, selectedChatModelId) {
                        settings.providers.findModelById(selectedChatModelId)
                    }
                    val chatProvider = remember(settings.providers, chatModel) {
                        chatModel?.findProvider(settings.providers)
                    }
                    TextInputRow(
                        state = state,
                        onSendMessage = { sendMessage() },
                        onUsageClick = { showUsageSheet = true },
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
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                    horizontalAlignment = Alignment.Start,
                                ) {
                                    ContextUsageIndicator(
                                        conversation = conversation,
                                        model = chatModel,
                                        modifier = Modifier.padding(start = 3.dp),
                                    )
                                    Row(
                                        verticalAlignment = Alignment.Bottom,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        ModelSelector(
                                            modelId = assistant.chatModelId ?: settings.chatModelId,
                                            providers = settings.providers,
                                            onSelect = {
                                                onUpdateChatModel(it)
                                                dismissExpand()
                                            },
                                            type = ModelType.CHAT,
                                            compact = true,
                                            modifier = Modifier,
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
                            val containerColor = when {
                                loading -> workspace.amberContainer
                                state.isEmpty() -> workspace.row
                                else -> workspace.blue
                            }
                            val contentColor = when {
                                loading -> workspace.amber
                                state.isEmpty() -> workspace.faint
                                else -> Color.White
                            }
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                shape = RoundedCornerShape(8.dp),
                                color = containerColor,
                                border = BorderStroke(1.dp, if (state.isEmpty()) workspace.hairline else Color.Transparent),
                                content = {})
                            if (loading) {
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

@Composable
private fun ContextUsageIndicator(
    conversation: Conversation,
    model: Model?,
    modifier: Modifier = Modifier,
) {
    val workspace = workspaceColors()
    val usedTokens = remember(conversation.messageNodes) {
        conversation.messageNodes.asReversed()
            .map { it.currentMessage }
            .firstOrNull { it.role == MessageRole.ASSISTANT }
            ?.usage
            ?.promptTokens
            ?: estimateConversationTokens(conversation)
    }
    val contextWindow = remember(model?.modelId, model?.contextWindowTokens) {
        model?.contextWindowTokens ?: model?.modelId?.let { ModelRegistry.MODEL_CONTEXT_WINDOW.getData(it) }
    }
    val ratio = if (contextWindow != null && contextWindow > 0) {
        (usedTokens.toFloat() / contextWindow.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val ringColor = when {
        ratio >= 0.7f -> workspace.red
        ratio >= 0.5f -> workspace.amber
        else -> workspace.muted.copy(alpha = 0.56f)
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(modifier = Modifier.size(10.dp)) {
            val stroke = 1.6.dp.toPx()
            val radius = (size.minDimension - stroke) / 2f
            drawCircle(
                color = workspace.faint.copy(alpha = 0.24f),
                radius = radius,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * ratio,
                useCenter = false,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            text = "Context ${usedTokens.formatNumber()} / ${contextWindow?.formatNumber() ?: "--"}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = workspace.muted.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

private fun estimateConversationTokens(conversation: Conversation): Int {
    val chars = conversation.currentMessages.sumOf { message ->
        message.parts.sumOf { it.estimatedTokenChars() }
    }
    return (chars / 4).coerceAtLeast(0)
}

private fun UIMessagePart.estimatedTokenChars(): Int = when (this) {
    is UIMessagePart.Text -> text.length
    is UIMessagePart.Reasoning -> reasoning.length
    is UIMessagePart.Document -> fileName.length
    is UIMessagePart.Tool -> input.length + output.sumOf { it.estimatedTokenChars() }
    is UIMessagePart.Image,
    is UIMessagePart.Video,
    is UIMessagePart.Audio -> 0
    else -> 0
}

private data class ComposerUsageStatus(
    val fiveHourQuota: ComposerUsageMetric? = null,
    val weeklyQuota: ComposerUsageMetric? = null,
    val cacheHitRate: ComposerUsageMetric? = null,
) {
    val hasData: Boolean
        get() = fiveHourQuota != null || weeklyQuota != null || cacheHitRate != null
}

private data class ComposerUsageMetric(
    val percent: Int? = null,
    val detail: String? = null,
)

@Composable
private fun ReasoningLevelChip(
    reasoningLevel: ReasoningLevel,
    model: Model?,
    provider: ProviderSetting?,
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val workspace = workspaceColors()
    val options = remember(model?.modelId, model?.abilities, provider.providerRoutingKey()) {
        model.reasoningOptions(provider)
    }
    val selectedLevel = remember(reasoningLevel, options) {
        reasoningLevel.coerceToReasoningOptions(options)
    }

    LaunchedEffect(selectedLevel, reasoningLevel, options) {
        if (selectedLevel != reasoningLevel) {
            onUpdateReasoningLevel(selectedLevel)
        }
    }

    Box(modifier = modifier) {
        ComposerStatusChip(
            text = options.labelFor(selectedLevel),
            accent = selectedLevel.isEnabled,
            onClick = { expanded = true },
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .width(232.dp)
                .background(workspace.paper, RoundedCornerShape(10.dp)),
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Reasoning",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = workspace.muted,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                options.chunked(3).fastForEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        row.fastForEach { option ->
                            ReasoningLevelMenuCell(
                                label = option.label,
                                selected = option.level == selectedLevel,
                                onClick = {
                                    onUpdateReasoningLevel(option.level)
                                    expanded = false
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(3 - row.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReasoningLevelMenuCell(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = workspaceColors()
    Surface(
        onClick = onClick,
        modifier = modifier.height(30.dp),
        shape = RoundedCornerShape(6.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        color = if (selected) workspace.blueContainer else Color.Transparent,
        contentColor = if (selected) workspace.blue else workspace.ink,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ComposerStatusChip(
    text: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val workspace = workspaceColors()
    Surface(
        onClick = onClick,
        modifier = modifier.height(32.dp),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        color = if (accent) workspace.blueContainer else workspace.paper,
        contentColor = if (accent) workspace.blue else workspace.ink,
        border = BorderStroke(1.dp, if (accent) workspace.blue.copy(alpha = 0.18f) else workspace.hairline),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ComposerUsageSheet(
    status: ComposerUsageStatus,
    onDismissRequest: () -> Unit,
) {
    val workspace = workspaceColors()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!status.hasData) {
                Text(
                    text = "No usage data",
                    style = MaterialTheme.typography.bodyMedium,
                    color = workspace.muted,
                )
            } else {
                status.fiveHourQuota?.let { UsageMetricRow(label = "5h", metric = it) }
                status.weeklyQuota?.let { UsageMetricRow(label = "weekly", metric = it) }
                status.cacheHitRate?.let { UsageMetricRow(label = "cache", metric = it) }
            }
        }
    }
}

@Composable
private fun UsageMetricRow(
    label: String,
    metric: ComposerUsageMetric,
) {
    val workspace = workspaceColors()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = workspace.muted,
        )
        Text(
            text = listOfNotNull(
                metric.percent?.let { "$it%" },
                metric.detail,
            ).joinToString("  ").ifBlank { "--" },
            style = MaterialTheme.typography.bodyMedium,
            color = workspace.ink,
        )
    }
}

private fun ReasoningLevel.composerLabel(): String = when (this) {
    ReasoningLevel.OFF -> "off"
    ReasoningLevel.AUTO -> "auto"
    ReasoningLevel.LOW -> "low"
    ReasoningLevel.MEDIUM -> "medium"
    ReasoningLevel.HIGH -> "high"
    ReasoningLevel.XHIGH -> "xhigh"
    ReasoningLevel.MAX -> "max"
}

private data class ReasoningOption(
    val level: ReasoningLevel,
    val label: String = level.composerLabel(),
)

private enum class ReasoningFamily {
    CLAUDE_OPUS_47,
    CLAUDE_MAX,
    CLAUDE_HIGH,
    OPENAI_XHIGH,
    OPENAI,
    DEEPSEEK,
    BINARY,
    GEMINI,
    GENERIC,
    NONE,
}

private fun List<ReasoningOption>.labelFor(level: ReasoningLevel): String {
    return firstOrNull { it.level == level }?.label ?: level.composerLabel()
}

private fun reasoningOptionsOf(vararg levels: ReasoningLevel): List<ReasoningOption> {
    return levels.map { ReasoningOption(it) }
}

private fun ReasoningLevel.coerceToReasoningOptions(options: List<ReasoningOption>): ReasoningLevel {
    if (options.any { it.level == this }) return this
    if ((this == ReasoningLevel.XHIGH || this == ReasoningLevel.MAX) && options.any { it.level == ReasoningLevel.MAX }) {
        return ReasoningLevel.MAX
    }
    if (isEnabled && options.any { it.level == ReasoningLevel.AUTO }) {
        return ReasoningLevel.AUTO
    }
    return options.firstOrNull()?.level ?: ReasoningLevel.OFF
}

private fun Model?.reasoningOptions(provider: ProviderSetting?): List<ReasoningOption> {
    if (this == null) {
        return reasoningOptionsOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.XHIGH,
        )
    }
    return when (reasoningFamily(provider)) {
        ReasoningFamily.CLAUDE_OPUS_47 -> reasoningOptionsOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.XHIGH,
            ReasoningLevel.MAX,
        )

        ReasoningFamily.CLAUDE_MAX -> reasoningOptionsOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.MAX,
        )

        ReasoningFamily.CLAUDE_HIGH -> reasoningOptionsOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
        )

        ReasoningFamily.OPENAI_XHIGH -> reasoningOptionsOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.XHIGH,
        )

        ReasoningFamily.OPENAI,
        ReasoningFamily.GEMINI -> reasoningOptionsOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
        )

        ReasoningFamily.DEEPSEEK -> listOf(
            ReasoningOption(ReasoningLevel.OFF),
            ReasoningOption(ReasoningLevel.AUTO, "on"),
            ReasoningOption(ReasoningLevel.MAX),
        )

        ReasoningFamily.BINARY -> listOf(
            ReasoningOption(ReasoningLevel.OFF),
            ReasoningOption(ReasoningLevel.AUTO, "on"),
        )

        ReasoningFamily.GENERIC -> reasoningOptionsOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.XHIGH,
        )

        ReasoningFamily.NONE -> reasoningOptionsOf(ReasoningLevel.OFF)
    }
}

private fun Model.reasoningFamily(provider: ProviderSetting?): ReasoningFamily {
    val id = modelId.lowercase()
    val providerKey = provider.providerRoutingKey()
    return when {
        "claude" in id || provider is ProviderSetting.Claude -> when {
            id.contains("opus") && id.contains("4") && id.contains("7") -> ReasoningFamily.CLAUDE_OPUS_47
            id.contains("mythos") -> ReasoningFamily.CLAUDE_MAX
            id.contains("opus") && id.contains("4") && (id.contains("5") || id.contains("6")) -> ReasoningFamily.CLAUDE_MAX
            id.contains("sonnet") && id.contains("4") && id.contains("6") -> ReasoningFamily.CLAUDE_HIGH
            else -> ReasoningFamily.GENERIC
        }

        "deepseek" in id || providerKey == "deepseek" -> ReasoningFamily.DEEPSEEK
        "kimi" in id || "moonshot" in id || providerKey == "kimi" -> ReasoningFamily.BINARY
        "glm" in id || "zhipu" in id || providerKey == "zhipu" -> ReasoningFamily.BINARY
        "mimo" in id -> ReasoningFamily.BINARY
        id.isQwenPlusBinaryReasoningModel() -> ReasoningFamily.BINARY
        provider is ProviderSetting.Google || providerKey == "gemini" -> ReasoningFamily.GEMINI
        id.contains("gpt-5.5") || id.contains("gpt-5.4") -> ReasoningFamily.OPENAI_XHIGH
        id.contains("gpt-5") || Regex("\\bo\\d+").containsMatchIn(id) -> ReasoningFamily.OPENAI
        ModelAbility.REASONING in abilities -> ReasoningFamily.GENERIC
        else -> ReasoningFamily.NONE
    }
}

private fun String.isQwenPlusBinaryReasoningModel(): Boolean {
    if (!contains("qwen") || !contains("plus")) return false
    return Regex("""(^|[^0-9])3[._-]?5([^0-9]|$)""").containsMatchIn(this) ||
        Regex("""(^|[^0-9])3[._-]?6([^0-9]|$)""").containsMatchIn(this)
}

private fun ProviderSetting?.providerRoutingKey(): String {
    return when (this) {
        is ProviderSetting.Claude -> "claude"
        is ProviderSetting.Google -> "gemini"
        is ProviderSetting.OpenAI -> {
            val endpoint = "${baseUrl} ${name}".lowercase()
            when {
                "deepseek" in endpoint -> "deepseek"
                "moonshot" in endpoint || "kimi" in endpoint -> "kimi"
                "bigmodel" in endpoint || "zhipu" in endpoint -> "zhipu"
                "api.openai.com" in endpoint || name.equals("openai", ignoreCase = true) -> "openai"
                else -> ""
            }
        }

        null -> ""
    }
}

@Composable
private fun SandboxPeekBar(
    activity: SandboxActivityUiState,
    onOpen: () -> Unit,
    onCancel: (() -> Unit)?,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AgentOperationPreviewPeek(
            activity = activity,
            onOpen = onOpen,
            modifier = Modifier
                .width(118.dp)
                .height(78.dp),
        )
        SandboxStepPeek(
            activity = activity,
            onOpen = onOpen,
            onCancel = onCancel,
            onPrevious = onPrevious,
            onNext = onNext,
            modifier = Modifier
                .weight(1f)
                .height(38.dp),
        )
    }
}

@Composable
private fun AgentOperationPreviewPeek(
    activity: SandboxActivityUiState,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val previewUrl = activity.operationPreviewUrl()
    val workspace = workspaceColors()
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onOpen() },
        shape = RoundedCornerShape(10.dp),
        color = workspace.paper,
        contentColor = workspace.ink,
        shadowElevation = 1.dp,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        if (previewUrl != null) {
            WebOperationPreviewThumbnail(
                url = previewUrl,
                onOpen = onOpen,
            )
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = activity.operationPreviewKind(),
                    color = workspace.amber,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = activity.operationPreviewText(),
                    color = workspace.muted,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun WebOperationPreviewThumbnail(
    url: String,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val webViewOperationStore: WebViewOperationStore = koinInject()
    val webState by webViewOperationStore.state.collectAsState()
    val isCurrentPreview = webState.requestedUrl == url || webState.url == url
    val thumbnailFile = webState.thumbnailPath
        .takeIf { isCurrentPreview && it.isNotBlank() }
        ?.let(::File)
        ?.takeIf { it.exists() && it.length() > 0L }
    LaunchedEffect(url, isCurrentPreview) {
        if (!isCurrentPreview) {
            webViewOperationStore.open(url)
        }
    }
    val state = rememberWebViewState(
        url = url,
        settings = {
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 85
        },
    )
    Box(modifier = Modifier.fillMaxSize()) {
        if (thumbnailFile != null) {
            AsyncImage(
                model = thumbnailFile,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            WebView(
                state = state,
                modifier = Modifier
                    .requiredSize(width = 336.dp, height = 252.dp)
                    .graphicsLayer {
                        scaleX = 0.35f
                        scaleY = 0.35f
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
                onCreated = { webView ->
                    webView.isFocusable = false
                    webView.isFocusableInTouchMode = false
                    webView.setOnTouchListener { _, _ -> true }
                },
                onUpdated = { webView ->
                    webView.setOnTouchListener { _, _ -> true }
                },
                onProgressChanged = { webView, progress ->
                    webViewOperationStore.updateLoading(webView?.url ?: url, progress)
                },
                onPageStarted = { webView, pageUrl ->
                    webViewOperationStore.updateLoading(pageUrl ?: webView?.url ?: url, 1)
                },
                onPageFinished = { webView, pageUrl ->
                    val resolvedUrl = pageUrl ?: webView?.url ?: url
                    webViewOperationStore.updateLoading(resolvedUrl, 100)
                    webView?.let {
                        extractReadablePage(it, webViewOperationStore)
                        captureWebViewThumbnail(it, webViewOperationStore, context)
                    }
                },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { onOpen() },
        )
    }
}

@Composable
private fun SandboxStepPeek(
    activity: SandboxActivityUiState,
    onOpen: () -> Unit,
    onCancel: (() -> Unit)?,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val workspace = workspaceColors()
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onOpen() },
        shape = RoundedCornerShape(10.dp),
        color = workspace.paper,
        contentColor = workspace.ink,
        shadowElevation = 1.dp,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SandboxStepStatusIcon(status = activity.status)
            Text(
                text = activity.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onCancel != null) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(22.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.stop),
                        tint = Color(0xFFE09A1B),
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                SandboxStepArrow(
                    enabled = onPrevious != null,
                    onClick = onPrevious,
                    left = true,
                )
                Text(
                    text = activity.stepProgressText(),
                    style = MaterialTheme.typography.labelMedium,
                    color = workspace.muted,
                    maxLines = 1,
                )
                SandboxStepArrow(
                    enabled = onNext != null,
                    onClick = onNext,
                    left = false,
                )
            }
        }
    }
}

@Composable
private fun SandboxStepArrow(
    enabled: Boolean,
    onClick: (() -> Unit)?,
    left: Boolean,
) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled && onClick != null) { onClick?.invoke() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (left) HugeIcons.ArrowLeft01 else HugeIcons.ArrowRight01,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.92f else 0.28f),
        )
    }
}

@Composable
private fun SandboxStepStatusIcon(status: ToolActivityStatus) {
    Surface(
        modifier = Modifier.size(20.dp),
        shape = CircleShape,
        color = sandboxStatusContainerColor(status),
        contentColor = sandboxStatusOnContainerColor(status),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = when (status) {
                    ToolActivityStatus.SUCCEEDED -> HugeIcons.Tick01
                    ToolActivityStatus.FAILED,
                    ToolActivityStatus.CANCELLED -> HugeIcons.Cancel01
                    else -> HugeIcons.Code
                },
                contentDescription = null,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
fun SandboxActivitySheet(
    activity: SandboxActivityUiState,
    onDismiss: () -> Unit,
    onCancel: (() -> Unit)?,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BackHandler { onDismiss() }
        Column(
            modifier = Modifier
                .fillMaxHeight(0.86f)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val previewUrl = activity.operationPreviewUrl()
            SandboxSheetHeader(
                activity = activity,
                isWebPreview = previewUrl != null,
                onCancel = onCancel,
                onPrevious = onPrevious,
                onNext = onNext,
            )

            if (previewUrl != null) {
                SandboxWebActivityContent(
                    activity = activity,
                    url = previewUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                SandboxToolActivityContent(
                    activity = activity,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SandboxSheetHeader(
    activity: SandboxActivityUiState,
    isWebPreview: Boolean,
    onCancel: (() -> Unit)?,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SandboxStepStatusIcon(status = activity.status)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = activity.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val supporting = when {
                    isWebPreview -> activity.operationPreviewUrl()?.webHostPreview().orEmpty()
                    activity.runtime.isNotBlank() -> activity.runtime
                    else -> activity.toolName
                }
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (activity.canCancel && onCancel != null) {
                Surface(
                    onClick = onCancel,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Text(
                        text = "中断",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
            SandboxStepArrow(
                enabled = onPrevious != null,
                onClick = onPrevious,
                left = true,
            )
            Text(
                text = activity.stepProgressText(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            SandboxStepArrow(
                enabled = onNext != null,
                onClick = onNext,
                left = false,
            )
        }
    }
}

@Composable
private fun SandboxToolActivityContent(
    activity: SandboxActivityUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SandboxSheetCodeBlock(
            title = "调用内容",
            language = if (activity.toolName.startsWith("terminal_")) "shell" else "text",
            content = activity.inputPreview.ifBlank { activity.title },
        )
        SandboxSheetCodeBlock(
            title = "调用结果",
            language = "text",
            content = activity.outputTail.ifBlank {
                if (activity.status == ToolActivityStatus.RUNNING || activity.status == ToolActivityStatus.WAITING_FOR_PERMISSION) {
                    "等待工具返回输出..."
                } else {
                    "无输出"
                }
            },
        )
    }
}

@Composable
private fun SandboxWebActivityContent(
    activity: SandboxActivityUiState,
    url: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Text(
                text = url,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OperationWebPreview(
            url = url,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

@Composable
private fun OperationWebPreview(
    url: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val webViewOperationStore: WebViewOperationStore = koinInject()
    val webState by webViewOperationStore.state.collectAsState()
    val isCurrentPreview = webState.requestedUrl == url || webState.url == url
    LaunchedEffect(url, isCurrentPreview) {
        if (!isCurrentPreview) {
            webViewOperationStore.open(url)
        }
    }
    val state = rememberWebViewState(url = url)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        WebView(
            state = state,
            modifier = Modifier.fillMaxSize(),
            onProgressChanged = { webView, progress ->
                webViewOperationStore.updateLoading(webView?.url ?: url, progress)
            },
            onPageStarted = { webView, pageUrl ->
                webViewOperationStore.updateLoading(pageUrl ?: webView?.url ?: url, 1)
            },
            onPageFinished = { webView, pageUrl ->
                val resolvedUrl = pageUrl ?: webView?.url ?: url
                webViewOperationStore.updateLoading(resolvedUrl, 100)
                webView?.let {
                    extractReadablePage(it, webViewOperationStore)
                    captureWebViewThumbnail(it, webViewOperationStore, context)
                }
            },
        )
    }
}

private fun extractReadablePage(
    webView: AndroidWebView,
    store: WebViewOperationStore,
) {
    val script = """
        (function() {
          const links = Array.from(document.querySelectorAll('a[href]')).slice(0, 40).map((a) => {
            let href = '';
            try { href = new URL(a.getAttribute('href'), location.href).href; } catch (_) { href = a.href || ''; }
            const title = (a.innerText || a.textContent || href || '').trim().replace(/\s+/g, ' ').slice(0, 160);
            return { title, url: href };
          }).filter((item) => item.url);
          return JSON.stringify({
            title: document.title || '',
            url: location.href,
            text: ((document.body && document.body.innerText) || '').slice(0, 40000),
            links
          });
        })();
    """.trimIndent()
    webView.post {
        webView.evaluateJavascript(script) { raw ->
            runCatching {
                if (raw.isNullOrBlank() || raw == "null") return@runCatching
                val decoded = JSONArray("[$raw]").getString(0)
                val payload = JSONObject(decoded)
                val linksJson = payload.optJSONArray("links")
                val links = buildList {
                    if (linksJson != null) {
                        for (index in 0 until linksJson.length()) {
                            val item = linksJson.optJSONObject(index) ?: continue
                            val linkUrl = item.optString("url").trim()
                            if (linkUrl.isBlank()) continue
                            add(
                                WebViewLink(
                                    title = item.optString("title").ifBlank { linkUrl },
                                    url = linkUrl,
                                )
                            )
                        }
                    }
                }
                store.updateReadablePage(
                    url = payload.optString("url").ifBlank { webView.url },
                    title = payload.optString("title"),
                    readableText = payload.optString("text"),
                    links = links,
                )
            }.onFailure {
                Log.w("ChatInput", "Failed to extract WebView readable content", it)
            }
        }
    }
}

private fun captureWebViewThumbnail(
    webView: AndroidWebView,
    store: WebViewOperationStore,
    context: android.content.Context,
) {
    webView.postDelayed({
        runCatching {
            val width = webView.width
            val height = webView.height
            if (width <= 0 || height <= 0) return@runCatching

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            webView.draw(canvas)

            val cropWidth = minOf(bitmap.width, bitmap.height * 4 / 3)
            val cropHeight = minOf(bitmap.height, bitmap.width * 3 / 4)
            val cropX = ((bitmap.width - cropWidth) / 2).coerceAtLeast(0)
            val cropped = Bitmap.createBitmap(bitmap, cropX, 0, cropWidth, cropHeight)
            if (cropped !== bitmap) {
                bitmap.recycle()
            }

            val dir = File(context.filesDir, "amberagent/artifacts/webview-thumbnails")
            dir.mkdirs()
            val output = File(dir, "webview-${System.currentTimeMillis()}.png")
            output.outputStream().use { stream ->
                cropped.compress(Bitmap.CompressFormat.PNG, 92, stream)
            }
            cropped.recycle()
            store.updateThumbnail(webView.url, output.absolutePath)
        }.onFailure {
            Log.w("ChatInput", "Failed to capture WebView thumbnail", it)
        }
    }, 500L)
}

@Composable
private fun SandboxSheetCodeBlock(
    title: String,
    language: String,
    content: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = language,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                )
            }
            Text(
                text = content,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun sandboxStatusLabel(status: ToolActivityStatus): String = when (status) {
    ToolActivityStatus.RUNNING -> "执行中"
    ToolActivityStatus.WAITING_FOR_PERMISSION -> "待授权"
    ToolActivityStatus.SUCCEEDED -> "成功"
    ToolActivityStatus.FAILED -> "失败"
    ToolActivityStatus.CANCELLED -> "已取消"
}

private fun sandboxStatusContainerColor(status: ToolActivityStatus): Color = when (status) {
    ToolActivityStatus.RUNNING -> Color(0xFF34C96E)
    ToolActivityStatus.WAITING_FOR_PERMISSION -> Color(0xFFFFC44D)
    ToolActivityStatus.SUCCEEDED -> Color(0xFF34C96E)
    ToolActivityStatus.FAILED -> Color(0xFFE45A5A)
    ToolActivityStatus.CANCELLED -> Color(0xFFB8C0CC)
}

private fun sandboxStatusOnContainerColor(status: ToolActivityStatus): Color = when (status) {
    ToolActivityStatus.WAITING_FOR_PERMISSION,
    ToolActivityStatus.CANCELLED -> Color(0xFF1B1C20)
    else -> Color.White
}

private fun SandboxActivityUiState.operationPreviewKind(): String = when {
    toolName == "search_web" -> "web search"
    toolName == "scrape_web" || toolName == "webview_open" || toolName == "webview_read" -> "webview"
    toolName.startsWith("icloud_") -> "icloud"
    toolName.startsWith("screen_") || toolName == "vlm_task" -> "screen"
    toolName.startsWith("file_") -> "workspace"
    toolName.startsWith("terminal_") -> "runtime"
    toolName.startsWith("mcp__") -> "mcp"
    else -> toolName
}

private fun SandboxActivityUiState.operationPreviewText(): String {
    val previewUrl = operationPreviewUrl()
    if (previewUrl != null) {
        return buildString {
            append(previewUrl.webHostPreview().compactForSandbox(30))
            append('\n')
            append(inputPreview.ifBlank { title }.compactForSandbox(42))
            append('\n')
            append(sandboxStatusLabel(status))
        }
    }

    val command = inputPreview.ifBlank { title }
    val tail = outputTail.trim()
    return buildString {
        append("• ")
        append(command.compactForSandbox(28))
        append('\n')
        if (tail.isNotBlank()) {
            append(tail.lines().takeLast(3).joinToString("\n").compactForSandbox(96))
        } else {
            append(runtime.ifBlank { sandboxStatusLabel(status) }.compactForSandbox(36))
        }
    }
}

private fun SandboxActivityUiState.operationPreviewUrl(): String? {
    if (toolName != "search_web" && toolName != "scrape_web" && toolName != "webview_open" && toolName != "webview_read") {
        return null
    }

    val directInputUrl = inputPreview.firstHttpUrl()
    if (directInputUrl != null) return directInputUrl

    val outputUrl = outputTail.firstHttpUrl()
    if (outputUrl != null) return outputUrl

    if (toolName == "search_web" && inputPreview.isNotBlank()) {
        return "https://www.google.com/search?q=${URLEncoder.encode(inputPreview, "UTF-8")}"
    }

    return null
}

private fun SandboxActivityUiState.stepProgressText(): String {
    val current = stepIndex
    val total = stepTotal
    return if (current != null && total != null) {
        "$current/$total"
    } else {
        sandboxStatusLabel(status)
    }
}

private fun SandboxActivityUiState.terminalTranscript(): String = buildString {
    append("$ ")
    append(inputPreview.ifBlank { title })
    append('\n')
    if (runtime.isNotBlank()) {
        append("正在调用内嵌 ")
        append(runtime)
        append(" 执行工具")
        append('\n')
    }
    if (workspace.isNotBlank()) {
        append("workspace: ")
        append(workspace)
        append('\n')
    }
    append("status: ")
    append(sandboxStatusLabel(status))
    append('\n')
    if (outputTail.isNotBlank()) {
        append('\n')
        append(outputTail)
    } else if (status == ToolActivityStatus.RUNNING || status == ToolActivityStatus.WAITING_FOR_PERMISSION) {
        append('\n')
        append("等待工具返回输出...")
    }
}

private val HTTP_URL_REGEX = Regex("https?://[^\\s\"'<>),]+")
private const val MAX_SLASH_COMMANDS = 6
private const val MAX_SLASH_COMMAND_TITLE_CHARS = 32
private val ComposerButtonSize = 44.dp
private val ComposerButtonIconSize = 28.dp
private val ComposerModelGroupHeight = 48.dp

private fun String.firstHttpUrl(): String? =
    HTTP_URL_REGEX.find(this)?.value?.trimEnd('.', ',', ';', ')')

private fun String.webHostPreview(): String =
    runCatching { Uri.parse(this).host?.removePrefix("www.") }.getOrNull() ?: this

private fun String.compactForSandbox(maxLength: Int): String {
    val compact = trim().replace(Regex("\\s+"), " ")
    return if (compact.length > maxLength) compact.take(maxLength - 1) + "…" else compact
}

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

@Composable
private fun TextInputRow(
    state: ChatInputState,
    onSendMessage: () -> Unit,
    onUsageClick: () -> Unit,
) {
    val settings = LocalSettings.current
    val filesManager: FilesManager = koinInject()
    val assistant = settings.getCurrentAssistant()
    val workspace = workspaceColors()
    val quickMessages = remember(settings.quickMessages, assistant.quickMessageIds) {
        settings.getQuickMessagesOfAssistant(assistant)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (state.isEditing()) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = workspace.blueContainer,
                contentColor = workspace.blue,
                border = BorderStroke(1.dp, workspace.blue.copy(alpha = 0.16f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = stringResource(R.string.editing))
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.cancel_edit),
                        modifier = Modifier.clickable { state.clearInput() }
                    )
                }
            }
        }

        var isFocused by remember { mutableStateOf(false) }
        var isFullScreen by remember { mutableStateOf(false) }
        val slashQuery = state.textContent.text.toString().slashCommandQuery()
        val showUsageCommand = slashQuery?.let { query ->
            query.isNotBlank() && (
                "usage".startsWith(query, ignoreCase = true) ||
                "用量".startsWith(query, ignoreCase = true)
                )
        } == true
        val slashCommands = remember(quickMessages, slashQuery) {
            slashQuery?.let { query ->
                quickMessages.filter { quickMessage ->
                    query.isBlank() ||
                        quickMessage.title.contains(query, ignoreCase = true) ||
                        quickMessage.content.contains(query, ignoreCase = true)
                }
            }.orEmpty()
        }
        val receiveContentListener = remember(
            settings.displaySetting.pasteLongTextAsFile, settings.displaySetting.pasteLongTextThreshold
        ) {
            ReceiveContentListener { transferableContent ->
                when {
                    transferableContent.hasMediaType(MediaType.Image) -> {
                        transferableContent.consume { item ->
                            val uri = item.uri
                            if (uri != null) {
                                state.addImages(
                                    filesManager.createChatFilesByContents(
                                        listOf(uri)
                                    )
                                )
                            }
                            uri != null
                        }
                    }

                    settings.displaySetting.pasteLongTextAsFile && transferableContent.hasMediaType(MediaType.Text) -> {
                        transferableContent.consume { item ->
                            val text = item.text?.toString()
                            if (text != null && text.length > settings.displaySetting.pasteLongTextThreshold) {
                                val document = filesManager.createChatTextFile(text)
                                state.addFiles(listOf(document))
                                true
                            } else {
                                false
                            }
                        }
                    }

                    else -> transferableContent
                }
            }
        }
        if (isFocused && slashQuery != null) {
            SlashCommandPanel(
                quickMessages = slashCommands,
                hasAnyCommand = quickMessages.isNotEmpty() || showUsageCommand,
                showUsageCommand = showUsageCommand,
                onUsageClick = {
                    state.setMessageText("")
                    onUsageClick()
                },
                onSelect = { quickMessage ->
                    state.setMessageText(quickMessage.content)
                },
            )
        }
        TextField(
            state = state.textContent,
            modifier = Modifier
                .fillMaxWidth()
                .contentReceiver(receiveContentListener)
                .onFocusChanged {
                    isFocused = it.isFocused
                },
            shape = RoundedCornerShape(8.dp),
            placeholder = {
                Text(
                    text = stringResource(R.string.chat_input_placeholder),
                    color = workspace.faint,
                )
            },
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
            keyboardOptions = KeyboardOptions(
                imeAction = if (settings.displaySetting.sendOnEnter) ImeAction.Send else ImeAction.Default
            ),
            onKeyboardAction = {
                if (settings.displaySetting.sendOnEnter && !state.isEmpty()) {
                    onSendMessage()
                }
            },
            colors = TextFieldDefaults.colors().copy(
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                focusedContainerColor = workspace.paper,
                unfocusedContainerColor = workspace.paper,
                focusedTextColor = workspace.ink,
                unfocusedTextColor = workspace.ink,
                focusedPlaceholderColor = workspace.faint,
                unfocusedPlaceholderColor = workspace.faint,
            ),
            trailingIcon = if (isFocused) {
                {
                    IconButton(
                        onClick = {
                            isFullScreen = !isFullScreen
                        },
                    ) {
                        Icon(HugeIcons.FullScreen, null)
                    }
                }
            } else {
                null
            },
            leadingIcon = if (quickMessages.isNotEmpty()) {
                {
                    QuickMessageButton(quickMessages = quickMessages, state = state)
                }
            } else {
                {
                    Surface(
                        modifier = Modifier.size(24.dp),
                        shape = RoundedCornerShape(5.dp),
                        color = workspace.blueContainer,
                        contentColor = workspace.blue,
                        border = BorderStroke(1.dp, workspace.hairline),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "/",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            },
        )
        if (isFullScreen) {
            FullScreenEditor(state = state) {
                isFullScreen = false
            }
        }
    }
}

@Composable
private fun SlashCommandPanel(
    quickMessages: List<QuickMessage>,
    hasAnyCommand: Boolean,
    showUsageCommand: Boolean,
    onUsageClick: () -> Unit,
    onSelect: (QuickMessage) -> Unit,
) {
    val workspace = workspaceColors()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        color = workspace.paper,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            when {
                !hasAnyCommand -> SlashCommandEmptyRow(
                    text = stringResource(R.string.chat_input_slash_command_empty)
                )

                quickMessages.isEmpty() && !showUsageCommand -> SlashCommandEmptyRow(
                    text = stringResource(R.string.chat_input_slash_command_no_match)
                )

                else -> {
                    if (showUsageCommand) {
                        SlashUsageCommandRow(onClick = onUsageClick)
                        if (quickMessages.isNotEmpty()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 58.dp),
                                color = workspace.hairline,
                            )
                        }
                    }
                    quickMessages.take(MAX_SLASH_COMMANDS).forEachIndexed { index, quickMessage ->
                        SlashCommandRow(
                            quickMessage = quickMessage,
                            onClick = { onSelect(quickMessage) },
                        )
                        if (index < quickMessages.take(MAX_SLASH_COMMANDS).lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 58.dp),
                                color = workspace.hairline,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlashUsageCommandRow(
    onClick: () -> Unit,
) {
    val workspace = workspaceColors()
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.size(30.dp),
                shape = RoundedCornerShape(6.dp),
                color = workspace.blueContainer,
                contentColor = workspace.blue,
                border = BorderStroke(1.dp, workspace.blue.copy(alpha = 0.14f)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = HugeIcons.ChartColumn,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "/usage",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "5h / weekly / cache",
                    style = MaterialTheme.typography.bodySmall,
                    color = workspace.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SlashCommandRow(
    quickMessage: QuickMessage,
    onClick: () -> Unit,
) {
    val workspace = workspaceColors()
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.size(30.dp),
                shape = RoundedCornerShape(6.dp),
                color = workspace.row,
                contentColor = workspace.muted,
                border = BorderStroke(1.dp, workspace.hairline),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "/",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                val fallbackTitle = stringResource(R.string.extension_content_unnamed)
                Text(
                    text = "/${quickMessage.slashTitle(fallbackTitle)}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = quickMessage.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = workspace.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SlashCommandEmptyRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

@Composable
private fun QuickMessageButton(
    quickMessages: List<QuickMessage>,
    state: ChatInputState,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(
        onClick = {
            expanded = !expanded
        }) {
        Icon(HugeIcons.Zap, null)
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .widthIn(min = 200.dp)
                .width(IntrinsicSize.Min)
        ) {
            quickMessages.forEach { quickMessage ->
                Surface(
                    onClick = {
                        state.appendText(quickMessage.content)
                        expanded = false
                    },
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            text = quickMessage.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = quickMessage.content,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun String.slashCommandQuery(): String? {
    if (!startsWith("/")) return null
    val query = drop(1)
    return query.takeIf { it.none { char -> char.isWhitespace() } }
}

private fun QuickMessage.slashTitle(fallback: String): String =
    title.ifBlank { content.lineSequence().firstOrNull().orEmpty() }
        .trim()
        .replace(Regex("\\s+"), "-")
        .take(MAX_SLASH_COMMAND_TITLE_CHARS)
        .ifBlank { fallback }

@Composable
private fun MediaFileInputRow(
    state: ChatInputState,
) {
    val filesManager: FilesManager = koinInject()
    val managedFiles by filesManager.observe().collectAsState(initial = emptyList())
    val displayNameByRelativePath = remember(managedFiles) {
        managedFiles.associate { it.relativePath to it.displayName }
    }
    val displayNameByFileName = remember(managedFiles) {
        managedFiles.associate { it.relativePath.substringAfterLast('/') to it.displayName }
    }

    fun removePart(part: UIMessagePart, url: String) {
        state.messageContent = state.messageContent.filterNot { it == part }
        if (state.shouldDeleteFileOnRemove(part)) {
            filesManager.deleteChatFiles(listOf(url.toUri()))
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        state.messageContent.fastForEach { part ->
            when (part) {
                is UIMessagePart.Image -> {
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = "image",
                            displayNameByRelativePath = displayNameByRelativePath,
                            displayNameByFileName = displayNameByFileName
                        ),
                        leading = {
                            Surface(
                                modifier = Modifier.size(34.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                AsyncImage(
                                    model = part.url,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        },
                        onRemove = { removePart(part, part.url) }
                    )
                }

                is UIMessagePart.Video -> {
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = "video",
                            displayNameByRelativePath = displayNameByRelativePath,
                            displayNameByFileName = displayNameByFileName
                        ),
                        leading = { AttachmentLeadingIcon(icon = HugeIcons.Video01) },
                        onRemove = { removePart(part, part.url) }
                    )
                }

                is UIMessagePart.Audio -> {
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = "audio",
                            displayNameByRelativePath = displayNameByRelativePath,
                            displayNameByFileName = displayNameByFileName
                        ),
                        leading = { AttachmentLeadingIcon(icon = HugeIcons.MusicNote03) },
                        onRemove = { removePart(part, part.url) }
                    )
                }

                is UIMessagePart.Document -> {
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = part.fileName,
                            displayNameByRelativePath = displayNameByRelativePath,
                            displayNameByFileName = displayNameByFileName
                        ),
                        leading = { AttachmentLeadingIcon(icon = HugeIcons.Files02) },
                        onRemove = { removePart(part, part.url) }
                    )
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun AttachmentChip(
    title: String,
    leading: @Composable () -> Unit,
    onRemove: () -> Unit,
) {
    val workspace = workspaceColors()
    Surface(
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        color = workspace.paper,
        contentColor = workspace.ink,
        border = BorderStroke(1.dp, workspace.hairline)
    ) {
        Row(
            modifier = Modifier
                .height(44.dp)
                .padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            leading()
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.widthIn(min = 40.dp, max = 180.dp),
            )
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .size(26.dp)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = null,
                    tint = workspace.muted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun AttachmentLeadingIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    val workspace = workspaceColors()
    Surface(
        modifier = Modifier.size(32.dp),
        shape = RoundedCornerShape(6.dp),
        color = workspace.row,
        contentColor = workspace.muted,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = workspace.muted
            )
        }
    }
}

private fun attachmentNameFromUrl(
    url: String,
    fallback: String,
    displayNameByRelativePath: Map<String, String>,
    displayNameByFileName: Map<String, String>,
): String {
    val parsed = runCatching { url.toUri() }.getOrNull()
    val relativePath = parsed?.path?.substringAfter("/files/", missingDelimiterValue = "")?.takeIf { it.isNotBlank() }
    if (relativePath != null) {
        displayNameByRelativePath[relativePath]?.let { return it }
    }

    val storedFileName = parsed?.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    if (storedFileName != null) {
        displayNameByFileName[storedFileName]?.let { return it }
        return storedFileName
    }

    return fallback
}

@Composable
private fun FilesPicker(
    onTakePic: () -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickAudio: () -> Unit,
    onPickFile: () -> Unit,
) {
    val settings = LocalSettings.current
    val provider = settings.getCurrentChatModel()?.findProvider(providers = settings.providers)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TakePicButton(onLaunchCamera = onTakePic)

            ImagePickButton(onClick = onPickImage)

            if (provider != null && provider is ProviderSetting.Google) {
                VideoPickButton(onClick = onPickVideo)

                AudioPickButton(onClick = onPickAudio)
            }

            FilePickButton(onClick = onPickFile)
        }
    }
}

@Composable
private fun FullScreenEditor(
    state: ChatInputState, onDone: () -> Unit
) {
    BasicAlertDialog(
        onDismissRequest = {
            onDone()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding(),
            verticalArrangement = Arrangement.Bottom
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 800.dp)
                    .fillMaxHeight(0.9f),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxSize(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row {
                        TextButton(
                            onClick = {
                                onDone()
                            }) {
                            Text(stringResource(R.string.chat_page_save))
                        }
                    }
                    TextField(
                        state = state.textContent,
                        modifier = Modifier
                            .padding(bottom = 2.dp)
                            .fillMaxSize(),
                        shape = RoundedCornerShape(32.dp),
                        placeholder = {
                            Text(stringResource(R.string.chat_input_placeholder))
                        },
                        colors = TextFieldDefaults.colors().copy(
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun useCropLauncher(
    onCroppedImageReady: (Uri) -> Unit, onCleanup: (() -> Unit)? = null
): Pair<ActivityResultLauncher<Intent>, (Uri) -> Unit> {
    val context = LocalContext.current
    var cropOutputUri by remember { mutableStateOf<Uri?>(null) }

    val cropActivityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            cropOutputUri?.let { croppedUri ->
                onCroppedImageReady(croppedUri)
            }
        }
        // Clean up crop output file
        cropOutputUri?.toFile()?.delete()
        cropOutputUri = null
        onCleanup?.invoke()
    }

    val launchCrop: (Uri) -> Unit = { sourceUri ->
        val outputFile = File(context.appTempFolder, "crop_output_${System.currentTimeMillis()}.jpg")
        cropOutputUri = Uri.fromFile(outputFile)

        val cropIntent = UCrop.of(sourceUri, cropOutputUri!!).withOptions(UCrop.Options().apply {
            setFreeStyleCropEnabled(true)
            setAllowedGestures(
                UCropActivity.SCALE, UCropActivity.ROTATE, UCropActivity.NONE
            )
            setCompressionFormat(Bitmap.CompressFormat.PNG)
        }).withMaxResultSize(4096, 4096).getIntent(context)

        cropActivityLauncher.launch(cropIntent)
    }

    return Pair(cropActivityLauncher, launchCrop)
}

@Composable
private fun ImagePickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Image02, null)
    }, text = {
        Text(stringResource(R.string.photo))
    }) {
        onClick()
    }
}

@Composable
fun TakePicButton(onLaunchCamera: () -> Unit = {}) {
    val cameraPermission = rememberPermissionState(PermissionCamera)

    // 使用权限管理器包装
    PermissionManager(
        permissionState = cameraPermission
    ) {
        BigIconTextButton(icon = {
            Icon(HugeIcons.Camera01, null)
        }, text = {
            Text(stringResource(R.string.take_picture))
        }) {
            if (cameraPermission.allRequiredPermissionsGranted) {
                onLaunchCamera()
            } else {
                // 请求权限
                cameraPermission.requestPermissions()
            }
        }
    }
}

@Composable
fun VideoPickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Video01, null)
    }, text = {
        Text(stringResource(R.string.video))
    }) {
        onClick()
    }
}

@Composable
fun AudioPickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.MusicNote03, null)
    }, text = {
        Text(stringResource(R.string.audio))
    }) {
        onClick()
    }
}

@Composable
fun FilePickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Files02, null)
    }, text = {
        Text(stringResource(R.string.upload_file))
    }) {
        onClick()
    }
}


@Composable
private fun BigIconTextButton(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    text: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick
            )
            .semantics {
                role = Role.Button
            }
            .wrapContentWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Surface(
            tonalElevation = 2.dp, shape = RoundedCornerShape(8.dp)
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp)
            ) {
                icon()
            }
        }
        ProvideTextStyle(MaterialTheme.typography.bodySmall) {
            text()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BigIconTextButtonPreview() {
    Row(
        modifier = Modifier.padding(16.dp)
    ) {
        BigIconTextButton(icon = {
            Icon(HugeIcons.Image02, null)
        }, text = {
            Text(stringResource(R.string.photo))
        }) {}
    }
}
