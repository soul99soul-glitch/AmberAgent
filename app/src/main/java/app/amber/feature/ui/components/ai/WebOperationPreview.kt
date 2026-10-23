package app.amber.feature.ui.components.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import app.amber.agent.R
import app.amber.feature.webview.WebViewLoadStatus
import app.amber.feature.webview.WebViewOperationState
import app.amber.feature.webview.WebViewOperationStore
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.components.webview.WebView
import app.amber.feature.ui.components.webview.WebViewWarmUp
import app.amber.feature.ui.components.webview.rememberWebViewState
import org.koin.compose.koinInject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun WebOperationPreviewThumbnail(
    url: String,
    toolCallId: String,
    live: Boolean,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val webViewOperationStore: WebViewOperationStore = koinInject()
    val webState by webViewOperationStore.state.collectAsStateWithLifecycle()
    val normalizedUrl = remember(url) { url.normalizedWebPreviewUrl() }
    // Key only on toolCallId + normalizedUrl (stable), not on webState fields (change on every capture)
    val isCurrentPreview = remember(toolCallId, normalizedUrl, webState.loadId) {
        webState.matchesPreview(toolCallId = toolCallId, normalizedUrl = normalizedUrl)
    }
    val cached by produceState<CachedWebThumbnail>(CachedWebThumbnail.Pending, normalizedUrl) {
        value = withContext(Dispatchers.IO) {
            WebPreviewThumbnails.cached(context, url)?.let(CachedWebThumbnail::Found) ?: CachedWebThumbnail.Missing
        }
    }
    val thumbnailFile = webState.bestThumbnailFile(isCurrentPreview, cached = (cached as? CachedWebThumbnail.Found)?.file)
    LaunchedEffect(toolCallId, normalizedUrl, isCurrentPreview, live, cached) {
        if (isCurrentPreview) return@LaunchedEffect
        // A finished tool with a saved thumbnail needs no WebView. Without one, wait until the
        // page has settled before paying for WebView startup, then keep the capture for next time.
        when (cached) {
            CachedWebThumbnail.Pending -> return@LaunchedEffect
            is CachedWebThumbnail.Found -> if (!live) return@LaunchedEffect
            CachedWebThumbnail.Missing -> if (!live) {
                WebViewWarmUp.request(context)
                delay(WEB_PREVIEW_BACKFILL_DELAY_MS)
            }
        }
        webViewOperationStore.open(url, toolCallId = toolCallId)
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

private sealed interface CachedWebThumbnail {
    data object Pending : CachedWebThumbnail
    data object Missing : CachedWebThumbnail
    data class Found(val file: File) : CachedWebThumbnail
}

private const val WEB_PREVIEW_BACKFILL_DELAY_MS = 2_000L

@Composable
private fun WebOperationPreviewPlaceholder(
    url: String,
    webState: WebViewOperationState?,
    workspace: app.amber.feature.ui.components.ui.WorkspaceColors,
) {
    val webPreviewTitle = stringResource(R.string.chat_sandbox_web_preview)
    val title = webState?.title.orEmpty().ifBlank { webPreviewTitle }
    val detail = when {
        webState?.lastError?.isNotBlank() == true -> webState.lastError
        webState?.isLoading == true -> stringResource(
            R.string.chat_sandbox_loading_web,
            url.webHostPreview(),
        )
        webState?.status == WebViewLoadStatus.STALLED -> stringResource(R.string.chat_sandbox_web_load_slow)
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
internal fun OperationWebPreview(
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
                            scheduleThumbnailCaptures(view, webViewOperationStore, context, loadId, cacheUrl = url)
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
                val loadingWeb = stringResource(
                    R.string.chat_sandbox_loading_web,
                    url.webHostPreview(),
                )
                Text(
                    text = webState?.title?.takeIf { it.isNotBlank() } ?: loadingWeb,
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
                        webState?.status == WebViewLoadStatus.STALLED -> stringResource(
                            R.string.chat_sandbox_web_load_slow_keep_preview,
                        )
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
