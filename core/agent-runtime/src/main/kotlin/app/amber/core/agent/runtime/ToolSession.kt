package app.amber.core.agent.runtime

import kotlinx.serialization.json.JsonElement

interface ToolSession {
    fun listAvailable(): List<ToolDescriptor>
    suspend fun invoke(toolId: ToolId, args: JsonElement): ToolCallResult
}

data class ToolDescriptor(
    val id: ToolId,
    val version: ToolVersion,
    val source: ToolSource,
    val schema: JsonElement,
    val isStable: Boolean,
    val permission: ToolPermission,
)

sealed interface ToolSource {
    data object Builtin : ToolSource
    data class McpServer(val serverId: String) : ToolSource
    data class Custom(val ownerId: String) : ToolSource
}

enum class ToolPermission {
    AUTO,
    REQUIRE_APPROVAL,
    DESTRUCTIVE,
}

@JvmInline
value class ToolId(val value: String)

@JvmInline
value class ToolVersion(val value: String)

data class ToolCallResult(
    val output: JsonElement,
    val isError: Boolean = false,
    val durationMs: Long = 0,
)
