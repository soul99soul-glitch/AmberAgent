package app.amber.feature.ui.pages.miniapp

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.db.entity.MiniAppEntity
import me.rerere.rikkahub.data.db.entity.MiniAppVersionEntity
import app.amber.feature.ui.theme.JetbrainsMono

@Composable
fun MiniAppRenameDialog(
    app: MiniAppEntity,
    onDismiss: () -> Unit,
    onConfirm: (title: String, description: String) -> Unit,
) {
    var title by remember(app.id) { mutableStateOf(app.title) }
    var description by remember(app.id) { mutableStateOf(app.description) }
    val normalizedTitle = title.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名小应用") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(40) },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(120) },
                    label = { Text("描述") },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = normalizedTitle.isNotBlank(),
                onClick = { onConfirm(normalizedTitle, description.trim()) },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
fun MiniAppDeleteDialog(
    app: MiniAppEntity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除小应用") },
        text = { Text("确定删除「${app.title}」吗？历史聊天里的小应用卡片会保留，但再次运行时会显示小应用不存在。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
fun MiniAppSourceDialog(
    app: MiniAppEntity,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("源码") },
        text = {
            SelectionContainer {
                Text(
                    text = app.htmlContent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
fun rememberMiniAppHtmlExporter(): (MiniAppEntity) -> Unit {
    val context = LocalContext.current
    var pendingApp by remember { mutableStateOf<MiniAppEntity?>(null) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/html")
    ) { uri: Uri? ->
        val app = pendingApp
        if (uri != null && app != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(app.htmlContent.toByteArray(Charsets.UTF_8))
                } ?: error("无法打开导出目标")
            }.onFailure {
                Toast.makeText(context, "导出失败：${it.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
            }
        }
        pendingApp = null
    }
    return { app ->
        pendingApp = app
        launcher.launch("${app.title.safeExportName().ifBlank { "miniapp" }}.html")
    }
}

@Composable
fun MiniAppVersionHistoryDialog(
    app: MiniAppEntity,
    versions: List<MiniAppVersionEntity>,
    onDismiss: () -> Unit,
    onRestore: (MiniAppVersionEntity) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("版本历史") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                versions.forEach { version ->
                    ListItem(
                        headlineContent = { Text("v${version.versionNumber}") },
                        supportingContent = {
                            Text(version.changeNote ?: if (version.versionNumber == app.version) "当前版本" else "历史版本")
                        },
                        trailingContent = {
                            TextButton(
                                enabled = version.versionNumber != app.version,
                                onClick = { onRestore(version) },
                            ) {
                                Text("恢复")
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

private fun String.safeExportName(): String {
    return trim()
        .replace(Regex("""[\\/:*?"<>|]+"""), "_")
        .take(48)
}
