package app.amber.feature.board.hotlist.deepread

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.amber.document.DocxParser
import app.amber.document.EpubParser
import app.amber.document.PdfParser
import app.amber.document.PptxParser
import java.io.File

/**
 * Extracts text from a user-picked document for use as a deep-read seed source.
 * Mirrors the dispatch rules in DocumentAsPromptTransformer (same parsers, same
 * text-extension list) but returns a typed result instead of prompt markup.
 */
object DeepReadSourceImporter {

    data class ImportedSource(
        val fileName: String,
        val mimeType: String,
        val text: String,
        val truncated: Boolean,
    )

    sealed interface Result {
        data class Success(val source: ImportedSource) : Result
        data class Failure(val reason: Reason) : Result
    }

    enum class Reason {
        UNSUPPORTED,
        TOO_LARGE,
        NO_TEXT,
        READ_ERROR,
    }

    suspend fun extract(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(resolver, uri) ?: uri.lastPathSegment ?: "file"
        val mime = resolver.getType(uri).orEmpty()
        val ext = displayName.substringAfterLast('.', "").lowercase()

        if (!isSupported(mime, ext)) return@withContext Result.Failure(Reason.UNSUPPORTED)

        val size = querySize(resolver, uri)
        if (size != null && size > MAX_IMPORT_FILE_BYTES) {
            return@withContext Result.Failure(Reason.TOO_LARGE)
        }

        val temp = runCatching {
            File.createTempFile("deepread-import-", ".$ext".ifBlank { ".bin" }, context.cacheDir).also { file ->
                resolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext Result.Failure(Reason.READ_ERROR)
            }
        }.getOrElse { return@withContext Result.Failure(Reason.READ_ERROR) }

        try {
            val text = runCatching { extractText(temp, mime, ext) }.getOrNull()
                ?: return@withContext Result.Failure(Reason.READ_ERROR)
            if (text.isBlank()) return@withContext Result.Failure(Reason.NO_TEXT)
            Result.Success(
                ImportedSource(
                    fileName = displayName,
                    mimeType = mime.ifBlank { ext },
                    text = text.take(MAX_IMPORT_TEXT_CHARS),
                    truncated = text.length > MAX_IMPORT_TEXT_CHARS,
                )
            )
        } finally {
            temp.delete()
        }
    }

    private fun extractText(file: File, mime: String, ext: String): String = when {
        mime == "application/pdf" || ext == "pdf" -> PdfParser.parserPdf(file, MAX_IMPORT_TEXT_CHARS)
        mime == MIME_DOCX || ext == "docx" -> DocxParser.parse(file, MAX_IMPORT_TEXT_CHARS)
        mime == MIME_PPTX || ext == "pptx" -> PptxParser.parse(file, MAX_IMPORT_TEXT_CHARS)
        mime == "application/epub+zip" || ext == "epub" -> EpubParser.parse(file, MAX_IMPORT_TEXT_CHARS)
        else -> file.bufferedReader().use { reader ->
            val buffer = CharArray(MAX_IMPORT_TEXT_CHARS + 1)
            var total = 0
            while (total < buffer.size) {
                val read = reader.read(buffer, total, buffer.size - total)
                if (read < 0) break
                total += read
            }
            String(buffer, 0, total)
        }
    }

    private fun isSupported(mime: String, ext: String): Boolean = when {
        mime == "application/pdf" || ext == "pdf" -> true
        mime == MIME_DOCX || ext == "docx" -> true
        mime == MIME_PPTX || ext == "pptx" -> true
        mime == "application/epub+zip" || ext == "epub" -> true
        mime.startsWith("text/") -> true
        else -> ext in TEXT_EXTENSIONS
    }

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

    private fun querySize(resolver: android.content.ContentResolver, uri: Uri): Long? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        }.getOrNull()

    /** Aligned with the iOS deep-read import cap (40k chars). */
    private const val MAX_IMPORT_TEXT_CHARS = 40_000
    private const val MAX_IMPORT_FILE_BYTES = 64L * 1024 * 1024
    private const val MIME_DOCX =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private const val MIME_PPTX =
        "application/vnd.openxmlformats-officedocument.presentationml.presentation"

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "csv", "json", "jsonl", "xml", "html", "htm",
        "log", "yml", "yaml", "toml",
    )
}
