package app.amber.feature.terminal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class TerminalRuntimeKind(val wireName: String) {
    @SerialName("builtin_alpine")
    BUILTIN_ALPINE("builtin_alpine"),

    @SerialName("android_shell")
    ANDROID_SHELL("android_shell"),

    @SerialName("termux_external")
    TERMUX_EXTERNAL("termux_external");

    companion object {
        fun fromWire(value: String?): TerminalRuntimeKind? =
            entries.firstOrNull { it.wireName == value || it.name.equals(value, ignoreCase = true) }
    }
}

@Serializable
enum class TerminalJobStatus(val wireName: String) {
    @SerialName("queued")
    QUEUED("queued"),

    @SerialName("running")
    RUNNING("running"),

    @SerialName("completed")
    COMPLETED("completed"),

    @SerialName("failed")
    FAILED("failed"),

    @SerialName("cancelled")
    CANCELLED("cancelled"),

    @SerialName("timed_out")
    TIMED_OUT("timed_out");

    val running: Boolean
        get() = this == QUEUED || this == RUNNING
}

data class TerminalJobSnapshot(
    val jobId: String,
    val runtime: TerminalRuntimeKind,
    val status: TerminalJobStatus,
    val exitCode: Int?,
    val running: Boolean,
    val outputTail: String,
    val outputLogPath: String,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val error: String?,
)

data class TermuxRuntimeStatus(
    val installed: Boolean,
    val runCommandPermissionGranted: Boolean,
    val allowExternalAppsConfigured: Boolean?,
    val ready: Boolean,
    val message: String,
)

data class TerminalInstallPlan(
    val packages: List<String>,
    val command: String,
    val verifyCommand: String,
)
