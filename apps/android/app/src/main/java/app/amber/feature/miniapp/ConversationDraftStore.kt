package app.amber.feature.miniapp

import app.amber.agent.data.db.dao.ConversationDAO
import app.amber.agent.data.db.dao.ConversationDraftDAO
import app.amber.agent.data.db.entity.ConversationDraftEntity
import app.amber.ai.ui.UIMessagePart
import app.amber.core.utils.JsonInstant
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * P3-03: one durable composer draft per conversation, written by the MiniApp
 * host bridge (`host.sendToConversation`, mode=draft / mode=send). The chat UI
 * (ChatVM) restores the draft into the composer when the conversation opens
 * and clears it once a message is actually sent — the "默认写入 composer，不
 * 自动发送" product rule.
 *
 * Attachments are limited to the sandbox-allowed schemes (data:image or
 * https), so the bridge can never smuggle arbitrary local file reads into the
 * composer.
 */
@Serializable
data class ConversationDraft(
    val conversationId: String,
    val draftId: String,
    val text: String,
    val attachments: List<UIMessagePart>,
    val updatedAtMs: Long,
) {
    /** Parts ready for the chat composer (text first, then attachments). */
    fun toParts(): List<UIMessagePart> =
        if (text.isBlank()) attachments else listOf(UIMessagePart.Text(text)) + attachments
}

@OptIn(ExperimentalUuidApi::class)
class ConversationDraftStore(
    private val dao: ConversationDraftDAO,
    private val conversationDao: ConversationDAO,
) {
    /** Whether the target conversation exists (P3-03: 目标会话不存在 → 明确失败). */
    suspend fun conversationExists(conversationId: String): Boolean =
        conversationDao.getConversationById(conversationId) != null

    /**
     * Persist (or replace) the draft for [conversationId]. Fails with
     * `conversation_not_found` when the target conversation does not exist.
     */
    suspend fun save(
        conversationId: String,
        text: String,
        attachments: List<UIMessagePart>,
    ): ConversationDraft {
        if (!conversationExists(conversationId)) {
            throw MiniAppBridgeException("conversation_not_found", "目标会话不存在")
        }
        val now = System.currentTimeMillis()
        val draft = ConversationDraft(
            conversationId = conversationId,
            draftId = Uuid.random().toString(),
            text = text,
            attachments = attachments,
            updatedAtMs = now,
        )
        dao.upsert(
            ConversationDraftEntity(
                conversationId = draft.conversationId,
                draftId = draft.draftId,
                text = draft.text,
                attachmentsJson = JsonInstant.encodeToString(draft.attachments),
                updatedAtMs = now,
            )
        )
        return draft
    }

    suspend fun load(conversationId: String): ConversationDraft? {
        val entity = dao.get(conversationId) ?: return null
        val attachments = runCatching {
            JsonInstant.decodeFromString<List<UIMessagePart>>(entity.attachmentsJson)
        }.getOrDefault(emptyList())
        return ConversationDraft(
            conversationId = entity.conversationId,
            draftId = entity.draftId,
            text = entity.text,
            attachments = attachments,
            updatedAtMs = entity.updatedAtMs,
        )
    }

    suspend fun clear(conversationId: String) {
        dao.delete(conversationId)
    }
}
