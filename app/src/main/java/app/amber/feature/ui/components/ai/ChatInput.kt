package app.amber.feature.ui.components.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.ui.UIMessagePart
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.Files
import com.composables.icons.lucide.Image
import app.amber.agent.R
import app.amber.feature.runtime.SandboxActivityUiState
import app.amber.core.ai.vision.ImageAttachmentStrings
import app.amber.core.ai.vision.ImageAttachmentValidator
import app.amber.core.context.CompactLifecycleState
import app.amber.core.settings.Settings
import app.amber.core.settings.findProvider
import app.amber.core.settings.findModelById
import app.amber.core.files.FilesManager
import app.amber.core.model.Conversation
import app.amber.core.service.PendingUserMessageMode
import app.amber.feature.ui.components.ui.KeepScreenOn
import app.amber.feature.ui.components.ui.permission.PermissionCamera
import app.amber.feature.ui.components.ui.permission.PermissionManager
import app.amber.feature.ui.components.ui.permission.rememberPermissionState
import app.amber.feature.ui.components.webmount.WebMountTaskCard
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.hooks.ChatInputAttachmentImportStatus
import app.amber.feature.ui.hooks.ChatInputState
import app.amber.feature.webmount.primitives.WebMountSessionMetadata
import app.amber.core.utils.ChatSendTransitionTracker
import org.koin.compose.koinInject
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
internal fun ChatInput(
    state: ChatInputState,
    submissionOwner: ChatInputSubmissionOwner,
    loading: Boolean,
    conversation: Conversation,
    contextCompacts: List<app.amber.core.context.ConversationCompact> = emptyList(),
    compactLifecycleState: CompactLifecycleState = CompactLifecycleState.idle(),
    pendingQueueCount: Int = 0,
    settings: Settings,
    timelineScrolling: Boolean = false,
    enableSearch: Boolean,
    onToggleSearch: (Boolean) -> Unit,
    sandboxActivity: SandboxActivityUiState? = null,
    suggestionFillPulseKey: Int = 0,
    onOpenSandbox: () -> Unit = {},
    onCancelSandbox: (() -> Unit)? = null,
    onPreviousSandbox: (() -> Unit)? = null,
    onNextSandbox: (() -> Unit)? = null,
    webMountSessions: List<WebMountSessionMetadata> = emptyList(),
    webMountActivity: String? = null,
    onDismissWebMount: () -> Unit = {},
    onOpenWebMountSession: (sessionId: String, reopen: Boolean) -> Unit = { _, _ -> },
    aboveComposerContent: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    onUpdateChatModel: (Model) -> Unit,
    onUpdateSettings: (Settings) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    onCancelClick: () -> Unit,
    onSendClick: (PendingUserMessageMode, List<UIMessagePart>, Uuid?) -> Boolean,
    onLongSendClick: (PendingUserMessageMode, List<UIMessagePart>, Uuid?) -> Boolean,
    onOpenQueue: () -> Unit = {},
    onCompactContext: () -> Unit = {},
) {
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val attachmentReadFailureMessage =
        stringResource(R.string.parity_attachment_read_failed_detail)
    val providerCatalog = koinInject<ProviderCatalog>()
    val coroutineScope = rememberCoroutineScope()
    val workspace = workspaceColors()
    val attachmentStrings = remember(context) { ImageAttachmentStrings.from(context) }
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
            if (!state.isEmpty() || state.isEditing() || state.hasUnresolvedAttachmentImports()) return@launch
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }

    fun showUnresolvedAttachmentImport(): Boolean {
        val unresolved = state.attachmentImports.firstOrNull { it.status != ChatInputAttachmentImportStatus.READY }
            ?: return false
        val messageRes = if (unresolved.status == ChatInputAttachmentImportStatus.FAILED) {
            R.string.parity_attachment_import_failed_short
        } else {
            R.string.parity_attachment_importing
        }
        toaster.show(context.getString(messageRes), type = ToastType.Error)
        return true
    }

    fun prepareSend(onSubmit: (List<UIMessagePart>, Uuid?) -> Boolean) {
        ChatSendTransitionTracker.start(
            conversationId = conversation.id.toString(),
            preSendLatestMessageId = conversation.currentMessages.lastOrNull()?.id?.toString(),
        )
        // Capture the clicked draft before this callback returns, then allow validation to suspend.
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val accepted = submissionOwner.submit(
                conversationId = conversation.id.toString(),
                validate = { parts ->
                    val blockingIssue = withContext(Dispatchers.IO) {
                        ImageAttachmentValidator.firstBlockingIssueForSend(
                            parts = parts,
                            settings = settings,
                            providerCatalog = providerCatalog,
                            strings = attachmentStrings,
                        )
                    }
                    if (blockingIssue != null) {
                        toaster.show(blockingIssue.message, type = ToastType.Error)
                    }
                    blockingIssue == null
                },
                send = onSubmit,
            )
            if (accepted) hideKeyboardAfterSend()
        }
    }

    fun sendMessage() {
        if (loading && state.isEmpty()) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            onCancelClick()
        } else if (showUnresolvedAttachmentImport()) {
            return
        } else {
            prepareSend { parts, editingMessage ->
                onSendClick(PendingUserMessageMode.FOLLOWUP, parts, editingMessage)
            }
        }
    }

    fun sendMessageWithoutAnswer() {
        if (loading && state.isEmpty()) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            onCancelClick()
        } else if (showUnresolvedAttachmentImport()) {
            return
        } else {
            val queueMode = if (loading) PendingUserMessageMode.STEER else PendingUserMessageMode.FOLLOWUP
            prepareSend { parts, editingMessage ->
                onLongSendClick(queueMode, parts, editingMessage)
            }
        }
    }

    var showUsageSheet by remember { mutableStateOf(false) }
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

    val filesManager: FilesManager = koinInject()
    val scope = rememberCoroutineScope()
    val conversationId = conversation.id.toString()

    LaunchedEffect(conversationId) {
        state.bindToConversation(conversationId).takeIf { it.isNotEmpty() }?.let {
            filesManager.deleteChatFiles(it)
        }
    }

    val selectedChatModelId = settings.chatModelId
    val chatModel = remember(settings.providers, selectedChatModelId) {
        settings.providers.findModelById(selectedChatModelId)
    }
    val chatProvider = remember(settings.providers, chatModel) {
        chatModel?.findProvider(settings.providers)
    }

    val importer = remember(state, filesManager, context, scope, attachmentReadFailureMessage, toaster) {
        ChatAttachmentImporter(
            state = state,
            filesManager = filesManager,
            context = context,
            scope = scope,
            attachmentReadFailureMessage = attachmentReadFailureMessage,
            showError = { toaster.show(it, type = ToastType.Error) },
        )
    }
    val attachmentLaunchers = rememberChatAttachmentLaunchers(
        conversationId = conversationId,
        skipCropImage = settings.displaySetting.skipCropImage,
        importer = importer,
        onImported = ::dismissExpand,
    )

    // Collapse when ime is visible
    LaunchedEffect(imeVisible, showUsageSheet) {
        if (imeVisible && !showUsageSheet) {
            dismissExpand()
        }
    }

    if (showUsageSheet) {
        // Constructing the OAuth/usage clients eagerly loaded and instantiated
        // network helper classes on every chat entry even when the sheet was
        // never opened.
        val usage = rememberComposerUsageLoader()
        LaunchedEffect(showUsageSheet, chatProvider.providerRoutingKey()) {
            usage.refresh(chatProvider, chatModel)
        }
        ComposerUsageSheet(
            status = usage.status,
            loading = usage.loading,
            error = usage.error,
            onRefresh = {
                scope.launch {
                    usage.refresh(chatProvider, chatModel)
                }
            },
            onDismissRequest = { showUsageSheet = false },
        )
    }

    val tokens = LocalAmberTokens.current
    // Continue the conversation paper under the composer and navigation inset, with
    // the same fine accent rule used below the chat header.
    Column(modifier = modifier.imePadding()) {
        aboveComposerContent()
        Surface(
            color = tokens.bg,
            modifier = Modifier.drawWithContent {
                drawContent()
                drawLine(
                    color = tokens.accent.copy(alpha = 0.10f),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
            },
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    // breathing room below the gesture/nav inset
                    .padding(bottom = 6.dp)
                    .padding(horizontal = 16.dp)
                    .padding(top = 10.dp),
                // spacedBy 控制 SandboxPeekBar 与 composer pill 之间的间距。
                // 设计稿是预览卡紧贴输入框，2dp 足够留一条 hair 缝
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (webMountSessions.isNotEmpty()) {
                    WebMountTaskCard(
                        sessions = webMountSessions,
                        currentActivity = webMountActivity,
                        onDismiss = onDismissWebMount,
                        onOpenSession = onOpenWebMountSession,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
                        .heightIn(min = 44.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    // ── attach chip — `surface-2` circle that morphs into the [× · image · file]
                    //    capsule (animated width via animateContentSize; + rotates to ×).
                    Row(
                        modifier = Modifier
                            .height(44.dp)
                            .clip(CircleShape)
                            .background(tokens.surface2)
                            .border(BorderStroke(1.dp, tokens.line), CircleShape)
                            .animateContentSize(animationSpec = tween(220, easing = FastOutSlowInEasing)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .clickable { expandToggle(ExpandState.Files) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Lucide.Plus,
                                contentDescription = stringResource(R.string.more_options),
                                // Collapsed attachment entry stays neutral; the open state uses the
                                // active accent to make the expanded affordance easy to scan.
                                tint = if (attachmentsExpanded) chatTheme.accent else tokens.ink2,
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
                                onTakePic = attachmentLaunchers.takePicture,
                                onPickImage = attachmentLaunchers.pickImages,
                                onPickFile = attachmentLaunchers.pickFiles,
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                    }

                    // ── center input pill — `surface-2`, 26dp radius, 1dp hairline, flat.
                    val pillShape = RoundedCornerShape(26.dp)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .clip(pillShape)
                            .background(tokens.surface2)
                            .border(BorderStroke(1.dp, pillBorder), pillShape)
                            // 26dp 大圆角下，文字左内距给足 18dp 才不贴边；右侧留 16dp 对称
                            .padding(start = 18.dp, end = 16.dp),
                        // 文字在药丸内垂直居中（TextField 取自然高度，多出的空隙上下均分）
                        verticalAlignment = Alignment.CenterVertically,
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
                                onUpdateSettings = onUpdateSettings,
                                onImportAttachment = { uri, kind ->
                                    importer.importAttachment(
                                        ownerConversationId = conversationId,
                                        sourceUri = uri,
                                        kind = kind,
                                    )
                                },
                                onImportTextFile = { text ->
                                    importer.importPastedTextFile(
                                        ownerConversationId = conversationId,
                                        text = text,
                                    )
                                },
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
                    val sendStopState = loading && sendEmpty
                    val hasUnresolvedAttachments = state.hasUnresolvedAttachmentImports()
                    val sendEnabled = sendStopState ||
                        (!hasUnresolvedAttachments && composerSendEnabled(sendEmpty, loading))
                    val sendFill by animateColorAsState(
                        targetValue = if (!sendEnabled || (sendEmpty && !loading)) {
                            tokens.surface2
                        } else {
                            tokens.accent
                        },
                        label = "sendButtonFill",
                    )
                    val sendIconTint by animateColorAsState(
                        // 有效发送键使用主题为当前 accent 计算的前景色，确保 terracotta 等
                        // accent 仍保持原稿的 AA 对比；空态仍用中性 ink3。
                        targetValue = if (!sendEnabled || (sendEmpty && !loading)) tokens.ink3 else tokens.accentInk,
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
                            .size(ComposerButtonSize)
                            .minimumInteractiveComponentSize()
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
                        Box(
                            modifier = Modifier
                                .graphicsLayer {
                                    scaleX = sendScale
                                    scaleY = sendScale
                                }
                                .size(ComposerButtonSize)
                                .clip(CircleShape)
                                .background(sendFill),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (sendStopState) Lucide.X else Lucide.ArrowUp,
                                contentDescription = stringResource(
                                    if (sendStopState) R.string.stop else R.string.send,
                                ),
                                tint = sendIconTint,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }

                if (state.messageContent.isNotEmpty() || state.attachmentImports.isNotEmpty()) {
                    MediaFileInputRow(
                        state = state,
                        onRetryAttachment = { importer.retryAttachmentImport(conversationId, it) },
                    )
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
                icon = Lucide.Camera,
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
            icon = Lucide.Image,
            contentDescription = stringResource(R.string.photo),
            onClick = onPickImage,
        )
        InlineAttachmentIcon(
            icon = Lucide.Files,
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
            .size(ComposerButtonSize)
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
