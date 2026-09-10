package app.amber.core.conversation.exchange

import android.content.ContentResolver
import android.net.Uri
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.core.utils.JsonInstant
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.amber.ai.ui.UIMessageAnnotation
import app.amber.ai.ui.UIMessagePart

/**
 * The sidecar used by iOS when a conversation export contains fork/thread
 * relationships. Android can decode and validate it, but its local
 * conversation database does not own the same edge contract yet; the import
 * service therefore rejects a non-empty sidecar before any write.
 */
@Serializable
data class ConversationExchangeThreadEdge(
    val childThreadId: String,
    val parentThreadId: String,
    val agentPath: String,
    val nickname: String? = null,
    val roleAssistantId: String? = null,
    val forkTurns: String,
    val status: String,
    val createdAt: Long,
)

data class ConversationExchangeArchive(
    val conversations: List<Conversation>,
    /** Null means that the archive did not contain the iOS sidecar. */
    val threadEdges: List<ConversationExchangeThreadEdge>?,
    /** Local file references found in media/document parts; bytes are not bundled. */
    val attachmentReferences: Set<String>,
)

data class ConversationExchangeImportResult(
    val importedCount: Int,
    val overwrittenCount: Int,
    val attachmentReferenceCount: Int,
)

data class ConversationExchangeConflict(
    val id: kotlin.uuid.Uuid,
    val incomingTitle: String,
    val existingTitle: String,
)

data class ConversationExchangeImportPreview(
    val conversationCount: Int,
    val newConversationCount: Int,
    val conflicts: List<ConversationExchangeConflict>,
    val attachmentReferenceCount: Int,
) {
    val conflictIds: Set<kotlin.uuid.Uuid> get() = conflicts.mapTo(linkedSetOf()) { it.id }
}

data class ConversationExchangePendingImport(
    val bytes: ByteArray,
    val preview: ConversationExchangeImportPreview,
)

class ConversationExchangeException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * Minimal cross-platform conversation archive codec.
 *
 * The wire format intentionally matches IOSSyncBackup.conversationsZip:
 * one root-level `<conversation UUID>.json` per conversation and an optional
 * root-level `thread-edges.json`. There is no index, manifest, encryption, or
 * attachment payload in this format.
 */
object ConversationExchangeCodec {
    const val MIME_TYPE = "application/vnd.amberagent.conversations+zip"
    const val FILE_EXTENSION = "amberconversations"
    const val THREAD_EDGES_ENTRY = "thread-edges.json"

    private val metadataEntries = setOf("index.json", "list-previews.json", "list-icons.json")
    private val sidecarJson = JsonInstant

    fun suggestedFileName(): String = "amber-conversations.$FILE_EXTENSION"

    fun countConversationEntries(bytes: ByteArray): Int = decode(bytes).conversations.size

