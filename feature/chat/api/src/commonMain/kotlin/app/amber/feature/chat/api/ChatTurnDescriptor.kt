package app.amber.feature.chat.api

import app.amber.core.agent.runtime.AgentCapability
import app.amber.core.agent.runtime.AgentDescriptor
import app.amber.core.agent.runtime.AgentDescriptorId

object ChatTurnDescriptor {
    val ID = AgentDescriptorId("chat_turn")

    val value = AgentDescriptor(
        id = ID,
        version = "1.0.0",
        displayName = "Chat Turn",
        capabilities = setOf(
            AgentCapability.CHAT_TURN,
            AgentCapability.TOOL_USE,
            AgentCapability.SUB_AGENT,
        ),
    )
}
