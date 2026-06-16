package app.amber.feature.modelcouncil

import android.content.Context
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Android adapter: wraps [SettingsAggregator] as a [ModelCouncilSettingsSource]
 * for the KMP [ModelCouncilManager].
 */
class AndroidModelCouncilSettingsSource(
    private val aggregator: SettingsAggregator,
) : ModelCouncilSettingsSource {
    override val settingsFlow: StateFlow<Settings> get() = aggregator.settingsFlow
}

/**
 * Android adapter: implements [ModelCouncilRunStorage] using java.io.File
 * (mirrors the original ModelCouncilManager transcript persistence).
 */
class AndroidModelCouncilRunStorage(
    context: Context,
) : ModelCouncilRunStorage {
    private val runDir = File(context.filesDir, "amberagent/model-council/runs").also { it.mkdirs() }

    override fun newTranscriptPath(runId: String): String =
        File(runDir, "$runId.jsonl").absolutePath

    override fun appendEvent(path: String, line: String) {
        File(path).appendText(line)
    }

    override fun transcriptExists(path: String): Boolean =
        File(path).exists()
}
