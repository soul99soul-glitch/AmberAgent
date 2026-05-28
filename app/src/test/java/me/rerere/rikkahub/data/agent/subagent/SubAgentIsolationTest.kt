package app.amber.feature.subagent

import me.rerere.ai.ui.UIMessage
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.GenerativeUiSetting
import app.amber.core.settings.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantRegex
import app.amber.core.settings.SpeculativeToolExecutionSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubAgentIsolationTest {
    @Test
    fun isolatedAssistantDoesNotInheritParentContextSurfaces() {
        val parent = Assistant(
            name = "Parent",
            systemPrompt = "parent prompt",
            contextMessageSize = 20,
            enableMemory = true,
            useGlobalMemory = true,
            enableRecentChatsReference = true,
            presetMessages = listOf(UIMessage.user("previous user message")),
            quickMessageIds = setOf(Uuid.random()),
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    name = "secret rewrite",
                    findRegex = "secret",
                    replaceString = "redacted",
                )
            ),
            mcpServers = setOf(Uuid.random()),
            modeInjectionIds = setOf(Uuid.random()),
            lorebookIds = setOf(Uuid.random()),
            enabledSkills = setOf("workspace-writer"),
            enableTimeReminder = true,
        )
        val definition = SubAgentDefinition(
            id = "reader",
            name = "Reader",
            description = "Use when a narrow read-only task needs isolated work.",
            systemPrompt = "Boundaries: read only. Report output as findings with evidence.",
            toolAllowlist = setOf("file_read"),
        )

        val isolated = parent.toIsolatedSubAgentAssistant(definition)

        assertEquals("Reader", isolated.name)
        assertEquals(definition.systemPrompt, isolated.systemPrompt)
        assertTrue(isolated.streamOutput)
        assertEquals(0, isolated.contextMessageSize)
        assertFalse(isolated.enableMemory)
        assertFalse(isolated.useGlobalMemory)
        assertFalse(isolated.enableRecentChatsReference)
        assertTrue(isolated.presetMessages.isEmpty())
        assertTrue(isolated.quickMessageIds.isEmpty())
        assertTrue(isolated.regexes.isEmpty())
        assertTrue(isolated.mcpServers.isEmpty())
        assertTrue(isolated.localTools.isEmpty())
        assertTrue(isolated.modeInjectionIds.isEmpty())
        assertTrue(isolated.lorebookIds.isEmpty())
        assertTrue(isolated.enabledSkills.isEmpty())
        assertFalse(isolated.enableTimeReminder)
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
