package app.amber.core.automation.display

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

data class VirtualDisplayFrameStats(
    val sequence: Long,
    val width: Int,
    val height: Int,
    val timestampNanos: Long,
    val unobservable: Boolean,
    val nonBlackRatio: Float,
    val distinctSampleColors: Int,
)

internal data class RawRgbaPlane(
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val pixelStride: Int,
    val timestampNanos: Long,
    val buffer: ByteBuffer,
)

internal interface FrameReaderPort : Closeable {
    val maxImages: Int
    val surface: Surface
    fun setListener(handler: Handler?, listener: (() -> Unit)?)
    fun acquireLatestPlane(block: (RawRgbaPlane) -> Unit): Boolean
    fun dropLatestFrame()
}

internal class AndroidImageReaderPort(
    width: Int,
    height: Int,
    override val maxImages: Int,
) : FrameReaderPort {
    private val reader: ImageReader = ImageReader.newInstance(
        width,
        height,
        PixelFormat.RGBA_8888,
        maxImages,
    )

    override val surface: Surface
        get() = reader.surface

    override fun setListener(handler: Handler?, listener: (() -> Unit)?) {
        if (listener == null) {
            runCatching { reader.setOnImageAvailableListener(null, null) }
        } else {
            reader.setOnImageAvailableListener({ listener.invoke() }, handler)
        }
    }

    override fun acquireLatestPlane(block: (RawRgbaPlane) -> Unit): Boolean {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return false
        image.use { img ->
            val plane = img.planes.firstOrNull() ?: return false
            val buf = plane.buffer
            buf.rewind()
            block(
                RawRgbaPlane(
                    width = img.width,
                    height = img.height,
                    rowStride = plane.rowStride,
                    pixelStride = plane.pixelStride,
                    timestampNanos = img.timestamp,
                    buffer = buf,
                ),
            )
            return true
        }
    }

    override fun dropLatestFrame() {
        runCatching { reader.acquireLatestImage()?.close() }
    }

    override fun close() {
        runCatching { reader.setOnImageAvailableListener(null, null) }
        runCatching { reader.close() }
    }
}

/**
 * App-side virtual display frame source backed by a bounded [ImageReader] (`maxImages = 2`)
 * running strictly on a dedicated background [HandlerThread].
 *
 * - Copies & crops RGBA planes by `rowStride` / `pixelStride` into a single reusable working buffer.
 * - Drops stale frames when busy so producer stalls never occur.
 * - Detects all-black / uniform blank frames (`unobservable`) without fabricating FLAG_SECURE truth.
 * - `close()` immediately stops listeners, closes the ImageReader/Surface, quits the HandlerThread,
 *   and guarantees zero callbacks afterwards.
 */
