package app.amber.feature.ui.pages.board

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Notebook01
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Share03
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.TransactionHistory
import app.amber.agent.Screen
import app.amber.agent.Screen.DeepRead
import app.amber.feature.board.TodayBoardHotListFilterMode
import app.amber.feature.board.hotlist.HotListDashboard
import app.amber.feature.board.hotlist.HOT_LIST_TOPIC_DISPLAY_LIMIT
import app.amber.feature.board.hotlist.HotListItem
import app.amber.feature.board.hotlist.HotListProviderSnapshot
import app.amber.feature.board.hotlist.HotTopic
import app.amber.feature.board.hotlist.presentationTitle
import app.amber.agent.data.db.entity.BoardItemEntity
import app.amber.agent.data.db.entity.BoardTaskArtifact
import app.amber.agent.data.db.entity.BoardTaskEntity
import app.amber.agent.data.db.entity.BoardTaskState
import app.amber.core.utils.JsonInstant
import app.amber.agent.data.db.entity.DailyReviewEntity
import app.amber.agent.data.db.entity.OpportunityEntity
import app.amber.agent.data.db.entity.OpportunityType
import app.amber.feature.ui.components.ds.Hairline
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.components.ui.WorkspaceTextButton
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.workspaceBorder
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.components.ds.LiveDot
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.core.utils.navigateToChatPage
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayBoardPage() {
    val navController = LocalNavController.current
    val vm: BoardViewModel = koinInject()

    val settings by vm.settings.collectAsStateWithLifecycle()
    val boardEnabled = settings.agentRuntime.todayBoard.enabled
    val items by vm.items.collectAsStateWithLifecycle(initialValue = emptyList())
    val tasks by vm.tasks.collectAsStateWithLifecycle(initialValue = emptyList())
    val opportunities by vm.opportunities.collectAsStateWithLifecycle(initialValue = emptyList())
    val dailyReview by vm.dailyReview.collectAsStateWithLifecycle(initialValue = null)
    val dashboard by vm.hotListDashboard.collectAsStateWithLifecycle(
        initialValue = HotListDashboard(emptyList(), emptyList(), 0L),
    )
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var pendingDeepRead by remember { mutableStateOf<PendingDeepReadRequest?>(null) }
    var selectedTopic by remember { mutableStateOf<HotTopic?>(null) }
    var selectedOpportunity by remember { mutableStateOf<OpportunityEntity?>(null) }

    fun requestDeepRead(topic: HotTopic, forceRegenerate: Boolean = false) {
        if (settings.agentRuntime.todayBoard.deepReadFirstUseConfirmed) {
            scope.launch {
                val prepared = vm.prepareDeepReadTopic(topic, forceRegenerate = forceRegenerate)
                navController.navigate(DeepRead(prepared.id, prepared.title))
            }
        } else {
            pendingDeepRead = PendingDeepReadRequest(topic, forceRegenerate)
        }
    }

    fun shareTopic(topic: HotTopic) {
        val text = buildString {
            append(topic.title)
            topic.primaryUrl()?.let { url ->
                append('\n')
                append(url)
            }
        }
        runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(intent, "分享热点"))
        }.onFailure {
            Toast.makeText(context, "无法打开分享面板", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("今日看板", fontWeight = FontWeight.Bold)
                },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.DeepReadHistory) }) {
                        Icon(HugeIcons.TransactionHistory, contentDescription = "深度阅读历史")
                    }
                    IconButton(onClick = { navController.navigate(Screen.SettingTodayBoard) }) {
                        Icon(HugeIcons.Settings03, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        }
    ) { innerPadding ->
        if (!boardEnabled) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text("今日看板未启用\n请在设置中开启", style = LocalAmberType.current.body)
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            SecondaryTabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                listOf("大看板", "任务流").forEachIndexed { index, label ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = {
                            Text(
                                label,
                                style = LocalAmberType.current.body.copy(
                                    fontWeight = if (pagerState.currentPage == index) FontWeight.SemiBold else FontWeight.Normal,
                                ),
                            )
                        },
                    )
                }
            }

            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                when (page) {
                    0 -> HotListTab(
                        dashboard = dashboard,
                        filterMode = settings.agentRuntime.todayBoard.hotListFilterMode,
                        onRefresh = vm::refreshHotList,
                        onTopicClick = { topic ->
                            selectedTopic = topic
                        },
                        onProviderItemClick = { provider, item ->
                            scope.launch {
                                selectedTopic = vm.createProviderTopic(provider, item)
                            }
                        },
                    )

                    1 -> TaskFlowTab(
                        opportunities = opportunities,
                        tasks = tasks,
                        review = dailyReview,
                        onRefresh = vm::refresh,
                        onDispatchOpportunity = { opportunity ->
                            vm.dispatchOpportunity(opportunity.id)
                            Toast.makeText(context, "已派发任务", Toast.LENGTH_SHORT).show()
                        },
                        onShowOpportunityEvidence = { opportunity ->
                            selectedOpportunity = opportunity
                        },
                        onDismissOpportunity = { opportunity ->
                            vm.dismissOpportunity(opportunity.id)
                            Toast.makeText(context, "已忽略机会", Toast.LENGTH_SHORT).show()
                        },
                        onMuteOpportunityType = { opportunity ->
                            vm.muteOpportunityType(opportunity.id)
                            Toast.makeText(context, "已不再提醒这类", Toast.LENGTH_SHORT).show()
                        },
                        onDispatch = { task ->
                            vm.dispatchTask(task.id)
                            Toast.makeText(context, "已派发任务", Toast.LENGTH_SHORT).show()
                        },
                        onDone = vm::markTaskDone,
                        onIgnore = vm::markTaskDismissed,
                        onCancel = vm::cancelTask,
                        onSnooze = vm::snoozeTask,
                        onOpenSession = { task ->
                            scope.launch {
                                vm.startTaskChat(task.id)
                                navigateToChatPage(navController, initText = vm.taskSessionPrompt(task.id))
                            }
                        },
                    )
                }
            }
        }
    }

    pendingDeepRead?.let { request ->
        AlertDialog(
            onDismissRequest = { pendingDeepRead = null },
            title = { Text("深度阅读会消耗更多 tokens", style = LocalAmberType.current.sessionTitle) },
            text = { Text("每次生成约消耗 3 万 tokens。后续同一话题 24 小时内会优先使用缓存。", style = LocalAmberType.current.body) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            vm.confirmDeepReadCost()
                            val prepared = vm.prepareDeepReadTopic(
                                request.topic,
                                forceRegenerate = request.forceRegenerate,
                            )
                            pendingDeepRead = null
                            navController.navigate(DeepRead(prepared.id, prepared.title))
                        }
                    }
                ) {
                    Text("继续")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeepRead = null }) {
                    Text("取消")
                }
            },
        )
    }

    selectedOpportunity?.let { opportunity ->
        AlertDialog(
            onDismissRequest = { selectedOpportunity = null },
            title = { Text(opportunity.title, style = LocalAmberType.current.sessionTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(opportunity.summary, style = LocalAmberType.current.body)
                    Hairline()
                    Text("依据", style = LocalAmberType.current.sessionTitle)
                    Text(
                        opportunity.evidenceJson,
                        style = LocalAmberType.current.secondary,
                        color = workspaceColors().muted,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("置信度 ${(opportunity.confidence * 100).toInt()}%", style = LocalAmberType.current.meta)
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedOpportunity = null }) {
                    Text("知道了")
                }
            },
        )
    }

    selectedTopic?.let { topic ->
        HotListActionSheet(
            topic = topic,
            onDismiss = { selectedTopic = null },
            onDeepRead = {
                selectedTopic = null
                requestDeepRead(topic)
            },
            onRegenerate = {
                selectedTopic = null
                requestDeepRead(topic, forceRegenerate = true)
            },
            onOpenOriginal = {
                selectedTopic = null
                topic.primaryUrl()?.let { url ->
                    runCatching { uriHandler.openUri(url) }
                }
            },
            onShare = {
                selectedTopic = null
                shareTopic(topic)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HotListActionSheet(
    topic: HotTopic,
    onDismiss: () -> Unit,
    onDeepRead: () -> Unit,
    onRegenerate: () -> Unit,
    onOpenOriginal: () -> Unit,
    onShare: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                topic.title,
                style = LocalAmberType.current.sessionTitle,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (topic.sources.isNotEmpty()) {
                Text(
                    topic.sources.joinToString(" · ") { "${it.providerName} #${it.rank}" },
                    style = LocalAmberType.current.meta,
                    color = workspaceColors().muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            Hairline()
            TopicActionRow(
                label = "深度阅读",
                icon = HugeIcons.Notebook01,
                onClick = onDeepRead,
            )
            TopicActionRow(
                label = "重新生成",
                icon = HugeIcons.Refresh03,
                onClick = onRegenerate,
            )
            TopicActionRow(
                label = "打开原文",
                icon = HugeIcons.ArrowRight01,
                enabled = topic.primaryUrl() != null,
                onClick = onOpenOriginal,
            )
            TopicActionRow(
                label = "分享",
                icon = HugeIcons.Share03,
                onClick = onShare,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

private data class PendingDeepReadRequest(
    val topic: HotTopic,
    val forceRegenerate: Boolean,
)

@Composable
private fun TopicActionRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else workspaceColors().muted
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = color)
        Text(label, style = LocalAmberType.current.body, color = color)
    }
}

private fun chatContext(item: BoardItemEntity): String =
    "看板待办: ${item.title}\n\n原因: ${item.reason}\n建议: ${item.suggestion}\n\n来源内容: ${item.sourceContent.take(1000)}"

internal fun visibleTodayReviewTodoItems(items: List<BoardItemEntity>): List<BoardItemEntity> =
    items
        .filter { it.status != "dismissed" && (it.category == "todo" || it.category == "action") }
        .sortedWith(compareBy<BoardItemEntity> { if (it.status == "active") 0 else 1 })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HotListTab(
    dashboard: HotListDashboard,
    filterMode: TodayBoardHotListFilterMode,
    onRefresh: () -> Unit,
    onTopicClick: (HotTopic) -> Unit,
    onProviderItemClick: (HotListProviderSnapshot, HotListItem) -> Unit,
) {
    val pullState = rememberPullToRefreshState()
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(dashboard.shouldShowSkeleton) {
        if (dashboard.shouldShowSkeleton) onRefresh()
    }
    LaunchedEffect(dashboard.lastUpdatedAt, dashboard.providers.map { it.error to it.fetchedAt }) {
        if (isRefreshing) isRefreshing = false
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            onRefresh()
            scope.launch {
                delay(15_000L)
                isRefreshing = false
            }
        },
        state = pullState,
        modifier = Modifier.fillMaxSize(),
    ) {
        if (!dashboard.hasEnabledSources) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                EmptyLine("未启用热榜数据源。请在设置中至少开启一个来源。")
            }
        } else if (dashboard.isEmpty) {
            HotListSkeleton()
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
            ) {
                item {
                    RubricHead(
                        label = "综合热点",
                        status = dashboard.lastUpdatedAt.takeIf { it > 0L }?.let { "${timeAgo(it)}更新" },
                    )
                }
                if (dashboard.topics.isEmpty()) {
                    item {
                        EmptyLine(
                            if (filterMode == TodayBoardHotListFilterMode.FOCUS_ONLY) {
                                "没有匹配关注词的热点。可以在今日看板设置里调整关注词，或切换为关注优先。"
                            } else {
                                "暂时没有可聚合的综合热点。"
                            }
                        )
                    }
                }
                val topics = dashboard.topics.take(HOT_LIST_TOPIC_DISPLAY_LIMIT)
                itemsIndexed(topics, key = { _, it -> it.id }) { index, topic ->
                    if (index == 0) {
                        LeadStory(
                            rank = topic.bestRank,
                            title = topic.title,
                            dek = null,
                            meta = topicMeta(topic),
                            onClick = { onTopicClick(topic) },
                        )
                    } else {
                        IndexRow(
                            rank = topic.bestRank,
                            title = topic.title,
                            meta = topicMeta(topic),
                            onClick = { onTopicClick(topic) },
                            last = index == topics.lastIndex,
                        )
                    }
                }
                dashboard.providers.forEach { provider ->
                    item("${provider.providerId}-head") {
                        RubricHead(
                            label = provider.providerName,
                            status = provider.error?.let { "⚠ 上次更新 ${timeAgo(provider.fetchedAt)}" }
                                ?: provider.fetchedAt.takeIf { it > 0L }?.let { timeAgo(it) },
                        )
                    }
                    val providerItems = provider.items.take(12)
                    if (providerItems.isEmpty()) {
                        item("${provider.providerId}-empty") {
                            EmptyLine(provider.error ?: "暂无数据")
                        }
                    } else {
                        itemsIndexed(
                            providerItems,
                            key = { i, _ -> "${provider.providerId}-$i" },
                        ) { index, item ->
                            if (index == 0) {
                                LeadStory(
                                    rank = item.rank,
                                    title = item.presentationTitle,
                                    dek = null,
                                    meta = MetaData(source = provider.providerName, detail = item.heat),
                                    onClick = { onProviderItemClick(provider, item) },
                                )
                            } else {
                                IndexRow(
                                    rank = item.rank,
                                    title = item.presentationTitle,
                                    meta = MetaData(source = provider.providerName, detail = item.heat),
                                    onClick = { onProviderItemClick(provider, item) },
                                    last = index == providerItems.lastIndex,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Flat "newspaper index" rows — ported from redesign/aa-board.jsx.
// Machine facts (rank / source / time) use the mono `meta` style; human titles use
// the sans `sessionTitle` family. Single `accent` for lead rank; `signal` green only
// for live/done. Flat + 1dp `line` hairline; no cards, no elevation.
// ─────────────────────────────────────────────────────────────────────────────

/** mono「source · detail」line — source in ink-3, separator+detail in ink-4. */
private data class MetaData(val source: String?, val detail: String?)

/** Bottom 1dp hairline (tokens.line) drawn at the row's lower edge. */
private fun Modifier.bottomHairline(color: Color, show: Boolean = true): Modifier =
    if (!show) this else drawBehind {
        val y = size.height - 0.5.dp.toPx()
        drawLine(color, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
    }

/** Zero-pad a rank to two digits, mirroring the JSX "01"/"02" formatting. */
private fun rank2(rank: Int): String =
    if (rank in 0..99) rank.toString().padStart(2, '0') else rank.toString()

/** Derive the topic meta line from its sources, keeping the same info the old pills showed. */
private fun topicMeta(topic: HotTopic): MetaData {
    val labels = topic.sources.take(4).map { "${it.providerName} #${it.rank}" }
    return MetaData(
        source = labels.firstOrNull(),
        detail = labels.drop(1).joinToString(" · ").takeIf { it.isNotBlank() },
    )
}

@Composable
private fun Meta(meta: MetaData, topPadding: Dp) {
    val t = LocalAmberTokens.current
    val source = meta.source
    val detail = meta.detail
    if (source.isNullOrBlank() && detail.isNullOrBlank()) return
    val mono = LocalAmberType.current.meta.copy(fontSize = 11.sp)
    Text(
        text = buildAnnotatedString {
            if (!source.isNullOrBlank()) {
                withStyle(SpanStyle(color = t.ink3)) { append(source) }
            }
            if (!detail.isNullOrBlank()) {
                withStyle(SpanStyle(color = t.ink4)) {
                    append(if (source.isNullOrBlank()) detail else " · $detail")
                }
            }
        },
        style = mono,
        modifier = Modifier.padding(top = topPadding),
    )
}

/** mono rubric label「// 综合热点」+ right-side live dot + status. */
@Composable
private fun RubricHead(label: String, status: String?) {
    val t = LocalAmberTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 22.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = t.accent)) { append("//") }
                withStyle(SpanStyle(color = t.ink2)) { append(" $label") }
            },
            style = LocalAmberType.current.meta.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
        )
        if (!status.isNullOrBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LiveDot(dotSize = 6.dp)
                Text(status, style = LocalAmberType.current.meta.copy(fontSize = 11.sp), color = t.ink3)
            }
        }
    }
}

/** Hero row: big accent mono rank + 19.5sp title + optional dek + mono meta. */
@Composable
private fun LeadStory(rank: Int, title: String, dek: String?, meta: MetaData, onClick: () -> Unit) {
    val t = LocalAmberTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .bottomHairline(t.line)
            .padding(top = 10.dp, bottom = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            rank2(rank),
            style = LocalAmberType.current.meta.copy(
                fontSize = 29.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 29.sp,
            ),
            color = t.accent,
            modifier = Modifier.padding(top = 2.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = LocalAmberType.current.sessionTitle.copy(
                    fontSize = 19.5.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 26.sp,
                ),
                color = t.ink,
            )
            if (!dek.isNullOrBlank()) {
                Text(
                    dek,
                    style = LocalAmberType.current.body.copy(fontSize = 14.sp, lineHeight = 21.sp),
                    color = t.ink3,
                    modifier = Modifier.padding(top = 7.dp),
                )
            }
            Meta(meta, topPadding = 9.dp)
        }
    }
}

/** Index row: small grey mono rank + 16sp 2-line title + mono meta. */
@Composable
private fun IndexRow(rank: Int, title: String, meta: MetaData, onClick: () -> Unit, last: Boolean) {
    val t = LocalAmberTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .bottomHairline(t.line, show = !last)
            .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            rank2(rank),
            style = LocalAmberType.current.meta.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            color = t.ink4,
            modifier = Modifier.width(20.dp).padding(top = 1.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = LocalAmberType.current.sessionTitle.copy(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 22.sp,
                ),
                color = t.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Meta(meta, topPadding = 6.dp)
        }
    }
}

/**
 * Task status dot: run/in-progress = breathing accent; done = static signal green;
 * pending/other = grey idle. Reuses [LiveDot] for the signal/idle variants; the accent
 * breathing dot is drawn locally since LiveDot hardcodes the signal color.
 */
@Composable
private fun StatusDot(state: String) {
    val t = LocalAmberTokens.current
    when (state) {
        BoardTaskState.IN_PROGRESS, BoardTaskState.WAITING_USER -> AccentLiveDot(t.accent)
        BoardTaskState.DONE -> Box(Modifier.size(7.dp).clip(CircleShape).background(t.signal))
        else -> LiveDot(idle = true, dotSize = 7.dp)
    }
}

/** Breathing dot in an arbitrary [color] (accent for running tasks). */
@Composable
private fun AccentLiveDot(color: Color, dotSize: Dp = 7.dp) {
    val tr = rememberInfiniteTransition(label = "accentDot")
    val p by tr.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "p",
    )
    Box(Modifier.size(dotSize * 2.2f), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(dotSize)
                .graphicsLayer {
                    val s = 1f + p * 1.1f
                    scaleX = s
                    scaleY = s
                    alpha = 0.35f * (1f - p)
                }
                .background(color, CircleShape),
        )
        Box(Modifier.size(dotSize).background(color, CircleShape))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodayReviewTab(
    todoItems: List<BoardItemEntity>,
    review: DailyReviewEntity?,
    onRefresh: () -> Unit,
    onComplete: (String) -> Unit,
    onChat: (BoardItemEntity) -> Unit,
) {
    val pullState = rememberPullToRefreshState()
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(
        todoItems.map { Triple(it.id, it.status, it.signalTime) },
        review?.updatedAt,
    ) {
        if (isRefreshing) isRefreshing = false
    }
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            onRefresh()
            scope.launch {
                delay(15_000L)
                isRefreshing = false
            }
        },
        state = pullState,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { SectionTitle("📋 待办", if (todoItems.isEmpty()) "暂无" else "${todoItems.size.coerceAtMost(5)} 条") }
            if (todoItems.isEmpty()) {
                item { EmptyLine("没有新的待办。") }
            } else {
                items(todoItems.take(5), key = { it.id }) { item ->
                    TodoRow(item = item, onComplete = { onComplete(item.id) }, onChat = { onChat(item) })
                }
            }
            item { SectionTitle("📝 今日回顾", review?.let { reviewPhaseLabel(it) }) }
            item {
                if (review == null) {
                    ReviewEmptyState()
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = workspaceColors().paper,
                        border = workspaceBorder(),
                    ) {
                        MarkdownBlock(content = review.content, modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskFlowTab(
    opportunities: List<OpportunityEntity>,
    tasks: List<BoardTaskEntity>,
    review: DailyReviewEntity?,
    onRefresh: () -> Unit,
    onDispatchOpportunity: (OpportunityEntity) -> Unit,
    onShowOpportunityEvidence: (OpportunityEntity) -> Unit,
    onDismissOpportunity: (OpportunityEntity) -> Unit,
    onMuteOpportunityType: (OpportunityEntity) -> Unit,
    onDispatch: (BoardTaskEntity) -> Unit,
    onDone: (String) -> Unit,
    onIgnore: (String) -> Unit,
    onCancel: (String) -> Unit,
    onSnooze: (String) -> Unit,
    onOpenSession: (BoardTaskEntity) -> Unit,
) {
    val pullState = rememberPullToRefreshState()
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(
        opportunities.map { Triple(it.id, it.status, it.updatedAt) },
        tasks.map { Triple(it.id, it.state, it.updatedAt) },
        review?.updatedAt,
    ) {
        if (isRefreshing) isRefreshing = false
    }
    val inProgress = tasks.filter { it.state == BoardTaskState.IN_PROGRESS }
    val waiting = tasks.filter { it.state == BoardTaskState.WAITING_USER }
    val terminal = tasks.filter {
        it.state == BoardTaskState.DONE ||
            it.state == BoardTaskState.DISMISSED ||
            it.state == BoardTaskState.BLOCKED
    }
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            onRefresh()
            scope.launch {
                delay(15_000L)
                isRefreshing = false
            }
        },
        state = pullState,
        modifier = Modifier.fillMaxSize(),
    ) {
        // One merged "智能体任务" list: running first, then waiting on user, then a
        // capped tail of finished/ignored/blocked records. Opportunities and the daily
        // review sit below as flat sub-blocks.
        val mergedTasks = inProgress + waiting + terminal.take(8)
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { RubricHead("智能体任务", "实时") }
            if (mergedTasks.isEmpty()) {
                item { EmptyLine("暂无智能体任务。") }
            } else {
                items(mergedTasks, key = { it.id }) { task ->
                    TaskRow(
                        task = task,
                        onDispatch = { onDispatch(task) },
                        onDone = { onDone(task.id) },
                        onIgnore = { onIgnore(task.id) },
                        onCancel = { onCancel(task.id) },
                        onSnooze = { onSnooze(task.id) },
                        onOpenSession = { onOpenSession(task) },
                    )
                }
            }
            item { RubricHead("机会建议", null) }
            if (opportunities.isEmpty()) {
                item { EmptyLine("没有新的主动机会。") }
            } else {
                items(opportunities, key = { "opp_${it.id}" }) { opportunity ->
                    OpportunityRow(
                        opportunity = opportunity,
                        onDispatch = { onDispatchOpportunity(opportunity) },
                        onShowEvidence = { onShowOpportunityEvidence(opportunity) },
                        onDismiss = { onDismissOpportunity(opportunity) },
                        onMuteType = { onMuteOpportunityType(opportunity) },
                    )
                }
            }
            item { RubricHead("今日复盘", review?.let { reviewPhaseLabel(it) }) }
            item {
                if (review == null) {
                    ReviewEmptyState()
                } else {
                    MarkdownBlock(content = review.content, modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun OpportunityRow(
    opportunity: OpportunityEntity,
    onDispatch: () -> Unit,
    onShowEvidence: () -> Unit,
    onDismiss: () -> Unit,
    onMuteType: () -> Unit,
) {
    val t = LocalAmberTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .bottomHairline(t.line)
            .padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            opportunity.title,
            style = LocalAmberType.current.sessionTitle.copy(
                fontSize = 15.5.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 22.sp,
            ),
            color = t.ink,
        )
        Text(
            "${opportunity.typeLabel()} · ${sourceLabel(opportunity.sourceType)} · ${(opportunity.confidence * 100).toInt()}%",
            style = LocalAmberType.current.meta.copy(fontSize = 11.5.sp),
            color = t.ink3,
        )
        if (opportunity.summary.isNotBlank()) {
            Text(opportunity.summary, style = LocalAmberType.current.secondary, color = t.ink3)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WorkspaceTextButton(text = "派发", onClick = onDispatch, tone = WorkspaceTone.Success)
            WorkspaceTextButton(text = "查看依据", onClick = onShowEvidence, tone = WorkspaceTone.Neutral)
            WorkspaceTextButton(text = "忽略", onClick = onDismiss, tone = WorkspaceTone.Neutral)
            WorkspaceTextButton(text = "不再提醒这类", onClick = onMuteType, tone = WorkspaceTone.Neutral)
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TaskRow(
    task: BoardTaskEntity,
    onDispatch: () -> Unit,
    onDone: () -> Unit,
    onIgnore: () -> Unit,
    onCancel: () -> Unit,
    onSnooze: () -> Unit,
    onOpenSession: () -> Unit,
) {
    val t = LocalAmberTokens.current
    val terminal = task.state == BoardTaskState.DONE || task.state == BoardTaskState.DISMISSED
    val dispatchable = task.state == BoardTaskState.DISMISSED ||
        task.state == BoardTaskState.BLOCKED
    Column(
        Modifier
            .fillMaxWidth()
            .bottomHairline(t.line)
            .padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSession),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.padding(top = 5.dp)) { StatusDot(task.state) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    task.title,
                    style = LocalAmberType.current.sessionTitle.copy(
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 22.sp,
                    ),
                    color = if (terminal) t.ink3 else t.ink,
                )
                Text(
                    "${task.stateLabel()} · ${sourceLabel(task.sourceType)} · ${timeAgo(task.updatedAt)}",
                    style = LocalAmberType.current.meta.copy(fontSize = 11.5.sp),
                    color = t.ink3,
                )
                if (task.summary.isNotBlank()) {
                    Text(task.summary, style = LocalAmberType.current.secondary, color = t.ink3)
                }
            }
            Icon(
                HugeIcons.ArrowRight01,
                contentDescription = null,
                modifier = Modifier.size(18.dp).padding(top = 2.dp),
                tint = t.ink4,
            )
        }
        // Surface the finished structured material so the user has something concrete to
        // confirm. Only shown once a round has settled (waiting_user / done); while running
        // the artifact slot is cleared by the repository, so this stays null.
        if (task.state == BoardTaskState.WAITING_USER || task.state == BoardTaskState.DONE) {
            rememberBoardTaskArtifact(task.artifactJson)?.let { TaskArtifactBlock(it) }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (task.state) {
                    BoardTaskState.IN_PROGRESS -> {
                        WorkspaceTextButton(text = "查看进展", onClick = onOpenSession, tone = WorkspaceTone.Success)
                        WorkspaceTextButton(text = "完成", onClick = onDone, tone = WorkspaceTone.Success)
                        WorkspaceTextButton(text = "取消", onClick = onCancel, tone = WorkspaceTone.Neutral)
                    }

                    BoardTaskState.WAITING_USER -> {
                        WorkspaceTextButton(text = "去确认", onClick = onOpenSession, tone = WorkspaceTone.Success)
                        WorkspaceTextButton(text = "稍后", onClick = onSnooze, tone = WorkspaceTone.Neutral)
                        WorkspaceTextButton(text = "取消", onClick = onCancel, tone = WorkspaceTone.Neutral)
                    }

                    BoardTaskState.BLOCKED -> {
                        WorkspaceTextButton(text = "重新派发", onClick = onDispatch, tone = WorkspaceTone.Success)
                        WorkspaceTextButton(text = "查看问题", onClick = onOpenSession, tone = WorkspaceTone.Neutral)
                        WorkspaceTextButton(text = "取消", onClick = onCancel, tone = WorkspaceTone.Neutral)
                    }

                    BoardTaskState.DONE -> {
                        WorkspaceTextButton(text = "查看记录", onClick = onOpenSession, tone = WorkspaceTone.Neutral)
                    }

                    BoardTaskState.DISMISSED -> {
                        WorkspaceTextButton(text = "查看记录", onClick = onOpenSession, tone = WorkspaceTone.Neutral)
                        WorkspaceTextButton(text = "重新派发", onClick = onDispatch, tone = WorkspaceTone.Success)
                    }

                    else -> {
                        if (dispatchable) {
                            WorkspaceTextButton(text = "派发", onClick = onDispatch, tone = WorkspaceTone.Success)
                        }
                        WorkspaceTextButton(text = "查看详情", onClick = onOpenSession, tone = WorkspaceTone.Neutral)
                    }
                }
        }
    }
}

@Composable
private fun rememberBoardTaskArtifact(artifactJson: String?): BoardTaskArtifact? =
    remember(artifactJson) {
        artifactJson
            ?.let { json -> runCatching { JsonInstant.decodeFromString(BoardTaskArtifact.serializer(), json) }.getOrNull() }
            ?.takeIf { it.title.isNotBlank() || it.sections.isNotEmpty() }
    }

@Composable
private fun TaskArtifactBlock(artifact: BoardTaskArtifact) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = workspaceColors().paper,
        border = workspaceBorder(),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (artifact.title.isNotBlank()) {
                Text(
                    artifact.title,
                    style = LocalAmberType.current.sessionTitle,
                )
            }
            artifact.sections.forEach { section ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (section.heading.isNotBlank()) {
                        Text(
                            section.heading,
                            style = LocalAmberType.current.body.copy(fontWeight = FontWeight.Medium),
                        )
                    }
                    section.oldValue?.takeIf { it.isNotBlank() }?.let {
                        Text("原值：$it", style = LocalAmberType.current.secondary, color = workspaceColors().muted)
                    }
                    section.newValue?.takeIf { it.isNotBlank() }?.let {
                        Text("新值：$it", style = LocalAmberType.current.secondary)
                    }
                    section.suggestedRewrite?.takeIf { it.isNotBlank() }?.let {
                        Text("建议改写：$it", style = LocalAmberType.current.secondary)
                    }
                    if (section.body.isNotBlank()) {
                        Text(section.body, style = LocalAmberType.current.secondary)
                    }
                    val sources = (section.sources + listOfNotNull(section.upstreamSource?.takeIf { it.isNotBlank() }))
                        .filter { it.isNotBlank() }
                    if (sources.isNotEmpty()) {
                        Text(
                            "来源：${sources.joinToString("、")}",
                            style = LocalAmberType.current.secondary,
                            color = workspaceColors().muted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoRow(item: BoardItemEntity, onComplete: () -> Unit, onChat: () -> Unit) {
    val completed = item.status == "completed"
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = workspaceColors().paper,
        border = workspaceBorder(),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(28.dp),
                shape = CircleShape,
                color = if (completed) MaterialTheme.colorScheme.primaryContainer else workspaceColors().paper,
                border = workspaceBorder(),
                onClick = { if (!completed) onComplete() },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (completed) Icon(HugeIcons.Tick01, contentDescription = null, modifier = Modifier.size(14.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.title,
                    style = LocalAmberType.current.sessionTitle.copy(
                        fontStyle = if (completed) FontStyle.Italic else FontStyle.Normal,
                    ),
                    color = if (completed) workspaceColors().muted else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "${sourceLabel(item.sourceType)} · ${timeAgo(item.signalTime)}",
                    style = LocalAmberType.current.meta,
                    color = workspaceColors().muted,
                )
                if (item.reason.isNotBlank()) {
                    Text(item.reason, style = LocalAmberType.current.secondary, color = workspaceColors().muted)
                }
            }
            WorkspaceTextButton(text = "聊一下", onClick = onChat, tone = WorkspaceTone.Neutral)
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(title)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = LocalAmberType.current.meta, color = workspaceColors().muted)
        }
    }
}

@Composable
private fun HotListSkeleton() {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { SectionTitle("🔥 综合热点", "正在更新") }
        items(6) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            )
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 14.dp),
        style = LocalAmberType.current.secondary,
        color = LocalAmberTokens.current.ink3,
    )
}

@Composable
private fun ReviewEmptyState() {
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                imageVector = HugeIcons.Notebook01,
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Text("今日复盘尚未生成", style = LocalAmberType.current.sessionTitle)
            Text("将在午间和晚间自动补全", style = LocalAmberType.current.secondary, color = workspaceColors().muted)
        }
    }
}

