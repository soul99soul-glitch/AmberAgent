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
