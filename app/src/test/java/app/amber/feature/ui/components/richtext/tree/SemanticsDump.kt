package app.amber.feature.ui.components.richtext.tree

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.LinkAnnotation

/**
 * Deterministic text dump of a semantics tree for golden comparison.
 * Captures node nesting, visible text, accessibility roles, image alt text,
 * and `LinkAnnotation.Url` destinations (so link regressions are caught);
 * deliberately ignores positions/sizes (layout jitter) and unmerged internals.
 *
 * What this dump does NOT capture — these are invisible to it and a change to
 * any of them will pass silently: heading level (an H1 and an H6 dump the same),
 * bold/italic/strikethrough styling, horizontal rules, task-checkbox
 * checked/unchecked state, and span colors. Pin those separately if they matter.
 *
 * Normalizations (determinism beats fidelity — see the snapshot test header):
 *  - Image nodes (Coil-loaded, async) collapse to a stable `image=<contentDescription>`
 *    line so the snapshot never depends on whether the bitmap finished loading.
 *  - Whitespace inside text/desc is collapsed to single spaces and trimmed, so
 *    soft-wrap / indentation jitter cannot perturb the golden.
 */
fun SemanticsNode.dumpNormalized(indent: String = ""): String = buildString {
    val texts = config.getOrNull(SemanticsProperties.Text)
    val text = texts?.joinToString("|") { it.text }
    val desc = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("|")
    val role = config.getOrNull(SemanticsProperties.Role)?.toString()

    // Link destinations live as LinkAnnotation.Url ranges on the raw AnnotatedString
    // (the renderer attaches them via withLink). Flattening to .text drops them, so we
    // pull them off the unflattened strings to keep link regressions visible.
    val links = texts.orEmpty().flatMap { ann ->
        ann.getLinkAnnotations(0, ann.length).mapNotNull { (it.item as? LinkAnnotation.Url)?.url }
    }

    // Image nodes carry only a ContentDescription (alt text) and no Text; their
    // bitmap loads asynchronously off the test thread, so we never want anything
    // size/load dependent in the golden. The alt text is static AST data, so it
    // is the stable identity we pin.
    val isImage = role == "Image"
    if (isImage) {
        appendLine("${indent}image=${(desc ?: "").normalizeWs()}")
    } else {
        val parts = listOfNotNull(
            role?.let { "role=$it" },
            text?.let { "text=${it.normalizeWs()}" },
            desc?.let { "desc=${it.normalizeWs()}" },
            links.takeIf { it.isNotEmpty() }?.let { "links=${it.joinToString("|")}" },
        )
        if (parts.isNotEmpty()) appendLine("$indent${parts.joinToString(" ")}")
    }
    children.forEach { append(it.dumpNormalized("$indent  ")) }
}

private fun String.normalizeWs(): String = replace(Regex("[ \\t]+"), " ").trim()
