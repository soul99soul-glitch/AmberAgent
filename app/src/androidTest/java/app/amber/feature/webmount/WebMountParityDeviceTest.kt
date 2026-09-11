package app.amber.feature.webmount

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.feature.webmount.primitives.SessionHandle
import app.amber.feature.webmount.primitives.WebMountLeaseResult
import app.amber.feature.webmount.primitives.WebMountLeaseInvalidatedException
import app.amber.feature.webmount.primitives.WebMountOwner
import app.amber.feature.webmount.primitives.WebMountSessionOwner
import app.amber.feature.webmount.primitives.WebViewPool
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.webmount.tools.WebMountDeps
import app.amber.feature.webmount.tools.createClickTool
import app.amber.feature.webmount.tools.withWebMountScope
import app.amber.feature.webmount.tools.runVerifiedAction
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android WebView/production JS bridge against scripts/webmount-parity. */
@RunWith(AndroidJUnit4::class)
class WebMountParityDeviceTest {
    private lateinit var pool: WebViewPool
    private lateinit var handle: SessionHandle
    private lateinit var owner: WebMountSessionOwner

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
        assertEquals("Amber 浏览器验收", handle.loadState.value.title)
    }

    @After
    fun tearDown() = runBlocking {
        if (::owner.isInitialized && ::handle.isInitialized) owner.close(handle.sessionId, "device test finished")
        if (::pool.isInitialized) pool.destroyAll("device test finished")
    }

    @Test
    fun snapshotRefreshRejectsOldRefBeforeDispatch() = runBlocking {
        val old = interactive()
        val oldRef = old["nodes"]!!.jsonArray.single {
            it.jsonObject["name"]?.jsonPrimitive?.contentOrNull == "执行一次"
        }.jsonObject["ref"]!!.jsonPrimitive.content
        val fresh = interactive()
        assertFalse(old["snapshot_id"] == fresh["snapshot_id"])

        val result = handle.callBridge("click", buildJsonObject {
            put("target", oldRef)
            put("snapshot_id", old["snapshot_id"]!!)
        }).jsonObject
        assertEquals(false, result["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("0", jsString("document.querySelector('#action-count').dataset.count"))
        val missingIdentity = handle.callBridge("click", buildJsonObject { put("target", oldRef) }).jsonObject
        assertEquals(false, missingIdentity["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("missing_snapshot", missingIdentity["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
        assertEquals("0", jsString("document.querySelector('#action-count').dataset.count"))
    }

    @Test
    fun replacingTargetDoesNotClickNewElementUsingOldIdentity() = runBlocking {
        val snapshot = interactive()
        val ref = snapshot["nodes"]!!.jsonArray.single {
            it.jsonObject["name"]?.jsonPrimitive?.contentOrNull == "执行一次"
        }.jsonObject["ref"]!!.jsonPrimitive.content
        handle.evalRaw("document.querySelector('#replace-target').click()")
        val result = handle.callBridge("click", buildJsonObject {
            put("target", ref)
            put("snapshot_id", snapshot["snapshot_id"]!!)
        }).jsonObject
        assertEquals(false, result["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("0", jsString("document.querySelector('#action-count').dataset.count"))
    }

    @Test
    fun dispatchCanApplyEvenWhenNextBridgeCallFails() = runBlocking {
        val receipt = runVerifiedAction(handle.sessionId, handle) {
            handle.callBridge("click", buildJsonObject {
                put("selector", "#bridge-drop")
                put("visible_only", false)
            })
        }
        assertEquals("unknown", receipt["status"]?.jsonPrimitive?.content)
        assertEquals(true, receipt["dispatched"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, receipt["may_have_applied"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, receipt["goal_verified"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("1", jsString("document.querySelector('#action-count').dataset.count"))
    }

    @Test
    fun existingGoalAndReadablePageDoNotProveActionGoalSucceeded() = runBlocking {
        assertEquals("true", jsString("document.querySelector('#goal').dataset.complete"))
        val receipt = runVerifiedAction(handle.sessionId, handle) {
            handle.callBridge("click", buildJsonObject { put("selector", "#action") })
        }
        assertEquals("dispatched", receipt["status"]?.jsonPrimitive?.content)
        assertEquals(false, receipt["goal_verified"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("1", jsString("document.querySelector('#action-count').dataset.count"))
    }

    @Test
    fun humanHandoffRetainsActualWebViewAndBlocksConcurrentAgent() = runBlocking {
        val agent = owner.acquire(handle.sessionId, WebMountOwner.AGENT, "fixture-conversation", "run-one")
            as WebMountLeaseResult.Granted
        handle.evalRaw("document.querySelector('#draft').value='handoff draft';document.querySelector('#save-draft').click()")
        owner.release(agent.lease.leaseId)
        val human = owner.acquire(handle.sessionId, WebMountOwner.HUMAN, "fixture-conversation")
            as WebMountLeaseResult.Granted
        assertSame(handle, human.lease.handle)
        assertTrue(owner.acquire(handle.sessionId, WebMountOwner.AGENT, "fixture-conversation", "run-two")
            is WebMountLeaseResult.Rejected)
        assertEquals("handoff draft", jsString("document.querySelector('#draft').value"))
        assertEquals("true", jsString("document.cookie.includes('amber_parity=synthetic')"))
        owner.release(human.lease.leaseId)
        val returned = owner.acquire(handle.sessionId, WebMountOwner.AGENT, "fixture-conversation", "run-two")
            as WebMountLeaseResult.Granted
        assertSame(handle, returned.lease.handle)
        owner.release(returned.lease.leaseId)
    }

    @Test
    fun wrongConversationCannotTakeOverBoundSession() = runBlocking {
        val agent = owner.acquire(handle.sessionId, WebMountOwner.AGENT, "owner-conversation", "run-one")
            as WebMountLeaseResult.Granted
        owner.release(agent.lease.leaseId)
        assertTrue(owner.acquire(handle.sessionId, WebMountOwner.AGENT, "other-conversation", "run-two")
            is WebMountLeaseResult.Rejected)
        assertTrue(owner.acquire(handle.sessionId, WebMountOwner.HUMAN, "other-conversation")
            is WebMountLeaseResult.Rejected)
        assertEquals("owner-conversation", owner.metadata(handle.sessionId)?.conversationId)
    }

    @Test
    fun recreatedOwnerRequiresExplicitReopenInsteadOfReturningBlankPage() = runBlocking {
        val lease = owner.acquire(handle.sessionId, WebMountOwner.AGENT, "fixture-conversation", "run-one")
            as WebMountLeaseResult.Granted
        owner.release(lease.lease.leaseId)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val replacementPool = WebViewPool(context)
        try {
            val recreated = WebMountSessionOwner(context, replacementPool)
            assertEquals(true, recreated.metadata(handle.sessionId)?.needsReopen)
            assertTrue(recreated.acquire(handle.sessionId, WebMountOwner.AGENT, "fixture-conversation", "run-two")
                is WebMountLeaseResult.Rejected)
            assertNull(replacementPool.peek(handle.sessionId))
            val reopened = recreated.reopen(handle.sessionId, "fixture-conversation")
                as WebMountLeaseResult.Granted
            assertEquals(SessionHandle.LoadStatus.READY, reopened.lease.handle.loadState.value.status)
            assertEquals("Amber 浏览器验收", reopened.lease.handle.loadState.value.title)
            recreated.release(reopened.lease.leaseId)
        } finally {
            replacementPool.destroyAll("recreated owner test finished")
        }
    }

    @Test
    fun endedRunCannotReacquireWhileNextRunStillWorks() = runBlocking {
        val first = owner.acquire(handle.sessionId, WebMountOwner.AGENT, "fixture-conversation", "ended-run")
            as WebMountLeaseResult.Granted
        owner.release(first.lease.leaseId)
        owner.endRun("ended-run", "fixture-conversation")
        assertTrue(owner.acquire(handle.sessionId, WebMountOwner.AGENT, "fixture-conversation", "ended-run")
            is WebMountLeaseResult.Rejected)
        val next = owner.acquire(handle.sessionId, WebMountOwner.AGENT, "fixture-conversation", "next-run")
            as WebMountLeaseResult.Granted
        assertSame(handle, next.lease.handle)
        owner.release(next.lease.leaseId)
    }

    @Test
    fun physicalBridgeDispatchRejectsEndedLeaseWithoutApplyingAction() = runBlocking {
        val lease = (owner.acquire(handle.sessionId, WebMountOwner.AGENT, "fixture-conversation", "physical-run")
            as WebMountLeaseResult.Granted).lease
        val dispatcher: (() -> Unit) -> Boolean = { action ->
            owner.dispatchIfActive(lease.leaseId, "fixture-conversation", "physical-run", action)
        }
        handle.callBridge("click", buildJsonObject { put("selector", "#action") }, dispatchWithLease = dispatcher)
        assertEquals("1", jsString("document.querySelector('#action-count').dataset.count"))
        owner.endRun("physical-run", "fixture-conversation")
        var rejected = false
        try {
            handle.callBridge("click", buildJsonObject { put("selector", "#action") }, dispatchWithLease = dispatcher)
        } catch (_: WebMountLeaseInvalidatedException) {
            rejected = true
        }
        assertTrue(rejected)
        val human = owner.acquire(handle.sessionId, WebMountOwner.HUMAN, "fixture-conversation")
            as WebMountLeaseResult.Granted
        assertFalse(owner.closeIfLeaseActive(lease.leaseId, "fixture-conversation", "physical-run"))
        assertSame(handle, pool.peek(handle.sessionId))
        assertEquals("1", jsString("document.querySelector('#action-count').dataset.count"))
        owner.release(human.lease.leaseId)
    }

    @Test
    fun cancellationAfterPoolCreationDoesNotLeaveAnOrphanHandle() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var cancelledOwner: WebMountSessionOwner
        var acquisition: Job? = null
        val cancelledPool = WebViewPool(context, onSessionCreated = { created ->
            cancelledOwner.onPoolSessionCreated(created)
            acquisition!!.cancel()
        })
        cancelledOwner = WebMountSessionOwner(context, cancelledPool)
        try {
            coroutineScope {
                acquisition = launch(start = CoroutineStart.LAZY) {
                    cancelledOwner.acquire("wm-cancel-reservation", WebMountOwner.AGENT, "cancel-conversation", "cancel-run")
                }
                acquisition!!.start()
                acquisition!!.join()
            }
            assertNull(cancelledPool.peek("wm-cancel-reservation"))
            assertFalse(cancelledOwner.metadata("wm-cancel-reservation")?.owner == WebMountOwner.AGENT)
        } finally {
            cancelledOwner.close("wm-cancel-reservation")
            cancelledPool.destroyAll("cancel reservation test finished")
        }
    }

    @Test
    fun cancelledReservationAfterReusingLiveHandleKeepsOriginal() = runBlocking {
        handle.evalRaw("document.querySelector('#draft').value='keep original'")
        val reused = pool.acquire(handle.sessionId, reservationToken = "cancel-reused")
        assertSame(handle, reused)
        pool.unpin(handle.sessionId, "cancel-reused")
        pool.release(reused, "cancelled acquire", expectedPinToken = "cancel-reused")
        assertSame(handle, pool.peek(handle.sessionId))
        assertFalse(handle.destroyed)
        assertEquals("keep original", jsString("document.querySelector('#draft').value"))
        assertEquals(false, owner.metadata(handle.sessionId)?.needsReopen)
    }

    @Test
    fun reservedHandleCannotBeEvictedBeforeOwnerPinsIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val smallPool = WebViewPool(context, maxSessions = 1)
        try {
            val reserved = smallPool.acquire("wm-reserved", reservationToken = "reservation-one")
            smallPool.acquire("wm-other")
            assertSame(reserved, smallPool.peek("wm-reserved"))
            assertEquals("2", reserved.evalRaw("1 + 1"))
            smallPool.pin("wm-reserved", "reservation-one")
            assertSame(reserved, smallPool.peek("wm-reserved"))
            smallPool.unpin("wm-reserved", "reservation-one")
            smallPool.acquire("wm-after-release")
            assertEquals(1, smallPool.listSessions().size)
            assertNull(smallPool.peek("wm-reserved"))
        } finally {
            smallPool.destroyAll("reservation eviction test finished")
        }
    }

    @Test
    fun confirmDialogCancellationReturnsToSamePage() = runBlocking {
        val human = owner.acquire(handle.sessionId, WebMountOwner.HUMAN, "fixture-conversation")
            as WebMountLeaseResult.Granted
        coroutineScope {
            val click = async {
                handle.callBridge("click", buildJsonObject {
                    put("selector", "#confirm")
                    put("visible_only", false)
                }, timeoutMs = 10_000)
            }
            val dialog = withTimeout(5_000) { handle.jsDialogs.first { it.isNotEmpty() } }.single()
            handle.resolveJsDialog(dialog.dialogId, confirmed = false)
            assertEquals(true, click.await().jsonObject["ok"]?.jsonPrimitive?.booleanOrNull)
            assertEquals("已取消", jsString("document.querySelector('#dialog-result').textContent"))
            assertTrue(handle.jsDialogs.value.isEmpty())
        }
        owner.release(human.lease.leaseId)
    }

    @Test
    fun actualClickToolYieldsPendingConfirmForHumanTakeover() = runBlocking {
        val clickTool = createClickTool(WebMountDeps(pool, AgentToolActivityStore(), owner))
        val parts = withTimeout(8_000) {
            clickTool.execute(buildJsonObject {
                put("session_id", handle.sessionId)
                put("selector", "#confirm")
                put("visible_only", false)
            }.withWebMountScope("fixture-conversation", "dialog-run"))
        }
        val receipt = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
        assertEquals(true, receipt["requires_human"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, receipt["goal_verified"]?.jsonPrimitive?.booleanOrNull)
        val dialog = handle.jsDialogs.value.single()
        // A normal model turn may finish after returning requires_human. The
        // host must retire its run identity without cancelling that handoff.
        owner.endRun("dialog-run", "fixture-conversation", preservePendingHandoff = true)
        assertEquals(dialog.dialogId, handle.jsDialogs.value.single().dialogId)
        val human = owner.acquire(handle.sessionId, WebMountOwner.HUMAN, "fixture-conversation")
            as WebMountLeaseResult.Granted
        handle.resolveJsDialog(dialog.dialogId, confirmed = false)
        handle.callBridge("wait", buildJsonObject {
            put("until", "selector")
            put("value", "xpath=//output[@id='dialog-result' and text()='已取消']")
            put("visible_only", false)
            put("timeout_ms", 2_000)
        })
        assertEquals("已取消", jsString("document.querySelector('#dialog-result').textContent"))
        owner.release(human.lease.leaseId)
    }

    @Test
    fun cancelledRunCancelsPendingHandoffAndIgnoresLateConfirmation() = runBlocking {
        val tool = createClickTool(WebMountDeps(pool, AgentToolActivityStore(), owner))
        withTimeout(8_000) {
            tool.execute(buildJsonObject {
                put("session_id", handle.sessionId)
                put("selector", "#confirm")
                put("visible_only", false)
            }.withWebMountScope("fixture-conversation", "cancelled-dialog-run"))
        }
        val dialog = handle.jsDialogs.value.single()
        owner.endRun("cancelled-dialog-run", "fixture-conversation")
        assertTrue(handle.jsDialogs.value.isEmpty())
        handle.resolveJsDialog(dialog.dialogId, confirmed = true)
        handle.callBridge("wait", buildJsonObject {
            put("until", "selector")
            put("value", "xpath=//output[@id='dialog-result' and text()='已取消']")
            put("visible_only", false)
            put("timeout_ms", 2_000)
        })
        assertEquals("已取消", jsString("document.querySelector('#dialog-result').textContent"))
    }

    @Test
    fun popupKeepsOpenerAndCookieThenClosesBackToParent() = runBlocking {
        handle.evalRaw("document.querySelector('#popup').click()")
        val popup = withTimeout(10_000) {
            handle.popups.first { windows -> windows.any { it.title == "合成登录窗口" } }
        }.single()
        withContext(Dispatchers.Main) {
            popup.webView.evaluateJavascript("document.querySelector('#complete').click()", null)
        }
        withTimeout(5_000) { handle.popups.first { it.isEmpty() } }
        handle.callBridge("wait", buildJsonObject {
            put("until", "selector")
            put("value", "xpath=//output[@id='login-status' and text()='合成登录完成']")
            put("visible_only", false)
            put("timeout_ms", 5_000)
        }, timeoutMs = 6_000)
        assertEquals("合成登录完成", jsString("document.querySelector('#login-status').textContent"))
        assertEquals("true", jsString("document.cookie.includes('amber_parity_login=done')"))
        assertEquals("Amber 浏览器验收", handle.loadState.value.title)
    }

    private suspend fun interactive(): JsonObject = handle.callBridge("extract", buildJsonObject {
        put("mode", "interactive")
        put("visible_only", false)
    }).jsonObject

    private suspend fun jsString(expression: String): String =
        Json.parseToJsonElement(requireNotNull(handle.evalRaw(expression))).jsonPrimitive.content
}
