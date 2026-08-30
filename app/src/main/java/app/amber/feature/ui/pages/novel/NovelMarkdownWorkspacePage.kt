package app.amber.feature.ui.pages.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.ModelType
import app.amber.agent.R
import app.amber.feature.ui.components.ai.TopModelMenu
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.novel.workspace.NovelWorkspaceCollectTarget
import app.amber.feature.novel.workspace.NovelWorkspaceGhostwriteCoordinator
import app.amber.feature.novel.workspace.NovelWorkspaceWriteProposal
import app.amber.feature.novelworkspace.NovelWorkspaceBranches
import app.amber.feature.novelworkspace.NovelWorkspaceCatalog
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteStage
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.theme.CustomColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import kotlinx.coroutines.delay
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.BotMessageSquare
import com.composables.icons.lucide.WandSparkles
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.ArrowDown
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
    viewModel: NovelMarkdownWorkspaceViewModel = koinViewModel(
        parameters = { parametersOf(projectId) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val appSettings = LocalSettings.current
    var tab by remember { mutableStateOf(0) }
    var showGhostwrite by remember { mutableStateOf(false) }
    // 批量润色：入口在正文 tab 顶部动作区；进度呈现复用代笔批次的 job 状态槽。
    var showPolish by remember { mutableStateOf(false) }
    // 分支 sheet：分支列表 / 新建 / 切换（TopBar 书名旁的分支 chip 打开）。
    var showBranchSheet by remember { mutableStateOf(false) }
    // Graphite TopModelMenu：与标准 chat 同款——顶栏下方卷帘下拉（替代 ModelSelector 弹层）。
    var modelMenuOpen by remember { mutableStateOf(false) }
    // 审稿模型菜单：复用同一个 TopModelMenu 组件，两个菜单互斥展开。
    var reviewMenuOpen by remember { mutableStateOf(false) }
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

    Scaffold(
        containerColor = workspace.canvas,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
        ),
        topBar = {
            TopAppBar(
                title = {
                    // Chat-header pattern: book/branch first, then full-width writing and
                    // review model triggers. Separate rows preserve 48dp targets on narrow phones.
                    val tokens = LocalAmberTokens.current
                    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
                    val currentModelUuid = state.writingModelId?.let {
                        runCatching { kotlin.uuid.Uuid.parse(it) }.getOrNull()
                    }
                    Column {
                        // 书名 + 分支 chip：chip 显示当前分支（主线显示主名），点击出分支 sheet。
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 4.dp),
                        ) {
                            Text(
                                if (state.title.isEmpty()) {
                                    stringResource(R.string.novel_workspace_title)
                                } else {
                                    state.title
                                },
                                style = type.sessionTitle,
                                color = tokens.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.size(8.dp))
                            val currentBranch = state.branches.firstOrNull { it.isCurrent }
                            val branchLabel = when {
                                currentBranch != null && currentBranch.isMain -> currentBranch.title
                                currentBranch != null -> currentBranch.slug
                                else -> state.branchSlug.orEmpty()
                            }
                            Row(
                                modifier = Modifier
                                    .widthIn(max = 140.dp)
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(workspace.paper)
                                    .border(1.dp, workspace.hairline, RoundedCornerShape(999.dp))
                                    .clickable { showBranchSheet = true }
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Lucide.GitBranch,
                                    contentDescription = stringResource(R.string.novel_switch_branch),
                                    tint = tokens.ink3,
                                    modifier = Modifier.size(11.dp),
                                )
                                Text(
                                    branchLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = type.meta.copy(
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Medium,
                                    ),
                                    color = tokens.ink2,
                                )
                            }
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                        ) {
                            val chevronRotation by animateFloatAsState(
                                targetValue = if (modelMenuOpen) 180f else 0f,
                                animationSpec = tween(durationMillis = 280),
                                label = "novelModelMenuChevron",
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        modelMenuOpen = !modelMenuOpen
                                        reviewMenuOpen = false
                                    }
                                    .padding(start = 4.dp, top = 6.dp, end = 4.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                val resolvedName = currentModelUuid
                                    ?.let { id ->
                                        appSettings.providers.asSequence()
                                            .flatMap { it.models }
                                            .firstOrNull { it.id == id }?.modelId
                                    }
                                    ?: stringResource(R.string.novel_follow_global)
                                Text(
                                    resolvedName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = type.meta.copy(
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Medium,
                                    ),
                                    color = lerp(tokens.ink3, tokens.ink2, 0.5f),
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    imageVector = Lucide.ArrowDown,
                                    contentDescription = stringResource(R.string.novel_select_model),
                                    tint = tokens.ink3,
                                    modifier = Modifier
                                        .size(13.dp)
                                        .rotate(chevronRotation),
                                )
                            }
                            // 审稿模型触发行：与写作模型分行，未选择时「跟随写作」。
                            // （审稿轮解析顺序：审稿覆盖 → 写作覆盖 → 全局聊天模型）。
                            val reviewChevronRotation by animateFloatAsState(
                                targetValue = if (reviewMenuOpen) 180f else 0f,
                                animationSpec = tween(durationMillis = 280),
                                label = "novelReviewModelMenuChevron",
                            )
                            val reviewResolvedName = reviewOverrideUuid
                                ?.let { id ->
                                    appSettings.providers.asSequence()
                                        .flatMap { it.models }
                                        .firstOrNull { it.id == id }?.modelId
                                }
                                ?: stringResource(R.string.novel_follow_writing)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        reviewMenuOpen = !reviewMenuOpen
                                        modelMenuOpen = false
                                    }
                                    .padding(start = 4.dp, top = 2.dp, end = 4.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    stringResource(R.string.novel_review_model),
                                    maxLines = 1,
                                    style = type.meta.copy(
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Medium,
                                    ),
                                    color = tokens.ink3,
                                )
                                Text(
                                    reviewResolvedName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = type.meta.copy(
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Medium,
                                    ),
                                    color = lerp(tokens.ink3, tokens.ink2, 0.5f),
                                    modifier = Modifier.weight(1f),
                                )
                                if (state.reviewModelId != null) {
                                    IconButton(
                                        onClick = { viewModel.setReviewModel(null) },
                                        modifier = Modifier.size(48.dp),
                                    ) {
                                        Icon(
                                            imageVector = Lucide.X,
                                            contentDescription = stringResource(R.string.novel_clear_review_model),
                                            tint = tokens.ink4,
                                            modifier = Modifier.size(12.dp),
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = Lucide.ArrowDown,
                                    contentDescription = stringResource(R.string.novel_select_review_model),
                                    tint = tokens.ink3,
                                    modifier = Modifier
                                        .size(13.dp)
                                        .rotate(reviewChevronRotation),
                                )
                            }
                        }
                        // 产品说明：代笔每章均联合审核；未指定审稿模型时跟随写作模型。
                        Text(
                            stringResource(R.string.novel_auto_review_note),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = type.meta.copy(fontSize = 9.sp),
                            color = tokens.ink4,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (state.writingModelId != null) {
                        IconButton(
                            onClick = { viewModel.setWritingModel(null) },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = Lucide.X,
                                contentDescription = stringResource(R.string.novel_reset_to_global),
                                tint = LocalAmberTokens.current.ink3,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    // 代笔入口：accentSoft 底 + accent 字的入口胶囊（accent=进入选择，非装饰）。
                    val entryAccent = app.amber.feature.ui.pages.chat.LocalChatTheme.current
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(entryAccent.accentSoft)
                            .clickable {
                                if (state.ghostwriteJob?.mode ==
                                    app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode.Polish
                                ) {
                                    showPolish = true
                                } else {
                                    showGhostwrite = true
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(
                                if (state.ghostwriteJob?.mode ==
                                    app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode.Polish
                                ) {
                                    R.string.novel_polish
                                } else {
                                    R.string.novel_ghostwrite
                                },
                            ),
                            style = type.meta.copy(fontWeight = FontWeight.SemiBold),
                            color = entryAccent.accent,
                        )
                    }
                },
                // AMOLED 下 topBarColors 的 #161512 会在纯黑页面上拼出一条横带；
                // 统一用页面 canvas 做容器色。
                colors = CustomColors.topBarColors.copy(containerColor = workspace.canvas),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            when {
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
                        stringResource(R.string.novel_project_missing),
                        style = type.secondary,
                        color = workspace.muted,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            else -> {
                Column(Modifier.fillMaxSize().padding(padding)) {
                    // Compact segmented control — the stock TabRow ate too much height.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(workspace.paper)
                            .border(1.dp, workspace.hairline, RoundedCornerShape(10.dp)),
                    ) {
                        listOf(
                            0 to stringResource(R.string.novel_tab_creation),
                            1 to stringResource(R.string.novel_tab_manuscript),
                            2 to stringResource(R.string.novel_tab_settings),
                        ).forEach { (index, label) ->
                            val selected = tab == index
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(3.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(if (selected) workspace.ink else Color.Transparent)
                                    .clickable { tab = index },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    style = type.body,
                                    color = if (selected) workspace.canvas else workspace.muted,
                                )
                            }
                        }
                    }
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
                    when (tab) {
                        0 -> MarkdownWorkspaceChat(
                            viewModel,
                            state,
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
                        )
                        else -> MarkdownWorkspaceCatalog(viewModel, state)
                    }
                }
            }
        }

            // Graphite TopModelMenu：与 chat 页同款——从顶栏下方卷帘展开的服务商/模型
            // 手风琴（遮罩点击关闭），替代 ModelSelector 的底部弹层。
            @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
            val overrideUuid = state.writingModelId?.let {
                runCatching { kotlin.uuid.Uuid.parse(it) }.getOrNull()
            }
            val menuProviders = appSettings.providers.filter { p ->
                p.enabled && p.models.any { it.type == ModelType.CHAT }
            }
            // 高亮解析后的实际生效模型（项目覆盖 ?? 全局），选择任一即设为项目覆盖。
            @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
            val resolvedModelId: kotlin.uuid.Uuid? = overrideUuid
                ?: appSettings.chatModelId
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
            val reviewResolvedId: kotlin.uuid.Uuid? = reviewOverrideUuid ?: resolvedModelId
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

    if (showGhostwrite) {
        MarkdownGhostwriteSheet(
            job = state.ghostwriteJob,
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
            branchLocked = state.ghostwriteJob?.status == "running" || state.ghostwriteJob?.status == "paused",
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
            onStart = { from, to -> viewModel.startPolish(from, to) },
            onPause = { viewModel.pauseGhostwriteBatch() },
            onResume = { viewModel.resumeGhostwriteBatch() },
            onRetryFailed = { viewModel.retryFailedGhostwriteBatch() },
            onCancel = { viewModel.cancelGhostwriteBatch() },
            onDismissFailure = { viewModel.dismissGhostwriteFailure() },
            onDismiss = { showPolish = false },
        )
    }

    state.consistencyReport?.let { report ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissConsistencyReport() },
            containerColor = workspace.paper,
            title = {
                Text(
                    stringResource(R.string.novel_consistency_report_title),
                    fontWeight = FontWeight.SemiBold,
                    color = workspace.ink,
                )
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(report, style = type.secondary, color = workspace.ink)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissConsistencyReport() }) {
                    Text(stringResource(R.string.novel_dismiss), color = workspace.ink)
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarkdownGhostwriteSheet(
    job: NovelMarkdownGhostwriteUi?,
    busy: Boolean,
    onStart: (Int) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetryFailed: () -> Unit,
    onCancel: () -> Unit,
    onDismissFailure: () -> Unit,
    onInjectionChange: (app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags) -> Unit,
    onGeneratePlan: () -> Unit,
    readChapterPlan: () -> String,
    saveChapterPlan: (String) -> Boolean,
    readUpcomingArc: () -> String,
    saveUpcomingArc: (String) -> Boolean,
    readWritingPreference: () -> String,
    saveWritingPreference: (String, () -> Unit) -> Unit,
    briefPreview: () -> String,
    onDismiss: () -> Unit,
    errorMessage: String? = null,
    injection: app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags =
        app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags(),
    planAutoTick: Int = 0,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val tokens = LocalAmberTokens.current
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val branchOwned = job?.status == "running" ||
        job?.status == "paused" ||
        job?.status == "failed"
    val ghostwriteLabel = stringResource(R.string.novel_ghostwrite)
    val polishLabel = stringResource(R.string.novel_polish)
    val chapterPlanInitial = remember(planAutoTick) { readChapterPlan() }
    var chapterPlanText by remember(planAutoTick) { mutableStateOf(chapterPlanInitial) }
    var chapterPlanDirty by remember(planAutoTick) { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = workspace.canvas) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Canonical sheet title row (ModelList pattern): accent `//` signboard
            // + bold cn title + right-aligned mono kicker.
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
                    ghostwriteLabel,
                    style = type.sessionTitle.copy(fontWeight = FontWeight.Bold),
                    color = workspace.ink,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.novel_ghostwrite_badge),
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

            // ── batch status / control ─────────────────────────────────────
            PanelSection(title = stringResource(R.string.novel_batch_progress)) {
                if (job == null) {
                    var target by remember { mutableStateOf(5) }
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
                            enabled = target > 1,
                            onClick = { if (target > 1) target -= 1 },
                        )
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    stringResource(R.string.novel_number, target),
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
                            enabled = target < NovelWorkspaceGhostwriteCoordinator.MAX_GHOSTWRITE_CHAPTERS,
                            onClick = {
                                if (target < NovelWorkspaceGhostwriteCoordinator.MAX_GHOSTWRITE_CHAPTERS) {
                                    target += 1
                                }
                            },
                        )
                    }
                    PanelCtaButton(
                        text = stringResource(R.string.novel_start_ghostwrite),
                        enabled = !busy,
                        onClick = {
                            if (!chapterPlanDirty || saveChapterPlan(chapterPlanText)) {
                                onStart(target)
                            }
                        },
                    )
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

            // ── editable control files (one frame: the card; editor is flat) ──
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
                PanelEditor(
                    placeholder = stringResource(R.string.novel_chapter_plan_placeholder),
                    initial = chapterPlanInitial,
                    enabled = !busy && !branchOwned,
                    onTextChange = { chapterPlanText = it },
                    onDirtyChange = { chapterPlanDirty = it },
                    onSave = { body, onSaved ->
                        if (saveChapterPlan(body)) onSaved()
                    },
                )
            }

            PanelSection(title = stringResource(R.string.novel_future_arc)) {
                Text(
                    stringResource(R.string.novel_future_arc_desc),
                    style = type.meta,
                    color = workspace.muted,
                )
                PanelEditor(
                    placeholder = stringResource(R.string.novel_future_arc_placeholder),
                    initial = remember(planAutoTick) { readUpcomingArc() },
                    enabled = !busy && !branchOwned,
                    onSave = { body, onSaved ->
                        if (saveUpcomingArc(body)) onSaved()
                    },
                )
            }

            PanelSection(title = stringResource(R.string.novel_writing_preferences)) {
                Text(
                    stringResource(R.string.novel_writing_preferences_desc),
                    style = type.meta,
                    color = workspace.muted,
                )
                PanelEditor(
                    placeholder = stringResource(R.string.novel_writing_preferences_placeholder),
                    initial = remember { readWritingPreference() },
                    enabled = !busy && !branchOwned,
                    onSave = saveWritingPreference,
                )
            }

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
                            .clickable {
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
                val brief = remember(injection, planAutoTick) { briefPreview() }
                Text(
                    if (brief.isBlank()) {
                        stringResource(R.string.novel_injected_brief_empty)
                    } else {
                        brief
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

/**
 * 批量润色 sheet：范围选择（默认第 1 章～最新章）与批次进度（进度条 + 暂停/继续/重试/
 * 取消），骨架与 MarkdownGhostwriteSheet 的批量进度区一致，文案区分润色/代笔。
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

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = workspace.canvas) {
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
 * the chat banner. Polish has no candidate-bound joint-review stage, so it always uses
 * the explicit polish label even if an older job record carries a generic stage.
 */
@Composable
private fun ghostwriteStageLabel(job: NovelMarkdownGhostwriteUi): String {
    val stage = if (job.mode == app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode.Polish) {
        stringResource(R.string.novel_batch_stage_polish)
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

/**
 * Graphite section: ONE frame (card) + mono `//` eyebrow title. Everything inside
 * is flat — no nested boxes, no icon chips (device feedback: 框套框 / star icon).
 */
@Composable
private fun PanelSection(
    title: String,
    action: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(workspace.paper)
            .border(1.dp, workspace.hairline, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // `//` glyph carries the accent (device feedback); the label itself stays
            // neutral ink-3 per the spec's accent budget for section headers.
            Text(
                "//",
                style = type.tinyTag.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = app.amber.feature.ui.theme.AmberMono,
                ),
                color = app.amber.feature.ui.pages.chat.LocalChatTheme.current.accent,
            )
            Spacer(Modifier.size(6.dp))
            Text(
                title,
                style = type.tinyTag.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    fontFamily = app.amber.feature.ui.theme.AmberMono,
                ),
                color = LocalAmberTokens.current.ink3,
                modifier = Modifier.weight(1f),
            )
            action?.invoke(this)
        }
        content()
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
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (enabled) bg else workspace.row)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = type.meta, color = if (enabled) fg else workspace.faint)
    }
}

/** Full-width primary CTA. */
@Composable
private fun PanelCtaButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val workspace = workspaceColors()
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val type = LocalAmberType.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) chatTheme.accent else workspace.row)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = type.body.copy(fontWeight = FontWeight.SemiBold),
            color = if (enabled) chatTheme.onAccent else workspace.muted,
        )
    }
}

