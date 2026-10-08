package app.amber.feature.ui.pages.novel

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performClick
import app.amber.agent.R
import app.amber.feature.novel.workspace.NovelWorkspaceWriteEntry
import app.amber.feature.novel.workspace.NovelWorkspaceWriteProposal
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h960dp-xxhdpi")
class NovelMarkdownReviewCardsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun singleFileApprovalWaitsForActualOriginalAndUsesOwnerCallback() {
        val original = CompletableDeferred<String?>()
        val busy = mutableStateOf(false)
        var approvals = 0
        compose.setContent {
            MaterialTheme {
                Column {
                    MarkdownProposalCard(proposal(1), busy.value, {}, {},
                        readRaw = { original.await() }, onApprove = { approvals++ })
                }
            }
        }
        val write = compose.onNodeWithText(compose.activity.getString(R.string.novel_confirm_write))
        write.assertIsNotEnabled()
        compose.runOnIdle { original.complete("---\nkind: chapter\nordinal: 2\ntitle: Original chapter name\n---\nActual original manuscript") }
        compose.onNodeWithText(compose.activity.getString(R.string.novel_chapter_heading, 2, "Original chapter name")).assertExists()
        compose.onNodeWithText("Actual original manuscript").assertTextContains("Actual original manuscript")
        compose.onNodeWithText("Proposed manuscript").assertTextContains("Proposed manuscript")
        write.assertIsEnabled().performClick()
        assertEquals(1, approvals)
        compose.runOnIdle { busy.value = true }
        write.assertIsNotEnabled()
    }

    @Test
    fun multiFileProposalRequiresFullReviewAndReadFailureKeepsWriteDisabled() {
        val multi = mutableStateOf(true)
        var reviews = 0
        compose.setContent {
            MaterialTheme {
                Column {
                    MarkdownProposalCard(proposal(if (multi.value) 2 else 1), false,
                        onReview = { reviews++ }, onReject = {},
                        readRaw = { error("Cannot read original") }, onApprove = {})
                }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.novel_confirm_write)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.novel_review_proposal)).performClick()
        assertEquals(1, reviews)
        compose.runOnIdle { multi.value = false }
        compose.onNodeWithText(compose.activity.getString(R.string.novel_review_load_error)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.novel_confirm_write)).assertIsNotEnabled()
    }

    @Test
    fun longManuscriptsKeepApprovalIdentityAndActionsInsidePhoneTimelineViewport() {
        val body = "The author checks character locations and the copper key before continuing the story. ".repeat(120)
        val original = "---\nkind: chapter\nordinal: 2\ntitle: Chapter 2\n---\n$body"
        val candidate = proposal(1).copy(entries = listOf(NovelWorkspaceWriteEntry(
            "branches/main/chapters/2.md", original,
            reason = "Keep the key with the author-confirmed character; both characters remain at the river crossing.",
        )))
        compose.setContent {
            MaterialTheme {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(546.dp).testTag("phone-timeline"),
                    reverseLayout = true, contentPadding = PaddingValues(12.dp),
                ) {
                    item { MarkdownProposalCard(candidate, false, {}, {}, readRaw = { original }, onApprove = {}) }
                }
            }
        }
        compose.waitForIdle()
        val viewport = compose.onNodeWithTag("phone-timeline").getUnclippedBoundsInRoot()
        listOf(R.string.novel_write_proposal_title, R.string.novel_review_proposal,
            R.string.novel_reject, R.string.novel_confirm_write).forEach { label ->
            val bounds = compose.onNodeWithText(compose.activity.getString(label)).getUnclippedBoundsInRoot()
            assertTrue("Entire approval label must fit: $label", bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        }
        compose.onNodeWithText(compose.activity.getString(R.string.novel_chapter_heading, 2, "Chapter 2")).assertExists()
    }

    private fun proposal(count: Int) = NovelWorkspaceWriteProposal(
        id = "proposal-$count", projectDirectory = File("test-book"), branchId = "branch", branchSlug = "main",
        baseHeadId = "head", baseTreeDigest = "tree", createdAt = Instant.EPOCH,
        entries = (1..count).map { NovelWorkspaceWriteEntry("branches/main/chapters/$it.md",
            "---\ntitle: Chapter $it\n---\nProposed manuscript", reason = null) },
    )
}