    fun encode(
        conversations: List<Conversation>,
        threadEdges: List<ConversationExchangeThreadEdge>? = null,
    ): ByteArray {
        if (conversations.isEmpty()) {
            throw ConversationExchangeException("没有可导出的会话")
        }

        val sortedConversations = conversations.sortedBy { it.id.toString() }
        val conversationIds = sortedConversations.map { it.id.toString() }.toSet()
        if (conversationIds.size != sortedConversations.size) {
            throw ConversationExchangeException("导出内容包含重复的会话 ID")
        }
        sortedConversations.forEach(::validateConversation)
        val normalizedEdges = threadEdges?.let { validateAndNormalizeEdges(it, conversationIds) }

        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                sortedConversations.forEach { conversation ->
                    val document = JsonInstant.encodeToString(Conversation.serializer(), conversation)
                    putStoredEntry(zip, "${conversation.id}.json", document.toByteArray(Charsets.UTF_8))
                }
                normalizedEdges?.let { edges ->
                    val document = sidecarJson.encodeToString(edges)
                    putStoredEntry(zip, THREAD_EDGES_ENTRY, document.toByteArray(Charsets.UTF_8))
                }
            }
            output.toByteArray()
        }
    }

    fun decode(bytes: ByteArray): ConversationExchangeArchive {
        if (bytes.isEmpty()) {
            throw ConversationExchangeException("会话交换文件为空")
        }

        val conversations = ArrayList<Conversation>()
        val conversationIds = HashSet<String>()
        var threadEdges: List<ConversationExchangeThreadEdge>? = null
        val attachmentReferences = linkedSetOf<String>()
        val entryNames = HashSet<String>()

        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val entryName = entry.name
                    if (!entryNames.add(entryName)) {
                        throw ConversationExchangeException("交换文件包含重复条目：$entryName")
                    }
                    validateEntryName(entryName)
                    if (entry.isDirectory) {
                        throw ConversationExchangeException("交换文件不支持目录条目：$entryName")
                    }

                    val lowerName = entryName.lowercase()
                    val document = zip.readBytes().toString(Charsets.UTF_8)
                    when {
                        lowerName == THREAD_EDGES_ENTRY -> {
                            if (threadEdges != null) {
                                throw ConversationExchangeException("交换文件包含重复的线程关系条目")
                            }
                            threadEdges = decodeEdges(document, entryName)
                        }

                        lowerName in metadataEntries -> {
                            // iOS excludes these metadata files. Ignore them so a
                            // user-selected iOS storage ZIP remains importable.
                        }

                        !lowerName.endsWith(".json") -> {
                            // The iOS reader only considers JSON entries.
                        }

                        else -> {
                            val conversation = decodeConversation(document, entryName)
                            val id = conversation.id.toString()
                            val fileId = entryName.substringBeforeLast('.', missingDelimiterValue = entryName)
                            if (!fileId.equals(id, ignoreCase = true)) {
                                throw ConversationExchangeException(
                                    "会话文件名与 JSON ID 不一致：$entryName -> $id",
                                )
                            }
                            if (!conversationIds.add(id)) {
                                throw ConversationExchangeException("交换文件包含重复的会话 ID：$id")
                            }
                            validateConversation(conversation)
                            conversations += conversation
                            attachmentReferences += collectAttachmentReferences(conversation)
                        }
                    }
                    zip.closeEntry()
                }
            }
        } catch (error: ConversationExchangeException) {
            throw error
        } catch (error: Throwable) {
            throw ConversationExchangeException("无法读取会话交换文件：${error.message.orEmpty()}", error)
        }

        if (conversations.isEmpty()) {
            throw ConversationExchangeException("交换文件不包含可导入的会话")
        }
        val normalizedEdges = threadEdges?.let { validateAndNormalizeEdges(it, conversationIds) }
        return ConversationExchangeArchive(
            conversations = conversations.sortedBy { it.id.toString() },
            threadEdges = normalizedEdges,
            attachmentReferences = attachmentReferences,
        )
    }

    private fun putStoredEntry(zip: ZipOutputStream, name: String, data: ByteArray) {
        val checksum = CRC32().apply { update(data) }.value
        zip.putNextEntry(
            ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = data.size.toLong()
                compressedSize = data.size.toLong()
                crc = checksum
            },
        )
        zip.write(data)
        zip.closeEntry()
    }

    private fun validateEntryName(entryName: String) {
        if (entryName.isBlank() || entryName.contains('\\') || entryName.startsWith('/') ||
            entryName.contains("//") || entryName.split('/').size != 1 ||
            entryName == "." || entryName == ".."
        ) {
            throw ConversationExchangeException("交换文件包含不安全条目名：$entryName")
        }
    }

    private fun decodeConversation(document: String, entryName: String): Conversation {
        val rawMemoryMode = runCatching {
            JsonInstant.parseToJsonElement(document).jsonObject["memoryMode"]
        }.getOrElse { error ->
            throw ConversationExchangeException("无法解析会话条目：$entryName", error)
        }
        if (rawMemoryMode != null) {
            val memoryMode = runCatching { rawMemoryMode.jsonPrimitive.contentOrNull }
                .getOrNull()
            if (!memoryMode.equals("enabled", ignoreCase = true)) {
                throw ConversationExchangeException(
                    "会话 $entryName 使用 memoryMode=${memoryMode ?: "null/无效值"}，Android 当前无法保留该记忆状态，已拒绝导入",
                )
            }
        }
        return runCatching {
            JsonInstant.decodeFromString(Conversation.serializer(), document)
        }.getOrElse { error ->
            throw ConversationExchangeException("无法解析会话条目：$entryName", error)
        }
    }

    private fun decodeEdges(
        document: String,
        entryName: String,
    ): List<ConversationExchangeThreadEdge> {
        return runCatching {
            sidecarJson.decodeFromString<List<ConversationExchangeThreadEdge>>(document)
        }.getOrElse { error ->
            throw ConversationExchangeException("无法解析线程关系条目：$entryName", error)
        }
    }

    private fun validateAndNormalizeEdges(
        edges: List<ConversationExchangeThreadEdge>,
        conversationIds: Set<String>,
    ): List<ConversationExchangeThreadEdge> {
        val normalized = edges.map { edge ->
            val child = canonicalUuid(edge.childThreadId, "childThreadId")
            val parent = canonicalUuid(edge.parentThreadId, "parentThreadId")
            if (child == parent) {
                throw ConversationExchangeException("线程关系不能指向自身：$child")
            }
            if (child !in conversationIds) {
                throw ConversationExchangeException("线程关系的子会话不存在：$child")
            }
            edge.copy(
                childThreadId = child,
                parentThreadId = parent,
                roleAssistantId = edge.roleAssistantId,
            )
        }.sortedBy { it.childThreadId }
        val childIds = HashSet<String>()
        normalized.forEach { edge ->
            if (!childIds.add(edge.childThreadId)) {
                throw ConversationExchangeException("线程关系包含重复的子会话：${edge.childThreadId}")
            }
        }

        val parentByChild = normalized.associate { it.childThreadId to it.parentThreadId }
        normalized.forEach { edge ->
            val visited = HashSet<String>()
            var current: String? = edge.childThreadId
            while (current != null && current in parentByChild) {
                if (!visited.add(current)) {
                    throw ConversationExchangeException("线程关系包含循环：${edge.childThreadId}")
                }
                current = parentByChild[current]
            }
        }
        return normalized
    }

    private fun canonicalUuid(value: String, field: String): String {
        return runCatching { kotlin.uuid.Uuid.parse(value).toString() }.getOrElse { error ->
            throw ConversationExchangeException("线程关系字段 $field 不是有效 UUID：$value", error)
        }
    }

    private fun validateConversation(conversation: Conversation) {
        conversation.messageNodes.flatMap { node -> node.messages }.forEach { message ->
            if (message.annotations.any { it is UIMessageAnnotation.GenerationInterrupted }) {
                throw ConversationExchangeException(
                    "会话包含 Android 独有的 generation_interrupted 标记，当前 iOS 版本无法读取",
                )
            }
            message.parts.forEach(::validatePart)
        }
    }

    private fun validatePart(part: UIMessagePart) {
        when (part) {
            is UIMessagePart.Image -> rejectInlineData(part.url)
            is UIMessagePart.Video -> rejectInlineData(part.url)
            is UIMessagePart.Audio -> rejectInlineData(part.url)
            is UIMessagePart.Document -> rejectInlineData(part.url)
            is UIMessagePart.Tool -> part.output.forEach(::validatePart)
            else -> Unit
        }
    }

    private fun rejectInlineData(url: String) {
        if (url.startsWith("data:", ignoreCase = true)) {
            throw ConversationExchangeException(
                "会话包含内嵌附件数据，交换格式只支持附件引用（请先保存文件后再导出）",
            )
        }
    }

    private fun collectAttachmentReferences(conversation: Conversation): Set<String> =
        conversation.messageNodes
            .flatMap { node -> node.messages }
            .flatMap { message -> message.parts }
            .flatMap(::collectPartReferences)
            .toSet()

    private fun collectPartReferences(part: UIMessagePart): List<String> = when (part) {
        is UIMessagePart.Image -> listOf(part.url).filter(::isFileReference)
        is UIMessagePart.Video -> listOf(part.url).filter(::isFileReference)
        is UIMessagePart.Audio -> listOf(part.url).filter(::isFileReference)
        is UIMessagePart.Document -> listOf(part.url).filter(::isFileReference)
        is UIMessagePart.Tool -> part.output.flatMap(::collectPartReferences)
        else -> emptyList()
    }

    private fun isFileReference(value: String): Boolean =
        value.startsWith("file://", ignoreCase = true)
}

