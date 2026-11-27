package app.amber.feature.ui.components.ai

import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.isEmptyInputMessage
import app.amber.feature.ui.hooks.ChatInputState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.uuid.Uuid

/** Owns one composer submission while image validation can suspend. Called on the UI dispatcher. */
internal class ChatInputSubmissionOwner(private val input: ChatInputState) {
    private var inFlight = false

    fun confirmEdit(
        regenerate: Boolean,
        send: (parts: List<UIMessagePart>, messageId: Uuid, regenerate: Boolean) -> Boolean,
    ): Boolean {
        if (inFlight || input.hasUnresolvedAttachmentImports()) return false
        val snapshot = input.captureSubmission() ?: return false
        val messageId = snapshot.editingMessage ?: return false
        if (snapshot.parts.isEmptyInputMessage()) return false
        inFlight = true
        return try {
            val accepted = send(snapshot.parts, messageId, regenerate)
            if (accepted) input.consumeAcceptedSubmission(snapshot)
            accepted
        } finally {
            inFlight = false
        }
    }

    fun sendWidgetAction(
        parts: List<UIMessagePart>,
        send: (List<UIMessagePart>) -> Boolean,
    ): Boolean {
        return send(parts)
    }

    suspend fun submit(
        conversationId: String,
        validate: suspend (List<UIMessagePart>) -> Boolean,
        send: suspend (parts: List<UIMessagePart>, editingMessageId: Uuid?) -> Boolean,
    ): Boolean {
        if (inFlight || !input.isCurrentConversation(conversationId)) return false
        currentCoroutineContext().ensureActive()
        if (input.hasUnresolvedAttachmentImports()) return false
        val snapshot = input.captureSubmission() ?: return false
        inFlight = true
        return try {
            if (!validate(snapshot.parts)) return false
            currentCoroutineContext().ensureActive()
            if (!input.canSubmitCapturedAttachments(snapshot)) return false

            val accepted = send(snapshot.parts, snapshot.editingMessage)
            if (accepted) input.consumeAcceptedSubmission(snapshot)
            accepted
        } finally {
            inFlight = false
        }
    }
}
