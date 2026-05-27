package me.rerere.rikkahub.di

import me.rerere.rikkahub.data.agent.board.agent.BoardAgent
import me.rerere.rikkahub.data.agent.board.agent.DailyReviewAgent
import me.rerere.rikkahub.data.agent.board.aggregator.SignalAggregator
import me.rerere.rikkahub.data.agent.board.collector.AppUsageCollector
import me.rerere.rikkahub.data.agent.board.collector.BoardSignalCollector
import me.rerere.rikkahub.data.agent.board.collector.CalendarSignalCollector
import me.rerere.rikkahub.data.agent.board.collector.ChatHistorySignalCollector
import me.rerere.rikkahub.data.agent.board.collector.FeishuDocSignalCollector
import me.rerere.rikkahub.data.agent.board.collector.FeishuMessageSignalCollector
import me.rerere.rikkahub.data.agent.board.collector.NotificationSignalCollector
import me.rerere.rikkahub.data.agent.board.collector.TimeAnchorSignalCollector
import me.rerere.rikkahub.data.agent.board.hotlist.HotListAggregator
import me.rerere.rikkahub.data.agent.board.hotlist.HotListSafeFetcher
import me.rerere.rikkahub.data.agent.board.hotlist.HotListScheduler
import me.rerere.rikkahub.data.agent.board.hotlist.HotListTitleLocalizer
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadAgentRunManager
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadNotifier
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadPlaybookRepository
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadResearchHarness
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadScheduler
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadSourcePrefetcher
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.template.DeepReadTemplateAgent
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.template.DeepReadTemplateRepository
import me.rerere.rikkahub.data.agent.board.hotlist.providers.BuiltInHotListProviders
import me.rerere.rikkahub.data.agent.board.worker.BoardNotifier
import me.rerere.rikkahub.data.agent.board.worker.BoardScheduler
import me.rerere.rikkahub.data.agent.office.radar.DocRadar
import me.rerere.rikkahub.data.agent.office.radar.FeishuChangeNotifier
import me.rerere.rikkahub.data.agent.tools.AgentToolSetFactory
import me.rerere.rikkahub.ui.pages.board.BoardViewModel
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
