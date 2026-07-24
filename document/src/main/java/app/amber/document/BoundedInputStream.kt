package app.amber.document

import java.io.FilterInputStream
import java.io.InputStream

internal class BoundedInputStream(
    input: InputStream,
    private val maxBytes: Long,
) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) account(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = super.read(buffer, offset, length)
        if (read > 0) account(read.toLong())
        return read
    }

    private fun account(bytes: Long) {
        count += bytes
        require(count <= maxBytes) { "Decompressed document entry exceeds $maxBytes bytes" }
    }
}
