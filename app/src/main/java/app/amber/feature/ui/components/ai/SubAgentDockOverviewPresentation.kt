package app.amber.feature.ui.components.ai

/** Keep the overview readable; diagnostic details remain available in the original output. */
internal fun SubAgentDockDetails.readableOverview(): SubAgentDockDetails {
    val readableStages = stages.mapNotNull { stage ->
        if (stage.kind == SubAgentDockStageKind.TOOL || stage.kind == SubAgentDockStageKind.REASONING) {
            return@mapNotNull null
        }
        val text = readableDockText(stage.text).take(320)
        if (text.isBlank()) return@mapNotNull null
        stage.copy(title = readableDockText(stage.title).take(60), text = text)
    }.distinctBy { it.text }.takeLast(5)
    val readableSummary = readableDockText(summary.orEmpty()).take(700)
        .ifBlank { if (readableStages.isEmpty()) readableDockText(output).take(700) else "" }
    return copy(
        objective = null,
        summary = readableSummary.takeIf { it.isNotBlank() },
        stages = readableStages.filterNot {
            it.text == readableSummary || (it.kind == SubAgentDockStageKind.RESULT && readableSummary.isNotBlank())
        },
        output = "",
        previousOutput = null,
    )
}

internal fun readableDockText(text: String): String = text
    .replace(DOCK_CODE_BLOCK, "")
    .lineSequence()
    .map(String::trim)
    .filter { line ->
        line.isNotBlank() && !line.startsWith('>') &&
            !line.startsWith('{') && !line.startsWith('}') &&
            !line.startsWith("[{") && !line.startsWith("[\"") &&
            !DOCK_PROTOCOL_LINE.containsMatchIn(line) && !DOCK_IDENTIFIER.matches(line)
    }
    .joinToString("\n")
    .replace(Regex("(?m)^#{1,6}\\s*"), "")
    .replace("**", "")
    .replace("`", "")
    .trim()
    .takeUnless { it.lowercase() in setOf("done", "completed", "success", "ok", "已完成", "完成") }
    .orEmpty()

private val DOCK_CODE_BLOCK = Regex("```[\\s\\S]*?(?:```|$)")
private val DOCK_PROTOCOL_LINE = Regex(
    "(?i)\\b(?:tool_call_id|tool_name|jsonrpc|subagent_\\w+|subagentreport|functions\\.\\w+)\\b|" +
        "^\\s*\\\"(?:arguments|parameters|function|tool_calls)\\\"\\s*:",
)
private val DOCK_IDENTIFIER = Regex("[a-z][a-z0-9]*(?:_[a-z0-9]+)+")
