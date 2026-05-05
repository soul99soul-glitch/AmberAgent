package me.rerere.rikkahub.data.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TerminalRuntimeModelsTest {
    @Test
    fun outputBufferKeepsTailAndReportsTruncation() {
        val buffer = TerminalOutputBuffer(maxChars = 12)

        buffer.appendLine("first")
        buffer.appendLine("second")
        buffer.appendLine("third")

        val snapshot = buffer.snapshot()
        assertTrue(snapshot.contains("Output truncated"))
        assertTrue(snapshot.contains("third"))
        assertFalse(snapshot.contains("first"))
    }

    @Test
    fun installPlannerBuildsAlpineYtDlpAndFfmpegInstall() {
        val plan = TerminalInstallPlanner.build(
            packages = listOf("yt-dlp", "ffmpeg"),
            runtime = TerminalRuntimeKind.BUILTIN_ALPINE,
        )

        assertEquals(listOf("yt-dlp", "ffmpeg"), plan.packages)
        assertTrue(plan.command.contains("command -v yt-dlp"))
        assertTrue(plan.command.contains("command -v ffmpeg"))
        assertTrue(plan.command.contains("apk add --no-cache"))
        assertTrue(plan.command.contains("skipping apk add"))
        assertTrue(plan.command.contains("apk add --no-cache yt-dlp ||"))
        assertFalse(plan.command.contains("need_apk yt-dlp"))
        assertTrue(plan.command.contains("python3 -m pip install"))
        assertTrue(plan.command.contains("yt-dlp --version"))
        assertTrue(plan.command.contains("ffmpeg -version"))
    }

    @Test
    fun installPlannerBuildsTermuxInstallCommand() {
        val plan = TerminalInstallPlanner.build(
            packages = listOf("yt-dlp"),
            runtime = TerminalRuntimeKind.TERMUX_EXTERNAL,
        )

        assertTrue(plan.command.contains("pkg update -y"))
        assertTrue(plan.command.contains("pkg install -y"))
        assertTrue(plan.command.contains("python -m pip install"))
        assertFalse(plan.command.contains("apk add"))
    }

    @Test
    fun installPlannerRejectsUnsafePackageNames() {
        runCatching {
            TerminalInstallPlanner.build(
                packages = listOf("ffmpeg;rm -rf /"),
                runtime = TerminalRuntimeKind.BUILTIN_ALPINE,
            )
        }.onSuccess {
            throw AssertionError("Expected unsafe package name to be rejected")
        }
    }

    @Test
    fun terminalJobLogCapsFileSize() {
        val file = Files.createTempFile("amberagent-terminal-log", ".log").toFile()
        try {
            val log = TerminalJobLog(file, maxBytes = 128)
            repeat(20) { index ->
                log.append("line-$index ${"x".repeat(32)}\n")
            }

            assertTrue(file.length() <= 128)
            val text = file.readText()
            assertTrue(text.contains("terminal log truncated"))
            assertTrue(text.contains("line-19"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun installPlannerRejectsAndroidShellInstall() {
        val plan = TerminalInstallPlanner.build(
            packages = listOf("ffmpeg"),
            runtime = TerminalRuntimeKind.ANDROID_SHELL,
        )

        assertTrue(plan.command.contains("Package installation is not available"))
        assertTrue(plan.command.contains("exit 64"))
    }

    @Test
    fun runtimeAndStatusWireValuesAreStable() {
        assertEquals(TerminalRuntimeKind.BUILTIN_ALPINE, TerminalRuntimeKind.fromWire("builtin_alpine"))
        assertEquals(TerminalRuntimeKind.TERMUX_EXTERNAL, TerminalRuntimeKind.fromWire("TERMUX_EXTERNAL"))
        assertTrue(TerminalJobStatus.QUEUED.running)
        assertTrue(TerminalJobStatus.RUNNING.running)
        assertFalse(TerminalJobStatus.COMPLETED.running)
        assertFalse(TerminalJobStatus.TIMED_OUT.running)
    }
}
