package app.amber.feature.ui.components.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.webkit.WebView as AndroidWebView
import app.amber.feature.webview.WebViewLink
import app.amber.feature.webview.WebViewOperationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

private val readablePageParseScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))
private val thumbnailWriteScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1))
private const val THUMBNAIL_MAX_WIDTH_PX = 540

internal fun extractReadablePage(
    webView: AndroidWebView,
    store: WebViewOperationStore,
    loadId: String,
    force: Boolean = false,
) {
    if (!force && !store.shouldExtractReadablePage(loadId, webView.url)) return
    val script = """
        (function() {
          const links = Array.from(document.querySelectorAll('a[href]')).slice(0, 40).map((a) => {
            let href = '';
            try { href = new URL(a.getAttribute('href'), location.href).href; } catch (_) { href = a.href || ''; }
            const title = (a.innerText || a.textContent || href || '').trim().replace(/\s+/g, ' ').slice(0, 160);
            return { title, url: href };
          }).filter((item) => item.url);
          return JSON.stringify({
            title: document.title || '',
            url: location.href,
            text: ((document.body && document.body.innerText) || '').slice(0, 40000),
            links
          });
        })();
    """.trimIndent()
    webView.post {
        webView.evaluateJavascript(script) { raw ->
            val callbackUrl = webView.url
            readablePageParseScope.launch {
                runCatching {
                    if (raw.isNullOrBlank() || raw == "null") return@runCatching
                    val decoded = JSONArray("[$raw]").getString(0)
                    val payload = JSONObject(decoded)
                    val linksJson = payload.optJSONArray("links")
                    val links = buildList {
                        if (linksJson != null) {
                            for (index in 0 until linksJson.length()) {
                                val item = linksJson.optJSONObject(index) ?: continue
                                val linkUrl = item.optString("url").trim()
                                if (linkUrl.isBlank()) continue
                                add(
                                    WebViewLink(
                                        title = item.optString("title").ifBlank { linkUrl },
                                        url = linkUrl,
                                    )
                                )
                            }
                        }
                    }
                    val pageUrl = payload.optString("url").ifBlank { callbackUrl }
                    val title = payload.optString("title")
                    val readableText = payload.optString("text")
                    withContext(Dispatchers.Main.immediate) {
                        store.updateReadablePage(
                            loadId = loadId,
                            url = pageUrl,
                            title = title,
                            readableText = readableText,
                            links = links,
                        )
                    }
                }.onFailure {
                    Log.w("ChatInput", "Failed to extract WebView readable content", it)
                }
            }
        }
    }
}

internal fun scheduleReadableExtracts(
    webView: AndroidWebView,
    store: WebViewOperationStore,
    loadId: String,
) {
    webView.postDelayed({ extractReadablePage(webView, store, loadId) }, 1_500L)
    webView.postDelayed({ extractReadablePage(webView, store, loadId) }, 3_000L)
}

internal fun scheduleThumbnailCaptures(
    webView: AndroidWebView,
    store: WebViewOperationStore,
    context: android.content.Context,
    loadId: String,
    cacheUrl: String,
) {
    captureWebViewThumbnail(webView, store, context, loadId, cacheUrl, delayMillis = 800L)
    captureWebViewThumbnail(webView, store, context, loadId, cacheUrl, delayMillis = 5_000L)
}

internal fun captureWebViewThumbnail(
    webView: AndroidWebView,
    store: WebViewOperationStore,
    context: android.content.Context,
    loadId: String,
    cacheUrl: String,
    delayMillis: Long = 500L,
    force: Boolean = false,
) {
    val appContext = context.applicationContext
    webView.postDelayed({
        runCatching {
            if (!store.shouldCaptureThumbnail(loadId, webView.url, force = force)) return@runCatching
            val width = webView.width
            val height = webView.height
            if (width <= 0 || height <= 0) return@runCatching

            // Only drawing has to happen on the main thread. Draw the 4:3 crop straight into a
            // thumbnail-sized bitmap, then scan, encode and write it off the main thread.
            val cropWidth = minOf(width, height * 4 / 3)
            val cropHeight = minOf(height, width * 3 / 4)
            val cropX = ((width - cropWidth) / 2).coerceAtLeast(0)
            val scale = minOf(1f, THUMBNAIL_MAX_WIDTH_PX.toFloat() / cropWidth)
            val bitmap = Bitmap.createBitmap(
                (cropWidth * scale).roundToInt().coerceAtLeast(1),
                (cropHeight * scale).roundToInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            Canvas(bitmap).apply {
                scale(scale, scale)
                translate(-cropX.toFloat(), 0f)
                webView.draw(this)
            }
            val pageUrl = webView.url
            thumbnailWriteScope.launch {
                runCatching {
                    if (bitmap.looksBlank()) return@runCatching
                    val output = WebPreviewThumbnails.write(appContext, cacheUrl, bitmap)
                    withContext(Dispatchers.Main.immediate) {
                        store.updateThumbnail(loadId, pageUrl, output.absolutePath)
                    }
                }.onFailure {
                    Log.w("ChatInput", "Failed to write WebView thumbnail", it)
                }
                bitmap.recycle()
            }
        }.onFailure {
            Log.w("ChatInput", "Failed to capture WebView thumbnail", it)
        }
    }, delayMillis)
}

internal fun Bitmap.looksBlank(): Boolean {
    if (width <= 0 || height <= 0) return true
    val xSamples = 12
    val ySamples = 12
    var opaqueSamples = 0
    var minRed = 255
    var minGreen = 255
    var minBlue = 255
    var maxRed = 0
    var maxGreen = 0
    var maxBlue = 0
    for (yIndex in 0 until ySamples) {
        val y = ((height - 1) * yIndex / (ySamples - 1).coerceAtLeast(1)).coerceIn(0, height - 1)
        for (xIndex in 0 until xSamples) {
            val x = ((width - 1) * xIndex / (xSamples - 1).coerceAtLeast(1)).coerceIn(0, width - 1)
            val pixel = getPixel(x, y)
            if (android.graphics.Color.alpha(pixel) <= 8) continue
            opaqueSamples++
            val red = android.graphics.Color.red(pixel)
            val green = android.graphics.Color.green(pixel)
            val blue = android.graphics.Color.blue(pixel)
            minRed = minOf(minRed, red)
            minGreen = minOf(minGreen, green)
            minBlue = minOf(minBlue, blue)
            maxRed = maxOf(maxRed, red)
            maxGreen = maxOf(maxGreen, green)
            maxBlue = maxOf(maxBlue, blue)
        }
    }
    if (opaqueSamples == 0) return true
    val channelRange = maxOf(maxRed - minRed, maxGreen - minGreen, maxBlue - minBlue)
    return channelRange < 8 && minRed > 245 && minGreen > 245 && minBlue > 245
}
