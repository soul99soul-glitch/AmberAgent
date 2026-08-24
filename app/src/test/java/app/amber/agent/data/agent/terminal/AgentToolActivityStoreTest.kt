package app.amber.feature.terminal

import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.runtime.SandboxActivityUiState
import app.amber.feature.runtime.ToolActivityStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentToolActivityStoreTest {
    @Test
    fun explicitTerminalStatusOverridesZeroExitCode() {
        listOf(ToolActivityStatus.TIMED_OUT, ToolActivityStatus.INTERRUPTED).forEach { status ->
            val store = AgentToolActivityStore()
            store.start(
                SandboxActivityUiState(
                    toolCallId = status.name,
                    toolName = "terminal_job_start",
                    title = "Terminal job",
                    status = ToolActivityStatus.RUNNING,
                )
            )

            store.complete(status.name, exitCode = 0, output = "", explicitStatus = status)

            assertEquals(status, store.sandboxActivity.value?.status)
        }
    }
}
