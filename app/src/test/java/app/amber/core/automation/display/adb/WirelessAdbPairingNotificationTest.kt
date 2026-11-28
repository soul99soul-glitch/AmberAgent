package app.amber.core.automation.display.adb

import android.app.Application
import android.app.Notification
import app.amber.agent.Screen
import app.amber.agent.phoneUseScreenFromIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.InetSocketAddress

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WirelessAdbPairingNotificationTest {
    private val address = InetSocketAddress(InetAddress.getLoopbackAddress(), 42424)

    @Test
    fun notificationUpdatesKeepOnePrivateReplyAndReturnToDisplaySettings() {
        val controller = Robolectric.buildService(WirelessAdbPairingNotification::class.java).create()
        try {
            val service = controller.get()
            val waiting = service.buildNotification(PairingStage.WAITING, null, alert = false)
            val found = service.buildNotification(PairingStage.FOUND, address.port, alert = true)
            assertEquals(waiting.channelId, found.channelId)
            assertTrue(waiting.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            assertEquals(0, found.flags and Notification.FLAG_ONLY_ALERT_ONCE)
            assertEquals(Notification.GROUP_ALERT_ALL, found.groupAlertBehavior)
            assertEquals(Notification.VISIBILITY_PRIVATE, found.visibility)
            assertTrue(found.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(address.port.toString()))
            for (notice in listOf(waiting, found)) {
                assertEquals(2, notice.actions.size)
                assertEquals(1, notice.actions.first().remoteInputs.size)
                assertEquals("wireless_adb.cancel", shadowOf(notice.actions.last().actionIntent).savedIntent.action)
                assertEquals(Screen.SettingPhoneUseDisplay, phoneUseScreenFromIntent(shadowOf(notice.contentIntent).savedIntent))
            }
            val completed = service.buildNotification(PairingStage.CONNECTED, null, alert = false)
            assertTrue(completed.actions.isNullOrEmpty())
            assertTrue(completed.flags and Notification.FLAG_AUTO_CANCEL != 0)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun promptTracksLiveEndpointAndPairsOnceWithLatestPort() = runTest {
        val events = MutableSharedFlow<InetSocketAddress?>(extraBufferCapacity = 8)
        val notices = mutableListOf<Pair<PairingStage, Int?>>()
        var connects = 0
        var pairs = 0
        val run = WirelessPairingRun(
            this, { events }, { selected, code ->
                assertEquals(address, selected)
                assertEquals("012345", code)
                pairs++
            }, { connects++ }, { stage, port -> notices += stage to port },
        )
        run.start()
        run.start()
        runCurrent()
        assertEquals(1, events.subscriptionCount.value)
        events.tryEmit(address)
        events.tryEmit(address)
        events.tryEmit(null)
        events.tryEmit(address)
        runCurrent()
        run.reply("1")
        run.reply("012345")
        run.reply("012345")
        runCurrent()
        assertEquals(
            listOf(PairingStage.WAITING, PairingStage.FOUND, PairingStage.WAITING, PairingStage.FOUND,
                PairingStage.INVALID_CODE, PairingStage.PAIRING, PairingStage.CONNECTING, PairingStage.CONNECTED),
            notices.map { it.first },
        )
        assertEquals(address.port, notices[1].second)
        assertEquals(1, pairs)
        assertEquals(1, connects)
        assertEquals(0, events.subscriptionCount.value)
    }

    @Test
    fun earlyReplyUsesDiscoveryAndHelperFailureRetainsPairedOutcome() = runTest {
        val notices = mutableListOf<PairingStage>()
        val run = WirelessPairingRun(this, { flow { emit(null) } }, { cached, _ -> assertNull(cached) },
            { error("helper not ready") }, { stage, _ -> notices += stage })
        run.start()
        run.reply("123456")
        runCurrent()
        assertEquals(
            listOf(PairingStage.WAITING, PairingStage.PAIRING, PairingStage.CONNECTING, PairingStage.DISCONNECTED), notices,
        )
    }

    @Test
    fun cancelDuringPairingPreventsHelperLaunchAndLateNotifications() = runTest {
        val notices = mutableListOf<PairingStage>()
        val hold = CompletableDeferred<Unit>()
        val run = WirelessPairingRun(this, { flow { emit(address) } }, { _, _ -> hold.await() },
            { error("cancelled pairing started the helper") }, { stage, _ -> notices += stage })
        run.start()
        runCurrent()
        run.reply("123456")
        runCurrent()
        run.cancel()
        hold.complete(Unit)
        runCurrent()
        assertEquals(listOf(PairingStage.WAITING, PairingStage.FOUND, PairingStage.PAIRING), notices)
    }

    @Test
    fun discoveryPermissionFailureStopsPrompt() = runTest {
        val notices = mutableListOf<PairingStage>()
        val run = WirelessPairingRun(this, { flow { throw SecurityException() } }, { _, _ -> error("unexpected pairing") },
            { error("unexpected connect") }, { stage, _ -> notices += stage })
        run.start()
        runCurrent()
        run.reply("123456")
        runCurrent()
        assertEquals(listOf(PairingStage.WAITING, PairingStage.DISCOVERY_UNAVAILABLE), notices)
    }
}
