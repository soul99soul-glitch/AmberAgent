package app.amber.feature.ui.hooks

import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.amber.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

class ChatInputState {
    val textContent = TextFieldState()
    var messageContent by mutableStateOf(listOf<UIMessagePart>())
    var editingMessage by mutableStateOf<Uuid?>(null)
    private var editingParts: List<UIMessagePart>? = null
    private var editingAttachmentUrls: Set<String> = emptySet()
    /** Source URIs already accepted this session (dedupe before UUID copy). */
    private var acceptedSourceUris: MutableSet<String> = mutableSetOf()

    fun clearInput() {
        textContent.setTextAndPlaceCursorAtEnd("")
        messageContent = emptyList()
        editingMessage = null
        editingParts = null
        editingAttachmentUrls = emptySet()
        acceptedSourceUris = mutableSetOf()
    }

    fun isEditing() = editingMessage != null

    fun setMessageText(text: String) {
        textContent.setTextAndPlaceCursorAtEnd(text)
    }

    fun appendText(content: String) {
        textContent.setTextAndPlaceCursorAtEnd(textContent.text.toString() + content)
    }

    fun setContents(contents: List<UIMessagePart>) {
        val lastTextIndex = contents.indexOfLast { it is UIMessagePart.Text }
        val text = if (lastTextIndex >= 0) {
            (contents[lastTextIndex] as UIMessagePart.Text).text
        } else {
            ""
        }
        textContent.setTextAndPlaceCursorAtEnd(text)
        messageContent = contents.filter { it !is UIMessagePart.Text }
        editingParts = contents
        editingAttachmentUrls = contents.mapNotNull { it.attachmentUrlOrNull() }.toSet()
    }

    fun getContents(): List<UIMessagePart> {
        val text = textContent.text.toString()
        if (isEditing()) {
            val originalParts = editingParts
            if (originalParts != null) {
                val editedTextIndex = originalParts.indexOfLast { it is UIMessagePart.Text }
                val remainingAttachments = messageContent.toMutableList()
                val merged = mutableListOf<UIMessagePart>()

                originalParts.forEachIndexed { index, part ->
                    when {
                        index == editedTextIndex -> {
                            merged.add(UIMessagePart.Text(text))
                        }

                        part is UIMessagePart.Text -> {
                            merged.add(part)
                        }

                        else -> {
                            val currentIndex = remainingAttachments.indexOf(part)
                            if (currentIndex >= 0) {
                                merged.add(remainingAttachments.removeAt(currentIndex))
                            }
                        }
                    }
                }
                // Newly added attachments are appended in insertion order.
                merged.addAll(remainingAttachments)
                return merged
            }
            return if (text.isBlank()) messageContent else listOf(UIMessagePart.Text(text)) + messageContent
        }
        return listOf(UIMessagePart.Text(text)) + messageContent
    }

    fun isEmpty(): Boolean {
        return textContent.text.isEmpty() && messageContent.isEmpty()
    }

    /**
     * Filter [sourceUris] against already-accepted sources before the caller copies
     * them into chat files (each copy gets a new UUID name, so target-url dedupe is a no-op).
     */
    fun filterNewSourceUris(sourceUris: List<Uri>): List<Uri> {
        return sourceUris.filter { uri ->
            val key = uri.toString()
            if (key in acceptedSourceUris) false else {
                acceptedSourceUris.add(key)
                true
            }
        }
    }

    fun addImages(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        val existingUrls = newMessage.filterIsInstance<UIMessagePart.Image>().map { it.url }.toMutableSet()
        uris.forEach { uri ->
            val url = uri.toString()
            if (url !in existingUrls) {
                newMessage.add(UIMessagePart.Image(url))
                existingUrls.add(url)
            }
        }
        messageContent = newMessage
    }

    fun addVideos(uris: List<Uri>, mimeTypes: List<String?> = emptyList()) {
        val newMessage = messageContent.toMutableList()
        val existingUrls = newMessage.filterIsInstance<UIMessagePart.Video>().map { it.url }.toMutableSet()
        uris.forEachIndexed { index, uri ->
            val url = uri.toString()
            if (url !in existingUrls) {
                newMessage.add(UIMessagePart.Video(url, mime = mimeTypes.getOrNull(index) ?: "video/mp4"))
                existingUrls.add(url)
            }
        }
        messageContent = newMessage
    }

    fun addAudios(uris: List<Uri>, fileNames: List<String> = emptyList(), mimeTypes: List<String?> = emptyList()) {
        val newMessage = messageContent.toMutableList()
        val existingUrls = newMessage.filterIsInstance<UIMessagePart.Audio>().map { it.url }.toMutableSet()
        uris.forEachIndexed { index, uri ->
            val url = uri.toString()
            if (url !in existingUrls) {
                val name = fileNames.getOrNull(index)?.takeIf { it.isNotBlank() }
                    ?: uri.lastPathSegment.orEmpty()
                newMessage.add(
                    UIMessagePart.Audio(
                        url = url,
                        fileName = name,
                        mime = mimeTypes.getOrNull(index) ?: "audio/mpeg",
                    )
                )
                existingUrls.add(url)
            }
        }
        messageContent = newMessage
    }

    fun addFiles(uris: List<UIMessagePart.Document>) {
        val newMessage = messageContent.toMutableList()
        val existingUrls = newMessage.filterIsInstance<UIMessagePart.Document>().map { it.url }.toMutableSet()
        uris.forEach {
            if (it.url !in existingUrls) {
                newMessage.add(it)
                existingUrls.add(it.url)
            }
        }
        messageContent = newMessage
    }

    /**
     * 仅删除当前输入组件临时新增的本地文件。
     * 编辑历史消息时，原有附件不在这里删除，由会话层统一做差异清理。
     */
    fun shouldDeleteFileOnRemove(part: UIMessagePart): Boolean {
        val url = part.attachmentUrlOrNull() ?: return false
        if (!url.startsWith("file:")) return false
        return !isEditing() || url !in editingAttachmentUrls
    }

    private fun UIMessagePart.attachmentUrlOrNull(): String? {
        return when (this) {
            is UIMessagePart.Image -> this.url
            is UIMessagePart.Video -> this.url
            is UIMessagePart.Audio -> this.url
            is UIMessagePart.Document -> this.url
            else -> null
        }
    }
}
