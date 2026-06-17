package app.amber.core.ai.mcp

import app.amber.ai.ui.UIMessagePart
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

interface McpManagerInterface {
    suspend fun callTool(toolName: String, args: JsonObject): List<UIMessagePart>

    fun getAllAvailableTools(): List<McpTool>

    suspend fun syncAll()

    fun getStatus(config: McpServerConfig): Flow<McpStatus>
}

interface McpClientInterface {
    suspend fun connect(config: McpServerConfig): Boolean

    suspend fun listTools(): List<McpTool>

    suspend fun callTool(name: String, args: JsonObject): List<UIMessagePart>

    fun disconnect()
}
