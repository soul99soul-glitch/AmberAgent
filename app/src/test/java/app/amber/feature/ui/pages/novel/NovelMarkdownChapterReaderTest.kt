package app.amber.feature.ui.pages.novel

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import app.amber.agent.R
import app.amber.core.settings.Settings
import app.amber.feature.ui.context.LocalSettings
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
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h720dp-xxhdpi")
class NovelMarkdownChapterReaderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val first = NovelMarkdownChapterUi("chapters/one.md", "First chapter", 1, 100)
    private val middle = NovelMarkdownChapterUi("chapters/four.md", "Middle chapter", 4, 100)
    private val last = NovelMarkdownChapterUi("chapters/nine.md", "Last chapter", 9, 100)
    private val chapters = listOf(last, first, middle)

    @Test
    fun chapterNavigationUsesActualNeighborsAcrossOrdinalGapsAndDisablesBookBoundaries() {
        val selected = mutableStateOf(first.path)
        val opened = mutableListOf<String>()
        val historyChapters = mutableListOf<NovelMarkdownChapterUi>()
        compose.setContent {
            MaterialTheme {
                Reader(
                    chapterPath = selected.value,
                    readBody = { "Manuscript at $it" },
                    onOpenChapter = { opened.add(it); selected.value = it },
                    onHistory = { historyChapters.add(it) },
                )
            }
        }
        previous().assertIsNotEnabled()
        next().assertIsEnabled().performClick()
        compose.onNodeWithText("Manuscript at ${middle.path}").assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.novel_more_actions)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.novel_chapter_history)).performClick()
        assertEquals(listOf(middle), historyChapters)
        previous().assertIsEnabled()
        next().assertIsEnabled().performClick()
        compose.onNodeWithText("Manuscript at ${last.path}").assertIsDisplayed()
        next().assertIsNotEnabled()
        previous().assertIsEnabled().performClick()
        compose.onNodeWithText("Manuscript at ${middle.path}").assertIsDisplayed()
        previous().performClick()
        compose.onNodeWithText("Manuscript at ${first.path}").assertIsDisplayed()
        previous().assertIsNotEnabled()
        assertEquals(listOf(middle.path, last.path, middle.path, first.path), opened)
    }

    @Test
    fun lateChapterReadCannotReplaceNewChapterAndBackRemainsAvailableDuringLoading() {
        val initialRead = CompletableDeferred<String?>()
        val reopenedRead = CompletableDeferred<String?>()
        val selected = mutableStateOf(first.path)
        val shown = mutableStateOf(true)
        var firstChapterReads = 0
        var backs = 0
        compose.setContent {
            MaterialTheme {
                if (shown.value) Reader(
                    chapterPath = selected.value,
                    readBody = { path ->
                        if (path == first.path) {
                            firstChapterReads++
                            if (firstChapterReads == 1) initialRead.await() else reopenedRead.await()
                        } else "New chapter body"
                    },
                    onOpenChapter = { selected.value = it },
                    onBack = { backs++; shown.value = false },
                )
            }
        }
        compose.runOnIdle { assertEquals(1, firstChapterReads) }
        back().assertIsDisplayed()
        next().assertIsEnabled().performClick()
        compose.onNodeWithText("New chapter body").assertIsDisplayed()
        compose.runOnIdle { initialRead.complete("Late first chapter body") }
        compose.onNodeWithText("New chapter body").assertIsDisplayed()
        compose.onNodeWithText("Late first chapter body").assertDoesNotExist()

        previous().performClick()
        compose.runOnIdle { assertEquals(2, firstChapterReads) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.novel_more_actions)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.edit)).performTouchInput { click() }
        // An unavailable Edit may leave the menu open; close it through its existing history action.
        val history = compose.onAllNodesWithText(compose.activity.getString(R.string.novel_chapter_history))
        if (history.fetchSemanticsNodes().isNotEmpty()) history[0].performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        back().assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, backs) }
        compose.onNodeWithTag("novel-chapter-reader-scroll").assertDoesNotExist()
        compose.runOnIdle { reopenedRead.complete("Body after leaving reader") }
        compose.onNodeWithText("Body after leaving reader").assertDoesNotExist()
    }

    @Test
    fun readFailureAndMissingChapterKeepAnExitAndRetryCanOpenTheActualEditor() {
        val shown = mutableStateOf(true)
        var reads = 0
        var backs = 0
        compose.setContent {
            MaterialTheme {
                if (shown.value) Reader(
                    chapterPath = middle.path,
                    readBody = {
                        when (reads++) {
                            0 -> error("Chapter read failed")
                            1 -> null
                            else -> "Recovered chapter body"
                        }
                    },
                    onBack = { backs++; shown.value = false },
                )
            }
        }
        val loadError = compose.activity.getString(R.string.novel_reader_load_error)
        val emptyChapter = compose.activity.getString(R.string.novel_empty_chapter)
        compose.onNodeWithText(loadError).assertIsDisplayed()
        compose.onNodeWithText(emptyChapter).assertDoesNotExist()
        back().assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, backs); assertEquals(1, reads) }
        compose.onNodeWithTag("novel-chapter-reader-navigation").assertDoesNotExist()

        compose.runOnIdle { shown.value = true }
        compose.onNodeWithText(loadError).assertIsDisplayed()
        compose.onNodeWithText(emptyChapter).assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, reads) }
        back().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.retry)).performClick()
        compose.onNodeWithText("Recovered chapter body").assertIsDisplayed()
        compose.onNodeWithText(loadError).assertDoesNotExist()
        compose.runOnIdle { assertEquals(3, reads) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.novel_more_actions)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.edit)).assertIsEnabled().performClick()
        compose.onNode(hasSetTextAction() and hasText("Recovered chapter body")).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
        back().assertIsDisplayed()
    }

    @Test
    fun busyRewriteBlocksEditingAndRevisionReloadsSameLengthCommittedManuscript() {
        val busy = mutableStateOf(true)
        val refreshVersion = mutableStateOf(0)
        val oldBody = "Old river crossing"
        val newBody = "New river crossing"
        var sourceBody = oldBody
        var reads = 0
        assertEquals(oldBody.length, newBody.length)
        compose.setContent {
            MaterialTheme {
                Reader(
                    chapterPath = middle.path,
                    busy = busy.value,
                    refreshVersion = refreshVersion.value,
                    readBody = { reads++; sourceBody },
                )
            }
        }
        compose.onNodeWithText(oldBody).assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.novel_more_actions)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.edit)).assertIsNotEnabled()
            .performTouchInput { click() }
        compose.onNodeWithText(compose.activity.getString(R.string.novel_chapter_history)).performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        back().assertIsDisplayed()

        compose.runOnIdle { busy.value = false; sourceBody = newBody }
        compose.onNodeWithText(oldBody).assertIsDisplayed()
        compose.onNodeWithText(newBody).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, reads); refreshVersion.value++ }
        // A retained MarkdownBlock reparses changed body on Dispatchers.Default, outside Compose idling.
        compose.waitUntil(timeoutMillis = 5_000) { compose.onNodeWithText(newBody).isDisplayed() }
        compose.onNodeWithText(newBody).assertIsDisplayed()
        compose.onNodeWithText(oldBody).assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, reads) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.novel_more_actions)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.edit)).assertIsEnabled().performClick()
        compose.onNode(hasSetTextAction() and hasText(newBody)).assertIsDisplayed()
    }

    @Test
    fun fullManuscriptCanScrollToItsTailAbovePersistentChapterNavigation() {
        val body = "The travelers crossed the river and kept the copper key safely with them.\n\n".repeat(80) +
            "FINAL_READER_SENTENCE"
        compose.setContent {
            MaterialTheme {
                Reader(chapterPath = middle.path, readBody = { body })
            }
        }
        val scroll = compose.onNodeWithTag("novel-chapter-reader-scroll")
        val navigation = compose.onNodeWithTag("novel-chapter-reader-navigation")
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy -> scrollBy(0f, Float.MAX_VALUE) }
        compose.waitForIdle()
        val range = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("The complete manuscript must extend beyond a phone viewport", range.maxValue() > 0f)
        assertEquals(range.maxValue(), range.value(), 1f)
        val tail = compose.onNodeWithText("FINAL_READER_SENTENCE", substring = true)
        tail.assertIsDisplayed().assertTextContains("FINAL_READER_SENTENCE", substring = true)
        val viewportBounds = scroll.getUnclippedBoundsInRoot()
        val footerBounds = navigation.getUnclippedBoundsInRoot()
        val tailBounds = tail.getUnclippedBoundsInRoot()
        assertTrue("The scroll viewport must stop before the chapter navigation", viewportBounds.bottom <= footerBounds.top)
        assertTrue("The manuscript's final line must fit above the navigation", tailBounds.bottom <= footerBounds.top)
        previous().assertIsDisplayed().assertIsEnabled()
        next().assertIsDisplayed().assertIsEnabled()
    }

    @Composable
    private fun Reader(
        chapterPath: String,
        readBody: suspend (String) -> String?,
        busy: Boolean = false,
        refreshVersion: Int = 0,
        onOpenChapter: (String) -> Unit = {},
        onBack: () -> Unit = {},
        onHistory: (NovelMarkdownChapterUi) -> Unit = {},
    ) {
        CompositionLocalProvider(LocalSettings provides remember { Settings() }) {
            NovelMarkdownChapterReader(
                chapters = chapters,
                chapterPath = chapterPath,
                active = true,
                busy = busy,
                writeLocked = false,
                errorMessage = null,
                refreshVersion = refreshVersion,
                scrollState = key(chapterPath) { rememberScrollState() },
                readBody = readBody,
                onBack = onBack,
                onOpenChapter = onOpenChapter,
                onSave = { _, _, _, _ -> },
                onRewrite = { false },
                onHistory = onHistory,
                onDiscard = {},
                onClearError = {},
            )
        }
    }

    private fun previous() = compose.onNodeWithText(compose.activity.getString(R.string.novel_previous_chapter))
    private fun next() = compose.onNodeWithText(compose.activity.getString(R.string.novel_next_chapter))
    private fun back() = compose.onNodeWithContentDescription(compose.activity.getString(R.string.novel_return_to_directory))
}
