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
    "secret_key",
    "secretkey",
    "access_key",
    "accesskey",
    "accesstoken",
    "secret",
)

private const val SENSITIVE_MASK = "••••"

/** 敏感键下非字符串原始值（数字 / 布尔等）的统一掩码。 */
private const val SENSITIVE_NON_STRING_MASK = "***"

/**
 * 递归掩码敏感字段值：键名（忽略大小写，camelCase 如 secretKey / accessToken 经
 * 小写归一自然命中）落在 [SENSITIVE_JSON_KEYS] 时，字符串值替换为 "••••" + 末 4 位
 * （长度 ≤ 4 全掩码），数字 / 布尔等非字符串原始值掩码为 "***"；空串与 null 原样保留
 * （空串 = 清除、null = 无值），其余元素原样保留。
 */
internal fun maskSensitiveJson(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> buildJsonObject {
        element.forEach { (key, value) ->
            val masked = if (key.lowercase() in SENSITIVE_JSON_KEYS) {
                maskSensitiveValue(value)
            } else {
                maskSensitiveJson(value)
            }
            put(key, masked)
        }
    }
    is JsonArray -> JsonArray(element.map { maskSensitiveJson(it) })
    else -> element
}

private fun maskSensitiveValue(value: JsonElement): JsonElement = when (value) {
    is JsonPrimitive -> when {
        value.isString -> {
            val content = value.content
            if (content.isEmpty()) {
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
