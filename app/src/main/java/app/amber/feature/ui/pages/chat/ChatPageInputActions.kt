package app.amber.feature.ui.pages.chat

import app.amber.agent.R
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.isEmptyInputMessage
import app.amber.core.ai.tools.parseDeepReadSlashCommand
import app.amber.core.service.PendingUserMessageMode
import app.amber.feature.ui.components.ai.ChatInputSubmissionOwner
import app.amber.feature.ui.hooks.ChatInputAttachmentImportStatus
import app.amber.feature.ui.hooks.ChatInputState
import kotlin.uuid.Uuid

/** Page-level admission and edit actions; the composer retains its one submission owner. */
internal class ChatPageInputActions(
    private val input: ChatInputState,
    private val submission: ChatInputSubmissionOwner,
    private val configurationIssue: ChatConfigurationIssue?,
    private val generating: Boolean,
    private val cloudDialogueMode: Boolean = false,
    private val showError: (messageRes: Int) -> Unit,
    private val sendMessage: (parts: List<UIMessagePart>, answer: Boolean, queueMode: PendingUserMessageMode) -> Boolean,
    private val editMessage: (parts: List<UIMessagePart>, messageId: Uuid, regenerate: Boolean) -> Boolean,
) {
    fun canSendWithoutChatModel(parts: List<UIMessagePart>): Boolean =
        cloudDialogueMode || parseDeepReadSlashCommand(parts) != null

    fun confirmEdit(regenerate: Boolean) {
        val unresolved = input.attachmentImports.firstOrNull {
            it.status != ChatInputAttachmentImportStatus.READY
        }
        if (unresolved != null) {
            showError(if (unresolved.status == ChatInputAttachmentImportStatus.FAILED) {
                R.string.parity_attachment_import_failed_short
            } else {
                R.string.parity_attachment_importing
            })
            return
        }
        val parts = input.getContents()
        if (parts.isEmptyInputMessage()) return
        if (regenerate && configurationIssue != null) {
            showError(configurationIssue.messageRes)
            return
        }
        submission.confirmEdit(regenerate, editMessage)
    }

    fun send(
        queueMode: PendingUserMessageMode,
        parts: List<UIMessagePart>,
        editingMessage: Uuid?,
    ): Boolean {
        if (!checkConfiguration(parts, editingMessage)) return false
        return if (editingMessage != null) {
            editMessage(parts, editingMessage, true)
        } else {
            sendMessage(parts, true, queueMode)
        }
    }

    fun longSend(
        queueMode: PendingUserMessageMode,
        parts: List<UIMessagePart>,
        editingMessage: Uuid?,
    ): Boolean {
        if (!checkConfiguration(parts, editingMessage)) return false
        return if (editingMessage != null) {
            editMessage(parts, editingMessage, true)
        } else {
            sendMessage(parts, generating && queueMode == PendingUserMessageMode.STEER, queueMode)
        }
    }

    fun sendSuggestion(suggestion: String): Boolean {
        val text = suggestion.trim()
        if (text.isEmpty()) return false
        val parts = listOf(UIMessagePart.Text(text))
        if (!checkConfiguration(parts, null)) return false
        return sendMessage(parts, true, PendingUserMessageMode.FOLLOWUP)
    }

    fun sendWidgetAction(instruction: String): Boolean {
        val text = instruction.trim()
        if (text.isEmpty()) return false
        val parts = listOf(UIMessagePart.Text(text))
        if (!checkConfiguration(parts, null)) return false
        return submission.sendWidgetAction(parts) { actionParts ->
            sendMessage(actionParts, true, PendingUserMessageMode.FOLLOWUP)
        }
    }

    private fun checkConfiguration(parts: List<UIMessagePart>, editingMessage: Uuid?): Boolean {
        val canRouteWithoutChatModel = editingMessage == null && canSendWithoutChatModel(parts)
        if (configurationIssue != null && !canRouteWithoutChatModel) {
            showError(configurationIssue.messageRes)
            return false
        }
        return true
    }
}
