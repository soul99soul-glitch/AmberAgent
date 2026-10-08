package app.amber.feature.ui.components.richtext

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertSame
import app.amber.core.settings.Settings
import app.amber.feature.ui.context.LocalSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class StreamingMarkdownCadenceComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun batchFadeDoesNotRemeasureUnchangedMarkdownOnEveryFrame() {
        compose.mainClock.autoAdvance = false
        val measures = AtomicInteger()
        compose.setContent {
            CompositionLocalProvider(LocalSettings provides Settings()) {
                MaterialTheme {
                    MarkdownBlock(
                        "body **bold text**",
                        streaming = true,
                        modifier = Modifier.layout { measurable, constraints ->
                            measures.incrementAndGet()
                            val placeable = measurable.measure(constraints)
                            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                        },
                    )
                }
            }
        }
        waitForRendered("body bold text")
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val before = measures.get()
        val beforeLayout = renderedLayout("body bold text")
        assertTrue("batch fade must preserve bold styling",
            beforeLayout.layoutInput.text.spanStyles.any { it.item.fontWeight == FontWeight.SemiBold })
        compose.mainClock.advanceTimeBy(120)
        compose.waitForIdle()
        assertEquals("fade-only frames must skip text measurement", before, measures.get())
        assertSame("fade-only frames must reuse text layout", beforeLayout.multiParagraph, renderedLayout("body bold text").multiParagraph)
    }

    @Test
    fun publishCadenceRepairsMarkdownAndKeepsTheTextNodeWhenStreamingEnds() {
        var source by mutableStateOf("body **bold")
        var streaming by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalSettings provides Settings()) {
                MaterialTheme { MarkdownBlock(source, streaming = streaming) }
            }
        }
        compose.waitUntil(4000) { rendered("body bold") }
        compose.runOnIdle { source = "body **bold text**" }
        compose.waitUntil(4000) { rendered("body bold text") }
        val before = compose.onNodeWithText("body bold text").fetchSemanticsNode()
        compose.runOnIdle { streaming = false }
        compose.waitForIdle()
        val after = compose.onNodeWithText("body bold text").fetchSemanticsNode()
        assertEquals("completion must reuse the rendered paragraph", before.id, after.id)
        assertEquals("completion must keep the paragraph height", before.boundsInRoot.height, after.boundsInRoot.height, 1f)
    }

    @Test
    fun completedTableKeepsItsCellsWhenTheFollowingParagraphArrives() {
        var source by mutableStateOf("| Header | Value |\n| --- | --- |\n| First | Cell |")
        compose.setContent {
            CompositionLocalProvider(LocalSettings provides Settings()) {
                MaterialTheme { MarkdownBlock(source, streaming = true) }
            }
        }
        waitForRendered("Header")
        val before = compose.onNodeWithText("Header").fetchSemanticsNode().id
        compose.runOnIdle { source += "\n\nFollowing paragraph" }
        waitForRendered("Following paragraph")
        assertEquals("promoting a completed table must reuse its cells", before,
            compose.onNodeWithText("Header").fetchSemanticsNode().id)
    }

    @Test
    fun deferredCouncilParsingStillShowsNewContentBeforeCompletion() {
        // Council's existing live-suffix animation runs for the whole stream.
        compose.mainClock.autoAdvance = false
        var source by mutableStateOf("start")
        var streaming by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalSettings provides Settings()) {
                MaterialTheme {
                    MarkdownBlock(source, streaming = streaming, deferStreamingParse = streaming)
                }
            }
        }
        waitForRendered("start")
        compose.runOnIdle { source = "start additional" }
        waitForRendered("additional")
        compose.runOnIdle { streaming = false }
        waitForRendered("start additional")
    }

    private fun renderedLayout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun waitForRendered(text: String) {
        compose.waitUntil(4000) {
            compose.mainClock.advanceTimeByFrame()
            rendered(text)
        }
    }

    private fun rendered(text: String): Boolean =
        compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
}
