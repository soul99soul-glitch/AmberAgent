package app.amber.core.di

import android.content.Context
import app.amber.core.recap.ConversationRecapGenerator
import app.amber.core.recap.ConversationRecapStore
import app.amber.core.service.ChatService
import app.amber.core.service.ChatRunPresenter
import app.amber.core.service.ChatRunCoordinator
import app.amber.core.service.ChatApprovalActions
import app.amber.core.service.ChatRunHooksFactory
import app.amber.core.service.AiAuxiliaryGenerator
import app.amber.core.service.tools.RunToolCatalogFactory
import app.amber.core.service.tools.MemoryRunToolFactory
import app.amber.core.service.ChatStartSuggestionGenerator
import app.amber.core.service.ConversationAccess
import app.amber.core.service.ConversationStore
import app.amber.core.service.ConversationEditor
import app.amber.core.service.ChatErrorStore
import app.amber.core.service.LocalAsyncTaskCoordinator
import app.amber.core.service.PendingMessageQueue
import app.amber.core.infra.AppScope
import app.amber.core.service.PendingMessageStore
import app.amber.core.service.UserInputPreprocessor
import java.io.File
import org.koin.dsl.module

/**
 * Conversation data, input commands, and chat run collaborators.
 * Generation and tool execution bindings live in their dedicated modules.
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
        ChatRunPresenter(
            context = get(),
            appScope = get(),
            activityStore = get(),
            liveStatusNotifier = get(),
            agentTaskScheduler = get(),
        )
    }

    single { ChatErrorStore() }
    single {
        PendingMessageQueue(
            appScope = get<AppScope>(),
            pendingMessageStore = get(),
            restoreWriteGate = get(),
        )
    }
    single {
        ConversationStore(
            conversationRepo = get(),
            settingsStore = get(),
            appScope = get<AppScope>(),
            filesManager = get(),
            pendingMessageQueue = get(),
            errorStore = get(),
            restoreWriteGate = get(),
        )
    }
    single {
        ConversationEditor(
            context = get(),
            conversationStore = get(),
            contextRepository = get(),
            userInputPreprocessor = get(),
            filesManager = get(),
            restoreWriteGate = get(),
        )
    }

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
        AiAuxiliaryGenerator(
            context = get(),
            settingsStore = get(),
            providerCatalog = get(),
            conversationStore = get(),
            restoreWriteGate = get(),
        )
    }
    single {
        ChatApprovalActions(
            context = get(),
            json = get(),
            conversationStore = get(),
            toolEffectLedger = get(),
            runTerminalStore = get(),
            runRecovery = get(),
            capabilityFlags = get(),
            capabilityPermissionStore = get(),
            restoreWriteGate = get(),
        )
    }
    single {
        ChatRunHooksFactory(
            context = get(),
            appScope = get(),
            settingsStore = get(),
            conversationStore = get(),
            pendingMessageQueue = get(),
            errorStore = get(),
            runPresenter = get(),
            approvalActions = get(),
            localTools = get(),
            subAgentManager = get(),
            screenCaptureManager = get(),
            aiAuxiliaryGenerator = get(),
            memoryExtractor = get(),
            agentEventStore = get(),
            runTerminalStore = get(),
            runRecovery = get(),
            runOwnershipRegistry = get(),
            toolEffectLedger = get(),
            capabilityFlags = get(),
            responsesResumeStore = get(),
            storedResponseStopCancel = get(),
            restoreWriteGate = get(),
            recapGenerator = get(),
            recapStore = get(),
        )
    }

    single {
        MemoryRunToolFactory(
            context = get(),
            json = get(),
            memoryRepository = get(),
            capabilityPermissionStore = get(),
        )
    }
    single {
        RunToolCatalogFactory(
            context = get(),
            settingsStore = get(),
            conversationRepo = get(),
            json = get(),
            localTools = get(),
            mcpManager = get(),
            activityStore = get(),
            skillManager = get(),
            workspaceManager = get(),
            contextEngine = get(),
            subAgentManager = get(),
            modelCouncilManager = get(),
            agentTaskScheduler = get(),
            sessionAccessGrantStore = get(),
            memoryToolFactory = get(),
            providerCatalog = get(),
            googleProvider = get(),
            openAICodexAuthStore = get(),
            grokAuthStore = get(),
            antigravityAuthStore = get(),
            capabilityFlags = get(),
            capabilityPermissionStore = get(),
            toolEffectLedger = get(),
            toolDispatcher = get(),
            recipeRegistry = get(),
            jsCellRuntime = get(),
            secretStore = get(),
            themePackageManager = get(),
            jevToolSemanticSearch = get(),
            agentSoulStore = get(),
        )
    }

    single {
        ChatRunCoordinator(
            context = get(),
            appScope = get(),
            settingsStore = get(),
            conversationStore = get(),
            pendingMessageQueue = get(),
            errorStore = get(),
            runPresenter = get(),
            approvalActions = get(),
            conversationEditor = get(),
            contextEngine = get(),
            screenCaptureManager = get(),
            userInputPreprocessor = get(),
            mcpManager = get(),
            agentRunner = get(),
            agentEventStore = get(),
            capabilityFlags = get(),
            toolEffectLedger = get(),
            runTerminalStore = get(),
            runRecovery = get(),
            coldStartRecoveryGate = get(),
            runOwnershipRegistry = get(),
            notificationApprovalTokens = get(),
            responsesResumeStore = get(),
            storedResponseGateway = get(),
            storedResponseStopCancel = get(),
            restoreWriteGate = get(),
        )
    }

    single {
        LocalAsyncTaskCoordinator(
            context = get(),
            appScope = get<AppScope>(),
            settingsStore = get(),
            conversationStore = get(),
            userInputPreprocessor = get(),
            runPresenter = get(),
            agentRunner = get(),
            agentEventStore = get(),
            runTerminalStore = get(),
            runOwnershipRegistry = get(),
            capabilityFlags = get(),
            toolEffectLedger = get(),
            runRecovery = get(),
            responsesResumeStore = get(),
            storedResponseGateway = get(),
            storedResponseStopCancel = get(),
        ).also { coordinator ->
            get<ChatRunHooksFactory>().bindLocalAsyncTaskCoordinator(coordinator)
        }
    }

    single {
        ChatService(
            context = get(),
            runCoordinator = get(),
            settingsStore = get(),
            toolCatalogFactory = get(),
            runPresenter = get(),
            approvalActions = get(),
            hooksFactory = get(),
            aiAuxiliaryGenerator = get(),
            mcpManager = get(),
            conversationEditor = get(),
            contextEngine = get(),
            conversationStore = get(),
            pendingMessageQueue = get(),
            errorStore = get(),
            restoreWriteGate = get(),
            localAsyncTaskCoordinator = get(),
        )
    }
    single<ConversationAccess> { get<ConversationStore>() }
}
