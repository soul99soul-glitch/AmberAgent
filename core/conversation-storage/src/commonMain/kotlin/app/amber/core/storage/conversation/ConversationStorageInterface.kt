package app.amber.core.storage.conversation

import app.amber.core.model.Conversation
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * 摘要：用于会话列表展示。从 [Conversation] 派生，本身不落盘语义——
 * index.json 只是 [ConversationStorageInterface] 写盘时同步刷新的派生缓存。
 *
 * `messageCount` 取 `messageNodes.size`（与 Conversation 持有的节点数一致，
 * 不展开分支内 UIMessage 数量——分支切换不改变列表展示）。
 *
 * @Serializable：index.json 整列表用 List<ConversationSummary> 编解码。
 * [Instant] 字段沿用 kotlin.time.Instant；序列化插件对 kotlinx 与 kotlin.time
 * 都能生成默认 ISO 字符串序列化器（与 Conversation 的 createAt/updateAt 一致）。
 */
@Serializable
data class ConversationSummary(
    val id: Uuid,
    val title: String,
    val assistantId: Uuid,
    @Serializable(with = app.amber.ai.util.InstantSerializer::class)
    val createAt: Instant,
    @Serializable(with = app.amber.ai.util.InstantSerializer::class)
    val updateAt: Instant,
    val isPinned: Boolean,
    val messageCount: Int,
)

/**
 * 会话持久化纯接口。iOS 端通过 [JsonConversationStorage] + [ConversationFile]
 * 在 Documents/conversations/ 下做文件 JSON 持久化；JVM target 仅为编译通过。
 *
 * 线程/协程模型：所有方法为 suspend，调用方需自行选择 Dispatcher。
 * 实现内部必须串行化文件与 index 的读改写窗口，避免 metadata 与消息树写入互相覆盖。
 */
interface ConversationStorageInterface {

    /** 列出所有会话摘要（按 updateAt 倒序、isPinned 优先）。读 index.json。 */
    @Throws(Throwable::class)
    suspend fun listSummaries(): List<ConversationSummary>

    /** 加载完整会话；不存在返回 null。读 {id}.json。 */
    @Throws(Throwable::class)
    suspend fun loadConversation(id: Uuid): Conversation?

    /**
     * upsert 会话内容：不存在则新建；已存在时，消息树/建议等内容字段随入参更新，
     * 但标题和置顶状态由 [updateMetadata] 拥有，不被旧快照反向覆盖。
     */
    @Throws(Throwable::class)
    suspend fun saveConversation(conversation: Conversation)

    /**
     * 批量导入完整 Conversation JSON。实现必须先校验整批 payload，再覆盖同 ID
     * 会话并重建派生 index；不在导入批次中的已有会话保持不变。
     */
    @Throws(Throwable::class)
    suspend fun importConversations(serializedConversations: List<String>)

    /** 删除 {id}.json，并从 index.json 移除对应条目。不存在则空操作。 */
    @Throws(Throwable::class)
    suspend fun deleteConversation(id: Uuid)

    /**
     * 仅更新标题/置顶状态（partial update）。传 null 表示保持原值。
     * 实现：load → copy → save（简单且与完整 save 共享原子写路径）。
     */
    @Throws(Throwable::class)
    suspend fun updateMetadata(id: Uuid, title: String? = null, isPinned: Boolean? = null)

    /** 仅当当前标题仍等于 [expectedTitle] 时更新标题；比较与写入必须处于同一锁内。 */
    @Throws(Throwable::class)
    suspend fun updateTitleIfCurrentTitleMatches(id: Uuid, title: String, expectedTitle: String): Boolean
}

/** 从完整 [Conversation] 派生摘要。供 save / index 重建复用。 */
fun Conversation.toSummary(): ConversationSummary = ConversationSummary(
    id = id,
    title = title,
    assistantId = assistantId,
    createAt = createAt,
    updateAt = updateAt,
    isPinned = isPinned,
    messageCount = messageNodes.size,
)
