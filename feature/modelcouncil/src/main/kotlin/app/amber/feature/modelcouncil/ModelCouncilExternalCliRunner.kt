package app.amber.feature.modelcouncil

/**
 * Minimal surface the Council Room executor needs from an external CLI runner.
 * Extracted as an interface so unit tests can substitute a fake without needing
 * a real Android [Context] or [TerminalRuntime].
 */
interface ModelCouncilExternalCliRunner {
    suspend fun generate(
        seat: ModelCouncilSeat,
        systemPrompt: String,
        userPrompt: String,
        timeoutMs: Long,
        outputBudgetChars: Int,
        onChunk: (String) -> Unit = {},
    ): String
}
