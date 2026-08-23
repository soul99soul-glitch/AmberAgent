package app.amber.agent.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * P3-03: durable composer draft for a conversation, written by the MiniApp
 * host bridge (`host.sendToConversation`, mode=draft). One row per
 * conversation; the chat UI restores it into the composer and clears it once
 * the message is actually sent.
 */
@Entity(tableName = "conversation_draft")
data class ConversationDraftEntity(
    @PrimaryKey
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    /** Receipt id returned to the MiniApp ("draft item ID"). */
    @ColumnInfo(name = "draft_id") val draftId: String,
    @ColumnInfo(name = "text") val text: String,
    /** Serialized list of allowed attachment UIMessageParts (data:/https only). */
    @ColumnInfo(name = "attachments_json") val attachmentsJson: String,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)
