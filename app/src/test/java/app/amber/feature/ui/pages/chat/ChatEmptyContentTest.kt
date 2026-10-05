package app.amber.feature.ui.pages.chat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.AmberTypography
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.buildAmberTokens
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp")
class ChatEmptyContentTest {
    @get:Rule val compose = createComposeRule()
    private val greeting = "Hi Arquiel，今天想聊点什么？"
    private val tasks = listOf(
        "帮我梳理长篇小说人物关系和各章节推进的关键线索",
        "帮我检查安卓流式消息渲染过程中的布局和滚动问题",
        "帮我整理本周项目进展并梳理下周需要关注的工作项",
    ).map { it.take(24) }
    private var selected: String? = null

    @Test fun normalPortraitKeepsAllThreeActionsReadableAndClickable() {
        render(393, 600, 1f, "portrait")
        assertNoOverlap()
        tasks.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText(tasks.first()).performClick()
        assertEquals(tasks.first(), selected)
    }

    @Test fun narrowContentDoesNotOverlapGreeting() {
        render(320, 400, 1f, "narrow")
        assertNoOverlap()
    }

    @Test fun largeTextKeepsActionsAccessibleWithoutOverlappingGreeting() {
        render(320, 500, 1.5f, "large-text")
        assertNoOverlap()
        compose.onNodeWithText(tasks.last()).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(tasks.last(), selected)
    }

    @Test fun wrappedSuggestionLinesAreCentered() {
        render(320, 600, 1f, "multiline")
        val layouts = mutableListOf<TextLayoutResult>()
        val node = compose.onNodeWithText(tasks.first(), useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
        val layout = layouts.single()
        assertTrue("This case must wrap", layout.lineCount > 1)
        assertFalse(layout.hasVisualOverflow)
        for (line in 0 until layout.lineCount) {
            val midpoint = (layout.getLineLeft(line) + layout.getLineRight(line)) / 2f
            assertEquals(layout.size.width / 2f, midpoint, 1f)
        }
    }

    private fun assertNoOverlap() {
        val hero = compose.onNodeWithText(greeting).fetchSemanticsNode().boundsInRoot
        val chips = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertEquals(3, chips.size)
        for (chip in chips) {
            val bounds = chip.boundsInRoot
            assertFalse("hero=$hero intersects chip=$bounds",
                hero.left < bounds.right && bounds.left < hero.right && hero.top < bounds.bottom && bounds.top < hero.bottom)
        }
    }

    private fun render(width: Int, height: Int, fontScale: Float, name: String) {
        var renderedView: View? = null
        compose.setContent {
            renderedView = LocalView.current
            val tokens = buildAmberTokens(AmberBase.LIGHT, Color(0xFFB8623A))
            CompositionLocalProvider(LocalAmberTokens provides tokens, LocalDensity provides Density(1f, fontScale)) {
                MaterialTheme(typography = AmberTypography) {
                    Box(Modifier.width(width.dp).height(height.dp).background(tokens.bg).testTag("canvas")) {
                        ChatEmptyContent(greeting, tasks, loading = false, showSuggestions = true, onSelect = { selected = it })
                    }
                }
            }
        }
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("canvas").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val view = requireNotNull(renderedView)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val cropped = Bitmap.createBitmap(bitmap, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
            val file = File("build/reports/chat-empty-ui/$name.png")
            file.parentFile.mkdirs()
            file.outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
