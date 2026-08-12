import Foundation
import Shared

struct IOSPendingApprovalRecoveryDescriptor: Equatable, Sendable {
    let runId: String
    let conversationId: String
    let toolCallId: String
}

/// Startup recovery for interrupted agent runs. Pending approvals are identified
/// by their durable tool owner and terminated explicitly; tools are never replayed.
@MainActor
enum IOSRunRecovery {
    static func recoverPendingApprovalDescriptors(
        excludingRunIds: Set<String> = [],
        runStore: IOSDurableRunStore = IOSDurableRunStore()
    ) async -> [IOSPendingApprovalRecoveryDescriptor]? {
        let runs: [IOSDurableRunStore.Snapshot]
        do {
            runs = try await runStore.recoverableRuns()
        } catch {
            return nil
        }
        return runs.compactMap { run -> IOSPendingApprovalRecoveryDescriptor? in
            guard run.status == .awaitingPermission,
                  !excludingRunIds.contains(run.runId),
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
    }

    static func completePendingApprovalRecovery(
        runId: String,
        reason: String = "process_killed",
        now: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        runStore: IOSDurableRunStore = IOSDurableRunStore()
    ) async {
        _ = try? await runStore.transition(
            runId: runId,
            expected: .awaitingPermission,
            to: .interrupted,
            detail: reason,
            at: now
        )
    }

    /// Reclassifies non-approval unfinished runs to "interrupted".
    @discardableResult
    static func recoverInterruptedRuns(
        candidateRunIds: Set<String>? = nil,
        excludingRunIds: Set<String> = [],
        reason: String = "process_killed",
        now: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        runStore: IOSDurableRunStore = IOSDurableRunStore()
    ) async -> Int {
        guard let runs = try? await runStore.recoverableRuns() else { return 0 }
        var transitioned = 0
        for run in runs where
            (candidateRunIds == nil || candidateRunIds?.contains(run.runId) == true) &&
            !excludingRunIds.contains(run.runId) {
            if (try? await runStore.transition(
                runId: run.runId,
                expected: run.status,
                to: .interrupted,
                inputSnapshotRef: run.inputSnapshotRef,
                detail: reason,
                at: now
            )) == true {
                transitioned += 1
            }
        }
        return transitioned
    }

    /// Snapshot of the (runId, conversationId) pairs from a startup-frozen set.
    /// AppShell captures candidate ids before bootstrapping conversations so a
    /// new foreground run started after the UI becomes interactive can never be
    /// mistaken for work inherited from the previous process.
    ///
    /// Runs with no `conversationId` are skipped: W3's tool-call recovery
    /// needs a conversation to write its recovery marker into, and a run
    /// without one (e.g. subagent/council-internal runs that don't map to a
    /// chat conversation) has nothing for it to do.
    static func unfinishedRunConversationPairs(
        candidateRunIds: Set<String>? = nil,
        excludingRunIds: Set<String> = [],
        runStore: IOSDurableRunStore = IOSDurableRunStore()
    ) async -> [(runId: String, conversationId: String)]? {
        guard let runs = try? await runStore.recoverableRuns() else { return nil }
        return runs.compactMap { run -> (runId: String, conversationId: String)? in
            guard (candidateRunIds == nil || candidateRunIds?.contains(run.runId) == true),
                  !excludingRunIds.contains(run.runId),
                  let conversationId = run.conversationId else {
                return nil
            }
            return (run.runId, conversationId)
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
        messages: [UIMessage],
        dao: AgentRuntimeDao = IosDatabaseFactory.shared.createDatabase().agentRuntimeDao()
    ) async -> [String: IOSToolCallRecoveryAction]? {
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
