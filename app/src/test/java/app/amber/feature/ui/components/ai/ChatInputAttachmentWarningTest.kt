package app.amber.feature.ui.components.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatInputAttachmentWarningTest {
    private val localizedMessage = "localized attachment read failure"

    @Test
    fun attachmentReadWarning_detectsPdfParserFailure() {
        assertEquals(
            localizedMessage,
            attachmentReadWarning("Error parsing PDF file: cannot open document", localizedMessage),
        )
    }

    @Test
    fun attachmentReadWarning_detectsKnownParserFailureFormats() {
        val failures = listOf(
            "[ERROR, failed to read file: report.docx]",
            "Error parsing DOCX file: invalid zip",
            "Error parsing document XML: malformed XML",
            "Error parsing PPTX file: invalid zip",
            "Error parsing EPUB file: invalid zip",
            "Unable to find document content in DOCX file",
            "No readable slides in PPTX file",
            "Unable to read OPF file in EPUB",
            "No readable content found in EPUB file",
        )

        failures.forEach { failure ->
            assertEquals(localizedMessage, attachmentReadWarning(failure, localizedMessage))
        }
    }

    @Test
    fun attachmentReadWarning_ignoresReadableAndTruncatedContent() {
        assertNull(attachmentReadWarning("Error parsing is explained in this text", localizedMessage))
        assertNull(attachmentReadWarning("Normal document text\n[TRUNCATED: document text exceeds limit]", localizedMessage))
    }
}
