package app.amber.feature.subagent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart

const val SUBAGENT_REPORT_TOOL_NAME = "subagent_report"

/**
 * Subagent-only result channel. The visible answer keeps streaming as Markdown for the user,
 * while this tool captures the compact payload the parent agent should read.
 */
class SubAgentReportCapture {
    var latest: SubAgentResult? = null
        private set

    val hasReport: Boolean
        get() = latest != null

    fun tool(): Tool = Tool(
        name = SUBAGENT_REPORT_TOOL_NAME,
        description = """
            Record the structured result for the supervisor. Keep writing normal Markdown for the
            human live panel; call this once near the end with the concise facts the main agent
            should consume. Do not paste JSON or machine-only wrappers into visible text.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("summary", stringProp("One concise answer for the supervisor. Required."))
                    put("findings", arrayProp("Key findings or completed work items."))
                    put("evidence", arrayProp("Evidence, source ids, files, commands, or observations supporting the findings."))
                    put("risks", arrayProp("Known risks, blockers, or uncertainty. Empty if none."))
                    put("recommended_next_steps", arrayProp("Concrete next steps for the supervisor. Empty if none."))
                    put("confidence", stringProp("Optional confidence label such as low, medium, or high."))
                    put("error", stringProp("Optional error or blocker text if the task could not be completed."))
                },
                required = listOf("summary")
            )
        },
        needsApproval = false,
        allowsAutoApproval = true,
        execute = execute@{ input ->
            val result = input.toSubAgentResult()
            if (result.summary.isBlank() && result.findings.isEmpty()) {
                return@execute listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("status", "failed")
                            put("message", "subagent_report requires a non-empty summary or findings.")
                        }.toString()
                    )
                )
            }
            latest = result
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("status", "ok")
                        put("message", "Structured subagent report recorded. Finish with normal human-readable text.")
                        put("summary_chars", result.summary.length)
                        put("findings_count", result.findings.size)
                        put("evidence_count", result.evidence.size)
                        put("risks_count", result.risks.size)
                        put("recommended_next_steps_count", result.recommendedNextSteps.size)
                    }.toString()
                )
            )
        }
    )

    fun resultOrFallback(displayText: String): SubAgentResult =
        latest ?: SubAgentResult(
            status = SubAgentRunStatus.COMPLETED,
            summary = displayText.trim().bounded(SUMMARY_MAX_CHARS)
                .ifBlank { "Subagent completed without structured report or text output." },
            risks = listOf("Subagent finished without calling $SUBAGENT_REPORT_TOOL_NAME; summary was derived from visible text."),
        )

    private fun JsonElement.toSubAgentResult(): SubAgentResult {
        val obj = runCatching { jsonObject }.getOrElse { JsonObject(emptyMap()) }
        val error = obj.string("error").bounded(ERROR_MAX_CHARS)
        return SubAgentResult(
            status = if (error.isNotBlank()) SubAgentRunStatus.FAILED else SubAgentRunStatus.COMPLETED,
            summary = obj.string("summary").bounded(SUMMARY_MAX_CHARS),
            findings = obj.stringList("findings"),
            evidence = obj.stringList("evidence"),
            risks = obj.stringList("risks"),
            confidence = obj.string("confidence").bounded(CONFIDENCE_MAX_CHARS),
            recommendedNextSteps = obj.stringList("recommended_next_steps"),
            error = error,
        )
    }

    private fun JsonObject.string(name: String): String =
        (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()

    private fun JsonObject.stringList(name: String): List<String> {
        val value = this[name] ?: return emptyList()
        val raw = when (value) {
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(value.contentOrNull)
            else -> emptyList()
        }
        return raw.map { it.trim().bounded(LIST_ITEM_MAX_CHARS) }
            .filter { it.isNotBlank() }
            .take(LIST_MAX_ITEMS)
    }

    private fun String.bounded(maxChars: Int): String =
        if (length <= maxChars) this else take(maxChars).trimEnd()

    private fun stringProp(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun arrayProp(description: String) = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", buildJsonObject { put("type", "string") })
    }

    private companion object {
        const val SUMMARY_MAX_CHARS = 4_000
        const val CONFIDENCE_MAX_CHARS = 64
        const val ERROR_MAX_CHARS = 1_000
        const val LIST_MAX_ITEMS = 12
        const val LIST_ITEM_MAX_CHARS = 1_000
    }
}
