package app.amber.agent

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.repository.ConversationRepository
import app.amber.core.service.ChatService
import app.amber.feature.ui.components.richtext.parseMarkdownContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Disposable, opt-in fixture for the existing AmberChatPerf timelinePlan log. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalUuidApi::class)
class ChatTimelinePlanPerfProbeTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun markdownParseSingleFlightOnDefaultThreads() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Requires -e amberPerfProbe true", InstrumentationRegistry.getArguments().getString("amberPerfProbe") == "true")
        assumeTrue("Requires the real app runner (-PuiSmokeTest=true)", targetContext.applicationContext is AmberAgentApp)
        val model = Build.MODEL.lowercase()
        val fingerprint = Build.FINGERPRINT.lowercase()
        assumeTrue(
            "Disposable fixture runs only on an emulator",
            model.contains("emulator") || model.contains("sdk_gphone") ||
                model.contains("android sdk built for") ||
                fingerprint.contains("generic") || fingerprint.contains("emulator"),
        )

        val id = Uuid.random()
        val markdown = probeMarkdown(77, 29_000, id)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        runBlocking {
            val first = async(Dispatchers.Default) {
                ready.countDown()
                start.await()
                parseMarkdownContent(markdown)
            }
            val second = async(Dispatchers.Default) {
                ready.countDown()
                start.await()
                parseMarkdownContent(markdown)
            }
            try {
                check(ready.await(10, TimeUnit.SECONDS)) { "Parse workers did not become ready" }
                Log.i(PERF_TAG, "singleFlightProbe begin id=$id chars=${markdown.length}")
            } finally {
                start.countDown()
            }
            check(first.await() === second.await()) { "Concurrent callers received different parse results" }
            Log.i(PERF_TAG, "singleFlightProbe end id=$id")
        }
    }

    @Test
    fun firstOpenWith80NodesAndLongMarkdown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        assumeTrue("Requires -e amberPerfProbe true", InstrumentationRegistry.getArguments().getString("amberPerfProbe") == "true")
        assumeTrue("Requires the real app runner (-PuiSmokeTest=true)", targetContext.applicationContext is AmberAgentApp)
        val model = Build.MODEL.lowercase()
        val fingerprint = Build.FINGERPRINT.lowercase()
        assumeTrue(
            "Disposable fixture runs only on an emulator",
            model.contains("emulator") || model.contains("sdk_gphone") ||
                model.contains("android sdk built for") ||
                fingerprint.contains("generic") || fingerprint.contains("emulator"),
        )

        val preferences = targetContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val originalLaunchMode = preferences.getString(LAUNCH_START_MODE_PREF, null)
        val repository: ConversationRepository = getKoin().get()
        val conversationId = Uuid.random()
        val title = "timeline-perf-probe-$conversationId"
        val tailMarker = "timeline-perf-tail-$conversationId"
        val longReplySizes = listOf(8_000, 12_000, 16_000, 22_000, 29_000)
        val longReplyTurns = listOf(3, 11, 19, 27, 35)
        val messages = buildList {
            repeat(40) { turn ->
                add(MessageNode.of(UIMessage.user("Timeline probe question $turn / 测量问题 $turn")))
                val longIndex = longReplyTurns.indexOf(turn)
                val answer = when {
                    longIndex >= 0 -> probeMarkdown(turn, longReplySizes[longIndex], conversationId)
                    turn == 39 -> tailMarker
                    else -> "Timeline probe reply $turn / 测量回复 $turn"
                }
                add(MessageNode.of(UIMessage.assistant(answer)))
            }
        }
        check(messages.size == 80)
        val fixture = Conversation.ofId(conversationId, messages = messages).copy(title = title)

        try {
            preferences.edit().putString(LAUNCH_START_MODE_PREF, LaunchStartMode.HOME.name).apply()
            runBlocking {
                check(repository.getConversationSummaryById(conversationId) == null)
                repository.insertConversation(fixture)
            }
            Log.i(PERF_TAG, "timelineProbe begin conversation=$conversationId nodes=80 longChars=$longReplySizes")
            val intent = Intent(targetContext, RouteActivity::class.java).apply {
                putExtra("conversationId", conversationId.toString())
            }
            ActivityScenario.launch<RouteActivity>(intent).use {
                compose.waitUntil(timeoutMillis = 45_000) {
                    val nodes = compose.onAllNodesWithText(tailMarker, substring = true, useUnmergedTree = true)
                    (0 until nodes.fetchSemanticsNodes().size).any { index ->
                        runCatching { nodes[index].isDisplayed() }.getOrDefault(false)
                    }
                }
                compose.waitForIdle()
                Log.i(PERF_TAG, "timelineProbe visible conversation=$conversationId")
            }
        } finally {
            try {
                runBlocking {
                    repository.getConversationById(conversationId)
                        ?.takeIf { it.title == title }
                        ?.let { repository.deleteConversation(it) }
                }
            } finally {
                preferences.edit().apply {
                    if (originalLaunchMode == null) remove(LAUNCH_START_MODE_PREF)
                    else putString(LAUNCH_START_MODE_PREF, originalLaunchMode)
                }.apply()
            }
        }
    }

    @Test
    fun olderPageLoadsLongMarkdownThroughUiScroll() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Requires -e amberPerfProbe true", InstrumentationRegistry.getArguments().getString("amberPerfProbe") == "true")
        assumeTrue("Requires the real app runner (-PuiSmokeTest=true)", targetContext.applicationContext is AmberAgentApp)
        assumeTrue("Disposable fixture runs only on an emulator",
            Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator") ||
                Build.MODEL.contains("emulator") || Build.MODEL.contains("sdk_gphone"))

        val preferences = targetContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val originalLaunchMode = preferences.getString(LAUNCH_START_MODE_PREF, null)
        val repository: ConversationRepository = getKoin().get()
        val service: ChatService = getKoin().get()
        val id = Uuid.random()
        val title = "timeline-older-page-probe-$id"
        val tailMarker = "timeline-older-tail-$id"
        val longTurns = listOf(3, 7, 11, 15, 19)
        val longSizes = listOf(8_000, 12_000, 16_000, 22_000, 29_000)
        val nodes = buildList {
            repeat(60) { turn ->
                add(MessageNode.of(UIMessage.user("Older page question $turn")))
                val longIndex = longTurns.indexOf(turn)
                val answer = when {
                    longIndex >= 0 -> probeMarkdown(turn, longSizes[longIndex], id)
                    turn == 59 -> tailMarker
                    else -> "Older page reply $turn"
                }
                add(MessageNode.of(UIMessage.assistant(answer)))
            }
        }
        val fixture = Conversation.ofId(id, messages = nodes).copy(title = title)
        try {
            preferences.edit().putString(LAUNCH_START_MODE_PREF, LaunchStartMode.HOME.name).apply()
            runBlocking { repository.insertConversation(fixture) }
            Log.i(PERF_TAG, "olderPageProbe begin conversation=$id nodes=120")
            val intent = Intent(targetContext, RouteActivity::class.java).apply {
                putExtra("conversationId", id.toString())
            }
            ActivityScenario.launch<RouteActivity>(intent).use {
                compose.waitUntil(timeoutMillis = 45_000) {
                    val tail = compose.onAllNodesWithText(tailMarker, substring = true, useUnmergedTree = true)
                    (0 until tail.fetchSemanticsNodes().size).any { index ->
                        runCatching { tail[index].isDisplayed() }.getOrDefault(false)
                    }
                }
                check(service.getTimelineLoadStateFlow(id).value.loadedNodeCount == 80)
                Log.i(PERF_TAG, "olderPageProbe beforeScroll conversation=$id loaded=80")
                compose.onAllNodes(hasScrollAction(), useUnmergedTree = true).onFirst()
                    .performScrollToIndex(82) // Two trailing items, 80 short nodes, then HistoryLoading.
                compose.waitUntil(timeoutMillis = 90_000) {
                    service.getTimelineLoadStateFlow(id).value.loadedNodeCount == 120
                }
                check(service.getConversationFlow(id).value.messageNodes.first().id == nodes.first().id)
                compose.waitForIdle()
                Log.i(PERF_TAG, "olderPageProbe afterLoad conversation=$id loaded=120")
            }
        } finally {
            try {
                runBlocking {
                    repository.getConversationById(id)
                        ?.takeIf { it.title == title }
                        ?.let { repository.deleteConversation(it) }
                }
            } finally {
                preferences.edit().apply {
                    if (originalLaunchMode == null) remove(LAUNCH_START_MODE_PREF)
                    else putString(LAUNCH_START_MODE_PREF, originalLaunchMode)
                }.apply()
            }
        }
    }

    @Test
    fun resolverLoadsFullConversationAndSanitizesOutsideWindow() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Requires -e amberPerfProbe true", InstrumentationRegistry.getArguments().getString("amberPerfProbe") == "true")
        assumeTrue("Requires the real app runner (-PuiSmokeTest=true)", targetContext.applicationContext is AmberAgentApp)
        val model = Build.MODEL.lowercase()
        val fingerprint = Build.FINGERPRINT.lowercase()
        assumeTrue(
            "Disposable fixture runs only on an emulator",
            model.contains("emulator") || model.contains("sdk_gphone") ||
                model.contains("android sdk built for") ||
                fingerprint.contains("generic") || fingerprint.contains("emulator"),
        )

        val repository: ConversationRepository = getKoin().get()
        val service: ChatService = getKoin().get()
        val fixtures = mutableListOf<Conversation>()
        try {
            for (invalidToolOutsideWindow in listOf(false, true)) {
                val id = Uuid.random()
                val nodes = buildList {
                    repeat(60) { turn ->
                        add(MessageNode.of(UIMessage.user("Resolver probe user $turn")))
                        val assistant = if (invalidToolOutsideWindow && turn == 5) {
                            UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool(
                                toolCallId = "resolver-probe-unresolved-$id",
                                toolName = "file_write",
                                input = "{\"path\":\"probe.txt\"}",
                                approvalState = ToolApprovalState.Auto,
                            )))
                        } else {
                            UIMessage.assistant("Resolver probe reply $turn")
                        }
                        add(MessageNode.of(assistant))
                    }
                }
                val invalidNodeId = nodes[11].id.takeIf { invalidToolOutsideWindow }
                val fixture = Conversation.ofId(id, messages = nodes)
                    .copy(title = "generation-resolver-probe-$id")
                fixtures += fixture
                runBlocking {
                    check(repository.getConversationSummaryById(id) == null)
                    repository.insertConversation(fixture)
                    service.initializeConversation(id)
                    val window = service.getConversationFlow(id).value.messageNodes
                    check(window.size == 80 && window.first().id == nodes[40].id)
                    check(invalidNodeId == null || window.none { it.id == invalidNodeId })

                    val resolved = withContext(Dispatchers.Default) {
                        service.conversationForGeneration(id)
                    }
                    val expectedCount = if (invalidToolOutsideWindow) 119 else 120
                    check(resolved.messageNodes.size == expectedCount)
                    check(resolved.messageNodes.none { it.id == invalidNodeId })
                    val sessionNodes = service.getConversationFlow(id).value.messageNodes
                    check(sessionNodes.size == (if (invalidToolOutsideWindow) 119 else 80))
                    val loadState = service.getTimelineLoadStateFlow(id).value
                    check(loadState.loadedNodeCount == sessionNodes.size)
                    check(loadState.isFullyLoaded == invalidToolOutsideWindow)
                    if (!invalidToolOutsideWindow) {
                        val partial = service.getConversationFlow(id).value
                        withTimeout(15_000) { service.saveConversation(id, partial) }
                        val saved = requireNotNull(repository.getConversationById(id))
                        check(saved.title == fixture.title && saved.messageNodes.size == 120)
                    }
                    val storedNodes = requireNotNull(repository.getConversationById(id)).messageNodes
                    check(storedNodes.size == expectedCount)
                    check(storedNodes.none { it.id == invalidNodeId })
                }
            }
        } finally {
            runBlocking {
                var cleanupFailure: Throwable? = null
                fixtures.forEach { fixture ->
                    runCatching {
                        repository.getConversationById(fixture.id)
                            ?.takeIf { it.title == fixture.title }
                            ?.let { repository.deleteConversation(it) }
                    }.onFailure { if (cleanupFailure == null) cleanupFailure = it }
                }
                cleanupFailure?.let { throw it }
            }
        }
    }

    private fun probeMarkdown(turn: Int, targetChars: Int, conversationId: Uuid): String = buildString {
        append("# Timeline analysis $turn / 时间线分析 / $conversationId\n\n")
        var section = 0
        while (length < targetChars) {
            append("## Section $section / 小节 $section\n")
            append("English explanation and 中文说明 describe the same user-visible result. ")
            append("Long replies include prose, a table, and code for Markdown parsing.\n\n")
            append("| Step | Result / 结果 | Note |\n| --- | --- | --- |\n")
            append("| $section | success / 成功 | stable text $turn |\n\n")
            append("```kotlin\nfun step$section(value: Int): String = \"result=\" + (value + $turn)\n```\n\n")
            section++
        }
        append("Final summary / 最终小结：the measured reply is complete.\n")
    }.also { check(it.length in 8_000..30_000) }

    private companion object {
        const val PERF_TAG = "AmberChatPerf"
        const val PREFERENCES_NAME = "amber_agent.preferences"
        const val LAUNCH_START_MODE_PREF = "launchStartMode"
    }
}
