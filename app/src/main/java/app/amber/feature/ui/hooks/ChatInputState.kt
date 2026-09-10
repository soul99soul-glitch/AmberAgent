package app.amber.feature.ui.hooks

import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.amber.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

enum class ChatInputAttachmentKind {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
}

enum class ChatInputAttachmentImportStatus {
    IMPORTING,
    READY,
    FAILED,
}

/**
 * The state for one picker selection.  The conversation id is captured at
 * selection time so a late SAF/copy callback cannot append into the next chat.
 */
data class ChatInputAttachmentImport(
    val id: String,
    val conversationId: String,
    val sourceUri: String,
    val displayName: String,
    val mimeType: String,
    val kind: ChatInputAttachmentKind,
    val status: ChatInputAttachmentImportStatus = ChatInputAttachmentImportStatus.IMPORTING,
    val part: UIMessagePart? = null,
    val errorMessage: String? = null,
    val sizeBytes: Long? = null,
    val textWasTruncated: Boolean = false,
    val readWarning: String? = null,
)

class ChatInputState {
    val textContent = TextFieldState()
    var messageContent by mutableStateOf(listOf<UIMessagePart>())
    var editingMessage by mutableStateOf<Uuid?>(null)
    var attachmentImports by mutableStateOf<List<ChatInputAttachmentImport>>(emptyList())
        private set
    private var editingParts: List<UIMessagePart>? = null
    private var editingAttachmentUrls: Set<String> = emptySet()
    /** Source URIs already accepted this session (dedupe before UUID copy). */
    private var acceptedSourceUris: MutableSet<String> = mutableSetOf()
    private var ownerConversationId: String? = null

    /**
     * Binds this ViewModel-owned composer to the visible conversation.
     * Pending selections are invalidated when the owner changes. The caller
     * receives copied files that can be cleaned up; ordinary pre-existing
     * message parts are deliberately left alone because the page owns them.
     */
    fun bindToConversation(conversationId: String): List<Uri> {
        val previousOwner = ownerConversationId
        if (previousOwner == conversationId) return emptyList()

        val staleFiles = if (previousOwner == null) {
            emptyList()
        } else {
            attachmentImports.mapNotNull { import ->
                import.part?.attachmentUrlOrNull()
                    ?.takeIf { it.startsWith("file:") }
                    ?.let(Uri::parse)
            }
        }
        if (previousOwner != null) {
            val trackedParts = attachmentImports.mapNotNull { it.part }.toSet()
            if (trackedParts.isNotEmpty()) {
                messageContent = messageContent.filterNot { it in trackedParts }
            }
        }
        ownerConversationId = conversationId
        attachmentImports = emptyList()
        acceptedSourceUris = mutableSetOf()
        return staleFiles
    }

    fun isCurrentConversation(conversationId: String): Boolean =
        ownerConversationId == conversationId

    fun hasUnresolvedAttachmentImports(): Boolean =
        attachmentImports.any { it.status != ChatInputAttachmentImportStatus.READY }

