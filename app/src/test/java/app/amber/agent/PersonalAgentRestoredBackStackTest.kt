package app.amber.agent

import android.app.Application
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class PersonalAgentRestoredBackStackTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun warmChatStackRestoresAboveItsConfiguredRoot() {
        val savedState = StateRestorationTester(compose)
        lateinit var stack: MutableList<NavKey>
        val target = Screen.Chat("11111111-1111-1111-1111-111111111111")
        savedState.setContent { stack = rememberNavBackStack(Screen.TodayBoard) }
        compose.runOnIdle { applyExternalChatToBackStack(stack, target, LaunchStartMode.PERSONAL_AGENT) }
        savedState.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(listOf(Screen.PersonalAgent, target), stack) }
    }
}
