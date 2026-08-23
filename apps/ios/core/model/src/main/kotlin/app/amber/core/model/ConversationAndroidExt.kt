package app.amber.core.model

import android.net.Uri
import androidx.core.net.toUri
import app.amber.ai.ui.UIMessagePart

/**
 * Android-specific extensions for types defined in :core:types.
 *
 * Contains Android Uri-based file helpers extracted from the original
 * Conversation.kt during the core:types module extraction.
 */

/**
 * Recursively expand all parts, including nested parts inside tool call results.
 */
private fun List<UIMessagePart>.collectAllParts(): List<UIMessagePart> =
    this + filterIsInstance<UIMessagePart.Tool>().flatMap { it.output.collectAllParts() }

/**
 * Extract local file URI referenced by a part.
 */
fun UIMessagePart.fileUri(): Uri? = when (this) {
    is UIMessagePart.Image -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Document -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Video -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Audio -> url.takeIf { it.startsWith("file://") }?.toUri()
    else -> null
}

/**
 * Collect all file URIs from the conversation's message parts.
 */
val Conversation.files: List<Uri>
    get() = messageNodes
        .flatMap { node -> node.messages.flatMap { it.parts } }
        .collectAllParts()
        .mapNotNull { it.fileUri() }
