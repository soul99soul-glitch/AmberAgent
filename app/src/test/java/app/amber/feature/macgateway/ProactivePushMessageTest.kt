package app.amber.feature.macgateway

import android.app.Application
import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.amber.agent.PROACTIVE_ACTIVE_CHANNEL_ID
import app.amber.agent.PROACTIVE_PASSIVE_CHANNEL_ID
import app.amber.agent.PROACTIVE_URGENT_CHANNEL_ID
import app.amber.agent.RouteActivity
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProactivePushMessageTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val pairedConnection = MacGatewayConnection(
        gatewayId = "gw-cloud-1",
        name = "Cloud Amber",
        addrs = listOf("127.0.0.1"),
        port = 8443,
        fingerprint = "fp-1",
        deviceId = "dev-android-1",
    )

    private fun validProactiveData(
        signalId: String = "wt_signal_01",
        gatewayId: String = pairedConnection.gatewayId,
        deviceId: String = pairedConnection.deviceId,
        interruptionLevel: String = "active",
    ): Map<String, String> = mapOf(
        "amber_v" to "1",
        "type" to "proactive",
        "title" to "Release v2.0 published",
        "body" to "Your watched repository published a new tag.",
        "gateway_id" to gatewayId,
        "device_id" to deviceId,
        "signal_id" to signalId,
        "deep_link" to "amber://proactive/$signalId",
        "interruption_level" to interruptionLevel,
        "category" to "amber.proactive.card",
        "tag" to "proactive:$signalId",
    )

    private fun sampleCard(
        signalId: String = "wt_signal_01",
        notifiedAt: Long? = null,
        feedbackState: String? = null,
    ): ProactiveCard = ProactiveCard(
        gatewayId = pairedConnection.gatewayId,
        deviceId = pairedConnection.deviceId,
        eventId = "proactive:$signalId",
        signalId = signalId,
        seq = 10L,
        text = "Body",
        meta = ProactiveEventMeta(
            signalId = signalId,
            topicKey = "watchtower:1",
            category = "WATCHTOWER",
            title = "Release v2.0 published",
        ),
        receivedAt = 1_000L,
        notifiedAt = notifiedAt,
        feedbackState = feedbackState,
    )

    @Test
    fun `parses valid amber_v=1 proactive, approval, and generic payloads with length clamps`() {
        val longTitle = "T".repeat(140)
        val longBody = "B".repeat(250)
        val proactive = ProactivePushMessage.parseForPairing(
            data = validProactiveData() + mapOf("title" to longTitle, "body" to longBody),
            connection = pairedConnection,
        )
        assertNotNull(proactive)
        assertEquals(ProactivePushType.PROACTIVE, proactive!!.type)
        assertEquals(100, proactive.title.length)
        assertEquals(180, proactive.body.length)
        assertEquals("wt_signal_01", proactive.signalId)
        assertEquals("amber://proactive/wt_signal_01", proactive.canonicalProactiveDeepLink())

        val approval = ProactivePushMessage.parseForPairing(
            data = mapOf(
                "amber_v" to "1",
                "type" to "approval",
                "title" to "Approval required",
                "body" to "Confirm terminal action in app.",
                "gateway_id" to pairedConnection.gatewayId,
                "device_id" to pairedConnection.deviceId,
                "session_key" to "sess-1",
                "tool_call_id" to "tc-42",
                "work_item_id" to "wi-7",
                "high_risk" to "true",
                "interruption_level" to "time-sensitive",
                "tag" to "approval:tc-42",
            ),
            connection = pairedConnection,
        )
        assertNotNull(approval)
        assertEquals(ProactivePushType.APPROVAL, approval!!.type)
        assertEquals("tc-42", approval.toolCallId)
        assertEquals("wi-7", approval.workItemId)
        assertTrue(approval.highRisk)
        assertEquals(ProactiveInterruptionLevel.TIME_SENSITIVE, approval.interruptionLevel)

        val generic = ProactivePushMessage.parseForPairing(
            data = mapOf(
                "amber_v" to "1",
                "type" to "generic",
                "title" to "Sync notice",
                "body" to "Gateway connected.",
                "gateway_id" to pairedConnection.gatewayId,
                "device_id" to pairedConnection.deviceId,
                "interruption_level" to "passive",
            ),
            connection = pairedConnection,
        )
        assertNotNull(generic)
        assertEquals(ProactivePushType.GENERIC, generic!!.type)
        assertEquals(ProactiveInterruptionLevel.PASSIVE, generic.interruptionLevel)
    }

    @Test
    fun `drops payloads with missing fields, invalid signal_id, or mismatched gateway or device`() {
        val base = validProactiveData()

        // Missing or wrong amber_v
        assertNull(ProactivePushMessage.parse(base - "amber_v"))
        assertNull(ProactivePushMessage.parse(base + ("amber_v" to "2")))

        // Missing required fields
        assertNull(ProactivePushMessage.parse(base - "type"))
        assertNull(ProactivePushMessage.parse(base + ("type" to "unknown")))
        assertNull(ProactivePushMessage.parse(base - "title"))
        assertNull(ProactivePushMessage.parse(base + ("title" to "   ")))
        assertNull(ProactivePushMessage.parse(base - "body"))
        assertNull(ProactivePushMessage.parse(base - "gateway_id"))
        assertNull(ProactivePushMessage.parse(base - "device_id"))
        assertNull(ProactivePushMessage.parse(base - "signal_id"))

        // Invalid signal_id values
        val badSignalIds = listOf(
            "",
            " ",
            "../etc",
            "sig/extra",
            "sig?x=1",
            "sig#frag",
            "sig.dot",
            "sig with space",
            "a".repeat(49),
        )
        for (bad in badSignalIds) {
            assertNull("Expected rejection for signal_id=$bad", ProactivePushMessage.parse(base + ("signal_id" to bad)))
        }

        // Pairing mismatch drops silently
        assertNull(ProactivePushMessage.parseForPairing(base, connection = null))
        assertNull(
            ProactivePushMessage.parseForPairing(
                base + ("gateway_id" to "gw-other"),
                connection = pairedConnection,
            ),
        )
        assertNull(
            ProactivePushMessage.parseForPairing(
                base + ("device_id" to "dev-other"),
                connection = pairedConnection,
            ),
        )
    }

    @Test
    fun `maps three interruption levels to three frozen proactive channels`() {
        assertEquals(
            PROACTIVE_PASSIVE_CHANNEL_ID,
            ProactiveNotificationPresenter.channelIdFor(ProactiveInterruptionLevel.PASSIVE),
        )
        assertEquals(
            PROACTIVE_ACTIVE_CHANNEL_ID,
            ProactiveNotificationPresenter.channelIdFor(ProactiveInterruptionLevel.ACTIVE),
        )
        assertEquals(
            PROACTIVE_URGENT_CHANNEL_ID,
            ProactiveNotificationPresenter.channelIdFor(ProactiveInterruptionLevel.TIME_SENSITIVE),
        )
    }

    @Test
    fun `approval notification exposes only open-app action and never an approve button`() {
        val presenter = ProactiveNotificationPresenter(
            context = context,
            awaitGatewayReady = {},
            currentConnection = { pairedConnection },
            isCloudMainStageVisible = { false },
            requestImmediatePoll = {},
            findLocalCard = { _, _ -> null },
            markCardNotified = { _, _, _ -> },
            hasNotificationPermission = { true },
            notifyPlatform = { _, _, _ -> },
        )
        val approvalMsg = ProactivePushMessage.parseForPairing(
            data = mapOf(
                "amber_v" to "1",
                "type" to "approval",
                "title" to "High-risk command",
                "body" to "Needs in-app approval.",
                "gateway_id" to pairedConnection.gatewayId,
                "device_id" to pairedConnection.deviceId,
                "tool_call_id" to "tc_999",
                "high_risk" to "true",
                "interruption_level" to "time-sensitive",
            ),
            connection = pairedConnection,
        )!!

        val built = presenter.buildNotification(approvalMsg)
        assertEquals(PROACTIVE_URGENT_CHANNEL_ID, built.channelId)
        assertEquals(1, built.actions.size)
        val onlyAction = built.actions.single()
        assertEquals(BuiltProactiveNotification.ActionKind.OPEN_APPROVAL, onlyAction.kind)
        assertEquals(RouteActivity::class.java.name, onlyAction.intent.component?.className)
        assertFalse(onlyAction.title.contains("批准", ignoreCase = true) && !onlyAction.title.contains("App", ignoreCase = true))
        assertEquals(1, built.notification.actions.size)
    }

    @Test
    fun `proactive notification PendingIntent requestCodes and data URIs are unique across gateway, signal, and action`() {
        val presenter = ProactiveNotificationPresenter(
            context = context,
            awaitGatewayReady = {},
            currentConnection = { pairedConnection },
            isCloudMainStageVisible = { false },
            requestImmediatePoll = {},
            findLocalCard = { _, _ -> null },
            markCardNotified = { _, _, _ -> },
            hasNotificationPermission = { true },
            notifyPlatform = { _, _, _ -> },
        )
        val msg1 = ProactivePushMessage.parse(validProactiveData(signalId = "sig_1", gatewayId = "gw-1"))!!
        val msg2 = ProactivePushMessage.parse(validProactiveData(signalId = "sig_2", gatewayId = "gw-1"))!!
        val msg3 = ProactivePushMessage.parse(validProactiveData(signalId = "sig_1", gatewayId = "gw-2"))!!

        val built1 = presenter.buildNotification(msg1)
        val built2 = presenter.buildNotification(msg2)
        val built3 = presenter.buildNotification(msg3)

        // Direct Activity PendingIntent for "Let Amber handle" (no trampoline)
        val act1 = built1.actions.first { it.kind == BuiltProactiveNotification.ActionKind.ACT }
        assertEquals(RouteActivity::class.java.name, act1.intent.component?.className)
        assertEquals("amber://proactive/sig_1", act1.intent.dataString)
        assertEquals("gw-1|sig_1|act".hashCode(), act1.requestCode)

        // Snooze and Mute point explicitly to ProactiveNotificationActionReceiver with distinct data URIs
        val snooze1 = built1.actions.first { it.kind == BuiltProactiveNotification.ActionKind.SNOOZE }
        val mute1 = built1.actions.first { it.kind == BuiltProactiveNotification.ActionKind.MUTE }
        val snooze2 = built2.actions.first { it.kind == BuiltProactiveNotification.ActionKind.SNOOZE }
        val snooze3 = built3.actions.first { it.kind == BuiltProactiveNotification.ActionKind.SNOOZE }

        assertEquals(ProactiveNotificationActionReceiver::class.java.name, snooze1.intent.component?.className)
        assertEquals(ProactiveNotificationActionReceiver::class.java.name, mute1.intent.component?.className)
        assertEquals(ACTION_PROACTIVE_SNOOZE, snooze1.intent.action)
        assertEquals(ACTION_PROACTIVE_MUTE, mute1.intent.action)
        assertEquals("gw-1|sig_1|$ACTION_PROACTIVE_SNOOZE".hashCode(), snooze1.requestCode)
        assertEquals("gw-1|sig_1|$ACTION_PROACTIVE_MUTE".hashCode(), mute1.requestCode)

        // Distinct request codes AND non-equal Intent filters (extras are ignored by Android PendingIntent matching)
        val codes = setOf(act1.requestCode, snooze1.requestCode, mute1.requestCode, snooze2.requestCode, snooze3.requestCode)
        assertEquals(5, codes.size)
        assertFalse(snooze1.intent.filterEquals(snooze2.intent))
        assertFalse(snooze1.intent.filterEquals(snooze3.intent))
        assertFalse(snooze1.intent.filterEquals(mute1.intent))
        assertNotEquals(snooze1.intent.dataString, snooze2.intent.dataString)
    }

    @Test
    fun `foreground visible cloud main stage suppresses banner and only triggers immediate poll`() = runBlocking {
        val posted = CopyOnWriteArrayList<Triple<String, Int, Notification>>()
        val pollReasons = CopyOnWriteArrayList<String>()
        val marked = CopyOnWriteArrayList<String>()

        val presenter = ProactiveNotificationPresenter(
            context = context,
            awaitGatewayReady = {},
            currentConnection = { pairedConnection },
            isCloudMainStageVisible = { true },
            requestImmediatePoll = { pollReasons += it },
            findLocalCard = { _, _ -> null },
            markCardNotified = { _, signalId, _ -> marked += signalId },
            hasNotificationPermission = { true },
            notifyPlatform = { tag, id, n -> posted += Triple(tag, id, n) },
        )

        val outcome = presenter.handleIncomingDataPush(validProactiveData(signalId = "sig_fg_1"))
        assertEquals(ProactivePushPresentationOutcome.SuppressedForegroundPolled, outcome)
        assertTrue(posted.isEmpty())
        assertTrue(marked.isEmpty())
        assertEquals(listOf("push"), pollReasons)
    }

    @Test
    fun `late push for already-handled or already-notified card does not re-notify`() = runBlocking {
        val posted = CopyOnWriteArrayList<Triple<String, Int, Notification>>()
        val marked = CopyOnWriteArrayList<String>()
        val cards = mutableMapOf(
            "sig_handled" to sampleCard(signalId = "sig_handled", feedbackState = "opened"),
            "sig_notified" to sampleCard(signalId = "sig_notified", notifiedAt = 9_999L),
        )

        val presenter = ProactiveNotificationPresenter(
            context = context,
            awaitGatewayReady = {},
            currentConnection = { pairedConnection },
            isCloudMainStageVisible = { false },
            requestImmediatePoll = {},
            findLocalCard = { _, signalId -> cards[signalId] },
            markCardNotified = { _, signalId, _ -> marked += signalId },
            hasNotificationPermission = { true },
            notifyPlatform = { tag, id, n -> posted += Triple(tag, id, n) },
        )

        assertEquals(
            ProactivePushPresentationOutcome.SuppressedAlreadyHandled,
            presenter.handleIncomingDataPush(validProactiveData(signalId = "sig_handled")),
        )
        assertEquals(
            ProactivePushPresentationOutcome.SuppressedAlreadyHandled,
            presenter.handleIncomingDataPush(validProactiveData(signalId = "sig_notified")),
        )
        assertTrue(posted.isEmpty())
        assertTrue(marked.isEmpty())

        // Fresh signal not yet notified/handled succeeds and records markNotified.
        val freshOutcome = presenter.handleIncomingDataPush(
            validProactiveData(signalId = "sig_fresh", interruptionLevel = "passive"),
        )
        assertTrue(freshOutcome is ProactivePushPresentationOutcome.Notified)
        assertEquals(
            PROACTIVE_PASSIVE_CHANNEL_ID,
            (freshOutcome as ProactivePushPresentationOutcome.Notified).channelId,
        )
        assertEquals(1, posted.size)
        assertEquals(listOf("sig_fresh"), marked)
    }
}