private fun reviewPhaseLabel(review: DailyReviewEntity): String {
    val time = Instant.ofEpochMilli(review.updatedAt)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))
    return if (review.phase == "evening") "下午已更新 · $time" else "上午版 · $time"
}

private fun sourceLabel(sourceType: String): String = when (sourceType) {
    "notification" -> "系统通知"
    "calendar" -> "日历"
    "feishu_msg" -> "飞书消息"
    "feishu_doc" -> "飞书文档"
    "chat_history" -> "聊天记录"
    else -> sourceType
}

private fun BoardTaskEntity.stateLabel(): String = when (state) {
    BoardTaskState.IN_PROGRESS -> "已经派发"
    BoardTaskState.WAITING_USER -> "等待确认"
    BoardTaskState.BLOCKED -> "遇到阻碍"
    BoardTaskState.DONE -> "任务完成"
    BoardTaskState.DISMISSED -> "已忽略"
    else -> "已经派发"
}

private fun OpportunityEntity.typeLabel(): String = when (opportunityType) {
    OpportunityType.MEETING_PREP -> "会议准备"
    OpportunityType.DEPENDENCY_STALE -> "文档过期"
    else -> "可能事项"
}

private fun timeAgo(timestamp: Long): String {
    if (timestamp <= 0L) return "未知时间"
    val diff = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    val minutes = diff / 60_000L
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes}分钟前"
        minutes < 24 * 60 -> "${minutes / 60}小时前"
        else -> "${minutes / (24 * 60)}天前"
    }
}

private fun HotTopic.primaryUrl(): String? =
    sources
        .sortedBy { it.rank }
        .firstNotNullOfOrNull { source ->
            source.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }
