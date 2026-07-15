package app.amber.feature.ui.pages.novel

import android.content.ClipData
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.Screen
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.novel.NovelGenerationGranularityRequest
import app.amber.feature.novel.NovelSessionModeRequest
import app.amber.feature.novel.domain.NovelCharacterEventMatcher
import app.amber.feature.novel.model.NovelBranchSyncStatus
import app.amber.feature.novel.model.NovelCandidateKind
import app.amber.feature.novel.model.NovelChapterId
import app.amber.feature.novel.model.NovelChapterVersionId
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

    // Settings (and other stacks) write through NovelCreation; refresh when returning.
    // Use fromResume so an already-open workspace does not full-screen reload mid-generation.
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
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
                        Text(
                            "让对话落进正文，让资料随故事生长",
                            style = type.meta,
                            color = workspace.muted,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
                actions = {
                    IconButton(
                        onClick = {
                            navController.navigate(Screen.NovelSettings(projectId))
                        },
                    ) {
                        Icon(
                            HugeIcons.Settings03,
                            contentDescription = "小说设置",
                            tint = workspace.ink,
                        )
                    }
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
                    }
                }
            }
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
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            messages.forEach { message ->
                key(message.id.rawValue) {
                    val isUser = message.role == NovelSessionRole.User
                    val candidateId = message.candidateID
                    val candidate = viewModel.availableCandidates()
                        .firstOrNull { it.id == candidateId }
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
                                    NovelCandidateActionBar(
                                        primaryLabel = if (state.busy) "收录中…" else "收录到正文",
                                        targetHint = viewModel.collectTargetHint(),
                                        enabled = !state.busy && !state.generating,
                                        onPrimary = { viewModel.collectCandidate(candidateId) },
                                    )
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
                            .size(40.dp)
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
                        .size(40.dp)
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

    BackHandler(enabled = openIndex != null || editingIndex != null) {
        when {
            editingIndex != null -> editingIndex = null
            openIndex != null -> openIndex = null
        }
    }

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
    )

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
        )
    }

    if (editingIndex != null && editingIndex in chapters.indices) {
        val ch = chapters[editingIndex!!]
        NovelChapterEditor(
            chapter = ch,
            busy = state.busy,
            onCancel = { editingIndex = null },
            onSave = { title, body ->
                viewModel.saveManualEdit(ch.chapterId, title, body)
                editingIndex = null
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
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                HugeIcons.MoreVertical,
                                contentDescription = "章节操作",
                                tint = workspace.muted,
                                modifier = Modifier.size(16.dp),
                            )
                        }
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
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
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
                    IconButton(onClick = { dismissWithAnim() }) {
                        Icon(
                            HugeIcons.ArrowLeft01,
                            contentDescription = "返回目录",
                            tint = workspace.muted,
                        )
                    }
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
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                HugeIcons.MoreVertical,
                                contentDescription = "菜单",
                                tint = workspace.muted,
                            )
                        }
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

    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) {
                Text("取消", color = workspace.muted)
            }
            Text(
                text = "编辑 · ${chapter.ordinalLabel}",
                style = type.body.copy(fontWeight = FontWeight.SemiBold),
                color = workspace.ink,
                modifier = Modifier.weight(1f),
            )
            NovelPrimaryButton(
                text = if (busy) "保存中…" else "保存",
                onClick = { onSave(title.trim(), body) },
                enabled = !busy,
                accent = true,
            )
        }
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("标题") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors,
            singleLine = true,
        )
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("正文") },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors,
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
)

