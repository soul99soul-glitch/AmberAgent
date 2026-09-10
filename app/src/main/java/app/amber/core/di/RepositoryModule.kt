package app.amber.core.di

import android.content.Context
import app.amber.core.conversation.exchange.ConversationExchangeFileHandler
import app.amber.core.conversation.exchange.ConversationExchangeService
import app.amber.feature.board.BoardRepository
import app.amber.feature.board.hotlist.HotListRepository
import app.amber.feature.miniapp.MiniAppRepository
import app.amber.feature.prompts.AgentPromptConfigRepository
import app.amber.core.files.FilesManager
import app.amber.core.files.SkillManager
import app.amber.core.repository.ConversationRepository
import app.amber.core.repository.FavoriteRepository
import app.amber.core.repository.FilesRepository
import app.amber.core.repository.ImageGenerationRepository
import app.amber.core.repository.MemoryRepository
import app.amber.core.memory.recall.MemoryRecallStore
import app.amber.core.sync.core.SyncRestoreWriteGate
import org.koin.dsl.module

val repositoryModule = module {
    single {
        ConversationRepository(
            conversationDAO = get(),
            messageNodeDAO = get(),
            messageStatsDAO = get(),
            favoriteDAO = get(),
            database = get(),
            filesManager = get(),
            messageFtsManager = get(),
            restoreWriteGate = get(),
        )
    }

    single { ConversationExchangeService(get(), get()) }

    single {
        ConversationExchangeFileHandler(
            contentResolver = get<Context>().contentResolver,
            service = get(),
        )
    }

    single {
        MemoryRepository(get(), get(), get(), get(), get())
    }

    single<app.amber.core.memory.store.MemoryRepository> {
        get<MemoryRepository>()
    }

    single {
        MemoryRecallStore(get())
    }

    single {
        AgentPromptConfigRepository(get())
    }

    single {
        ImageGenerationRepository(
            settingsStore = get(),
            providerCatalog = get(),
            filesManager = get(),
            promptConfigRepository = get(),
        )
    }

    single {
        FilesRepository(get())
    }

    single {
        FavoriteRepository(get(), get<SyncRestoreWriteGate>())
    }

    single {
        FilesManager(get(), get(), get(), get<SyncRestoreWriteGate>())
    }

    single {
        SkillManager(get(), get())
    }

    single {
        BoardRepository(get(), get(), get(), get(), get(), get())
    }

    single {
        HotListRepository(get(), get(), get<SyncRestoreWriteGate>())
    }

    single {
        MiniAppRepository(
            database = get(),
            dao = get(),
            grantDao = get(),
            versionDao = get(),
            auditLogDao = get(),
            sharedDataDao = get(),
            json = get(),
            restoreWriteGate = get(),
        )
    }
}