/** Repository and file-picker boundary for the lightweight exchange format. */
class ConversationExchangeService(
    private val conversationRepository: ConversationRepository,
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
) {
    suspend fun exportAll(threadEdges: List<ConversationExchangeThreadEdge>? = null): ByteArray =
        ConversationExchangeCodec.encode(
            conversations = conversationRepository.getAllConversationsForExchange(),
            threadEdges = threadEdges,
        )

    suspend fun inspect(bytes: ByteArray): ConversationExchangeImportPreview {
        val archive = ConversationExchangeCodec.decode(bytes)
        rejectUnsupportedImportData(archive)
        val conflicts = archive.conversations.mapNotNull { incoming ->
            conversationRepository.getConversationTitleById(incoming.id)?.let { existingTitle ->
                ConversationExchangeConflict(
                    id = incoming.id,
                    incomingTitle = incoming.title,
                    existingTitle = existingTitle,
                )
            }
        }
        return ConversationExchangeImportPreview(
            conversationCount = archive.conversations.size,
            newConversationCount = archive.conversations.size - conflicts.size,
            conflicts = conflicts,
            attachmentReferenceCount = archive.attachmentReferences.size,
        )
    }

    suspend fun import(
        bytes: ByteArray,
        overwriteExisting: Boolean,
        expectedConflictIds: Set<kotlin.uuid.Uuid>? = null,
    ): ConversationExchangeImportResult {
        val archive = ConversationExchangeCodec.decode(bytes)
        rejectUnsupportedImportData(archive)
        suspend fun importUnderWriteGate(): ConversationExchangeImportResult {
            val currentConflictIds = archive.conversations.mapNotNull { conversation ->
                conversation.id.takeIf { conversationRepository.existsConversationById(it) }
            }.toSet()
            if (expectedConflictIds != null && currentConflictIds != expectedConflictIds) {
                throw ConversationExchangeException("会话在预览后发生变化，请重新选择交换文件")
            }
            if (currentConflictIds.isNotEmpty() && !overwriteExisting) {
                throw ConversationExchangeException(
                    "交换文件包含 ${currentConflictIds.size} 个同 ID 会话，请先确认覆盖",
                )
            }
            conversationRepository.upsertConversationsForExchange(archive.conversations)
            return ConversationExchangeImportResult(
                importedCount = archive.conversations.size,
                overwrittenCount = currentConflictIds.size,
                attachmentReferenceCount = archive.attachmentReferences.size,
            )
        }
        return restoreWriteGate?.withRestore { importUnderWriteGate() }
            ?: importUnderWriteGate()
    }

    private fun rejectUnsupportedImportData(archive: ConversationExchangeArchive) {
        if (!archive.threadEdges.isNullOrEmpty()) {
            throw ConversationExchangeException(
                "交换文件包含 ${archive.threadEdges.size} 条线程关系，Android 当前线程图与 iOS thread-edge 契约不同，已拒绝导入",
            )
        }
    }
}

