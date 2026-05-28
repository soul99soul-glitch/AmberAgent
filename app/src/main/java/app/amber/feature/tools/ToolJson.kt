package app.amber.feature.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart

internal fun JsonElement.string(name: String): String? =
    jsonObject[name]?.jsonPrimitive?.contentOrNull

internal fun JsonElement.requiredString(name: String): String =
    string(name) ?: error("$name is required")

internal fun JsonElement.boolean(name: String): Boolean? =
    jsonObject[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

internal fun JsonElement.int(name: String): Int? =
    jsonObject[name]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

internal fun JsonElement.long(name: String): Long? =
    jsonObject[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

internal inline fun textJson(builder: JsonObjectBuilder.() -> Unit): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject(builder).toString()))
