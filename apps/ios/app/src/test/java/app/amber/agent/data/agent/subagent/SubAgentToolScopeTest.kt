package app.amber.feature.subagent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.tools.TOOL_SEARCH_TOOL_NAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentToolScopeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun scopedToolSearchOnlySeesAllowedSubagentTools() = runBlocking {
        val tools = scopedSubAgentTools(
            listOf(
                tool("file_read", "Read workspace files."),
                tool("terminal_execute", "Run a shell command."),
            )
        )
        val search = tools.single { it.name == TOOL_SEARCH_TOOL_NAME }

        val payload = json.parseToJsonElement(
            (search.execute(json.parseToJsonElement("""{"query":"terminal","limit":5}""")).single() as UIMessagePart.Text).text
        )
        val expanded = payload.jsonObject["expanded_tools"]!!.jsonArray.map { it.jsonPrimitive.content }

        assertEquals(listOf("terminal_execute"), expanded)
        assertTrue(tools.any { it.name == "file_read" })
        assertFalse(tools.any { it.name == "file_write" })
    }

    @Test
    fun scopedToolSearchReplacesAnyParentSearchTool() {
        val parentSearch = tool(TOOL_SEARCH_TOOL_NAME, "Parent full-catalog search.")

        val tools = scopedSubAgentTools(listOf(tool("file_read"), parentSearch))

        assertEquals(1, tools.count { it.name == TOOL_SEARCH_TOOL_NAME })
    }

    @Test
    fun scopedToolsListReplacesParentCatalogTool() = runBlocking {
        val parentToolsList = Tool(
            name = "tools_list",
            description = "Parent full-catalog list.",
            execute = { listOf(UIMessagePart.Text("""{"tools":[{"name":"terminal_execute"}]}""")) },
        )
        val tools = scopedSubAgentTools(listOf(tool("file_read"), parentToolsList))
        val listTool = tools.single { it.name == "tools_list" }

        val payload = json.parseToJsonElement(
            (listTool.execute(json.parseToJsonElement("""{"query":"","include_schema":true}""")).single() as UIMessagePart.Text).text
        )
        val names = payload.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

        assertTrue(payload.jsonObject["scoped"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(listOf("file_read"), names)
        assertFalse(names.contains("terminal_execute"))
        assertEquals(1, tools.count { it.name == "tools_list" })
    }

    @Test
    fun scopedToolPolicyExplainCannotInspectParentOnlyTool() = runBlocking {
        val parentPolicy = tool("tool_policy_explain", "Parent full-catalog policy.")
        val tools = scopedSubAgentTools(listOf(tool("file_read"), parentPolicy))
        val policyTool = tools.single { it.name == "tool_policy_explain" }

        val payload = json.parseToJsonElement(
            (policyTool.execute(json.parseToJsonElement("""{"tool_name":"terminal_execute"}""")).single() as UIMessagePart.Text).text
        )

        assertEquals("not_found", payload.jsonObject["status"]!!.jsonPrimitive.content)
        assertTrue(payload.jsonObject["scoped"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(1, tools.count { it.name == "tool_policy_explain" })
    }

    private fun tool(name: String, description: String = "test tool") = Tool(
        name = name,
        description = description,
        execute = { listOf(UIMessagePart.Text("ok")) },
    )
}
