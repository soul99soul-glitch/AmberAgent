package me.rerere.rikkahub.ui.components.message

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowExpand01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.rikkahub.data.ai.generative.GenerativeWidgetSanitizeStatus
import me.rerere.rikkahub.data.ai.generative.GenerativeWidgetSanitizer
import me.rerere.rikkahub.data.ai.generative.GenerativeWidgetSegment
import me.rerere.rikkahub.data.ai.generative.VChartSpecValidator
import me.rerere.rikkahub.data.datastore.GenerativeUiSetting
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.exportJpegImage
import me.rerere.rikkahub.utils.getActivity
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.toCssHex
import org.json.JSONObject
import java.util.UUID

private const val WIDGET_MIN_HEIGHT_DP = 160
private const val WIDGET_FALLBACK_HEIGHT_DP = 320
// Inline card starts rendering as soon as the partial widget code passes this length.
// Was 40 — too high; users complained that opening the mid-card showed several drawn shapes
// while the inline card was still blank. 16 lets even an "<svg viewBox=\"0 0 X Y\">" stub start
// painting so the inline preview keeps pace with what the model is streaming.
private const val WIDGET_MIN_PARTIAL_RENDER_CHARS = 16
private const val MAX_WIDGET_URL_LENGTH = 2048
// Push interval during streaming. Was 48ms — perceptible lag on fast streams. 16ms ≈ 1 frame.
private const val STREAM_WIDGET_DEBOUNCE_MS = 16L

private val heightCache = object {
    private val map = java.util.LinkedHashMap<String, Int>(16, 0.75f, true)
    private val MAX_ENTRIES = 100

    @Synchronized fun get(key: String): Int? = map[key]
    @Synchronized fun put(key: String, value: Int) {
        map[key] = value
        if (map.size > MAX_ENTRIES) {
            val iter = map.keys.iterator()
            if (iter.hasNext()) { iter.next(); iter.remove() }
        }
    }
}

