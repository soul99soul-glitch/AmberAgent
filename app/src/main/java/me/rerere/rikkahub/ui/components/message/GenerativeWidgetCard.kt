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
private const val WIDGET_MIN_PARTIAL_RENDER_CHARS = 40
private const val MAX_WIDGET_URL_LENGTH = 2048
private const val STREAM_WIDGET_DEBOUNCE_MS = 48L

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
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var isFullscreen by remember { mutableStateOf(false) }
    val isRichRenderer = widget.renderer in setOf("vchart", "slides")

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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = if (isFullscreen) 12.dp else 0.dp, vertical = if (isFullscreen) 6.dp else 0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = widget.title?.takeIf { it.isNotBlank() } ?: "可视化卡片",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = { isFullscreen = !isFullscreen },
                        ) {
                            Icon(
                                if (isFullscreen) HugeIcons.Cancel01 else HugeIcons.ArrowExpand01,
                                contentDescription = if (isFullscreen) "退出全屏" else "全屏",
                            )
                        }
                        IconButton(
                            onClick = {
                                val activity = context.getActivity()
                                val target = webView
                                if (activity == null || target == null) {
                                    toaster.show("暂时无法保存这张卡片", type = ToastType.Error)
                                    return@IconButton
                                }
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
                            },
                        ) {
                            Icon(HugeIcons.Download01, contentDescription = "保存 JPG")
                        }
                        if (!isFullscreen) {
                            IconButton(onClick = onDismissRequest) {
                                Icon(HugeIcons.Cancel01, contentDescription = "关闭")
                            }
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
                                modifier = Modifier.align(Alignment.TopCenter),
                                widgetKey = "expanded-${widget.title.orEmpty()}-${widget.widgetCode.toStableWidgetKeyFragment()}",
                                minHeightDp = 240,
                                fallbackHeightDp = 520,
                                maxHeightOverrideDp = maxHeight.value.toInt().coerceAtLeast(240),
                                interactive = true,
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
    var activeWebView by remember(cacheKey) { mutableStateOf<WebView?>(null) }
    val bridgeToken = remember { UUID.randomUUID().toString() }
    var heightDp by remember(cacheKey) {
        mutableStateOf(
            heightCache.get(cacheKey)
                ?: if (streaming) minHeightDp else fallbackHeightDp
        )
    }
    var hasMeasuredHeight by remember(cacheKey) {
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
    ) {
        buildReceiverHtml(
            bridgeToken = bridgeToken,
            background = "transparent",
            foreground = colorScheme.onSurface.toCssHex(),
            surface = colorScheme.surface.toCssHex(),
            outline = colorScheme.outlineVariant.toCssHex(),
            primary = colorScheme.primary.toCssHex(),
            interactive = interactive,
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
            .height(animatedHeight)
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

    DisposableEffect(cacheKey) {
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
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setSupportZoom(renderer != "slides")
                    settings.builtInZoomControls = renderer != "slides"
                    settings.displayZoomControls = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
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
): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1${if (interactive) ",minimum-scale=0.5,maximum-scale=5,user-scalable=yes" else ""}">
<style>
html,body{margin:0;padding:0;background:$background;color:$foreground;font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;}
*{box-sizing:border-box;max-width:100%;}
body{overflow:${if (interactive) "auto" else "hidden"};}
#root{width:100%;min-height:1px;overflow:${if (interactive) "auto" else "hidden"};}
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
  window.__amberWidgetSetHtml=function(html){
    if(root.innerHTML!==html){ root.innerHTML=html; }
    report();
  };
  window.__amberWidgetFinalizeHtml=function(html){
    if(root.innerHTML!==html){ root.innerHTML=html; }
    setTimeout(report, 32);
  };
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
