package app.amber.feature.ui.pages.synara

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.amber.agent.Screen
import app.amber.feature.ui.context.LocalNavController

private const val TAG = "SynaraWorkspace"

internal sealed interface SynaraWorkspaceLoadState {
    data class Loading(val progress: Int = 0) : SynaraWorkspaceLoadState

    data object Ready : SynaraWorkspaceLoadState

    data class Error(val message: String?) : SynaraWorkspaceLoadState
}

internal fun synaraLoadStarted(): SynaraWorkspaceLoadState =
    SynaraWorkspaceLoadState.Loading()

internal fun synaraLoadProgress(
    state: SynaraWorkspaceLoadState,
    progress: Int,
): SynaraWorkspaceLoadState = when (state) {
    is SynaraWorkspaceLoadState.Loading -> SynaraWorkspaceLoadState.Loading(progress.coerceIn(0, 100))
    else -> state
}

internal fun synaraLoadFinished(state: SynaraWorkspaceLoadState): SynaraWorkspaceLoadState =
    when (state) {
        is SynaraWorkspaceLoadState.Error -> state
        else -> SynaraWorkspaceLoadState.Ready
    }

internal fun synaraMainFrameError(
    state: SynaraWorkspaceLoadState,
    description: String? = null,
    isMainFrame: Boolean = true,
): SynaraWorkspaceLoadState = if (isMainFrame) {
    SynaraWorkspaceLoadState.Error(description.cleanSynaraError())
} else {
    state
}

internal fun synaraMainFrameHttpError(
    state: SynaraWorkspaceLoadState,
    statusCode: Int,
    reason: String? = null,
    isMainFrame: Boolean = true,
): SynaraWorkspaceLoadState = if (isMainFrame) {
    val status = statusCode.takeIf { it > 0 }?.let { "HTTP $it" }
    SynaraWorkspaceLoadState.Error(reason.cleanSynaraError() ?: status)
} else {
    state
}

private fun String?.cleanSynaraError(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() }?.take(240)

/**
 * Full-screen Synara LAN workbench — no Amber chrome.
 *
 * Important: do **not** invent a partial `window.desktopBridge`.
 * Synara treats any truthy desktopBridge as Electron and calls methods like
 * `setTheme` / `browser.*` that we do not implement — that freezes the boot logo.
 *
 * Instead we stay on the pure-browser path and only wrap `WebSocket` so
 * `/ws` upgrades include `?token=` (legacy LAN auth).
 */
@Composable
fun SynaraWorkspacePage(connection: SynaraConnection) {
    val navController = LocalNavController.current
    val validationError = connection.validationError()
    val pageUrl = remember(connection) { connection.workspaceUrl() }

    var canGoBack by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var loadState by remember(pageUrl) {
        mutableStateOf<SynaraWorkspaceLoadState>(SynaraWorkspaceLoadState.Loading())
    }

    BackHandler {
        if (canGoBack) {
            webViewRef?.goBack()
        } else {
            navController.popBackStack()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let { wv ->
                runCatching {
                    wv.stopLoading()
                    wv.destroy()
                }
            }
            webViewRef = null
        }
    }

    if (validationError != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = validationError, color = MaterialTheme.colorScheme.error)
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SynaraAndroidWebView(
            connection = connection,
            pageUrl = pageUrl,
            onWebViewReady = { webViewRef = it },
            onCanGoBack = { canGoBack = it },
            onPageStarted = { loadState = synaraLoadStarted() },
            onPageProgress = { progress ->
                loadState = synaraLoadProgress(loadState, progress)
            },
            onPageFinished = {
                loadState = synaraLoadFinished(loadState)
            },
            onMainFrameError = { description ->
                loadState = synaraMainFrameError(loadState, description)
            },
            onMainFrameHttpError = { statusCode, reason ->
                loadState = synaraMainFrameHttpError(loadState, statusCode, reason)
            },
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        )

        when (val state = loadState) {
            is SynaraWorkspaceLoadState.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 12.dp)
                        .size(24.dp),
                    strokeWidth = 2.dp,
                )
            }

            is SynaraWorkspaceLoadState.Error -> {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    tonalElevation = 4.dp,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = androidx.compose.ui.res.stringResource(
                                app.amber.agent.R.string.parity_synara_workspace_error_title,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = state.message ?: androidx.compose.ui.res.stringResource(
                                app.amber.agent.R.string.parity_synara_workspace_unknown_error,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = {
                                loadState = synaraLoadStarted()
                                webViewRef?.stopLoading()
                                webViewRef?.loadUrl(pageUrl)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = androidx.compose.ui.res.stringResource(
                                    app.amber.agent.R.string.parity_synara_workspace_retry,
                                ),
                            )
                        }
                        OutlinedButton(
                            onClick = { navController.navigate(Screen.SynaraCompanion) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = androidx.compose.ui.res.stringResource(
                                    app.amber.agent.R.string.parity_synara_workspace_connection_settings,
                                ),
                            )
                        }
                    }
                }
            }

            SynaraWorkspaceLoadState.Ready -> Unit
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SynaraAndroidWebView(
    connection: SynaraConnection,
    pageUrl: String,
    onWebViewReady: (WebView) -> Unit,
    onCanGoBack: (Boolean) -> Unit,
    onPageStarted: () -> Unit,
    onPageProgress: (Int) -> Unit,
    onPageFinished: () -> Unit,
    onMainFrameError: (String?) -> Unit,
    onMainFrameHttpError: (Int, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val injectScript = remember(connection) { buildSynaraInjectScript(connection) }
    val origin = remember(pageUrl) { originWithPort(pageUrl) }
    val scriptHandlers = remember { mutableListOf<ScriptHandler>() }

    DisposableEffect(Unit) {
        onDispose {
            scriptHandlers.forEach { runCatching { it.remove() } }
            scriptHandlers.clear()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                // chrome://inspect — verify SPA / WS without leaving Amber.
                // 仅限 debug 包：release 下开启会让页面 URL 里的 ?token= 经 adb 可见。
                WebView.setWebContentsDebuggingEnabled(app.amber.agent.BuildConfig.DEBUG)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.mixedContentMode = if (connection.useHttps) {
                    WebSettings.MIXED_CONTENT_NEVER_ALLOW
                } else {
                    WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
                settings.mediaPlaybackRequiresUserGesture = false
                // Stay browser-like, not Electron (Synara probes "; wv" in UA).
                settings.userAgentString = settings.userAgentString
                    .replace("; wv", "")

                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && origin != null) {
                    runCatching {
                        scriptHandlers += WebViewCompat.addDocumentStartJavaScript(
                            this,
                            injectScript,
                            setOf(origin),
                        )
                    }.onFailure { Log.e(TAG, "document-start install failed", it) }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        onPageProgress(newProgress)
                    }

                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val msg = consoleMessage ?: return super.onConsoleMessage(consoleMessage)
                        if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                            Log.e(TAG, "console ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}")
                        }
                        return true
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        onPageStarted()
                        view?.evaluateJavascript(injectScript, null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        onCanGoBack(view?.canGoBack() == true)
                        onPageFinished()
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            Log.e(TAG, "main frame error ${error?.description}")
                            onMainFrameError(error?.description?.toString())
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            val statusCode = errorResponse?.statusCode ?: 0
                            val reason = errorResponse?.reasonPhrase
                            Log.e(TAG, "main frame HTTP error $statusCode $reason")
                            onMainFrameHttpError(statusCode, reason)
                        }
                    }
                }

                onWebViewReady(this)
                loadUrl(pageUrl)
            }
        },
        update = { webView -> onWebViewReady(webView) },
    )
}

