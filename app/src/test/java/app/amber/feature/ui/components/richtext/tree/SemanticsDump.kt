package app.amber.feature.ui.components.richtext.tree

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

/**
 * Deterministic text dump of a semantics tree for golden comparison.
 * Captures structure + text + roles + link annotations; deliberately
 * ignores positions/sizes (layout jitter) and unmerged internals.
 *
 * Normalizations (determinism beats fidelity — see the snapshot test header):
 *  - Image nodes (Coil-loaded, async) collapse to a stable `image=<contentDescription>`
 *    line so the snapshot never depends on whether the bitmap finished loading.
 *  - Whitespace inside text/desc is collapsed to single spaces and trimmed, so
 *    soft-wrap / indentation jitter cannot perturb the golden.
 */
fun SemanticsNode.dumpNormalized(indent: String = ""): String = buildString {
    val text = config.getOrNull(SemanticsProperties.Text)?.joinToString("|") { it.text }
    val desc = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("|")
    val role = config.getOrNull(SemanticsProperties.Role)?.toString()

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
        )
        if (parts.isNotEmpty()) appendLine("$indent${parts.joinToString(" ")}")
    }
    children.forEach { append(it.dumpNormalized("$indent  ")) }
}

private fun String.normalizeWs(): String = replace(Regex("[ \\t]+"), " ").trim()
