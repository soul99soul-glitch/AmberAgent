package me.rerere.rikkahub.ui.components.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView as AndroidWebView
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.agent.SandboxActivityUiState
import me.rerere.rikkahub.data.agent.ToolActivityStatus
import me.rerere.rikkahub.data.agent.webview.WebViewLink
import me.rerere.rikkahub.data.agent.webview.WebViewLoadStatus
import me.rerere.rikkahub.data.agent.webview.WebViewOperationState
import me.rerere.rikkahub.data.agent.webview.WebViewOperationStore
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import org.json.JSONArray
import org.json.JSONObject
import org.koin.compose.koinInject
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

@Composable
internal fun SandboxPeekBar(
    activity: SandboxActivityUiState,
    onOpen: () -> Unit,
    onCancel: (() -> Unit)?,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AgentOperationPreviewPeek(
            activity = activity,
            onOpen = onOpen,
            modifier = Modifier
                .width(118.dp)
                .height(78.dp),
        )
        SandboxStepPeek(
            activity = activity,
            onOpen = onOpen,
            onCancel = onCancel,
            onPrevious = onPrevious,
            onNext = onNext,
            modifier = Modifier
                .weight(1f)
                .height(38.dp),
        )
    }
}

@Composable
private fun AgentOperationPreviewPeek(
    activity: SandboxActivityUiState,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val previewUrl = activity.operationPreviewUrl()
    val workspace = workspaceColors()
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onOpen() },
        shape = RoundedCornerShape(10.dp),
        color = workspace.paper,
        contentColor = workspace.ink,
        shadowElevation = 1.dp,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        if (previewUrl != null) {
            WebOperationPreviewThumbnail(
                url = previewUrl,
                toolCallId = activity.toolCallId,
                onOpen = onOpen,
            )
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = activity.operationPreviewKind(),
                    color = workspace.amber,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = activity.operationPreviewText(),
                    color = workspace.muted,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun WebOperationPreviewThumbnail(
    url: String,
    toolCallId: String,
    onOpen: () -> Unit,
) {
    val webViewOperationStore: WebViewOperationStore = koinInject()
    val webState by webViewOperationStore.state.collectAsStateWithLifecycle()
    val normalizedUrl = remember(url) { url.normalizedWebPreviewUrl() }
    // Key only on toolCallId + normalizedUrl (stable), not on webState fields (change on every capture)
    val isCurrentPreview = remember(toolCallId, normalizedUrl, webState.loadId) {
        webState.matchesPreview(toolCallId = toolCallId, normalizedUrl = normalizedUrl)
    }
    val thumbnailFile = webState.bestThumbnailFile(isCurrentPreview)
    LaunchedEffect(toolCallId, normalizedUrl, isCurrentPreview) {
        if (!isCurrentPreview) {
            webViewOperationStore.open(url, toolCallId = toolCallId)
        }
    }
    val loadId = webState.loadId.takeIf { isCurrentPreview }
    val workspace = workspaceColors()
    Box(modifier = Modifier.fillMaxSize()) {
        if (!loadId.isNullOrBlank()) {
            HiddenWebOperationRenderer(
                url = url,
                loadId = loadId,
                webViewOperationStore = webViewOperationStore,
                modifier = Modifier
                    .requiredSize(width = 336.dp, height = 252.dp)
                    .graphicsLayer {
                        alpha = 0f
                        scaleX = 0.35f
                        scaleY = 0.35f
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
            )
        }
        if (thumbnailFile != null) {
            AsyncImage(
                model = thumbnailFile,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            WebOperationPreviewPlaceholder(
                url = url,
                webState = webState.takeIf { isCurrentPreview },
                workspace = workspace,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { onOpen() },
        )
    }
}

@Composable
private fun HiddenWebOperationRenderer(
    url: String,
    loadId: String,
    webViewOperationStore: WebViewOperationStore,
    modifier: Modifier = Modifier,
) {
    val state = rememberWebViewState(
        url = url,
        settings = {
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 85
            disablePreviewDarkening()
        },
    )
    DisposableEffect(loadId) {
        webViewOperationStore.markRendererActive(loadId, true)
        onDispose {
            webViewOperationStore.markRendererActive(loadId, false)
        }
    }
    WebView(
        state = state,
        modifier = modifier,
        onCreated = { webView ->
            webView.isFocusable = false
            webView.isFocusableInTouchMode = false
            webView.setOnTouchListener { _, _ -> true }
            webViewOperationStore.markRendererActive(loadId, true)
        },
        onUpdated = { webView ->
            webView.setOnTouchListener { _, _ -> true }
        },
        onProgressChanged = { webView, progress ->
            webViewOperationStore.updateLoading(loadId, webView?.url ?: url, progress)
            if (progress >= 35) {
                webView?.let { extractReadablePage(it, webViewOperationStore, loadId) }
            }
        },
        onPageStarted = { webView, pageUrl ->
            webViewOperationStore.updateLoading(loadId, pageUrl ?: webView?.url ?: url, 1)
            webView?.let { scheduleReadableExtracts(it, webViewOperationStore, loadId) }
        },
        onPageFinished = { webView, pageUrl ->
            val resolvedUrl = pageUrl ?: webView?.url ?: url
            webViewOperationStore.markPageFinished(loadId, resolvedUrl)
            webView?.let {
                extractReadablePage(it, webViewOperationStore, loadId, force = true)
                scheduleThumbnailCaptures(it, webViewOperationStore, context = it.context, loadId = loadId)
            }
        },
        onReceivedError = { webView, pageUrl, error ->
            webViewOperationStore.markFailed(loadId, pageUrl ?: webView?.url ?: url, error.orEmpty())
        },
    )
}

@Composable
private fun WebOperationPreviewPlaceholder(
    url: String,
    webState: WebViewOperationState?,
    workspace: me.rerere.rikkahub.ui.components.ui.WorkspaceColors,
) {
    val title = webState?.title.orEmpty().ifBlank { "网页预览" }
    val detail = when {
        webState?.lastError?.isNotBlank() == true -> webState.lastError
        webState?.isLoading == true -> "正在加载 ${url.webHostPreview()}"
        webState?.status == WebViewLoadStatus.STALLED -> "网页加载较慢"
        else -> url
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(workspace.paper),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = workspace.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            color = workspace.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun SandboxStepPeek(
    activity: SandboxActivityUiState,
    onOpen: () -> Unit,
    onCancel: (() -> Unit)?,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val workspace = workspaceColors()
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onOpen() },
        shape = RoundedCornerShape(10.dp),
        color = workspace.paper,
        contentColor = workspace.ink,
        shadowElevation = 1.dp,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SandboxStepStatusIcon(status = activity.status)
            Text(
                text = activity.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onCancel != null) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(22.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.stop),
                        tint = Color(0xFFE09A1B),
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                SandboxStepArrow(
                    enabled = onPrevious != null,
                    onClick = onPrevious,
                    left = true,
                )
                Text(
                    text = activity.stepProgressText(),
                    style = MaterialTheme.typography.labelMedium,
                    color = workspace.muted,
                    maxLines = 1,
                )
                SandboxStepArrow(
                    enabled = onNext != null,
                    onClick = onNext,
                    left = false,
                )
            }
        }
    }
}

@Composable
private fun SandboxStepArrow(
    enabled: Boolean,
    onClick: (() -> Unit)?,
    left: Boolean,
) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled && onClick != null) { onClick?.invoke() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (left) HugeIcons.ArrowLeft01 else HugeIcons.ArrowRight01,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.92f else 0.28f),
        )
    }
}

