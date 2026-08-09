package app.amber.feature.ui.pages.novel

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import app.amber.agent.Screen
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.novel.NovelGenerationGranularityRequest
import app.amber.feature.novel.NovelSessionModeRequest
import app.amber.feature.novel.domain.NovelCharacterEventMatcher
import app.amber.feature.novel.domain.NovelParagraphSelection
import app.amber.feature.novel.model.NovelBranchSyncStatus
import app.amber.feature.novel.model.NovelCandidateKind
import app.amber.feature.novel.model.NovelCandidateStatus
import app.amber.feature.novel.model.NovelChapterId
import app.amber.feature.novel.model.NovelChapterVersionId
import app.amber.feature.novel.model.NovelChapterVersionKind
import app.amber.feature.novel.model.NovelChapterVersionRecord
import app.amber.feature.novel.model.NovelMaterialId
import app.amber.feature.novel.model.NovelMaterialKind
import app.amber.feature.novel.model.NovelProjectLoadAccess
import app.amber.feature.novel.model.NovelSessionMessageKind
import app.amber.feature.novel.model.NovelSessionRole
import app.amber.feature.novel.runtime.NovelDiscussionAskParser
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.BlinkingCursor
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.components.message.AskUserToolStep
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.components.ui.ChainOfThought
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.workspaceBorder
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.theme.CustomColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.feature.ui.theme.LocalDarkMode
import app.amber.core.utils.NotificationUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.BubbleChat
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.QuillWrite01
import me.rerere.hugeicons.stroke.Settings03
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelWorkspacePage(
    projectId: String,
    viewModel: NovelWorkspaceViewModel = koinViewModel(parameters = { parametersOf(projectId) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val document = state.document
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val navController = LocalNavController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val fontScale = LocalDensity.current.fontScale
    var showCreationControl by remember { mutableStateOf(false) }
    var notificationPermissionDenied by remember { mutableStateOf(false) }
    var pendingNotificationAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val notificationAvailable = granted && NotificationUtil.canShowNotification(
            context,
            CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
        )
        notificationPermissionDenied = !notificationAvailable
        val action = pendingNotificationAction
        pendingNotificationAction = null
        if (notificationAvailable) action?.invoke()
    }
    val runWithNotificationPermission: (() -> Unit) -> Unit = { action ->
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingNotificationAction = action
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            val notificationAvailable = NotificationUtil.canShowNotification(
                context,
                CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            )
            notificationPermissionDenied = !notificationAvailable
            if (notificationAvailable) action()
        }
    }
    val startGhostwriteWithPermission = {
        runWithNotificationPermission(viewModel::startGhostwrite)
    }
    val openNotificationSettings: () -> Unit = {
        val notificationSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        runCatching { context.startActivity(notificationSettings) }
            .onFailure {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            }
        Unit
    }

    // Settings (and other stacks) write through NovelCreation; refresh when returning.
    // Use fromResume so an already-open workspace does not full-screen reload mid-generation.
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationPermissionDenied = !NotificationUtil.canShowNotification(
                    context,
                    CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
                )
                viewModel.refresh(fromResume = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Immersive bottom: exclude nav bar from Scaffold insets so the composer
    // tray (surface) can paint under the system bar; content pads itself.
    Scaffold(
        containerColor = workspace.canvas,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
        ),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            document?.project?.name ?: "小说项目",
                            fontWeight = FontWeight.Bold,
                            color = workspace.ink,
                            maxLines = 1,
                        )
                        if (fontScale < 1.3f) {
                            Text(
                                "让对话落进正文，让资料随故事生长",
                                style = type.meta,
                                color = workspace.muted,
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
                actions = {
                    NovelIconButton(
                        icon = HugeIcons.QuillWrite01,
                        contentDescription = "创作控制",
                        onClick = { showCreationControl = true },
                        enabled = document != null,
                        tint = workspace.ink,
                    )
                    NovelIconButton(
                        icon = HugeIcons.Settings03,
                        contentDescription = "小说设置",
                        onClick = {
                            navController.navigate(Screen.NovelSettings(projectId))
                        },
                        tint = workspace.ink,
                    )
                },
            )
        },
    ) { padding ->
        val phase = when {
            state.loading && document == null -> WorkspacePhase.Loading
            document == null -> WorkspacePhase.Unavailable
            else -> WorkspacePhase.Ready
        }
        AnimatedContent(
            targetState = phase,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            transitionSpec = { NovelMotion.fadeScale() },
            label = "novelWorkspacePhase",
        ) { targetPhase ->
            when (targetPhase) {
                WorkspacePhase.Loading -> {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .navigationBarsPadding(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("加载中…", style = type.secondary, color = workspace.muted)
                    }
                }
                WorkspacePhase.Unavailable -> {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .navigationBarsPadding(),
                        contentAlignment = Alignment.Center,
                    ) {
                        NovelEmptyState(
                            title = "项目不可用",
                            subtitle = state.errorMessage ?: "无法加载此项目",
                        )
                    }
                }
                WorkspacePhase.Ready -> {
                    val readyDoc = state.document
                    if (readyDoc == null) {
                        Box(Modifier.fillMaxSize())
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            AnimatedVisibility(
                                visible = state.access == NovelProjectLoadAccess.DegradedPrevious,
                                enter = fadeIn(tween(NovelMotion.MediumMs)) +
                                    expandVertically(tween(NovelMotion.MediumMs)),
                                exit = fadeOut(tween(NovelMotion.FastMs)) +
                                    shrinkVertically(tween(NovelMotion.FastMs)),
                            ) {
                                NovelBanner(
                                    text = "主文件损坏，当前为只读恢复副本" +
                                        (state.primaryFailure?.let { "：$it" } ?: ""),
                                    tone = WorkspaceTone.Danger,
                                    actionLabel = "恢复可写",
                                    onAction = viewModel::restorePrevious,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                )
                            }

                            val pending = readyDoc.settingProposals.count { !it.isResolved }
                            NovelSegmentedTabs(
                                selectedIndex = state.tab.ordinal,
                                labels = listOf(
                                    "创作",
                                    "正文",
                                    if (pending > 0) "设定 · $pending" else "设定",
                                ),
                                onSelect = { index ->
                                    viewModel.selectTab(NovelWorkspaceTab.entries[index])
                                },
                            )

                            AnimatedContent(
                                targetState = state.tab,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                transitionSpec = {
                                    NovelMotion.horizontalPage(
                                        forward = targetState.ordinal > initialState.ordinal,
                                    )
                                },
                                label = "novelWorkspaceTab",
                            ) { tab ->
                                when (tab) {
                                    NovelWorkspaceTab.Chat -> NovelChatTab(viewModel, state)
                                    NovelWorkspaceTab.Manuscript -> NovelManuscriptTab(viewModel, state)
                                    NovelWorkspaceTab.Living -> NovelLivingTab(viewModel, state)
                                }
                            }
                        }

                        val collectSheet = state.collectSheet
                        if (collectSheet != null) {
                            NovelCollectCandidateSheet(
                                sheet = collectSheet,
                                busy = state.busy,
                                busyPhase = state.busyPhase,
                                onDismiss = viewModel::dismissCollectSheet,
                                onConfirm = { selectedText, appendToCurrent, runStateDelta, replaceTarget ->
                                    viewModel.confirmCollect(
                                        selectedText = selectedText,
                                        appendToCurrent = appendToCurrent,
                                        runStateDelta = runStateDelta,
                                        replaceTarget = replaceTarget,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreationControl && document != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showCreationControl = false },
            sheetState = sheetState,
            containerColor = workspace.canvas,
        ) {
            NovelGhostwritePanel(
                state = state,
                notificationPermissionDenied = notificationPermissionDenied,
                onDismiss = { showCreationControl = false },
                onSetMode = viewModel::setCollaborationMode,
                onSetPauseOnBlockingContinuity =
                    viewModel::setPauseGhostwriteOnBlockingContinuity,
                onSavePlan = viewModel::saveChapterPlan,
                onClearPlan = viewModel::clearChapterPlan,
                onSaveUpcomingArc = viewModel::saveUpcomingArc,
                onClearUpcomingArc = viewModel::clearUpcomingArc,
                onStart = startGhostwriteWithPermission,
                onPause = viewModel::pauseGhostwrite,
                onStartBatch = { target ->
                    runWithNotificationPermission {
                        viewModel.startGhostwriteBatch(target)
                    }
                },
                onPauseBatch = viewModel::pauseGhostwriteBatch,
                onResumeBatch = {
                    runWithNotificationPermission(viewModel::resumeGhostwriteBatch)
                },
                onCancelBatch = viewModel::cancelGhostwriteBatch,
                onQuarantineBatchFailure = viewModel::quarantineGhostwriteBatchFailure,
                onOpenNotificationSettings = { openNotificationSettings() },
            )
        }
    }
}

private enum class WorkspacePhase { Loading, Unavailable, Ready }

// region Chat — 聊天写作

private const val NovelAssistantDisplayName = "小说助手"

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NovelChatTab(
    viewModel: NovelWorkspaceViewModel,
    state: NovelWorkspaceUiState,
) {
    val messages = viewModel.currentSessionMessages()
    val writeSelected = state.composerMode == NovelSessionModeRequest.WriteProse
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    // Continuous Column scroll (not LazyColumn) so stream growth is height-based, not item jumps.
    val scrollState = rememberScrollState()
    var stickToBottom by remember { mutableStateOf(true) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Small slop: only "at bottom" for resume, not a wide magnetic zone.
    val bottomSlopPx = 56

    // User intent only while a gesture is in progress. Programmatic pin uses
    // dispatchRawDelta (no isScrollInProgress), so it never clears stick.
    LaunchedEffect(scrollState) {
        var lastValue = scrollState.value
        snapshotFlow {
            Triple(scrollState.value, scrollState.maxValue, scrollState.isScrollInProgress)
        }
            .distinctUntilChanged()
            .collect { (value, max, scrolling) ->
                val nearBottom = max <= 0 || max - value <= bottomSlopPx
                if (scrolling) {
                    if (value < lastValue || !nearBottom) {
                        stickToBottom = false
                    }
                } else if (nearBottom) {
                    // Gesture ended parked at bottom → re-arm follow.
                    stickToBottom = true
                }
                lastValue = value
            }
    }

    fun pinToBottomIfNeeded() {
        if (!stickToBottom) return
        val gap = scrollState.maxValue - scrollState.value
        if (gap > 0) {
            // Same-frame pin without scroll mutex / isScrollInProgress — avoids
            // the down-then-up bounce of deferred scrollTo after layout.
            scrollState.dispatchRawDelta(gap.toFloat())
        }
    }

    // Re-arm path: user parked at bottom without a size change still needs a pin.
    LaunchedEffect(stickToBottom) {
        if (stickToBottom) pinToBottomIfNeeded()
    }

    fun copyText(text: String) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("novel", text)))
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Bottom-anchored column: while content < viewport, growth is pure layout
        // (text rises, no scroll). Past viewport, onSizeChanged keeps the pin.
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val viewportMin = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .heightIn(min = viewportMin)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .onSizeChanged { pinToBottomIfNeeded() },
                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom),
            ) {
            val needsQuickStart = viewModel.needsQuickStartGeneration(state.document)
            if (messages.isEmpty() && state.streamingText.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp, horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = when {
                            state.generating && needsQuickStart ->
                                "正在根据题材与核心想法生成角色、世界观、剧情大纲与写作要求…"
                            state.generating ->
                                "正在生成…"
                            needsQuickStart ->
                                "快速开始会根据你填写的题材与想法，自动生成可确认的角色设定、世界观、剧情大纲和写作要求。"
                            else ->
                                "在这里讨论规划或生成正文候选。收录前，候选不会进入正式章节。"
                        },
                        style = type.secondary,
                        color = workspace.muted,
                    )
                    if (needsQuickStart && !state.generating && !state.busy) {
                        NovelPrimaryButton(
                            text = "开始生成初始设定",
                            onClick = { viewModel.startQuickStartSuggestions() },
                            enabled = state.access == NovelProjectLoadAccess.ReadWrite,
                            accent = true,
                            compact = true,
                        )
                    }
                }
            } else if (
                needsQuickStart &&
                !state.generating &&
                !state.busy &&
                state.streamingText.isEmpty()
            ) {
                // Failed / interrupted first attempt left chat history but no proposals.
                NovelPrimaryButton(
                    text = "重新生成初始设定",
                    onClick = {
                        // Allow a manual retry even if auto-kick already spent its flag.
                        viewModel.startQuickStartSuggestions(fromAuto = false)
                    },
                    enabled = state.access == NovelProjectLoadAccess.ReadWrite,
                    accent = true,
                    compact = true,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            messages.forEach { message ->
                key(message.id.rawValue) {
                    val isUser = message.role == NovelSessionRole.User
                    val candidateId = message.candidateID
                    val candidate = viewModel.proseCandidateForMessage(candidateId)
                    val discussionAsk = remember(message.content) {
                        if (!isUser &&
                            (message.kind == NovelSessionMessageKind.Discussion ||
                                candidateId == null)
                        ) {
                            NovelDiscussionAskParser.parse(message.content)
                        } else {
                            null
                        }
                    }
                    val bubbleContent = discussionAsk?.displayContent ?: message.content
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        NovelMessageBubble(
                            isUser = isUser,
                            content = bubbleContent,
                            streaming = false,
                            longPressItems = buildList {
                                // Copy what's on screen (without ask_user fence noise).
                                add("复制" to { copyText(bubbleContent) })
                                if (isUser) {
                                    add("重新发送" to { viewModel.resendUserText(message.content) })
                                }
                            },
                        )
                        // Reuse chat AskUserToolStep (same chips / submit control).
                        if (discussionAsk != null) {
                            NovelChatAskUserHost(
                                messageId = message.id.rawValue,
                                toolInput = discussionAsk.toolInput,
                                questions = discussionAsk.questions,
                                enabled = !state.busy && !state.generating,
                                onSubmit = { answer -> viewModel.send(overrideText = answer) },
                            )
                        }
                        // Collect / polish CTAs live outside the bubble.
                        if (!isUser && candidateId != null && candidate != null) {
                            when (candidate.kind) {
                                NovelCandidateKind.Prose -> {
                                    val interrupted =
                                        candidate.status == NovelCandidateStatus.Interrupted
                                    val collectedHead = viewModel.isCollectHeadCandidate(candidate)
                                    val blockReason = viewModel.collectBlockReason(candidate)
                                    val collectable = blockReason == null
                                    val phase = state.busyPhase
                                    when {
                                        collectedHead -> {
                                            var undoConfirm by remember(candidateId.rawValue) {
                                                mutableStateOf(false)
                                            }
                                            NovelCandidateActionBar(
                                                primaryLabel = if (state.busy) "处理中…" else "撤销收录",
                                                targetHint = "这是当前分支最近一次收录 · 可撤销回退正文",
                                                enabled = !state.busy && !state.generating,
                                                onPrimary = { undoConfirm = true },
                                            )
                                            if (undoConfirm) {
                                                AlertDialog(
                                                    onDismissRequest = {
                                                        if (!state.busy) undoConfirm = false
                                                    },
                                                    title = { Text("撤销这次收录？") },
                                                    text = {
                                                        Text("正文将回退到收录前；候选不会被物理删除，但分支 head 会后移。")
                                                    },
                                                    confirmButton = {
                                                        TextButton(
                                                            onClick = {
                                                                viewModel.undoHead {
                                                                    undoConfirm = false
                                                                }
                                                            },
                                                            enabled = !state.busy,
                                                        ) {
                                                            Text(
                                                                if (state.busy) "撤销中…" else "撤销",
                                                                color = workspace.red,
                                                            )
                                                        }
                                                    },
                                                    dismissButton = {
                                                        TextButton(
                                                            onClick = { undoConfirm = false },
                                                            enabled = !state.busy,
                                                        ) { Text("取消") }
                                                    },
                                                    containerColor = workspace.paper,
                                                )
                                            }
                                        }
                                        else -> {
                                            val primaryLabel = when {
                                                state.busy && phase != null -> phase
                                                state.busy -> "收录中…"
                                                interrupted -> "收录已生成部分"
                                                else -> "收录到正文"
                                            }
                                            val hint = blockReason
                                                ?: viewModel.collectTargetHint(
                                                    candidate = candidate,
                                                    isWholeChapter = viewModel
                                                        .isWholeChapterCollectDefault(candidate),
                                                    isInterrupted = interrupted,
                                                )
                                            NovelCandidateActionBar(
                                                primaryLabel = primaryLabel,
                                                targetHint = hint,
                                                enabled = collectable &&
                                                    !state.busy &&
                                                    !state.generating,
                                                onPrimary = {
                                                    viewModel.openCollectSheet(candidateId)
                                                },
                                                secondaryLabel = if (
                                                    interrupted &&
                                                    !state.busy &&
                                                    !state.generating
                                                ) {
                                                    "重新生成"
                                                } else {
                                                    null
                                                },
                                                onSecondary = if (interrupted) {
                                                    { viewModel.resendFromCandidate(candidateId) }
                                                } else {
                                                    null
                                                },
                                            )
                                        }
                                    }
                                }
                                NovelCandidateKind.Polish -> {
                                    NovelCandidateActionBar(
                                        primaryLabel = if (state.busy) "处理中…" else "采用润色",
                                        targetHint = "替换当前章节正文",
                                        enabled = !state.busy && !state.generating,
                                        onPrimary = {
                                            viewModel.adoptPolish(candidateId, asRewrite = false)
                                        },
                                        secondaryLabel = "保存为剧情改写",
                                        onSecondary = {
                                            viewModel.adoptPolish(candidateId, asRewrite = true)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (state.streamingText.isNotEmpty()) {
                // Hide incomplete ask_user fence while streaming; chips appear after complete.
                val streamDisplay = remember(state.streamingText) {
                    NovelDiscussionAskParser.stripFence(state.streamingText)
                }
                NovelMessageBubble(
                    isUser = false,
                    content = streamDisplay,
                    streaming = true,
                    showCursor = true,
                )
            }

            val unresolved = state.document?.settingProposals?.filter { !it.isResolved }.orEmpty()
            AnimatedVisibility(
                visible = unresolved.isNotEmpty(),
                enter = fadeIn(tween(NovelMotion.MediumMs)) +
                    expandVertically(tween(NovelMotion.MediumMs, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(NovelMotion.FastMs)) +
                    shrinkVertically(tween(NovelMotion.MediumMs, easing = FastOutSlowInEasing)),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .animateContentSize(tween(NovelMotion.MediumMs, easing = FastOutSlowInEasing)),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SectionLabel(
                        text = "设定建议 · ${unresolved.size}",
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    unresolved.forEach { proposal ->
                        key(proposal.id.rawValue) {
                            SettingProposalCard(
                                title = proposal.title,
                                body = proposal.content.take(400),
                                busy = state.busy,
                                onAccept = {
                                    viewModel.resolveProposal(proposal.id, accept = true)
                                },
                                onDismiss = {
                                    viewModel.resolveProposal(proposal.id, accept = false)
                                },
                            )
                        }
                    }
                }
            }
            } // scroll content Column
        } // BoxWithConstraints

        // Unified surface tray with composer (full-bleed under nav bar).
        // Mode is a compact popup on the composer row — no permanent chip strip.
        val tokens = LocalAmberTokens.current
        Column(
            Modifier
                .fillMaxWidth()
                .background(tokens.surface),
        ) {
            AnimatedVisibility(
                visible = state.errorMessage != null,
                enter = fadeIn(tween(NovelMotion.FastMs)),
                exit = fadeOut(tween(NovelMotion.FastMs)),
            ) {
                Text(
                    text = state.errorMessage.orEmpty(),
                    color = workspace.red,
                    style = type.meta,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            AnimatedVisibility(
                visible = state.statusMessage != null && state.errorMessage == null,
                enter = fadeIn(tween(NovelMotion.FastMs)),
                exit = fadeOut(tween(NovelMotion.FastMs)),
            ) {
                Text(
                    text = state.statusMessage.orEmpty(),
                    color = workspace.green,
                    style = type.meta,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            var showInjectionPanel by remember { mutableStateOf(false) }
            val injectionReceipt = viewModel.latestInjectionReceipt()
            val archivable = viewModel.discussionArchivableMessages()
            val branchNeedsSync = viewModel.currentBranch()?.syncStatus ==
                NovelBranchSyncStatus.NeedsSync
            val archiveSheet = state.archiveSheet

            NovelInjectionContextStrip(
                receipt = injectionReceipt,
                expanded = showInjectionPanel,
                onToggle = { showInjectionPanel = !showInjectionPanel },
                canArchive = archivable.isNotEmpty() &&
                    !state.busy &&
                    !state.generating &&
                    archiveSheet == null &&
                    !branchNeedsSync &&
                    state.access == NovelProjectLoadAccess.ReadWrite,
                archiveHint = when {
                    archivable.isEmpty() -> "没有可归档的新讨论"
                    branchNeedsSync -> "请先同步状态"
                    else -> null
                },
                onArchive = { viewModel.openArchiveSheet() },
            )

            NovelComposerBar(
                draft = state.draft,
                onDraftChange = viewModel::updateDraft,
                generating = state.generating,
                canSend = state.draft.isNotBlank() && !state.busy && !state.generating,
                onSend = { viewModel.send() },
                onStop = viewModel::stop,
                isDiscussMode = !writeSelected,
                isWholeChapter = state.granularity == NovelGenerationGranularityRequest.WholeChapter,
                onSelectDiscuss = {
                    viewModel.setComposerMode(NovelSessionModeRequest.DiscussPlan)
                },
                onSelectWriteSegment = {
                    viewModel.setGranularity(NovelGenerationGranularityRequest.Continuation)
                },
                onSelectWriteChapter = {
                    viewModel.setGranularity(NovelGenerationGranularityRequest.WholeChapter)
                },
                placeholder = when {
                    state.composerMode == NovelSessionModeRequest.DiscussPlan ->
                        "讨论剧情、人物动机、设定…"
                    state.granularity == NovelGenerationGranularityRequest.WholeChapter ->
                        "描述本章要点，或直接说「写下一章」…"
                    else -> "接下去怎么写，或直接说「继续」…"
                },
            )

            if (archiveSheet != null) {
                NovelDiscussionArchiveSheet(
                    messages = archivable,
                    sheet = archiveSheet,
                    busy = state.busy,
                    onDismiss = {
                        // Allow cancel during distill; block only while confirm-save is in flight.
                        if (!state.busy || archiveSheet.distilling) {
                            viewModel.dismissArchiveSheet()
                        }
                    },
                    onRetryDistill = viewModel::retryArchiveDistill,
                    onSummaryChange = viewModel::updateArchiveSummary,
                    onDecisionsChange = viewModel::updateArchiveDecisions,
                    onConfirm = { summary, decisions ->
                        viewModel.archiveDiscussion(summary, decisions)
                    },
                )
            }
        }
    }
}

/**
 * Mount chat's [AskUserToolStep] for a novel discussion ask_user fence.
 * Synthetic [UIMessagePart.Tool] keeps the same chips / submit control as chat.
 */
@Composable
private fun NovelChatAskUserHost(
    messageId: String,
    toolInput: String,
    questions: List<NovelDiscussionAskParser.Question>,
    enabled: Boolean,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var answeredPayload by remember(messageId) { mutableStateOf<String?>(null) }
    val tool = remember(messageId, toolInput, answeredPayload) {
        UIMessagePart.Tool(
            toolCallId = "novel-ask-$messageId",
            toolName = "ask_user",
            input = toolInput,
            approvalState = answeredPayload
                ?.let { ToolApprovalState.Answered(it) }
                ?: ToolApprovalState.Pending,
        )
    }
    // Single-step CoT host — same scope receiver AskUserToolStep expects in chat.
    ChainOfThought(
        modifier = modifier.fillMaxWidth(),
        steps = listOf(tool),
        drawTimeline = false,
        animateContentChanges = false,
        collapsedVisibleCount = 1,
    ) { step ->
        AskUserToolStep(
            tool = step,
            loading = false,
            onToolAnswer = if (enabled && answeredPayload == null) {
                { _, answerJson ->
                    answeredPayload = answerJson
                    val text = NovelDiscussionAskParser.formatAnswerFromToolPayload(
                        answerJson,
                        questions,
                    )
                    if (text.isNotBlank()) onSubmit(text)
                }
            } else {
                null
            },
        )
    }
}

/**
 * Collect sheet: choose append vs new chapter, multi-select paragraphs, then confirm.
 * Defaults match iOS: whole-chapter → create next; continuation → append current.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovelCollectCandidateSheet(
    sheet: NovelCollectSheetState,
    busy: Boolean,
    busyPhase: String?,
    onDismiss: () -> Unit,
    onConfirm: (
        selectedText: String,
        appendToCurrent: Boolean,
        runStateDelta: Boolean,
        replaceTarget: Boolean,
    ) -> Unit,
) {
    val workspace = workspaceColors()
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val paragraphs = remember(sheet.content) {
        NovelParagraphSelection.splitParagraphs(sheet.content)
    }
    var selectedIds by remember(sheet.candidateId.rawValue, paragraphs) {
        mutableStateOf(NovelParagraphSelection.defaultSelectedIds(paragraphs))
    }
    val canAppend = sheet.chapterCount > 0 && sheet.currentChapterId != null
    val canReplace = sheet.replaceChapterId != null
    // Target: replace (regenerate) | append | createNext
    var targetMode by remember(
        sheet.candidateId.rawValue,
        sheet.isWholeChapterDefault,
        canAppend,
        canReplace,
    ) {
        mutableStateOf(
            when {
                canReplace -> 2
                canAppend && !sheet.isWholeChapterDefault -> 1
                else -> 0
            },
        )
    }
    // Default on: update living state. Advanced users can skip for speed.
    var runStateDelta by remember(sheet.candidateId.rawValue) { mutableStateOf(true) }
    val selectedText = remember(paragraphs, selectedIds) {
        NovelParagraphSelection.joinSelected(paragraphs, selectedIds)
    }
    val canConfirm = selectedText.isNotBlank() && !busy
    val confirmLabel = when {
        busy && busyPhase != null -> busyPhase
        busy -> "收录中…"
        targetMode == 2 && canReplace -> {
            val title = sheet.replaceChapterTitle?.takeIf { it.isNotBlank() }
            if (title != null) "收录并替换「$title」" else "收录并替换目标章"
        }
        targetMode == 1 && canAppend -> "收录并并入当前章"
        sheet.chapterCount <= 0 -> "收录并创建第 1 章"
        else -> "收录并新开第 ${sheet.chapterCount + 1} 章"
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
        containerColor = workspace.paper,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = when {
                        canReplace && !sheet.isInterrupted -> "收录重写 · 替换原文"
                        sheet.isInterrupted -> "收录已生成部分"
                        else -> "收录到正文"
                    },
                    style = type.sessionTitle,
                    color = workspace.ink,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when {
                        canReplace ->
                            "将替换目标章正文；旧版本保留在数据层（版本历史后续可浏览）。默认会尝试更新剧情状态。"
                        sheet.isInterrupted ->
                            "生成已中断 · 可只收录仍想保留的段落；默认会尝试更新剧情状态"
                        else ->
                            "选择写入目标与段落；正文会立即写入，并尽量更新剧情 / 设定"
                    },
                    style = type.meta,
                    color = workspace.muted,
                )
            }

            NovelCheckRow(
                checked = runStateDelta,
                title = "同时更新剧情状态",
                subtitle = if (runStateDelta) {
                    "推荐 · 失败时正文仍保留，可到「正文」同步重试"
                } else {
                    "快速收录 · 跳过状态更新（设定可能落后）"
                },
                onToggle = { runStateDelta = !runStateDelta },
                enabled = !busy,
            )

            // Target — full-width choice chips so stacked options share one width.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "写入目标",
                    style = type.meta,
                    color = workspace.muted,
                    fontWeight = FontWeight.SemiBold,
                )
                if (canReplace) {
                    val replaceLabel = sheet.replaceChapterTitle
                        ?.takeIf { it.isNotBlank() }
                        ?.let { "替换「$it」" }
                        ?: "替换目标章"
                    NovelChipButton(
                        text = "$replaceLabel · 推荐",
                        selected = targetMode == 2,
                        onClick = { targetMode = 2 },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (canAppend && !canReplace) {
                    val currentLabel = sheet.currentChapterTitle
                        ?.takeIf { it.isNotBlank() }
                        ?.let { "并入「$it」" }
                        ?: "并入当前章"
                    NovelChipButton(
                        text = currentLabel,
                        selected = targetMode == 1,
                        onClick = { targetMode = 1 },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                NovelChipButton(
                    text = if (sheet.chapterCount <= 0) {
                        "创建第 1 章"
                    } else {
                        "新开第 ${sheet.chapterCount + 1} 章"
                    },
                    selected = targetMode == 0,
                    onClick = { targetMode = 0 },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Paragraphs
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = buildString {
                            append("段落 · 已选 ${selectedIds.size}/${paragraphs.size.coerceAtLeast(1)}")
                            append(" · ${selectedText.length} 字")
                        },
                        style = type.meta,
                        color = workspace.muted,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        NovelQuietButton(
                            text = "全选",
                            onClick = {
                                selectedIds = NovelParagraphSelection.defaultSelectedIds(paragraphs)
                            },
                            enabled = !busy && paragraphs.isNotEmpty(),
                        )
                        NovelQuietButton(
                            text = "清空",
                            onClick = { selectedIds = emptySet() },
                            enabled = !busy && selectedIds.isNotEmpty(),
                        )
                    }
                }
                if (paragraphs.isEmpty()) {
                    Text(
                        text = "无法分段，将收录全部正文。",
                        style = type.secondary,
                        color = workspace.muted,
                    )
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        paragraphs.forEach { paragraph ->
                            val selected = paragraph.id in selectedIds
                            val shape = RoundedCornerShape(12.dp)
                            val markShape = RoundedCornerShape(6.dp)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(shape)
                                    .border(
                                        1.dp,
                                        if (selected) tokens.ink else workspace.hairline,
                                        shape,
                                    )
                                    .background(
                                        if (selected) {
                                            tokens.ink.copy(alpha = 0.06f)
                                        } else {
                                            workspace.canvas
                                        },
                                    )
                                    .clickable(enabled = !busy) {
                                        selectedIds = if (selected) {
                                            selectedIds - paragraph.id
                                        } else {
                                            selectedIds + paragraph.id
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(20.dp)
                                        .clip(markShape)
                                        .background(if (selected) tokens.ink else workspace.paper)
                                        .border(
                                            1.dp,
                                            if (selected) tokens.ink else workspace.hairline,
                                            markShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (selected) {
                                        Text(
                                            "✓",
                                            color = tokens.bg,
                                            style = type.meta.copy(fontWeight = FontWeight.Bold),
                                        )
                                    }
                                }
                                Text(
                                    text = paragraph.text,
                                    style = type.secondary,
                                    color = workspace.ink,
                                    maxLines = 6,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }

            if (selectedText.isBlank() && paragraphs.isNotEmpty()) {
                Text(
                    text = "请至少选择一段正文",
                    style = type.meta,
                    color = workspace.red,
                )
            }

            NovelPrimaryButton(
                text = confirmLabel,
                onClick = {
                    val text = if (paragraphs.isEmpty()) sheet.content.trim() else selectedText
                    onConfirm(
                        text,
                        targetMode == 1 && canAppend,
                        runStateDelta,
                        targetMode == 2 && canReplace,
                    )
                },
                enabled = canConfirm || (paragraphs.isEmpty() && sheet.content.isNotBlank() && !busy),
                accent = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Collect / adopt CTAs sit under the assistant bubble, not inside it. */
@Composable
private fun NovelCandidateActionBar(
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    targetHint: String? = null,
    enabled: Boolean = true,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (targetHint != null) {
            Text(
                text = targetHint,
                style = type.meta,
                color = workspace.muted,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (secondaryLabel != null && onSecondary != null) {
            NovelGhostButton(
                text = secondaryLabel,
                onClick = onSecondary,
                enabled = enabled,
            )
        }
        NovelPrimaryButton(
            text = primaryLabel,
            onClick = onPrimary,
            enabled = enabled,
            accent = true,
            compact = true,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NovelMessageBubble(
    isUser: Boolean,
    content: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    showCursor: Boolean = false,
    /** Long-press body menu (copy / resend). */
    longPressItems: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val workspace = workspaceColors()
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    var longPressMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        // Skip animateContentSize while streaming: height animation fights bottom pin.
        Surface(
            shape = if (isUser) NovelBubbleShapeUser else NovelBubbleShapeAssistant,
            color = if (isUser) tokens.ink else workspace.paper,
            border = if (isUser) null else workspaceBorder(),
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(if (isUser) 0.88f else 1f)
                .then(
                    if (streaming) {
                        Modifier
                    } else {
                        Modifier.animateContentSize(
                            tween(NovelMotion.FastMs, easing = FastOutSlowInEasing),
                        )
                    },
                ),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isUser) {
                    if (streaming || showCursor) {
                        NovelGeneratingHeader()
                    } else {
                        Text(
                            NovelAssistantDisplayName,
                            style = type.meta,
                            color = workspace.muted,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Box(
                    modifier = if (longPressItems.isNotEmpty() && !streaming) {
                        Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { longPressMenuOpen = true },
                        )
                    } else {
                        Modifier
                    },
                ) {
                    SelectionContainer {
                        if (isUser) {
                            Text(
                                text = content,
                                style = type.body,
                                color = tokens.bg,
                            )
                        } else {
                            Column {
                                MarkdownBlock(
                                    content = content,
                                    style = LocalTextStyle.current.merge(type.body)
                                        .copy(color = workspace.ink),
                                    streaming = streaming,
                                    fillWidth = true,
                                )
                                if (showCursor) {
                                    BlinkingCursor(Modifier.padding(top = 2.dp))
                                }
                            }
                        }
                    }
                    DropdownMenu(
                        expanded = longPressMenuOpen,
                        onDismissRequest = { longPressMenuOpen = false },
                    ) {
                        longPressItems.forEach { (label, action) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    longPressMenuOpen = false
                                    action()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NovelInjectionContextStrip(
    receipt: app.amber.feature.novel.model.NovelInjectionReceiptRecord?,
    expanded: Boolean,
    onToggle: () -> Unit,
    canArchive: Boolean,
    archiveHint: String? = null,
    onArchive: () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NovelQuietButton(
                text = if (expanded) "收起上下文" else "本次上下文",
                onClick = onToggle,
            )
            Column(horizontalAlignment = Alignment.End) {
                NovelQuietButton(
                    text = "归档讨论",
                    onClick = onArchive,
                    enabled = canArchive,
                )
                if (!canArchive && !archiveHint.isNullOrBlank()) {
                    Text(
                        archiveHint,
                        style = type.meta,
                        color = workspace.muted,
                    )
                }
            }
        }
        if (expanded) {
            if (receipt == null) {
                Text(
                    "尚无生成注入记录。发送讨论或正文后，这里会显示模型实际用到的上下文片段。",
                    style = type.meta,
                    color = workspace.muted,
                )
            } else {
                val includedMats = receipt.materialDecisions.count { it.included }
                val excludedMats = receipt.materialDecisions.count { !it.included }
                Text(
                    text = buildString {
                        append(receipt.promptVersion)
                        append(" · 预估 ")
                        append(receipt.estimatedInputTokens)
                        append(" tokens · 片段 ")
                        append(receipt.sections.size)
                        append(" · 资料 +")
                        append(includedMats)
                        append(" / -")
                        append(excludedMats)
                    },
                    style = type.meta,
                    color = workspace.muted,
                )
                receipt.sections.take(12).forEach { section ->
                    Text(
                        text = "· ${section.label}  (~${section.estimatedTokens})",
                        style = type.meta,
                        color = workspace.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (receipt.sections.size > 12) {
                    Text(
                        "… 另有 ${receipt.sections.size - 12} 段",
                        style = type.meta,
                        color = workspace.muted,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovelDiscussionArchiveSheet(
    messages: List<app.amber.feature.novel.model.NovelSessionMessageRecord>,
    sheet: NovelArchiveSheetUi,
    busy: Boolean,
    onDismiss: () -> Unit,
    onRetryDistill: () -> Unit,
    onSummaryChange: (String) -> Unit,
    onDecisionsChange: (List<Pair<String, String>>) -> Unit,
    onConfirm: (summary: String, decisions: List<Pair<String, String>>) -> Unit,
) {
    val workspace = workspaceColors()
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val summary = sheet.summary
    val decisions = sheet.decisions
    val fieldsEnabled = !busy && !sheet.distilling
    val canConfirm = summary.isNotBlank() &&
        decisions.any { it.first.isNotBlank() && it.second.isNotBlank() } &&
        fieldsEnabled
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = tokens.accent,
        unfocusedBorderColor = workspace.hairline,
        focusedContainerColor = workspace.paper,
        unfocusedContainerColor = workspace.paper,
        cursorColor = tokens.accent,
        focusedTextColor = workspace.ink,
        unfocusedTextColor = workspace.ink,
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = workspace.paper,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "归档讨论",
                style = type.sessionTitle,
                color = workspace.ink,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                buildString {
                    if (messages.isNotEmpty()) {
                        append("将最近 ${messages.size} 条讨论蒸馏为「决定」资料；")
                    } else {
                        append("将本轮讨论蒸馏为「决定」资料；")
                    }
                    append("请确认后再写入，归档后这些消息不再进入注入窗口。")
                },
                style = type.meta,
                color = workspace.muted,
            )
            if (sheet.distilling) {
                Text(
                    "正在提炼本轮讨论…",
                    style = type.meta,
                    color = tokens.accent,
                )
            }
            if (!sheet.distillError.isNullOrBlank()) {
                Text(sheet.distillError, style = type.meta, color = workspace.red)
                NovelQuietButton(
                    text = "重新提炼",
                    onClick = onRetryDistill,
                    enabled = fieldsEnabled,
                )
                Text(
                    "也可直接手动填写下方摘要与决定。",
                    style = type.meta,
                    color = workspace.muted,
                )
            } else if (sheet.draft != null) {
                Text(
                    "已自动提炼 ${sheet.draft.decisions.size} 条，可编辑后确认。",
                    style = type.meta,
                    color = workspace.green,
                )
            }
            OutlinedTextField(
                value = summary,
                onValueChange = { if (it.length <= 300) onSummaryChange(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("摘要（≤300 字）") },
                enabled = fieldsEnabled,
                colors = fieldColors,
                minLines = 2,
            )
            Text(
                "决定（至少 1 条）",
                style = type.meta.copy(fontWeight = FontWeight.SemiBold),
                color = workspace.muted,
            )
            decisions.forEachIndexed { index, pair ->
                OutlinedTextField(
                    value = pair.first,
                    onValueChange = { v ->
                        onDecisionsChange(
                            decisions.toMutableList().also {
                                it[index] = v to it[index].second
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("主题 ${index + 1}") },
                    enabled = fieldsEnabled,
                    singleLine = true,
                    colors = fieldColors,
                )
                OutlinedTextField(
                    value = pair.second,
                    onValueChange = { v ->
                        onDecisionsChange(
                            decisions.toMutableList().also {
                                it[index] = it[index].first to v
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("决定内容") },
                    enabled = fieldsEnabled,
                    colors = fieldColors,
                    minLines = 2,
                )
            }
            NovelQuietButton(
                text = "添加决定",
                onClick = {
                    onDecisionsChange(decisions + ("" to ""))
                },
                enabled = fieldsEnabled && decisions.size < 12,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                NovelGhostButton(
                    text = "取消",
                    onClick = onDismiss,
                    enabled = !busy || sheet.distilling,
                )
                NovelPrimaryButton(
                    text = when {
                        busy && !sheet.distilling -> "归档中…"
                        sheet.distilling -> "提炼中…"
                        else -> "确认归档"
                    },
                    onClick = { onConfirm(summary, decisions) },
                    enabled = canConfirm,
                    accent = true,
                    compact = true,
                )
            }
        }
    }
}

/**
 * Compact graphite composer: circular mode icon (left) · input pill · circular send.
 * Mode is icon-only (bubble = discuss, quill = write); long labels live in the popup.
 */
@Composable
private fun NovelComposerBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    generating: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isDiscussMode: Boolean,
    isWholeChapter: Boolean,
    onSelectDiscuss: () -> Unit,
    onSelectWriteSegment: () -> Unit,
    onSelectWriteChapter: () -> Unit,
    placeholder: String,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val empty = draft.isBlank()
    val sendStopState = generating
    val sendEnabled = if (generating) true else canSend
    val sendFill by animateColorAsState(
        targetValue = if (empty && !generating) tokens.surface2 else tokens.accent,
        label = "novelSendFill",
    )
    val sendIconTint by animateColorAsState(
        targetValue = if (empty && !generating) tokens.ink3 else Color.White,
        label = "novelSendIconTint",
    )
    val sendInteraction = remember { MutableInteractionSource() }
    val sendPressed by sendInteraction.collectIsPressedAsState()
    val sendScale by animateFloatAsState(
        targetValue = if (sendPressed) 0.975f else 1f,
        label = "novelSendPress",
    )
    val modeInteraction = remember { MutableInteractionSource() }
    val modePressed by modeInteraction.collectIsPressedAsState()
    val modeScale by animateFloatAsState(
        targetValue = if (modePressed) 0.975f else 1f,
        label = "novelModePress",
    )
    // Discuss stays neutral; write gets a soft accent fill so the mode is glanceable.
    val modeFill by animateColorAsState(
        targetValue = if (isDiscussMode) tokens.surface2 else tokens.accent.copy(alpha = 0.14f),
        label = "novelModeFill",
    )
    val modeIconTint by animateColorAsState(
        targetValue = if (isDiscussMode) tokens.ink else tokens.accent,
        label = "novelModeIconTint",
    )
    val pillShape = RoundedCornerShape(22.dp)
    var modeMenuOpen by remember { mutableStateOf(false) }
    val modeIcon = if (isDiscussMode) HugeIcons.BubbleChat else HugeIcons.QuillWrite01
    val modeDescription = if (isDiscussMode) {
        "讨论模式，点按切换"
    } else if (isWholeChapter) {
        "写一章模式，点按切换"
    } else {
        "写一段模式，点按切换"
    }

    Surface(color = tokens.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 4.dp)
                .padding(horizontal = 10.dp)
                .padding(top = 6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 42.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Mode — same 40dp circle as send; icon only.
                Box {
                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = modeScale
                                scaleY = modeScale
                            }
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(modeFill)
                            .border(1.dp, tokens.line, CircleShape)
                            .clickable(
                                interactionSource = modeInteraction,
                                indication = null,
                                enabled = !generating,
                                onClick = { modeMenuOpen = true },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = modeIcon,
                            contentDescription = modeDescription,
                            tint = modeIconTint,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = modeMenuOpen,
                        onDismissRequest = { modeMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        HugeIcons.BubbleChat,
                                        contentDescription = null,
                                        tint = tokens.ink,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        "讨论",
                                        fontWeight = if (isDiscussMode) {
                                            FontWeight.SemiBold
                                        } else {
                                            FontWeight.Normal
                                        },
                                    )
                                }
                            },
                            onClick = {
                                modeMenuOpen = false
                                onSelectDiscuss()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        HugeIcons.QuillWrite01,
                                        contentDescription = null,
                                        tint = tokens.ink,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        "写正文 · 一段",
                                        fontWeight = if (!isDiscussMode && !isWholeChapter) {
                                            FontWeight.SemiBold
                                        } else {
                                            FontWeight.Normal
                                        },
                                    )
                                }
                            },
                            onClick = {
                                modeMenuOpen = false
                                onSelectWriteSegment()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        HugeIcons.QuillWrite01,
                                        contentDescription = null,
                                        tint = tokens.ink,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        "写正文 · 一章",
                                        fontWeight = if (!isDiscussMode && isWholeChapter) {
                                            FontWeight.SemiBold
                                        } else {
                                            FontWeight.Normal
                                        },
                                    )
                                }
                            },
                            onClick = {
                                modeMenuOpen = false
                                onSelectWriteChapter()
                            },
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp, max = 120.dp)
                        .clip(pillShape)
                        .background(tokens.surface2)
                        .border(BorderStroke(1.dp, tokens.line), pillShape)
                        .padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (empty) {
                            Text(
                                text = placeholder,
                                style = type.secondary,
                                color = tokens.ink4,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            value = draft,
                            onValueChange = onDraftChange,
                            enabled = !generating,
                            textStyle = type.body.copy(color = tokens.ink),
                            cursorBrush = SolidColor(tokens.accent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 1.dp),
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = sendScale
                            scaleY = sendScale
                        }
                            .size(48.dp)
                        .clip(CircleShape)
                        .background(sendFill)
                        .clickable(
                            interactionSource = sendInteraction,
                            indication = null,
                            enabled = sendEnabled,
                            onClick = { if (sendStopState) onStop() else onSend() },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (sendStopState) HugeIcons.Cancel01 else HugeIcons.ArrowUp02,
                        contentDescription = if (sendStopState) "停止" else "发送",
                        tint = sendIconTint,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * Setting proposal card with exit motion: confirm/ignore first play leave animation,
 * then commit the domain resolve so the list doesn't pop without transition.
 */
@Composable
private fun SettingProposalCard(
    title: String,
    body: String,
    busy: Boolean,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(true) }
    var committing by remember { mutableStateOf(false) }

    fun leaveThen(accept: Boolean) {
        if (committing || busy) return
        committing = true
        visible = false
        scope.launch {
            delay(NovelMotion.MediumMs.toLong())
            if (accept) onAccept() else onDismiss()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(NovelMotion.MediumMs)) +
            expandVertically(tween(NovelMotion.MediumMs, easing = FastOutSlowInEasing)) +
            scaleIn(initialScale = 0.98f, animationSpec = tween(NovelMotion.MediumMs)),
        exit = fadeOut(tween(NovelMotion.FastMs)) +
            shrinkVertically(tween(NovelMotion.MediumMs, easing = FastOutSlowInEasing)) +
            scaleOut(targetScale = 0.96f, animationSpec = tween(NovelMotion.MediumMs)),
    ) {
        AmberCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(title, style = type.sessionTitle, color = workspace.ink)
                NovelBodyText(text = body, muted = true)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NovelPrimaryButton(
                        text = if (committing) "…" else "确认",
                        onClick = { leaveThen(accept = true) },
                        accent = true,
                        compact = true,
                        enabled = !committing && !busy,
                    )
                    NovelGhostButton(
                        text = "忽略",
                        onClick = { leaveThen(accept = false) },
                        enabled = !committing && !busy,
                    )
                }
            }
        }
    }
}

// endregion

// region Manuscript — 目录 + 全屏阅读器

/** One formal chapter for TOC / continuous reader. */
private data class NovelChapterItem(
    val index: Int,
    val chapterId: NovelChapterId,
    val versionId: NovelChapterVersionId,
    val title: String,
    val content: String,
) {
    val ordinalLabel: String get() = "第 ${index + 1} 章"

    /** Prefer stored title; if it's a generic「第N章」, try first markdown heading in body. */
    val displayTitle: String
        get() {
            val trimmed = title.trim()
            if (trimmed.isNotEmpty() &&
                trimmed != ordinalLabel &&
                !trimmed.matches(Regex("""第\s*\d+\s*章"""))
            ) {
                return trimmed
            }
            val firstLine = content.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
            val heading = firstLine.removePrefix("###").removePrefix("##").removePrefix("#").trim()
            // "第一章 破庙…" → keep full heading, or strip leading 第N章 for cleaner top title
            return when {
                heading.isNotEmpty() -> heading
                trimmed.isNotEmpty() -> trimmed
                else -> ordinalLabel
            }
        }

    val charCount: Int get() = content.length
}

/** e.g. 3748 → "3,748 字" */
private fun formatChapterCharCount(count: Int): String {
    val grouped = "%,d".format(count)
    return "$grouped 字"
}

/**
 * If the manuscript opens with a markdown heading that restates the chrome title,
 * drop that first line so the reader doesn't show the chapter name twice.
 */
private fun stripLeadingDuplicateHeading(content: String, displayTitle: String): String {
    val title = displayTitle.trim()
    if (content.isBlank() || title.isEmpty()) return content
    val lines = content.lineSequence().toList()
    val firstIdx = lines.indexOfFirst { it.isNotBlank() }
    if (firstIdx < 0) return content
    val first = lines[firstIdx].trim()
    val heading = first
        .removePrefix("###")
        .removePrefix("##")
        .removePrefix("#")
        .trim()
        .trimStart { it == '*' || it == '_' }
        .trimEnd { it == '*' || it == '_' }
        .trim()
    val same = heading.equals(title, ignoreCase = true) ||
        (heading.length >= 2 && title.length >= 2 &&
            (heading.endsWith(title) || title.endsWith(heading)))
    if (!same) return content
    val rest = lines.drop(firstIdx + 1)
    val after = if (rest.firstOrNull()?.isBlank() == true) rest.drop(1) else rest
    return after.joinToString("\n").trimStart()
}

@Composable
private fun NovelManuscriptTab(
    viewModel: NovelWorkspaceViewModel,
    state: NovelWorkspaceUiState,
) {
    val document = state.document ?: return
    val branch = viewModel.currentBranch()
    val selections = branch?.workingChapterSelections.orEmpty()

    val chapters = remember(document.chapterVersions, selections) {
        selections.mapIndexed { index, sel ->
            val version = document.chapterVersions.firstOrNull { it.id == sel.versionID }
            NovelChapterItem(
                index = index,
                chapterId = sel.chapterID,
                versionId = sel.versionID,
                title = version?.title.orEmpty(),
                content = version?.content.orEmpty(),
            )
        }
    }

    // null = 目录；非空 = 全屏阅读（从该章起连续向下）
    var openIndex by remember { mutableStateOf<Int?>(null) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var historyChapterId by remember {
        mutableStateOf<NovelChapterId?>(null)
    }

    BackHandler(enabled = openIndex != null || editingIndex != null || historyChapterId != null) {
        when {
            historyChapterId != null -> historyChapterId = null
            editingIndex != null -> editingIndex = null
            openIndex != null -> openIndex = null
        }
    }

    var showBatchPolish by remember { mutableStateOf(false) }
    var batchSelected by remember { mutableStateOf(setOf<NovelChapterVersionId>()) }

    // TOC under workspace tabs; reading is a true fullscreen Dialog (covers project bar + tabs).
    NovelChapterToc(
        chapters = chapters,
        needsSync = branch?.syncStatus == NovelBranchSyncStatus.NeedsSync,
        onSync = viewModel::syncManualEdits,
        onOpen = { openIndex = it },
        onEdit = { editingIndex = it },
        onPolish = { idx ->
            chapters.getOrNull(idx)?.let { viewModel.polishChapter(it.versionId) }
        },
        onRegenerate = { idx ->
            chapters.getOrNull(idx)?.let { viewModel.regenerateChapter(it.versionId) }
        },
        onVersionHistory = { idx ->
            chapters.getOrNull(idx)?.let { historyChapterId = it.chapterId }
        },
        onDiscard = { idx ->
            chapters.getOrNull(idx)?.let { viewModel.setChapterDiscarded(it.chapterId, true) }
        },
        onBatchPolish = {
            batchSelected = chapters.map { it.versionId }.toSet()
            showBatchPolish = true
        },
        onContinuityAudit = viewModel::runContinuityAudit,
        continuityConsistent = state.continuityConsistent,
        continuityIssues = state.continuityIssues,
        onClearContinuity = viewModel::clearContinuityAudit,
        batchPolishResults = state.batchPolishResults,
        batchPolishRunning = state.batchPolishRunning,
        onCancelBatch = viewModel::cancelBatchPolish,
        onClearBatchResults = viewModel::clearBatchPolishResults,
    )

    if (showBatchPolish) {
        NovelBatchPolishSheet(
            chapters = chapters,
            selected = batchSelected,
            onToggle = { id ->
                batchSelected = if (id in batchSelected) batchSelected - id else batchSelected + id
            },
            busy = state.busy || state.batchPolishRunning,
            onDismiss = { if (!state.batchPolishRunning) showBatchPolish = false },
            onStart = {
                val ordered = chapters.map { it.versionId }.filter { it in batchSelected }
                showBatchPolish = false
                viewModel.startBatchPolish(ordered)
            },
        )
    }

    val reading = openIndex
    if (reading != null && chapters.isNotEmpty()) {
        NovelFullscreenReader(
            chapters = chapters,
            startIndex = reading.coerceIn(0, chapters.lastIndex),
            onBackToToc = { openIndex = null },
            onEdit = { editIdx ->
                openIndex = null
                editingIndex = editIdx
            },
            onPolish = { polishIdx ->
                chapters.getOrNull(polishIdx)?.let { viewModel.polishChapter(it.versionId) }
            },
            onRegenerate = { regenIdx ->
                chapters.getOrNull(regenIdx)?.let { viewModel.regenerateChapter(it.versionId) }
            },
            onVersionHistory = { histIdx ->
                chapters.getOrNull(histIdx)?.let {
                    openIndex = null
                    historyChapterId = it.chapterId
                }
            },
        )
    }

    if (editingIndex != null && editingIndex in chapters.indices) {
        val ch = chapters[editingIndex!!]
        NovelChapterEditor(
            chapter = ch,
            busy = state.busy,
            errorMessage = state.errorMessage,
            onCancel = { if (!state.busy) editingIndex = null },
            onSave = { title, body ->
                viewModel.saveManualEdit(ch.chapterId, title, body) {
                    editingIndex = null
                }
            },
        )
    }

    historyChapterId?.let { chapterId ->
        val versions = viewModel.chapterVersionsFor(chapterId)
        val headId = branch?.workingChapterSelections
            ?.firstOrNull { it.chapterID == chapterId }
            ?.versionID
        NovelChapterVersionsSheet(
            versions = versions,
            headVersionId = headId,
            busy = state.busy,
            errorMessage = state.errorMessage,
            onDismiss = { historyChapterId = null },
            onRestore = { versionId ->
                viewModel.restoreChapterVersion(versionId)
            },
        )
    }
}

@Composable
private fun NovelChapterToc(
    chapters: List<NovelChapterItem>,
    needsSync: Boolean,
    onSync: () -> Unit,
    onOpen: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    onPolish: (Int) -> Unit,
    onRegenerate: (Int) -> Unit,
    onVersionHistory: (Int) -> Unit,
    onDiscard: (Int) -> Unit,
    onBatchPolish: () -> Unit,
    onContinuityAudit: () -> Unit,
    continuityConsistent: Boolean?,
    continuityIssues: List<NovelContinuityUiIssue>?,
    onClearContinuity: () -> Unit,
    batchPolishResults: List<NovelBatchPolishResult>,
    batchPolishRunning: Boolean,
    onCancelBatch: () -> Unit,
    onClearBatchResults: () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val tokens = LocalAmberTokens.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (needsSync) {
            item {
                NovelBanner(
                    text = "正文已改写，需同步后才能正式生成 / 收录。",
                    tone = WorkspaceTone.Warning,
                    actionLabel = "同步状态",
                    onAction = onSync,
                )
            }
        }

        if (batchPolishRunning) {
            item {
                NovelBanner(
                    text = "批量润色进行中…",
                    tone = WorkspaceTone.Warning,
                    actionLabel = "取消",
                    onAction = onCancelBatch,
                )
            }
        } else if (batchPolishResults.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("批量润色报告", style = type.meta.copy(fontWeight = FontWeight.SemiBold), color = workspace.ink)
                        NovelQuietButton(text = "清除", onClick = onClearBatchResults)
                    }
                    batchPolishResults.forEach { r ->
                        Text("· ${r.title}：${r.outcome}", style = type.meta, color = workspace.muted)
                    }
                }
            }
        }

        if (continuityIssues != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            if (continuityConsistent == true) "一致性：通过" else "一致性：有问题",
                            style = type.meta.copy(fontWeight = FontWeight.SemiBold),
                            color = workspace.ink,
                        )
                        NovelQuietButton(text = "关闭", onClick = onClearContinuity)
                    }
                    if (continuityIssues.isEmpty()) {
                        Text("未发现可证实的矛盾。", style = type.meta, color = workspace.muted)
                    } else {
                        continuityIssues.forEach { issue ->
                            Text(
                                "[${issue.severity}] ${issue.summary}",
                                style = type.meta,
                                color = workspace.ink,
                            )
                            issue.references.take(3).forEach { ref ->
                                Text("  · $ref", style = type.meta, color = workspace.muted, maxLines = 2)
                            }
                        }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "目录",
                    style = type.sessionTitle,
                    color = workspace.ink,
                )
                Text(
                    text = if (chapters.isEmpty()) {
                        "收录候选后会出现章节"
                    } else {
                        "共 ${chapters.size} 章 · 点章节开始阅读，向下可连续接下一章"
                    },
                    style = type.meta,
                    color = workspace.muted,
                )
                if (chapters.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NovelQuietButton(text = "批量润色", onClick = onBatchPolish)
                        NovelQuietButton(text = "一致性审计", onClick = onContinuityAudit)
                    }
                }
            }
        }

        if (chapters.isEmpty()) {
            item {
                NovelEmptyState(
                    title = "还没有正式章节",
                    subtitle = "在「创作」生成候选并点「收录到正文」，章节会出现在这里。",
                )
            }
        } else {
            itemsIndexed(chapters, key = { _, ch -> ch.chapterId.rawValue }) { index, ch ->
                var menuOpen by remember(ch.chapterId.rawValue) { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(workspace.paper)
                        .border(1.dp, workspace.hairline, RoundedCornerShape(14.dp))
                        .clickable { onOpen(index) }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = ch.ordinalLabel,
                        style = type.meta.copy(fontWeight = FontWeight.SemiBold),
                        color = tokens.accent,
                        modifier = Modifier.widthIn(min = 52.dp),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = ch.displayTitle,
                            style = type.body.copy(fontWeight = FontWeight.SemiBold),
                            color = workspace.ink,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (ch.charCount == 0) "空章节" else "约 ${ch.charCount} 字",
                            style = type.meta,
                            color = workspace.muted,
                        )
                    }
                    Box {
                        NovelIconButton(
                            icon = HugeIcons.MoreVertical,
                            contentDescription = "章节操作",
                            onClick = { menuOpen = true },
                        )
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("阅读") },
                                onClick = {
                                    menuOpen = false
                                    onOpen(index)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("编辑") },
                                onClick = {
                                    menuOpen = false
                                    onEdit(index)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("整章润色") },
                                onClick = {
                                    menuOpen = false
                                    onPolish(index)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("重写本章") },
                                onClick = {
                                    menuOpen = false
                                    onRegenerate(index)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("版本历史") },
                                onClick = {
                                    menuOpen = false
                                    onVersionHistory(index)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("废弃本章") },
                                onClick = {
                                    menuOpen = false
                                    onDiscard(index)
                                },
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovelBatchPolishSheet(
    chapters: List<NovelChapterItem>,
    selected: Set<NovelChapterVersionId>,
    onToggle: (NovelChapterVersionId) -> Unit,
    busy: Boolean,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = workspace.paper,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("批量整章润色", style = type.sessionTitle, color = workspace.ink, fontWeight = FontWeight.SemiBold)
            Text(
                "按目录顺序串行润色并尝试采用；若事实漂移则跳过该章（不改原文）。",
                style = type.meta,
                color = workspace.muted,
            )
            chapters.forEach { ch ->
                NovelCheckRow(
                    checked = ch.versionId in selected,
                    title = ch.displayTitle,
                    subtitle = "约 ${ch.charCount} 字",
                    onToggle = { onToggle(ch.versionId) },
                    enabled = !busy,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                NovelGhostButton(text = "取消", onClick = onDismiss, enabled = !busy)
                NovelPrimaryButton(
                    text = "开始（${selected.size}）",
                    onClick = onStart,
                    enabled = !busy && selected.isNotEmpty(),
                    accent = true,
                    compact = true,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovelChapterVersionsSheet(
    versions: List<NovelChapterVersionRecord>,
    headVersionId: NovelChapterVersionId?,
    busy: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onRestore: (NovelChapterVersionId) -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var preview by remember(versions) { mutableStateOf(versions.firstOrNull()) }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
        containerColor = workspace.paper,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "版本历史",
                style = type.sessionTitle,
                color = workspace.ink,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "仅可恢复与当前 head 同一事实兼容链的版本（通常为整章润色或同链恢复）。手改 / 替换收录 / 追加会换事实链，不可一键恢复。",
                style = type.meta,
                color = workspace.muted,
            )
            if (!errorMessage.isNullOrBlank()) {
                Text(errorMessage, style = type.meta, color = workspace.red)
            }
            if (versions.isEmpty()) {
                Text("暂无版本记录", style = type.body, color = workspace.muted)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    versions.forEach { version ->
                        val isHead = version.id == headVersionId
                        val selected = preview?.id == version.id
                        NovelChipButton(
                            text = buildString {
                                append(chapterVersionKindLabel(version.kind))
                                append(" · ")
                                append(version.content.length)
                                append(" 字")
                                if (isHead) append(" · 当前")
                            },
                            selected = selected,
                            onClick = { preview = version },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                val shown = preview
                if (shown != null) {
                    Text(
                        text = shown.title.ifBlank { "（无标题）" },
                        style = type.body.copy(fontWeight = FontWeight.SemiBold),
                        color = workspace.ink,
                    )
                    Text(
                        text = shown.content.ifBlank { "（空）" },
                        style = type.body,
                        color = workspace.ink,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val isHead = shown.id == headVersionId
                    NovelPrimaryButton(
                        text = when {
                            busy -> "恢复中…"
                            isHead -> "已是当前版本"
                            else -> "恢复为此版本"
                        },
                        onClick = { onRestore(shown.id) },
                        enabled = !busy && !isHead,
                        accent = true,
                        compact = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            NovelGhostButton(
                text = "关闭",
                onClick = onDismiss,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun chapterVersionKindLabel(kind: NovelChapterVersionKind): String = when (kind) {
    NovelChapterVersionKind.Collected -> "收录"
    NovelChapterVersionKind.ManualEdit -> "手改"
    NovelChapterVersionKind.Polish -> "润色"
    NovelChapterVersionKind.Restore -> "恢复"
}

/**
 * Pin status/nav icon polarity on a Compose [Dialog] window to the app theme.
 * Dialog windows do not inherit Activity edge-to-edge appearance, so TOC →
 * fullscreen reader was flipping icons back to white on a light canvas.
 */
@Composable
private fun NovelDialogSystemBars() {
    val view = LocalView.current
    val darkTheme = LocalDarkMode.current
    fun apply() {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        // Apply on both decorView and the compose root — ColorOS is picky.
        listOf(window.decorView, view).forEach { target ->
            WindowCompat.getInsetsController(window, target).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    SideEffect { apply() }
    // Second pass after the dialog window is fully attached / focused.
    LaunchedEffect(darkTheme) {
        apply()
        delay(32)
        apply()
    }
}

/**
 * True fullscreen novel reader — covers project top bar + workspace tabs.
 * Chrome: [←]  章名（中）  [⋯]
 * Body: continuous chapters; scroll down auto-continues into the next.
 * Enter/exit: slide + fade (exit animates before dismiss).
 */
@Composable
private fun NovelFullscreenReader(
    chapters: List<NovelChapterItem>,
    startIndex: Int,
    onBackToToc: () -> Unit,
    onEdit: (Int) -> Unit,
    onPolish: (Int) -> Unit,
    onRegenerate: (Int) -> Unit,
    onVersionHistory: (Int) -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val tokens = LocalAmberTokens.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }

    val enterOffsetPx = with(density) { 56.dp.toPx() }
    val slide = remember { Animatable(enterOffsetPx) }
    val fade = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch {
            slide.animateTo(0f, tween(NovelMotion.MediumMs, easing = FastOutSlowInEasing))
        }
        launch {
            fade.animateTo(1f, tween(NovelMotion.MediumMs))
        }
    }

    fun dismissWithAnim() {
        scope.launch {
            launch {
                slide.animateTo(enterOffsetPx, tween(NovelMotion.MediumMs, easing = FastOutSlowInEasing))
            }
            fade.animateTo(0f, tween(NovelMotion.MediumMs))
            onBackToToc()
        }
    }

    LaunchedEffect(startIndex, chapters.size) {
        if (chapters.isNotEmpty()) {
            listState.scrollToItem(startIndex.coerceIn(0, chapters.lastIndex))
        }
    }

    val currentIndex by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
        }
    }
    val current = chapters.getOrNull(currentIndex)

    Dialog(
        onDismissRequest = { dismissWithAnim() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        // Compose Dialog owns a separate Window — Activity theme SideEffect does not
        // apply. Without this, ColorOS defaults to light (white) status icons on a
        // pale reader canvas when entering from TOC.
        NovelDialogSystemBars()
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = slide.value
                    alpha = fade.value
                },
            color = workspace.canvas,
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                // Reader chrome: tools recede, meta+title form one quiet center stack.
                // [← muted]  第N章 · 字数 / 标题  [⋯ muted]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp)
                        .padding(top = 2.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NovelIconButton(
                        icon = HugeIcons.ArrowLeft01,
                        contentDescription = "返回目录",
                        onClick = { dismissWithAnim() },
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        val meta = buildString {
                            val ordinal = current?.ordinalLabel.orEmpty()
                            val words = current?.let { formatChapterCharCount(it.charCount) }.orEmpty()
                            if (ordinal.isNotEmpty()) append(ordinal)
                            if (ordinal.isNotEmpty() && words.isNotEmpty()) append("  ·  ")
                            if (words.isNotEmpty()) append(words)
                        }
                        if (meta.isNotEmpty()) {
                            Text(
                                text = meta,
                                style = type.meta,
                                color = workspace.faint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Text(
                            text = current?.displayTitle ?: "正文",
                            style = type.body.copy(fontWeight = FontWeight.SemiBold),
                            color = workspace.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Box {
                        NovelIconButton(
                            icon = HugeIcons.MoreVertical,
                            contentDescription = "菜单",
                            onClick = { menuOpen = true },
                        )
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("返回目录") },
                                onClick = {
                                    menuOpen = false
                                    dismissWithAnim()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("编辑本章") },
                                onClick = {
                                    menuOpen = false
                                    onEdit(currentIndex)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("整章润色") },
                                onClick = {
                                    menuOpen = false
                                    onPolish(currentIndex)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("重写本章") },
                                onClick = {
                                    menuOpen = false
                                    onRegenerate(currentIndex)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("版本历史") },
                                onClick = {
                                    menuOpen = false
                                    onVersionHistory(currentIndex)
                                },
                            )
                        }
                    }
                }

                // Whisper divider — present enough to anchor chrome, soft enough to ignore.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(workspace.hairline.copy(alpha = 0.55f)),
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 4.dp),
                ) {
                    itemsIndexed(chapters, key = { _, ch -> ch.chapterId.rawValue }) { index, ch ->
                        val body = remember(ch.content, ch.displayTitle) {
                            stripLeadingDuplicateHeading(ch.content, ch.displayTitle)
                        }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    // First chapter needs air under the rule; later chapters
                                    // keep a larger gap after "接下文".
                                    top = if (index == 0) 20.dp else 28.dp,
                                    bottom = 8.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            // Title lives only in chrome — body starts as prose.
                            if (body.isBlank()) {
                                Text(
                                    "（本章暂无内容）",
                                    style = type.secondary,
                                    color = workspace.muted,
                                    modifier = Modifier.padding(vertical = 24.dp),
                                )
                            } else {
                                MarkdownBlock(
                                    content = body,
                                    style = LocalTextStyle.current.merge(type.body)
                                        .copy(color = workspace.ink),
                                    fillWidth = true,
                                )
                            }

                            if (index < chapters.lastIndex) {
                                val next = chapters[index + 1]
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(top = 32.dp, bottom = 16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(0.28f)
                                            .height(1.dp)
                                            .background(tokens.line),
                                    )
                                    Text(
                                        text = "接下文",
                                        style = type.meta,
                                        color = workspace.muted,
                                    )
                                    Text(
                                        text = next.displayTitle,
                                        style = type.meta.copy(fontWeight = FontWeight.SemiBold),
                                        color = workspace.ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            } else {
                                Text(
                                    text = "— 全书完 —",
                                    style = type.meta,
                                    color = workspace.muted,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 40.dp),
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(56.dp)) }
                }
            }
        }
    }
}

@Composable
private fun NovelChapterEditor(
    chapter: NovelChapterItem,
    busy: Boolean,
    errorMessage: String? = null,
    onCancel: () -> Unit,
    onSave: (title: String, body: String) -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val tokens = LocalAmberTokens.current
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = tokens.ink,
        unfocusedBorderColor = workspace.hairline,
        focusedContainerColor = workspace.paper,
        unfocusedContainerColor = workspace.paper,
    )
    var title by remember(chapter.versionId.rawValue) { mutableStateOf(chapter.title) }
    var body by remember(chapter.versionId.rawValue) { mutableStateOf(chapter.content) }
    var showFindReplace by remember(chapter.versionId.rawValue) { mutableStateOf(false) }
    var findQuery by remember(chapter.versionId.rawValue) { mutableStateOf("") }
    var replaceWith by remember(chapter.versionId.rawValue) { mutableStateOf("") }
    // -1 = no active match yet; first "下一个" lands on index 0.
    var matchIndex by remember(chapter.versionId.rawValue) { mutableStateOf(-1) }
    var statusHint by remember(chapter.versionId.rawValue) { mutableStateOf<String?>(null) }

    fun matchStarts(haystack: String, needle: String): List<Int> {
        if (needle.isEmpty()) return emptyList()
        val starts = mutableListOf<Int>()
        var from = 0
        while (from <= haystack.length) {
            val at = haystack.indexOf(needle, startIndex = from, ignoreCase = false)
            if (at < 0) break
            starts += at
            from = at + needle.length.coerceAtLeast(1)
        }
        return starts
    }

    fun findNext(forward: Boolean = true) {
        val starts = matchStarts(body, findQuery)
        if (starts.isEmpty()) {
            matchIndex = -1
            statusHint = if (findQuery.isEmpty()) "输入要查找的内容" else "未找到「$findQuery」"
            return
        }
        val next = when {
            matchIndex < 0 -> if (forward) 0 else starts.lastIndex
            forward -> (matchIndex + 1).mod(starts.size)
            else -> (matchIndex - 1).mod(starts.size)
        }
        matchIndex = next
        statusHint = "第 ${next + 1}/${starts.size} 处"
    }

    fun replaceCurrent() {
        val starts = matchStarts(body, findQuery)
        if (starts.isEmpty() || findQuery.isEmpty()) {
            statusHint = if (findQuery.isEmpty()) "输入要查找的内容" else "未找到「$findQuery」"
            return
        }
        // If user never navigated, replace the first match.
        val idx = if (matchIndex < 0) 0 else matchIndex.coerceIn(0, starts.lastIndex)
        val at = starts[idx]
        body = body.replaceRange(at, at + findQuery.length, replaceWith)
        // After replace, re-scan and advance to next remaining match at same ordinal.
        val nextStarts = matchStarts(body, findQuery)
        matchIndex = if (nextStarts.isEmpty()) -1 else idx.coerceAtMost(nextStarts.lastIndex)
        statusHint = if (nextStarts.isEmpty()) {
            "已替换 · 无更多匹配"
        } else {
            "已替换 · 第 ${matchIndex + 1}/${nextStarts.size} 处"
        }
    }

    fun replaceAll() {
        if (findQuery.isEmpty()) {
            statusHint = "输入要查找的内容"
            return
        }
        val starts = matchStarts(body, findQuery)
        if (starts.isEmpty()) {
            statusHint = "未找到「$findQuery」"
            return
        }
        // Replace from the end so earlier offsets stay valid.
        var next = body
        for (at in starts.asReversed()) {
            next = next.replaceRange(at, at + findQuery.length, replaceWith)
        }
        body = next
        matchIndex = -1
        statusHint = "已全部替换 ${starts.size} 处"
    }

    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NovelQuietButton(
                text = "取消",
                onClick = onCancel,
                enabled = !busy,
            )
            Text(
                text = "编辑 · ${chapter.ordinalLabel}",
                style = type.body.copy(fontWeight = FontWeight.SemiBold),
                color = workspace.ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            NovelQuietButton(
                text = if (showFindReplace) "收起" else "查找",
                onClick = {
                    showFindReplace = !showFindReplace
                    if (!showFindReplace) statusHint = null
                },
                enabled = !busy,
            )
            NovelPrimaryButton(
                text = if (busy) "保存中…" else "保存",
                onClick = { onSave(title.trim(), body) },
                enabled = !busy,
                accent = true,
                compact = true,
            )
        }
        if (!errorMessage.isNullOrBlank()) {
            Text(errorMessage, style = type.meta, color = workspace.red)
        }
        if (showFindReplace) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = workspace.paper,
                border = BorderStroke(1.dp, workspace.hairline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = findQuery,
                        onValueChange = {
                            findQuery = it
                            matchIndex = -1
                            statusHint = null
                        },
                        label = { Text("查找") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !busy,
                        colors = fieldColors,
                    )
                    OutlinedTextField(
                        value = replaceWith,
                        onValueChange = { replaceWith = it },
                        label = { Text("替换为") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !busy,
                        colors = fieldColors,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NovelQuietButton(
                            text = "上一个",
                            onClick = { findNext(forward = false) },
                            enabled = !busy && findQuery.isNotEmpty(),
                        )
                        NovelQuietButton(
                            text = "下一个",
                            onClick = { findNext(forward = true) },
                            enabled = !busy && findQuery.isNotEmpty(),
                        )
                        Spacer(Modifier.weight(1f))
                        NovelQuietButton(
                            text = "替换",
                            onClick = { replaceCurrent() },
                            enabled = !busy && findQuery.isNotEmpty(),
                        )
                        NovelPrimaryButton(
                            text = "全部",
                            onClick = { replaceAll() },
                            enabled = !busy && findQuery.isNotEmpty(),
                            accent = false,
                            compact = true,
                        )
                    }
                    if (!statusHint.isNullOrBlank()) {
                        Text(statusHint!!, style = type.meta, color = workspace.muted)
                    }
                }
            }
        }
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("标题") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors,
            singleLine = true,
            enabled = !busy,
        )
        OutlinedTextField(
            value = body,
            onValueChange = {
                body = it
                // Manual body edits invalidate match position.
                if (matchIndex >= 0) {
                    matchIndex = -1
                    statusHint = null
                }
            },
            label = { Text("正文") },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors,
            enabled = !busy,
        )
    }
}

// endregion

// region Living — 设定（主 Tab 下轻量分类筛选，非第二套 Segmented）

private enum class LivingSection(val label: String) {
    Characters("角色"),
    World("世界观"),
    Plot("剧情"),
    More("更多"),
}

private data class LivingDetail(
    val title: String,
    val body: String,
    val footer: String? = null,
    val materialId: NovelMaterialId? = null,
    val kind: NovelMaterialKind? = null,
) {
    val editable: Boolean get() = materialId != null && kind != null
}

private data class LivingMaterialDraft(
    val materialId: NovelMaterialId? = null,
    val kind: NovelMaterialKind,
    val title: String,
    val content: String,
    val kindLocked: Boolean = false,
)

@Composable
private fun NovelLivingTab(
    viewModel: NovelWorkspaceViewModel,
    state: NovelWorkspaceUiState,
) {
    val document = state.document ?: return
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val tokens = LocalAmberTokens.current

    val characters = document.materials.filter {
        !it.isDeleted && it.kind is NovelMaterialKind.Character
    }
    val worlds = document.materials.filter {
        !it.isDeleted && it.kind is NovelMaterialKind.World
    }
    val outlineMaterial = document.materials.firstOrNull {
        !it.isDeleted && it.kind is NovelMaterialKind.MasterOutline
    }
    val outlineRevision = outlineMaterial?.let { m ->
        document.materialRevisions.firstOrNull { it.id == m.currentRevisionID }
    }
    val outline = outlineRevision?.content.orEmpty()
    val requirementsMaterial = document.materials.firstOrNull {
        !it.isDeleted && it.kind is NovelMaterialKind.WritingRequirements
    }
    val requirementsRevision = requirementsMaterial?.let { m ->
        document.materialRevisions.firstOrNull { it.id == m.currentRevisionID }
    }
    val requirements = requirementsRevision?.content.orEmpty()
    val decisions = document.materials.filter {
        !it.isDeleted && it.kind is NovelMaterialKind.DecisionLog
    }
    val customs = document.materials.filter {
        !it.isDeleted && it.kind is NovelMaterialKind.Custom
    }
    val branch = viewModel.currentBranch()
    val snap = document.stateSnapshots.firstOrNull { it.id == branch?.currentStateSnapshotID }
    val branchEvents = snap?.eventIDs
        ?.mapNotNull { id -> document.events.firstOrNull { it.id == id } }
        .orEmpty()
    val events = branchEvents.sortedByDescending { it.sequence }

    var section by remember { mutableStateOf(LivingSection.Characters) }
    var detail by remember { mutableStateOf<LivingDetail?>(null) }
    var editor by remember { mutableStateOf<LivingMaterialDraft?>(null) }
    var pendingDelete by remember { mutableStateOf<LivingDetail?>(null) }

    // Keep open detail in sync after save/delete (document revision bump).
    LaunchedEffect(document.project.revision, detail?.materialId) {
        val open = detail ?: return@LaunchedEffect
        val mid = open.materialId ?: return@LaunchedEffect
        val material = document.materials.firstOrNull { it.id == mid && !it.isDeleted }
        if (material == null) {
            detail = null
            return@LaunchedEffect
        }
        val revision = document.materialRevisions.firstOrNull { it.id == material.currentRevisionID }
            ?: return@LaunchedEffect
        val title = revision.title
        val body = revision.content.ifBlank { "（空）" }
        val footer = if (material.kind is NovelMaterialKind.Character) {
            val matches = NovelCharacterEventMatcher.matchExperiences(
                characterTitle = title,
                events = branchEvents,
            )
            if (matches.isNotEmpty()) {
                matches.take(5).joinToString("\n") { "· ${it.event.summary}" }
            } else {
                null
            }
        } else {
            open.footer
        }
        if (title != open.title || body != open.body || footer != open.footer || material.kind != open.kind) {
            detail = open.copy(
                title = title,
                body = body,
                footer = footer,
                kind = material.kind,
            )
        }
    }

    BackHandler(enabled = detail != null && editor == null) { detail = null }

    editor?.let { draft ->
        LivingMaterialEditorSheet(
            draft = draft,
            busy = state.busy,
            errorMessage = state.errorMessage,
            onDismiss = { if (!state.busy) editor = null },
            onSave = { kind, title, content ->
                viewModel.reviseMaterial(
                    // When kind is locked, never remap DecisionLog → Custom via free-create chips.
                    kind = if (draft.kindLocked) draft.kind else kind,
                    title = title,
                    content = content.trim(),
                    materialId = draft.materialId,
                    onSuccess = { editor = null },
                )
            },
        )
    }

    pendingDelete?.let { target ->
        val mid = target.materialId
        if (mid != null) {
            AlertDialog(
                onDismissRequest = { if (!state.busy) pendingDelete = null },
                title = { Text("删除资料？") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("「${target.title}」将从设定列表移除。已关联的事件引用会保留 ID，但资料不再展示。")
                        state.errorMessage?.takeIf { it.isNotBlank() }?.let { err ->
                            Text(err, color = workspace.red, style = type.meta)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteMaterial(mid) {
                                pendingDelete = null
                                detail = null
                            }
                        },
                        enabled = !state.busy,
                    ) {
                        Text(if (state.busy) "删除中…" else "删除", color = workspace.red)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { pendingDelete = null },
                        enabled = !state.busy,
                    ) { Text("取消") }
                },
                containerColor = workspace.paper,
            )
        }
    }

    // Single AnimatedContent owns list↔detail so open/close share a continuous push/pop.
    AnimatedContent(
        targetState = detail,
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        transitionSpec = {
            when {
                initialState == null && targetState != null -> NovelMotion.pushDetail()
                initialState != null && targetState == null -> NovelMotion.popDetail()
                else -> NovelMotion.fadeScale()
            }
        },
        label = "livingListDetail",
    ) { currentDetail ->
        if (currentDetail != null) {
            LivingDetailPane(
                detail = currentDetail,
                busy = state.busy,
                onBack = { detail = null },
                onEdit = {
                    val mid = currentDetail.materialId
                    val kind = currentDetail.kind
                    if (mid != null && kind != null) {
                        editor = LivingMaterialDraft(
                            materialId = mid,
                            kind = kind,
                            title = currentDetail.title,
                            content = currentDetail.body.takeUnless { it == "（空）" }.orEmpty(),
                            // DecisionLog / fixed kinds must stay immutable on save.
                            kindLocked = true,
                        )
                    }
                },
                onDelete = { pendingDelete = currentDetail },
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                LivingSectionFilters(
                    selected = section,
                    counts = mapOf(
                        LivingSection.Characters to characters.size,
                        LivingSection.World to worlds.size,
                        LivingSection.Plot to (1 + if (events.isNotEmpty()) 1 else 0),
                        LivingSection.More to (1 + decisions.size + customs.size),
                    ),
                    onSelect = { section = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )

                AnimatedContent(
                    targetState = section,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    transitionSpec = {
                        NovelMotion.horizontalByIndex(
                            initialIndex = initialState.ordinal,
                            targetIndex = targetState.ordinal,
                        )
                    },
                    label = "livingSection",
                ) { sec ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        state.errorMessage?.let { msg ->
                            item {
                                Text(msg, color = workspace.red, style = type.meta)
                            }
                        }
                        state.statusMessage?.let { msg ->
                            item {
                                Text(msg, color = tokens.accent, style = type.meta)
                            }
                        }

                        when (sec) {
                            LivingSection.Characters -> {
                                item {
                                    LivingSectionHeader(
                                        title = "角色",
                                        actionLabel = "新建",
                                        onAction = {
                                            editor = LivingMaterialDraft(
                                                kind = NovelMaterialKind.Character,
                                                title = "",
                                                content = "",
                                                kindLocked = true,
                                            )
                                        },
                                        enabled = !state.busy,
                                    )
                                }
                                if (characters.isEmpty()) {
                                    item {
                                        LivingEmptyHint("还没有人物档案。确认设定建议或点「新建」添加。")
                                    }
                                } else {
                                    item {
                                        LivingListCard {
                                            characters.forEachIndexed { i, material ->
                                                val revision = document.materialRevisions
                                                    .firstOrNull { it.id == material.currentRevisionID }
                                                val title = revision?.title ?: "角色"
                                                val body = revision?.content.orEmpty()
                                                val matches = NovelCharacterEventMatcher.matchExperiences(
                                                    characterTitle = title,
                                                    events = branchEvents,
                                                )
                                                val subtitle = when {
                                                    matches.isNotEmpty() ->
                                                        "相关经历 ${matches.size} 条"
                                                    body.isBlank() -> "暂无经历"
                                                    else -> body.lineSequence().firstOrNull {
                                                        it.isNotBlank()
                                                    }?.trim()?.take(36) ?: "查看档案"
                                                }
                                                LivingListRow(
                                                    title = title,
                                                    subtitle = subtitle,
                                                    showDivider = i < characters.lastIndex,
                                                    onClick = {
                                                        detail = LivingDetail(
                                                            title = title,
                                                            body = body.ifBlank { "（空）" },
                                                            footer = if (matches.isNotEmpty()) {
                                                                matches.take(5).joinToString("\n") {
                                                                    "· ${it.event.summary}"
                                                                }
                                                            } else {
                                                                null
                                                            },
                                                            materialId = material.id,
                                                            kind = material.kind,
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            LivingSection.World -> {
                                item {
                                    LivingSectionHeader(
                                        title = "世界观",
                                        actionLabel = "新建",
                                        onAction = {
                                            editor = LivingMaterialDraft(
                                                kind = NovelMaterialKind.World,
                                                title = "",
                                                content = "",
                                                kindLocked = true,
                                            )
                                        },
                                        enabled = !state.busy,
                                    )
                                }
                                if (worlds.isEmpty()) {
                                    item { LivingEmptyHint("还没有世界观资料。点「新建」添加。") }
                                } else {
                                    item {
                                        LivingListCard {
                                            worlds.forEachIndexed { i, material ->
                                                val revision = document.materialRevisions
                                                    .firstOrNull { it.id == material.currentRevisionID }
                                                val title = revision?.title ?: "世界观"
                                                val body = revision?.content.orEmpty()
                                                LivingListRow(
                                                    title = title,
                                                    subtitle = body.lineSequence().firstOrNull {
                                                        it.isNotBlank()
                                                    }?.trim()?.take(40) ?: "查看设定",
                                                    showDivider = i < worlds.lastIndex,
                                                    onClick = {
                                                        detail = LivingDetail(
                                                            title = title,
                                                            body = body.ifBlank { "（空）" },
                                                            materialId = material.id,
                                                            kind = material.kind,
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            LivingSection.Plot -> {
                                item {
                                    LivingSectionHeader(
                                        title = "剧情",
                                        actionLabel = if (outlineMaterial == null) "新建总纲" else null,
                                        onAction = if (outlineMaterial == null) {
                                            {
                                                editor = LivingMaterialDraft(
                                                    kind = NovelMaterialKind.MasterOutline,
                                                    title = "总纲",
                                                    content = "",
                                                    kindLocked = true,
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                        enabled = !state.busy,
                                    )
                                }
                                item {
                                    LivingListCard {
                                        LivingListRow(
                                            title = outlineRevision?.title ?: "总纲",
                                            subtitle = if (outline.isBlank()) {
                                                if (outlineMaterial == null) "（空）· 点按新建" else "（空）"
                                            } else {
                                                outline.take(40)
                                            },
                                            showDivider = true,
                                            onClick = {
                                                val material = outlineMaterial
                                                if (material == null) {
                                                    editor = LivingMaterialDraft(
                                                        kind = NovelMaterialKind.MasterOutline,
                                                        title = "总纲",
                                                        content = "",
                                                        kindLocked = true,
                                                    )
                                                } else {
                                                    detail = LivingDetail(
                                                        title = outlineRevision?.title ?: "总纲",
                                                        body = outline.ifBlank { "（空）" },
                                                        materialId = material.id,
                                                        kind = material.kind,
                                                    )
                                                }
                                            },
                                        )
                                        LivingListRow(
                                            title = "当前分支摘要",
                                            subtitle = snap?.summary?.take(40)?.ifBlank { "（空）" }
                                                ?: "（空）",
                                            showDivider = true,
                                            onClick = {
                                                detail = LivingDetail(
                                                    "当前分支摘要",
                                                    snap?.summary.orEmpty().ifBlank { "（空）" },
                                                )
                                            },
                                        )
                                        LivingListRow(
                                            title = "分支走向",
                                            subtitle = snap?.branchOutline?.take(40)
                                                ?.ifBlank { "（空）" } ?: "（空）",
                                            showDivider = events.isNotEmpty(),
                                            onClick = {
                                                detail = LivingDetail(
                                                    "分支走向",
                                                    snap?.branchOutline.orEmpty().ifBlank { "（空）" },
                                                )
                                            },
                                        )
                                        if (events.isNotEmpty()) {
                                            LivingListRow(
                                                title = "事件时间线",
                                                subtitle = "共 ${events.size} 条",
                                                showDivider = false,
                                                onClick = {
                                                    detail = LivingDetail(
                                                        title = "事件时间线",
                                                        body = events.joinToString("\n\n") {
                                                            "#${it.sequence}  ${it.summary}"
                                                        },
                                                    )
                                                },
                                            )
                                        }
                                    }
                                }
                            }

                            LivingSection.More -> {
                                item {
                                    LivingSectionHeader(
                                        title = "更多",
                                        actionLabel = "新建",
                                        onAction = {
                                            editor = LivingMaterialDraft(
                                                kind = NovelMaterialKind.Custom("自定义"),
                                                title = "",
                                                content = "",
                                                kindLocked = false,
                                            )
                                        },
                                        enabled = !state.busy,
                                    )
                                }
                                item {
                                    LivingListCard {
                                        LivingListRow(
                                            title = requirementsRevision?.title ?: "写作要求",
                                            subtitle = if (requirements.isBlank()) {
                                                if (requirementsMaterial == null) {
                                                    "（空）· 点按新建"
                                                } else {
                                                    "（空）"
                                                }
                                            } else {
                                                requirements.take(40)
                                            },
                                            showDivider = decisions.isNotEmpty() || customs.isNotEmpty(),
                                            onClick = {
                                                val material = requirementsMaterial
                                                if (material == null) {
                                                    editor = LivingMaterialDraft(
                                                        kind = NovelMaterialKind.WritingRequirements,
                                                        title = "写作要求",
                                                        content = "",
                                                        kindLocked = true,
                                                    )
                                                } else {
                                                    detail = LivingDetail(
                                                        title = requirementsRevision?.title
                                                            ?: "写作要求",
                                                        body = requirements.ifBlank { "（空）" },
                                                        materialId = material.id,
                                                        kind = material.kind,
                                                    )
                                                }
                                            },
                                        )
                                        decisions.forEachIndexed { i, material ->
                                            val revision = document.materialRevisions
                                                .firstOrNull { it.id == material.currentRevisionID }
                                            val title = revision?.title ?: "决定"
                                            val body = revision?.content.orEmpty()
                                            LivingListRow(
                                                title = "决定 · $title",
                                                subtitle = body.lineSequence().firstOrNull {
                                                    it.isNotBlank()
                                                }?.trim()?.take(40) ?: "查看",
                                                showDivider = i < decisions.lastIndex ||
                                                    customs.isNotEmpty(),
                                                onClick = {
                                                    detail = LivingDetail(
                                                        title = title,
                                                        body = body.ifBlank { "（空）" },
                                                        materialId = material.id,
                                                        kind = material.kind,
                                                    )
                                                },
                                            )
                                        }
                                        customs.forEachIndexed { i, material ->
                                            val revision = document.materialRevisions
                                                .firstOrNull { it.id == material.currentRevisionID }
                                            val title = revision?.title ?: "自定义"
                                            val body = revision?.content.orEmpty()
                                            LivingListRow(
                                                title = title,
                                                subtitle = body.lineSequence().firstOrNull {
                                                    it.isNotBlank()
                                                }?.trim()?.take(40) ?: "查看",
                                                showDivider = i < customs.lastIndex,
                                                onClick = {
                                                    detail = LivingDetail(
                                                        title = title,
                                                        body = body.ifBlank { "（空）" },
                                                        materialId = material.id,
                                                        kind = material.kind,
                                                    )
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        item { Spacer(Modifier.height(28.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LivingSectionHeader(
    title: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    enabled: Boolean,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = type.meta.copy(fontWeight = FontWeight.SemiBold),
            color = workspace.muted,
        )
        if (actionLabel != null && onAction != null) {
            NovelQuietButton(
                text = actionLabel,
                onClick = onAction,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun LivingSectionFilters(
    selected: LivingSection,
    counts: Map<LivingSection, Int>,
    onSelect: (LivingSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LivingSection.entries.forEach { sec ->
            val isOn = sec == selected
            val count = counts[sec] ?: 0
            val label = if (count > 0 && sec != LivingSection.More && sec != LivingSection.Plot) {
                "${sec.label} $count"
            } else {
                sec.label
            }
            NovelChipButton(
                text = label,
                selected = isOn,
                onClick = { onSelect(sec) },
                compact = true,
            )
        }
    }
}

@Composable
private fun LivingListCard(content: @Composable ColumnScope.() -> Unit) {
    val workspace = workspaceColors()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(workspace.paper)
            .border(1.dp, workspace.hairline, RoundedCornerShape(16.dp)),
        content = content,
    )
}

@Composable
private fun LivingListRow(
    title: String,
    subtitle: String,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val tokens = LocalAmberTokens.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = tween(NovelMotion.FastMs, easing = FastOutSlowInEasing),
        label = "livingRowPress",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tokens.accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title.take(1).ifEmpty { "·" },
                    style = type.meta.copy(fontWeight = FontWeight.SemiBold),
                    color = tokens.accent,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = title,
                    style = type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = workspace.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = type.meta,
                    color = workspace.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                HugeIcons.ArrowRight01,
                contentDescription = null,
                tint = workspace.faint,
                modifier = Modifier.size(16.dp),
            )
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 62.dp)
                    .height(1.dp)
                    .background(workspace.hairline),
            )
        }
    }
}

@Composable
private fun LivingEmptyHint(text: String) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    Text(
        text = text,
        style = type.secondary,
        color = workspace.muted,
        modifier = Modifier.padding(vertical = 24.dp, horizontal = 4.dp),
    )
}

@Composable
private fun LivingDetailPane(
    detail: LivingDetail,
    busy: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NovelIconButton(
                icon = HugeIcons.ArrowLeft01,
                contentDescription = "返回",
                onClick = onBack,
                tint = workspace.ink,
            )
            Text(
                text = detail.title,
                style = type.body.copy(fontWeight = FontWeight.SemiBold),
                color = workspace.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (detail.editable) {
                NovelQuietButton(
                    text = "编辑",
                    onClick = onEdit,
                    enabled = !busy,
                )
                NovelQuietButton(
                    text = "删除",
                    onClick = onDelete,
                    enabled = !busy,
                    danger = true,
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = detail.title,
                style = type.sessionTitle,
                color = workspace.ink,
            )
            Text(
                text = detail.body,
                style = type.body,
                color = workspace.ink,
            )
            if (!detail.footer.isNullOrBlank()) {
                Text(
                    text = detail.footer,
                    style = type.secondary,
                    color = workspace.muted,
                )
            }
            if (!detail.editable) {
                Text(
                    text = "此条为只读剧情摘要，不可直接编辑。",
                    style = type.meta,
                    color = workspace.muted,
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LivingMaterialEditorSheet(
    draft: LivingMaterialDraft,
    busy: Boolean,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onSave: (kind: NovelMaterialKind, title: String, content: String) -> Unit,
) {
    val workspace = workspaceColors()
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by remember(draft.materialId, draft.title) { mutableStateOf(draft.title) }
    var content by remember(draft.materialId, draft.content) { mutableStateOf(draft.content) }
    var kindChoice by remember(draft.materialId, draft.kind) {
        mutableStateOf(livingKindChoiceOf(draft.kind))
    }
    var customKindLabel by remember(draft.materialId, draft.kind) {
        mutableStateOf(
            (draft.kind as? NovelMaterialKind.Custom)?.value?.takeIf { it.isNotBlank() } ?: "自定义",
        )
    }
    val resolvedKind = livingKindFromChoice(kindChoice, customKindLabel)
    val canSave = title.isNotBlank() && !busy
    val isCreate = draft.materialId == null
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = tokens.accent,
        unfocusedBorderColor = workspace.hairline,
        focusedContainerColor = workspace.paper,
        unfocusedContainerColor = workspace.paper,
        cursorColor = tokens.accent,
        focusedTextColor = workspace.ink,
        unfocusedTextColor = workspace.ink,
        focusedPlaceholderColor = workspace.muted,
        unfocusedPlaceholderColor = workspace.muted,
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = workspace.paper,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (isCreate) "新建资料" else "编辑资料",
                style = type.sessionTitle,
                color = workspace.ink,
                fontWeight = FontWeight.SemiBold,
            )
            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = type.meta,
                    color = workspace.red,
                )
            }
            if (!draft.kindLocked) {
                Text(
                    text = "类型",
                    style = type.meta,
                    color = workspace.muted,
                    fontWeight = FontWeight.SemiBold,
                )
                // Free create: avoid spawning hidden duplicate MasterOutline / WritingRequirements
                // (those have dedicated Plot / More rows that only show firstOrNull).
                val freeChoices = listOf(
                    LivingKindChoice.Character,
                    LivingKindChoice.World,
                    LivingKindChoice.Custom,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    freeChoices.forEach { choice ->
                        NovelChipButton(
                            text = choice.label,
                            selected = kindChoice == choice,
                            onClick = { kindChoice = choice },
                            enabled = !busy,
                            compact = true,
                        )
                    }
                }
                if (kindChoice == LivingKindChoice.Custom) {
                    OutlinedTextField(
                        value = customKindLabel,
                        onValueChange = { customKindLabel = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("自定义类型名") },
                        enabled = !busy,
                        colors = fieldColors,
                    )
                }
            } else {
                Text(
                    text = "类型 · ${livingKindLabel(draft.kind)}",
                    style = type.meta,
                    color = workspace.muted,
                )
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("标题") },
                enabled = !busy,
                colors = fieldColors,
            )
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp),
                label = { Text("正文") },
                enabled = !busy,
                colors = fieldColors,
                minLines = 6,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NovelGhostButton(
                    text = "取消",
                    onClick = onDismiss,
                    enabled = !busy,
                )
                NovelPrimaryButton(
                    text = if (busy) "保存中…" else if (isCreate) "创建" else "保存",
                    onClick = {
                        if (canSave) {
                            onSave(resolvedKind, title.trim(), content)
                        }
                    },
                    enabled = canSave,
                    accent = true,
                    compact = true,
                )
            }
        }
    }
}

private enum class LivingKindChoice(val label: String) {
    Character("角色"),
    World("世界观"),
    MasterOutline("总纲"),
    WritingRequirements("写作要求"),
    Custom("自定义"),
}

private fun livingKindChoiceOf(kind: NovelMaterialKind): LivingKindChoice = when (kind) {
    is NovelMaterialKind.Character -> LivingKindChoice.Character
    is NovelMaterialKind.World -> LivingKindChoice.World
    is NovelMaterialKind.MasterOutline -> LivingKindChoice.MasterOutline
    is NovelMaterialKind.WritingRequirements -> LivingKindChoice.WritingRequirements
    is NovelMaterialKind.DecisionLog -> LivingKindChoice.Custom
    is NovelMaterialKind.Custom -> LivingKindChoice.Custom
}

private fun livingKindFromChoice(choice: LivingKindChoice, customLabel: String): NovelMaterialKind =
    when (choice) {
        LivingKindChoice.Character -> NovelMaterialKind.Character
        LivingKindChoice.World -> NovelMaterialKind.World
        LivingKindChoice.MasterOutline -> NovelMaterialKind.MasterOutline
        LivingKindChoice.WritingRequirements -> NovelMaterialKind.WritingRequirements
        LivingKindChoice.Custom -> NovelMaterialKind.Custom(
            customLabel.trim().ifBlank { "自定义" },
        )
    }

private fun livingKindLabel(kind: NovelMaterialKind): String = when (kind) {
    is NovelMaterialKind.Character -> "角色"
    is NovelMaterialKind.World -> "世界观"
    is NovelMaterialKind.MasterOutline -> "总纲"
    is NovelMaterialKind.WritingRequirements -> "写作要求"
    is NovelMaterialKind.DecisionLog -> "讨论决定"
    is NovelMaterialKind.Custom -> kind.value.ifBlank { "自定义" }
}

// endregion
