package me.rerere.rikkahub.data.agent.terminal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

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

        val genericPackages = linkedSetOf<String>()
        var needsFfmpeg = false
        var needsYtDlpFallback = false
        normalized.forEach { name ->
            when (name) {
                "yt-dlp", "ytdlp" -> {
                    needsYtDlpFallback = true
                }

                "ffmpeg" -> {
                    needsFfmpeg = true
                }

                else -> {
                    genericPackages += name
                }
            }
        }

        val command = buildString {
            append("set -e\n")
            when (runtime) {
                TerminalRuntimeKind.BUILTIN_ALPINE -> {
                    append("need_ffmpeg=0\n")
                    append("need_yt_dlp=0\n")
                    append("need_apk=\"\"\n")
                    normalized.forEach { name ->
                        when (name) {
                            "ffmpeg" -> {
                                append("if command -v ffmpeg >/dev/null 2>&1; then\n")
                                append("  echo \"ffmpeg already available: ${'$'}(command -v ffmpeg)\"\n")
                                append("else\n")
                                append("  need_ffmpeg=1\n")
                                append("fi\n")
                            }

                            "yt-dlp", "ytdlp" -> {
                                append("if command -v yt-dlp >/dev/null 2>&1; then\n")
                                append("  echo \"yt-dlp already available: ${'$'}(command -v yt-dlp)\"\n")
                                append("else\n")
                                append("  need_yt_dlp=1\n")
                                append("fi\n")
                            }

                            else -> {
                                append("need_apk=\"${'$'}need_apk ${name.shellSafePackage()}\"\n")
                            }
                        }
                    }
                    if (needsFfmpeg || needsYtDlpFallback || genericPackages.isNotEmpty()) {
                        append("if [ \"${'$'}need_ffmpeg\" = \"1\" ] || [ \"${'$'}need_yt_dlp\" = \"1\" ] || [ -n \"${'$'}need_apk\" ]; then\n")
                        append("  apk update || echo 'apk update failed; continuing with targeted installs.'\n")
                        append("else\n")
                        append("  echo 'Requested terminal packages are already available; skipping apk add.'\n")
                        append("fi\n")
                    }
                    if (needsFfmpeg) {
                        append("if [ \"${'$'}need_ffmpeg\" = \"1\" ]; then\n")
                        append("  apk add --no-cache ffmpeg\n")
                        append("fi\n")
                    }
                    if (genericPackages.isNotEmpty()) {
                        append("if [ -n \"${'$'}need_apk\" ]; then\n")
                        append("  apk add --no-cache ${'$'}need_apk\n")
                        append("fi\n")
                    }
                    if (needsYtDlpFallback) {
                        append("if [ \"${'$'}need_yt_dlp\" = \"1\" ] && ! command -v yt-dlp >/dev/null 2>&1; then\n")
                        append("  apk add --no-cache yt-dlp || { echo 'apk yt-dlp unavailable; falling back to pip.'; apk add --no-cache python3 py3-pip ca-certificates; python3 -m pip install --break-system-packages --upgrade yt-dlp; }\n")
                        append("fi\n")
                    }
                }

                TerminalRuntimeKind.TERMUX_EXTERNAL -> {
                    append("need_ffmpeg=0\n")
                    append("need_yt_dlp=0\n")
                    append("need_pkg=\"\"\n")
                    normalized.forEach { name ->
                        when (name) {
                            "ffmpeg" -> {
                                append("if command -v ffmpeg >/dev/null 2>&1; then\n")
                                append("  echo \"ffmpeg already available: ${'$'}(command -v ffmpeg)\"\n")
                                append("else\n")
                                append("  need_ffmpeg=1\n")
                                append("fi\n")
                            }

                            "yt-dlp", "ytdlp" -> {
                                append("if command -v yt-dlp >/dev/null 2>&1; then\n")
                                append("  echo \"yt-dlp already available: ${'$'}(command -v yt-dlp)\"\n")
                                append("else\n")
                                append("  need_yt_dlp=1\n")
                                append("fi\n")
                            }

                            else -> {
                                append("need_pkg=\"${'$'}need_pkg ${name.shellSafePackage()}\"\n")
                            }
                        }
                    }
                    if (needsFfmpeg || needsYtDlpFallback || genericPackages.isNotEmpty()) {
                        append("if [ \"${'$'}need_ffmpeg\" = \"1\" ] || [ \"${'$'}need_yt_dlp\" = \"1\" ] || [ -n \"${'$'}need_pkg\" ]; then\n")
                        append("  pkg update -y || echo 'pkg update failed; continuing with targeted installs.'\n")
                        append("else\n")
                        append("  echo 'Requested terminal packages are already available; skipping pkg install.'\n")
                        append("fi\n")
                    }
                    if (needsFfmpeg) {
                        append("if [ \"${'$'}need_ffmpeg\" = \"1\" ]; then\n")
                        append("  pkg install -y ffmpeg\n")
                        append("fi\n")
                    }
                    if (genericPackages.isNotEmpty()) {
                        append("if [ -n \"${'$'}need_pkg\" ]; then\n")
                        append("  pkg install -y ${'$'}need_pkg\n")
                        append("fi\n")
                    }
                    if (needsYtDlpFallback) {
                        append("if [ \"${'$'}need_yt_dlp\" = \"1\" ] && ! command -v yt-dlp >/dev/null 2>&1; then\n")
                        append("  pkg install -y yt-dlp || { echo 'pkg yt-dlp unavailable; falling back to pip.'; pkg install -y python; python -m pip install --upgrade yt-dlp; }\n")
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

internal class TerminalJobLog(
    val file: File,
    private val maxBytes: Int,
) {
    private var truncatedBytes = 0L

    @Synchronized
    fun append(text: String) {
        file.parentFile?.mkdirs()
        val incoming = text.toByteArray(Charsets.UTF_8)
        val currentLength = file.takeIf { it.exists() }?.length() ?: 0L
        if (currentLength + incoming.size <= maxBytes) {
            file.appendBytes(incoming)
            return
        }

        val note = "\n... [terminal log truncated; omitted %d earlier bytes] ...\n"
        val existing = file.takeIf { it.exists() }?.readBytes() ?: ByteArray(0)
        val combined = ByteArray(existing.size + incoming.size)
        existing.copyInto(combined, destinationOffset = 0)
        incoming.copyInto(combined, destinationOffset = existing.size)
        val firstHeader = note.format(truncatedBytes).toByteArray(Charsets.UTF_8)
        val firstKeepBytes = (maxBytes - firstHeader.size).coerceAtLeast(0)
        val overflow = (combined.size - firstKeepBytes).coerceAtLeast(0)
        truncatedBytes += overflow.toLong()
        val header = note.format(truncatedBytes).toByteArray(Charsets.UTF_8)
        val keepBytes = (maxBytes - header.size).coerceAtLeast(0)
        val tail = combined.takeLast(keepBytes).toByteArray()
        file.writeBytes(header + tail)
    }
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
