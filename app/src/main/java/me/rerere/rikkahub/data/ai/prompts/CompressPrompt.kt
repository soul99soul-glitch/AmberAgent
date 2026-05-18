package me.rerere.rikkahub.data.ai.prompts

internal val DEFAULT_COMPRESS_PROMPT = """
    You are a conversation compression assistant. Compress the following conversation into a structured continuation summary.

    Requirements:
    1. Preserve key facts, decisions, and important context that would be needed to continue the conversation
    2. Keep the summary in the same language as the original conversation
    3. Target approximately {target_tokens} tokens
    4. Output one short human-readable preamble sentence first, then valid JSON exactly as requested below
    5. Format the JSON as context information that can be used to continue the conversation
    6. Use {locale} language
    7. Do not use Markdown headings like "[Summary of previous conversation]" unless they are inside the preamble sentence

    {additional_context}

    <conversation>
    {content}
    </conversation>
""".trimIndent()
