package app.amber.feature.ui.context

import androidx.navigation3.runtime.NavKey
import app.amber.agent.Screen
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigatorSessionReturnTest {
    @Test
    fun toolbarReturnPopsChatAndRetainsTheExistingHomeEntry() {
        val stack = mutableListOf<NavKey>(Screen.SessionHome)
        val navigator = Navigator(stack)
        navigator.navigate(Screen.Chat("conversation"))
        navigator.returnToSessionHome()
        assertEquals(listOf(Screen.SessionHome), stack)
    }

    @Test
    fun replacingAChatStillKeepsHomeForSystemBack() {
        val stack = mutableListOf<NavKey>(Screen.SessionHome, Screen.Chat("old"))
        val navigator = Navigator(stack)
        val chat = Screen.Chat("new")
        navigator.clearAndNavigate(chat)
        assertEquals(listOf(Screen.SessionHome, chat), stack)
        navigator.popBackStack()
        assertEquals(listOf(Screen.SessionHome), stack)
    }

    @Test
    fun returningFromAnOlderRootChatDoesNotLeaveTheChatBehindHome() {
        val stack = mutableListOf<NavKey>(Screen.Chat("legacy"))
        Navigator(stack).returnToSessionHome()
        assertEquals(listOf(Screen.SessionHome), stack)
    }
}
