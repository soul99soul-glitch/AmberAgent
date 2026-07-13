package app.amber.feature.ui.pages.novel

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.feature.novel.NovelGenerationGranularityRequest
import app.amber.feature.novel.NovelSessionModeRequest
import app.amber.feature.novel.domain.NovelCharacterEventMatcher
import app.amber.feature.novel.model.NovelBranchLifecycle
import app.amber.feature.novel.model.NovelBranchSyncStatus
import app.amber.feature.novel.model.NovelCandidateKind
import app.amber.feature.novel.model.NovelMaterialKind
import app.amber.feature.novel.model.NovelProjectLoadAccess
import app.amber.feature.novel.model.NovelProjectModelPolicy
import app.amber.feature.novel.model.NovelSessionRole
import app.amber.feature.novel.model.NovelSettingProposalOrigin
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.BlinkingCursor
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.workspaceBorder
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.CustomColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Sparkles
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

    Scaffold(
        containerColor = workspace.canvas,
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
                        val branch = viewModel.currentBranch()
                        if (branch != null) {
                            Text(
                                "当前分支 · ${branch.name}",
                                style = type.meta,
                                color = workspace.muted,
                            )
                        }
                    }
                },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
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
                .padding(padding),
            transitionSpec = { NovelMotion.fadeScale() },
            label = "novelWorkspacePhase",
        ) { targetPhase ->
            when (targetPhase) {
                WorkspacePhase.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("加载中…", style = type.secondary, color = workspace.muted)
                    }
                }
                WorkspacePhase.Unavailable -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                        Column(
                            Modifier
                                .fillMaxSize()
                                .imePadding(),
                        ) {
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
                                selectedIndex = if (state.tab == NovelWorkspaceTab.Create) 0 else 1,
                                labels = listOf(
                                    "创作",
                                    if (pending > 0) "资料 · $pending" else "资料",
                                ),
                                onSelect = { index ->
                                    viewModel.selectTab(
                                        if (index == 0) {
                                            NovelWorkspaceTab.Create
                                        } else {
                                            NovelWorkspaceTab.Materials
                                        },
                                    )
                                },
                            )

                            AnimatedContent(
                                targetState = state.tab,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                transitionSpec = {
                                    NovelMotion.horizontalPage(
                                        forward = targetState == NovelWorkspaceTab.Materials,
                                    )
                                },
                                label = "novelWorkspaceTab",
                            ) { tab ->
                                when (tab) {
                                    NovelWorkspaceTab.Create -> NovelCreateTab(viewModel, state)
                                    NovelWorkspaceTab.Materials -> NovelMaterialsTab(viewModel, state)
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

@Composable
private fun NovelCreateTab(
    viewModel: NovelWorkspaceViewModel,
    state: NovelWorkspaceUiState,
) {
    val messages = viewModel.currentSessionMessages()
    val writeSelected = state.composerMode == NovelSessionModeRequest.WriteProse
    val workspace = workspaceColors()
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, state.streamingText) {
        if (messages.isNotEmpty() || state.streamingText.isNotEmpty()) {
            listState.animateScrollToItem(
                (messages.size + if (state.streamingText.isNotEmpty()) 1 else 0).coerceAtLeast(0),
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        val branches = state.document?.branches
            ?.filter { it.lifecycle == NovelBranchLifecycle.Active }
            .orEmpty()
        if (branches.size > 1) {
            NovelChipRow(Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                branches.forEach { branch ->
                    NovelChipButton(
                        text = branch.name,
                        selected = branch.id == state.selectedBranchId,
                        onClick = { viewModel.selectBranch(branch.id) },
                        compact = true,
                    )
                }
            }
        }

        NovelChipRow(Modifier.padding(vertical = 4.dp)) {
            NovelChipButton(
                text = "讨论",
                selected = state.composerMode == NovelSessionModeRequest.DiscussPlan,
                onClick = { viewModel.setComposerMode(NovelSessionModeRequest.DiscussPlan) },
            )
            NovelChipButton(
                text = "续写",
                selected = writeSelected &&
                    state.granularity == NovelGenerationGranularityRequest.Continuation,
                onClick = {
                    viewModel.setGranularity(NovelGenerationGranularityRequest.Continuation)
                },
            )
            NovelChipButton(
                text = "整章",
                selected = writeSelected &&
                    state.granularity == NovelGenerationGranularityRequest.WholeChapter,
                onClick = {
                    viewModel.setGranularity(NovelGenerationGranularityRequest.WholeChapter)
                },
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (messages.isEmpty() && state.streamingText.isEmpty()) {
                item {
                    AmberCard(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                HugeIcons.Sparkles,
                                contentDescription = null,
                                tint = tokens.accent,
                                modifier = Modifier.size(28.dp),
                            )
                            Text(
                                "从讨论到落笔",
                                style = type.sessionTitle,
                                color = workspace.ink,
                            )
                            Text(
                                "在这里规划剧情、续写段落或生成整章。收录前，候选不会进入正式正文。",
                                style = type.secondary,
                                color = workspace.muted,
                            )
                        }
                    }
                }
            }

            items(messages, key = { it.id.rawValue }) { message ->
                val isUser = message.role == NovelSessionRole.User
                NovelMessageBubble(
                    isUser = isUser,
                    content = message.content,
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(NovelMotion.MediumMs),
                        fadeOutSpec = tween(NovelMotion.FastMs),
                        placementSpec = tween(NovelMotion.MediumMs, easing = FastOutSlowInEasing),
                    ),
                    actions = {
                        val candidateId = message.candidateID
                        val candidate = viewModel.availableCandidates()
                            .firstOrNull { it.id == candidateId }
                        if (candidateId != null && candidate != null) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateContentSize(tween(NovelMotion.FastMs)),
                            ) {
                                if (candidate.kind == NovelCandidateKind.Prose) {
                                    NovelGhostButton(
                                        text = "收录正文",
                                        onClick = { viewModel.collectCandidate(candidateId) },
                                    )
                                }
                                if (candidate.kind == NovelCandidateKind.Polish) {
                                    NovelGhostButton(
                                        text = "采用润色",
                                        onClick = {
                                            viewModel.adoptPolish(candidateId, asRewrite = false)
                                        },
                                    )
                                    NovelGhostButton(
                                        text = "保存改写",
                                        onClick = {
                                            viewModel.adoptPolish(candidateId, asRewrite = true)
                                        },
                                    )
                                }
                                NovelGhostButton(
                                    text = "Fork",
                                    onClick = { viewModel.forkFromHead("分支") },
                                )
                            }
                        }
                    },
                )
            }

            item(key = "streaming") {
                AnimatedVisibility(
                    visible = state.streamingText.isNotEmpty(),
                    enter = fadeIn(tween(NovelMotion.FastMs)) +
                        expandVertically(tween(NovelMotion.MediumMs)),
                    exit = fadeOut(tween(NovelMotion.FastMs)) +
                        shrinkVertically(tween(NovelMotion.FastMs)),
                ) {
                    Surface(
                        shape = NovelBubbleShapeAssistant,
                        color = workspace.paper,
                        border = workspaceBorder(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 560.dp)
                            .animateContentSize(tween(NovelMotion.FastMs)),
                    ) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            NovelGeneratingHeader()
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    state.streamingText,
                                    style = type.body,
                                    color = workspace.ink,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                BlinkingCursor(Modifier.padding(start = 2.dp, bottom = 2.dp))
                            }
                        }
                    }
                }
            }

            val unresolved = state.document?.settingProposals?.filter { !it.isResolved }.orEmpty()
            item(key = "proposals-header") {
                AnimatedVisibility(
                    visible = unresolved.isNotEmpty(),
                    enter = fadeIn(tween(NovelMotion.MediumMs)) +
                        expandVertically(tween(NovelMotion.MediumMs)),
                    exit = fadeOut(tween(NovelMotion.FastMs)) +
                        shrinkVertically(tween(NovelMotion.FastMs)),
                ) {
                    SectionLabel(
                        text = "设定建议 · ${unresolved.size}",
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            items(unresolved, key = { it.id.rawValue }) { proposal ->
                AmberCard(
                    Modifier
                        .fillMaxWidth()
                        .animateItem()
                        .animateContentSize(tween(NovelMotion.FastMs)),
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            proposal.title,
                            style = type.sessionTitle,
                            color = workspace.ink,
                        )
                        NovelBodyText(
                            text = proposal.content.take(400),
                            muted = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NovelPrimaryButton(
                                text = "确认",
                                onClick = {
                                    viewModel.resolveProposal(proposal.id, accept = true)
                                },
                                accent = true,
                            )
                            NovelGhostButton(
                                text = "忽略",
                                onClick = {
                                    viewModel.resolveProposal(proposal.id, accept = false)
                                },
                            )
                            NovelGhostButton(
                                text = "去资料",
                                onClick = {
                                    val cat = when (
                                        (proposal.origin as? NovelSettingProposalOrigin.QuickStart)
                                            ?.suggestedKind
                                    ) {
                                        is NovelMaterialKind.World -> NovelMaterialsCategory.World
                                        is NovelMaterialKind.Character -> NovelMaterialsCategory.Characters
                                        is NovelMaterialKind.MasterOutline -> NovelMaterialsCategory.Plot
                                        else -> NovelMaterialsCategory.More
                                    }
                                    viewModel.selectMaterialsCategory(cat)
                                },
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state.errorMessage != null,
            enter = fadeIn(tween(NovelMotion.FastMs)) + expandVertically(tween(NovelMotion.MediumMs)),
            exit = fadeOut(tween(NovelMotion.FastMs)) + shrinkVertically(tween(NovelMotion.FastMs)),
        ) {
            NovelBanner(
                text = state.errorMessage.orEmpty(),
                tone = WorkspaceTone.Danger,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        NovelComposerBar(
            draft = state.draft,
            onDraftChange = viewModel::updateDraft,
            generating = state.generating,
            canSend = state.draft.isNotBlank() && !state.busy,
            onSend = viewModel::send,
            onStop = viewModel::stop,
            placeholder = when {
                state.composerMode == NovelSessionModeRequest.DiscussPlan -> "讨论剧情、人物、设定…"
                state.granularity == NovelGenerationGranularityRequest.WholeChapter -> "描述本章要点，生成整章…"
                else -> "描述续写方向，或直接说「继续」…"
            },
        )
    }
}

@Composable
private fun NovelMessageBubble(
    isUser: Boolean,
    content: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    val workspace = workspaceColors()
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = if (isUser) NovelBubbleShapeUser else NovelBubbleShapeAssistant,
            color = if (isUser) tokens.ink else workspace.paper,
            border = if (isUser) null else workspaceBorder(),
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(if (isUser) 0.88f else 1f)
                .animateContentSize(tween(NovelMotion.FastMs, easing = FastOutSlowInEasing)),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (isUser) "你" else "助手",
                    style = type.meta,
                    color = if (isUser) tokens.bg.copy(alpha = 0.7f) else workspace.muted,
                )
                Text(
                    text = content,
                    style = type.body,
                    color = if (isUser) tokens.bg else workspace.ink,
                )
                actions()
            }
        }
    }
}

@Composable
private fun NovelComposerBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    generating: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    placeholder: String,
) {
    val workspace = workspaceColors()
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current

    Surface(
        color = workspace.paper,
        border = workspaceBorder(),
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp, max = 140.dp)
                    .clip(NovelComposerShape)
                    .background(workspace.row)
                    .border(1.dp, workspace.hairline, NovelComposerShape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (draft.isEmpty()) {
                    Text(placeholder, style = type.secondary, color = workspace.faint)
                }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    enabled = !generating,
                    textStyle = type.body.copy(color = workspace.ink),
                    cursorBrush = SolidColor(tokens.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val actionEnabled = if (generating) true else canSend
            val targetBg = when {
                generating -> workspace.red
                canSend -> tokens.ink
                else -> workspace.row
            }
            val targetFg = when {
                generating -> workspace.paper
                canSend -> tokens.bg
                else -> workspace.faint
            }
            val actionBg by animateColorAsState(
                targetValue = targetBg,
                animationSpec = tween(NovelMotion.FastMs, easing = FastOutSlowInEasing),
                label = "novelComposerActionBg",
            )
            val actionFg by animateColorAsState(
                targetValue = targetFg,
                animationSpec = tween(NovelMotion.FastMs, easing = FastOutSlowInEasing),
                label = "novelComposerActionFg",
            )
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(actionBg)
                    .then(
                        if (actionEnabled) {
                            Modifier.pressable(onClick = if (generating) onStop else onSend)
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = generating,
                    transitionSpec = {
                        (
                            fadeIn(tween(NovelMotion.FastMs)) +
                                scaleIn(initialScale = 0.7f, animationSpec = tween(NovelMotion.FastMs))
                            ) togetherWith (
                            fadeOut(tween(NovelMotion.FastMs)) +
                                scaleOut(targetScale = 0.7f, animationSpec = tween(NovelMotion.FastMs))
                            )
                    },
                    label = "novelComposerActionIcon",
                ) { isGenerating ->
                    Icon(
                        imageVector = if (isGenerating) HugeIcons.Cancel01 else HugeIcons.ArrowUp02,
                        contentDescription = if (isGenerating) "停止" else "发送",
                        tint = actionFg,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NovelMaterialsTab(
    viewModel: NovelWorkspaceViewModel,
    state: NovelWorkspaceUiState,
) {
    val document = state.document ?: return
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = LocalAmberTokens.current.ink,
        unfocusedBorderColor = workspace.hairline,
        focusedContainerColor = workspace.paper,
        unfocusedContainerColor = workspace.paper,
    )

    Column(Modifier.fillMaxSize()) {
        state.errorMessage?.let { msg ->
            NovelBanner(
                text = msg,
                tone = WorkspaceTone.Danger,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        NovelChipRow(Modifier.padding(vertical = 6.dp)) {
            NovelMaterialsCategory.entries.forEach { category ->
                NovelChipButton(
                    text = when (category) {
                        NovelMaterialsCategory.Manuscript -> "正文"
                        NovelMaterialsCategory.Characters -> "角色"
                        NovelMaterialsCategory.World -> "世界观"
                        NovelMaterialsCategory.Plot -> "剧情"
                        NovelMaterialsCategory.More -> "更多"
                    },
                    selected = state.materialsCategory == category,
                    onClick = { viewModel.selectMaterialsCategory(category) },
                    compact = true,
                )
            }
        }

        AnimatedContent(
            targetState = state.materialsCategory,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                NovelMotion.horizontalByIndex(
                    initialIndex = initialState.ordinal,
                    targetIndex = targetState.ordinal,
                )
            },
            label = "novelMaterialsCategory",
        ) { category ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (category) {
                NovelMaterialsCategory.Manuscript -> {
                    val branch = viewModel.currentBranch()
                    if (branch?.syncStatus == NovelBranchSyncStatus.NeedsSync) {
                        item {
                            NovelBanner(
                                text = "正文已改写，需同步后才能正式生成 / 收录。",
                                tone = WorkspaceTone.Warning,
                                actionLabel = "同步状态",
                                onAction = viewModel::syncManualEdits,
                            )
                        }
                    }
                    val selections = branch?.workingChapterSelections.orEmpty()
                    if (selections.isEmpty()) {
                        item {
                            NovelEmptyState(
                                title = "还没有正式章节",
                                subtitle = "在创作页生成候选并点「收录正文」，章节会出现在这里。",
                            )
                        }
                    } else {
                        items(selections, key = { it.chapterID.rawValue }) { selection ->
                            val version = document.chapterVersions
                                .firstOrNull { it.id == selection.versionID }
                            var editing by remember(selection.chapterID.rawValue) {
                                mutableStateOf(false)
                            }
                            var title by remember(version?.id?.rawValue) {
                                mutableStateOf(version?.title.orEmpty())
                            }
                            var body by remember(version?.id?.rawValue) {
                                mutableStateOf(version?.content.orEmpty())
                            }
                            NovelSectionCard(
                                title = version?.title ?: "章节",
                                meta = "正文",
                            ) {
                                if (!editing) {
                                    NovelBodyText(
                                        text = version?.content.orEmpty().ifBlank { "（空）" },
                                        muted = version?.content.isNullOrBlank(),
                                    )
                                } else {
                                    OutlinedTextField(
                                        value = title,
                                        onValueChange = { title = it },
                                        label = { Text("标题") },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = fieldColors,
                                    )
                                    OutlinedTextField(
                                        value = body,
                                        onValueChange = { body = it },
                                        label = { Text("正文") },
                                        minLines = 6,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = fieldColors,
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (version != null && !editing) {
                                        NovelGhostButton(
                                            text = "整章润色",
                                            onClick = { viewModel.polishChapter(version.id) },
                                        )
                                    }
                                    NovelGhostButton(
                                        text = if (editing) "收起" else "编辑",
                                        onClick = { editing = !editing },
                                    )
                                    if (editing) {
                                        NovelPrimaryButton(
                                            text = "保存修改",
                                            onClick = {
                                                viewModel.saveManualEdit(
                                                    selection.chapterID,
                                                    title,
                                                    body,
                                                )
                                                editing = false
                                            },
                                            accent = true,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                NovelMaterialsCategory.Characters -> {
                    val characters = document.materials.filter {
                        !it.isDeleted && it.kind is NovelMaterialKind.Character
                    }
                    if (characters.isEmpty()) {
                        item {
                            NovelEmptyState(
                                title = "还没有人物档案",
                                subtitle = "快速开始的设定建议确认后会出现在这里。",
                            )
                        }
                    } else {
                        items(characters, key = { it.id.rawValue }) { material ->
                            val revision = document.materialRevisions
                                .firstOrNull { it.id == material.currentRevisionID }
                            val branch = viewModel.currentBranch()
                            val stateSnap = document.stateSnapshots
                                .firstOrNull { it.id == branch?.currentStateSnapshotID }
                            val events = stateSnap?.eventIDs
                                ?.mapNotNull { id -> document.events.firstOrNull { it.id == id } }
                                .orEmpty()
                            val matches = NovelCharacterEventMatcher.matchExperiences(
                                characterTitle = revision?.title.orEmpty(),
                                events = events,
                            )
                            NovelSectionCard(
                                title = revision?.title ?: "角色",
                                meta = "人物",
                            ) {
                                NovelBodyText(
                                    text = revision?.content.orEmpty().ifBlank { "（空）" },
                                    muted = revision?.content.isNullOrBlank(),
                                )
                                if (matches.isNotEmpty()) {
                                    Text(
                                        "当前分支经历",
                                        style = type.meta,
                                        color = workspace.muted,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    matches.take(5).forEach { match ->
                                        Text(
                                            "· ${match.event.summary}",
                                            style = type.secondary,
                                            color = workspace.ink,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                NovelMaterialsCategory.World -> {
                    val worlds = document.materials.filter {
                        !it.isDeleted && it.kind is NovelMaterialKind.World
                    }
                    if (worlds.isEmpty()) {
                        item {
                            NovelEmptyState(
                                title = "还没有世界观资料",
                                subtitle = "确认设定建议，或在对话中沉淀世界观条目。",
                            )
                        }
                    } else {
                        items(worlds, key = { it.id.rawValue }) { material ->
                            val revision = document.materialRevisions
                                .firstOrNull { it.id == material.currentRevisionID }
                            NovelSectionCard(
                                title = revision?.title ?: "世界观",
                                meta = "世界",
                            ) {
                                NovelBodyText(
                                    text = revision?.content.orEmpty().ifBlank { "（空）" },
                                    muted = revision?.content.isNullOrBlank(),
                                )
                            }
                        }
                    }
                }

                NovelMaterialsCategory.Plot -> {
                    val branch = viewModel.currentBranch()
                    val snap = document.stateSnapshots
                        .firstOrNull { it.id == branch?.currentStateSnapshotID }
                    val outline = document.materials.firstOrNull {
                        !it.isDeleted && it.kind is NovelMaterialKind.MasterOutline
                    }?.let { m ->
                        document.materialRevisions
                            .firstOrNull { it.id == m.currentRevisionID }
                            ?.content
                    }.orEmpty()

                    item {
                        NovelSectionCard(title = "总纲", meta = "大纲") {
                            NovelBodyText(
                                text = outline.ifBlank { "（空）" },
                                muted = outline.isBlank(),
                            )
                        }
                    }
                    item {
                        NovelSectionCard(title = "当前分支摘要") {
                            NovelBodyText(
                                text = snap?.summary.orEmpty().ifBlank { "（空）" },
                                muted = snap?.summary.isNullOrBlank(),
                            )
                        }
                    }
                    item {
                        NovelSectionCard(title = "分支走向") {
                            NovelBodyText(
                                text = snap?.branchOutline.orEmpty().ifBlank { "（空）" },
                                muted = snap?.branchOutline.isNullOrBlank(),
                            )
                        }
                    }
                    item { SectionLabel(text = "事件时间线") }
                    val events = snap?.eventIDs
                        ?.mapNotNull { id -> document.events.firstOrNull { it.id == id } }
                        .orEmpty()
                        .sortedByDescending { it.sequence }
                    if (events.isEmpty()) {
                        item {
                            Text("暂无事件", style = type.secondary, color = workspace.muted)
                        }
                    } else {
                        items(events, key = { it.id.rawValue }) { event ->
                            AmberCard(Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Text(
                                        "#${event.sequence}",
                                        style = type.meta,
                                        color = LocalAmberTokens.current.accent,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        event.summary,
                                        style = type.body,
                                        color = workspace.ink,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }

                NovelMaterialsCategory.More -> {
                    item {
                        NovelSectionCard(title = "写作要求") {
                            val req = document.materials.firstOrNull {
                                !it.isDeleted && it.kind is NovelMaterialKind.WritingRequirements
                            }?.let { m ->
                                document.materialRevisions
                                    .firstOrNull { it.id == m.currentRevisionID }
                                    ?.content
                            }.orEmpty()
                            NovelBodyText(
                                text = req.ifBlank { "（空）" },
                                muted = req.isBlank(),
                            )
                        }
                    }
                    item {
                        NovelSectionCard(title = "项目模型") {
                            when (val policy = document.project.modelPolicy) {
                                NovelProjectModelPolicy.Global ->
                                    NovelBodyText("跟随全局聊天模型", muted = true)
                                is NovelProjectModelPolicy.Fixed ->
                                    NovelBodyText("固定模型 · ${policy.modelID}")
                            }
                            NovelGhostButton(
                                text = "恢复跟随全局",
                                onClick = {
                                    viewModel.setModelPolicy(NovelProjectModelPolicy.Global)
                                },
                            )
                            var fixedProvider by remember { mutableStateOf("") }
                            var fixedModel by remember { mutableStateOf("") }
                            OutlinedTextField(
                                value = fixedProvider,
                                onValueChange = { fixedProvider = it },
                                label = { Text("Provider UUID") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = fieldColors,
                            )
                            OutlinedTextField(
                                value = fixedModel,
                                onValueChange = { fixedModel = it },
                                label = { Text("Model UUID") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = fieldColors,
                            )
                            NovelPrimaryButton(
                                text = "固定为该模型",
                                onClick = {
                                    if (fixedProvider.isNotBlank() && fixedModel.isNotBlank()) {
                                        viewModel.setModelPolicy(
                                            NovelProjectModelPolicy.Fixed(
                                                fixedProvider.trim(),
                                                fixedModel.trim(),
                                            ),
                                        )
                                    }
                                },
                                enabled = fixedProvider.isNotBlank() && fixedModel.isNotBlank(),
                                accent = true,
                            )
                        }
                    }
                    item {
                        NovelSectionCard(title = "润色偏好") {
                            var pref by remember(document.project.polishPreference) {
                                mutableStateOf(document.project.polishPreference)
                            }
                            OutlinedTextField(
                                value = pref,
                                onValueChange = { pref = it },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                shape = RoundedCornerShape(12.dp),
                                colors = fieldColors,
                            )
                            NovelPrimaryButton(
                                text = "保存润色偏好",
                                onClick = { viewModel.setPolishPreference(pref) },
                                accent = true,
                            )
                        }
                    }
                    item {
                        NovelSectionCard(title = "导出 Markdown") {
                            val context = LocalContext.current
                            val scope = rememberCoroutineScope()
                            var pendingMd by remember { mutableStateOf<Pair<String, String>?>(null) }
                            val createMd = rememberLauncherForActivityResult(
                                ActivityResultContracts.CreateDocument("text/markdown"),
                            ) { uri ->
                                val payload = pendingMd
                                pendingMd = null
                                if (uri == null || payload == null) return@rememberLauncherForActivityResult
                                scope.launch(Dispatchers.IO) {
                                    context.contentResolver.openOutputStream(uri)?.use {
                                        it.write(payload.second.toByteArray(Charsets.UTF_8))
                                    }
                                }
                            }
                            NovelBodyText(
                                "导出当前分支的正式章节为 Markdown 文件。",
                                muted = true,
                            )
                            NovelGhostButton(
                                text = "导出当前分支",
                                onClick = {
                                    viewModel.exportMarkdown { name, content ->
                                        pendingMd = name to content
                                        createMd.launch(name)
                                    }
                                },
                            )
                        }
                    }
                    item {
                        NovelSectionCard(title = "分支管理") {
                            document.branches
                                .filter { it.lifecycle == NovelBranchLifecycle.Active }
                                .forEach { branch ->
                                    val main = branch.id == document.project.mainBranchID
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                branch.name,
                                                style = type.sessionTitle,
                                                color = workspace.ink,
                                            )
                                            if (main) {
                                                Text(
                                                    "主分支",
                                                    style = type.meta,
                                                    color = LocalAmberTokens.current.accent,
                                                )
                                            }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            if (!main) {
                                                NovelGhostButton(
                                                    text = "设为主分支",
                                                    onClick = {
                                                        viewModel.setMainBranch(branch.id)
                                                    },
                                                )
                                            }
                                            NovelGhostButton(
                                                text = "重命名+",
                                                onClick = {
                                                    viewModel.renameBranch(
                                                        branch.id,
                                                        branch.name + "+",
                                                    )
                                                },
                                            )
                                        }
                                    }
                                }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NovelGhostButton(
                                    text = "Fork 新分支",
                                    onClick = { viewModel.forkFromHead("分支") },
                                )
                                NovelGhostButton(
                                    text = "撤销最近收录",
                                    onClick = viewModel::undoHead,
                                    danger = true,
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        }
    }
}

