package app.amber.feature.ui.components.message

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToasterState
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowExpand01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.FileZip
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.Play
import app.amber.core.ai.generative.GuizangHtmlDeckValidator
import app.amber.core.ai.generative.GenerativeWidgetSanitizeStatus
import app.amber.core.ai.generative.GenerativeWidgetSanitizer
import app.amber.core.ai.generative.GenerativeWidgetSegment
import app.amber.core.ai.generative.VChartSpecValidator
import app.amber.core.settings.GenerativeUiSetting
import app.amber.core.font.SlidesFontRepository
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.context.LocalToaster
import app.amber.core.utils.exportJpegImage
import app.amber.core.utils.getActivity
import app.amber.core.utils.openUrl
import app.amber.core.utils.toCssHex
import org.json.JSONObject
import org.koin.compose.koinInject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    // T-C perf-layer dispatch — see PerfFlags + docs/visual-sanity-check.md.
    if (app.amber.agent.PerfFlags.USE_SPLIT_GENERATIVE_WIDGET_CARD) {
        GenerativeWidgetCardSplit(widget = widget, modifier = modifier, onAction = onAction)
        return
    }

    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val settings = LocalSettings.current.agentRuntime.generativeUi
    val fontRepository = koinInject<SlidesFontRepository>()
    val fontStates by fontRepository.fontsFlow.collectAsStateWithLifecycle()
    val sanitized = remember(widget.widgetCode, settings) {
        GenerativeWidgetSanitizer.sanitize(widget.widgetCode, settings)
    }
    val slidesFontHint = remember(widget.renderer, widget.specJson, settings, fontStates) {
        widget.specJson
            ?.takeIf { widget.renderer == "slides" }
            ?.let { fontRepository.resolveSlidesFontHint(it, settings) }
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
            initialFullscreen = widget.renderer == "slides" || widget.renderer == GuizangHtmlDeckValidator.RENDERER,
        )
    }

    // V3: 画板风 widget 卡片. 之前用 colorScheme.surface + outlineVariant 1dp 在 Paper/Midnight
    // 主题下会显示成"硬黑框 + 浅底", 跟设计稿不符. 改为 chatTheme.widgetCanvas (比 bg 略深,
    // 暗色略浅), 默认无描边; 若主题显式给了 widgetCanvasBorder 才画 1dp 描边.
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val widgetSurfaceColor = chatTheme.widgetCanvas.takeIf { it.isSpecified } ?: MaterialTheme.colorScheme.surface
    val widgetBorder = chatTheme.widgetCanvasBorder
        .takeIf { it.alpha > 0f }
        ?.let { BorderStroke(1.dp, it) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = canOpenExpanded) { showExpanded = true },
        shape = RoundedCornerShape(10.dp),
        color = widgetSurfaceColor,
        contentColor = chatTheme.ink,
        border = widgetBorder,
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
                    color = chatTheme.inkSoft,
                )
            }
            slidesFontHint?.let { hint ->
                Surface(
                    onClick = {
                        toaster.show(
                            "当前预览使用系统字体，可在 Slides 字体资源中下载 ${
                                hint.pack?.displayName ?: hint.requestedPackId
                            }",
                        )
                    },
                    shape = RoundedCornerShape(999.dp),
                    color = chatTheme.accentSoft,
                    contentColor = chatTheme.accent,
                ) {
                    Text(
                        text = "使用系统字体 · 下载字体",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
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
            if (widget.complete && widget.renderer in setOf("vchart", "slides", GuizangHtmlDeckValidator.RENDERER) &&
                widget.specJson != null && canOpenExpanded
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        onClick = { showExpanded = true },
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Text(
                            text = when (widget.renderer) {
                                "slides" -> "▶ 打开演示"
                                GuizangHtmlDeckValidator.RENDERER -> "▶ 打开 Live 演示"
                                else -> "▶ 交互式图表"
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    if (widget.renderer == "slides" || widget.renderer == GuizangHtmlDeckValidator.RENDERER) {
                        Surface(
                            onClick = {
                                scope.launch {
                                    if (widget.renderer == GuizangHtmlDeckValidator.RENDERER) {
                                        shareGuizangDeckArchive(
                                            context = context,
                                            title = widget.title,
                                            specJson = widget.specJson,
                                            coverHtml = sanitized.html,
                                            toaster = toaster,
                                        )
                                    } else {
                                        shareSlidesDeckArchive(
                                            context = context,
                                            title = widget.title,
                                            specJson = widget.specJson,
                                            coverHtml = sanitized.html,
                                            toaster = toaster,
                                        )
                                    }
                                }
                            },
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Text(
                                text = "分享 ZIP",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
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
    val isGuizangRenderer = widget.renderer == GuizangHtmlDeckValidator.RENDERER
    val isRichRenderer = widget.renderer in setOf("vchart", "slides", GuizangHtmlDeckValidator.RENDERER)
    val isSlidesRenderer = widget.renderer == "slides"
    val isPresentationRenderer = isSlidesRenderer || isGuizangRenderer

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = !isFullscreen,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        if (isFullscreen) {
            FullscreenDialogWindowEffect()
        }
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
                    val headerCompact = isFullscreen && isPresentationRenderer
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
                        if (widget.specJson != null && isPresentationRenderer) {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        if (isGuizangRenderer) {
                                            shareGuizangDeckArchive(
                                                context = context,
                                                title = widget.title,
                                                specJson = widget.specJson,
                                                coverHtml = html,
                                                toaster = toaster,
                                            )
                                        } else {
                                            shareSlidesDeckArchive(
                                                context = context,
                                                title = widget.title,
                                                specJson = widget.specJson,
                                                coverHtml = html,
                                                toaster = toaster,
                                            )
                                        }
                                    }
                                },
                            ) {
                                Icon(HugeIcons.FileZip, contentDescription = "分享 ZIP")
                            }
                        }
                        IconButton(
                            onClick = {
                                val activity = context.getActivity()
                                val target = webView
                                if (activity == null || target == null) {
                                    toaster.show("暂时无法保存这张卡片", type = ToastType.Error)
                                    return@IconButton
                                }
                                val isPresentation = (widget.renderer == "slides" ||
                                    widget.renderer == GuizangHtmlDeckValidator.RENDERER) &&
                                    widget.specJson != null &&
                                    setting.enableInteractiveCharts
                                if (isPresentation) {
                                    scope.launch {
                                        captureSlidesToJpg(
                                            webView = target,
                                            activity = activity,
                                            context = context,
                                            deckTitle = widget.title,
                                            toaster = toaster,
                                            settleDelayMs = if (isGuizangRenderer) 900L else 160L,
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
                            if (isGuizangRenderer) {
                                GuizangHtmlDeckWebView(
                                    specJson = widget.specJson,
                                    modifier = Modifier.fillMaxSize(),
                                    onWebViewReady = { webView = it },
                                )
                            } else {
                                RichSandboxWebView(
                                    renderer = widget.renderer,
                                    specJson = widget.specJson,
                                    setting = setting,
                                    modifier = if (widget.renderer == "slides") Modifier.fillMaxSize() else Modifier.align(Alignment.TopCenter),
                                    maxHeightDp = maxHeight.value.toInt().coerceAtLeast(240),
                                    onWebViewReady = { webView = it },
                                )
                            }
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
private fun FullscreenDialogWindowEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window == null) {
            onDispose { }
        } else {
            val previousStatusBarColor = window.statusBarColor
            val previousNavigationBarColor = window.navigationBarColor
            val previousSoftInputMode = window.attributes.softInputMode
            val previousCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode
            } else {
                null
            }
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
            onDispose {
                WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(window, true)
                window.statusBarColor = previousStatusBarColor
                window.navigationBarColor = previousNavigationBarColor
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && previousCutoutMode != null) {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = previousCutoutMode
                        softInputMode = previousSoftInputMode
                    }
                } else {
                    window.attributes = window.attributes.apply {
                        softInputMode = previousSoftInputMode
                    }
                }
            }
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
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = chatTheme.widgetCanvas.takeIf { it.isSpecified } ?: chatTheme.surface,
        contentColor = chatTheme.inkSoft,
        border = BorderStroke(1.dp, chatTheme.surfaceEdge),
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
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
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
        chatTheme.ink,
        chatTheme.surface,
        chatTheme.surfaceEdge,
        chatTheme.accent,
        interactive,
        fillContainer,
    ) {
        buildReceiverHtml(
            bridgeToken = bridgeToken,
            background = "transparent",
            foreground = chatTheme.ink.toCssHex(),
            surface = chatTheme.surface.toCssHex(),
            outline = chatTheme.surfaceEdge.toCssHex(),
            primary = chatTheme.accent.toCssHex(),
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

private suspend fun shareGuizangDeckArchive(
    context: Context,
    title: String?,
    specJson: String,
    coverHtml: String,
    toaster: ToasterState,
) {
    toaster.show("正在打包全功能 HTML…")
    val archive = runCatching {
        withContext(Dispatchers.IO) {
            createGuizangDeckArchive(context, title, specJson, coverHtml)
        }
    }.getOrElse { error ->
        toaster.show(error.message ?: "打包失败", type = ToastType.Error)
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archive)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, archive.name)
            putExtra(Intent.EXTRA_SUBJECT, title?.takeIf { it.isNotBlank() } ?: "Full HTML Deck")
            clipData = ClipData.newUri(context.contentResolver, archive.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "分享全功能 HTML ZIP")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }.onSuccess {
        toaster.show("已打开分享菜单", type = ToastType.Success)
    }.onFailure { error ->
        toaster.show(error.message ?: "无法打开分享菜单", type = ToastType.Error)
    }
}

private fun createGuizangDeckArchive(
    context: Context,
    title: String?,
    specJson: String,
    coverHtml: String,
): File {
    val now = System.currentTimeMillis()
    val safeTitle = safeArchiveName(title)
    val dir = File(context.cacheDir, "full-html-shares").apply { mkdirs() }
    dir.listFiles()
        ?.filter { it.isFile && now - it.lastModified() > 2 * 24 * 60 * 60 * 1000L }
        ?.forEach { it.delete() }
    val archive = File(dir, "AmberAgent_${safeTitle}_full_html_$now.zip")
    val deck = GuizangHtmlDeckValidator.normalizeSpecJson(specJson)
        ?: error("全功能 HTML 数据格式不正确，无法分享")
    val validation = GuizangHtmlDeckValidator.validateDeck(deck)
    if (!validation.valid) {
        error("全功能 HTML 不安全或过大，无法分享: ${validation.reason.orEmpty()}")
    }
    val exportHtml = GuizangHtmlDeckValidator.rewriteRuntimeUrlsForArchive(deck.html)
    ZipOutputStream(FileOutputStream(archive)).use { zip ->
        zip.writeTextEntry(
            name = "manifest.json",
            text = JSONObject()
                .put("type", "amberagent-full-html")
                .put("title", title?.takeIf { it.isNotBlank() } ?: "Full HTML Deck")
                .put("source", deck.source ?: GuizangHtmlDeckValidator.RENDERER)
                .put("allow_remote_images", deck.allowRemoteImages)
                .put("allow_remote_fonts", deck.allowRemoteFonts)
                .put("created_at_ms", now)
                .toString(2),
        )
        zip.writeTextEntry("index.html", exportHtml)
        zip.writeAssetEntry(context, "assets/motion.min.js", GuizangHtmlDeckValidator.MOTION_ASSET_PATH)
        zip.writeAssetEntry(context, "assets/lucide.min.js", GuizangHtmlDeckValidator.LUCIDE_ASSET_PATH)
        zip.writeTextEntry(
            "README.md",
            """
            # ${title?.takeIf { it.isNotBlank() } ?: "Full HTML Deck"}

            - `index.html`: Full HTML deck, with Motion One and Lucide URLs rewritten to local files.
            - `assets/motion.min.js`: bundled Motion One runtime.
            - `assets/lucide.min.js`: bundled Lucide runtime.
            - `cover.svg`: optional chat preview cover.
            - Remote images/fonts may still load if the deck references them and the viewer has network access.
            """.trimIndent(),
        )
        val cover = coverHtml.trim()
        if (cover.startsWith("<svg", ignoreCase = true) && cover.length <= 32_000) {
            zip.writeTextEntry("cover.svg", cover)
        }
    }
    return archive
}

private suspend fun shareSlidesDeckArchive(
    context: Context,
    title: String?,
    specJson: String,
    coverHtml: String,
    toaster: ToasterState,
) {
    toaster.show("正在打包幻灯片…")
    val archive = runCatching {
        withContext(Dispatchers.IO) {
            createSlidesDeckArchive(context, title, specJson, coverHtml)
        }
    }.getOrElse { error ->
        toaster.show(error.message ?: "打包失败", type = ToastType.Error)
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archive)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, archive.name)
            putExtra(Intent.EXTRA_SUBJECT, title?.takeIf { it.isNotBlank() } ?: "AmberAgent Slides")
            clipData = ClipData.newUri(context.contentResolver, archive.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "分享幻灯片 ZIP")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }.onSuccess {
        toaster.show("已打开分享菜单", type = ToastType.Success)
    }.onFailure { error ->
        toaster.show(error.message ?: "无法打开分享菜单", type = ToastType.Error)
    }
}

private fun createSlidesDeckArchive(
    context: Context,
    title: String?,
    specJson: String,
    coverHtml: String,
): File {
    val now = System.currentTimeMillis()
    val safeTitle = safeArchiveName(title)
    val dir = File(context.cacheDir, "slide-shares").apply { mkdirs() }
    dir.listFiles()
        ?.filter { it.isFile && now - it.lastModified() > 2 * 24 * 60 * 60 * 1000L }
        ?.forEach { it.delete() }
    val archive = File(dir, "AmberAgent_${safeTitle}_$now.zip")
    val normalizedSlides = VChartSpecValidator.normalizeSlidesDeckSpecJson(specJson)
        ?: error("幻灯片数据格式不正确，无法分享")
    val validation = VChartSpecValidator.validateSlidesSpec(normalizedSlides)
    if (!validation.valid) {
        error("幻灯片数据不安全或过大，无法分享: ${validation.reason.orEmpty()}")
    }
    val deckObject = JSONObject(normalizedSlides)
    val prettySlides = deckObject.toString(2)
    val slideCount = deckObject.getJSONArray("slides").length()
    ZipOutputStream(FileOutputStream(archive)).use { zip ->
        zip.writeTextEntry(
            name = "manifest.json",
            text = JSONObject()
                .put("type", "amberagent-slides")
                .put("title", title?.takeIf { it.isNotBlank() } ?: "AmberAgent Slides")
                .put("slide_count", slideCount)
                .put("created_at_ms", now)
                .toString(2),
        )
        zip.writeTextEntry("slides.json", prettySlides)
        zip.writeTextEntry("theme.json", buildSlidesThemeJson(deckObject))
        zip.writeTextEntry("index.html", buildStandaloneSlidesHtml(context, title, normalizedSlides))
        zip.writeTextEntry(
            "README.md",
            """
            # ${title?.takeIf { it.isNotBlank() } ?: "AmberAgent Slides"}

            - `index.html`: 离线预览页面，解压后用浏览器打开。
            - `slides.json`: AmberAgent slides renderer 使用的原始结构化内容，包含 style/accent/fontPack。
            - `theme.json`: 当前 deck 的主题与字体 fallback 信息。
            - `cover.svg`: 如果存在，是聊天内联预览封面。
            - 默认不内嵌大型中文字体；离线预览会使用系统 fallback 字体。
            """.trimIndent(),
        )
        val cover = coverHtml.trim()
        if (cover.startsWith("<svg", ignoreCase = true) && cover.length <= 32_000) {
            zip.writeTextEntry("cover.svg", cover)
        }
    }
    return archive
}

private fun buildSlidesThemeJson(deckObject: JSONObject): String =
    JSONObject()
        .put("style", deckObject.optString("style", "system"))
        .put("accent", deckObject.optString("accent", "#1F5EFF"))
        .put("fontPack", deckObject.optString("fontPack", ""))
        .put("fontEmbedding", "not_included_large_cjk_fonts_use_system_fallback")
        .toString(2)

private fun ZipOutputStream.writeTextEntry(name: String, text: String) {
    putNextEntry(ZipEntry(name))
    write(text.toByteArray(Charsets.UTF_8))
    closeEntry()
}

private fun ZipOutputStream.writeAssetEntry(context: Context, name: String, assetPath: String) {
    putNextEntry(ZipEntry(name))
    context.assets.open(assetPath).use { input -> input.copyTo(this) }
    closeEntry()
}

private fun buildStandaloneSlidesHtml(context: Context, title: String?, specJson: String): String {
    val safeTitle = htmlEscape(title?.takeIf { it.isNotBlank() } ?: "AmberAgent Slides")
    val slidesLiteral = JSONObject.quote(specJson.toScriptSafeJsonString())
    val sandbox = context.assets.open("generative-libs/slides-sandbox.html")
        .use { it.reader(Charsets.UTF_8).readText() }
    return sandbox
        .replace("<head>", "<head>\n<title>$safeTitle</title>")
        .replace(
            "</body>",
            """
            <script>
            window.__setAmberFontCss && window.__setAmberFontCss("");
            window.__renderSlides && window.__renderSlides($slidesLiteral);
            </script>
            </body>
            """.trimIndent(),
        )
}

private fun safeArchiveName(value: String?): String =
    (value?.takeIf { it.isNotBlank() } ?: "slides")
        .replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
        .take(40)
        .ifBlank { "slides" }

private fun htmlEscape(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

private fun String.toScriptSafeJsonString(): String =
    replace("<", "\\u003C")
        .replace(">", "\\u003E")
        .replace("&", "\\u0026")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")

private suspend fun captureSlidesToJpg(
    webView: WebView,
    activity: android.app.Activity,
    context: android.content.Context,
    deckTitle: String?,
    toaster: com.dokar.sonner.ToasterState,
    settleDelayMs: Long = 160L,
) {
    toaster.show("正在保存演示…")
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
            delay(settleDelayMs)
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

private val BUILTIN_FONTS = mapOf(
    "PlayfairDisplay-Regular.ttf" to "generative-libs/builtin-fonts/PlayfairDisplay-Regular.ttf",
    "Inter-Regular.ttf" to "generative-libs/builtin-fonts/Inter-Regular.ttf",
)

private fun WebView.interceptBuiltinFontRequest(request: WebResourceRequest?): WebResourceResponse? {
    val uri = request?.url ?: return null
    if (uri.scheme != "https" || uri.host != "amberagent.local") return null
    val segments = uri.pathSegments
    if (segments.size != 2 || segments[0] != "builtin-fonts") return null
    val assetPath = BUILTIN_FONTS[segments[1]] ?: return null
    val mimeType = if (segments[1].endsWith(".otf")) "font/otf" else "font/ttf"
    return runCatching {
        WebResourceResponse(mimeType, null, context.assets.open(assetPath))
    }.getOrNull()
}

private fun isSafeExternalWidgetUrl(url: String): Boolean {
    if (url.length !in 1..MAX_WIDGET_URL_LENGTH) return false
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    return uri.scheme?.lowercase() in setOf("http", "https")
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GuizangHtmlDeckWebView(
    specJson: String,
    modifier: Modifier = Modifier,
    onWebViewReady: (WebView?) -> Unit = {},
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val deck = remember(specJson) { GuizangHtmlDeckValidator.normalizeSpecJson(specJson) }
    val validated = remember(deck) {
        deck?.let { GuizangHtmlDeckValidator.validateDeck(it) }
            ?: GuizangHtmlDeckValidator.ValidationResult(false, "expected spec.html")
    }
    var activeWebView by remember { mutableStateOf<WebView?>(null) }
    var currentSlide by remember { mutableStateOf(0) }
    var slideCount by remember { mutableStateOf(0) }
    var lowPower by remember { mutableStateOf(false) }
    val lowPowerState by rememberUpdatedState(lowPower)

    if (!validated.valid || deck == null) {
        Text(
            text = "全功能 HTML 校验失败: ${validated.reason.orEmpty()}",
            modifier = modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }

    fun setLowPowerMode(on: Boolean) {
        lowPower = on
        activeWebView?.evaluateJavascript(
            guizangSetLowPowerModeJs(on),
            null,
        )
    }

    fun runDeckAction(js: String) {
        activeWebView?.evaluateJavascript(js, null)
    }

    LaunchedEffect(activeWebView) {
        while (true) {
            val target = activeWebView ?: break
            val nextCount = evaluateJsAwait(target, "window.__getSlideCount && window.__getSlideCount()")
                ?.toIntOrNull()
                ?: 0
            val nextCurrent = evaluateJsAwait(target, "window.__getCurrentSlide && window.__getCurrentSlide()")
                ?.toIntOrNull()
                ?: 0
            slideCount = nextCount
            currentSlide = nextCurrent.coerceIn(0, (nextCount - 1).coerceAtLeast(0))
            delay(650)
        }
    }

    DisposableEffect(lifecycleOwner, activeWebView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    activeWebView?.evaluateJavascript(
                        guizangSetLowPowerModeJs(true),
                        null,
                    )
                    activeWebView?.onPause()
                }

                Lifecycle.Event.ON_RESUME -> {
                    activeWebView?.onResume()
                    activeWebView?.evaluateJavascript(
                        guizangSetLowPowerModeJs(lowPowerState),
                        null,
                    )
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    overScrollMode = android.view.View.OVER_SCROLL_NEVER
                    isHorizontalScrollBarEnabled = false
                    isVerticalScrollBarEnabled = false
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                    setBackgroundColor(android.graphics.Color.BLACK)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.allowFileAccessFromFileURLs = false
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    settings.useWideViewPort = false
                    settings.loadWithOverviewMode = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.blockNetworkLoads = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setSupportMultipleWindows(false)
                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message?,
                        ): Boolean = false
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean = true

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val url = request?.url?.toString() ?: return null
                            GuizangHtmlDeckValidator.runtimeAssetForUrl(url)?.let { asset ->
                                return guizangAssetResponse(asset)
                            }
                            interceptBuiltinFontRequest(request)?.let { return it }

                            val uri = request.url
                            val scheme = uri.scheme?.lowercase()
                            if (scheme == "file" || scheme == "content" || scheme == "intent") {
                                return blockedGuizangResponse()
                            }
                            if (scheme == "http" || scheme == "https") {
                                if (uri.host == "amberagent.local") return blockedGuizangResponse()
                                if (deck.allowRemoteImages && GuizangHtmlDeckValidator.isAllowedRemoteImage(url)) return null
                                if (deck.allowRemoteFonts && GuizangHtmlDeckValidator.isAllowedRemoteFontOrStylesheet(url)) return null
                                return blockedGuizangResponse()
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.evaluateJavascript(buildGuizangDeckBootstrapJs()) {
                                view.evaluateJavascript(guizangSetLowPowerModeJs(lowPowerState), null)
                            }
                        }
                    }
                    val runtimeHtml = GuizangHtmlDeckValidator.prepareRuntimeHtml(deck.html)
                    post {
                        loadDataWithBaseURL(
                            "https://amberagent.local/full-html-deck/",
                            runtimeHtml,
                            "text/html",
                            "utf-8",
                            null,
                        )
                    }
                    activeWebView = this
                    onWebViewReady(this)
                }
            },
            update = { webView ->
                activeWebView = webView
                onWebViewReady(webView)
            },
        )

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(14.dp),
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
            tonalElevation = 2.dp,
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(
                    onClick = {
                        runDeckAction("window.__amberDeckPrev && window.__amberDeckPrev();")
                    },
                ) {
                    Icon(HugeIcons.ArrowLeft01, contentDescription = "上一页")
                }
                Text(
                    text = if (slideCount > 0) "${currentSlide + 1}/$slideCount" else "0/0",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                IconButton(onClick = { setLowPowerMode(!lowPower) }) {
                    Icon(
                        if (lowPower) HugeIcons.Play else HugeIcons.Pause,
                        contentDescription = if (lowPower) "恢复动画" else "低功耗",
                    )
                }
                IconButton(
                    onClick = {
                        runDeckAction("window.__amberDeckNext && window.__amberDeckNext();")
                    },
                ) {
                    Icon(HugeIcons.ArrowRight01, contentDescription = "下一页")
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activeWebView?.apply {
                evaluateJavascript(guizangSetLowPowerModeJs(true), null)
                onPause()
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            activeWebView = null
            onWebViewReady(null)
        }
    }
}

private fun guizangSetLowPowerModeJs(on: Boolean): String =
    "window.__setLowPowerMode && window.__setLowPowerMode($on, {persist:false});"

private fun WebView.guizangAssetResponse(
    asset: GuizangHtmlDeckValidator.RuntimeAsset,
): WebResourceResponse? =
    runCatching {
        WebResourceResponse(asset.mimeType, "utf-8", context.assets.open(asset.assetPath)).apply {
            responseHeaders = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-store",
            )
        }
    }.getOrNull()

private fun blockedGuizangResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", ByteArray(0).inputStream()).apply {
        responseHeaders = mapOf("Cache-Control" to "no-store")
    }

private fun buildGuizangDeckBootstrapJs(): String = """
(function(){
  if (window.__amberGuizangReady) return true;
  window.__amberGuizangReady = true;

  function installViewportHeightFallback(){
    var style = document.getElementById('amber-full-html-viewport-fallback');
    if (!style) {
      style = document.createElement('style');
      style.id = 'amber-full-html-viewport-fallback';
      document.head.appendChild(style);
    }
    style.textContent = [
      'html,body{height:100%!important;min-height:100%!important;}',
      'canvas.bg,#deck{height:100%!important;min-height:100%!important;}',
      '#deck .slide{height:100%!important;min-height:100%!important;}'
    ].join('\n');
  }

  function slideSelector(){ return '.slide, section[data-slide], article[data-slide], div[data-slide]'; }
  function ensureSlideClass(el){
    if (el && el.classList && !el.classList.contains('slide')) el.classList.add('slide');
    return el;
  }
  function deckEl(){
    var deck = document.getElementById('deck') ||
      document.querySelector('[data-guizang-deck], [data-deck], .deck, .slides, main');
    if (deck && deck.querySelectorAll(slideSelector()).length) {
      if (!deck.id) deck.id = 'deck';
      Array.prototype.forEach.call(deck.querySelectorAll(slideSelector()), ensureSlideClass);
      return deck;
    }
    var slides = Array.prototype.slice.call(document.querySelectorAll(slideSelector()));
    if (slides.length) {
      var wrapper = document.createElement('div');
      wrapper.id = 'deck';
      slides[0].parentNode.insertBefore(wrapper, slides[0]);
      slides.forEach(function(slide){ wrapper.appendChild(ensureSlideClass(slide)); });
      return wrapper;
    }
    return null;
  }
  function navEl(){ return document.getElementById('nav'); }
  function slideList(){
    var deck = deckEl();
    return deck ? Array.prototype.slice.call(deck.querySelectorAll(slideSelector())).map(ensureSlideClass) : [];
  }
  function count(){ return slideList().length; }
  function getIndex(){
    try {
      if (typeof idx !== 'undefined' && Number.isFinite(idx)) return idx;
    } catch(e) {}
    return window.__currentSlideIndex || window.__amberGuizangCurrent || 0;
  }
  function applyTheme(slide){
    if (!slide) return;
    var th = slide.dataset && slide.dataset.theme;
    var isLight = th ? th === 'light' : slide.classList.contains('light');
    var isDark = th ? th === 'dark' : (slide.classList.contains('dark') || slide.classList.contains('accent'));
    document.body.classList.toggle('light-bg', !!isLight);
    document.body.classList.toggle('dark-bg', !!isDark);
  }
  function setIndex(n, animate){
    var slides = slideList();
    var total = slides.length;
    if (!total) return false;
    n = Math.max(0, Math.min(total - 1, Number(n) || 0));
    try { if (typeof lock !== 'undefined') lock = false; } catch(e) {}
    try { if (typeof idx !== 'undefined') idx = n; } catch(e) {}
    window.__currentSlideIndex = n;
    window.__amberGuizangCurrent = n;
    var deck = deckEl();
    if (deck) {
      var previousTransition = deck.style.transition;
      if (!animate) deck.style.transition = 'none';
      deck.style.width = (total * 100) + 'vw';
      deck.style.transform = 'translateX(' + (-n * 100) + 'vw)';
      if (!animate) requestAnimationFrame(function(){ deck.style.transition = previousTransition; });
    }
    var nav = navEl();
    if (nav) {
      Array.prototype.forEach.call(nav.querySelectorAll('.dot'), function(dot, i){
        dot.classList.toggle('active', i === n);
      });
    }
    applyTheme(slides[n]);
    if (window.__playSlide) setTimeout(function(){ window.__playSlide(n); }, animate ? 450 : 30);
    return true;
  }

  window.__getSlideCount = count;
  window.__getCurrentSlide = function(){
    var total = count();
    if (!total) return 0;
    return Math.max(0, Math.min(total - 1, getIndex()));
  };
  window.__jumpToSlideInstant = function(n){ return setIndex(n, false); };
  window.__amberDeckPrev = function(){
    var next = window.__getCurrentSlide() - 1;
    if (typeof go === 'function') {
      try { if (typeof lock !== 'undefined') lock = false; } catch(e) {}
      go(next);
      window.__amberGuizangCurrent = window.__getCurrentSlide();
      return true;
    }
    return setIndex(next, true);
  };
  window.__amberDeckNext = function(){
    if (window.__pipeAdvance && window.__pipeAdvance()) return 'step';
    var next = window.__getCurrentSlide() + 1;
    if (typeof go === 'function') {
      try { if (typeof lock !== 'undefined') lock = false; } catch(e) {}
      go(next);
      window.__amberGuizangCurrent = window.__getCurrentSlide();
      return true;
    }
    return setIndex(next, true);
  };
  window.__beginCapture = function(){
    document.body.classList.add('amber-capture');
    var style = document.getElementById('amber-capture-style');
    if (!style) {
      style = document.createElement('style');
      style.id = 'amber-capture-style';
      style.textContent = '#hint,#nav,#overview{display:none!important} body{overflow:hidden!important}';
      document.head.appendChild(style);
    }
  };
  window.__endCapture = function(){
    document.body.classList.remove('amber-capture');
    var style = document.getElementById('amber-capture-style');
    if (style) style.remove();
  };
  installViewportHeightFallback();
  setIndex(getIndex(), false);
  if (window.lucide && window.lucide.createIcons) window.lucide.createIcons();
  return true;
})();
""".trimIndent()

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun RichSandboxWebView(
    renderer: String,
    specJson: String,
    setting: GenerativeUiSetting,
    modifier: Modifier = Modifier,
    maxHeightDp: Int = 720,
    onWebViewReady: (WebView?) -> Unit = {},
) {
    val fontRepository = koinInject<SlidesFontRepository>()
    val normalizedSpecJson = remember(specJson, renderer) {
        if (renderer == "slides") VChartSpecValidator.normalizeSlidesDeckSpecJson(specJson) else specJson
    }
    val validated = remember(normalizedSpecJson, renderer) {
        when (renderer) {
            "vchart" -> VChartSpecValidator.validateChartSpec(normalizedSpecJson.orEmpty())
            "slides" -> VChartSpecValidator.validateSlidesSpec(normalizedSpecJson.orEmpty())
            else -> VChartSpecValidator.ValidationResult(false, "unknown renderer")
        }
    }
    // Start at a reasonable initial height; JS resize will adjust to exact content.
    // Capped to avoid jarring shrink animation when maxHeightDp is very large (tablet fullscreen).
    var heightDp by remember(maxHeightDp) { mutableStateOf(maxHeightDp) }
    // Strip U+2028/U+2029 (line/paragraph separators) — JSON allows them,
    // but evaluateJavascript treats them as line terminators and silently fails.
    val safeJson = normalizedSpecJson.orEmpty().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    val fontCss = remember(safeJson, renderer, setting) {
        if (renderer == "slides") fontRepository.buildSlidesFontCss(safeJson, setting) else ""
    }
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
                    settings.blockNetworkLoads = renderer != "slides"
                    // Slides need MIXED_CONTENT_ALWAYS_ALLOW so that the file:///android_asset/ page
                    // can trigger https://amberagent.local/ font requests which are intercepted locally
                    // by shouldInterceptRequest. NEVER_ALLOW silently blocks these before interception.
                    settings.mixedContentMode = if (renderer == "slides")
                        WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    else
                        WebSettings.MIXED_CONTENT_NEVER_ALLOW
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

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            // Serve downloaded CJK fonts from local storage
                            fontRepository.interceptFontRequest(request)?.let { return it }
                            // Serve builtin English fonts from APK assets
                            interceptBuiltinFontRequest(request)?.let { return it }
                            // Block all other network requests
                            val scheme = request?.url?.scheme?.lowercase()
                            if (scheme == "http" || scheme == "https") {
                                return WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.evaluateJavascript(
                                """
                                window.__setAmberFontCss && window.__setAmberFontCss(${JSONObject.quote(fontCss)});
                                window.$renderFunction(${JSONObject.quote(safeJson)});
                                """.trimIndent(),
                                null,
                            )
                        }
                    }
                    // Load via loadDataWithBaseURL so the page origin is https://amberagent.local/.
                    // This lets @font-face url("https://amberagent.local/fonts/...") trigger
                    // shouldInterceptRequest (same-origin). loadUrl("file:///") would make
                    // the https font requests cross-origin and silently blocked.
                    // Delay until after one layout pass so viewport height is non-zero.
                    val sandboxHtml = context.assets.open(assetFile).bufferedReader().readText()
                    post {
                        loadDataWithBaseURL(
                            "https://amberagent.local/",
                            sandboxHtml,
                            "text/html",
                            "utf-8",
                            null,
                        )
                    }
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
