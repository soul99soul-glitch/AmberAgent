package me.rerere.rikkahub.di

import android.content.Context
import app.amber.feature.history.SessionAccessGrantStore
import me.rerere.rikkahub.data.agent.modelcouncil.ExternalCliModelCouncilRunner
import me.rerere.rikkahub.data.agent.modelcouncil.ModelCouncilManager
import me.rerere.rikkahub.data.agent.modelcouncil.ProviderModelCouncilTextRunner
import me.rerere.rikkahub.data.agent.subagent.GenerationSubAgentRunner
import me.rerere.rikkahub.data.agent.subagent.SubAgentManager
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
