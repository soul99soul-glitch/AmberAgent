package app.amber.document

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedInputStreamTest {
    @Test
    fun read_allowsPayloadAtLimit() {
        val payload = byteArrayOf(1, 2, 3, 4)

        val actual = BoundedInputStream(ByteArrayInputStream(payload), payload.size.toLong()).readBytes()

        assertArrayEquals(payload, actual)
    }

    @Test
    fun read_rejectsPayloadPastLimit() {
        val input = BoundedInputStream(ByteArrayInputStream(ByteArray(5)), 4)

        assertThrows(IllegalArgumentException::class.java) {
            input.readBytes()
        }
    }
}
