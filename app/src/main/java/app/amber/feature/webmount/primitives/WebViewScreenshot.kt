package app.amber.feature.webmount.primitives

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
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

    suspend fun captureRegion(
        handle: SessionHandle,
        region: Region,
        format: Format = Format.JPEG,
        quality: Int = 70,
        maxEdge: Int = 1280,
        timeoutMs: Long = 15_000L,
        paddingPx: Int = 0,
    ): Result {
        val bitmap = runCatching {
            withTimeoutOrNull(timeoutMs) {
                captureViewportRegion(handle, region, maxEdge, paddingPx)
            } ?: return Result.Failed("region screenshot timed out after ${timeoutMs}ms")
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            return Result.Failed(error.message ?: "region screenshot failed")
        }

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

    private suspend fun captureViewportRegion(
        handle: SessionHandle,
        region: Region,
        maxEdge: Int,
        paddingPx: Int,
    ): Bitmap = withContext(Dispatchers.Main) {
        val webView = handle.webView
        val viewportWidth = webView.width.coerceAtLeast(1)
        val viewportHeight = webView.height.coerceAtLeast(1)
        val captureBounds = captureBounds(region, viewportWidth, viewportHeight, paddingPx)
            ?: error("region is outside the visible viewport; scroll the target into view or pass a visible region")
        val viewport = drawViewportBitmap(webView, viewportWidth, viewportHeight)
        val cropped = Bitmap.createBitmap(
            viewport,
            captureBounds.x,
            captureBounds.y,
            captureBounds.width,
            captureBounds.height,
        )
        viewport.recycle()
        scaleDown(cropped, maxEdge.coerceAtLeast(64))
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

    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val currentMax = maxOf(bitmap.width, bitmap.height)
        if (currentMax <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / currentMax.toFloat()
        val targetW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        bitmap.recycle()
        return scaled
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

    data class Region(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    /**
     * Return the viewport-clipped capture bounds for [region]. The original
     * region must intersect the viewport; padding may then extend beyond an
     * edge and is clipped back to the viewport.
     */
    internal fun captureBounds(
        region: Region,
        viewportWidth: Int,
        viewportHeight: Int,
        paddingPx: Int = 0,
    ): Region? {
        if (viewportWidth <= 0 || viewportHeight <= 0 || region.width <= 0 || region.height <= 0) return null
        val rawLeft = region.x.toLong()
        val rawTop = region.y.toLong()
        val rawRight = rawLeft + region.width.toLong()
        val rawBottom = rawTop + region.height.toLong()
        val visibleLeft = maxOf(rawLeft, 0L)
        val visibleTop = maxOf(rawTop, 0L)
        val visibleRight = minOf(rawRight, viewportWidth.toLong())
        val visibleBottom = minOf(rawBottom, viewportHeight.toLong())
        if (visibleLeft >= visibleRight || visibleTop >= visibleBottom) return null

        val padding = paddingPx.coerceAtLeast(0).toLong()
        val captureLeft = maxOf(rawLeft - padding, 0L)
        val captureTop = maxOf(rawTop - padding, 0L)
        val captureRight = minOf(rawRight + padding, viewportWidth.toLong())
        val captureBottom = minOf(rawBottom + padding, viewportHeight.toLong())
        return Region(
            x = captureLeft.toInt(),
            y = captureTop.toInt(),
            width = (captureRight - captureLeft).toInt(),
            height = (captureBottom - captureTop).toInt(),
        )
    }

    private const val TAG = "WebMountScreenshot"
    private const val MAX_FULL_PAGE_HEIGHT = 16_384
}
