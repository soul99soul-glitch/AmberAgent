package me.rerere.rikkahub.data.context

import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import app.amber.feature.task.AgentTaskOutputRef
import app.amber.feature.task.AgentTaskRecoveryState
import app.amber.feature.task.AgentTaskRetryPolicy
import app.amber.feature.task.AgentTaskSnapshot
import app.amber.feature.task.AgentTaskStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCapabilitySnapshotBuilderTest {
    @Test
    fun compactSnapshotIncludesToolsAndTasks() {
        val snapshot = AgentCapabilitySnapshotBuilder().build(
            tools = listOf(tool("file_read"), tool("terminal_job_start")),
            tasks = listOf(
                AgentTaskSnapshot(
                    taskId = "task-1",
                    type = "terminal",
                    title = "Install ffmpeg",
                    status = AgentTaskStatus.RUNNING,
                    recoveryState = AgentTaskRecoveryState.RETRYABLE,
                    retryPolicy = AgentTaskRetryPolicy(retryable = true, maxRetries = 1),
                    outputRef = AgentTaskOutputRef(type = "terminal_log", path = "/tmp/job.log", exists = true),
                    createdAtMs = 1L,
                )
            ),
            maxChars = 8_000,
        ).toText()

        assertTrue(snapshot.contains("workspace: file_read"))
        assertTrue(snapshot.contains("terminal_job_start"))
        assertTrue(snapshot.contains("task-1 · terminal · running · retryable"))
        assertTrue(snapshot.contains("output readable"))
        assertTrue(snapshot.contains("Use tool_search to expose callable schemas"))
        assertTrue(snapshot.contains("tools_list is catalog/debug only"))
        assertFalse(snapshot.contains("Use tools_list for exact tool metadata"))
        assertTrue(snapshot.contains("truncated=false"))
    }

    @Test
    fun compactSnapshotMarksTruncation() {
        val tools = (0 until 200).map { tool("file_read_$it") }
        val snapshot = AgentCapabilitySnapshotBuilder().build(
            tools = tools,
            tasks = emptyList(),
            maxChars = 600,
        ).toText()

        assertTrue(snapshot.contains("truncated=true"))
        assertTrue(snapshot.length <= 620)
    }

    private fun tool(name: String) = Tool(
        name = name,
        description = "test tool",
        execute = { listOf(UIMessagePart.Text("ok")) },
    )
}
