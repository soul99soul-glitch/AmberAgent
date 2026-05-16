package me.rerere.rikkahub.data.agent.modelcouncil

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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.agent.task.AgentTaskSnapshot
import me.rerere.rikkahub.data.agent.task.AgentTaskOutputRef
import me.rerere.rikkahub.data.agent.task.AgentTaskRetryPolicy
import me.rerere.rikkahub.data.agent.task.AgentTaskStatus
import me.rerere.rikkahub.data.agent.task.AgentTaskStore
import me.rerere.rikkahub.data.agent.task.toQueueState
import me.rerere.rikkahub.data.agent.terminal.TerminalRuntime
import me.rerere.rikkahub.data.agent.terminal.TerminalRuntimeKind
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import java.io.File
import java.time.Instant
import kotlin.uuid.Uuid

interface ModelCouncilTextRunner {
    /**
     * Generate the seat's reply.
     * - [onChunk] is invoked with the *cumulative* text every time the provider streams in.
     *   Pass `{ }` if you don't care about live updates.
     * - The returned String is the final (already truncated to budget) text.
     */
    suspend fun generate(
        settings: Settings,
        modelId: Uuid,
        systemPrompt: String,
        userPrompt: String,
        outputBudgetChars: Int,
        onChunk: (String) -> Unit = {},
    ): String
}

class ProviderModelCouncilTextRunner(
    private val providerManager: ProviderManager,
) : ModelCouncilTextRunner {
    override suspend fun generate(
        settings: Settings,
        modelId: Uuid,
        systemPrompt: String,
        userPrompt: String,
        outputBudgetChars: Int,
        onChunk: (String) -> Unit,
    ): String {
        val model = settings.findModelById(modelId) ?: error("Model not found: $modelId")
        val provider = model.findProvider(settings.providers) ?: error("Provider not found for model: ${model.displayName}")
        val providerImpl = providerManager.getProviderByType(provider)
        val messages = buildList {
            add(UIMessage.system(systemPrompt))
            add(UIMessage.user(userPrompt))
        }
        val params = TextGenerationParams(
            model = model,
            tools = emptyList(),
            reasoningLevel = ReasoningLevel.OFF,
            customHeaders = model.customHeaders,
            customBody = model.customBodies,
        )
        // Streaming path: per-chunk MessageChunk.choices.first.delta is the delta; we accumulate
        // and emit the running text. The provider abstraction returns chunked deltas, so we keep
        // a builder. If anything throws mid-stream, the partial text we already have is what
        // callers see (better than blank).
        val accumulated = StringBuilder()
        providerImpl.streamText(
            providerSetting = provider,
            messages = messages,
            params = params,
        ).collect { chunk ->
            val delta = chunk.choices.firstOrNull()?.delta?.parts
                ?.filterIsInstance<UIMessagePart.Text>()
                ?.joinToString("") { it.text }
                .orEmpty()
            if (delta.isNotEmpty()) {
                accumulated.append(delta)
                onChunk(accumulated.toString())
            }
        }
        return accumulated.toString().take(outputBudgetChars)
    }
}

