package app.amber.feature.workspace

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedLineReaderTest {
    @Test
    fun readsNormalLinesAndTracksConsumedCharacters() {
        val reader = StringReader("first\r\nsecond").buffered()

        val first = reader.readLineWithinLimit(20)
        val second = reader.readLineWithinLimit(20)

        assertEquals("first", first?.text)
        assertEquals(7, first?.consumedChars)
        assertTrue(first?.terminated == true)
        assertEquals("second", second?.text)
        assertTrue(second?.terminated == true)
        assertNull(reader.readLineWithinLimit(20))
    }

    @Test
    fun stopsBeforeAllocatingAnUnboundedLine() {
        val reader = StringReader("a".repeat(100)).buffered()

        val line = reader.readLineWithinLimit(10)

        assertEquals("a".repeat(10), line?.text)
        assertEquals(10, line?.consumedChars)
        assertFalse(line?.terminated ?: true)
    }
}
