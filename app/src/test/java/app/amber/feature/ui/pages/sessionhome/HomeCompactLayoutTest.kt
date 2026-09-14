package app.amber.feature.ui.pages.sessionhome

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.core.settings.Settings
import app.amber.feature.home.ContinueCandidate
import app.amber.feature.home.ContinueRoute
import app.amber.feature.home.ContinueSourceKind
import app.amber.feature.home.ContinueStatus
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.buildAmberTokens
import java.time.Instant
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w373dp-h812dp")
class HomeCompactLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val article = "A misalignment of incentives in modern systems"
    private val phase = "正在生成概览"

    @Test
    fun narrowLargeTextHeaderShowsWholeDateAndSearchStillOpens() {
        var searches = 0
        content(width = 320, fontScale = 1.5f) {
            HomeHeader(
                settings = Settings(),
                searchExpanded = false,
                onOpenSearch = { searches++ },
                onOpenSettings = {},
                onOpenProfile = {},
            )
        }
        val date = compose.onNode(hasText("今天 ·", substring = true), useUnmergedTree = true)
            .fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue(date.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
        val layout = layouts.single()
        assertFalse("the complete date must fit at 320dp and 150% text", layout.hasVisualOverflow)
        for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
        compose.onNodeWithContentDescription(context.getString(R.string.history_page_search))
            .performClick()
        assertEquals(1, searches)
    }

    @Test
    fun idleCardHasTitleRightAlignedContinueAndCompactHeight() {
        var opened: ContinueCandidate? = null
        val candidate = candidate(running = false)
        content {
            HomeFeatureRail(
                resumeCandidate = candidate,
                onOpenResume = { opened = it },
                onDeepRead = {}, onMiniApps = {}, onNovel = {}, onWebMount = {}, onCouncil = {},
            )
        }
        compose.onNodeWithText(article, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(phase, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.session_home_hide_continue_candidate))
            .assertDoesNotExist()
        val action = context.getString(R.string.session_home_continue)
        val actionBounds = compose.onNodeWithText(action, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val titleBounds = compose.onNodeWithText(article, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val rootBounds = compose.onNodeWithTag("home-test-root").fetchSemanticsNode().boundsInRoot
        assertTrue("continue text leaves only card and button padding at the trailing edge: $actionBounds", rootBounds.right - actionBounds.right <= 46f)
        assertTrue("title and continue must not overlap", titleBounds.right <= actionBounds.left)
        assertTrue("card including outer margins should stay compact", compose.onNodeWithTag("home-test-root").fetchSemanticsNode().boundsInRoot.height <= 140f)
        compose.onNode(hasText(action) and hasClickAction()).performClick()
        assertEquals(candidate.route, opened?.route)
    }

    @Test
    fun runningSubtitleCrossfadesToRealStageAndReturnsToTitleWhenPaused() {
        compose.mainClock.autoAdvance = false
        val state = mutableStateOf(candidate(running = true))
        content {
            HomeFeatureRail(
                resumeCandidate = state.value,
                onOpenResume = {},
                onDeepRead = {}, onMiniApps = {}, onNovel = {}, onWebMount = {}, onCouncil = {},
            )
        }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText(article, useUnmergedTree = true).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(4_500)
        compose.onNodeWithText(phase, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(article, useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(isRunning = false) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText(article, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(phase, useUnmergedTree = true).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(8_000)
        compose.onNodeWithText(article, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun resumeCountBadgeSharesThePillWithoutAddingHeight() {
        var opened = 0
        var resumed = 0
        var count by mutableStateOf(1)
        content {
            HomeFeatureRail(
                resumeCandidate = candidate(running = false),
                resumeCount = count,
                onOpenResume = { resumed++ },
                onChooseResume = { opened++ },
                onDeepRead = {}, onMiniApps = {}, onNovel = {}, onWebMount = {}, onCouncil = {},
            )
        }
        val label = context.getString(R.string.session_home_continue)
        val initialHeight = compose.onRoot().fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithText(label).performClick()
        assertEquals(1, resumed)
        assertEquals(0, opened)
        compose.runOnIdle { count = 3 }
        val continueText = compose.onNodeWithText(label, useUnmergedTree = true)
        val countText = compose.onNodeWithText("3", useUnmergedTree = true)
        assertEquals(initialHeight, compose.onRoot().fetchSemanticsNode().boundsInRoot.height, 0.5f)
        assertEquals(
            continueText.fetchSemanticsNode().boundsInRoot.center.y,
            countText.fetchSemanticsNode().boundsInRoot.center.y,
            0.5f,
        )
        compose.onNodeWithText("$label · 2").assertDoesNotExist()
        compose.onNodeWithText(label).performClick()
        assertEquals(1, opened)
        assertEquals(1, resumed)
    }

    @Test
    fun continueCandidatesSheetReturnsTheSelectedCandidate() {
        var opened: ContinueCandidate? = null
        val first = candidate(running = false, id = "candidate-one", title = "first candidate")
        val second = candidate(running = false, id = "candidate-two", title = "second candidate")
        compose.setContent {
            CompositionLocalProvider(
                LocalAmberTokens provides buildAmberTokens(AmberBase.LIGHT, Color(0xFFB8623A)),
            ) {
                MaterialTheme {
                    ContinueCandidatesSheet(
                        candidates = listOf(first, second),
                        onOpen = { opened = it },
                        onDismiss = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("second candidate", useUnmergedTree = true).performClick()
        assertEquals(second.route, opened?.route)
    }

    @Test
    fun homeTopFadeStaysAtClippedEdgeUntilReturningToStart() {
        compose.mainClock.autoAdvance = false
        lateinit var listState: LazyListState
        lateinit var scrollScope: CoroutineScope
        compose.setContent {
            CompositionLocalProvider(
                LocalSettings provides Settings(),
                LocalAmberTokens provides buildAmberTokens(AmberBase.LIGHT, Color(0xFFB8623A)),
            ) {
                MaterialTheme {
                    val state = rememberLazyListState()
                    listState = state
                    scrollScope = rememberCoroutineScope()
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                            item {
                                HomeHeader(
                                    settings = Settings(),
                                    searchExpanded = false,
                                    onOpenSearch = {},
                                    onOpenSettings = {},
                                    onOpenProfile = {},
                                )
                            }
                            items((0 until 32).toList()) { index ->
                                Text("row $index", modifier = Modifier.height(64.dp))
                            }
                        }
                        HomeScrollTopFade(
                            hazeState = rememberHazeState(),
                            visible = state.canScrollBackward,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNode(hasText("今天 ·", substring = true), useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithTag("home-scroll-top-fade", useUnmergedTree = true)
            .assertDoesNotExist()

        compose.runOnIdle {
            scrollScope.launch { listState.animateScrollToItem(20) }
        }
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithTag("home-scroll-top-fade", useUnmergedTree = true)
            .assertIsDisplayed()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNode(hasText("今天 ·", substring = true), useUnmergedTree = true)
            .assertDoesNotExist()
        compose.mainClock.advanceTimeBy(220)
        assertFalse(listState.isScrollInProgress)
        compose.onNodeWithTag("home-scroll-top-fade", useUnmergedTree = true)
            .assertIsDisplayed()
        compose.runOnIdle {
            scrollScope.launch { listState.scrollToItem(0) }
        }
        compose.mainClock.advanceTimeBy(220)
        compose.onNodeWithTag("home-scroll-top-fade", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    private fun candidate(
        running: Boolean,
        id: String = "home-layout-fixture",
        title: String = article,
    ) = ContinueCandidate(
        sourceKind = ContinueSourceKind.DEEP_READ,
        sourceId = id,
        route = ContinueRoute.DeepRead(id, title, "https://example.com/article"),
        title = title,
        summary = phase,
        lastUpdatedAt = Instant.EPOCH,
        status = ContinueStatus.FAILED_RESUMABLE,
        isRunning = running,
    )

    private fun content(width: Int = 373, fontScale: Float = 1f, block: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale),
                LocalSettings provides Settings(),
                LocalAmberTokens provides buildAmberTokens(AmberBase.LIGHT, Color(0xFFB8623A)),
            ) {
                MaterialTheme {
                    Box(Modifier.width(width.dp).testTag("home-test-root")) { block() }
                }
            }
        }
    }
}
