package me.rerere.rikkahub.ui.components.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView as AndroidWebView
import androidx.compose.ui.graphics.Color
import androidx.core.net.toUri
import me.rerere.rikkahub.data.agent.SandboxActivityUiState
import me.rerere.rikkahub.data.agent.ToolActivityStatus
import me.rerere.rikkahub.data.agent.webview.WebViewLink
import me.rerere.rikkahub.data.agent.webview.WebViewOperationState
import me.rerere.rikkahub.data.agent.webview.WebViewOperationStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

internal fun String.normalizedWebPreviewUrl(): String {
    val raw = trim()
    if (raw.isBlank()) return raw
    return runCatching {
        raw.toUri()
            .buildUpon()
            .fragment(null)
            .build()
            .toString()
            .trimEnd('/')
    }.getOrDefault(raw.trimEnd('/'))
}

internal fun WebViewOperationState.matchesPreview(toolCallId: String, normalizedUrl: String): Boolean {
    if (loadId.isBlank()) return false
    if (toolCallId.isNotBlank() && this.toolCallId == toolCallId) return true
    if (normalizedUrl.isBlank()) return false
    return sequenceOf(requestedUrl, committedUrl, this.url, displayUrl, lastGoodPreviewUrl)
        .filter { it.isNotBlank() }
        .map { it.normalizedWebPreviewUrl() }
        .any { it == normalizedUrl }
}

internal fun WebViewOperationState.bestThumbnailFile(isCurrentPreview: Boolean): File? =
    thumbnailPath.takeIf { isCurrentPreview }?.asValidThumbnailFile()
        ?: lastGoodThumbnailPath.asValidThumbnailFile()

internal fun String.asValidThumbnailFile(): File? =
    takeIf { it.isNotBlank() }
        ?.let { path -> File(path) }
        ?.takeIf { file -> file.exists() && file.length() > 0L }

@Suppress("DEPRECATION")
internal fun WebSettings.disablePreviewDarkening() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        isAlgorithmicDarkeningAllowed = false
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        forceDark = WebSettings.FORCE_DARK_OFF
    }
}

internal fun extractReadablePage(
    webView: AndroidWebView,
    store: WebViewOperationStore,
    loadId: String,
    force: Boolean = false,
) {
    if (!force && !store.shouldExtractReadablePage(loadId, webView.url)) return
    val script = """
        (function() {
          const links = Array.from(document.querySelectorAll('a[href]')).slice(0, 40).map((a) => {
            let href = '';
            try { href = new URL(a.getAttribute('href'), location.href).href; } catch (_) { href = a.href || ''; }
            const title = (a.innerText || a.textContent || href || '').trim().replace(/\s+/g, ' ').slice(0, 160);
            return { title, url: href };
          }).filter((item) => item.url);
          return JSON.stringify({
            title: document.title || '',
            url: location.href,
            text: ((document.body && document.body.innerText) || '').slice(0, 40000),
            links
          });
        })();
    """.trimIndent()
    webView.post {
        webView.evaluateJavascript(script) { raw ->
            runCatching {
                if (raw.isNullOrBlank() || raw == "null") return@runCatching
                val decoded = JSONArray("[$raw]").getString(0)
                val payload = JSONObject(decoded)
                val linksJson = payload.optJSONArray("links")
                val links = buildList {
                    if (linksJson != null) {
                        for (index in 0 until linksJson.length()) {
                            val item = linksJson.optJSONObject(index) ?: continue
                            val linkUrl = item.optString("url").trim()
                            if (linkUrl.isBlank()) continue
                            add(
                                WebViewLink(
                                    title = item.optString("title").ifBlank { linkUrl },
                                    url = linkUrl,
                                )
                            )
                        }
                    }
                }
                store.updateReadablePage(
                    loadId = loadId,
                    url = payload.optString("url").ifBlank { webView.url },
                    title = payload.optString("title"),
                    readableText = payload.optString("text"),
                    links = links,
                )
            }.onFailure {
                Log.w("ChatInput", "Failed to extract WebView readable content", it)
            }
        }
    }
}

internal fun scheduleReadableExtracts(
    webView: AndroidWebView,
    store: WebViewOperationStore,
    loadId: String,
) {
    webView.postDelayed({ extractReadablePage(webView, store, loadId) }, 1_500L)
    webView.postDelayed({ extractReadablePage(webView, store, loadId) }, 3_000L)
}

