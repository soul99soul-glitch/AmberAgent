package app.amber.core.storage.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import platform.Foundation.NSTemporaryDirectory

class ConversationFileIosTest {

    @Test
    fun deleteNonExistentFileReturnsFalsePerContract() {
        val ghost = ConversationFile(tempPath("missing"))

        assertFalse(ghost.exists())
        assertFalse(ghost.delete())
    }

    @Test
    fun deleteExistingFileReturnsTrueAndRemovesIt() {
        val file = ConversationFile(tempPath("deletable"))

        file.writeText("payload")

        assertTrue(file.exists())
        assertTrue(file.delete())
        assertFalse(file.exists())
    }

    @Test
    fun writeTextToDirectoryThrows() {
        val directory = ConversationFile(tempPath("write-target-dir"))

        assertTrue(directory.mkdirs())

        assertFailsWith<IllegalStateException> {
            directory.writeText("payload")
        }
        assertTrue(directory.delete())
    }

    private fun tempPath(prefix: String): String {
        val base = NSTemporaryDirectory().trimEnd('/')
        return "$base/$prefix-${Random.nextLong()}.json"
    }
}
