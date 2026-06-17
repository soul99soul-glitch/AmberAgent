package app.amber.core.ai.mcp

import app.amber.ai.ui.UIMessagePart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class McpInterfacesTest {
    @Test
    fun managerInterfaceExposesStatusToolsSyncAndToolCalls() {
        val manager = FakeMcpManager()
        val statusFlow: Flow<McpStatus> = manager.getStatus(manager.config)

        assertEquals(listOf(McpTool(name = "search")), manager.getAllAvailableTools())
        assertIs<Flow<McpStatus>>(statusFlow)
    }

    @Test
    fun clientInterfaceExposesConnectionLifecycle() {
        val client = FakeMcpClient()

        assertEquals(false, client.disconnected)
        client.disconnect()
        assertEquals(true, client.disconnected)
    }

    private class FakeMcpManager : McpManagerInterface {
        val config = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(name = "search", tools = listOf(McpTool(name = "search"))),
            url = "https://example.com/mcp"
        )

        override suspend fun callTool(toolName: String, args: JsonObject): List<UIMessagePart> {
            return listOf(UIMessagePart.Text("called $toolName"))
        }

        override fun getAllAvailableTools(): List<McpTool> = config.commonOptions.tools

        override suspend fun syncAll() = Unit

        override fun getStatus(config: McpServerConfig): Flow<McpStatus> = flowOf(McpStatus.Connected)
    }

    private class FakeMcpClient : McpClientInterface {
        var disconnected = false

        override suspend fun connect(config: McpServerConfig): Boolean = true

        override suspend fun listTools(): List<McpTool> = listOf(McpTool(name = "search"))

        override suspend fun callTool(name: String, args: JsonObject): List<UIMessagePart> {
            return listOf(UIMessagePart.Text("called $name"))
        }

        override fun disconnect() {
            disconnected = true
        }
    }
}
