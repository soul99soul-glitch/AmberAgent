package app.amber.core.context

import me.rerere.ai.ui.UIMessagePart

object ToolResultCompactor {
    fun summarize(parts: List<UIMessagePart>, maxChars: Int = 8_000): String {
        val raw = parts.joinToString("\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                is UIMessagePart.Tool -> "nested_tool:${part.toolName}:${summarize(part.output, maxChars / 2)}"
                else -> part.toString()
            }
        }
        return raw.takeMiddle(maxChars)
    }
}
