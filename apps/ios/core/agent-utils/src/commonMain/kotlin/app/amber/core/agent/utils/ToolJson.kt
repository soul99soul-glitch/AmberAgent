package app.amber.core.agent.utils

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun JsonElement.string(name: String): String? =
    jsonObject[name]?.jsonPrimitive?.contentOrNull

fun JsonElement.requiredString(name: String): String =
    string(name) ?: error("$name is required")

fun JsonElement.boolean(name: String): Boolean? =
    jsonObject[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

fun JsonElement.int(name: String): Int? =
    jsonObject[name]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

fun JsonElement.long(name: String): Long? =
    jsonObject[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
