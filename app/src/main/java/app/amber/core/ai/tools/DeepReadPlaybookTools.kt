package app.amber.core.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import app.amber.feature.board.hotlist.deepread.DeepReadPlaybookRepository

class DeepReadPlaybookTools(
    private val repository: DeepReadPlaybookRepository,
) {
    fun getTools(): List<Tool> = listOf(readTool(), updateTool(), restoreDefaultTool(), restorePreviousTool())

    private fun readTool() = Tool(
        name = "deep_read_playbook_read",
        description = "Read the local Deep Read Playbook markdown and revision. Use when the user asks to inspect Deep Read rules or preferences.",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        allowsAutoApproval = true,
        execute = {
            val snapshot = repository.read()
            listOf(UIMessagePart.Text(snapshot.toJson().toString()))
        },
    )

    private fun updateTool() = Tool(
        name = "deep_read_playbook_update",
        description = "Update the local Deep Read Playbook only after the user explicitly asks to change Deep Read rules. Requires base_revision, change_summary, and full updated_markdown.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("base_revision", stringProp("Revision returned by deep_read_playbook_read."))
                    put("change_summary", stringProp("Short explanation of the requested change."))
                    put("updated_markdown", stringProp("Full replacement markdown for the Playbook."))
                },
                required = listOf("base_revision", "change_summary", "updated_markdown"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            val obj = input.objectOrEmpty()
            val result = repository.update(
                baseRevision = obj.string("base_revision").orEmpty(),
                changeSummary = obj.string("change_summary").orEmpty(),
                updatedMarkdown = obj.string("updated_markdown").orEmpty(),
            )
            listOf(UIMessagePart.Text(result.toToolJson("updated").toString()))
        },
    )

    private fun restoreDefaultTool() = Tool(
        name = "deep_read_playbook_restore_default",
        description = "Restore the built-in default Deep Read Playbook after explicit user request.",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = {
            val snapshot = repository.restoreDefault()
            listOf(UIMessagePart.Text(snapshot.toJson("restored_default").toString()))
        },
    )

    private fun restorePreviousTool() = Tool(
        name = "deep_read_playbook_restore_previous",
        description = "Restore the latest previous Deep Read Playbook snapshot after explicit user request.",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = {
            val result = repository.restorePrevious()
            listOf(UIMessagePart.Text(result.toToolJson("restored_previous").toString()))
        },
    )
}

private fun app.amber.feature.board.hotlist.deepread.DeepReadPlaybookSnapshot.toJson(
    status: String = "ok",
) = buildJsonObject {
    put("status", status)
    put("revision", revision)
    put("updated_at", updatedAt)
    put("markdown", markdown)
}

private fun Result<app.amber.feature.board.hotlist.deepread.DeepReadPlaybookSnapshot>.toToolJson(
    successStatus: String,
) = fold(
    onSuccess = { it.toJson(successStatus) },
    onFailure = { error ->
        buildJsonObject {
            put("status", "rejected")
            put("error", error.message ?: error::class.simpleName.orEmpty())
        }
    },
)

private fun stringProp(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun kotlinx.serialization.json.JsonElement.objectOrEmpty(): JsonObject =
    runCatching { jsonObject }.getOrDefault(JsonObject(emptyMap()))

private fun JsonObject.string(name: String): String? =
    get(name)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
