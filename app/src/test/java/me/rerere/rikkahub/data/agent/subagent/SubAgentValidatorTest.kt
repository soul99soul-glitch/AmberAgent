package me.rerere.rikkahub.data.agent.subagent

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentValidatorTest {
    private val setting = SubAgentRuntimeSetting(
        enabled = true,
        maxTurns = 4,
        timeoutMs = 60_000L,
        outputBudgetChars = 12_000,
    )

    @Test
    fun taskSpecRequiresFourElements() {
        val input = buildJsonObject {
            put("task", buildJsonObject {
                put("objective", "Inspect one focused issue")
                put("output_format", "Short findings")
                put("tools_and_sources", "Use file_read only")
            })
        }

        val error = runCatching { SubAgentValidator.parseTask(input) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("boundaries"))
    }

    @Test
    fun dynamicRoleRejectsGenericName() {
        val input = inputWithCustomSubagent(name = "General Helper")

        val error = runCatching {
            SubAgentValidator.resolveDefinition(input, setting, setOf("file_read"))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("too broad"))
    }

    @Test
    fun dynamicRoleDefaultsToAvailableReadOnlyTools() {
        val input = inputWithCustomSubagent()

        val result = SubAgentValidator.resolveDefinition(
            input = input,
            setting = setting,
            availableToolNames = setOf(
                "file_read",
                "file_search",
                "session_search",
                "session_read",
                "terminal_execute",
                "http_request",
                "officepro_capture_context",
                "officepro_context_digest",
            ),
        )

        assertEquals(setOf("file_read", "file_search", "session_search"), result.definition.toolAllowlist)
        assertTrue(result.definition.dynamic)
    }

    @Test
    fun taskSpecParsesHistoryShardFields() {
        val input = buildJsonObject {
            put("task", buildJsonObject {
                put("objective", "Summarize historical sessions")
                put("output_format", "Summary with source ids")
                put("tools_and_sources", "Use session_read with the grant")
                put("boundaries", "Only read granted sessions")
                put("session_grant_id", "grant-1")
                put("history_query", "飞书增强模式")
                put("shard_index", 1)
                put("shard_count", 3)
                put("source_session_ids", buildJsonArray {
                    add("session-a")
                    add("session-b")
                })
            })
        }

        val task = SubAgentValidator.parseTask(input)

        assertEquals("grant-1", task.sessionGrantId)
        assertEquals(listOf("session-a", "session-b"), task.sourceSessionIds)
        assertEquals("飞书增强模式", task.historyQuery)
        assertEquals(1, task.shardIndex)
        assertEquals(3, task.shardCount)
    }

    @Test
    fun dynamicRoleRejectsUnavailableExplicitTool() {
        val input = inputWithCustomSubagent(
            toolAllowlist = listOf("file_read", "terminal_execute"),
        )

        val error = runCatching {
            SubAgentValidator.resolveDefinition(input, setting, setOf("file_read"))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("unavailable tools"))
    }

    @Test
    fun dynamicRoleRejectsOverBudgetConfig() {
        val input = inputWithCustomSubagent(maxTurns = 9)

        val error = runCatching {
            SubAgentValidator.resolveDefinition(input, setting, setOf("file_read"))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("max_turns"))
    }

    @Test
    fun dynamicRoleAcceptsExtendedBudgetWhenSettingAllowsIt() {
        val extendedSetting = setting.copy(
            timeoutMs = EXTENDED_SUB_AGENT_TIMEOUT_MS,
            outputBudgetChars = EXTENDED_SUB_AGENT_OUTPUT_BUDGET_CHARS,
        )
        val input = inputWithCustomSubagent(
            timeoutMs = EXTENDED_SUB_AGENT_TIMEOUT_MS,
            outputBudgetChars = EXTENDED_SUB_AGENT_OUTPUT_BUDGET_CHARS,
        )

        val result = SubAgentValidator.resolveDefinition(input, extendedSetting, setOf("file_read"))

        assertEquals(EXTENDED_SUB_AGENT_TIMEOUT_MS, result.definition.timeoutMs)
        assertEquals(EXTENDED_SUB_AGENT_OUTPUT_BUDGET_CHARS, result.definition.outputBudgetChars)
    }

    private fun inputWithCustomSubagent(
        name: String = "Focused Code Reviewer",
        toolAllowlist: List<String>? = null,
        maxTurns: Int? = null,
        timeoutMs: Long? = null,
        outputBudgetChars: Int? = null,
    ) = buildJsonObject {
        put("custom_subagent", buildJsonObject {
            put("name", name)
            put("description", "Use when a narrow read-only code review is needed for a specific file or behavior.")
            put(
                "system_prompt",
                "You are a narrow reviewer. Boundaries: do not edit files, do not spawn agents, and do not use tools outside the allowlist. Report output as summary, findings, evidence, risks, and next steps."
            )
            toolAllowlist?.let { tools ->
                put("tool_allowlist", buildJsonArray {
                    tools.forEach { add(JsonPrimitive(it)) }
                })
            }
            maxTurns?.let { put("max_turns", it) }
            timeoutMs?.let { put("timeout_ms", it) }
            outputBudgetChars?.let { put("output_budget_chars", it) }
        })
        put("task", buildJsonObject {
            put("objective", "Review one issue")
            put("output_format", "Findings with evidence")
            put("tools_and_sources", "Use the listed tools only")
            put("boundaries", "Do not edit files")
        })
    }
}
