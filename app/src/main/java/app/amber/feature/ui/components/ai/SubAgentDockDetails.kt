package app.amber.feature.ui.components.ai

import app.amber.ai.ui.UIMessagePart
import app.amber.feature.subagent.SubAgentManager
import app.amber.feature.subagent.SubAgentResult
import app.amber.feature.subagent.SubAgentRun
import app.amber.feature.subagent.ThreadGraphManager
import app.amber.feature.task.AgentTaskSnapshot
import app.amber.feature.task.AgentTaskStatus
import app.amber.feature.task.AgentTaskStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.RandomAccessFile

/** The kind of a real, already-produced subagent stage excerpt. */
enum class SubAgentDockStageKind {
    TOOL,
    REASONING,
    TEXT,
    RESULT,
    FINDING,
    EVIDENCE,
    RISK,
    NEXT_STEP,
    PREVIOUS_RESULT,
}

data class SubAgentDockStage(
    val kind: SubAgentDockStageKind,
    val title: String = "",
    val text: String,
    val isRunning: Boolean = false,
)

/** Detail data is collected only while a details sheet is open. */
data class SubAgentDockDetails(
    val objective: String? = null,
    val summary: String? = null,
    val stages: List<SubAgentDockStage> = emptyList(),
    val output: String = "",
    val previousOutput: String? = null,
    val available: Boolean = false,
)

private data class DockDetailSources(
    val text: Flow<String>?,
    val parts: Flow<List<UIMessagePart>>?,
)

private data class DockDetailsInput(
    val snapshot: AgentTaskSnapshot?,
    val run: SubAgentRun?,
    val liveText: String,
    val liveParts: List<UIMessagePart>,
)

internal data class TranscriptDockDetails(
    val currentOutput: String = "",
    val previousOutput: String? = null,
    val currentResult: SubAgentResult? = null,
    val previousStages: List<SubAgentDockStage> = emptyList(),
)

private const val DETAILS_SAMPLE_MS = 180L
private const val MAX_STAGE_COUNT = 5
private const val MAX_STAGE_TEXT_CHARS = 240
private const val MAX_OBJECTIVE_CHARS = 1_000
private const val MAX_SUMMARY_CHARS = 1_000
private const val MAX_TRANSCRIPT_TAIL_BYTES = 256 * 1024

