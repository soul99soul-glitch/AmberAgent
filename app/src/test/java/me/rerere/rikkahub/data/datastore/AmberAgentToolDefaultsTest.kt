package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmberAgentToolDefaultsTest {
    @Test
    fun defaultAmberAgentEnablesAllLocalToolGroups() {
        val amberAgent = DEFAULT_ASSISTANTS.first { it.id == DEFAULT_ASSISTANT_ID }

        assertTrue(
            amberAgent.localTools.containsAll(
                listOf(
                    LocalToolOption.JavascriptEngine,
                    LocalToolOption.TimeInfo,
                    LocalToolOption.Clipboard,
                    LocalToolOption.Tts,
                    LocalToolOption.AskUser,
                    LocalToolOption.WorkspaceFiles,
                    LocalToolOption.Terminal,
                    LocalToolOption.ScreenAutomation,
                    LocalToolOption.SystemAccess,
                    LocalToolOption.WebView,
                )
            )
        )
    }

    @Test
    fun agentRuntimeDefaultsKeepLongToolLoop() {
        val runtime = AgentRuntimeSetting()

        assertEquals(DEFAULT_AGENT_MAX_TOOL_LOOP_STEPS, runtime.maxToolLoopSteps)
        assertTrue(runtime.maxToolLoopSteps >= 256)
    }

    @Test
    fun currentAssistantPrefersApplicationLevelAmberAgent() {
        val legacyAssistantId = DEFAULT_ASSISTANTS.first { it.id != DEFAULT_ASSISTANT_ID }.id
        val settings = Settings(assistantId = legacyAssistantId)

        assertEquals(DEFAULT_ASSISTANT_ID, settings.getCurrentAssistant().id)
    }
}
