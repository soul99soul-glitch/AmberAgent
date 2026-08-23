package app.amber.tts.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {

    @Test
    fun `unbroken text still respects the hard chunk limit`() {
        val chunks = TextChunker(maxChunkLength = 10).split("abcdefghijklmnopqrstuvwxyz")

        assertTrue(chunks.all { it.text.length <= 10 })
        assertEquals("abcdefghijklmnopqrstuvwxyz", chunks.joinToString("") { it.text })
    }
}
