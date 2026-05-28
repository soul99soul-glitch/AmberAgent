package me.rerere.rikkahub.di

import android.content.Context
import app.amber.core.agent.runtime.AgentRegistry
import app.amber.core.agent.runtime.AgentRunner
import app.amber.core.agent.runtime.impl.InMemoryAgentRegistry
import app.amber.core.agent.runtime.impl.InProcessAgentRunner
import app.amber.core.agent.store.RoomAgentEventStore
import app.amber.feature.chat.api.ChatTurnInput
import app.amber.feature.chat.api.ChatTurnArtifact
import app.amber.feature.chat.api.ChatTurnDescriptor
import app.amber.feature.chat.api.ChatTurnInput as ChatTurnInputAlias
import app.amber.feature.chat.impl.ChatEventProjector
import app.amber.feature.chat.impl.ChatSessionResolverImpl
import app.amber.feature.chat.impl.ChatTurnAgent
import app.amber.feature.chat.impl.ProjectingEventWriter
import app.amber.feature.chat.impl.ProjectingRunScope
import app.amber.feature.deepread.api.DeepReadInput
import app.amber.feature.deepread.api.DeepReadArtifact
import app.amber.feature.deepread.api.DeepReadDescriptor
import app.amber.feature.deepread.impl.DeepReadAgentAdapter
import app.amber.feature.history.SessionAccessGrantStore
import app.amber.feature.modelcouncil.ExternalCliModelCouncilRunner
import app.amber.feature.modelcouncil.ModelCouncilManager
import app.amber.feature.modelcouncil.ProviderModelCouncilTextRunner
import app.amber.feature.subagent.GenerationSubAgentRunner
import app.amber.feature.subagent.SubAgentManager
import org.koin.dsl.module

/**
 * Agent runtime Koin module — sub-agent dispatch + model council orchestration
 * + session access grant book-keeping.
 *
 * Extracted from AppModule in M1.5 continuation. Focused on the
 * "agent-runs-while-chat-runs" surface: SubAgentManager + ModelCouncilManager
 * are both invoked by ChatService during a single user turn to delegate
 * sub-tasks to alternate models / external CLIs.
 */
val agentRuntimeModule = module {
    single { SessionAccessGrantStore() }

    // Agent Kernel
    single<AgentRegistry> {
        InMemoryAgentRegistry().apply {
            register(
                descriptor = ChatTurnDescriptor.value,
                inputClass = ChatTurnInput::class,
                inputSerializer = ChatTurnInput.serializer(),
                artifactSerializer = ChatTurnArtifact.serializer(),
                factory = { ChatTurnAgent(get(), get(), get<me.rerere.rikkahub.service.ChatService>()) },
            )
            register(
                descriptor = DeepReadDescriptor.value,
                inputClass = DeepReadInput::class,
                inputSerializer = DeepReadInput.serializer(),
                artifactSerializer = DeepReadArtifact.serializer(),
                factory = { DeepReadAgentAdapter(get()) },
            )
        }
    }

    single { RoomAgentEventStore(get()) }

    single { ChatEventProjector(get<RoomAgentEventStore>(), get(), get(), get()) }

    single<AgentRunner> {
        val projector: ChatEventProjector = get()
        InProcessAgentRunner(
            registry = get(),
            eventStore = get<RoomAgentEventStore>(),
            runScopeFactory = { runId, input ->
                if (input is ChatTurnInput) {
                    val conversationUuid = kotlin.uuid.Uuid.parse(input.conversationId.value)
                    val writer = ProjectingEventWriter(runId, conversationUuid, projector)
                    ProjectingRunScope(
                        runId = runId,
                        conversationId = input.conversationId,
                        messageNodeId = input.messageNodeId,
                        events = writer,
                    )
                } else {
                    app.amber.core.agent.runtime.adapter.LegacyRunScope(runId = runId)
                }
            },
        )
    }

    single { ChatSessionResolverImpl(get(), get(), get(), get(), get()) }

    single<app.amber.feature.chat.impl.ChatSessionResolver> { get<ChatSessionResolverImpl>() }

    single { GenerationSubAgentRunner(get()) }

    single {
        SubAgentManager(
            get(),
            get(),
            get(),
            get(),
            get<GenerationSubAgentRunner>(),
            get(),
            get(),
        )
    }

    single { ProviderModelCouncilTextRunner(get()) }

    single { ExternalCliModelCouncilRunner(get(), get<Context>(), get()) }

    single {
        ModelCouncilManager(
            get(),
            get(),
            get(),
            get(),
            get<ProviderModelCouncilTextRunner>(),
            get(),
            get(),
        )
    }
}
