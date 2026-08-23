package app.amber.core.model

import app.amber.ai.ui.UIMessage
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageNodeTest {
    @Test
    fun invalidSelectIndexFailsFast() {
        val node = MessageNode(
            messages = listOf(UIMessage.user("hello")),
            selectIndex = 2,
        )

        val error = runCatching { node.currentMessage }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("selectIndex=2"))
    }
}
