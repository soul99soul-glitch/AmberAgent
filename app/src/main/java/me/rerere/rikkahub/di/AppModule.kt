package me.rerere.rikkahub.di

import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import com.google.firebase.remoteconfig.remoteConfig
import kotlinx.serialization.json.Json
import me.rerere.highlight.Highlighter
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.AILoggingManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.agent.AgentLiveStatusNotifier
import me.rerere.rikkahub.data.agent.AgentToolActivityStore
import me.rerere.rikkahub.data.agent.cron.AgentCronManager
import me.rerere.rikkahub.data.agent.icloud.ICloudDriveClient
import me.rerere.rikkahub.data.agent.icloud.ICloudDriveCookieProvider
import me.rerere.rikkahub.data.agent.icloud.ICloudDriveManager
import me.rerere.rikkahub.data.agent.modelcouncil.ModelCouncilManager
import me.rerere.rikkahub.data.agent.modelcouncil.ProviderModelCouncilTextRunner
import me.rerere.rikkahub.data.agent.office.FeishuOfficeEnhancementManager
import me.rerere.rikkahub.data.agent.subagent.GenerationSubAgentRunner
import me.rerere.rikkahub.data.agent.subagent.SubAgentManager
import me.rerere.rikkahub.data.agent.tools.AgentCronTools
import me.rerere.rikkahub.data.agent.tools.ExternalFileTools
import me.rerere.rikkahub.data.agent.tools.FeishuOfficeTools
import me.rerere.rikkahub.data.agent.system.AgentPermissionBroker
import me.rerere.rikkahub.data.agent.terminal.AlpineRuntimeInstaller
import me.rerere.rikkahub.data.agent.task.AgentTaskStore
import me.rerere.rikkahub.data.agent.task.AgentTaskScheduler
import me.rerere.rikkahub.data.agent.terminal.TerminalRuntime
import me.rerere.rikkahub.data.agent.tools.ICloudDriveTools
import me.rerere.rikkahub.data.agent.tools.ScreenAutomationTools
import me.rerere.rikkahub.data.agent.tools.SystemAccessTools
import me.rerere.rikkahub.data.agent.tools.TerminalTools
import me.rerere.rikkahub.data.agent.tools.WorkspaceArtifactTools
import me.rerere.rikkahub.data.agent.tools.WorkspaceTools
import me.rerere.rikkahub.data.agent.webview.WebViewOperationStore
import me.rerere.rikkahub.data.agent.workspace.WorkspaceManager
import me.rerere.rikkahub.data.automation.ScreenCaptureManager
import me.rerere.rikkahub.data.context.AgentCapabilitySnapshotBuilder
import me.rerere.rikkahub.data.context.ConversationContextEngine
import me.rerere.rikkahub.data.context.ConversationContextRepository
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        Highlighter(get())
    }

    single {
        AppEventBus()
    }

    single {
        WorkspaceManager(get())
    }

    single {
        WorkspaceTools(get(), get())
    }

    single {
        ICloudDriveCookieProvider()
    }

    single {
        ICloudDriveClient(get(), get())
    }

    single {
        ICloudDriveManager(get(), get(), get())
    }

    single {
        ICloudDriveTools(get(), get())
    }

    single {
        FeishuOfficeEnhancementManager(get(), get(), get(), get(), get())
    }

    single {
        FeishuOfficeTools(get(), get(), get())
    }

    single {
        ConversationContextRepository(get(), get(), get())
    }

    single {
        AgentCapabilitySnapshotBuilder(get())
    }

    single {
        ConversationContextEngine(get(), get(), get(), get(), get())
    }

    single {
        GenerationSubAgentRunner(get())
    }

    single {
        SubAgentManager(get(), get(), get(), get(), get<GenerationSubAgentRunner>(), get())
    }

    single {
        ProviderModelCouncilTextRunner(get())
    }

    single {
        ModelCouncilManager(get(), get(), get(), get(), get<ProviderModelCouncilTextRunner>(), get())
    }

    single {
        WorkspaceArtifactTools(get(), get(), get())
    }

    single {
        ExternalFileTools(get(), get(), get())
    }

    single {
        AgentToolActivityStore()
    }

    single {
        AgentTaskStore(get(), get())
    }

    single {
        AgentTaskScheduler(get())
    }

    single {
        WebViewOperationStore()
    }

    single {
        AgentLiveStatusNotifier(get())
    }

    single {
        AlpineRuntimeInstaller(get())
    }

    single {
        TerminalRuntime(get(), get(), get(), get(), get(), get(), get())
    }

    single {
        TerminalTools(get(), get())
    }

    single {
        ScreenCaptureManager(get())
    }

    single {
        ScreenAutomationTools(get(), get(), get())
    }

    single {
        AgentPermissionBroker(get())
    }

    single {
        SystemAccessTools(get(), get(), get(), get())
    }

    single {
        AgentCronManager(get(), get(), get())
    }

    single {
        AgentCronTools(get())
    }

    single {
        LocalTools(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get())
    }

    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    single {
        Firebase.crashlytics
    }

    single {
        Firebase.remoteConfig
    }

    single {
        Firebase.analytics
    }

    single {
        AILoggingManager()
    }

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
        )
    }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            settingsStore = get(),
            filesManager = get()
        )
    }
}
