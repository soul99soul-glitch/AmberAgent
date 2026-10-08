package app.amber.feature.ui.pages.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.ModelType
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.core.settings.findModelById
import app.amber.core.settings.getCurrentChatModel
import app.amber.feature.ui.components.ai.TopModelMenu
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.novel.workspace.NovelWorkspaceGhostwriteCoordinator
import app.amber.feature.novelworkspace.NovelWorkspaceBranches
import app.amber.feature.novelworkspace.NovelWorkspaceCatalog
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteStage
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import kotlinx.coroutines.delay
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.BotMessageSquare
import com.composables.icons.lucide.WandSparkles
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.X
import com.composables.icons.lucide.CircleX
import com.composables.icons.lucide.SquarePen
import com.composables.icons.lucide.Zap
import com.composables.icons.lucide.Compass
import com.composables.icons.lucide.Database
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Notebook
import com.composables.icons.lucide.CirclePlay
import com.composables.icons.lucide.PenLine
import com.composables.icons.lucide.CheckCheck
import com.composables.icons.lucide.GitBranch
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Ellipsis
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Experimental workspace-native novel screen. Chat drives the agent loop over the
 * markdown tree; the manuscript tab reads real chapter files.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelMarkdownWorkspacePage(
    projectId: String,
    branchSlug: String? = null,
    jobId: String? = null,
    viewModel: NovelMarkdownWorkspaceViewModel = koinViewModel(
        key = "$projectId:$branchSlug:$jobId",
        parameters = { parametersOf(projectId, app.amber.feature.novelworkspace.NovelWorkspaceFocus(branchSlug, jobId)) },
    ),
) {
    DisposableEffect(viewModel) {
        onDispose { viewModel.stopTurn() }
    }
    val observedState by viewModel.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.key(observedState.restoreVersion) {
    val state = observedState.copy(busy = observedState.busy || observedState.loading)
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val appSettings = LocalSettings.current
    val navController = LocalNavController.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val clearPageFocus = {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }
    var tab by remember { mutableStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    val settingsTransition = updateTransition(showSettings, label = "novelSettings")
    val tabTransition = updateTransition(tab, label = "novelWorkspaceTab")
    val pageOffsetPx = with(LocalDensity.current) { 12.dp.roundToPx() }
    var composerDraft by remember(projectId, state.branchSlug) { mutableStateOf("") }
    val chatListState = remember(projectId, state.branchSlug) { LazyListState() }
    var openChapterPath by remember(projectId, state.branchSlug) { mutableStateOf<String?>(null) }
    var readerOpen by remember(projectId, state.branchSlug) { mutableStateOf(false) }
    val readerTransition = updateTransition(readerOpen, label = "novelReader")
    val manuscriptListState = remember(projectId, state.branchSlug) { LazyListState() }
    val chapterPreviewScrollState = remember(projectId, state.branchSlug, openChapterPath) { ScrollState(0) }
    var readerRefreshVersion by remember(projectId, state.branchSlug) { mutableStateOf(0) }
    val readerPolishProgress = state.ghostwriteJob
        ?.takeIf { it.mode == app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode.Polish }
        ?.let { it.jobId to it.written }
    var lastReaderPolishProgress by remember(projectId, state.branchSlug) { mutableStateOf(readerPolishProgress) }
    LaunchedEffect(readerPolishProgress) {
        // Progress comes from committed ledger entries. The final refresh clears a completed job,
        // so that observed transition also refreshes the last chapter, even at the same text length.
        if (lastReaderPolishProgress != null && readerPolishProgress != lastReaderPolishProgress) {
            readerRefreshVersion++
        }
        lastReaderPolishProgress = readerPolishProgress
    }
    var historyChapter by remember(projectId, state.branchSlug) { mutableStateOf<NovelMarkdownChapterUi?>(null) }
    var discardChapter by remember(projectId, state.branchSlug) { mutableStateOf<NovelMarkdownChapterUi?>(null) }
    var catalogCategory by remember(projectId, state.branchSlug) { mutableStateOf(CatalogCategory.Characters) }
    val catalogListState = remember(projectId, state.branchSlug) { LazyListState() }
    var showConsistencyReport by remember(projectId, state.branchSlug) { mutableStateOf(false) }
    var showGhostwrite by remember(projectId, branchSlug, jobId) { mutableStateOf(false) }
    // 批量润色：入口在正文 tab 顶部动作区；进度呈现复用代笔批次的 job 状态槽。
    var showPolish by remember { mutableStateOf(false) }
    // 分支选择留在项目控制面板中，顶栏只保留当前分支摘要。
    var showBranchSheet by remember { mutableStateOf(false) }
    LaunchedEffect(jobId, state.ghostwriteJob?.jobId) {
        if (jobId != null && state.ghostwriteJob?.jobId == jobId) {
            if (state.ghostwriteJob?.mode == app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode.Polish) {
                showPolish = true
            } else {
                showGhostwrite = true
            }
        }
    }
    // Graphite TopModelMenu：与标准 chat 同款——顶栏下方卷帘下拉（替代 ModelSelector 弹层）。
    var modelMenuOpen by remember { mutableStateOf(false) }
    // 审稿模型菜单：复用同一个 TopModelMenu 组件，两个菜单互斥展开。
    var reviewMenuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(readerOpen) {
        if (readerOpen) {
            modelMenuOpen = false
            reviewMenuOpen = false
        }
    }
    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    val reviewOverrideUuid = state.reviewModelId?.let {
        runCatching { kotlin.uuid.Uuid.parse(it) }.getOrNull()
    }

    // Keep the durable batch and workspace projection live on either tab, even when
    // the sheet is closed. A terminal refresh naturally stops this effect.
    LaunchedEffect(projectId, state.ghostwriteJob?.jobId, state.ghostwriteJob?.status) {
        if (state.ghostwriteJob?.status == "running") {
            while (true) {
                delay(2_500)
                viewModel.refreshGhostwrite()
            }
        }
    }

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    val writingOverrideUuid = state.writingModelId?.let {
        runCatching { kotlin.uuid.Uuid.parse(it) }.getOrNull()
    }
    val currentBranch = state.branches.firstOrNull { it.isCurrent }
    val branchLabel = currentBranch?.title ?: state.branchSlug.orEmpty()
    val writingOverrideModel = writingOverrideUuid?.let(appSettings::findModelById)
    val reviewOverrideModel = reviewOverrideUuid?.let(appSettings::findModelById)
    val effectiveWritingModel = writingOverrideModel ?: appSettings.getCurrentChatModel()
    val writingName = writingOverrideModel?.modelId ?: stringResource(R.string.novel_follow_global)
    val reviewName = reviewOverrideModel?.modelId ?: stringResource(R.string.novel_follow_writing)
    val effectiveWritingName = effectiveWritingModel?.modelId
        ?: stringResource(R.string.novel_ghostwrite_error_model_missing)
    val title = state.title.ifEmpty { stringResource(R.string.novel_workspace_title) }
    val modeLabel = stringResource(if (state.ghostwriteMode) R.string.novel_ghostwrite else R.string.novel_co_creation)
    BackHandler(enabled = showSettings || modelMenuOpen || reviewMenuOpen) {
        when {
            modelMenuOpen -> modelMenuOpen = false
            reviewMenuOpen -> reviewMenuOpen = false
            else -> {
                clearPageFocus()
                showSettings = false
            }
        }
    }

    readerTransition.AnimatedContent(
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            workspacePageMotion(forward = targetState, offsetPx = pageOffsetPx)
                .using(SizeTransform(clip = false) { _, _ -> tween(NovelMotion.MediumMs) })
        },
    ) { readerVisible ->
    Box(Modifier.fillMaxSize().workspaceTransitionInput(active = readerVisible == readerOpen)) {
    val readerPath = openChapterPath
    if (readerVisible && readerPath != null) {
        NovelMarkdownChapterReader(
            chapters = state.chapters,
            chapterPath = readerPath,
            active = readerVisible == readerOpen && !showSettings && !modelMenuOpen && !reviewMenuOpen,
            busy = state.busy,
            writeLocked = state.ghostwriteJob?.status in setOf("running", "paused", "failed"),
            errorMessage = state.errorMessage,
            refreshVersion = readerRefreshVersion,
            scrollState = chapterPreviewScrollState,
            readBody = viewModel::readChapter,
            onBack = { clearPageFocus(); readerOpen = false },
            onOpenChapter = { clearPageFocus(); openChapterPath = it },
            onSave = { chapter, chapterTitle, body, onSaved ->
                viewModel.saveChapterEdit(chapter.path, chapterTitle, body, onSaved)
            },
            onRewrite = { viewModel.rewriteChapter(it.ordinal) },
            onHistory = { viewModel.clearError(); historyChapter = it },
            onDiscard = { viewModel.clearError(); discardChapter = it },
            onClearError = viewModel::clearError,
        )
    } else {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = workspace.canvas,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
        ),
        topBar = {
            settingsTransition.AnimatedContent(
                transitionSpec = {
                    workspacePageMotion(forward = targetState, offsetPx = pageOffsetPx / 2)
                        .using(SizeTransform(clip = false) { _, _ -> tween(NovelMotion.MediumMs) })
                },
            ) { settingsVisible ->
                Box(Modifier.workspaceTransitionInput(active = settingsVisible == showSettings)) {
                    if (settingsVisible) {
                        NovelWorkspaceSettingsHeader(
                            title = stringResource(R.string.novel_project_settings),
                            onBack = {
                                clearPageFocus()
                                showSettings = false
                                modelMenuOpen = false
                                reviewMenuOpen = false
                            },
                        )
                    } else {
                        NovelWorkspaceHeader(
                            title = title,
                            subtitle = listOf(modeLabel, branchLabel, effectiveWritingName).filter { it.isNotBlank() }.joinToString(" · "),
                            controlsEnabled = state.exists && !state.loading,
                            onOpenControls = {
                                modelMenuOpen = false
                                reviewMenuOpen = false
                                showGhostwrite = true
                            },
                            onOpenSettings = {
                                clearPageFocus()
                                modelMenuOpen = false
                                reviewMenuOpen = false
                                showSettings = true
                            },
                            backButton = { WorkspaceBackButton() },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            settingsTransition.AnimatedContent(
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    workspacePageMotion(forward = targetState, offsetPx = pageOffsetPx)
                        .using(SizeTransform(clip = false) { _, _ -> tween(NovelMotion.MediumMs) })
                },
            ) { settingsVisible ->
            Box(Modifier.fillMaxSize().workspaceTransitionInput(active = settingsVisible == showSettings)) {
            when {
                settingsVisible -> NovelWorkspaceSettingsContent(
                    writingModel = writingName,
                    reviewModel = reviewName,
                    writingOverride = state.writingModelId != null,
                    reviewOverride = state.reviewModelId != null,
                    busy = state.busy || !state.exists,
                    errorMessage = state.errorMessage,
                    onClearError = viewModel::clearError,
                    onWritingModel = { modelMenuOpen = true; reviewMenuOpen = false },
                    onReviewModel = { reviewMenuOpen = true; modelMenuOpen = false },
                    onResetWritingModel = { viewModel.setWritingModel(null) },
                    onResetReviewModel = { viewModel.setReviewModel(null) },
                    onManageProjects = { navController.navigate(Screen.NovelProjects) {
                        popUpTo(Screen.NovelProjects) { inclusive = true }
                        launchSingleTop = true
                    } },
                    modifier = Modifier.padding(padding),
                )
                state.loading -> {
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = workspace.ink)
                }
            }
            !state.exists -> {
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        state.errorMessage ?: stringResource(R.string.novel_project_missing),
                        style = type.secondary,
                        color = workspace.muted,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            else -> {
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (state.unreadableJobFiles.isNotEmpty()) {
                        Text(
                            stringResource(R.string.parity_novel_unreadable_jobs, state.unreadableJobFiles.joinToString()),
                            color = workspace.red,
                            style = type.meta,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                        NovelWorkspaceOptions(
                            labels = listOf(
                                stringResource(R.string.novel_tab_creation),
                                stringResource(R.string.novel_tab_manuscript),
                                stringResource(R.string.novel_tab_settings),
                            ),
                            selected = tab,
                            onSelect = {
                                if (it != tab) {
                                    clearPageFocus()
                                    tab = it
                                }
                            },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                    if (tab != 0) {
                        state.errorMessage?.let { message ->
                            Text(
                                message,
                                style = type.meta,
                                color = workspace.red,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.clearError() }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    NovelWorkspaceContinuityStatus(
                        checking = state.consistencyChecking,
                        checkedChapters = state.consistencyCheckedChapters,
                        totalChapters = state.consistencyTotalChapters,
                        reportAvailable = !state.consistencyReport.isNullOrBlank(),
                        onStop = viewModel::stopTurn,
                        onViewReport = { showConsistencyReport = true },
                    )
                    tabTransition.AnimatedContent(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        transitionSpec = {
                            workspacePageMotion(forward = targetState >= initialState, offsetPx = pageOffsetPx)
                                .using(SizeTransform(clip = false) { _, _ -> tween(NovelMotion.MediumMs) })
                        },
                            ) { visibleTab ->
                    Box(Modifier.fillMaxSize().workspaceTransitionInput(active = visibleTab == tab)) {
                    when (visibleTab) {
                        0 -> MarkdownWorkspaceChat(
                            viewModel,
                            state,
                            draft = composerDraft,
                            onDraftChange = { composerDraft = it },
                            listState = chatListState,
                            onOpenGhostwrite = {
                                if (state.ghostwriteJob?.mode ==
                                    app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode.Polish
                                ) {
                                    showPolish = true
                                } else {
                                    showGhostwrite = true
                                }
                            },
                        )
                        1 -> MarkdownWorkspaceManuscript(
                            viewModel,
                            state,
                            onOpenPolish = { showPolish = true },
                            onOpenChapter = {
                                clearPageFocus()
                                openChapterPath = it
                                readerOpen = true
                            },
                            onHistory = { viewModel.clearError(); historyChapter = it },
                            onDiscard = { viewModel.clearError(); discardChapter = it },
                            listState = manuscriptListState,
                            onManageProjects = { navController.navigate(Screen.NovelProjects) {
                                popUpTo(Screen.NovelProjects) { inclusive = true }
                                launchSingleTop = true
                            } },
                        )
                        else -> MarkdownWorkspaceCatalog(
                            viewModel = viewModel,
                            state = state,
                            category = catalogCategory,
                            onSelectCategory = { catalogCategory = it },
                            listState = catalogListState,
                        )
                    }
                    }
                    }
                }
            }
        }
        }
        }

            // Graphite TopModelMenu：与 chat 页同款——从顶栏下方卷帘展开的服务商/模型
            // 手风琴（遮罩点击关闭），替代 ModelSelector 的底部弹层。
            val menuProviders = appSettings.providers.filter { p ->
                p.enabled && p.models.any { it.type == ModelType.CHAT }
            }
            // 高亮解析后的实际生效模型（项目覆盖 ?? 全局），选择任一即设为项目覆盖。
            @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
            val resolvedModelId: kotlin.uuid.Uuid? = effectiveWritingModel?.id
            TopModelMenu(
                open = modelMenuOpen,
                providers = menuProviders,
                modelType = ModelType.CHAT,
                currentProviderId = menuProviders.firstOrNull { p ->
                    p.models.any { it.id == resolvedModelId }
                }?.id,
                currentModelId = resolvedModelId,
                onSelect = { model ->
                    viewModel.setWritingModel(model.id.toString())
                    modelMenuOpen = false
                },
                onClose = { modelMenuOpen = false },
                modifier = Modifier.padding(top = padding.calculateTopPadding()),
            )
            // 审稿模型菜单：同一组件复用；选中即持久化为项目审稿覆盖（setReviewModel）。
            @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
            val reviewResolvedId: kotlin.uuid.Uuid? = reviewOverrideModel?.id ?: resolvedModelId
            TopModelMenu(
                open = reviewMenuOpen,
                providers = menuProviders,
                modelType = ModelType.CHAT,
                currentProviderId = menuProviders.firstOrNull { p ->
                    p.models.any { it.id == reviewResolvedId }
                }?.id,
                currentModelId = reviewResolvedId,
                onSelect = { model ->
                    viewModel.setReviewModel(model.id.toString())
                    reviewMenuOpen = false
                },
                onClose = { reviewMenuOpen = false },
                modifier = Modifier.padding(top = padding.calculateTopPadding()),
            )
        }
    }

    }
    }
    }

    val chapterWriteLocked = state.ghostwriteJob?.status in setOf("running", "paused", "failed")
    historyChapter?.let { selected ->
        MarkdownChapterHistorySheet(
            chapter = selected, busy = state.busy, writeLocked = chapterWriteLocked,
            errorMessage = state.errorMessage, loadHistory = viewModel::chapterHistory,
            readVersion = viewModel::readChapterVersion,
            onRestore = { hash, head, currentHash, onSaved ->
                viewModel.restoreChapterVersion(selected.path, hash, head, currentHash) {
                    readerRefreshVersion++
                    onSaved()
                }
            },
            onDismiss = { historyChapter = null },
        )
    }
    discardChapter?.let { selected ->
        MarkdownDiscardChapterDialog(
            chapter = selected, busy = state.busy, writeLocked = chapterWriteLocked,
            errorMessage = state.errorMessage, loadSnapshot = viewModel::loadDiscardedChapters,
            onDiscard = { head, tree, done ->
                viewModel.discardChapter(selected.path, head, tree) {
                    if (openChapterPath == selected.path) readerOpen = false
                    done()
                }
            },
            onDismiss = { discardChapter = null },
        )
    }

    if (showGhostwrite && state.exists && !state.loading) {
        NovelWorkspaceProjectControlsSheet(
            job = state.ghostwriteJob,
            ghostwriteMode = state.ghostwriteMode,
            branchLabel = branchLabel,
            plotStale = state.plotStale,
            unresolvedFromOrdinal = state.unresolvedFromOrdinal,
            onGhostwriteModeChange = viewModel::setGhostwriteMode,
            onBranch = { showGhostwrite = false; showBranchSheet = true },
            onOpenPolish = { showGhostwrite = false; showPolish = true },
            busy = state.busy,
            errorMessage = state.errorMessage,
            injection = state.injection,
            planAutoTick = state.planAutoTick,
            onStart = { viewModel.startGhostwriteBatch(it) },
            onPause = { viewModel.pauseGhostwriteBatch() },
            onResume = { viewModel.resumeGhostwriteBatch() },
            onRetryFailed = { viewModel.retryFailedGhostwriteBatch() },
            onCancel = { viewModel.cancelGhostwriteBatch() },
            onDismissFailure = { viewModel.dismissGhostwriteFailure() },
            onInjectionChange = { viewModel.setInjectionFlags(it) },
            onGeneratePlan = { viewModel.generateChapterPlan() },
            readChapterPlan = { viewModel.readChapterPlan() },
            saveChapterPlan = { viewModel.saveChapterPlan(it) },
            readUpcomingArc = { viewModel.readUpcomingArc() },
            saveUpcomingArc = { viewModel.saveUpcomingArc(it) },
            readWritingPreference = { viewModel.readWritingPreference() },
            saveWritingPreference = { body, onSaved ->
                viewModel.saveWritingPreference(body, onSaved)
            },
            briefPreview = { viewModel.briefPreview() },
            onDismiss = { showGhostwrite = false },
        )
    }

    if (showBranchSheet) {
        BranchSheet(
            branches = state.branches,
            branchLocked = state.ghostwriteJob?.status in setOf("running", "paused", "failed"),
            busy = state.busy,
            errorMessage = state.errorMessage,
            onSwitch = {
                showBranchSheet = false
                viewModel.switchBranch(it)
            },
            onCreate = { viewModel.createBranch(it) },
            onDismiss = { showBranchSheet = false },
        )
    }

    if (showPolish) {
        PolishBatchSheet(
            job = state.ghostwriteJob,
            busy = state.busy,
            latestOrdinal = state.chapters.maxOfOrNull { it.ordinal } ?: 0,
            errorMessage = state.errorMessage,
            onStart = { from, to ->
                if (viewModel.startPolish(from, to) && from == to) {
                    showPolish = false
                    tab = 0
                }
            },
            onPause = { viewModel.pauseGhostwriteBatch() },
            onResume = { viewModel.resumeGhostwriteBatch() },
            onRetryFailed = { viewModel.retryFailedGhostwriteBatch() },
            onCancel = { viewModel.cancelGhostwriteBatch() },
            onDismissFailure = { viewModel.dismissGhostwriteFailure() },
            onDismiss = { showPolish = false },
        )
    }

    if (showConsistencyReport && !state.consistencyChecking) state.consistencyReport?.let { report ->
        NovelWorkspaceContinuityReportDialog(report, onDismiss = { showConsistencyReport = false })
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovelWorkspaceProjectControlsSheet(
    job: NovelMarkdownGhostwriteUi?,
    ghostwriteMode: Boolean,
    branchLabel: String,
    plotStale: Boolean,
    unresolvedFromOrdinal: Int?,
    onGhostwriteModeChange: (Boolean) -> Unit,
    onBranch: () -> Unit,
    onOpenPolish: () -> Unit,
    busy: Boolean,
    onStart: (Int) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetryFailed: () -> Unit,
    onCancel: () -> Unit,
    onDismissFailure: () -> Unit,
    onInjectionChange: (app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags) -> Unit,
    onGeneratePlan: () -> Unit,
    readChapterPlan: suspend () -> String,
    saveChapterPlan: (String) -> Boolean,
    readUpcomingArc: suspend () -> String,
    saveUpcomingArc: (String) -> Boolean,
    readWritingPreference: suspend () -> String,
    saveWritingPreference: (String, () -> Unit) -> Unit,
    briefPreview: suspend () -> String,
    onDismiss: () -> Unit,
    errorMessage: String? = null,
    injection: app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags =
        app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags(),
    planAutoTick: Int = 0,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val branchOwned = job?.status == "running" ||
        job?.status == "paused" ||
        job?.status == "failed"
    val ghostwriteLabel = stringResource(R.string.novel_ghostwrite)
    val polishLabel = stringResource(R.string.novel_polish)
    val canStartBatch = job == null || job.status in setOf("completed", "cancelled")
    var controlsTab by remember { mutableStateOf(0) }
    var ghostwriteTarget by remember { mutableStateOf(1) }
    val chapterPlanInitial by produceState<String?>(initialValue = null, planAutoTick) {
        value = readChapterPlan()
    }
    val loadedChapterPlan = chapterPlanInitial
    var chapterPlanText by remember(planAutoTick, chapterPlanInitial) {
        mutableStateOf(chapterPlanInitial.orEmpty())
    }
    val chapterPlanDirty = chapterPlanText != chapterPlanInitial.orEmpty()
    val futureArcInitial by produceState<String?>(initialValue = null, planAutoTick) {
        value = readUpcomingArc()
    }
    var futureArcText by remember(futureArcInitial) { mutableStateOf(futureArcInitial.orEmpty()) }
    val writingPreferenceInitial by produceState<String?>(initialValue = null) {
        value = readWritingPreference()
    }
    var writingPreferenceText by remember(writingPreferenceInitial) {
        mutableStateOf(writingPreferenceInitial.orEmpty())
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        containerColor = workspace.canvas,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.chat_page_close), color = workspace.muted)
                }
                Text(
                    stringResource(R.string.novel_project_controls),
                    style = type.screenTitle,
                    color = workspace.ink,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                TextButton(
                    enabled = ghostwriteMode && canStartBatch && !busy && !plotStale &&
                        unresolvedFromOrdinal == null && loadedChapterPlan != null && chapterPlanText.isNotBlank(),
                    onClick = {
                        if (!chapterPlanDirty || saveChapterPlan(chapterPlanText)) onStart(ghostwriteTarget)
                    },
                ) { Text(stringResource(R.string.novel_start)) }
            }
            NovelWorkspaceOptions(
                labels = listOf(stringResource(R.string.novel_mode_preferences), stringResource(R.string.novel_context_injection)),
                selected = controlsTab,
                onSelect = { controlsTab = it },
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (errorMessage != null) {
                    Text(errorMessage, style = type.meta, color = workspace.red)
                }

            if (controlsTab == 0) {
                PanelSection(title = stringResource(R.string.novel_creation_mode)) {
                    NovelWorkspaceOptions(
                        labels = listOf(
                            stringResource(R.string.novel_collaboration_co_create),
                            stringResource(R.string.novel_collaboration_ghostwrite),
                        ),
                        selected = if (ghostwriteMode) 1 else 0,
                        enabled = !busy && !branchOwned,
                        onSelect = { onGhostwriteModeChange(it == 1) },
                    )
                    Text(
                        stringResource(if (ghostwriteMode) R.string.novel_ghostwrite_mode_description else R.string.novel_co_creation_description),
                        style = type.secondary, color = workspace.muted,
                    )
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable(enabled = !busy && !branchOwned, role = Role.Button, onClick = onBranch),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.novel_branches_title), style = type.body, color = workspace.ink,
                            modifier = Modifier.weight(1f))
                        Text(branchLabel, style = type.secondary, color = workspace.muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                        Icon(Lucide.ChevronRight, contentDescription = stringResource(R.string.novel_switch_branch),
                            modifier = Modifier.padding(start = 8.dp).size(16.dp), tint = workspace.faint)
                    }
                }
                if (ghostwriteMode || branchOwned) {
            PanelSection(title = stringResource(R.string.novel_batch_progress)) {
                if (job == null || job.status == "completed" || job.status == "cancelled") {
                    Text(
                        stringResource(R.string.novel_ghostwrite_target_desc),
                        style = type.meta,
                        color = workspace.muted,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        PanelRoundIcon(
                            icon = Lucide.Minus,
                            contentDescription = stringResource(R.string.novel_decrease_chapter),
                            enabled = ghostwriteTarget > 1,
                            onClick = { if (ghostwriteTarget > 1) ghostwriteTarget -= 1 },
                        )
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    stringResource(R.string.novel_number, ghostwriteTarget),
                                    style = type.screenTitle.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = app.amber.feature.ui.theme.AmberMono,
                                    ),
                                    color = workspace.ink,
                                )
                                Text(
                                    stringResource(R.string.novel_chapter_unit),
                                    style = type.meta,
                                    color = workspace.muted,
                                )
                            }
                        }
                        PanelRoundIcon(
                            icon = Lucide.Plus,
                            contentDescription = stringResource(R.string.novel_increase_chapter),
                            enabled = ghostwriteTarget < NovelWorkspaceGhostwriteCoordinator.MAX_GHOSTWRITE_CHAPTERS,
                            onClick = {
                                if (ghostwriteTarget < NovelWorkspaceGhostwriteCoordinator.MAX_GHOSTWRITE_CHAPTERS) {
                                    ghostwriteTarget += 1
                                }
                            },
                        )
                    }
                } else if (job.status == "failed") {
                    val failedLabel = if (
                        job.mode == app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode.Polish
                    ) {
                        polishLabel
                    } else {
                        ghostwriteLabel
                    }
                    Text(
                        stringResource(
                            R.string.novel_batch_failed,
                            failedLabel,
                            job.written,
                            job.target,
                        ),
                        style = type.body.copy(fontWeight = FontWeight.SemiBold),
                        color = workspace.red,
                    )
                    Text(
                        ghostwriteStageLabel(job),
                        style = type.meta,
                        color = workspace.red,
                    )
                    Text(
                        job.reason ?: stringResource(R.string.novel_unknown_reason),
                        style = type.meta,
                        color = workspace.red,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PanelPill(
                            text = stringResource(R.string.novel_dismiss),
                            onClick = onDismissFailure,
                        )
                        PanelPill(
                            text = stringResource(R.string.novel_continue_remaining),
                            tone = PanelTone.Accent,
                            enabled = !busy,
                            onClick = onRetryFailed,
                        )
                    }
                } else {
                    val paused = job.status == "paused"
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Graphite: signal green = liveness (running); paused stays quiet.
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (paused) workspace.row else workspace.greenContainer)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                if (!paused) {
                                    // Signal live-dot (spec: green dot = liveness).
                                    Box(
                                        Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(workspace.green),
                                    )
                                }
                                Text(
                                    if (paused) {
                                        stringResource(R.string.novel_paused)
                                    } else {
                                        stringResource(R.string.novel_in_progress)
                                    },
                                    style = type.tinyTag,
                                    color = if (paused) workspace.muted else workspace.green,
                                )
                            }
                        }
                        Text(
                            ghostwriteStageLabel(job),
                            style = type.meta.copy(fontWeight = FontWeight.SemiBold),
                            color = workspace.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            val runningLabel = if (
                                job.mode == app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode.Polish
                            ) {
                                polishLabel
                            } else {
                                ghostwriteLabel
                            }
                            Text(
                                stringResource(R.string.novel_number, job.written),
                                style = type.screenTitle.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = app.amber.feature.ui.theme.AmberMono,
                                ),
                                color = workspace.ink,
                            )
                            Text(
                                stringResource(R.string.novel_batch_progress_detail, job.target, runningLabel),
                                style = type.secondary,
                                color = workspace.muted,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(workspace.hairline),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(if (job.target == 0) 0f else job.written.toFloat() / job.target)
                                .fillMaxHeight()
                                .background(chatTheme.accent),
                        )
                    }
                    Text(
                        stringResource(R.string.novel_ghostwrite_progress_note),
                        style = type.meta,
                        color = workspace.muted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PanelPill(
                            text = if (paused) {
                                stringResource(R.string.novel_continue)
                            } else {
                                stringResource(R.string.novel_pause)
                            },
                            onClick = if (paused) onResume else onPause,
                        )
                        PanelPill(
                            text = stringResource(R.string.novel_cancel_batch),
                            tone = PanelTone.Danger,
                            onClick = onCancel,
                        )
                    }
                }
            }

            }

            // ── editable control files (one frame: the card; editor is flat) ──
            if (ghostwriteMode) {
            PanelSection(
                title = stringResource(R.string.novel_chapter_plan),
                action = {
                    PanelPill(
                        text = if (busy) {
                            stringResource(R.string.novel_generating)
                        } else {
                            stringResource(R.string.novel_generate_once)
                        },
                        tone = PanelTone.Accent,
                        enabled = !busy && !branchOwned,
                        onClick = onGeneratePlan,
                    )
                },
            ) {
                Text(
                    stringResource(R.string.novel_chapter_plan_desc),
                    style = type.meta,
                    color = workspace.muted,
                )
                if (loadedChapterPlan == null) {
                    PanelLoading()
                } else {
                    PanelEditor(
                        placeholder = stringResource(R.string.novel_chapter_plan_placeholder),
                        initial = loadedChapterPlan,
                        text = chapterPlanText,
                        enabled = !busy && !branchOwned,
                        onTextChange = { chapterPlanText = it },
                        onSave = { body, onSaved ->
                            if (saveChapterPlan(body)) onSaved()
                        },
                    )
                }
            }

            }
            PanelSection(title = stringResource(R.string.novel_future_arc)) {
                Text(
                    stringResource(R.string.novel_future_arc_desc),
                    style = type.meta,
                    color = workspace.muted,
                )
                val loadedFutureArc = futureArcInitial
                if (loadedFutureArc == null) {
                    PanelLoading()
                } else {
                    PanelEditor(
                        placeholder = stringResource(R.string.novel_future_arc_placeholder),
                        initial = loadedFutureArc,
                        text = futureArcText,
                        onTextChange = { futureArcText = it },
                        enabled = !busy && !branchOwned,
                        onSave = { body, onSaved ->
                            if (saveUpcomingArc(body)) onSaved()
                        },
                    )
                }
            }

            PanelSection(title = stringResource(R.string.novel_writing_preferences)) {
                Text(
                    stringResource(R.string.novel_writing_preferences_desc),
                    style = type.meta,
                    color = workspace.muted,
                )
                val loadedWritingPreference = writingPreferenceInitial
                if (loadedWritingPreference == null) {
                    PanelLoading()
                } else {
                    PanelEditor(
                        placeholder = stringResource(R.string.novel_writing_preferences_placeholder),
                        initial = loadedWritingPreference,
                        text = writingPreferenceText,
                        onTextChange = { writingPreferenceText = it },
                        enabled = !busy && !branchOwned,
                        onSave = saveWritingPreference,
                    )
                }
            }

                TextButton(onClick = onOpenPolish, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.novel_batch_polish), style = type.body, color = workspace.blue)
                }
                if (plotStale && !branchOwned) {
                    Text(stringResource(R.string.novel_plot_stale_warning), style = type.secondary, color = workspace.amber)
                }
                unresolvedFromOrdinal?.let {
                    Text(stringResource(R.string.novel_unresolved_chapter_warning, it), style = type.secondary, color = workspace.amber)
                }
            } else {
            // ── injection selection (hairline rows, no nested fills) ────────
            PanelSection(title = stringResource(R.string.novel_context_injection)) {
                val rows = listOf(
                    Triple(
                        stringResource(R.string.novel_injection_plot_title),
                        stringResource(R.string.novel_injection_plot_desc),
                        injection.plot,
                    ),
                    Triple(
                        stringResource(R.string.novel_injection_foreshadowing_title),
                        stringResource(R.string.novel_injection_foreshadowing_desc),
                        injection.foreshadowing,
                    ),
                    Triple(
                        stringResource(R.string.novel_injection_neighborhood_title),
                        stringResource(R.string.novel_injection_neighborhood_desc),
                        injection.neighborhood,
                    ),
                    Triple(
                        stringResource(R.string.novel_injection_decisions_title),
                        stringResource(R.string.novel_injection_decisions_desc),
                        injection.decisions,
                    ),
                )
                rows.forEachIndexed { index, (label, desc, checked) ->
                    if (index > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .height(1.dp)
                                .background(workspace.hairline),
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy) {
                                onInjectionChange(
                                    when (index) {
                                        0 -> injection.copy(plot = !checked)
                                        1 -> injection.copy(foreshadowing = !checked)
                                        2 -> injection.copy(neighborhood = !checked)
                                        else -> injection.copy(decisions = !checked)
                                    },
                                )
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(label, style = type.body, color = workspace.ink)
                            Text(desc, style = type.tinyTag, color = workspace.faint)
                        }
                        Switch(
                            checked = checked,
                            enabled = !busy,
                            onCheckedChange = {
                                onInjectionChange(
                                    when (index) {
                                        0 -> injection.copy(plot = it)
                                        1 -> injection.copy(foreshadowing = it)
                                        2 -> injection.copy(neighborhood = it)
                                        else -> injection.copy(decisions = it)
                                    },
                                )
                            },
                            modifier = Modifier.height(28.dp),
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = chatTheme.accent,
                                uncheckedTrackColor = workspace.hairline,
                            ),
                        )
                    }
                }
            }

            // ── injected-brief preview (flat text, no inner box) ────────────
            PanelSection(title = stringResource(R.string.novel_injected_brief_title)) {
                val brief by produceState<String?>(initialValue = null, injection, planAutoTick) {
                    value = briefPreview()
                }
                if (brief == null) {
                    PanelLoading()
                } else {
                    Text(
                        if (brief.orEmpty().isBlank()) {
                            stringResource(R.string.novel_injected_brief_empty)
                        } else {
                            brief.orEmpty()
                        },
                        style = type.meta,
                        color = workspace.muted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }

            }
            }
        }
    }
}

