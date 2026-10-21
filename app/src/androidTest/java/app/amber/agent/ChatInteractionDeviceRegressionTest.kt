package app.amber.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.ai.provider.*
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.repository.ConversationRepository
import app.amber.core.service.ChatService
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.java.KoinJavaComponent.getKoin
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Real UI -> ChatService/CAS -> Room, with a scripted provider and isolated app storage. */
class ChatInteractionDeviceRegressionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun requireFixture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("amberDeviceRegression") == "true")
        check(context.applicationContext is ChatDeviceFixtureApplication) { "Requires -PchatDeviceFixture=true" }
        Thread.sleep(1500) // Allow the host's one-shot foreground kick to finish before opening chat.
    }
    private fun open(id: Uuid): ActivityScenario<RouteActivity> {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/androidx.activity.ComponentActivity",
        )).bufferedReader().use { it.readText() }
        return ActivityScenario.launch(Intent(context, RouteActivity::class.java).putExtra("conversationId", id.toString()))
    }
    private fun text(service: ChatService, id: Uuid) = service.getConversationFlow(id).value.currentMessages
        .lastOrNull { it.role == app.amber.ai.core.MessageRole.ASSISTANT }?.parts
        ?.filterIsInstance<UIMessagePart.Text>()?.joinToString("") { it.text }.orEmpty()
    private fun storedText(conversation: Conversation) = conversation.currentMessages.last().parts
        .filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
    private fun sendStopButton(resource: Int) = context.getString(resource).let { description ->
        compose.onAllNodes(hasClickAction() and (hasContentDescription(description) or
            hasAnyDescendant(hasContentDescription(description))), useUnmergedTree = true).onFirst()
    }
    private fun markerNodes(marker: String) =
        compose.onAllNodesWithText(marker, substring = true, useUnmergedTree = true)
    private fun markerDisplayed(marker: String): Boolean = markerNodes(marker).fetchSemanticsNodes()
        .indices.any { runCatching { markerNodes(marker)[it].isDisplayed() }.getOrDefault(false) }
    private fun timelineScrollable() =
        compose.onAllNodes(hasScrollAction(), useUnmergedTree = true).onFirst()

    /**
     * The timeline pins to the newest content after a stream ends, so the head
     * of a long reply is composed but clipped above the viewport. Move with a
     * real drag until the marker is actually displayed; never weaken the
     * visibility assertion.
     */
    private fun revealMarker(marker: String, towardOlder: Boolean, maxSwipes: Int = 8): Boolean {
        compose.waitForIdle()
        repeat(maxSwipes + 1) {
            if (markerDisplayed(marker)) return true
            runCatching {
                timelineScrollable().performTouchInput {
                    if (towardOlder) swipeDown() else swipeUp()
                }
            }
            compose.waitForIdle()
        }
        return markerDisplayed(marker)
    }

    private fun openMenu(marker: String, towardOlder: Boolean = true) {
        check(revealMarker(marker, towardOlder)) { "Timeline marker is not reachable: $marker" }
        markerNodes(marker).onFirst().performTouchInput { longClick() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(context.getString(R.string.copy)).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun imeBottom(scenario: ActivityScenario<RouteActivity>): Int {
        var value = -1
        scenario.onActivity { value = it.window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom }
        return value
    }
    private suspend fun awaitImeHidden(scenario: ActivityScenario<RouteActivity>) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            compose.mainClock.advanceTimeBy(16)
            delay(16)
            if (imeBottom(scenario) == 0) return
        }
        error("IME did not hide before the stop tap")
    }
    private fun tap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            check(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
    }

    @Test(timeout = 180_000)
    fun cancelRegenerateSwitchHistoryAndCopy(): Unit = runBlocking {
        requireFixture()
        val koin = getKoin()
        val settings: SettingsAggregator = koin.get()
        withTimeout(15_000) { settings.settingsFlow.first { !it.init } }
        val model = Model(modelId = "device-script", displayName = "Isolated device script", type = ModelType.CHAT)
        val provider = ProviderSetting.OpenAI(name = "Isolated fixture only", models = listOf(model), apiKey = "fixture-only")
        val base = Settings()
        settings.update(base.copy(providers = listOf(provider), chatModelId = model.id,
            titleModelId = model.id, suggestionModelId = model.id, compressModelId = model.id,
            streamOutput = true, maxTokens = 8192, enableWebSearch = false,
            enabledMcpServerIds = emptySet(), presetMessages = emptyList(), regexes = emptyList(),
            systemPrompt = "Local device regression. Do not call tools.",
            agentRuntime = base.agentRuntime.copy(enableCoreMemory = false, enableShortTermMemory = false,
                enableLongTermMemory = false, enableRecentChatsReference = false,
                enableLiveStatusNotification = false, keepGenerationAliveInBackground = false)))
        context.getSharedPreferences("amber_agent.preferences", Context.MODE_PRIVATE).edit()
            .putString(LAUNCH_START_MODE_PREF, LaunchStartMode.HOME.name).apply()
        val repository: ConversationRepository = koin.get()
        val database: app.amber.agent.data.db.AppDatabase = koin.get()
        Log.i(TAG, "sandboxDatabase=${database.openHelper.readableDatabase.path}")
        val service: ChatService = koin.get()
        val gateway: FixtureTextGateway = koin.get()
        val id = Uuid.random()
        val historyId = Uuid.random()
        val historyTail = "DEVICE_HISTORY_TAIL_$historyId"
        val historyHead = "DEVICE_HISTORY_HEAD_$historyId"
        val history = buildList {
            repeat(60) { turn ->
                add(MessageNode.of(UIMessage.user(if (turn == 0) historyHead else "Fixture history question $turn")))
                add(MessageNode.of(UIMessage.assistant(if (turn == 59) historyTail else "Fixture history answer $turn")))
            }
        }
        repository.insertConversation(Conversation.ofId(id).copy(title = "Device interaction fixture"))
        repository.insertConversation(Conversation.ofId(historyId, messages = history).copy(title = "Device history fixture"))
        var cancelPrefix = ""
        var finalReply = ""
        try {
            gateway.reply = "DEVICE_CANCEL_MARKER 中文 👩‍💻 é。\n\n" + "这是正在生成的可取消回答，保持全部已输出内容。\n\n".repeat(150)
            gateway.delayMs = 33
            open(id).use { scenario ->
                compose.waitUntil(30_000) { compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextInput("Run isolated device stream")
                val send = sendStopButton(R.string.send)
                val stopPoint = send.fetchSemanticsNode().boundsInWindow.center
                compose.mainClock.autoAdvance = false
                tap(stopPoint.x, stopPoint.y)
                // Compose's idle synchronization may wait until the stream drains.
                // Inject the real stop touch without waiting for a continuously updating UI.
                withTimeout(30_000) {
                    while (text(service, id).length < 300) {
                        compose.mainClock.advanceTimeBy(16)
                        delay(16)
                    }
                }
                compose.mainClock.advanceTimeBy(200)
                // The composer moves when the keyboard hides. Wait for the IME
                // to be gone, then measure the live stop control instead of
                // offsetting a stale pre-stream measurement (a stale offset sent
                // the tap off-screen and let the stream finish).
                awaitImeHidden(scenario)
                val stop = sendStopButton(R.string.stop)
                val stopCenter = stop.fetchSemanticsNode().boundsInWindow.center
                Log.i(TAG, "before-stop chars=${text(service, id).length} active=${service.getGenerationJobStateFlow(id).value?.isActive} point=$stopCenter ime=${imeBottom(scenario)}")
                tap(stopCenter.x, stopCenter.y)
                withTimeout(20_000) {
                    while (service.getGenerationJobStateFlow(id).value?.isActive == true) {
                        compose.mainClock.advanceTimeBy(16)
                        delay(16)
                    }
                }
                compose.mainClock.autoAdvance = true
                cancelPrefix = text(service, id)
                check(cancelPrefix.startsWith("DEVICE_CANCEL_MARKER") && cancelPrefix.length < gateway.reply.length) {
                    "Cancellation was not partial: chars=${cancelPrefix.length} expectedMax=${gateway.reply.length}"
                }
                withTimeout(15_000) {
                    while (storedText(requireNotNull(repository.getConversationById(id))) != cancelPrefix) delay(100)
                }
                Log.i(TAG, "cancel passed chars=${cancelPrefix.length} durable=true")
                finalReply = "# Device regenerated answer\n\n" +
                    (0 until 40).joinToString("\n\n") {
                        "**完整答案 $it** preserves Unicode 👩‍💻 é and copy text. 这是一段完整的中文说明，用于真实长消息交互验证。"
                    } + "\n\n```kotlin\nfun verified() = 42\n```\n\nDEVICE_FINAL_COPY_MARKER 最终文本完整。"
                gateway.reply = finalReply
                gateway.delayMs = 12
                val calls = gateway.calls.get()
                openMenu("DEVICE_CANCEL_MARKER")
                compose.onNodeWithText(context.getString(R.string.regenerate)).performClick()
                compose.waitUntil(30_000) { gateway.calls.get() > calls && text(service, id) == finalReply &&
                    service.getGenerationJobStateFlow(id).value?.isActive != true }
                withTimeout(15_000) {
                    while (storedText(requireNotNull(repository.getConversationById(id))) != finalReply) delay(100)
                }
                Log.i(TAG, "regenerate passed chars=${finalReply.length} durable=true")
                // The reply is much longer than the viewport; return to the
                // newest content before opening its tail marker menu.
                runCatching { timelineScrollable().performScrollToIndex(0) }
                compose.waitForIdle()
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                var prior: ClipData? = null
                instrumentation.runOnMainSync { prior = clipboard.primaryClip }
                try {
                    openMenu("DEVICE_FINAL_COPY_MARKER", towardOlder = false)
                    compose.onNodeWithText(context.getString(R.string.select_and_copy)).performClick()
                    compose.onNodeWithText(context.getString(R.string.copy_all)).performClick()
                    var copied = ""
                    instrumentation.runOnMainSync { copied = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context).toString() }
                    check(copied == finalReply) { "Copy changed complete Markdown/Unicode source" }
                    Log.i(TAG, "select-copy passed sha256=${sha(finalReply)}")
                } finally {
                    instrumentation.runOnMainSync {
                        val previous = prior
                        if (previous != null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip()
                    }
                }
            }
            open(historyId).use {
                compose.waitUntil(30_000) { compose.onAllNodesWithText(historyTail, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes().any { it.boundsInRoot.height > 0 } }
                check(service.getTimelineLoadStateFlow(historyId).value.loadedNodeCount == 80)
                compose.onAllNodes(hasScrollAction(), useUnmergedTree = true).onFirst().performScrollToIndex(82)
                compose.waitUntil(45_000) { service.getTimelineLoadStateFlow(historyId).value.loadedNodeCount == 120 }
                check(service.getConversationFlow(historyId).value.messageNodes.first().currentMessage.parts
                    .filterIsInstance<UIMessagePart.Text>().single().text == historyHead)
                Log.i(TAG, "switch-history passed initial=80 loaded=120 headVerified=true")
            }
            open(id).use {
                compose.waitUntil(30_000) { compose.onAllNodesWithText("DEVICE_FINAL_COPY_MARKER", substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes().any { it.boundsInRoot.height > 0 } }
                check(text(service, id) == finalReply)
                Log.i(TAG, "switch-back passed chars=${finalReply.length}")
            }
            context.getSharedPreferences(PROOF_PREF, Context.MODE_PRIVATE).edit()
                .putString("id", id.toString()).putString("text", finalReply).commit()
            Log.i(TAG, "first-process complete conversation=$id sha256=${sha(finalReply)}")
        } finally {
            service.stopGeneration(id)
            repository.getConversationById(historyId)?.let { service.deleteConversation(it) }
        }
    }

    private suspend fun isolatedFixture(): Triple<ConversationRepository, ChatService, FixtureTextGateway> {
        val koin = getKoin()
        val settings: SettingsAggregator = koin.get()
        withTimeout(15_000) { settings.settingsFlow.first { !it.init } }
        val model = Model(modelId = "device-script", displayName = "Isolated device script", type = ModelType.CHAT)
        val provider = ProviderSetting.OpenAI(name = "Isolated fixture only", models = listOf(model), apiKey = "fixture-only")
        val base = Settings()
        settings.update(base.copy(providers = listOf(provider), chatModelId = model.id,
            titleModelId = model.id, suggestionModelId = model.id, compressModelId = model.id,
            streamOutput = true, maxTokens = 8192, enableWebSearch = false,
            enabledMcpServerIds = emptySet(), presetMessages = emptyList(), regexes = emptyList(),
            systemPrompt = "Local device regression. Do not call tools.",
            agentRuntime = base.agentRuntime.copy(enableCoreMemory = false, enableShortTermMemory = false,
                enableLongTermMemory = false, enableRecentChatsReference = false,
                enableLiveStatusNotification = false, keepGenerationAliveInBackground = false)))
        context.getSharedPreferences("amber_agent.preferences", Context.MODE_PRIVATE).edit()
            .putString(LAUNCH_START_MODE_PREF, LaunchStartMode.HOME.name).apply()
        return Triple(koin.get(), koin.get(), koin.get())
    }

    /** Real-time frames for the uptime-based pacer while the test clock is manual. */
    private suspend fun pump(milliseconds: Long) {
        val end = SystemClock.uptimeMillis() + milliseconds
        while (SystemClock.uptimeMillis() < end) {
            compose.mainClock.advanceTimeBy(16)
            delay(16)
        }
    }

    private suspend fun pumpUntil(timeoutMs: Long, label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            if (SystemClock.uptimeMillis() >= end) {
                val imageBox = fixtureImageBounds()
                Log.i(
                    TAG,
                    "timeout $label rich=${richState()} image=${imageBox?.let { "${it.width}x${it.height}@${it.top}" } ?: "none"} " +
                        "uri=${runCatching { fixtureImageUri() }.getOrNull()} texts=" +
                        renderedTexts().joinToString(" | ") {
                            "${it.text.take(24).replace('\n', ' ')}(${it.text.length})@${it.top}"
                        },
                )
                error("Timed out waiting for $label")
            }
            pump(32)
        }
    }

    private data class RenderedText(val text: String, val top: Float, val layout: TextLayoutResult)

    /**
     * One coordinate space for both renderers: the unclipped Text origin
     * (positionInRoot) plus the character box from its own TextLayoutResult.
     * Semantics bounds are clipped to the viewport and are never mixed in.
     */
    private fun renderedTexts(): List<RenderedText> = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true,
    ).fetchSemanticsNodes().mapNotNull { node ->
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: return@mapNotNull null
        val layouts = ArrayList<TextLayoutResult>()
        runCatching { node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) }
        val layout = layouts.firstOrNull() ?: return@mapNotNull null
        RenderedText(text, node.positionInRoot.y, layout)
    }
    private fun tokenTop(token: String): Float? = renderedTexts().firstNotNullOfOrNull { node ->
        val offset = node.text.indexOf(token)
        if (offset < 0) null else node.top + node.layout.getBoundingBox(offset).top
    }
    private fun tokenBottom(token: String): Float? = renderedTexts().firstNotNullOfOrNull { node ->
        val offset = node.text.indexOf(token)
        if (offset < 0) null else node.top + node.layout.getBoundingBox(offset + token.length - 1).bottom
    }
    /**
     * The settled MarkdownBlock renders a paragraph as its own short Text. The
     * streaming presentation keeps it inside a committed >=256-unit chunk or
     * the single full Text, never as a standalone paragraph node.
     */
    private fun settledParagraph(token: String) = renderedTexts().any { token in it.text && it.text.length < 120 }
    private fun streamingParagraph(token: String) = renderedTexts().any { token in it.text && it.text.length >= 256 }
    private fun timelineNode() = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange), useUnmergedTree = true,
    ).fetchSemanticsNodes().first { it.config[SemanticsProperties.VerticalScrollAxisRange].reverseScrolling }
    /** Lazy list semantics: 0 only at item 0 with zero scroll offset (the absolute bottom). */
    private fun timelineScrollValue() = timelineNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    /**
     * Screen-space equivalent of [app.amber.feature.ui.pages.chat.viewportReadingLine]:
     * the visual top of the reverseLayout timeline (older-content edge), not
     * the midpoint. The production gate treats the reading line as inside the
     * reply when this Y falls in that message's item; the older-anchor case
     * needs the reply top below it.
     */
    private fun timelineReadingLineY(): Float {
        val bounds = timelineNode().boundsInRoot
        return bounds.top + 1f
    }

    /**
     * Settled-only semantics: a code card copy action and a standalone table
     * cell Text; the degraded "[image: alt]" label of the streaming
     * presentation must be gone (the image block carries no Text).
     */
    private fun richState(): Triple<Boolean, Boolean, Boolean> {
        val copy = compose.onAllNodes(hasContentDescription(context.getString(R.string.code_block_copy)), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
        val texts = renderedTexts()
        val cell = texts.any { it.text == RICH_CELL }
        val imageLabelGone = texts.none { RICH_IMAGE_ALT in it.text }
        return Triple(copy, cell, imageLabelGone)
    }
    private fun richSettled(): Boolean = richState().let { it.first && it.second && it.third }
    /** The streaming presentation's image label is the only text carrying the alt. */
    private fun imagePlaceholderVisible() = renderedTexts().any { RICH_IMAGE_ALT in it.text }
    private fun mathSettled() = renderedTexts().any { "[Latex]" in it.text }
    private fun fixtureImageHeight(): Float = fixtureImageBounds()?.height ?: 0f
    private fun fixtureImageLoaded(): Boolean {
        val bounds = fixtureImageBounds() ?: return false
        // Placeholder is square (120dp min). The fixture PNG is 3:2.
        return bounds.height > 0f && bounds.width / bounds.height > 1.2f
    }
    private fun fixtureImageBounds(): androidx.compose.ui.geometry.Rect? =
        ChatDeviceImageFixtures.bounds(compose)
    private fun fixtureImageUri(): String = ChatDeviceImageFixtures.writePng(context)
    private fun richReply(): String =
        "RICH_INTRO 富文本回复，生成结束后应切换为正式渲染。\n\n" +
            "| 项目 | 结果 |\n| --- | --- |\n| $RICH_CELL | CELL_BETA |\n\n" +
            "```kotlin\nfun richFixture() = 42\n```\n\n" +
            "行内公式 MATH_INLINE_MARK \$E=mc^2\$ 之后。\n\n" +
            "\$\$\n\\sum_{n=1}^{N} n = \\frac{N(N+1)}{2}\n\$\$\n\n" +
            "MATH_BLOCK_AFTER 块级公式之后。\n\n" +
            "RICH_END 完成。\n\n" +
            "收尾段落保持完整输出。\n\n".repeat(6) +
            "![$RICH_IMAGE_ALT](${fixtureImageUri()})\n\n" +
            "RICH_TAIL 结束。"
    /**
     * Short rich reply that can sit in the lower half of the viewport under
     * an older reading line. The pinned fixture keeps the trailing
     * paragraphs so the image is a bottom-of-message load; this one keeps
     * the image next to the intro so it stays composed while the reader
     * looks at the older message.
     */
    private fun compactRichReply(): String =
        "RICH_INTRO 富文本回复，生成结束后应切换为正式渲染。\n\n" +
            "| 项目 | 结果 |\n| --- | --- |\n| $RICH_CELL | CELL_BETA |\n\n" +
            "```kotlin\nfun richFixture() = 42\n```\n\n" +
            "行内公式 MATH_INLINE_MARK \$E=mc^2\$ 之后。\n\n" +
            "\$\$\n\\sum_{n=1}^{N} n = \\frac{N(N+1)}{2}\n\$\$\n\n" +
            "MATH_BLOCK_AFTER 块级公式之后。\n\n" +
            "![$RICH_IMAGE_ALT](${fixtureImageUri()})\n\n" +
            "RICH_END 完成。\n\n" +
            "RICH_TAIL 结束。"

    /** Real touch drag on the timeline; positive steps move the finger down (toward older content). */
    private suspend fun drag(scenario: ActivityScenario<RouteActivity>, stepPx: Float, steps: Int = 12) {
        lateinit var view: android.view.View
        scenario.onActivity { view = it.window.decorView }
        val x = view.width * .5f
        val y = view.height * (if (stepPx > 0) .3f else .7f)
        val down = SystemClock.uptimeMillis()
        fun touch(action: Int, targetY: Float) {
            instrumentation.runOnMainSync {
                MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, targetY, 0).also {
                    view.dispatchTouchEvent(it)
                    it.recycle()
                }
            }
        }
        touch(MotionEvent.ACTION_DOWN, y)
        repeat(steps) { step -> pump(20); touch(MotionEvent.ACTION_MOVE, y + (step + 1) * stepPx) }
        pump(200)
        touch(MotionEvent.ACTION_UP, y + steps * stepPx)
        pump(800)
    }

    /** Real upward drags (the reader scrolling back down) until the absolute bottom. */
    private suspend fun returnToBottom(scenario: ActivityScenario<RouteActivity>) {
        repeat(4) {
            if (timelineScrollValue() == 0f) return
            drag(scenario, stepPx = -60f)
            Log.i(TAG, "return-to-bottom drag=${it + 1} scrollValue=${timelineScrollValue()}")
        }
        pumpUntil(5_000, "return to bottom (scrollValue=${timelineScrollValue()})") { timelineScrollValue() == 0f }
    }

    private fun writeEvidence(name: String, result: org.json.JSONObject) {
        java.io.File(context.getExternalFilesDir(null), "$name.json").writeText(result.toString(2))
        Log.i(TAG, "$name ${result.toString().take(900)}")
    }

    @Test(timeout = 120_000)
    fun productionCompletionPreservesOffBottomReadingAnchor(): Unit = runBlocking {
        verifyProductionReadingAnchor(cancel = false)
    }

    @Test(timeout = 120_000)
    fun productionCancelPreservesOffBottomReadingAnchor(): Unit = runBlocking {
        verifyProductionReadingAnchor(cancel = true)
    }

    /**
     * Off-bottom reader anchored inside a long generating reply. The settled
     * block tree does not share the streaming single-Text geometry, so the
     * gate defers until the reader returns to the bottom. The in-reply token
     * must not move more than 2px while deferred, then swap after return.
     */
    private suspend fun verifyProductionReadingAnchor(cancel: Boolean) {
        requireFixture()
        val (repository, service, gateway) = isolatedFixture()
        val id = Uuid.random()
        val paragraph = "生产阅读段落保持稳定，Unicode 👩‍💻 é。\n\n"
        // A distinct tail identifies the final coalesced frame, which the
        // production accumulator may legitimately hold until stream completion.
        val reply = paragraph.repeat(140) + "PROD_READ_ANCHOR 固定可视字符。\n\n" + paragraph.repeat(6) + "PROD_OUTPUT_END 完整。"
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        gateway.reply = reply
        gateway.delayMs = 12
        gateway.beforeFinish = gate
        gateway.finishGateReached = false
        repository.insertConversation(Conversation.ofId(id).copy(title = "Production reading anchor fixture"))
        try {
            open(id).use { scenario ->
                compose.waitUntil(30_000) { compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextInput("Verify reading position")
                val send = sendStopButton(R.string.send).fetchSemanticsNode().boundsInWindow.center
                compose.mainClock.autoAdvance = false
                Log.i(TAG, "production-anchor send point=$send ime=${imeBottom(scenario)} expectedChars=${reply.length}")
                tap(send.x, send.y)
                withTimeout(45_000) {
                    var checks = 0
                    while (!gateway.finishGateReached || "PROD_READ_ANCHOR" !in text(service, id)) {
                        pump(32)
                        if (++checks % 90 == 0) Log.i(TAG, "production-anchor feed calls=${gateway.calls.get()} chars=${text(service, id).length} gate=${gateway.finishGateReached} active=${service.getGenerationJobStateFlow(id).value?.isActive}")
                    }
                }
                Log.i(TAG, "production-anchor gated sourceChars=${reply.length} publishedChars=${text(service, id).length}")
                awaitImeHidden(scenario)
                pumpUntil(30_000, "rendered anchor") { tokenTop("PROD_READ_ANCHOR") != null }
                pump(1_500)
                val height = run { var h = 0; scenario.onActivity { h = it.window.decorView.height }; h }
                drag(scenario, stepPx = 28f)
                val before = requireNotNull(tokenTop("PROD_READ_ANCHOR"))
                val scrollBefore = timelineScrollValue()
                Log.i(TAG, "production-anchor before y=$before scroll=$scrollBefore chars=${text(service, id).length}")
                check(before in 0f..height.toFloat()) { "Production anchor is not visible: $before" }
                check(scrollBefore > 0f) { "Reader must be away from the bottom" }
                check(service.getGenerationJobStateFlow(id).value?.isActive == true)
                if (cancel) {
                    val stop = sendStopButton(R.string.stop).fetchSemanticsNode().boundsInWindow.center
                    tap(stop.x, stop.y)
                } else gate.complete(Unit)
                withTimeout(20_000) {
                    while (service.getGenerationJobStateFlow(id).value?.isActive == true) pump(32)
                }
                pump(1_500)
                val after = requireNotNull(tokenTop("PROD_READ_ANCHOR"))
                val scrollAfter = timelineScrollValue()
                Log.i(TAG, "production-anchor after y=$after scroll=$scrollAfter chars=${text(service, id).length}")
                pump(1_000)
                val stable = requireNotNull(tokenTop("PROD_READ_ANCHOR"))
                val swappedWhileAnchored = settledParagraph("PROD_READ_ANCHOR") && !streamingParagraph("PROD_READ_ANCHOR")
                val finalText = text(service, id)
                check(if (cancel) reply.startsWith(finalText) && "PROD_READ_ANCHOR" in finalText else finalText == reply)
                returnToBottom(scenario)
                pumpUntil(10_000, "settled renderer after returning to the bottom") { settledParagraph("PROD_READ_ANCHOR") }
                val settledAtBottom = !streamingParagraph("PROD_READ_ANCHOR")
                val result = org.json.JSONObject().apply {
                    put("cancel", cancel); put("before", before); put("after", after); put("stable", stable)
                    put("scrollBefore", scrollBefore.toDouble()); put("scrollAfter", scrollAfter.toDouble())
                    put("swappedWhileAnchored", swappedWhileAnchored); put("settledAfterReturningToBottom", settledAtBottom)
                    put("coordinateSpace", "Text positionInRoot + TextLayoutResult.getBoundingBox")
                    put("sourceChars", reply.length); put("storedChars", finalText.length); put("sourceSha256", sha(reply))
                }
                writeEvidence("production-anchor-$cancel", result)
                check(kotlin.math.abs(after - before) <= 2f) { "Production reading anchor jumped: $before->$after" }
                check(kotlin.math.abs(stable - after) <= 2f) { "Production reading anchor drifted: $after->$stable" }
                check(!swappedWhileAnchored) {
                    "Settled renderer took over while the reading anchor was inside the message"
                }
                check(settledAtBottom) { "Streaming presentation outlived the return to the bottom" }
                withTimeout(15_000) {
                    while (storedText(requireNotNull(repository.getConversationById(id))) != finalText) delay(50)
                }
                Log.i(TAG, "production-anchor passed cancel=$cancel before=$before after=$after stable=$stable settledAtBottom=true Room=true chars=${finalText.length}")
            }
        } finally {
            compose.mainClock.autoAdvance = true
            gate.complete(Unit)
            gateway.beforeFinish = null
            service.stopGeneration(id)
            repository.getConversationById(id)?.let { service.deleteConversation(it) }
        }
    }

    /**
     * (a)+(c): a short rich reply finishes while the reader stays pinned and
     * does nothing. The settled block tree (table cells, code card copy action,
     * image node) must replace the degraded streaming presentation, and the
     * timeline must still be at the absolute bottom.
     */
    @Test(timeout = 120_000)
    fun productionPinnedCompletionShowsSettledRichMarkdown(): Unit = runBlocking {
        requireFixture()
        val (repository, service, gateway) = isolatedFixture()
        val id = Uuid.random()
        val reply = richReply()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        gateway.reply = reply
        gateway.delayMs = 12
        gateway.beforeFinish = gate
        gateway.finishGateReached = false
        repository.insertConversation(Conversation.ofId(id).copy(title = "Production rich settle fixture"))
        try {
            open(id).use { scenario ->
                compose.waitUntil(30_000) { compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextInput("Rich pinned reply")
                val send = sendStopButton(R.string.send).fetchSemanticsNode().boundsInWindow.center
                compose.mainClock.autoAdvance = false
                tap(send.x, send.y)
                withTimeout(45_000) {
                    while (!gateway.finishGateReached || "RICH_END" !in text(service, id)) pump(32)
                }
                awaitImeHidden(scenario)
                // Generation is still active: the degraded streaming presentation is on screen.
                pumpUntil(10_000, "streaming presentation of the complete reply") { imagePlaceholderVisible() }
                val sawStreamingPresentation = !richSettled()
                val sawRawMath = renderedTexts().any { "\$E=mc^2\$" in it.text && it.text.length >= 256 }
                gate.complete(Unit)
                withTimeout(20_000) {
                    while (service.getGenerationJobStateFlow(id).value?.isActive == true) pump(32)
                }
                // No touch input after send: the reader stays pinned.
                pumpUntil(10_000, "settled rich renderer after completion") { richSettled() }
                pumpUntil(8_000, "local fixture image loaded") { fixtureImageLoaded() }
                val settledAt = tokenBottom("RICH_TAIL")
                val bottom = timelineNode().boundsInRoot.bottom
                pump(1_000)
                val stableAt = tokenBottom("RICH_TAIL")
                val scrollValue = timelineScrollValue()
                val result = org.json.JSONObject().apply {
                    put("sawStreamingPresentation", sawStreamingPresentation)
                    put("richSettled", richSettled()); put("imagePlaceholderVisible", imagePlaceholderVisible())
                    put("mathSettled", mathSettled()); put("sawRawMath", sawRawMath)
                    put("imageHeight", fixtureImageHeight())
                    put("timelineScrollValue", scrollValue.toDouble()); put("timelineBottom", bottom)
                    put("endBottomAtSettle", settledAt ?: -1f); put("endBottomStable", stableAt ?: -1f)
                }
                writeEvidence("production-rich-pinned", result)
                check(sawStreamingPresentation) { "Streaming presentation was never observed" }
                check(!imagePlaceholderVisible()) { "Degraded image label remained after the handover" }
                check(mathSettled()) { "Settled math did not render as [Latex]" }
                check(renderedTexts().none { it.text.trim() == "$" || it.text.trim() == "$$" }) {
                    "Settled tree invented a lone \$ node"
                }
                check(fixtureImageLoaded()) { "Local fixture image did not load (height=${fixtureImageHeight()})" }
                check(scrollValue == 0f) { "Pinned completion left the bottom: $scrollValue" }
                requireNotNull(settledAt); requireNotNull(stableAt)
                check(settledAt <= bottom + 2f) { "Last message end is below the timeline: $settledAt > $bottom" }
                check(kotlin.math.abs(stableAt - settledAt) <= 2f) { "Pinned bottom drifted after the handover: $settledAt->$stableAt" }
                withTimeout(15_000) {
                    while (storedText(requireNotNull(repository.getConversationById(id))) != reply) delay(50)
                }
                Log.i(TAG, "production-rich-pinned passed settled=true pinned=true math=true image=true Room=true")
            }
        } finally {
            compose.mainClock.autoAdvance = true
            gate.complete(Unit)
            gateway.beforeFinish = null
            service.stopGeneration(id)
            repository.getConversationById(id)?.let { service.deleteConversation(it) }
        }
    }

    /**
     * Measurement only (PR #31 review item 2), not a pass/fail gate: the real
     * timeline streams a long pinned reply under 30 warmed history replies.
     * Records MarkdownParseCache misses and newly cached chars during
     * generation, how many history bodies stay cached, frame durations, and
     * the misses needed to walk the history again afterwards. The same test
     * APK runs against app builds before and after the prefetch exclusion.
     */
    @Test(timeout = 240_000)
    fun productionLongReplyPrefetchParseBudget(): Unit = runBlocking {
        requireFixture()
        val (repository, service, gateway) = isolatedFixture()
        val id = Uuid.random()
        val historyBodies = (0 until 30).map(::prefetchHistoryBody)
        val history = buildList {
            historyBodies.forEachIndexed { turn, body ->
                add(MessageNode.of(UIMessage.user("Prefetch history question $turn")))
                add(MessageNode.of(UIMessage.assistant(body)))
            }
        }
        val reply = prefetchLongReply()
        gateway.reply = reply
        gateway.delayMs = 20
        gateway.beforeFinish = null
        repository.insertConversation(Conversation.ofId(id, messages = history).copy(title = "Prefetch measurement fixture"))
        val cache = ParseCacheProbe()
        val worker = android.os.HandlerThread("prefetch-frames").apply { start() }
        try {
            open(id).use { scenario ->
                compose.waitUntil(30_000) { compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                suspend fun walkHistory() {
                    for (index in listOf(15, 30, 45, 58, 0)) {
                        timelineScrollable().performScrollToIndex(index)
                        compose.waitForIdle()
                        delay(400)
                    }
                    delay(1_500)
                }
                walkHistory()
                val historyCachedBefore = cache.cachedCount(historyBodies)

                val frames = java.util.Collections.synchronizedList(mutableListOf<Double>())
                val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                    frames.add(metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION) / 1_000_000.0)
                }
                compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextInput("Long reply for prefetch measurement")
                val send = sendStopButton(R.string.send).fetchSemanticsNode().boundsInWindow.center
                val missesBefore = cache.misses()
                cache.startTracking()
                scenario.onActivity { it.window.addOnFrameMetricsAvailableListener(listener, android.os.Handler(worker.looper)) }
                compose.mainClock.autoAdvance = false
                val started = SystemClock.uptimeMillis()
                tap(send.x, send.y)
                withTimeout(150_000) {
                    while (text(service, id).length < reply.length ||
                        service.getGenerationJobStateFlow(id).value?.isActive == true
                    ) pump(32)
                }
                pump(2_000) // drain + settled handover
                scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
                val generationMs = SystemClock.uptimeMillis() - started
                val (newEntries, newChars) = cache.stopTracking()
                val missesGeneration = cache.misses() - missesBefore
                val historyCachedAfter = cache.cachedCount(historyBodies)
                compose.mainClock.autoAdvance = true
                awaitImeHidden(scenario)

                val missesBeforeWalk = cache.misses()
                walkHistory()
                val historyReparseMisses = cache.misses() - missesBeforeWalk

                val sorted = synchronized(frames) { frames.sorted() }
                fun pct(q: Double) = if (sorted.isEmpty()) -1.0 else sorted[minOf(sorted.lastIndex, (q * sorted.size).toInt())]
                val result = org.json.JSONObject().apply {
                    put("replyChars", reply.length); put("generationMs", generationMs)
                    put("missesDuringGeneration", missesGeneration)
                    put("newCacheEntriesDuringGeneration", newEntries); put("newCachedCharsDuringGeneration", newChars)
                    put("historyBodies", historyBodies.size)
                    put("historyCachedBefore", historyCachedBefore); put("historyCachedAfter", historyCachedAfter)
                    put("historyReparseMisses", historyReparseMisses)
                    put("frames", sorted.size); put("p50", pct(0.50)); put("p95", pct(0.95)); put("p99", pct(0.99))
                    put("over32Pct", if (sorted.isEmpty()) -1.0 else 100.0 * sorted.count { it > 32.0 } / sorted.size)
                }
                writeEvidence("production-prefetch-measure", result)
                check(text(service, id) == reply) { "Generated reply changed" }
            }
        } finally {
            compose.mainClock.autoAdvance = true
            worker.quitSafely()
            service.stopGeneration(id)
            repository.getConversationById(id)?.let { service.deleteConversation(it) }
        }
    }

    private fun prefetchHistoryBody(turn: Int): String =
        "## 历史第 $turn 轮\n\n这是第 $turn 轮的完整回答，包含 **加粗**、`code` 与 [链接](https://example.com/$turn)。\n\n" +
            "- 要点一：缓存命中\n- 要点二：滚动回看\n\n```kotlin\nfun history$turn() = $turn\n```\n\n" +
            "| 项目 | 值 |\n| --- | --- |\n| 轮次 | $turn |\n\n" + "历史正文用于填充长度。".repeat(30)

    private fun prefetchLongReply(): String = buildString {
        for (section in 0 until 40) {
            append("## 第 $section 节\n\n中文说明 **重点** and [link](https://example.com/$section)。")
            append("这一段用于验证生成中的长回复。\n\n```kotlin\nfun answer$section(x: Int) = x + $section\n```\n\n")
            append("| 项目 | 结果 |\n| --- | --- |\n| 小节 | $section |\n\n")
        }
        append("PREFETCH_MEASURE_END")
    }

    /** Reads MarkdownParseCache by reflection without touching its LRU access order. */
    private class ParseCacheProbe {
        private val owner = Class.forName("app.amber.feature.ui.components.richtext.MarkdownParseCache")
        private val instance = owner.getDeclaredField("INSTANCE").get(null)
        private fun field(name: String) = owner.getDeclaredField(name).apply { isAccessible = true }
        private val lock: Any = field("lock").get(instance)!!
        private val missesField = field("misses")
        @Suppress("UNCHECKED_CAST")
        private val entries = field("entries").get(instance) as Map<Any, Any>
        private var contentField: java.lang.reflect.Field? = null
        private val seen = HashSet<String>()
        @Volatile private var running = false
        private var tracker: Thread? = null
        private var newEntries = 0
        private var newChars = 0L

        fun misses(): Int = synchronized(lock) { missesField.getInt(instance) }

        fun keys(): List<String> {
            val raw = synchronized(lock) { entries.keys.toList() }
            return raw.map { key ->
                val f = contentField ?: key.javaClass.getDeclaredField("content").apply { isAccessible = true }
                    .also { contentField = it }
                f.get(key) as String
            }
        }

        fun cachedCount(bodies: List<String>): Int = keys().toHashSet().let { cached -> bodies.count { it in cached } }

        private fun sample() {
            for (key in keys()) if (seen.add(key)) { newEntries++; newChars += key.length }
        }

        fun startTracking() {
            seen.addAll(keys())
            running = true
            tracker = Thread {
                while (running) { sample(); Thread.sleep(5) }
            }.apply { start() }
        }

        fun stopTracking(): Pair<Int, Long> {
            running = false
            tracker?.join()
            sample()
            return newEntries to newChars
        }
    }

    /**
     * The reader is anchored in an older message while the short reply below
     * stays visible off-bottom. The viewport reading line is outside the reply,
     * so same-frame hold lets the settled tree take over immediately without
     * moving the older anchor more than 2px in any sampled frame. The method
     * name used to say "defers"; that was the round-3 visible-reply rule, which
     * this round keeps only when the reading line is *inside* the reply.
     */
    @Test(timeout = 120_000)
    fun productionVisibleReplySwapsUnderOlderReadingAnchor(): Unit = runBlocking {
        requireFixture()
        val (repository, service, gateway) = isolatedFixture()
        val id = Uuid.random()
        // Settled history is virtualized per Markdown block; keep the anchor in
        // the last block so it is composed just above the new exchange.
        val older = "旧消息段落用于阅读位置，保持稳定。\n\n".repeat(40) + "OLDER_READ_ANCHOR 旧消息锚点。"
        val reply = compactRichReply()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        gateway.reply = reply
        gateway.delayMs = 12
        gateway.beforeFinish = gate
        gateway.finishGateReached = false
        repository.insertConversation(Conversation.ofId(id, messages = listOf(
            MessageNode.of(UIMessage.user("Older question")),
            MessageNode.of(UIMessage.assistant(older)),
        )).copy(title = "Production older anchor fixture"))
        try {
            open(id).use { scenario ->
                compose.waitUntil(30_000) { compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextInput("Rich reply below older anchor")
                val send = sendStopButton(R.string.send).fetchSemanticsNode().boundsInWindow.center
                compose.mainClock.autoAdvance = false
                tap(send.x, send.y)
                withTimeout(45_000) {
                    while (!gateway.finishGateReached || "RICH_END" !in text(service, id)) pump(32)
                }
                awaitImeHidden(scenario)
                pumpUntil(15_000, "rendered reply tail") { tokenTop("RICH_END") != null }
                pump(1_500)
                val height = run { var h = 0; scenario.onActivity { h = it.window.decorView.height }; h }
                val readingLine = timelineReadingLineY()
                var dragPasses = 0
                fun olderY() = tokenTop("OLDER_READ_ANCHOR")
                fun introY() = tokenTop("RICH_INTRO")
                fun olderAnchorReady(): Boolean {
                    val older = olderY() ?: return false
                    val intro = introY() ?: return false
                    val bounds = timelineNode().boundsInRoot
                    val lowerHalf = bounds.top + bounds.height * 0.5f
                    // Visual-top is the production reading line; the fixture still
                    // places the reply in the lower half so the reader is in the
                    // older message (4c passing geometry). One short drag leaves
                    // the reply filling the midpoint and a 84px settle jump
                    // (359→275) that 6-pass 4c runs did not see.
                    return older in 0f..height.toFloat() &&
                        intro in 0f..height.toFloat() &&
                        intro > older &&
                        intro > lowerHalf &&
                        older < lowerHalf &&
                        timelineScrollValue() > 0f
                }
                while (!olderAnchorReady() && dragPasses < 40) {
                    drag(scenario, stepPx = 28f, steps = 4)
                    dragPasses++
                    Log.i(
                        TAG,
                        "older-anchor-drag pass=$dragPasses older=${olderY()} intro=${introY()} " +
                            "readingLine=$readingLine height=$height scroll=${timelineScrollValue()}",
                    )
                    if (introY() == null) break
                }
                pump(400)
                val before = requireNotNull(tokenTop("OLDER_READ_ANCHOR")) {
                    "Older anchor is not rendered after $dragPasses drag passes"
                }
                val replyTopBefore = requireNotNull(tokenTop("RICH_INTRO")) {
                    "Reply left the viewport after $dragPasses drag passes"
                }
                check(before in 0f..height.toFloat()) { "Older anchor is not visible: $before" }
                check(replyTopBefore in 0f..height.toFloat()) { "Reply must stay visible: $replyTopBefore" }
                check(replyTopBefore > readingLine) {
                    "Reply top $replyTopBefore is still above the reading line $readingLine; the gate would defer"
                }
                check(timelineScrollValue() > 0f) { "Reader must be away from the bottom" }
                check(!richSettled()) { "Reply settled before generation ended" }
                check(service.getGenerationJobStateFlow(id).value?.isActive == true)
                val frames = ArrayList<Float>()
                gate.complete(Unit)
                withTimeout(20_000) {
                    while (service.getGenerationJobStateFlow(id).value?.isActive == true) {
                        tokenTop("OLDER_READ_ANCHOR")?.let { frames += it }
                        pump(32)
                    }
                }
                try {
                    pumpUntil(2_000, "settled rich renderer while visible off-bottom") { richSettled() }
                } catch (error: Throwable) {
                    Log.i(
                        TAG,
                        "older-anchor-gate-timeout " +
                            "readingLine=$readingLine older=${olderY()} intro=${introY()} " +
                            "height=$height scroll=${timelineScrollValue()} " +
                            "rich=${richState()} image=${fixtureImageBounds()?.let { "${it.width}x${it.height}@${it.top}" }} " +
                            "before=$before replyTopBefore=$replyTopBefore " +
                            "samples=${frames.size} last=${frames.lastOrNull()}",
                    )
                    throw error
                }
                val imageDeadline = SystemClock.uptimeMillis() + 8_000
                while (SystemClock.uptimeMillis() < imageDeadline && !fixtureImageLoaded()) {
                    tokenTop("OLDER_READ_ANCHOR")?.let { frames += it }
                    pump(32)
                }
                pumpUntil(2_000, "older anchor still composed after settle/image") {
                    tokenTop("OLDER_READ_ANCHOR") != null
                }
                val after = requireNotNull(tokenTop("OLDER_READ_ANCHOR")) {
                    "Older anchor left composition after settle/image; " +
                        "samples=${frames.size} last=${frames.lastOrNull()} before=$before " +
                        "image=${fixtureImageBounds()?.let { "${it.width}x${it.height}" }}"
                }
                frames += after
                val replyTopAfter = tokenTop("RICH_INTRO")
                pump(1_000)
                val stable = requireNotNull(tokenTop("OLDER_READ_ANCHOR"))
                frames += stable
                val swapped = richSettled()
                val maxDeviation = (frames + before).maxOf { kotlin.math.abs(it - before) }
                if (maxDeviation > 2f) {
                    val bounds = timelineNode().boundsInRoot
                    Log.i(
                        TAG,
                        "older-anchor-moved " +
                            "readingLine=$readingLine older=${olderY()} intro=${introY()} " +
                            "height=$height scroll=${timelineScrollValue()} " +
                            "bounds=${bounds.top}..${bounds.bottom} " +
                            "before=$before after=$after stable=$stable " +
                            "replyTopBefore=$replyTopBefore replyTopAfter=$replyTopAfter " +
                            "samples=$frames",
                    )
                }
                returnToBottom(scenario)
                pumpUntil(10_000, "settled rich renderer remains at the bottom") { richSettled() }
                val result = org.json.JSONObject().apply {
                    put("before", before); put("after", after); put("stable", stable)
                    put("replyTopBefore", replyTopBefore); put("replyTopAfter", replyTopAfter ?: -1f)
                    put("swappedWhileVisible", swapped); put("settledAfterReturningToBottom", true)
                    put("maxDeviation", maxDeviation); put("layoutSamples", frames.size)
                    put("imageHeight", fixtureImageHeight()); put("mathSettled", mathSettled())
                    put("coordinateSpace", "Text positionInRoot + TextLayoutResult.getBoundingBox")
                }
                writeEvidence("production-older-anchor", result)
                check(maxDeviation <= 2f) { "Older reading anchor moved in some frame: ${maxDeviation}px $before->$after samples=$frames" }
                check(kotlin.math.abs(stable - after) <= 2f) { "Older reading anchor drifted: $after->$stable" }
                check(swapped) { "Visible off-bottom reply did not swap to settled under the reader" }
                check(mathSettled()) { "Settled math did not render as [Latex]" }
                check(fixtureImageLoaded()) { "Local fixture image did not load (height=${fixtureImageHeight()})" }
                withTimeout(15_000) {
                    while (storedText(requireNotNull(repository.getConversationById(id))) != reply) delay(50)
                }
                Log.i(TAG, "production-older-anchor passed before=$before after=$after stable=$stable swapped=true maxDeviation=$maxDeviation Room=true")
            }
        } finally {
            compose.mainClock.autoAdvance = true
            gate.complete(Unit)
            gateway.beforeFinish = null
            service.stopGeneration(id)
            repository.getConversationById(id)?.let { service.deleteConversation(it) }
        }
    }

    @Test(timeout = 90_000)
    fun reopenDurableReplyInFreshProcess(): Unit = runBlocking {
        requireFixture()
        val proof = context.getSharedPreferences(PROOF_PREF, Context.MODE_PRIVATE)
        val id = Uuid.parse(requireNotNull(proof.getString("id", null)))
        val expected = requireNotNull(proof.getString("text", null))
        val repository: ConversationRepository = getKoin().get()
        val stored = requireNotNull(repository.getConversationById(id))
        check(storedText(stored) == expected)
        open(id).use {
            compose.waitUntil(30_000) { compose.onAllNodesWithText("DEVICE_FINAL_COPY_MARKER", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().any { it.boundsInRoot.height > 0 } }
            Log.i(TAG, "fresh-process reopen passed conversation=$id chars=${expected.length} sha256=${sha(expected)}")
        }
        getKoin().get<ChatService>().deleteConversation(stored)
        proof.edit().clear().commit()
    }
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
    companion object {
        private const val TAG = "AmberDeviceRegression"
        private const val PROOF_PREF = "chat-device-proof"
        private const val RICH_CELL = "CELL_ALPHA"
        private const val RICH_IMAGE_ALT = "FIXTURE_IMAGE_ALT"
    }
}
