package app.amber.feature.home

import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/** Only job records and project summaries are read; no manuscript or model replay on Home. */
class NovelWorkspaceContinueSource(
    private val repository: NovelWorkspaceProjectRepository,
    private val pollIntervalMillis: Long = 5_000L,
) : ContinueCandidateSource {
    override fun observe(): Flow<List<ContinueCandidate>> = flow {
        while (currentCoroutineContext().isActive) {
            val candidates = repository.listProjects().flatMap { project ->
                val snapshot = NovelWorkspaceGhostwriteJobs.snapshot(repository.projectDirectory(project.id))
                val jobs = snapshot.jobs.groupBy { it.branchSlug }.values.mapNotNull { branchJobs ->
                    branchJobs.filter { !it.isTerminal }.maxByOrNull { it.updatedAt }
                        ?: branchJobs.filter { it.status == NovelWorkspaceGhostwriteJob.STATUS_FAILED }
                            .maxByOrNull { it.updatedAt }
                }
                buildList {
                    jobs.forEach { job ->
                        add(ContinueCandidate(
                            sourceKind = ContinueSourceKind.NOVEL_WORKSPACE,
                            sourceId = "${project.id}:${job.id}",
                            route = ContinueRoute.NovelWorkspace(project.id, job.branchSlug, job.id),
                            title = project.name,
                            summary = "${job.branchSlug} · " + when (job.status) {
                                NovelWorkspaceGhostwriteJob.STATUS_RUNNING -> "代笔进行中"
                                NovelWorkspaceGhostwriteJob.STATUS_PAUSED -> "代笔已暂停"
                                else -> job.reason ?: "代笔需要处理"
                            },
                            lastUpdatedAt = job.updatedAt,
                            status = if (job.status == NovelWorkspaceGhostwriteJob.STATUS_PAUSED) {
                                ContinueStatus.PAUSED
                            } else ContinueStatus.FAILED_RESUMABLE,
                            isRunning = job.status == NovelWorkspaceGhostwriteJob.STATUS_RUNNING,
                        ))
                    }
                    if (snapshot.unreadableFiles.isNotEmpty()) {
                        add(ContinueCandidate(
                            sourceKind = ContinueSourceKind.NOVEL_WORKSPACE,
                            sourceId = "${project.id}:unreadable",
                            route = ContinueRoute.NovelWorkspace(project.id, null, null),
                            title = project.name,
                            summary = "部分代笔记录无法读取，原文件已保留",
                            lastUpdatedAt = project.updatedAt,
                            status = ContinueStatus.WAITING_USER,
                        ))
                    }
                }
            }
            emit(candidates)
            delay(pollIntervalMillis)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)
}
