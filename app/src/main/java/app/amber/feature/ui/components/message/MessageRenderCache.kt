package app.amber.feature.ui.components.message

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.UIMessagePart
import app.amber.core.model.Assistant
import app.amber.core.model.AssistantAffectScope
import app.amber.core.ai.transformers.replaceRegexes
import app.amber.feature.ui.components.richtext.MarkdownParseResult
import app.amber.feature.ui.components.richtext.cachedMarkdownParseResult
import app.amber.feature.ui.components.richtext.parseMarkdownContent
import app.amber.core.utils.JsonInstant
import java.security.MessageDigest

private const val MESSAGE_RENDER_CACHE_MAX_ENTRIES = 512
private const val MESSAGE_RENDER_TEXT_CACHE_MAX_CHARS = 200_000
private const val MESSAGE_RENDER_VISUAL_TEXT_CACHE_MAX_CHARS = 1_200_000
private const val MESSAGE_RENDER_TOOL_JSON_CACHE_MAX_CHARS = 18_000_000
private const val MESSAGE_RENDER_TOOL_JSON_ENTRY_MAX_CHARS = 9_000_000
private const val MESSAGE_RENDER_TOOL_OUTPUT_DIGEST_MAX_CHARS = 200_000

internal object MessageRenderCache {
    private val lock = Any()
    private val visualTextEntries = lruMap<VisualTextKey, String>()
    private val toolJsonEntries = lruMap<ToolJsonKey, JsonElement>()
    private val toolOutputIdentityEntries = lruMap<ToolOutputIdentityKey, ToolOutputIdentityEntry>()
    private var visualTextChars = 0
    private var toolJsonChars = 0
    private var toolOutputIdentityChars = 0

    fun visualRegexText(
        text: String,
        assistant: Assistant?,
        scope: AssistantAffectScope,
    ): String {
        if (text.length > MESSAGE_RENDER_TEXT_CACHE_MAX_CHARS) {
            return text.replaceRegexes(
                assistant = assistant,
                scope = scope,
                visual = true,
            )
        }
        val key = VisualTextKey(
            text = text,
            textHash = text.hashCode(),
            assistantSignature = assistant.renderSignature(),
            scope = scope,
        )
        synchronized(lock) {
            visualTextEntries[key]?.let { return it }
        }
        val rendered = text.replaceRegexes(
            assistant = assistant,
            scope = scope,
            visual = true,
        )
        synchronized(lock) {
            visualTextEntries[key]?.let { return it }
            visualTextEntries[key] = rendered
            visualTextChars += key.text.length + rendered.length
            trimVisualTextLocked()
        }
        return rendered
    }

    fun markdownParseResult(content: String): MarkdownParseResult {
        return cachedMarkdownParseResult(content) ?: parseMarkdownContent(content)
    }

    fun toolInputJson(input: String): JsonElement {
        return toolJson(
            text = input.ifBlank { "{}" },
            kind = ToolJsonKind.Input,
        )
    }

    fun toolOutputJson(output: List<UIMessagePart>): JsonElement {
        val identityKey = ToolOutputIdentityKey(output)
        val signature = output.toolOutputIdentitySignature()
        synchronized(lock) {
            val cached = toolOutputIdentityEntries[identityKey]
            if (cached != null) {
                if (cached.matches(signature)) return cached.parsed
                toolOutputIdentityEntries.remove(identityKey)
                toolOutputIdentityChars -= cached.textChars
            }
        }
        val text = output.textOutputForJson()
        val parsed = if (text.length > MESSAGE_RENDER_TOOL_OUTPUT_DIGEST_MAX_CHARS) {
            parseToolJson(text)
        } else {
            toolJson(
                text = text,
                kind = ToolJsonKind.Output,
            )
        }
        synchronized(lock) {
            val cached = toolOutputIdentityEntries[identityKey]
            if (cached != null && cached.matches(signature)) return cached.parsed
            if (cached != null) {
                toolOutputIdentityChars -= cached.textChars
            }
            toolOutputIdentityEntries[identityKey] = ToolOutputIdentityEntry(
                signature = signature,
                parsed = parsed,
                textChars = text.length,
            )
            toolOutputIdentityChars += text.length
            trimToolOutputIdentityLocked()
        }
        return parsed
    }

    private fun toolJson(
        text: String,
        kind: ToolJsonKind,
    ): JsonElement {
        if (text.length > MESSAGE_RENDER_TOOL_JSON_ENTRY_MAX_CHARS) {
            return parseToolJson(text)
        }
        val key = ToolJsonKey(
            length = text.length,
            digest = text.sha256Hex(),
            kind = kind,
        )
        synchronized(lock) {
            toolJsonEntries[key]?.let { return it }
        }
        val parsed = parseToolJson(text)
        synchronized(lock) {
            toolJsonEntries[key]?.let { return it }
            toolJsonEntries[key] = parsed
            toolJsonChars += key.length
            trimToolJsonLocked()
        }
        return parsed
    }

    private fun trimVisualTextLocked() {
        while (
            visualTextEntries.size > MESSAGE_RENDER_CACHE_MAX_ENTRIES ||
            visualTextChars > MESSAGE_RENDER_VISUAL_TEXT_CACHE_MAX_CHARS
        ) {
            val iterator = visualTextEntries.entries.iterator()
            if (!iterator.hasNext()) break
            val eldest = iterator.next()
            visualTextChars -= eldest.key.text.length + eldest.value.length
            iterator.remove()
        }
    }

