package app.amber.feature.ui.pages.chat

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageAnnotation
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

class ChatListSupportTest {
    @Test
    fun `latest render token is empty for empty conversation`() {
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = emptyList(),
        )

        assertEquals("0:empty", conversation.latestRenderToken())
    }

    @Test
    fun `latest render token uses current message from last node`() {
        val first = UIMessage.user("first")
        val unselected = UIMessage.assistant("unselected")
        val selected = UIMessage.assistant("selected")
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(
                MessageNode.of(first),
                MessageNode(
                    messages = listOf(unselected, selected),
                    selectIndex = 1,
                ),
            ),
        )

        val token = conversation.latestRenderToken()

        assertTrue(token.startsWith("${conversation.messageNodes.size}:${selected.id}:${selected.parts.size}:0:"))
        assertTrue(token.contains("text:8:selected:${"selected".hashCode()}"))
    }

    @Test
    fun `latest render token keeps compact text and tool part format`() {
        val textMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("12345678901234567890")),
        )
        val toolMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "search",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("done")),
                    approvalState = ToolApprovalState.Approved,
                ),
            ),
        )

        val textToken = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(textMessage)),
        ).latestRenderToken()
        val toolToken = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(toolMessage)),
        ).latestRenderToken()

        assertTrue(textToken.contains("text:20:5678901234567890:${"12345678901234567890".hashCode()}"))
        assertTrue(toolToken.contains("tool:call-1:search:2:{}:${"{}".hashCode()}:true:approved:1:"))
    }

    @Test
    fun `latest render token changes when non trailing tool input changes`() {
        val first = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "search",
                    input = "{\"q\":\"a\"}",
                ),
                UIMessagePart.Text("tail"),
            ),
        )
        val second = first.copy(
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "search",
                    input = "{\"q\":\"amber\"}",
                ),
                UIMessagePart.Text("tail"),
            ),
        )

        val firstToken = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(first)),
        ).latestRenderToken()
        val secondToken = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(second)),
        ).latestRenderToken()

        assertTrue(firstToken.contains("tool:call-1:search:9:"))
        assertTrue(secondToken.contains("tool:call-1:search:13:"))
        assertTrue(firstToken != secondToken)
    }

    @Test
    fun `latest render token changes when same shape text content changes`() {
        val first = UIMessage.assistant("A1234567890123456")
        val second = first.copy(parts = listOf(UIMessagePart.Text("B1234567890123456")))

        val firstToken = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(first)),
        ).latestRenderToken()
        val secondToken = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(second)),
        ).latestRenderToken()

        assertTrue(firstToken != secondToken)
    }

    @Test
    fun `latest render token changes when same count annotations change`() {
        val first = UIMessage.assistant("answer").copy(
            annotations = listOf(UIMessageAnnotation.UrlCitation(title = "old", url = "https://old.example")),
        )
        val second = first.copy(
            annotations = listOf(UIMessageAnnotation.UrlCitation(title = "new", url = "https://new.example")),
        )

        val firstToken = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(first)),
        ).latestRenderToken()
        val secondToken = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(second)),
        ).latestRenderToken()

        assertTrue(firstToken != secondToken)
    }

    @Test
    fun `timeline uses reverse layout anchored at visual bottom`() {
        val source = repoFile("src/main/java/app/amber/feature/ui/pages/chat/ChatListNormalSection.kt").readText()
        val page = repoFile("src/main/java/app/amber/feature/ui/pages/chat/ChatPage.kt").readText()

        assertTrue(source.contains("reverseLayout = true"))
        // 跟随状态机已由 reverseLayout 原生锚定取代
        assertFalse(source.contains("TimelineFollowMode"))
        assertFalse(source.contains("scrollToTimelineBottom"))
        assertFalse(source.contains("streamingVisibleEvents"))
        assertFalse(source.contains("programmaticScrollInProgress"))
        // 默认初始位即底部：ChatPage 不再做"等两帧再 scrollToItem(last)"的初始化
        assertTrue(page.contains("rememberLazyListState(initialFirstVisibleItemIndex = initialChatListIndex)"))
        assertFalse(page.contains("withFrameNanos { }"))
    }

    @Test
    fun `timeline tail shield keeps native anchoring for structural insertions`() {
        val source = repoFile("src/main/java/app/amber/feature/ui/pages/chat/ChatListSupport.kt").readText()
        val section = repoFile("src/main/java/app/amber/feature/ui/pages/chat/ChatListNormalSection.kt").readText()

        // TimelineTail 恒为 reversed 发射的第一个 entry（index 0 = 视觉底部锚定边），
        // 一切结构性插入都发生在它之上（index ≥ 1），Compose key 锚定使钉底视图
        // 原生保持不动 —— 因此不需要任何"插入时 re-pin"的补偿 effect。
        assertTrue(source.contains("add(ChatTimelineEntry.TimelineTail)"))
        assertFalse(section.contains("prevFirst == 0"))
        assertFalse(section.contains("first == inserted"))
    }

    @Test
    fun `tail compact markers entry keeps plan and lazy indexes aligned`() {
        val plan = repoFile("src/main/java/app/amber/feature/ui/pages/chat/ChatListSupport.kt").readText()
        val section = repoFile("src/main/java/app/amber/feature/ui/pages/chat/ChatListNormalSection.kt").readText()

        // 尾部 compact 标记是常驻 plan entry（无标记时渲染 0 尺寸占位），否则
        // 反转发射序下它会把其后所有消息条目的 lazy index 推偏 +1。
        assertTrue(plan.contains("add(ChatTimelineEntry.TailCompactMarkers)"))
        assertTrue(section.contains("contentType = \"compact-timeline-tail\""))
        assertTrue(section.contains("Spacer(Modifier.fillMaxWidth())"))
    }

    @Test
    fun `tail indicator reserve is retained until the next message replaces the tail`() {
        val source = repoFile("src/main/java/app/amber/feature/ui/pages/chat/ChatListNormalSection.kt").readText()

        // reverseLayout 下 TimelineTail（index 0）在锚定边，生成结束立刻移除其
        // 70dp reserve 会让时间线单帧下坠；保留空间直到下一条消息成为尾部。
        assertTrue(source.contains("retainedTailReserveMessageId"))
        assertTrue(source.contains("timelineLoading ||"))
    }

    @Test
    fun `send paths land the view on the bottom regardless of scroll position`() {
        val source = repoFile("src/main/java/app/amber/feature/ui/pages/chat/ChatListNormalSection.kt").readText()

        // 监听 pending/直接发送追加统一覆盖所有发送路径；`prevPendingCount == 0`
        // 守卫把直接发送（追加前队列空）与队列排水（排水前 pending 非空）区分，
        // 后者不得拽走读历史的用户；尾节点 id 变化判定排除历史分页 prepend。
        assertTrue(source.contains("if (pendingAdded > 0 || directSendAppend)"))
        assertTrue(source.contains("prevPendingCount == 0"))
        assertTrue(source.contains("lastNodeId != prevLastNodeId"))
    }

    @Test
    fun `hidden assistant renders zero size placeholder keeping indexes aligned`() {
        val source = repoFile("src/main/java/app/amber/feature/ui/pages/chat/ChatListNormalSection.kt").readText()

        // 倒序发射下 Hidden 之后还有全部更早消息，不发 item 会让 plan 数组
        // 与真实 lazy index 错位（破坏 prewarm 与跳转映射）。
        assertTrue(source.contains("contentType = \"post-send-hidden-assistant\""))
        assertTrue(source.contains("Spacer(Modifier.fillMaxWidth())"))
    }

    @Test
    fun `markdown streaming visual callbacks remain available for council room`() {
        val markdown = repoFile("src/main/java/app/amber/feature/ui/components/richtext/Markdown.kt").readText()
        val message = repoFile("src/main/java/app/amber/feature/ui/components/message/ChatMessage.kt").readText()
        val council = repoFile("src/main/java/app/amber/feature/ui/pages/councilroom/CouncilTimelineTab.kt").readText()

        assertTrue(markdown.contains("onStreamingVisualActiveChange"))
        assertTrue(message.contains("onStreamingVisualActiveChange = onStreamingVisualActiveChange"))
        assertTrue(council.contains("onStreamingVisibleFrame"))
    }

    @Test
    fun `streaming tail indicator renders in list without pinned overlay`() {
        val source = repoFile("src/main/java/app/amber/feature/ui/pages/chat/ChatListNormalSection.kt").readText()
        val plan = repoFile("src/main/java/app/amber/feature/ui/pages/chat/ChatListSupport.kt").readText()

        // reverseLayout 下跟随时 TimelineTail item 恒在底部视口内，列表内指示器
        // 足够；pin 交接/保留圆点等防抖机制已删除。
        assertTrue(source.contains("tailIndicatorReserveVisible"))
        assertTrue(source.contains("key = TimelineTailKey"))
        assertTrue(source.contains("contentType = \"timeline-tail\""))
        assertTrue(source.contains("TimelineTailWorkingIndicator("))
        assertFalse(source.contains("pinTailIndicator"))
        assertFalse(source.contains("retainedTailIndicatorMessageId"))
        assertFalse(source.contains("AgentWorkingIndicator("))
        assertTrue(plan.contains("add(ChatTimelineEntry.TimelineTail)"))
        assertFalse(plan.contains("add(ChatTimelineEntry.Loading)"))
        assertFalse(plan.contains("add(ChatTimelineEntry.ScrollBottom)"))
    }

    private fun repoFile(pathInAppModule: String): File {
        return listOf(
            File(pathInAppModule),
            File("app/$pathInAppModule"),
        ).firstOrNull { it.isFile }
            ?: error("Cannot locate $pathInAppModule")
    }
}
