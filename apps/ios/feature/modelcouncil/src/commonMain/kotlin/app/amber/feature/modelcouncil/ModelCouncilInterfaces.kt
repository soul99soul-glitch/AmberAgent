package app.amber.feature.modelcouncil

import app.amber.ai.core.ReasoningLevel
import app.amber.core.settings.Settings
import kotlinx.coroutines.flow.StateFlow
import kotlin.uuid.Uuid

/**
 * Abstraction over the settings source for [ModelCouncilManager].
 * Android implements this with [app.amber.core.settings.prefs.SettingsAggregator];
 * iOS implements it with IosSettingsDefaults (seeded snapshot).
 */
interface ModelCouncilSettingsSource {
    val settingsFlow: StateFlow<Settings>
}

/**
 * Abstraction over transcript file storage for [ModelCouncilManager].
 * Android implements with java.io.File; iOS can stub with no-op or in-memory.
 */
interface ModelCouncilRunStorage {
    /** Create (or get) the transcript file path for a new run. */
    fun newTranscriptPath(runId: String): String

    /** Append a JSONL event line to the transcript file. */
    fun appendEvent(path: String, line: String)

    /** Whether a transcript file exists at [path]. */
    fun transcriptExists(path: String): Boolean
}

/**
 * Abstraction over external CLI runners (e.g. Claude CLI, Gemini CLI).
 * Android implements with TerminalRuntime; iOS has no external CLI support.
 */
interface ExternalCliCouncilRunner {
    suspend fun generate(
        seat: ModelCouncilSeat,
        systemPrompt: String,
        userPrompt: String,
        timeoutMs: Long,
        outputBudgetChars: Int,
        onChunk: (String) -> Unit,
    ): String
}

/**
 * Generate the seat's reply via a model provider.
 * - [onChunk] is invoked with the *cumulative* text every time the provider streams in.
 *   Pass `{ }` if you don't care about live updates.
 * - The returned text is the final (already truncated to budget) text.
 */
interface ModelCouncilTextRunner {
    suspend fun generate(
        settings: Settings,
        modelId: Uuid,
        systemPrompt: String,
        userPrompt: String,
        outputBudgetChars: Int,
        reasoningLevel: ReasoningLevel? = null,
        temperature: Float? = null,
        onChunk: (String) -> Unit = {},
    ): ModelCouncilTextResult
}

data class ModelCouncilTextResult(
    val text: String,
    val warnings: List<String> = emptyList(),
)
