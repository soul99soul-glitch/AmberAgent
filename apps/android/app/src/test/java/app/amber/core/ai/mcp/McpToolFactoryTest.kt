package app.amber.core.ai.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-02 model-facing tool entries (parity plan §P2-02): namespaced expanded
 * names with original server/tool preserved in the schema, legacy alias
 * gateways (unique routes, multi-match rejects with a structured error), and
 * old-message replay through the gateway.
 */
class McpToolFactoryTest {

    private fun ref(
        server: String,
        tool: String,
        schema: InputSchema? = InputSchema.Obj(properties = JsonObject(emptyMap())),
    ) = McpToolRef(
        serverId = "srv-${server.hashCode()}",
        serverName = server,
        toolName = tool,
        description = "tool $tool",
        inputSchema = schema,
        needsApproval = true,
    )

    private fun execute(tool: Tool, input: String = "{}"): List<UIMessagePart> =
        runBlocking { tool.execute(Json.parseToJsonElement(input)) }

    // ---- namespaced entries ----

    @Test
    fun namespacedToolKeepsOriginalIdentityInSchema() {
        val refs = listOf(ref("server-a", "search"))
        val tools = createMcpTools(refs) { _, _ -> listOf(UIMessagePart.Text("ok")) }

        val tool = tools.first { it.name == "mcp__server-a__search" }
        val schema = tool.parameters() as InputSchema.Obj
        assertEquals("server-a", schema.originalServerName)
        assertEquals("search", schema.originalToolName)
        assertFalse(tool.allowsAutoApproval)
    }

    @Test
    fun twoServersSameToolCreateTwoDistinctNamespacedEntries() {
        val refs = listOf(ref("server-a", "search"), ref("server-b", "search"))
        val tools = createMcpTools(refs) { _, _ -> listOf(UIMessagePart.Text("ok")) }

        val names = tools.map { it.name }.filter { it.startsWith("mcp__server-") }.toSet()
        assertEquals(2, names.size)
        assertTrue(names.containsAll(listOf("mcp__server-a__search", "mcp__server-b__search")))
    }

    // ---- legacy gateways ----

    @Test
    fun legacyUniqueAliasRoutesToTheOnlyServer() {
        val refs = listOf(ref("server-a", "search_doc"))
        val calls = mutableListOf<McpToolRef>()
        val tools = createMcpTools(refs) { r, _ ->
            calls += r
            listOf(UIMessagePart.Text("ok"))
        }

        val gateway = tools.first { it.name == "mcp__search_doc" }
        val output = execute(gateway)

        assertEquals(1, calls.size)
        assertEquals(refs.first(), calls.first())
        assertTrue(output.filterIsInstance<UIMessagePart.Text>().any { it.text == "ok" })
    }

    @Test
    fun legacyMultiMatchGatewayRejectsWithStructuredError() {
        val refs = listOf(ref("server-a", "search_doc"), ref("server-b", "search_doc"))
        val calls = mutableListOf<McpToolRef>()
        val tools = createMcpTools(refs) { r, _ ->
            calls += r
            listOf(UIMessagePart.Text("must not run"))
        }

        val gateway = tools.first { it.name == "mcp__search_doc" }
        val text = execute(gateway).filterIsInstance<UIMessagePart.Text>().joinToString { it.text }

        // Reject: nothing executed, structured error asks the model to re-select.
        assertTrue(calls.isEmpty())
        assertTrue(text.contains("mcp_tool_name_ambiguous"))
        assertTrue(text.contains("server-a/search_doc"))
        assertTrue(text.contains("server-b/search_doc"))
        assertTrue(text.contains("recoverable"))
    }

    // ---- old-message replay ----

    @Test
    fun oldMessageToolCallReplaysThroughUniqueGateway() {
        // Old session message: tool call name "mcp__search_doc" (pre-namespace).
        val refs = listOf(ref("server-a", "search_doc"), ref("server-b", "other"))
        val resolution = McpToolNamespace.resolve("mcp__search_doc", refs)
        assertTrue(resolution is McpToolNameResolution.Unique)

        val tools = createMcpTools(refs) { r, _ -> listOf(UIMessagePart.Text("replayed")) }
        val gateway = tools.first { it.name == "mcp__search_doc" }
        val text = execute(gateway).filterIsInstance<UIMessagePart.Text>().joinToString { it.text }
        assertEquals("replayed", text)
    }

    @Test
    fun oldMessageReplayAcrossTwoServersIsRejectedNotMisrouted() {
        val refs = listOf(ref("server-a", "search_doc"), ref("server-b", "search_doc"))
        val resolution = McpToolNamespace.resolve("mcp__search_doc", refs)
        assertTrue(resolution is McpToolNameResolution.Ambiguous)

        val tools = createMcpTools(refs) { _, _ -> listOf(UIMessagePart.Text("must not run")) }
        val gateway = tools.first { it.name == "mcp__search_doc" }
        val text = execute(gateway).filterIsInstance<UIMessagePart.Text>().joinToString { it.text }
        assertTrue(text.contains("mcp_tool_name_ambiguous"))
    }

    // ---- gateway/namespaced name collision ----

    @Test
    fun gatewayCollidingWithNamespacedNameIsSkipped() {
        // Tool "a__b" on server-x would produce legacy gateway "mcp__a__b",
        // colliding with the namespaced name of server-a/b. The namespaced
        // entry wins and no duplicate registration happens.
        val refs = listOf(ref("a", "b"), ref("server-x", "a__b"))
        val calls = mutableListOf<McpToolRef>()
        val tools = createMcpTools(refs) { r, _ ->
            calls += r
            listOf(UIMessagePart.Text("ok"))
        }

        val names = tools.map { it.name }
        assertEquals("no duplicate tool names registered", names.size, names.toSet().size)
        // One entry owns "mcp__a__b" — the namespaced tool of server-a/b.
        val entry = tools.filter { it.name == "mcp__a__b" }
        assertEquals(1, entry.size)
        val text = execute(entry.first()).filterIsInstance<UIMessagePart.Text>().first().text
        assertEquals("ok", text)
        assertEquals(1, calls.size)
    }

    // ---- schema metadata on gateways ----

    @Test
    fun uniqueGatewaySchemaAlsoCarriesOriginalIdentity() {
        val refs = listOf(ref("server-a", "search_doc"))
        val tools = createMcpTools(refs) { _, _ -> listOf(UIMessagePart.Text("ok")) }

        val gateway = tools.first { it.name == "mcp__search_doc" }
        val schema = gateway.parameters() as InputSchema.Obj
        assertEquals("server-a", schema.originalServerName)
        assertEquals("search_doc", schema.originalToolName)
    }

    @Test
    fun ambiguousGatewayHasEmptySchemaAndNoApprovalRequirement() {
        val refs = listOf(ref("server-a", "search_doc"), ref("server-b", "search_doc"))
        val tools = createMcpTools(refs) { _, _ -> emptyList() }

        val gateway = tools.first { it.name == "mcp__search_doc" }
        assertFalse(gateway.needsApproval)
        val schema = gateway.parameters() as InputSchema.Obj
        assertTrue(schema.properties.isEmpty())
    }
}
