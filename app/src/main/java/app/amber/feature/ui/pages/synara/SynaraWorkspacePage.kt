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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
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
import app.amber.feature.ui.context.LocalNavController

private const val TAG = "SynaraWorkspace"

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

    SynaraAndroidWebView(
        connection = connection,
        pageUrl = pageUrl,
        onWebViewReady = { webViewRef = it },
        onCanGoBack = { canGoBack = it },
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SynaraAndroidWebView(
    connection: SynaraConnection,
    pageUrl: String,
    onWebViewReady: (WebView) -> Unit,
    onCanGoBack: (Boolean) -> Unit,
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
                        view?.evaluateJavascript(injectScript, null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        onCanGoBack(view?.canGoBack() == true)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            Log.e(TAG, "main frame error ${error?.description}")
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
