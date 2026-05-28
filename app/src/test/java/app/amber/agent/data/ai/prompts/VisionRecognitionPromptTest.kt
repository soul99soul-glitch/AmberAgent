package app.amber.core.ai.prompts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionRecognitionPromptTest {
    @Test
    fun `default visual prompt should not use OCR-only instructions`() {
        assertFalse(DEFAULT_OCR_PROMPT.contains("OCR assistant", ignoreCase = true))
        assertFalse(DEFAULT_OCR_PROMPT.contains("image_file_ocr", ignoreCase = true))
        assertFalse(DEFAULT_OCR_PROMPT.contains("Do not interpret", ignoreCase = true))
        assertTrue(DEFAULT_OCR_PROMPT.contains("visual recognition assistant", ignoreCase = true))
    }

    @Test
    fun `legacy OCR prompt should resolve to visual recognition prompt`() {
        val resolved = resolveVisionRecognitionPrompt(
            """
            You are an OCR assistant.
            Do not interpret or translate—only transcribe and describe what is visually present.
            """.trimIndent()
        )

        assertEquals(DEFAULT_VISION_RECOGNITION_PROMPT, resolved)
    }
}
