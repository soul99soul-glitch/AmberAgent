package app.amber.core.ai.transformers

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
 *
 * Fence payload format (one entry per line inside ```search-images):
 *   `<url>|<caption?>` — caption is optional; absent caption renders as bare image
 *   Captions use " · " as the separator between title and domain in the UI; the
 *   transformer pre-joins them into one string here so SearchImageBlock doesn't
 *   need to know about title vs. domain.
 */
object SearchImageInjectorTransformer : OutputMessageTransformer {

    private const val TAG = "SearchImageInjector"
    private val CITATION_REGEX = Regex("""\[citation,[^\]]*]\(([^)]+)\)""")
    // Strip any `![alt](url)` the LLM wrote on its own. Even with the prompt
    // telling it not to, some models still inline images — and once they do,
    // those images bypass our SearchImageBlock and go through the default
    // markdown renderer (inconsistent sizes, grey rectangles on load failure,
    // no group layout). Stripping at the transformer guarantees every search
    // image goes through one renderer with one set of rules.
    private val INLINE_MARKDOWN_IMAGE_REGEX = Regex("""!\[[^\]]*]\([^)\s]+\)""")
    private val json = Json { ignoreUnknownKeys = true }

    private data class ImageMeta(val url: String, val caption: String?)

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

        // Build image map from search_web Tool parts; each entry now carries an
        // optional caption ("title · domain") so SearchImageBlock can render it
        // under the single-image variant, matching the mockup.
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

    private fun buildImageMap(messages: List<UIMessage>): Map<String, List<ImageMeta>> {
        val map = mutableMapOf<String, List<ImageMeta>>()
        for (msg in messages) {
            if (msg.role != MessageRole.ASSISTANT) continue
            for (part in msg.parts) {
                if (part !is UIMessagePart.Tool) continue
                if (part.toolName != "search_web") continue
                val outputJson = part.output
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("") { it.text }
                if (outputJson.isBlank()) continue
                val rootObj = runCatching { json.parseToJsonElement(outputJson).jsonObject }
                    .getOrNull() ?: continue
                val items = rootObj["items"]?.jsonArray ?: continue
                for (item in items) {
                    val obj = item as? JsonObject ?: continue
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                    val imgs = runCatching {
                        obj["images"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    }.getOrNull().orEmpty()
                    if (imgs.isEmpty()) continue
                    // Build "title · domain" caption from the same item, so each
                    // injected image carries provenance even after the LLM's text
                    // is regenerated. Domain falls back to host part of the URL
                    // when the orchestrator's explicit domain field is absent
                    // (SearchAggregator path vs. SearchOrchestrator path differ).
                    val title = obj["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    val domain = obj["domain"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: obj["url"]?.jsonPrimitive?.contentOrNull?.let { url ->
                            runCatching {
                                java.net.URI(url).host
                                    ?.removePrefix("www.")
                                    ?.takeIf { it.isNotBlank() }
                            }.getOrNull()
                        }
                    val caption = when {
                        title != null && domain != null -> "$title · $domain"
                        title != null -> title
                        domain != null -> domain
                        else -> null
                    }
                    map[id] = imgs.map { url -> ImageMeta(url = url, caption = caption) }
                }
            }
        }
        return map
    }

    private fun injectByCitation(text: String, imagesByItemId: Map<String, List<ImageMeta>>): String {
        // Strip any inline `![](url)` the LLM may have written — see
        // INLINE_MARKDOWN_IMAGE_REGEX docstring. Trim trailing whitespace per
        // line so the strip doesn't leave dangling spaces at paragraph ends.
        val stripped = text
            .replace(INLINE_MARKDOWN_IMAGE_REGEX, "")
            .lineSequence()
            .map { it.trimEnd() }
            .joinToString("\n")
        val paragraphs = stripped.split(Regex("\n{2,}")).filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) return text

        // First pass: walk paragraphs in order, collect one ImageMeta per
        // citation hit, dedup by URL, cap at 5 globally.
        val usedUrls = mutableSetOf<String>()
        data class Injection(val paragraphIdx: Int, val image: ImageMeta)
        val injections = mutableListOf<Injection>()
        val orderedHero = mutableListOf<ImageMeta>()

        paragraphs.forEachIndexed { idx, paragraph ->
            if (usedUrls.size >= 5) return@forEachIndexed
            // Per-citation-hit one image, no per-paragraph cap. The downstream
            // grouping (same paragraph's hits merged into ONE fence) lets
            // SearchImageBlock auto-switch between layouts: a single image is
            // rendered full-width with caption, multiple images are rendered as
            // a side-by-side row. Whichever the LLM happened to cite per
            // paragraph wins.
            for (match in CITATION_REGEX.findAll(paragraph)) {
                val id = match.groupValues[1]
                val metas = imagesByItemId[id] ?: continue
                for (meta in metas) {
                    if (meta.url !in usedUrls && usedUrls.size < 5) {
                        injections.add(Injection(idx, meta))
                        orderedHero.add(meta)
                        usedUrls.add(meta.url)
                        break // one image per citation hit
                    }
                }
            }
        }

        if (injections.isEmpty()) return text

        // Build output. Two fence variants:
        //   * Top thumbnail strip — list every used URL on its own line, no caption
        //     (mockup's thumbnail strip is photo-only).
        //   * Inline single image after a cited paragraph — URL plus "|caption" so
        //     SearchImageBlock can render the small grey "title · domain" line
        //     directly beneath the image, matching mockup's `.img-caption`.
        val sb = StringBuilder()
        sb.append("```search-images\n")
        orderedHero.forEach { sb.append(it.url).append('\n') }
        sb.append("```\n\n")

        // Group ALL images cited in the same paragraph into ONE fenced block, so
        // SearchImageBlock can apply the "multiple URLs → horizontal Row" rule
        // (one image full-width with caption; multiple images side-by-side as a
        // thumbnail strip). Previously each inj got its own fence, which always
        // hit the single-image branch and stacked vertically.
        val injectAfter = injections.groupBy { it.paragraphIdx }
        paragraphs.forEachIndexed { idx, paragraph ->
            sb.append(paragraph)
            val imgs = injectAfter[idx].orEmpty()
            if (imgs.isNotEmpty()) {
                sb.append("\n\n```search-images\n")
                for (inj in imgs) {
                    sb.append(inj.image.url)
                    inj.image.caption?.let { sb.append('|').append(it) }
                    sb.append('\n')
                }
                // Fence end on its own line, then the outer loop adds \n\n before the
                // next paragraph. Without this trailing \n some markdown parsers fold
                // the next paragraph into the code block.
                sb.append("```\n")
            }
            if (idx < paragraphs.size - 1) sb.append("\n\n")
        }

        return sb.toString()
    }
}
