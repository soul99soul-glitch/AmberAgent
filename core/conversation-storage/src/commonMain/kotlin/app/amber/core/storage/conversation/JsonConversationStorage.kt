package app.amber.core.storage.conversation

import app.amber.core.agent.utils.JsonInstant
import app.amber.core.model.Conversation
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

private val SUMMARY_STORAGE_JSON: Json = JsonInstant

/**
 * 文件 JSON 持久化的实现。目录布局（PLAN_CONVERSATION_PERSISTENCE.md 已定）：
 *
 *   {baseDir}/index.json          # List<ConversationSummary>，派生缓存
 *   {baseDir}/{conversationId}.json   # 完整 Conversation
 *
 * 写策略：每个 save/delete 同时落 {id}.json 与 index.json；index 损坏可从
 * 所有 {id}.json 扫描重建（[rebuildIndex]）。
 *
 * `baseDir` 由构造方传入（iOS 传 NSDocumentDirectory/conversations，JVM 传
 * 临时目录用于测试）。
 *
 * ## 并发契约（重要）
 * `upsertIndex` / `removeFromIndex` 对 index.json 做 read-modify-write，**非线程安全**。
 * iOS 调用方（IOSConversationStore）必须在 `@MainActor` 上串行调用本类的 suspend 方法；
 * 任何后台 `Task.detached` / 并发写都会丢失 index 更新。
 * 单写者假设是 Phase 2 的硬约束，违反会导致 index 漂移（但 listSummaries 有 rebuild
 * 兜底，最终一致）。
 */
class JsonConversationStorage(
    private val baseDir: ConversationFile,
) : ConversationStorageInterface {

    private val indexFile: ConversationFile get() = baseDir.child(INDEX_FILENAME)

    init {
        // 确保目录存在；不存在则静默创建（首次启动/全新安装场景）。
        baseDir.mkdirs()
    }

    override suspend fun listSummaries(): List<ConversationSummary> {
        ensureBaseDir()
        val cached = readIndex()
        if (cached != null) {
            return orderSummaries(cached)
        }
        // index 损坏或缺失：从 {id}.json 扫描重建。
        val rebuilt = rebuildIndex()
        return orderSummaries(rebuilt)
    }

    override suspend fun loadConversation(id: Uuid): Conversation? {
        ensureBaseDir()
        val file = conversationFile(id)
        val text = file.readText() ?: return null
        return runCatching {
            JsonInstant.decodeFromString<Conversation>(text)
        }.getOrElse {
            // 单条会话损坏不应让整个列表崩；返回 null 让上层视为「不存在」。
            // 损坏文件可由用户在 Phase 3 UI 上删除恢复。
            null
        }
    }

    override suspend fun saveConversation(conversation: Conversation) {
        ensureBaseDir()
        val text = JsonInstant.encodeToString(conversation)
        conversationFile(conversation.id).writeText(text)
        upsertIndex(conversation.toSummary())
    }

    override suspend fun deleteConversation(id: Uuid) {
        ensureBaseDir()
        conversationFile(id).delete()
        removeFromIndex(id)
    }

    override suspend fun updateMetadata(id: Uuid, title: String?, isPinned: Boolean?) {
        val current = loadConversation(id) ?: return
        val updated = current.copy(
            title = title ?: current.title,
            isPinned = isPinned ?: current.isPinned,
        )
        saveConversation(updated)
    }

    // ---- 内部：index 维护 ----

    private fun readIndex(): List<ConversationSummary>? {
        val text = indexFile.readText() ?: return null
        return runCatching {
            JsonInstant.decodeFromString<List<ConversationSummary>>(text)
        }.getOrNull()
    }

    private fun writeIndex(summaries: List<ConversationSummary>) {
        val text = SUMMARY_STORAGE_JSON.encodeToString(summaries)
        indexFile.writeText(text)
    }

    private fun upsertIndex(summary: ConversationSummary) {
        val current = readIndex().orEmpty()
        val without = current.filterNot { it.id == summary.id }
        writeIndex(without + summary)
    }

    private fun removeFromIndex(id: Uuid) {
        val current = readIndex().orEmpty()
        val without = current.filterNot { it.id == id }
        if (without.size != current.size) {
            writeIndex(without)
        }
    }

    /**
     * 扫描 baseDir 下所有 {id}.json，逐个 load 出 Conversation 取摘要。
     * 用于 index 损坏/首次迁移恢复。会顺带把重建结果落盘，避免下次再扫。
     */
    private suspend fun rebuildIndex(): List<ConversationSummary> {
        val files = baseDir.listFilesByExtension("json")
            .filterNot { it.path.endsWith(INDEX_FILENAME) }
        val summaries = files.mapNotNull { file ->
            val text = file.readText() ?: return@mapNotNull null
            runCatching {
                JsonInstant.decodeFromString<Conversation>(text)
            }.getOrNull()?.toSummary()
        }
        if (summaries.isNotEmpty()) writeIndex(summaries)
        return summaries
    }

    // ---- 排序：置顶优先，再按 updateAt 倒序 ----

    private fun orderSummaries(summaries: List<ConversationSummary>): List<ConversationSummary> =
        summaries.sortedWith(
            compareByDescending<ConversationSummary> { it.isPinned }
                .thenByDescending { it.updateAt }
        )

    // ---- 路径助手 ----

    private fun ensureBaseDir() {
        baseDir.mkdirs()
    }

    private fun conversationFile(id: Uuid): ConversationFile =
        baseDir.child("${id}.json")

    private companion object {
        const val INDEX_FILENAME = "index.json"
    }
}
