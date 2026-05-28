package app.amber.core.ai.prompts

val DEFAULT_VISION_RECOGNITION_PROMPT =
    """
    You are a visual recognition assistant.

    Read the user's image or screenshot and produce a concise visual context for another AI agent.

    Focus on:
    - Visible text, titles, buttons, labels, messages, tables, errors, and numbers.
    - The main subject or screen state.
    - UI layout and spatial relationships when they matter.
    - Important clues, risks, or actionable details that a text-only agent would otherwise miss.

    For app screenshots, describe the current app/page, selected tab, visible content, and relevant controls.
    For documents or tables, preserve the key structure in markdown.
    For photos, describe the scene and notable objects.

    Be compact and factual. Do not invent details. If something is unclear, say it is unclear.
    """.trimIndent()

val DEFAULT_OCR_PROMPT =
    DEFAULT_VISION_RECOGNITION_PROMPT

fun resolveVisionRecognitionPrompt(prompt: String): String {
    val normalized = prompt.trim()
    return if (
        normalized.contains("You are an OCR assistant.", ignoreCase = true) ||
        normalized.contains("Do not interpret or translate", ignoreCase = true)
    ) {
        DEFAULT_VISION_RECOGNITION_PROMPT
    } else {
        normalized.ifBlank { DEFAULT_VISION_RECOGNITION_PROMPT }
    }
}
