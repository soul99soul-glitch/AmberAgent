package me.rerere.rikkahub.data.agent.webmount.primitives

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/**
 * Render a headless [WebView] to a [Bitmap] without it being attached to a
 * real Window.
 *
 *  - Visible area: capture the current viewport at the WebView's
 *    layout dimensions (set by [WebViewPool] to 412×915 by default).
 *  - Full page: scroll + repeat capture, then stitch the slices into a
 *    tall bitmap. The last slice is clipped to whatever remained.
 *
 * WebViews backed by hardware acceleration may render black when `.draw(canvas)`
 * is called on a software-mode canvas. We fall back to forcing the WebView's
 * `layerType` to [View.LAYER_TYPE_SOFTWARE] for the duration of the capture
 * and restoring the original type afterwards.
 */
object WebViewScreenshot {

    /**
     * Capture [handle]'s current view. If [fullPage] is true, scroll through the
     * document and stitch. PNG vs JPEG is selectable via [format]; JPEG honors
     * [quality] (1–100).
     */
    suspend fun capture(
        handle: SessionHandle,
        fullPage: Boolean,
        format: Format = Format.PNG,
        quality: Int = 85,
        timeoutMs: Long = 30_000L,
    ): Result {
        val bitmap = withTimeoutOrNull(timeoutMs) {
            if (fullPage) captureFullPage(handle) else captureViewport(handle)
        } ?: return Result.Failed("screenshot timed out after ${timeoutMs}ms")

        val bytes = encode(bitmap, format, quality)
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return Result.Success(
            base64 = base64,
            width = bitmap.width,
            height = bitmap.height,
            format = format.wireName,
            sizeBytes = bytes.size,
        ).also { bitmap.recycle() }
    }

    // ----------------------------------------------------------- viewport

    private suspend fun captureViewport(handle: SessionHandle): Bitmap = withContext(Dispatchers.Main) {
        val webView = handle.webView
        val width = webView.width.coerceAtLeast(1)
        val height = webView.height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawWithSoftwareFallback(webView, Canvas(bitmap))
        bitmap
    }

    // ---------------------------------------------------------- full page

    private suspend fun captureFullPage(handle: SessionHandle): Bitmap = withContext(Dispatchers.Main) {
        val webView = handle.webView
        val viewportW = webView.width.coerceAtLeast(1)
        val viewportH = webView.height.coerceAtLeast(1)
        // Read total content height via JS — WebView.contentHeight reports CSS
        // pixels and is not always accurate for dynamic pages.
        val totalHeight = withTimeoutOrNull(2_000L) {
            queryContentHeight(handle)
        } ?: webView.contentHeight.coerceAtLeast(viewportH)
        val cappedHeight = totalHeight.coerceAtMost(MAX_FULL_PAGE_HEIGHT)
        if (cappedHeight <= viewportH) {
            return@withContext drawViewportBitmap(webView, viewportW, viewportH)
        }
        val target = Bitmap.createBitmap(viewportW, cappedHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        var offset = 0
        val originalScrollY = webView.scrollY
        try {
            while (offset < cappedHeight) {
                val sliceHeight = minOf(viewportH, cappedHeight - offset)
                webView.scrollTo(0, offset)
                // Give the WebView a moment to react to the scroll (sticky
                // headers, intersection observers).
                awaitFrameSettle(webView)
                val sliceBitmap = drawViewportBitmap(webView, viewportW, viewportH)
                canvas.save()
                canvas.translate(0f, offset.toFloat())
                canvas.drawBitmap(sliceBitmap, 0f, 0f, null)
                canvas.restore()
                sliceBitmap.recycle()
                offset += sliceHeight
            }
        } finally {
            webView.scrollTo(0, originalScrollY)
        }
        target
    }

    private fun drawViewportBitmap(webView: WebView, w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawWithSoftwareFallback(webView, Canvas(bitmap))
        return bitmap
    }

    /**
     * Draw the WebView into [canvas]. Headless WebViews (not attached to a
     * Window) plus hardware acceleration plus our software-only Bitmap canvas
     * is a known sad path — many Android builds render black to the bitmap
     * even though `webView.draw()` returns without throwing. To make capture
     * reliable, temporarily switch the WebView into LAYER_TYPE_SOFTWARE for
     * the draw, then restore the original layer type.
     */
    private fun drawWithSoftwareFallback(webView: WebView, canvas: Canvas) {
        val originalLayerType = webView.layerType
        if (originalLayerType != View.LAYER_TYPE_SOFTWARE) {
            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
        try {
            webView.draw(canvas)
        } catch (error: Throwable) {
            Log.w(TAG, "webView.draw() failed even in software mode", error)
        } finally {
            if (originalLayerType != View.LAYER_TYPE_SOFTWARE) {
                webView.setLayerType(originalLayerType, null)
            }
        }
    }

    /**
     * Trigger one VSYNC + draw cycle by toggling layer type. Lets the WebView
     * finish any post-scroll layout before the next slice is grabbed.
     */
    private suspend fun awaitFrameSettle(webView: WebView) {
        val deferred = CompletableDeferred<Unit>()
        webView.post {
            // Two posts back-to-back: first to consume the current frame, second
            // to let onDraw flush. Good enough for the M1.4 native impl —
            // M1.5+ may refine with explicit doFrame() if sticky elements ghost.
            webView.post { deferred.complete(Unit) }
        }
        withTimeoutOrNull(500L) { deferred.await() }
    }

    /** Read `document.documentElement.scrollHeight` via the bridge. */
    private suspend fun queryContentHeight(handle: SessionHandle): Int? {
        val raw = handle.evalRaw("(function(){return document.documentElement.scrollHeight | 0;})();", timeoutMs = 1_500L)
            ?: return null
        return raw.trim().trim('"').toIntOrNull()
    }

    // -------------------------------------------------------------- encode

    private fun encode(bitmap: Bitmap, format: Format, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        when (format) {
            Format.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            Format.JPEG -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), stream)
        }
        return stream.toByteArray()
    }

    // -------------------------------------------------------------- types

    enum class Format(val wireName: String) {
        PNG("png"),
        JPEG("jpeg"),
    }

    sealed class Result {
        data class Success(
            val base64: String,
            val width: Int,
            val height: Int,
            val format: String,
            val sizeBytes: Int,
        ) : Result()

        data class Failed(val message: String) : Result()
    }

    private const val TAG = "WebMountScreenshot"
    private const val MAX_FULL_PAGE_HEIGHT = 16_384
}
