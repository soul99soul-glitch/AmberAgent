package app.amber.feature.subagent

import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.ai.GenerationTerminal
import app.amber.core.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local mailbox for the non-serializable runtime objects of one
 * sub-agent turn (the serializable identity travels in [SubAgentTurnInput]).
 * Keyed by the runner run id — NOT the thread id: a thread runs one turn
 * per runner run, and the payload dies with that turn.
 *
 * The manager registers the payload before `AgentRunner.launch` and removes
 * it once the turn settles; the handler resolves it from `RunScope.runId`.
 */
class SubAgentTurnPayloads {

    class Payload(
        val threadId: String,
        val settings: Settings,
        val definition: SubAgentDefinition,
        val task: SubAgentTaskSpec,
        val tools: List<Tool>,
        val liveText: MutableStateFlow<String>,
        val liveParts: MutableStateFlow<List<UIMessagePart>>,
        /**
         * Run id the generation kernel should use for its durable path
         * (ledger + terminal CAS). Null when the thread graph is off — the
         * legacy in-memory behavior keeps the kernel undurable then.
         */
        val kernelRunId: String?,
        val onTerminal: (suspend (GenerationTerminal) -> Unit)?,
        val consumeSteerMessages: suspend () -> List<UIMessage>,
        val previousAnswer: String,
        /**
         * Step 6: optional narrowed sandbox policy for this child turn. Null
         * keeps the parent-permissive default (v1: no producer narrows yet —
         * the seam exists and is test-proven). Forwarded to
         * [SubAgentRunner.run] as `childPolicy` and intersected with the
         * parent policy there.
         */
        val executionPolicy: app.amber.feature.runtime.ExecutionPolicy? = null,
        /**
         * P1-7: the parent run's sandbox policy as of the sub-agent start
         * (subagent_start / subagent_followup tool execution). Null when the
         * producer cannot know the parent policy — the turn agent then runs
         * the child under the permissive default (v1 behavior). Forwarded to
         * [SubAgentRunner.run] as `parentPolicy`; the runner narrows the
         * child onto it, so a child can never widen the parent. Process-local
         * like the rest of the payload: never serialized.
         */
        val parentPolicy: app.amber.feature.runtime.ExecutionPolicy? = null,
    )

    private val payloads = ConcurrentHashMap<String, Payload>()

    fun register(runId: String, payload: Payload) {
        payloads[runId] = payload
    }

    fun resolve(runId: String): Payload? = payloads[runId]

    fun remove(runId: String) {
        payloads.remove(runId)
    }

    /** Test/diagnostic view of the live payloads. */
    fun snapshot(): Map<String, Payload> = payloads.toMap()
}
