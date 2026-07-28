import Foundation
import Shared

struct IOSPendingApprovalRecoveryDescriptor: Equatable, Sendable {
    let runId: String
    let conversationId: String
    let toolCallId: String
}

/// Startup recovery for interrupted agent runs. Pending approvals are identified
/// by their durable tool owner and terminated explicitly; tools are never replayed.
enum IOSRunRecovery {
    static func recoverPendingApprovalDescriptors(
        excludingRunIds: Set<String> = []
    ) async -> [IOSPendingApprovalRecoveryDescriptor] {
        let dao = IosDatabaseFactory.shared.createDatabase().agentRuntimeDao()
        let descriptors = await withCheckedContinuation {
            (continuation: CheckedContinuation<[IOSPendingApprovalRecoveryDescriptor], Never>) in
            dao.listAwaitingPermission { result, _ in
                let descriptors = (result ?? []).compactMap { run -> IOSPendingApprovalRecoveryDescriptor? in
                    guard !excludingRunIds.contains(run.runId),
                          let conversationId = run.conversationId,
                          let snapshotRef = run.inputSnapshotRef,
                          snapshotRef.hasPrefix("tool_call:") else { return nil }
                    let toolCallId = String(snapshotRef.dropFirst("tool_call:".count))
                    guard !toolCallId.isEmpty else { return nil }
                    return IOSPendingApprovalRecoveryDescriptor(
                        runId: run.runId,
                        conversationId: conversationId,
                        toolCallId: toolCallId
                    )
                }
                continuation.resume(returning: descriptors)
            }
        }
        return descriptors
    }

    static func completePendingApprovalRecovery(
        runId: String,
        reason: String = "process_killed",
        now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) async {
        let dao = IosDatabaseFactory.shared.createDatabase().agentRuntimeDao()
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            dao.markInterrupted(runId: runId, reason: reason, now: now) { _ in
                continuation.resume()
            }
        }
    }

    /// Reclassifies non-approval unfinished runs to "interrupted".
    @discardableResult
    static func recoverInterruptedRuns(
        excludingRunIds: Set<String> = [],
        reason: String = "process_killed",
        now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) async -> Int {
        let dao = IosDatabaseFactory.shared.createDatabase().agentRuntimeDao()
        // listUnfinished returns rows with status IN ('running','awaiting_permission').
        // Extract only the Sendable runId strings inside the callback to avoid
        // crossing isolation with the non-Sendable KMP [AgentRunEntity].
        let runIds: [String] = await withCheckedContinuation { (cont: CheckedContinuation<[String], Never>) in
            dao.listUnfinished { result, _ in
                let ids = (result ?? []).map { $0.runId }.filter {
                    !excludingRunIds.contains($0)
                }
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
