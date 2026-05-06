package me.rerere.rikkahub.data.agent.modelcouncil

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
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
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.agent.task.AgentTaskSnapshot
import me.rerere.rikkahub.data.agent.task.AgentTaskOutputRef
import me.rerere.rikkahub.data.agent.task.AgentTaskRetryPolicy
import me.rerere.rikkahub.data.agent.task.AgentTaskStatus
import me.rerere.rikkahub.data.agent.task.AgentTaskStore
import me.rerere.rikkahub.data.agent.task.toQueueState
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import java.io.File
import java.time.Instant
import kotlin.uuid.Uuid

interface ModelCouncilTextRunner {
    suspend fun generate(
        settings: Settings,
        modelId: Uuid,
        systemPrompt: String,
        userPrompt: String,
        outputBudgetChars: Int,
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
    ): String {
        val model = settings.findModelById(modelId) ?: error("Model not found: $modelId")
        val provider = model.findProvider(settings.providers) ?: error("Provider not found for model: ${model.displayName}")
        val providerImpl = providerManager.getProviderByType(provider)
        val messages = buildList {
            add(UIMessage.system(systemPrompt))
            add(UIMessage.user(userPrompt))
        }
        val chunk = providerImpl.generateText(
            providerSetting = provider,
            messages = messages,
            params = TextGenerationParams(
                model = model,
                temperature = 0.2f,
                tools = emptyList(),
                reasoningLevel = ReasoningLevel.OFF,
            ),
        )
        return chunk.choices.firstOrNull()?.message?.toText()
            ?.take(outputBudgetChars)
            .orEmpty()
    }
}

class ModelCouncilManager(
    context: Context,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val runner: ModelCouncilTextRunner,
    private val agentTaskStore: AgentTaskStore,
) {
    private val runDir = File(context.filesDir, "amberagent/model-council/runs").also { it.mkdirs() }
    private val runs = java.util.concurrent.ConcurrentHashMap<String, RuntimeRun>()

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
        val deadline = System.currentTimeMillis() + waitTimeoutMs.coerceIn(0, 60_000L)
        while (System.currentTimeMillis() < deadline) {
            val current = runs[runId]?.snapshot ?: return@withContext readMissingRun(runId)
            if (!current.status.running) return@withContext runToPayload(current)
            delay(250)
        }
        read(runId)
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

    fun runtimeSummary(): JsonObject {
        val settings = settingsStore.settingsFlow.value
        val setting = settings.agentRuntime.modelCouncil
        return buildJsonObject {
            put("enabled", setting.enabled)
            put("default_seat_count", setting.defaultSeats.size)
            put("max_seats", setting.maxSeats)
            put("default_rounds", setting.defaultRounds)
            put("max_rounds", setting.maxRounds)
            put("seat_timeout_ms", setting.seatTimeoutMs)
            put("total_timeout_ms", setting.totalTimeoutMs)
            put("output_budget_chars", setting.outputBudgetChars)
            put("synthesis_model_id", (setting.synthesisModelId ?: settings.chatModelId).toString())
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
        val synthesis = synthesize(settings, setting, synthesisModelId, task, allTurns)
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
        runtimeRun.snapshot.seats.map { seat ->
            async {
                val systemPrompt = seatSystemPrompt(seat)
                val result = runCatching {
                    withTimeout(setting.seatTimeoutMs.coerceAtLeast(1_000L)) {
                        runner.generate(
                            settings = settings,
                            modelId = seat.modelId,
                            systemPrompt = systemPrompt,
                            userPrompt = promptForSeat(seat),
                            outputBudgetChars = seat.outputBudgetChars,
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
                            status = if (error is kotlinx.coroutines.TimeoutCancellationException) {
                                ModelCouncilRunStatus.TIMED_OUT
                            } else {
                                ModelCouncilRunStatus.FAILED
                            },
                            error = error.message ?: error::class.java.simpleName,
                        )
                    },
                )
            }
        }.awaitAll().also { turns ->
            appendTurns(runtimeRun, turns)
        }
    }

    private suspend fun synthesize(
        settings: Settings,
        setting: ModelCouncilRuntimeSetting,
        synthesisModelId: Uuid,
        task: ModelCouncilTaskSpec,
        turns: List<ModelCouncilTurn>,
    ): ModelCouncilResult {
        val prompt = synthesisPrompt(task, turns)
        val text = runCatching {
            withTimeout(setting.seatTimeoutMs.coerceAtLeast(1_000L)) {
                runner.generate(
                    settings = settings,
                    modelId = synthesisModelId,
                    systemPrompt = "你是 AmberAgent 的 Model Council 裁判。只综合证据，不引入未给出的事实。",
                    userPrompt = prompt,
                    outputBudgetChars = setting.outputBudgetChars,
                )
            }
        }.getOrElse { error ->
            "Synthesis failed: ${error.message ?: error::class.java.simpleName}"
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

    private fun appendTurns(runtimeRun: RuntimeRun, turns: List<ModelCouncilTurn>) {
        val current = runtimeRun.snapshot
        if (!current.status.running) return
        val next = current.copy(
            turns = current.turns + turns,
            updatedAtMs = Instant.now().toEpochMilli(),
        )
        runtimeRun.snapshot = next
        appendEvent(runtimeRun, "turns", runToPayload(next))
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
        put("status", run.status.name.lowercase())
        put("run_id", run.runId)
        put("mode", run.mode.name.lowercase())
        put("transcript_path", run.transcriptPath)
        put("started_at_ms", run.startedAtMs)
        put("updated_at_ms", run.updatedAtMs)
        put("seats", encoded(run.seats))
        put("turns", encoded(run.turns))
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
    "Round $round / $seatName / ${status.name.lowercase()}:\n${(content.ifBlank { error }).take(limit)}"

private fun StringBuilder.appendLines(lines: List<String>) {
    if (lines.isEmpty()) {
        appendLine("- None")
    } else {
        lines.forEach { appendLine("- $it") }
    }
}
