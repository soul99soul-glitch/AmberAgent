package me.rerere.rikkahub.data.agent.office.radar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.FeishuDocSnapshotDAO
import me.rerere.rikkahub.data.db.dao.FeishuWatchedDocDAO
import me.rerere.rikkahub.data.db.entity.FeishuDocSnapshotEntity
import me.rerere.rikkahub.data.db.entity.FeishuWatchedDocEntity
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.text.Charsets.UTF_8
import kotlin.uuid.Uuid

data class DocContent(
    val plainText: String,
    val headingList: List<String>,
    val contentHash: String,
)

class FeishuDocumentFetcher(
    private val mcpManager: McpManager,
    private val settingsStore: SettingsStore,
    private val watchedDocDAO: FeishuWatchedDocDAO,
    private val snapshotDAO: FeishuDocSnapshotDAO,
) {
    private val docReaderToolCache = ConcurrentHashMap<String, Pair<String, String>?>()

    suspend fun findFeishuDocReaderTool(): Pair<String, String>? {
        val settings = settingsStore.settingsFlow.value
        return docReaderToolCache.computeIfAbsent("feishu") {
            val feishuServer = settings.mcpServers.firstOrNull { server ->
                val n = server.commonOptions.name
                server.commonOptions.enable &&
                    (n.contains("feishu", ignoreCase = true) || n.contains("lark", ignoreCase = true))
            } ?: return@computeIfAbsent null

            val tool = feishuServer.commonOptions.tools.firstOrNull { tool ->
                val name = tool.name.lowercase()
                val desc = tool.description?.lowercase().orEmpty()
                tool.enable &&
                    (name.contains("doc") || name.contains("read") || name.contains("get") ||
                        name.contains("fetch") || name.contains("文档") || name.contains("读取") ||
                        desc.contains("document") || desc.contains("文档") || desc.contains("读取"))
            } ?: return@computeIfAbsent null

            Pair(feishuServer.id.toString(), tool.name)
        }
    }

    suspend fun fetchDocument(watchedDoc: FeishuWatchedDocEntity): DocContent? {
        val (serverId, toolName) = findFeishuDocReaderTool() ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val args = buildJsonObject {
                    put("url", watchedDoc.docUrl)
                }
                val parts = mcpManager.callConfiguredTool(
                    serverId = serverId,
                    serverName = null,
                    toolName = toolName,
                    args = args as JsonObject,
                )
                val text = parts.joinToString("\n") { part ->
                    when (part) {
                        is me.rerere.ai.ui.UIMessagePart.Text -> part.text
                        else -> ""
                    }
                }
                if (text.isBlank()) return@runCatching null
                val headings = parseHeadings(text)
                DocContent(
                    plainText = text,
                    headingList = headings,
                    contentHash = sha256(text),
                )
            }.getOrNull()
        }
    }

    suspend fun createSnapshot(watchedDocId: String, content: DocContent): FeishuDocSnapshotEntity {
        val headingJson = buildString {
            append("[")
            content.headingList.forEachIndexed { i, h ->
                if (i > 0) append(",")
                append("\"${h.replace("\"", "\\\"")}\"")
            }
            append("]")
        }
        val snapshot = FeishuDocSnapshotEntity(
            id = Uuid.random().toString(),
            watchedDocId = watchedDocId,
            plainText = content.plainText,
            contentHash = content.contentHash,
            headingListJson = headingJson,
            capturedAt = System.currentTimeMillis(),
        )
        snapshotDAO.insert(snapshot)
        snapshotDAO.trimTo(watchedDocId, keepCount = 10)
        return snapshot
    }

    suspend fun checkDocument(watchedDoc: FeishuWatchedDocEntity): FeishuDocSnapshotEntity? {
        val content = fetchDocument(watchedDoc) ?: return null
        val latestSnapshot = snapshotDAO.getLatest(watchedDoc.id)
        if (latestSnapshot?.contentHash == content.contentHash) {
            return null
        }
        return createSnapshot(watchedDoc.id, content)
    }

    private fun parseHeadings(text: String): List<String> {
        val headingPattern = Regex("""^#{1,6}\s+(.+)$""", RegexOption.MULTILINE)
        return headingPattern.findAll(text).map { it.groupValues[1].trim() }.take(80).toList()
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
