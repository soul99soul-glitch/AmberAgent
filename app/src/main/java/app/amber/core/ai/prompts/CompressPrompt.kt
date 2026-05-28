package app.amber.core.ai.prompts

internal val DEFAULT_COMPRESS_PROMPT = """
    You are a conversation compression assistant. Compress the following conversation into a structured continuation handoff.

    Requirements:
    1. Preserve key facts, decisions, and important context that would be needed to continue the conversation
    2. Keep the summary in the same language as the original conversation
    3. Target approximately {target_tokens} tokens
    4. Return valid JSON only, with no Markdown fence and no prose before or after the JSON
    5. The JSON must follow the schema requested in the additional context
    6. Use {locale} language
    7. Keep `timeline_summary` human-readable and keep `handoff_markdown` dense enough for another model to resume the task

    {additional_context}

    <conversation>
    {content}
    </conversation>
""".trimIndent()
