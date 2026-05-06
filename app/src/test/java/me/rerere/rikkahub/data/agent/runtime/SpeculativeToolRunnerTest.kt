package me.rerere.rikkahub.data.agent.runtime

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeculativeToolRunnerTest {
    private val dispatcher = AgentToolDispatcher(Json { ignoreUnknownKeys = true }, PermissionDecisionResolver())

    @Test
    fun safeReadOnlyToolCanBeReused() = runBlocking {
        val runner = SpeculativeToolRunner(this, dispatcher)
        val call = toolCall("file_read", id = "call-1", input = """{"path":"notes.md"}""")

        runner.observe(listOf(call), mapOf("file_read" to tool("file_read", "file content")))
        waitForCompletion(runner)

        val reusable = runner.reusableResults(listOf(call))

        assertEquals(setOf("call-1"), reusable.keys)
        assertEquals("file content", (reusable["call-1"]!!.output.single() as UIMessagePart.Text).text)
    }

    @Test
    fun changedFinalToolDiscardsSpeculativeResult() = runBlocking {
        val runner = SpeculativeToolRunner(this, dispatcher)
        val first = toolCall("file_read", id = "call-1", input = """{"path":"a.md"}""")
        val changed = toolCall("file_read", id = "call-1", input = """{"path":"b.md"}""")

        runner.observe(listOf(first), mapOf("file_read" to tool("file_read", "file content")))
        waitForCompletion(runner)
        val reusable = runner.reusableResults(listOf(changed))

        assertTrue(reusable.isEmpty())
        assertEquals(SpeculativeToolStatus.DISCARDED, runner.snapshot().single().status)
    }

    @Test
    fun unsafeToolIsNotStartedSpeculatively() = runBlocking {
        val runner = SpeculativeToolRunner(this, dispatcher)

        runner.observe(
            listOf(
                toolCall("terminal_job_start", id = "call-terminal"),
                toolCall("officepro_read_screen", id = "call-office"),
                toolCall("agent_task_retry", id = "call-retry"),
            ),
            mapOf(
                "terminal_job_start" to tool("terminal_job_start", "nope"),
                "officepro_read_screen" to tool("officepro_read_screen", "nope"),
                "agent_task_retry" to tool("agent_task_retry", "nope"),
            ),
        )

        assertTrue(runner.snapshot().isEmpty())
    }

    private suspend fun waitForCompletion(runner: SpeculativeToolRunner) {
        repeat(20) {
            if (runner.snapshot().any { it.status == SpeculativeToolStatus.COMPLETED }) return
            delay(10)
        }
    }

    private fun tool(name: String, output: String) = Tool(
        name = name,
        description = "test tool",
        execute = { listOf(UIMessagePart.Text(output)) },
    )

    private fun toolCall(
        name: String,
        id: String = "call-$name",
        input: String = "{}",
    ) = UIMessagePart.Tool(
        toolCallId = id,
        toolName = name,
        input = input,
    )
}