/**
 * 批量润色 sheet：范围选择（默认第 1 章～最新章）与批次进度（进度条 + 暂停/继续/重试/
 * 取消），骨架与项目控制面板的批量进度区一致，文案区分润色/代笔。
 * 批次状态复用 state.ghostwriteJob 槽（分支同时只允许一个批次，代笔占用时此处只读提示）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PolishBatchSheet(
    job: NovelMarkdownGhostwriteUi?,
    busy: Boolean,
    latestOrdinal: Int,
    onStart: (fromOrdinal: Int, toOrdinal: Int) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetryFailed: () -> Unit,
    onCancel: () -> Unit,
    onDismissFailure: () -> Unit,
    onDismiss: () -> Unit,
    errorMessage: String? = null,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val tokens = LocalAmberTokens.current
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val polish = job?.mode == app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode.Polish

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        containerColor = tokens.raised,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "//",
                    style = type.meta.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                    color = chatTheme.accent,
                )
                Text(
                    stringResource(R.string.novel_batch_polish),
                    style = type.sessionTitle.copy(fontWeight = FontWeight.Bold),
                    color = workspace.ink,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.novel_polish_badge),
                    style = type.meta.copy(
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        fontFamily = app.amber.feature.ui.theme.AmberMono,
                    ),
                    color = tokens.ink4,
                )
            }
            if (errorMessage != null) {
                Text(errorMessage, style = type.meta, color = workspace.red)
            }

            PanelSection(title = stringResource(R.string.novel_polish_progress)) {
                when {
                    // 代笔批次占用：只读提示，不能从这里开新批次（两者互斥）。
                    job != null && !polish -> {
                        Text(
                            stringResource(R.string.novel_polish_blocked_by_ghostwrite),
                            style = type.meta,
                            color = workspace.muted,
                        )
                    }
                    job != null && job.status == "failed" -> {
                        Text(
                            stringResource(
                                R.string.novel_polish_batch_failed,
                                job.written,
                                job.target,
                            ),
                            style = type.body.copy(fontWeight = FontWeight.SemiBold),
                            color = workspace.red,
                        )
                        Text(
                            ghostwriteStageLabel(job),
                            style = type.meta,
                            color = workspace.red,
                        )
                        Text(
                            job.reason ?: stringResource(R.string.novel_unknown_reason),
                            style = type.meta,
                            color = workspace.red,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PanelPill(
                                text = stringResource(R.string.novel_dismiss),
                                onClick = onDismissFailure,
                            )
                            PanelPill(
                                text = stringResource(R.string.novel_continue_remaining),
                                tone = PanelTone.Accent,
                                enabled = !busy,
                                onClick = onRetryFailed,
                            )
                        }
                    }
                    job != null -> {
                        val paused = job.status == "paused"
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(if (paused) workspace.row else workspace.greenContainer)
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                            Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    if (!paused) {
                                        Box(
                                            Modifier
                                                .size(5.dp)
                                                .clip(CircleShape)
                                                .background(workspace.green),
                                        )
                                    }
                                    Text(
                                        if (paused) {
                                            stringResource(R.string.novel_paused)
                                        } else {
                                            stringResource(R.string.novel_in_progress)
                                        },
                                        style = type.tinyTag,
                                        color = if (paused) workspace.muted else workspace.green,
                                    )
                                }
                            }
                            Text(
                                ghostwriteStageLabel(job),
                                style = type.meta.copy(fontWeight = FontWeight.SemiBold),
                                color = workspace.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    stringResource(R.string.novel_number, job.written),
                                    style = type.screenTitle.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = app.amber.feature.ui.theme.AmberMono,
                                    ),
                                    color = workspace.ink,
                                )
                                Text(
                                    stringResource(R.string.novel_chapter_progress, job.target),
                                    style = type.secondary,
                                    color = workspace.muted,
                                    modifier = Modifier.padding(bottom = 6.dp),
                                )
                            }
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(workspace.hairline),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(if (job.target == 0) 0f else job.written.toFloat() / job.target)
                                    .fillMaxHeight()
                                    .background(chatTheme.accent),
                            )
                        }
                        Text(
                            stringResource(R.string.novel_polish_progress_note),
                            style = type.meta,
                            color = workspace.muted,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PanelPill(
                                text = if (paused) {
                                    stringResource(R.string.novel_continue)
                                } else {
                                    stringResource(R.string.novel_pause)
                                },
                                onClick = if (paused) onResume else onPause,
                            )
                            PanelPill(
                                text = stringResource(R.string.novel_cancel_batch),
                                tone = PanelTone.Danger,
                                onClick = onCancel,
                            )
                        }
                    }
                    else -> {
                        if (latestOrdinal <= 0) {
                            Text(
                                stringResource(R.string.novel_no_chapters_to_polish),
                                style = type.meta,
                                color = workspace.muted,
                            )
                        } else {
                            var fromOrdinal by remember { mutableStateOf(1) }
                            var toOrdinal by remember(latestOrdinal) { mutableStateOf(latestOrdinal) }
                            Text(
                                stringResource(R.string.novel_polish_range_desc),
                                style = type.meta,
                                color = workspace.muted,
                            )
                            RangeStepperRow(
                                label = stringResource(R.string.novel_start_chapter),
                                value = fromOrdinal,
                                bounds = 1..toOrdinal,
                                onChange = { fromOrdinal = it },
                            )
                            RangeStepperRow(
                                label = stringResource(R.string.novel_end_chapter),
                                value = toOrdinal,
                                bounds = fromOrdinal..latestOrdinal,
                                onChange = { toOrdinal = it },
                            )
                            PanelCtaButton(
                                text = stringResource(R.string.novel_start_polish),
                                enabled = !busy && fromOrdinal <= toOrdinal,
                                onClick = { onStart(fromOrdinal, toOrdinal) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 起/止章 stepper row（与代笔的章数步进同款几何）。 */
