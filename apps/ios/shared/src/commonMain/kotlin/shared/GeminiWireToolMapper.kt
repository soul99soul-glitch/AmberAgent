package shared

import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Gemini wire-format helpers for the native Swift Gemini provider
 * (`IOSGeminiProvider`).
 *
 * Swift cannot easily serialize the KMP [InputSchema] (kotlinx JsonObject
 * doesn't bridge to a JSON text representation), so the functionDeclarations
 * JSON is produced here and handed over as a JSON string that Swift feeds
 * through JSONSerialization before merging into the request body.
 */
object GeminiWireToolMapper {

    /**
     * Serializes [tools] into the Gemini `functionDeclarations` array JSON:
     * `[{"name":..., "description":..., "parameters": {"type":"object",
     * "properties":..., "required":[...]}}]`.
     *
     * Gemini's parameters schema expects a JSON-schema shape, which is exactly
     * [InputSchema.Obj] minus the outer @SerialName("object") wrapper — so the
     * object schema is rebuilt explicitly instead of reusing the serialized
     * enum form.
     *
     * Mirrors the verified Android wire path (GoogleProvider): Gemini's REST
     * schema rejects the JSON-schema keywords [GEMINI_UNSUPPORTED_SCHEMA_KEYS],
     * and MCP-sourced schemas pass them through verbatim, so they are stripped
     * recursively before the request goes out.
     */
    fun functionDeclarationsJson(tools: List<Tool>): String {
        val array = buildJsonArray {
            tools.forEach { tool ->
                add(buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    val schema = tool.parameters()
                    if (schema is InputSchema.Obj) {
                        put("parameters", buildJsonObject {
                            put("type", "object")
                            put("properties", schema.properties)
                            schema.required?.let { required ->
                                put("required", buildJsonArray { required.forEach { add(it) } })
                            }
                        }.strippedForGemini())
                    }
                })
            }
        }
        return array.toString()
    }

    /**
     * Keys of the same list Android's GoogleProvider strips via
     * `JsonElement.removeElements(...)` before hitting cloudcode-pa /
     * generativelanguage (both iOS routes share these backends).
     */
    private val GEMINI_UNSUPPORTED_SCHEMA_KEYS = setOf(
        "const",
        "exclusiveMaximum",
        "exclusiveMinimum",
        "format",
        "additionalProperties",
        "enum",
    )

    private fun JsonElement.strippedForGemini(): JsonElement = when (this) {
        is JsonObject -> JsonObject(
            toMap()
                .filterKeys { it !in GEMINI_UNSUPPORTED_SCHEMA_KEYS }
                .mapValues { (_, value) -> value.strippedForGemini() },
        )
        is JsonArray -> JsonArray(map { it.strippedForGemini() })
        else -> this
    }
}
