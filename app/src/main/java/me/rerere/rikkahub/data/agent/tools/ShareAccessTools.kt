package me.rerere.rikkahub.data.agent.tools

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.agent.workspace.WorkspaceManager
import java.io.File

internal fun createShareTextTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "share_text",
    description = "Open Android share sheet with plain text. Requires approval because it sends data outside AmberAgent.",
    parameters = {
        obj(
            "text" to accessStringProp("Text to share."),
            "title" to accessStringProp("Optional chooser title."),
            required = listOf("text")
        )
    },
    needsApproval = true,
    allowsAutoApproval = false,
    execute = { input ->
        deps.trackSystemTool("share_text", "分享文本", "apps", input.safePreview()) {
            val intent = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, input.requiredString("text"))
            val chooser = Intent.createChooser(intent, input.string("title") ?: "Share from AmberAgent")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            textJson { put("success", true) }
        }
    }
)

internal fun createShareFileTool(
    context: Context,
    workspaceManager: WorkspaceManager,
    deps: SystemAccessDeps,
): Tool = Tool(
    name = "share_file",
    description = "Share a file from /workspace through the Android share sheet (e.g. send to WeChat, save to Drive). " +
        "Use only when the user explicitly asks to share/send/export/forward the file to another app or person. " +
        "Do NOT use this when the user just wants to preview/open/browse the artifact inside AmberAgent — for that, " +
        "re-emit the artifact as a show-widget block in your reply (see widget guidance).",
    parameters = {
        obj(
            "path" to accessStringProp("Workspace-relative file path."),
            "title" to accessStringProp("Optional chooser title."),
            required = listOf("path")
        )
    },
    needsApproval = true,
    allowsAutoApproval = false,
    execute = { input ->
        deps.trackSystemTool("share_file", "分享文件", "apps", input.safePreview()) {
            val path = input.requiredString("path")
            val file = cacheWorkspaceFileForSharing(context, workspaceManager, path)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND)
                .setType(file.extension.takeIf { it.isNotBlank() }?.let { mimeFromExtension(it) } ?: "application/octet-stream")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooser = Intent.createChooser(intent, input.string("title") ?: "Share from AmberAgent")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            textJson {
                put("success", true)
                put("path", path)
                put("size_bytes", file.length())
            }
        }
    }
)

private suspend fun cacheWorkspaceFileForSharing(
    context: Context,
    workspaceManager: WorkspaceManager,
    path: String,
): File {
    val bytes = workspaceManager.readBytes(path)
    val safeName = path.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "shared.bin" }
    val dir = File(context.cacheDir, "agent-share").apply { mkdirs() }
    return File(dir, safeName).apply { writeBytes(bytes) }
}

private fun mimeFromExtension(extension: String): String =
    MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(extension.lowercase())
        ?: "application/octet-stream"
