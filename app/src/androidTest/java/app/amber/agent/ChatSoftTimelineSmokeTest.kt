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
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
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
import app.amber.core.settings.AgentOperationPreviewMode
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.task.AgentTaskQueueState
import app.amber.feature.task.AgentTaskRecoveryState
import app.amber.feature.task.AgentTaskRetryPolicy
import app.amber.feature.task.AgentTaskSnapshot
import app.amber.feature.task.AgentTaskStatus
import app.amber.feature.task.AgentTaskStore
import app.amber.feature.ui.components.ai.SubAgentDockState
import java.io.File
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    private var originalSettings: Settings? = null

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

        try {
            setDeterministicUiPreferences()
            val (firstConversation, secondConversation) = prepareConversations()
            prepareTasks(firstConversation, secondConversation)
            awaitEagerDockSeed()

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
                val approvalDetailsLabel = targetContext.getString(R.string.setting_cron_tasks_view_details)
                revealText(approvalDetailsLabel)
                clickVisibleText(approvalDetailsLabel)
                waitForVisibleText(targetContext.getString(R.string.chat_message_tool_call_title))
                waitForVisibleText(
                    targetContext.getString(
                        R.string.chat_message_tool_call_label,
                        "file_write",
                    ),
                )
                waitForVisibleText(PENDING_TOOL_PATH)
                pressBack()
                waitForTextToDisappear(targetContext.getString(R.string.chat_message_tool_call_title))
                waitForVisibleText(TASK_TITLES.first())
                capture("01-first-collapsed", anchor = approvalDetailsLabel)

                // The compact rail is intentionally a LazyRow: the third pill
                // need not be composed on a phone-width viewport. Expand the
                // real header first, then verify all three in the two-column grid.
                ensureTaskDockExpanded()
                TASK_TITLES.forEach(::waitForVisibleText)

                // The mixed chain initially keeps its last two steps. Expand the
                // chain, then the reasoning row, so the framed thinking shape and
                // its real body are visible in the second capture.
                clickVisibleText(targetContext.getString(R.string.chain_of_thought_show_more_steps, 1))
                clickVisibleText(targetContext.getString(R.string.deep_thinking_seconds, 0f))
                waitForVisibleText(REASONING_MARKER)
                capture("02-first-thinking-expanded", anchor = targetContext.getString(R.string.deep_thinking_seconds, 0f))

                // ContextRing is a clickable parent around the percentage text.
                // Its popup is read-only and uses the production 380ms enter path.
                clickContextRing()
                waitForVisibleText(targetContext.getString(R.string.context_ring_usage_context))
                capture("03-first-context-popup")
                pressBack()
                waitForTextToDisappear(targetContext.getString(R.string.context_ring_usage_context))

                // Navigate through the real global dock's source action, so both
                // directions exercise the feature without geometric header selectors.
                openTaskDetails(TASK_TITLES[1])
                clickVisibleText(targetContext.getString(R.string.subagent_dock_source_conversation))
                waitForVisibleText(SECOND_USER_MARKER)

                // Global dock continuity: switching conversations must keep all
                // three store-backed tasks visible, regardless of source session.
                waitForVisibleText(TASK_TITLES.first())
                ensureTaskDockExpanded()
                TASK_TITLES.forEach(::waitForVisibleText)
                capture("04-second-tasks-running", anchor = TASK_TITLES[2])

                // Focus the real composer field without entering or sending
                // text. The dock owns its IME policy and collapses back to the
                // compact LazyRow while the keyboard is visible.
                focusChatInput()
                waitForVisibleText(targetContext.getString(R.string.subagent_dock_expand))
                waitForTextToDisappear(targetContext.getString(R.string.subagent_dock_collapse))
                waitForVisibleText(targetContext.getString(R.string.chat_input_compose_placeholder))
                capture("05-keyboard-dock-collapsed")
                closeSoftKeyboard()
                compose.waitForIdle()
                ensureTaskDockExpanded()
                TASK_TITLES.forEach(::waitForVisibleText)

                val taskStore: AgentTaskStore = getKoin().get()
                runBlocking {
                    taskStore.update(
                        taskId = fixtureTaskIds.first(),
                        status = AgentTaskStatus.COMPLETED,
                        summary = "Synthetic canary completed",
                    )
                }
                waitForVisibleText(targetContext.getString(R.string.chat_message_subagent_status_completed))
                capture("06-second-task-completed", anchor = TASK_TITLES.first())

                // Source is a read-only route back to the first fixture. This
                // opens the selected pill's details sheet first; no task action
                // or provider execution is involved.
                openTaskDetails(TASK_TITLES.first())
                waitForVisibleText(targetContext.getString(R.string.subagent_dock_details_title))
                clickVisibleText(targetContext.getString(R.string.subagent_dock_source_conversation))
                revealText(FIRST_USER_MARKER)

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

                // Terminal pills expose their own details sheet. Dismiss each
                // one through the real `subagent_dock_dismiss` action instead
                // of toggling the header, and prove the store rows remain.
                ensureTaskDockExpanded()
                TASK_TITLES.forEach { title ->
                    openTaskDetails(title)
                    waitForVisibleText(targetContext.getString(R.string.subagent_dock_details_title))
                    clickVisibleText(targetContext.getString(R.string.subagent_dock_dismiss))
                    waitForTextToDisappear(title)
                }
                runBlocking {
                    fixtureTaskIds.forEach { taskId ->
                        check(taskStore.read(taskId)?.status == AgentTaskStatus.COMPLETED) {
                            "Dock dismiss unexpectedly changed task status: $taskId"
                        }
                    }
                }
            }
        } finally {
            try {
                cleanupFixtures()
            } finally {
                restoreCanarySettings()
            }
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

        val settingsStore: SettingsAggregator = getKoin().get()
        val original = runBlocking {
            withTimeout(WAIT_TIMEOUT_MS) {
                settingsStore.settingsFlow.first { !it.init }
            }
        }
        originalSettings = original
        runBlocking {
            settingsStore.update(
                original.copy(
                    agentRuntime = original.agentRuntime.copy(
                        operationPreviewMode = AgentOperationPreviewMode.HIDDEN,
                    ),
                ),
            )
        }
    }

    private fun restoreCanarySettings() {
        val original = originalSettings ?: return
        val settingsStore: SettingsAggregator = getKoin().get()
        try {
            runBlocking { settingsStore.update(original) }
        } finally {
            originalSettings = null
        }
    }

    private fun prepareConversations(): Pair<Uuid, Uuid> {
        val repository: ConversationRepository = getKoin().get()
        val firstId = Uuid.random()
        val secondId = Uuid.random()
        val firstTitle = "会话甲"
        val secondTitle = "会话乙"
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
            reasoning = "$REASONING_MARKER\n\n保留思考、普通工具和待批写入。",
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
            input = "{\"path\":\"$PENDING_TOOL_PATH\",\"content\":\"测试内容\"}",
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

    private fun awaitEagerDockSeed() {
        val dockState: SubAgentDockState = getKoin().get()
        runBlocking {
            withTimeout(WAIT_TIMEOUT_MS) {
                dockState.uiState.first { state ->
                    fixtureTaskIds.all { taskId ->
                        state.tasks.any { task -> task.key.taskId == taskId }
                    }
                }
            }
        }
    }

    private fun clickContextRing() {
        clickVisibleMatcher(
            hasClickAction() and hasAnyDescendant(hasText("%", substring = true)),
            description = "ContextRing",
        )
    }

    private fun focusChatInput() {
        val inputs = compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
        compose.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
            visibleIndex(inputs) >= 0
        }
        val index = visibleIndex(inputs)
        check(index >= 0) { "Chat input field was not reachable" }
        inputs[index].assertIsDisplayed().performClick()
        compose.waitForIdle()
    }

    private fun ensureTaskDockExpanded() {
        val collapseLabel = targetContext.getString(R.string.subagent_dock_collapse)
        if (isVisibleText(collapseLabel)) return
        clickVisibleText(targetContext.getString(R.string.subagent_dock_expand))
    }

    private fun openTaskDetails(title: String) {
        clickVisibleText(title)
        compose.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
            isVisibleText(targetContext.getString(R.string.subagent_dock_details_title))
        }
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

    private fun isVisibleText(value: String): Boolean {
        val matches = compose.onAllNodesWithText(
            value,
            substring = true,
            useUnmergedTree = true,
        )
        return visibleIndex(matches) >= 0
    }

    private fun visibleIndex(matches: androidx.compose.ui.test.SemanticsNodeInteractionCollection): Int {
        val count = matches.fetchSemanticsNodes().size
        return (0 until count).firstOrNull { index ->
            runCatching { matches[index].isDisplayed() }.getOrDefault(false)
        } ?: -1
    }

    private fun capture(name: String, anchor: String? = null) {
        anchor?.let(::revealText)
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
        val taskStore = runCatching { getKoin().get<AgentTaskStore>() }.getOrNull()
        val repository = runCatching { getKoin().get<ConversationRepository>() }.getOrNull()
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
                        if (existing != null && existing.title == expectedTitle) {
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
        const val ORDINARY_TOOL_PATH = "普通.txt"
        const val PENDING_TOOL_PATH = "待批.txt"
        const val ORDINARY_TOOL_MARKER = "普通工具完成"
        const val REASONING_MARKER = "软时序甲·推理"
        const val FIRST_USER_MARKER = "软时序甲·用户"
        const val FIRST_ASSISTANT_MARKER = "软时序甲·回答"
        const val SECOND_USER_MARKER = "软时序乙·用户"
        const val SECOND_ASSISTANT_MARKER = "软时序乙·回答"
        val TASK_TITLES = listOf(
            "任务甲",
            "任务乙",
            "任务丙",
        )
    }
}