@Composable
private fun RangeStepperRow(
    label: String,
    value: Int,
    bounds: IntRange,
    onChange: (Int) -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        PanelRoundIcon(
            icon = Lucide.Minus,
            contentDescription = stringResource(R.string.novel_decrease_chapter_named, label),
            enabled = value > bounds.first,
            onClick = { if (value > bounds.first) onChange(value - 1) },
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    label,
                    style = type.meta,
                    color = workspace.muted,
                )
                Text(
                    stringResource(R.string.novel_chapter_number, value),
                    style = type.body.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = app.amber.feature.ui.theme.AmberMono,
                    ),
                    color = workspace.ink,
                )
            }
        }
        PanelRoundIcon(
            icon = Lucide.Plus,
            contentDescription = stringResource(R.string.novel_increase_chapter_named, label),
            enabled = value < bounds.last,
            onClick = { if (value < bounds.last) onChange(value + 1) },
        )
    }
}

private enum class PanelTone { Normal, Accent, Danger, Saved }

/**
 * Project the durable batch stage into the short status line shared by the sheet and
 * the chat banner, including the factual review before a batch polish is saved.
 */
@Composable
private fun ghostwriteStageLabel(job: NovelMarkdownGhostwriteUi): String {
    val stage = if (job.mode == app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode.Polish) {
        when (job.stage) {
            NovelWorkspaceGhostwriteStage.Reviewing -> stringResource(R.string.novel_batch_stage_polish_review)
            NovelWorkspaceGhostwriteStage.Committing -> stringResource(R.string.novel_saving)
            else -> stringResource(R.string.novel_batch_stage_polish)
        }
    } else {
        when (job.stage) {
            NovelWorkspaceGhostwriteStage.Idle,
            NovelWorkspaceGhostwriteStage.Writing ->
                stringResource(R.string.novel_batch_stage_writing)
            NovelWorkspaceGhostwriteStage.Reviewing ->
                stringResource(R.string.novel_batch_stage_reviewing)
            NovelWorkspaceGhostwriteStage.Rewriting ->
                stringResource(
                    R.string.novel_batch_stage_rewriting,
                    (job.rewriteAttempt + 1).coerceIn(1, 2),
                )
            NovelWorkspaceGhostwriteStage.Committing ->
                stringResource(R.string.novel_batch_stage_committing)
            NovelWorkspaceGhostwriteStage.Planning ->
                stringResource(R.string.novel_batch_stage_planning)
        }
    }
    val ordinal = job.currentChapterOrdinal.takeIf { it > 0 } ?: if (
        job.mode == app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode.Polish
    ) {
        (job.startOrdinal + job.written).coerceAtLeast(1)
    } else {
        (job.written + 1).coerceAtLeast(1)
    }
    return stringResource(R.string.novel_batch_stage, ordinal, stage)
}

