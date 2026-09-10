package app.amber.feature.ui.hooks

import android.app.Application
import android.net.Uri
import app.amber.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

/**
 * Regression for issue #1: 附件-only 草稿曾被 [ChatInputState.isEmpty] 误判为空，
 * 导致无法发送、且生成中点发送被当作 stop。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatInputStateTest {
    @Test
    fun isEmpty_trueWhenNoTextAndNoAttachments() {
        assertTrue(ChatInputState().isEmpty())
    }

    @Test
    fun isEmpty_falseWhenTextOnly() {
        val state = ChatInputState()
        state.setMessageText("hello")
        assertFalse(state.isEmpty())
    }

    @Test
    fun isEmpty_falseWhenAttachmentOnly() {
        val state = ChatInputState()
        state.addImages(listOf(Uri.parse("file:///tmp/a.png")))
        assertFalse(state.isEmpty())
    }

    @Test
    fun isEmpty_backToTrueAfterClear() {
        val state = ChatInputState()
        state.addImages(listOf(Uri.parse("file:///tmp/a.png")))
        state.clearInput()
        assertTrue(state.isEmpty())
    }

    @Test
    fun attachmentImport_tracksReadyPartAndMetadata() {
        val state = ChatInputState()
        state.bindToConversation("conversation-a")
        val item = state.beginAttachmentImport(
            conversationId = "conversation-a",
            sourceUri = Uri.parse("content://picker/report.md"),
            displayName = "report.md",
            mimeType = "text/markdown",
            kind = ChatInputAttachmentKind.DOCUMENT,
            sizeBytes = 2_048L,
        )
        assertNotNull(item)
        assertTrue(state.hasUnresolvedAttachmentImports())

        val part = UIMessagePart.Document(
            url = "file:///data/user/0/app.amber.agent/files/upload/report.md",
            fileName = "report.md",
            mime = "text/markdown",
        )
        assertTrue(
            state.completeAttachmentImport(
                conversationId = "conversation-a",
                importId = item!!.id,
                part = part,
                sizeBytes = 2_048L,
                textWasTruncated = true,
            )
        )

        assertEquals(listOf(part), state.messageContent)
        assertEquals(ChatInputAttachmentImportStatus.READY, state.attachmentImports.single().status)
        assertEquals(2_048L, state.attachmentImports.single().sizeBytes)
        assertTrue(state.attachmentImports.single().textWasTruncated)
        assertFalse(state.hasUnresolvedAttachmentImports())
    }

    @Test
    fun failedImport_canRetryThenRemoveAndReuseSource() {
        val state = ChatInputState()
        state.bindToConversation("conversation-a")
        val source = Uri.parse("content://picker/retry.txt")
        val item = state.beginAttachmentImport(
            conversationId = "conversation-a",
            sourceUri = source,
            displayName = "retry.txt",
            mimeType = "text/plain",
            kind = ChatInputAttachmentKind.DOCUMENT,
        )!!

        assertTrue(state.failAttachmentImport("conversation-a", item.id, "copy failed"))
        assertTrue(state.hasUnresolvedAttachmentImports())
        val retry = state.retryAttachmentImport("conversation-a", item.id)
        assertNotNull(retry)
        assertEquals(ChatInputAttachmentImportStatus.IMPORTING, retry!!.status)
        assertNull(retry.errorMessage)

        val removed = state.removeAttachmentImport(item.id)
        assertEquals(source.toString(), removed!!.sourceUri)
        assertTrue(state.attachmentImports.isEmpty())
        assertNotNull(
            state.beginAttachmentImport(
                conversationId = "conversation-a",
                sourceUri = source,
                displayName = "retry.txt",
                mimeType = "text/plain",
                kind = ChatInputAttachmentKind.DOCUMENT,
            )
        )
    }

    @Test
    fun lateCompletion_afterConversationSwitch_isRejected() {
        val state = ChatInputState()
        state.bindToConversation("conversation-a")
        val item = state.beginAttachmentImport(
            conversationId = "conversation-a",
            sourceUri = Uri.parse("content://picker/late.txt"),
            displayName = "late.txt",
            mimeType = "text/plain",
            kind = ChatInputAttachmentKind.DOCUMENT,
        )!!
        val part = UIMessagePart.Document("file:///tmp/late.txt", "late.txt", "text/plain")

        state.bindToConversation("conversation-b")

        assertFalse(
            state.completeAttachmentImport(
                conversationId = "conversation-a",
                importId = item.id,
                part = part,
                sizeBytes = 10L,
            )
        )
        assertTrue(state.attachmentImports.isEmpty())
        assertTrue(state.messageContent.isEmpty())
    }

    @Test
    fun drainAttachmentFilesForDiscard_keepsOriginalEditAttachment() {
        val state = ChatInputState()
        val original = UIMessagePart.Document("file:///tmp/original.pdf", "original.pdf", "application/pdf")
        val added = UIMessagePart.Document("file:///tmp/added.pdf", "added.pdf", "application/pdf")
        state.editingMessage = Uuid.random()
        state.setContents(listOf(UIMessagePart.Text("draft"), original))
        state.messageContent = state.messageContent + added

        val files = state.drainAttachmentFilesForDiscard()

        assertEquals(listOf(Uri.parse(added.url)), files)
        assertEquals(listOf(original), state.messageContent)
    }

    @Test
    fun drainAttachmentFilesForDiscard_removesReadyImportedCopy() {
        val state = ChatInputState()
        state.bindToConversation("conversation-a")
        val import = state.beginAttachmentImport(
            conversationId = "conversation-a",
            sourceUri = Uri.parse("content://picker/ready.pdf"),
            displayName = "ready.pdf",
            mimeType = "application/pdf",
            kind = ChatInputAttachmentKind.DOCUMENT,
        )!!
        val part = UIMessagePart.Document("file:///tmp/ready.pdf", "ready.pdf", "application/pdf")
        assertTrue(state.completeAttachmentImport("conversation-a", import.id, part, 10L))

        val files = state.drainAttachmentFilesForDiscard()

        assertEquals(listOf(Uri.parse(part.url)), files)
        assertTrue(state.messageContent.isEmpty())
        assertTrue(state.attachmentImports.isEmpty())
    }
}
