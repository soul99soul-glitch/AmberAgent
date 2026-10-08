package app.amber.feature.ui.pages.novel

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import app.amber.agent.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h720dp-xxhdpi")
class NovelWorkspaceContinuityReportUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun progressCanStopAndIncompleteEvidenceReportOpensOnlyWhenRequested() {
        val checking = mutableStateOf(true)
        val report = mutableStateOf<String?>(null)
        val showReport = mutableStateOf(false)
        val stoppedReport = "Stopped; incomplete: 2 of 5 chapters\n" +
            "Chapter 2 · branches/main/chapters/2.md\nEvidence: original manuscript quote\n" +
            "Author review suggestion\n".repeat(100) + "FINAL_EVIDENCE"
        compose.setContent {
            MaterialTheme {
                Column {
                    NovelWorkspaceContinuityStatus(
                        checking = checking.value, checkedChapters = 2, totalChapters = 5,
                        reportAvailable = report.value != null,
                        onStop = { checking.value = false; report.value = stoppedReport },
                        onViewReport = { showReport.value = true },
                    )
                }
                if (showReport.value) report.value?.let { value ->
                    NovelWorkspaceContinuityReportDialog(value) { showReport.value = false }
                }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.novel_consistency_progress, 2, 5)).assertIsDisplayed()
        compose.onNodeWithTag("novel-continuity-report-text").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.stop)).assertIsDisplayed().performClick()
        compose.onNodeWithTag("novel-continuity-report-text").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.novel_consistency_view_report)).assertIsDisplayed().performClick()
        compose.onNodeWithTag("novel-continuity-report-text")
            .assertTextContains("Stopped; incomplete", substring = true)
            .assertTextContains("branches/main/chapters/2.md", substring = true)
            .assertTextContains("FINAL_EVIDENCE", substring = true)
        val scroll = compose.onNodeWithTag("novel-continuity-report-scroll")
        scroll.performTouchInput { swipeUp() }
        assertTrue(scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0)
        compose.onNodeWithText(compose.activity.getString(R.string.novel_dismiss)).assertIsDisplayed().performClick()
        compose.onNodeWithTag("novel-continuity-report-text").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.novel_consistency_view_report)).assertIsDisplayed()
    }
}
