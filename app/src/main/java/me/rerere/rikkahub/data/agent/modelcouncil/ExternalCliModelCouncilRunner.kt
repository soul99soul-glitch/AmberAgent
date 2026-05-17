package me.rerere.rikkahub.data.agent.modelcouncil

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.agent.terminal.TerminalRuntime
import me.rerere.rikkahub.data.agent.terminal.TerminalRuntimeKind

/**
 * External-CLI [ModelCouncilTextRunner] alternative — runs the seat's reply by
 * shelling out to a CLI (Claude Code, Aider, etc.) through [TerminalRuntime].
 *
 * Extracted from ModelCouncilManager.kt in Phase 1 god-class slimming.
 */
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
