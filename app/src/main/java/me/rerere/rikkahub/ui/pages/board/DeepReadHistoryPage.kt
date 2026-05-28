package me.rerere.rikkahub.ui.pages.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.Screen
import app.amber.feature.board.hotlist.DeepReadHistoryItem
import app.amber.feature.board.hotlist.HotListRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.WorkspaceStatusPill
import me.rerere.rikkahub.ui.components.ui.WorkspaceTone
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeepReadHistoryPage(
    repository: HotListRepository = koinInject(),
) {
    val navController = LocalNavController.current
    val history by repository.observeDeepReadHistory().collectAsStateWithLifecycle(initialValue = emptyList())
    val colors = workspaceColors()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("深度阅读历史") },
                navigationIcon = { BackButton() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        containerColor = colors.canvas,
    ) { innerPadding ->
        if (history.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text("还没有生成过深度阅读", color = colors.muted)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            itemsIndexed(history, key = { _, item -> item.topicId }) { index, item ->
                DeepReadHistoryRow(
                    item = item,
                    onClick = {
                        repository.rememberDeepReadHistoryPreview(item)
                        navController.navigate(
                            Screen.DeepRead(
                                topicId = item.topicId,
                                title = item.title,
                                fromHistory = true,
                            )
                        )
                    },
                )
                if (index != history.lastIndex) {
                    HorizontalDivider(color = colors.hairline)
                }
            }
        }
    }
}

@Composable
private fun DeepReadHistoryRow(
    item: DeepReadHistoryItem,
    onClick: () -> Unit,
) {
    val colors = workspaceColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatHistoryTime(item.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        WorkspaceStatusPill(
            text = if (item.expired) "已失效" else "有效",
            tone = if (item.expired) WorkspaceTone.Warning else WorkspaceTone.Success,
        )
    }
}

private fun formatHistoryTime(timestamp: Long): String {
    if (timestamp <= 0L) return "未知时间"
    return Instant.ofEpochMilli(timestamp)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}
