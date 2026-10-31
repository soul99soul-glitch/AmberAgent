package app.amber.core.utils

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// Re-exports from the shared :core:agent-utils module.
// Kept here as aliases so existing app.amber.core.utils.* callers
// continue to compile during the package migration.
val JsonInstant = app.amber.core.agent.utils.JsonInstant
val JsonInstantPretty = app.amber.core.agent.utils.JsonInstantPretty

// Same behavior as app.amber.core.agent.utils.jsonPrimitiveOrNull; kept
// as a local extension so legacy `app.amber.core.utils.jsonPrimitiveOrNull`
// imports continue to resolve.
val JsonElement.jsonPrimitiveOrNull: JsonPrimitive?
    get() = this as? JsonPrimitive

// ---------------------------------------------------------------------------
// 敏感字段掩码（通用渲染层脱敏：工具参数等 JSON 展示前调用，覆盖所有工具）
// ---------------------------------------------------------------------------

private val SENSITIVE_JSON_KEYS = setOf(
    "api_key",
    "apikey",
    "token",
    "authorization",
    "password",
    "private_key",
    "privatekey",
    "passphrase",
    "secret_key",
    "secretkey",
    "access_key",
    "accesskey",
    "accesstoken",
    "secret",
)

private const val SENSITIVE_MASK = "••••"

/**
 * Keys whose value must never show even a suffix. A credential suffix is still
 * credential material: it narrows a password search and confirms a guess, so
 * password / private key / passphrase always render as a complete mask.
 */
private val FULLY_MASKED_JSON_KEYS = setOf(
    "password",
    "private_key",
    "privatekey",
    "passphrase",
)

/** The storage-boundary placeholder; it is already masked and stays verbatim. */
private const val CREDENTIAL_REDACTION_PLACEHOLDER = "<redacted>"

/** 敏感键下非字符串原始值（数字 / 布尔等）的统一掩码。 */
private const val SENSITIVE_NON_STRING_MASK = "***"

/**
 * 递归掩码敏感字段值：键名（忽略大小写，camelCase 如 secretKey / accessToken 经
 * 小写归一自然命中）落在 [SENSITIVE_JSON_KEYS] 时，字符串值替换为 "••••" + 末 4 位
 * （长度 ≤ 4 全掩码），数字 / 布尔等非字符串原始值掩码为 "***"；空串与 null 原样保留
 * （空串 = 清除、null = 无值），其余元素原样保留。
 *
 * SSH 凭据键（[FULLY_MASKED_JSON_KEYS]）例外：一律输出完整掩码，不保留末 4 位；
 * 存储层已经写入的占位符原样保留，避免二次截断成无意义片段。
 */
internal fun maskSensitiveJson(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> buildJsonObject {
        element.forEach { (key, value) ->
            val normalized = key.lowercase()
            val masked = when (normalized) {
                in FULLY_MASKED_JSON_KEYS -> maskFully(value)
                in SENSITIVE_JSON_KEYS -> maskSensitiveValue(value)
                else -> maskSensitiveJson(value)
            }
            put(key, masked)
        }
    }
    is JsonArray -> JsonArray(element.map { maskSensitiveJson(it) })
    else -> element
}

/** Complete mask for keys whose value must never reveal any character. */
private fun maskFully(value: JsonElement): JsonElement = when (value) {
    is JsonPrimitive -> when {
        value.isString -> when (value.content) {
            "" -> value
            CREDENTIAL_REDACTION_PLACEHOLDER -> value
            else -> JsonPrimitive(SENSITIVE_MASK)
        }
        value is JsonNull -> value
        else -> JsonPrimitive(SENSITIVE_NON_STRING_MASK)
    }
    else -> JsonPrimitive(SENSITIVE_MASK)
}

private fun maskSensitiveValue(value: JsonElement): JsonElement = when (value) {
    is JsonPrimitive -> when {
        value.isString -> {
            val content = value.content
            if (content.isEmpty() || content == CREDENTIAL_REDACTION_PLACEHOLDER) {
                value
            } else if (content.length <= 4) {
                JsonPrimitive(SENSITIVE_MASK)
            } else {
                JsonPrimitive(SENSITIVE_MASK + content.takeLast(4))
            }
        }
        // null = 无值，原样保留；数字 / 布尔等非字符串原始值统一掩码为 "***"
        value is JsonNull -> value
        else -> JsonPrimitive(SENSITIVE_NON_STRING_MASK)
    }
    else -> maskSensitiveJson(value)
}
