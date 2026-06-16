package app.amber.feature.subagent

import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over the subagent runtime settings source for SubAgentManager.
 * Android implements this with app.amber.core.settings.prefs.SettingsAggregator;
 * iOS implements it with seeded defaults.
 */
interface SubAgentSettingsSource<T> {
    val settingsFlow: StateFlow<T>
}

/**
 * Abstraction over transcript file storage for SubAgentManager.
 * Android implements with java.io.File; iOS can stub with no-op or in-memory.
 */
interface SubAgentRunStorage {
    /** Create (or get) the transcript file path for a new run. */
    fun newTranscriptPath(runId: String): String

    /** Append a JSONL event line to the transcript file. */
    fun appendEvent(path: String, line: String)

    /** Whether a transcript file exists at [path]. */
    fun transcriptExists(path: String): Boolean
}
