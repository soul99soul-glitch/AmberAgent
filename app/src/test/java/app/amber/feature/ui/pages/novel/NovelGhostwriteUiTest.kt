package app.amber.feature.ui.pages.novel

import app.amber.feature.novel.NovelGhostwriteFailureReason
import app.amber.feature.novel.NovelGhostwriteBinding
import app.amber.feature.novel.NovelGhostwritePauseReason
import app.amber.feature.novel.NovelGhostwritePhase
import app.amber.feature.novel.NovelGhostwriteProgress
import app.amber.feature.novel.NovelGhostwriteStartResult
import app.amber.feature.novel.NovelInterruptReason
import app.amber.feature.novel.NovelInterruptRequest
import app.amber.feature.novel.domain.NovelGhostwriteReadinessIssue
import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelRunId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelGhostwriteUiTest {
    @Test
    fun planLines_trimsDropsBlanksAndKeepsContractRows() {
        assertEquals(
            listOf("使者身份曝光", "主角离城", "使者身份曝光"),
            novelPlanLines(
                """

                  使者身份曝光
                主角离城
                使者身份曝光
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun upcomingArcLines_deduplicatesCaseInsensitively() {
        assertEquals(
            listOf("Beat A", "Beat B"),
            novelUpcomingArcLines("Beat A\nbeat a\nBeat B"),
        )
    }

    @Test
    fun planLines_keepsUserOrder() {
        assertEquals(
            listOf("C", "A", "B"),
            novelPlanLines("C\nA\nB"),
        )
    }

    @Test
    fun ghostwriteBusy_fastStartToWaitingUserClearsPendingBusy() {
        assertTrue(
            shouldClearGhostwriteBusy(
                progress = NovelGhostwriteProgress(phase = NovelGhostwritePhase.WaitingUser),
                wasRunning = false,
                startPending = true,
            ),
        )
    }

    @Test
    fun ghostwriteBusy_initialIdleDoesNotClearPendingStart() {
        assertFalse(
            shouldClearGhostwriteBusy(
                progress = NovelGhostwriteProgress(phase = NovelGhostwritePhase.Idle),
                wasRunning = false,
                startPending = true,
            ),
        )
    }

    @Test
    fun legacyFinally_doesNotClearBusyAfterTerminalReleasedItsOperation() {
        assertEquals(
            null,
            legacyOperationBusyAfterFinally(
                operationTokenMatches = true,
                operationStillActive = false,
                ghostwriteOwnedOrPending = true,
            ),
        )
    }

    @Test
    fun legacyFinally_preservesConcurrentGhostwriteBusy() {
        assertEquals(
            true,
            legacyOperationBusyAfterFinally(
                operationTokenMatches = true,
                operationStillActive = true,
                ghostwriteOwnedOrPending = true,
            ),
        )
    }

    @Test
    fun legacyFinally_releasesItsOwnBusyNormally() {
        assertEquals(
            false,
            legacyOperationBusyAfterFinally(
                operationTokenMatches = true,
                operationStillActive = true,
                ghostwriteOwnedOrPending = false,
            ),
        )
    }

    @Test
    fun routeExit_usesOneProjectWideInterruptAndExcludesOwnedBackgroundRun() {
        val projectId = NovelProjectId.generate()
        val ownedGhostwrite = NovelRunId.generate()

        assertEquals(
            NovelInterruptRequest(
                projectId = projectId,
                reason = NovelInterruptReason.RouteExit,
                excludedRunIds = setOf(ownedGhostwrite),
            ),
            novelRouteExitRequest(projectId, setOf(ownedGhostwrite)),
        )
    }

    @Test
    fun routeExit_withoutBackgroundOwnerKeepsOldProjectWideBehavior() {
        val projectId = NovelProjectId.generate()

        assertEquals(
            NovelInterruptRequest(
                projectId = projectId,
                reason = NovelInterruptReason.RouteExit,
            ),
            novelRouteExitRequest(projectId, emptySet()),
        )
    }

    @Test
    fun pausedProgress_oldBranchDoesNotOfferRecoveryOnNewBranch() {
        val projectId = NovelProjectId.generate()
        val oldBranch = NovelBranchId.generate()
        val newBranch = NovelBranchId.generate()
        val staleProgress = NovelGhostwriteProgress(
            binding = NovelGhostwriteBinding(projectId, oldBranch),
            phase = NovelGhostwritePhase.Failed,
            pauseReason = NovelGhostwritePauseReason.SyncFailed,
        )

        val projected = ghostwriteProgressForBranch(staleProgress, projectId, newBranch)

        assertEquals(null, projected)
        assertFalse(projected.canAttemptRecovery())
    }

    @Test
    fun ghostwriteRecovery_syncFailureBypassesInitialReadinessGate() {
        assertTrue(
            NovelGhostwriteProgress(
                phase = NovelGhostwritePhase.Failed,
                pauseReason = NovelGhostwritePauseReason.SyncFailed,
            ).canAttemptRecovery(),
        )
    }

    @Test
    fun ghostwriteRecovery_waitingForNextChapterDoesNotBypassReadiness() {
        assertFalse(
            NovelGhostwriteProgress(
                phase = NovelGhostwritePhase.WaitingUser,
                pauseReason = NovelGhostwritePauseReason.SyncFailed,
            ).canAttemptRecovery(),
        )
    }

    @Test
    fun canonicalContinuityConflictCannotEnterAutomaticRecoveryLoop() {
        assertFalse(
            NovelGhostwriteProgress(
                phase = NovelGhostwritePhase.Paused,
                pauseReason = NovelGhostwritePauseReason.CanonicalContinuityConflict,
                candidateId = app.amber.feature.novel.model.NovelCandidateId.generate(),
            ).canAttemptRecovery(),
        )
    }

    @Test
    fun rejectionCopy_listsEveryReadinessBlocker() {
        assertEquals(
            "代笔条件尚未满足：缺少总剧情大纲；还没有确认的本章计划",
            humanizeGhostwriteRejection(
                NovelGhostwriteStartResult.Rejected(
                    reason = NovelGhostwriteFailureReason.ReadinessBlocked,
                    readinessIssues = listOf(
                        NovelGhostwriteReadinessIssue.MissingMasterOutline,
                        NovelGhostwriteReadinessIssue.MissingChapterPlan,
                    ),
                ),
            ),
        )
    }

    @Test
    fun rejectionCopy_prefersCoordinatorDetail() {
        assertEquals(
            "系统拒绝启动后台服务",
            humanizeGhostwriteRejection(
                NovelGhostwriteStartResult.Rejected(
                    reason = NovelGhostwriteFailureReason.ForegroundServiceUnavailable,
                    detailMessage = "系统拒绝启动后台服务",
                ),
            ),
        )
    }

    @Test
    fun rejectionCopy_notificationFailurePointsToSystemSettings() {
        assertEquals(
            "请到系统设置中为 Amber 开启通知，再开始后台代笔",
            humanizeGhostwriteRejection(
                NovelGhostwriteStartResult.Rejected(
                    reason = NovelGhostwriteFailureReason.NotificationPermissionRequired,
                    detailMessage = "需要通知权限才能在后台继续代笔。",
                ),
            ),
        )
    }
}
