package app.amber.feature.novel.runtime

import app.amber.feature.novel.model.NovelProjectModelPolicy
import app.amber.feature.novel.model.NovelRunId
import kotlinx.coroutines.flow.Flow

data class NovelResolvedModel(
    val providerID: String,
    val ownerProviderID: String,
    val modelID: String,
    val wireModelID: String,
    val displayName: String,
    val contextWindowTokens: Int? = null,
)

enum class NovelModelPurpose {
    QuickStart,
    Discussion,
    Prose,
    Polish,
    StateExtraction,
    StateRebuild,
    DriftCheck,
}

data class NovelModelMessage(
    val role: Role,
    val content: String,
) {
    enum class Role { System, User, Assistant }
}

data class NovelModelParameters(
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxOutputTokens: Int? = null,
    val reasoningLevel: String = "off",
) {
    fun evidenceDictionary(): Map<String, String> = buildMap {
        put("reasoningLevel", reasoningLevel)
        temperature?.let { put("temperature", it.toString()) }
        topP?.let { put("topP", it.toString()) }
        maxOutputTokens?.let { put("maxOutputTokens", it.toString()) }
    }
}

data class NovelModelRequest(
    val runID: NovelRunId,
    val model: NovelResolvedModel,
    val purpose: NovelModelPurpose,
    val messages: List<NovelModelMessage>,
    val parameters: NovelModelParameters = NovelModelParameters(),
)

sealed interface NovelModelEvent {
    data class TextDelta(val text: String) : NovelModelEvent
    data class TextReplacement(val text: String) : NovelModelEvent
    data object Completed : NovelModelEvent
    data class Failed(val code: String, val message: String, val isRetryable: Boolean) : NovelModelEvent
}

interface NovelModelRunning {
    suspend fun resolveModel(policy: NovelProjectModelPolicy): NovelResolvedModel
    fun start(request: NovelModelRequest): Flow<NovelModelEvent>
    fun cancel(runId: NovelRunId)
}
