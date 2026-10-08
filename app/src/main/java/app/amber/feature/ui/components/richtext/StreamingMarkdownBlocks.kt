package app.amber.feature.ui.components.richtext

import app.amber.feature.ui.components.richtext.tree.MdNode

internal fun markdownBlockKey(node: MdNode, sourceOffset: Int): String =
    "block:${node.type}:${sourceOffset + node.startOffset}"

internal data class MarkdownRenderBlock(
    val key: String,
    val node: MdNode,
    val content: String,
    val stable: Boolean,
    val preserveParagraphBottomPadding: Boolean = false,
    val lastActive: Boolean = false,
)

/** One ordered list and one Compose call site keep a block alive when it is promoted. */
internal fun MarkdownParseResult.renderBlocks(): List<MarkdownRenderBlock> = buildList {
    stableTopLevelBlocks.forEach { block ->
        block.parseResult.tree.children.forEachIndexed { index, node ->
            add(MarkdownRenderBlock(
                key = if (index == 0) block.key else "${block.key}:$index",
                node = node,
                content = block.parseResult.preprocessed,
                stable = true,
                preserveParagraphBottomPadding = block.preserveParagraphBottomPadding,
            ))
        }
    }
    tree.children.forEachIndexed { index, node ->
        add(MarkdownRenderBlock(
            key = markdownBlockKey(node, activeBaseOffset),
            node = node,
            content = preprocessed,
            stable = false,
            lastActive = index == tree.children.lastIndex,
        ))
    }
}
