package app.amber.core.ai.prompts

val DEFAULT_SUGGESTION_PROMPT = """
    I will provide you with some chat content in the `<content>` block, including conversations between the User and the AI assistant.
    You need to act as the **User** to reply to the assistant, generating 3~5 appropriate and contextually relevant responses to the assistant.

    Rules:
    1. If the assistant explicitly offers choices or next actions, prefer copying those choices as suggestions.
    2. Reply directly with suggestions, do not add any formatting, and separate suggestions with newlines, no need to add markdown list formats.
    3. Use {locale} language.
    4. Ensure each suggestion is valid.
    5. Each suggestion should usually stay within 24 characters unless copying an explicit option from the assistant.
    6. Imitate the user's previous conversational style.
    7. Act as a User, not an Assistant!

    <content>
    {content}
    </content>
""".trimIndent()
