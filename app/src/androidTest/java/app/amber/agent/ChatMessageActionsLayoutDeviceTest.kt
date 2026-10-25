package app.amber.agent

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.core.model.MessageNode
import app.amber.core.settings.Settings
import app.amber.feature.ui.components.message.ChatMessage
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.context.Navigator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Uses native window insets; Robolectric's zero insets miss sheet sizing loops. */
class ChatMessageActionsLayoutDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test(timeout = 60_000)
    fun longPressMenuStopsGrowingAfterOpening() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${instrumentation.targetContext.packageName}/androidx.activity.ComponentActivity",
        )).bufferedReader().use { it.readText() }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.enableEdgeToEdge()
                activity.setContent {
                    CompositionLocalProvider(
                        LocalSettings provides Settings(),
                        LocalNavController provides Navigator(mutableListOf()),
                    ) {
                        MaterialTheme {
                            Box(Modifier.fillMaxSize()) {
                                ChatMessage(
                                    node = MessageNode.of(UIMessage.assistant(MARKER)),
                                    model = Model(modelId = "layout-test", displayName = "Layout test"),
                                    onFork = {}, onRegenerate = {}, onEdit = {},
                                    onShare = {}, onDelete = {}, onQuote = {}, onUpdate = {},
                                    onToggleFavorite = {}, onSaveToWorkspace = {},
                                )
                            }
                        }
                    }
                }
            }
            compose.onNodeWithText(MARKER).performTouchInput { longClick() }
            val sheet = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle))
            val initial = sheet.fetchSemanticsNode().boundsInWindow
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            instrumentation.targetContext.getExternalFilesDir(null)!!.resolve("message-menu-layout.png")
                .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            var safeTop = 0
            scenario.onActivity { activity ->
                safeTop = requireNotNull(ViewCompat.getRootWindowInsets(activity.window.decorView))
                    .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).top
            }
            assertTrue("Menu must stay below the status bar: $initial safeTop=$safeTop", initial.top >= safeTop)
            repeat(12) { sample ->
                Thread.sleep(250)
                val current = sheet.fetchSemanticsNode().boundsInWindow
                Log.i("AmberMessageMenu", "sample=$sample initial=$initial current=$current")
                assertEquals("Sheet top must settle after opening", initial.top, current.top, 1f)
                assertEquals("Sheet height must stop growing", initial.height, current.height, 1f)
            }
            compose.onNodeWithText("Layout test").performScrollTo().assertIsDisplayed()
            val afterScroll = sheet.fetchSemanticsNode().boundsInWindow
            assertEquals("Scrolling content must not move the sheet", initial.top, afterScroll.top, 1f)
            assertEquals("Scrolling content must not resize the sheet", initial.height, afterScroll.height, 1f)
            pressBack()
            compose.onNodeWithText(MARKER).performTouchInput { longClick() }
            val reopened = sheet.fetchSemanticsNode().boundsInWindow
            assertEquals("Reopening must not grow the sheet", initial.height, reopened.height, 1f)
            assertEquals("Reopening must not move the sheet upward", initial.top, reopened.top, 1f)
        }
    }

    private companion object {
        const val MARKER = "Long press message menu layout regression"
    }
}
