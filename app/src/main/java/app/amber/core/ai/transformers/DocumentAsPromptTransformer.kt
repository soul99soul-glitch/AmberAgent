package app.amber.core.ai.transformers

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.document.DocxParser
import app.amber.document.EpubParser
import app.amber.document.PdfParser
import app.amber.document.PptxParser
import java.io.File

object DocumentAsPromptTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return withContext(Dispatchers.IO) {
            messages.map { message ->
                message.copy(
                    parts = message.parts.toMutableList().apply {
                        val documents = filterIsInstance<UIMessagePart.Document>()
                        if (documents.isNotEmpty()) {
                            documents.reversed().forEach { document ->
                                val content = readDocumentContent(document)
                                val prompt = """
                  ## user sent a file: ${document.fileName}
                  <content>
                  ```
                  $content
                  ```
                  </content>
                  """.trimMargin()
                                add(0, UIMessagePart.Text(prompt))
                            }
                        }
                    }
                )
            }
        }
    }

    /**
     * Extract a document's text the same way [transform] does, exposed for callers
     * that need the content outside the UIMessage transform pipeline (e.g. the
     * Council Room, whose generation path has no transformer chain). Runs on IO.
     */
    suspend fun extractText(document: UIMessagePart.Document): String =
        withContext(Dispatchers.IO) { readDocumentContent(document) }

    private fun parsePdfAsText(file: File): String {
        return PdfParser.parserPdf(file, MAX_INLINE_TEXT_CHARS)
    }

    private fun parseDocxAsText(file: File): String = DocxParser.parse(file)

    private fun parsePptxAsText(file: File): String = PptxParser.parse(file)

    private fun parseEpubAsText(file: File): String = EpubParser.parse(file)

    private fun readDocumentContent(document: UIMessagePart.Document): String {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull()
            ?: return "[ERROR, invalid file uri: ${document.fileName}]"
        if (!file.exists() || !file.isFile) {
            return "[ERROR, file not found: ${document.fileName}]"
        }
        if (file.length() > MAX_INLINE_FILE_BYTES) {
            return "[ERROR, file too large to inline: ${document.fileName} (${file.length()} bytes)]"
        }
        return runCatching {
            val content = when (document.mime) {
                "application/pdf" -> parsePdfAsText(file)
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> parseDocxAsText(file)
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> parsePptxAsText(file)
                "application/epub+zip" -> parseEpubAsText(file)
                else -> {
                    if (document.isLikelyTextFile()) {
                        file.bufferedReader().use { reader ->
                            val buffer = CharArray(MAX_INLINE_TEXT_CHARS + 1)
                            var total = 0
                            while (total < buffer.size) {
                                val read = reader.read(buffer, total, buffer.size - total)
                                if (read < 0) break
                                total += read
                            }
                            String(buffer, 0, total).let { content ->
                                if (content.length > MAX_INLINE_TEXT_CHARS) {
                                    content.take(MAX_INLINE_TEXT_CHARS) +
                                        "\n[TRUNCATED: document text exceeds $MAX_INLINE_TEXT_CHARS characters]"
                                } else {
                                    content
                                }
                            }
                        }
                    } else {
                        buildString {
                            appendLine("[BINARY_OR_ARCHIVE_FILE]")
                            appendLine("name: ${document.fileName}")
                            appendLine("mime: ${document.mime}")
                            appendLine("size_bytes: ${file.length()}")
                            appendLine("local_path: ${file.absolutePath}")
                            appendLine("The file is attached but was not inlined as text. Use available tools, such as terminal_execute, to inspect, extract, or process it.")
                        }
                    }
                }
            }
            if (content.length > MAX_INLINE_TEXT_CHARS) {
                content.take(MAX_INLINE_TEXT_CHARS) +
                    "\n[TRUNCATED: document text exceeds $MAX_INLINE_TEXT_CHARS characters]"
            } else {
                content
            }
        }.getOrElse {
            "[ERROR, failed to read file: ${document.fileName}]"
        }
    }

    private fun UIMessagePart.Document.isLikelyTextFile(): Boolean {
        if (mime.startsWith("text/")) return true
        return fileName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase() in setOf(
                "txt", "md", "markdown", "mdx", "csv", "json", "jsonl", "xml", "html", "htm",
                "css", "js", "ts", "tsx", "jsx", "py", "java", "kt", "kts", "swift", "go",
                "rs", "c", "h", "cpp", "hpp", "cs", "sh", "bash", "zsh", "fish", "rb",
                "php", "sql", "yml", "yaml", "toml", "ini", "conf", "gradle", "properties",
                "log", "svg"
            )
    }

    private const val MAX_INLINE_TEXT_CHARS = 512 * 1024
    private const val MAX_INLINE_FILE_BYTES = 64L * 1024 * 1024
}
