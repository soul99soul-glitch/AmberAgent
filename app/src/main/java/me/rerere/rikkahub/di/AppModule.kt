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
import me.rerere.rikkahub.data.agent.webmount.adapters.hackernews.HnAdapter
import me.rerere.rikkahub.data.agent.webmount.adapters.hackernews.HnClient
import me.rerere.rikkahub.data.agent.webmount.adapters.hackernews.HnTools
import me.rerere.rikkahub.data.agent.webmount.profile.HostShimRegistry
import me.rerere.rikkahub.data.agent.webmount.profile.ProfileBridge
import me.rerere.rikkahub.data.agent.webmount.profile.ProfileRegistry
import me.rerere.rikkahub.data.agent.webmount.usersites.UserSiteRegistry
import me.rerere.rikkahub.data.agent.webmount.adapters.bilibili.BilibiliAdapter
import me.rerere.rikkahub.data.agent.webmount.adapters.bilibili.BilibiliClient
import me.rerere.rikkahub.data.agent.webmount.adapters.bilibili.BilibiliTools
import me.rerere.rikkahub.data.agent.webmount.adapters.feishudocs.FeishuDocsAdapter
import me.rerere.rikkahub.data.agent.webmount.adapters.feishudocs.FeishuDocsClient
import me.rerere.rikkahub.data.agent.webmount.adapters.feishudocs.FeishuDocsTools
import me.rerere.rikkahub.data.agent.webmount.adapters.github.GithubAdapter
import me.rerere.rikkahub.data.agent.webmount.adapters.github.GithubClient
import me.rerere.rikkahub.data.agent.webmount.adapters.github.GithubTools
import me.rerere.rikkahub.data.agent.webmount.adapters.juejin.JuejinAdapter
import me.rerere.rikkahub.data.agent.webmount.adapters.juejin.JuejinClient
import me.rerere.rikkahub.data.agent.webmount.adapters.juejin.JuejinTools
import me.rerere.rikkahub.data.agent.webmount.adapters.zhihu.ZhihuAdapter
import me.rerere.rikkahub.data.agent.webmount.adapters.zhihu.ZhihuClient
import me.rerere.rikkahub.data.agent.webmount.adapters.zhihu.ZhihuTools
import me.rerere.rikkahub.data.agent.webmount.adapters.reddit.RedditAdapter
import me.rerere.rikkahub.data.agent.webmount.adapters.reddit.RedditClient
import me.rerere.rikkahub.data.agent.webmount.adapters.reddit.RedditTools
import me.rerere.rikkahub.data.agent.webmount.cookie.WebMountCookieProvider
import me.rerere.rikkahub.data.agent.webmount.core.WebMountAdapter
import me.rerere.rikkahub.data.agent.webmount.core.WebMountManager
import me.rerere.rikkahub.data.agent.webmount.oauth.FeishuOAuthProvider
import me.rerere.rikkahub.data.agent.webmount.oauth.OAuthCallbackDispatcher
import me.rerere.rikkahub.data.agent.webmount.oauth.PendingOAuthStore
import me.rerere.rikkahub.data.agent.webmount.oauth.WebMountOAuthClient
import me.rerere.rikkahub.data.agent.webmount.oauth.WebMountOAuthTokenStore
import me.rerere.rikkahub.data.agent.webmount.primitives.WebViewPool
import me.rerere.rikkahub.data.agent.webmount.tools.WebMountPrimitiveTools
import me.rerere.rikkahub.data.agent.live.LiveModeManager
import me.rerere.rikkahub.data.agent.modelcouncil.ExternalCliModelCouncilRunner
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
import me.rerere.rikkahub.data.font.SlidesFontRepository
import me.rerere.rikkahub.data.agent.office.radar.FeishuChangeNotifier
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
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
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

    // WebMount Stations (experimental, Phase 1).
    // Independent from the iCloud experimental feature above — iCloud keeps
    // its own standalone panel. WebMount is a separate framework that will
    // host the new sites added in M1.6 (Feishu Docs, GitHub, Bilibili, etc.).
    single {
        WebMountCookieProvider()
    }

    single {
        WebMountOAuthTokenStore(context = get())
    }

    single {
        PendingOAuthStore(context = get())
    }

    single {
        OAuthCallbackDispatcher()
    }

    // Phase 2 M2.0.3 fix: createdAtStart so the OAuth resume collector
    // subscribes to dispatcher.events BEFORE a cold-start callback Intent
    // can arrive. Combined with OAuthCallbackDispatcher's replay=1 this
    // closes the race where the dispatch fires before any UI/service has
    // resolved this singleton.
    single(createdAtStart = true) {
        WebMountOAuthClient(
            context = get(),
            store = get(),
            pendingStore = get(),
            dispatcher = get(),
            http = get(),
            appScope = get(),
        ).apply {
            register(FeishuOAuthProvider)
        }
    }

    single {
        HnClient(http = get())
    }

    single {
        HnTools(client = get())
    }

    single {
        HnAdapter(tools = get())
    }

    single {
        RedditClient(http = get())
    }

    single {
        RedditTools(client = get())
    }

    single {
        RedditAdapter(tools = get())
    }

    single {
        JuejinClient(http = get())
    }

    single {
        JuejinTools(client = get())
    }

    single {
        JuejinAdapter(tools = get(), cookieProvider = get())
    }

    single {
        FeishuDocsClient(http = get())
    }

    single {
        FeishuDocsTools(client = get(), pool = get())
    }

    single {
        FeishuDocsAdapter(tools = get(), oauthClient = get())
    }

    single {
        GithubClient(http = get())
    }

    single {
        GithubTools(client = get())
    }

    single {
        GithubAdapter(tools = get(), oauthStore = get())
    }

    single {
        BilibiliClient(http = get())
    }

    single {
        BilibiliTools(client = get())
    }

    single {
        BilibiliAdapter(tools = get(), cookieProvider = get())
    }

    single {
        ZhihuClient(http = get())
    }

    single {
        ZhihuTools(client = get())
    }

    single {
        ZhihuAdapter(tools = get(), cookieProvider = get())
    }

    single {
        val adapters: List<WebMountAdapter> = listOf(
            get<HnAdapter>(),
            get<RedditAdapter>(),
            get<JuejinAdapter>(),
            get<FeishuDocsAdapter>(),
            get<GithubAdapter>(),
            get<BilibiliAdapter>(),
            get<ZhihuAdapter>(),
        )
        WebMountManager(
            context = get(),
            adapters = adapters,
            cookieProvider = get(),
            oauthStore = get(),
            activityStore = get(),
            appScope = get(),
        )
    }

    single {
        WebViewPool(appContext = get())
    }

    single {
        ProfileRegistry(context = get())
    }

    single {
        UserSiteRegistry(context = get())
    }

    single {
        HostShimRegistry(context = get())
    }

    single {
        ProfileBridge(shimRegistry = get())
    }

    single {
        WebMountPrimitiveTools(
            pool = get(),
            activityStore = get(),
            manager = get(),
            profileRegistry = get(),
            cookieProvider = get(),
            profileBridge = get(),
            userSiteRegistry = get(),
            oauthStore = get(),
        )
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
        ExternalCliModelCouncilRunner(get())
    }

    single {
        ModelCouncilManager(get(), get(), get(), get(), get<ProviderModelCouncilTextRunner>(), get(), get())
    }

    single {
        WorkspaceArtifactTools(get(), get(), get())
    }

    single {
        SlidesFontRepository(context = get(), client = get(), json = get())
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
        LocalTools(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get())
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
    // Feishu Document Radar (refactored)
    single {
        FeishuChangeNotifier(get())
    }

    single {
        me.rerere.rikkahub.data.agent.office.radar.DocRadar(
            context = get(),
            subscriptionDao = get(),
            changeLogDao = get(),
            mcpManager = get(),
            settingsStore = get(),
            notifier = get(),
        )
    }

    // Today Board
    single {
        CalendarSignalCollector(get())
    }

    single {
        ChatHistorySignalCollector(get())
    }

    single {
        FeishuDocSignalCollector(
            subscriptionDao = get<me.rerere.rikkahub.data.db.dao.DocSubscriptionDAO>(),
            changeLogDao = get<me.rerere.rikkahub.data.db.dao.DocChangeLogDAO>(),
        )
    }

    single {
        FeishuMessageSignalCollector(get())
    }

    single {
        TimeAnchorSignalCollector {
            get<me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator>()
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
                get<me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator>()
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

    single {
        me.rerere.rikkahub.data.agent.board.collector.AppUsageCollector(get())
    }

    single {
        me.rerere.rikkahub.data.agent.board.agent.DailyReviewAgent(
            settingsStore = get(),
            providerManager = get(),
            boardRepository = get(),
            conversationRepository = get(),
            appUsageCollector = get(),
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


}
