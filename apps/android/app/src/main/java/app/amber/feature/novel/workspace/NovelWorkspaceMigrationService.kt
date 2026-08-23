package app.amber.feature.novel.workspace

import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelProjectLoadAccess
import app.amber.feature.novel.persistence.NovelProjectPersisting
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import app.amber.feature.novelworkspace.NovelWorkspaceSessions
import java.time.Instant

/**
 * One-way bridge from the legacy JSON engine to the markdown workspace.
 *
 * Local migration keeps the source project id (the legacy store stays untouched as the
 * rollback copy); the "always a fresh id" rule applies to cross-device imports only.
 * Book and sessions survive; CAS/checkpoint machinery is intentionally left behind.
 */
class NovelWorkspaceMigrationService(
    private val legacyRepository: NovelProjectPersisting,
    private val workspaceRepository: NovelWorkspaceProjectRepository,
) {
    sealed interface Result {
        data class Completed(val projectId: String, val projectName: String, val plotMissing: Boolean) : Result
        data class AlreadyMigrated(val projectId: String) : Result
        data class Rejected(val reason: String) : Result
    }

    suspend fun migrate(projectId: NovelProjectId, now: Instant = Instant.now()): Result {
        if (workspaceRepository.exists(projectId.rawValue)) {
            return Result.AlreadyMigrated(projectId.rawValue)
        }
        val loaded = try {
            legacyRepository.loadProject(projectId)
        } catch (error: Exception) {
            return Result.Rejected(error.message ?: "无法读取原项目")
        }
        if (loaded.access != NovelProjectLoadAccess.ReadWrite) {
            return Result.Rejected("原项目处于只读恢复状态，请先修复再转换")
        }
        val document = loaded.document
        val files = NovelLegacyWorkspaceMigrator.workspaceFiles(document, exportedAt = now)
        val sessions = NovelLegacyWorkspaceMigrator.sessionsFile(document)
        val installed = try {
            workspaceRepository.install(projectId.rawValue, files, now = now)
        } catch (error: Exception) {
            return Result.Rejected(error.message ?: "工作区写入失败")
        }
        NovelWorkspaceSessions.save(sessions, installed.projectDirectory)
        return Result.Completed(
            projectId = projectId.rawValue,
            projectName = document.project.name,
            plotMissing = installed.plotMissing,
        )
    }

    data class MigrateAllResult(val migrated: Int, val skipped: Int, val failed: Int)

    /**
     * Migrate every legacy project to the workspace format. Idempotent: an existing
     * workspace copy is skipped; originals are left untouched as rollback copies.
     */
    suspend fun migrateAll(now: Instant = Instant.now()): MigrateAllResult {
        val legacy = legacyRepository.listProjects()
        var migrated = 0
        var skipped = 0
        var failed = 0
        for (summary in legacy) {
            when (migrate(summary.id, now)) {
                is Result.Completed -> migrated++
                is Result.AlreadyMigrated -> skipped++
                is Result.Rejected -> failed++
            }
        }
        return MigrateAllResult(migrated, skipped, failed)
    }
}
