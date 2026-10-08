package app.amber.feature.ui.adaptive

import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import app.amber.agent.Screen
import app.amber.feature.ui.context.Navigator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoPaneLayoutTest {
    @Test
    fun tabletAndUnfoldedLandscapeUseTwoPanes() {
        assertTrue(isTwoPaneWindow(widthDp = 1280f, heightDp = 800f))
        assertTrue(isTwoPaneWindow(widthDp = 841f, heightDp = 701f))
    }

    @Test
    fun portraitTabletsAndLandscapePhonesStaySinglePane() {
        assertFalse(isTwoPaneWindow(widthDp = 800f, heightDp = 1280f))
        assertFalse(isTwoPaneWindow(widthDp = 701f, heightDp = 841f))
        assertFalse(isTwoPaneWindow(widthDp = 914f, heightDp = 411f))
        assertFalse(isTwoPaneWindow(widthDp = 411f, heightDp = 914f))
        // Small landscape tablet window: the chat pane would be narrower than the list.
        assertFalse(isTwoPaneWindow(widthDp = 700f, heightDp = 520f))
    }

    @Test
    fun listPaneIsNarrowAndClamped() {
        assertEquals(384f, twoPaneListWidth(1280.dp).value, 0.01f)
        assertEquals(360f, twoPaneListWidth(841.dp).value, 0.01f)
        assertEquals(400f, twoPaneListWidth(1600.dp).value, 0.01f)
    }

    @Test
    fun homeAloneGetsAnEmptyDetailPane() {
        assertEquals(TwoPaneSlots(listIndex = 0, detailIndex = null), slotsOf(listOf(Screen.SessionHome)))
    }

    @Test
    fun topChatSitsBesideHome() {
        assertEquals(
            TwoPaneSlots(listIndex = 0, detailIndex = 1),
            slotsOf(listOf(Screen.SessionHome, Screen.Chat("a"))),
        )
        // A chat pushed on a chat (notification, search result) still shows beside home.
        assertEquals(
            TwoPaneSlots(listIndex = 0, detailIndex = 2),
            slotsOf(listOf(Screen.SessionHome, Screen.Chat("a"), Screen.Chat("b"))),
        )
    }

    @Test
    fun anyOtherScreenAboveHomeStaysFullScreen() {
        assertNull(slotsOf(listOf(Screen.SessionHome, Screen.Chat("a"), Screen.Setting)))
        assertNull(slotsOf(listOf(Screen.SessionHome, Screen.Setting, Screen.Chat("a"))))
        assertNull(slotsOf(listOf(Screen.Setting)))
    }

    @Test
    fun openingASessionFromHomeReplacesTheDetailInsteadOfStacking() {
        val stack = mutableListOf<NavKey>(Screen.SessionHome, Screen.Chat("a"))
        Navigator(stack).navigate(Screen.Chat("b")) {
            popUpTo(Screen.SessionHome)
            launchSingleTop = true
        }
        assertEquals(listOf(Screen.SessionHome, Screen.Chat("b")), stack)
    }

    @Test
    fun openingASessionOnPhoneStillPushesOverHome() {
        val stack = mutableListOf<NavKey>(Screen.SessionHome)
        Navigator(stack).navigate(Screen.Chat("a")) {
            popUpTo(Screen.SessionHome)
            launchSingleTop = true
        }
        assertEquals(listOf(Screen.SessionHome, Screen.Chat("a")), stack)
    }

    private fun slotsOf(keys: List<NavKey>): TwoPaneSlots? = twoPaneSlots(
        keys.map {
            when (it) {
                Screen.SessionHome -> TwoPaneRole.List
                is Screen.Chat -> TwoPaneRole.Detail
                else -> null
            }
        },
    )
}
