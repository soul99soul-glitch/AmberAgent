package app.amber.feature.ui.pages.zcode

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
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
        // 远程 ZCode 移动页自身不避让状态栏，WebView 顶部下移，否则页头被状态栏盖住、顶部按钮点不到
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    )
}