@Composable
private fun SandboxStepStatusIcon(status: ToolActivityStatus) {
    Surface(
        modifier = Modifier.size(20.dp),
        shape = CircleShape,
        color = sandboxStatusContainerColor(status),
        contentColor = sandboxStatusOnContainerColor(status),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = when (status) {
                    ToolActivityStatus.SUCCEEDED -> HugeIcons.Tick01
                    ToolActivityStatus.FAILED,
                    ToolActivityStatus.CANCELLED -> HugeIcons.Cancel01
                    else -> HugeIcons.Code
                },
                contentDescription = null,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
internal fun SandboxSheetHeader(
    activity: SandboxActivityUiState,
    isWebPreview: Boolean,
    onCancel: (() -> Unit)?,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SandboxStepStatusIcon(status = activity.status)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = activity.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val supporting = when {
                    isWebPreview -> activity.operationPreviewUrl()?.webHostPreview().orEmpty()
                    activity.runtime.isNotBlank() -> activity.runtime
                    else -> activity.toolName
                }
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (activity.canCancel && onCancel != null) {
                Surface(
                    onClick = onCancel,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Text(
                        text = "中断",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
            SandboxStepArrow(
                enabled = onPrevious != null,
                onClick = onPrevious,
                left = true,
            )
            Text(
                text = activity.stepProgressText(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            SandboxStepArrow(
                enabled = onNext != null,
                onClick = onNext,
                left = false,
            )
        }
    }
}

@Composable
internal fun SandboxToolActivityContent(
    activity: SandboxActivityUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SandboxSheetCodeBlock(
            title = "调用内容",
            language = if (activity.toolName.startsWith("terminal_")) "shell" else "text",
            content = activity.inputPreview.ifBlank { activity.title },
        )
        SandboxSheetCodeBlock(
            title = "调用结果",
            language = "text",
            content = activity.outputTail.ifBlank {
                if (activity.status == ToolActivityStatus.RUNNING || activity.status == ToolActivityStatus.WAITING_FOR_PERMISSION) {
                    "等待工具返回输出..."
                } else {
                    "无输出"
                }
            },
        )
    }
}

@Composable
internal fun SandboxWebActivityContent(
    activity: SandboxActivityUiState,
    url: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Text(
                text = url,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OperationWebPreview(
            url = url,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

@Composable
private fun OperationWebPreview(
    url: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val webViewOperationStore: WebViewOperationStore = koinInject()
    val webState by webViewOperationStore.state.collectAsStateWithLifecycle()
    val normalizedUrl = remember(url) { url.normalizedWebPreviewUrl() }
    // Key only on normalizedUrl + loadId (stable), not on all webState fields
    val isCurrentPreview = remember(normalizedUrl, webState.loadId) {
        webState.matchesPreview(toolCallId = "", normalizedUrl = normalizedUrl)
    }
    LaunchedEffect(normalizedUrl, isCurrentPreview) {
        if (!isCurrentPreview) {
            webViewOperationStore.open(url)
        }
    }
    val loadId = webState.loadId.takeIf { isCurrentPreview }
    DisposableEffect(loadId) {
        if (!loadId.isNullOrBlank()) {
            webViewOperationStore.markRendererActive(loadId, true)
        }
        onDispose {
            if (!loadId.isNullOrBlank()) {
                webViewOperationStore.markRendererActive(loadId, false)
            }
        }
    }
    val thumbnailFile = webState.bestThumbnailFile(isCurrentPreview)
    val state = rememberWebViewState(url = url)
    // derivedStateOf keyed on loadId so status transitions are coalesced within a single load.
    // loadId changes rarely (only on new navigation), so the derivedStateOf instance is stable.
    val statusReady by remember(loadId) {
        derivedStateOf {
            webState.status in setOf(WebViewLoadStatus.INTERACTIVE, WebViewLoadStatus.READY)
        }
    }
    val showLiveWebView = !loadId.isNullOrBlank() && statusReady
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!loadId.isNullOrBlank()) {
                WebView(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    onProgressChanged = { webView, progress ->
                        webViewOperationStore.updateLoading(loadId, webView?.url ?: url, progress)
                        if (progress >= 35) {
                            webView?.let { view -> extractReadablePage(view, webViewOperationStore, loadId) }
                        }
                    },
                    onPageStarted = { webView, pageUrl ->
                        webViewOperationStore.updateLoading(loadId, pageUrl ?: webView?.url ?: url, 1)
                        webView?.let { view -> scheduleReadableExtracts(view, webViewOperationStore, loadId) }
                    },
                    onPageFinished = { webView, pageUrl ->
                        val resolvedUrl = pageUrl ?: webView?.url ?: url
                        webViewOperationStore.markPageFinished(loadId, resolvedUrl)
                        webView?.let { view ->
                            extractReadablePage(view, webViewOperationStore, loadId, force = true)
                            scheduleThumbnailCaptures(view, webViewOperationStore, context, loadId)
                        }
                    },
                    onReceivedError = { webView, pageUrl, error ->
                        webViewOperationStore.markFailed(loadId, pageUrl ?: webView?.url ?: url, error.orEmpty())
                    },
                )
            }
            if (!showLiveWebView) {
                WebOperationPreviewLoadingOverlay(
                    url = url,
                    webState = webState.takeIf { isCurrentPreview },
                    thumbnailFile = thumbnailFile,
                )
            }
        }
    }
}

@Composable
private fun WebOperationPreviewLoadingOverlay(
    url: String,
    webState: WebViewOperationState?,
    thumbnailFile: File?,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (thumbnailFile != null) {
            AsyncImage(
                model = thumbnailFile,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = webState?.title?.takeIf { it.isNotBlank() } ?: "正在加载网页",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when {
                        webState?.lastError?.isNotBlank() == true -> webState.lastError
                        webState?.readableText?.isNotBlank() == true -> webState.readableText.compactForSandbox(120)
                        webState?.status == WebViewLoadStatus.STALLED -> "网页加载较慢，正在保留当前预览"
                        else -> url.webHostPreview()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SandboxSheetCodeBlock(
    title: String,
    language: String,
    content: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = language,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                )
            }
            Text(
                text = content,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
