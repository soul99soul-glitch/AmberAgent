package app.amber.core.settings

import app.amber.core.model.LocalToolOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmberAgentToolDefaultsTest {
    @Test
    fun defaultAmberAgentEnablesAllLocalToolGroups() {
        assertTrue(
            AMBER_AGENT_LOCAL_TOOLS.containsAll(
                listOf(
                    LocalToolOption.JavascriptEngine,
                    LocalToolOption.TimeInfo,
                    LocalToolOption.Clipboard,
                    LocalToolOption.AskUser,
                    LocalToolOption.WorkspaceFiles,
                    LocalToolOption.Terminal,
                    LocalToolOption.ScreenAutomation,
                    LocalToolOption.SystemAccess,
                    LocalToolOption.WebView,
                    LocalToolOption.ICloudDrive,
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

}
