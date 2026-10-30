package app.amber.agent.perffixture

import android.app.Activity
import android.app.Application
import app.amber.agent.Screen
import app.amber.feature.ui.context.Navigator
import app.amber.feature.ui.pages.sessionhome.homeChatNavigation
import app.amber.feature.ui.utils.*
import androidx.navigation3.runtime.NavKey
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeFirstEntryTest {
    private val conversation = "10000000-0000-4000-8000-000000000001"
    @Before fun isolatedProcessState() {
        val field = ChatPerfHomeEntry::class.java.getDeclaredField("visited")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST") (field.get(ChatPerfHomeEntry) as MutableSet<String>).clear()
    }
    private fun owner(): Activity {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        ChatPerfHomeEntry.attach(activity, HOME_FIRST_PATH)
        ChatPerfHomeEntry.route(true, JSONObject().put("darkMode", false))
        ChatPerfHomeEntry.rowPlaced(conversation, true)
        return activity
    }
    @Test fun actualHomeCallbackBindsT0BeforeOriginalNavigatorAndWarmKeepsOwner() {
        val activity = owner()
        val id = ChatPerfHomeEntry.snapshot(conversation).getString("activityId")
        val prior = ChatPerfTrace.currentRunId()
        assertNull(ChatPerfHomeEntry.arm("home-cold-test", "S1", conversation, "cold"))
        assertEquals("arming must not start measurement", prior, ChatPerfTrace.currentRunId())
        val backing = mutableListOf<NavKey>(Screen.SessionHome)
        val observed = object : AbstractMutableList<NavKey>() {
            override val size get() = backing.size
            override fun get(index: Int) = backing[index]
            override fun set(index: Int, element: NavKey) = backing.set(index, element)
            override fun removeAt(index: Int) = backing.removeAt(index)
            override fun add(index: Int, element: NavKey) {
                if (element is Screen.Chat) {
                    assertEquals("home-cold-test", ChatPerfCommandBridge.fixtureRunId)
                    if (ChatPerfTrace.enabled) assertEquals("home-cold-test", ChatPerfTrace.currentRunId())
                    assertTrue(ShadowLog.getLogsForTag("AmberPerfFixture").any { it.msg.contains("home-entry runId=home-cold-test") })
                }
                backing.add(index, element)
            }
        }
        val nav = Navigator(observed)
        homeChatNavigation(nav)(Screen.Chat(conversation))
        assertEquals(listOf(Screen.SessionHome, Screen.Chat(conversation)), backing)
        assertTrue(ChatPerfHomeEntry.snapshot(conversation).getBoolean("chatVisited"))
        assertEquals("cold_already_visited_chat", ChatPerfHomeEntry.arm("wrong-cold", "S1", conversation, "cold"))
        nav.popBackStack() // actual production stack return, Activity unchanged
        ChatPerfHomeEntry.route(true, JSONObject())
        assertNull(ChatPerfHomeEntry.arm("home-warm-test", "S1", conversation, "warm"))
        assertEquals(id, ChatPerfHomeEntry.snapshot(conversation).getString("activityId"))
        assertFalse(activity.isDestroyed)
    }
    @Test fun recreatedActivityCannotMasqueradeAsWarmOrNeverVisitedCold() {
        owner()
        assertNull(ChatPerfHomeEntry.arm("owner-old", "S1", conversation, "cold"))
        ChatPerfHomeEntry.openFromHome(conversation)
        val old = ChatPerfHomeEntry.snapshot(conversation).getString("activityId")
        owner()
        assertNotEquals(old, ChatPerfHomeEntry.snapshot(conversation).getString("activityId"))
        assertEquals("warm_owner_changed", ChatPerfHomeEntry.arm("owner-new", "S1", conversation, "warm"))
        assertEquals("cold_already_visited_chat", ChatPerfHomeEntry.arm("bad-cold", "S1", conversation, "cold"))
    }
    @Test fun invisibleRowAndAlreadyObservedChatFailClosed() {
        owner()
        ChatPerfHomeEntry.rowPlaced(conversation, false)
        assertEquals("row_not_visible", ChatPerfHomeEntry.arm("no-row", "S1", conversation, "cold"))
        ChatPerfHomeEntry.rowPlaced(conversation, true)
        ChatPerfHomeEntry.chatObserved("another-chat")
        assertEquals("cold_already_visited_chat", ChatPerfHomeEntry.arm("not-first", "S1", conversation, "cold"))
    }
}
