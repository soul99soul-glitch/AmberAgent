package app.amber.feature.modelcouncil

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import app.amber.core.infra.AppScope
import app.amber.feature.task.AgentTaskSnapshot
import app.amber.feature.task.AgentTaskOutputRef
import app.amber.feature.task.AgentTaskRetryPolicy
import app.amber.feature.task.AgentTaskStatus
import app.amber.feature.task.AgentTaskStore
import app.amber.feature.task.toQueueState
import app.amber.feature.terminal.TerminalRuntime
import app.amber.feature.terminal.TerminalRuntimeKind
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.findModelById
import app.amber.core.settings.findProvider
import java.io.File
import java.time.Instant
import kotlin.uuid.Uuid

interface ModelCouncilTextRunner {
    /**
     * Generate the seat's reply.
     * - [onChunk] is invoked with the *cumulative* text every time the provider streams in.
     *   Pass `{ }` if you don't care about live updates.
     * - The returned text is the final (already truncated to budget) text.
     */
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

class ModelCouncilManager(
    context: Context,
    private val appScope: AppScope,
    private val settingsStore: SettingsAggregator,
    private val json: Json,
    private val modelRunner: ModelCouncilTextRunner,
    private val externalCliRunner: ExternalCliModelCouncilRunner,
    private val agentTaskStore: AgentTaskStore,
) {
    private val runDir = File(context.filesDir, "amberagent/model-council/runs").also { it.mkdirs() }
    private val runs = java.util.concurrent.ConcurrentHashMap<String, RuntimeRun>()

    /**
     * Per-run, per-seat live text streams. Each seat's MutableStateFlow receives the seat's
     * accumulating reply as it streams from the provider; UI subscribes via [liveTextFlow] /
     * [liveTextFlows] to render real-time tabs in the run sheet.
     *
     * Synthesizer uses the special key [SYNTHESIZER_SEAT_KEY].
     *
     * Kept after the run finishes so a sheet opened later can still see the final text.
     * Capped at [LIVE_TEXT_CAP] active runs by evicting the oldest finished entries.
     */
    private val seatLiveTextFlows = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<String>>>()

    suspend fun start(input: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val settings = settingsStore.settingsFlow.value
        val councilSetting = settings.agentRuntime.modelCouncil
        val task = runCatching {
            ModelCouncilValidator.parseTask(input, settings, councilSetting)
        }.getOrElse {
            return@withContext errorPayload("invalid_model_council_task", it.message ?: it.toString())
        }
        val synthesisModelId = runCatching {
            ModelCouncilValidator.resolveSynthesisModelId(settings, councilSetting)
        }.getOrElse {
            return@withContext errorPayload("invalid_synthesis_model", it.message ?: it.toString())
        }

        val now = Instant.now().toEpochMilli()
        val runId = Uuid.random().toString()
        val transcript = File(runDir, "$runId.jsonl")
        val run = ModelCouncilRun(
            runId = runId,
            status = ModelCouncilRunStatus.RUNNING,
            mode = task.mode,
            seats = task.seats,
            task = task,
            transcriptPath = transcript.absolutePath,
            startedAtMs = now,
        )
        val runtimeRun = RuntimeRun(run)
        runs[runId] = runtimeRun
        // Pre-create a flow per seat (and the synthesizer) so the run sheet can subscribe
        // immediately, even before the first chunk arrives. Live flows survive after finish so
        // a freshly opened sheet still sees the final text.
        val perSeatFlows = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<String>>()
        run.seats.forEach { seat -> perSeatFlows[seat.seatId] = MutableStateFlow("") }
        perSeatFlows[SYNTHESIZER_SEAT_KEY] = MutableStateFlow("")
        seatLiveTextFlows[runId] = perSeatFlows
        capLiveTextFlows()
        agentTaskStore.register(run.toAgentTaskSnapshot(), cancel = {
            cancel(runId)
            true
        })
        appendEvent(runtimeRun, "started", runToPayload(run))

        runtimeRun.job = appScope.launch(Dispatchers.IO) {
            val result = try {
                withTimeoutOrNull(settings.effectiveCouncilTotalTimeoutMs(councilSetting, task)) {
                    executeCouncil(
                        settings = settings,
                        setting = councilSetting,
                        synthesisModelId = synthesisModelId,
                        runtimeRun = runtimeRun,
                    )
                } ?: failResult("Model Council timed out.")
            } catch (error: CancellationException) {
                return@launch
            } catch (error: Throwable) {
                failResult(error.message ?: error::class.java.simpleName)
            }
            finish(runId, result.first, result.second)
        }
        runToPayload(run)
    }

    suspend fun read(runId: String): JsonObject = withContext(Dispatchers.IO) {
        val run = runs[runId]?.snapshot ?: return@withContext readMissingRun(runId)
        runToPayload(run)
    }

    suspend fun wait(runId: String, waitTimeoutMs: Long): JsonObject = withContext(Dispatchers.IO) {
        val setting = settingsStore.settingsFlow.value.agentRuntime.modelCouncil
        val maxWaitMs = (setting.totalTimeoutMs + 30_000L)
            .coerceAtLeast(60_000L)
            .coerceAtMost(15 * 60_000L)
        val effectiveWaitMs = if (waitTimeoutMs > 0L) {
            waitTimeoutMs.coerceAtMost(maxWaitMs)
        } else {
            DEFAULT_MODEL_COUNCIL_WAIT_TIMEOUT_MS.coerceAtMost(maxWaitMs)
        }
        val deadline = System.currentTimeMillis() + effectiveWaitMs
        while (System.currentTimeMillis() < deadline) {
            val current = runs[runId]?.snapshot ?: return@withContext readMissingRun(runId)
            if (!current.status.running) return@withContext runToPayload(current)
            delay(250)
        }
        val current = runs[runId]?.snapshot ?: return@withContext readMissingRun(runId)
        if (!current.status.running) {
            runToPayload(current)
        } else {
            buildJsonObject {
                runToPayload(current).forEach { (key, value) -> put(key, value) }
                put("wait_status", "still_running")
                put("wait_timeout_ms", effectiveWaitMs)
                put(
                    "message",
                    "Model Council is still running. This is not a failure; call model_council_read or model_council_wait again to see new seat outputs.",
                )
            }
        }
    }

    suspend fun cancel(runId: String): JsonObject = withContext(Dispatchers.IO) {
        val runtimeRun = runs[runId] ?: return@withContext readMissingRun(runId)
        runtimeRun.job?.cancel()
        finish(
            runId,
            ModelCouncilRunStatus.CANCELLED,
            ModelCouncilResult(error = "Model Council run was cancelled."),
        )
        runToPayload(runtimeRun.snapshot)
    }

    /**
     * UI-facing live stream of a council seat's accumulating reply. `seatId` may be a real seat id
     * or [SYNTHESIZER_SEAT_KEY] for the synthesizer pane. Null = unknown runId or seatId.
     *
     * **Completion signal**: this flow does NOT carry a "done" marker. UI should observe
     * [snapshot] / [read] in parallel to know when the run finished.
     */
    fun liveTextFlow(runId: String, seatId: String): StateFlow<String>? =
        seatLiveTextFlows[runId]?.get(seatId)?.asStateFlow()

    /** All live flows for a run, keyed by seatId (+ [SYNTHESIZER_SEAT_KEY]). Null if run unknown. */
    fun liveTextFlows(runId: String): Map<String, StateFlow<String>>? =
        seatLiveTextFlows[runId]?.mapValues { it.value.asStateFlow() }

    /** Snapshot of a known run, or null if it was never started or was already evicted. */
    fun snapshot(runId: String): ModelCouncilRun? = runs[runId]?.snapshot

    /**
     * Drop oldest finished entries when over [LIVE_TEXT_CAP]. Active runs are skipped (the runner
     * still holds references to write into them). Iterates [seatLiveTextFlows] keys (not [runs])
     * so orphaned entries — flows whose snapshot was already evicted elsewhere — also get reclaimed.
     */
    private fun capLiveTextFlows() {
        if (seatLiveTextFlows.size <= LIVE_TEXT_CAP) return
        val candidates = seatLiveTextFlows.keys.mapNotNull { id ->
            val snap = runs[id]?.snapshot
            when {
                snap == null -> id to 0L  // orphan
                snap.status.running -> null
                else -> id to snap.updatedAtMs
            }
        }.sortedBy { it.second }
        val toDrop = seatLiveTextFlows.size - LIVE_TEXT_CAP
        candidates.take(toDrop).forEach { (id, _) -> seatLiveTextFlows.remove(id) }
    }

    fun runtimeSummary(): JsonObject {
        val settings = settingsStore.settingsFlow.value
        val setting = settings.agentRuntime.modelCouncil
        val synthesisModelId = setting.synthesisModelId ?: settings.chatModelId
        val synthesisModelInfo = settings.describeCouncilProviderModel(synthesisModelId)
        return buildJsonObject {
            put("enabled", setting.enabled)
            put("default_seat_count", setting.defaultSeats.size)
            put("auto_injected_core_seats", 3)
            put("seat_composition_note", "Effective seat count at run time = 3 core (supporter/opponent/judge, always auto-injected) + user lens defaults + any extra_lens passed in tool call, deduped by role id and capped at max_seats.")
            put("max_seats", setting.maxSeats)
            put("default_rounds", setting.defaultRounds)
            put("max_rounds", setting.maxRounds.coerceAtLeast(DEFAULT_MODEL_COUNCIL_MAX_ROUNDS))
            put("seat_timeout_ms", setting.seatTimeoutMs)
            put("total_timeout_ms", setting.totalTimeoutMs)
            put("output_budget_chars", setting.outputBudgetChars)
            put("show_seat_outputs", setting.showSeatOutputs)
            put("recommended_wait_timeout_ms", DEFAULT_MODEL_COUNCIL_WAIT_TIMEOUT_MS)
            put("synthesis_model_id", synthesisModelId.toString())
            put("synthesis_model_name", synthesisModelInfo.modelName)
            put("synthesis_provider_name", synthesisModelInfo.providerName)
            put("running", runs.values.count { it.snapshot.status.running })
            putJsonArray("default_seats") {
                setting.defaultSeats.forEach { seat ->
                    add(encoded(seat))
                }
            }
        }
    }

    fun reportMarkdown(runId: String, title: String): String {
        val run = runs[runId]?.snapshot ?: error("Unknown active model council run_id: $runId")
        val result = run.result ?: error("Model Council run has no result yet.")
        return buildString {
            appendLine("# ${title.ifBlank { "Model Council Report" }}")
            appendLine()
            appendLine("- run_id: `${run.runId}`")
            appendLine("- mode: `${run.mode.name.lowercase()}`")
            appendLine("- status: `${run.status.name.lowercase()}`")
            appendLine()
            appendLine("## Final Recommendation")
            appendLine()
            appendLine(result.finalRecommendation.ifBlank { result.error.ifBlank { "No final recommendation." } })
            appendLine()
            appendLine("## Consensus")
            appendLines(result.consensus)
            appendLine()
            appendLine("## Conflicts")
            appendLines(result.conflicts)
            appendLine()
            appendLine("## Strongest Evidence")
            appendLines(result.strongestEvidence)
            appendLine()
            appendLine("## Risks")
            appendLines(result.risks)
            appendLine()
            appendLine("## Per Seat Summaries")
            appendLines(result.perSeatSummaries)
            if (result.warnings.isNotEmpty()) {
                appendLine()
                appendLine("## Warnings")
                appendLines(result.warnings)
            }
            appendLine()
            appendLine("## Raw Turns")
            run.turns.forEach { turn ->
                appendLine()
                appendLine("### Round ${turn.round} · ${turn.seatName} · ${turn.modelLabel()} · ${turn.status.name.lowercase()}")
                appendLine()
                turn.warnings.forEach { warning -> appendLine("> $warning") }
                if (turn.warnings.isNotEmpty()) appendLine()
                appendLine(turn.content.ifBlank { turn.error })
            }
        }
    }

    private suspend fun executeCouncil(
        settings: Settings,
        setting: ModelCouncilRuntimeSetting,
        synthesisModelId: Uuid,
        runtimeRun: RuntimeRun,
    ): Pair<ModelCouncilRunStatus, ModelCouncilResult> {
        val task = runtimeRun.snapshot.task
        val roundOne = runRound(
            settings = settings,
            setting = setting,
            runtimeRun = runtimeRun,
            round = 1,
            promptForSeat = { seat -> openingPrompt(task, seat) },
        )
        var allTurns = roundOne
        if (task.mode == ModelCouncilMode.DEBATE) {
            var round = 2
            while (round <= task.rounds && allTurns.any { it.status == ModelCouncilRunStatus.COMPLETED }) {
                val priorTurns = allTurns
                val currentRound = round
                val roundTurns = runRound(
                    settings = settings,
                    setting = setting,
                    runtimeRun = runtimeRun,
                    round = currentRound,
                    promptForSeat = { seat ->
                        if (currentRound >= 3 && currentRound == task.rounds) {
                            finalPositionPrompt(task, seat, priorTurns)
                        } else {
                            responsePrompt(task, seat, priorTurns)
                        }
                    },
                )
                allTurns += roundTurns
                round += 1
            }
        }

        val completed = allTurns.filter { it.status == ModelCouncilRunStatus.COMPLETED && it.content.isNotBlank() }
        if (completed.isEmpty()) {
            return failResultFromTurns(allTurns)
        }
        val synthesis = synthesize(settings, setting, synthesisModelId, task, allTurns, runtimeRun.snapshot.runId)
        val status = if (synthesis.failed || allTurns.any { it.status != ModelCouncilRunStatus.COMPLETED }) {
            ModelCouncilRunStatus.PARTIAL_FAILED
        } else {
            ModelCouncilRunStatus.COMPLETED
        }
        return status to synthesis.result
    }

    private suspend fun runRound(
        settings: Settings,
        setting: ModelCouncilRuntimeSetting,
        runtimeRun: RuntimeRun,
        round: Int,
        promptForSeat: (ModelCouncilSeat) -> String,
    ): List<ModelCouncilTurn> = supervisorScope {
        val runId = runtimeRun.snapshot.runId
        val seatFlows = seatLiveTextFlows[runId]
        // Derive prior text from the canonical `runtimeRun.snapshot.turns` (the source of truth)
        // instead of reading from the live flow. The live flow could in theory be
        // cleared/reformatted by a future change; turns are append-only.
        val priorPrefixBySeat: Map<String, String> = if (round > 1) {
            runtimeRun.snapshot.seats.associate { seat ->
                val priorTurns = runtimeRun.snapshot.turns
                    .filter { it.seatId == seat.seatId && it.round < round }
                    .sortedBy { it.round }
                val priorText = priorTurns.joinToString("\n\n") { turn ->
                    "--- 第 ${turn.round} 轮 ---\n\n${turn.content}"
                }
                seat.seatId to if (priorText.isNotEmpty()) {
                    "$priorText\n\n--- 第 $round 轮 ---\n\n"
                } else {
                    "--- 第 $round 轮 ---\n\n"
                }
            }
        } else {
            runtimeRun.snapshot.seats.associate { seat ->
                seat.seatId to "--- 第 $round 轮 ---\n\n"
            }
        }
        val providerGateByKey = runtimeRun.snapshot.seats
            .groupingBy { seat -> settings.describeCouncilSeatModel(seat).providerGateKey(seat) }
            .eachCount()
            .filterValues { it > MODEL_COUNCIL_PROVIDER_PARALLELISM }
            .mapValues { Semaphore(MODEL_COUNCIL_PROVIDER_PARALLELISM) }
        runtimeRun.snapshot.seats.map { seat ->
            async {
                val systemPrompt = seatSystemPrompt(seat)
                val seatFlow = seatFlows?.get(seat.seatId)
                val priorPrefix = priorPrefixBySeat[seat.seatId].orEmpty()
                if (priorPrefix.isNotEmpty() && seatFlow?.value != priorPrefix) {
                    seatFlow?.value = priorPrefix
                }
                val modelInfo = settings.describeCouncilSeatModel(seat)
                val providerGate = providerGateByKey[modelInfo.providerGateKey(seat)]
                val seatTimeoutMs = setting.seatTimeoutMs.coerceAtLeast(1_000L)
                val result = runCatching {
                    val generate: suspend () -> ModelCouncilTextResult = {
                        generateSeatReply(
                            settings = settings,
                            setting = setting,
                            seat = seat,
                            systemPrompt = systemPrompt,
                            userPrompt = promptForSeat(seat),
                            onChunk = { running ->
                                val nextLiveText = priorPrefix + running
                                if (seatFlow?.value != nextLiveText) {
                                    seatFlow?.value = nextLiveText
                                }
                            },
                        )
                    }
                    val timedGenerate: suspend () -> ModelCouncilTextResult = {
                        withTimeout(seatTimeoutMs) { generate() }
                    }
                    if (providerGate != null) {
                        providerGate.withPermit { timedGenerate() }
                    } else {
                        timedGenerate()
                    }
                }
                result.fold(
                    onSuccess = { content ->
                        ModelCouncilTurn(
                            round = round,
                            seatId = seat.seatId,
                            seatName = seat.name,
                            role = seat.role,
                            modelId = seat.modelId,
                            modelName = modelInfo.modelName,
                            providerName = modelInfo.providerName,
                            status = ModelCouncilRunStatus.COMPLETED,
                            content = content.text.take(seat.outputBudgetChars),
                            warnings = content.warnings,
                        )
                    },
                    onFailure = { error ->
                        ModelCouncilTurn(
                            round = round,
                            seatId = seat.seatId,
                            seatName = seat.name,
                            role = seat.role,
                            modelId = seat.modelId,
                            modelName = modelInfo.modelName,
                            providerName = modelInfo.providerName,
                            status = if (error is kotlinx.coroutines.TimeoutCancellationException) {
                                ModelCouncilRunStatus.TIMED_OUT
                            } else {
                                ModelCouncilRunStatus.FAILED
                            },
                            error = modelInfo.decorateError(error.message ?: error::class.java.simpleName),
                        )
                    },
                ).also { turn ->
                    appendTurn(runtimeRun, turn)
                }
            }
        }.awaitAll()
    }

    private suspend fun synthesize(
        settings: Settings,
        setting: ModelCouncilRuntimeSetting,
        synthesisModelId: Uuid,
        task: ModelCouncilTaskSpec,
        turns: List<ModelCouncilTurn>,
        runId: String,
    ): ModelCouncilSynthesisResult {
        val prompt = synthesisPrompt(task, turns)
        val synthesisModelInfo = settings.describeCouncilProviderModel(synthesisModelId)
        val synthFlow = seatLiveTextFlows[runId]?.get(SYNTHESIZER_SEAT_KEY)
        var synthesisError = ""
        val generated = runCatching {
            withTimeout(setting.seatTimeoutMs.coerceAtLeast(1_000L)) {
                modelRunner.generate(
                    settings = settings,
                    modelId = synthesisModelId,
                    systemPrompt = "你是 AmberAgent 的 Model Council 裁判。只综合证据，不引入未给出的事实。",
                    userPrompt = prompt,
                    outputBudgetChars = setting.outputBudgetChars,
                    reasoningLevel = ReasoningLevel.OFF,
                    temperature = null,
                    onChunk = { running -> synthFlow?.value = running },
                )
            }
        }.getOrElse { error ->
            synthesisError = "Synthesis failed (${synthesisModelInfo.label()}): ${error.message ?: error::class.java.simpleName}"
            ModelCouncilTextResult(text = synthesisError, warnings = emptyList())
        }
        val result = ModelCouncilResult(
            consensus = listOf("See final recommendation for synthesized consensus."),
            conflicts = turns.filter { it.status != ModelCouncilRunStatus.COMPLETED }
                .map { "${it.seatName}: ${it.error}" },
            strongestEvidence = turns.filter { it.status == ModelCouncilRunStatus.COMPLETED }
                .take(4)
                .map { "${it.seatName}: ${it.content.take(360)}" },
            risks = turns.filter { it.status != ModelCouncilRunStatus.COMPLETED }
                .map { "${it.seatName} did not complete." },
            finalRecommendation = generated.text.take(setting.outputBudgetChars),
            perSeatSummaries = turns.map { turn ->
                "${turn.seatName} (${turn.status.name.lowercase()}): ${(turn.content.ifBlank { turn.error }).take(700)}"
            },
            warnings = generated.warnings + turns.flatMap { turn ->
                turn.warnings.map { warning -> "${turn.seatName}: $warning" }
            },
            error = synthesisError,
        )
        return ModelCouncilSynthesisResult(
            result = result,
            failed = synthesisError.isNotBlank(),
        )
    }

    private suspend fun generateSeatReply(
        settings: Settings,
        setting: ModelCouncilRuntimeSetting,
        seat: ModelCouncilSeat,
        systemPrompt: String,
        userPrompt: String,
        onChunk: (String) -> Unit,
    ): ModelCouncilTextResult = when (seat.runnerType) {
        ModelCouncilSeatRunner.PROVIDER_MODEL -> modelRunner.generate(
            settings = settings,
            modelId = seat.modelId,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            outputBudgetChars = seat.outputBudgetChars,
            reasoningLevel = seat.reasoningLevel ?: ReasoningLevel.OFF,
            temperature = seat.temperature,
            onChunk = onChunk,
        )

        ModelCouncilSeatRunner.EXTERNAL_CLI -> ModelCouncilTextResult(
            text = externalCliRunner.generate(
                seat = seat,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                timeoutMs = setting.seatTimeoutMs.coerceAtLeast(1_000L),
                outputBudgetChars = seat.outputBudgetChars,
                onChunk = onChunk,
            ),
        )
    }

    private fun appendTurn(runtimeRun: RuntimeRun, turn: ModelCouncilTurn) {
        val next = synchronized(runtimeRun) {
            val current = runtimeRun.snapshot
            if (!current.status.running) return@synchronized null
            current.copy(
                turns = current.turns + turn,
                updatedAtMs = Instant.now().toEpochMilli(),
            ).also { runtimeRun.snapshot = it }
        } ?: return
        appScope.launch(Dispatchers.IO) {
            agentTaskStore.update(
                taskId = next.runId,
                status = AgentTaskStatus.RUNNING,
                summary = "${turn.seatName}: ${(turn.content.ifBlank { turn.error }).take(700)}",
                cancelCapability = true,
            )
        }
        appendEvent(runtimeRun, "turn", turnToPayload(next, turn))
    }

    private fun finish(runId: String, status: ModelCouncilRunStatus, result: ModelCouncilResult) {
        val runtimeRun = runs[runId] ?: return
        val next = synchronized(runtimeRun) {
            val current = runtimeRun.snapshot
            if (!current.status.running) return@synchronized null
            current.copy(
                status = status,
                result = result,
                updatedAtMs = Instant.now().toEpochMilli(),
            ).also { runtimeRun.snapshot = it }
        } ?: return
        appScope.launch(Dispatchers.IO) {
            agentTaskStore.update(
                taskId = runId,
                status = status.toAgentTaskStatus(),
                summary = result.finalRecommendation.ifBlank { result.error }.take(4_000),
                error = result.error.takeIf { it.isNotBlank() },
                cancelCapability = false,
            )
        }
        appendEvent(runtimeRun, "finished", runToPayload(next))
    }

    private fun readMissingRun(runId: String): JsonObject {
        val transcript = File(runDir, "$runId.jsonl")
        return if (transcript.exists()) {
            buildJsonObject {
                put("status", ModelCouncilRunStatus.INTERRUPTED.name.lowercase())
                put("run_id", runId)
                put("transcript_path", transcript.absolutePath)
                put("error", "Model Council run is no longer active in memory.")
            }
        } else {
            errorPayload("not_found", "Unknown model council run_id: $runId")
        }
    }

    private fun appendEvent(runtimeRun: RuntimeRun, event: String, payload: JsonObject) {
        val line = buildJsonObject {
            put("event", event)
            put("created_at_ms", Instant.now().toEpochMilli())
            put("payload", payload)
        }
        val transcriptPath = runtimeRun.snapshot.transcriptPath
        synchronized(runtimeRun.transcriptLock) {
            File(transcriptPath).appendText(line.toString() + "\n")
        }
    }

    private fun runToPayload(run: ModelCouncilRun): JsonObject = buildJsonObject {
        val settings = settingsStore.settingsFlow.value
        val exposeSeatOutputs = settings.agentRuntime.modelCouncil.showSeatOutputs
        put("status", run.status.name.lowercase())
        put("run_id", run.runId)
        put("mode", run.mode.name.lowercase())
        put("transcript_path", run.transcriptPath)
        put("started_at_ms", run.startedAtMs)
        put("updated_at_ms", run.updatedAtMs)
        put("seats", encoded(run.seats))
        put("seat_model_labels", buildJsonObject {
            run.seats.forEach { seat ->
                put(seat.seatId, settings.describeCouncilSeatModel(seat).label())
            }
            val synthesisModelId = settings.agentRuntime.modelCouncil.synthesisModelId ?: settings.chatModelId
            put(SYNTHESIZER_SEAT_KEY, settings.describeCouncilProviderModel(synthesisModelId).label())
        })
        put("seat_outputs_visible", exposeSeatOutputs)
        put("turns", encoded(run.turns.map { it.visible(exposeSeatOutputs) }))
        run.result?.let { put("result", encoded(it)) }
    }

    private fun turnToPayload(run: ModelCouncilRun, turn: ModelCouncilTurn): JsonObject = buildJsonObject {
        val exposeSeatOutputs = settingsStore.settingsFlow.value.agentRuntime.modelCouncil.showSeatOutputs
        put("status", run.status.name.lowercase())
        put("run_id", run.runId)
        put("mode", run.mode.name.lowercase())
        put("updated_at_ms", run.updatedAtMs)
        put("turn", encoded(turn.visible(exposeSeatOutputs)))
        put("turn_count", run.turns.size)
    }

    private fun errorPayload(code: String, message: String): JsonObject = buildJsonObject {
        put("status", "failed")
        put("code", code)
        put("error", message)
    }

    private fun failResult(message: String): Pair<ModelCouncilRunStatus, ModelCouncilResult> =
        ModelCouncilRunStatus.FAILED to ModelCouncilResult(error = message, finalRecommendation = message)

    private fun failResultFromTurns(turns: List<ModelCouncilTurn>): Pair<ModelCouncilRunStatus, ModelCouncilResult> {
        val details = turns.joinToString("\n") { turn ->
            "- ${turn.seatName} (${turn.modelLabel()}): ${turn.error.ifBlank { turn.status.name.lowercase() }}"
        }.ifBlank { "- No seat turns were recorded." }
        val message = "All Model Council seats failed.\n$details"
        return ModelCouncilRunStatus.FAILED to ModelCouncilResult(
            conflicts = turns.map { "${it.seatName}: ${it.error.ifBlank { it.status.name.lowercase() }}" },
            risks = turns.map { "${it.seatName} did not complete." },
            finalRecommendation = message,
            perSeatSummaries = turns.map { turn ->
                "${turn.seatName} (${turn.modelLabel()} / ${turn.status.name.lowercase()}): ${turn.error.ifBlank { "No output." }}"
            },
            warnings = turns.flatMap { turn ->
                turn.warnings.map { warning -> "${turn.seatName}: $warning" }
            },
            error = message,
        )
    }

    private fun ModelCouncilRun.toAgentTaskSnapshot() = AgentTaskSnapshot(
        taskId = runId,
        type = "model_council",
        title = "${mode.name.lowercase()} · ${task.objective.take(48)}",
        status = status.toAgentTaskStatus(),
        queueState = status.toAgentTaskStatus().toQueueState("model_council"),
        outputPath = transcriptPath,
        outputRef = AgentTaskOutputRef(
            type = "transcript",
            path = transcriptPath,
            exists = File(transcriptPath).exists(),
        ),
        retryPolicy = AgentTaskRetryPolicy(
            retryable = status == ModelCouncilRunStatus.FAILED,
            requiresApproval = false,
            maxRetries = 1,
            reason = "Model Council retry starts a new run from the original council task.",
        ),
        sourceToolName = "model_council_start",
        createdAtMs = startedAtMs,
        updatedAtMs = updatedAtMs,
        cancelCapability = status.running,
        summary = task.objective.take(1_000),
    )

    private fun ModelCouncilRunStatus.toAgentTaskStatus(): AgentTaskStatus = when (this) {
        ModelCouncilRunStatus.RUNNING -> AgentTaskStatus.RUNNING
        ModelCouncilRunStatus.COMPLETED,
        ModelCouncilRunStatus.PARTIAL_FAILED -> AgentTaskStatus.COMPLETED
        ModelCouncilRunStatus.FAILED -> AgentTaskStatus.FAILED
        ModelCouncilRunStatus.CANCELLED -> AgentTaskStatus.CANCELLED
        ModelCouncilRunStatus.TIMED_OUT -> AgentTaskStatus.TIMED_OUT
        ModelCouncilRunStatus.INTERRUPTED -> AgentTaskStatus.INTERRUPTED
    }

    private inline fun <reified T> encoded(value: T): JsonElement =
        json.encodeToJsonElement(value)

    private class RuntimeRun(
        @Volatile var snapshot: ModelCouncilRun,
        @Volatile var job: Job? = null,
        val transcriptLock: Any = Any(),
    )

    private data class ModelCouncilSynthesisResult(
        val result: ModelCouncilResult,
        val failed: Boolean,
    )

    companion object {
        /** Reserved seat id for the synthesizer pane in [seatLiveTextFlows]. */
        const val SYNTHESIZER_SEAT_KEY = "__synthesizer__"

        /** Soft cap on retained live-text run entries. Same scale as SubAgent. */
        const val LIVE_TEXT_CAP = 64
    }
}

private const val MODEL_COUNCIL_PROVIDER_PARALLELISM = 4
private const val MODEL_COUNCIL_TOTAL_TIMEOUT_OVERHEAD_MS = 30_000L

private fun ModelCouncilTurn.visible(exposeContent: Boolean): ModelCouncilTurn {
    if (exposeContent) return this
    return copy(
        content = content.take(700),
        error = error.take(700),
    )
}


private fun seatSystemPrompt(seat: ModelCouncilSeat): String = """
    You are `${seat.name}` in AmberAgent Model Council.
    Role: ${seat.role}

    ${seat.systemPrompt.ifBlank { "Apply the role faithfully and stay within the provided context." }}

    Hard boundaries:
    - You have no tools.
    - Do not claim to have inspected files, apps, web pages, or private data unless included in the prompt.
    - Return concise evidence-backed analysis for the supervisor.
""".trimIndent()

private fun openingPrompt(task: ModelCouncilTaskSpec, seat: ModelCouncilSeat): String = """
    Objective:
    ${task.objective}

    Context:
    ${task.context.ifBlank { "(none)" }}

    Evaluation criteria:
    ${task.evaluationCriteria.ifBlank { "(none)" }}

    Output format:
    ${task.outputFormat}

    Give your independent answer as `${seat.name}`. Do not reference other council seats.
""".trimIndent()

private fun responsePrompt(
    task: ModelCouncilTaskSpec,
    seat: ModelCouncilSeat,
    previousTurns: List<ModelCouncilTurn>,
): String = """
    Objective:
    ${task.objective}

    Context:
    ${task.context.ifBlank { "(none)" }}

    Other council summaries:
    ${previousTurns.filter { it.seatId != seat.seatId }.joinToString("\n\n") { it.summaryBlock() }}

    Respond as `${seat.name}`. Revise or defend your position. Focus on disagreements, missing evidence, and practical implications.
""".trimIndent()

private fun finalPositionPrompt(
    task: ModelCouncilTaskSpec,
    seat: ModelCouncilSeat,
    turns: List<ModelCouncilTurn>,
): String = """
    Objective:
    ${task.objective}

    Debate so far:
    ${turns.joinToString("\n\n") { it.summaryBlock() }}

    Give your final position as `${seat.name}`. Be decisive and concise.
""".trimIndent()

private fun synthesisPrompt(
    task: ModelCouncilTaskSpec,
    turns: List<ModelCouncilTurn>,
): String = """
    Objective:
    ${task.objective}

    Context:
    ${task.context.ifBlank { "(none)" }}

    Council turns:
    ${turns.joinToString("\n\n") { it.summaryBlock(limit = 1_200) }}

    Synthesize:
    - consensus
    - conflicts
    - strongest evidence
    - risks
    - final recommendation
""".trimIndent()

private fun ModelCouncilTurn.summaryBlock(limit: Int = 700): String =
    "Round $round / $seatName / ${modelLabel()} / ${status.name.lowercase()}:\n${(content.ifBlank { error }).take(limit)}"

private fun ModelCouncilTurn.modelLabel(): String =
    listOf(providerName, modelName)
        .filter { it.isNotBlank() }
        .joinToString(" / ")
        .ifBlank { modelId.toString() }

private data class CouncilSeatModelInfo(
    val modelName: String,
    val providerName: String,
)

private fun Settings.describeCouncilSeatModel(seat: ModelCouncilSeat): CouncilSeatModelInfo =
    when (seat.runnerType) {
        ModelCouncilSeatRunner.PROVIDER_MODEL -> describeCouncilProviderModel(seat.modelId)

        ModelCouncilSeatRunner.EXTERNAL_CLI -> CouncilSeatModelInfo(
            modelName = seat.externalModel.ifBlank { seat.externalTool.ifBlank { "external_cli" } },
            providerName = seat.externalTool.ifBlank { "external_cli" },
        )
    }

private fun Settings.describeCouncilProviderModel(modelId: Uuid): CouncilSeatModelInfo {
    val model = findModelById(modelId)
    val provider = model?.findProvider(providers)
    return CouncilSeatModelInfo(
        modelName = model?.displayName?.takeIf { it.isNotBlank() }
            ?: model?.modelId?.takeIf { it.isNotBlank() }
            ?: modelId.toString(),
        providerName = provider?.name.orEmpty(),
    )
}

private fun CouncilSeatModelInfo.decorateError(message: String): String {
    val label = label()
    return if (label.isBlank()) message else "$label: $message"
}

private fun CouncilSeatModelInfo.label(): String =
    listOf(providerName, modelName).filter { it.isNotBlank() }.joinToString(" / ")

private fun Settings.effectiveCouncilTotalTimeoutMs(
    setting: ModelCouncilRuntimeSetting,
    task: ModelCouncilTaskSpec,
): Long {
    val seatTimeoutMs = setting.seatTimeoutMs.coerceAtLeast(1_000L)
    val rounds = when (task.mode) {
        ModelCouncilMode.COMPARE -> 1
        ModelCouncilMode.DEBATE -> task.rounds.coerceAtLeast(1)
    }
    val maxProviderWaves = task.seats
        .groupingBy { seat -> describeCouncilSeatModel(seat).providerGateKey(seat) }
        .eachCount()
        .values
        .maxOfOrNull { count -> ((count - 1) / MODEL_COUNCIL_PROVIDER_PARALLELISM) + 1 }
        ?: 1
    val waveBudgetMs = seatTimeoutMs * ((rounds * maxProviderWaves) + 1L)
    return maxOf(
        setting.totalTimeoutMs.coerceAtLeast(10_000L),
        waveBudgetMs + MODEL_COUNCIL_TOTAL_TIMEOUT_OVERHEAD_MS,
    )
}

private fun CouncilSeatModelInfo.providerGateKey(seat: ModelCouncilSeat): String =
    when (seat.runnerType) {
        ModelCouncilSeatRunner.PROVIDER_MODEL -> providerName.ifBlank { "provider:${seat.modelId}" }
        ModelCouncilSeatRunner.EXTERNAL_CLI -> {
            val runtime = seat.externalRuntime.ifBlank { "default" }
            val tool = seat.externalTool.ifBlank { "external_cli" }
            "external:$runtime:$tool"
        }
    }

private fun StringBuilder.appendLines(lines: List<String>) {
    if (lines.isEmpty()) {
        appendLine("- None")
    } else {
        lines.forEach { appendLine("- $it") }
    }
}

internal object ModelCouncilExternalCliCommandBuilder {
    private const val OUTPUT_BEGIN = "__AMBERAGENT_MODEL_COUNCIL_CLI_OUTPUT_BEGIN__"
    private const val OUTPUT_END = "__AMBERAGENT_MODEL_COUNCIL_CLI_OUTPUT_END__"

