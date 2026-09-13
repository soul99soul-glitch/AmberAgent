package app.amber.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.repository.ConversationRepository
import app.amber.feature.task.AgentTaskQueueState
import app.amber.feature.task.AgentTaskRecoveryState
import app.amber.feature.task.AgentTaskRetryPolicy
import app.amber.feature.task.AgentTaskSnapshot
import app.amber.feature.task.AgentTaskStatus
import app.amber.feature.task.AgentTaskStore
import java.io.File
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin

/**
 * Emulator-only canary for the native soft chat timeline.
 *
 * It inserts two disposable conversations and three metadata-only subagent task
 * snapshots. No real subagent is created, no provider is selected or called,
 * and the approval actions are deliberately left untouched.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalUuidApi::class)
class ChatSoftTimelineSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val preferences = targetContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val originalLaunchMode = preferences.getString(LAUNCH_START_MODE_PREF, null)
    private val originalColorMode = preferences.getString(COLOR_MODE_PREF, null)

    private val fixtureConversationIds = mutableListOf<Uuid>()
    private val fixtureConversationTitles = mutableMapOf<Uuid, String>()
    private val fixtureTaskIds = mutableListOf<String>()

    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun softTimelineConversationSwitchAndGlobalTaskDock() {
        assumeTrue(
            "Run with -PuiSmokeTest=true so the real AmberAgentApp is installed",
            targetContext.applicationContext is AmberAgentApp,
        )
        assumeEmulator()
        setDeterministicUiPreferences()

        try {
            val (firstConversation, secondConversation) = prepareConversations()
            prepareTasks(firstConversation, secondConversation)

            val intent = Intent(targetContext, RouteActivity::class.java).apply {
                // RouteActivity owns this production deep-link path and lands on
                // the exact disposable conversation without opening the home list.
                putExtra("conversationId", firstConversation.toString())
            }
            ActivityScenario.launch<RouteActivity>(intent).use {
                // First surface: user bubble, mixed reasoning/tool chain, and the
                // independent pending approval card are all real persisted UI data.
                waitForVisibleText(FIRST_ASSISTANT_MARKER)
                revealText(FIRST_USER_MARKER)
                revealText(targetContext.getString(R.string.chat_message_tool_deny))
                revealText(targetContext.getString(R.string.chat_message_tool_approve))
                revealText(targetContext.getString(R.string.setting_cron_tasks_view_details))
                waitForVisibleText(TASK_TITLES.first())
                capture("01-first-collapsed")

                // The mixed chain initially keeps its last two steps. Expand the
                // chain, then the reasoning row, so the framed thinking shape and
                // its real body are visible in the second capture.
                clickVisibleText(targetContext.getString(R.string.chain_of_thought_show_more_steps, 1))
                clickVisibleText(targetContext.getString(R.string.deep_thinking_seconds, 0f))
                waitForVisibleText(REASONING_MARKER)
                capture("02-first-thinking-expanded")

                // ContextRing is a clickable parent around the percentage text.
                // Its popup is read-only and uses the production 380ms enter path.
                clickContextRing()
                waitForVisibleText(targetContext.getString(R.string.context_ring_usage_context))
                capture("03-first-context-popup")
                pressBack()
                waitForTextToDisappear(targetContext.getString(R.string.context_ring_usage_context))

                // The chat header's left control routes back to SessionHome. It is
                // a custom drawn arrow, so locate its real top-left clickable node
                // by bounds rather than adding a test-only content description.
                clickChatHeaderBack()
                waitForVisibleText(secondConversationTitle())
                clickVisibleText(secondConversationTitle())
                waitForVisibleText(SECOND_USER_MARKER)

                // Global dock continuity: switching conversations must keep all
                // three store-backed tasks visible, regardless of source session.
                TASK_TITLES.forEach(::waitForVisibleText)
                capture("04-second-tasks-running")

                val taskStore: AgentTaskStore = getKoin().get()
                runBlocking {
                    taskStore.update(
                        taskId = fixtureTaskIds.first(),
                        status = AgentTaskStatus.COMPLETED,
                        summary = "Synthetic canary completed",
                    )
                }
                waitForVisibleText(targetContext.getString(R.string.chat_message_subagent_status_completed))
                capture("05-second-task-completed")

                // Source is a read-only route back to the first fixture. This
                // matcher accepts either the localized text or the equivalent
                // accessibility description supplied by the dock implementation.
                clickTaskSource()
                waitForVisibleText(FIRST_USER_MARKER)

                // Settle the remaining two metadata-only tasks after the source
                // route has been exercised, leaving the final dock in its
                // completed-only state for the collapse check.
                runBlocking {
                    fixtureTaskIds.drop(1).forEach { taskId ->
                        taskStore.update(
                            taskId = taskId,
                            status = AgentTaskStatus.COMPLETED,
                            summary = "Synthetic canary completed",
                        )
                    }
                }
                waitForVisibleText(targetContext.getString(R.string.chat_message_subagent_status_completed))

                // Completed-only dock state can be collapsed. The test never
                // invokes any task row's provider or approval action.
                clickTerminalDockCollapse()
                TASK_TITLES.forEach(::waitForTextToDisappear)
            }
        } finally {
            cleanupFixtures()
        }
    }

    @After
    fun restoreOwnedUiPreferences() {
        preferences.edit().apply {
            if (originalLaunchMode == null) remove(LAUNCH_START_MODE_PREF)
            else putString(LAUNCH_START_MODE_PREF, originalLaunchMode)
            if (originalColorMode == null) remove(COLOR_MODE_PREF)
            else putString(COLOR_MODE_PREF, originalColorMode)
        }.apply()
    }

    private fun assumeEmulator() {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val isEmulator = fingerprint.contains("generic") ||
            fingerprint.contains("emulator") ||
            model.contains("emulator") ||
            model.contains("sdk_gphone") ||
            model.contains("android sdk built for")
        assumeTrue(
            "This canary creates disposable fixtures only on an Android emulator; " +
                "fingerprint=${Build.FINGERPRINT}, model=${Build.MODEL}",
            isEmulator,
        )
    }

    private fun setDeterministicUiPreferences() {
        preferences.edit()
            .putString(LAUNCH_START_MODE_PREF, LaunchStartMode.HOME.name)
            .putString(COLOR_MODE_PREF, COLOR_MODE_LIGHT)
            .apply()
    }

    private fun prepareConversations(): Pair<Uuid, Uuid> {
        val repository: ConversationRepository = getKoin().get()
        val firstId = Uuid.random()
        val secondId = Uuid.random()
        val firstTitle = "Canary conversation A"
        val secondTitle = "Canary conversation B"
        fixtureConversationIds += firstId
        fixtureConversationIds += secondId
        fixtureConversationTitles[firstId] = firstTitle
        fixtureConversationTitles[secondId] = secondTitle

        check(runBlocking { repository.getConversationSummaryById(firstId) == null }) {
            "Timeline fixture UUID already exists: $firstId"
        }
        check(runBlocking { repository.getConversationSummaryById(secondId) == null }) {
            "Timeline fixture UUID already exists: $secondId"
        }

        val now = Clock.System.now()
        val reasoning = UIMessagePart.Reasoning(
            reasoning = "$REASONING_MARKER\n\nThe local fixture keeps reasoning, an ordinary read, and a pending write as separate timeline steps.",
            createdAt = now,
            finishedAt = now,
        )
        val ordinaryTool = UIMessagePart.Tool(
            toolCallId = "soft-timeline-read-$firstId",
            toolName = "file_read",
            input = "{\"path\":\"$ORDINARY_TOOL_PATH\"}",
            output = listOf(
                UIMessagePart.Text(
                    "{\"status\":\"succeeded\",\"content\":\"$ORDINARY_TOOL_MARKER\"}",
                ),
            ),
        )
        val pendingTool = UIMessagePart.Tool(
            toolCallId = "soft-timeline-pending-$firstId",
            toolName = "file_write",
            input = "{\"path\":\"$PENDING_TOOL_PATH\",\"content\":\"synthetic fixture\"}",
            approvalState = ToolApprovalState.Pending,
        )
        val firstAssistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                reasoning,
                ordinaryTool,
                pendingTool,
                UIMessagePart.Text(FIRST_ASSISTANT_MARKER),
            ),
        )
        val firstConversation = Conversation.ofId(
            id = firstId,
            messages = listOf(
                MessageNode.of(UIMessage.user(FIRST_USER_MARKER)),
                MessageNode.of(firstAssistant),
            ),
        ).copy(title = firstTitle)
        val secondConversation = Conversation.ofId(
            id = secondId,
            messages = listOf(
                MessageNode.of(UIMessage.user(SECOND_USER_MARKER)),
                MessageNode.of(UIMessage.assistant(SECOND_ASSISTANT_MARKER)),
            ),
        ).copy(title = secondTitle)

        runBlocking {
            repository.insertConversation(firstConversation)
            check(repository.getConversationSummaryById(firstId)?.title == firstTitle) {
                "First timeline fixture was not durably inserted"
            }
            repository.insertConversation(secondConversation)
            check(repository.getConversationSummaryById(secondId)?.title == secondTitle) {
                "Second timeline fixture was not durably inserted"
            }
        }
        return firstId to secondId
    }

    private fun prepareTasks(firstConversation: Uuid, secondConversation: Uuid) {
        val taskStore: AgentTaskStore = getKoin().get()
        val now = System.currentTimeMillis()
        runBlocking {
            TASK_TITLES.forEachIndexed { index, title ->
                val taskId = Uuid.random().toString()
                check(taskStore.read(taskId) == null) { "Task fixture UUID already exists: $taskId" }
                fixtureTaskIds += taskId
                taskStore.register(
                    AgentTaskSnapshot(
                        taskId = taskId,
                        type = "subagent",
                        title = title,
                        spec = null,
                        runtime = "chat-soft-timeline-canary",
                        queueState = AgentTaskQueueState.ACTIVE,
                        recoveryState = AgentTaskRecoveryState.ACTIVE,
                        retryPolicy = AgentTaskRetryPolicy(retryable = false),
                        sourceConversationId = if (index == 1) {
                            secondConversation.toString()
                        } else {
                            firstConversation.toString()
                        },
                        status = AgentTaskStatus.RUNNING,
                        createdAtMs = now,
                        updatedAtMs = now,
                        summary = "Synthetic canary output",
                    ),
                )
            }
        }
    }

    private fun secondConversationTitle(): String =
        fixtureConversationTitles[fixtureConversationIds[1]]
            ?: error("Second conversation fixture title is missing")

    private fun clickContextRing() {
        clickVisibleMatcher(
            hasClickAction() and hasAnyDescendant(hasText("%", substring = true)),
            description = "ContextRing",
        )
    }

    private fun clickChatHeaderBack() {
        val candidates = compose.onAllNodes(hasClickAction(), useUnmergedTree = true)
        compose.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
            candidates.fetchSemanticsNodes().any(::isTopLeftHeaderNode)
        }
        val nodes = candidates.fetchSemanticsNodes()
        val index = nodes.indexOfFirst(::isTopLeftHeaderNode)
        check(index >= 0) { "Chat header back action was not reachable" }
        candidates[index].assertIsDisplayed().performClick()
        compose.waitForIdle()
    }

    private fun isTopLeftHeaderNode(node: androidx.compose.ui.semantics.SemanticsNode): Boolean {
        val bounds = node.boundsInRoot
        val density = targetContext.resources.displayMetrics.density
        return bounds.top < density * 96f &&
            bounds.left < density * 64f &&
            bounds.right < density * 80f
    }

    private fun clickTaskSource() {
        val sourceText = hasText("来源", substring = true) or hasText("Source", substring = true)
        val sourceDescription = hasContentDescription("来源", substring = true) or
            hasContentDescription("Source", substring = true)
        val direct = hasClickAction() and (sourceText or sourceDescription)
        val ancestor = hasClickAction() and hasAnyDescendant(sourceText or sourceDescription)
        clickVisibleMatcher(direct or ancestor, description = "task source")
    }

    private fun clickTerminalDockCollapse() {
        val collapseText = hasText("收起", substring = true) or
            hasText("Collapse", substring = true) or
            hasText("隐藏任务", substring = true) or
            hasText("Hide tasks", substring = true)
        val collapseDescription = hasContentDescription("收起", substring = true) or
            hasContentDescription("Collapse", substring = true) or
            hasContentDescription("隐藏任务", substring = true) or
            hasContentDescription("Hide tasks", substring = true)
        clickVisibleMatcher(
            hasClickAction() and (collapseText or collapseDescription),
            description = "terminal task dock collapse",
        )
    }

    private fun clickVisibleText(value: String) {
        revealText(value)
        val textMatcher = hasText(value, substring = true)
        val direct = hasClickAction() and textMatcher
        val ancestor = hasClickAction() and hasAnyDescendant(textMatcher)
        clickVisibleMatcher(direct or ancestor, description = "text=$value")
    }

    private fun clickVisibleMatcher(matcher: SemanticsMatcher, description: String) {
        val matches = compose.onAllNodes(matcher, useUnmergedTree = true)
        compose.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
            visibleIndex(matches) >= 0
        }
        val index = visibleIndex(matches)
        check(index >= 0) { "No visible clickable node for $description" }
        matches[index].assertIsDisplayed().performClick()
        compose.waitForIdle()
    }

    private fun revealText(value: String) {
        val target = hasText(value, substring = true)
        val matches = compose.onAllNodes(target, useUnmergedTree = true)
        val composedCount = matches.fetchSemanticsNodes().size
        if (composedCount == 0) {
            val scrollers = compose.onAllNodes(hasScrollAction(), useUnmergedTree = true)
            check(scrollers.fetchSemanticsNodes().isNotEmpty()) {
                "No scroll container can compose target text=$value"
            }
            scrollers.onFirst().performScrollToNode(target)
        } else {
            val first = (0 until composedCount).firstOrNull { index ->
                runCatching { matches[index].isDisplayed() }.getOrDefault(false)
            }
            if (first == null) {
                matches[0].performScrollTo()
            }
        }
        waitForVisibleText(value)
    }

    private fun waitForVisibleText(value: String) {
        compose.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
            val matches = compose.onAllNodesWithText(
                value,
                substring = true,
                useUnmergedTree = true,
            )
            visibleIndex(matches) >= 0
        }
    }

    private fun waitForTextToDisappear(value: String) {
        compose.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
            val matches = compose.onAllNodesWithText(
                value,
                substring = true,
                useUnmergedTree = true,
            )
            visibleIndex(matches) < 0
        }
    }

    private fun visibleIndex(matches: androidx.compose.ui.test.SemanticsNodeInteractionCollection): Int {
        val count = matches.fetchSemanticsNodes().size
        return (0 until count).firstOrNull { index ->
            runCatching { matches[index].isDisplayed() }.getOrDefault(false)
        } ?: -1
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        // Cover the ContextRing enter and the dock's settling transition before
        // writing a screenshot, without depending on a fixed device density.
        SystemClock.sleep(450)
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val directory = targetContext.getExternalFilesDir(SCREENSHOT_DIRECTORY)
            ?: error("Target app has no external files directory")
        require(directory.exists() || directory.mkdirs()) {
            "Unable to create screenshot directory: ${directory.absolutePath}"
        }
        val file = File(directory, "$name.png")
        file.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Unable to encode screenshot: ${file.absolutePath}"
            }
        }
        bitmap.recycle()
        check(file.length() > 0L) { "Screenshot was not written: ${file.absolutePath}" }
        println("ChatSoftTimelineSmokeTest screenshot=${file.absolutePath}")
    }

    private fun cleanupFixtures() {
        val taskStore: AgentTaskStore = runCatching { getKoin().get() }.getOrNull()
        val repository: ConversationRepository = runCatching { getKoin().get() }.getOrNull()
        var firstFailure: Throwable? = null
        runBlocking {
            taskStore?.let { store ->
                fixtureTaskIds.toList().forEach { taskId ->
                    runCatching { store.remove(taskId) }.onFailure { error ->
                        if (firstFailure == null) firstFailure = error
                    }
                }
            }
            repository?.let { repo ->
                fixtureConversationIds.toList().forEach { conversationId ->
                    runCatching {
                        val existing = repo.getConversationById(conversationId)
                        val expectedTitle = fixtureConversationTitles[conversationId]
                        if (existing?.title == expectedTitle) {
                            repo.deleteConversation(existing)
                        }
                    }.onFailure { error ->
                        if (firstFailure == null) firstFailure = error
                    }
                }
            }
        }
        fixtureTaskIds.clear()
        fixtureConversationIds.clear()
        fixtureConversationTitles.clear()
        firstFailure?.let { throw it }
    }

    private companion object {
        const val PREFERENCES_NAME = "amber_agent.preferences"
        const val LAUNCH_START_MODE_PREF = "launchStartMode"
        const val COLOR_MODE_PREF = "colorMode"
        const val COLOR_MODE_LIGHT = "LIGHT"
        const val SCREENSHOT_DIRECTORY = "chat-soft-timeline-smoke"
        const val WAIT_TIMEOUT_MS = 15_000L
        const val ORDINARY_TOOL_PATH = "chat-soft-timeline/ordinary.txt"
        const val PENDING_TOOL_PATH = "chat-soft-timeline/pending.txt"
        const val ORDINARY_TOOL_MARKER = "soft timeline ordinary output"
        const val REASONING_MARKER = "soft timeline reasoning marker"
        const val FIRST_USER_MARKER = "soft timeline first user marker"
        const val FIRST_ASSISTANT_MARKER = "soft timeline first assistant marker"
        const val SECOND_USER_MARKER = "soft timeline second user marker"
        const val SECOND_ASSISTANT_MARKER = "soft timeline second assistant marker"
        val TASK_TITLES = listOf(
            "Canary task A",
            "Canary task B",
            "Canary task C",
        )
    }
}
