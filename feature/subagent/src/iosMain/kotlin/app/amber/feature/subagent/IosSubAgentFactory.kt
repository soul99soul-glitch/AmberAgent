package app.amber.feature.subagent

import app.amber.ai.core.MessageRole
import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.openai.OpenAIKmpProvider
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.infra.AppScope
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import app.amber.core.settings.getCurrentAssistant
import app.amber.core.settings.getCurrentChatModel
import app.amber.feature.task.AgentTaskStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/**
 * iOS factory for [SubAgentManager] with iOS-compatible adapters.
 */
object IosSubAgentFactory {

    fun create(documentsDir: String): SubAgentManager {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }
        val agentTaskStore = AgentTaskStore(filesDirPath = documentsDir, json = json)
        val settings = iosSmokeSettings()

        return SubAgentManager(
            appScope = AppScope(),
            settingsSource = StubSettingsSource(settings),
            json = json,
            runner = StubSubAgentRunner(),
            agentTaskStore = agentTaskStore,
            sessionAccessGrantStore = app.amber.feature.history.SessionAccessGrantStore(),
            runStorage = StubRunStorage(),
        )
    }

    /**
     * Create a SubAgentManager with a REAL OpenAI-compatible provider runner.
     * HONESTY: this makes REAL network API calls. Requires valid API key + network.
     */
    fun createWithRealProvider(
        documentsDir: String,
        baseUrl: String,
        apiKey: String,
        modelId: String,
    ): SubAgentManager {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }
        val agentTaskStore = AgentTaskStore(filesDirPath = documentsDir, json = json)
        val settings = iosSmokeSettings()

        return SubAgentManager(
            appScope = AppScope(),
            settingsSource = StubSettingsSource(settings),
            json = json,
            runner = RealOpenAISubAgentRunner(baseUrl = baseUrl, apiKey = apiKey, modelId = modelId),
            agentTaskStore = agentTaskStore,
            sessionAccessGrantStore = app.amber.feature.history.SessionAccessGrantStore(),
            runStorage = StubRunStorage(),
        )
    }

    fun startInput(
        objective: String,
        subagentId: String = "ios-smoke-runner",
        outputFormat: String = "Return a concise Markdown report with summary, findings, evidence, risks, and next steps.",
        toolsAndSources: String = "No external tools are granted; rely only on the provided context.",
        boundaries: String = "Stay within the delegated task. Do not ask the user follow-up questions. Do not spawn subagents.",
        context: String = "",
    ): JsonObject = buildJsonObject {
        put("task", buildJsonObject {
            put("objective", objective)
            put("output_format", outputFormat)
            put("tools_and_sources", toolsAndSources)
            put("boundaries", boundaries)
            if (context.isNotBlank()) put("context", context)
        })
        put("custom_subagent", buildJsonObject {
            put("name", subagentId)
            put("description", "Use when iOS needs a bounded temporary subagent to verify the SubAgentManager runtime bridge.")
            put(
                "system_prompt",
                "You are an iOS runtime verification subagent. Boundaries: stay within the assigned objective, use only granted tools, do not spawn subagents, and do not ask the user. Report output as: concise summary, findings, evidence, risks, and recommended next steps. Report once and stop."
            )
            put("tool_profile", "none")
        })
    }

    fun extractRunId(result: JsonObject): String =
        (result["run_id"] as? JsonPrimitive)?.content ?: "(unknown)"

    fun extractStatus(result: JsonObject): String =
        (result["status"] as? JsonPrimitive)?.content ?: "(unknown)"
}

private fun iosSmokeSettings(): Settings =
    app.amber.core.settings.IosSettingsDefaults.defaultSeededSettings().let { settings ->
        settings.copy(
            agentRuntime = settings.agentRuntime.copy(
                subAgent = settings.agentRuntime.subAgent.copy(enabled = true),
            ),
        )
    }

private class StubSettingsSource(private val snapshot: Settings) : SubAgentSettingsSource<Settings> {
    override val settingsFlow: StateFlow<Settings> = MutableStateFlow(snapshot)
}