/** A quiet section title and a single paper group keep project controls readable. */
@Composable
internal fun PanelSection(
    title: String,
    action: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = type.secondary.copy(fontWeight = FontWeight.SemiBold),
                color = workspace.muted,
                modifier = Modifier.weight(1f),
            )
            action?.invoke(this)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(workspace.paper)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/** Uniform pill action — one geometry for every panel button, tone only changes color. */
@Composable
private fun PanelPill(
    text: String,
    tone: PanelTone = PanelTone.Normal,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val workspace = workspaceColors()
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val type = LocalAmberType.current
    val (bg, fg) = when (tone) {
        PanelTone.Accent -> chatTheme.accentSoft to chatTheme.accent
        PanelTone.Danger -> workspace.redContainer to workspace.red
        PanelTone.Saved -> workspace.greenContainer to workspace.green
        PanelTone.Normal -> workspace.row to workspace.ink
    }
    Box(
        Modifier.heightIn(min = 48.dp).novelPressable(onClick = onClick, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.heightIn(min = 36.dp).clip(CircleShape)
                .background(if (enabled) bg else workspace.row)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = type.meta, color = if (enabled) fg else workspace.faint)
        }
    }
}

/** Primary surface is compact while the whole 48dp row remains tappable. */
@Composable
private fun PanelCtaButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = workspaceColors()
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val type = LocalAmberType.current
    Box(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .novelPressable(onClick = onClick, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp))
                .background(if (enabled) chatTheme.accent else workspace.row)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = type.body.copy(fontWeight = FontWeight.SemiBold),
                color = if (enabled) chatTheme.onAccent else workspace.muted,
                maxLines = 2, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PanelRoundIcon(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    NovelWorkspaceNavButton(icon = icon, contentDescription = contentDescription,
        enabled = enabled, onClick = onClick)
}

