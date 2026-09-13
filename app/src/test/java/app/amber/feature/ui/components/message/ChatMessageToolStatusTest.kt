package app.amber.feature.ui.components.message

import app.amber.ai.ui.UIMessagePart
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageToolStatusTest {
    @Test
    fun `unknown WebMount receipt remains pending verification`() {
        val output = buildJsonObject {
            put("ok", true)
            put("status", "unknown")
            put("goal_verified", false)
            put("may_have_applied", true)
        }

        assertEquals(
            AgentToolStatus.UNKNOWN,
            toolStatusFromMessagePart(
                tool = tool(output),
                loading = false,
                content = output,
            ),
        )
    }

    @Test
    fun `verified receipt remains succeeded`() {
        val output = buildJsonObject {
            put("ok", true)
            put("status", "verified")
            put("goal_verified", true)
        }

        assertEquals(
            AgentToolStatus.SUCCEEDED,
            toolStatusFromMessagePart(
                tool = tool(output),
                loading = false,
                content = output,
            ),
        )
    }

    @Test
    fun `failed receipt remains failed`() {
        val output = buildJsonObject {
            put("ok", false)
            put("status", "failed")
            put("error", "stale_target")
        }

        assertEquals(
            AgentToolStatus.FAILED,
            toolStatusFromMessagePart(
                tool = tool(output),
                loading = false,
                content = output,
            ),
        )
    }

    private fun tool(output: kotlinx.serialization.json.JsonObject): UIMessagePart.Tool =
        UIMessagePart.Tool(
            toolCallId = "wm-call",
            toolName = "wm_click",
            input = "{}",
            output = listOf(UIMessagePart.Text(output.toString())),
        )
}