    private fun trimToolJsonLocked() {
        while (
            toolJsonEntries.size > MESSAGE_RENDER_CACHE_MAX_ENTRIES ||
            toolJsonChars > MESSAGE_RENDER_TOOL_JSON_CACHE_MAX_CHARS
        ) {
            val iterator = toolJsonEntries.entries.iterator()
            if (!iterator.hasNext()) break
            val eldest = iterator.next()
            toolJsonChars -= eldest.key.length
            iterator.remove()
        }
    }

    private fun trimToolOutputIdentityLocked() {
        while (
            toolOutputIdentityEntries.size > MESSAGE_RENDER_CACHE_MAX_ENTRIES ||
            toolOutputIdentityChars > MESSAGE_RENDER_TOOL_JSON_CACHE_MAX_CHARS
        ) {
            val iterator = toolOutputIdentityEntries.entries.iterator()
            if (!iterator.hasNext()) break
            val eldest = iterator.next()
            toolOutputIdentityChars -= eldest.value.textChars
            iterator.remove()
        }
    }

    private fun parseToolJson(text: String): JsonElement =
        runCatching { JsonInstant.parseToJsonElement(text) }
            .getOrElse { JsonObject(emptyMap()) }
}

private data class VisualTextKey(
    val text: String,
    val textHash: Int,
    val assistantSignature: Int,
    val scope: AssistantAffectScope,
)

private data class ToolJsonKey(
    val length: Int,
    val digest: String,
    val kind: ToolJsonKind,
)

private class ToolOutputIdentityKey(
    private val output: List<UIMessagePart>,
) {
    override fun equals(other: Any?): Boolean =
        other is ToolOutputIdentityKey && output === other.output

    override fun hashCode(): Int = System.identityHashCode(output)
}

private data class ToolOutputIdentityEntry(
    val signature: ToolOutputIdentitySignature,
    val parsed: JsonElement,
    val textChars: Int,
) {
    fun matches(other: ToolOutputIdentitySignature): Boolean =
        signature.outputSize == other.outputSize &&
            signature.textCount == other.textCount &&
            signature.totalTextLength == other.totalTextLength &&
            signature.textIdentityHash == other.textIdentityHash &&
            signature.firstTextPart === other.firstTextPart &&
            signature.lastTextPart === other.lastTextPart &&
            signature.firstTextLength == other.firstTextLength &&
            signature.lastTextLength == other.lastTextLength
}

private data class ToolOutputIdentitySignature(
    val outputSize: Int,
    val textCount: Int,
    val totalTextLength: Int,
    val textIdentityHash: Int,
    val firstTextPart: UIMessagePart.Text?,
    val firstTextLength: Int,
    val lastTextPart: UIMessagePart.Text?,
    val lastTextLength: Int,
)

private enum class ToolJsonKind {
    Input,
    Output,
}

private fun Assistant?.renderSignature(): Int {
    if (this == null) return 0
    var result = id.hashCode()
    regexes.forEach { regex ->
        result = 31 * result + regex.id.hashCode()
        result = 31 * result + regex.enabled.hashCode()
        result = 31 * result + regex.findRegex.hashCode()
        result = 31 * result + regex.replaceString.hashCode()
        result = 31 * result + regex.affectingScope.hashCode()
        result = 31 * result + regex.visualOnly.hashCode()
    }
    return result
}

private fun List<UIMessagePart>.textOutputForJson(): String {
    var firstText: String? = null
    var textCount = 0
    var builder: StringBuilder? = null
    for (part in this) {
        if (part !is UIMessagePart.Text) continue
        textCount += 1
        when (textCount) {
            1 -> firstText = part.text
            2 -> {
                builder = StringBuilder(firstText.orEmpty().length + 1 + part.text.length)
                    .append(firstText.orEmpty())
                    .append('\n')
                    .append(part.text)
            }

            else -> builder
                ?.append('\n')
                ?.append(part.text)
        }
    }
    return when (textCount) {
        0 -> ""
        1 -> firstText.orEmpty()
        else -> builder.toString()
    }
}

private fun List<UIMessagePart>.toolOutputIdentitySignature(): ToolOutputIdentitySignature {
    var firstText: UIMessagePart.Text? = null
    var lastText: UIMessagePart.Text? = null
    var textCount = 0
    var totalTextLength = 0
    var textIdentityHash = 1
    for (part in this) {
        if (part !is UIMessagePart.Text) continue
        textCount += 1
        totalTextLength += part.text.length
        textIdentityHash = 31 * textIdentityHash + System.identityHashCode(part)
        if (firstText == null) firstText = part
        lastText = part
    }
    return ToolOutputIdentitySignature(
        outputSize = size,
        textCount = textCount,
        totalTextLength = totalTextLength,
        textIdentityHash = textIdentityHash,
        firstTextPart = firstText,
        firstTextLength = firstText?.text?.length ?: 0,
        lastTextPart = lastText,
        lastTextLength = lastText?.text?.length ?: 0,
    )
}

private fun String.sha256Hex(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    val chars = CharArray(bytes.size * 2)
    bytes.forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        chars[index * 2] = HEX_CHARS[value ushr 4]
        chars[index * 2 + 1] = HEX_CHARS[value and 0x0f]
    }
    return String(chars)
}

private val HEX_CHARS = "0123456789abcdef".toCharArray()

private fun <K, V> lruMap() = object : LinkedHashMap<K, V>(
    MESSAGE_RENDER_CACHE_MAX_ENTRIES,
    0.75f,
    true,
) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean {
        return false
    }
}
