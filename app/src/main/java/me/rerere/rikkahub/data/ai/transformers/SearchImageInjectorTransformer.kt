package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * After generation finishes, injects search result images into the
 * assistant's response text based on citation matching.
 *
 * - Thumbnail row at the top (only if citations matched images)
 * - Individual images after paragraphs with matching citations
 * - Each image appears once only, max 5 total
 */
object SearchImageInjectorTransformer : OutputMessageTransformer {

    private const val TAG = "SearchImageInjector"
    private val CITATION_REGEX = Regex("""\[citation,[^\]]*]\(([^)]+)\)""")
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        Log.d(TAG, "onGenerationFinish called, ${messages.size} messages")

        // Early check: is there an assistant message with text?
        val lastIdx = messages.indexOfLast {
            it.role == MessageRole.ASSISTANT && it.parts.any { p -> p is UIMessagePart.Text }
        }
        if (lastIdx < 0) {
            Log.d(TAG, "no assistant message with text found")
            return messages
        }

        // Dump message structure for debugging
        messages.forEachIndexed { i, msg ->
            val partTypes = msg.parts.joinToString(",") { p ->
                when (p) {
                    is UIMessagePart.Tool -> "Tool(${p.toolName},out=${p.output.size})"
                    is UIMessagePart.Text -> "Text(${p.text.take(40)})"
                    else -> p::class.simpleName ?: "?"
                }
            }
            Log.d(TAG, "  msg[$i] role=${msg.role} parts=[$partTypes]")
        }

        // Build image map from search_web Tool parts
        val imagesByItemId = buildImageMap(messages)
        Log.d(TAG, "imageMap: ${imagesByItemId.size} items with images")
        if (imagesByItemId.isEmpty()) return messages

        val msg = messages[lastIdx]
        // Use last Text part (main response), not first (could be converted base64 etc.)
        val textIdx = msg.parts.indexOfLast { it is UIMessagePart.Text }
        if (textIdx < 0) return messages

        val originalText = (msg.parts[textIdx] as UIMessagePart.Text).text
        Log.d(TAG, "original text (first 100): ${originalText.take(100)}")
        val injected = injectByCitation(originalText, imagesByItemId)
        Log.d(TAG, "injection result: changed=${injected != originalText}, length ${originalText.length}->${injected.length}")
        if (injected == originalText) return messages

        val newParts = msg.parts.toMutableList()
        newParts[textIdx] = UIMessagePart.Text(text = injected)
        return messages.toMutableList().also {
            it[lastIdx] = msg.copy(parts = newParts)
        }
    }

    private fun buildImageMap(messages: List<UIMessage>): Map<String, List<String>> {
        val map = mutableMapOf<String, List<String>>()
        for (msg in messages) {
            if (msg.role != MessageRole.ASSISTANT) continue
            for (part in msg.parts) {
                if (part !is UIMessagePart.Tool) continue
                if (part.toolName != "search_web") continue
                val outputJson = part.output
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("") { it.text }
                if (outputJson.isBlank()) continue
                val items = runCatching {
                    json.parseToJsonElement(outputJson).jsonObject["items"]?.jsonArray
                }.getOrNull() ?: continue
                for (item in items) {
                    val obj = item as? JsonObject ?: continue
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                    val imgs = runCatching {
                        obj["images"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    }.getOrNull().orEmpty()
                    if (imgs.isNotEmpty()) map[id] = imgs
                }
            }
        }
        return map
    }

    private fun injectByCitation(text: String, imagesByItemId: Map<String, List<String>>): String {
        // Split into paragraphs, filter out blank ones from triple-newlines
        val paragraphs = text.split(Regex("\n{2,}")).filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) return text

        // First pass: find which paragraphs have citation-matched images
        val usedImages = mutableSetOf<String>()
        data class Injection(val paragraphIdx: Int, val imageUrl: String)
        val injections = mutableListOf<Injection>()

        paragraphs.forEachIndexed { idx, paragraph ->
            if (usedImages.size >= 5) return@forEachIndexed
            for (match in CITATION_REGEX.findAll(paragraph)) {
                val id = match.groupValues[1]
                val imgs = imagesByItemId[id] ?: continue
                for (img in imgs) {
                    if (img !in usedImages && usedImages.size < 5) {
                        injections.add(Injection(idx, img))
                        usedImages.add(img)
                        break // one image per citation
                    }
                }
            }
        }

        // No citations matched any images — don't inject anything
        if (injections.isEmpty()) return text

        // Build output
        val sb = StringBuilder()

        // Thumbnail row at top (only the images that will be used inline — no duplicates)
        sb.appendLine(usedImages.joinToString(" ") { "![]($it)" })
        sb.appendLine()

        // Reconstruct paragraphs with inline images after cited ones
        val injectAfter = injections.groupBy { it.paragraphIdx }
        paragraphs.forEachIndexed { idx, paragraph ->
            sb.append(paragraph)
            val imgs = injectAfter[idx]
            if (imgs != null) {
                for (inj in imgs) {
                    sb.append("\n\n![](${inj.imageUrl})")
                }
            }
            if (idx < paragraphs.size - 1) sb.append("\n\n")
        }

        return sb.toString()
    }
}
