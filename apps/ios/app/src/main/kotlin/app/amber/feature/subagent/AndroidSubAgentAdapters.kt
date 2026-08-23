package app.amber.feature.subagent

import android.content.Context
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Android adapter: wraps [SettingsAggregator] as a [SubAgentSettingsSource]
 * for the KMP [SubAgentManager].
 */
class AndroidSubAgentSettingsSource(
    private val aggregator: SettingsAggregator,
) : SubAgentSettingsSource<Settings> {
    override val settingsFlow: StateFlow<Settings> get() = aggregator.settingsFlow
}

/**
 * Android adapter: implements [SubAgentRunStorage] using java.io.File
 * (mirrors the original SubAgentManager transcript persistence).
 */
class AndroidSubAgentRunStorage(
    context: Context,
) : SubAgentRunStorage {
    private val runDir = File(context.filesDir, "amberagent/subagents/runs").also { it.mkdirs() }

    override fun newTranscriptPath(runId: String): String =
        File(runDir, "$runId.jsonl").absolutePath

    override fun appendEvent(path: String, line: String) {
        File(path).appendText(line)
    }

    override fun transcriptExists(path: String): Boolean =
        File(path).exists()
}
