package app.amber.feature.subagent

import app.amber.ai.ui.UIMessage
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.GenerativeUiSetting
import app.amber.core.settings.Settings
import app.amber.core.model.AssistantRegex
import app.amber.core.settings.SpeculativeToolExecutionSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubAgentIsolationTest {
    @Test
    fun isolatedSettingsDoNotInheritParentContextSurfaces() {
        val parent = Settings(
            systemPrompt = "parent prompt",
            contextMessageSize = 20,
            presetMessages = listOf(UIMessage.user("previous user message")),
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    name = "secret rewrite",
                    findRegex = "secret",
                    replaceString = "redacted",
                )
            ),
            enabledMcpServerIds = setOf(Uuid.random()),
            enabledModeInjectionIds = setOf(Uuid.random()),
            enabledLorebookIds = setOf(Uuid.random()),
            enabledSkills = setOf("workspace-writer"),
            agentRuntime = AgentRuntimeSetting(
                enableCoreMemory = true,
                enableRecentChatsReference = true,
                enableTimeReminder = true,
            ),
        )
        val definition = SubAgentDefinition(
            id = "reader",
            name = "Reader",
            description = "Use when a narrow read-only task needs isolated work.",
            systemPrompt = "Boundaries: read only. Report output as findings with evidence.",
            toolAllowlist = setOf("file_read"),
        )

        val isolated = parent.toIsolatedSubAgentSettings().copy(systemPrompt = definition.systemPrompt)

        assertEquals(definition.systemPrompt, isolated.systemPrompt)
        assertTrue(isolated.streamOutput)
        assertEquals(0, isolated.contextMessageSize)
        assertFalse(isolated.agentRuntime.enableCoreMemory)
        assertFalse(isolated.agentRuntime.enableRecentChatsReference)
        assertTrue(isolated.presetMessages.isEmpty())
        assertTrue(isolated.quickMessages.isEmpty())
        assertTrue(isolated.regexes.isEmpty())
        assertTrue(isolated.mcpServers.isEmpty())
        assertTrue(isolated.enabledMcpServerIds.isEmpty())
        assertTrue(isolated.enabledModeInjectionIds.isEmpty())
        assertTrue(isolated.enabledLorebookIds.isEmpty())
        assertTrue(isolated.enabledSkills.isEmpty())
        assertFalse(isolated.agentRuntime.enableTimeReminder)
        assertEquals("{{ message }}", isolated.messageTemplate)
    }

    @Test
    fun isolatedSettingsDisableGlobalRuntimePromptSurfaces() {
        val settings = Settings(
            agentRuntime = AgentRuntimeSetting(
                enableCoreMemory = true,
                enableShortTermMemory = true,
                enableLongTermMemory = true,
                enableRecentChatsReference = true,
                enableTimeReminder = true,
                agentSoulMarkdown = "global behavior",
                generativeUi = GenerativeUiSetting(enabled = true),
                speculativeToolExecution = SpeculativeToolExecutionSetting(enabled = true),
            )
        )

        val isolated = settings.toIsolatedSubAgentSettings().agentRuntime

        assertFalse(isolated.enableCoreMemory)
        assertFalse(isolated.enableShortTermMemory)
        assertFalse(isolated.enableLongTermMemory)
        assertFalse(isolated.enableRecentChatsReference)
        assertFalse(isolated.enableTimeReminder)
        assertEquals("", isolated.agentSoulMarkdown)
        assertFalse(isolated.generativeUi.enabled)
        assertFalse(isolated.speculativeToolExecution.enabled)
    }
}
