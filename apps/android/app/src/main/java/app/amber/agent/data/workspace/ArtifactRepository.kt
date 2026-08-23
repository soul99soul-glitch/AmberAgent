package app.amber.agent.data.workspace

import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.agent.data.db.dao.ArtifactDAO
import app.amber.agent.data.db.dao.ConversationDAO
import app.amber.agent.data.db.dao.MessageNodeDAO
import app.amber.agent.data.db.entity.ArtifactReferenceEntity
import app.amber.core.utils.JsonInstant
import app.amber.feature.workspace.WorkspaceManager
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Workspace Artifact Registry — P3-01.
 *
 * Owns the artifact lifecycle (create / read / update metadata / reparse /
 * delete), source tracking (reverse-open the origin conversation), reference
 * counting (delete confirmation), and content storage.
 *
 * Content is stored as a workspace file referenced by [Artifact.contentLocator]
 * ("大内容使用文件/数据库正文"): written into the SAF workspace when one is
 * configured (durable, survives mirror syncs) and into the workspace mirror
 * otherwise (mirror-only mode — same storage the file browser reads). The
 * mirror file is always kept in sync so the canary/UI file paths resolve.
 *
 * Chat saves (P3-02) record sourceKind=chat, sourceId=conversationId and
 * sourceMessageId; reparse re-extracts content from the persisted source
 * message and fails with a distinguishable error when the source is gone
 * ("来源会话已删除：Artifact 仍可读，来源标记不可用").
 */
