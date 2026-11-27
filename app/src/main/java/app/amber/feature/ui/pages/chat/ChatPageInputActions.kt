package app.amber.feature.ui.pages.chat

import app.amber.agent.R
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.isEmptyInputMessage
import app.amber.core.service.PendingUserMessageMode
import app.amber.feature.ui.components.ai.ChatInputSubmissionOwner
import app.amber.feature.ui.hooks.ChatInputAttachmentImportStatus
import app.amber.feature.ui.hooks.ChatInputState
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.uuid.Uuid

/** Pure admission rule for whether a submitted message may arm a send stage. */
internal fun shouldTriggerSendStage(
    editingMessage: Uuid?,
    queueMode: PendingUserMessageMode,
    generating: Boolean,
    followupTargetActive: Boolean,
): Boolean = editingMessage == null &&
    queueMode != PendingUserMessageMode.STEER &&
    !generating &&
    !followupTargetActive

/** Page-level admission and edit actions; the composer retains its one submission owner. */
internal class ChatPageInputActions(
    private val input: ChatInputState,
    private val submission: ChatInputSubmissionOwner,
    private val configurationIssue: ChatConfigurationIssue?,
    private val generating: Boolean,
    private val cloudDialogueMode: Boolean = false,
    private val conversationId: String = "",
    private val sendStage: ChatSendStageState? = null,
    private val isFollowupTargetActive: () -> Boolean = { false },
    private val nextSendRevision: () -> Long = { 1L },
    private val hideKeyboard: () -> Unit = {},
    private val showError: (messageRes: Int) -> Unit,
    private val sendMessage: (parts: List<UIMessagePart>, answer: Boolean, queueMode: PendingUserMessageMode, messageId: Uuid) -> Deferred<Boolean>?,
    private val editMessage: (parts: List<UIMessagePart>, messageId: Uuid, regenerate: Boolean) -> Boolean,
) {
    fun canSendWithoutChatModel(): Boolean = cloudDialogueMode

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

    suspend fun send(
        queueMode: PendingUserMessageMode,
        parts: List<UIMessagePart>,
        editingMessage: Uuid?,
    ): Boolean {
        if (!checkConfiguration(editingMessage)) return false
        if (editingMessage != null) {
            return editMessage(parts, editingMessage, true)
        }
        val armStage = shouldTriggerSendStage(
            editingMessage = null,
            queueMode = queueMode,
            generating = generating,
            followupTargetActive = isFollowupTargetActive(),
        )
        val messageId = Uuid.random()
        if (armStage) sendStage?.beginLocalAdmission(messageId)
        try {
            val accepted = sendMessage(parts, true, queueMode, messageId)?.await() == true
            if (accepted && armStage && (sendStage == null || sendStage.admittingMessageId == messageId)) {
                sendStage?.onSubmitAccepted(
                    SendStageIntent(
                        host = if (cloudDialogueMode) "cloud" else "local",
                        conversationId = conversationId,
                        sendRevision = nextSendRevision(),
                        messageId = messageId,
                    ),
                )
                if (input.isCurrentConversation(conversationId) && input.getContents() == parts) hideKeyboard()
            }
            return accepted
        } finally {
            sendStage?.endLocalAdmission(messageId)
        }
    }

    suspend fun longSend(
        queueMode: PendingUserMessageMode,
        parts: List<UIMessagePart>,
        editingMessage: Uuid?,
    ): Boolean {
        if (!checkConfiguration(editingMessage)) return false
        return if (editingMessage != null) {
            editMessage(parts, editingMessage, true)
        } else {
            sendMessage(parts, generating && queueMode == PendingUserMessageMode.STEER, queueMode, Uuid.random())?.await() == true
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun sendSuggestion(suggestion: String): Boolean {
        val text = suggestion.trim()
        if (text.isEmpty()) return false
        val parts = listOf(UIMessagePart.Text(text))
        if (!checkConfiguration(null)) return false
        val armStage = shouldTriggerSendStage(
            editingMessage = null,
            queueMode = PendingUserMessageMode.FOLLOWUP,
            generating = generating,
            followupTargetActive = isFollowupTargetActive(),
        )
        val messageId = Uuid.random()
        if (armStage) sendStage?.beginLocalAdmission(messageId)
        val deferred = sendMessage(parts, true, PendingUserMessageMode.FOLLOWUP, messageId)
        if (deferred == null) {
            sendStage?.endLocalAdmission(messageId)
            return false
        }
        if (armStage) {
            deferred.invokeOnCompletion { cause ->
                try {
                    val accepted = cause == null &&
                        runCatching { deferred.getCompleted() }.getOrDefault(false)
                    if (accepted && (sendStage == null || sendStage.admittingMessageId == messageId)) {
                        sendStage?.onSubmitAccepted(
                            SendStageIntent(
                                host = if (cloudDialogueMode) "cloud" else "local",
                                conversationId = conversationId,
                                sendRevision = nextSendRevision(),
                                messageId = messageId,
                            ),
                        )
                        hideKeyboard()
                    }
                } finally {
                    sendStage?.endLocalAdmission(messageId)
                }
            }
        }
        return true
    }

    fun sendWidgetAction(instruction: String): Boolean {
        val text = instruction.trim()
        if (text.isEmpty()) return false
        val parts = listOf(UIMessagePart.Text(text))
        if (!checkConfiguration(null)) return false
        return submission.sendWidgetAction(parts) { actionParts ->
            sendMessage(actionParts, true, PendingUserMessageMode.FOLLOWUP, Uuid.random()) != null
        }
    }

    private fun checkConfiguration(editingMessage: Uuid?): Boolean {
        val canRouteWithoutChatModel = editingMessage == null && canSendWithoutChatModel()
        if (configurationIssue != null && !canRouteWithoutChatModel) {
            showError(configurationIssue.messageRes)
            return false
        }
        return true
    }
}