    fun clearInput() {
        textContent.setTextAndPlaceCursorAtEnd("")
        messageContent = emptyList()
        editingMessage = null
        editingParts = null
        editingAttachmentUrls = emptySet()
        acceptedSourceUris = mutableSetOf()
        attachmentImports = emptyList()
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

    /** Register a source before copying it into the app-owned upload folder. */
    fun beginAttachmentImport(
        conversationId: String,
        sourceUri: Uri,
        displayName: String,
        mimeType: String,
        kind: ChatInputAttachmentKind,
        sizeBytes: Long? = null,
    ): ChatInputAttachmentImport? {
        if (!isCurrentConversation(conversationId)) return null
        val sourceKey = sourceUri.toString()
        if (!acceptedSourceUris.add(sourceKey)) return null
        val import = ChatInputAttachmentImport(
            id = Uuid.random().toString(),
            conversationId = conversationId,
            sourceUri = sourceKey,
            displayName = displayName,
            mimeType = mimeType,
            kind = kind,
            sizeBytes = sizeBytes,
        )
        attachmentImports = attachmentImports + import
        return import
    }

    fun retryAttachmentImport(
        conversationId: String,
        importId: String,
    ): ChatInputAttachmentImport? {
        if (!isCurrentConversation(conversationId)) return null
        val current = attachmentImports.firstOrNull { it.id == importId }
            ?: return null
        if (current.status != ChatInputAttachmentImportStatus.FAILED) return null
        val retried = current.copy(
            status = ChatInputAttachmentImportStatus.IMPORTING,
            part = null,
            errorMessage = null,
            readWarning = null,
        )
        attachmentImports = attachmentImports.map { if (it.id == importId) retried else it }
        return retried
    }

    /**
     * Completes only if the item and conversation are still current. A false
     * result tells the caller to delete the copied file because the user
     * switched chats or removed the item while the copy was running.
     */
    fun completeAttachmentImport(
        conversationId: String,
        importId: String,
        part: UIMessagePart,
        sizeBytes: Long?,
        textWasTruncated: Boolean = false,
        readWarning: String? = null,
    ): Boolean {
        if (!isCurrentConversation(conversationId)) return false
        val current = attachmentImports.firstOrNull { it.id == importId } ?: return false
        if (current.conversationId != conversationId || current.status != ChatInputAttachmentImportStatus.IMPORTING) {
            return false
        }
        val completed = current.copy(
            status = ChatInputAttachmentImportStatus.READY,
            part = part,
            errorMessage = null,
            sizeBytes = sizeBytes ?: current.sizeBytes,
            textWasTruncated = textWasTruncated,
            readWarning = readWarning,
        )
        attachmentImports = attachmentImports.map { if (it.id == importId) completed else it }
        addPartIfMissing(part)
        return true
    }

    fun failAttachmentImport(
        conversationId: String,
        importId: String,
        errorMessage: String,
        sizeBytes: Long? = null,
    ): Boolean {
        if (!isCurrentConversation(conversationId)) return false
        val current = attachmentImports.firstOrNull { it.id == importId } ?: return false
        if (current.conversationId != conversationId || current.status != ChatInputAttachmentImportStatus.IMPORTING) {
            return false
        }
        attachmentImports = attachmentImports.map {
            if (it.id == importId) {
                it.copy(
                    status = ChatInputAttachmentImportStatus.FAILED,
                    errorMessage = errorMessage,
                    sizeBytes = sizeBytes ?: it.sizeBytes,
                )
            } else {
                it
            }
        }
        return true
    }

    /** Removes a tracked item and releases its source URI for a later retry. */
    fun removeAttachmentImport(importId: String): ChatInputAttachmentImport? {
        val removed = attachmentImports.firstOrNull { it.id == importId } ?: return null
        attachmentImports = attachmentImports.filterNot { it.id == importId }
        acceptedSourceUris.remove(removed.sourceUri)
        removed.part?.let { part -> messageContent = messageContent.filterNot { it == part } }
        return removed
    }

    /**
     * Discards unsent attachment parts and returns their local URIs for cleanup.
     * Existing attachments in an edit are protected by [shouldDeleteFileOnRemove].
     * Sending still uses [clearInput], so a successful send never drains files.
     */
    fun drainAttachmentFilesForDiscard(): List<Uri> {
        val discardableParts = messageContent.filter { shouldDeleteFileOnRemove(it) }
        val discardableSet = discardableParts.toSet()
        val files = discardableParts.mapNotNull { part ->
            part.attachmentUrlOrNull()
                ?.takeIf { it.startsWith("file:") }
                ?.let(Uri::parse)
        }.distinct()
        if (discardableSet.isNotEmpty()) {
            messageContent = messageContent.filterNot { it in discardableSet }
        }
        attachmentImports = emptyList()
        acceptedSourceUris = mutableSetOf()
        return files
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

    private fun addPartIfMissing(part: UIMessagePart) {
        if (part !in messageContent) messageContent = messageContent + part
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
