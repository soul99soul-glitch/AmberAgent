package app.amber.core.di

import android.content.Context
import app.amber.agent.AppScope
import app.amber.core.agent.runtime.AgentEventPayloadCodec
import app.amber.core.agent.runtime.AgentEventStore
import app.amber.core.agent.runtime.AgentRegistry
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunner
import app.amber.core.agent.runtime.ToolLifecycleEvent
import app.amber.core.agent.runtime.adapter.LegacyRunScope
import app.amber.core.agent.runtime.impl.InMemoryAgentRegistry
import app.amber.core.agent.runtime.impl.InProcessAgentRunner
import app.amber.core.agent.runtime.impl.PersistingEventWriter
import app.amber.core.agent.store.RoomAgentEventStore
import app.amber.feature.chat.api.ChatEventPayload
import app.amber.feature.chat.api.ChatTurnInput
import app.amber.feature.chat.api.ChatTurnArtifact
import app.amber.feature.chat.api.ChatTurnDescriptor
import app.amber.feature.chat.api.ChatTurnInput as ChatTurnInputAlias
import app.amber.feature.chat.impl.ChatEventProjector
import app.amber.feature.chat.impl.ChatSessionResolverImpl
import app.amber.feature.chat.impl.ChatTurnAgent
import app.amber.feature.chat.impl.ProjectingEventWriter
import app.amber.feature.chat.impl.ProjectingRunScope
import app.amber.feature.deepread.api.DeepReadEventPayload
import app.amber.feature.deepread.api.DeepReadInput
import app.amber.feature.deepread.api.DeepReadArtifact
import app.amber.feature.deepread.api.DeepReadDescriptor
import app.amber.feature.deepread.impl.DeepReadAgentAdapter
import app.amber.feature.history.SessionAccessGrantStore
import app.amber.core.repository.CouncilRoomRepository
import app.amber.feature.modelcouncil.CouncilRoomManager
import app.amber.feature.modelcouncil.CouncilRoomStore
import app.amber.feature.modelcouncil.ExternalCliModelCouncilRunner
import app.amber.feature.modelcouncil.ModelCouncilManager
import app.amber.feature.modelcouncil.ProviderModelCouncilTextRunner
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.novel.workspace.NovelTurnAgent
import app.amber.feature.novel.workspace.NovelTurnArtifact
import app.amber.feature.novel.workspace.NovelTurnDescriptor
import app.amber.feature.novel.workspace.NovelTurnEventPayload
import app.amber.feature.novel.workspace.NovelTurnInput
import app.amber.feature.runtime.RunRecoveryService
import app.amber.feature.subagent.GenerationSubAgentRunner
import app.amber.feature.subagent.SubAgentManager
import app.amber.feature.subagent.SubAgentResult
import app.amber.feature.subagent.SubAgentTurnAgent
import app.amber.feature.subagent.SubAgentTurnDescriptor
import app.amber.feature.subagent.SubAgentTurnInput
import app.amber.feature.subagent.SubAgentTurnPayloads
import kotlinx.serialization.json.Json
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
                factory = { ChatTurnAgent(get(), get(), get<app.amber.core.service.ChatService>()) },
            )
            register(
                descriptor = DeepReadDescriptor.value,
                inputClass = DeepReadInput::class,
                inputSerializer = DeepReadInput.serializer(),
                artifactSerializer = DeepReadArtifact.serializer(),
                factory = { DeepReadAgentAdapter(get()) },
            )
            register(
                descriptor = SubAgentTurnDescriptor.value,
                inputClass = SubAgentTurnInput::class,
                inputSerializer = SubAgentTurnInput.serializer(),
                artifactSerializer = SubAgentResult.serializer(),
                factory = {
                    SubAgentTurnAgent(
                        runner = get<app.amber.feature.subagent.SubAgentRunner>(),
                        payloads = get(),
                        // Step 3-5: dead turns (cancel/interrupt/timeout/
                        // failure) classify their orphaned STARTED ledger
                        // effects — thread-graph turns have no run_terminal
                        // row, so cold-start recovery never visits them.
                        reconcileStartedEffects = { runId ->
                            get<RunRecoveryService>().reconcileStartedEffects(runId)
                            Unit
                        },
                    )
                },
            )
            register(
                descriptor = NovelTurnDescriptor.value,
                inputClass = NovelTurnInput::class,
                inputSerializer = NovelTurnInput.serializer(),
                artifactSerializer = NovelTurnArtifact.serializer(),
                factory = { NovelTurnAgent(get()) },
            )
        }
    }

    single { RoomAgentEventStore(get()) }
    single<app.amber.core.agent.runtime.AgentEventStore> { get<RoomAgentEventStore>() }

    // Step 3: codecs for every payload type the persisting writer may commit.
    // Keyed by payload-class qualified name (= the stored payloadType column);
    // an unregistered Final is dropped with a warning instead of crashing a run.
    single<Map<String, AgentEventPayloadCodec<*>>> {
        mapOf(
            ToolLifecycleEvent.Prepared::class.qualifiedName!! to
                AgentEventPayloadCodec(ToolLifecycleEvent.TYPE_PREPARED, ToolLifecycleEvent.Prepared.serializer()),
            ToolLifecycleEvent.Started::class.qualifiedName!! to
                AgentEventPayloadCodec(ToolLifecycleEvent.TYPE_STARTED, ToolLifecycleEvent.Started.serializer()),
            ToolLifecycleEvent.Finished::class.qualifiedName!! to
                AgentEventPayloadCodec(ToolLifecycleEvent.TYPE_FINISHED, ToolLifecycleEvent.Finished.serializer()),
            DeepReadEventPayload.SectionStarted::class.qualifiedName!! to
                AgentEventPayloadCodec("SectionStarted", DeepReadEventPayload.SectionStarted.serializer()),
            DeepReadEventPayload.SectionCompleted::class.qualifiedName!! to
                AgentEventPayloadCodec("SectionCompleted", DeepReadEventPayload.SectionCompleted.serializer()),
            DeepReadEventPayload.VerificationCompleted::class.qualifiedName!! to
                AgentEventPayloadCodec("VerificationCompleted", DeepReadEventPayload.VerificationCompleted.serializer()),
            DeepReadEventPayload.GenerationPhaseChanged::class.qualifiedName!! to
                AgentEventPayloadCodec("GenerationPhaseChanged", DeepReadEventPayload.GenerationPhaseChanged.serializer()),
            NovelTurnEventPayload.ToolActivity::class.qualifiedName!! to
                AgentEventPayloadCodec(NovelTurnEventPayload.TYPE_TOOL_ACTIVITY, NovelTurnEventPayload.ToolActivity.serializer()),
            NovelTurnEventPayload.TurnCompleted::class.qualifiedName!! to
                AgentEventPayloadCodec(NovelTurnEventPayload.TYPE_TURN_COMPLETED, NovelTurnEventPayload.TurnCompleted.serializer()),
            NovelTurnEventPayload.TurnFailed::class.qualifiedName!! to
                AgentEventPayloadCodec(NovelTurnEventPayload.TYPE_TURN_FAILED, NovelTurnEventPayload.TurnFailed.serializer()),
            // Step 5: request snapshots persist through the generic Final path
            // — this codec map backs every run kind's PersistingEventWriter
            // (DeepRead / SubAgent / Novel scopes, and chat's persisting
            // fallback); chat's ProjectingEventWriter handles the payload
            // directly. Coverage truth: EMISSION is gated by the kernel's
            // durable path (runId + onTerminal + ledger + flags), not by this
            // registration. Today that means chat turns and thread-graph
            // SubAgent turns emit snapshots; DeepRead / Novel thread their run
            // scope's writer + runId but do not arm onTerminal yet, so their
            // rounds stay snapshot-free until their durable path is on.
            ChatEventPayload.RequestSnapshot::class.qualifiedName!! to
                AgentEventPayloadCodec(ChatEventPayload.RequestSnapshot.TYPE, ChatEventPayload.RequestSnapshot.serializer()),
        )
    }

    single { ChatEventProjector(get<RoomAgentEventStore>(), get(), get(), get()) }

    single<AgentRunner> {
        // Resolve projector lazily inside runScopeFactory: ChatEventProjector
        // depends on ConversationAccess (= ChatService) which itself depends on
        // AgentRunner. Eager resolution at AgentRunner construction triggers a
        // ChatService → AgentRunner → ChatEventProjector → ChatService cycle.
        InProcessAgentRunner(
            registry = get(),
            eventStore = get<RoomAgentEventStore>(),
            runScopeFactory = { runId, input ->
                // Step 3: every registered run kind gets a persisting event
                // writer — the event stream is the unified run truth, not a
                // chat-only channel. Chat keeps its projecting writer for its
                // domain payloads and delegates protocol-level finals (tool
                // lifecycle) to the same generic persistence path.
                val codecs = get<Map<String, AgentEventPayloadCodec<*>>>()
                fun persistingWriter(descriptorId: String, parentRunId: AgentRunId? = null) =
                    PersistingEventWriter(
                        runId = runId,
                        parentRunId = parentRunId,
                        agentDescriptorId = descriptorId,
                        store = get<RoomAgentEventStore>(),
                        json = get<Json>(),
                        codecs = codecs,
                    )
                when (input) {
                    is ChatTurnInput -> {
                        val projector: ChatEventProjector = get()
                        val conversationUuid = kotlin.uuid.Uuid.parse(input.conversationId.value)
                        val writer = ProjectingEventWriter(
                            runId,
                            conversationUuid,
                            projector,
                            fallback = persistingWriter(ChatTurnDescriptor.ID.value),
                        )
                        ProjectingRunScope(
                            runId = runId,
                            conversationId = input.conversationId,
                            messageNodeId = input.messageNodeId,
                            events = writer,
                        )
                    }
                    is DeepReadInput -> LegacyRunScope(
                        runId = runId,
                        events = persistingWriter(DeepReadDescriptor.ID.value),
                    )
                    is SubAgentTurnInput -> LegacyRunScope(
                        runId = runId,
                        parentRunId = input.parentRunId?.let(::AgentRunId),
                        events = persistingWriter(
                            SubAgentTurnDescriptor.ID.value,
                            parentRunId = input.parentRunId?.let(::AgentRunId),
                        ),
                    )
                    is NovelTurnInput -> LegacyRunScope(
                        runId = runId,
                        events = persistingWriter(NovelTurnDescriptor.ID.value),
                    )
                    else -> LegacyRunScope(runId = runId)
                }
            },
        )
    }

    single { ChatSessionResolverImpl(get(), get(), get(), get()) }

    single<app.amber.feature.chat.impl.ChatSessionResolver> { get<ChatSessionResolverImpl>() }

    single {
        GenerationSubAgentRunner(
            kernel = get(),
        )
    }
    single<app.amber.feature.subagent.SubAgentRunner> { get<GenerationSubAgentRunner>() }

    single { SubAgentTurnPayloads() }

    single {
        SubAgentManager(
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            agentRunner = get(),
            turnPayloads = get(),
            // P4-02: persistent thread graph (thread_graph_v2 flag gates usage).
            threadGraphStore = get(),
            capabilityFlags = get(),
        )
    }

    single { ProviderModelCouncilTextRunner(get()) }
    single<app.amber.feature.modelcouncil.ModelCouncilTextRunner> { get<ProviderModelCouncilTextRunner>() }

    single { ExternalCliModelCouncilRunner(get(), get<Context>(), get()) }
    single<app.amber.feature.modelcouncil.ModelCouncilExternalCliRunner> {
        get<ExternalCliModelCouncilRunner>()
    }

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

    // ── Council Room (full-featured host-led room; parallel to legacy batch) ──
    single<CouncilRoomStore> {
        CouncilRoomRepository(
            conversationDao = get(),
            appScope = get<AppScope>(),
        )
    }
    single<app.amber.feature.modelcouncil.CouncilRoomTaskReporter> {
        app.amber.feature.modelcouncil.AgentTaskStoreReporter(get())
    }
    single<app.amber.feature.modelcouncil.CouncilHostToolProvider> {
        app.amber.feature.modelcouncil.AppCouncilHostToolProvider(
            providerCatalog = get(),
            toolDispatcher = get(),
            // Step 6: v1 default codifies no new restriction. The real reason
            // it must stay permissive here is structural, not the tool
            // allowlist: this provider is a DI singleton constructed at app
            // start, so no per-run policy exists to capture, and scrape_web's
            // outbound fetches inside a chat-run host turn are not constrained
            // by allowedDomains. Tightening requires a session-scoped council
            // host.
            executionPolicy = app.amber.feature.runtime.ExecutionPolicy.permissive(),
        )
    }
    single {
        CouncilRoomManager(
            get(),
            get<SettingsAggregator>().settingsFlow,
            get(),
            get<ProviderModelCouncilTextRunner>(),
            get(),
            get(),
            get<app.amber.feature.modelcouncil.CouncilRoomTaskReporter>(),
            get<app.amber.feature.modelcouncil.CouncilHostToolProvider>(),
        )
    }
}
