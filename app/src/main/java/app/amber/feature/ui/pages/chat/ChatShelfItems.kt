package app.amber.feature.ui.pages.chat

import androidx.compose.runtime.Immutable
import app.amber.ai.ui.UIMessageAnnotation
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import kotlin.uuid.Uuid

enum class ShelfItemKind { IMAGE, FILE, MINI_APP, WEB }

@Immutable
data class ShelfItem(
    val kind: ShelfItemKind,
    val label: String,
    /** Image/file URL, web URL, or mini-app id. */
    val target: String,
    val nodeId: Uuid,
    /** Selected message id of [nodeId] when collected. */
    val messageId: String,
)

/**
 * Artifacts on the current branch, newest first: images and files from messages and
 * tool outputs, mini apps, and cited web pages. Duplicate targets keep the newest.
 */
fun Conversation.collectShelfItems(): List<ShelfItem> {
    val seen = HashSet<String>()
    val items = ArrayList<ShelfItem>()
    for (node in messageNodes.asReversed()) {
        val message = node.messages.getOrNull(node.selectIndex) ?: continue
        val messageId = message.id.toString()
        val found = ArrayList<ShelfItem>()
        fun visit(part: UIMessagePart) {
            when (part) {
                is UIMessagePart.Image -> if (part.url.isNotBlank()) {
                    found += ShelfItem(ShelfItemKind.IMAGE, part.url.fileLabel(), part.url, node.id, messageId)
                }
                is UIMessagePart.Document -> if (part.url.isNotBlank()) {
                    found += ShelfItem(ShelfItemKind.FILE, part.fileName.ifBlank { part.url.fileLabel() }, part.url, node.id, messageId)
                }
                is UIMessagePart.MiniApp -> found += ShelfItem(ShelfItemKind.MINI_APP, part.title, part.appId, node.id, messageId)
                is UIMessagePart.Tool -> part.output.forEach(::visit)
                else -> Unit
            }
        }
        message.parts.forEach(::visit)
        message.annotations.filterIsInstance<UIMessageAnnotation.UrlCitation>().forEach { citation ->
            if (citation.url.isNotBlank()) {
                found += ShelfItem(ShelfItemKind.WEB, citation.title.ifBlank { citation.url }, citation.url, node.id, messageId)
            }
        }
        // Within one message keep reading order; across messages newest first.
        found.forEach { if (seen.add(it.target)) items += it }
    }
    return items
}

/** Last path segment; blank for inline data URLs so the UI falls back to the kind label. */
private fun String.fileLabel(): String =
    if (startsWith("data:")) "" else substringAfterLast('/').substringBefore('?')
