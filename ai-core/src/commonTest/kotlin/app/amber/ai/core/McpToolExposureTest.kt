package app.amber.ai.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P0-b: flattened `mcp__{server}__{tool}` declaration contract. These are
 * pure functions shared by iOS (declaration generation + execution routing)
 * and Android (follow-up); the tests pin naming/sanitize/truncation
 * determinism, schema normalization branches, collision handling, and the
 * approval flags copied from `mcp_call`.
 */
class McpToolExposureTest {

    // MARK: - Naming / sanitize / truncation

    @Test
    fun expandedNameJoinsSanitizedServerAndTool() {
        assertEquals("mcp__docs__search", expandedMcpToolName("docs", "search"))
        // Sanitize replaces non [a-zA-Z0-9_-] chars; case is preserved.
        assertEquals("mcp__My_Server___read_file", expandedMcpToolName("My Server!", "read file"))
        assertEquals("mcp__srv-one__tool-2", expandedMcpToolName("srv-one", "tool-2"))
        assertEquals("mcp__a_b_c__x-y_z", expandedMcpToolName("a.b.c", "x-y_z"))
    }

    @Test
    fun expandedNameIsDeterministic() {
        val server = "srv with spaces / slashes"
        val tool = "tool:name&symbols"
        assertEquals(expandedMcpToolName(server, tool), expandedMcpToolName(server, tool))
    }

    @Test
    fun expandedNameTruncatesWholeNameAt64Chars() {
        val server = "s".repeat(40)
        val tool = "t".repeat(40)
        val name = expandedMcpToolName(server, tool)
        assertEquals(64, name.length, "overlong names must truncate deterministically at 64 chars")
        assertTrue(name.startsWith("mcp__"))
        assertEquals(name, expandedMcpToolName(server, tool))
        // Short names stay untruncated.
        assertEquals("mcp__docs__search", expandedMcpToolName("docs", "search"))
    }

    @Test
    fun isExpandedMcpToolNameMatchesPrefixOnly() {
        assertTrue(isExpandedMcpToolName("mcp__docs__search"))
        assertTrue(isExpandedMcpToolName("mcp__"))
        assertFalse(isExpandedMcpToolName("mcp_call"), "mcp_call is the passthrough entry, not an expanded name")
        assertFalse(isExpandedMcpToolName("mcp_list"))
        assertFalse(isExpandedMcpToolName("mcp_describe_tool"))
        assertFalse(isExpandedMcpToolName("search_web"))
        assertFalse(isExpandedMcpToolName(""))
        assertFalse(isExpandedMcpToolName("mcp_"))
    }

    // MARK: - Schema normalization

