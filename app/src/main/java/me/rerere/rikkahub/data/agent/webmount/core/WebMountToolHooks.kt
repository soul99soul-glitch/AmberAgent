package me.rerere.rikkahub.data.agent.webmount.core

import kotlinx.serialization.json.JsonElement
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.AgentToolActivityStore

/**
 * Per-adapter wrapper around [AgentToolActivityStore]. Generalization of
 * `ICloudDriveTools.trackICloudTool` so every WebMount tool reports its run
 * (start / complete / fail) with consistent runtime metadata.
 */
class WebMountToolHooks(
    private val activityStore: AgentToolActivityStore,
    val stationId: String,
    val runtimeLabel: String,
    val workspace: String,
) {
    suspend fun track(
        toolName: String,
        title: String,
        input: JsonElement,
        block: suspend () -> List<UIMessagePart>,
    ): List<UIMessagePart> {
        val toolCallId = activityStore.startTool(
            toolName = toolName,
            title = title,
            inputPreview = input.toString(),
            runtime = runtimeLabel,
            workspace = workspace,
        )
        return try {
            val result = block()
            activityStore.complete(toolCallId, previewText(result))
            result
        } catch (error: Throwable) {
            activityStore.fail(toolCallId, error)
            throw error
        }
    }

    private fun previewText(parts: List<UIMessagePart>): String =
        parts.joinToString("\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                else -> part.toString()
            }
        }.takeLast(1_600)
}
