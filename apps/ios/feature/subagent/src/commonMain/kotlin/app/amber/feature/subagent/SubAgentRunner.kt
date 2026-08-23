package app.amber.feature.subagent

import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow

interface SubAgentRunner {
    /**
     * Run a subagent task. [liveText] receives the running assistant text as it streams in;
     * [liveParts] receives the latest message parts so UI can derive render-time presentation
     * from tool outputs without rewriting assistant text.
     * UI code can subscribe via [SubAgentManager.liveTextFlow] to render real-time output.
     * Pass a no-op flow if you don't need live updates.
     */
    suspend fun run(
        settings: Settings,
        definition: SubAgentDefinition,
        task: SubAgentTaskSpec,
        tools: List<Tool>,
        liveText: MutableStateFlow<String>,
        liveParts: MutableStateFlow<List<UIMessagePart>>,
    ): SubAgentResult
}
