package app.amber.feature.ui.pages.miniapp

import android.app.Application
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.buildAmberTokens
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
@Config(sdk = [34], application = Application::class)
class MiniAppSourceEditorLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun largeTextKeepsGutterAlignedAndLastLineVerticallyScrollable() {
        val density = Density(density = 1f, fontScale = 1.5f)
        val source = (1..16).joinToString("\n") { line ->
            "<p>第${line}行 中文 👋 ${"x".repeat(240)}</p>"
        }
        setEditor(source, density)

        val sourceLayout = textLayout(source)
        assertEquals(source.count { it == '\n' } + 1, sourceLayout.lineCount)
        assertTrue(
            "last source line must fit inside its real text layout",
            sourceLayout.getLineBottom(sourceLayout.lineCount - 1) <= sourceLayout.size.height,
        )

        for (line in 0 until sourceLayout.lineCount) {
            compose.onNodeWithText((line + 1).toString()).performScrollTo()
            val numberNode = compose.onNodeWithText((line + 1).toString()).fetchSemanticsNode()
            val sourceNode = compose.onNodeWithText(source).fetchSemanticsNode()
            val numberLayout = textLayout((line + 1).toString())
            val numberBaseline = numberNode.layoutInfo.coordinates.positionInRoot().y +
                numberLayout.getLineBaseline(0)
            val sourceBaseline = sourceNode.layoutInfo.coordinates.positionInRoot().y +
                sourceLayout.getLineBaseline(line)
            assertEquals("line $line baseline", sourceBaseline, numberBaseline, 1f)
        }

        // The line is beyond the 380.dp viewport, so this also proves the real
        // editor content measured past the viewport instead of being clipped by
        // a hand-derived height.
        compose.onNodeWithText("16").assertIsDisplayed()
        compose.onNodeWithText(source).assertExists()
    }

    @Test
    fun readOnlyLongSourceExposesVerticalAndHorizontalScrollActions() {
        val source = (1..14).joinToString("\n") {
            "<div>中文 👩‍💻 ${"long-line ".repeat(120)}</div>"
        }
        setEditor(source, Density(1f, 1f))

        val scrollNodes = compose
            .onAllNodes(hasScrollAction(), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("editor should expose vertical and horizontal scrolling", scrollNodes.size >= 2)
        assertTrue(
            "read-only source should expose horizontal scrolling",
            scrollNodes.any { node ->
                node.config.contains(SemanticsProperties.HorizontalScrollAxisRange)
            },
        )
    }

    private fun setEditor(source: String, density: Density) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides density,
                LocalAmberTokens provides buildAmberTokens(AmberBase.DARK, Color(0xFFB8623A)),
            ) {
                MiniAppCodeEditor(
                    source = source,
                    editable = false,
                    onSourceChange = {},
                    enabled = false,
                    scrollState = rememberScrollState(),
                )
            }
        }
    }

    private fun textLayout(text: String): TextLayoutResult {
        val node = compose.onNodeWithText(text).fetchSemanticsNode()
        assertTrue(node.config.contains(SemanticsActions.GetTextLayoutResult))
        val results = mutableListOf<TextLayoutResult>()
        val action = node.config[SemanticsActions.GetTextLayoutResult].action
        assertTrue(action?.invoke(results) == true)
        return results.single()
    }
}
