package app.amber.feature.webmount.primitives

import android.app.Application
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebViewScreenshotRegionTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun captureRegionClipsPartiallyVisibleBottomRightTargetAfterPadding() = runTest {
        val result = WebViewScreenshot.captureRegion(
            handle = newHandle(),
            region = WebViewScreenshot.Region(x = 400, y = 900, width = 20, height = 30),
            format = WebViewScreenshot.Format.PNG,
            maxEdge = 1_600,
            paddingPx = 8,
            timeoutMs = 1_000,
        )

        assertTrue("Expected partial ROI success, got $result", result is WebViewScreenshot.Result.Success)
        result as WebViewScreenshot.Result.Success
        assertEquals(20, result.width)
        assertEquals(23, result.height)
    }

    @Test
    fun captureRegionKeepsFullyOffscreenTargetAsExplicitFailure() = runTest {
        val result = WebViewScreenshot.captureRegion(
            handle = newHandle(),
            region = WebViewScreenshot.Region(x = -20, y = 100, width = 4, height = 10),
            format = WebViewScreenshot.Format.PNG,
            maxEdge = 1_600,
            paddingPx = 8,
            timeoutMs = 1_000,
        )

        assertTrue(result is WebViewScreenshot.Result.Failed)
        assertTrue((result as WebViewScreenshot.Result.Failed).message.contains("outside the visible viewport"))
    }

    @Test
    fun captureRegionPropagatesCallerCancellation() = runTest {
        val mainDispatcher = QueuedDispatcher()
        Dispatchers.setMain(mainDispatcher)
        val completed = CompletableDeferred<WebViewScreenshot.Result>()
        val job = launch {
            completed.complete(
                WebViewScreenshot.captureRegion(
                    handle = newHandle(),
                    region = WebViewScreenshot.Region(x = 10, y = 10, width = 20, height = 20),
                    timeoutMs = 1_000,
                ),
            )
        }

        runCurrent()
        assertFalse(completed.isCompleted)

        job.cancel()
        mainDispatcher.runAll()
        runCurrent()
        job.join()

        assertFalse(completed.isCompleted)
    }

    private fun newHandle(): SessionHandle {
        val webView = WebView(RuntimeEnvironment.getApplication()).apply {
            right = 412
            bottom = 915
        }
        assertEquals("WebView width must be laid out for ROI tests", 412, webView.width)
        assertEquals("WebView height must be laid out for ROI tests", 915, webView.height)
        return SessionHandle(
            sessionId = "test",
            webView = webView,
            jsBridge = JsBridge("test"),
            bridgeBootstrapJs = "",
        )
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

        fun runAll() {
            while (queue.isNotEmpty()) {
                queue.removeFirst().run()
            }
        }
    }
}
