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
import app.amber.document.nativebridge.OfficeNativeSwitch
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
                            documents.forEach { document ->
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

    private fun parsePdfAsText(file: File): String {
        return PdfParser.parserPdf(file)
    }

    // Phase 2 Step 1: route through OfficeNativeSwitch. Default config is
    // disabled → parseXOrNull always returns null → JVM parser runs unchanged.
    // When a user opts in via NativePathPrefs and the .so loaded, native runs;
    // any failure (load, panic, NativeUnavailable) returns null and the JVM
    // path executes via the Elvis fallback. The sampling-path JVM call
    // happens *inside* the Switch's jvmFallback lambda, not via the Elvis,
    // so steady-state cost stays at one parse per document.

    private fun parseDocxAsText(file: File): String {
        return OfficeNativeSwitch.parseDocxOrNull(file) { DocxParser.parse(file) }
            ?: DocxParser.parse(file)
    }

    private fun parsePptxAsText(file: File): String {
        return OfficeNativeSwitch.parsePptxOrNull(file) { PptxParser.parse(file) }
            ?: PptxParser.parse(file)
    }

    private fun parseEpubAsText(file: File): String {
        return OfficeNativeSwitch.parseEpubOrNull(file) { EpubParser.parse(file) }
            ?: EpubParser.parse(file)
    }

    private fun readDocumentContent(document: UIMessagePart.Document): String {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull()
            ?: return "[ERROR, invalid file uri: ${document.fileName}]"
        if (!file.exists() || !file.isFile) {
            return "[ERROR, file not found: ${document.fileName}]"
        }
        return runCatching {
            when (document.mime) {
                "application/pdf" -> parsePdfAsText(file)
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> parseDocxAsText(file)
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> parsePptxAsText(file)
                "application/epub+zip" -> parseEpubAsText(file)
                else -> {
                    if (document.isLikelyTextFile()) {
                        file.readText()
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
}
