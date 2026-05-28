package me.rerere.rikkahub.ui.components.workspace

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.webkit.MimeTypeMap
import android.widget.Toast
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Share01
import app.amber.feature.workspace.WorkspaceManager
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import java.io.File

private const val MAX_MARKDOWN_RENDER_CHARS = 200_000
private const val MAX_TEXT_PREVIEW_CHARS = 1_000_000

sealed interface PreviewContent {
    data class Text(
        val text: String,
        val forcePlain: Boolean = false,
        val truncated: Boolean = false,
    ) : PreviewContent
    data class Image(val bitmap: android.graphics.Bitmap) : PreviewContent
    data class Error(val message: String) : PreviewContent
}

@Composable
fun WorkspaceFilePreview(
    relativePath: String,
    workspaceManager: WorkspaceManager,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var content by remember { mutableStateOf<PreviewContent?>(null) }

    LaunchedEffect(relativePath) {
        val ext = relativePath.substringAfterLast('.', "").lowercase()
        content = when (ext) {
            in setOf("md", "txt", "json", "html", "csv", "xml", "yml", "yaml", "log") -> {
                runCatching {
                    val raw = workspaceManager.readText(relativePath)
                    val truncated = raw.length > MAX_TEXT_PREVIEW_CHARS
                    val text = if (truncated) raw.take(MAX_TEXT_PREVIEW_CHARS) else raw
                    val forcePlain = ext != "md" || text.length > MAX_MARKDOWN_RENDER_CHARS
                    PreviewContent.Text(
                        text = text,
                        forcePlain = forcePlain,
                        truncated = truncated,
                    )
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
                    IconButton(
                        onClick = {
                            shareWorkspacePreviewFile(
                                context = context,
                                workspaceManager = workspaceManager,
                                relativePath = relativePath,
                                scope = scope,
                            )
                        },
                    ) {
                        Icon(HugeIcons.Share01, contentDescription = "分享")
                    }
                    IconButton(onClick = onDismiss) { Icon(HugeIcons.Cancel01, contentDescription = "关闭") }
                }
                when (val c = content) {
                    null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    is PreviewContent.Text -> {
                        val scrollState = rememberScrollState()
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (c.truncated || c.forcePlain && relativePath.endsWith(".md", ignoreCase = true)) {
                                Text(
                                    text = if (c.truncated) "文件过大，已截取前 ${MAX_TEXT_PREVIEW_CHARS / 1000}K 字符" else "内容超过渲染上限，已降级为纯文本",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                            if (relativePath.endsWith(".md", ignoreCase = true) && !c.forcePlain) {
                                MarkdownBlock(c.text, Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 16.dp))
                            } else {
                                SelectionContainer {
                                    Text(c.text, Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 16.dp),
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                                }
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

private fun shareWorkspacePreviewFile(
    context: Context,
    workspaceManager: WorkspaceManager,
    relativePath: String,
    scope: CoroutineScope,
) {
    scope.launch {
        runCatching {
            val staged = withContext(Dispatchers.IO) {
                val bytes = workspaceManager.readBytes(relativePath)
                val shareDir = File(context.cacheDir, "workspace-share").also { it.mkdirs() }
                val fileName = relativePath.substringAfterLast('/').ifBlank { "workspace-file.md" }
                val target = shareDir.resolve(fileName)
                target.writeBytes(bytes)
                target
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", staged)
            val mimeType = staged.extension.shareMimeType()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, staged.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享 ${staged.name}"))
        }.onFailure { error ->
            Toast.makeText(context, error.message ?: "无法打开分享面板", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun String.shareMimeType(): String {
    val ext = lowercase()
    return when (ext) {
        "md", "markdown", "txt", "log" -> "text/plain"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
