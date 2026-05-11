package me.rerere.rikkahub.data.ai.generative

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Validates VChart/slides spec JSON before injection into the sandboxed WebView.
 * Runs the blocklist on parsed JSON string values (not raw text), which defeats
 * Unicode-escape bypasses like \u0065val(.
 */
object VChartSpecValidator {
    private const val MAX_SPEC_BYTES = 51_200 // 50KB
    private const val MAX_ARRAY_ELEMENTS = 1_000
    private const val MAX_STRING_LENGTH = 500
    private const val MAX_DEPTH = 12

    // Blocked in any string value (checked after JSON parse, not on raw text)
    private val BLOCKED_PATTERNS = listOf(
        "javascript:",
        "<script",
        "</script",
        "eval(",
        "Function(",
        "new Function",
        "__proto__",
        "constructor[",
        "prototype.",
        "setTimeout(",
        "setInterval(",
        "atob(",
        "btoa(",
        "srcdoc",
        "onerror=",
        "onload=",
        "data:text/html",
    )

    // String values matching these regexes are rejected as code
    private val FUNCTION_PATTERN = Regex("""^\s*function\s*\(|\=\>""")
    private val INNER_HTML_PATTERN = Regex("""\.innerHTML\s*=|\bdocument\.\w+""")

    data class ValidationResult(
        val valid: Boolean,
        val reason: String? = null,
    )

    fun validateChartSpec(specJson: String): ValidationResult {
        if (specJson.length > MAX_SPEC_BYTES) {
            return ValidationResult(false, "spec too large: ${specJson.length} bytes")
        }
        val parsed = runCatching {
            Json.parseToJsonElement(specJson)
        }.getOrNull() ?: return ValidationResult(false, "invalid JSON")

        val obj = (parsed as? JsonObject) ?: return ValidationResult(false, "expected JSON object")
        return validateElement(obj, depth = 0)
    }

    fun validateSlidesSpec(slidesJson: String): ValidationResult {
        if (slidesJson.length > MAX_SPEC_BYTES) {
            return ValidationResult(false, "slides too large: ${slidesJson.length} bytes")
        }
        val normalized = normalizeSlidesSpecJson(slidesJson)
            ?: return ValidationResult(false, "expected JSON array")
        val array = runCatching {
            Json.parseToJsonElement(normalized) as? JsonArray
        }.getOrNull() ?: return ValidationResult(false, "invalid JSON")
        if (array.size > 24) {
            return ValidationResult(false, "too many slides: ${array.size}")
        }
        array.forEach { element ->
            val result = validateElement(element, depth = 0)
            if (!result.valid) return result
        }
        return ValidationResult(true)
    }

    fun normalizeSlidesSpecJson(slidesJson: String): String? {
        val parsed = runCatching {
            Json.parseToJsonElement(slidesJson)
        }.getOrNull() ?: return null
        val array = when (parsed) {
            is JsonArray -> parsed
            is JsonObject -> parsed["slides"] as? JsonArray
                ?: parsed["spec"] as? JsonArray
                ?: parsed["pages"] as? JsonArray
            else -> null
        } ?: return null
        return array.toString()
    }

    private fun validateElement(element: JsonElement, depth: Int): ValidationResult {
        if (depth > MAX_DEPTH) {
            return ValidationResult(false, "nesting too deep")
        }
        return when (element) {
            is JsonPrimitive -> {
                if (element.isString) {
                    validateStringValue(element.content)
                } else {
                    ValidationResult(true)
                }
            }

            is JsonArray -> {
                if (element.size > MAX_ARRAY_ELEMENTS) {
                    return ValidationResult(false, "array too large: ${element.size}")
                }
                element.forEach { child ->
                    val result = validateElement(child, depth + 1)
                    if (!result.valid) return result
                }
                ValidationResult(true)
            }

            is JsonObject -> {
                element.values.forEach { child ->
                    val result = validateElement(child, depth + 1)
                    if (!result.valid) return result
                }
                ValidationResult(true)
            }

            else -> ValidationResult(true)
        }
    }

    private fun validateStringValue(value: String): ValidationResult {
        if (value.length > MAX_STRING_LENGTH) {
            return ValidationResult(false, "string value too long: ${value.length}")
        }
        val lower = value.lowercase()
        BLOCKED_PATTERNS.forEach { pattern ->
            if (pattern.lowercase() in lower) {
                return ValidationResult(false, "blocked pattern: $pattern")
            }
        }
        if (FUNCTION_PATTERN.containsMatchIn(value)) {
            return ValidationResult(false, "function or arrow syntax in string value")
        }
        if (INNER_HTML_PATTERN.containsMatchIn(value)) {
            return ValidationResult(false, "DOM access in string value")
        }
        return ValidationResult(true)
    }
}
