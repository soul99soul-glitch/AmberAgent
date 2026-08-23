package app.amber.core.agent.runtime.impl

import app.amber.core.agent.runtime.Agent
import app.amber.core.agent.runtime.AgentArtifact
import app.amber.core.agent.runtime.AgentDescriptor
import app.amber.core.agent.runtime.AgentDescriptorId
import app.amber.core.agent.runtime.AgentInput
import app.amber.core.agent.runtime.AgentRegistry
import app.amber.core.agent.runtime.RegisteredAgent
import kotlinx.serialization.KSerializer
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

class InMemoryAgentRegistry : AgentRegistry {
    private val agents = ConcurrentHashMap<AgentDescriptorId, RegisteredAgent<*, *>>()

    override fun <I : AgentInput, A : AgentArtifact> register(
        descriptor: AgentDescriptor,
        inputClass: KClass<I>,
        inputSerializer: KSerializer<I>,
        artifactSerializer: KSerializer<A>,
        factory: () -> Agent<I, A>,
    ) {
        agents[descriptor.id] = RegisteredAgent(
            descriptor = descriptor,
            inputClass = inputClass,
            inputSerializer = inputSerializer,
            artifactSerializer = artifactSerializer,
            factory = factory,
        )
    }

    override fun resolve(id: AgentDescriptorId): RegisteredAgent<*, *>? = agents[id]
}
