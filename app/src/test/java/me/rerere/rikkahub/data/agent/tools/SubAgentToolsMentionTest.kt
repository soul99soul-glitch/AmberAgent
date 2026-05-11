package me.rerere.rikkahub.data.agent.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentToolsMentionTest {
    @Test
    fun councilMentionRequiresModelCouncilStart() {
        val directive = buildMentionOverrideDirective(listOf("council"))

        assertTrue(directive.contains("model_council_start"))
        assertFalse(directive.contains("subagent_start with subagent_id=\"council\""))
    }

    @Test
    fun mixedMentionsKeepSeparateToolFamilies() {
        val directive = buildMentionOverrideDirective(listOf("council", "oracle"))

        assertTrue(directive.contains("model_council_start"))
        assertTrue(directive.contains("subagent_start with subagent_id=\"oracle\""))
    }

    @Test
    fun modelCouncilToolsDetectCouncilMentionWithoutSubAgentTools() {
        val directive = buildModelCouncilMentionOverrideDirective("请 @council 看一下这个方案")

        assertTrue(directive.contains("model_council_start"))
        assertFalse(directive.contains("subagent_start"))
    }
}
