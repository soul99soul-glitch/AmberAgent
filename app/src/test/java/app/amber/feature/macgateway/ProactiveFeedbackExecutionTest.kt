package app.amber.feature.macgateway

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.OneTimeWorkRequest
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProactiveFeedbackExecutionTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val activeConnection = MacGatewayConnection(
        gatewayId = "gw-1",
        name = "Cloud Amber",
        addrs = listOf("127.0.0.1"),
        port = 8443,
        fingerprint = "fp-1",
        deviceId = "dev-1",
    )

    @After
    fun tearDown() {
        ProactiveNotificationActionReceiver.enqueueWorkOverride = null
    }

    @Test
    fun `receiver enqueues unique work with non-secret WorkData only and never includes token`() {
        val enqueued = CopyOnWriteArrayList<Pair<String, OneTimeWorkRequest>>()
        ProactiveNotificationActionReceiver.enqueueWorkOverride = { _, name, request ->
            enqueued += name to request
        }

        val intent = ProactiveNotificationPresenter.buildBackgroundActionIntent(
            context = context,
            gatewayId = "gw-1",
            deviceId = "dev-1",
            signalId = "sig_42",
            action = ACTION_PROACTIVE_SNOOZE,
            notificationTag = "proactive:sig_42",
            notificationId = 0,
        )
        ProactiveNotificationActionReceiver().onReceive(context, intent)

        assertEquals(1, enqueued.size)
        val (workName, request) = enqueued.single()
        assertEquals("proactive-feedback/gw-1/dev-1/sig_42/snooze", workName)

        val data = request.workSpec.input
        assertEquals("gw-1", data.getString(ProactiveFeedbackWorker.KEY_GATEWAY_ID))
        assertEquals("dev-1", data.getString(ProactiveFeedbackWorker.KEY_DEVICE_ID))
        assertEquals("sig_42", data.getString(ProactiveFeedbackWorker.KEY_SIGNAL_ID))
        assertEquals("snooze", data.getString(ProactiveFeedbackWorker.KEY_ACTION))
        assertEquals(
            setOf("gatewayId", "deviceId", "signalId", "action"),
            data.keyValueMap.keys,
        )
        assertFalse(data.keyValueMap.keys.any { it.contains("token", ignoreCase = true) })

        // Invalid signalId or unknown action is ignored cleanly.
        val badIntent = ProactiveNotificationPresenter.buildBackgroundActionIntent(
            context = context,
            gatewayId = "gw-1",
            deviceId = "dev-1",
            signalId = "../bad",
            action = ACTION_PROACTIVE_MUTE,
            notificationTag = "proactive:bad",
            notificationId = 0,
        )
        assertNull(ProactiveNotificationActionReceiver.parseEnqueueSpec(badIntent))
    }

    @Test
    fun `pairing mismatch aborts immediately without reading token or calling network and notifies incomplete`() = runBlocking {
        val networkCalls = AtomicInteger(0)
        val incompleteAlerts = CopyOnWriteArrayList<Pair<String, ProactiveFeedbackAction>>()

        val executor = ProactiveFeedbackExecutor(
            awaitGatewayReady = {},
            currentConnection = { activeConnection },
            currentGeneration = { 2L },
            submitFeedback = { _, _, _, _, _, _ ->
                networkCalls.incrementAndGet()
                ProactiveFeedbackResult(ok = true, action = "snooze", applied = true)
            },
            recordLocalFeedback = { _, _, _, _ -> },
            onTerminalIncomplete = { signalId, action -> incompleteAlerts += signalId to action },
        )

        // Different gatewayId
        val gwMismatch = executor.execute(
            gatewayId = "gw-old",
            deviceId = "dev-1",
            signalId = "sig_old_1",
            actionWire = "snooze",
            runAttemptCount = 0,
        )
        assertEquals(ProactiveFeedbackOutcome.PairingMismatch, gwMismatch)

        // Different deviceId
        val devMismatch = executor.execute(
            gatewayId = "gw-1",
            deviceId = "dev-old",
            signalId = "sig_old_2",
            actionWire = "mute_topic",
            runAttemptCount = 0,
        )
        assertEquals(ProactiveFeedbackOutcome.PairingMismatch, devMismatch)

        assertEquals(0, networkCalls.get())
        assertEquals(
            listOf(
                "sig_old_1" to ProactiveFeedbackAction.SNOOZE,
                "sig_old_2" to ProactiveFeedbackAction.MUTE_TOPIC,
            ),
            incompleteAlerts,
        )
    }

    @Test
    fun `pairing rotation while acquiring bound client or waiting for feedback never updates new pairing`() = runBlocking {
        val generation = AtomicLong(1)
        var clientReplaced = true
        val executor = ProactiveFeedbackExecutor(
            awaitGatewayReady = {},
            currentConnection = { activeConnection },
            currentGeneration = generation::get,
            submitFeedback = { _, expected, _, _, _, _ ->
                assertEquals(generation.get(), expected)
                generation.incrementAndGet()
                if (clientReplaced) null else ProactiveFeedbackResult(ok = true, action = "snooze", applied = true)
            },
            recordLocalFeedback = { _, _, _, _ -> error("stale receipt") },
            onCompletedCancelNotification = { _, _, _ -> error("stale notification") },
        )
        assertEquals(ProactiveFeedbackOutcome.PairingMismatch, executor.execute("gw-1", "dev-1", "signal", "snooze", 0))
        clientReplaced = false
        assertEquals(ProactiveFeedbackOutcome.PairingMismatch, executor.execute("gw-1", "dev-1", "signal", "snooze", 0))
    }

    @Test
    fun `applied=false is treated as idempotent success and updates local feedback state`() = runBlocking {
        val recorded = CopyOnWriteArrayList<Triple<String, String, String>>()
        val cancelled = CopyOnWriteArrayList<String>()
        val incompleteAlerts = CopyOnWriteArrayList<Pair<String, ProactiveFeedbackAction>>()
        var capturedSnoozeMinutes: Int? = null

        val executor = ProactiveFeedbackExecutor(
            awaitGatewayReady = {},
            currentConnection = { activeConnection },
            currentGeneration = { 5L },
            submitFeedback = { _, _, _, action, snoozeMinutes, _ ->
                capturedSnoozeMinutes = snoozeMinutes
                ProactiveFeedbackResult(ok = true, action = action.wire, applied = false)
            },
            recordLocalFeedback = { gw, _, sig, state -> recorded += Triple(gw, sig, state) },
            onCompletedCancelNotification = { _, _, signal -> cancelled += signal },
            onTerminalIncomplete = { sig, action -> incompleteAlerts += sig to action },
        )

        val outcome = executor.execute(
            gatewayId = "gw-1",
            deviceId = "dev-1",
            signalId = "sig_idem",
            actionWire = "snooze",
            runAttemptCount = 0,
        )

        assertEquals(ProactiveFeedbackOutcome.Success(applied = false), outcome)
        assertEquals(ProactiveFeedbackExecutor.DEFAULT_SNOOZE_MINUTES, capturedSnoozeMinutes)
        assertEquals(listOf(Triple("gw-1", "sig_idem", "snooze")), recorded)
        assertEquals(listOf("sig_idem"), cancelled)
        assertTrue(incompleteAlerts.isEmpty())
    }

    @Test
    fun `HTTP 401 and 404 are terminal failures without retry and trigger local incomplete notice`() = runBlocking {
        val incomplete401 = CopyOnWriteArrayList<Pair<String, ProactiveFeedbackAction>>()
        val executor401 = ProactiveFeedbackExecutor(
            awaitGatewayReady = {},
            currentConnection = { activeConnection },
            currentGeneration = { 1L },
            submitFeedback = { _, _, _, _, _, _ -> throw MacGatewayException.Unauthorized() },
            recordLocalFeedback = { _, _, _, _ -> },
            onTerminalIncomplete = { sig, action -> incomplete401 += sig to action },
        )
        val outcome401 = executor401.execute("gw-1", "dev-1", "sig_401", "snooze", runAttemptCount = 0)
        assertTrue(outcome401 is ProactiveFeedbackOutcome.TerminalFailure)
        assertEquals(listOf("sig_401" to ProactiveFeedbackAction.SNOOZE), incomplete401)

        val incomplete404 = CopyOnWriteArrayList<Pair<String, ProactiveFeedbackAction>>()
        val executor404 = ProactiveFeedbackExecutor(
            awaitGatewayReady = {},
            currentConnection = { activeConnection },
            currentGeneration = { 1L },
            submitFeedback = { _, _, _, _, _, _ -> throw MacGatewayException.NotFound("signal_not_found") },
            recordLocalFeedback = { _, _, _, _ -> },
            onTerminalIncomplete = { sig, action -> incomplete404 += sig to action },
        )
        val outcome404 = executor404.execute("gw-1", "dev-1", "sig_404", "mute_topic", runAttemptCount = 0)
        assertTrue(outcome404 is ProactiveFeedbackOutcome.TerminalFailure)
        assertEquals(listOf("sig_404" to ProactiveFeedbackAction.MUTE_TOPIC), incomplete404)
    }

    @Test
    fun `offline or unreachable failures retry within bound and notify incomplete once exhausted`() = runBlocking {
        val incompleteAlerts = CopyOnWriteArrayList<Pair<String, ProactiveFeedbackAction>>()
        val generation = AtomicLong(3L)

        val executor = ProactiveFeedbackExecutor(
            awaitGatewayReady = {},
            currentConnection = { activeConnection },
            currentGeneration = { generation.get() },
            submitFeedback = { _, _, _, _, _, _ -> throw MacGatewayException.Unreachable() },
            recordLocalFeedback = { _, _, _, _ -> },
            onTerminalIncomplete = { sig, action -> incompleteAlerts += sig to action },
            maxRetryAttempts = 3,
        )

        // Attempts 0, 1, 2 -> RetryableFailure, NO incomplete notification yet.
        val attempt0 = executor.execute("gw-1", "dev-1", "sig_off", "snooze", runAttemptCount = 0)
        val attempt2 = executor.execute("gw-1", "dev-1", "sig_off", "snooze", runAttemptCount = 2)
        assertTrue(attempt0 is ProactiveFeedbackOutcome.RetryableFailure)
        assertTrue(attempt2 is ProactiveFeedbackOutcome.RetryableFailure)
        assertTrue(incompleteAlerts.isEmpty())

        // Attempt 3 (bound reached) -> TerminalFailure + incomplete notification.
        val attempt3 = executor.execute("gw-1", "dev-1", "sig_off", "snooze", runAttemptCount = 3)
        assertTrue(attempt3 is ProactiveFeedbackOutcome.TerminalFailure)
        assertEquals(listOf("sig_off" to ProactiveFeedbackAction.SNOOZE), incompleteAlerts)

        // IOException is also treated as retryable before bound.
        val ioExecutor = ProactiveFeedbackExecutor(
            awaitGatewayReady = {},
            currentConnection = { activeConnection },
            currentGeneration = { 1L },
            submitFeedback = { _, _, _, _, _, _ -> throw IOException("offline") },
            recordLocalFeedback = { _, _, _, _ -> },
            maxRetryAttempts = 2,
        )
        val ioRetry = ioExecutor.execute("gw-1", "dev-1", "sig_io", "mute_topic", runAttemptCount = 0)
        assertNotNull(ioRetry as? ProactiveFeedbackOutcome.RetryableFailure)
    }
}
