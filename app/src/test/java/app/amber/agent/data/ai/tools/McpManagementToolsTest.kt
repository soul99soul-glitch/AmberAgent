package app.amber.core.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class McpManagementToolsTest {
    @Test
    fun parsesObjectArguments() {
        val input = buildJsonObject {
            put("arguments", buildJsonObject {
                put("query", "Q代")
            })
        }

        val args = input.mcpArgumentsObject()

        assertEquals("Q代", args["query"]!!.jsonPrimitive.content)
    }

    @Test
    fun parsesStringArgumentsForCompatibility() {
        val input = Json.parseToJsonElement(
            """{"arguments":"{\"limit\":3,\"query\":\"飞书\"}"}"""
        )

        val args = input.mcpArgumentsObject()

        assertEquals("3", args["limit"]!!.jsonPrimitive.content)
        assertEquals("飞书", args["query"]!!.jsonPrimitive.content)
    }
}