/** 48dp flat round stepper chip. */
@Composable
private fun PanelRoundIcon(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val workspace = workspaceColors()
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(workspace.row)
            .border(1.dp, workspace.hairline, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (enabled) workspace.ink else workspace.faint, modifier = Modifier.size(18.dp))
    }
}

/** Flat multi-line editor: no inner frame — the card is the only box (device feedback). */
@Composable
private fun PanelEditor(
    placeholder: String,
    initial: String,
    enabled: Boolean,
    onSave: (String, () -> Unit) -> Unit,
    onTextChange: (String) -> Unit = {},
    onDirtyChange: (Boolean) -> Unit = {},
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    var text by remember(initial) { mutableStateOf(initial) }
    var savedTick by remember(initial) { mutableStateOf(false) }
    val dirty = text != initial
    LaunchedEffect(dirty) { onDirtyChange(dirty) }
    fun save(value: String) {
        onSave(value) {
            if (text == value) savedTick = true
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
                text = it
                onTextChange(it)
                savedTick = false
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
                    text = ""
                    onTextChange("")
                    save("")
                },
            )
        }
        PanelPill(
            text = if (savedTick) {
                stringResource(R.string.novel_saved)
            } else {
                stringResource(R.string.chat_page_save)
            },
            tone = if (savedTick) PanelTone.Saved else PanelTone.Accent,
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
    onOpenGhostwrite: () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val ghostwriteLabel = stringResource(R.string.novel_ghostwrite)
    val polishLabel = stringResource(R.string.novel_polish)
    var draft by remember { mutableStateOf("") }

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
                    .background(workspace.paper)
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
                    .background(workspace.red.copy(alpha = 0.10f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.novel_plot_stale_warning),
                    style = type.meta,
                    color = workspace.red,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = !state.busy,
                    onClick = {
                        viewModel.setComposerMode(NovelMarkdownComposerMode.Discuss)
                        viewModel.send("根据最新正文同步 plot/current.md")
                    },
                ) {
                    Text(stringResource(R.string.novel_sync_plot), color = workspace.red)
                }
            }
        }
        state.unresolvedFromOrdinal?.let { fromOrdinal ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(workspace.red.copy(alpha = 0.14f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.novel_unresolved_chapter_warning, fromOrdinal),
                    style = type.meta,
                    color = workspace.red,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { viewModel.rewriteLaterChapters() },
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.novel_rewrite_later_chapters), color = workspace.ink)
                }
                TextButton(
                    onClick = { viewModel.resolveUnresolved() },
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.novel_mark_resolved), color = workspace.muted)
                }
            }
        }
        // reverseLayout + newest-first ordering: fresh messages, the streaming bubble,
        // and errors are index 0-2, i.e. pinned at the visual bottom where the user is
        // looking — no scroll bookkeeping, no yank when history grows (device-observed:
        // long assistant answers overflowed the viewport with no auto-follow).
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
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
            for (proposal in state.proposals) {
                item(key = "proposal-${proposal.id}") {
                    MarkdownProposalCard(
                        proposal = proposal,
                        busy = state.busy || branchOwned,
                        onApprove = { viewModel.approve(proposal.id) },
                        onReject = { viewModel.reject(proposal.id) },
                    )
                }
            }
            if (state.drafts.isNotEmpty()) {
                item(key = "drafts-header") {
                    Text(
                        stringResource(R.string.novel_drafts_count, state.drafts.size),
                        style = type.meta,
                        color = workspace.muted,
                    )
                }
                items(state.drafts, key = { it.path }) { draft ->
                    MarkdownDraftCard(
                        draft = draft,
                        hasChapters = state.chapters.isNotEmpty(),
                        busy = state.busy || branchOwned,
                        onCollectNew = { viewModel.collectDraft(draft.path, NovelWorkspaceCollectTarget.NewChapter) },
                        onCollectAppend = {
                            state.chapters.lastOrNull()?.let { last ->
                                viewModel.collectDraft(
                                    draft.path,
                                    NovelWorkspaceCollectTarget.AppendToChapter(last.path),
                                )
                            }
                        },
                    )
                }
            }
            // Chronological order REVERSED inside the block: reverseLayout renders the
            // last list item at the top, so newest-first iteration puts the latest
            // message at the block's bottom edge, right next to the activity/error rows.
            items(state.messages.asReversed(), key = { it.id }) { message ->
                MarkdownChatBubble(isUser = message.role == MessageRole.USER, content = message.content)
            }
            if (state.messages.isEmpty() && !state.busy) {
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
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                enabled = !state.busy && !branchLocked,
                onClick = { showCharacterProposal = true },
            ) {
                Text(
                    stringResource(R.string.novel_propose_character),
                    color = workspace.ink,
                    style = type.meta,
                )
            }
        }
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

        // Composer mirrors the standard chat tray (Graphite §6.2): circular mode chip ·
        // 26dp-radius input pill · circular send/stop — 46dp circles, 9dp gaps, one 48dp row.
        // Graphite invariant 5: accent = selection (themeable), never a hardcoded blue.
        val tokens = LocalAmberTokens.current
        val accent = app.amber.feature.ui.pages.chat.LocalChatTheme.current.accent
        val onAccent = app.amber.feature.ui.pages.chat.LocalChatTheme.current.onAccent
        var modeMenu by remember { mutableStateOf(false) }
        // Graphite 不变量 4：composer 是带顶部发丝线的 tray 面（chat 同款）。
        Column(
            Modifier
                .fillMaxWidth()
                .background(workspace.paper)
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
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(tokens.surface2)
                        .clickable(enabled = !state.busy) { modeMenu = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (state.composerMode == NovelMarkdownComposerMode.WriteProse) {
                            Lucide.PenLine
                        } else {
                            Lucide.BotMessageSquare
                        },
                        contentDescription = stringResource(R.string.novel_composer_mode),
                        tint = if (state.composerMode == NovelMarkdownComposerMode.WriteProse) {
                            accent
                        } else {
                            tokens.ink3
                        },
                        modifier = Modifier.size(22.dp),
                    )
                }
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
                }
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 46.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(tokens.surface2)
                    .border(1.dp, tokens.line, RoundedCornerShape(26.dp))
                    .padding(start = 18.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    enabled = !state.busy,
                    textStyle = type.body.copy(color = workspace.ink),
                    cursorBrush = SolidColor(workspace.ink),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 1.dp, max = 120.dp),
                )
            }

            val hasDraft = draft.isNotBlank()
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (state.busy || hasDraft) accent else tokens.surface2,
                    )
                    .clickable(
                        enabled = state.busy || hasDraft,
                    ) {
                        if (state.busy) {
                            viewModel.stopTurn()
                        } else {
                            if (viewModel.send(draft)) draft = ""
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (state.busy) Lucide.X else Lucide.ArrowUp,
                    contentDescription = if (state.busy) {
                        stringResource(R.string.stop)
                    } else {
                        stringResource(R.string.send)
                    },
                    // 与 ChatInput 一致：accent 实心圆上的字形固定浅色（sage-green
                    // 这类浅 accent 的 onAccent 是近黑，箭头会一黑一白不一致）。
                    tint = if (state.busy || hasDraft) Color.White else tokens.ink3,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
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
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.TopEnd else Alignment.TopStart,
    ) {
        Box(
            Modifier
                .then(if (isUser) Modifier.widthIn(max = maxWidth * 0.82f) else Modifier.fillMaxWidth())
                .clip(
                    if (isUser) {
                        // Graphite user bubble: solid ink fill + inverted text, asymmetric
                        // radius 16/16/5/16 (tail toward the sender).
                        RoundedCornerShape(16.dp, 16.dp, 5.dp, 16.dp)
                    } else {
                        RoundedCornerShape(14.dp)
                    },
                )
                .background(if (isUser) workspace.ink else workspace.paper)
                .then(if (isUser) Modifier else Modifier.border(1.dp, workspace.hairline, RoundedCornerShape(14.dp)))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (isUser) {
                Text(
                    content,
                    style = type.body,
                    color = workspace.canvas,
                )
            } else {
                // Assistant replies carry markdown (headings/bold/lists) — render them
                // instead of showing raw syntax (device-observed bare ## / **).
                app.amber.feature.ui.components.richtext.MarkdownBlock(
                    content = content,
                    style = type.body.copy(color = workspace.ink),
                    streaming = streaming,
                )
            }
        }
    }
}