private class StubSubAgentRunner : SubAgentRunner {
    override suspend fun run(
        settings: Settings,
        definition: SubAgentDefinition,
        task: SubAgentTaskSpec,
        tools: List<Tool>,
        liveText: MutableStateFlow<String>,
        liveParts: MutableStateFlow<List<UIMessagePart>>,
    ): SubAgentResult {
        val reply = "[iOS stub] SubAgent 模型推理未接入。这是 SubAgentManager 调用链验证，不是真实子代理输出。"
        liveText.value = reply
        liveParts.value = listOf(UIMessagePart.Text(reply))
        return SubAgentResult(
            status = SubAgentRunStatus.COMPLETED,
            summary = reply,
            risks = listOf("iOS stub runner — no real model inference"),
            confidence = "stub",
        )
    }
}

private class RealOpenAISubAgentRunner(
    private val baseUrl: String,
    private val apiKey: String,
    private val modelId: String,
) : SubAgentRunner {
    private val provider = OpenAIKmpProvider()

    override suspend fun run(
        settings: Settings,
        definition: SubAgentDefinition,
        task: SubAgentTaskSpec,
        tools: List<Tool>,
        liveText: MutableStateFlow<String>,
        liveParts: MutableStateFlow<List<UIMessagePart>>,
    ): SubAgentResult {
        val providerSetting = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "iOS SubAgent Provider",
            baseUrl = baseUrl,
            apiKey = apiKey,
            models = emptyList(),
            chatCompletionsPath = "/chat/completions",
        )
        val selectedModel = definition.modelId?.let { settings.findModelById(it)?.modelId }
            ?: settings.getCurrentChatModel()?.modelId
            ?: modelId
        val assistant = settings.getCurrentAssistant().toIsolatedSubAgentAssistant(definition)
        val messages = listOf(
            UIMessage(
                role = MessageRole.SYSTEM,
                parts = listOf(UIMessagePart.Text(assistant.systemPrompt)),
            ),
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text(buildTaskPrompt(definition, task))),
            ),
        )
        val params = TextGenerationParams(
            model = Model(modelId = selectedModel),
            temperature = definition.temperature ?: assistant.temperature,
            maxTokens = (definition.outputBudgetChars / 4).coerceAtLeast(256),
        )
        return runCatching {
            provider.generateText(providerSetting, messages, params)
        }.fold(
            onSuccess = { chunk ->
                val text = chunk.choices.firstOrNull()?.message?.parts
                    ?.filterIsInstance<UIMessagePart.Text>()
                    ?.joinToString("") { it.text }
                    .orEmpty()
                    .take(definition.outputBudgetChars)
                liveText.value = text
                liveParts.value = listOf(UIMessagePart.Text(text))
                SubAgentResult(
                    status = SubAgentRunStatus.COMPLETED,
                    summary = text.ifBlank { "SubAgent completed with empty provider output." }.take(1_000),
                    findings = listOf(text).filter { it.isNotBlank() },
                    confidence = "provider",
                )
            },
            onFailure = { error ->
                SubAgentResult(
                    status = SubAgentRunStatus.FAILED,
                    error = "Provider error: ${error.message ?: error::class.simpleName}",
                )
            },
        )
    }

    private fun buildTaskPrompt(definition: SubAgentDefinition, task: SubAgentTaskSpec): String = """
        You are running as subagent `${definition.id}`.

        Objective:
        ${task.objective}

        Output format:
        ${task.outputFormat}

        Tools and sources guidance:
        ${task.toolsAndSources}

        Boundaries:
        ${task.boundaries}

        Context from parent:
        ${task.context.ifBlank { "(none)" }}

        Return only the useful result for the supervisor. Do not ask the user follow-up questions.
    """.trimIndent()
}

private class StubRunStorage : SubAgentRunStorage {
    private val events = mutableMapOf<String, MutableList<String>>()

    override fun newTranscriptPath(runId: String): String = "ios-memory://subagent/$runId"

    override fun appendEvent(path: String, line: String) {
        events.getOrPut(path) { mutableListOf() }.add(line)
    }

    override fun transcriptExists(path: String): Boolean = events.containsKey(path)
}
