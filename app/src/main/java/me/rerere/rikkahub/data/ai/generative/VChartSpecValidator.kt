package me.rerere.rikkahub.data.ai.generative

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Validates VChart/slides spec JSON before injection into the sandboxed WebView.
 * Runs the blocklist on parsed JSON string values (not raw text), which defeats
 * Unicode-escape bypasses like \u0065val(.
 */
object VChartSpecValidator {
    private const val MAX_SPEC_BYTES = 51_200 // 50KB
    private const val MAX_ARRAY_ELEMENTS = 1_000
    private const val MAX_STRING_LENGTH = 500
    private const val MAX_SLIDE_STRING_LENGTH = 800
    private const val MAX_DEPTH = 12
    private val json = Json { ignoreUnknownKeys = true }

    private val SUPPORTED_SLIDE_LAYOUTS = setOf(
        "cover",
        "section",
        "quote",
        "split",
        "metrics",
        "timeline",
        "cards",
        "image-grid",
        "comparison",
        "closing",
    )

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
            json.parseToJsonElement(specJson)
        }.getOrNull() ?: return ValidationResult(false, "invalid JSON")

        val obj = (parsed as? JsonObject) ?: return ValidationResult(false, "expected JSON object")
        return validateElement(obj, depth = 0, maxStringLength = MAX_STRING_LENGTH)
    }

    fun validateSlidesSpec(slidesJson: String): ValidationResult {
        if (slidesJson.length > MAX_SPEC_BYTES) {
            return ValidationResult(false, "slides too large: ${slidesJson.length} bytes")
        }
        val normalized = normalizeSlidesDeckSpecJson(slidesJson)
            ?: return ValidationResult(false, "expected slides JSON")
        val deck = runCatching {
            json.parseToJsonElement(normalized) as? JsonObject
        }.getOrNull() ?: return ValidationResult(false, "invalid JSON")
        val array = deck["slides"] as? JsonArray ?: return ValidationResult(false, "expected slides array")
        if (array.isEmpty()) {
            return ValidationResult(false, "empty slides")
        }
        if (array.size > 24) {
            return ValidationResult(false, "too many slides: ${array.size}")
        }
        validateElement(deck, depth = 0, maxStringLength = MAX_SLIDE_STRING_LENGTH).let { result ->
            if (!result.valid) return result
        }
        array.forEach { element ->
            val layout = (element as? JsonObject)
                ?.get("layout")
                ?.let { it as? JsonPrimitive }
                ?.contentOrNull
                ?.lowercase()
            if (layout != null && layout !in SUPPORTED_SLIDE_LAYOUTS) {
                return ValidationResult(false, "unsupported slide layout: $layout")
            }
            val result = validateElement(element, depth = 0, maxStringLength = MAX_SLIDE_STRING_LENGTH)
            if (!result.valid) return result
        }
        return ValidationResult(true)
    }

    /**
     * Returns only the legacy slide array. Kept for old callers/tests that only
     * need the page list. New renderer paths should use [normalizeSlidesDeckSpecJson]
     * so top-level style/accent/fontPack survive.
     */
    fun normalizeSlidesSpecJson(slidesJson: String): String? {
        return slidesArrayOrNull(slidesJson)?.toString()
    }

    fun normalizeSlidesDeckSpecJson(slidesJson: String): String? {
        val parsed = runCatching {
            json.parseToJsonElement(slidesJson)
        }.getOrNull() ?: return null
        return normalizeSlidesDeckSpec(parsed)?.toString()
    }

    fun slidesArrayOrNull(slidesJson: String): JsonArray? {
        val parsed = runCatching {
            json.parseToJsonElement(slidesJson)
        }.getOrNull() ?: return null
        return findSlidesSource(parsed)?.slides
    }

    private fun normalizeSlidesDeckSpec(parsed: JsonElement): JsonObject? {
        val source = findSlidesSource(parsed) ?: return null
        val explicitSource = source.source
        val topLevel = parsed as? JsonObject
        return buildJsonObject {
            put("schemaVersion", pickInt(topLevel, explicitSource, "schemaVersion") ?: 2)
            put("style", pickString(topLevel, explicitSource, "style") ?: "system")
            pickString(topLevel, explicitSource, "accent")?.let { put("accent", it) }
            pickString(topLevel, explicitSource, "fontPack")?.let { put("fontPack", it) }
            pickString(topLevel, explicitSource, "title")?.let { put("title", it) }
            put("slides", source.slides)
        }
    }

    private data class SlidesSource(
        val source: JsonObject?,
        val slides: JsonArray,
    )

    private fun findSlidesSource(element: JsonElement): SlidesSource? {
        return when (element) {
            is JsonArray -> SlidesSource(source = null, slides = element)
            is JsonObject -> {
                (element["slides"] as? JsonArray)?.let { SlidesSource(element, it) }
                    ?: (element["pages"] as? JsonArray)?.let { SlidesSource(element, it) }
                    ?: (element["spec"] as? JsonArray)?.let { SlidesSource(element, it) }
                    ?: (element["spec"] as? JsonObject)?.let { findSlidesSource(it) }
            }

            else -> null
        }
    }

    private fun pickString(primary: JsonObject?, fallback: JsonObject?, key: String): String? =
        (primary?.get(key) as? JsonPrimitive)?.contentOrNull
            ?: (fallback?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun pickInt(primary: JsonObject?, fallback: JsonObject?, key: String): Int? =
        (primary?.get(key) as? JsonPrimitive)?.intOrNull
            ?: (fallback?.get(key) as? JsonPrimitive)?.intOrNull

    private fun validateElement(
        element: JsonElement,
        depth: Int,
        maxStringLength: Int,
    ): ValidationResult {
        if (depth > MAX_DEPTH) {
            return ValidationResult(false, "nesting too deep")
        }
        return when (element) {
            is JsonPrimitive -> {
                if (element.isString) {
                    validateStringValue(element.content, maxStringLength)
                } else {
                    ValidationResult(true)
                }
            }

            is JsonArray -> {
                if (element.size > MAX_ARRAY_ELEMENTS) {
                    return ValidationResult(false, "array too large: ${element.size}")
                }
                element.forEach { child ->
                    val result = validateElement(child, depth + 1, maxStringLength)
                    if (!result.valid) return result
                }
                ValidationResult(true)
            }

            is JsonObject -> {
                element.entries.forEach { (key, child) ->
                    val keyResult = validateObjectKey(key)
                    if (!keyResult.valid) return keyResult
                    val result = validateElement(child, depth + 1, maxStringLength)
                    if (!result.valid) return result
                }
                ValidationResult(true)
            }
        }
    }

    private fun validateObjectKey(key: String): ValidationResult {
        if (key.length > MAX_STRING_LENGTH) {
            return ValidationResult(false, "object key too long: ${key.length}")
        }
        val lower = key.lowercase()
        if (lower == "__proto__" || lower == "constructor" || lower == "prototype") {
            return ValidationResult(false, "blocked object key: $key")
        }
        BLOCKED_PATTERNS.forEach { pattern ->
            if (pattern.lowercase() in lower) {
                return ValidationResult(false, "blocked object key pattern: $pattern")
            }
        }
        return ValidationResult(true)
    }

    private fun validateStringValue(value: String, maxStringLength: Int): ValidationResult {
        if (value.length > maxStringLength) {
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