/** Flat multi-line editor: no inner frame — the card is the only box (device feedback). */
@Composable
private fun PanelLoading() {
    val workspace = workspaceColors()
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = workspace.ink,
        )
    }
}

@Composable
private fun PanelEditor(
    placeholder: String,
    initial: String,
    text: String,
    enabled: Boolean,
    onSave: (String, () -> Unit) -> Unit,
    onTextChange: (String) -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    var savedText by remember(initial) { mutableStateOf<String?>(null) }
    val saved = savedText == text
    val dirty = text != initial
    fun save(value: String) {
        onSave(value) {
            savedText = value
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp),
    ) {
        if (text.isEmpty()) {
            Text(placeholder, style = type.meta, color = workspace.faint)
        }
        BasicTextField(
            value = text,
            enabled = enabled,
            onValueChange = {
                onTextChange(it)
                savedText = null
            },
            textStyle = type.body.copy(color = workspace.ink),
            cursorBrush = SolidColor(workspace.ink),
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        )
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        if (text.isNotEmpty()) {
            PanelPill(
                text = stringResource(R.string.clear),
                enabled = enabled,
                onClick = {
                    onTextChange("")
                    save("")
                },
            )
        }
        PanelPill(
            text = if (saved) {
                stringResource(R.string.novel_saved)
            } else {
                stringResource(R.string.chat_page_save)
            },
            tone = if (saved) PanelTone.Saved else PanelTone.Accent,
            enabled = enabled && dirty,
            onClick = {
                save(text)
            },
        )
    }
}

