package app.amber.core.di

import app.amber.feature.board.agent.BoardAgent
import app.amber.feature.board.agent.DailyReviewAgent
import app.amber.feature.board.aggregator.SignalAggregator
import app.amber.feature.board.collector.AppUsageCollector
import app.amber.feature.board.collector.BoardSignalCollector
import app.amber.feature.board.collector.CalendarSignalCollector
import app.amber.feature.board.collector.ChatHistorySignalCollector
import app.amber.feature.board.collector.FeishuDocSignalCollector
import app.amber.feature.board.collector.FeishuMessageSignalCollector
import app.amber.feature.board.collector.NotificationSignalCollector
import app.amber.feature.board.collector.TimeAnchorSignalCollector
import app.amber.feature.board.hotlist.HotListAggregator
import app.amber.feature.board.hotlist.HotListSafeFetcher
import app.amber.feature.board.hotlist.HotListScheduler
import app.amber.feature.board.hotlist.HotListTitleLocalizer
import app.amber.feature.board.hotlist.deepread.DeepReadAgentRunManager
import app.amber.feature.board.hotlist.deepread.DeepReadNotifier
import app.amber.feature.board.hotlist.deepread.DeepReadPlaybookRepository
import app.amber.feature.board.hotlist.deepread.DeepReadResearchHarness
import app.amber.feature.board.hotlist.deepread.DeepReadScheduler
import app.amber.feature.board.hotlist.deepread.DeepReadSourcePrefetcher
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateAgent
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateRepository
import app.amber.feature.board.hotlist.providers.BuiltInHotListProviders
import app.amber.feature.board.worker.BoardNotifier
import app.amber.feature.board.worker.BoardScheduler
import app.amber.feature.office.radar.DocRadar
import app.amber.feature.office.radar.FeishuChangeNotifier
import app.amber.feature.tools.AgentToolSetFactory
import app.amber.feature.ui.pages.board.BoardViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Today Board + Feishu Doc Radar Koin module — extracted from AppModule in
 * M1.5 continuation.
 *
 * Covers the entire pull-based signal collection pipeline (calendar, chat
 * history, Feishu docs/messages, notifications, time anchors, app usage),
 * the SignalAggregator that scores+filters them, the BoardScheduler /
 * BoardNotifier that anchor the daily run, the BoardAgent / DailyReviewAgent
 * that consume scored signals to produce items + reviews, and the
 * BoardViewModel that surfaces them to UI.
 */
val boardModule = module {
    // Feishu Document Radar (refactored)
    single { FeishuChangeNotifier(get()) }

    single { BuiltInHotListProviders(client = get(), json = get()) }

    single { HotListAggregator() }

    single { HotListSafeFetcher() }

    single {
        AgentToolSetFactory(
            localTools = get(),
        )
    }

    single {
        DeepReadTemplateRepository(
            context = get(),
            json = get(),
        )
    }

    single {
        DeepReadPlaybookRepository(
            context = get(),
        )
    }

    single {
        DeepReadTemplateAgent(
            settingsStore = get(),
            providerManager = get(),
            repository = get(),
            json = get(),
        )
    }

    single {
        HotListTitleLocalizer(
            settingsStore = get(),
            providerManager = get(),
            json = get(),
        )
    }

    single {
        DeepReadSourcePrefetcher(
            settingsStore = get(),
            hotListRepository = get(),
            client = get(),
        )
    }

    single { DeepReadResearchHarness() }

    single {
        DeepReadAgentRunManager(
            settingsStore = get(),
            generationHandler = get(),
            hotListRepository = get(),
            toolSetFactory = get(),
            sourcePrefetcher = get(),
            playbookRepository = get(),
            researchHarness = get(),
            appScope = get(),
        )
    }

    single { DeepReadScheduler(context = get()) }

    single { DeepReadNotifier(context = get()) }

    single {
        HotListScheduler(
            context = get(),
            settingsStore = get(),
        )
    }

    single {
        DocRadar(
            context = get(),
            subscriptionDao = get(),
            changeLogDao = get(),
            mcpManager = get(),
            settingsStore = get(),
            notifier = get(),
        )
    }

    // Today Board — signal collectors
    single { CalendarSignalCollector(get()) }

    single { ChatHistorySignalCollector(get()) }

    single {
        FeishuDocSignalCollector(
            subscriptionDao = get<me.rerere.rikkahub.data.db.dao.DocSubscriptionDAO>(),
            changeLogDao = get<me.rerere.rikkahub.data.db.dao.DocChangeLogDAO>(),
        )
    }

    single { FeishuMessageSignalCollector(get()) }

    single {
        TimeAnchorSignalCollector {
            get<app.amber.core.settings.prefs.SettingsAggregator>()
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
                get<app.amber.core.settings.prefs.SettingsAggregator>()
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

    single { BoardNotifier(get()) }

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

    single { AppUsageCollector(get()) }

    single {
        DailyReviewAgent(
            settingsStore = get(),
            providerManager = get(),
            boardRepository = get(),
            conversationRepository = get(),
            appUsageCollector = get(),
        )
    }

    viewModel {
        BoardViewModel(
            boardRepository = get(),
            hotListRepository = get(),
            settingsStore = get(),
            scheduler = get(),
            hotListScheduler = get(),
            appScope = get(),
        )
    }
}
