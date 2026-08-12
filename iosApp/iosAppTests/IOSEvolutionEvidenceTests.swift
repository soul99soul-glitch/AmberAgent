import XCTest
@preconcurrency import Shared
@testable import iosApp

/// §15 Phase 0 acceptance tests. Uses REAL components: the Room-backed
/// `IOSAgentRunLedger` actor (isolated DB via `IosDatabaseFactory.shared
/// .createDatabase(atFilePath:)`), the real `IOSSkillFileStore` /
/// `IOSSkillMcpToolService` promotion path in a temp directory, and the real
/// projector. No source-string anchors.
///
/// Acceptance covered:
///   1. a scripted tool run projects evidence whose source refs resolve back
///      to real ledger events;
///   2. promotion receipt `toHash` equals the live package hash on disk, and
///      the rollback receipt's `toHash` equals the restored live hash;
///   3. evidence summaries never carry message bodies or full tool output;
///   4. ledger payloads written before the evolution contract still decode —
///      new keys absent means nil, not a crash.
@MainActor
final class IOSEvolutionEvidenceTests: XCTestCase {
    private var tempDirs: [URL] = []

    override func tearDown() async throws {
        for dir in tempDirs {
            try? FileManager.default.removeItem(at: dir)
        }
        tempDirs.removeAll()
    }

    // MARK: - Acceptance 1: scripted run → resolvable evidence refs

    func testScriptedRunProjectsEvidenceWithResolvableSourceRefs() async throws {
        let (_, dao) = makeDatabase()
        let ledger = IOSAgentRunLedger(dao: dao)
        let runId = "ev-scripted-\(UUID().uuidString)"

        // One successful call — must NOT project per-tool evidence (§9.1).
        await ledger.recordToolCallStarted(
            runId: runId, toolCallId: "tc-ok", toolName: "search_web",
            argsDigest: chatInputDigest(for: "{}"), effectClass: .pure
        )
        await ledger.recordToolCallFinished(
            runId: runId, toolCallId: "tc-ok", outcome: "completed"
        )

        // One structured tool error carrying the evolution-contract keys.
        await ledger.recordToolCallStarted(
            runId: runId, toolCallId: "tc-fail", toolName: "scrape_web",
            argsDigest: chatInputDigest(for: "{}"), effectClass: .pure
        )
        await ledger.recordToolCallFinished(
            runId: runId, toolCallId: "tc-fail", outcome: "failed",
            artifactId: "news-digest", artifactVersion: "1.0.0",
            outcomeKind: "error", errorCode: "http_500", sourceRef: "msg:123"
        )

        // One user approval denial (§11.1 evidence source).
        await ledger.recordApprovalDenied(
            runId: runId, toolCallId: "tc-denied", toolName: "workspace_file_write",
            reason: "User denied Workspace tool access.",
            capabilityId: "ios.workspace.file_write"
        )

        // Durable terminal: the run row (same writer as ChatViewModel.recordRun).
        try await insertRun(dao: dao, runId: runId, status: "completed")

        let evidence = await IOSEvolutionEvidenceProjector.project(runId: runId, dao: dao)

        // Error evidence: structured outcome + code surfaced, tool name paired
        // from the Started row.
        let error = try XCTUnwrap(
            evidence.first { $0.observedOutcome == .error && $0.toolId == "scrape_web" },
            "scripted failed tool call must project error evidence"
        )
        XCTAssertTrue(error.redactedSummary.contains("http_500"))
        XCTAssertNil(error.userSignal, "tool error is not a user signal")

        // Denial evidence: user signal + tool name.
        let denial = try XCTUnwrap(
            evidence.first { $0.userSignal == .approvalDenied },
            "approval denial must project denial evidence"
        )
        XCTAssertEqual(denial.observedOutcome, .denied)
        XCTAssertEqual(denial.toolId, "workspace_file_write")

        // Terminal evidence from the run row.
        let terminal = try XCTUnwrap(
            evidence.first { $0.id == "ev:terminal:\(runId)" },
            "terminal status must project terminal evidence"
        )
        XCTAssertEqual(terminal.observedOutcome, .success)
        XCTAssertTrue(terminal.sourceRefs.contains(IOSEvidenceRef(kind: .agentRun, id: runId)))

        // No per-tool success noise.
        XCTAssertFalse(evidence.contains { $0.toolId == "search_web" })

        // Every ledgerEvent source ref resolves back to a real ledger event
        // (acceptance 1).
        let eventIds = await eventIds(dao: dao, runId: runId)
        for ref in evidence.flatMap(\.sourceRefs) where ref.kind == .ledgerEvent {
            XCTAssertTrue(eventIds.contains(ref.id), "sourceRef \(ref.id) must resolve to a ledger event")
        }
        XCTAssertFalse(eventIds.isEmpty, "scripted run must have ledger events")

        // Evidence ids are deterministic across re-projection.
        let reprojected = await IOSEvolutionEvidenceProjector.project(runId: runId, dao: dao)
        XCTAssertEqual(Set(evidence.map(\.id)), Set(reprojected.map(\.id)))
    }

