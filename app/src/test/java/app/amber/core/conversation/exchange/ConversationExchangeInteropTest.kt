package app.amber.core.conversation.exchange

import app.amber.core.model.Conversation
import app.amber.core.utils.JsonInstant
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Optional host-side leg of the real iOS production codec check.
 *
 * The fixture is produced by the temporary iOS Shared.framework runner and is
 * deliberately kept outside the repository. When it is present, this test
 * decodes it with the Android production codec, edits the document, and writes
 * both the Android ZIP and its JSON payload for the iOS import runner.
 */
class ConversationExchangeInteropTest {
    @Test
    fun `ios production fixture is edited and exported by android codec`() {
        val root = File(System.getenv("AMBER_CONVERSATION_W15_ROOT") ?: "/private/tmp")
        val input = root.resolve("ios-production-conversations.zip")
        assumeTrue(input.isFile)

        val archive = ConversationExchangeCodec.decode(input.readBytes())
        assertEquals(1, archive.conversations.size)
        val edited = archive.conversations.map { it.copy(title = "android codec edited") }
        val output = ConversationExchangeCodec.encode(edited, archive.threadEdges)
        root.resolve("android-edited-conversations.zip").writeBytes(output)
        root.resolve("android-edited-conversation.json").writeText(
            JsonInstant.encodeToString(Conversation.serializer(), edited.single()),
        )

        val reread = ConversationExchangeCodec.decode(output)
        assertEquals("android codec edited", reread.conversations.single().title)
        assertTrue(output.isNotEmpty())
    }
}