@Composable
private fun MarkdownDraftCard(
    draft: app.amber.feature.ui.pages.novel.NovelMarkdownDraftUi,
    hasChapters: Boolean,
    busy: Boolean,
    onCollectNew: () -> Unit,
    onCollectAppend: () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    AmberCard(
        Modifier.fillMaxWidth(),
        containerColor = workspace.paper,
        borderColor = workspace.hairline,
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                draft.title,
                style = type.body.copy(fontWeight = FontWeight.SemiBold),
                color = workspace.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                draft.excerpt,
                style = type.secondary,
                color = workspace.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCollectNew, enabled = !busy) {
                    Text(stringResource(R.string.novel_collect_as_new_chapter), color = workspace.ink)
                }
                if (hasChapters) {
                    TextButton(onClick = onCollectAppend, enabled = !busy) {
                        Text(stringResource(R.string.novel_append_to_last_chapter), color = workspace.muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownProposalCard(
    proposal: NovelWorkspaceWriteProposal,
    busy: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    AmberCard(
        Modifier.fillMaxWidth(),
        containerColor = workspace.paper,
        borderColor = workspace.hairline,
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.novel_write_proposal_title),
                style = type.body.copy(fontWeight = FontWeight.SemiBold),
                color = workspace.ink,
            )
            proposal.entries.forEach { entry ->
                Text(
                    entry.path,
                    style = type.meta,
                    color = workspace.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.content.take(240),
                    style = type.secondary,
                    color = workspace.ink,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onReject, enabled = !busy) {
                    Text(stringResource(R.string.novel_reject), color = workspace.muted)
                }
                TextButton(onClick = onApprove, enabled = !busy) {
                    Text(stringResource(R.string.novel_confirm_write), color = workspace.ink)
                }
            }
        }
    }
}

@Composable
private fun MarkdownChapterEditor(
    chapter: NovelMarkdownChapterUi,
    initialBody: String,
    busy: Boolean,
    writeLocked: Boolean,
    onSave: (title: String, body: String) -> Unit,
    onCancel: () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    var title by remember(chapter.path) { mutableStateOf(chapter.title) }
    var body by remember(chapter.path) { mutableStateOf(initialBody) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onCancel,
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(R.string.cancel),
                    color = workspace.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                stringResource(R.string.novel_edit_chapter),
                style = type.body.copy(fontWeight = FontWeight.SemiBold),
                color = workspace.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(2f),
            )
            TextButton(
                onClick = { onSave(title, body) },
                enabled = !busy && !writeLocked,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (busy) {
                        stringResource(R.string.novel_saving)
                    } else {
                        stringResource(R.string.chat_page_save)
                    },
                    color = workspace.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicTextField(
                value = title,
                onValueChange = { title = it },
                enabled = !busy && !writeLocked,
                singleLine = true,
                textStyle = type.body.copy(color = workspace.ink, fontWeight = FontWeight.SemiBold),
                cursorBrush = SolidColor(workspace.ink),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(workspace.paper)
                    .border(1.dp, workspace.hairline, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
            BasicTextField(
                value = body,
                onValueChange = { body = it },
                enabled = !busy && !writeLocked,
                textStyle = type.body.copy(color = workspace.ink),
                cursorBrush = SolidColor(workspace.ink),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(workspace.paper)
                    .border(1.dp, workspace.hairline, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
            Spacer(Modifier.size(48.dp))
        }
    }
}

@Composable
private fun MarkdownWorkspaceManuscript(
    viewModel: NovelMarkdownWorkspaceViewModel,
    state: NovelMarkdownWorkspaceUiState,
    onOpenPolish: () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    var openChapter by remember { mutableStateOf<NovelMarkdownChapterUi?>(null) }
    var editingChapter by remember { mutableStateOf(false) }
    var contentTick by remember { mutableStateOf(0) }
    // 重写本章的在途标记：仅在按钮点击时置位，busy 释放后复位（页面态，不进 VM）。
    var rewritingOrdinal by remember { mutableStateOf<Int?>(null) }
    // 切分支后重置明细视图：openChapter 是无 key remember 的内存态，switchBranch 的
    // reload 不触及——不清会把上一分支的章节继续显示/编辑在当前分支下，保存即跨分支
    // 写入（runtime 侧另有分支前缀防御，这里是 UI 层的第一道）。
    LaunchedEffect(state.branchSlug) {
        openChapter = null
        editingChapter = false
        rewritingOrdinal = null
        contentTick++
    }
    LaunchedEffect(state.busy) { if (!state.busy) rewritingOrdinal = null }
    val branchLocked = state.ghostwriteJob?.status == "running" ||
        state.ghostwriteJob?.status == "paused" ||
        state.ghostwriteJob?.status == "failed"

    val chapter = openChapter
    if (chapter != null) {
        if (editingChapter) {
            val body = remember(chapter.path, contentTick) { viewModel.readChapter(chapter.path).orEmpty() }
            MarkdownChapterEditor(
                chapter = chapter,
                initialBody = body,
                busy = state.busy,
                writeLocked = branchLocked,
                onSave = { title, text ->
                    viewModel.saveChapterEdit(chapter.path, title, text) {
                        contentTick++
                        editingChapter = false
                    }
                },
                onCancel = { editingChapter = false },
            )
        } else {
            val body = remember(chapter.path, contentTick) { viewModel.readChapter(chapter.path).orEmpty() }
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { openChapter = null }) {
                        Text(stringResource(R.string.novel_return_to_directory), color = workspace.ink)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        chapter.title,
                        style = type.body.copy(fontWeight = FontWeight.SemiBold),
                        color = workspace.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { editingChapter = true }, enabled = !branchLocked) {
                        Text(stringResource(R.string.edit), color = workspace.ink)
                    }
                }
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(
                        if (body.isBlank()) {
                            stringResource(R.string.novel_empty_chapter)
                        } else {
                            body
                        },
                        style = type.body,
                        color = workspace.ink,
                    )
                    Spacer(Modifier.size(48.dp))
                }
            }
        }
        return
    }

    if (state.chapters.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.novel_no_chapters),
                style = type.secondary,
                color = workspace.muted,
            )
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            val activeJob = state.ghostwriteJob
            val batchOwnsBranch = activeJob?.status == "running" ||
                activeJob?.status == "paused" || activeJob?.status == "failed"
            val polishOwned = activeJob != null && batchOwnsBranch && activeJob.mode ==
                app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode.Polish
            val writeOwned = batchOwnsBranch && !polishOwned
            if (writeOwned) {
                Text(
                    stringResource(R.string.novel_batch_in_use),
                    color = workspace.muted,
                    style = type.meta,
                )
            }
            // 批量润色入口：range 对话框 + 进度都进同一张 sheet；润色批次进行中时按钮
            // 变为查看进度入口（代笔占用时禁用，两者互斥）。
            TextButton(
                onClick = onOpenPolish,
                enabled = !state.busy && !writeOwned,
            ) {
                Text(
                    if (polishOwned) {
                        stringResource(R.string.novel_polish_in_progress)
                    } else {
                        stringResource(R.string.novel_batch_polish)
                    },
                    color = workspace.muted,
                    style = type.meta,
                )
            }
            if (state.canUndo) {
                TextButton(
                    onClick = { viewModel.undoLast() },
                    enabled = !state.busy && !branchLocked,
                ) {
                    Text(
                        stringResource(R.string.novel_undo_last),
                        color = workspace.muted,
                        style = type.meta,
                    )
                }
            }
            TextButton(
                onClick = { viewModel.runConsistencyCheck() },
                enabled = !state.busy && !state.consistencyChecking,
            ) {
                Text(
                    if (state.consistencyChecking) {
                        stringResource(R.string.novel_consistency_checking)
                    } else {
                        stringResource(R.string.novel_consistency_check)
                    },
                    color = workspace.muted,
                    style = type.meta,
                )
            }
        }
    LazyColumn(
        Modifier.fillMaxWidth().weight(1f).navigationBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(state.chapters, key = { it.path }) { chapterItem ->
            AmberCard(
                Modifier
                    .fillMaxWidth()
                    .clickable { openChapter = chapterItem },
                containerColor = workspace.paper,
                borderColor = workspace.hairline,
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        stringResource(
                            R.string.novel_chapter_heading,
                            chapterItem.ordinal,
                            chapterItem.title,
                        ),
                        style = type.body.copy(fontWeight = FontWeight.SemiBold),
                        color = workspace.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.novel_character_count, chapterItem.charCount),
                        style = type.meta,
                        color = workspace.muted,
                    )
                    // 重写本章：整章替换稿走正文审批门（提案卡确认后生效）；
                    // 中间章未决状态照常允许重写，未决门既有语义自会处理。
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = {
                                if (viewModel.rewriteChapter(chapterItem.ordinal)) {
                                    rewritingOrdinal = chapterItem.ordinal
                                }
                            },
                            enabled = !state.busy && !branchLocked,
                        ) {
                            Text(
                                if (rewritingOrdinal == chapterItem.ordinal) {
                                    stringResource(R.string.novel_rewriting_chapter)
                                } else {
                                    stringResource(R.string.novel_rewrite_chapter)
                                },
                                color = workspace.muted,
                                style = type.meta,
                            )
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

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = workspace.canvas) {
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
            branches.forEach { branch ->
                val current = branch.isCurrent
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (current) workspace.row else Color.Transparent)
                        .border(1.dp, workspace.hairline, RoundedCornerShape(12.dp))
                        .clickable(enabled = !current && !branchLocked && !busy) {
                            onSwitch(branch.slug)
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
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

/**
 * 设定 tab：设定文件区（setting/ 按目录分组，显示最近提交时间）、伏笔区（未回收/已回收，
 * 点击进入该文件编辑）、决定区（只读）。数据来自 [NovelWorkspaceCatalog]（feature 层
 * 纯函数），每次 commit 与切分支后由 VM 刷新。
 */
@Composable
private fun MarkdownWorkspaceCatalog(
    viewModel: NovelMarkdownWorkspaceViewModel,
    state: NovelMarkdownWorkspaceUiState,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val branchLocked = state.ghostwriteJob?.status == "running" ||
        state.ghostwriteJob?.status == "paused" ||
        state.ghostwriteJob?.status == "failed"
    var openPath by remember { mutableStateOf<String?>(null) }
    var openTitle by remember { mutableStateOf("") }
    var contentTick by remember { mutableStateOf(0) }
    // 切分支后重置设定明细视图（同正文 tab：无 key remember 的内存态属于上一分支）。
    LaunchedEffect(state.branchSlug) {
        openPath = null
        openTitle = ""
        contentTick++
    }

    val path = openPath
    if (path != null) {
        val body = remember(path, contentTick) { viewModel.readFileBody(path).orEmpty() }
        WorkspaceFileEditor(
            title = openTitle,
            initialBody = body,
            busy = state.busy,
            writeLocked = branchLocked,
            onSave = { text ->
                viewModel.saveFileEdit(path, text) {
                    contentTick++
                    openPath = null
                }
            },
            onCancel = { openPath = null },
        )
        return
    }

    val catalog = state.catalog
    if (catalog == null ||
        (catalog.settingGroups.isEmpty() && catalog.foreshadowing.isEmpty() && catalog.decisions.isEmpty())
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.novel_no_setting_files),
                style = type.secondary,
                color = workspace.muted,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (catalog.settingGroups.isNotEmpty()) {
            item(key = "setting-header") {
                CatalogSectionHeader(stringResource(R.string.novel_setting_files))
            }
            catalog.settingGroups.forEach { group ->
                item(key = "setting-${group.directory}") {
                    PanelSection(
                        title = if (group.directory == NovelWorkspaceCatalog.ROOT_GROUP) {
                            stringResource(R.string.novel_catalog_root_directory)
                        } else {
                            group.directory
                        },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            group.entries.forEach { entry ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            openTitle = entry.title
                                            openPath = entry.path
                                        }
                                        .padding(horizontal = 4.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            entry.title,
                                            style = type.body,
                                            color = workspace.ink,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            entry.path,
                                            style = type.tinyTag,
                                            color = workspace.faint,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Text(
                                        entry.updatedAt?.let { formatCatalogTime(it) }
                                            ?: stringResource(R.string.novel_uncommitted),
                                        style = type.tinyTag,
                                        color = workspace.faint,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (catalog.foreshadowing.isNotEmpty()) {
            item(key = "foreshadowing-header") {
                CatalogSectionHeader(stringResource(R.string.novel_foreshadowing))
            }
            val open = catalog.foreshadowing.filter { !it.resolved }
            val resolved = catalog.foreshadowing.filter { it.resolved }
            if (open.isNotEmpty()) {
                item(key = "foreshadowing-open") {
                    PanelSection(
                        title = stringResource(R.string.novel_open_foreshadowing, open.size),
                    ) {
                        ForeshadowingRows(open, workspace, type) { entry ->
                            openTitle = entry.title
                            openPath = entry.path
                        }
                    }
                }
            }
            if (resolved.isNotEmpty()) {
                item(key = "foreshadowing-resolved") {
                    PanelSection(
                        title = stringResource(R.string.novel_resolved_foreshadowing, resolved.size),
                    ) {
                        ForeshadowingRows(resolved, workspace, type) { entry ->
                            openTitle = entry.title
                            openPath = entry.path
                        }
                    }
                }
            }
        }
        if (catalog.decisions.isNotEmpty()) {
            item(key = "decision-header") {
                CatalogSectionHeader(stringResource(R.string.novel_confirmed_decisions))
            }
            item(key = "decision-list") {
                PanelSection(title = stringResource(R.string.novel_decision_records)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        catalog.decisions.forEach { decision ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    decision.title,
                                    style = type.body,
                                    color = workspace.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    decision.path,
                                    style = type.tinyTag,
                                    color = workspace.faint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogSectionHeader(text: String) {
    val type = LocalAmberType.current
    val workspace = workspaceColors()
    Text(
        text,
        style = type.meta.copy(fontWeight = FontWeight.SemiBold),
        color = workspace.muted,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun ForeshadowingRows(
    entries: List<NovelWorkspaceCatalog.NovelWorkspaceForeshadowingEntry>,
    workspace: app.amber.feature.ui.components.ui.WorkspaceColors,
    type: app.amber.feature.ui.theme.AmberTextStyles,
    onOpen: (NovelWorkspaceCatalog.NovelWorkspaceForeshadowingEntry) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        entries.forEach { entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpen(entry) }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.title,
                        style = type.body,
                        color = workspace.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        entry.path,
                        style = type.tinyTag,
                        color = workspace.faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 设定/伏笔文件编辑器：正文编辑（front matter 由宿主保留），保存走宿主手改 +
 * 「手改」commit + undo 记录，与章节编辑器同一套保存/撤销约定。
 */
@Composable
private fun WorkspaceFileEditor(
    title: String,
    initialBody: String,
    busy: Boolean,
    writeLocked: Boolean,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    var body by remember(title, initialBody) { mutableStateOf(initialBody) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onCancel,
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(R.string.cancel),
                    color = workspace.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                title,
                style = type.body.copy(fontWeight = FontWeight.SemiBold),
                color = workspace.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(2f),
            )
            TextButton(
                onClick = { onSave(body) },
                enabled = !busy && !writeLocked,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (busy) {
                        stringResource(R.string.novel_saving)
                    } else {
                        stringResource(R.string.chat_page_save)
                    },
                    color = workspace.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicTextField(
                value = body,
                onValueChange = { body = it },
                enabled = !busy && !writeLocked,
                textStyle = type.body.copy(color = workspace.ink),
                cursorBrush = SolidColor(workspace.ink),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(workspace.paper)
                    .border(1.dp, workspace.hairline, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
            Spacer(Modifier.size(48.dp))
        }
    }
}

/** 设定文件区的更新时间列（ledger 最近 commit 时间，本地时区）。 */
private fun formatCatalogTime(instant: java.time.Instant): String =
    java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
        .format(instant)
