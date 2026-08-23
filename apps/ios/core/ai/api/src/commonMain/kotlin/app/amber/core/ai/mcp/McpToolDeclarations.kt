package app.amber.core.ai.mcp

import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import kotlinx.serialization.json.buildJsonObject

fun mcpToolDeclarationName(serverName: String, toolName: String): String {
    val safeServer = serverName.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val safeTool = toolName.replace(Regex("[^A-Za-z0-9_-]"), "_")
    return "mcp__${safeServer}__${safeTool}"
}

fun createMcpToolDeclaration(serverName: String, tool: McpTool): Tool {
    return Tool(
        name = mcpToolDeclarationName(serverName, tool.name),
        description = tool.description ?: "Call MCP tool `${tool.name}` on server `$serverName`.",
        parameters = { tool.inputSchema ?: InputSchema.Obj(properties = buildJsonObject { }) },
        needsApproval = tool.needsApproval,
        execute = { emptyList() }
    )
}
