package me.rerere.rikkahub.data.sync.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.Settings

private const val SECRET_MASK = "__MASKED_BY_AMBERAGENT_SYNC__"

class SyncRedactor(private val json: Json) {
    fun encodeSettings(settings: Settings, mode: SyncMode): String {
        val raw = json.parseToJsonElement(json.encodeToString(settings))
        val output = if (mode == SyncMode.STANDARD) redact(raw) else raw
        return output.toString()
    }

    fun encodeSecrets(snapshot: SyncSecretSnapshot, mode: SyncMode): String {
        if (mode == SyncMode.STANDARD) {
            return json.encodeToString(SyncSecretSnapshot())
        }
        val raw = json.parseToJsonElement(json.encodeToString(snapshot))
        return raw.toString()
    }

    fun decodeSettingsForRestore(
        settingsJson: String,
        mode: SyncMode,
        localSettings: Settings,
    ): Settings {
        val exported = json.parseToJsonElement(settingsJson)
        val merged = if (mode == SyncMode.STANDARD) {
            val local = json.parseToJsonElement(json.encodeToString(localSettings))
            restoreLocalSecrets(exported, local, trustLocalSecret = true)
        } else {
            exported
        }
        return json.decodeFromString(merged.toString())
    }

    private fun redact(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.mapValues { (key, value) ->
                when {
                    key.isSensitiveFieldName() -> JsonPrimitive(SECRET_MASK)
                    key.equals("headers", ignoreCase = true) -> redactHeaderCollection(value)
                    key.equals("customHeaders", ignoreCase = true) -> redactHeaderCollection(value)
                    else -> redact(value)
                }
            }
        )

        is JsonArray -> JsonArray(element.map { redact(it) })
        else -> element
    }

    private fun redactHeaderCollection(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map { redactHeaderEntry(it) })
        else -> redact(element)
    }

    private fun redactHeaderEntry(element: JsonElement): JsonElement {
        if (element is JsonObject) {
            val name = element["first"]?.jsonPrimitive?.contentOrNull
                ?: element["name"]?.jsonPrimitive?.contentOrNull
                ?: element["key"]?.jsonPrimitive?.contentOrNull
            if (name.isSensitiveHeaderName()) {
                return JsonObject(
                    element.mapValues { (key, value) ->
                        if (key == "second" || key == "value") JsonPrimitive(SECRET_MASK) else redact(value)
                    }
                )
            }
        }
        return redact(element)
    }

    private fun restoreLocalSecrets(
        exported: JsonElement,
        local: JsonElement?,
        trustLocalSecret: Boolean,
    ): JsonElement = when {
        exported is JsonPrimitive && exported.contentOrNull == SECRET_MASK -> {
            if (trustLocalSecret) local ?: JsonPrimitive("") else JsonPrimitive("")
        }

        exported is JsonObject -> JsonObject(
            exported.mapValues { (key, value) ->
                val localObject = local as? JsonObject
                val localValue = localObject?.get(key)
                restoreLocalSecrets(
                    exported = value,
                    local = localValue,
                    trustLocalSecret = trustLocalSecret && exported.hasSameSecretScopeAs(localObject),
                )
            }
        )

        exported is JsonArray -> JsonArray(
            exported.map { value ->
                val localItem = if (value is JsonObject) {
                    (local as? JsonArray)?.firstOrNull { candidate ->
                        candidate is JsonObject && value.hasSameSecretScopeAs(candidate)
                    }
                } else {
                    null
                }
                restoreLocalSecrets(
                    exported = value,
                    local = localItem,
                    trustLocalSecret = trustLocalSecret && localItem != null,
                )
            }
        )

        else -> exported
    }

    private fun JsonObject.hasSameSecretScopeAs(local: JsonObject?): Boolean {
        if (local == null) return false
        val keys = secretScopeKeys.filter { key ->
            containsKey(key) || local.containsKey(key)
        }
        if (keys.isEmpty()) return true
        return keys.all { key ->
            this[key]?.jsonPrimitive?.contentOrNull == local[key]?.jsonPrimitive?.contentOrNull
        }
    }

    private fun String.isSensitiveFieldName(): Boolean {
        val normalized = lowercase().replace("-", "").replace("_", "")
        return normalized in sensitiveFieldNames
    }

    private fun String?.isSensitiveHeaderName(): Boolean {
        val normalized = this?.lowercase()?.replace("-", "")?.replace("_", "").orEmpty()
        return normalized in sensitiveHeaderNames
    }

    companion object {
        private val sensitiveFieldNames = setOf(
            "apikey",
            "password",
            "secret",
            "secretaccesskey",
            "clientsecret",
            "appsecret",
            "accesstoken",
            "refreshtoken",
            "idtoken",
            "token",
            "bearertoken",
            "authorization",
            "cookie",
            "webserveraccesspassword",
        )

        private val sensitiveHeaderNames = setOf(
            "authorization",
            "proxyauthorization",
            "xapikey",
            "apikey",
            "xauthkey",
            "xauthtoken",
            "cookie",
            "setcookie",
        )

        private val secretScopeKeys = listOf(
            "type",
            "id",
            "name",
            "key",
            "first",
            "baseUrl",
            "url",
            "endpoint",
            "bucket",
            "region",
            "path",
            "username",
            "projectId",
            "serviceAccountEmail",
        )
    }
}
