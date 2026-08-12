import XCTest
@preconcurrency import Shared
@testable import iosApp

@MainActor
final class IOSDurableRunStoreTests: XCTestCase {
    private func makeDatabase(_ name: String = #function) -> AgentRuntimeDatabase {
        let path = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(name)-\(UUID().uuidString).db")
            .path
        return IosDatabaseFactory.shared.createDatabase(atFilePath: path)
    }

    func testStartAndTerminalTransitionAreCASBound() async throws {
        let db = makeDatabase()
        let store = IOSDurableRunStore(dao: db.agentRuntimeDao())
        let runId = "typed-run-\(UUID().uuidString)"

        let didStart = try await store.startChatRun(
            runId: runId,
            startedAt: 100,
            inputDigest: "digest",
            conversationId: "conversation"
        )
        XCTAssertTrue(didStart)
        let didRestart = try await store.startChatRun(
            runId: runId,
            startedAt: 999,
            inputDigest: "late-start",
            conversationId: "other"
        )
        XCTAssertFalse(
            didRestart,
            "a duplicate start must not overwrite immutable identity"
        )

        let didComplete = try await store.transition(
            runId: runId,
            expected: .running,
            to: .completed,
            at: 200
        )
        XCTAssertTrue(didComplete)
        let staleTransition = try await store.transition(
            runId: runId,
            expected: .running,
            to: .interrupted,
            detail: "stale recovery",
            at: 300
        )
        XCTAssertFalse(staleTransition)

        let loadedSnapshot = try await store.snapshot(runId: runId)
        let snapshot = try XCTUnwrap(loadedSnapshot)
        XCTAssertTrue(snapshot.status == .completed)
        XCTAssertEqual(snapshot.startedAt, 100)
        XCTAssertEqual(snapshot.finishedAt, 200)
        XCTAssertEqual(snapshot.conversationId, "conversation")
    }

    func testRecoveryPendingRemainsActiveUntilCASSettlement() async throws {
        let db = makeDatabase()
        let store = IOSDurableRunStore(dao: db.agentRuntimeDao())
        let runId = "pending-run-\(UUID().uuidString)"
        _ = try await store.startChatRun(
            runId: runId,
            startedAt: 100,
            inputDigest: "digest",
            conversationId: "conversation"
        )

        let didMarkPending = try await store.transition(
            runId: runId,
            expected: .running,
            to: .recoveryPending,
            at: 200
        )
        XCTAssertTrue(didMarkPending)
        let loadedPending = try await store.snapshot(runId: runId)
        let pending = try XCTUnwrap(loadedPending)
        XCTAssertTrue(pending.status == .recoveryPending)
        XCTAssertNil(pending.finishedAt)

        let didInterrupt = try await store.transition(
            runId: runId,
            expected: .recoveryPending,
            to: .interrupted,
            detail: "process_killed",
            at: 300
        )
        XCTAssertTrue(didInterrupt)
        let loadedTerminal = try await store.snapshot(runId: runId)
        let terminal = try XCTUnwrap(loadedTerminal)
        XCTAssertTrue(terminal.status == .interrupted)
        XCTAssertEqual(terminal.finishedAt, 300)
    }

    func testGenericRunCanResumeOnlyItsOwnRecoveryCheckpoint() async throws {
        let db = makeDatabase()
        let store = IOSDurableRunStore(dao: db.agentRuntimeDao())
        let runId = "novel-run-\(UUID().uuidString)"
        let didStart = try await store.ensureRunning(
            runId: runId,
            descriptorId: IOSDurableRunStore.Descriptor.novelGeneration,
            startedAt: 100,
            inputDigest: "digest",
            inputSnapshotRef: "novel:project:branch"
        )
        XCTAssertTrue(didStart)
        let didCheckpoint = try await store.transition(
            runId: runId,
            expected: .running,
            to: .recoveryPending
        )
        XCTAssertTrue(didCheckpoint)
        let didResume = try await store.ensureRunning(
            runId: runId,
            descriptorId: IOSDurableRunStore.Descriptor.novelGeneration,
            startedAt: 999,
            inputDigest: "ignored",
            inputSnapshotRef: "novel:project:branch"
        )
        XCTAssertTrue(didResume)
        let loadedSnapshot = try await store.snapshot(runId: runId)
        let snapshot = try XCTUnwrap(loadedSnapshot)
        XCTAssertTrue(snapshot.status == .running)
        XCTAssertEqual(snapshot.startedAt, 100)
    }

