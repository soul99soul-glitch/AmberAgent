import Foundation
import CryptoKit
@preconcurrency import Shared

/// Swift-facing projection of the shared `agent_run` store.
///
/// Room remains the single source of truth. This adapter only converts Swift
/// values at the Kotlin callback boundary and centralizes the historic Chat
/// descriptor aliases; it does not own another database or recovery state.
final class IOSDurableRunStore: @unchecked Sendable {
    enum Descriptor {
        /// Keep writing the existing iOS wire identity in this phase so rows
        /// created by older releases stay recoverable. `chat_turn` is read as
        /// an alias because Android already uses the canonical descriptor.
        static let chat = "chat"
        static let chatVersion = "1"
        static let chatRecoveryAliases = ["chat", "chat_turn"]
        static let novelGeneration = "novel_generation"
        static let miniAppAI = "miniapp_ai"
        static let council = "council"
        static let deepRead = "deep_read"
        static let version = "1"
    }

    struct Snapshot {
        let runId: String
        let descriptorId: String
        let conversationId: String?
        let status: AgentRunStatus
        let inputSnapshotRef: String?
        let startedAt: Int64
        let finishedAt: Int64?
        let detail: String?
    }

    private let store: RoomAgentEventStore

    init(dao: AgentRuntimeDao = IosDatabaseFactory.shared.createDatabase().agentRuntimeDao()) {
        self.store = RoomAgentEventStore(dao: dao)
    }

    static func inputDigest(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    @discardableResult
    func startRun(
        runId: String,
        descriptorId: String,
        descriptorVersion: String = Descriptor.version,
        conversationId: String? = nil,
        startedAt: Int64,
        inputDigest: String,
        inputSnapshotRef: String? = nil
    ) async throws -> Bool {
        let run = AgentRunRecord(
            runId: runId,
            parentRunId: nil,
            agentDescriptorId: descriptorId,
            agentVersion: descriptorVersion,
            conversationId: conversationId,
            messageNodeId: nil,
            producesMessageId: nil,
            assistantId: nil,
            status: .running,
            inputDigest: inputDigest,
            inputSnapshotRef: inputSnapshotRef,
            inputSchemaVersion: 1,
            startedAt: startedAt,
            finishedAt: nil,
            interruptedReason: nil
        )
        return try await withCheckedThrowingContinuation { continuation in
            store.startRun(run: run) { started, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: started?.boolValue ?? false)
                }
            }
        }
    }

    /// Claims the shared outer run before provider or tool effects begin.
    /// An existing recovery checkpoint may be resumed; terminal or mismatched
    /// rows fail closed instead of silently starting work without an owner.
    func ensureRunning(
        runId: String,
        descriptorId: String,
        descriptorVersion: String = Descriptor.version,
        conversationId: String? = nil,
        startedAt: Int64,
        inputDigest: String,
        inputSnapshotRef: String? = nil
    ) async throws -> Bool {
        if try await startRun(
            runId: runId,
            descriptorId: descriptorId,
            descriptorVersion: descriptorVersion,
            conversationId: conversationId,
            startedAt: startedAt,
            inputDigest: inputDigest,
            inputSnapshotRef: inputSnapshotRef
        ) {
            return true
        }
        guard let existing = try await snapshot(runId: runId),
              existing.descriptorId == descriptorId else {
            return false
        }
        if existing.status == .running {
            return true
        }
        if existing.status == .recoveryPending {
            return try await transition(
                runId: runId,
                expected: .recoveryPending,
                to: .running,
                inputSnapshotRef: inputSnapshotRef
            )
        }
        return false
    }

    @discardableResult
    func startChatRun(
        runId: String,
        startedAt: Int64,
        inputDigest: String,
        conversationId: String?
    ) async throws -> Bool {
        try await startRun(
            runId: runId,
            descriptorId: Descriptor.chat,
            descriptorVersion: Descriptor.chatVersion,
            conversationId: conversationId,
            startedAt: startedAt,
            inputDigest: inputDigest
        )
    }

    @discardableResult
    func transition(
        runId: String,
        expected: AgentRunStatus,
        to status: AgentRunStatus,
        inputSnapshotRef: String? = nil,
        detail: String? = nil,
        at: Int64 = Int64(Date().timeIntervalSince1970 * 1_000)
    ) async throws -> Bool {
        try await withCheckedThrowingContinuation { continuation in
            store.transitionRun(
                runId: runId,
                expectedStatus: expected,
                status: status,
                inputSnapshotRef: inputSnapshotRef,
                detail: detail,
                at: at
            ) { transitioned, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: transitioned?.boolValue ?? false)
                }
            }
        }
    }

    @discardableResult
    func transitionFromAnyActive(
        runId: String,
        to status: AgentRunStatus,
        inputSnapshotRef: String? = nil,
        detail: String? = nil,
        at: Int64 = Int64(Date().timeIntervalSince1970 * 1_000)
    ) async throws -> Bool {
        for expected in [AgentRunStatus.running, .awaitingPermission, .recoveryPending] {
            if try await transition(
                runId: runId,
                expected: expected,
                to: status,
                inputSnapshotRef: inputSnapshotRef,
                detail: detail,
                at: at
            ) {
                return true
            }
        }
        return false
    }

    func snapshot(runId: String) async throws -> Snapshot? {
        try await withCheckedThrowingContinuation { continuation in
            store.getRun(runId: runId) { run, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: run.map(Self.snapshot))
                }
            }
        }
    }

    func recoverableRuns(
        descriptorIds: [String] = Descriptor.chatRecoveryAliases
    ) async throws -> [Snapshot] {
        try await withCheckedThrowingContinuation { continuation in
            store.listRecoverableRuns(descriptorIds: descriptorIds) { runs, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: (runs ?? []).map(Self.snapshot))
                }
            }
        }
    }

    nonisolated private static func snapshot(_ run: AgentRunRecord) -> Snapshot {
        Snapshot(
            runId: run.runId,
            descriptorId: run.agentDescriptorId,
            conversationId: run.conversationId,
            status: run.status,
            inputSnapshotRef: run.inputSnapshotRef,
            startedAt: run.startedAt,
            finishedAt: run.finishedAt?.int64Value,
            detail: run.interruptedReason
        )
    }
}
