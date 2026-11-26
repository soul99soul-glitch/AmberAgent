package app.amber.agent

import android.app.Application
import android.content.Context
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import app.amber.feature.ui.adaptive.TwoPaneRole
import app.amber.feature.ui.adaptive.TwoPaneSlots
import app.amber.feature.ui.adaptive.twoPaneSlots
import app.amber.feature.ui.context.Navigator
import app.amber.feature.ui.hooks.readStringPreference
import app.amber.feature.ui.hooks.writeStringPreference
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
class PersonalAgentBackStackTest {
    @Test
    fun `cold start landing on Chat uses homeScreenFor(mode) as back stack bottom`() {
        val chat = Screen.Chat("11111111-1111-1111-1111-111111111111")

        assertEquals(
            listOf(Screen.SessionHome, chat),
            initialBackStackFor(startScreen = chat, startMode = LaunchStartMode.HOME),
        )
        assertEquals(
            listOf(Screen.PersonalAgent, chat),
            initialBackStackFor(startScreen = chat, startMode = LaunchStartMode.PERSONAL_AGENT),
        )
        assertEquals(
            listOf(Screen.PersonalAgent, chat),
            initialBackStackFor(startScreen = chat, startMode = LaunchStartMode.LAST_SESSION),
        )
        assertEquals(
            listOf(Screen.PersonalAgent, chat),
            initialBackStackFor(startScreen = chat, startMode = LaunchStartMode.NEW_CHAT),
        )
        assertEquals(
            listOf(Screen.PersonalAgent, chat),
            initialBackStackFor(startScreen = chat, startMode = LaunchStartMode.AUTO),
        )
    }

    @Test
    fun `PersonalAgent root has single entry so system back exits app`() {
        val stack = initialBackStackFor(
            startScreen = Screen.PersonalAgent,
            startMode = LaunchStartMode.PERSONAL_AGENT,
        ).toMutableList<NavKey>()
        val navigator = Navigator(stack)

        assertEquals(listOf<NavKey>(Screen.PersonalAgent), stack)
        assertFalse("Root PersonalAgent must not intercept system back", navigator.canPop)
    }

    @Test
    fun `entering Sessions and Chat from PersonalAgent preserves return path`() {
        val chat = Screen.Chat("22222222-2222-2222-2222-222222222222")
        val stack = initialBackStackFor(
            startScreen = Screen.PersonalAgent,
            startMode = LaunchStartMode.PERSONAL_AGENT,
        ).toMutableList<NavKey>()
        val navigator = Navigator(stack)

        navigator.navigate(Screen.SessionHome) { launchSingleTop = true }
        assertEquals(listOf<NavKey>(Screen.PersonalAgent, Screen.SessionHome), stack)
        assertTrue(navigator.canPop)

        // Opening a chat from SessionHome pushes Chat above SessionHome.
        navigator.navigate(chat)
        assertEquals(listOf<NavKey>(Screen.PersonalAgent, Screen.SessionHome, chat), stack)

        // Back from Chat returns to SessionHome; back from SessionHome returns to PersonalAgent.
        navigator.popBackStack()
        assertEquals(listOf<NavKey>(Screen.PersonalAgent, Screen.SessionHome), stack)
        navigator.popBackStack()
        assertEquals(listOf<NavKey>(Screen.PersonalAgent), stack)
        assertFalse(navigator.canPop)
    }

    @Test
    fun `PersonalAgent entry is single-pane while SessionHome and Chat keep two-pane roles`() {
        val entries = appNavigationEntries()
        val chat = Screen.Chat("33333333-3333-3333-3333-333333333333")

        val personalAgentRole = entries(Screen.PersonalAgent).metadata["app.amber.adaptive.twoPaneRole"] as? TwoPaneRole
        val sessionHomeRole = entries(Screen.SessionHome).metadata["app.amber.adaptive.twoPaneRole"] as? TwoPaneRole
        val chatRole = entries(chat).metadata["app.amber.adaptive.twoPaneRole"] as? TwoPaneRole

        assertNull("PersonalAgent must be registered as single-pane", personalAgentRole)
        assertEquals(TwoPaneRole.List, sessionHomeRole)
        assertEquals(TwoPaneRole.Detail, chatRole)

        assertNull(twoPaneSlots(listOf(personalAgentRole)))
        assertEquals(
            TwoPaneSlots(listIndex = 1, detailIndex = null),
            twoPaneSlots(listOf(personalAgentRole, sessionHomeRole)),
        )
        assertEquals(
            TwoPaneSlots(listIndex = 1, detailIndex = 2),
            twoPaneSlots(listOf(personalAgentRole, sessionHomeRole, chatRole)),
        )
    }

    @Test
    fun `display setting preference write feeds RouteActivity start mode resolution`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.writeStringPreference(LAUNCH_START_MODE_PREF, LaunchStartMode.HOME.name)

        val stored = context.readStringPreference(LAUNCH_START_MODE_PREF)
        val resolvedMode = migrateLaunchStartMode(storedMode = stored, legacyCreateNewConversationOnStart = null)
        assertEquals(LaunchStartMode.HOME, resolvedMode)

        val startScreen = resolveLaunchStartScreen(
            mode = resolvedMode,
            lastConversationId = null,
            newConversationId = "44444444-4444-4444-4444-444444444444",
        )
        assertEquals(Screen.SessionHome, startScreen)

        context.writeStringPreference(LAUNCH_START_MODE_PREF, LaunchStartMode.PERSONAL_AGENT.name)
        val updatedMode = migrateLaunchStartMode(
            storedMode = context.readStringPreference(LAUNCH_START_MODE_PREF),
            legacyCreateNewConversationOnStart = false,
        )
        assertEquals(LaunchStartMode.PERSONAL_AGENT, updatedMode)
        assertEquals(
            Screen.PersonalAgent,
            resolveLaunchStartScreen(updatedMode, null, "44444444-4444-4444-4444-444444444444"),
        )
    }
}