    @Test
    fun objectRootPassesPropertiesThroughAndExtractsRequired() {
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("q", buildJsonObject { put("type", "string") })
                put("ref", buildJsonObject { put("\$ref", "#/definitions/thing") })
                put("nested", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject { put("inner", buildJsonObject { put("type", "string") }) })
                })
            })
            put("required", buildJsonArray { add("q") })
        }

        val normalized = normalizeMcpInputSchema(schema)

        assertIs<InputSchema.Obj>(normalized)
        assertEquals(listOf("q"), normalized.required)
        val properties = normalized.properties
        assertEquals("string", properties["q"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        // $ref / nesting pass through untouched — no recursive resolution.
        assertEquals(
            "#/definitions/thing",
            properties["ref"]?.jsonObject?.get("\$ref")?.jsonPrimitive?.contentOrNull,
        )
        assertEquals(
            "string",
            properties["nested"]?.jsonObject?.get("properties")
                ?.jsonObject?.get("inner")?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull,
        )
    }

    @Test
    fun objectLikeRootWithoutTypeKeywordIsAccepted() {
        val schema = buildJsonObject {
            put("properties", buildJsonObject { put("x", buildJsonObject { put("type", "string") }) })
            put("required", buildJsonArray { add("x") })
        }
        val normalized = normalizeMcpInputSchema(schema)
        assertIs<InputSchema.Obj>(normalized)
        assertEquals("string", normalized.properties["x"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals(listOf("x"), normalized.required)
    }

    @Test
    fun arrayTypeKeywordDoesNotCrashAndFallsBackToObjectLikeProbe() {
        // JSON Schema allows "type" as an array (e.g. Python MCP SDK emits
        // ["object", "null"]); a primitive-only read of `type` used to throw.
        val objectArray = buildJsonObject {
            put("type", buildJsonArray { add("object"); add("null") })
            put("properties", buildJsonObject { put("x", buildJsonObject { put("type", "string") }) })
        }
        val objectNormalized = normalizeMcpInputSchema(objectArray)
        assertIs<InputSchema.Obj>(objectNormalized)
        assertEquals(
            "string",
            objectNormalized.properties["x"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull,
        )

        val stringArray = buildJsonObject { put("type", buildJsonArray { add("string") }) }
        val stringNormalized = normalizeMcpInputSchema(stringArray)
        assertIs<InputSchema.Obj>(stringNormalized)
        assertEquals(listOf("input"), stringNormalized.required)
    }

    @Test
    fun nonObjectRootsAreWrappedUnderInputProperty() {
        val arrayRoot = buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "string") })
        }
        val arrayNormalized = normalizeMcpInputSchema(arrayRoot)
        assertIs<InputSchema.Obj>(arrayNormalized)
        assertEquals(listOf("input"), arrayNormalized.required)
        assertEquals(
            "array",
            arrayNormalized.properties["input"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull,
        )

        val stringRoot = buildJsonObject { put("type", "string") }
        val stringNormalized = normalizeMcpInputSchema(stringRoot)
        assertIs<InputSchema.Obj>(stringNormalized)
        assertEquals(listOf("input"), stringNormalized.required)
        assertEquals(
            "string",
            stringNormalized.properties["input"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull,
        )
    }

    @Test
    fun nullSchemaFallsBackToEmptyObject() {
        val normalized = normalizeMcpInputSchema(null)
        assertIs<InputSchema.Obj>(normalized)
        assertTrue(normalized.properties.isEmpty())
        assertNull(normalized.required)
    }

    @Test
    fun objectRootWithoutPropertiesUsesEmptyProperties() {
        val normalized = normalizeMcpInputSchema(buildJsonObject { put("type", "object") })
        assertIs<InputSchema.Obj>(normalized)
        assertTrue(normalized.properties.isEmpty())
        assertNull(normalized.required)
    }

    @Test
    fun requiredThatIsNotStringArrayIsIgnored() {
        val schema = buildJsonObject {
            put("type", "object")
            put("required", buildJsonArray { add(JsonPrimitive(42)) })
        }
        val normalized = normalizeMcpInputSchema(schema)
        assertIs<InputSchema.Obj>(normalized)
        assertNull(normalized.required, "non-string required entries must not be trusted")
    }

    // MARK: - Declarations

    @Test
    fun declarationsGenerateOneToolPerSpecWithFallbackDescriptionAndNormalizedSchema() {
        val tools = mcpExpandedToolDeclarations(
            serverName = "docs",
            discovered = listOf(
                McpDiscoveredToolSpec("search", "Search the docs", objectSchema()),
                McpDiscoveredToolSpec("empty-desc", "   "),
                McpDiscoveredToolSpec("no-desc"),
            ),
        )

        assertEquals(3, tools.size)
        assertEquals("mcp__docs__search", tools[0].name)
        assertEquals("Search the docs", tools[0].description)
        assertEquals("MCP tool empty-desc on docs", tools[1].description)
        assertEquals("MCP tool no-desc on docs", tools[2].description)
        val searchParams = tools[0].parameters()
        assertIs<InputSchema.Obj>(searchParams)
        assertEquals("string", searchParams.properties["q"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        assertTrue(tools[1].parameters() is InputSchema.Obj)
        assertTrue(tools[2].parameters() is InputSchema.Obj)
    }

    @Test
    fun declarationsDeduplicateSanitizedNameCollisionsWithinServer() {
        val tools = mcpExpandedToolDeclarations(
            serverName = "docs",
            discovered = listOf(
                McpDiscoveredToolSpec("t.o", "first"),
                McpDiscoveredToolSpec("t_o", "second"),
                McpDiscoveredToolSpec("t.o", "third"),
            ),
        )
        assertEquals(1, tools.size, "sanitized name collisions keep the first occurrence")
        assertEquals("first", tools[0].description)
    }

    @Test
    fun crossServerSanitizedCollisionsShareNameAndCallerMergeKeepsFirst() {
        // "srv.1" and "srv_1" sanitize to the same part; the flattened names
        // collide across servers, so the caller-side merge keeps the first.
        val fromSrvOne = mcpExpandedToolDeclarations("srv.1", listOf(McpDiscoveredToolSpec("x", "from srv.1")))
        val fromSrvTwo = mcpExpandedToolDeclarations("srv_1", listOf(McpDiscoveredToolSpec("x", "from srv_1")))
        assertEquals(fromSrvOne.single().name, fromSrvTwo.single().name)
        // Caller-side merge (per-server generation then concatenation) keeps the first occurrence.
        val merged = (fromSrvOne + fromSrvTwo).distinctBy { it.name }
        assertEquals(1, merged.size)
        assertEquals("from srv.1", merged.single().description)
    }

    @Test
    fun expandedDeclarationsCopyMcpCallApprovalFlags() {
        val mcpCall = createMcpCallToolDeclaration()
        val tool = mcpExpandedToolDeclarations("docs", listOf(McpDiscoveredToolSpec("search"))).single()

        assertEquals(mcpCall.needsApproval, tool.needsApproval)
        assertEquals(mcpCall.allowsAutoApproval, tool.allowsAutoApproval)
        assertEquals(mcpCall.mandatoryApproval, tool.mandatoryApproval)
    }

    @Test
    fun emptyDiscoveredListYieldsNoDeclarations() {
        assertTrue(mcpExpandedToolDeclarations("docs", emptyList()).isEmpty())
    }

    // MARK: - Swift-facing string-schema constructor (raw persisted schema text)

    @Test
    fun stringSchemaConstructorParsesRawPersistedText() {
        val spec = McpDiscoveredToolSpec(
            "search",
            "desc",
            """{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}""",
        )
        assertNotNull(spec.inputSchema)
        val params = mcpExpandedToolDeclarations("docs", listOf(spec)).single().parameters()
        assertIs<InputSchema.Obj>(params)
        assertEquals("string", params.properties["q"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals(listOf("q"), params.required)
    }

    @Test
    fun stringSchemaConstructorIgnoresInvalidJson() {
        val spec = McpDiscoveredToolSpec("broken", null, """{"type":"object"""")
        assertNull(spec.inputSchema, "unparseable persisted schema text must degrade to null, not throw")
        val params = mcpExpandedToolDeclarations("docs", listOf(spec)).single().parameters()
        assertIs<InputSchema.Obj>(params)
        assertTrue(params.properties.isEmpty())
    }

    private fun objectSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("q", buildJsonObject { put("type", "string") }) })
        put("required", buildJsonArray { add("q") })
    }
}