class ExternalCliModelCouncilRunner(
    private val terminalRuntime: TerminalRuntime,
) {
    suspend fun generate(
        seat: ModelCouncilSeat,
        systemPrompt: String,
        userPrompt: String,
        timeoutMs: Long,
        outputBudgetChars: Int,
        onChunk: (String) -> Unit = {},
    ): String {
        var terminalJobId: String? = null
        try {
            val command = ModelCouncilExternalCliCommandBuilder.build(
                seat = seat,
                prompt = buildExternalCliPrompt(systemPrompt, userPrompt),
                timeoutMs = timeoutMs,
            )
            val output = StringBuilder()
            var inCliOutput = false
            var cliOutputEnded = false
            val started = terminalRuntime.startJob(
                command = command,
                timeoutMillis = timeoutMs,
                runtime = TerminalRuntimeKind.fromWire(seat.externalRuntime),
                toolName = "model_council_external_cli",
                title = "Model Council 外部 CLI · ${seat.name}",
                syncWorkspace = false,
                flushWorkspace = false,
                outputCallback = { line ->
                    when (ModelCouncilExternalCliCommandBuilder.marker(line)) {
                        ModelCouncilExternalCliCommandBuilder.Marker.BEGIN -> {
                            inCliOutput = true
                        }

                        ModelCouncilExternalCliCommandBuilder.Marker.END -> {
                            cliOutputEnded = true
                        }

                        ModelCouncilExternalCliCommandBuilder.Marker.NONE -> Unit
                    }
                    if (inCliOutput && !cliOutputEnded) {
                        ModelCouncilExternalCliCommandBuilder.extractLiveLine(line)?.let { contentLine ->
                            if (contentLine.isNotBlank()) {
                                if (output.isNotEmpty()) output.append('\n')
                                output.append(contentLine)
                                onChunk(output.toString().take(outputBudgetChars))
                            }
                        }
                    }
                },
            )
            terminalJobId = started.jobId
            val finished = if (started.running) {
                terminalRuntime.waitJob(started.jobId, timeoutMs + MODEL_COUNCIL_EXTERNAL_CLI_WAIT_GRACE_MS)
            } else {
                started
            }
            val cliOutput = ModelCouncilExternalCliCommandBuilder.extractFinalOutput(finished.outputTail).trim()
            if (finished.exitCode != 0) {
                error(
                    finished.error
                        ?: cliOutput.ifBlank {
                            finished.outputTail.take(1_000)
                                .ifBlank { "External CLI seat failed with exit code ${finished.exitCode}." }
                        },
                )
            }
            val parsed = cliOutput.ifBlank { finished.outputTail }.trim()
            return parsed.take(outputBudgetChars)
        } catch (error: CancellationException) {
            terminalJobId?.let { id ->
                withContext(NonCancellable) {
                    stopExternalCliJobIfRunning(id)
                }
            }
            throw error
        } finally {
            terminalJobId?.let { id ->
                withContext(NonCancellable) {
                    stopExternalCliJobIfRunning(id)
                }
            }
        }
    }

    private suspend fun stopExternalCliJobIfRunning(jobId: String) {
        runCatching {
            val snapshot = terminalRuntime.readJob(jobId)
            if (snapshot.running) {
                terminalRuntime.stopJob(jobId, "Model Council external CLI seat stopped.")
            }
        }
    }

    private fun buildExternalCliPrompt(systemPrompt: String, userPrompt: String): String = """
        $systemPrompt

        You are running as an external CLI participant in AmberAgent Model Council.
        Important:
        - Return only the seat analysis text.
        - Do not execute tools or modify files.
        - Do not ask follow-up questions.

        $userPrompt
    """.trimIndent()
}

