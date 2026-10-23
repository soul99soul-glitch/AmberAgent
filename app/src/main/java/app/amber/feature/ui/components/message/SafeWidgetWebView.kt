package app.amber.feature.ui.components.message

import android.annotation.SuppressLint
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import app.amber.core.settings.GenerativeUiSetting
import app.amber.core.utils.openUrl
import app.amber.core.utils.toCssHex
import app.amber.feature.ui.components.webview.WebViewWarmUp
import org.json.JSONObject
import java.util.UUID

private const val WIDGET_MIN_HEIGHT_DP = 160
private const val WIDGET_FALLBACK_HEIGHT_DP = 320
private const val MAX_WIDGET_URL_LENGTH = 2048
private const val STREAM_WIDGET_DEBOUNCE_MS = 16L
// A settled inline widget outside the viewport mounts its WebView once the page has had time to
// settle, so opening a conversation never pays for WebView startup inside its first frames.
private const val OFFSCREEN_WIDGET_MOUNT_DELAY_MS = 1_500L

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

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun SafeGenerativeWidgetWebView(
    html: String,
    setting: GenerativeUiSetting,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    liveStreaming: Boolean = streaming,
    complete: Boolean = !streaming,
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
    val maxHeight = (maxHeightOverrideDp ?: setting.maxWidgetHeightDp)
        .coerceIn(minHeightDp, 1600)
    val cacheKey = remember(widgetKey) {
        widgetKey?.takeIf { it.isNotBlank() } ?: html.take(200).ifBlank { UUID.randomUUID().toString() }
    }
    val revealState = remember { UIGenerationRevealState("widget:${cacheKey.take(48)}") }
    var pendingReveal by remember { mutableStateOf(false) }
    LaunchedEffect(liveStreaming, streaming, complete, interactive) {
        if (!interactive && revealState.update(isStreaming = liveStreaming || streaming, complete = complete)) {
            pendingReveal = true
        }
    }
    // Do not key on cacheKey — see DisposableEffect comment below. Resetting this to null
    // mid-stream creates a brief window where LaunchedEffect skips the push because
    // activeWebView is momentarily null.
    var activeWebView by remember { mutableStateOf<WebView?>(null) }
    // A new WebView gets its first payload only after its receiver page has loaded.
    var readyWebView by remember { mutableStateOf<WebView?>(null) }
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
    LaunchedEffect(renderedHtml, streaming) {
        if (streaming && renderedHtml.isNotBlank()) {
            hasMeasuredHeight = false
        }
    }
    val animatedHeight by animateDpAsState(
        targetValue = heightDp.coerceIn(minHeightDp, maxHeight).dp,
        animationSpec = if (streaming || !hasMeasuredHeight) {
            snap()
        } else {
            tween(durationMillis = 180)
        },
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
    LaunchedEffect(html, renderedHtml, streaming, readyWebView, pendingReveal) {
        val target = readyWebView ?: return@LaunchedEffect
        val effectiveHtml = if (streaming) renderedHtml else html
        val next = effectiveHtml.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        target.pushWidgetHtml(next, finalize = !streaming)
        if (!streaming && pendingReveal) {
            pendingReveal = false
            UIGenerationMotion.play(
                context = context,
                webView = target,
                selector = UIGenerationMotion.DEFAULT_WIDGET_SELECTOR,
            )
        }
    }

    // Streaming and expanded widgets need their WebView at once. A settled inline widget keeps
    // its measured slot empty until it has been drawn once (on screen) or the page has settled
    // (off screen): WebView creation costs tens of ms on the main thread, and the first one in a
    // process also loads the Chromium provider.
    val deferMount = !streaming && !liveStreaming && !interactive && !fillContainer
    var mounted by remember { mutableStateOf(!deferMount) }
    var onScreen by remember { mutableStateOf(false) }
    if (!mounted) {
        LaunchedEffect(onScreen) {
            WebViewWarmUp.request(context)
            if (onScreen) {
                withFrameNanos {}
                withFrameNanos {}
            } else {
                delay(OFFSCREEN_WIDGET_MOUNT_DELAY_MS)
            }
            mounted = true
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (fillContainer) Modifier.fillMaxSize() else Modifier.height(animatedHeight))
            .then(
                if (mounted) {
                    Modifier
                } else {
                    Modifier.onGloballyPositioned { onScreen = it.boundsInWindow().height > 0f }
                }
            )
    ) {
        if (mounted) AndroidView(
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
                            if (view === activeWebView) readyWebView = view
                        }
                    }
                    activeWebView = this
                    loadDataWithBaseURL(
                        "https://amberagent.widget.local/",
                        receiverHtml,
                        "text/html",
                        "utf-8",
                        null,
                    )
                    onWebViewReady(this)
                }
            },
            update = { webView ->
                activeWebView = webView
                onWebViewReady(webView)
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
                UIGenerationMotion.cancel(this)
                stopLoading()
                removeJavascriptInterface("AmberWidget")
                loadUrl("about:blank")
                destroy()
            }
            activeWebView = null
            onWebViewReady(null)
        }
    }

    // Pause declarative widget animation (SMIL timelines + CSS animations)
    // when the host lifecycle stops; an infinite loop keeps compositing an
    // offscreen WebView otherwise. Same pause/resume contract the Guizang
    // deck uses, minus its low-power persistence.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, activeWebView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    UIGenerationMotion.cancel(activeWebView)
                    activeWebView?.evaluateJavascript(WIDGET_PAUSE_ANIMATIONS_JS, null)
                }

                Lifecycle.Event.ON_RESUME ->
                    activeWebView?.evaluateJavascript(WIDGET_RESUME_ANIMATIONS_JS, null)

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            UIGenerationMotion.cancel(activeWebView)
            activeWebView?.evaluateJavascript(WIDGET_PAUSE_ANIMATIONS_JS, null)
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private const val WIDGET_PAUSE_ANIMATIONS_JS =
    "window.__amberWidgetPaused=true;window.__amberWidgetApplyPause&&window.__amberWidgetApplyPause();"

private const val WIDGET_RESUME_ANIMATIONS_JS =
    "window.__amberWidgetPaused=false;window.__amberWidgetApplyResume&&window.__amberWidgetApplyResume();"

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

private fun isSafeExternalWidgetUrl(url: String): Boolean {
    if (url.length !in 1..MAX_WIDGET_URL_LENGTH) return false
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    return uri.scheme?.lowercase() in setOf("http", "https")
}

private fun WebView.pushWidgetHtml(html: String, finalize: Boolean) {
    val fn = if (finalize) "__amberWidgetFinalizeHtml" else "__amberWidgetSetHtml"
    evaluateJavascript("window.$fn(${JSONObject.quote(html)});", null)
}