@OptIn(ExperimentalUuidApi::class)
class ArtifactRepository(
    private val dao: ArtifactDAO,
    private val workspaceManager: WorkspaceManager,
    private val messageNodeDao: MessageNodeDAO,
    private val conversationDao: ConversationDAO,
) {
    val observeAll: Flow<List<Artifact>> = dao.observeAll().map { list -> list.map { it.toArtifact() } }

    suspend fun get(artifactId: String): Artifact? = dao.getById(artifactId)?.toArtifact()

    suspend fun list(): List<Artifact> = dao.listAll().map { it.toArtifact() }

    /** Distinct workspace ids that already hold artifacts. */
    suspend fun workspaceIds(): List<String> = dao.listWorkspaceIds()

    suspend fun findBySourceMessage(sourceKind: ArtifactSourceKind, sourceMessageId: String): Artifact? =
        dao.findBySourceMessage(sourceKind.id, sourceMessageId)?.toArtifact()

    /**
     * Save a chat message into the workspace (P3-02). Body = message text +
     * attachment references; reasoning is included only when requested.
     *
     * @param existingArtifactId when set, updates that artifact's content and
     *   metadata in place instead of creating a new row (duplicate prompt's
     *   "更新" branch); pass null to create a copy (or the first save).
     */
    suspend fun saveChatMessage(
        message: UIMessage,
        conversationId: String,
        workspaceId: String,
        includeReasoning: Boolean,
        existingArtifactId: String? = null,
    ): Artifact {
        val content = ChatMessageContentBuilder.build(message, includeReasoning)
        val digest = sha256Hex(content)
        val size = content.toByteArray(Charsets.UTF_8).size.toLong()
        val title = ChatMessageContentBuilder.titleOf(message, content)
        val now = System.currentTimeMillis()
        val metadata = ChatMessageContentBuilder.metadata(message, includeReasoning)
        return if (existingArtifactId != null) {
            val existing = requireNotNull(dao.getById(existingArtifactId)) {
                "Artifact not found: $existingArtifactId"
            }
            writeContentFile(existing.contentLocator, content)
            val updated = existing.copy(
                workspaceId = workspaceId,
                title = title,
                contentDigest = digest,
                sizeBytes = size,
                parserVersion = CHAT_PARSER_VERSION,
                parseStatus = ArtifactParseStatus.PARSED.id,
                parseError = null,
                metadataJson = metadata,
                updatedAtMs = now,
            )
            dao.update(updated)
            updated.toArtifact()
        } else {
            val artifactId = Uuid.random().toString()
            val locator = contentLocatorFor(artifactId)
            writeContentFile(locator, content)
            val created = Artifact(
                artifactId = artifactId,
                workspaceId = workspaceId,
                type = TYPE_CHAT_MESSAGE,
                mimeType = "text/markdown",
                title = title,
                sourceKind = ArtifactSourceKind.CHAT,
                sourceId = conversationId,
                sourceRunId = null,
                sourceMessageId = message.id.toString(),
                contentLocator = locator,
                contentDigest = digest,
                sizeBytes = size,
                parserVersion = CHAT_PARSER_VERSION,
                parseStatus = ArtifactParseStatus.PARSED,
                parseError = null,
                metadataJson = metadata,
                createdAtMs = now,
                updatedAtMs = now,
            )
            dao.insert(created.toEntity())
            created
        }
    }

    /** Persist one completed DeepRead result in the registry, updating its
     * existing topic row instead of creating a duplicate on retries. */
    suspend fun saveDeepRead(
        topicId: String,
        title: String,
        content: String,
    ): Artifact {
        val digest = sha256Hex(content)
        val size = content.toByteArray(Charsets.UTF_8).size.toLong()
        val now = System.currentTimeMillis()
        val metadata = buildJsonObject {
            put("sourceTopicId", topicId)
        }.toString()
        val existing = dao.listBySourceKindAndSourceId(
            ArtifactSourceKind.DEEPREAD.id,
            topicId,
        ).firstOrNull()
        if (existing != null) {
            writeContentFile(existing.contentLocator, content)
            val updated = existing.copy(
                title = title,
                type = TYPE_DEEP_READ,
                mimeType = "application/json",
                contentDigest = digest,
                sizeBytes = size,
                parserVersion = DEEPREAD_PARSER_VERSION,
                parseStatus = ArtifactParseStatus.PARSED.id,
                parseError = null,
                metadataJson = metadata,
                updatedAtMs = now,
            )
            dao.update(updated)
            registerReference(updated.artifactId, REF_KIND_DEEPREAD, topicId)
            return updated.toArtifact()
        }

        val artifactId = Uuid.random().toString()
        val created = Artifact(
            artifactId = artifactId,
            workspaceId = DEFAULT_WORKSPACE_ID,
            type = TYPE_DEEP_READ,
            mimeType = "application/json",
            title = title,
            sourceKind = ArtifactSourceKind.DEEPREAD,
            sourceId = topicId,
            sourceRunId = null,
            sourceMessageId = null,
            contentLocator = deepReadContentLocatorFor(artifactId),
            contentDigest = digest,
            sizeBytes = size,
            parserVersion = DEEPREAD_PARSER_VERSION,
            parseStatus = ArtifactParseStatus.PARSED,
            parseError = null,
            metadataJson = metadata,
            createdAtMs = now,
            updatedAtMs = now,
        )
        writeContentFile(created.contentLocator, content)
        dao.insert(created.toEntity())
        registerReference(created.artifactId, REF_KIND_DEEPREAD, topicId)
        return created
    }

    /**
     * P3-04: persist a MiniApp `host.createArtifact` call into the registry.
     * Writes the artifact row + content file with sourceKind=miniapp,
     * sourceId=appId, and registers the reverse reference (refKind=miniapp)
     * so the MiniApp shows up in the delete-confirmation reference count.
     *
     * [effectId] idempotency is enforced by the caller ([MiniAppWorkspaceWriter]
     * → [findByMiniAppEffect]); the effectId itself is kept in metadataJson so
     * dedup survives process restarts without a schema change.
     */
    suspend fun createMiniAppArtifact(
        appId: String,
        effectId: String?,
        title: String,
        content: String,
        type: String,
        mimeType: String,
    ): Artifact {
        val artifactId = Uuid.random().toString()
        val locator = contentLocatorFor(artifactId)
        writeContentFile(locator, content)
        val now = System.currentTimeMillis()
        val created = Artifact(
            artifactId = artifactId,
            workspaceId = DEFAULT_WORKSPACE_ID,
            type = type,
            mimeType = mimeType,
            title = title,
            sourceKind = ArtifactSourceKind.MINIAPP,
            sourceId = appId,
            sourceRunId = null,
            sourceMessageId = null,
            contentLocator = locator,
            contentDigest = sha256Hex(content),
            sizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong(),
            parserVersion = MINIAPP_PARSER_VERSION,
            parseStatus = ArtifactParseStatus.PARSED,
            parseError = null,
            metadataJson = buildMiniAppMetadata(appId, effectId),
            createdAtMs = now,
            updatedAtMs = now,
        )
        dao.insert(created.toEntity())
        registerReference(artifactId, REF_KIND_MINIAPP, appId)
        return created
    }

    /**
     * P3-04: the artifact already persisted for (appId, effectId), if any —
     * duplicate effectId replays reuse it instead of creating a new row.
     */
    suspend fun findByMiniAppEffect(appId: String, effectId: String): Artifact? =
        dao.listBySourceKindAndSourceId(ArtifactSourceKind.MINIAPP.id, appId)
            .map { it.toArtifact() }
            .firstOrNull { artifactMetadataEffectId(it.metadataJson) == effectId }

    private fun artifactMetadataEffectId(metadataJson: String?): String? {
        if (metadataJson.isNullOrBlank()) return null
        return runCatching {
            (JsonInstant.parseToJsonElement(metadataJson) as? JsonObject)
                ?.get("effectId")
                ?.let { it as? JsonPrimitive }
                ?.contentOrNull
        }.getOrNull()
    }

    private fun buildMiniAppMetadata(appId: String, effectId: String?): String =
        buildJsonObject {
            put("sourceAppId", appId)
            effectId?.let { put("effectId", it) }
        }.toString()

    /**
     * Re-run the source-driven parser (P3-01 "Parser 状态与重新解析").
     * Chat artifacts re-extract content from the persisted source message;
     * a missing source yields a distinguishable FAILED state instead of
     * deleting or blanking the artifact.
     */
    suspend fun reparse(artifactId: String): ReparseResult {
        val artifact = dao.getById(artifactId)?.toArtifact() ?: return ReparseResult.Failed(artifactId, "artifact_not_found")
        return when (artifact.sourceKind) {
            ArtifactSourceKind.CHAT -> reparseFromChatSource(artifact)
            else -> ReparseResult.Failed(artifactId, "unsupported_source_kind")
        }
    }

    private suspend fun reparseFromChatSource(artifact: Artifact): ReparseResult {
        val message = findSourceMessage(artifact.sourceId, artifact.sourceMessageId)
        if (message == null) {
            markParseFailure(artifact, "source_unavailable")
            return ReparseResult.Failed(artifact.artifactId, "source_unavailable")
        }
        val includeReasoning = ChatMessageContentBuilder.includeReasoning(artifact.metadataJson)
        val content = ChatMessageContentBuilder.build(message, includeReasoning)
        val now = System.currentTimeMillis()
        writeContentFile(artifact.contentLocator, content)
        val updated = artifact.toEntity().copy(
            title = ChatMessageContentBuilder.titleOf(message, content),
            contentDigest = sha256Hex(content),
            sizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong(),
            parserVersion = CHAT_PARSER_VERSION,
            parseStatus = ArtifactParseStatus.PARSED.id,
            parseError = null,
            updatedAtMs = now,
        )
        dao.update(updated)
        return ReparseResult.Success(artifact.artifactId, CHAT_PARSER_VERSION)
    }

    private suspend fun markParseFailure(artifact: Artifact, error: String) {
        dao.update(
            artifact.toEntity().copy(
                parseStatus = ArtifactParseStatus.FAILED.id,
                parseError = error,
                updatedAtMs = System.currentTimeMillis(),
            )
        )
    }

    /**
     * Delete an artifact: registry row (references cascade), then the content
     * file in the SAF workspace and the mirror.
     */
    suspend fun delete(artifactId: String): Boolean {
        val artifact = dao.getById(artifactId)?.toArtifact() ?: return false
        dao.deleteById(artifactId)
        deleteContentFile(artifact.contentLocator)
        return true
    }

    /** Number of cross-feature references — shown in the delete confirmation. */
    suspend fun referenceCount(artifactId: String): Int = dao.countReferences(artifactId)

    suspend fun registerReference(artifactId: String, refKind: String, refId: String) {
        dao.insertReference(
            ArtifactReferenceEntity(
                artifactId = artifactId,
                refKind = refKind,
                refId = refId,
            )
        )
    }

    /**
     * Whether the artifact's origin is still reachable. A deleted source
     * conversation keeps the artifact readable but marks the source unusable
     * (P3-02 acceptance).
     */
    suspend fun sourceAvailable(artifact: Artifact): Boolean = when (artifact.sourceKind) {
        ArtifactSourceKind.CHAT ->
            artifact.sourceId != null && conversationDao.getConversationById(artifact.sourceId) != null
        else -> false
    }

    /** Read artifact content from SAF (primary) or the mirror fallback. */
    suspend fun readContent(artifact: Artifact): String? = readContent(artifact.contentLocator)

    /** Mirror file backing the content locator (open/share path used by tests). */
    fun contentFile(artifact: Artifact): File? {
        val root = workspaceManager.mirrorDir.canonicalFile
        val target = workspaceManager.mirrorDir.resolve(artifact.contentLocator).canonicalFile
        if (!target.path.startsWith(root.path + File.separator) || !target.isFile) return null
        return target
    }

    private suspend fun readContent(locator: String): String? {
        if (workspaceManager.state.value.configured) {
            runCatching { workspaceManager.readText(locator) }.getOrNull()?.let { return it }
        }
        val mirror = mirrorFile(locator)
        return if (mirror.isFile) mirror.readText() else null
    }

    private suspend fun writeContentFile(locator: String, content: String) {
        val writtenToSaf = if (workspaceManager.state.value.configured) {
            runCatching {
                workspaceManager.writeText(locator, content)
                true
            }.getOrDefault(false)
        } else {
            false
        }
        if (!writtenToSaf) {
            val mirror = mirrorFile(locator)
            mirror.parentFile?.mkdirs()
            mirror.writeText(content)
        }
    }

    private suspend fun deleteContentFile(locator: String) {
        mirrorFile(locator).takeIf { it.isFile }?.delete()
        if (workspaceManager.state.value.configured) {
            runCatching { workspaceManager.deleteFile(locator) }
        }
    }

    private fun mirrorFile(locator: String): File = workspaceManager.mirrorDir.resolve(locator)

    private suspend fun findSourceMessage(conversationId: String?, messageId: String?): UIMessage? {
        if (conversationId == null || messageId == null) return null
        // Scans the conversation's persisted nodes in Kotlin instead of the
        // json_each() SQL helper so the reparse path works on every SQLite
        // build (Robolectric's bundled SQLite has no JSON table functions).
        for (node in messageNodeDao.getNodesOfConversation(conversationId)) {
            val messages = runCatching { JsonInstant.decodeFromString<List<UIMessage>>(node.messages) }
                .getOrNull() ?: continue
            messages.firstOrNull { it.id.toString() == messageId }?.let { return it }
        }
        return null
    }

    private fun contentLocatorFor(artifactId: String): String =
        "artifacts/$artifactId.md"

    private fun deepReadContentLocatorFor(artifactId: String): String =
        "artifacts/$artifactId.json"

    private fun sha256Hex(content: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val DEFAULT_WORKSPACE_ID = "default"
        const val TYPE_CHAT_MESSAGE = "chat_message"
        const val CHAT_PARSER_VERSION = "chat-v1"
        const val MINIAPP_PARSER_VERSION = "miniapp-v1"
        const val DEEPREAD_PARSER_VERSION = "deepread-v1"
        const val TYPE_DEEP_READ = "deep_read"
        const val REF_KIND_MINIAPP = "miniapp"
        const val REF_KIND_DEEPREAD = "deepread"
    }
}