class ConversationExchangeFileHandler(
    private val contentResolver: ContentResolver,
    private val service: ConversationExchangeService,
) {
    suspend fun exportAllToUri(
        uri: Uri,
        threadEdges: List<ConversationExchangeThreadEdge>? = null,
    ): ConversationExchangeExportResult = withContext(Dispatchers.IO) {
        val bytes = service.exportAll(threadEdges)
        contentResolver.openOutputStream(uri)?.use { output -> output.write(bytes) }
            ?: throw ConversationExchangeException("无法写入选定的位置")
        ConversationExchangeExportResult(
            conversationCount = ConversationExchangeCodec.countConversationEntries(bytes),
            byteCount = bytes.size,
        )
    }

    suspend fun prepareImportFromUri(uri: Uri): ConversationExchangePendingImport = withContext(Dispatchers.IO) {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw ConversationExchangeException("无法读取选定的文件")
        ConversationExchangePendingImport(
            bytes = bytes,
            preview = service.inspect(bytes),
        )
    }

    suspend fun importPrepared(
        pending: ConversationExchangePendingImport,
    ): ConversationExchangeImportResult = withContext(Dispatchers.IO) {
        service.import(
            bytes = pending.bytes,
            overwriteExisting = true,
            expectedConflictIds = pending.preview.conflictIds,
        )
    }
}

data class ConversationExchangeExportResult(
    val conversationCount: Int,
    val byteCount: Int,
)
