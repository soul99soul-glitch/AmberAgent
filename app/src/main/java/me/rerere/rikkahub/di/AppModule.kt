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
import me.rerere.rikkahub.data.agent.history.SessionAccessGrantStore
import me.rerere.rikkahub.data.agent.icloud.ICloudDriveClient
import me.rerere.rikkahub.data.agent.icloud.ICloudDriveCookieProvider
import me.rerere.rikkahub.data.agent.icloud.ICloudDriveManager
import me.rerere.rikkahub.data.agent.live.LiveModeManager
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
import me.rerere.rikkahub.data.memory.dream.MemoryDreamApplier
import me.rerere.rikkahub.data.memory.dream.MemoryDreamNotifier
import me.rerere.rikkahub.data.memory.dream.MemoryDreamPlanStore
import me.rerere.rikkahub.data.memory.dream.MemoryDreamPlanner
import me.rerere.rikkahub.data.memory.dream.MemoryDreamScheduler
import me.rerere.rikkahub.data.agent.office.radar.FeishuChangeAnalyzer
import me.rerere.rikkahub.data.agent.office.radar.FeishuChangeNotifier
import me.rerere.rikkahub.data.agent.office.radar.FeishuDocumentFetcher
import me.rerere.rikkahub.data.agent.office.radar.FeishuDocumentMonitor
import me.rerere.rikkahub.data.agent.board.aggregator.SignalAggregator
import me.rerere.rikkahub.data.agent.board.agent.BoardAgent
import me.rerere.rikkahub.ui.pages.board.BoardViewModel
import me.rerere.rikkahub.data.agent.board.collector.BoardSignalCollector
import me.rerere.rikkahub.data.agent.board.collector.CalendarSignalCollector
import me.rerere.rikkahub.data.agent.board.collector.ChatHistorySignalCollector
import me.rerere.rikkahub.data.agent.board.collector.FeishuDocSignalCollector
import me.rerere.rikkahub.data.agent.board.collector.FeishuMessageSignalCollector
import me.rerere.rikkahub.data.agent.board.collector.NotificationSignalCollector
import me.rerere.rikkahub.data.agent.board.collector.TimeAnchorSignalCollector
import me.rerere.rikkahub.data.agent.board.worker.BoardNotifier
import me.rerere.rikkahub.data.agent.board.worker.BoardScheduler
import me.rerere.rikkahub.data.memory.extraction.MemoryCandidateFilter
import me.rerere.rikkahub.data.memory.extraction.MemoryExtractor
import me.rerere.rikkahub.data.memory.export.MemoryFrontmatterCodec
import me.rerere.rikkahub.data.memory.export.MemoryImportExportManager
import me.rerere.rikkahub.data.memory.telemetry.MemoryEventLogger
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
        SessionAccessGrantStore()
    }

    single {
        GenerationSubAgentRunner(get())
    }

    single {
        SubAgentManager(get(), get(), get(), get(), get<GenerationSubAgentRunner>(), get(), get())
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
        LiveModeManager(get(), get(), get(), get())
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
        MemoryEventLogger(get())
    }

    single {
        MemoryCandidateFilter()
    }

    single {
        MemoryExtractor(get(), get(), get(), get(), get(), get())
    }

    single {
        MemoryDreamPlanner(get(), get(), get(), get(), get())
    }

    single {
        MemoryDreamApplier(get(), get())
    }

    single {
        MemoryDreamPlanStore(get(), get())
    }

    single {
        MemoryDreamNotifier(get())
    }

    single {
        MemoryDreamScheduler(get(), get())
    }

    // Feishu Document Radar
    single {
        FeishuDocumentFetcher(get(), get(), get(), get())
    }

    single {
        FeishuChangeAnalyzer(get(), get(), get())
    }

    single {
        FeishuChangeNotifier(get())
    }

    single {
        FeishuDocumentMonitor(get(), get())
    }

    // Today Board
    single {
        CalendarSignalCollector(get())
    }

    single {
        ChatHistorySignalCollector(get())
    }

    single {
        FeishuDocSignalCollector(get(), get())
    }

    single {
        FeishuMessageSignalCollector(get())
    }

    single {
        TimeAnchorSignalCollector {
            get<me.rerere.rikkahub.data.datastore.SettingsStore>()
              .settingsFlow.value.agentRuntime.todayBoard.triggerHours
        }
    }

    single {
        val collectors: List<BoardSignalCollector> = listOf(
            get<CalendarSignalCollector>(),
            get<ChatHistorySignalCollector>(),
            get<FeishuDocSignalCollector>(),
            get<FeishuMessageSignalCollector>(),
            get<TimeAnchorSignalCollector>(),
        )
        SignalAggregator(
            boardRepository = get(),
            collectors = collectors,
            settingProvider = {
                get<me.rerere.rikkahub.data.datastore.SettingsStore>()
                    .settingsFlow.value.agentRuntime.todayBoard
            },
            onThresholdReached = { get<BoardScheduler>().runIncremental() },
        )
    }

    single {
        BoardScheduler(
            context = get(),
            settingsStore = get(),
        )
    }

    single {
        BoardNotifier(get())
    }

    single {
        NotificationSignalCollector(
            context = get(),
            aggregator = get(),
            ioScope = get<me.rerere.rikkahub.AppScope>(),
        )
    }

    single {
        BoardAgent(
            settingsStore = get(),
            providerManager = get(),
            boardRepository = get(),
        )
    }

    factory {
        BoardViewModel(
            boardRepository = get(),
            settingsStore = get(),
            scheduler = get(),
            appScope = get(),
        )
    }

    single {
        MemoryFrontmatterCodec()
    }

    single {
        MemoryImportExportManager(get(), get())
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
            sessionAccessGrantStore = get(),
            memoryExtractor = get(),
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
