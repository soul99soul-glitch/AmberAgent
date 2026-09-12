package app.amber.feature.webmount.primitives

import android.app.Application
import android.webkit.WebView
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SessionHandleLifecycleTest {
    private lateinit var mainDispatcher: QueuedDispatcher

    @Before
    fun setUp() {
        mainDispatcher = QueuedDispatcher()
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `cancelling a load before Main dispatch leaves state idle`() = runTest {
        val handle = newHandle()
        val job = launch {
            handle.loadUrlNoWait("https://example.com")
        }

        // The caller is suspended before the queued Main section can install
        // LOADING or start navigation.
        runCurrent()
        assertEquals(SessionHandle.LoadStatus.IDLE, handle.loadState.value.status)

        job.cancel()

        assertEquals(SessionHandle.LoadStatus.IDLE, handle.loadState.value.status)

        // A cancelled continuation left in the dispatcher must not navigate
        // after cleanup.
        mainDispatcher.runAll()
        runCurrent()
        job.join()
        assertEquals(SessionHandle.LoadStatus.IDLE, handle.loadState.value.status)
    }

    @Test
    fun `cross-origin page callback reports page-finished coverage`() = runTest {
        assumeTrue(
            runCatching {
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            }.getOrDefault(false),
        )
        val handle = newHandle()
        val job = launch {
            handle.loadUrlNoWait("https://first.example")
        }

        runCurrent()
        mainDispatcher.runAll()
        runCurrent()
        job.join()
        // This keeps the test honest on Robolectric versions whose feature
        // probe is true but whose WebView provider cannot install a handler.
        assumeTrue(handle.bridgeInjectionCoverage == "document_start")

        handle.onPageFinished("https://second.example", null)

        assertEquals("page_finished", handle.bridgeInjectionCoverage)
    }

    private fun newHandle(): SessionHandle {
        return SessionHandle(
            sessionId = "test",
            webView = WebView(RuntimeEnvironment.getApplication()),
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
