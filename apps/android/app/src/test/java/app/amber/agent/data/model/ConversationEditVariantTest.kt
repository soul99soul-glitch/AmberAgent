package app.amber.core.model

import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.service.blockedReason
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P8-01 / P8-02 验收项：
 * - 编辑 → 新 user variant 生成新分支（模型层：variant 追加 + 选中，生成绑定新 variant）
 * - 原分支保留（原 variant 不被覆盖）
 * - 生成中编辑冲突处理（editBlockedReason 策略）
 * - user variant 切换联动后续分支（withSelectedVariant 截断下游）
 */
class ConversationEditVariantTest {

    private fun conversationWith(userNode: MessageNode, vararg following: MessageNode) = Conversation(
        assistantId = Uuid.random(),
        messageNodes = listOf(userNode) + following,
    )

    @Test
    fun editAddsNewVariantSelectsItAndPreservesOriginal() {
        val originalUser = UIMessage.user("original question")
        val reply = UIMessage.assistant("original answer")
        val conversation = conversationWith(MessageNode.of(originalUser), MessageNode.of(reply))

        val edited = conversation.withEditedUserVariant(
            messageId = originalUser.id,
            newParts = listOf(UIMessagePart.Text("edited question")),
        )

        // 下游（旧回复）被截断 —— 不会形成旧提问+新回复的混合上下文
        assertEquals(1, edited.messageNodes.size)
        val node = edited.messageNodes.single()
        // 新 user variant 追加且被选中 → 后续生成绑定新 variant
        assertEquals(2, node.messages.size)
        assertEquals(1, node.selectIndex)
        // 原分支保留：原 variant 未被覆盖
        assertEquals("original question", (node.messages[0].parts.single() as UIMessagePart.Text).text)
        assertEquals("edited question", (node.messages[1].parts.single() as UIMessagePart.Text).text)
    }

    @Test
    fun editUnknownMessageIsNoOp() {
        val conversation = conversationWith(MessageNode.of(UIMessage.user("question")))
        val edited = conversation.withEditedUserVariant(
            messageId = Uuid.random(),
            newParts = listOf(UIMessagePart.Text("edited")),
        )
        assertSame(conversation, edited)
    }

    @Test
    fun editWhileGeneratingIsBlockedWithExplicitMessage() {
        // 生成中：明确提示且拒绝（不打断当前生成）
        val reason = blockedReason(isGenerating = true, message = "请先停止生成再编辑消息")
        assertNotNull(reason)
        assertTrue(reason!!.contains("停止生成"))
        // idle：允许编辑
        assertNull(blockedReason(isGenerating = false, message = "请先停止生成再编辑消息"))
    }

    @Test
    fun variantSwitchWhileGeneratingIsBlockedWithExplicitMessage() {
        // Minor-1: 生成中切换 user variant 被拒（selectMessageNode 权威守卫的决策）
        val reason = blockedReason(isGenerating = true, message = "请先停止生成再切换消息分支")
        assertNotNull(reason)
        assertTrue(reason!!.contains("停止生成"))
        // idle：正常切换
        assertNull(blockedReason(isGenerating = false, message = "请先停止生成再切换消息分支"))
    }

    @Test
    fun switchingUserVariantSyncsDownstreamBranch() {
        val originalUser = UIMessage.user("original question")
        val editedUser = UIMessage.user("edited question")
        val userNode = MessageNode(messages = listOf(originalUser, editedUser), selectIndex = 1)
        val reply = MessageNode.of(UIMessage.assistant("answer to edited question"))
        val conversation = conversationWith(userNode, reply)

        // 切回原 variant：后续可见分支同步切换（下游截断到该节点）
        val switched = conversation.withSelectedVariant(userNode.id, 0)

        assertEquals(1, switched.messageNodes.size)
        assertEquals(0, switched.messageNodes.single().selectIndex)
    }

    @Test
    fun switchingToSameVariantIsNoOp() {
        val userNode = MessageNode(
            messages = listOf(UIMessage.user("a"), UIMessage.user("b")),
            selectIndex = 0,
        )
        val conversation = conversationWith(userNode)
        assertSame(conversation, conversation.withSelectedVariant(userNode.id, 0))
    }

    @Test
    fun switchingInvalidVariantFailsFast() {
        val userNode = MessageNode(
            messages = listOf(UIMessage.user("a"), UIMessage.user("b")),
            selectIndex = 0,
        )
        val conversation = conversationWith(userNode)
        val error = runCatching { conversation.withSelectedVariant(userNode.id, 2) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        val missing = runCatching { conversation.withSelectedVariant(Uuid.random(), 0) }.exceptionOrNull()
        assertTrue(missing is NoSuchElementException)
    }
}
