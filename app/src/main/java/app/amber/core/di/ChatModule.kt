package app.amber.core.di

import android.content.Context
import app.amber.core.recap.ConversationRecapGenerator
import app.amber.core.recap.ConversationRecapStore
import app.amber.core.service.ChatService
import app.amber.core.service.ChatStartSuggestionGenerator
import app.amber.core.service.ConversationAccess
import app.amber.core.service.PendingMessageStore
import app.amber.core.service.UserInputPreprocessor
import app.amber.core.service.orchestrator.BranchMessageOrchestrator
import app.amber.core.service.orchestrator.RegenerateMessageOrchestrator
import app.amber.core.service.orchestrator.SendMessageOrchestrator
import java.io.File
import org.koin.dsl.module

/**
 * Chat-domain Koin module — DI for ChatService, its on-disk store, and
 * Orchestrator entry points.
 *
 * Extracted from `AppModule` in M1.5 (per blueprint §E "5 module split"). The
 * remaining domains (aiModule / agentModule / webMountModule) are deferred as
 * follow-up — same pattern, larger scope.
 */
val chatModule = module {
    single { ChatStartSuggestionGenerator(get(), get(), get()) }

    single {
        PendingMessageStore(
            context = get(),
            json = get(),
        )
    }

    single { UserInputPreprocessor(settingsStore = get()) }

    single {
        ConversationRecapStore(
            rootDir = File(get<Context>().filesDir, ConversationRecapStore.DIRECTORY),
        )
    }

    single {
        ConversationRecapGenerator(
            context = get(),
            settingsStore = get(),
            providerCatalog = get(),
            conversationRepo = get(),
            store = get(),
        )
    }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryRepository = get(),
            templateTransformer = get(),
            providerCatalog = get(),
            googleProvider = get(),
            json = get(),
            localTools = get(),
            mcpManager = get(),
            activityStore = get(),
            liveStatusNotifier = get(),
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
            agentEventStore = get(),
            capabilityFlags = get(),
            toolEffectLedger = get(),
            runTerminalStore = get(),
            runRecovery = get(),
            coldStartRecoveryGate = get(),
            runOwnershipRegistry = get(),
            notificationApprovalTokens = get(),
            capabilityPermissionStore = get(),
            toolDispatcher = get(),
            recipeRegistry = get(),
            jsCellRuntime = get(),
            responsesResumeStore = get(),
            storedResponseGateway = get(),
            storedResponseStopCancel = get(),
            secretStore = get(),
            themePackageManager = get(),
            restoreWriteGate = get(),
            jevToolSemanticSearch = get(),
            recapGenerator = get(),
            recapStore = get(),
        )
    }
    single<ConversationAccess> { get<ChatService>() }

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

}
