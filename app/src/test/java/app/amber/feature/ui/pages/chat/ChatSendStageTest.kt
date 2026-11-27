package app.amber.feature.ui.pages.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.model.toMessageNode
import app.amber.core.service.ConversationTimelineLoadState
import app.amber.core.service.PendingUserMessage
import app.amber.core.service.PendingUserMessageMode
import app.amber.core.settings.Settings
import app.amber.feature.macgateway.ProactiveCard
import app.amber.feature.macgateway.ProactiveEventMeta
import app.amber.feature.ui.components.ai.ChatInputSubmissionOwner
import app.amber.feature.ui.hooks.ChatInputState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class ChatSendStageTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun onlyAcceptedNormalSendArmsAndMatchingUserNodeActivatesStage() {
        val stage = ChatSendStageState()
        assertFalse(stage.isActive)

        // History replay / process restore / unsolicited user node without accepted submit
        // must never activate the stage.
        stage.onUserNodeAppeared(Uuid.random())
        assertFalse(stage.isActive)

        // Accepted normal submit arms the stage, but does not activate until user node appears.
        val intent = SendStageIntent(host = "local", conversationId = "conv-1", sendRevision = 1L, messageId = Uuid.random())
        stage.onSubmitAccepted(intent)
        assertFalse(stage.isActive)
        assertTrue(stage.isPending)

        // Matching user node appears -> stage activates.
        val userKey = Uuid.random()
        stage.onUserNodeAppeared(userKey)
        assertTrue(stage.isActive)
        assertEquals(intent, stage.activeIntent)
        assertEquals(userKey, stage.stagedMessageKey)

        // Reset clears everything cleanly (conversation switch / branch switch / deep link).
        stage.reset()
        assertFalse(stage.isActive)
        assertFalse(stage.isPending)
        assertNull(stage.activeIntent)
        assertNull(stage.stagedMessageKey)
        assertEquals(0, stage.spacerPx)
    }

    @Test
    fun onlyAcceptedNormalSendArmsStageForSubmittedMessageId() = runBlocking {
        val input = ChatInputState().apply {
            bindToConversation("conv-1")
            setMessageText("hello")
        }
        val stage = ChatSendStageState()
        var hideCount = 0
        var submittedMessageId = Uuid.random()
        var rev = 0L
        var followupActive = false
        var sendAccepted = true
        val errors = mutableListOf<Int>()

        fun makeActions(
            configurationIssue: ChatConfigurationIssue? = null,
            generating: Boolean = false,
            cloudDialogueMode: Boolean = false,
        ) = ChatPageInputActions(
            input = input,
            submission = ChatInputSubmissionOwner(input),
            configurationIssue = configurationIssue,
            generating = generating,
            cloudDialogueMode = cloudDialogueMode,
            conversationId = "conv-1",
            sendStage = stage,
            isFollowupTargetActive = { followupActive },
            nextSendRevision = { ++rev },
            hideKeyboard = { hideCount++ },
            showError = { errors += it },
            sendMessage = { _, _, _, messageId ->
                submittedMessageId = messageId
                if (sendAccepted) CompletableDeferred(true) else null
            },
            editMessage = { _, _, _ -> true },
        )

        val parts = listOf(UIMessagePart.Text("hello"))

        // 1. Config issue fails -> keeps draft & focus, no stage, no keyboard hide.
        assertFalse(
            makeActions(configurationIssue = ChatConfigurationIssue.MissingModel)
                .send(PendingUserMessageMode.FOLLOWUP, parts, null),
        )
        assertFalse(stage.isPending)
        assertEquals("hello", input.textContent.text.toString())

        // 2. Rejected send -> no stage, no keyboard hide.
        sendAccepted = false
        assertFalse(makeActions().send(PendingUserMessageMode.FOLLOWUP, parts, null))
        assertFalse(stage.isPending)
        sendAccepted = true

        // 3. Edit message -> must never trigger stage or hide via send stage.
        assertTrue(makeActions().send(PendingUserMessageMode.FOLLOWUP, parts, Uuid.random()))
        assertFalse(stage.isPending)

        // 4. STEER mode -> must never trigger stage.
        assertTrue(makeActions().send(PendingUserMessageMode.STEER, parts, null))
        assertFalse(stage.isPending)

        // 5. Interjection while generating -> must never trigger stage.
        assertTrue(makeActions(generating = true).send(PendingUserMessageMode.FOLLOWUP, parts, null))
        assertFalse(stage.isPending)

        // 6. Cloud targeted followup (interjection to an active cloud task) -> must never trigger stage.
        followupActive = true
        assertTrue(makeActions(cloudDialogueMode = true).send(PendingUserMessageMode.FOLLOWUP, parts, null))
        assertFalse(stage.isPending)
        followupActive = false

        // 7. longSend -> must never trigger stage.
        assertTrue(makeActions().longSend(PendingUserMessageMode.FOLLOWUP, parts, null))
        assertFalse(stage.isPending)

        assertTrue(makeActions().send(
            PendingUserMessageMode.FOLLOWUP,
            listOf(UIMessagePart.Text("/deepread article")),
            null,
        ))
        assertFalse("route commands do not arm a future chat message", stage.isPending)
        assertEquals(0, hideCount)

        // 8. Normal local send accepted -> arms stage with host="local" and hides keyboard.
        assertTrue(makeActions().send(PendingUserMessageMode.FOLLOWUP, parts, null))
        assertTrue(stage.isPending)
        assertEquals(SendStageIntent("local", "conv-1", 1L, submittedMessageId), stage.pendingIntent)
        assertEquals(1, hideCount)

        // 9. Normal cloud send accepted -> arms stage with host="cloud" and increments sendRevision.
        assertTrue(makeActions(cloudDialogueMode = true).send(PendingUserMessageMode.FOLLOWUP, parts, null))
        assertTrue(stage.isPending)
        assertEquals(SendStageIntent("cloud", "conv-1", 2L, submittedMessageId), stage.pendingIntent)
        assertEquals(2, hideCount)
    }

    @Test
    fun cloudDraftIsConsumedOnlyAfterLocalEchoAndNewTypingSurvives() = runBlocking {
        val input = ChatInputState().apply {
            bindToConversation("conv")
            setMessageText("original")
        }
        val owner = ChatInputSubmissionOwner(input)
        val stage = ChatSendStageState()
        var echo = CompletableDeferred<Boolean>()
        var hidden = 0
        var calls = 0
        val actions = ChatPageInputActions(
            input = input, submission = owner, configurationIssue = null, generating = false,
            cloudDialogueMode = true, conversationId = "conv", sendStage = stage,
            hideKeyboard = { hidden++ }, showError = {}, editMessage = { _, _, _ -> false },
            sendMessage = { _, _, _, _ -> calls++; echo },
        )
        suspend fun submit() = owner.submit("conv", validate = { true }) { parts, edit ->
            actions.send(PendingUserMessageMode.FOLLOWUP, parts, edit)
        }

        val rejected = async(start = CoroutineStart.UNDISPATCHED) { submit() }
        assertFalse(submit())
        assertEquals(1, calls)
        assertEquals("original", input.textContent.text.toString())
        assertFalse(stage.isPending)
        echo.complete(false)
        assertFalse(rejected.await())
        assertEquals("original", input.textContent.text.toString())
        assertEquals(0, hidden)

        echo = CompletableDeferred()
        val accepted = async(start = CoroutineStart.UNDISPATCHED) { submit() }
        input.setMessageText("new draft")
        echo.complete(true)
        assertTrue(accepted.await())
        assertEquals("new draft", input.textContent.text.toString())
        assertEquals(0, hidden)
        assertTrue(stage.isPending)
    }

    @Test
    fun spacerGeometryRespondsToViewportHAndStreamingTAndHandsOffOnOverflow() {
        assertEquals(600, computeSendStageSpacerPx(viewportHeightPx = 800, turnHeightPx = 200))
        assertEquals(0, computeSendStageSpacerPx(viewportHeightPx = 800, turnHeightPx = 800))
        assertEquals(0, computeSendStageSpacerPx(viewportHeightPx = 800, turnHeightPx = 1100))

        val stage = ChatSendStageState()
        stage.onSubmitAccepted(SendStageIntent("local", "conv-1", 1L, Uuid.random()))
        stage.onUserNodeAppeared("u1")

        // Keyboard open: H = 420, T = 160 -> spacer = 260.
        stage.updateViewportHeight(420)
        assertEquals(260, stage.updateTurnHeight(160))
        assertTrue(stage.isActive)

        // Keyboard dismissing: H grows 420 -> 620 -> 820, T unchanged -> spacer grows in lockstep.
        assertEquals(460, stage.updateViewportHeight(620))
        assertEquals(660, stage.updateViewportHeight(820))
        assertTrue(stage.isActive)

        // Streaming response grows: T grows 160 -> 360 -> 620, H unchanged -> spacer absorbs growth.
        assertEquals(460, stage.updateTurnHeight(360))
        assertEquals(200, stage.updateTurnHeight(620))
        assertTrue(stage.isActive)

        // Short reply settles: stage stays active with spacer preserved (no single-frame bounce).
        assertEquals(200, stage.spacerPx)
        assertTrue(stage.isActive)

        // Long reply exceeds viewport: T = 840 >= H = 820 -> spacer clamps to 0 and stage hands off.
        assertEquals(0, stage.updateTurnHeight(840))
        assertFalse(stage.isActive)
    }

    @Test
    fun stageActiveIsMutuallyExclusiveWithAutoScrollAndTailGrowthHold() {
        assertFalse(
            shouldAutoScrollOnSendAppend(
                enableAutoScroll = true,
                pendingAdded = 1,
                directSendAppend = true,
                sendStageActive = true,
            ),
        )
        assertTrue(
            shouldAutoScrollOnSendAppend(
                enableAutoScroll = true,
                pendingAdded = 0,
                directSendAppend = true,
                sendStageActive = false,
            ),
        )
        assertFalse(
            shouldHoldReadingOnTailGrowth(
                firstIndex = 1,
                firstOffset = 120,
                followBottomPx = 24,
                growingItemVisible = true,
                sendStageActive = true,
            ),
        )
        assertFalse(
            shouldAnimateStreamingTail(
                index = 0,
                offset = 0,
                threshold = 24,
                scrolling = false,
                sendStageActive = true,
            ),
        )
    }

    @Test
    fun tailGrowthHoldDoesNotWriteScrollStateWhileSendStageIsActive() {
        val stage = ChatSendStageState().apply {
            onSubmitAccepted(SendStageIntent("local", "conv-1", 1L, Uuid.random()))
            onUserNodeAppeared("msg-1")
        }
        var tailHeight by mutableIntStateOf(120)
        lateinit var listState: LazyListState
        compose.setContent {
            listState = rememberLazyListState()
            LazyColumn(Modifier.height(360.dp), state = listState, reverseLayout = true) {
                item(key = "tail") { Box(Modifier.fillMaxWidth().height(24.dp)) }
                item(key = "msg-1") {
                    Box(
                        Modifier
                            .holdReadingOnTailGrowth(listState, lazyIndex = 1, followBottomPx = 0, itemKey = "msg-1", sendStage = stage)
                            .fillMaxWidth()
                            .height(tailHeight.dp),
                    )
                }
                items(8, key = { "old-$it" }) {
                    Box(Modifier.fillMaxWidth().height(90.dp))
                }
            }
        }
        compose.runOnIdle { runBlocking { listState.scrollToItem(1, 50) } }
        compose.waitForIdle()
        val beforeOffset = listState.firstVisibleItemScrollOffset
        compose.runOnIdle { tailHeight = 260 }
        compose.waitForIdle()
        assertEquals(
            "ChatTailGrowthHold must not mutate scroll offset while sendStage owns scrolling",
            beforeOffset,
            listState.firstVisibleItemScrollOffset,
        )
    }

    @Test
    fun queueDrainDoesNotActivateStageAndUserDragExitsStageImmediately() {
        var conversation by mutableStateOf(
            Conversation(
                assistantId = Uuid.random(),
                messageNodes = List(10) { UIMessage.user("history $it").toMessageNode() },
            ),
        )
        var pending by mutableStateOf(
            listOf(PendingUserMessage("queued-1", listOf(UIMessagePart.Text("queued")))),
        )
        var cards by mutableStateOf(emptyList<ProactiveCard>())
        val stage = ChatSendStageState()
        lateinit var listState: LazyListState

        compose.setContent {
            listState = rememberLazyListState()
            val timelineConversation = conversation
            val timelinePending = pending
            val plan = buildChatTimelinePlan(
                conversation = timelineConversation,
                assistant = emptyList(),
                showAssistantBubble = true,
                timelineLoading = false,
                hasHistoryLoadingItem = false,
                pendingMessageCount = timelinePending.size,
                postSendState = buildPostSendTimelineState(timelineConversation, false),
                virtualItemCache = remember { ChatVirtualItemCache() },
                proactiveCards = cards,
            )
            ChatTimelineScrollEffects(
                conversation = timelineConversation,
                pendingUserMessages = timelinePending,
                activeGeneration = false,
                timelineLoadState = ConversationTimelineLoadState(),
                chatTimelinePlan = plan,
                chatRegexes = emptyList(),
                state = listState,
                innerPadding = PaddingValues(),
                settings = Settings(),
                onLoadOlderTimeline = {},
                onRecentScrollChanged = {},
                sendStage = stage,
            )
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .size(320.dp, 360.dp)
                    .testTag("stage-list"),
            ) {
                items(
                    count = plan.entries.size,
                    key = {
                        plan.entries[it].lazyItemKey(
                            timelineConversation.id, plan.postSendState, timelinePending, "compact",
                        )
                    },
                ) {
                    Box(Modifier.fillMaxWidth().height(72.dp))
                }
            }
        }

        // Scroll into history and drain a queued message -> must NOT activate stage or yank reader.
        compose.runOnIdle { listState.requestScrollToItem(6) }
        compose.waitForIdle()
        compose.runOnIdle {
            pending = emptyList()
            conversation = conversation.copy(
                messageNodes = conversation.messageNodes + UIMessage.user("drained").toMessageNode(),
            )
        }
        compose.waitForIdle()
        assertFalse(stage.isActive)
        assertTrue(listState.firstVisibleItemIndex > 0)

        // Normal accepted send + matching new user node -> activates stage and returns once to index 0.
        val sentNode = UIMessage.user("normal send").toMessageNode()
        compose.runOnIdle {
            stage.onSubmitAccepted(
                SendStageIntent(
                    host = "local",
                    conversationId = conversation.id.toString(),
                    sendRevision = 1L,
                    messageId = sentNode.currentMessage.id,
                ),
            )
            cards = listOf(ProactiveCard(
                gatewayId = "gw", deviceId = "device", eventId = "proactive:s1",
                signalId = "s1", seq = 1, text = "Reminder",
                meta = ProactiveEventMeta("s1", "topic", "reminder", "Reminder"),
                receivedAt = System.currentTimeMillis(),
            ))
        }
        compose.waitForIdle()
        assertTrue("an incoming proactive card leaves the send pending", stage.isPending)
        assertTrue(listState.firstVisibleItemIndex > 0)

        compose.runOnIdle {
            conversation = conversation.copy(messageNodes = conversation.messageNodes + UIMessage.user("unrelated").toMessageNode())
        }
        compose.waitForIdle()
        assertTrue("an unrelated user echo cannot claim the stage", stage.isPending)
        assertTrue(listState.firstVisibleItemIndex > 0)

        compose.runOnIdle {
            conversation = conversation.copy(messageNodes = conversation.messageNodes + sentNode)
        }
        compose.waitForIdle()
        assertTrue("matching user node activates stage", stage.isActive)
        assertEquals(sentNode.id, stage.stagedMessageKey)
        assertEquals(0, listState.firstVisibleItemIndex)

        // Manual drag exits stage immediately.
        compose.onNodeWithTag("stage-list").performTouchInput {
            down(Offset(160f, 180f))
            moveBy(Offset(0f, 120f))
            up()
        }
        compose.waitForIdle()
        assertFalse("user drag must immediately exit send stage", stage.isActive)

        val delayedNode = UIMessage.user("late echo").toMessageNode()
        compose.runOnIdle {
            listState.requestScrollToItem(6)
            stage.onSubmitAccepted(SendStageIntent("cloud", conversation.id.toString(), 2L, delayedNode.currentMessage.id))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("stage-list").performTouchInput {
            down(Offset(160f, 180f))
            moveBy(Offset(0f, 60f))
            up()
        }
        compose.waitForIdle()
        assertFalse(stage.isPending)
        compose.runOnIdle {
            conversation = conversation.copy(messageNodes = conversation.messageNodes + delayedNode)
        }
        compose.waitForIdle()
        assertFalse(stage.isActive)
        assertTrue("a late echo cannot pull a reader back after dragging", listState.firstVisibleItemIndex > 0)
    }

    @Test
    fun branchSwitchAndConversationSwitchResetActiveStage() {
        val firstConvId = Uuid.random()
        val stagedNode = MessageNode(
            messages = listOf(UIMessage.user("v1"), UIMessage.user("v2")),
            selectIndex = 0,
        )
        var conversation by mutableStateOf(
            Conversation(
                id = firstConvId,
                assistantId = Uuid.random(),
                messageNodes = listOf(UIMessage.user("older").toMessageNode()),
            ),
        )
        val stage = ChatSendStageState()

        compose.setContent {
            val state = rememberLazyListState()
            val plan = rememberChatTimelinePlan(conversation, emptyList(), true, false, false, false, 0)
            ChatTimelineScrollEffects(
                conversation = conversation,
                pendingUserMessages = emptyList(),
                activeGeneration = false,
                timelineLoadState = ConversationTimelineLoadState(),
                chatTimelinePlan = plan,
                chatRegexes = emptyList(),
                state = state,
                innerPadding = PaddingValues(),
                settings = Settings(),
                onLoadOlderTimeline = {},
                onRecentScrollChanged = {},
                sendStage = stage,
            )
        }

        compose.runOnIdle {
            stage.onSubmitAccepted(SendStageIntent("local", firstConvId.toString(), 1L, stagedNode.currentMessage.id))
            conversation = conversation.copy(messageNodes = conversation.messageNodes + stagedNode)
        }
        compose.waitForIdle()
        assertTrue(stage.isActive)

        // Switching message variant (branch) resets stage.
        compose.runOnIdle {
            conversation = conversation.copy(
                messageNodes = listOf(
                    conversation.messageNodes.first(),
                    stagedNode.copy(selectIndex = 1),
                ),
            )
        }
        compose.waitForIdle()
        assertFalse("branch switch must reset active stage", stage.isActive)
        assertEquals(0, stage.spacerPx)

        // Re-arm and activate, then switch conversation -> reset.
        val secondUserNode = UIMessage.user("second").toMessageNode()
        compose.runOnIdle {
            stage.onSubmitAccepted(SendStageIntent("local", firstConvId.toString(), 2L, secondUserNode.currentMessage.id))
            conversation = conversation.copy(messageNodes = conversation.messageNodes + secondUserNode)
        }
        compose.waitForIdle()
        assertTrue(stage.isActive)

        compose.runOnIdle {
            stage.reset()
            conversation = Conversation(
                id = Uuid.random(),
                assistantId = Uuid.random(),
                messageNodes = emptyList(),
            )
        }
        compose.waitForIdle()
        assertFalse(stage.isActive)
        assertEquals(0, stage.spacerPx)
    }

    @Test
    fun reverseLayoutStageKeepsStagedUserTopPinnedAcrossKeyboardDismissAndStreamingGrowth() {
        // Stay within Robolectric's default 470dp window height so constraints are never clamped.
        var viewportHeightDp by mutableIntStateOf(260)
        var assistantHeightDp by mutableIntStateOf(40)
        val stage = ChatSendStageState()
        val userKey = Uuid.random()
        var userTopPx = -1f

        compose.setContent {
            val density = LocalDensity.current
            val listState = rememberLazyListState()
            val contentPadding = remember(density, stage) {
                SendStageContentPaddingValues(
                    start = 0.dp,
                    top = 0.dp,
                    end = 0.dp,
                    baseBottom = 0.dp,
                    density = density,
                    sendStage = stage,
                )
            }
            CompositionLocalProvider(LocalChatSendStage provides stage) {
                Box(Modifier.size(320.dp, viewportHeightDp.dp)) {
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        contentPadding = contentPadding,
                        modifier = Modifier
                            .fillMaxSize()
                            .chatSendStageLayout(
                                listState = listState,
                                sendStage = stage,
                                topPaddingPx = 0,
                                baseBottomPaddingPx = 0,
                                stagedLazyTopIndex = 2,
                            ),
                    ) {
                        item(key = TimelineTailKey) { Box(Modifier.fillMaxWidth().height(20.dp)) }
                        item(key = "assistant") { Box(Modifier.fillMaxWidth().height(assistantHeightDp.dp)) }
                        item(key = userKey) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(60.dp)
                                    .onGloballyPositioned { userTopPx = it.positionInRoot().y },
                            )
                        }
                        items(8, key = { "hist-$it" }) {
                            Box(Modifier.fillMaxWidth().height(90.dp))
                        }
                    }
                }
            }
        }

        // Activate stage: H = 260dp, T = 20 + 40 + 60 = 120dp -> spacer = 140dp.
        compose.runOnIdle {
            stage.onSubmitAccepted(SendStageIntent("local", "conv-1", 1L, Uuid.random()))
            stage.onUserNodeAppeared(userKey)
        }
        compose.waitForIdle()
        val expected140 = with(compose.density) { 140.dp.roundToPx() }
        assertTrue(stage.isActive)
        assertTrue("expected spacer ~${expected140}px, got ${stage.spacerPx}", abs(stage.spacerPx - expected140) <= 2)
        assertTrue("user message top should align near top reading line, got $userTopPx", abs(userTopPx) <= 2f)

        // Keyboard dismisses: H grows 260dp -> 420dp -> spacer grows to 300dp, user top stays pinned at 0.
        compose.runOnIdle { viewportHeightDp = 420 }
        compose.waitForIdle()
        val expected300 = with(compose.density) { 300.dp.roundToPx() }
        assertTrue("expected spacer ~${expected300}px, got ${stage.spacerPx}", abs(stage.spacerPx - expected300) <= 2)
        assertTrue("user message top stayed pinned after keyboard dismiss, got $userTopPx", abs(userTopPx) <= 2f)

        // Streaming reply grows: assistant 40dp -> 180dp (+140dp) -> spacer shrinks to 160dp, user top stays pinned.
        compose.runOnIdle { assistantHeightDp = 180 }
        compose.waitForIdle()
        val expected160 = with(compose.density) { 160.dp.roundToPx() }
        assertTrue("expected spacer ~${expected160}px, got ${stage.spacerPx}", abs(stage.spacerPx - expected160) <= 2)
        assertTrue("user message top stayed pinned during streaming growth, got $userTopPx", abs(userTopPx) <= 2f)
    }
}
