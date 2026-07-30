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
    ) async -> [IOSPendingApprovalRecoveryDescriptor]? {
        let dao = IosDatabaseFactory.shared.createDatabase().agentRuntimeDao()
        let descriptors = await withCheckedContinuation {
            (continuation: CheckedContinuation<[IOSPendingApprovalRecoveryDescriptor]?, Never>) in
            dao.listAwaitingPermission { result, error in
                guard error == nil, let result else {
                    continuation.resume(returning: nil)
                    return
                }
                let descriptors = result.compactMap { run -> IOSPendingApprovalRecoveryDescriptor? in
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
        // listUnfinished returns running, awaiting-permission, and recovery-pending rows.
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

    /// Snapshot of the (runId, conversationId) pairs `recoverInterruptedRuns`
    /// is about to reclassify — read separately, rather than changing that
    /// function's `Int`-count return type, so its existing signature/behavior
    /// stays exactly as S1/S2 left it. This runs the same `listUnfinished()`
    /// query a second time; AppShell's startup sweep is a strictly sequential
    /// single-digit-row read with no concurrent writer in between, so the
    /// extra round trip is cheap and the two reads agree.
    ///
    /// Runs with no `conversationId` are skipped: W3's tool-call recovery
    /// needs a conversation to write its recovery marker into, and a run
    /// without one (e.g. subagent/council-internal runs that don't map to a
    /// chat conversation) has nothing for it to do.
    static func unfinishedRunConversationPairs(
        excludingRunIds: Set<String> = []
    ) async -> [(runId: String, conversationId: String)]? {
        let dao = IosDatabaseFactory.shared.createDatabase().agentRuntimeDao()
        return await withCheckedContinuation { (cont: CheckedContinuation<[(runId: String, conversationId: String)]?, Never>) in
            dao.listUnfinished { result, error in
                guard error == nil, let result else {
                    cont.resume(returning: nil)
                    return
                }
                let pairs = result.compactMap { run -> (runId: String, conversationId: String)? in
                    guard !excludingRunIds.contains(run.runId), let conversationId = run.conversationId else { return nil }
                    return (run.runId, conversationId)
                }
                cont.resume(returning: pairs)
            }
        }
    }

    /// W3 (§ crash-recovery UX, invariant I-3): reads one run's `agent_event`
    /// ledger, decodes it, and decides each unresolved/lost toolCallId's
    /// recovery action (`IOSToolCallRecoveryPlanner`). `messages` is the
    /// caller's already-loaded snapshot of that run's conversation — this
    /// function only reads the ledger; it never touches conversation storage
    /// itself, so callers stay in control of when/whether to persist the
    /// result of applying the plan.
    ///
    /// `@MainActor`: `[UIMessage]` (a KMP-bridged type) isn't `Sendable`, and
    /// every real caller (`ChatViewModel`) is already MainActor-isolated —
    /// pinning this here avoids an actor-crossing "sending risks data races"
    /// diagnostic for no actual concurrency benefit (the DB hop still happens
    /// via `withCheckedContinuation` regardless of which actor called in).
    @MainActor
    static func planToolCallRecovery(
        runId: String,
        messages: [UIMessage]
    ) async -> [String: IOSToolCallRecoveryAction]? {
        let dao = IosDatabaseFactory.shared.createDatabase().agentRuntimeDao()
        let rows: [IOSToolCallLedgerRow]? = await withCheckedContinuation { continuation in
            dao.listEventsForRun(id: runId) { result, error in
                guard error == nil, let result else {
                    continuation.resume(returning: nil)
                    return
                }
                let decoded = result.compactMap {
                    IOSToolCallLedgerRow.decode(type: $0.type, seq: $0.seq, payload: $0.payload)
                }
                continuation.resume(returning: decoded)
            }
        }
        guard let rows else { return nil }
        guard !rows.isEmpty else { return [:] }
        let emptyOutputToolCallIds = Set(
            messages
                .flatMap(\.parts)
                .compactMap { ($0 as? UIMessagePart.Tool) }
                .filter { $0.output.isEmpty }
                .map(\.toolCallId)
        )
        return IOSToolCallRecoveryPlanner.plan(rows: rows) { emptyOutputToolCallIds.contains($0) }
    }
}