@Composable
private fun MarkdownWorkspaceChat(
    viewModel: NovelMarkdownWorkspaceViewModel,
    state: NovelMarkdownWorkspaceUiState,
    draft: String,
    onDraftChange: (String) -> Unit,
    listState: LazyListState,
    onOpenGhostwrite: () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val ghostwriteLabel = stringResource(R.string.novel_ghostwrite)
    val polishLabel = stringResource(R.string.novel_polish)
    var reviewDraft by remember(state.branchSlug) { mutableStateOf<NovelMarkdownDraftUi?>(null) }
    var reviewProposalId by remember(state.branchSlug) { mutableStateOf<String?>(null) }
    var archiveMessage by remember(state.branchSlug) { mutableStateOf<NovelMarkdownMessageUi?>(null) }
    val reviewLocked = state.ghostwriteJob?.status in setOf("running", "paused", "failed")
    archiveMessage?.let { message ->
        MarkdownMaterialCreateSheet(
            busy = state.busy, writeLocked = reviewLocked, errorMessage = state.errorMessage,
            decision = true, initialBody = message.content,
            onCreate = { _, title, body, done -> viewModel.archiveDecision(message.id, title, body, done) },
            onDismiss = { archiveMessage = null },
        )
    }
    reviewDraft?.let { candidate ->
        MarkdownDraftReviewSheet(
            draft = candidate, chapters = state.chapters, busy = state.busy,
            writeLocked = reviewLocked, errorMessage = state.errorMessage,
            readBody = viewModel::readFileBody,
            onSave = viewModel::saveFileEdit,
            onCollect = { target, onCollected ->
                viewModel.collectDraft(candidate.path, target, onCollected = onCollected)
            },
            onDismiss = { reviewDraft = null },
        )
    }
    state.proposals.find { it.id == reviewProposalId }?.let { proposal ->
        MarkdownProposalReviewSheet(
            proposal = proposal, busy = state.busy, writeLocked = reviewLocked,
            errorMessage = state.errorMessage, readRaw = viewModel::readFileRaw,
            onSave = { entries, onSaved -> viewModel.editProposal(proposal.id, entries, onSaved) },
            onApprove = { onApproved -> viewModel.approve(proposal.id, onApproved) },
            onReject = { viewModel.reject(proposal.id) },
            onDismiss = { reviewProposalId = null },
        )
    }

    Column(Modifier.fillMaxSize()) {
        // A running batch must be visible on the page itself (device-observed: the
        // user cannot tell whether anything is happening when this OEM blocks the
        // foreground-service notification).
        val activeJob = state.ghostwriteJob
        val batchActive = activeJob?.status == "running" || activeJob?.status == "paused"
        val branchOwned = batchActive || activeJob?.status == "failed"
        if (activeJob != null && batchActive) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onOpenGhostwrite)
                    .background(workspace.canvas)
                    .border(1.dp, workspace.hairline, RoundedCornerShape(0.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (activeJob.status == "running") {
                    CircularProgressIndicator(
                        Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = workspace.blue,
                    )
                }
                val batchLabel = if (
                    activeJob.mode == app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode.Polish
                ) {
                    polishLabel
                } else {
                    ghostwriteLabel
                }
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        ghostwriteStageLabel(activeJob),
                        style = type.meta.copy(fontWeight = FontWeight.SemiBold),
                        color = workspace.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (activeJob.status == "running") {
                            stringResource(
                                R.string.novel_batch_running,
                                batchLabel,
                                activeJob.written,
                                activeJob.target,
                            )
                        } else {
                            stringResource(
                                R.string.novel_batch_paused,
                                batchLabel,
                                activeJob.written,
                                activeJob.target,
                            )
                        },
                        style = type.tinyTag,
                        color = workspace.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth(0.28f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(workspace.hairline),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(
                                if (activeJob.target == 0) 0f else activeJob.written.toFloat() / activeJob.target,
                            )
                            .fillMaxHeight()
                            .background(workspace.green),
                    )
                }
            }
        }
        // Chapters created by the current batch naturally make plot stale; resume is
        // allowed from that durable cursor. Show the repair instruction once it releases.
        if (state.plotStale && !branchOwned) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(workspace.canvas)
                    .drawBehind {
                        drawLine(workspace.hairline, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx())
                    }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.novel_plot_stale_summary),
                    style = type.secondary,
                    color = workspace.muted,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = !state.busy,
                    onClick = {
                        viewModel.setComposerMode(NovelMarkdownComposerMode.Discuss)
                        viewModel.send("根据最新正文同步 plot/current.md")
                    },
                ) {
                    Text(stringResource(R.string.novel_sync_plot), style = type.secondary, color = workspace.amber)
                }
            }
        }
        state.unresolvedFromOrdinal?.let { fromOrdinal ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(workspace.canvas)
                    .drawBehind {
                        drawLine(workspace.hairline, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx())
                    }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(
                    stringResource(R.string.novel_unresolved_chapter_warning, fromOrdinal),
                    style = type.secondary,
                    color = workspace.muted,
                )
                FlowRow(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    TextButton(onClick = { viewModel.rewriteLaterChapters() }, enabled = !state.busy) {
                        Text(stringResource(R.string.novel_rewrite_later_chapters), style = type.secondary, color = workspace.amber)
                    }
                    TextButton(onClick = { viewModel.resolveUnresolved() }, enabled = !state.busy) {
                        Text(stringResource(R.string.novel_mark_resolved), style = type.secondary, color = workspace.muted)
                    }
                }
            }
        }
        // reverseLayout + newest-first ordering: fresh messages, the streaming bubble,
        // and errors are index 0-2, i.e. pinned at the visual bottom where the user is
        // looking — no scroll bookkeeping, no yank when history grows (device-observed:
        // long assistant answers overflowed the viewport with no auto-follow).
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.errorMessage?.let { message ->
                item(key = "error") {
                    Text(
                        message,
                        style = type.meta,
                        color = workspace.red,
                        modifier = Modifier.clickable { viewModel.clearError() },
                    )
                }
            }
            if (state.streamingText.isNotEmpty()) {
                item(key = "streaming") {
                    MarkdownChatBubble(isUser = false, content = state.streamingText, streaming = true)
                }
            }
            if (state.busy) {
                item(key = "activity") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = workspace.muted,
                        )
                        Text(
                            state.toolActivity ?: stringResource(R.string.novel_thinking),
                            style = type.meta,
                            color = workspace.muted,
                        )
                    }
                }
            }
            // Messages, drafts and approvals keep their real creation order in one timeline.
            items(novelMarkdownTimeline(state), key = { it.key }) { row ->
                Column(Modifier.fillMaxWidth().animateItem()) {
                when (row) {
                    is NovelMarkdownTimelineRow.Proposal -> {
                        val proposal = row.proposal
                        MarkdownProposalCard(
                            proposal = proposal,
                            busy = state.busy || branchOwned,
                            readRaw = viewModel::readFileRaw,
                            onApprove = { viewModel.approve(proposal.id) },
                            onReview = { viewModel.clearError(); reviewProposalId = proposal.id },
                            onReject = { viewModel.reject(proposal.id) },
                        )
                    }
                    is NovelMarkdownTimelineRow.Draft -> MarkdownDraftCard(
                        draft = row.draft,
                        onReview = { viewModel.clearError(); reviewDraft = row.draft },
                    )
                    is NovelMarkdownTimelineRow.Message -> {
                        val message = row.message
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (message.kind == "interrupted") Text(
                                stringResource(R.string.novel_output_interrupted),
                                style = type.secondary, color = workspace.muted,
                            )
                            MarkdownChatBubble(isUser = message.role == MessageRole.USER, content = message.content)
                            if (message.role == MessageRole.ASSISTANT && message.kind != "interrupted") {
                                TextButton(
                                    onClick = { viewModel.clearError(); archiveMessage = message },
                                    enabled = !state.busy && !reviewLocked,
                                    modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                                ) { Text(stringResource(R.string.novel_archive_decision), style = type.secondary) }
                            }
                        }
                    }
                }
                }
            }
            if (state.messages.isEmpty() && state.drafts.isEmpty() && state.proposals.isEmpty() && !state.busy) {
                item {
                    Text(
                        stringResource(R.string.novel_chat_empty),
                        style = type.secondary,
                        color = workspace.muted,
                    )
                }
            }
        }

        // 快捷动作：提案角色 —— 对话框收集角色名与一句话设想，交给模型把人物卡
        // 写入 setting/characters/（自由写路径，直存无需审批）。
        var showCharacterProposal by remember { mutableStateOf(false) }
        val branchLocked = state.ghostwriteJob?.status == "running" ||
            state.ghostwriteJob?.status == "paused" ||
            state.ghostwriteJob?.status == "failed"
        if (showCharacterProposal) {
            CharacterProposalDialog(
                busy = state.busy || branchLocked,
                onSubmit = { name, sketch ->
                    showCharacterProposal = false
                    viewModel.proposeCharacter(name, sketch)
                },
                onDismiss = { showCharacterProposal = false },
            )
        }

        // One compact input row; less frequent actions live in its existing mode menu.
        val tokens = LocalAmberTokens.current
        val accent = app.amber.feature.ui.pages.chat.LocalChatTheme.current.accent
        val onAccent = app.amber.feature.ui.pages.chat.LocalChatTheme.current.onAccent
        var modeMenu by remember { mutableStateOf(false) }
        // The tray shares the canvas and separates from the timeline with one hairline.
        Column(
            Modifier
                .fillMaxWidth()
                .background(workspace.canvas)
                .drawBehind {
                    drawLine(
                        color = workspace.hairline,
                        strokeWidth = 1.dp.toPx(),
                        start = Offset(x = 0f, y = 0f),
                        end = Offset(x = size.width, y = 0f),
                    )
                },
        ) {
        Row(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box {
                WorkspaceComposerButton(
                    icon = if (state.composerMode == NovelMarkdownComposerMode.WriteProse) Lucide.PenLine else Lucide.BotMessageSquare,
                    contentDescription = stringResource(R.string.novel_composer_mode),
                    enabled = !state.busy,
                    containerColor = workspace.paper,
                    tint = if (state.composerMode == NovelMarkdownComposerMode.WriteProse) accent else tokens.ink3,
                    onClick = { modeMenu = true },
                )
                DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                    listOf(
                        NovelMarkdownComposerMode.Discuss to stringResource(R.string.novel_mode_discuss),
                        NovelMarkdownComposerMode.WriteProse to stringResource(R.string.novel_mode_write_prose),
                    ).forEach { (mode, label) ->
                        // Graphite: color is the only selection signal — no check marks.
                        val selected = state.composerMode == mode
                        DropdownMenuItem(
                            text = {
                                Text(
                                    label,
                                    style = type.body,
                                    color = if (selected) accent else workspace.ink,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            onClick = {
                                viewModel.setComposerMode(mode)
                                modeMenu = false
                            },
                        )
                    }
                    HorizontalDivider(color = workspace.hairline)
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.novel_propose_character), style = type.body) },
                        enabled = !state.busy && !branchLocked,
                        onClick = { modeMenu = false; showCharacterProposal = true },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(workspace.paper)
                    .border(1.dp, workspace.hairline, RoundedCornerShape(24.dp))
                    .padding(start = 18.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    enabled = !state.busy,
                    textStyle = type.body.copy(color = workspace.ink),
                    cursorBrush = SolidColor(workspace.ink),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 1.dp, max = 120.dp),
                    decorationBox = { innerTextField ->
                        Box {
                            if (draft.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.chat_input_compose_placeholder),
                                    style = type.body,
                                    color = workspace.faint,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
            }

            val hasDraft = draft.isNotBlank()
            WorkspaceComposerButton(
                icon = if (state.busy) Lucide.X else Lucide.ArrowUp,
                contentDescription = stringResource(if (state.busy) R.string.stop else R.string.send),
                enabled = state.busy || hasDraft,
                containerColor = if (state.busy || hasDraft) accent else workspace.paper,
                tint = if (state.busy || hasDraft) onAccent else tokens.ink3,
                onClick = {
                    if (state.busy) viewModel.stopTurn()
                    else if (viewModel.send(draft)) onDraftChange("")
                },
            )
        }
        }
    }
}

