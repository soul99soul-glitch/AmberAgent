import XCTest
@preconcurrency import Shared
@testable import iosApp

@MainActor
final class IOSRecipeExperienceFeedbackTests: XCTestCase {
    private var tempRoots: [URL] = []
    private var databases: [AgentRuntimeDatabase] = []

    override func tearDown() async throws {
        databases.removeAll()
        for root in tempRoots {
            try? FileManager.default.removeItem(at: root)
        }
        tempRoots.removeAll()
        try await super.tearDown()
    }

    func testHelpfulRequiresExactVersionSuccessfulExecution() async throws {
        let (root, dao, ledger) = makeEnvironment()
        let store = IOSEvolutionExperienceStore(baseDirectory: root)
        let service = IOSRecipeExperienceFeedbackService(dao: dao, ledger: ledger, store: store)

        let outcome = await service.record(
            .helpful,
            recipeName: "note_writer",
            version: "1.0.0",
            description: "写笔记"
        )

        guard case .unavailable = outcome else {
            return XCTFail("没有真实执行记录时必须拒绝 helpful，got \(outcome)")
        }
        XCTAssertTrue(try store.allExperiences().isEmpty)
    }

    func testHelpfulCreatesThenUpdatesSameVersionExperience() async throws {
        let (root, dao, ledger) = makeEnvironment()
        await insertSuccessfulRecipeRun(
            dao: dao,
            ledger: ledger,
            runId: "run-note-v1",
            artifactId: "recipe__note_writer",
            version: "1.0.0"
        )
        let store = IOSEvolutionExperienceStore(baseDirectory: root)
        let service = IOSRecipeExperienceFeedbackService(dao: dao, ledger: ledger, store: store)

        let first = await service.record(
            .helpful,
            recipeName: "note_writer",
            version: "1.0.0",
            description: "把输入写成笔记并复制到输出目录"
        )
        guard case .recorded(let firstReceipt) = first,
              let experienceId = firstReceipt.experienceId else {
            return XCTFail("第一次 helpful 必须创建 Experience，got \(first)")
        }

        let second = await service.record(
            .helpful,
            recipeName: "note_writer",
            version: "1.0.0",
            description: "把输入写成笔记并复制到输出目录"
        )
        guard case .recorded(let secondReceipt) = second else {
            return XCTFail("第二次 helpful 必须更新 Experience，got \(second)")
        }

        XCTAssertEqual(secondReceipt.experienceId, experienceId)
        XCTAssertEqual(try store.allExperiences().count, 1)
        let stored = try XCTUnwrap(store.experience(id: experienceId))
        XCTAssertEqual(stored.helpfulCount, 2)
        XCTAssertEqual(stored.sourceArtifactId, "recipe__note_writer")
        XCTAssertEqual(stored.sourceArtifactVersion, "1.0.0")
        XCTAssertTrue(stored.evidenceRefs.contains(.init(kind: .agentRun, id: "run-note-v1")))
        XCTAssertTrue(stored.ruleText.contains("recipe__note_writer@1.0.0"))
    }

    func testHarmfulTargetsExactVersionAndOnlyProducesSuggestion() async throws {
        let (root, dao, ledger) = makeEnvironment()
        await insertSuccessfulRecipeRun(
            dao: dao,
            ledger: ledger,
            runId: "run-note-v1",
            artifactId: "recipe__note_writer",
            version: "1.0.0"
        )
        await insertSuccessfulRecipeRun(
            dao: dao,
            ledger: ledger,
            runId: "run-note-v2",
            artifactId: "recipe__note_writer",
            version: "2.0.0"
        )
        let store = IOSEvolutionExperienceStore(baseDirectory: root)
        let service = IOSRecipeExperienceFeedbackService(dao: dao, ledger: ledger, store: store)

        let v1 = await service.record(
            .helpful,
            recipeName: "note_writer",
            version: "1.0.0",
            description: "写笔记"
        )
        let v2 = await service.record(
            .helpful,
            recipeName: "note_writer",
            version: "2.0.0",
            description: "写笔记"
        )
        guard case .recorded(let v1Receipt) = v1,
              case .recorded(let v2Receipt) = v2,
              let v1Id = v1Receipt.experienceId,
              let v2Id = v2Receipt.experienceId else {
            return XCTFail("两个版本都应先形成独立 Experience")
        }
        let v1Before = try XCTUnwrap(store.experience(id: v1Id))

        var finalSuggestion: IOSExperienceActionSuggestion?
        for _ in 0..<3 {
            let outcome = await service.record(
                .harmful,
                recipeName: "note_writer",
                version: "2.0.0",
                description: "写笔记"
            )
            guard case .recorded(let receipt) = outcome else {
                return XCTFail("harmful 必须被记录，got \(outcome)")
            }
            finalSuggestion = receipt.suggestion ?? finalSuggestion
        }

        XCTAssertEqual(try store.experience(id: v1Id), v1Before, "v2 负反馈不得污染 v1")
        let v2After = try XCTUnwrap(store.experience(id: v2Id))
        XCTAssertEqual(v2After.harmfulCount, 3)
        XCTAssertEqual(v2After.status, .active, "建议未经批准不得自动停用")
        XCTAssertEqual(finalSuggestion?.kind, .supersede)
        XCTAssertEqual(finalSuggestion?.experienceId, v2Id)
    }

    private func makeEnvironment() -> (URL, AgentRuntimeDao, IOSAgentRunLedger) {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("recipe-feedback-\(UUID().uuidString)", isDirectory: true)
        try! FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        tempRoots.append(root)
        let db = IosDatabaseFactory.shared.createDatabase(
            atFilePath: root.appendingPathComponent("agent_runtime.db").path
        )
        databases.append(db)
        let dao = db.agentRuntimeDao()
        return (root, dao, IOSAgentRunLedger(dao: dao))
    }

    private func insertSuccessfulRecipeRun(
        dao: AgentRuntimeDao,
        ledger: IOSAgentRunLedger,
        runId: String,
        artifactId: String,
        version: String
    ) async {
        let now = Int64(Date().timeIntervalSince1970 * 1_000)
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
            inputDigest: "digest",
            inputSnapshotRef: nil,
            inputSchemaVersion: 1,
            startedAt: now - 1_000,
            finishedAt: KotlinLong(value: now),
            interruptedReason: nil
        )
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            dao.insertRun(run: run) { _ in continuation.resume() }
        }
        await ledger.recordToolCallFinished(
            runId: runId,
            toolCallId: "recipe-level-\(UUID().uuidString)",
            outcome: "completed",
            artifactId: artifactId,
            artifactVersion: version,
            outcomeKind: "success",
            errorCode: nil,
            sourceRef: UUID().uuidString
        )
    }
}
