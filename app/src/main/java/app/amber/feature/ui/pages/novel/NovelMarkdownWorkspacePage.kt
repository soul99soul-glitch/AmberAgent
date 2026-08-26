package app.amber.feature.ui.pages.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.ModelType
import app.amber.feature.ui.components.ai.TopModelMenu
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.novel.workspace.NovelWorkspaceCollectTarget
import app.amber.feature.novel.workspace.NovelWorkspaceWriteProposal
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
    // Graphite TopModelMenu：与标准 chat 同款——顶栏下方卷帘下拉（替代 ModelSelector 弹层）。
    var modelMenuOpen by remember { mutableStateOf(false) }

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
                    // Chat-header pattern: bold book name on line 1, tiny model-id trigger
                    // on line 2 — opens the same Graphite TopModelMenu curtain as the chat.
                    val tokens = LocalAmberTokens.current
                    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
                    val currentModelUuid = state.writingModelId?.let {
                        runCatching { kotlin.uuid.Uuid.parse(it) }.getOrNull()
                    }
                    Column {
                        Text(
                            state.title.ifEmpty { "小说工作区" },
                            style = type.sessionTitle,
                            color = tokens.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                        val chevronRotation by animateFloatAsState(
                            targetValue = if (modelMenuOpen) 180f else 0f,
                            animationSpec = tween(durationMillis = 280),
                            label = "novelModelMenuChevron",
                        )
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { modelMenuOpen = !modelMenuOpen }
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
                                ?: "跟随全局"
                            Text(
                                resolvedName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = type.meta.copy(
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = lerp(tokens.ink3, tokens.ink2, 0.5f),
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Icon(
                                imageVector = Lucide.ArrowDown,
                                contentDescription = "选择模型",
                                tint = tokens.ink3,
                                modifier = Modifier
                                    .size(13.dp)
                                    .rotate(chevronRotation),
                            )
                        }
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
                                contentDescription = "改回跟随全局",
                                tint = LocalAmberTokens.current.ink3,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    // 代笔入口：accentSoft 底 + accent 字的入口胶囊（accent=进入选择，非装饰）。
                    val entryAccent = app.amber.feature.ui.pages.chat.LocalChatTheme.current
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(entryAccent.accentSoft)
                            .clickable { showGhostwrite = true }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "代笔",
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
                        "项目不存在或已被删除。",
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
                        listOf(0 to "创作", 1 to "正文").forEach { (index, label) ->
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
                    if (tab == 0) {
                        MarkdownWorkspaceChat(viewModel, state, onOpenGhostwrite = { showGhostwrite = true })
                    } else {
                        MarkdownWorkspaceManuscript(viewModel, state)
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
            saveWritingPreference = { viewModel.saveWritingPreference(it) },
            briefPreview = { viewModel.briefPreview() },
            onDismiss = { showGhostwrite = false },
        )
    }

    state.consistencyReport?.let { report ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissConsistencyReport() },
            containerColor = workspace.paper,
            title = { Text("一致性检查结果", fontWeight = FontWeight.SemiBold, color = workspace.ink) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(report, style = type.secondary, color = workspace.ink)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissConsistencyReport() }) {
                    Text("知道了", color = workspace.ink)
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
    saveChapterPlan: (String) -> Unit,
    readUpcomingArc: () -> String,
    saveUpcomingArc: (String) -> Unit,
    readWritingPreference: () -> String,
    saveWritingPreference: (String) -> Unit,
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
    val branchOwned = job?.status == "running" || job?.status == "paused"

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
                    "代笔",
                    style = type.sessionTitle.copy(fontWeight = FontWeight.Bold),
                    color = workspace.ink,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "GHOSTWRITE",
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
            PanelSection(title = "批量进度") {
                if (job == null) {
                    var target by remember { mutableStateOf(5) }
                    Text("选择要连写的章数，逐章生成并自动收录。", style = type.meta, color = workspace.muted)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        PanelRoundIcon(
                            icon = Lucide.Minus,
                            contentDescription = "减一章",
                            enabled = target > 1,
                            onClick = { if (target > 1) target -= 1 },
                        )
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "$target",
                                    style = type.screenTitle.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = app.amber.feature.ui.theme.AmberMono,
                                    ),
                                    color = workspace.ink,
                                )
                                Text("章", style = type.meta, color = workspace.muted)
                            }
                        }
                        PanelRoundIcon(
                            icon = Lucide.Plus,
                            contentDescription = "加一章",
                            enabled = target < 99,
                            onClick = { if (target < 99) target += 1 },
                        )
                    }
                    PanelCtaButton(text = "开始代笔", enabled = !busy, onClick = { onStart(target) })
                } else if (job.status == "failed") {
                    Text(
                        "批次失败 · 已写 ${job.written} / ${job.target} 章",
                        style = type.body.copy(fontWeight = FontWeight.SemiBold),
                        color = workspace.red,
                    )
                    Text(job.reason ?: "未知原因", style = type.meta, color = workspace.red)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PanelPill(text = "知道了", onClick = onDismissFailure)
                        PanelPill(
                            text = "继续剩余",
                            tone = PanelTone.Accent,
                            enabled = !busy,
                            onClick = onRetryFailed,
                        )
                    }
                } else {
                    val paused = job.status == "paused"
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                                    if (paused) "已暂停" else "进行中",
                                    style = type.tinyTag,
                                    color = if (paused) workspace.muted else workspace.green,
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "${job.written}",
                                style = type.screenTitle.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = app.amber.feature.ui.theme.AmberMono,
                                ),
                                color = workspace.ink,
                            )
                            Text(
                                " / ${job.target} 章",
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
                        "每章一笔 commit，可随时暂停；崩了按 commit 续跑不重写。",
                        style = type.meta,
                        color = workspace.muted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PanelPill(
                            text = if (paused) "继续" else "暂停",
                            onClick = if (paused) onResume else onPause,
                        )
                        PanelPill(text = "取消批次", tone = PanelTone.Danger, onClick = onCancel)
                    }
                }
            }

            // ── editable control files (one frame: the card; editor is flat) ──
            PanelSection(
                title = "本章计划",
                action = {
                    PanelPill(
                        text = if (busy) "生成中…" else "一键生成",
                        tone = PanelTone.Accent,
                        enabled = !busy && !branchOwned,
                        onClick = onGeneratePlan,
                    )
                },
            ) {
                Text("代笔写下一章前会读取它：目标、冲突、必须发生的事。", style = type.meta, color = workspace.muted)
                PanelEditor(
                    placeholder = "例如：赵大夜探军营，与旧部相认；不可暴露身份。结尾留：密信被截。",
                    initial = remember(planAutoTick) { readChapterPlan() },
                    enabled = !busy && !branchOwned,
                    onSave = saveChapterPlan,
                )
            }

            PanelSection(title = "往后几章方向") {
                Text("未来几章的走向要点，供模型照应，避免跑偏。", style = type.meta, color = workspace.muted)
                PanelEditor(
                    placeholder = "例如：\n- 兵变前夜的双线布局\n- 黄龙旗来历揭晓",
                    initial = remember(planAutoTick) { readUpcomingArc() },
                    enabled = !busy && !branchOwned,
                    onSave = saveUpcomingArc,
                )
            }

            PanelSection(title = "写作偏好") {
                Text("文风、节奏、禁忌等长期要求（存于 setting/writing）。", style = type.meta, color = workspace.muted)
                PanelEditor(
                    placeholder = "例如：文风冷峻克制；单章 2000-3000 字；避免说教。",
                    initial = remember { readWritingPreference() },
                    enabled = !busy && !branchOwned,
                    onSave = saveWritingPreference,
                )
            }

            // ── injection selection (hairline rows, no nested fills) ────────
            PanelSection(title = "上下文注入") {
                val rows = listOf(
                    Triple("剧情状态", "plot/current.md 当前局面", injection.plot),
                    Triple("未回收伏笔", "埋下未收的伏笔节点", injection.foreshadowing),
                    Triple("本章相关节点", "计划中实体的人物卡与关系", injection.neighborhood),
                    Triple("已确认决定", "不可违背的既定事实", injection.decisions),
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
            PanelSection(title = "注入简报预览") {
                val brief = remember(injection, planAutoTick) { briefPreview() }
                Text(
                    brief.ifBlank { "（暂无可注入的约束：书写定后这里会显示每轮注入的剧情状态、伏笔与节点）" },
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

private enum class PanelTone { Normal, Accent, Danger, Saved }

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
            .clip(RoundedCornerShape(999.dp))
            .background(if (enabled) bg else workspace.row)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
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
            .height(46.dp)
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

/** 44dp flat round stepper chip. */
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
            .size(44.dp)
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
    onSave: (String) -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    var text by remember(initial) { mutableStateOf(initial) }
    var savedTick by remember(initial) { mutableStateOf(false) }
    val dirty = text != initial
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
                text = "清除",
                enabled = enabled,
                onClick = {
                    text = ""
                    onSave("")
                    savedTick = true
                },
            )
        }
        PanelPill(
            text = if (savedTick) "已保存 ✓" else "保存",
            tone = if (savedTick) PanelTone.Saved else PanelTone.Accent,
            enabled = enabled && dirty,
            onClick = {
                onSave(text)
                savedTick = true
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
    var draft by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        // A running batch must be visible on the page itself (device-observed: the
        // user cannot tell whether anything is happening when this OEM blocks the
        // foreground-service notification).
        val activeJob = state.ghostwriteJob
        val branchOwned = activeJob?.status == "running" || activeJob?.status == "paused"
        if (activeJob != null && branchOwned) {
            Row(
                Modifier
                    .fillMaxWidth()
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
                Text(
                    if (activeJob.status == "running") {
                        "代笔进行中 ${activeJob.written} / ${activeJob.target} 章"
                    } else {
                        "代笔已暂停 ${activeJob.written} / ${activeJob.target} 章"
                    },
                    style = type.meta,
                    color = workspace.ink,
                    modifier = Modifier.weight(1f),
                )
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
                    "⚠️ 剧情落后于正文。同步后批准剧情修改，即可继续写作。",
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
                    Text("同步剧情", color = workspace.red)
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
                    "⛔ 中间章被修改：第 $fromOrdinal 章起可能已对不上，处理前暂停推进。",
                    style = type.meta,
                    color = workspace.red,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { viewModel.rewriteLaterChapters() },
                    enabled = !state.busy,
                ) {
                    Text("重写后章", color = workspace.ink)
                }
                TextButton(
                    onClick = { viewModel.resolveUnresolved() },
                    enabled = !state.busy,
                ) {
                    Text("确认无碍", color = workspace.muted)
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
                            state.toolActivity ?: "正在思考…",
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
                        busy = state.busy,
                        onApprove = { viewModel.approve(proposal.id) },
                        onReject = { viewModel.reject(proposal.id) },
                    )
                }
            }
            if (state.drafts.isNotEmpty()) {
                item(key = "drafts-header") {
                    Text(
                        "未收录草稿 · ${state.drafts.size}",
                        style = type.meta,
                        color = workspace.muted,
                    )
                }
                items(state.drafts, key = { it.path }) { draft ->
                    MarkdownDraftCard(
                        draft = draft,
                        hasChapters = state.chapters.isNotEmpty(),
                        busy = state.busy,
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
                        "和写作助手讨论剧情与设定。它用五个文件工具读这本书；" +
                            "改正文或剧情会出审批卡，你确认后才写入。",
                        style = type.secondary,
                        color = workspace.muted,
                    )
                }
            }
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
                        contentDescription = "模式",
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
                        NovelMarkdownComposerMode.Discuss to "讨论",
                        NovelMarkdownComposerMode.WriteProse to "写正文",
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
                    contentDescription = if (state.busy) "停止" else "发送",
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
                    Text("收录为新章", color = workspace.ink)
                }
                if (hasChapters) {
                    TextButton(onClick = onCollectAppend, enabled = !busy) {
                        Text("追加到末章", color = workspace.muted)
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
                "写入正文提案",
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
                TextButton(onClick = onReject, enabled = !busy) { Text("拒绝", color = workspace.muted) }
                TextButton(onClick = onApprove, enabled = !busy) { Text("确认写入", color = workspace.ink) }
            }
        }
    }
}

