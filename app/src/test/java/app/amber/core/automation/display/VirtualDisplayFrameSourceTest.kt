package app.amber.core.automation.display

import android.app.Application
import android.graphics.SurfaceTexture
import android.os.Handler
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VirtualDisplayFrameSourceTest {

    @Test
    fun enforcesMaxImagesEqualToTwo() {
        assertEquals(2, VirtualDisplayFrameSource.MAX_IMAGES)
        val badPort = FakeFrameReaderPort(maxImages = 4)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                VirtualDisplayFrameSource(width = 8, height = 8, readerPort = badPort, ownWorkerThread = false)
            }
        } finally {
            badPort.close()
        }
    }

    @Test
    fun listenerRegistrationFailureReleasesTheReader() {
        val port = FakeFrameReaderPort(maxImages = 2)
        port.failRegistration = true
        assertThrows(IllegalStateException::class.java) {
            VirtualDisplayFrameSource(4, 4, port, ownWorkerThread = false)
        }
        assertTrue(port.closed)
    }

    @Test
    fun cropsStridePaddingCorrectlyAndDetectsMarkerAndUnobservableFrames() {
        val port = FakeFrameReaderPort(maxImages = 2)
        val source = VirtualDisplayFrameSource(width = 4, height = 4, readerPort = port, ownWorkerThread = false)
        try {
            // 1. All-black frame with rowStride padding (rowStride = 24 bytes for 4px * 4bpp = 16 bytes + 8 bytes padding).
            val blackPlane = buildPaddedRgbaPlane(width = 4, height = 4, rowPaddingBytes = 8) { _, _ ->
                0xFF000000.toInt()
            }
            port.nextPlane = blackPlane
            port.triggerAvailable()

            val blackStats = source.latestStats.value
            assertNotNull(blackStats)
            assertTrue(blackStats!!.unobservable)
            assertEquals(1L, blackStats.sequence)

            // 2. Colorful frame with top half marker 0xFFE63946 and bottom half 0xFF457B9D + stride padding.
            val markerColor = 0xFFE63946.toInt()
            val otherColor = 0xFF457B9D.toInt()
            val colorPlane = buildPaddedRgbaPlane(width = 4, height = 4, rowPaddingBytes = 12) { _, y ->
                if (y < 2) markerColor else otherColor
            }
            port.nextPlane = colorPlane
            port.triggerAvailable()

            val colorStats = source.latestStats.value!!
            assertFalse(colorStats.unobservable)
            assertEquals(2L, colorStats.sequence)
            assertTrue(source.containsMarkerColor(markerColor))
            assertFalse(source.containsMarkerColor(0xFF00FF00.toInt()))

            val pixels = source.copyLatestArgbPixels()!!
            assertEquals(16, pixels.size)
            assertEquals(markerColor, pixels[0])
            assertEquals(markerColor, pixels[3])
            assertEquals(otherColor, pixels[8])
            assertEquals(otherColor, pixels[15])
        } finally {
            source.close()
        }
    }

    @Test
    fun closeStopsAllCallbacksAndReleasesReaderWithoutResidualDelivery() {
        val port = FakeFrameReaderPort(maxImages = 2)
        val source = VirtualDisplayFrameSource(width = 4, height = 4, readerPort = port, ownWorkerThread = false)
        val delivered = AtomicInteger(0)
        source.setOnFrameListener { delivered.incrementAndGet() }

        port.nextPlane = buildPaddedRgbaPlane(4, 4, 0) { x, _ ->
            if (x % 2 == 0) 0xFFFF0000.toInt() else 0xFF0000FF.toInt()
        }
        port.triggerAvailable()
        assertEquals(1, delivered.get())

        // Close must clear listener, close underlying reader, and suppress any late callbacks.
        source.close()
        assertTrue(source.isClosed)
        assertTrue(port.closed)
        assertNull(port.registeredListener)
        assertNull(source.copyLatestBitmap())

        // Even if a stale callback fires directly after close(), zero listener deliveries happen.
        source.onImageAvailable()
        assertEquals(1, delivered.get())
        assertEquals(0, port.droppedCount) // A queued callback must not touch the closed reader.
        assertNull(source.latestStats.value)
    }

    @Test
    fun dropsReEntrantFrameWhenBusyProcessing() {
        val port = FakeFrameReaderPort(maxImages = 2)
        val source = VirtualDisplayFrameSource(width = 4, height = 4, readerPort = port, ownWorkerThread = false)
        try {
            val plane = buildPaddedRgbaPlane(4, 4, 0) { x, _ ->
                if (x < 2) 0xFFFFAA00.toInt() else 0xFF00AAFF.toInt()
            }
            port.nextPlane = plane
            port.onInsideAcquire = {
                // Simulate producer pushing another frame while the previous one is still being copied.
                source.onImageAvailable()
            }
            port.triggerAvailable()
            assertEquals(1L, source.droppedFrameCount)
            assertEquals(1, port.droppedCount)
        } finally {
            source.close()
        }
    }

    private fun buildPaddedRgbaPlane(
        width: Int,
        height: Int,
        rowPaddingBytes: Int,
        pixelColor: (x: Int, y: Int) -> Int,
    ): RawRgbaPlane {
        val pixelStride = 4
        val rowStride = width * pixelStride + rowPaddingBytes
        val bytes = ByteArray(rowStride * height) { 0x7F.toByte() } // Poison padding bytes.
        for (y in 0 until height) {
            for (x in 0 until width) {
                val argb = pixelColor(x, y)
                val offset = y * rowStride + x * pixelStride
                bytes[offset] = ((argb ushr 16) and 0xFF).toByte() // R
                bytes[offset + 1] = ((argb ushr 8) and 0xFF).toByte() // G
                bytes[offset + 2] = (argb and 0xFF).toByte() // B
                bytes[offset + 3] = ((argb ushr 24) and 0xFF).toByte() // A
            }
        }
        return RawRgbaPlane(
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
            timestampNanos = 123456L,
            buffer = ByteBuffer.wrap(bytes),
        )
    }

    private class FakeFrameReaderPort(
        override val maxImages: Int,
    ) : FrameReaderPort {
        private val texture = SurfaceTexture(20)
        override val surface: Surface = Surface(texture)
        var registeredListener: (() -> Unit)? = null
        var nextPlane: RawRgbaPlane? = null
        var onInsideAcquire: (() -> Unit)? = null
        var closed: Boolean = false
        var droppedCount: Int = 0
        var failRegistration = false

        override fun setListener(handler: Handler?, listener: (() -> Unit)?) {
            if (failRegistration && listener != null) error("reader registration failed")
            registeredListener = listener
        }

        fun triggerAvailable() {
            registeredListener?.invoke()
        }

        override fun acquireLatestPlane(block: (RawRgbaPlane) -> Unit): Boolean {
            val plane = nextPlane ?: return false
            plane.buffer.rewind()
            onInsideAcquire?.invoke()
            block(plane)
            return true
        }

        override fun dropLatestFrame() {
            droppedCount++
        }

        override fun close() {
            closed = true
            registeredListener = null
            surface.release()
            texture.release()
        }
    }
}