sealed interface ReparseResult {
    val artifactId: String

    data class Success(override val artifactId: String, val parserVersion: String) : ReparseResult
    data class Failed(override val artifactId: String, val reason: String) : ReparseResult
}

/**
 * Turns a chat [UIMessage] into the artifact body: message text plus
 * attachment references (images/documents/videos/audio as markdown links).
 * Reasoning is omitted unless explicitly included (P3-02 acceptance).
 * Used by both the initial save and reparse-from-source.
 */
object ChatMessageContentBuilder {
    @Serializable
    data class AttachmentRef(val name: String, val url: String, val mime: String)

    /** P6-02: tool part whose output carries generated images. */
    const val GENERATE_IMAGE_TOOL_NAME = "generate_image"

    fun build(message: UIMessage, includeReasoning: Boolean): String {
        val parts = mutableListOf<String>()
        message.parts.forEach { part ->
            when (part) {
                is UIMessagePart.Text -> if (part.text.isNotBlank()) parts += part.text
                is UIMessagePart.Image -> {
                    // P6-02: edit results carry the referenced source in the
                    // part metadata — record it so the saved artifact keeps
                    // the source trail (source artifact/message/image).
                    val sourceRef = part.metadata?.get("edit_source_url")
                        ?.let { it as? JsonPrimitive }
                        ?.contentOrNull
                    if (!sourceRef.isNullOrBlank()) {
                        parts += "![图片](${part.url}) (修改自 $sourceRef)"
                    } else {
                        parts += "![图片](${part.url})"
                    }
                }
                is UIMessagePart.Document -> parts += "[${part.fileName}](${part.url})"
                is UIMessagePart.Video -> parts += "[视频](${part.url})"
                is UIMessagePart.Audio -> parts += "[音频](${part.url})"
                is UIMessagePart.MiniApp -> parts += "[MiniApp: ${part.title}]"
                is UIMessagePart.Reasoning ->
                    if (includeReasoning && part.reasoning.isNotBlank()) parts += "> 推理过程\n\n${part.reasoning}"
                // P6-02: generated images live inside the generate_image tool
                // part's output — include them so saving an assistant message
                // to the Workspace captures the generated (or edited) images.
                is UIMessagePart.Tool ->
                    if (part.toolName == GENERATE_IMAGE_TOOL_NAME) {
                        part.output.filterIsInstance<UIMessagePart.Image>().forEach { imagePart ->
                            val sourceRef = imagePart.metadata?.get("edit_source_url")
                                ?.let { it as? JsonPrimitive }
                                ?.contentOrNull
                            if (!sourceRef.isNullOrBlank()) {
                                parts += "![图片](${imagePart.url}) (修改自 $sourceRef)"
                            } else {
                                parts += "![图片](${imagePart.url})"
                            }
                        }
                    }
            }
        }
        return parts.joinToString("\n\n")
    }