@Composable
private fun WorkspaceComposerButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    containerColor: Color,
    tint: Color,
    onClick: () -> Unit,
) {
    val workspace = workspaceColors()
    Box(
        Modifier.size(48.dp).novelPressable(onClick = onClick, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(containerColor)
                .border(1.dp, workspace.hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

/** 提案角色对话框：角色名 + 一句话设想，提交后走 proposeCharacter 轮。 */
@Composable
private fun CharacterProposalDialog(
    busy: Boolean,
    onSubmit: (name: String, sketch: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    var name by remember { mutableStateOf("") }
    var sketch by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = workspace.paper,
        title = {
            Text(
                stringResource(R.string.novel_propose_character),
                fontWeight = FontWeight.SemiBold,
                color = workspace.ink,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    enabled = !busy,
                    singleLine = true,
                    placeholder = {
                        Text(
                            stringResource(R.string.novel_character_name),
                            style = type.meta,
                            color = workspace.muted,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = sketch,
                    onValueChange = { sketch = it },
                    enabled = !busy,
                    placeholder = {
                        Text(
                            stringResource(R.string.novel_character_idea_hint),
                            style = type.meta,
                            color = workspace.muted,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank(),
                onClick = { onSubmit(name, sketch) },
            ) {
                Text(stringResource(R.string.novel_generate_proposal), color = workspace.ink)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = workspace.muted)
            }
        },
    )
}

@Composable
private fun MarkdownChatBubble(isUser: Boolean, content: String, streaming: Boolean = false) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    if (!isUser) {
        app.amber.feature.ui.components.richtext.MarkdownBlock(
            content = content,
            style = type.body.copy(color = workspace.ink),
            streaming = streaming,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
        Box(
            Modifier
                .widthIn(max = maxWidth * 0.82f)
                .clip(RoundedCornerShape(16.dp, 16.dp, 5.dp, 16.dp))
                .background(workspace.ink)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(content, style = type.body, color = workspace.canvas)
        }
    }
}

@Composable
private fun MarkdownWorkspaceManuscript(
    viewModel: NovelMarkdownWorkspaceViewModel,
    state: NovelMarkdownWorkspaceUiState,
    onOpenPolish: () -> Unit,
    onOpenChapter: (String) -> Unit,
    onHistory: (NovelMarkdownChapterUi) -> Unit,
    onDiscard: (NovelMarkdownChapterUi) -> Unit,
    listState: LazyListState,
    onManageProjects: () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    var showDiscarded by remember(state.branchSlug) { mutableStateOf(false) }
    var rewritingOrdinal by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.branchSlug) { rewritingOrdinal = null }
    LaunchedEffect(state.busy) { if (!state.busy) rewritingOrdinal = null }
    val branchLocked = state.ghostwriteJob?.status in setOf("running", "paused", "failed")
    if (showDiscarded) MarkdownDiscardedSheet(
        busy = state.busy, writeLocked = branchLocked, errorMessage = state.errorMessage,
        loadSnapshot = viewModel::loadDiscardedChapters, readBody = viewModel::readFileBody,
        onRestore = viewModel::restoreDiscardedChapter,
        onDismiss = { showDiscarded = false },
    )

    var directoryMenu by remember { mutableStateOf(false) }
    val activeJob = state.ghostwriteJob
    val batchOwnsBranch = activeJob?.status in setOf("running", "paused", "failed")
    val polishOwned = batchOwnsBranch && activeJob?.mode ==
        app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode.Polish
    val writeOwned = batchOwnsBranch && !polishOwned
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.novel_manuscript_directory), style = type.sessionTitle,
                color = workspace.ink, modifier = Modifier.weight(1f))
            TextButton(enabled = !writeOwned && (!state.busy || polishOwned), onClick = onOpenPolish) {
                Icon(Lucide.WandSparkles, contentDescription = null, tint = workspace.blue, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(if (polishOwned) R.string.novel_polish_in_progress else R.string.novel_batch_polish),
                    style = type.secondary, color = workspace.blue,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Text(stringResource(R.string.novel_directory_count, state.chapters.size),
                style = type.secondary, color = workspace.muted)
            Box {
                IconButton(onClick = { directoryMenu = true }) {
                    Icon(Lucide.Ellipsis, contentDescription = stringResource(R.string.novel_more_actions),
                        tint = workspace.muted, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = directoryMenu, onDismissRequest = { directoryMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.novel_discarded_chapters)) },
                        onClick = { directoryMenu = false; viewModel.clearError(); showDiscarded = true },
                    )
                    if (state.canUndo) DropdownMenuItem(
                        text = { Text(stringResource(R.string.novel_undo_last)) },
                        enabled = !state.busy && !branchLocked,
                        onClick = { directoryMenu = false; viewModel.undoLast() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.novel_consistency_check)) },
                        enabled = !state.busy && !state.consistencyChecking,
                        onClick = { directoryMenu = false; viewModel.runConsistencyCheck() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.novel_manage_projects)) },
                        onClick = { directoryMenu = false; onManageProjects() },
                    )
                }
            }
        }
        if (writeOwned) Text(stringResource(R.string.novel_batch_in_use), style = type.secondary,
            color = workspace.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        if (state.chapters.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.novel_no_chapters), style = type.secondary, color = workspace.muted)
            }
        } else {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
        // Paper ends with the actual group, even when a short book leaves the viewport empty.
        itemsIndexed(state.chapters, key = { _, chapter -> chapter.path }) { index, chapterItem ->
            val rowShape = RoundedCornerShape(
                topStart = if (index == 0) 14.dp else 0.dp,
                topEnd = if (index == 0) 14.dp else 0.dp,
                bottomStart = if (index == state.chapters.lastIndex) 14.dp else 0.dp,
                bottomEnd = if (index == state.chapters.lastIndex) 14.dp else 0.dp,
            )
            Column(Modifier.fillMaxWidth().clip(rowShape).background(workspace.paper)) {
            if (index > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 60.dp)
                        .height(1.dp)
                        .background(workspace.hairline),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 68.dp)
                    .novelPressable(onClick = { onOpenChapter(chapterItem.path) })
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(workspace.blue.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = chapterItem.ordinal.toString(),
                        style = type.body.copy(fontWeight = FontWeight.SemiBold),
                        color = workspace.blue,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = chapterItem.title,
                        style = type.body.copy(fontWeight = FontWeight.SemiBold),
                        color = workspace.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.novel_character_count, chapterItem.charCount),
                        style = type.secondary,
                        color = workspace.muted,
                    )
                }
                var chapterMenu by remember(chapterItem.path) { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { chapterMenu = true }) {
                        Icon(Lucide.Ellipsis, contentDescription = stringResource(R.string.novel_more_actions),
                            tint = workspace.faint, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = chapterMenu, onDismissRequest = { chapterMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (rewritingOrdinal == chapterItem.ordinal)
                                R.string.novel_rewriting_chapter else R.string.novel_rewrite_chapter)) },
                            enabled = !state.busy && !branchLocked,
                            onClick = {
                                chapterMenu = false
                                if (viewModel.rewriteChapter(chapterItem.ordinal)) rewritingOrdinal = chapterItem.ordinal
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.novel_chapter_history)) },
                            onClick = { chapterMenu = false; onHistory(chapterItem) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.novel_discard_chapter), color = workspace.red) },
                            enabled = !state.busy && !branchLocked,
                            onClick = { chapterMenu = false; onDiscard(chapterItem) },
                        )
                    }
                }
            }
            }
        }
        }
        }
    }
}

