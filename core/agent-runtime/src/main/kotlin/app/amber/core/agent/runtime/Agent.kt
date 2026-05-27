package app.amber.core.agent.runtime

import kotlin.reflect.KClass
import kotlinx.serialization.KSerializer

interface Agent<INPUT : AgentInput, ARTIFACT : AgentArtifact> {
    val descriptor: AgentDescriptor
    val handler: AgentHandler<INPUT, ARTIFACT>
}

fun interface AgentHandler<INPUT : AgentInput, ARTIFACT : AgentArtifact> {
    suspend fun handle(input: INPUT, scope: RunScope): ARTIFACT
}

interface AgentInput

interface AgentArtifact

data class AgentDescriptor(
    val id: AgentDescriptorId,
    val version: String,
    val displayName: String,
    val capabilities: Set<AgentCapability>,
)

enum class AgentCapability {
    CHAT_TURN,
    DEEP_READ,
    TOOL_USE,
    SUB_AGENT,
    BACKGROUND,
}

@JvmInline
value class AgentDescriptorId(val value: String)

@JvmInline
value class AgentRunId(val value: String) {
    companion object {
        fun new(): AgentRunId = AgentRunId(java.util.UUID.randomUUID().toString())
    }
}

@JvmInline
value class ConversationId(val value: String)

@JvmInline
value class MessageNodeId(val value: String)

@JvmInline
value class AssistantId(val value: String)

data class RegisteredAgent<I : AgentInput, A : AgentArtifact>(
    val descriptor: AgentDescriptor,
    val inputClass: KClass<I>,
    val inputSerializer: KSerializer<I>,
    val artifactSerializer: KSerializer<A>,
    val factory: () -> Agent<I, A>,
)
