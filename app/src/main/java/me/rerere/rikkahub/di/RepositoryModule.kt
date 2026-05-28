package me.rerere.rikkahub.di

import app.amber.feature.board.BoardRepository
import app.amber.feature.board.hotlist.HotListRepository
import app.amber.feature.miniapp.MiniAppRepository
import app.amber.feature.prompts.AgentPromptConfigRepository
import app.amber.core.files.FilesManager
import app.amber.core.files.SkillManager
import app.amber.core.repository.ConversationRepository
import app.amber.core.repository.FavoriteRepository
import app.amber.core.repository.FilesRepository
import app.amber.core.repository.GenMediaRepository
import app.amber.core.repository.ImageGenerationRepository
import app.amber.core.repository.MemoryRepository
import app.amber.core.memory.recall.MemoryRecallStore
import org.koin.dsl.module

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get(), get())
    }

    single {
        MemoryRepository(get(), get(), get())
    }

    single<app.amber.core.memory.store.MemoryRepository> {
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
        MiniAppRepository(get(), get(), get(), get(), get(), get(), get())
    }
}