private fun originWithPort(url: String): String? {
    return runCatching {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host ?: return null
        if (scheme != "http" && scheme != "https") return null
        val port = uri.port
        val defaultPort = if (scheme == "https") 443 else 80
        if (port == -1 || port == defaultPort) "$scheme://$host" else "$scheme://$host:$port"
    }.getOrNull()
}

/**
 * Browser-path inject only:
 * 1) Wrap WebSocket so /ws gets ?token= (desktop legacy auth)
 * 2) Desktop-width viewport for the desktop SPA on a phone
 *
 * Deliberately does **not** set window.desktopBridge.
 */
private fun buildSynaraInjectScript(connection: SynaraConnection): String {
    val token = connection.token.trim().toJsSingleQuoted()
    return """
        (function() {
          if (window.__amberSynaraWsPatch) return;
          window.__amberSynaraWsPatch = true;
          var TOKEN = '$token';
          var Orig = window.WebSocket;
          if (typeof Orig === 'function' && TOKEN) {
            function PatchedWebSocket(url, protocols) {
              try {
                var u = new URL(String(url), location.href);
                var path = (u.pathname || '').replace(/\/+$/, '');
                var isWsEndpoint = path === '/ws' || path === '/ws/bootstrap' ||
                    path.endsWith('/ws') || path.endsWith('/ws/bootstrap');
                if (isWsEndpoint && !u.searchParams.get('token')) {
                  u.searchParams.set('token', TOKEN);
                  url = u.toString();
                }
              } catch (e) {}
              if (protocols === undefined) return new Orig(url);
              return new Orig(url, protocols);
            }
            PatchedWebSocket.prototype = Orig.prototype;
            PatchedWebSocket.CONNECTING = Orig.CONNECTING;
            PatchedWebSocket.OPEN = Orig.OPEN;
            PatchedWebSocket.CLOSING = Orig.CLOSING;
            PatchedWebSocket.CLOSED = Orig.CLOSED;
            window.WebSocket = PatchedWebSocket;
          }
          try {
            var content = 'width=1280, initial-scale=0.28, minimum-scale=0.2, maximum-scale=3, user-scalable=yes';
            var metas = document.querySelectorAll('meta[name="viewport"]');
            if (metas && metas.length) metas[0].setAttribute('content', content);
            else if (document.head) {
              var m = document.createElement('meta');
              m.name = 'viewport'; m.content = content;
              document.head.appendChild(m);
            }
          } catch (e2) {}
        })();
    """.trimIndent()
}

private fun String.toJsSingleQuoted(): String =
    buildString(length + 8) {
        for (ch in this@toJsSingleQuoted) {
            when (ch) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> append(ch)
            }
        }
    }
