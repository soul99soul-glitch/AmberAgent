package app.amber.feature.novel.runtime

import app.amber.ai.core.MessageRole
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.ProviderManager
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.findModelById
import app.amber.core.settings.findProvider
import app.amber.core.settings.getCurrentChatModel
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.novel.domain.NovelError
import app.amber.feature.novel.model.NovelProjectModelPolicy
import app.amber.feature.novel.model.NovelRunId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * Production novel model adapter. Tools/Memory/Skills/MCP/Workspace are intentionally empty.
 * Does not use ChatService.
 */
class AndroidNovelModelAdapter(
    private val settingsAggregator: SettingsAggregator,
    private val providerManager: ProviderManager,
) : NovelModelRunning {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val mutex = Mutex()

    override suspend fun resolveModel(policy: NovelProjectModelPolicy): NovelResolvedModel {
        val settings = settingsAggregator.settingsFlow.filterNot { it.init }.first()
        return when (policy) {
            NovelProjectModelPolicy.Global -> {
                val model = settings.getCurrentChatModel()
                    ?: throw NovelError.ModelUnavailable("Global chat model is not configured")
                val provider = model.findProvider(settings.providers)
                    ?: throw NovelError.ModelUnavailable("Provider missing for global model")
                NovelResolvedModel(
                    providerID = provider.id.toString(),
                    ownerProviderID = provider.id.toString(),
                    modelID = model.id.toString(),
                    wireModelID = model.modelId,
                    displayName = model.displayName.ifBlank { model.modelId },
                )
            }
            is NovelProjectModelPolicy.Fixed -> {
                val modelUuid = runCatching { Uuid.parse(policy.modelID) }.getOrNull()
                    ?: throw NovelError.ModelUnavailable("Project model is unavailable; please reselect")
                val model = settings.findModelById(modelUuid)
                    ?: throw NovelError.ModelUnavailable("Project model is unavailable; please reselect")
                val provider = model.findProvider(settings.providers)
                    ?: throw NovelError.ModelUnavailable("Project model is unavailable; please reselect")
                if (provider.id.toString() != policy.providerID) {
                    throw NovelError.ModelUnavailable("Project model is unavailable; please reselect")
                }
                NovelResolvedModel(
                    providerID = provider.id.toString(),
                    ownerProviderID = policy.providerID,
                    modelID = model.id.toString(),
                    wireModelID = model.modelId,
                    displayName = model.displayName.ifBlank { model.modelId },
                )
            }
        }
    }

    override fun start(request: NovelModelRequest): Flow<NovelModelEvent> = channelFlow {
        // Register a parent Job *before* launch so cancel() in the start window is not a no-op.
        val runJob = Job(coroutineContext[Job])
        mutex.withLock { jobs[request.runID.rawValue] = runJob }
        try {
            launch(runJob) {
                try {
                    val settings = settingsAggregator.settingsFlow.filterNot { it.init }.first()
                    val modelUuid = Uuid.parse(request.model.modelID)
                    val model = settings.findModelById(modelUuid)
                        ?: throw NovelError.ModelUnavailable("Model not found: ${request.model.modelID}")
                    val provider = model.findProvider(settings.providers)
                        ?: throw NovelError.ModelUnavailable("Provider not found for model")
                    val providerImpl = providerManager.getProviderByType(provider)
                    val messages = request.messages.map { msg ->
                        when (msg.role) {
                            NovelModelMessage.Role.System -> UIMessage.system(msg.content)
                            NovelModelMessage.Role.User -> UIMessage.user(msg.content)
                            NovelModelMessage.Role.Assistant -> UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(UIMessagePart.Text(msg.content)),
                            )
                        }
                    }
                    val params = TextGenerationParams(
                        model = model,
                        tools = emptyList(),
                        temperature = request.parameters.temperature?.toFloat(),
                        topP = request.parameters.topP?.toFloat(),
                        maxTokens = request.parameters.maxOutputTokens,
                        reasoningLevel = ReasoningLevel.OFF,
                        customHeaders = model.customHeaders,
                        customBody = model.customBodies,
                    )
                    providerImpl.streamText(
                        providerSetting = provider,
                        messages = messages,
                        params = params,
                    ).collect { chunk ->
                        val delta = chunk.choices.firstOrNull()?.delta?.parts
                            ?.filterIsInstance<UIMessagePart.Text>()
                            ?.joinToString("") { it.text }
                            .orEmpty()
                        if (delta.isNotEmpty()) {
                            send(NovelModelEvent.TextDelta(delta))
                        }
                    }
                    send(NovelModelEvent.Completed)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    send(
                        NovelModelEvent.Failed(
                            code = "provider_error",
                            message = error.message ?: "Provider failed",
                            isRetryable = true,
                        ),
                    )
                }
            }.join()
        } finally {
            mutex.withLock { jobs.remove(request.runID.rawValue) }
            runJob.complete()
        }
    }

    override fun cancel(runId: NovelRunId) {
        jobs[runId.rawValue]?.cancel()
    }
}