/**
 * 分支 sheet：分支列表（当前标出）、「新建分支」（从当前分支分叉）、点选切换。
 * 有活跃批次（running/paused）时切换项禁用并在顶部说明——批次按 job 绑定的分支写盘，
 * 切走后进度/审批/撤销会全部错位。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BranchSheet(
    branches: List<NovelWorkspaceBranches.NovelWorkspaceBranchInfo>,
    branchLocked: Boolean,
    busy: Boolean,
    errorMessage: String?,
    onSwitch: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    var showCreate by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        containerColor = LocalAmberTokens.current.raised,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "//",
                    style = type.meta.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                    color = chatTheme.accent,
                )
                Text(
                    stringResource(R.string.novel_branches_title),
                    style = type.sessionTitle.copy(fontWeight = FontWeight.Bold),
                    color = workspace.ink,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.novel_branches_badge),
                    style = type.meta.copy(
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        fontFamily = app.amber.feature.ui.theme.AmberMono,
                    ),
                    color = LocalAmberTokens.current.ink4,
                )
            }
            if (branchLocked) {
                Text(
                    stringResource(R.string.novel_branch_locked),
                    style = type.meta,
                    color = workspace.red,
                )
            }
            if (errorMessage != null) {
                Text(errorMessage, style = type.meta, color = workspace.red)
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = workspace.paper,
                border = BorderStroke(1.dp, workspace.hairline),
            ) {
                Column {
                    branches.forEachIndexed { index, branch ->
                        if (index > 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(workspace.hairline),
                            )
                        }
                        val current = branch.isCurrent
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(if (current) workspace.row else Color.Transparent)
                                .clickable(enabled = !current && !branchLocked && !busy) {
                                    onSwitch(branch.slug)
                                }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Lucide.GitBranch,
                                contentDescription = null,
                                tint = if (current) chatTheme.accent else LocalAmberTokens.current.ink3,
                                modifier = Modifier.size(15.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (branch.isMain) branch.title else branch.slug,
                                    style = type.body.copy(fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal),
                                    color = workspace.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!branch.isMain && branch.slug != branch.title) {
                                    Text(branch.slug, style = type.tinyTag, color = workspace.faint, maxLines = 1)
                                }
                            }
                            if (current) {
                                Text(
                                    stringResource(R.string.novel_current),
                                    style = type.tinyTag,
                                    color = chatTheme.accent,
                                )
                            }
                        }
                    }
                }
            }
            // 新建分支同样受批次锁约束：createBranch 复制文件、落 fork commit 与批次
            // Worker 的写盘/提交存在竞态，存储层对任何活跃批次（任何分支）都会拒绝，
            // 这里直接禁用入口而不是等报错。
            PanelCtaButton(
                text = stringResource(R.string.novel_new_branch),
                enabled = !busy && !branchLocked,
                onClick = { showCreate = true },
            )
            Text(
                stringResource(R.string.novel_branch_description),
                style = type.meta,
                color = workspace.muted,
            )
        }
    }

    if (showCreate) {
        NewBranchDialog(
            busy = busy,
            onSubmit = { name ->
                onCreate(name)
                showCreate = false
            },
            onDismiss = { showCreate = false },
        )
    }
}

/** 新建分支对话框：分支名（从当前分支分叉）。 */
@Composable
private fun NewBranchDialog(
    busy: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = workspace.paper,
        title = {
            Text(
                stringResource(R.string.novel_new_branch),
                fontWeight = FontWeight.SemiBold,
                color = workspace.ink,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.novel_new_branch_description),
                    style = type.meta,
                    color = workspace.muted,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    enabled = !busy,
                    singleLine = true,
                    placeholder = {
                        Text(
                            stringResource(R.string.novel_branch_name_placeholder),
                            style = type.meta,
                            color = workspace.muted,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank(),
                onClick = { onSubmit(name) },
            ) {
                Text(stringResource(R.string.novel_create), color = workspace.ink)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = workspace.muted)
            }
        },
    )
}

// Retained legacy loading helper; the full-screen reader owns chapter load presentation.
@Composable
private fun ChapterBodyLoading() {
    val workspace = workspaceColors()
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            strokeWidth = 2.dp,
            color = workspace.ink,
        )
    }
}

@Composable
internal fun NovelWorkspaceSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("//", style = type.eyebrow, color = tokens.accent)
        Text(text, style = type.eyebrow, color = tokens.ink2)
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(tokens.line),
        )
    }
}

// Retained legacy chapter action helper; directory actions now live in its menus.
@Composable
private fun NovelChapterActionChip(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    accent: Boolean = false,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .height(40.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(32.dp)
                .clip(shape)
                .background(
                    when {
                        !enabled -> tokens.surface2.copy(alpha = 0.55f)
                        accent -> tokens.accent.copy(alpha = 0.13f)
                        else -> tokens.surface2
                    },
                )
                .border(
                    1.dp,
                    when {
                        !enabled -> tokens.line
                        accent -> tokens.accent.copy(alpha = 0.34f)
                        else -> tokens.line
                    },
                    shape,
                )
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = type.meta.copy(fontWeight = FontWeight.SemiBold),
                color = when {
                    !enabled -> tokens.ink4
                    accent -> tokens.accent
                    else -> tokens.ink2
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun NovelEditorSaveButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val shape = RoundedCornerShape(15.dp)
    Box(
        modifier = Modifier
            .height(40.dp)
            .widthIn(min = 64.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(32.dp)
                .clip(shape)
                .background(if (enabled) tokens.accent else tokens.surface2)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = type.meta.copy(fontWeight = FontWeight.SemiBold),
                color = if (enabled) tokens.accentInk else tokens.ink4,
                maxLines = 1,
            )
        }
    }
}

/** Header back shares the compact visible control and its larger touch target. */
@Composable
private fun WorkspaceBackButton(onClick: (() -> Unit)? = null) {
    val navController = LocalNavController.current
    NovelWorkspaceNavButton(
        icon = Lucide.ArrowLeft,
        contentDescription = stringResource(R.string.back),
        onClick = { onClick?.invoke() ?: navController.popBackStack() },
    )
}

/** Short translation and opacity changes connect pages without scaling long text. */
internal fun workspacePageMotion(forward: Boolean, offsetPx: Int): ContentTransform =
    (fadeIn(tween(NovelMotion.MediumMs)) + slideInHorizontally(
        animationSpec = tween(NovelMotion.MediumMs, easing = FastOutSlowInEasing),
        initialOffsetX = { if (forward) offsetPx else -offsetPx },
    )) togetherWith (fadeOut(tween(NovelMotion.FastMs)) + slideOutHorizontally(
        animationSpec = tween(NovelMotion.FastMs, easing = FastOutSlowInEasing),
        targetOffsetX = { if (forward) -offsetPx else offsetPx },
    ))

/** During overlap only the current page may receive pointer, keyboard or accessibility actions. */
internal fun Modifier.workspaceTransitionInput(active: Boolean): Modifier =
    this
        .onPreviewKeyEvent { !active }
        .pointerInput(active) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (!active) event.changes.forEach { it.consume() }
                }
            }
        }
        .then(if (active) Modifier else Modifier.clearAndSetSemantics {})
