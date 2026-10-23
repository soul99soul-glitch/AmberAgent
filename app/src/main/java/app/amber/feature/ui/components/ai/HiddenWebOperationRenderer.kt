package app.amber.feature.ui.components.ai

import android.os.Build
import android.webkit.WebSettings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import app.amber.feature.webview.WebViewOperationStore
import app.amber.feature.ui.components.webview.WebView
import app.amber.feature.ui.components.webview.rememberWebViewState

@Suppress("DEPRECATION")
internal fun WebSettings.disablePreviewDarkening() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        isAlgorithmicDarkeningAllowed = false
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        forceDark = WebSettings.FORCE_DARK_OFF
    }
}

@Composable
internal fun HiddenWebOperationRenderer(
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
                scheduleThumbnailCaptures(it, webViewOperationStore, context = it.context, loadId = loadId, cacheUrl = url)
            }
        },
        onReceivedError = { webView, pageUrl, error ->
            webViewOperationStore.markFailed(loadId, pageUrl ?: webView?.url ?: url, error.orEmpty())
        },
    )
}
