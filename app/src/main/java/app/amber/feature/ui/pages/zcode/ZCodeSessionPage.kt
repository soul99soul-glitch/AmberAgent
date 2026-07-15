package app.amber.feature.ui.pages.zcode

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.amber.feature.ui.components.webview.WebView
import app.amber.feature.ui.components.webview.rememberWebViewState
import app.amber.feature.ui.context.LocalNavController

/**
 * Full-screen ZCode session — no Amber TopAppBar.
 * System back: WebView history first, then leave the session.
 */
@Composable
fun ZCodeSessionPage(url: String) {
    val navController = LocalNavController.current
    val state = rememberWebViewState(
        url = url,
        settings = {
            javaScriptEnabled = true
            domStorageEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
        },
    )

    BackHandler {
        if (state.canGoBack) {
            state.goBack()
        } else {
            navController.popBackStack()
        }
    }

    WebView(
        state = state,
        modifier = Modifier.fillMaxSize(),
    )
}
