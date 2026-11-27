package app.amber.feature.ui.pages.chat

import android.app.Application
import android.net.Uri
import app.amber.agent.R
import app.amber.ai.ui.UIMessagePart
import app.amber.core.service.PendingUserMessageMode
import app.amber.feature.ui.components.ai.ChatInputSubmissionOwner
import app.amber.feature.ui.hooks.ChatInputAttachmentKind
import app.amber.feature.ui.hooks.ChatInputState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatPageInputActionsTest {
    @Test
    fun unresolvedEditShowsImportFeedbackBeforeEmptyOrModelGateForBothActions() {
        val input = ChatInputState().apply { bindToConversation("conversation-a") }
        val messageId = Uuid.random()
        input.editingMessage = messageId
        val attachment = requireNotNull(input.beginAttachmentImport(
            conversationId = "conversation-a",
            sourceUri = Uri.parse("content://picked/pending-image"),
            displayName = "pending.png",
            mimeType = "image/png",
            kind = ChatInputAttachmentKind.IMAGE,
        ))
        val errors = mutableListOf<Int>()
        var edits = 0
        val actions = actions(input, errors) { _, _, _ -> edits++; true }

        actions.confirmEdit(false)
        actions.confirmEdit(true)
        assertEquals(listOf(R.string.parity_attachment_importing, R.string.parity_attachment_importing), errors)
        assertTrue(input.failAttachmentImport("conversation-a", attachment.id, "copy failed"))
        actions.confirmEdit(false)
        actions.confirmEdit(true)

        assertEquals(listOf(
            R.string.parity_attachment_importing,
            R.string.parity_attachment_importing,
            R.string.parity_attachment_import_failed_short,
            R.string.parity_attachment_import_failed_short,
        ), errors)
        assertEquals(0, edits)
        assertEquals(messageId, input.editingMessage)
        assertEquals(attachment.id, input.attachmentImports.single().id)
    }

    @Test
    fun savingAnEditNeedsNoModelButRegeneratingRetainsTheRejectedDraft() {
        val input = ChatInputState().apply {
            bindToConversation("conversation-a")
            setMessageText("edited draft")
            editingMessage = Uuid.random()
        }
        val messageId = requireNotNull(input.editingMessage)
        val errors = mutableListOf<Int>()
        val edits = mutableListOf<Triple<List<UIMessagePart>, Uuid, Boolean>>()
        val actions = actions(input, errors) { parts, id, regenerate ->
            edits += Triple(parts, id, regenerate)
            true
        }

        actions.confirmEdit(true)
        assertEquals(listOf(R.string.chat_setup_missing_model), errors)
        assertTrue(edits.isEmpty())
        assertEquals("edited draft", input.textContent.text.toString())
        assertEquals(messageId, input.editingMessage)

        actions.confirmEdit(false)
        assertEquals(listOf(Triple(listOf(UIMessagePart.Text("edited draft")), messageId, false)), edits)
        assertTrue(input.isEmpty())
    }

    @Test
    fun formerDeepReadCommandRequiresChatModelForSendAndEdit() = runBlocking {
        val input = ChatInputState().apply { bindToConversation("conversation-a") }
        val errors = mutableListOf<Int>()
        var sends = 0
        var edits = 0
        val actions = ChatPageInputActions(
            input = input,
            submission = ChatInputSubmissionOwner(input),
            configurationIssue = ChatConfigurationIssue.MissingModel,
            generating = false,
            showError = { errors += it },
            sendMessage = { _, answer, mode, _ ->
                assertTrue(answer)
                assertEquals(PendingUserMessageMode.FOLLOWUP, mode)
                sends++
                CompletableDeferred(true)
            },
            editMessage = { _, _, _ -> edits++; true },
        )
        val parts = listOf(UIMessagePart.Text("/deepread https://example.org/article"))

        assertFalse(actions.send(PendingUserMessageMode.FOLLOWUP, parts, null))
        assertFalse(actions.send(PendingUserMessageMode.FOLLOWUP, parts, Uuid.random()))
        assertEquals(0, sends)
        assertEquals(0, edits)
        assertEquals(List(2) { R.string.chat_setup_missing_model }, errors)
    }

    private fun actions(
        input: ChatInputState,
        errors: MutableList<Int>,
        edit: (List<UIMessagePart>, Uuid, Boolean) -> Boolean,
    ) = ChatPageInputActions(
        input = input,
        submission = ChatInputSubmissionOwner(input),
        configurationIssue = ChatConfigurationIssue.MissingModel,
        generating = false,
        showError = { errors += it },
        sendMessage = { _, _, _, _ -> error("Edit confirmation must not submit a new message") },
        editMessage = edit,
    )
}
