package app.amber.feature.ui.pages.chat

import app.amber.ai.ui.UIMessagePart
import app.amber.feature.miniapp.ConversationDraftStore
import app.amber.feature.ui.hooks.ChatInputState

/** Owns only host-draft projection and consumption for this retained chat composer. */
internal class HostDraftComposer(
    private val conversationId: String,
    private val store: ConversationDraftStore,
    private val input: ChatInputState,
) {
    private var restoredId: String? = null
    private var restoredParts: List<UIMessagePart>? = null
    private var consumedId: String? = null
    private var revision = 0L

    suspend fun refresh() {
        val requestRevision = ++revision
        val before = input.getContents()
        if (input.isEditing() || input.hasUnresolvedAttachmentImports()) return
        if (!input.isEmpty() && before != restoredParts) return
        val draft = store.load(conversationId) ?: return
        // A send / a newer refresh / hand typing during Room's suspended read owns the composer.
        if (revision != requestRevision || input.getContents() != before || input.isEditing() ||
            input.hasUnresolvedAttachmentImports() || draft.draftId == consumedId || draft.draftId == restoredId
        ) return
        input.setContents(draft.toParts())
        restoredId = draft.draftId
        restoredParts = input.getContents()
    }

    /** Capture synchronously at acceptance, before the asynchronous durable delete. */
    fun acceptedSendDraftId(): String? {
        revision++
        val id = restoredId ?: return null
        consumedId = id
        restoredId = null
        restoredParts = null
        return id
    }

    suspend fun consume(draftId: String) = store.clearIfCurrent(conversationId, draftId)
}
