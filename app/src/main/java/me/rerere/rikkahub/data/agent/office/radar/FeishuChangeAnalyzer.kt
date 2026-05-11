package me.rerere.rikkahub.data.agent.office.radar

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.DEFAULT_AUTO_MODEL_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.resolveTaskChatModel
import me.rerere.rikkahub.data.db.entity.FeishuDocChangeEntity
import me.rerere.rikkahub.data.db.entity.FeishuDocDependencyEntity

@Serializable
data class ChangeAnalysis(
    val changeSummary: String = "",
    val whyItMatters: String = "",
    val changedSections: List<String> = emptyList(),
    val suggestedActions: List<SuggestedAction> = emptyList(),
    val downstreamImpacts: List<DownstreamImpact> = emptyList(),
    val confidence: Float = 0f,
)

@Serializable
data class SuggestedAction(
    val type: String = "",
    val desc: String = "",
)

@Serializable
data class DownstreamImpact(
    val doc: String = "",
    val section: String = "",
    val reason: String = "",
)

class FeishuChangeAnalyzer(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val json: Json,
) {
    suspend fun analyze(
        change: FeishuDocChangeEntity,
        docTitle: String,
        changedSections: List<String>,
        downstreams: List<FeishuDocDependencyEntity>,
    ): ChangeAnalysis? {
        val settings = settingsStore.settingsFlow.value
        val worker = settings.agentRuntime.memoryWorker
        if (!worker.enabled) return null

        val model = when {
            worker.daydreamModelId != DEFAULT_AUTO_MODEL_ID ->
                settings.resolveTaskChatModel(worker.daydreamModelId)
            worker.modelId != DEFAULT_AUTO_MODEL_ID ->
                settings.resolveTaskChatModel(worker.modelId)
            else -> settings.resolveTaskChatModel(settings.chatModelId)
        } ?: settings.resolveTaskChatModel(settings.chatModelId) ?: return null

        val provider = model.findProvider(settings.providers) ?: return null

        val downstreamHint = if (downstreams.isNotEmpty()) {
            "\nDownstream documents that may need syncing:\n${
                downstreams.joinToString("\n") { 
                    "- ${it.downstreamLabel.ifBlank { it.downstreamUrl }}: ${it.relationNote}" 
                }
            }"
        } else ""

        val prompt = buildString {
            appendLine("Analyze this Feishu document change and produce structured JSON.")
            appendLine()
            appendLine("Document: $docTitle")
            appendLine("Change: +${change.addedChars} / -${change.removedChars} chars")
            appendLine("Changed sections: ${changedSections.joinToString(", ")}")
            appendLine("Diff summary: ${change.diffSummary.orEmpty()}")
            appendLine(downstreamHint)
            appendLine()
            appendLine("Return ONLY valid JSON:")
            appendLine(buildJsonSchemaExample())
        }

        val response = providerManager.getProviderByType(provider).generateText(
            providerSetting = provider,
            messages = listOf(UIMessage.user(prompt)),
            params = TextGenerationParams(
                model = model,
                reasoningLevel = worker.daydreamReasoningLevel,
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            ),
        )
        val text = response.choices.firstOrNull()?.message?.toText().orEmpty()
        return parseAnalysis(text)
    }

    private fun buildJsonSchemaExample(): String = """
        {
          "change_summary": "one-sentence summary in user's language",
          "why_it_matters": "why this change is important",
          "changed_sections": ["section heading"],
          "suggested_actions": [
            {"type": "review|update_downstream|ask_owner|ignore", "desc": "action description"}
          ],
          "downstream_impacts": [
            {"doc": "downstream document name", "section": "affected section", "reason": "why affected"}
          ],
          "confidence": 0.85
        }
    """.trimIndent()

    private fun parseAnalysis(raw: String): ChangeAnalysis? {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
            .let { text ->
                val start = text.indexOf('{')
                val end = text.lastIndexOf('}')
                if (start >= 0 && end > start) text.substring(start, end + 1) else text
            }
        return try {
            val root = json.parseToJsonElement(cleaned).jsonObject
            ChangeAnalysis(
                changeSummary = root["change_summary"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                whyItMatters = root["why_it_matters"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                changedSections = root["changed_sections"]?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonPrimitive?.contentOrNull },
                suggestedActions = root["suggested_actions"]?.jsonArray.orEmpty().mapNotNull { item ->
                    val obj = item.jsonObject
                    SuggestedAction(
                        type = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        desc = obj["desc"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                },
                downstreamImpacts = root["downstream_impacts"]?.jsonArray.orEmpty().mapNotNull { item ->
                    val obj = item.jsonObject
                    DownstreamImpact(
                        doc = obj["doc"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        section = obj["section"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        reason = obj["reason"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                },
                confidence = root["confidence"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f,
            )
        } catch (_: Exception) {
            null
        }
    }
}
