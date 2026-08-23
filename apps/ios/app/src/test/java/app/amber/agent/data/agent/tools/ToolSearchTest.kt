package app.amber.feature.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.MainAgentToolProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSearchTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun searchReturnsTopMatchesAndExpandedTools() {
        val registry = ToolRegistry.from(
            listOf(
                tool("calendar_list", "List phone calendar events."),
                tool("file_read", "Read workspace file."),
                tool("mcp__feishu_docs_fetch", "Fetch Feishu cloud document content."),
            )
        )

        val payload = ToolSearchIndex(registry).searchPayload("feishu document", null, 5)

        val expanded = payload["expanded_tools"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull }
        assertEquals(listOf("mcp__feishu_docs_fetch"), expanded)
        assertTrue(payload["tools"]!!.jsonArray.single().jsonObject["schema"]!!.jsonPrimitive.contentOrNull!!.contains("query"))
    }

    @Test
    fun searchReturnsCategoryCandidatesOnMiss() {
        val registry = ToolRegistry.from(listOf(tool("file_read", "Read workspace file.")))

        val payload = ToolSearchIndex(registry).searchPayload("nonexistent capability", null, 5)

        assertEquals(0, payload["matches_count"]!!.jsonPrimitive.contentOrNull!!.toInt())
        assertTrue(payload["category_candidates"]!!.jsonArray.isNotEmpty())
        assertTrue(payload["debug_hint"]!!.jsonPrimitive.contentOrNull!!.contains("tools_list"))
        assertTrue(payload["debug_hint"]!!.jsonPrimitive.contentOrNull!!.contains("tool_search"))
    }

    @Test
    fun searchMatchesChineseAliasesForCommonCapabilities() {
        val registry = ToolRegistry.from(
            listOf(
                tool("screen_screenshot", "Capture one screen frame for VLM reasoning."),
                tool("screen_click", "Tap screen coordinates."),
                tool("feishu_docs_snapshot", "Read current Feishu document page structure."),
                tool("subagent_start", "Start a subagent."),
                tool("terminal_execute", "Execute a shell command."),
            )
        )

        fun expanded(query: String): List<String> =
            ToolSearchIndex(registry).searchPayload(query, null, 1)["expanded_tools"]!!.jsonArray
                .mapNotNull { it.jsonPrimitive.contentOrNull }

        assertEquals(listOf("screen_screenshot"), expanded("截图"))
        assertEquals(listOf("screen_click"), expanded("点击屏幕"))
        assertEquals(listOf("feishu_docs_snapshot"), expanded("飞书云文档"))
        assertEquals(listOf("subagent_start"), expanded("子代理"))
        assertEquals(listOf("terminal_execute"), expanded("终端命令"))
    }

    @Test
    fun subagentStartSchemaDoesNotExposeDisplayTitle() {
        val registry = ToolRegistry.from(
            listOf(
                tool("subagent_start", "Start a subagent."),
                tool("file_read", "Read workspace file."),
            )
        )

        val schemas = registry.tools().associate { tool ->
            tool.name to (tool.parameters() as InputSchema.Obj).properties
        }

        assertFalse(schemas["subagent_start"]!!.containsKey("display_title"))
        assertTrue(schemas["file_read"]!!.containsKey("display_title"))
    }

    @Test
    fun searchResultPreservesPermissionMetadata() {
        val registry = ToolRegistry.from(
            listOf(
                tool("memory_tool", "Create, edit, delete, or search user memories."),
            )
        )

        val payload = ToolSearchIndex(registry).searchPayload("memory delete", null, 1)
        val tool = payload["tools"]!!.jsonArray.single().jsonObject

        assertEquals("memory_tool", tool["name"]!!.jsonPrimitive.contentOrNull)
        assertEquals(true, tool["mutates"]!!.jsonPrimitive.contentOrNull!!.toBoolean())
        assertEquals(true, tool["needs_approval"]!!.jsonPrimitive.contentOrNull!!.toBoolean())
        assertEquals("high", tool["risk"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun searchIndexReflectsCurrentRegistryOnly() {
        val oldRegistry = ToolRegistry.from(listOf(tool("old_tool", "Old capability.")))
        val newRegistry = ToolRegistry.from(listOf(tool("new_tool", "New capability.")))

        val oldExpanded = ToolSearchIndex(oldRegistry)
            .searchPayload("new_tool", null, 5)["expanded_tools"]!!.jsonArray
        val newExpanded = ToolSearchIndex(newRegistry)
            .searchPayload("new_tool", null, 5)["expanded_tools"]!!.jsonArray

        assertTrue(oldExpanded.isEmpty())
        assertEquals(listOf("new_tool"), newExpanded.map { it.jsonPrimitive.contentOrNull })
    }

    @Test
    fun searchTraceIncludesCurrentProfile() {
        val registry = ToolRegistry.from(listOf(tool("search_web", "Search web sources.")))

        val payload = ToolSearchIndex(registry, MainAgentToolProfile.WEB_READ)
            .searchPayload("search", null, 1)
        val trace = payload["trace"]!!.jsonObject

        assertEquals("web_read", trace["profile"]!!.jsonPrimitive.contentOrNull)
        assertEquals("true", trace["profile_filtered"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun searchCannotReturnToolsFilteredOutByProfileRegistry() {
        val filtered = ToolProfileFilter.filter(
            listOf(
                tool("search_web", "Search web sources."),
                tool("terminal_execute", "Execute shell commands."),
            ),
            MainAgentToolProfile.WEB_READ,
        )
        val registry = ToolRegistry.from(filtered.tools)

        val payload = ToolSearchIndex(registry, MainAgentToolProfile.WEB_READ)
            .searchPayload("terminal", null, 5)

        assertTrue(payload["expanded_tools"]!!.jsonArray.isEmpty())
    }

    @Test
    fun searchCannotReturnLateOrchestrationToolsAfterFinalProfileFilter() {
        val filtered = ToolProfileFilter.filter(
            listOf(
                tool("search_web", "Search web sources."),
                tool("subagent_start", "Start a delegated subagent."),
                tool("model_council_start", "Start a model council run."),
            ),
            MainAgentToolProfile.WEB_READ,
        )
        val registry = ToolRegistry.from(filtered.tools)

        val subagentPayload = ToolSearchIndex(registry, MainAgentToolProfile.WEB_READ)
            .searchPayload("subagent", null, 5)
        val councilPayload = ToolSearchIndex(registry, MainAgentToolProfile.WEB_READ)
            .searchPayload("model council", null, 5)

        assertTrue(subagentPayload["expanded_tools"]!!.jsonArray.isEmpty())
        assertTrue(councilPayload["expanded_tools"]!!.jsonArray.isEmpty())
    }

    @Test
    fun exposureBypassesLazyModeForSmallCatalogs() {
        val tools = listOf(tool("file_read"), tool("custom_tool"))

        val exposure = ToolExposureState.from(tools)

        assertFalse(exposure.enabled)
        assertEquals(setOf("file_read", "custom_tool"), exposure.toolsForStep().map { it.name }.toSet())
    }

    @Test
    fun exposureStartsWithResidentToolsThenExpandsSearchHits() = runBlocking {
        val hiddenTools = (0 until 45).map { tool("hidden_tool_$it", "Hidden capability $it") }
        val registry = ToolRegistry.from(hiddenTools + tool("file_read", "Read workspace file."))
        val searchTool = createToolSearchTool(registry)
        val exposure = ToolExposureState.from(hiddenTools + tool("file_read", "Read workspace file.") + searchTool)

        assertTrue(exposure.enabled)
        assertTrue("file_read" in exposure.toolsForStep().map { it.name })
        assertFalse("hidden_tool_7" in exposure.toolsForStep().map { it.name })

        val output = searchTool.execute(json.parseToJsonElement("""{"query":"hidden_tool_7","limit":1}"""))
        exposure.observeExecutedTools(
            listOf(
                UIMessagePart.Tool(
                    toolCallId = "search-1",
                    toolName = TOOL_SEARCH_TOOL_NAME,
                    input = "{}",
                    output = output,
                )
            )
        )

        assertTrue("hidden_tool_7" in exposure.toolsForStep().map { it.name })
    }

    @Test
    fun modelCouncilCoreToolsStayResidentInLazyMode() {
        val hiddenTools = (0 until 45).map { tool("hidden_tool_$it", "Hidden capability $it") }
        val councilTools = listOf(
            tool("model_council_status", "Show Model Council status."),
            tool("model_council_start", "Start a Model Council run."),
            tool("model_council_read", "Read a Model Council run."),
            tool("model_council_wait", "Wait for a Model Council run."),
            tool("model_council_cancel", "Cancel a Model Council run."),
            tool("model_council_make_report", "Write a Model Council report."),
        )
        val exposure = ToolExposureState.from(hiddenTools + councilTools + tool(TOOL_SEARCH_TOOL_NAME))
        val visible = exposure.toolsForStep().map { it.name }.toSet()

        assertTrue(exposure.enabled)
        assertTrue("model_council_status" in visible)
        assertTrue("model_council_start" in visible)
        assertTrue("model_council_read" in visible)
        assertTrue("model_council_wait" in visible)
        assertTrue("model_council_cancel" in visible)
        assertFalse("model_council_make_report" in visible)
    }

    @Test
    fun generateImageStaysResidentInLazyMode() {
        val hiddenTools = (0 until 45).map { tool("hidden_tool_$it", "Hidden capability $it") }
        val exposure = ToolExposureState.from(
            hiddenTools + tool("generate_image", "Generate raster images.") + tool(TOOL_SEARCH_TOOL_NAME)
        )

        assertTrue(exposure.enabled)
        assertTrue("generate_image" in exposure.toolsForStep().map { it.name })
    }

    @Test
    fun toolsListOutputDoesNotExposeHiddenTools() {
        val hiddenTools = (0 until 45).map { tool("hidden_tool_$it", "Hidden capability $it") }
        val exposure = ToolExposureState.from(
            hiddenTools + tool("file_read", "Read workspace file.") + tool(TOOL_SEARCH_TOOL_NAME) + tool("tools_list")
        )

        assertTrue(exposure.enabled)
        assertFalse("hidden_tool_7" in exposure.toolsForStep().map { it.name })

        exposure.observeExecutedTools(
            listOf(
                UIMessagePart.Tool(
                    toolCallId = "list-1",
                    toolName = "tools_list",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("""{"tools":[{"name":"hidden_tool_7"}]}""")),
                )
            )
        )

        assertFalse("hidden_tool_7" in exposure.toolsForStep().map { it.name })
    }

    @Test
    fun exposureCanRestorePendingHiddenToolForApprovalResume() {
        val hiddenTools = (0 until 45).map { tool("hidden_tool_$it", "Hidden capability $it") }
        val registry = ToolRegistry.from(hiddenTools + tool("file_read", "Read workspace file."))
        val searchTool = createToolSearchTool(registry)
        val exposure = ToolExposureState.from(hiddenTools + tool("file_read", "Read workspace file.") + searchTool)

        assertTrue(exposure.enabled)
        assertFalse("hidden_tool_7" in exposure.toolsForStep().map { it.name })

        exposure.exposeToolNames(listOf("hidden_tool_7"))

        assertTrue("hidden_tool_7" in exposure.toolsForStep().map { it.name })
    }

    private fun tool(
        name: String,
        description: String = "test tool",
    ) = Tool(
        name = name,
        description = description,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "query")
                    })
                }
            )
        },
        execute = { listOf(UIMessagePart.Text("ok")) },
    )
}
