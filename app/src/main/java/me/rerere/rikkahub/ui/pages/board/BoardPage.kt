package me.rerere.rikkahub.ui.pages.board

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Notebook01
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.entity.BoardItemEntity
import me.rerere.rikkahub.data.db.entity.DailyReviewEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.NotionListRow
import me.rerere.rikkahub.ui.components.ui.WorkspaceTone
import me.rerere.rikkahub.ui.components.ui.WorkspaceStatusPill
import me.rerere.rikkahub.ui.components.ui.WorkspaceTextButton
import me.rerere.rikkahub.ui.components.ui.workspaceBorder
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.navigateToChatPage
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayBoardPage() {
    val navController = LocalNavController.current
    val vm: BoardViewModel = koinInject()

    val settings by vm.settings.collectAsStateWithLifecycle()
    val boardEnabled = settings.agentRuntime.todayBoard.enabled
    val items by vm.items.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeItems = items.filter { it.status == "active" }
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    var expandedItemId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "今日看板",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(HugeIcons.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = { navController.navigate(Screen.SettingTodayBoard) }) {
                        Icon(HugeIcons.Settings03, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        }
    ) { innerPadding ->
        if (!boardEnabled) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "今日看板未启用\n请在实验性功能设置中开启",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无内容\n稍后会有信号汇入", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                val dailyReview by vm.dailyReview.collectAsStateWithLifecycle(initialValue = null)
                val workspace = workspaceColors()

                SecondaryTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                        text = {
                            Text(
                                "大看板",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (pagerState.currentPage == 0) FontWeight.SemiBold else FontWeight.Normal,
                                ),
                            )
                        },
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                        text = {
                            Text(
                                "今日回顾",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (pagerState.currentPage == 1) FontWeight.SemiBold else FontWeight.Normal,
                                ),
                            )
                        },
                    )
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (page) {
                        0 -> BoardSectionTab(
                            items = activeItems,
                            expandedItemId = expandedItemId,
                            onToggleExpand = { id ->
                                expandedItemId = if (expandedItemId == id) null else id
                            },
                            onComplete = { vm.markCompleted(it) },
                            onDismiss = { vm.markDismissed(it) },
                            onChat = { itemId ->
                                vm.startChat(itemId)
                                activeItems.find { it.id == itemId }?.let { item ->
                                    navigateToChatPage(navController, initText = chatContext(item))
                                }
                            },
                        )

                        1 -> DailyReviewTab(
                            review = dailyReview,
                            onRefresh = { vm.refresh() },
                        )
                    }
                }
            }
        }
    }
}

private fun chatContext(item: BoardItemEntity): String =
    "看板条目: ${item.title}\n\n原因: ${item.reason}\n建议: ${item.suggestion}\n\n来源内容: ${item.sourceContent.take(1000)}"

// ── Tab 0: Board sections ────────────────────────────────────────────────────

