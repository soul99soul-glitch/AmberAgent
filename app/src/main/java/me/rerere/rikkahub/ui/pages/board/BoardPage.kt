package me.rerere.rikkahub.ui.pages.board

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.entity.BoardItemEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.NotionListRow
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.navigateToChatPage
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
    var selectedTab by remember { mutableIntStateOf(0) }
    var expandedItemId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("今日看板") },
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
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text("今日看板未启用\n请在实验性功能设置中开启", style = MaterialTheme.typography.bodyLarge)
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无内容\n稍后会有信号汇入", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("分区") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("紧急度") })
                }

                if (selectedTab == 0) {
                BoardSectionTab(
                    items = activeItems,
                    expandedItemId = expandedItemId,
                    onToggleExpand = { id -> expandedItemId = if (expandedItemId == id) null else id },
                    onComplete = { vm.markCompleted(it) },
                    onDismiss = { vm.markDismissed(it) },
                    onChat = { itemId ->
                        vm.startChat(itemId)
                        activeItems.find { it.id == itemId }?.let { item ->
                            navigateToChatPage(navController, initText = chatContext(item))
                        }
                    },
                )
            } else {
                BoardUrgencyTab(
                    items = activeItems,
                    onComplete = { vm.markCompleted(it) },
                    onChat = { itemId ->
                        vm.startChat(itemId)
                        activeItems.find { it.id == itemId }?.let { item ->
                            navigateToChatPage(navController, initText = chatContext(item))
                        }
                    },
                )
            }
        }
    }
}

}

private fun chatContext(item: BoardItemEntity): String =
    "看板条目: ${item.title}\n\n原因: ${item.reason}\n建议: ${item.suggestion}\n\n来源内容: ${item.sourceContent.take(1000)}"

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
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val actions = items.filter { it.category == "action" }
        val attentions = items.filter { it.category == "attention" }
        val infos = items.filter { it.category == "info" }

        if (actions.isNotEmpty()) {
            item { SectionHeader("📋 要做的事", actions.size) }
            items(actions, key = { it.id }) { item ->
                BoardItemRow(item, expandedItemId, onToggleExpand, onComplete, onDismiss, onChat)
            }
        }
        if (attentions.isNotEmpty()) {
            item { SectionHeader("👀 值得关注", attentions.size) }
            items(attentions, key = { it.id }) { item ->
                BoardItemRow(item, expandedItemId, onToggleExpand, onComplete, onDismiss, onChat)
            }
        }
        if (infos.isNotEmpty()) {
            item { SectionHeader("📰 今日动态", infos.size) }
            items(infos, key = { it.id }) { item ->
                BoardItemRow(item, expandedItemId, onToggleExpand, onComplete, onDismiss, onChat)
            }
        }
    }
}

@Composable
fun BoardUrgencyTab(
    items: List<BoardItemEntity>,
    onComplete: (String) -> Unit,
    onChat: (String) -> Unit,
) {
    val sorted = items.sortedWith(
        compareBy<BoardItemEntity> { if (it.urgency == "high") 0 else if (it.urgency == "medium") 1 else 2 }
            .thenByDescending { it.signalTime }
    )
    val top3 = sorted.take(3).filter { it.urgency == "high" }
    val timeline = sorted.filter { it !in top3 }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (top3.isNotEmpty()) {
            item { SectionHeader("📌 最紧急", top3.size) }
            items(top3, key = { it.id }) { item ->
                PinnedItemCard(item, onComplete, onChat)
            }
        }
        if (timeline.isNotEmpty()) {
            item { SectionHeader("时间轴", timeline.size) }
            items(timeline, key = { it.id }) { item ->
                TimelineItemRow(item, onComplete)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(8.dp))
        Badge { Text("$count") }
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
    val isExpanded = item.id == expandedItemId
    Column {
        NotionListRow(
            title = item.title,
            subtitle = item.reason.take(80),
            leading = { UrgencyDot(urgencyColor(item.urgency)) },
            trailing = {
                TextButton(onClick = { onComplete(item.id) }) { Text("✓") }
            },
            onClick = { onToggleExpand(item.id) },
        )
        AnimatedVisibility(visible = isExpanded, enter = fadeIn(), exit = fadeOut()) {
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.suggestion.isNotBlank()) {
                        Text("💡 ${item.suggestion}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { onDismiss(item.id) }) { Text("忽略") }
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = { onChat(item.id) }) { Text("💬 聊一下") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinnedItemCard(item: BoardItemEntity, onComplete: (String) -> Unit, onChat: (String) -> Unit) {
    val workspace = workspaceColors()
    val c = urgencyColor(item.urgency)
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = c.copy(alpha = 0.08f)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UrgencyDot(c)
                Spacer(Modifier.width(8.dp))
                Text(item.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { onComplete(item.id) }) { Text("✓") }
            }
            if (item.reason.isNotBlank()) {
                Text(item.reason.take(120), style = MaterialTheme.typography.bodySmall, color = workspace.muted)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onChat(item.id) }) { Text("💬 聊一下") }
            }
        }
    }
}

@Composable
private fun TimelineItemRow(item: BoardItemEntity, onComplete: (String) -> Unit) {
    val timeText = java.time.Instant.ofEpochMilli(item.signalTime)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    NotionListRow(
        title = item.title,
        subtitle = item.reason.take(60),
        leading = { Text(timeText, style = MaterialTheme.typography.labelSmall, color = workspaceColors().muted) },
        trailing = { TextButton(onClick = { onComplete(item.id) }) { Text("✓") } },
    )
}

@Composable
private fun UrgencyDot(color: androidx.compose.ui.graphics.Color) {
    Surface(Modifier.size(10.dp), shape = RoundedCornerShape(50), color = color, content = {})
}

private fun urgencyColor(urgency: String): androidx.compose.ui.graphics.Color = when (urgency) {
    "high" -> androidx.compose.ui.graphics.Color(0xFFE53935)
    "medium" -> androidx.compose.ui.graphics.Color(0xFFFB8C00)
    else -> androidx.compose.ui.graphics.Color(0xFF43A047)
}
