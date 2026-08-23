package app.amber.core.agent.runtime

import kotlin.reflect.KClass
import kotlinx.serialization.KSerializer

interface AgentRegistry {
    fun <I : AgentInput, A : AgentArtifact> register(
        descriptor: AgentDescriptor,
        inputClass: KClass<I>,
        inputSerializer: KSerializer<I>,
        artifactSerializer: KSerializer<A>,
        factory: () -> Agent<I, A>,
    )

    fun resolve(id: AgentDescriptorId): RegisteredAgent<*, *>?
}
