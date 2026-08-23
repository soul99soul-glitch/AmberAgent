package app.amber.core.sync

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

/**
 * Restore: merge local secrets back into a decoded backup Settings so that
 * masked fields ("__MASKED_BY_AMBERAGENT_BACKUP__") recover the device's real
 * credentials instead of persisting the literal mask string.
 */
internal fun Json.restoreBackupSecrets(exported: Settings, local: Settings): Settings {
    val exportedEl = parseToJsonElement(encodeToString(exported))
    val localEl = parseToJsonElement(encodeToString(local))
    val merged = mergeMaskedSecrets(exportedEl, localEl)
    return decodeFromString(merged.toString())
}

private fun mergeMaskedSecrets(exported: JsonElement, local: JsonElement?): JsonElement = when {
    exported is JsonPrimitive && exported.contentOrNull == BACKUP_SECRET_MASK ->
        local ?: JsonPrimitive("")

    exported is JsonObject -> JsonObject(
        exported.mapValues { (key, value) ->
            mergeMaskedSecrets(value, (local as? JsonObject)?.get(key))
        }
    )

    exported is JsonArray -> JsonArray(
        exported.mapIndexed { index, value ->
            mergeMaskedSecrets(value, (local as? JsonArray)?.getOrNull(index))
        }
    )

    else -> exported
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
        // P1-01: WebDAV/S3 用户名、access key id 与 Vertex service account 私钥也属于凭据，
        // 不再明文进入导出 JSON
        "username",
        "accesskeyid",
        "privatekey",
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