@Composable
private fun NovelLivingTab(
    viewModel: NovelWorkspaceViewModel,
    state: NovelWorkspaceUiState,
) {
    val document = state.document ?: return
    val workspace = workspaceColors()
    val type = LocalAmberType.current

    val characters = document.materials.filter {
        !it.isDeleted && it.kind is NovelMaterialKind.Character
    }
    val worlds = document.materials.filter {
        !it.isDeleted && it.kind is NovelMaterialKind.World
    }
    val outline = document.materials.firstOrNull {
        !it.isDeleted && it.kind is NovelMaterialKind.MasterOutline
    }?.let { m ->
        document.materialRevisions.firstOrNull { it.id == m.currentRevisionID }?.content
    }.orEmpty()
    val requirements = document.materials.firstOrNull {
        !it.isDeleted && it.kind is NovelMaterialKind.WritingRequirements
    }?.let { m ->
        document.materialRevisions.firstOrNull { it.id == m.currentRevisionID }?.content
    }.orEmpty()
    val branch = viewModel.currentBranch()
    val snap = document.stateSnapshots.firstOrNull { it.id == branch?.currentStateSnapshotID }
    val branchEvents = snap?.eventIDs
        ?.mapNotNull { id -> document.events.firstOrNull { it.id == id } }
        .orEmpty()
    val events = branchEvents.sortedByDescending { it.sequence }

    var section by remember { mutableStateOf(LivingSection.Characters) }
    var detail by remember { mutableStateOf<LivingDetail?>(null) }

    BackHandler(enabled = detail != null) { detail = null }

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
                onBack = { detail = null },
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                LivingSectionFilters(
                    selected = section,
                    counts = mapOf(
                        LivingSection.Characters to characters.size,
                        LivingSection.World to worlds.size,
                        LivingSection.Plot to (1 + if (events.isNotEmpty()) 1 else 0),
                        LivingSection.More to 1,
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

                        when (sec) {
                            LivingSection.Characters -> {
                                item {
                                    Text(
                                        "角色",
                                        style = type.meta.copy(fontWeight = FontWeight.SemiBold),
                                        color = workspace.muted,
                                    )
                                }
                                if (characters.isEmpty()) {
                                    item {
                                        LivingEmptyHint("还没有人物档案。确认设定建议后会出现在这里。")
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
                                    Text(
                                        "世界观",
                                        style = type.meta.copy(fontWeight = FontWeight.SemiBold),
                                        color = workspace.muted,
                                    )
                                }
                                if (worlds.isEmpty()) {
                                    item { LivingEmptyHint("还没有世界观资料。") }
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
                                    Text(
                                        "剧情",
                                        style = type.meta.copy(fontWeight = FontWeight.SemiBold),
                                        color = workspace.muted,
                                    )
                                }
                                item {
                                    LivingListCard {
                                        LivingListRow(
                                            title = "总纲",
                                            subtitle = if (outline.isBlank()) "（空）" else outline.take(40),
                                            showDivider = true,
                                            onClick = {
                                                detail = LivingDetail(
                                                    "总纲",
                                                    outline.ifBlank { "（空）" },
                                                )
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
                                    Text(
                                        "更多",
                                        style = type.meta.copy(fontWeight = FontWeight.SemiBold),
                                        color = workspace.muted,
                                    )
                                }
                                item {
                                    LivingListCard {
                                        LivingListRow(
                                            title = "写作要求",
                                            subtitle = if (requirements.isBlank()) {
                                                "（空）"
                                            } else {
                                                requirements.take(40)
                                            },
                                            showDivider = false,
                                            onClick = {
                                                detail = LivingDetail(
                                                    "写作要求",
                                                    requirements.ifBlank { "（空）" },
                                                )
                                            },
                                        )
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
private fun LivingSectionFilters(
    selected: LivingSection,
    counts: Map<LivingSection, Int>,
    onSelect: (LivingSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val tokens = LocalAmberTokens.current
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
            val fill by animateColorAsState(
                targetValue = if (isOn) tokens.ink else Color.Transparent,
                animationSpec = tween(NovelMotion.FastMs, easing = FastOutSlowInEasing),
                label = "livingChipFill",
            )
            val stroke by animateColorAsState(
                targetValue = if (isOn) tokens.ink else workspace.hairline,
                animationSpec = tween(NovelMotion.FastMs, easing = FastOutSlowInEasing),
                label = "livingChipStroke",
            )
            val labelColor by animateColorAsState(
                targetValue = if (isOn) tokens.bg else workspace.muted,
                animationSpec = tween(NovelMotion.FastMs, easing = FastOutSlowInEasing),
                label = "livingChipLabel",
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(fill)
                    .border(1.dp, stroke, RoundedCornerShape(999.dp))
                    .clickable { onSelect(sec) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = type.meta.copy(
                        fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    color = labelColor,
                )
            }
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
    onBack: () -> Unit,
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
            IconButton(onClick = onBack) {
                Icon(
                    HugeIcons.ArrowLeft01,
                    contentDescription = "返回",
                    tint = workspace.ink,
                )
            }
            Text(
                text = detail.title,
                style = type.body.copy(fontWeight = FontWeight.SemiBold),
                color = workspace.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
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
            Spacer(Modifier.height(32.dp))
        }
    }
}

// endregion


