package me.rerere.rikkahub.ui.components.ai

import me.rerere.rikkahub.data.agent.subagent.SubAgentDefinitions
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
}
