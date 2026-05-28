package app.amber.core.di

import app.amber.core.service.ChatService
import app.amber.core.service.PendingMessageStore
import app.amber.core.service.UserInputPreprocessor
import app.amber.core.service.orchestrator.BranchMessageOrchestrator
import app.amber.core.service.orchestrator.RegenerateMessageOrchestrator
import app.amber.core.service.orchestrator.SendMessageOrchestrator
import app.amber.core.web.WebServerManager
import org.koin.dsl.module

/**
 * Chat-domain Koin module — DI for ChatService, its on-disk store, its three
 * Orchestrator entry points, and the embedded web server that exposes the
 * conversation surface over HTTP.
 *
 * Extracted from `AppModule` in M1.5 (per blueprint §E "5 module split"). The
 * remaining domains (aiModule / agentModule / webMountModule) are deferred as
 * follow-up — same pattern, larger scope.
 */
val chatModule = module {
    single {
        PendingMessageStore(
            context = get(),
            json = get(),
        )
    }

    single { UserInputPreprocessor(settingsStore = get()) }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryRepository = get(),
            generationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            json = get(),
            localTools = get(),
            mcpManager = get(),
            activityStore = get(),
            liveStatusNotifier = get(),
            terminalRuntime = get(),
            screenCaptureManager = get(),
            filesManager = get(),
            skillManager = get(),
            workspaceManager = get(),
            contextEngine = get(),
            subAgentManager = get(),
            modelCouncilManager = get(),
            agentTaskScheduler = get(),
            sessionAccessGrantStore = get(),
            memoryExtractor = get(),
            pendingMessageStore = get(),
            userInputPreprocessor = get(),
            agentRunner = get(),
        )
    }

    single {
        SendMessageOrchestrator(
            chatService = get(),
            analytics = get(),
            eventBus = get(),
        )
    }

    single {
        RegenerateMessageOrchestrator(
            chatService = get(),
            analytics = get(),
        )
    }

    single {
        BranchMessageOrchestrator(
            chatService = get(),
        )
    }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            settingsStore = get(),
            filesManager = get(),
        )
    }
}
