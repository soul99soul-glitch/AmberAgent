package app.amber.feature.ui.pages.board

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.feature.ui.theme.LocalAmberTokens
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import app.amber.agent.Screen
import app.amber.agent.R
import app.amber.feature.board.hotlist.DeepReadHistoryItem
import app.amber.feature.board.hotlist.HotListRepository
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.core.utils.plus
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
    val tokens = LocalAmberTokens.current
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.amberCanvas(),
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.deep_read_history_title),
                navigationIcon = { BackButton() },
            )
        },
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
    ) { innerPadding ->
        if (history.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.deep_read_history_empty), color = colors.muted)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("//", style = LocalAmberType.current.eyebrow, color = tokens.accent)
                    Text(stringResource(R.string.deep_read_history_title), style = LocalAmberType.current.eyebrow, color = tokens.ink2)
                    Text(history.size.toString(), style = LocalAmberType.current.eyebrow, color = tokens.ink3)
                    HorizontalDivider(Modifier.weight(1f), color = tokens.line)
                }
            }
            itemsIndexed(history, key = { _, item -> item.topicId }) { index, item ->
                DeepReadHistoryRow(
                    item = item,
                    onClick = {
                        repository.rememberDeepReadHistoryPreview(item)
                        navController.navigate(
                            Screen.DeepRead(
                                topicId = item.topicId,
                                title = item.title,
                                sourceUrl = item.sourceUrl,
                                fromHistory = true,
                            )
                        )
                    },
                    onTogglePin = { pinned ->
                        scope.launch { repository.setDeepReadPinned(item.topicId, pinned) }
                    },
                )
                if (index != history.lastIndex) {
                    HorizontalDivider(color = tokens.line)
                }
            }
        }
    }
}

@Composable
private fun DeepReadHistoryRow(
    item: DeepReadHistoryItem,
    onClick: () -> Unit,
    onTogglePin: (Boolean) -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(22.dp)
                .background(if (item.pinned) tokens.accent else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(1.dp)),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = item.title,
                style = LocalAmberType.current.sessionTitle.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
                color = tokens.ink,
            )
            Text(
                text = formatHistoryTime(item.updatedAt),
                style = LocalAmberType.current.meta,
                color = tokens.ink3,
            )
        }
        Column(
            modifier = Modifier.width(74.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            DeepReadHistoryStatus(
                text = when {
                    item.pinned -> stringResource(R.string.deep_read_pinned)
                    item.expired -> stringResource(R.string.deep_read_expired)
                    else -> stringResource(R.string.deep_read_active)
                },
                tone = when {
                    item.pinned -> HistoryTone.Accent
                    item.expired -> HistoryTone.Warning
                    else -> HistoryTone.Success
                },
            )
            TextButton(
                onClick = { onTogglePin(!item.pinned) },
                contentPadding = PaddingValues(horizontal = 3.dp, vertical = 0.dp),
            ) {
                Text(
                    if (item.pinned) stringResource(R.string.deep_read_unfavorite)
                    else stringResource(R.string.deep_read_favorite),
                    style = LocalAmberType.current.secondary,
                    color = tokens.ink2,
                )
            }
        }
    }
}

private enum class HistoryTone { Accent, Success, Warning }

@Composable
private fun DeepReadHistoryStatus(text: String, tone: HistoryTone) {
    val tokens = LocalAmberTokens.current
    val base = when (tone) {
        HistoryTone.Accent -> tokens.accent
        HistoryTone.Success -> tokens.signal
        HistoryTone.Warning -> MaterialTheme.colorScheme.tertiary
    }
    Surface(
        modifier = Modifier.height(22.dp),
        shape = CircleShape,
        color = base.copy(alpha = 0.14f),
        border = androidx.compose.foundation.BorderStroke(1.dp, base.copy(alpha = 0.38f)),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(5.dp).background(base, CircleShape))
            Text(text, style = LocalAmberType.current.tinyTag, color = base, maxLines = 1)
        }
    }
}

@Composable
private fun formatHistoryTime(timestamp: Long): String {
    if (timestamp <= 0L) return stringResource(R.string.board_time_unknown)
    return Instant.ofEpochMilli(timestamp)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}
