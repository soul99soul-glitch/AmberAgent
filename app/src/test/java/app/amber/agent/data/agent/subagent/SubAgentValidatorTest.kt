package app.amber.feature.subagent

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun smartModeRejectsBuiltInSubagentId() {
        val input = inputWithSubagentId("explorer")

        val error = runCatching {
            SubAgentValidator.resolveDefinition(
                input,
                setting.copy(mode = SubAgentMode.SMART_DYNAMIC),
                setOf("file_read"),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("disabled in smart dynamic mode"))
    }

    @Test
    fun rosterModeAllowsBuiltInSubagentId() {
        val input = inputWithSubagentId("explorer")

        val result = SubAgentValidator.resolveDefinition(input, setting, setOf("file_read"))

        assertEquals("explorer", result.definition.id)
    }

    @Test
    fun smartModeAssignsEnglishNameWhenNameMissing() {
        val input = inputWithCustomSubagent(name = null)

        val result = SubAgentValidator.resolveDefinition(
            input,
            setting.copy(mode = SubAgentMode.SMART_DYNAMIC),
            setOf("file_read"),
        )

        assertTrue(result.definition.name.matches(Regex("[A-Z][a-z]+")))
        assertTrue(result.definition.dynamic)
    }

    @Test
    fun smartModeReplacesGenericNameInsteadOfFailing() {
        val input = inputWithCustomSubagent(name = "General Helper")

        val result = SubAgentValidator.resolveDefinition(
            input,
            setting.copy(mode = SubAgentMode.SMART_DYNAMIC),
            setOf("file_read"),
        )

        assertTrue(result.definition.name.matches(Regex("[A-Z][a-z]+")))
        assertFalse(result.definition.id.contains("general"))
    }

    @Test
    fun smartModeSavedCustomRoleIsReducedToReadOnlyDynamicTools() {
        val saved = SubAgentDefinition(
            id = "saved-reviewer",
            name = "Saved Reviewer",
            description = "Use when a saved reviewer should inspect a focused issue.",
            systemPrompt = "Boundaries: do not edit files. Report output as findings with evidence.",
            toolAllowlist = setOf("file_read", "terminal_execute"),
        )
        val smartSetting = setting.copy(
            mode = SubAgentMode.SMART_DYNAMIC,
            customDefinitions = listOf(saved),
        )

        val result = SubAgentValidator.resolveDefinition(
            input = inputWithSubagentId("saved-reviewer"),
            setting = smartSetting,
            availableToolNames = setOf("file_read", "terminal_execute"),
        )

        assertEquals(setOf("file_read"), result.definition.toolAllowlist)
        assertTrue(result.definition.dynamic)
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
    fun dynamicRoleToolProfileNarrowsToWebRead() {
        val input = inputWithCustomSubagent(
            toolProfile = "web_read",
        )

        val result = SubAgentValidator.resolveDefinition(
            input = input,
            setting = setting.copy(mode = SubAgentMode.SMART_DYNAMIC),
            availableToolNames = setOf("tools_list", "file_read", "search_web", "scrape_web"),
        )

        assertEquals(setOf("tools_list", "search_web", "scrape_web"), result.definition.toolAllowlist)
    }

    @Test
    fun dynamicRoleToolAllowlistCannotEscapeProfile() {
        val input = inputWithCustomSubagent(
            toolProfile = "web_read",
            toolAllowlist = listOf("search_web", "terminal_execute"),
        )

        val result = SubAgentValidator.resolveDefinition(
            input = input,
            setting = setting.copy(mode = SubAgentMode.SMART_DYNAMIC),
            availableToolNames = setOf("search_web", "terminal_execute"),
        )

        assertEquals(setOf("search_web"), result.definition.toolAllowlist)
    }

    @Test
    fun dynamicRoleToolProfileNoneAllowsNoTools() {
        val input = inputWithCustomSubagent(
            toolProfile = "none",
        )

        val result = SubAgentValidator.resolveDefinition(
            input = input,
            setting = setting.copy(mode = SubAgentMode.SMART_DYNAMIC),
            availableToolNames = setOf("file_read"),
        )

        assertEquals(emptySet<String>(), result.definition.toolAllowlist)
    }

    @Test
    fun dynamicHistoryProfileDoesNotIncludeFullReadTools() {
        val input = inputWithCustomSubagent(
            toolProfile = "history_read",
        )

        val result = SubAgentValidator.resolveDefinition(
            input = input,
            setting = setting.copy(mode = SubAgentMode.SMART_DYNAMIC),
            availableToolNames = setOf("session_list", "session_search", "session_read", "session_expand"),
        )

        assertEquals(setOf("session_list", "session_search"), result.definition.toolAllowlist)
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
    fun taskSpecRejectsOversizedContext() {
        val input = taskInputWithContext("x".repeat(MAX_SUB_AGENT_CONTEXT_CHARS + 1))

        val error = runCatching { SubAgentValidator.parseTask(input) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("task.context is too large"))
    }

    @Test
    fun taskSpecRejectsRawToolResultDumpContext() {
        val input = taskInputWithContext(
            """
            {"tool_call_id":"call_1","tool_name":"file_read","output":"very long raw result"}
            <tool_result>raw output</tool_result>
            """.trimIndent()
        )

        val error = runCatching { SubAgentValidator.parseTask(input) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("raw tool result dumps"))
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
        name: String? = "Focused Code Reviewer",
        toolProfile: String? = null,
        toolAllowlist: List<String>? = null,
        maxTurns: Int? = null,
        timeoutMs: Long? = null,
        outputBudgetChars: Int? = null,
    ) = buildJsonObject {
        put("custom_subagent", buildJsonObject {
            name?.let { put("name", it) }
            put("description", "Use when a narrow read-only code review is needed for a specific file or behavior.")
            put(
                "system_prompt",
                "You are a narrow reviewer. Boundaries: do not edit files, do not spawn agents, and do not use tools outside the allowlist. Report output as summary, findings, evidence, risks, and next steps."
            )
            toolProfile?.let { put("tool_profile", it) }
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

    private fun inputWithSubagentId(id: String) = buildJsonObject {
        put("subagent_id", id)
        put("task", buildJsonObject {
            put("objective", "Review one issue")
            put("output_format", "Findings with evidence")
            put("tools_and_sources", "Use the listed tools only")
            put("boundaries", "Do not edit files")
        })
    }

    private fun taskInputWithContext(context: String) = buildJsonObject {
        put("task", buildJsonObject {
            put("objective", "Review one issue")
            put("output_format", "Findings with evidence")
            put("tools_and_sources", "Use file_read only")
            put("boundaries", "Do not edit files")
            put("context", context)
        })
    }
}
