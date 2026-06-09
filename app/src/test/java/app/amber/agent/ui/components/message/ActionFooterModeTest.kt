package app.amber.feature.ui.components.message

import app.amber.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionFooterModeTest {
    @Test
    fun user_messages_never_show_footer() {
        assertEquals(
            ActionFooterMode.Hidden,
            resolveActionFooterMode(
                role = MessageRole.USER,
                lastMessage = true,
                loading = false,
                hasContent = true,
            ),
        )
    }

    @Test
    fun streaming_last_message_reserves_footer_space() {
        assertEquals(
            ActionFooterMode.Reserved,
            resolveActionFooterMode(
                role = MessageRole.ASSISTANT,
                lastMessage = true,
                loading = true,
                hasContent = true,
            ),
        )
    }

    @Test
    fun completed_last_message_shows_footer() {
        assertEquals(
            ActionFooterMode.Visible,
            resolveActionFooterMode(
                role = MessageRole.ASSISTANT,
                lastMessage = true,
                loading = false,
                hasContent = true,
            ),
        )
    }
}