class VirtualDisplayFrameSource internal constructor(
    val width: Int,
    val height: Int,
    private val readerPort: FrameReaderPort,
    private val ownWorkerThread: Boolean,
) : Closeable {

    constructor(
        width: Int = AmberDisplayUserService.DEFAULT_WIDTH,
        height: Int = AmberDisplayUserService.DEFAULT_HEIGHT,
    ) : this(
        width = width,
        height = height,
        readerPort = AndroidImageReaderPort(width, height, MAX_IMAGES),
        ownWorkerThread = true,
    )

    init {
        require(width > 0 && height > 0) { "VirtualDisplayFrameSource requires positive dimensions" }
        require(readerPort.maxImages == MAX_IMAGES) { "ImageReader maxImages must be $MAX_IMAGES" }
    }

    private val workerThread: HandlerThread? = if (ownWorkerThread) {
        HandlerThread("AmberVirtualDisplayFrames", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    } else {
        null
    }
    private val workerHandler: Handler? = workerThread?.let { Handler(it.looper) }

    private val closed = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private val sequence = AtomicLong(0L)
    private val droppedCount = AtomicLong(0L)

    private val bufferLock = Any()
    // Single reusable cropped ARGB_8888 pixel buffer — zero per-frame allocations.
    private val workingPixels = IntArray(width * height)
    private var hasFrame = false

    @Volatile
    private var frameListener: ((VirtualDisplayFrameStats) -> Unit)? = null

    private val _latestStats = MutableStateFlow<VirtualDisplayFrameStats?>(null)
    val latestStats: StateFlow<VirtualDisplayFrameStats?> = _latestStats.asStateFlow()

    val maxImages: Int
        get() = readerPort.maxImages

    val surface: Surface
        get() = readerPort.surface

    val isClosed: Boolean
        get() = closed.get()

    val droppedFrameCount: Long
        get() = droppedCount.get()

    init {
        try {
            readerPort.setListener(workerHandler, ::onImageAvailable)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    @Synchronized
    fun setOnFrameListener(listener: ((VirtualDisplayFrameStats) -> Unit)?) {
        frameListener = if (closed.get()) null else listener
    }

    @Synchronized
    internal fun onImageAvailable() {
        if (closed.get()) return
        if (!busy.compareAndSet(false, true)) {
            droppedCount.incrementAndGet()
            readerPort.dropLatestFrame()
            return
        }
        try {
            if (closed.get()) {
                readerPort.dropLatestFrame()
                return
            }
            var capturedStats: VirtualDisplayFrameStats? = null
            readerPort.acquireLatestPlane { plane ->
                if (closed.get()) return@acquireLatestPlane
                val seq = sequence.incrementAndGet()
                val stats = synchronized(bufferLock) {
                    if (closed.get()) return@synchronized null
                    cropRgbaPlaneIntoArgbBuffer(
                        plane = plane,
                        targetWidth = width,
                        targetHeight = height,
                        outArgb = workingPixels,
                    )
                    hasFrame = true
                    analyzeFrame(
                        argbPixels = workingPixels,
                        width = width,
                        height = height,
                        sequence = seq,
                        timestampNanos = plane.timestampNanos,
                    )
                }
                capturedStats = stats
            }
            val stats = capturedStats ?: return
            if (closed.get()) return
            _latestStats.value = stats
            val listener = frameListener
            if (!closed.get() && listener != null) {
                listener.invoke(stats)
            }
        } finally {
            busy.set(false)
        }
    }

    /**
     * Returns an immutable software [Bitmap] copy of the latest captured frame, or null when closed
     * or before the first frame arrives. Never encodes PNG.
     */
    fun copyLatestBitmap(): Bitmap? = synchronized(bufferLock) {
        if (closed.get() || !hasFrame) return null
        Bitmap.createBitmap(workingPixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Returns a copy of the latest ARGB working buffer for zero-Bitmap JVM verification and diagnostics.
     */
    internal fun copyLatestArgbPixels(): IntArray? = synchronized(bufferLock) {
        if (closed.get() || !hasFrame) return null
        workingPixels.clone()
    }

    /**
     * Checks whether the latest frame contains a solid marker region matching [expectedRgb]
     * (used by [IsolationProbeRunner] to verify virtual display rendering without PNG/OCR overhead).
     */
    fun containsMarkerColor(
        expectedRgb: Int,
        tolerance: Int = 24,
        minMatchingRatio: Float = 0.01f,
    ): Boolean = synchronized(bufferLock) {
        if (closed.get() || !hasFrame) return false
        matchesMarkerColor(workingPixels, expectedRgb, tolerance, minMatchingRatio)
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        frameListener = null
        runCatching { readerPort.setListener(null, null) }
        runCatching { readerPort.close() }
        workerHandler?.removeCallbacksAndMessages(null)
        workerThread?.quitSafely()
        synchronized(bufferLock) {
            hasFrame = false
            _latestStats.value = null
        }
    }

    companion object {
        const val MAX_IMAGES = 2
        private const val BLACK_CHANNEL_THRESHOLD = 10
        private const val UNOBSERVABLE_NON_BLACK_RATIO = 0.005f

        /**
         * Copies an RGBA_8888 [RawRgbaPlane] into [outArgb] (packed ARGB_8888 ints),
         * honoring arbitrary [RawRgbaPlane.rowStride] and [RawRgbaPlane.pixelStride] padding.
         */
        internal fun cropRgbaPlaneIntoArgbBuffer(
            plane: RawRgbaPlane,
            targetWidth: Int,
            targetHeight: Int,
            outArgb: IntArray,
        ) {
            val copyWidth = minOf(targetWidth, plane.width)
            val copyHeight = minOf(targetHeight, plane.height)
            val buffer = plane.buffer
            val basePos = buffer.position()
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride.coerceAtLeast(4)
            val limit = buffer.limit()

            for (y in 0 until copyHeight) {
                val rowStart = basePos + y * rowStride
                val outRow = y * targetWidth
                for (x in 0 until copyWidth) {
                    val idx = rowStart + x * pixelStride
                    if (idx + 3 >= limit) break
                    val r = buffer.get(idx).toInt() and 0xFF
                    val g = buffer.get(idx + 1).toInt() and 0xFF
                    val b = buffer.get(idx + 2).toInt() and 0xFF
                    val a = buffer.get(idx + 3).toInt() and 0xFF
                    outArgb[outRow + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        /**
         * Detects all-black or single-color blank frames (`unobservable = true`) using bounded
         * sub-sampling across the frame so cost stays constant and tiny.
         */
        internal fun analyzeFrame(
            argbPixels: IntArray,
            width: Int,
            height: Int,
            sequence: Long,
            timestampNanos: Long,
        ): VirtualDisplayFrameStats {
            val stepX = (width / 32).coerceAtLeast(1)
            val stepY = (height / 32).coerceAtLeast(1)
            var totalSamples = 0
            var nonBlackSamples = 0
            val distinctBuckets = HashSet<Int>(16)

            var y = 0
            while (y < height) {
                val row = y * width
                var x = 0
                while (x < width) {
                    val pixel = argbPixels[row + x]
                    val r = (pixel ushr 16) and 0xFF
                    val g = (pixel ushr 8) and 0xFF
                    val b = pixel and 0xFF
                    totalSamples++
                    if (r > BLACK_CHANNEL_THRESHOLD || g > BLACK_CHANNEL_THRESHOLD || b > BLACK_CHANNEL_THRESHOLD) {
                        nonBlackSamples++
                    }
                    if (distinctBuckets.size < 16) {
                        // Quantize to 4-bit per channel to ignore tiny compression/dither noise.
                        val bucket = ((r ushr 4) shl 8) or ((g ushr 4) shl 4) or (b ushr 4)
                        distinctBuckets.add(bucket)
                    }
                    x += stepX
                }
                y += stepY
            }

            val nonBlackRatio = if (totalSamples > 0) nonBlackSamples.toFloat() / totalSamples else 0f
            val unobservable = nonBlackRatio < UNOBSERVABLE_NON_BLACK_RATIO || distinctBuckets.size <= 1
            return VirtualDisplayFrameStats(
                sequence = sequence,
                width = width,
                height = height,
                timestampNanos = timestampNanos,
                unobservable = unobservable,
                nonBlackRatio = nonBlackRatio,
                distinctSampleColors = distinctBuckets.size,
            )
        }

        internal fun matchesMarkerColor(
            argbPixels: IntArray,
            expectedRgb: Int,
            tolerance: Int = 24,
            minMatchingRatio: Float = 0.01f,
        ): Boolean {
            if (argbPixels.isEmpty()) return false
            val targetR = (expectedRgb ushr 16) and 0xFF
            val targetG = (expectedRgb ushr 8) and 0xFF
            val targetB = expectedRgb and 0xFF
            val step = (argbPixels.size / 4096).coerceAtLeast(1)
            var sampled = 0
            var matched = 0
            var i = 0
            while (i < argbPixels.size) {
                val pixel = argbPixels[i]
                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF
                sampled++
                if (abs(r - targetR) <= tolerance &&
                    abs(g - targetG) <= tolerance &&
                    abs(b - targetB) <= tolerance
                ) {
                    matched++
                }
                i += step
            }
            return sampled > 0 && (matched.toFloat() / sampled) >= minMatchingRatio
        }
    }
}