@Composable
fun GenerativeWidgetCard(
    widget: GenerativeWidgetSegment.Widget,
    modifier: Modifier = Modifier,
    onAction: (String) -> Unit = {},
) {
    val settings = LocalSettings.current.agentRuntime.generativeUi
    val sanitized = remember(widget.widgetCode, settings) {
        GenerativeWidgetSanitizer.sanitize(widget.widgetCode, settings)
    }
    val widgetKey = remember(widget.title, widget.widgetCode.take(120)) {
        listOfNotNull(
            widget.title?.takeIf { it.isNotBlank() },
            widget.widgetCode.toStableWidgetKeyFragment(),
        )
            .joinToString("|")
            .ifBlank { "widget-${widget.widgetCode.hashCode()}" }
    }
    var showExpanded by remember { mutableStateOf(false) }
    val canOpenExpanded = sanitized.status == GenerativeWidgetSanitizeStatus.READY &&
        (widget.complete || sanitized.html.length >= WIDGET_MIN_PARTIAL_RENDER_CHARS)

    if (showExpanded && canOpenExpanded) {
        ExpandedGenerativeWidgetDialog(
            widget = widget,
            html = sanitized.html,
            setting = settings,
            onDismissRequest = { showExpanded = false },
            initialFullscreen = widget.renderer == "slides",
        )
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = canOpenExpanded) { showExpanded = true },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            widget.title?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (sanitized.status) {
                GenerativeWidgetSanitizeStatus.READY -> {
                    if (widget.complete || sanitized.html.length >= WIDGET_MIN_PARTIAL_RENDER_CHARS) {
                        SafeGenerativeWidgetWebView(
                            html = sanitized.html,
                            setting = settings,
                            streaming = !widget.complete,
                            widgetKey = widgetKey,
                            onTap = { showExpanded = true },
                        )
                    } else {
                        GenerativeWidgetLoading()
                    }
                }

                GenerativeWidgetSanitizeStatus.EMPTY -> {
                    Text(
                        text = "可视化为空，已忽略。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                GenerativeWidgetSanitizeStatus.TOO_LARGE -> {
                    Text(
                        text = "可视化过大，已转为代码块。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MarkdownBlock(content = "```html\n${widget.widgetCode.take(4_000)}\n```")
                }

                GenerativeWidgetSanitizeStatus.UNSAFE -> {
                    Text(
                        text = "可视化包含不安全内容，已转为代码块。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    MarkdownBlock(content = "```html\n${widget.widgetCode.take(4_000)}\n```")
                }
            }
            if (settings.enableActions && widget.complete && widget.actions.isNotEmpty()) {
                GenerativeWidgetActions(
                    widget = widget,
                    onAction = onAction,
                )
            }
            if (widget.complete && widget.renderer in setOf("vchart", "slides") && widget.specJson != null && canOpenExpanded) {
                Surface(
                    onClick = { showExpanded = true },
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Text(
                        text = if (widget.renderer == "slides") "▶ 打开演示" else "▶ 交互式图表",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (!widget.complete && sanitized.status == GenerativeWidgetSanitizeStatus.READY) {
                Text(
                    text = "正在生成可视化...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
fun ExpandedGenerativeWidgetDialog(
    widget: GenerativeWidgetSegment.Widget,
    html: String,
    setting: GenerativeUiSetting,
    onDismissRequest: () -> Unit,
    initialFullscreen: Boolean = false,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    // No more user-facing fullscreen toggle: slides always open fullscreen (initialFullscreen=true),
    // SVG/HTML widgets always stay in mid-card. The two layouts had inconsistent backgrounds and
    // button orders anyway — collapsing to one mode per renderer keeps things predictable.
    val isFullscreen = initialFullscreen
    val isRichRenderer = widget.renderer in setOf("vchart", "slides")
    val isSlidesRenderer = widget.renderer == "slides"

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = !isFullscreen,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (isFullscreen) MaterialTheme.colorScheme.surface
                    else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.42f)
                )
                .then(if (isFullscreen) Modifier else Modifier.padding(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = if (isFullscreen) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.9f)
                        .widthIn(max = 1080.dp)
                },
                shape = if (isFullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = if (isFullscreen) 0.dp else 2.dp,
                shadowElevation = if (isFullscreen) 0.dp else 12.dp,
            ) {
                Column(
                    modifier = Modifier.padding(if (isFullscreen) 0.dp else 12.dp),
                    verticalArrangement = Arrangement.spacedBy(if (isFullscreen) 0.dp else 10.dp),
                ) {
                    val headerCompact = isFullscreen && isSlidesRenderer
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = if (headerCompact) 8.dp else if (isFullscreen) 12.dp else 0.dp,
                                vertical = if (headerCompact) 2.dp else if (isFullscreen) 6.dp else 0.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = widget.title?.takeIf { it.isNotBlank() } ?: "可视化卡片",
                            style = if (headerCompact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = {
                                val activity = context.getActivity()
                                val target = webView
                                if (activity == null || target == null) {
                                    toaster.show("暂时无法保存这张卡片", type = ToastType.Error)
                                    return@IconButton
                                }
                                val isSlides = widget.renderer == "slides" &&
                                    widget.specJson != null &&
                                    setting.enableInteractiveCharts
                                if (isSlides) {
                                    scope.launch {
                                        captureSlidesToJpg(
                                            webView = target,
                                            activity = activity,
                                            context = context,
                                            deckTitle = widget.title,
                                            toaster = toaster,
                                        )
                                    }
                                } else {
                                    scope.launch {
                                        toaster.show("正在保存 JPG")
                                        val bitmap = withContext(Dispatchers.Main) {
                                            target.captureWidgetBitmap()
                                        }
                                        if (bitmap == null) {
                                            toaster.show("卡片还没渲染完成", type = ToastType.Error)
                                            return@launch
                                        }
                                        val saved = withContext(Dispatchers.IO) {
                                            context.exportJpegImage(activity, bitmap)
                                        }
                                        bitmap.recycle()
                                        toaster.show(
                                            message = if (saved) "已保存 JPG 到相册" else "保存失败",
                                            type = if (saved) ToastType.Success else ToastType.Error,
                                        )
                                    }
                                }
                            },
                        ) {
                            Icon(HugeIcons.Download01, contentDescription = "保存 JPG")
                        }
                        IconButton(onClick = onDismissRequest) {
                            Icon(HugeIcons.Cancel01, contentDescription = "关闭")
                        }
                    }
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        if (isRichRenderer && widget.specJson != null && setting.enableInteractiveCharts) {
                            RichSandboxWebView(
                                renderer = widget.renderer,
                                specJson = widget.specJson,
                                modifier = Modifier.align(Alignment.TopCenter),
                                maxHeightDp = maxHeight.value.toInt().coerceAtLeast(240),
                                onWebViewReady = { webView = it },
                            )
                        } else {
                            SafeGenerativeWidgetWebView(
                                html = html,
                                setting = setting,
                                streaming = false,
                                modifier = Modifier.fillMaxSize(),
                                widgetKey = "expanded-${widget.title.orEmpty()}-${widget.widgetCode.toStableWidgetKeyFragment()}",
                                minHeightDp = 240,
                                fallbackHeightDp = 520,
                                maxHeightOverrideDp = maxHeight.value.toInt().coerceAtLeast(240),
                                interactive = true,
                                fillContainer = true,
                                onWebViewReady = { webView = it },
                            )
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                removeJavascriptInterface("AmberWidget")
                loadUrl("about:blank")
                destroy()
            }
            webView = null
        }
    }
}

@Composable
private fun GenerativeWidgetActions(
    widget: GenerativeWidgetSegment.Widget,
    onAction: (String) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        widget.actions.take(3).forEach { action ->
            AssistChip(
                onClick = { onAction(action.toUserPrompt(widget.title)) },
                label = {
                    Text(
                        text = action.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
fun GenerativeWidgetLoading(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Text(
            text = "正在生成可视化...",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 18.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SafeGenerativeWidgetWebView(
    html: String,
    setting: GenerativeUiSetting,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    widgetKey: String? = null,
    minHeightDp: Int = WIDGET_MIN_HEIGHT_DP,
    fallbackHeightDp: Int = WIDGET_FALLBACK_HEIGHT_DP,
    maxHeightOverrideDp: Int? = null,
    interactive: Boolean = false,
    fillContainer: Boolean = false,
    onTap: (() -> Unit)? = null,
    onWebViewReady: (WebView?) -> Unit = {},
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    var renderedHtml by remember {
        mutableStateOf(if (streaming) html.toStableStreamingWidgetHtml().orEmpty() else html)
    }
    LaunchedEffect(html, streaming) {
        val nextHtml = if (streaming) html.toStableStreamingWidgetHtml() else html
        if (nextHtml.isNullOrBlank()) return@LaunchedEffect
        if (streaming) delay(STREAM_WIDGET_DEBOUNCE_MS)
        renderedHtml = nextHtml
    }
    val latestHtml by rememberUpdatedState(renderedHtml)
    val maxHeight = (maxHeightOverrideDp ?: setting.maxWidgetHeightDp)
        .coerceIn(minHeightDp, 1600)
    val cacheKey = remember(widgetKey) {
        widgetKey?.takeIf { it.isNotBlank() } ?: html.take(200).ifBlank { UUID.randomUUID().toString() }
    }
    // Do not key on cacheKey — see DisposableEffect comment below. Resetting this to null
    // mid-stream creates a brief window where LaunchedEffect skips the push because
    // activeWebView is momentarily null.
    var activeWebView by remember { mutableStateOf<WebView?>(null) }
    val bridgeToken = remember { UUID.randomUUID().toString() }
    // Same reasoning as activeWebView above: cacheKey changes every chunk in early streaming
    // (because widgetCode.take(120) keeps growing), and resetting heightDp to minHeightDp
    // every time would clobber the height that ResizeObserver just reported, leaving the
    // inline card stuck at 160dp until streaming finishes — which is exactly the symptom.
    // The composable identity already changes when a real new widget mounts, so plain
    // remember{} is enough. heightCache.get(cacheKey) is still consulted on first composition
    // for warm-start sizing.
    var heightDp by remember {
        mutableStateOf(
            heightCache.get(cacheKey)
                ?: if (streaming) minHeightDp else fallbackHeightDp
        )
    }
    var hasMeasuredHeight by remember {
        mutableStateOf(heightCache.get(cacheKey) != null)
    }
    val animatedHeight by animateDpAsState(
        targetValue = heightDp.coerceIn(minHeightDp, maxHeight).dp,
        animationSpec = if (hasMeasuredHeight) tween(durationMillis = 180) else snap(),
        label = "generative-widget-height",
    )
    val receiverHtml = remember(
        bridgeToken,
        colorScheme.background,
        colorScheme.onSurface,
        colorScheme.surface,
        colorScheme.outlineVariant,
        colorScheme.primary,
        interactive,
        fillContainer,
    ) {
        buildReceiverHtml(
            bridgeToken = bridgeToken,
            background = "transparent",
            foreground = colorScheme.onSurface.toCssHex(),
            surface = colorScheme.surface.toCssHex(),
            outline = colorScheme.outlineVariant.toCssHex(),
            primary = colorScheme.primary.toCssHex(),
            interactive = interactive,
            fillContainer = fillContainer,
        )
    }
    LaunchedEffect(renderedHtml, streaming, activeWebView) {
        val target = activeWebView ?: return@LaunchedEffect
        val next = renderedHtml.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        target.pushWidgetHtml(next, finalize = !streaming)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (fillContainer) Modifier.fillMaxSize() else Modifier.height(animatedHeight))
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setSupportZoom(interactive)
                    settings.builtInZoomControls = interactive
                    settings.displayZoomControls = false
                    settings.useWideViewPort = interactive
                    settings.loadWithOverviewMode = interactive
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.blockNetworkLoads = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setSupportMultipleWindows(false)
                    addJavascriptInterface(
                        WidgetBridge(
                            onResize = { nextHeight ->
                                val coerced = nextHeight.coerceIn(minHeightDp, maxHeight)
                                hasMeasuredHeight = true
                                heightDp = coerced
                                heightCache.put(cacheKey, coerced)
                            },
                            onOpenUrl = { url ->
                                if (isSafeExternalWidgetUrl(url)) {
                                    context.openUrl(url)
                                }
                            },
                            onTap = { onTap?.invoke() },
                            bridgeToken = bridgeToken,
                        ),
                        "AmberWidget",
                    )
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            request?.url?.toString()?.let { url ->
                                if (isSafeExternalWidgetUrl(url)) {
                                    context.openUrl(url)
                                }
                            }
                            return true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.pushWidgetHtml(latestHtml, finalize = !streaming)
                        }
                    }
                    loadDataWithBaseURL(
                        "https://amberagent.widget.local/",
                        receiverHtml,
                        "text/html",
                        "utf-8",
                        null,
                    )
                    activeWebView = this
                    onWebViewReady(this)
                }
            },
            update = { webView ->
                activeWebView = webView
                onWebViewReady(webView)
                webView.post {
                    webView.pushWidgetHtml(latestHtml, finalize = !streaming)
                }
            },
        )
    }

    // CRITICAL: do NOT key this effect on cacheKey. cacheKey changes every time the widget's
    // streaming widgetCode passes the take(120) prefix boundary or accumulates more lines —
    // i.e. constantly during the first second of streaming. If we key on cacheKey, every change
    // calls onDispose → webView.destroy() → the chromium sandbox process gets killed
    // (logcat shows "isolated not needed" + "Death received" within 3s of stream start).
    // AndroidView keeps the same WebView instance across recompositions, so the destroyed
    // WebView stays in the layout tree but is silently dead — every subsequent
    // evaluateJavascript call no-ops, ResizeObserver never fires, the inline card stays blank.
    // The mid-card seems to work because user opens it after streaming is mostly done, so
    // its cacheKey is stable from the start.
    DisposableEffect(Unit) {
        onDispose {
            activeWebView?.apply {
                stopLoading()
                removeJavascriptInterface("AmberWidget")
                loadUrl("about:blank")
                destroy()
            }
            activeWebView = null
            onWebViewReady(null)
        }
    }
}

private class WidgetBridge(
    private val onResize: (Int) -> Unit,
    private val onOpenUrl: (String) -> Unit,
    private val onTap: () -> Unit,
    private val bridgeToken: String,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun resize(height: Int) {
        main.post { onResize(height) }
    }

    @JavascriptInterface
    fun openUrl(token: String, url: String) {
        if (token != bridgeToken) return
        main.post { onOpenUrl(url) }
    }

    @JavascriptInterface
    fun tap(token: String) {
        if (token != bridgeToken) return
        main.post { onTap() }
    }
}

private suspend fun captureSlidesToJpg(
    webView: WebView,
    activity: android.app.Activity,
    context: android.content.Context,
    deckTitle: String?,
    toaster: com.dokar.sonner.ToasterState,
) {
    toaster.show("正在保存幻灯片…")
    val count = evaluateJsAwait(webView, "window.__getSlideCount && window.__getSlideCount()")
        ?.toIntOrNull() ?: 0
    if (count <= 0) {
        toaster.show("幻灯片还没渲染完成", type = ToastType.Error)
        return
    }
    val originalSlide = evaluateJsAwait(webView, "window.__getCurrentSlide && window.__getCurrentSlide()")
        ?.toIntOrNull() ?: 0
    withContext(Dispatchers.Main) {
        webView.evaluateJavascript("window.__beginCapture && window.__beginCapture();", null)
    }
    val baseTime = System.currentTimeMillis()
    val safeTitle = (deckTitle?.takeIf { it.isNotBlank() } ?: "deck")
        .replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
        .take(40)
        .ifBlank { "deck" }
    var saved = 0
    try {
        for (i in 0 until count) {
            withContext(Dispatchers.Main) {
                webView.evaluateJavascript(
                    "window.__jumpToSlideInstant && window.__jumpToSlideInstant($i);",
                    null,
                )
            }
            delay(160)
            val bitmap = withContext(Dispatchers.Main) { webView.captureWidgetBitmap() } ?: continue
            val ok = withContext(Dispatchers.IO) {
                context.exportJpegImage(
                    activity = activity,
                    bitmap = bitmap,
                    fileName = "AmberAgent_${safeTitle}_${baseTime}_p${i + 1}.jpg",
                )
            }
            bitmap.recycle()
            if (ok) saved++
        }
    } finally {
        withContext(Dispatchers.Main) {
            webView.evaluateJavascript("window.__endCapture && window.__endCapture();", null)
            webView.evaluateJavascript(
                "window.__jumpToSlideInstant && window.__jumpToSlideInstant($originalSlide);",
                null,
            )
        }
    }
    when {
        saved == count -> toaster.show("已保存 $count 页到相册", type = ToastType.Success)
        saved > 0 -> toaster.show("仅保存 $saved/$count 页", type = ToastType.Warning)
        else -> toaster.show("保存失败", type = ToastType.Error)
    }
}

private suspend fun evaluateJsAwait(webView: WebView, js: String): String? {
    val deferred = CompletableDeferred<String?>()
    withContext(Dispatchers.Main) {
        webView.evaluateJavascript(js) { raw ->
            val cleaned = raw
                ?.takeIf { it.isNotBlank() && it != "null" && it != "undefined" }
                ?.removeSurrounding("\"")
            deferred.complete(cleaned)
        }
    }
    return deferred.await()
}

private fun WebView.captureWidgetBitmap(): Bitmap? {
    val width = width.takeIf { it > 0 } ?: return null
    val height = height.takeIf { it > 0 } ?: return null
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        draw(canvas)
    }
}

private fun isSafeExternalWidgetUrl(url: String): Boolean {
    if (url.length !in 1..MAX_WIDGET_URL_LENGTH) return false
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    return uri.scheme?.lowercase() in setOf("http", "https")
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun RichSandboxWebView(
    renderer: String,
    specJson: String,
    modifier: Modifier = Modifier,
    maxHeightDp: Int = 720,
    onWebViewReady: (WebView?) -> Unit = {},
) {
    val validated = remember(specJson, renderer) {
        when (renderer) {
            "vchart" -> VChartSpecValidator.validateChartSpec(specJson)
            "slides" -> VChartSpecValidator.validateSlidesSpec(specJson)
            else -> VChartSpecValidator.ValidationResult(false, "unknown renderer")
        }
    }
    // Start at a reasonable initial height; JS resize will adjust to exact content.
    // Capped to avoid jarring shrink animation when maxHeightDp is very large (tablet fullscreen).
    var heightDp by remember(maxHeightDp) { mutableStateOf(maxHeightDp) }
    // Strip U+2028/U+2029 (line/paragraph separators) — JSON allows them,
    // but evaluateJavascript treats them as line terminators and silently fails.
    val safeJson = specJson.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    val animatedHeight by animateDpAsState(
        targetValue = heightDp.coerceIn(240, maxHeightDp).dp,
        animationSpec = tween(durationMillis = 200),
        label = "rich-widget-height",
    )

    if (!validated.valid) {
        Text(
            text = "交互式图表数据校验失败: ${validated.reason.orEmpty()}",
            modifier = modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }

    val assetFile = when (renderer) {
        "vchart" -> "generative-libs/vchart-sandbox.html"
        "slides" -> "generative-libs/slides-sandbox.html"
        else -> return
    }
    val renderFunction = when (renderer) {
        "vchart" -> "__renderChart"
        "slides" -> "__renderSlides"
        else -> return
    }

    var activeWebView by remember { mutableStateOf<WebView?>(null) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (renderer == "slides") Modifier.fillMaxSize() else Modifier.height(animatedHeight))
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    overScrollMode = android.view.View.OVER_SCROLL_NEVER
                    isHorizontalScrollBarEnabled = false
                    isVerticalScrollBarEnabled = renderer != "slides"
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setSupportZoom(renderer != "slides")
                    settings.builtInZoomControls = renderer != "slides"
                    settings.displayZoomControls = false
                    settings.useWideViewPort = renderer != "slides"
                    settings.loadWithOverviewMode = renderer != "slides"
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.blockNetworkLoads = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setSupportMultipleWindows(false)
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun resize(height: Int) {
                                Handler(Looper.getMainLooper()).post {
                                    heightDp = height.coerceIn(240, maxHeightDp)
                                }
                            }
                        },
                        "AmberWidget",
                    )
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean = true

                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.evaluateJavascript(
                                "window.$renderFunction(${JSONObject.quote(safeJson)});",
                                null,
                            )
                        }
                    }
                    loadUrl("file:///android_asset/$assetFile")
                    activeWebView = this
                    onWebViewReady(this)
                }
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            activeWebView?.apply {
                stopLoading()
                removeJavascriptInterface("AmberWidget")
                loadUrl("about:blank")
                destroy()
            }
            activeWebView = null
            onWebViewReady(null)
        }
    }
}

private fun String.toStableStreamingWidgetHtml(): String? {
    var stable = trim()
    if (stable.length < WIDGET_MIN_PARTIAL_RENDER_CHARS) return null

    val scriptStart = stable.lastIndexOf("<script", ignoreCase = true)
    if (scriptStart >= 0 && stable.indexOf("</script", startIndex = scriptStart, ignoreCase = true) < 0) {
        stable = stable.substring(0, scriptStart).trim()
    }

    val styleStart = stable.lastIndexOf("<style", ignoreCase = true)
    if (styleStart >= 0 && stable.indexOf("</style", startIndex = styleStart, ignoreCase = true) < 0) {
        stable = stable.substring(0, styleStart).trim()
    }

    val lastOpenTag = stable.lastIndexOf('<')
    val lastCloseTag = stable.lastIndexOf('>')
    if (lastOpenTag > lastCloseTag) {
        stable = stable.substring(0, lastOpenTag).trim()
    }

    return stable
        .closeStreamingSvgIfNeeded()
        .takeIf { it.length >= WIDGET_MIN_PARTIAL_RENDER_CHARS }
}

private fun String.closeStreamingSvgIfNeeded(): String {
    val compact = trim()
    if (!compact.startsWith("<svg", ignoreCase = true) || compact.contains("</svg", ignoreCase = true)) {
        return compact
    }
    return "$compact</svg>"
}

private fun String.toStableWidgetKeyFragment(): String {
    val lines = trim()
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .take(6)
        .joinToString("")
        .take(220)
    return lines.ifBlank { "hash-${hashCode()}" }
}

private fun WebView.pushWidgetHtml(html: String, finalize: Boolean) {
    val fn = if (finalize) "__amberWidgetFinalizeHtml" else "__amberWidgetSetHtml"
    evaluateJavascript("window.$fn(${JSONObject.quote(html)});", null)
}

private fun buildReceiverHtml(
    bridgeToken: String,
    background: String,
    foreground: String,
    surface: String,
    outline: String,
    primary: String,
    interactive: Boolean,
    fillContainer: Boolean = false,
): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1${if (interactive) ",minimum-scale=0.5,maximum-scale=5,user-scalable=yes" else ""}">
<script>
// Defined eagerly in <head> so any host evaluateJavascript() that fires before the body
// script runs gets queued instead of crashing with "is not a function".
window.__amberWidgetPending = null;
window.__amberWidgetReady = false;
window.__amberWidgetSetHtml = function(html){
  if (window.__amberWidgetReady && window.__amberWidgetSetHtmlReal) {
    window.__amberWidgetSetHtmlReal(html);
  } else {
    window.__amberWidgetPending = { html: html, finalize: false };
  }
};
window.__amberWidgetFinalizeHtml = function(html){
  if (window.__amberWidgetReady && window.__amberWidgetFinalizeHtmlReal) {
    window.__amberWidgetFinalizeHtmlReal(html);
  } else {
    window.__amberWidgetPending = { html: html, finalize: true };
  }
};
</script>
<style>
html,body{margin:0;padding:0;background:$background;color:$foreground;font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;${if (fillContainer) "height:100%;" else ""}}
*{box-sizing:border-box;max-width:100%;}
body{overflow:${if (interactive) "auto" else "hidden"};${if (fillContainer) "-webkit-overflow-scrolling:touch;" else ""}}
#root{width:100%;${if (fillContainer) "min-height:100%;" else "min-height:1px;"}overflow:${if (interactive) "auto" else "hidden"};}
#root>*{max-width:100%!important;}
svg{display:block;width:100%!important;max-width:100%;height:auto;overflow:hidden;}
table{width:100%;border-collapse:collapse;}
td,th{border:1px solid $outline;padding:6px 8px;}
button,input,select{font:inherit;border:1px solid $outline;border-radius:8px;background:$surface;color:$foreground;padding:6px 10px;}
a{color:$primary;text-decoration:none;}
</style>
</head>
<body>
<div id="root"></div>
<script>
(function(){
  var root=document.getElementById('root');
  var timer=null;
  function report(){
    if(timer) clearTimeout(timer);
    timer=setTimeout(function(){
      var rect=root.getBoundingClientRect();
      var height=Math.ceil(Math.max(rect.height, root.scrollHeight, document.body.scrollHeight, 1));
      AmberWidget.resize(height);
    }, 16);
  }
  function clampSvgOverflow(){
    // 1) Force every SVG to clip to its viewBox.
    // 2) Force SVG to have a real, responsive height. Without an explicit height attribute,
    //    Chromium's CSS "height: auto" on SVG falls back to the replaced-element default of
    //    150px — which compresses the entire viewBox into a tiny stripe. The inline card
    //    (height-by-content) ends up only 150px tall while the mid-card (fillMaxSize) accidentally
    //    looks correct because the parent already enforced a height. Computing aspect-ratio from
    //    viewBox makes the SVG height responsive in BOTH layouts.
    var svgs = root.getElementsByTagName('svg');
    for (var i = 0; i < svgs.length; i++) {
      var s = svgs[i];
      s.setAttribute('overflow', 'hidden');
      s.style.overflow = 'hidden';
      var vb = s.getAttribute('viewBox');
      if (vb) {
        var parts = vb.split(/[\s,]+/);
        if (parts.length === 4) {
          var w = parseFloat(parts[2]);
          var h = parseFloat(parts[3]);
          if (w > 0 && h > 0) {
            s.style.aspectRatio = w + ' / ' + h;
            if (!s.getAttribute('width')) s.style.width = '100%';
            s.style.height = 'auto';
          }
        }
      }
    }
  }
  window.__amberWidgetSetHtmlReal = function(html){
    if(root.innerHTML!==html){ root.innerHTML=html; clampSvgOverflow(); }
    report();
  };
  window.__amberWidgetFinalizeHtmlReal = function(html){
    if(root.innerHTML!==html){ root.innerHTML=html; clampSvgOverflow(); }
    setTimeout(report, 32);
  };
  // Drain any push that landed before this script ran.
  window.__amberWidgetReady = true;
  if (window.__amberWidgetPending) {
    var p = window.__amberWidgetPending;
    window.__amberWidgetPending = null;
    if (p.finalize) window.__amberWidgetFinalizeHtmlReal(p.html);
    else window.__amberWidgetSetHtmlReal(p.html);
  }
  new ResizeObserver(report).observe(root);
  document.addEventListener('click', function(event){
    if(!event.isTrusted) return;
    var target=event.target;
    var anchor=target && target.closest ? target.closest('a[href]') : null;
    if(anchor){
      var href=anchor.getAttribute('href') || '';
      if(href.indexOf('http://')===0 || href.indexOf('https://')===0){
        event.preventDefault();
        AmberWidget.openUrl('$bridgeToken', href);
      }
      return;
    }
    if(event.defaultPrevented) return;
    ${if (!interactive) """try{
      event.preventDefault();
      AmberWidget.tap('$bridgeToken');
    }catch(e){
    }""" else ""}
  });
  report();
})();
</script>
</body>
</html>
""".trimIndent()
