package app.amber.core.di

import android.content.Context
import app.amber.feature.novel.DefaultNovelCreation
import app.amber.feature.novel.NovelCreation
import app.amber.feature.novel.NovelLifecycleBridge
import app.amber.feature.novel.persistence.NovelFileProjectRepository
import app.amber.feature.novel.persistence.NovelProjectPersisting
import app.amber.feature.novel.persistence.NovelRecoveryStore
import app.amber.feature.novel.runtime.AndroidNovelModelAdapter
import app.amber.feature.novel.runtime.NovelModelRunning
import app.amber.feature.ui.pages.novel.NovelProjectUiSession
import app.amber.feature.ui.pages.novel.NovelProjectsViewModel
import app.amber.feature.ui.pages.novel.NovelSettingsViewModel
import app.amber.feature.ui.pages.novel.NovelWorkspaceViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Novel creation DI — single repository + coordinator + model adapter.
 * [NovelLifecycleBridge] is started from [app.amber.agent.AmberAgentApp].
 */
val novelModule = module {
    single(named("novelAppScope")) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    single<NovelProjectPersisting> {
        val context: Context = androidContext()
        NovelFileProjectRepository(
            rootDirectory = NovelFileProjectRepository.defaultRoot(context.filesDir),
        )
    }

    single<NovelModelRunning> {
        AndroidNovelModelAdapter(
            settingsAggregator = get(),
            providerManager = get(),
        )
    }

    single {
        val repo: NovelProjectPersisting = get()
        val root = (repo as? NovelFileProjectRepository)?.let {
            // recovery next to projects root via defaultRoot
            NovelFileProjectRepository.defaultRoot(androidContext().filesDir)
        } ?: NovelFileProjectRepository.defaultRoot(androidContext().filesDir)
        DefaultNovelCreation(
            repository = repo,
            modelRunning = get(),
            appScope = get(named("novelAppScope")),
            recoveryStore = NovelRecoveryStore(root),
        )
    } binds arrayOf(NovelCreation::class)

    single {
        NovelLifecycleBridge(
            novelCreation = get(),
            appScope = get(named("novelAppScope")),
        )
    }

    single { NovelProjectUiSession() }

    viewModel {
        NovelProjectsViewModel(novelCreation = get())
    }

    viewModel { parameters ->
        NovelWorkspaceViewModel(
            projectId = parameters.get(),
            novelCreation = get(),
            uiSession = get(),
        )
    }

    viewModel { parameters ->
        NovelSettingsViewModel(
            projectId = parameters.get(),
            novelCreation = get(),
            uiSession = get(),
        )
    }
}
