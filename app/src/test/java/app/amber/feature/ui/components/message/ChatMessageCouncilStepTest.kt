package app.amber.feature.ui.components.message

import app.amber.ai.ui.UIMessagePart
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ChatMessageCouncilStepTest {
    @Test
    fun `partial failure is rendered as failed tool status`() {
        assertEquals(
            AgentToolStatus.FAILED,
            ModelCouncilCardStatus.PARTIAL_FAILED.toAgentToolStatus(),
        )
    }

    @Test
    fun `cold start restores seats and final text from council transcript`() {
        val runRoot = Files.createTempDirectory("amber-council-ui").toFile()
        try {
            val runId = "run-ui-1"
            val transcript = File(runRoot, "$runId.jsonl")
            val seats = """
                [{"seat_id":"seat-a","name":"分析席","role":"supporter","model_id":"00000000-0000-0000-0000-000000000001"},
                 {"seat_id":"seat-b","name":"批评席","role":"opponent","model_id":"00000000-0000-0000-0000-000000000002"}]
            """.trimIndent().replace("\n", "")
            val turns = """
                [{"round":1,"seat_id":"seat-a","seat_name":"分析席","content":"从持久化记录恢复的席位内容"},
                 {"round":1,"seat_id":"seat-b","seat_name":"批评席","content":"第二席位内容"}]
            """.trimIndent().replace("\n", "")
            val result = """{"final_recommendation":"从持久化记录恢复的综合结论"}"""
            val started = buildJsonObject {
                put("event", "started")
                put("payload", buildJsonObject {
                    put("status", "running")
                    put("run_id", runId)
                    // The manager encodes arrays as JSON strings in its tool payload.
                    put("seats", seats)
                })
            }
            val finished = buildJsonObject {
                put("event", "finished")
                put("payload", buildJsonObject {
                    put("status", "completed")
                    put("run_id", runId)
                    put("seats", seats)
                    put("turns", turns)
                    put("result", result)
                })
            }
            transcript.writeText("$started\n$finished\n")

            val tools = listOf(
                tool(
                    name = "model_council_start",
                    output = buildJsonObject {
                        put("status", "running")
                        put("run_id", runId)
                        put("transcript_path", transcript.absolutePath)
                        put("seats", seats)
                    }.toString(),
                ),
            )
            val persisted = readCouncilTranscriptPayloads(tools, runRoot)

            assertEquals(ModelCouncilCardStatus.COMPLETED, parseLatestCouncilStatus(tools, persisted))
            assertEquals(
                listOf("seat-a", "seat-b"),
                extractCouncilSeatEntries(tools, persisted).map { it.key },
            )
            assertEquals(
                listOf("分析席", "批评席"),
                extractCouncilSeatEntries(tools, persisted).map { it.label },
            )
            assertTrue(
                extractFinalCouncilSeatText(tools, "seat-a", persisted)
                    .contains("从持久化记录恢复的席位内容"),
            )
            assertEquals(
                "从持久化记录恢复的综合结论",
                extractFinalCouncilSynthesisText(tools, persisted),
            )
        } finally {
            runRoot.deleteRecursively()
        }
    }

    @Test
    fun `latest interrupted tool status wins over an older running transcript`() {
        val runRoot = Files.createTempDirectory("amber-council-interrupted").toFile()
        try {
            val runId = "run-ui-interrupted"
            val transcript = File(runRoot, "$runId.jsonl")
            val seats = """[{"seat_id":"seat-a","name":"分析席","role":"supporter","model_id":"00000000-0000-0000-0000-000000000001"}]"""
            transcript.writeText(
                buildJsonObject {
                    put("event", "started")
                    put("payload", buildJsonObject {
                        put("status", "running")
                        put("run_id", runId)
                        put("seats", seats)
                    })
                }.toString() + "\n",
            )
            val tools = listOf(
                tool(
                    name = "model_council_start",
                    output = """{"status":"running","run_id":"$runId"}""",
                ),
                tool(
                    name = "model_council_read",
                    output = buildJsonObject {
                        put("status", "interrupted")
                        put("run_id", runId)
                        put("transcript_path", transcript.absolutePath)
                    }.toString(),
                ),
            )
            val persisted = readCouncilTranscriptPayloads(tools, runRoot)

            assertEquals(ModelCouncilCardStatus.INTERRUPTED, parseLatestCouncilStatus(tools, persisted))
            assertEquals(listOf("seat-a"), extractCouncilSeatEntries(tools, persisted).map { it.key })
        } finally {
            runRoot.deleteRecursively()
        }
    }

    private fun tool(name: String, output: String): UIMessagePart.Tool =
        UIMessagePart.Tool(
            toolCallId = name,
            toolName = name,
            input = "{}",
            output = listOf(UIMessagePart.Text(output)),
        )
}