@Composable
fun BoardSectionTab(
    items: List<BoardItemEntity>,
    expandedItemId: String?,
    onToggleExpand: (String) -> Unit,
    onComplete: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onChat: (String) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val actions = items.filter { it.category == "action" }
        val attentions = items.filter { it.category == "attention" }
        val infos = items.filter { it.category == "info" }

        if (actions.isNotEmpty()) {
            item { BoardSectionHeader("要做的事", actions.size, "📋") }
            item { Spacer(Modifier.height(4.dp)) }
            items(actions, key = { it.id }) { item ->
                BoardItemRow(item, expandedItemId, onToggleExpand, onComplete, onDismiss, onChat)
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        if (attentions.isNotEmpty()) {
            item { BoardSectionHeader("值得关注", attentions.size, "👀") }
            item { Spacer(Modifier.height(4.dp)) }
            items(attentions, key = { it.id }) { item ->
                BoardItemRow(item, expandedItemId, onToggleExpand, onComplete, onDismiss, onChat)
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        if (infos.isNotEmpty()) {
            item { BoardSectionHeader("今日动态", infos.size, "📰") }
            item { Spacer(Modifier.height(4.dp)) }
            items(infos, key = { it.id }) { item ->
                BoardItemRow(item, expandedItemId, onToggleExpand, onComplete, onDismiss, onChat)
            }
        }
    }
}

@Composable
private fun BoardSectionHeader(title: String, count: Int, emoji: String) {
    val workspace = workspaceColors()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(emoji, style = MaterialTheme.typography.titleSmall)
        Text(
            title,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
            ),
        )
        Spacer(Modifier.width(4.dp))
        Badge(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Text("$count", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun BoardItemRow(
    item: BoardItemEntity,
    expandedItemId: String?,
    onToggleExpand: (String) -> Unit,
    onComplete: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onChat: (String) -> Unit,
) {
    val workspace = workspaceColors()
    val isExpanded = item.id == expandedItemId
    val (accentColor, accentTone) = urgencyAccent(item.urgency)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(workspaceBorder(), MaterialTheme.shapes.medium),
    ) {
        // Left-accent stripe + row
        Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(56.dp)
                    .background(accentColor),
            )
            NotionListRow(
                title = item.title,
                subtitle = item.reason.take(80),
                modifier = Modifier.weight(1f),
                trailing = {
                    // Checkbox affordance: circle with Tick icon
                    Surface(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape),
                        shape = CircleShape,
                        color = workspace.paper,
                        border = workspaceBorder(),
                        onClick = { onComplete(item.id) },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = HugeIcons.Tick01,
                                contentDescription = "完成",
                                modifier = Modifier.size(15.dp),
                                tint = workspace.muted,
                            )
                        }
                    }
                },
                onClick = { onToggleExpand(item.id) },
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            )
        }

        // Expandable detail card
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(tween(150)) + fadeIn(tween(150)),
            exit = shrinkVertically(tween(120)) + fadeOut(tween(120)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(workspace.paper)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (item.suggestion.isNotBlank()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text("💡", style = MaterialTheme.typography.bodySmall)
                        Text(
                            item.suggestion,
                            style = MaterialTheme.typography.bodySmall,
                            color = workspace.ink,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WorkspaceTextButton(
                        text = "忽略",
                        onClick = { onDismiss(item.id) },
                        tone = WorkspaceTone.Neutral,
                    )
                    WorkspaceTextButton(
                        text = "聊一下",
                        onClick = { onChat(item.id) },
                        tone = WorkspaceTone.Accent,
                    )
                }
            }
        }
    }
}

// ── Tab 1: Daily Review ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyReviewTab(
    review: DailyReviewEntity?,
    onRefresh: () -> Unit,
) {
    val workspace = workspaceColors()
    val pullState = rememberPullToRefreshState()
    var isRefreshing by remember { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = false
            onRefresh()
        },
        state = pullState,
        modifier = Modifier.fillMaxSize(),
    ) {
        if (review == null) {
            ReviewEmptyState()
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    ReviewHeader(review)
                }
                item(key = "review_${review.id}") {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(workspaceBorder(), RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp),
                        color = workspace.paper,
                    ) {
                        MarkdownBlock(
                            content = review.content,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewHeader(review: DailyReviewEntity) {
    val isEvening = review.phase == "evening"
    val updatedTime = java.time.Instant.ofEpochMilli(review.updatedAt)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "今日回顾",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkspaceStatusPill(
                text = if (isEvening) "下午已更新" else "上午版",
                tone = if (isEvening) WorkspaceTone.Success else WorkspaceTone.Warning,
            )
            WorkspaceStatusPill(
                text = updatedTime,
                tone = WorkspaceTone.Neutral,
            )
        }
    }
}

@Composable
private fun ReviewEmptyState() {
    val workspace = workspaceColors()
    Box(
        Modifier
            .fillMaxSize()
            .padding(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Illustration-like icon cluster
            Box(contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                    border = workspaceBorder(),
                ) {}
                Icon(
                    imageVector = HugeIcons.Notebook01,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "今日回顾尚未生成",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = workspace.ink,
                )
                Text(
                    "将在 13:00 生成上午回顾\n19:00 补充下午内容",
                    style = MaterialTheme.typography.bodySmall,
                    color = workspace.muted,
                )
            }
        }
    }
}

// ── Urgency helpers ──────────────────────────────────────────────────────────

/**
 * Maps urgency level to a workspace semantic color + its tone enum.
 * Uses [workspaceColors] tokens instead of hardcoded hex values.
 */
@Composable
private fun urgencyAccent(urgency: String): Pair<Color, WorkspaceTone> {
    val workspace = workspaceColors()
    return when (urgency) {
        "high" -> workspace.red to WorkspaceTone.Danger
        "medium" -> workspace.amber to WorkspaceTone.Warning
        else -> workspace.green to WorkspaceTone.Success
    }
}