class ModelCouncilManager(
    context: Context,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
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
            val result = withTimeoutOrNull(councilSetting.totalTimeoutMs.coerceAtLeast(10_000L)) {
                runCatching {
                    executeCouncil(
                        settings = settings,
                        setting = councilSetting,
                        synthesisModelId = synthesisModelId,
                        runtimeRun = runtimeRun,
                    )
                }.getOrElse { error ->
                    failResult(error.message ?: error::class.java.simpleName)
                }
            } ?: failResult("Model Council timed out.")
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
            put("max_rounds", setting.maxRounds)
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
            appendLine()
            appendLine("## Raw Turns")
            run.turns.forEach { turn ->
                appendLine()
                appendLine("### Round ${turn.round} · ${turn.seatName} · ${turn.status.name.lowercase()}")
                appendLine()
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
        if (task.mode == ModelCouncilMode.DEBATE && task.rounds >= 2 && roundOne.any { it.status == ModelCouncilRunStatus.COMPLETED }) {
            val roundTwo = runRound(
                settings = settings,
                setting = setting,
                runtimeRun = runtimeRun,
                round = 2,
                promptForSeat = { seat -> responsePrompt(task, seat, roundOne) },
            )
            allTurns += roundTwo
        }
        if (task.mode == ModelCouncilMode.DEBATE && task.rounds >= 3 && allTurns.any { it.status == ModelCouncilRunStatus.COMPLETED }) {
            val finalRound = runRound(
                settings = settings,
                setting = setting,
                runtimeRun = runtimeRun,
                round = 3,
                promptForSeat = { seat -> finalPositionPrompt(task, seat, allTurns) },
            )
            allTurns += finalRound
        }

        val completed = allTurns.filter { it.status == ModelCouncilRunStatus.COMPLETED && it.content.isNotBlank() }
        if (completed.isEmpty()) {
            return failResult("All Model Council seats failed.")
        }
        val synthesis = synthesize(settings, setting, synthesisModelId, task, allTurns, runtimeRun.snapshot.runId)
        val status = if (allTurns.any { it.status != ModelCouncilRunStatus.COMPLETED }) {
            ModelCouncilRunStatus.PARTIAL_FAILED
        } else {
            ModelCouncilRunStatus.COMPLETED
        }
        return status to synthesis
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
        // For round 2+, derive prior text from the canonical `runtimeRun.snapshot.turns` (the
        // source of truth) instead of reading from the live flow. The live flow could in theory
        // be cleared/reformatted by a future change; turns are append-only.
        val priorPrefixBySeat: Map<String, String> = if (round > 1) {
            runtimeRun.snapshot.seats.associate { seat ->
                val priorTurns = runtimeRun.snapshot.turns
                    .filter { it.seatId == seat.seatId && it.round < round }
                    .sortedBy { it.round }
                val priorText = priorTurns.joinToString("\n\n") { turn ->
                    if (turn.round == 1) turn.content
                    else "--- 第 ${turn.round} 轮 ---\n\n${turn.content}"
                }
                seat.seatId to if (priorText.isNotEmpty()) "$priorText\n\n--- 第 $round 轮 ---\n\n" else ""
            }
        } else emptyMap()
        runtimeRun.snapshot.seats.map { seat ->
            async {
                val systemPrompt = seatSystemPrompt(seat)
                val seatFlow = seatFlows?.get(seat.seatId)
                val priorPrefix = priorPrefixBySeat[seat.seatId].orEmpty()
                val modelInfo = settings.describeCouncilSeatModel(seat)
                val result = runCatching {
                    withTimeout(setting.seatTimeoutMs.coerceAtLeast(1_000L)) {
                        generateSeatReply(
                            settings = settings,
                            setting = setting,
                            seat = seat,
                            systemPrompt = systemPrompt,
                            userPrompt = promptForSeat(seat),
                            onChunk = { running -> seatFlow?.value = priorPrefix + running },
                        )
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
                            content = content.take(seat.outputBudgetChars),
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
    ): ModelCouncilResult {
        val prompt = synthesisPrompt(task, turns)
        val synthesisModelInfo = settings.describeCouncilProviderModel(synthesisModelId)
        val synthFlow = seatLiveTextFlows[runId]?.get(SYNTHESIZER_SEAT_KEY)
        val text = runCatching {
            withTimeout(setting.seatTimeoutMs.coerceAtLeast(1_000L)) {
                modelRunner.generate(
                    settings = settings,
                    modelId = synthesisModelId,
                    systemPrompt = "你是 AmberAgent 的 Model Council 裁判。只综合证据，不引入未给出的事实。",
                    userPrompt = prompt,
                    outputBudgetChars = setting.outputBudgetChars,
                    onChunk = { running -> synthFlow?.value = running },
                )
            }
        }.getOrElse { error ->
            "Synthesis failed (${synthesisModelInfo.label()}): ${error.message ?: error::class.java.simpleName}"
        }.take(setting.outputBudgetChars)
        return ModelCouncilResult(
            consensus = listOf("See final recommendation for synthesized consensus."),
            conflicts = turns.filter { it.status != ModelCouncilRunStatus.COMPLETED }
                .map { "${it.seatName}: ${it.error}" },
            strongestEvidence = turns.filter { it.status == ModelCouncilRunStatus.COMPLETED }
                .take(4)
                .map { "${it.seatName}: ${it.content.take(360)}" },
            risks = turns.filter { it.status != ModelCouncilRunStatus.COMPLETED }
                .map { "${it.seatName} did not complete." },
            finalRecommendation = text,
            perSeatSummaries = turns.map { turn ->
                "${turn.seatName} (${turn.status.name.lowercase()}): ${(turn.content.ifBlank { turn.error }).take(700)}"
            },
        )
    }

    private suspend fun generateSeatReply(
        settings: Settings,
        setting: ModelCouncilRuntimeSetting,
        seat: ModelCouncilSeat,
        systemPrompt: String,
        userPrompt: String,
        onChunk: (String) -> Unit,
    ): String = when (seat.runnerType) {
        ModelCouncilSeatRunner.PROVIDER_MODEL -> modelRunner.generate(
            settings = settings,
            modelId = seat.modelId,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            outputBudgetChars = seat.outputBudgetChars,
            onChunk = onChunk,
        )

        ModelCouncilSeatRunner.EXTERNAL_CLI -> externalCliRunner.generate(
            seat = seat,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            timeoutMs = setting.seatTimeoutMs.coerceAtLeast(1_000L),
            outputBudgetChars = seat.outputBudgetChars,
            onChunk = onChunk,
        )
    }

    private fun appendTurn(runtimeRun: RuntimeRun, turn: ModelCouncilTurn) {
        synchronized(runtimeRun) {
            val current = runtimeRun.snapshot
            if (!current.status.running) return
            val next = current.copy(
                turns = current.turns + turn,
                updatedAtMs = Instant.now().toEpochMilli(),
            )
            runtimeRun.snapshot = next
            appScope.launch(Dispatchers.IO) {
                agentTaskStore.update(
                    taskId = next.runId,
                    status = AgentTaskStatus.RUNNING,
                    summary = "${turn.seatName}: ${(turn.content.ifBlank { turn.error }).take(700)}",
                    cancelCapability = true,
                )
            }
            appendEvent(runtimeRun, "turn", runToPayload(next))
        }
    }

    private fun finish(runId: String, status: ModelCouncilRunStatus, result: ModelCouncilResult) {
        val runtimeRun = runs[runId] ?: return
        val current = runtimeRun.snapshot
        if (!current.status.running) return
        val next = current.copy(
            status = status,
            result = result,
            updatedAtMs = Instant.now().toEpochMilli(),
        )
        runtimeRun.snapshot = next
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
        File(runtimeRun.snapshot.transcriptPath).appendText(line.toString() + "\n")
    }

    private fun runToPayload(run: ModelCouncilRun): JsonObject = buildJsonObject {
        val exposeSeatOutputs = settingsStore.settingsFlow.value.agentRuntime.modelCouncil.showSeatOutputs
        put("status", run.status.name.lowercase())
        put("run_id", run.runId)
        put("mode", run.mode.name.lowercase())
        put("transcript_path", run.transcriptPath)
        put("started_at_ms", run.startedAtMs)
        put("updated_at_ms", run.updatedAtMs)
        put("seats", encoded(run.seats))
        put("seat_outputs_visible", exposeSeatOutputs)
        put("turns", encoded(run.turns.map { it.visible(exposeSeatOutputs) }))
        run.result?.let { put("result", encoded(it)) }
    }

    private fun errorPayload(code: String, message: String): JsonObject = buildJsonObject {
        put("status", "failed")
        put("code", code)
        put("error", message)
    }

    private fun failResult(message: String): Pair<ModelCouncilRunStatus, ModelCouncilResult> =
        ModelCouncilRunStatus.FAILED to ModelCouncilResult(error = message, finalRecommendation = message)

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
        json.parseToJsonElement(json.encodeToString(value))

    private class RuntimeRun(
        @Volatile var snapshot: ModelCouncilRun,
        @Volatile var job: Job? = null,
    )

    companion object {
        /** Reserved seat id for the synthesizer pane in [seatLiveTextFlows]. */
        const val SYNTHESIZER_SEAT_KEY = "__synthesizer__"

        /** Soft cap on retained live-text run entries. Same scale as SubAgent. */
        const val LIVE_TEXT_CAP = 64
    }
}

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
    private val safeCliModelRegex = Regex("[A-Za-z0-9._:/+-]{1,120}")

    enum class Marker { NONE, BEGIN, END }

    fun build(seat: ModelCouncilSeat, prompt: String, timeoutMs: Long): String {
        require(seat.runnerType == ModelCouncilSeatRunner.EXTERNAL_CLI) {
            "External CLI command requires runner_type=external_cli."
        }
        val tool = seat.externalTool.ifBlank { "gemini_cli" }
        require(tool == "gemini_cli") {
            "Unsupported external_tool: $tool. Currently supported: gemini_cli."
        }
        val boundedPrompt = prompt.take(MODEL_COUNCIL_EXTERNAL_CLI_PROMPT_CHARS)
        val timeoutSeconds = (timeoutMs / 1_000L).coerceAtLeast(1L).coerceAtMost(24 * 60 * 60)
        val promptDelimiter = "AMBERAGENT_COUNCIL_PROMPT_${Uuid.random().toString().replace("-", "")}"
        val modelArg = seat.externalModel
            .takeIf { it.isNotBlank() }
            ?.also { require(safeCliModelRegex.matches(it)) { "Unsafe external_model: $it" } }
            ?.let { " --model ${it.shellSingleQuoted()}" }
            .orEmpty()
        return buildString {
            appendLine("set -u")
            appendLine("if ! command -v gemini >/dev/null 2>&1; then")
            appendLine("  echo 'gemini CLI not found in this runtime. Install Gemini CLI in Termux/selected runtime or use provider model seats.' >&2")
            appendLine("  exit 127")
            appendLine("fi")
            appendLine("tmp_root=\"./.amberagent-council-cli-${'$'}$\"")
            appendLine("mkdir -p \"${'$'}tmp_root/home\" \"${'$'}tmp_root/work\"")
            appendLine("cli_pid=\"\"")
            appendLine("stop_cli_tree() {")
            appendLine("  if [ -n \"${'$'}cli_pid\" ] && kill -0 \"${'$'}cli_pid\" >/dev/null 2>&1; then")
            appendLine("    if command -v pkill >/dev/null 2>&1; then")
            appendLine("      pkill -TERM -P \"${'$'}cli_pid\" >/dev/null 2>&1 || true")
            appendLine("    fi")
            appendLine("    kill -TERM \"-${'$'}cli_pid\" >/dev/null 2>&1 || true")
            appendLine("    kill -TERM \"${'$'}cli_pid\" >/dev/null 2>&1 || true")
            appendLine("    sleep 2")
            appendLine("    if command -v pkill >/dev/null 2>&1; then")
            appendLine("      pkill -KILL -P \"${'$'}cli_pid\" >/dev/null 2>&1 || true")
            appendLine("    fi")
            appendLine("    kill -KILL \"-${'$'}cli_pid\" >/dev/null 2>&1 || true")
            appendLine("    kill -KILL \"${'$'}cli_pid\" >/dev/null 2>&1 || true")
            appendLine("  fi")
            appendLine("}")
            appendLine("cleanup() { stop_cli_tree; rm -rf \"${'$'}tmp_root\"; }")
            appendLine("trap cleanup EXIT INT TERM")
            appendLine("export HOME=\"${'$'}tmp_root/home\"")
            appendLine("export GEMINI_CLI_HOME=\"${'$'}tmp_root/home/.gemini\"")
            appendLine("export NO_COLOR=1")
            appendLine("prompt_file=\"${'$'}tmp_root/prompt.txt\"")
            appendLine("cat > \"${'$'}prompt_file\" <<'$promptDelimiter'")
            appendLine(boundedPrompt)
            appendLine(promptDelimiter)
            appendLine("cd \"${'$'}tmp_root/work\"")
            appendLine("printf '%s\\n' '$OUTPUT_BEGIN'")
            appendLine("if command -v setsid >/dev/null 2>&1; then")
            appendLine("  setsid gemini --skip-trust --approval-mode plan --output-format text$modelArg --prompt \"${'$'}(cat \"${'$'}prompt_file\")\" &")
            appendLine("else")
            appendLine("  gemini --skip-trust --approval-mode plan --output-format text$modelArg --prompt \"${'$'}(cat \"${'$'}prompt_file\")\" &")
            appendLine("fi")
            appendLine("cli_pid=${'$'}!")
            appendLine("(")
            appendLine("  sleep $timeoutSeconds")
            appendLine("  if kill -0 \"${'$'}cli_pid\" >/dev/null 2>&1; then")
            appendLine("    echo 'External CLI seat timed out after ${timeoutSeconds}s; stopping gemini.' >&2")
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

    private fun String.shellSingleQuoted(): String =
        "'" + replace("'", "'\"'\"'") + "'"
}

private const val MODEL_COUNCIL_EXTERNAL_CLI_PROMPT_CHARS = 24_000
private const val MODEL_COUNCIL_EXTERNAL_CLI_WAIT_GRACE_MS = 1_000L
