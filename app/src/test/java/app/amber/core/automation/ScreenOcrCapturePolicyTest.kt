package app.amber.core.automation

import android.app.Application
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/**
 * Production frame policy for OCR:
 * - never decode `ScreenCaptureManager.lastResult` (history can be arbitrarily old);
 * - request a new frame only while a MediaProjection session is already active;
 * - degrade to unavailable on failure, but never swallow coroutine cancellation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScreenOcrCapturePolicyTest {

    private fun pngFrame(width: Int = 20, height: Int = 10): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val file = File.createTempFile("amber-ocr-frame", ".png")
        file.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        bitmap.recycle()
        return file
    }

    private fun captureResult(file: File, width: Int = 20, height: Int = 10) =
        ScreenCaptureResult(
            file = file,
            width = width,
            height = height,
            sizeBytes = file.length(),
            createdAt = Instant.now(),
        )

    private fun lowCoverageSnapshot() = ScreenSnapshot(
        id = "capture-policy",
        packageName = "com.example.webview",
        windowId = 1,
        nodes = listOf(
            ScreenNode(
                ref = "0/0",
                label = "",
                viewId = "id/0/0",
                className = "android.webkit.WebView",
                left = 0,
                top = 0,
                right = 1000,
                bottom = 2000,
                clickable = false,
                editable = false,
                scrollable = false,
                enabled = true,
            ),
        ),
    )

    @Test
    fun noActiveSessionNeverRequestsCaptureEvenWhenHistoryCouldExist() = runTest {
        var captureCalls = 0
        val staleFile = pngFrame()

        val captured = captureFreshBitmap(
            hasActiveSession = { false },
            captureNewFrame = {
                captureCalls++
                captureResult(staleFile)
            },
            accessibilityScreenshot = null,
            fallbackWidth = 1000,
            fallbackHeight = 2000,
        )

        assertNull(captured)
        assertEquals(0, captureCalls)
        staleFile.delete()
    }

    @Test
    fun activeSessionRequestsExactlyOneFreshFrameAndDecodesIt() = runTest {
        var captureCalls = 0
        val file = pngFrame()

        val captured = captureFreshBitmap(
            hasActiveSession = { true },
            captureNewFrame = {
                captureCalls++
                captureResult(file)
            },
            accessibilityScreenshot = null,
            fallbackWidth = 1000,
            fallbackHeight = 2000,
        )

        assertNotNull(captured)
        assertEquals(1, captureCalls)
        assertEquals("media_projection", captured?.source)
        assertEquals(20, captured?.bitmap?.width)
        assertEquals(10, captured?.bitmap?.height)
        captured?.bitmap?.recycle()
        file.delete()
    }

    @Test
    fun captureFailureDegradesToUnavailable() = runTest {
        val captured = captureFreshBitmap(
            hasActiveSession = { true },
            captureNewFrame = { error("projection failed") },
            accessibilityScreenshot = null,
            fallbackWidth = 1000,
            fallbackHeight = 2000,
        )

        assertNull(captured)
    }

    @Test
    fun callerCancellationDuringCapturePropagatesWithoutDecoding() = runTest {
        val started = CompletableDeferred<Unit>()
        var completedWithoutCancellation = false
        val job = launch {
            captureFreshBitmap(
                hasActiveSession = { true },
                captureNewFrame = {
                    started.complete(Unit)
                    awaitCancellation()
                },
                accessibilityScreenshot = null,
                fallbackWidth = 1000,
                fallbackHeight = 2000,
            )
            completedWithoutCancellation = true
        }
        started.await()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertFalse(completedWithoutCancellation)
    }

    @Test
    fun callerCancellationDuringAccessibilityScreenshotPropagates() = runTest {
        val started = CompletableDeferred<Unit>()
        var completedWithoutCancellation = false
        val job = launch {
            captureFreshBitmap(
                hasActiveSession = { true },
                captureNewFrame = { error("must not be reached") },
                accessibilityScreenshot = {
                    started.complete(Unit)
                    awaitCancellation()
                },
                fallbackWidth = 1000,
                fallbackHeight = 2000,
            )
            completedWithoutCancellation = true
        }
        started.await()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertFalse(completedWithoutCancellation)
    }

    @Test
    fun accessibilityCancellationDoesNotFallThroughToMediaProjection() = runTest {
        var captureCalls = 0
        var completedWithoutCancellation = false
        val job = launch {
            captureFreshBitmap(
                hasActiveSession = { true },
                captureNewFrame = {
                    captureCalls++
                    error("must not capture after accessibility cancellation")
                },
                accessibilityScreenshot = {
                    throw CancellationException("a11y screenshot cancelled")
                },
                fallbackWidth = 1000,
                fallbackHeight = 2000,
            )
            completedWithoutCancellation = true
        }
        job.join()

        assertTrue(job.isCancelled)
        assertFalse(completedWithoutCancellation)
        assertEquals(0, captureCalls)
    }

    @Test
    fun providerInternalCancellationWhileCallerActiveDegrades() = runTest {
        var captureCalls = 0

        val captured = captureFreshBitmap(
            hasActiveSession = { true },
            captureNewFrame = {
                captureCalls++
                throw CancellationException("capture timeout")
            },
            accessibilityScreenshot = null,
            fallbackWidth = 1000,
            fallbackHeight = 2000,
        )

        assertNull(captured)
        assertEquals(1, captureCalls)
    }

    @Test
    fun accessibilityScreenshotFailureFallsBackToFreshActiveSessionFrame() = runTest {
        var captureCalls = 0
        val file = pngFrame()

        val captured = captureFreshBitmap(
            hasActiveSession = { true },
            captureNewFrame = {
                captureCalls++
                captureResult(file)
            },
            accessibilityScreenshot = { error("screenshot unavailable") },
            fallbackWidth = 1000,
            fallbackHeight = 2000,
        )

        assertNotNull(captured)
        assertEquals(1, captureCalls)
        assertEquals("media_projection", captured?.source)
        captured?.bitmap?.recycle()
        file.delete()
    }

    @Test
    fun accessibilityScreenshotIsPreferredOverMediaProjection() = runTest {
        var captureCalls = 0
        val accessibilityBitmap = Bitmap.createBitmap(30, 15, Bitmap.Config.ARGB_8888)

        val captured = captureFreshBitmap(
            hasActiveSession = { true },
            captureNewFrame = {
                captureCalls++
                error("must not capture when accessibility screenshot is available")
            },
            accessibilityScreenshot = { accessibilityBitmap },
            fallbackWidth = 1000,
            fallbackHeight = 2000,
        )

        assertEquals(0, captureCalls)
        assertEquals("accessibility_screenshot", captured?.source)
        assertEquals(30, captured?.bitmap?.width)
        captured?.bitmap?.recycle()
    }

    @Test
    fun productionEngineNeverUsesAStaleLastCapture() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val manager = ScreenCaptureManager(context)
        val staleFile = pngFrame()
        val staleResult = captureResult(staleFile)
        val field = ScreenCaptureManager::class.java.getDeclaredField("lastResult")
        field.isAccessible = true
        field.set(manager, staleResult)

        val engine = DefaultScreenOcrEngine(context, manager)
        try {
            val observation = engine.observe(snapshot = lowCoverageSnapshot(), mode = ScreenOcrMode.ON)

            assertTrue(observation.triggered)
            assertNull(observation.screenshotSource)
            assertEquals(ScreenOcrOutcome.UNAVAILABLE, observation.outcome)
            assertTrue(observation.blocks.isEmpty())
            assertTrue(observation.diagnostics.contains("screenshot_unavailable"))
            assertFalse(observation.diagnostics.any { it.contains("media_projection") })
        } finally {
            engine.close()
            staleFile.delete()
        }
    }
}
