package app.amber.feature.modelcouncil

import app.amber.ai.core.ReasoningLevel
import app.amber.core.infra.AppScope
import app.amber.core.settings.Settings
import app.amber.feature.task.AgentTaskStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/**
 * iOS factory for [ModelCouncilManager] with stub adapters.
 *
 * Builds a ModelCouncilManager wired with iOS-compatible stubs:
 * - Settings source: seeded snapshot from IosSettingsDefaults (NOT Android DataStore)
 * - Model runner: returns placeholder text (NOT real model inference)
 * - External CLI: unsupported (iOS has no Termux)
 * - Run storage: in-memory (no transcript files)
 * - Agent task store: real KMP AgentTaskStore (documents dir)
 *
 * This proves the start/read call chain works end-to-end on iOS. The stub
 * model runner means start() will return a runId and seats will "complete"
 * with placeholder text — NOT real council reasoning. Real model inference
 * needs a KMP ProviderManager bridge (future work).
 */
object IosCouncilFactory {

    fun create(documentsDir: String): ModelCouncilManager {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }
        val agentTaskStore = AgentTaskStore(filesDirPath = documentsDir, json = json)
        val settings = app.amber.core.settings.IosSettingsDefaults.defaultSeededSettings()

        return ModelCouncilManager(
            appScope = AppScope(),
            settingsSource = StubSettingsSource(settings),
            json = json,
            modelRunner = StubModelRunner(),
            externalCliRunner = StubExternalCliRunner(),
            agentTaskStore = agentTaskStore,
            runStorage = StubRunStorage(),
        )
    }

    /**
     * Create a ModelCouncilManager with a REAL OpenAI-compatible provider runner.
     * Uses OpenAIKmpProvider (KMP commonMain, already exported) to make actual
     * model API calls for council seat generation + synthesis.
     *
     * @param baseUrl API base URL (e.g. "https://api.openai.com/v1")
     * @param apiKey API key (will be sent as Bearer token)
     * @param modelId Model ID to use for generation (e.g. "gpt-4o")
     *
     * HONESTY: this makes REAL network API calls. The council will produce real
     * model-generated text (not stub). Requires valid API key + network.
     */
    fun createWithRealProvider(
        documentsDir: String,
        baseUrl: String,
        apiKey: String,
        modelId: String,
    ): ModelCouncilManager {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }
        val agentTaskStore = AgentTaskStore(filesDirPath = documentsDir, json = json)
        val settings = app.amber.core.settings.IosSettingsDefaults.defaultSeededSettings()

        return ModelCouncilManager(
            appScope = AppScope(),
            settingsSource = StubSettingsSource(settings),
            json = json,
            modelRunner = RealOpenAIModelRunner(baseUrl = baseUrl, apiKey = apiKey, modelId = modelId),
            externalCliRunner = StubExternalCliRunner(),
            agentTaskStore = agentTaskStore,
            runStorage = StubRunStorage(),
        )
    }

    /**
     * Build a minimal start() input JsonObject from a plain objective string.
     * Called from Swift to avoid complex JsonObject construction interop.
     */
    fun startInput(objective: String): kotlinx.serialization.json.JsonObject {
        return kotlinx.serialization.json.buildJsonObject {
            put("objective", kotlinx.serialization.json.JsonPrimitive(objective))
        }
    }

    /**
     * Extract the "run_id" string from a start() result JsonObject.
     */
    fun extractRunId(result: kotlinx.serialization.json.JsonObject): String {
        return (result["run_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "(unknown)"
    }

    /**
     * Extract the "status" string from a result JsonObject.
     */
    fun extractStatus(result: kotlinx.serialization.json.JsonObject): String {
        return (result["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "(unknown)"
    }
}

private class StubSettingsSource(private val snapshot: Settings) : ModelCouncilSettingsSource {
    override val settingsFlow: StateFlow<Settings> = MutableStateFlow(snapshot)
}

private class StubModelRunner : ModelCouncilTextRunner {
    override suspend fun generate(
        settings: Settings,
        modelId: Uuid,
        systemPrompt: String,
        userPrompt: String,
        outputBudgetChars: Int,
        reasoningLevel: ReasoningLevel?,
        temperature: Float?,
        onChunk: (String) -> Unit,
    ): ModelCouncilTextResult {
        val reply = "[iOS stub] 模型推理未接入。这是 ModelCouncilManager 调用链验证，不是真实议会输出。"
        onChunk(reply)
        return ModelCouncilTextResult(text = reply, warnings = listOf("iOS stub runner — no real model inference"))
    }
}

/**
 * Real ModelCouncilTextRunner backed by OpenAIKmpProvider. Makes actual API
 * calls to generate council seat replies. Requires valid baseUrl/apiKey/modelId.
 */
private class RealOpenAIModelRunner(
    private val baseUrl: String,
    private val apiKey: String,
    private val modelId: String,
) : ModelCouncilTextRunner {
    private val provider = app.amber.ai.provider.openai.OpenAIKmpProvider()

    override suspend fun generate(
        settings: Settings,
        modelId: Uuid,
        systemPrompt: String,
        userPrompt: String,
        outputBudgetChars: Int,
        reasoningLevel: ReasoningLevel?,
        temperature: Float?,
        onChunk: (String) -> Unit,
    ): ModelCouncilTextResult {
        val providerSetting = app.amber.ai.provider.ProviderSetting.OpenAI(
            id = kotlin.uuid.Uuid.random(),
            name = "iOS Council Provider",
            baseUrl = baseUrl,
            apiKey = apiKey,
            models = emptyList(),
            chatCompletionsPath = "/chat/completions",
        )
        val messages = listOf(
            app.amber.ai.ui.UIMessage(
                role = app.amber.ai.core.MessageRole.SYSTEM,
                parts = listOf(app.amber.ai.ui.UIMessagePart.Text(systemPrompt)),
            ),
            app.amber.ai.ui.UIMessage(
                role = app.amber.ai.core.MessageRole.USER,
                parts = listOf(app.amber.ai.ui.UIMessagePart.Text(userPrompt)),
            ),
        )
        val params = app.amber.ai.provider.TextGenerationParams(
            model = app.amber.ai.provider.Model(modelId = this.modelId),
            temperature = temperature,
            maxTokens = (outputBudgetChars / 4).coerceAtLeast(256),
        )
        val result = runCatching {
            provider.generateText(providerSetting, messages, params)
        }
        return result.fold(
            onSuccess = { chunk ->
                val text = chunk.choices.firstOrNull()?.message?.parts
                    ?.filterIsInstance<app.amber.ai.ui.UIMessagePart.Text>()
                    ?.joinToString("") { it.text }
                    ?: ""
                onChunk(text)
                ModelCouncilTextResult(text = text.take(outputBudgetChars))
            },
            onFailure = { error ->
                val errMsg = "Provider error: ${error.message ?: error::class.simpleName}"
                ModelCouncilTextResult(text = errMsg, warnings = listOf(errMsg))
            },
        )
    }
}

private class StubExternalCliRunner : ExternalCliCouncilRunner {
    override suspend fun generate(
        seat: ModelCouncilSeat,
        systemPrompt: String,
        userPrompt: String,
        timeoutMs: Long,
        outputBudgetChars: Int,
        onChunk: (String) -> Unit,
    ): String {
        return "[iOS] 外部 CLI 不可用"
    }
}

private class StubRunStorage : ModelCouncilRunStorage {
    private val events = mutableMapOf<String, MutableList<String>>()

    override fun newTranscriptPath(runId: String): String = "ios-memory://council/$runId"

    override fun appendEvent(path: String, line: String) {
        events.getOrPut(path) { mutableListOf() }.add(line)
    }

    override fun transcriptExists(path: String): Boolean = events.containsKey(path)
}