    enum class Marker { NONE, BEGIN, END }

    fun build(
        seat: ModelCouncilSeat,
        prompt: String,
        timeoutMs: Long,
        externalCliHomeRoot: String,
        runtime: TerminalRuntimeKind = TerminalRuntimeKind.BUILTIN_ALPINE,
    ): String {
        require(seat.runnerType == ModelCouncilSeatRunner.EXTERNAL_CLI) {
            "External CLI command requires runner_type=external_cli."
        }
        require(externalCliHomeRoot.isNotBlank()) { "externalCliHomeRoot is required." }
        val spec = ExternalCliToolRegistry.requireSpec(seat.externalTool)
        val boundedPrompt = prompt.take(MODEL_COUNCIL_EXTERNAL_CLI_PROMPT_CHARS)
        val timeoutSeconds = (timeoutMs / 1_000L).coerceAtLeast(1L).coerceAtMost(24 * 60 * 60)
        val promptDelimiter = "AMBERAGENT_COUNCIL_PROMPT_${Uuid.random().toString().replace("-", "")}"
        val modelArg = ExternalCliToolRegistry.safeModelArg(seat.externalModel)
        val homeRoot = "${externalCliHomeRoot.trimEnd('/')}/${spec.id}"
        val seatCommand = spec.seatCommand("\${prompt_file}", modelArg)
        val readinessProbeCommand = spec.readinessProbeCommand(modelArg)
        return buildString {
            appendLine("set -u")
            appendLine("if ! command -v ${spec.binary.shellSingleQuoted()} >/dev/null 2>&1; then")
            appendLine("  echo ${spec.missingMessage.shellSingleQuoted()} >&2")
            appendLine("  exit 127")
            appendLine("fi")
            appendLine("workspace_root=\"${'$'}PWD\"")
            appendLine("tmp_root=\"${'$'}workspace_root/.amberagent-council-cli-${'$'}$\"")
            if (runtime == TerminalRuntimeKind.TERMUX_EXTERNAL) {
                appendLine("home_root=\"${'$'}HOME/.amberagent/external-cli-home/${spec.id}\"")
            } else {
                appendLine("home_root=${homeRoot.shellSingleQuoted()}")
            }
            appendLine("if ! mkdir -p \"${'$'}tmp_root/home\" \"${'$'}tmp_root/work\" \"${'$'}home_root/${spec.credentialHome}\"; then")
            appendLine("  echo 'External CLI credential home is not writable in this runtime. Choose builtin_alpine/android_shell or finish login in an accessible runtime.' >&2")
            appendLine("  exit 125")
            appendLine("fi")
            appendLine("cli_pid=\"\"")
            appendLine("probe_pid=\"\"")
            appendLine("stop_process_tree() {")
            appendLine("  target_pid=\"${'$'}1\"")
            appendLine("  if [ -n \"${'$'}target_pid\" ] && kill -0 \"${'$'}target_pid\" >/dev/null 2>&1; then")
            appendLine("    if command -v pkill >/dev/null 2>&1; then")
            appendLine("      pkill -TERM -P \"${'$'}target_pid\" >/dev/null 2>&1 || true")
            appendLine("    fi")
            appendLine("    kill -TERM \"-${'$'}target_pid\" >/dev/null 2>&1 || true")
            appendLine("    kill -TERM \"${'$'}target_pid\" >/dev/null 2>&1 || true")
            appendLine("    sleep 2")
            appendLine("    if command -v pkill >/dev/null 2>&1; then")
            appendLine("      pkill -KILL -P \"${'$'}target_pid\" >/dev/null 2>&1 || true")
            appendLine("    fi")
            appendLine("    kill -KILL \"-${'$'}target_pid\" >/dev/null 2>&1 || true")
            appendLine("    kill -KILL \"${'$'}target_pid\" >/dev/null 2>&1 || true")
            appendLine("  fi")
            appendLine("}")
            appendLine("stop_cli_tree() { stop_process_tree \"${'$'}cli_pid\"; }")
            appendLine("stop_probe_tree() { stop_process_tree \"${'$'}probe_pid\"; }")
            appendLine("cleanup() { stop_probe_tree; stop_cli_tree; rm -rf \"${'$'}tmp_root\"; }")
            appendLine("trap cleanup EXIT INT TERM")
            appendLine("export HOME=\"${'$'}home_root\"")
            appendLine("export CLAUDE_CONFIG_DIR=\"${'$'}home_root/.claude\"")
            appendLine("export KIMI_HOME=\"${'$'}home_root/.kimi\"")
            appendLine("export GEMINI_CLI_HOME=\"${'$'}home_root/.gemini\"")
            appendLine("export NO_COLOR=1")
            appendLine("probe_log=\"${'$'}tmp_root/probe.log\"")
            appendLine("probe_status=0")
            appendLine("if command -v setsid >/dev/null 2>&1; then")
            appendLine("  setsid sh -lc ${readinessProbeCommand.shellSingleQuoted()} >\"${'$'}probe_log\" 2>&1 &")
            appendLine("else")
            appendLine("  sh -lc ${readinessProbeCommand.shellSingleQuoted()} >\"${'$'}probe_log\" 2>&1 &")
            appendLine("fi")
            appendLine("probe_pid=${'$'}!")
            appendLine("(")
            appendLine("  sleep $MODEL_COUNCIL_EXTERNAL_CLI_PROBE_TIMEOUT_SECONDS")
            appendLine("  if kill -0 \"${'$'}probe_pid\" >/dev/null 2>&1; then")
            appendLine("    echo 'External CLI readiness probe timed out for ${spec.displayName}; stopping ${spec.binary}.' >&2")
            appendLine("    stop_probe_tree")
            appendLine("  fi")
            appendLine(") &")
            appendLine("probe_watchdog_pid=${'$'}!")
            appendLine("wait \"${'$'}probe_pid\" || probe_status=${'$'}?")
            appendLine("kill \"${'$'}probe_watchdog_pid\" >/dev/null 2>&1 || true")
            appendLine("wait \"${'$'}probe_watchdog_pid\" >/dev/null 2>&1 || true")
            appendLine("if [ \"${'$'}probe_status\" -ne 0 ]; then")
            appendLine("  echo 'External CLI ${spec.displayName} is not ready. Run ${spec.loginCommand} in the selected runtime, complete login, then retry. Council runs never start login.' >&2")
            appendLine("  exit 65")
            appendLine("fi")
            appendLine("prompt_file=\"${'$'}tmp_root/prompt.txt\"")
            appendLine("cat > \"${'$'}prompt_file\" <<'$promptDelimiter'")
            appendLine(boundedPrompt)
            appendLine(promptDelimiter)
            appendLine("export prompt_file")
            appendLine("cd \"${'$'}tmp_root/work\"")
            appendLine("printf '%s\\n' '$OUTPUT_BEGIN'")
            appendLine("if command -v setsid >/dev/null 2>&1; then")
            appendLine("  setsid sh -lc ${seatCommand.shellSingleQuoted()} &")
            appendLine("else")
            appendLine("  sh -lc ${seatCommand.shellSingleQuoted()} &")
            appendLine("fi")
            appendLine("cli_pid=${'$'}!")
            appendLine("(")
            appendLine("  sleep $timeoutSeconds")
            appendLine("  if kill -0 \"${'$'}cli_pid\" >/dev/null 2>&1; then")
            appendLine("    echo 'External CLI seat timed out after ${timeoutSeconds}s; stopping ${spec.binary}.' >&2")
            appendLine("    stop_cli_tree")
            appendLine("  fi")
            appendLine(") &")
            appendLine("watchdog_pid=${'$'}!")
            appendLine("wait \"${'$'}cli_pid\"")
            appendLine("status=${'$'}?")
            appendLine("kill \"${'$'}watchdog_pid\" >/dev/null 2>&1 || true")
            appendLine("wait \"${'$'}watchdog_pid\" >/dev/null 2>&1 || true")
            appendLine("printf '\\n%s\\n' '$OUTPUT_END'")
            appendLine("exit \"${'$'}status\"")
        }
    }

    fun extractLiveLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed == OUTPUT_BEGIN || trimmed == OUTPUT_END) return null
        if (line.contains("Preparing /workspace mirror") || line.contains("Starting ")) return null
        return line.takeIf { it.isNotBlank() }
    }

    fun marker(line: String): Marker = when (line.trim()) {
        OUTPUT_BEGIN -> Marker.BEGIN
        OUTPUT_END -> Marker.END
        else -> Marker.NONE
    }

    fun extractFinalOutput(output: String): String {
        val start = output.indexOf(OUTPUT_BEGIN)
        if (start < 0) return ""
        val bodyStart = start + OUTPUT_BEGIN.length
        val end = output.indexOf(OUTPUT_END, startIndex = bodyStart)
        return if (end >= 0) {
            output.substring(bodyStart, end)
        } else {
            output.substring(bodyStart)
        }.trim()
    }

}

private const val MODEL_COUNCIL_EXTERNAL_CLI_PROMPT_CHARS = 24_000
private const val MODEL_COUNCIL_EXTERNAL_CLI_PROBE_TIMEOUT_SECONDS = 15
internal const val MODEL_COUNCIL_EXTERNAL_CLI_WAIT_GRACE_MS = 1_000L
