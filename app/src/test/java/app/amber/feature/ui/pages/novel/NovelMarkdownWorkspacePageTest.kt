package app.amber.feature.ui.pages.novel

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
class NovelMarkdownWorkspacePageTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rejectedSaveLeavesEditorAndCancelAvailableWhileBusyBlocksAnotherSave() {
        val busy = mutableStateOf(false)
        var saveCalls = 0
        var cancelCalls = 0

        compose.setContent {
            MaterialTheme {
                MarkdownChapterEditor(
                    chapter = NovelMarkdownChapterUi(
                        path = "chapters/001-first.md",
                        title = "First chapter",
                        ordinal = 1,
                        charCount = 4,
                    ),
                    initialBody = "body",
                    busy = busy.value,
                    writeLocked = false,
                    onSave = { _, _, _ -> saveCalls++ },
                    onCancel = { cancelCalls++ },
                )
            }
        }

        onSave().performClick()
        assertEquals("the simulated owner rejection must not invoke success", 1, saveCalls)

        compose.onAllNodes(hasSetTextAction())[1]
            .assertIsEnabled()
            .performTextInput(" updated")
        onCancel().assertIsEnabled().performClick()
        assertEquals(1, cancelCalls)

        busy.value = true
        compose.waitForIdle()
        compose.onNodeWithText("Saving…").assertIsNotEnabled()
        assertEquals("busy must prevent a duplicate save", 1, saveCalls)
    }

    private fun onSave() = compose.onNodeWithText("Save")

    private fun onCancel() = compose.onNodeWithText("Cancel")
}
