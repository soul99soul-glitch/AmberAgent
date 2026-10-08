package app.amber.feature.ui.pages.novel

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.amber.agent.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h720dp-xxhdpi")
class NovelMarkdownMaterialSheetsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun deleteRequiresSpecificConfirmationAndKeepsEditorCancelableWhenSourceChanged() {
        val path = "setting/characters/protagonist.md"
        val title = ("Author character " + "with a long descriptive title ".repeat(8)).trimEnd()
        val raw = "---\nid: author-character\ntitle: $title\naliases:\n  - Alias\nrelations:\n  - {with: friend-id, type: ally}\n---\n\nFull character description"
        val error = mutableStateOf<String?>(null)
        var expectedRaw: String? = null
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                MarkdownMaterialEditSheet(
                    path = path, displayTitle = title, busy = false, writeLocked = false,
                    errorMessage = error.value, allowRenameDelete = true, readRaw = { raw },
                    onSave = { _, _, _, _ -> },
                    onDelete = { snapshot, _ -> expectedRaw = snapshot; error.value = "The setting changed; reopen it before deleting" },
                    onDismiss = { dismissed = true },
                )
            }
        }
        val deleteLabel = compose.activity.getString(R.string.delete)
        compose.onNodeWithTag("novel-material-body").assertExists()
        compose.onNodeWithText(deleteLabel).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(null, expectedRaw) }
        compose.onNodeWithText(compose.activity.getString(R.string.novel_delete_material_message, title)).assertExists()
        compose.onAllNodesWithText(deleteLabel).onLast().performClick()
        compose.runOnIdle {
            assertEquals(raw, expectedRaw)
            assertFalse(dismissed)
        }
        compose.onNodeWithText("The setting changed; reopen it before deleting").assertExists()
        compose.onNodeWithTag("novel-material-body").assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(true, dismissed) }
    }
}
