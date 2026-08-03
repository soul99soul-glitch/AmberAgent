package app.amber.feature.novel.runtime

import app.amber.feature.novel.model.NovelProjectModelPolicy
import app.amber.feature.novel.model.NovelRunId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Test-only scripted model. Production must not depend on this class.
 */
class ScriptedNovelModelAdapter(
    private val scripts: Map<NovelModelPurpose, String> = emptyMap(),
    private val defaultText: String = "Scripted response.",
    private val chunkSize: Int = 32,
) : NovelModelRunning {
    private val cancelled = mutableSetOf<String>()

    override suspend fun resolveModel(policy: NovelProjectModelPolicy): NovelResolvedModel =
        NovelResolvedModel(
            providerID = "scripted-provider",
            ownerProviderID = "scripted-provider",
            modelID = "scripted-model",
            wireModelID = "scripted-model",
            displayName = "Scripted",
            contextWindowTokens = 128_000,
        )

    override fun start(request: NovelModelRequest): Flow<NovelModelEvent> = flow {
        val full = scripts[request.purpose] ?: defaultText
        var index = 0
        while (index < full.length) {
            if (request.runID.rawValue in cancelled) {
                emit(NovelModelEvent.Failed("cancelled", "Cancelled", isRetryable = false))
                return@flow
            }
            val end = (index + chunkSize).coerceAtMost(full.length)
            emit(NovelModelEvent.TextDelta(full.substring(index, end)))
            index = end
        }
        emit(NovelModelEvent.Completed)
    }

    override fun cancel(runId: NovelRunId) {
        cancelled += runId.rawValue
    }
}
