package app.amber.feature.modelcouncil

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import app.amber.feature.terminal.TerminalRuntime
import app.amber.feature.terminal.TerminalRuntimeKind
import app.amber.core.settings.prefs.SettingsAggregator

/**
 * External-CLI [ModelCouncilTextRunner] alternative — runs the seat's reply by
 * shelling out to a CLI (Claude Code, Aider, etc.) through [TerminalRuntime].
 *
 * Extracted from ModelCouncilManager.kt in Phase 1 god-class slimming.
 */
class ExternalCliModelCouncilRunner(
    private val terminalRuntime: TerminalRuntime,
    context: Context,
    private val settingsStore: SettingsAggregator,
) {
    private val externalCliHomeRoot = context.filesDir
        .resolve("amberagent/external-cli-home")
        .also { it.mkdirs() }
        .absolutePath

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
            val runtime = TerminalRuntimeKind.fromWire(seat.externalRuntime)
                ?: settingsStore.settingsFlow.value.agentRuntime.terminalDefaultRuntime
            val command = ModelCouncilExternalCliCommandBuilder.build(
                seat = seat,
                prompt = buildExternalCliPrompt(systemPrompt, userPrompt),
                timeoutMs = timeoutMs,
                externalCliHomeRoot = externalCliHomeRoot,
                runtime = runtime,
            )
            val output = StringBuilder()
            var inCliOutput = false
            var cliOutputEnded = false
            val started = terminalRuntime.startJob(
                command = command,
                timeoutMillis = timeoutMs,
                runtime = runtime,
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
                    externalCliFailureMessage(
                        externalTool = seat.externalTool,
                        exitCode = finished.exitCode,
                        failureError = finished.error,
                        cliOutput = cliOutput,
                        outputTail = finished.outputTail,
                    ),
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

fun externalCliFailureMessage(
    externalTool: String,
    exitCode: Int?,
    failureError: String?,
    cliOutput: String,
    outputTail: String,
): String {
    val combinedOutput = listOfNotNull(
        failureError?.takeIf { it.isNotBlank() },
        cliOutput.takeIf { it.isNotBlank() },
        outputTail.takeIf { it.isNotBlank() },
    ).joinToString("\n")
    val loginHint = ExternalCliToolRegistry.detectLoginHint(combinedOutput)
    if (loginHint.needsUserAction) {
        val spec = runCatching { ExternalCliToolRegistry.requireSpec(externalTool) }.getOrNull()
        val displayName = spec?.displayName ?: "External CLI"
        val loginCommand = spec?.loginCommand ?: "the CLI login command"
        return "External CLI $displayName is not ready. Run $loginCommand in the selected runtime, complete login, then retry. Council runs never start login."
    }

    failureError?.takeIf { it.isNotBlank() }?.let { return it }
    if (cliOutput.isNotBlank()) return cliOutput
    return outputTail
        .redactExternalCliFailureArtifacts()
        .take(1_000)
        .ifBlank { "External CLI seat failed with exit code ${exitCode ?: "unknown"}." }
}

private fun String.redactExternalCliFailureArtifacts(): String =
    replace(Regex("""https?://[^\s"'<>]+"""), "[redacted-url]")
        .replace(
            Regex(
                """(?i)\b(?:verification code|authorization code|user code|code)\b(?:\s+(?:is|:|=))?[\s:=]*[A-Z0-9][A-Z0-9._-]{3,}"""
            ),
            "code [redacted]",
        )
