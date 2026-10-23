package app.amber.feature.ui.pages.sessionhome

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.swipeLeft
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import app.amber.feature.ui.theme.LocalAmberTokens
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.model.Conversation
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeSessionRowSwipeDeviceTest {
    private val compose = createComposeRule()
    // Some vendor systems reject ActivityScenario's first background launch.
    // Bring only the test host forward before the Compose rule launches it.
    @get:Rule val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(
        object : org.junit.rules.ExternalResource() {
            override fun before() {
                val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                val packageName = instrumentation.targetContext.packageName
                instrumentation.uiAutomation.executeShellCommand(
                    "am start -W -n $packageName/androidx.activity.ComponentActivity",
                ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            }
        },
    ).around(compose)

    @Test fun ltrStartToEndFollowsFingerAndPins() = swipe(LayoutDirection.Ltr, delete = false)
    @Test fun ltrEndToStartFollowsFingerAndDeletes() = swipe(LayoutDirection.Ltr, delete = true)
    @Test fun rtlStartToEndFollowsFingerAndPins() = swipe(LayoutDirection.Rtl, delete = false)
    @Test fun rtlEndToStartFollowsFingerAndDeletes() = swipe(LayoutDirection.Rtl, delete = true)

    @Test fun undoRestoresProductionPagingRowAndAllowsAnotherSwipe() {
        val enqueues = AtomicInteger()
        val durableDeletes = AtomicInteger()
        val conversation = Conversation(
            assistantId = AMBER_AGENT_ID, title = "Undo fixture only", messageNodes = emptyList(),
        )
        compose.setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val owner = remember {
                    HomeDeleteUndo(scope, android.os.SystemClock::elapsedRealtime,
                        delete = { durableDeletes.incrementAndGet() }, exists = { true })
                }
                val state = owner.state.collectAsState().value
                val source = remember {
                    observeVisibleHomeConversations(
                        flowOf(PagingData.from(listOf(conversation))),
                        owner.state.map { it.hiddenIds }, scope,
                    )
                }
                val items = source.collectAsLazyPagingItems()
                val tokens = LocalAmberTokens.current
                Box(Modifier.fillMaxSize()) {
                    LazyColumn {
                        homeConversationItems(
                            conversations = items, hasConversationError = false,
                            rowVersions = state.rowVersions, homeSearchQuery = "",
                            lastConversationId = null, tokens = tokens, onRetry = {}, onOpen = {},
                            onDelete = { enqueues.incrementAndGet(); owner.enqueue(it) }, onTogglePin = {},
                        )
                    }
                    if (state.pendingIds.isNotEmpty()) {
                        HomeDeleteUndoBar(
                            count = state.pendingIds.size, seconds = state.remainingSeconds,
                            onUndo = { owner.undo() },
                            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
        repeat(2) { index ->
            compose.onNodeWithText(conversation.title).assertIsDisplayed().performTouchInput { swipeLeft() }
            compose.waitUntil(5_000) { enqueues.get() == index + 1 }
            compose.onNodeWithTag("home-delete-undo-action").assertIsDisplayed().performClick()
            compose.onNodeWithText(conversation.title).assertIsDisplayed()
            compose.onNodeWithTag("home-delete-undo").assertDoesNotExist()
            assertEquals(index + 1, enqueues.get())
            assertEquals(0, durableDeletes.get())
        }
    }

    private fun swipe(direction: LayoutDirection, delete: Boolean) {
        val pins = AtomicInteger()
        val deletes = AtomicInteger()
        val title = "Swipe direction fixture"
        val conversation = Conversation(assistantId = AMBER_AGENT_ID, title = title, messageNodes = emptyList())
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        HomeSessionRow(
                            conversation = conversation,
                            tileColor = Color.Gray,
                            isLastVisited = false,
                            isFirst = true,
                            isLast = true,
                            onOpen = {},
                            onDelete = { deletes.incrementAndGet() },
                            onTogglePin = { pins.incrementAndGet() },
                            modifier = Modifier.testTag("swipe-row"),
                        )
                    }
                }
            }
        }
        val row = compose.onNodeWithTag("swipe-row")
        val text = compose.onNodeWithText(title, useUnmergedTree = true)
        // Pin is start -> end; delete is end -> start. In RTL both physical
        // directions mirror, but the visible row must always follow the finger.
        val sign = (if (direction == LayoutDirection.Rtl) -1f else 1f) * (if (delete) -1f else 1f)
        repeat(if (delete) 1 else 2) { completed ->
            val initialX = text.getUnclippedBoundsInRoot().left.value
            row.performTouchInput {
                down(center)
                moveBy(Offset(sign * width * 0.25f, 0f), delayMillis = 250)
            }
            compose.waitForIdle()
            val displacement = text.getUnclippedBoundsInRoot().left.value - initialX
            assertTrue("$direction delete=$delete moved $displacement against finger sign=$sign",
                displacement * sign > 10f)
            row.performTouchInput {
                moveBy(Offset(sign * width * 0.4f, 0f), delayMillis = 400)
            }
            compose.waitForIdle()
            assertEquals("No action while pointer is held", completed, pins.get() + deletes.get())
            row.performTouchInput {
                advanceEventTime(200)
                up()
            }
            compose.waitUntil(5_000) { pins.get() + deletes.get() > completed }
            compose.waitForIdle()
            assertEquals(if (delete) 0 else completed + 1, pins.get())
            assertEquals(if (delete) 1 else 0, deletes.get())
        }
    }
}
