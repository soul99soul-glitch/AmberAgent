package app.amber.feature.terminal

data class TerminalResult(
    val exitCode: Int?,
    val output: String,
    val runtime: String,
    val workspace: String,
    val syncNote: String,
    val jobId: String? = null,
    val status: String? = null,
    val running: Boolean = false,
    val outputLogPath: String = "",
    val sshProfileId: String? = null,
    val sshRoute: List<String> = emptyList(),
    val sshFailure: SshHopFailure? = null,
)

data class TerminalSessionInfo(
    val id: String,
    val runtime: String,
    val workspace: String,
)

data class TerminalReadResult(
    val id: String,
    val output: String,
    val running: Boolean,
)
