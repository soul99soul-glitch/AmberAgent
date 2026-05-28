package app.amber.feature.ui.components.ai

import app.amber.feature.subagent.DEFAULT_SUB_AGENT_TIMEOUT_MS
import app.amber.feature.subagent.DEFAULT_SUB_AGENT_MAX_TURNS
import app.amber.feature.subagent.DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS
import app.amber.feature.subagent.SubAgentDefinition
import app.amber.feature.subagent.SubAgentDefinitions
import app.amber.feature.subagent.SubAgentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionRolesTest {
    @Test
    fun councilAppearsWhenModelCouncilIsEnabled() {
        val items = buildMentionRoleItems(
            subAgentEnabled = false,
            modelCouncilEnabled = true,
        )

        assertEquals(listOf("council"), items.map { it.id })
        assertEquals(MentionRoleKind.COUNCIL, items.single().kind)
    }

    @Test
    fun subagentsAndCouncilCanCoexist() {
        val items = buildMentionRoleItems(
            subAgentEnabled = true,
            modelCouncilEnabled = true,
        )

        assertTrue(items.any { it.id == "explorer" })
        assertTrue(items.any { it.id == "council" })
        assertEquals(SubAgentDefinitions.builtIns.size + 1, items.size)
    }

    @Test
    fun mentionFilteringMatchesCouncil() {
        val items = buildMentionRoleItems(
            subAgentEnabled = true,
            modelCouncilEnabled = true,
        )

        val matches = filterMentionRoleItems(items, "cou")

        assertEquals(listOf("council"), matches.map { it.id })
        assertFalse(matches.any { it.kind == MentionRoleKind.SUBAGENT })
    }

    @Test
    fun smartModeHidesBuiltInsButKeepsCouncilAndCustomRoles() {
        val custom = SubAgentDefinition(
            id = "brief-reader",
            name = "Brief Reader",
            description = "Use when a saved custom brief-reading role is needed.",
            systemPrompt = "Boundaries: read only. Report output as a short summary with evidence.",
            toolAllowlist = setOf("file_read"),
            maxTurns = DEFAULT_SUB_AGENT_MAX_TURNS,
            timeoutMs = DEFAULT_SUB_AGENT_TIMEOUT_MS,
            outputBudgetChars = DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS,
        )

        val items = buildMentionRoleItems(
            subAgentEnabled = true,
            modelCouncilEnabled = true,
            subAgentMode = SubAgentMode.SMART_DYNAMIC,
            customSubAgents = listOf(custom),
        )

        assertFalse(items.any { it.id == "explorer" })
        assertTrue(items.any { it.id == "brief-reader" })
        assertTrue(items.any { it.id == "council" })
    }
}