@Composable
private fun MarkdownChapterEditor(
    chapter: NovelMarkdownChapterUi,
    initialBody: String,
    busy: Boolean,
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
            TextButton(onClick = onCancel, enabled = !busy) {
                Text("取消", color = workspace.muted)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "编辑章节",
                style = type.body.copy(fontWeight = FontWeight.SemiBold),
                color = workspace.ink,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onSave(title, body) }, enabled = !busy) {
                Text(if (busy) "保存中…" else "保存", color = workspace.ink)
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
                enabled = !busy,
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
                enabled = !busy,
                textStyle = type.body.copy(color = workspace.ink),
                cursorBrush = SolidColor(workspace.ink),
                modifier = Modifier
                    .fillMaxWidth()
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
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    var openChapter by remember { mutableStateOf<NovelMarkdownChapterUi?>(null) }
    var editingChapter by remember { mutableStateOf(false) }
    var contentTick by remember { mutableStateOf(0) }
    val branchLocked = state.ghostwriteJob?.status == "running" || state.ghostwriteJob?.status == "paused"

    val chapter = openChapter
    if (chapter != null) {
        if (editingChapter) {
            val body = remember(chapter.path, contentTick) { viewModel.readChapter(chapter.path).orEmpty() }
            MarkdownChapterEditor(
                chapter = chapter,
                initialBody = body,
                busy = state.busy || branchLocked,
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
                        Text("返回目录", color = workspace.ink)
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
                        Text("编辑", color = workspace.ink)
                    }
                }
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(body.ifBlank { "（空章节）" }, style = type.body, color = workspace.ink)
                    Spacer(Modifier.size(48.dp))
                }
            }
        }
        return
    }

    if (state.chapters.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "正文还没有章节。在创作里让助手写草稿，收录后会出现在这里。",
                style = type.secondary,
                color = workspace.muted,
            )
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (branchLocked) {
                Text("当前分支由代笔批次占用", color = workspace.muted, style = type.meta)
                Spacer(Modifier.weight(1f))
            }
            if (state.canUndo) {
                TextButton(
                    onClick = { viewModel.undoLast() },
                    enabled = !state.busy && !branchLocked,
                ) {
                    Text("撤销最近一笔", color = workspace.muted, style = type.meta)
                }
            }
            TextButton(
                onClick = { viewModel.runConsistencyCheck() },
                enabled = !state.busy && !state.consistencyChecking,
            ) {
                Text(
                    if (state.consistencyChecking) "检查中…" else "一致性检查",
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
                        "第 ${chapterItem.ordinal} 章 · ${chapterItem.title}",
                        style = type.body.copy(fontWeight = FontWeight.SemiBold),
                        color = workspace.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "约 ${chapterItem.charCount} 字",
                        style = type.meta,
                        color = workspace.muted,
                    )
                }
            }
        }
    }
    }
}
