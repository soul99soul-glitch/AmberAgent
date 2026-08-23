package app.amber.feature.ui.pages.board

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
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
import app.amber.feature.ui.components.ds.Hairline
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.workspaceBorder
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.components.ds.LiveDot
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayBoardPage() {
    val navController = LocalNavController.current
    val vm: BoardViewModel = koinInject()

    val settings by vm.settings.collectAsStateWithLifecycle()
    val boardEnabled = settings.agentRuntime.todayBoard.enabled
    val dashboard by vm.hotListDashboard.collectAsStateWithLifecycle(
        initialValue = HotListDashboard(emptyList(), emptyList(), 0L),
    )
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var pendingDeepRead by remember { mutableStateOf<PendingDeepReadRequest?>(null) }
    var selectedTopic by remember { mutableStateOf<HotTopic?>(null) }

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
                    Text("深度阅读", fontWeight = FontWeight.Bold)
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

        HotListTab(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            dashboard = dashboard,
            filterMode = settings.agentRuntime.todayBoard.hotListFilterMode,
            onRefresh = vm::refreshHotList,
            onTopicClick = { topic -> selectedTopic = topic },
            onProviderItemClick = { provider, item ->
                scope.launch { selectedTopic = vm.createProviderTopic(provider, item) }
            },
        )
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

private data class PendingDeepReadRequest(
    val topic: HotTopic,
    val forceRegenerate: Boolean,
)

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HotListTab(
    modifier: Modifier = Modifier,
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
        modifier = modifier,
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

private fun timeAgo(timestamp: Long): String {
    if (timestamp <= 0L) return "未知时间"
    val diff = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    val minutes = diff / 60_000L
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${'$'}{minutes}分钟前"
        minutes < 24 * 60 -> "${'$'}{minutes / 60}小时前"
        else -> "${'$'}{minutes / (24 * 60)}天前"
    }
}

private fun HotTopic.primaryUrl(): String? =
    sources
        .sortedBy { it.rank }
        .firstNotNullOfOrNull { source ->
            source.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }
