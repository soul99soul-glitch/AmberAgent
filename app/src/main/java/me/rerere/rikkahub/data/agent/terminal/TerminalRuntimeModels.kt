package me.rerere.rikkahub.data.agent.terminal

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

internal object TerminalInstallPlanner {
    private val packageNameRegex = Regex("[A-Za-z0-9._+:-]+")

    fun build(packages: List<String>, runtime: TerminalRuntimeKind = TerminalRuntimeKind.BUILTIN_ALPINE): TerminalInstallPlan {
        val normalized = packages
            .flatMap { it.split(Regex("[,\\s]+")) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        require(normalized.isNotEmpty()) { "packages must not be empty" }
        normalized.forEach { name ->
            require(packageNameRegex.matches(name)) { "Unsupported package name: $name" }
        }

        val verifyCommands = verifyCommandsFor(normalized)
        if (runtime == TerminalRuntimeKind.ANDROID_SHELL) {
            return TerminalInstallPlan(
                packages = normalized,
                command = "echo 'Package installation is not available in android_shell runtime.' >&2; exit 64",
                verifyCommand = verifyCommands.joinToString(" && "),
            )
        }

        val packageManagerPackages = linkedSetOf<String>()
        var needsYtDlpFallback = false
        normalized.forEach { name ->
            when (name) {
                "yt-dlp", "ytdlp" -> {
                    packageManagerPackages += when (runtime) {
                        TerminalRuntimeKind.TERMUX_EXTERNAL -> "python"
                        else -> "python3"
                    }
                    if (runtime == TerminalRuntimeKind.BUILTIN_ALPINE) {
                        packageManagerPackages += "py3-pip"
                        packageManagerPackages += "ca-certificates"
                    }
                    needsYtDlpFallback = true
                }

                "ffmpeg" -> {
                    packageManagerPackages += "ffmpeg"
                }

                else -> {
                    packageManagerPackages += name
                }
            }
        }

        val command = buildString {
            append("set -e\n")
            when (runtime) {
                TerminalRuntimeKind.BUILTIN_ALPINE -> {
                    append("need_apk=\"\"\n")
                    append("need_yt_dlp=0\n")
                    normalized.forEach { name ->
                        when (name) {
                            "ffmpeg" -> {
                                append("if command -v ffmpeg >/dev/null 2>&1; then\n")
                                append("  echo \"ffmpeg already available: ${'$'}(command -v ffmpeg)\"\n")
                                append("else\n")
                                append("  need_apk=\"${'$'}need_apk ffmpeg\"\n")
                                append("fi\n")
                            }

                            "yt-dlp", "ytdlp" -> {
                                append("if command -v yt-dlp >/dev/null 2>&1; then\n")
                                append("  echo \"yt-dlp already available: ${'$'}(command -v yt-dlp)\"\n")
                                append("else\n")
                                append("  need_apk=\"${'$'}need_apk yt-dlp python3 py3-pip ca-certificates\"\n")
                                append("  need_yt_dlp=1\n")
                                append("fi\n")
                            }

                            else -> {
                                append("need_apk=\"${'$'}need_apk ${name.shellSafePackage()}\"\n")
                            }
                        }
                    }
                    if (packageManagerPackages.isNotEmpty()) {
                        append("if [ -n \"${'$'}need_apk\" ]; then\n")
                        append("  apk update\n")
                        append("  apk add --no-cache ${'$'}need_apk\n")
                        append("else\n")
                        append("  echo 'Requested terminal packages are already available; skipping apk add.'\n")
                        append("fi\n")
                    }
                    if (needsYtDlpFallback) {
                        append("if [ \"${'$'}need_yt_dlp\" = \"1\" ] && ! command -v yt-dlp >/dev/null 2>&1; then\n")
                        append("  apk add --no-cache yt-dlp || python3 -m pip install --break-system-packages --upgrade yt-dlp\n")
                        append("fi\n")
                    }
                }

                TerminalRuntimeKind.TERMUX_EXTERNAL -> {
                    append("need_pkg=\"\"\n")
                    append("need_yt_dlp=0\n")
                    normalized.forEach { name ->
                        when (name) {
                            "ffmpeg" -> {
                                append("if command -v ffmpeg >/dev/null 2>&1; then\n")
                                append("  echo \"ffmpeg already available: ${'$'}(command -v ffmpeg)\"\n")
                                append("else\n")
                                append("  need_pkg=\"${'$'}need_pkg ffmpeg\"\n")
                                append("fi\n")
                            }

                            "yt-dlp", "ytdlp" -> {
                                append("if command -v yt-dlp >/dev/null 2>&1; then\n")
                                append("  echo \"yt-dlp already available: ${'$'}(command -v yt-dlp)\"\n")
                                append("else\n")
                                append("  need_pkg=\"${'$'}need_pkg yt-dlp python\"\n")
                                append("  need_yt_dlp=1\n")
                                append("fi\n")
                            }

                            else -> {
                                append("need_pkg=\"${'$'}need_pkg ${name.shellSafePackage()}\"\n")
                            }
                        }
                    }
                    if (packageManagerPackages.isNotEmpty()) {
                        append("if [ -n \"${'$'}need_pkg\" ]; then\n")
                        append("  pkg update -y\n")
                        append("  pkg install -y ${'$'}need_pkg\n")
                        append("else\n")
                        append("  echo 'Requested terminal packages are already available; skipping pkg install.'\n")
                        append("fi\n")
                    }
                    if (needsYtDlpFallback) {
                        append("if [ \"${'$'}need_yt_dlp\" = \"1\" ] && ! command -v yt-dlp >/dev/null 2>&1; then\n")
                        append("  pkg install -y yt-dlp || python -m pip install --upgrade yt-dlp\n")
                        append("fi\n")
                    }
                }

                TerminalRuntimeKind.ANDROID_SHELL -> Unit
            }
            append(verifyCommands.joinToString("\n"))
        }

        return TerminalInstallPlan(
            packages = normalized,
            command = command,
            verifyCommand = verifyCommands.joinToString(" && "),
        )
    }

    private fun verifyCommandsFor(packages: List<String>): List<String> =
        packages.map { name ->
            when (name) {
                "yt-dlp", "ytdlp" -> "yt-dlp --version"
                "ffmpeg" -> "ffmpeg -version | head -n 1"
                else -> "command -v ${name.shellQuoted()} || true"
            }
        }
}

private fun String.shellSafePackage(): String {
    require(Regex("[A-Za-z0-9._+:-]+").matches(this)) { "Unsupported package name: $this" }
    return this
}

internal class TerminalOutputBuffer(
    private val maxChars: Int,
) {
    private val buffer = StringBuilder()
    private var omittedChars = 0

    @Synchronized
    fun append(text: String) {
        buffer.append(text)
        trimIfNeeded()
    }

    @Synchronized
    fun appendLine(line: String) {
        buffer.append(line).append('\n')
        trimIfNeeded()
    }

    @Synchronized
    fun snapshot(): String {
        val output = buffer.toString()
        return if (omittedChars > 0) {
            "Output truncated to the last $maxChars characters; omitted $omittedChars earlier characters.\n$output"
        } else {
            output
        }
    }

    private fun trimIfNeeded() {
        if (buffer.length > maxChars) {
            val overflow = buffer.length - maxChars
            buffer.delete(0, overflow)
            omittedChars += overflow
        }
    }
}

internal fun String.shellQuoted(): String =
    "'" + replace("'", "'\"'\"'") + "'"
