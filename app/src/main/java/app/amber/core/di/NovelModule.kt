package app.amber.core.di

import app.amber.feature.novel.persistence.NovelFileProjectRepository
import app.amber.feature.novel.persistence.NovelProjectPersisting
import app.amber.feature.novel.workspace.NovelTurnLauncher
import app.amber.feature.novel.workspace.NovelTurnPayloads
import app.amber.feature.novel.workspace.NovelWorkspaceGhostwriteController
import app.amber.feature.novel.workspace.NovelWorkspaceGhostwriteCoordinator
import app.amber.feature.novel.workspace.NovelWorkspaceMigrationService
import app.amber.feature.novel.workspace.NovelWorkspaceRuntime
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import app.amber.feature.ui.pages.novel.NovelMarkdownWorkspaceViewModel
import app.amber.feature.ui.pages.novel.NovelProjectsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Novel DI — the markdown workspace chain is the active pipeline; the legacy JSON
 * engine is gone. What remains here is the migration read shell (legacy file project
 * repository + one-way migrator) so old-format books can still be read and converted.
 */
val novelModule = module {
    single<NovelProjectPersisting> {
        NovelFileProjectRepository(
            rootDirectory = NovelFileProjectRepository.defaultRoot(androidContext().filesDir),
        )
    }

    single {
        NovelWorkspaceProjectRepository(
            NovelWorkspaceProjectRepository.defaultRoot(androidContext().filesDir),
        )
    }

    single {
        NovelWorkspaceMigrationService(
            legacyRepository = get(),
            workspaceRepository = get(),
        )
    }

    single { NovelTurnPayloads() }

    single { NovelTurnLauncher(get(), get()) }

    single { NovelWorkspaceGhostwriteCoordinator(NovelWorkspaceRuntime(get()), get()) }

    single { NovelWorkspaceGhostwriteController(androidContext(), get()) }

    viewModel {
        NovelProjectsViewModel(
            workspaceRepository = get(),
            workspaceMigrationService = get(),
            legacyRepository = get(),
        )
    }

    viewModel { parameters ->
        NovelMarkdownWorkspaceViewModel(
            projectId = parameters.get(),
            repository = get(),
            settingsAggregator = get(),
            ghostwriteController = get(),
            turnLauncher = get(),
            kernel = get(),
            context = androidContext(),
        )
    }
}
