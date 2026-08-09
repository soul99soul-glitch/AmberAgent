package app.amber.core.di

import app.amber.feature.novel.DefaultNovelCreation
import app.amber.feature.novel.NovelCreation
import app.amber.feature.novel.NovelBackgroundRunRegistry
import app.amber.feature.novel.NovelGhostwriteBatchController
import app.amber.feature.novel.NovelGhostwriteCoordinator
import app.amber.feature.novel.NovelLifecycleBridge
import app.amber.feature.novel.background.NovelGhostwriteBatchExecutor
import app.amber.feature.novel.background.NovelGhostwriteBatchRunning
import app.amber.feature.novel.background.NovelGhostwriteBatchScheduler
import app.amber.feature.novel.persistence.NovelFileProjectRepository
import app.amber.feature.novel.persistence.NovelGhostwriteJobStore
import app.amber.feature.novel.persistence.NovelProjectPersisting
import app.amber.feature.novel.persistence.NovelRecoveryStore
import app.amber.feature.novel.runtime.AndroidNovelModelAdapter
import app.amber.feature.novel.runtime.NovelModelRunning
import app.amber.feature.ui.pages.novel.NovelProjectUiSession
import app.amber.feature.ui.pages.novel.NovelProjectsViewModel
import app.amber.feature.ui.pages.novel.NovelSettingsViewModel
import app.amber.feature.ui.pages.novel.NovelWorkspaceViewModel
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
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
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            android.util.Log.e("NovelScope", "Uncaught exception", e)
        })
    }

    single<File>(named("novelRootDirectory")) {
        NovelFileProjectRepository.defaultRoot(androidContext().filesDir)
    }

    single<NovelProjectPersisting> {
        NovelFileProjectRepository(
            rootDirectory = get(named("novelRootDirectory")),
        )
    }

    single {
        NovelGhostwriteJobStore(
            rootDirectory = get(named("novelRootDirectory")),
        )
    }

    single { NovelGhostwriteBatchScheduler(androidContext()) }

    single<NovelGhostwriteBatchRunning> {
        NovelGhostwriteBatchExecutor(
            store = get(),
            novelCreation = get(),
            runRegistry = get(),
        )
    }

    single { NovelBackgroundRunRegistry() }

    single<NovelModelRunning> {
        AndroidNovelModelAdapter(
            settingsAggregator = get(),
            providerManager = get(),
        )
    }

    single {
        DefaultNovelCreation(
            repository = get(),
            modelRunning = get(),
            appScope = get(named("novelAppScope")),
            recoveryStore = NovelRecoveryStore(get(named("novelRootDirectory"))),
        )
    } binds arrayOf(NovelCreation::class)

    single {
        NovelGhostwriteBatchController(
            context = androidContext(),
            novelCreation = get(),
            store = get(),
            scheduler = get(),
        )
    }

    single {
        NovelGhostwriteCoordinator(
            context = androidContext(),
            novelCreation = get(),
            appScope = get(named("novelAppScope")),
            backgroundRunRegistry = get(),
        )
    }

    single {
        NovelLifecycleBridge(
            novelCreation = get(),
            backgroundRunRegistry = get(),
            appScope = get(named("novelAppScope")),
        )
    }

    single { NovelProjectUiSession() }

    viewModel {
        NovelProjectsViewModel(
            novelCreation = get(),
            ghostwriteBatchController = get(),
        )
    }

    viewModel { parameters ->
        NovelWorkspaceViewModel(
            projectId = parameters.get(),
            novelCreation = get(),
            uiSession = get(),
            ghostwriteCoordinator = get(),
            backgroundRunRegistry = get(),
            ghostwriteBatchController = get(),
        )
    }

    viewModel { parameters ->
        NovelSettingsViewModel(
            projectId = parameters.get(),
            novelCreation = get(),
            uiSession = get(),
            ghostwriteCoordinator = get(),
            ghostwriteBatchController = get(),
        )
    }
}
