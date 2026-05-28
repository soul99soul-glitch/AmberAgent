package me.rerere.rikkahub.data.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.amber.core.settings.Settings

internal const val BACKUP_SECRET_MASK = "__MASKED_BY_AMBERAGENT_BACKUP__"

internal fun Json.encodeSettingsForBackup(settings: Settings): String {
    val element = parseToJsonElement(encodeToString(settings))
    return maskBackupSecrets(element).toString()
}

internal fun maskBackupSecrets(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(
        element.mapValues { (key, value) ->
            when {
                key.isSensitiveBackupKey() -> JsonPrimitive(BACKUP_SECRET_MASK)
                key.equals("headers", ignoreCase = true) -> maskHeaderCollection(value)
                else -> maskBackupSecrets(value)
            }
        }
    )

    is JsonArray -> JsonArray(element.map { maskBackupSecrets(it) })
    else -> element
}

private fun maskHeaderCollection(element: JsonElement): JsonElement = when (element) {
    is JsonArray -> JsonArray(element.map { maskHeaderEntry(it) })
    else -> maskBackupSecrets(element)
}

private fun maskHeaderEntry(element: JsonElement): JsonElement {
    if (element is JsonObject) {
        val name = element["first"]?.jsonPrimitive?.contentOrNull
            ?: element["name"]?.jsonPrimitive?.contentOrNull
            ?: element["key"]?.jsonPrimitive?.contentOrNull
        if (name.isSensitiveHeaderName()) {
            return JsonObject(
                element.mapValues { (key, value) ->
                    if (key == "second" || key == "value") JsonPrimitive(BACKUP_SECRET_MASK) else maskBackupSecrets(value)
                }
            )
        }
    }
    return maskBackupSecrets(element)
}

private fun String.isSensitiveBackupKey(): Boolean {
    val normalized = lowercase().replace("-", "").replace("_", "")
    return normalized in setOf(
        "apikey",
        "password",
        "secret",
        "secretaccesskey",
        "clientsecret",
        "accesstoken",
        "refreshtoken",
        "token",
        "bearertoken",
        "authorization",
    )
}

private fun String?.isSensitiveHeaderName(): Boolean {
    val normalized = this?.lowercase()?.replace("-", "")?.replace("_", "").orEmpty()
    return normalized in setOf(
        "authorization",
        "proxyauthorization",
        "xapikey",
        "apikey",
        "xauthkey",
        "xauthtoken",
        "cookie",
        "setcookie",
    )
}
