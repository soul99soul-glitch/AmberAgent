package app.amber.core.di

import app.amber.core.ai.ChatGenerationRoundEngine
import app.amber.core.ai.DefaultRunKernel
import app.amber.core.ai.GenerationRoundEngine
import app.amber.core.ai.RunKernel
import app.amber.core.ai.transformers.TemplateTransformer
import org.koin.dsl.module

/** Provider rounds and the kernel that sequences them. */
val generationModule = module {
    single { TemplateTransformer() }

    single {
        ChatGenerationRoundEngine(
            context = get(),
            providerCatalog = get(),
            json = get(),
            memoryRecallStore = get(),
            conversationRepo = get(),
            aiLoggingManager = get(),
            conversationContextEngine = get(),
        )
    }
    single {
        DefaultRunKernel(
            context = get(),
            toolDispatcher = get(),
            roundEngine = get(),
            toolEffectLedger = get(),
            capabilityFlags = get(),
            capabilityPermissionStore = get(),
            autoApprovalGate = get(),
            approvalTriage = get(),
            completionCheck = get(),
            scriptJudgment = get(),
        )
    }
    single<GenerationRoundEngine> { get<ChatGenerationRoundEngine>() }
    single<RunKernel> { get<DefaultRunKernel>() }

}
