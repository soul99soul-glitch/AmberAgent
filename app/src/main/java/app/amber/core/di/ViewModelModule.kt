package app.amber.core.di

import app.amber.feature.ui.pages.assistant.AssistantVM
import app.amber.feature.ui.pages.assistant.detail.AssistantDetailVM
import app.amber.feature.ui.pages.backup.BackupVM
import app.amber.feature.ui.pages.chat.ChatDrawerVM
import app.amber.feature.ui.pages.chat.ChatVM
import app.amber.feature.ui.pages.debug.DebugVM
import app.amber.feature.ui.pages.developer.DeveloperVM
import app.amber.feature.ui.pages.favorite.FavoriteVM
import app.amber.feature.ui.pages.search.SearchVM
import app.amber.feature.ui.pages.history.HistoryVM
import app.amber.feature.ui.pages.stats.StatsVM
import app.amber.feature.ui.pages.imggen.ImgGenVM
import app.amber.feature.ui.pages.extensions.PromptVM
import app.amber.feature.ui.pages.extensions.QuickMessagesVM
import app.amber.feature.ui.pages.extensions.SkillDetailVM
import app.amber.feature.ui.pages.extensions.SkillsVM
import app.amber.feature.ui.pages.live.LiveCompanionVM
import app.amber.feature.ui.pages.setting.SettingAgentMemoryVM
import app.amber.feature.ui.pages.setting.SettingVM
import app.amber.feature.ui.pages.share.handler.ShareHandlerVM
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val viewModelModule = module {
    viewModel<ChatVM> { params ->
        ChatVM(
            id = params.get(),
            context = get(),
            settingsStore = get(),
            conversationRepo = get(),
            chatService = get(),
            analytics = get(),
            filesManager = get(),
            favoriteRepository = get(),
            contextRepository = get(),
            sendMessageOrchestrator = get(),
            regenerateMessageOrchestrator = get(),
            branchMessageOrchestrator = get(),
        )
    }
    viewModelOf(::ChatDrawerVM)
    viewModelOf(::SettingVM)
    viewModelOf(::SettingAgentMemoryVM)
    viewModelOf(::DebugVM)
    viewModelOf(::HistoryVM)
    viewModelOf(::AssistantVM)
    viewModel<AssistantDetailVM> {
        AssistantDetailVM(
            id = it.get(),
            settingsStore = get(),
            memoryRepository = get(),
            filesManager = get(),
            skillManager = get(),
        )
    }
    viewModelOf(::LiveCompanionVM)
    viewModel<ShareHandlerVM> {
        ShareHandlerVM(
            text = it.get(),
            settingsStore = get(),
        )
    }
    viewModelOf(::BackupVM)
    viewModelOf(::ImgGenVM)
    viewModelOf(::DeveloperVM)
    viewModelOf(::PromptVM)
    viewModelOf(::QuickMessagesVM)
    viewModelOf(::SkillsVM)
    viewModelOf(::SkillDetailVM)
    viewModelOf(::FavoriteVM)
    viewModelOf(::SearchVM)
    viewModelOf(::StatsVM)
}
