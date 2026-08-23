package app.amber.feature.miniapp

import app.amber.ai.ui.UIMessagePart
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** P3-03 receipt returned to the MiniApp for `host.sendToConversation`. */
data class SendToConversationReceipt(
    val conversationId: String,
    /** Draft item id; for mode=send it identifies the item that was written then sent. */
    val itemId: String,
    /** "drafted" | "sent" | "send_rejected" (send blocked — draft kept). */
    val status: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("conversationId", conversationId)
        put("itemId", itemId)
        put("status", status)
    }
}

/**
 * P3-03 real write-back for the MiniApp host bridge:
 *
 * - mode=draft writes text + allowed attachments into the target conversation's
 *   durable composer draft (default, no auto-send);
 * - mode=send additionally triggers a real send through the chat service
 *   (only reachable after the caller cleared the [MiniAppSendGate]); if the
 *   send is rejected the draft is kept so nothing is lost.
 *
 * 目标会话不存在 → structured `conversation_not_found` failure.
 */
class MiniAppConversationWriter(
    private val draftStore: ConversationDraftStore,
    private val sendMessage: suspend (conversationId: String, parts: List<UIMessagePart>) -> Boolean,
) {
    suspend fun writeDraft(
        conversationId: String,
        text: String,
        attachments: List<UIMessagePart>,
    ): SendToConversationReceipt {
        val draft = draftStore.save(conversationId, text, attachments)
        return SendToConversationReceipt(draft.conversationId, draft.draftId, "drafted")
    }

    suspend fun writeAndSend(
        conversationId: String,
        text: String,
        attachments: List<UIMessagePart>,
    ): SendToConversationReceipt {
        val draft = draftStore.save(conversationId, text, attachments)
        val parts = buildList {
            if (text.isNotBlank()) add(UIMessagePart.Text(text))
            addAll(attachments)
        }
        val accepted = sendMessage(conversationId, parts)
        if (!accepted) {
            // Queue/session rejected the message — keep the draft for the user.
            return SendToConversationReceipt(draft.conversationId, draft.draftId, "send_rejected")
        }
        draftStore.clear(conversationId)
        return SendToConversationReceipt(draft.conversationId, draft.draftId, "sent")
    }

    companion object {
        const val MAX_ATTACHMENTS = 4
        const val MAX_ATTACHMENT_URL_CHARS = 2000

        /**
         * Parse the `attachments` param. Only the sandbox-allowed schemes pass
         * (data:image or https — same boundary as MiniAppHtmlValidator images),
         * so no arbitrary local file can be attached via the bridge.
         */
        fun parseAttachments(element: JsonElement?): List<UIMessagePart> {
            if (element == null || element is JsonNull) return emptyList()
            val array = element as? JsonArray ?: return emptyList()
            val parts = mutableListOf<UIMessagePart>()
            for (item in array) {
                if (parts.size >= MAX_ATTACHMENTS) break
                val obj = item as? JsonObject ?: continue
                val url = obj["url"]?.jsonPrimitive?.contentOrNull
                    ?.trim()
                    ?.take(MAX_ATTACHMENT_URL_CHARS)
                    ?.takeIf { it.isAllowedAttachmentUrl() } ?: continue
                val kind = obj["kind"]?.jsonPrimitive?.contentOrNull
                val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()
                val mime = obj["mime"]?.jsonPrimitive?.contentOrNull?.trim()
                parts += when (kind) {
                    "document" -> UIMessagePart.Document(
                        url = url,
                        fileName = name?.takeIf { it.isNotBlank() } ?: "附件",
                        mime = mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream",
                    )
                    else -> UIMessagePart.Image(url)
                }
            }
            return parts
        }

        private fun String.isAllowedAttachmentUrl(): Boolean =
            startsWith("data:image/", ignoreCase = true) || startsWith("https://", ignoreCase = true)
    }
}
