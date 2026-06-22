import Foundation
import Shared

/// Startup recovery sweep for interrupted agent runs (truth_matrix `recover`
/// column). On launch, any run left in a non-terminal state ("running" /
/// "awaiting_permission") by a hard kill is reclassified to "interrupted" so the
/// UI can surface it honestly (resume / re-show pending cards) instead of
/// silently losing it. P5 scope; shipped as an independent module that can be
/// disabled wholesale (spec rollback note).
///
/// This is the read+reclassify pass only. It does NOT auto-resume execution
/// (resume decisions are surfaced to the user) and does NOT re-run tools
/// (idempotency is handled by the checkpoint layer in P5 proper).
enum IOSRunRecovery {
    /// Reclassifies all non-terminal runs to "interrupted". Call once at app
    /// launch (after the database is available). Returns the count reclassified.
    /// Reason is recorded for the UI to display.
    @discardableResult
    static func recoverInterruptedRuns(reason: String = "process_killed", now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) async -> Int {
        let dao = IosDatabaseFactory.shared.createDatabase().agentRuntimeDao()
        // listUnfinished returns rows with status IN ('running','awaiting_permission').
        // Extract only the Sendable runId strings inside the callback to avoid
        // crossing isolation with the non-Sendable KMP [AgentRunEntity].
        let runIds: [String] = await withCheckedContinuation { (cont: CheckedContinuation<[String], Never>) in
            dao.listUnfinished { result, _ in
                let ids = (result ?? []).map { $0.runId }
                cont.resume(returning: ids)
            }
        }
        guard !runIds.isEmpty else { return 0 }
        // Reclassify each to "interrupted". markInterrupted is per-runId.
        for runId in runIds {
            await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
                dao.markInterrupted(runId: runId, reason: reason, now: now) { _ in
                    cont.resume()
                }
            }
        }
        return runIds.count
    }
}
