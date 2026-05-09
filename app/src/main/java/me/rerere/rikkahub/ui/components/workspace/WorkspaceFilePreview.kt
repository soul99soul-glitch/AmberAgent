package me.rerere.rikkahub.ui.components.workspace

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.data.agent.workspace.WorkspaceManager
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock

sealed interface PreviewContent {
    data class Text(val text: String) : PreviewContent
    data class Image(val bitmap: android.graphics.Bitmap) : PreviewContent
    data class Error(val message: String) : PreviewContent
}

@Composable
fun WorkspaceFilePreview(
    relativePath: String,
    workspaceManager: WorkspaceManager,
    onDismiss: () -> Unit,
) {
    var content by remember { mutableStateOf<PreviewContent?>(null) }

    LaunchedEffect(relativePath) {
        val ext = relativePath.substringAfterLast('.', "").lowercase()
        content = when (ext) {
            in setOf("md", "txt", "json", "html", "csv", "xml", "yml", "yaml", "log") -> {
                runCatching {
                    PreviewContent.Text(workspaceManager.readText(relativePath))
                }.getOrElse { PreviewContent.Error(it.message ?: "读取失败") }
            }
            in setOf("png", "jpg", "jpeg", "gif", "webp") -> {
                runCatching {
                    val bytes = workspaceManager.readBytes(relativePath)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) PreviewContent.Image(bitmap)
                    else PreviewContent.Error("无法解码图片")
                }.getOrElse { PreviewContent.Error(it.message ?: "读取失败") }
            }
            else -> PreviewContent.Error("不支持预览此文件类型 (.$ext)")
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(relativePath.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(HugeIcons.Cancel01, contentDescription = "关闭") }
                }
                when (val c = content) {
                    null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    is PreviewContent.Text -> {
                        val scrollState = rememberScrollState()
                        if (relativePath.endsWith(".md", ignoreCase = true)) {
                            MarkdownBlock(c.text, Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 16.dp))
                        } else {
                                SelectionContainer {
                                    Text(c.text, Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 16.dp),
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                                }
                        }
                    }
                    is PreviewContent.Image -> Image(c.bitmap.asImageBitmap(), contentDescription = relativePath, modifier = Modifier.fillMaxSize())
                    is PreviewContent.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(c.message, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
