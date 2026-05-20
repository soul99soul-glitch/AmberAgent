package me.rerere.rikkahub.data.agent.miniapp

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

class MiniAppOutputParser(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
) {
    fun parseOrNull(text: String): MiniAppGeneratedOutput? {
        val candidate = extractJsonObject(text) ?: return null
        return runCatching {
            val element = json.parseToJsonElement(candidate).jsonObject
            json.decodeFromJsonElement<MiniAppGeneratedOutput>(element).also(::validateOutput)
        }.getOrElse { error ->
            if (error is MiniAppValidationException || error is SerializationException) null else throw error
        }
    }

    fun parse(text: String): MiniAppGeneratedOutput {
        val candidate = extractJsonObject(text) ?: throw MiniAppValidationException("No MiniApp JSON object found")
        val element: JsonObject = json.parseToJsonElement(candidate).jsonObject
        return json.decodeFromJsonElement<MiniAppGeneratedOutput>(element).also(::validateOutput)
    }

    private fun validateOutput(output: MiniAppGeneratedOutput) {
        val title = output.title.trim()
        if (title.isEmpty() || title.length > 20) {
            throw MiniAppValidationException("Title must be 1-20 characters")
        }
        val description = output.description.trim()
        if (description.isEmpty() || description.length > 80) {
            throw MiniAppValidationException("Description must be 1-80 characters")
        }
        val icon = output.icon?.trim().orEmpty()
        if (icon.length > 2) {
            throw MiniAppValidationException("Icon must be at most 2 characters")
        }
        if (output.category !in MiniAppCategories) {
            throw MiniAppValidationException("Unsupported category: ${output.category}")
        }
        val unknown = output.permissions.filterNot { it in MiniAppV3Permissions }
        if (unknown.isNotEmpty()) {
            throw MiniAppValidationException("Unsupported MiniApp permissions: ${unknown.joinToString()}")
        }
        MiniAppHtmlValidator.validate(output.html)
    }

    private fun extractJsonObject(text: String): String? {
        extractFencedJson(text)?.let { return it }
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val c = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    private fun extractFencedJson(text: String): String? {
        val fenceStart = text.indexOf("```")
        if (fenceStart < 0) return null
        val contentStart = text.indexOf('\n', startIndex = fenceStart + 3).takeIf { it >= 0 }?.plus(1)
            ?: (fenceStart + 3)
        val fenceEnd = text.indexOf("```", startIndex = contentStart)
        if (fenceEnd < 0) return null
        return text.substring(contentStart, fenceEnd)
            .removePrefix("json")
            .trim()
            .takeIf { it.startsWith("{") }
    }
}