internal fun scheduleThumbnailCaptures(
    webView: AndroidWebView,
    store: WebViewOperationStore,
    context: android.content.Context,
    loadId: String,
) {
    captureWebViewThumbnail(webView, store, context, loadId, delayMillis = 800L, force = true)
    captureWebViewThumbnail(webView, store, context, loadId, delayMillis = 3_000L, force = true)
}

internal fun captureWebViewThumbnail(
    webView: AndroidWebView,
    store: WebViewOperationStore,
    context: android.content.Context,
    loadId: String,
    delayMillis: Long = 500L,
    force: Boolean = false,
) {
    if (!store.shouldCaptureThumbnail(loadId, webView.url, force = force)) return
    webView.postDelayed({
        runCatching {
            val width = webView.width
            val height = webView.height
            if (width <= 0 || height <= 0) return@runCatching

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            webView.draw(canvas)

            val cropWidth = minOf(bitmap.width, bitmap.height * 4 / 3)
            val cropHeight = minOf(bitmap.height, bitmap.width * 3 / 4)
            val cropX = ((bitmap.width - cropWidth) / 2).coerceAtLeast(0)
            val cropped = Bitmap.createBitmap(bitmap, cropX, 0, cropWidth, cropHeight)
            if (cropped !== bitmap) {
                bitmap.recycle()
            }
            if (cropped.looksBlank()) {
                cropped.recycle()
                return@runCatching
            }

            val dir = File(context.filesDir, "amberagent/artifacts/webview-thumbnails")
            dir.mkdirs()
            val output = File(dir, "webview-${System.currentTimeMillis()}.png")
            output.outputStream().use { stream ->
                cropped.compress(Bitmap.CompressFormat.PNG, 92, stream)
            }
            cropped.recycle()
            store.updateThumbnail(loadId, webView.url, output.absolutePath)
        }.onFailure {
            Log.w("ChatInput", "Failed to capture WebView thumbnail", it)
        }
    }, delayMillis)
}

internal fun Bitmap.looksBlank(): Boolean {
    if (width <= 0 || height <= 0) return true
    val xSamples = 12
    val ySamples = 12
    var opaqueSamples = 0
    var minRed = 255
    var minGreen = 255
    var minBlue = 255
    var maxRed = 0
    var maxGreen = 0
    var maxBlue = 0
    for (yIndex in 0 until ySamples) {
        val y = ((height - 1) * yIndex / (ySamples - 1).coerceAtLeast(1)).coerceIn(0, height - 1)
        for (xIndex in 0 until xSamples) {
            val x = ((width - 1) * xIndex / (xSamples - 1).coerceAtLeast(1)).coerceIn(0, width - 1)
            val pixel = getPixel(x, y)
            if (android.graphics.Color.alpha(pixel) <= 8) continue
            opaqueSamples++
            val red = android.graphics.Color.red(pixel)
            val green = android.graphics.Color.green(pixel)
            val blue = android.graphics.Color.blue(pixel)
            minRed = minOf(minRed, red)
            minGreen = minOf(minGreen, green)
            minBlue = minOf(minBlue, blue)
            maxRed = maxOf(maxRed, red)
            maxGreen = maxOf(maxGreen, green)
            maxBlue = maxOf(maxBlue, blue)
        }
    }
    if (opaqueSamples == 0) return true
    val channelRange = maxOf(maxRed - minRed, maxGreen - minGreen, maxBlue - minBlue)
    return channelRange < 8 && minRed > 245 && minGreen > 245 && minBlue > 245
}

internal fun sandboxStatusLabel(status: ToolActivityStatus): String = when (status) {
    ToolActivityStatus.RUNNING -> "执行中"
    ToolActivityStatus.WAITING_FOR_PERMISSION -> "待授权"
    ToolActivityStatus.SUCCEEDED -> "成功"
    ToolActivityStatus.FAILED -> "失败"
    ToolActivityStatus.CANCELLED -> "已取消"
}

internal fun sandboxStatusContainerColor(status: ToolActivityStatus): Color = when (status) {
    ToolActivityStatus.RUNNING -> Color(0xFF34C96E)
    ToolActivityStatus.WAITING_FOR_PERMISSION -> Color(0xFFFFC44D)
    ToolActivityStatus.SUCCEEDED -> Color(0xFF34C96E)
    ToolActivityStatus.FAILED -> Color(0xFFE45A5A)
    ToolActivityStatus.CANCELLED -> Color(0xFFB8C0CC)
}

