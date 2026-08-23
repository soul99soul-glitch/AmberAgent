package app.amber.feature.ui.components.message

import app.amber.ai.ui.UIMessage
import app.amber.core.model.MessageNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P8-02 验收项：user bubble 的 variant selector —— 单 variant 不显示（不占布局），
 * 多 variant 显示 x/y；assistant 节点不经过 user selector 入口。
 */
class ChatMessageVariantSelectorTest {

    @Test
    fun singleVariantUserMessageDoesNotShowSelector() {
        val node = MessageNode.of(UIMessage.user("hello"))
        assertFalse(node.showUserVariantSelector())
    }

    @Test
    fun multiVariantUserMessageShowsSelector() {
        val node = MessageNode(
            messages = listOf(UIMessage.user("hello"), UIMessage.user("hello edited")),
            selectIndex = 1,
        )
        assertTrue(node.showUserVariantSelector())
    }

    @Test
    fun assistantVariantsDoNotShowUserSelector() {
        val node = MessageNode(
            messages = listOf(UIMessage.assistant("a"), UIMessage.assistant("b")),
            selectIndex = 1,
        )
        assertFalse(node.showUserVariantSelector())
    }
}
