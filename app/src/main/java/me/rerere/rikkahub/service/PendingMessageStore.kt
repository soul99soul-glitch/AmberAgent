package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.uuid.Uuid

private const val TAG = "PendingMessageStore"
private const val BASE_DIR = "amberagent/message-queue"

/**
 * Persistence for pending user messages (the queue waiting to be sent during
 * an in-flight generation) and an append-only audit log of queue events.
 *
 * Extracted from ChatService in M1.3.1 — pure file IO concern, no
 * cross-cutting state. ChatService holds the in-memory ConversationSession;
 * this class owns the on-disk mirror so the queue survives process death.
 *
 * - `<filesDir>/amberagent/message-queue/<conversationId>.json` — current queue
 * - `<filesDir>/amberagent/message-queue/<conversationId>.events.jsonl` — audit
 */
class PendingMessageStore(
    private val context: Context,
    private val json: Json,
) {
    fun load(conversationId: Uuid): List<PendingUserMessage> {
        val file = queueFile(conversationId)
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<PendingUserMessage>>(file.readText())
        }.onFailure {
            Log.w(TAG, "Failed to load pending user messages for $conversationId", it)
        }.getOrDefault(emptyList())
    }

    fun persist(conversationId: Uuid, messages: List<PendingUserMessage>) {
        runCatching {
            val file = queueFile(conversationId)
            if (messages.isEmpty()) {
                file.delete()
            } else {
                file.parentFile?.mkdirs()
                file.writeText(json.encodeToString(messages))
            }
        }.onFailure {
            Log.w(TAG, "Failed to persist pending user messages for $conversationId", it)
        }
    }

    fun recordEvent(
        conversationId: Uuid,
        event: String,
        messageId: String? = null,
        count: Int? = null,
        detail: String? = null,
    ) {
        runCatching {
            val file = auditFile(conversationId)
            file.parentFile?.mkdirs()
            file.appendText(
                buildJsonObject {
                    put("created_at_ms", System.currentTimeMillis())
                    put("conversation_id", conversationId.toString())
                    put("event", event)
                    messageId?.let { put("message_id", it) }
                    count?.let { put("count", it) }
                    detail?.let { put("detail", it) }
                }.toString() + "\n"
            )
        }.onFailure {
            Log.w(TAG, "Failed to write pending message audit for $conversationId", it)
        }
    }

    private fun queueFile(conversationId: Uuid): File =
        File(context.filesDir, BASE_DIR).resolve("$conversationId.json")

    private fun auditFile(conversationId: Uuid): File =
        File(context.filesDir, BASE_DIR).resolve("$conversationId.events.jsonl")
}