    fun titleOf(message: UIMessage, content: String): String {
        content.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(60)
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        val attachment = message.parts.filterIsInstance<UIMessagePart.Document>().firstOrNull()
        return attachment?.fileName ?: "未命名消息"
    }

    /** Domain metadata persisted on the artifact row (not in settings). */
    fun metadata(message: UIMessage, includeReasoning: Boolean): String {
        val attachments = message.parts.flatMap { part ->
            when (part) {
                is UIMessagePart.Image -> listOf(AttachmentRef("图片", part.url, "image/*"))
                is UIMessagePart.Document -> listOf(AttachmentRef(part.fileName, part.url, part.mime))
                is UIMessagePart.Video -> listOf(AttachmentRef("视频", part.url, part.mime))
                is UIMessagePart.Audio ->
                    listOf(AttachmentRef(part.fileName.ifBlank { "音频" }, part.url, part.mime))
                // P6-02: generated images live in the tool part output.
                is UIMessagePart.Tool ->
                    if (part.toolName == GENERATE_IMAGE_TOOL_NAME) {
                        part.output.filterIsInstance<UIMessagePart.Image>()
                            .map { AttachmentRef("图片", it.url, "image/*") }
                    } else {
                        emptyList()
                    }
                else -> emptyList()
            }
        }
        return buildJsonObject {
            put("includeReasoning", includeReasoning)
            put(
                "attachments",
                buildJsonArray {
                    attachments.forEach { ref ->
                        add(
                            buildJsonObject {
                                put("name", ref.name)
                                put("url", ref.url)
                                put("mime", ref.mime)
                            }
                        )
                    }
                }
            )
        }.toString()
    }

    fun includeReasoning(metadataJson: String?): Boolean {
        if (metadataJson.isNullOrBlank()) return false
        return runCatching {
            (JsonInstant.parseToJsonElement(metadataJson) as? JsonObject)
                ?.get("includeReasoning")
                ?.let { it as? JsonPrimitive }
                ?.contentOrNull
                ?.toBoolean()
                ?: false
        }.getOrDefault(false)
    }
}
