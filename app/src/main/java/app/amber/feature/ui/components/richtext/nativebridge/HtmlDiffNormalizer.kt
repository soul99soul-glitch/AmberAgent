package app.amber.feature.ui.components.richtext.nativebridge

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities

/**
 * Canonicalises HTML so the [MarkdownNativeSwitch] diff sampler can compare
 * JetBrains `HtmlGenerator` output against `pulldown-cmark` output without
 * being drowned in cosmetic divergences (whitespace, attr order, entity
 * escape style, self-closing-tag style).
 *
 * What this normalizer **does** (cosmetic equalisations the two engines
 * legitimately disagree on):
 *
 * - Parses with Jsoup (lenient HTML5 reader) so malformed minor differences
 *   resolve to the same tree.
 * - Sorts each element's attributes by key (Jsoup preserves source order;
 *   the two engines disagree on emission order).
 * - Forces XHTML entity-escape mode (`&amp;` vs `&` for ambiguous chars).
 * - Disables `prettyPrint` so block boundaries don't add stray newlines.
 * - Collapses runs of ASCII whitespace inside text nodes (the two engines
 *   differ on whether trailing whitespace inside `<p>` is retained).
 * - Strips leading + trailing whitespace from the document body.
 *
 * What this normalizer **does NOT do** (genuine differences worth diffing):
 *
 * - Inline-HTML semantics: if one engine emits `<br/>` and the other emits
 *   `<br>`, Jsoup HTML5-canonicalises both to `<br>` — equal. But if one
 *   engine drops an `<img>` because of a safelink rule and the other
 *   keeps it, that survives normalization and reports as divergence.
 * - Tag-tree topology: an extra `<em>` wrapper, missing list item, etc. all
 *   survive normalization.
 * - Text content: zero-width chars / U+00A0 vs ASCII space are preserved
 *   so a "real" different character still reports.
 *
 * **Intentional tradeoff** (Phase 3 C review): the `\s+ → " "` collapse and
 * `>\s+<` tag-boundary strip run over the *entire serialized output*,
 * including content inside `<pre>` / `<code>`. Two `<pre><code>` blocks
 * that genuinely differ only in internal indentation will be equalised; a
 * theoretical input with adjacent siblings inside `<pre>` (e.g.
 * `<pre><code>a</code>\n<code>b</code></pre>`) loses the inter-sibling
 * whitespace. This is acceptable because a diff sampler should under-report
 * code-formatting tweaks rather than flood Crashlytics — but it means this
 * normalizer is NOT suitable for code-equality checks. If you need
 * byte-faithful code-block diffing, slot it BEFORE the parse + collapse
 * chain.
 *
 * Public surface is intentionally tiny — see [normalize].
 */
internal object HtmlDiffNormalizer {

    /**
     * Inputs longer than this (in UTF-16 code units — Kotlin `String.length`)
     * skip Jsoup parsing. `parseBodyFragment` on a 200 K-char chunk can take
     * 10–50 ms and the sampler runs inside the streaming hot path. For very
     * large inputs we accept a guaranteed "diff" reading (the raw strings
     * rarely match) rather than blocking the streamer. ~64 K chars covers
     * the long tail of LLM markdown chunks (typical chunk is well under
     * 4 K chars). Codex review P3-6 renamed this from `_BYTES` — Kotlin
     * `String.length` is char count, not UTF-8 byte size.
     */
    private const val MAX_NORMALIZE_INPUT_CHARS: Int = 64 * 1024

    /**
     * Returns the canonical form of [html]. Pure function — same input always
     * yields same output regardless of call order (Jsoup parser is stateless
     * per call).
     *
     * Falls back to the original input on a Jsoup parse failure (extremely
     * rare since Jsoup accepts almost anything) or when the input exceeds
     * [MAX_NORMALIZE_INPUT_CHARS]. Both fallback paths leave the string
     * unchanged, so a malformed-but-different pair will still report as
     * divergence — the safe direction.
     */
    fun normalize(html: String): String {
        if (html.length > MAX_NORMALIZE_INPUT_CHARS) return html
        val doc: Document = try {
            Jsoup.parseBodyFragment(html)
        } catch (t: Throwable) {
            return html
        }
        doc.outputSettings()
            .prettyPrint(false)
            .escapeMode(Entities.EscapeMode.xhtml)
            .syntax(Document.OutputSettings.Syntax.html)
            .charset(Charsets.UTF_8)
        canonicalizeAttributes(doc.body())
        // Render the body's inner HTML rather than the wrapped `<html><body>...`
        // — `parseBodyFragment` always synthesises that wrapper even when the
        // input had none.
        val raw = doc.body().html()
        // Three passes — each catches a different real-world divergence
        // between JetBrains and pulldown-cmark:
        //   1. `\s+` → ` `  : collapse internal whitespace runs
        //   2. `\s+(</)` → `\1`  : strip space before close tags (handles
        //      `<code>x\n</code>` vs `<code>x</code>`)
        //   3. `>\s+<` → `><`  : strip space between adjacent tags
        //      (handles `</tr>\n<tr>` vs `</tr><tr>`)
        var s = WHITESPACE_RUN.replace(raw, " ")
        s = SPACE_BEFORE_CLOSE_TAG.replace(s, "$1")
        s = SPACE_BETWEEN_TAGS.replace(s, "><")
        return s.trim()
    }

    /**
     * Recursively sort every element's attributes by key. Jsoup `Attributes`
     * is a list-backed structure preserving insertion order; we rebuild it
     * via remove + put-back so the final serialization order is
     * lexicographic.
     */
    private fun canonicalizeAttributes(element: Element) {
        val attrs = element.attributes()
        if (attrs.size() > 1) {
            val sorted = attrs.asList()
                .map { it.key to it.value }
                .sortedBy { it.first }
            attrs.asList().forEach { attrs.remove(it.key) }
            sorted.forEach { (k, v) -> attrs.put(k, v) }
        }
        for (child in element.children()) {
            canonicalizeAttributes(child)
        }
    }

    private val WHITESPACE_RUN: Regex = Regex("\\s+")

    /**
     * Matches whitespace immediately before a close tag (`<\code>`-style).
     * Used to strip trailing whitespace inside elements without removing
     * meaningful inter-word spaces. The capture group preserves the close
     * tag itself.
     */
    private val SPACE_BEFORE_CLOSE_TAG: Regex = Regex("\\s+(</[A-Za-z][^>]*>)")

    /** Matches whitespace between two adjacent tags (`>\n<`-style). */
    private val SPACE_BETWEEN_TAGS: Regex = Regex(">\\s+<")
}
