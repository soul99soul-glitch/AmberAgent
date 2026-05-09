package me.rerere.rikkahub.ui.components.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Refresh

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceFileSheet(
    vm: WorkspaceFileVM,
    onDismiss: () -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.7f)
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Workspace 文件", style = MaterialTheme.typography.titleLarge)
                Row {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(HugeIcons.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(HugeIcons.Cancel01, contentDescription = "关闭")
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else if (groups.isEmpty()) {
                Text(
                    text = "还没有文件。让 Amber 帮你生成报告或分析。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 32.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    groups.forEach { group ->
                        item(key = "header-${group.label}") {
                            WorkspaceGroupRow(group = group)
                        }
                        items(group.items, key = { it.path }) { file ->
                            WorkspaceFileRow(file = file, onClick = { onOpenFile(file.path) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceGroupRow(group: WorkspaceGroup) {
    var collapsed by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { collapsed = !collapsed },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HugeIcons.Folder01, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(group.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("${group.items.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WorkspaceFileRow(file: WorkspaceFileItem, onClick: () -> Unit) {
    val extColor = when (file.extension) {
        "md" -> MaterialTheme.colorScheme.primary
        "json" -> MaterialTheme.colorScheme.tertiary
        "png", "jpg", "jpeg", "gif", "webp" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(buildMeta(file), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Surface(shape = RoundedCornerShape(4.dp), color = extColor.copy(alpha = 0.12f)) {
            Text(file.extension.uppercase().take(4), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = extColor)
        }
    }
}

private fun buildMeta(file: WorkspaceFileItem): String {
    val parts = mutableListOf<String>()
    file.sizeBytes?.let { parts.add(formatSize(it)) }
    if (file.lastModified > 0) parts.add(formatTime(file.lastModified))
    return parts.joinToString(" · ")
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))}MB"
}

private fun formatTime(epochMs: Long): String {
    val diff = System.currentTimeMillis() - epochMs
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3600_000 -> "${diff / 60_000}分钟前"
        diff < 86_400_000 -> "${diff / 3600_000}小时前"
        else -> java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(epochMs))
    }
}