internal fun sandboxStatusOnContainerColor(status: ToolActivityStatus): Color = when (status) {
    ToolActivityStatus.WAITING_FOR_PERMISSION,
    ToolActivityStatus.CANCELLED -> Color(0xFF1B1C20)
    else -> Color.White
}

internal fun SandboxActivityUiState.operationPreviewKind(): String = when {
    toolName == "agent_idle" -> "agent"
    toolName == "search_web" -> "web search"
    toolName == "scrape_web" || toolName == "webview_search_open" || toolName == "webview_open" || toolName == "webview_wait_for_load" || toolName == "webview_read" -> "webview"
    toolName.startsWith("icloud_") -> "icloud"
    toolName.startsWith("screen_") || toolName == "vlm_task" -> "screen"
    toolName.startsWith("file_") -> "workspace"
    toolName.startsWith("terminal_") -> "runtime"
    toolName.startsWith("mcp__") -> "mcp"
    else -> toolName
}

internal fun SandboxActivityUiState.operationPreviewText(): String {
    if (toolName == "agent_idle") {
        return "• ${inputPreview.ifBlank { "等待下一次工具调用" }}\n常驻预览已开启"
    }

    val previewUrl = operationPreviewUrl()
    if (previewUrl != null) {
        return buildString {
            append(previewUrl.webHostPreview().compactForSandbox(30))
            append('\n')
            append(inputPreview.ifBlank { title }.compactForSandbox(42))
            append('\n')
            append(sandboxStatusLabel(status))
        }
    }

    val command = inputPreview.ifBlank { title }
    val tail = outputTail.trim()
    return buildString {
        append("• ")
        append(command.compactForSandbox(28))
        append('\n')
        if (tail.isNotBlank()) {
            append(tail.lines().takeLast(3).joinToString("\n").compactForSandbox(96))
        } else {
            append(runtime.ifBlank { sandboxStatusLabel(status) }.compactForSandbox(36))
        }
    }
}

internal fun SandboxActivityUiState.operationPreviewUrl(): String? {
    if (toolName != "search_web" && toolName != "scrape_web" && toolName != "webview_search_open" && toolName != "webview_open" && toolName != "webview_wait_for_load" && toolName != "webview_read") {
        return null
    }

    val directInputUrl = inputPreview.firstHttpUrl()
    if (directInputUrl != null) return directInputUrl

    val outputUrl = outputTail.firstHttpUrl()
    if (outputUrl != null) return outputUrl

    if ((toolName == "search_web" || toolName == "webview_search_open") && inputPreview.isNotBlank()) {
        return "https://www.google.com/search?q=${URLEncoder.encode(inputPreview, "UTF-8")}"
    }

    return null
}

internal fun SandboxActivityUiState.stepProgressText(): String {
    if (toolName == "agent_idle") return "待命"
    val current = stepIndex
    val total = stepTotal
    return if (current != null && total != null) {
        "$current/$total"
    } else {
        sandboxStatusLabel(status)
    }
}

internal fun SandboxActivityUiState.terminalTranscript(): String = buildString {
    append("$ ")
    append(inputPreview.ifBlank { title })
    append('\n')
    if (runtime.isNotBlank()) {
        append("正在调用内嵌 ")
        append(runtime)
        append(" 执行工具")
        append('\n')
    }
    if (workspace.isNotBlank()) {
        append("workspace: ")
        append(workspace)
        append('\n')
    }
    append("status: ")
    append(sandboxStatusLabel(status))
    append('\n')
    if (outputTail.isNotBlank()) {
        append('\n')
        append(outputTail)
    } else if (status == ToolActivityStatus.RUNNING || status == ToolActivityStatus.WAITING_FOR_PERMISSION) {
        append('\n')
        append("等待工具返回输出...")
    }
}

private val HTTP_URL_REGEX = Regex("https?://[^\\s\"'<>),]+")

internal fun String.firstHttpUrl(): String? =
    HTTP_URL_REGEX.find(this)?.value?.trimEnd('.', ',', ';', ')')

internal fun String.webHostPreview(): String =
    runCatching { Uri.parse(this).host?.removePrefix("www.") }.getOrNull() ?: this

internal fun String.compactForSandbox(maxLength: Int): String {
    val compact = trim().replace(Regex("\\s+"), " ")
    return if (compact.length > maxLength) compact.take(maxLength - 1) + "…" else compact
}