/**
 * Creates a cold details stream. No live output or transcript is observed until a sheet starts
 * collecting it; the global dock therefore remains metadata-only and cheap during generation.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
internal fun subAgentDockDetailsFlow(
    agentTaskStore: AgentTaskStore,
    subAgentManager: SubAgentManager,
    key: SubAgentDockRunKey,
    runRoot: File,
): Flow<SubAgentDockDetails> {
    val selectedSnapshotFlow = agentTaskStore.tasksFlow
        .map { snapshots -> snapshots.firstOrNull { it.matchesDockRunKey(key) } }
        .distinctUntilChanged()
    val runStateFlow = subAgentManager.runStateFlow(key.taskId)
    return combine(selectedSnapshotFlow, runStateFlow) { _, _ ->
        DockDetailSources(
            text = subAgentManager.liveTextFlow(key.taskId),
            parts = subAgentManager.livePartsFlow(key.taskId),
        )
    }
        // Rebind only when a generation replaces the actual live-flow objects. Lifecycle and
        // timestamp updates below must not keep cancelling the sampling window or its caches.
        .distinctUntilChanged()
        .flatMapLatest { sources ->
            val liveTextFlow = sources.text ?: flowOf("")
            val livePartsFlow = sources.parts ?: flowOf(emptyList())
            var followupSeed: String? = null
            var transcript: TranscriptDockDetails? = null
            var transcriptReadForTerminal = false
            var persistedState: ThreadGraphManager.ThreadGraphState? = null
            var persistedLoaded = false

            combine(
                selectedSnapshotFlow,
                runStateFlow,
                liveTextFlow,
                livePartsFlow,
            ) { snapshot, currentRun, liveText, liveParts ->
                DockDetailsInput(snapshot, currentRun, liveText, liveParts)
            }
                // Throttle the raw stream before any stage extraction or bounded file fallback;
                // the global dock never collects this cold flow in the first place.
                .sample(DETAILS_SAMPLE_MS)
                .mapLatest { input ->
                    val snapshot = input.snapshot
                    if (snapshot == null) {
                        // The task id may have been reused. Once its createdAt changes, this
                        // collector is intentionally unable to see the replacement generation.
                        return@mapLatest SubAgentDockDetails()
                    }
                    val liveRun = input.run?.takeIf { it.matches(snapshot) }
                    val active = snapshot.status.isActiveTaskStatus()
                    if (!persistedLoaded && input.run == null && !active) {
                        persistedState = subAgentManager.persistedState(key.taskId)
                        persistedLoaded = true
                    }
                    val followupSeedNow = active &&
                        liveRun != null &&
                        liveRun.startedAtMs < key.createdAtMs &&
                        liveRun.displayText == input.liveText &&
                        input.liveParts.isEmpty() &&
                        input.liveText.isNotBlank()
                    if (followupSeedNow && followupSeed == null) {
                        followupSeed = input.liveText
                    }
                    val currentLiveText = input.liveText.takeUnless { followupSeedNow }.orEmpty()
                    val needsTranscript = currentLiveText.isBlank() || !active
                    if (needsTranscript && (transcript == null || (!active && !transcriptReadForTerminal))) {
                        transcript = withContext(Dispatchers.IO) {
                            readTranscriptDetails(snapshot, key, runRoot)
                        }
                        if (!active) transcriptReadForTerminal = true
                    }
                    buildDockDetails(
                        snapshot = snapshot,
                        liveRun = liveRun,
                        liveText = currentLiveText,
                        liveParts = input.liveParts,
                        persistedState = persistedState,
                        followupSeed = followupSeed,
                        transcript = transcript ?: TranscriptDockDetails(),
                    )
                }
        }
        // Stage extraction is deliberately bounded and throttled above. The details panel can
        // update while a run streams without making the process-wide dock recompose per token.
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
}

internal fun buildDockDetails(
    snapshot: AgentTaskSnapshot,
    liveRun: SubAgentRun?,
    liveText: String,
    liveParts: List<UIMessagePart>,
    persistedState: ThreadGraphManager.ThreadGraphState?,
    followupSeed: String?,
    transcript: TranscriptDockDetails,
): SubAgentDockDetails {
    val active = snapshot.status.isActiveTaskStatus()
    val objective = if (active) {
        (liveRun?.task?.objective?.takeIf { it.isNotBlank() }
            ?: snapshot.summary?.takeIf { it.isNotBlank() })?.let(::boundedObjective)
    } else {
        null
    }
    val result = liveRun?.result ?: transcript.currentResult
    val summary = when {
        result?.summary?.isNotBlank() == true -> boundedSummary(result.summary)
        snapshot.status == AgentTaskStatus.COMPLETED ->
            snapshot.summary?.takeIf { it.isNotBlank() }?.let(::boundedSummary)
        else -> null
    }
    // A thread node's updatedAt changes on followup/recovery even when its result still belongs
    // to an older generation. Bind the answer to the result's own completion timestamp.
    val persistedFinalAnswer = persistedState
        ?.takeIf { (it.resultFinishedAtMs ?: Long.MIN_VALUE) >= snapshot.createdAtMs }
        ?.finalAnswer.orEmpty()
    val structuredOutput = result?.toStructuredOutput().orEmpty()
    val output = if (active) {
        liveText
    } else {
        // A terminal run's displayText is the full assistant transcript, while liveText is only
        // the latest streaming assistant message. Prefer the terminal/full sources first.
        liveRun?.displayText.orEmpty()
            .ifBlank { transcript.currentOutput }
            .ifBlank { liveText }
            .ifBlank { structuredOutput }
            .ifBlank { persistedFinalAnswer }
    }
    val resultStages = result?.let(::resultStages)
        ?: summary?.let { listOf(SubAgentDockStage(SubAgentDockStageKind.RESULT, text = boundedStageText(it))) }
        .orEmpty()
    val errorStages = listOfNotNull(
        result?.error?.takeIf { it.isNotBlank() }?.let {
            SubAgentDockStage(SubAgentDockStageKind.RISK, text = boundedStageText(it))
        },
        snapshot.error?.takeIf { it.isNotBlank() }?.let {
            SubAgentDockStage(SubAgentDockStageKind.RISK, text = boundedStageText(it))
        },
    )
    val liveStages = extractLiveStages(
        parts = liveParts,
        isRunActive = active,
    )
    val stages = limitDockStages(
        (transcript.previousStages + liveStages + resultStages + errorStages)
            // Raw tool/reasoning entries must not evict readable progress from the recent window.
            .filter { it.kind != SubAgentDockStageKind.TOOL && it.kind != SubAgentDockStageKind.REASONING }
            .deduplicateStages(),
    )
    return SubAgentDockDetails(
        objective = objective,
        summary = summary,
        stages = stages,
        output = output,
        previousOutput = followupSeed ?: transcript.previousOutput,
        available = true,
    )
}

internal fun extractLiveStages(
    parts: List<UIMessagePart>,
    isRunActive: Boolean,
): List<SubAgentDockStage> = buildList {
    val lastTextIndex = parts.indexOfLast { part ->
        part is UIMessagePart.Text && part.text.isNotBlank()
    }
    parts.forEachIndexed { index, part ->
        when (part) {
            is UIMessagePart.Tool -> {
                val output = part.output.asDisplayExcerpt()
                add(
                    SubAgentDockStage(
                        kind = SubAgentDockStageKind.TOOL,
                        title = part.toolName,
                        text = output,
                        isRunning = isRunActive && (!part.isExecuted || part.isPending),
                    )
                )
            }
            is UIMessagePart.Reasoning -> part.reasoning
                .takeIf { it.isNotBlank() }
                ?.let { reasoning ->
                    add(
                        SubAgentDockStage(
                            kind = SubAgentDockStageKind.REASONING,
                            text = boundedStageText(reasoning),
                            isRunning = isRunActive && part.finishedAt == null,
                        )
                    )
                }
            is UIMessagePart.Text -> part.text
                .takeIf { it.isNotBlank() }
                ?.let { text ->
                    add(
                        SubAgentDockStage(
                            kind = SubAgentDockStageKind.TEXT,
                            text = boundedStageText(text),
                            isRunning = isRunActive && index == lastTextIndex,
                        )
                    )
                }
            else -> Unit
        }
    }
}

private fun resultStages(result: SubAgentResult): List<SubAgentDockStage> = buildList {
    result.summary.takeIf { it.isNotBlank() }?.let { summary ->
        add(SubAgentDockStage(SubAgentDockStageKind.RESULT, text = boundedStageText(summary)))
    }
    result.findings.forEach { finding ->
        add(SubAgentDockStage(SubAgentDockStageKind.FINDING, text = boundedStageText(finding)))
    }
    result.evidence.forEach { evidence ->
        add(SubAgentDockStage(SubAgentDockStageKind.EVIDENCE, text = boundedStageText(evidence)))
    }
    result.risks.forEach { risk ->
        add(SubAgentDockStage(SubAgentDockStageKind.RISK, text = boundedStageText(risk)))
    }
    result.recommendedNextSteps.forEach { nextStep ->
        add(SubAgentDockStage(SubAgentDockStageKind.NEXT_STEP, text = boundedStageText(nextStep)))
    }
}

private fun SubAgentResult.toStructuredOutput(): String = buildString {
    summary.takeIf { it.isNotBlank() }?.let(::appendLine)
    findings.forEach { appendLine(it) }
    evidence.forEach { appendLine(it) }
    risks.forEach { appendLine(it) }
    recommendedNextSteps.forEach { appendLine(it) }
}.trim()

private fun previousResultStages(result: SubAgentResult): List<SubAgentDockStage> =
    result.toStructuredOutput()
        .takeIf { it.isNotBlank() }
        ?.let { listOf(SubAgentDockStage(SubAgentDockStageKind.PREVIOUS_RESULT, text = boundedStageText(it))) }
        .orEmpty()

private fun List<UIMessagePart>.asDisplayExcerpt(): String = buildString {
    val limit = MAX_STAGE_TEXT_CHARS * 3
    for (part in this@asDisplayExcerpt) {
        val value = when (part) {
            is UIMessagePart.Text -> part.text
            is UIMessagePart.Reasoning -> part.reasoning
            is UIMessagePart.Tool -> part.toolName
            else -> ""
        }
        if (value.isBlank()) continue
        val remaining = limit - length
        if (remaining <= 0) { append("…"); break }
        append(value.take(remaining))
        if (value.length > remaining) { append("…"); break }
        append(' ')
    }
}.let(::boundedStageText)

private fun List<SubAgentDockStage>.deduplicateStages(): List<SubAgentDockStage> =
    asReversed()
        .distinctBy { Triple(it.kind, it.title, it.text) }
        .asReversed()

private fun limitDockStages(stages: List<SubAgentDockStage>): List<SubAgentDockStage> {
    if (stages.size <= MAX_STAGE_COUNT) return stages
    // A risk must remain visible even when a long result contains many findings/evidence items;
    // the summary field remains separately available to the details UI.
    val risks = stages.filter { it.kind == SubAgentDockStageKind.RISK }.takeLast(1)
    val tail = stages.takeLast(MAX_STAGE_COUNT - risks.size)
    return (risks + tail.filterNot { it.kind == SubAgentDockStageKind.RISK })
        .takeLast(MAX_STAGE_COUNT)
}

private fun boundedStageText(value: String): String {
    val trimmed = value.trim()
    val prefix = trimmed.take(MAX_STAGE_TEXT_CHARS * 3)
    val normalized = prefix.replace(Regex("\\s+"), " ")
    return if (normalized.length <= MAX_STAGE_TEXT_CHARS && trimmed.length <= prefix.length) {
        normalized
    } else {
        normalized.take(MAX_STAGE_TEXT_CHARS - 1) + "…"
    }
}

private fun boundedObjective(value: String): String = value.trim().take(MAX_OBJECTIVE_CHARS)

private fun boundedSummary(value: String): String = value.trim().take(MAX_SUMMARY_CHARS)

internal fun AgentTaskSnapshot.matchesDockRunKey(key: SubAgentDockRunKey): Boolean =
    type == "subagent" && taskId == key.taskId && createdAtMs == key.createdAtMs

private fun SubAgentRun.matches(snapshot: AgentTaskSnapshot): Boolean =
    runId == snapshot.taskId &&
        parentConversationId.toString() == snapshot.sourceConversationId &&
        updatedAtMs >= snapshot.createdAtMs

private fun AgentTaskStatus.isActiveTaskStatus(): Boolean =
    this == AgentTaskStatus.QUEUED || this == AgentTaskStatus.RUNNING

private val transcriptJson = Json { ignoreUnknownKeys = true }

/**
 * Read only the app-owned transcript tail referenced by this exact task snapshot, using the same
 * canonical-root and bounded-tail rules as the existing transcript reader. This adds bounded
 * previous terminal result extraction for followups without opening unrelated sessions or the
 * whole file.
 */
