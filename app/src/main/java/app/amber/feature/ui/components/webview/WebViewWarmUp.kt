package app.amber.feature.ui.components.webview

import android.content.Context
import android.util.Log
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewStartUpConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Starts WebView's provider off the main thread before a screen is about to mount its first
 * WebView. Loading the provider and starting Chromium otherwise run inside the frame that creates
 * the first WebView in the process (~75 ms on 25128PNA1C). Later calls are no-ops.
 */
internal object WebViewWarmUp {
    private val requested = AtomicBoolean(false)

    @androidx.annotation.OptIn(WebViewCompat.ExperimentalAsyncStartUp::class)
    fun request(context: Context) {
        if (!requested.compareAndSet(false, true)) return
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "WebViewWarmUp") }
        val config = WebViewStartUpConfig.Builder(executor)
            .setShouldRunUiThreadStartUpTasks(true)
            .build()
        runCatching {
            WebViewCompat.startUpWebView(context.applicationContext, config) { executor.shutdown() }
        }.onFailure { error ->
            executor.shutdown()
            Log.w("WebViewWarmUp", "WebView warm-up failed; the first WebView starts on demand", error)
        }
    }
}
