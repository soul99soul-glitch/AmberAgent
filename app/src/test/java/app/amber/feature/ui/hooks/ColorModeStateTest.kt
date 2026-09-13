package app.amber.feature.ui.hooks

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.amber.feature.ui.theme.ColorMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ColorModeStateTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun changingModeUpdatesAnotherConsumerWithoutRecreatingItAndPersists() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val prefs = context.getSharedPreferences("amber_agent.preferences", Context.MODE_PRIVATE)
        prefs.edit().putString("colorMode", "LIGHT").putBoolean("amoledDark", true).commit()
        compose.setContent {
            Column {
                var selection by rememberColorMode()
                val themeMode by rememberColorMode()
                val amoled by rememberAmoledDarkMode()
                Text("theme:${themeMode.name}:$amoled")
                TextButton(onClick = { selection = ColorMode.DARK }) { Text("dark") }
                TextButton(onClick = { selection = ColorMode.SYSTEM }) { Text("system") }
            }
        }
        compose.onNodeWithText("theme:LIGHT:true").assertExists()
        compose.onNodeWithText("dark").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("theme:DARK:true").fetchSemanticsNodes().size == 1
        }
        assertEquals("DARK", prefs.getString("colorMode", null))
        compose.onNodeWithText("system").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("theme:SYSTEM:true").fetchSemanticsNodes().size == 1
        }
        assertEquals("SYSTEM", prefs.getString("colorMode", null))
    }
}
