package shared

import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeminiWireToolMapperTest {
    private val json = Json

    @Test
    fun functionDeclarationsCarryNameDescriptionAndObjectSchema() {
        val tool = Tool(
            name = "search_web",
            description = "Search the web",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("query", buildJsonObject { put("type", "string") })
                    },
                    required = listOf("query"),
                )
            },
            execute = { emptyList() },
        )

        val raw = GeminiWireToolMapper.functionDeclarationsJson(listOf(tool))
        val declarations = json.parseToJsonElement(raw).jsonArray
        assertEquals(1, declarations.size)
        val declaration = declarations.first().jsonObject
        assertEquals("search_web", declaration["name"]?.jsonPrimitive?.content)
        assertEquals("Search the web", declaration["description"]?.jsonPrimitive?.content)
        val parameters = declaration["parameters"]!!.jsonObject
        assertEquals("object", parameters["type"]?.jsonPrimitive?.content)
        assertTrue(parameters["properties"]!!.jsonObject.containsKey("query"))
        assertEquals(listOf("query"), parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun functionDeclarationsOmitParametersWhenSchemaIsNull() {
        val tool = Tool(
            name = "tools_list",
            description = "List tools",
            parameters = { null },
            execute = { emptyList() },
        )

        val raw = GeminiWireToolMapper.functionDeclarationsJson(listOf(tool))
        val declaration = json.parseToJsonElement(raw).jsonArray.first().jsonObject
        assertFalse(declaration.containsKey("parameters"))
    }

    @Test
    fun emptyToolListYieldsEmptyArray() {
        assertEquals("[]", GeminiWireToolMapper.functionDeclarationsJson(emptyList()))
    }

    @Test
    fun functionDeclarationsStripGeminiUnsupportedSchemaKeywordsRecursively() {
        // Mirrors the Android wire path: MCP-sourced schemas pass keywords such as
        // const/format/enum through verbatim, and the Gemini REST schema rejects
        // them (400 INVALID_ARGUMENT), so the mapper must strip them recursively.
        val tool = Tool(
            name = "mcp__demo__tool",
            description = "Demo tool",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put(
                            "name",
                            buildJsonObject {
                                put("type", "string")
                                put("format", "uuid")
                                put("const", "fixed")
                            },
                        )
                        put(
                            "choice",
                            buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray {
                                    add(JsonPrimitive("a"))
                                    add(JsonPrimitive("b"))
                                })
                            },
                        )
                        put(
                            "range",
                            buildJsonObject {
                                put("type", "number")
                                put("exclusiveMinimum", 0)
                                put("exclusiveMaximum", 10)
                                put("additionalProperties", false)
                            },
                        )
                        put("tags", buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject {
                                put("type", "string")
                                put("format", "date-time")
                            })
                        })
                    },
                    required = listOf("name"),
                )
            },
            execute = { emptyList() },
        )

        val raw = GeminiWireToolMapper.functionDeclarationsJson(listOf(tool))
        val parameters = json.parseToJsonElement(raw).jsonArray.first().jsonObject["parameters"]!!.jsonObject

        // Supported keys survive at the top level and nested levels.
        assertEquals("object", parameters["type"]?.jsonPrimitive?.content)
        assertEquals(listOf("name"), parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        val props = parameters["properties"]!!.jsonObject
        assertEquals("string", props["name"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("array", props["tags"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(
            "string",
            props["tags"]!!.jsonObject["items"]!!.jsonObject["type"]?.jsonPrimitive?.content,
        )

        // Unsupported keywords are gone everywhere, including nested levels.
        val unsupported = setOf(
            "const", "exclusiveMaximum", "exclusiveMinimum", "format", "additionalProperties", "enum",
        )
        fun assertNoUnsupportedKeys(element: JsonElement, path: String) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    assertFalse(
                        key in unsupported,
                        "unsupported key \"$key\" at $path",
                    )
                    assertNoUnsupportedKeys(value, "$path.$key")
                }
                is JsonArray -> element.forEachIndexed { index, value ->
                    assertNoUnsupportedKeys(value, "$path[$index]")
                }
                else -> Unit
            }
        }
        assertNoUnsupportedKeys(parameters, "parameters")
    }
}
