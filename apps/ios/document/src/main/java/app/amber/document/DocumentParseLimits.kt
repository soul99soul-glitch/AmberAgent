package app.amber.document

import java.io.FilterInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Hard safety envelope shared by chat attachment and workspace document reads.
 * The 80k output ceiling matches the existing pdf_read/office_read tool
 * contract; the 64 MiB input/entry ceiling matches the workspace archive
 * extraction contract.
 */
object DocumentParseLimits {
    const val MAX_OUTPUT_CHARS: Int = 80_000
    const val MAX_INPUT_BYTES: Long = 64L * 1024L * 1024L
    const val MAX_PARSED_ARCHIVE_BYTES: Long = MAX_INPUT_BYTES
    const val MAX_CONTAINER_ENTRIES: Int = 10_000

    fun requireInputFile(file: File) {
        require(file.exists() && file.isFile) { "Document file does not exist" }
        require(file.length() <= MAX_INPUT_BYTES) {
            "Document exceeds the ${MAX_INPUT_BYTES / (1024L * 1024L)} MiB parsing limit"
        }
    }

    fun requireOfficeArchive(file: File) {
        requireInputFile(file)
        ZipFile(file).use { zip ->
            require(zip.size() <= MAX_CONTAINER_ENTRIES) { "Document archive contains too many entries" }
            var parsedBytes = 0L
            zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .filter { entry -> entry.name.substringAfterLast('.', "").lowercase() in PARSED_ENTRY_EXTENSIONS }
                .forEach { entry ->
                    require(entry.size >= 0L) { "Document archive entry has an unknown size" }
                    require(entry.size <= MAX_INPUT_BYTES) { "Document archive entry is too large" }
                    parsedBytes += entry.size
                    require(parsedBytes <= MAX_PARSED_ARCHIVE_BYTES) {
                        "Document archive expands beyond the parsing limit"
                    }
                }
        }
    }

    fun limitOutput(text: String, maxChars: Int = MAX_OUTPUT_CHARS): String =
        text.take(maxChars.coerceIn(1, MAX_OUTPUT_CHARS))

    internal fun appendLimited(output: StringBuilder, text: CharSequence, maxChars: Int) {
        val remaining = maxChars.coerceIn(1, MAX_OUTPUT_CHARS) - output.length
        if (remaining > 0) output.append(text, 0, minOf(text.length, remaining))
    }

    fun readText(file: File, maxChars: Int = MAX_OUTPUT_CHARS): String {
        requireInputFile(file)
        val limit = maxChars.coerceIn(1, MAX_OUTPUT_CHARS)
        return file.bufferedReader().use { reader ->
            val output = StringBuilder(minOf(limit, 16_384))
            val buffer = CharArray(DEFAULT_BUFFER_SIZE)
            while (output.length < limit) {
                val read = reader.read(buffer, 0, minOf(buffer.size, limit - output.length))
                if (read < 0) break
                if (read > 0) output.append(buffer, 0, read)
            }
            output.toString()
        }
    }

    fun boundedEntry(input: InputStream): InputStream =
        BoundedInputStream(input, MAX_INPUT_BYTES)

    private val PARSED_ENTRY_EXTENSIONS = setOf("xml", "xhtml", "html", "htm", "opf", "ncx")
}

private class BoundedInputStream(
    input: InputStream,
    private val maxBytes: Long,
) : FilterInputStream(input) {
    private var bytesRead = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) account(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = super.read(buffer, offset, length)
        if (read > 0) account(read)
        return read
    }

    private fun account(count: Int) {
        bytesRead += count
        require(bytesRead <= maxBytes) { "Document archive entry expands beyond the parsing limit" }
    }
}
