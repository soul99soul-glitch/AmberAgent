package me.rerere.rikkahub.data.ai.generative

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Validates VChart/slides spec JSON before injection into the sandboxed WebView.
 * Enforces size limits and blocks strings that look like code injection.
 */
object VChartSpecValidator {
    private const val MAX_SPEC_BYTES = 51_200 // 50KB
    private const val MAX_ARRAY_ELEMENTS = 1_000
    private const val MAX_STRING_LENGTH = 500
    private const val MAX_DEPTH = 12

    private val BLOCKED_PATTERNS = listOf(
        "javascript:",
        "<script",
        "</script",
        "\\u003cscript",
        "eval(",
        "Function(",
        "__proto__",
        "constructor[",
        "prototype.",
    )

    data class ValidationResult(
        val valid: Boolean,
        val reason: String? = null,
    )

    fun validateChartSpec(specJson: String): ValidationResult {
        if (specJson.length > MAX_SPEC_BYTES) {
            return ValidationResult(false, "spec too large: ${specJson.length} bytes")
        }
        val lower = specJson.lowercase()
        BLOCKED_PATTERNS.forEach { pattern ->
            if (pattern.lowercase() in lower) {
                return ValidationResult(false, "blocked pattern: $pattern")
            }
        }
        val parsed = runCatching {
            Json.parseToJsonElement(specJson).jsonObject
        }.getOrNull() ?: return ValidationResult(false, "invalid JSON")

        return validateElement(parsed, depth = 0)
    }

    fun validateSlidesSpec(slidesJson: String): ValidationResult {
        if (slidesJson.length > MAX_SPEC_BYTES) {
            return ValidationResult(false, "slides too large: ${slidesJson.length} bytes")
        }
        val lower = slidesJson.lowercase()
        BLOCKED_PATTERNS.forEach { pattern ->
            if (pattern.lowercase() in lower) {
                return ValidationResult(false, "blocked pattern: $pattern")
            }
        }
        val parsed = runCatching {
            Json.parseToJsonElement(slidesJson).jsonArray
        }.getOrNull() ?: return ValidationResult(false, "invalid JSON array")

        if (parsed.size > 24) {
            return ValidationResult(false, "too many slides: ${parsed.size}")
        }
        parsed.forEach { element ->
            val result = validateElement(element, depth = 0)
            if (!result.valid) return result
        }
        return ValidationResult(true)
    }

    private fun validateElement(element: JsonElement, depth: Int): ValidationResult {
        if (depth > MAX_DEPTH) {
            return ValidationResult(false, "nesting too deep")
        }
        return when (element) {
            is JsonPrimitive -> {
                if (element.isString) {
                    val content = element.content
                    if (content.length > MAX_STRING_LENGTH) {
                        ValidationResult(false, "string value too long: ${content.length}")
                    } else {
                        ValidationResult(true)
                    }
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
}
