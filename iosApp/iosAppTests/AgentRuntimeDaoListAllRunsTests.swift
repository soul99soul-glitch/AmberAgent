import XCTest
@preconcurrency import Shared
@testable import iosApp

/// [Slice 5] Verifies the AgentRuntimeDao.listAllRuns() read path that the
/// AccountView heatmap depends on. The DAO method is new (AgentRuntimeDao.kt:42).
/// Writes a run via the same insertRun path ChatViewModel.recordRun uses, then
/// asserts listAllRuns returns it with a usable startedAt for day-bucketing.
///
/// These tests gate Slice 5's "AccountView 用量热力图真接" without needing UI
/// taps or a real API key (no chat run required — we insert directly).
@MainActor
final class AgentRuntimeDaoListAllRunsTests: XCTestCase {

    func testListAllRunsReturnsInsertedRun() async throws {
        let db = IosDatabaseFactory.shared.createDatabase()
        let dao = db.agentRuntimeDao()

        let runId = "slice5-test-\(UUID().uuidString)"
        let startedAt = Int64(Date().timeIntervalSince1970 * 1000)
        let run = AgentRunEntity(
            runId: runId,
            parentRunId: nil,
            agentDescriptorId: "chat",
            agentVersion: "1",
            conversationId: nil,
            messageNodeId: nil,
            producesMessageId: nil,
            assistantId: nil,
            status: "completed",
            inputDigest: "test-digest",
            inputSnapshotRef: nil,
            inputSchemaVersion: 1,
            startedAt: startedAt,
            finishedAt: KotlinLong(value: startedAt),
            interruptedReason: nil
        )

        // Insert via the same path as ChatViewModel.recordRun.
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            dao.insertRun(run: run) { error in
                if let error { cont.resume(throwing: error) }
                else { cont.resume() }
            }
        }

        // Read via the new listAllRuns. Bucket in the callback to avoid
        // crossing isolation with non-Sendable [AgentRunEntity].
        let calendar = Calendar.current
        let todayBucket = await withCheckedContinuation { (cont: CheckedContinuation<Int, Never>) in
            dao.listAllRuns { result, _ in
                let runs = result ?? []
                let today = calendar.startOfDay(for: Date())
                let count = runs.filter {
                    let date = Date(timeIntervalSince1970: TimeInterval($0.startedAt) / 1000.0)
                    return calendar.startOfDay(for: date) == today
                }.count
                cont.resume(returning: count)
            }
        }

        XCTAssertGreaterThanOrEqual(
            todayBucket,
            1,
            "listAllRuns must return the run we just inserted, bucketed to today"
        )
    }

}
