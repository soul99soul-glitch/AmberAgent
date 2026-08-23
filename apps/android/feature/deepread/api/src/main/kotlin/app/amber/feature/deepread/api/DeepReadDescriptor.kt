package app.amber.feature.deepread.api

import app.amber.core.agent.runtime.AgentCapability
import app.amber.core.agent.runtime.AgentDescriptor
import app.amber.core.agent.runtime.AgentDescriptorId

object DeepReadDescriptor {
    val ID = AgentDescriptorId("deep_read")

    val value = AgentDescriptor(
        id = ID,
        version = "1.0.0",
        displayName = "Deep Read",
        capabilities = setOf(AgentCapability.DEEP_READ, AgentCapability.TOOL_USE),
    )
}