private fun readTranscriptDetails(
    snapshot: AgentTaskSnapshot,
    key: SubAgentDockRunKey,
    runRoot: File,
): TranscriptDockDetails {
    val path = snapshot.outputRef?.path ?: snapshot.outputPath ?: return TranscriptDockDetails()
    val root = runCatching { runRoot.canonicalFile }.getOrNull() ?: return TranscriptDockDetails()
    val transcript = runCatching { File(path).canonicalFile }.getOrNull() ?: return TranscriptDockDetails()
    if (!transcript.isFile || transcript.extension != "jsonl") return TranscriptDockDetails()
    if (!transcript.path.startsWith(root.path + File.separator)) return TranscriptDockDetails()
    if (transcript.name != "${key.taskId}.jsonl") return TranscriptDockDetails()
    return runCatching {
        val lines = transcript.tailText().lineSequence().filter { it.isNotBlank() }.toList()
        val finished = lines.mapNotNull { line ->
            val event = runCatching { transcriptJson.parseToJsonElement(line) as? JsonObject }.getOrNull()
                ?: return@mapNotNull null
            if (event["event"]?.jsonPrimitive?.contentOrNull != "finished") return@mapNotNull null
            val payload = event["payload"] as? JsonObject ?: return@mapNotNull null
            if (payload["run_id"]?.jsonPrimitive?.contentOrNull != key.taskId) return@mapNotNull null
            val updatedAt = payload["updated_at_ms"]?.jsonPrimitive?.longOrNull
                ?: event["created_at_ms"]?.jsonPrimitive?.longOrNull
                ?: return@mapNotNull null
            val displayText = (payload["display_text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val result = (payload["result"] as? JsonPrimitive)?.contentOrNull?.let { raw ->
                runCatching { transcriptJson.decodeFromString<SubAgentResult>(raw) }.getOrNull()
            }
            TranscriptFinished(updatedAt, displayText, result)
        }
        val current = finished.lastOrNull { it.updatedAtMs >= key.createdAtMs }
        val previous = finished.filter { it.updatedAtMs < key.createdAtMs }
        TranscriptDockDetails(
            currentOutput = current?.displayText.orEmpty(),
            previousOutput = previous.lastOrNull()?.displayText?.takeIf { it.isNotBlank() },
            currentResult = current?.result,
            previousStages = previous.flatMap { entry ->
                entry.result?.let(::previousResultStages).orEmpty()
            },
        )
    }.getOrDefault(TranscriptDockDetails())
}

private data class TranscriptFinished(
    val updatedAtMs: Long,
    val displayText: String,
    val result: SubAgentResult?,
)

private fun File.tailText(): String {
    val length = length()
    val start = (length - MAX_TRANSCRIPT_TAIL_BYTES).coerceAtLeast(0)
    val size = (length - start).coerceAtMost(MAX_TRANSCRIPT_TAIL_BYTES.toLong()).toInt()
    if (size <= 0) return ""
    val bytes = ByteArray(size)
    RandomAccessFile(this, "r").use { file ->
        file.seek(start)
        file.readFully(bytes)
    }
    val text = bytes.toString(Charsets.UTF_8)
    return if (start > 0) text.substringAfter('\n', "") else text
}
