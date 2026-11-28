package app.amber.agent

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import app.amber.core.automation.display.ActiveVirtualDisplayRun
import app.amber.core.automation.display.PhoneDisplayMode
import app.amber.core.automation.display.PhoneDisplaySession
import app.amber.core.automation.display.VirtualDisplayRunNotifier
import app.amber.core.automation.display.VirtualDisplayRunRegistry
import app.amber.core.automation.AccessibilityController
import app.amber.feature.macgateway.ProactiveDetailRequest
import app.amber.feature.macgateway.ProactiveDeepLinkInbox
import app.amber.feature.system.AmberQuickTileService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProactiveDeepLinkTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        VirtualDisplayRunRegistry.clearForTest()
    }

    @Test
    fun `parses valid amber proactive deep links across length and character boundaries`() {
        assertEquals("a", proactiveDeepLinkSignalIdFrom("amber://proactive/a"))
        assertEquals("wt_123-ABC", proactiveDeepLinkSignalIdFrom("amber://proactive/wt_123-ABC"))
        val maxLen = "A".repeat(48)
        assertEquals(maxLen, proactiveDeepLinkSignalIdFrom("amber://proactive/$maxLen"))

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("amber://proactive/signal_99"))
        assertEquals("signal_99", proactiveDeepLinkSignalIdFrom(intent))
    }

    @Test
    fun `rejects multi-segment paths, query strings, fragments, traversal, and illegal characters`() {
        val invalidUris = listOf(
            "",
            "amber://proactive",
            "amber://proactive/",
            "amber://proactive/sig_1/extra",
            "amber://proactive/sig_1/",
            "amber://proactive//sig_1",
            "amber://proactive/sig_1?foo=bar",
            "amber://proactive/sig_1?",
            "amber://proactive/sig_1#section",
            "amber://proactive/../secret",
            "amber://proactive/%2e%2e",
            "amber://proactive/sig%2F1",
            "amber://proactive/${"x".repeat(49)}",
            "amber://proactive/sig.dot",
            "amber://proactive/sig with space",
            "amber://user@proactive/sig_1",
            "amber://proactive:8080/sig_1",
            "amberagent://proactive/sig_1",
            "https://proactive/sig_1",
            "amber://other/sig_1",
        )
        for (raw in invalidUris) {
            assertNull("Expected rejection for raw URI: $raw", proactiveDeepLinkSignalIdFrom(raw))
            assertNull(
                "Expected rejection for Intent URI: $raw",
                proactiveDeepLinkSignalIdFrom(Intent(Intent.ACTION_VIEW, Uri.parse(raw))),
            )
        }
    }

    @Test
    fun `cold and warm Intent consumption is strictly one-shot and updates PersonalAgent stack`() {
        val inbox = ProactiveDeepLinkInbox()

        // Cold start Intent
        val coldIntent = Intent(Intent.ACTION_VIEW, Uri.parse("amber://proactive/cold_sig_1"))
        val firstConsume = consumeProactiveDeepLink(coldIntent, inbox, alreadyConsumed = false)
        assertEquals("cold_sig_1", firstConsume)
        assertEquals("cold_sig_1", inbox.pending.value?.signalId)
        assertNull("Intent data must be cleared after one-shot consumption", coldIntent.data)

        // UI consumes it once
        inbox.consume(ProactiveDetailRequest("cold_sig_1", null, null))
        assertNull(inbox.pending.value)

        // Re-running cold consume on the same Intent (e.g. Activity recreation) must NOT re-submit.
        assertNull(consumeProactiveDeepLink(coldIntent, inbox, alreadyConsumed = false))
        assertNull(consumeProactiveDeepLink(Intent(Intent.ACTION_VIEW, Uri.parse("amber://proactive/cold_sig_2")), inbox, alreadyConsumed = true))
        assertNull(inbox.pending.value)

        // Cold initial back stack for PersonalAgent is [Screen.PersonalAgent] regardless of startMode.
        assertEquals(
            listOf<Screen>(Screen.PersonalAgent),
            initialBackStackFor(Screen.PersonalAgent, LaunchStartMode.HOME),
        )

        // Warm start Intent collapses existing stack onto [Screen.PersonalAgent] and submits once.
        val warmStack = mutableListOf<NavKey>(
            Screen.PersonalAgent,
            Screen.SessionHome,
            Screen.Chat("11111111-1111-1111-1111-111111111111"),
        )
        val warmIntent = Intent(Intent.ACTION_VIEW, Uri.parse("amber://proactive/warm_sig_2"))
        val warmConsumed = consumeProactiveDeepLink(warmIntent, inbox, alreadyConsumed = false)
        assertEquals("warm_sig_2", warmConsumed)
        applyProactiveDeepLinkToBackStack(warmStack)

        assertEquals(listOf<NavKey>(Screen.PersonalAgent), warmStack)
        assertEquals("warm_sig_2", inbox.pending.value?.signalId)
        assertNull(warmIntent.data)
    }

    @Test
    fun `clicking an old notification cannot resolve the same signal on another device`() {
        val inbox = ProactiveDeepLinkInbox()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("amber://proactive/test"))
            .putExtra(app.amber.feature.macgateway.ProactiveNotificationPresenter.EXTRA_GATEWAY_ID, "gw")
            .putExtra(app.amber.feature.macgateway.ProactiveNotificationPresenter.EXTRA_DEVICE_ID, "old")
        consumeProactiveDeepLink(intent, inbox)
        val request = requireNotNull(inbox.pending.value)
        val otherDevice = app.amber.feature.macgateway.MacGatewayConnection("gw", "Cloud", emptyList(), 123, "fp", "new")
        assertFalse(request.matches(otherDevice))
        assertTrue(request.matches(otherDevice.copy(deviceId = "old")))
        inbox.submit("test", "gw", "new")
        inbox.consume(request)
        assertEquals("new", inbox.pending.value?.deviceId)
    }

    @Test
    fun `pairing notification brings Amber forward and opens phone automation settings without an active run`() {
        val intent = phoneUseDisplaySettingsIntent(context)
        assertEquals(RouteActivity::class.java.name, intent.component?.className)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
        assertEquals(Screen.SettingPhoneUseDisplay, phoneUseScreenFromIntent(intent))
        assertNull(phoneUseScreenFromIntent(Intent(Intent.ACTION_MAIN)))
    }

    @Test
    fun `quick tile routes to PhoneUseLive when a virtual display run is active and PersonalAgent when idle`() {
        val idleIntent = AmberQuickTileService.buildLaunchIntent(context, activeRun = null)
        assertEquals(RouteActivity::class.java.name, idleIntent.component?.className)
        assertTrue(openPersonalAgentFromIntent(idleIntent))
        assertNull(phoneUseScreenFromIntent(idleIntent))

        val fakeSession = object : PhoneDisplaySession {
            override val mode: PhoneDisplayMode = PhoneDisplayMode.VIRTUAL
            override val displayId: Int = 7
            override val generation: Long = 1L
            override val controller: AccessibilityController
                get() = error("unused in route test")
            override suspend fun launchTarget(): Boolean = true
            override suspend fun close(reason: String) = Unit
        }
        val activeRun = ActiveVirtualDisplayRun(
            runId = "vd_run_42",
            packageName = "com.example.app",
            goal = "check status",
            session = fakeSession,
        )
        VirtualDisplayRunRegistry.register(activeRun)
        val liveIntent = AmberQuickTileService.buildLaunchIntent(context, activeRun = activeRun)
        assertEquals(RouteActivity::class.java.name, liveIntent.component?.className)
        assertEquals(VirtualDisplayRunNotifier.ACTION_VIEW_VIRTUAL_RUN, liveIntent.action)
        assertEquals(Screen.PhoneUseLive("vd_run_42"), phoneUseScreenFromIntent(liveIntent))
        assertFalse(openPersonalAgentFromIntent(liveIntent))
        VirtualDisplayRunRegistry.unregister(activeRun.runId, activeRun)
        assertNull(phoneUseScreenFromIntent(liveIntent))
    }
}
