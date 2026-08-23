package app.amber.core.storage.conversation

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import app.amber.core.model.DEFAULT_ASSISTANT_ID
import app.amber.core.model.MessageNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * P1-c: forkConversation 契约（三模式 + 变体折叠 + 截断点安全 + assistantId 继承）。
 * 红先行：三模式、变体折叠、空 tool part 裁剪、assistantId 继承。
 */
class ConversationForkTest {

    private fun user(text: String) = UIMessage.user(prompt = text)

    private fun assistant(text: String) = UIMessage.assistant(prompt = text)

    private fun assistantWithTool(toolCallId: String, toolName: String, output: List<UIMessagePart> = emptyList()) =
        UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool(toolCallId = toolCallId, toolName = toolName, input = "{}", output = output)),
        )

    private fun node(message: UIMessage, extraVariants: List<UIMessage> = emptyList(), selectIndex: Int = 0) =
        MessageNode(messages = listOf(message) + extraVariants, selectIndex = selectIndex)

    private fun sourceConversation(nodes: List<MessageNode>) = Conversation(
        id = Uuid.random(),
        assistantId = DEFAULT_ASSISTANT_ID,
        title = "源会话",
        messageNodes = nodes,
        isPinned = true,
    )

    private fun fork(
        source: Conversation,
        forkTurns: String,
        newId: Uuid = Uuid.random(),
        newTitle: String = "子线程",
    ): Conversation = forkConversation(source, newId, newTitle, forkTurns)

    private fun texts(conversation: Conversation): List<String> = conversation.messageNodes
        .map { node ->
            node.messages.first().parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("") { it.text }
        }

    // MARK: - 三模式

    @Test
    fun forkNoneProducesEmptyMessageNodes() {
        val source = sourceConversation(listOf(node(user("你好")), node(assistant("你好！"))))
        val forked = fork(source, "none")

        assertTrue(forked.messageNodes.isEmpty(), "none 模式必须空 messageNodes")
    }

    @Test
    fun forkAllCopiesEveryMessage() {
        val source = sourceConversation(
            listOf(node(user("q1")), node(assistant("a1")), node(user("q2")), node(assistant("a2"))),
        )
        val forked = fork(source, "all")

        assertEquals(listOf("q1", "a1", "q2", "a2"), texts(forked))
        assertEquals("子线程", forked.title)
    }

    @Test
    fun forkNumberKeepsLastNUserTurns() {
        val source = sourceConversation(
            listOf(
                node(user("q1")), node(assistant("a1")),
                node(user("q2")), node(assistant("a2-1")), node(assistant("a2-2")),
                node(user("q3")), node(assistant("a3")),
            ),
        )
        val forked = fork(source, "2")

        assertEquals(
            listOf("q2", "a2-1", "a2-2", "q3", "a3"),
            texts(forked),
            "最近 2 个用户轮次：q2 与其全部 assistant 序列 + q3",
        )
        assertEquals(
            listOf("q1", "a1", "q2", "a2-1", "a2-2", "q3", "a3"),
            texts(fork(source, "9")),
            "请求轮次超过现有轮次时保留全部消息",
        )
    }

    @Test
    fun forkNumberRejectsInvalidValues() {
        val source = sourceConversation(listOf(node(user("q1"))))
        assertFailsWith<IllegalArgumentException> { fork(source, "abc") }
        assertFailsWith<IllegalArgumentException> { fork(source, "0") }
    }

    // MARK: - 变体折叠

    @Test
    fun forkFoldsVariantsToSelectedMessageOnly() {
        val selected = user("选中分支")
        val staleVariant = user("未选中候选")
        val source = sourceConversation(
            listOf(node(selected, extraVariants = listOf(staleVariant), selectIndex = 0)),
        )
        val forked = fork(source, "all")

        assertEquals(1, forked.messageNodes.size)
        val foldedNode = forked.messageNodes.single()
        assertEquals(
            listOf("选中分支"),
            foldedNode.messages.map { it.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text } },
            "变体只保留 selectIndex 当前项",
        )
    }

    // MARK: - 截断点安全（空 tool part 裁剪）

    @Test
    fun forkTrimsConsecutiveUnsafeAssistantMessagesForRequestedTurns() {
        val unfinished1 = assistantWithTool("tc-1", "search_web")
        val unfinished2 = assistantWithTool("tc-2", "scrape_web")
        val source = sourceConversation(
            listOf(
                node(user("q1")), node(assistant("a1")),
                node(user("q2")), node(assistant("a2")), node(unfinished1), node(unfinished2),
            ),
        )

        assertEquals(
            listOf("q1", "a1", "q2", "a2"),
            texts(fork(source, "2")),
            "连续多条未完成工具调用必须从末尾全部裁掉",
        )
        assertEquals(
            listOf("q2", "a2"),
            texts(fork(source, "1")),
            "保留最近轮次时，截断边界上的未完成工具调用也必须裁掉",
        )
    }

    @Test
    fun forkKeepsCompletedToolCalls() {
        val completedTool = assistantWithTool("tc-1", "search_web", output = listOf(UIMessagePart.Text("结果", metadata = null)))
        val source = sourceConversation(
            listOf(node(user("q1")), node(assistant("a1")), node(user("q2")), node(completedTool)),
        )
        val forked = fork(source, "2")

        assertEquals(4, forked.messageNodes.size, "已执行的 tool part 不是截断点风险，最近 2 轮整轮保留")
        val toolNode = forked.messageNodes.last()
        assertTrue(toolNode.messages.single().parts.single() is UIMessagePart.Tool)
    }

    // MARK: - 新会话字段

    @Test
    fun forkInheritsAssistantIdAndResetsLifecycleFields() {
        val source = sourceConversation(listOf(node(user("q1")), node(assistant("a1"))))
        val newId = Uuid.random()
        val forked = forkConversation(
            source = source,
            newId = newId,
            newTitle = "子线程标题",
            forkTurns = "all",
            assistantId = null,
        )

        assertEquals(newId, forked.id)
        assertEquals("子线程标题", forked.title)
        assertEquals(DEFAULT_ASSISTANT_ID, forked.assistantId, "assistantId 必须继承 source")
        assertFalse(forked.isPinned, "fork 出来的会话不置顶")
        assertEquals(forked.createAt, forked.updateAt, "createAt/updateAt 同一时刻")
        assertNotEquals(source.id, forked.id)
        assertFalse(forked.newConversation)
    }

    // MARK: - M4: 显式 role assistantId（spawn 的 role_assistant_id）

    @Test
    fun forkAppliesExplicitAssistantIdWhenProvided() {
        val source = sourceConversation(listOf(node(user("q1")), node(assistant("a1"))))
        val roleId = Uuid.random()
        val forked = forkConversation(source, Uuid.random(), "子线程", "all", assistantId = roleId)

        assertEquals(roleId, forked.assistantId, "显式 role assistantId 必须覆盖 source 继承值")
        assertEquals(source.assistantId, DEFAULT_ASSISTANT_ID, "fixture 前提：source 为默认助手")
        assertNotEquals(roleId, source.assistantId, "role 与 source 助手不同才证明覆盖生效")
    }

}
