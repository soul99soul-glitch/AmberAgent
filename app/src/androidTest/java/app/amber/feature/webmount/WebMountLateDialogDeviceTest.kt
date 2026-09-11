package app.amber.feature.webmount

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.feature.webmount.primitives.SessionHandle
import app.amber.feature.webmount.primitives.WebMountOwner
import app.amber.feature.webmount.primitives.WebMountSessionOwner
import app.amber.feature.webmount.primitives.WebMountLeaseResult
import app.amber.feature.webmount.primitives.WebViewPool
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real WebView callback ordering around a cancelled run.
 * No production callback/test hook is injected: the page schedules its own
 * delayed confirm through the existing bridge, then the owner run ends before
 * Android delivers WebChromeClient.onJsConfirm.
 */
@RunWith(AndroidJUnit4::class)
class WebMountLateDialogDeviceTest {
    private lateinit var pool: WebViewPool
    private lateinit var owner: WebMountSessionOwner
    private lateinit var handle: SessionHandle

    @Before
    fun setUp() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        pool = WebViewPool(
            instrumentation.targetContext,
            onSessionCreated = { owner.onPoolSessionCreated(it) },
            onSessionDestroyed = { owner.onPoolSessionDestroyed(it) },
            onSessionStateChanged = { id, state -> owner.onPoolSessionStateChanged(id, state) },
        )
        owner = WebMountSessionOwner(instrumentation.targetContext, pool)
        handle = pool.acquireNew()
        val fixtureUrl = InstrumentationRegistry.getArguments().getString("webmountFixtureUrl")
            ?: "http://127.0.0.1:18765/index.html"
        assertEquals(SessionHandle.LoadStatus.READY, handle.loadUrl(fixtureUrl).status)
    }

    @After
    fun tearDown() = runBlocking {
        if (::owner.isInitialized && ::handle.isInitialized) {
            owner.close(handle.sessionId, "late dialog test finished")
        }
        if (::pool.isInitialized) pool.destroyAll("late dialog test finished")
    }

    @Test
    fun delayedDialogArrivingAfterRunEndIsCancelledAsOldRun() = runBlocking {
        val conversationId = "late-dialog-conversation"
        val runId = "late-dialog-run"
        val lease = (owner.acquire(
            sessionId = handle.sessionId,
            actor = WebMountOwner.AGENT,
            conversationId = conversationId,
            runId = runId,
        ) as WebMountLeaseResult.Granted).lease
        val dispatch = { action: () -> Unit ->
            owner.dispatchIfActive(
                leaseId = lease.leaseId,
                conversationId = conversationId,
                runId = runId,
                action = action,
            )
        }

        // `eval` returns as soon as the timer is registered. The actual
        // confirm callback is delivered by WebChromeClient later, after the
        // run has been ended below.
        handle.callBridge(
            method = "eval",
            args = buildJsonObject {
                put(
                    "expression",
                    "setTimeout(function(){window.lateDialogResult=confirm('late cancelled dialog');}, 350)",
                )
            },
            timeoutMs = 2_000L,
            dispatchWithLease = dispatch,
        )
        owner.endRun(runId, conversationId)

        // Let the timer fire and give Android's WebChrome callback enough
        // time to reach the real SessionHandle. A correctly cancelled old run
        // remains empty; an escaped callback makes the assertion below fail.
        delay(800L)
        // A failure here means Android delivered the callback after endRun,
        // and the untagged dialog escaped old-run cleanup.
        assertTrue("late dialog must not survive cancelled run", handle.jsDialogs.value.isEmpty())
        assertEquals("false", handle.evalRaw("window.lateDialogResult"))
    }
}
