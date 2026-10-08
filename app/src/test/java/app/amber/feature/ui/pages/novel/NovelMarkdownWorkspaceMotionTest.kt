package app.amber.feature.ui.pages.novel

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
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
class NovelMarkdownWorkspaceMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun outgoingPageStaysComposedButCannotReceivePointerOrAccessibilityActions() {
        val target = mutableStateOf(0)
        val composedPages = mutableSetOf<Int>()
        val clicks = IntArray(2)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().testTag("motion-root")) {
                    AnimatedContent(
                        targetState = target.value,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            (fadeIn(tween(700)) togetherWith fadeOut(tween(700)))
                                .using(SizeTransform(clip = false))
                        },
                        label = "workspace-test-pages",
                    ) { page ->
                        DisposableEffect(page) {
                            composedPages.add(page)
                            onDispose { composedPages.remove(page) }
                        }
                        Box(Modifier.fillMaxSize().workspaceTransitionInput(active = page == target.value)) {
                            Button(
                                onClick = { clicks[page]++ },
                                modifier = Modifier.offset(y = if (page == 0) 20.dp else 160.dp)
                                    .width(180.dp).height(56.dp),
                            ) {
                                Text("Page $page action")
                            }
                        }
                    }
                }
            }
        }
        pauseClockAfterInitialComposition()
        val root = compose.onNodeWithTag("motion-root")
        val oldClickPosition = compose.onNodeWithText("Page 0 action").fetchSemanticsNode().boundsInRoot.center -
            root.fetchSemanticsNode().boundsInRoot.topLeft

        compose.runOnIdle { target.value = 1 }
        pumpFrames(4)
        compose.runOnIdle { assertTrue("The test must exercise overlapping pages: $composedPages", composedPages.containsAll(listOf(0, 1))) }
        compose.onNodeWithText("Page 0 action").assertDoesNotExist()
        compose.onNodeWithText("Page 1 action").assertExists()
        root.performTouchInput { click(oldClickPosition) }
        assertEquals("The departing page must reject a click at its old location", 0, clicks[0])
        compose.onNodeWithText("Page 1 action").performTouchInput { click() }
        assertEquals(1, clicks[1])
        compose.onNodeWithText("Page 1 action").performClick()
        assertEquals("The current page must also retain its accessibility action", 2, clicks[1])

        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.runOnIdle { target.value = 0 }
        pumpFrames(2)
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onNodeWithText("Page 0 action").performTouchInput { click() }
        assertEquals("The same page must receive actions when it becomes current again", 1, clicks[0])
        compose.onNodeWithText("Page 1 action").assertDoesNotExist()
    }

    @Test
    fun rapidReverseRestoresCurrentFullPageTouchAndKeepsDepartingActionUnavailable() {
        val target = mutableStateOf(0)
        val clicks = IntArray(2)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().testTag("full-page-root")) {
                    AnimatedContent(
                        targetState = target.value,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            fadeIn(tween(700)) togetherWith fadeOut(tween(700))
                        },
                        label = "workspace-test-full-pages",
                    ) { page ->
                        Box(Modifier.fillMaxSize().workspaceTransitionInput(active = page == target.value)) {
                            Button(
                                onClick = { clicks[page]++ },
                                modifier = Modifier.offset(y = if (page == 0) 20.dp else 160.dp)
                                    .width(180.dp).height(56.dp),
                            ) {
                                Text("Full page $page action")
                            }
                        }
                    }
                }
            }
        }
        pauseClockAfterInitialComposition()
        compose.onNodeWithText("Full page 0 action").assertExists()
        compose.runOnIdle { target.value = 1 }
        pumpFrames(3)
        compose.onNodeWithText("Full page 1 action").performTouchInput { click() }
        assertEquals(1, clicks[1])
        val root = compose.onNodeWithTag("full-page-root")
        val departingPosition = compose.onNodeWithText("Full page 1 action").fetchSemanticsNode().boundsInRoot.center -
            root.fetchSemanticsNode().boundsInRoot.topLeft

        compose.runOnIdle { target.value = 0 }
        pumpFrames(3)
        compose.onNodeWithText("Full page 1 action").assertDoesNotExist()
        compose.onNodeWithText("Full page 0 action").performTouchInput { click() }
        assertEquals("The outgoing full-page gate must not swallow the current page's touch", 1, clicks[0])
        root.performTouchInput { click(departingPosition) }
        assertEquals("The outgoing page must not regain its old action during the reverse", 1, clicks[1])
    }

    @Test
    fun rapidMiddleTabReturnRemainsTouchableDuringOverlap() {
        val target = mutableStateOf(0)
        val composedPages = mutableSetOf<Int>()
        val clicks = IntArray(3)
        var middlePageMounts = 0
        compose.setContent {
            MaterialTheme {
                AnimatedContent(
                    targetState = target.value,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(700)) },
                    label = "workspace-test-three-pages",
                ) { page ->
                    DisposableEffect(page) {
                        if (page == 1) middlePageMounts++
                        composedPages.add(page)
                        onDispose { composedPages.remove(page) }
                    }
                    Box(Modifier.fillMaxSize().workspaceTransitionInput(active = page == target.value)) {
                        Button(
                            onClick = { clicks[page]++ },
                            modifier = Modifier.offset(y = 20.dp).width(180.dp).height(56.dp),
                        ) {
                            Text("Tab $page action")
                        }
                    }
                }
            }
        }
        pauseClockAfterInitialComposition()
        compose.onNodeWithText("Tab 0 action").assertExists()
        listOf(1, 2, 1).forEach { next ->
            compose.runOnIdle { target.value = next }
            pumpFrames(3)
        }
        compose.runOnIdle {
            println("Middle tab return: page 1 mounts=$middlePageMounts; composed pages=$composedPages")
            assertTrue("The current tab must coexist with the later outgoing tab: $composedPages", composedPages.containsAll(listOf(1, 2)))
        }
        compose.onNodeWithText("Tab 0 action").assertDoesNotExist()
        compose.onNodeWithText("Tab 2 action").assertDoesNotExist()
        compose.onNodeWithText("Tab 1 action").performTouchInput { click() }
        assertEquals("The returned current tab must receive the physical touch", 1, clicks[1])
        assertEquals(0, clicks[0])
        assertEquals(0, clicks[2])
    }

    @Test
    fun rapidOptionChangesDuringMotionKeepLatestSelectionWithoutRepeatingCallbacks() {
        val selected = mutableStateOf(0)
        val choices = mutableListOf<Int>()
        compose.setContent {
            MaterialTheme {
                NovelWorkspaceOptions(
                    labels = listOf("Create", "Manuscript", "Materials"),
                    selected = selected.value,
                    onSelect = { choices.add(it); selected.value = it },
                )
            }
        }
        compose.onNodeWithText("Create").assertIsSelected()
        pauseClockAfterInitialComposition()
        compose.onNodeWithText("Manuscript").performClick()
        pumpFrames(2)
        compose.onNodeWithText("Materials").performClick()
        pumpFrames(2)
        compose.onNodeWithText("Create").performClick()
        pumpFrames(2)
        compose.onNodeWithText("Create").assertIsSelected()
        compose.onNodeWithText("Manuscript").assertIsNotSelected()
        compose.onNodeWithText("Materials").assertIsNotSelected()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithText("Create").assertIsSelected()
        assertEquals(listOf(1, 2, 0), choices)
    }

    private fun pauseClockAfterInitialComposition() {
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    /** Let native looper snapshot notifications run between manual recomposition frames. */
    private fun pumpFrames(count: Int) {
        repeat(count) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }
}