    func testApprovalClaimReturnsToRunningBeforeSuccessfulTerminal() async throws {
        let db = makeDatabase()
        let store = IOSDurableRunStore(dao: db.agentRuntimeDao())
        let runId = "approval-run-\(UUID().uuidString)"
        _ = try await store.startChatRun(
            runId: runId,
            startedAt: 100,
            inputDigest: "digest",
            conversationId: "conversation"
        )

        let didPause = try await store.transition(
            runId: runId,
            expected: .running,
            to: .awaitingPermission,
            inputSnapshotRef: "tool_call:tool-1",
            at: 200
        )
        XCTAssertTrue(didPause)
        let directTerminal = try await store.transition(
            runId: runId,
            expected: .awaitingPermission,
            to: .completed,
            at: 250
        )
        XCTAssertFalse(directTerminal)

        let didClaim = try await store.transition(
            runId: runId,
            expected: .awaitingPermission,
            to: .running,
            inputSnapshotRef: nil,
            at: 300
        )
        XCTAssertTrue(didClaim)
        let didComplete = try await store.transition(
            runId: runId,
            expected: .running,
            to: .completed,
            at: 400
        )
        XCTAssertTrue(didComplete)

        let loadedSnapshot = try await store.snapshot(runId: runId)
        let snapshot = try XCTUnwrap(loadedSnapshot)
        XCTAssertTrue(snapshot.status == .completed)
        XCTAssertNil(snapshot.inputSnapshotRef)
        XCTAssertEqual(snapshot.finishedAt, 400)
        let recoverable = try await store.recoverableRuns()
        XCTAssertTrue(recoverable.isEmpty)
    }

    func testChatRecoveryDoesNotClaimOtherDescriptors() async throws {
        let db = makeDatabase()
        let dao = db.agentRuntimeDao()
        let store = IOSDurableRunStore(dao: dao)
        let chatRunId = "chat-run-\(UUID().uuidString)"
        let novelRunId = "novel-run-\(UUID().uuidString)"

        _ = try await store.startChatRun(
            runId: chatRunId,
            startedAt: 100,
            inputDigest: "chat",
            conversationId: "chat-conversation"
        )
        try await insertRawRun(
            dao: dao,
            runId: novelRunId,
            descriptorId: "novel",
            conversationId: "novel-project",
            status: "running",
            startedAt: 50
        )

        let loadedPairs = await IOSRunRecovery.unfinishedRunConversationPairs(runStore: store)
        let pairs = try XCTUnwrap(loadedPairs)
        XCTAssertTrue(pairs.contains { $0.runId == chatRunId })
        XCTAssertFalse(pairs.contains { $0.runId == novelRunId })

        let recoveredCount = await IOSRunRecovery.recoverInterruptedRuns(
            reason: "test_restart",
            now: 500,
            runStore: store
        )
        XCTAssertEqual(recoveredCount, 1)
        let novelStatus = await rawRunStatus(dao: dao, runId: novelRunId)
        XCTAssertEqual(novelStatus, "running")
    }

    func testPendingApprovalRecoveryIsDescriptorScoped() async throws {
        let db = makeDatabase()
        let dao = db.agentRuntimeDao()
        let store = IOSDurableRunStore(dao: dao)
        let chatRunId = "chat-approval-\(UUID().uuidString)"
        let otherRunId = "miniapp-approval-\(UUID().uuidString)"

        _ = try await store.startChatRun(
            runId: chatRunId,
            startedAt: 100,
            inputDigest: "chat",
            conversationId: "chat-conversation"
        )
        _ = try await store.transition(
            runId: chatRunId,
            expected: .running,
            to: .awaitingPermission,
            inputSnapshotRef: "tool_call:chat-tool"
        )
        try await insertRawRun(
            dao: dao,
            runId: otherRunId,
            descriptorId: "miniapp",
            conversationId: "miniapp-conversation",
            status: "awaiting_permission",
            inputSnapshotRef: "tool_call:miniapp-tool",
            startedAt: 50
        )

        let loadedRecovered = await IOSRunRecovery.recoverPendingApprovalDescriptors(runStore: store)
        let recovered = try XCTUnwrap(loadedRecovered)
        XCTAssertEqual(recovered.map(\.runId), [chatRunId])
        XCTAssertEqual(recovered.first?.toolCallId, "chat-tool")
    }

    private func insertRawRun(
        dao: AgentRuntimeDao,
        runId: String,
        descriptorId: String,
        conversationId: String?,
        status: String,
        inputSnapshotRef: String? = nil,
        startedAt: Int64
    ) async throws {
        let run = AgentRunEntity(
            runId: runId,
            parentRunId: nil,
            agentDescriptorId: descriptorId,
            agentVersion: "1",
            conversationId: conversationId,
            messageNodeId: nil,
            producesMessageId: nil,
            assistantId: nil,
            status: status,
            inputDigest: "digest",
            inputSnapshotRef: inputSnapshotRef,
            inputSchemaVersion: 1,
            startedAt: startedAt,
            finishedAt: nil,
            interruptedReason: nil
        )
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            dao.insertRunIfAbsent(run: run) { _, error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume() }
            }
        }
    }

    private func rawRunStatus(dao: AgentRuntimeDao, runId: String) async -> String? {
        await withCheckedContinuation { (continuation: CheckedContinuation<String?, Never>) in
            dao.getRun(id: runId) { run, _ in
                continuation.resume(returning: run?.status)
            }
        }
    }
}
