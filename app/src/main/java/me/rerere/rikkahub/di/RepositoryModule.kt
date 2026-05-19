package me.rerere.rikkahub.di

import me.rerere.rikkahub.data.agent.board.BoardRepository
import me.rerere.rikkahub.data.agent.board.hotlist.HotListRepository
import me.rerere.rikkahub.data.agent.miniapp.MiniAppRepository
import me.rerere.rikkahub.data.agent.prompts.AgentPromptConfigRepository
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.ImageGenerationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.memory.recall.MemoryRecallStore
import org.koin.dsl.module

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get(), get())
    }

    single {
        MemoryRepository(get(), get(), get())
    }

    single<me.rerere.rikkahub.data.memory.store.MemoryRepository> {
        get<MemoryRepository>()
    }

    single {
        MemoryRecallStore(get())
    }

    single {
        GenMediaRepository(get())
    }

    single {
        AgentPromptConfigRepository(get())
    }

    single {
        ImageGenerationRepository(
            settingsStore = get(),
            providerManager = get(),
            filesManager = get(),
            promptConfigRepository = get(),
        )
    }

    single {
        FilesRepository(get())
    }

    single {
        FavoriteRepository(get())
    }

    single {
        FilesManager(get(), get(), get())
    }

    single {
        SkillManager(get(), get())
    }

    single {
        BoardRepository(get(), get(), get(), get(), get())
    }

    single {
        HotListRepository(get(), get())
    }

    single {
        MiniAppRepository(get(), get(), get(), get(), get())
    }
}