    // MARK: - Acceptance 2: promotion/rollback receipt hash matches live package

    func testSkillPromotionReceiptToHashEqualsLivePackageHashAndRollbackClears() async throws {
        let root = tempRoot()
        let skillStore = IOSSkillFileStore(baseDirectory: root)
        let receiptStore = IOSPromotionReceiptStore(baseDirectory: root)
        let name = "ev-receipt-skill"
        let markdown = skillMarkdown(name: name, description: "candidate v1")

        let service = try makeSkillService(root: root)
        let prepared = try await writeAndPrepareSkillImport(
            service: service, name: name, markdown: markdown
        )
        let imported = try service.applyPreparedSkillImport(prepared)
        XCTAssertTrue(imported.contains(#""success":true"#), imported)
        XCTAssertEqual(prepared.preview.kind, .new)

        let snapshot = try XCTUnwrap(receiptStore.snapshot(artifactId: name))
        let receipt = try XCTUnwrap(snapshot.active)
        XCTAssertNil(receipt.fromHash, "new import has no previous live hash")
        XCTAssertEqual(receipt.approvedBy, "user")
        XCTAssertNil(receipt.evaluationReportHash)
        XCTAssertNil(receipt.catalogRevision)
        // Acceptance 2: toHash == the actual live package hash on disk
        // (re-derived through the store's own installed-package read).
        let live = try XCTUnwrap(
            try skillStore.prepareSkillPackage(
                importedFiles: ["SKILL.md": Data(markdown.utf8)], mergeExisting: false
            ).base,
            "applied skill must exist on disk"
        )
        XCTAssertEqual(receipt.toHash, live.hash)
        XCTAssertEqual(receipt.toHash, prepared.preview.candidateHash)

        // Rollback of a "new" import removes the artifact → receipts cleared.
        guard case .available(let manifest) = try skillStore.rollbackAvailability(name: name) else {
            return XCTFail("new import must publish a rollback manifest")
        }
        _ = try skillStore.rollbackSkillPackage(name: name, expectedManifest: manifest)
        XCTAssertNil(
            receiptStore.snapshot(artifactId: name),
            "rolled-back (removed) artifact must not keep receipts"
        )
    }

    func testSkillRollbackReceiptToHashEqualsRestoredLiveHash() async throws {
        let root = tempRoot()
        let skillStore = IOSSkillFileStore(baseDirectory: root)
        let receiptStore = IOSPromotionReceiptStore(baseDirectory: root)
        let name = "ev-rollback-skill"

        let service = try makeSkillService(root: root)

        // v1 import (new) — establishes base.
        let v1 = skillMarkdown(name: name, description: "v1 base")
        let preparedV1 = try await writeAndPrepareSkillImport(service: service, name: name, markdown: v1)
        XCTAssertTrue(try service.applyPreparedSkillImport(preparedV1).contains(#""success":true"#))

        // v2 import (update) — promotion receipt: fromHash = v1, toHash = v2.
        let v2 = skillMarkdown(name: name, description: "v2 candidate")
        let preparedV2 = try await writeAndPrepareSkillImport(service: service, name: name, markdown: v2)
        XCTAssertEqual(preparedV2.preview.kind, .update)
        XCTAssertTrue(try service.applyPreparedSkillImport(preparedV2).contains(#""success":true"#))

        let afterPromotion = try XCTUnwrap(receiptStore.snapshot(artifactId: name))
        let promotion = try XCTUnwrap(afterPromotion.active)
        XCTAssertEqual(promotion.fromHash, preparedV1.preview.candidateHash)
        XCTAssertEqual(promotion.toHash, preparedV2.preview.candidateHash)
        XCTAssertEqual(
            afterPromotion.previous?.toHash, preparedV1.preview.candidateHash,
            "active+previous: the old active receipt stays as previous"
        )

        // Rollback → receipt whose toHash is the restored live hash.
        guard case .available(let manifest) = try skillStore.rollbackAvailability(name: name) else {
            return XCTFail("update import must publish a rollback manifest")
        }
        _ = try skillStore.rollbackSkillPackage(name: name, expectedManifest: manifest)

        let restored = try XCTUnwrap(
            try skillStore.prepareSkillPackage(
                importedFiles: ["SKILL.md": Data(v1.utf8)], mergeExisting: false
            ).base,
            "rolled-back skill must exist on disk"
        )
        let afterRollback = try XCTUnwrap(receiptStore.snapshot(artifactId: name))
        let rollbackReceipt = try XCTUnwrap(afterRollback.active)
        XCTAssertEqual(rollbackReceipt.fromHash, manifest.promotedHash)
        XCTAssertEqual(rollbackReceipt.toHash, manifest.baseHash)
        XCTAssertEqual(rollbackReceipt.toHash, restored.hash, "rollback toHash must equal restored live hash")
    }

    // MARK: - Acceptance 3: no message bodies in evidence

    func testEvidenceNeverCarriesMessageBodiesOrFullToolOutput() async throws {
        let (_, dao) = makeDatabase()
        let ledger = IOSAgentRunLedger(dao: dao)
        let runId = "ev-privacy-\(UUID().uuidString)"

        let userBody = "user message body " + String(repeating: "隐私正文", count: 300)
        let fullToolOutput = String(repeating: "full tool result payload ", count: 300)

        await ledger.recordToolCallStarted(
            runId: runId, toolCallId: "tc-1", toolName: "scrape_web",
            argsDigest: chatInputDigest(for: userBody), effectClass: .pure
        )
        // A pathological error code attempting to smuggle body-sized content.
        await ledger.recordToolCallFinished(
            runId: runId, toolCallId: "tc-1", outcome: "failed",
            outcomeKind: "error", errorCode: fullToolOutput, sourceRef: nil
        )
        await ledger.recordApprovalDenied(
            runId: runId, toolCallId: "tc-2", toolName: "workspace_file_write",
            reason: "User denied Workspace tool access.", capabilityId: nil
        )
        try await insertRun(dao: dao, runId: runId, status: "interrupted")

        let evidence = await IOSEvolutionEvidenceProjector.project(runId: runId, dao: dao)
        XCTAssertFalse(evidence.isEmpty)
        for item in evidence {
            XCTAssertLessThan(item.redactedSummary.count, 400, "summary must stay short")
            XCTAssertFalse(item.redactedSummary.contains(userBody), "user body must never reach evidence")
            XCTAssertFalse(item.redactedSummary.contains("隐私正文"), "user body content must never reach evidence")
            XCTAssertFalse(
                item.redactedSummary.contains(fullToolOutput),
                "full tool output must never reach evidence (only a truncated prefix may)"
            )
        }
    }

    // MARK: - Acceptance 4: pre-contract payloads still decode

    func testLegacyPayloadWithoutEvolutionKeysStillDecodes() async throws {
        let (_, dao) = makeDatabase()
        let ledger = IOSAgentRunLedger(dao: dao)
        let runId = "ev-legacy-\(UUID().uuidString)"

        await ledger.recordToolCallStarted(
            runId: runId, toolCallId: "tc-old", toolName: "search_web",
            argsDigest: chatInputDigest(for: "{}"), effectClass: .pure
        )
        // Simulate a `tool_call_finished` row written BEFORE the evolution
        // contract: the old ledger wrote exactly `{"toolCallId":…,"outcome":…}`
        // (sorted keys, same as `IOSAgentRunLedger.jsonPayload`).
        try await insertEvent(
            dao: dao, runId: runId, seq: 2, type: "tool_call_finished",
            payload: #"{"outcome":"failed","toolCallId":"tc-old"}"#
        )
        try await insertRun(dao: dao, runId: runId, status: "completed")

        let evidence = await IOSEvolutionEvidenceProjector.project(runId: runId, dao: dao)

        // The old failed row still projects (plain `outcome` mapping), with the
        // new keys absent → nil, and no crash.
        let error = try XCTUnwrap(
            evidence.first { $0.observedOutcome == .error },
            "legacy failed row must still project error evidence"
        )
        XCTAssertEqual(error.toolId, "search_web", "tool name paired from the Started row")
        XCTAssertNil(error.userSignal)
        XCTAssertFalse(error.redactedSummary.contains("outcomeKind"))
        XCTAssertFalse(error.redactedSummary.contains("errorCode"))

        XCTAssertTrue(evidence.contains { $0.id == "ev:terminal:\(runId)" })

        // The W3 recovery reader must also still decode the legacy row.
        let rows: [IOSToolCallLedgerRow]? = await withCheckedContinuation { continuation in
            dao.listEventsForRun(id: runId) { result, error in
                guard error == nil, let result else {
                    continuation.resume(returning: nil)
                    return
                }
                continuation.resume(returning: result.compactMap {
                    IOSToolCallLedgerRow.decode(type: $0.type, seq: $0.seq, payload: $0.payload)
                })
            }
        }
        XCTAssertEqual(rows?.first { $0.toolCallId == "tc-old" && $0.type == IOSToolCallLedgerClassifier.finishedType }?.outcome, "failed")
    }

    // MARK: - Recent-window projection

    func testRecentWindowProjectionReturnsScriptedRunEvidence() async throws {
        let (_, dao) = makeDatabase()
        let ledger = IOSAgentRunLedger(dao: dao)
        let runId = "ev-recent-\(UUID().uuidString)"
        let before = Int64(Date().timeIntervalSince1970 * 1000)

        await ledger.recordToolCallStarted(
            runId: runId, toolCallId: "tc-1", toolName: "search_web",
            argsDigest: chatInputDigest(for: "{}"), effectClass: .pure
        )
        await ledger.recordToolCallFinished(
            runId: runId, toolCallId: "tc-1", outcome: "completed"
        )
        try await insertRun(dao: dao, runId: runId, status: "completed")

        let evidence = await IOSEvolutionEvidenceProjector.projectRecent(
            sinceEpochMs: before - 1, dao: dao
        )
        XCTAssertTrue(
            evidence.contains { $0.runId == runId && $0.id == "ev:terminal:\(runId)" },
            "recent window must include the scripted run's terminal evidence"
        )
    }

    // MARK: - Fixtures

    private func makeDatabase() -> (db: AgentRuntimeDatabase, dao: AgentRuntimeDao) {
        let root = tempRoot()
        let path = root.appendingPathComponent("agent_runtime.db").path
        let db = IosDatabaseFactory.shared.createDatabase(atFilePath: path)
        return (db, db.agentRuntimeDao())
    }

    private func insertRun(
        dao: AgentRuntimeDao,
        runId: String,
        status: String
    ) async throws {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let run = AgentRunEntity(
            runId: runId,
            parentRunId: nil,
            agentDescriptorId: "chat",
            agentVersion: "1",
            conversationId: nil,
            messageNodeId: nil,
            producesMessageId: nil,
            assistantId: nil,
            status: status,
            inputDigest: "ev-test",
            inputSnapshotRef: nil,
            inputSchemaVersion: 1,
            startedAt: now,
            finishedAt: status == "running" ? nil : KotlinLong(value: now),
            interruptedReason: status == "interrupted" ? "user_cancelled" : nil
        )
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            dao.insertRunIfAbsent(run: run) { _, error in
                if let error { cont.resume(throwing: error) }
                else { cont.resume() }
            }
        }
    }

    private func insertEvent(
        dao: AgentRuntimeDao,
        runId: String,
        seq _: Int64,
        type: String,
        payload: String
    ) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            dao.insertRunEvent(
                runId: runId,
                eventId: UUID().uuidString,
                type: type,
                payloadType: type,
                payload: payload,
                payloadSchemaVersion: 1,
                isFinal: false,
                ts: Int64(Date().timeIntervalSince1970 * 1_000)
            ) { _, error in
                if let error { cont.resume(throwing: error) }
                else { cont.resume() }
            }
        }
    }

    private func eventIds(dao: AgentRuntimeDao, runId: String) async -> Set<String> {
        await withCheckedContinuation { continuation in
            dao.listEventsForRun(id: runId) { result, error in
                guard error == nil, let result else {
                    continuation.resume(returning: [])
                    return
                }
                continuation.resume(returning: Set(result.map(\.eventId)))
            }
        }
    }

    private func makeSkillService(root: URL) throws -> IOSSkillMcpToolService {
        let defaults = UserDefaults(suiteName: "ev-skill-\(UUID().uuidString)")!
        let settings = IOSSharedSettingsStore(userDefaults: defaults)
        let workspace = IOSWorkspaceStore(
            baseDirectory: tempRoot().appendingPathComponent("workspace", isDirectory: true)
        )
        let mcpDefaults = UserDefaults(suiteName: "ev-skill-mcp-\(UUID().uuidString)")!
        let mcpStore = IOSMcpConfigStore(userDefaults: mcpDefaults)
        let mcpManager = IOSMcpManager(sharedSettings: settings, configStore: mcpStore)
        return IOSSkillMcpToolService(
            skillStore: IOSSkillFileStore(baseDirectory: root),
            sharedSettings: settings,
            workspaceStore: workspace,
            mcpConfigStore: mcpStore,
            mcpManager: mcpManager
        )
    }

    private func writeAndPrepareSkillImport(
        service: IOSSkillMcpToolService,
        name: String,
        markdown: String
    ) async throws -> IOSPreparedSkillImport {
        let writeInput = try JSONSerialization.data(
            withJSONObject: [
                "path": "/workspace/\(name)/SKILL.md",
                "content": markdown,
                "overwrite": true,
            ] as [String: Any],
            options: []
        )
        let workspaceResult = await service.workspaceStore.executeTool(
            toolName: "workspace_file_write",
            input: String(data: writeInput, encoding: .utf8) ?? "{}"
        )
        XCTAssertTrue(workspaceResult.contains(#""ok":true"#), workspaceResult)
        return try service.prepareSkillImport(
            arguments: #"{"workspace_path":"/workspace/\#(name)/SKILL.md"}"#
        )
    }

    private func skillMarkdown(name: String, description: String) -> String {
        """
        ---
        name: \(name)
        description: \(description)
        ---

        # \(description)
        """
    }

    private func tempRoot() -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ios-evolution-evidence-\(UUID().uuidString)", isDirectory: true)
        try! FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        tempDirs.append(url)
        return url
    }
}
