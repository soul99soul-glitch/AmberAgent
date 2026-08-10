import XCTest
@testable import iosApp

class NovelPolishTestCase: XCTestCase {
    let now = Date(timeIntervalSince1970: 1_700_500_000)
    struct Harness {
        let repository: any NovelProjectPersisting
        let adapter: ScriptedNovelModelAdapter
        let creation: DefaultNovelCreation
        let projectID: NovelProjectID
        let sourceVersionID: NovelChapterVersionID
        let sourceContent: String
        let polishedContent: String
    }

    struct CanonicalEvidence: Equatable {
        let eventsSHA256: String
        let stateSummarySHA256: String
        let stateSnapshotID: NovelStateSnapshotID
    }

    struct RetryablePolishFixture {
        let command: NovelAdoptPolishCandidateCommand
        let candidateID: NovelCandidateID
        let document: NovelProjectDocumentV1
    }

    var resolvedModel: NovelResolvedModel {
        NovelResolvedModel(
            providerID: "polish-provider",
            ownerProviderID: "polish-owner",
            modelID: "polish-model-id",
            wireModelID: "polish-model-wire",
            displayName: "Polish Model",
            contextWindowTokens: 128_000
        )
    }

    var compatibleDriftJSON: String {
        """
        {
          "schemaVersion": 1,
          "compatible": true,
          "differences": []
        }
        """
    }

    var incompatibleDriftJSON: String {
        """
        {
          "schemaVersion": 1,
          "compatible": false,
          "differences": [{
            "id": "ending-changed",
            "category": "ending",
            "summary": "The candidate opens the sealed gate.",
            "sourceEvidence": "The gate remained sealed.",
            "candidateEvidence": "The gate opened."
          }]
        }
        """
    }

    var manualRebuildJSON: String {
        """
        {
          "schemaVersion": 1,
          "stateSummary": "Mara has left the sealed gate behind.",
          "branchOutline": "Mara continues toward the archive.",
          "events": [],
          "characterStates": [],
          "relationships": [],
          "foreshadowing": [],
          "unresolvedEntityNames": ["Mara"],
          "settingProposals": []
        }
        """
    }

    func makeHarness(
        repository suppliedRepository: (any NovelProjectPersisting)? = nil,
        remainingScripts: [NovelModelScript],
        polishAssessmentTimeout: TimeInterval = 1
    ) async throws -> Harness {
        let fixture = try documentWithChapterAndState()
        let polishedContent = "Mara crossed the courtyard. The sealed gate stayed behind her."
        let generation = NovelModelScript(steps: [
            .delta("\(polishedContent)\n\(NovelPromptCatalog.polishCompletionSentinel)"),
            .complete,
        ])
        let repository: any NovelProjectPersisting = suppliedRepository ??
            InMemoryNovelProjectRepository()
        _ = try await repository.createProject(fixture.document)
        let adapter = ScriptedNovelModelAdapter(
            resolvedModel: resolvedModel,
            scripts: [generation] + remainingScripts
        )
        return Harness(
            repository: repository,
            adapter: adapter,
            creation: DefaultNovelCreation(
                repository: repository,
                modelRunner: adapter,
                polishAssessmentTimeout: polishAssessmentTimeout,
                now: { Date(timeIntervalSince1970: 1_700_500_000) }
            ),
            projectID: fixture.document.project.id,
            sourceVersionID: fixture.sourceVersionID,
            sourceContent: fixture.sourceContent,
            polishedContent: polishedContent
        )
    }

    func documentWithChapterAndState() throws -> (
        document: NovelProjectDocumentV1,
        sourceVersionID: NovelChapterVersionID,
        sourceContent: String
    ) {
        var document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let branch = document.branches[0]
        let chapterID = NovelChapterID()
        let versionID = NovelChapterVersionID()
        let sourceContent = "Mara crossed the courtyard. The sealed gate remained behind her."
        let operationID = document.appliedOperations[0].operationID
        let eventID = NovelEventID()

        document.chapters.append(NovelChapterRecord(id: chapterID, createdAt: now))
        document.chapterVersions.append(NovelChapterVersionRecord(
            id: versionID,
            chapterID: chapterID,
            kind: .collected,
            title: "Chapter One",
            content: sourceContent,
            factCompatibilityID: UUID(),
            sourceCandidateID: nil,
            createdAt: now,
            operationID: operationID
        ))
        document.events.append(NovelStoryEventRecord(
            id: eventID,
            sequence: 0,
            kind: "departure",
            summary: "Mara leaves the sealed gate behind.",
            entityReferences: ["Mara"],
            createdAt: now
        ))
        let stateIndex = try XCTUnwrap(document.stateSnapshots.firstIndex {
            $0.id == branch.currentStateSnapshotID
        })
        let state = document.stateSnapshots[stateIndex]
        document.stateSnapshots[stateIndex] = NovelStateSnapshotRecord(
            id: state.id,
            eventIDs: [eventID],
            summary: "Mara has left the sealed gate behind.",
            branchOutline: "Mara continues toward the archive.",
            unresolvedEntityNames: ["Mara"],
            createdAt: state.createdAt,
            settingProposalIDs: state.settingProposalIDs
        )
        let selection = NovelChapterSelection(chapterID: chapterID, versionID: versionID)
        let checkpointIndex = try XCTUnwrap(document.checkpoints.firstIndex {
            $0.id == branch.headCheckpointID
        })
        let checkpoint = document.checkpoints[checkpointIndex]
        document.checkpoints[checkpointIndex] = NovelBranchCheckpointRecord(
            id: checkpoint.id,
            kind: checkpoint.kind,
            createdOnBranchID: checkpoint.createdOnBranchID,
            parentCheckpointID: checkpoint.parentCheckpointID,
            chapterSelections: [selection],
            stateSnapshotID: checkpoint.stateSnapshotID,
            sessionCursor: checkpoint.sessionCursor,
            branchOverrideRevisionIDs: checkpoint.branchOverrideRevisionIDs,
            sourceCandidateID: checkpoint.sourceCandidateID,
            baseHeadRevision: checkpoint.baseHeadRevision,
            operationID: checkpoint.operationID,
            createdAt: checkpoint.createdAt
        )
        document.branches[0].workingChapterSelections = [selection]
        try NovelDocumentValidator.validate(document)
        return (document, versionID, sourceContent)
    }

    func document(in harness: Harness) async throws -> NovelProjectDocumentV1 {
        try await harness.repository.loadProject(id: harness.projectID).document
    }

    func generatePolish(
        in harness: Harness,
        document suppliedDocument: NovelProjectDocumentV1? = nil
    ) async throws -> NovelCandidateID {
        let current: NovelProjectDocumentV1
        if let suppliedDocument {
            current = suppliedDocument
        } else {
            current = try await document(in: harness)
        }
        let branch = current.branches[0]
        let candidateID = NovelCandidateID()
        let request = NovelRunRequest(
            id: NovelRunID(),
            operationID: NovelOperationID(),
            projectID: current.project.id,
            branchID: branch.id,
            kind: .polish,
            mode: .writeProse,
            granularity: nil,
            userText: "Polish this complete chapter without changing its story.",
            userMessageID: NovelMessageID(),
            assistantMessageID: NovelMessageID(),
            candidateID: candidateID,
            generationReceiptID: NovelReceiptID(),
            injectionReceiptID: NovelReceiptID(),
            sourceChapterVersionID: harness.sourceVersionID,
            inputBudgetTokens: 16_000,
            expectedProjectRevision: current.project.revision,
            expectedConfigRevision: current.project.configRevision,
            expectedBranchHeadRevision: branch.headRevision
        )
        let run = try await harness.creation.start(request)
        var completed: NovelSessionMessageSnapshot?
        for await event in run.events {
            switch event {
            case .completed(let message):
                completed = message
            case .failed(let failure), .persistenceBlocked(let failure):
                XCTFail("Polish generation failed: \(failure)")
            case .started, .delta, .replaced, .reasoningDelta, .interrupted:
                break
            }
        }
        let message = try XCTUnwrap(completed)
        XCTAssertEqual(message.message.kind, .polishCandidate)
        XCTAssertEqual(message.message.candidateID, candidateID)
        return candidateID
    }

    func makeRetryablePolish(in harness: Harness) async throws -> RetryablePolishFixture {
        let candidateID = try await generatePolish(in: harness)
        let generated = try await document(in: harness)
        let command = adoptionCommand(document: generated, candidateID: candidateID)
        await NovelXCTAssertThrowsErrorAsync(
            try await harness.creation.perform(.adoptPolishCandidate(command))
        )
        let retryable = try await document(in: harness)
        XCTAssertEqual(retryable.polishTransactions.first?.status, .retryable)
        return RetryablePolishFixture(
            command: command,
            candidateID: candidateID,
            document: retryable
        )
    }

    func adoptionCommand(
        document: NovelProjectDocumentV1,
        candidateID: NovelCandidateID,
        operationID: NovelOperationID = NovelOperationID(),
        transactionID: NovelPendingOperationID = NovelPendingOperationID(),
        proposedVersionID: NovelChapterVersionID = NovelChapterVersionID(),
        checkpointID: NovelCheckpointID = NovelCheckpointID()
    ) -> NovelAdoptPolishCandidateCommand {
        NovelAdoptPolishCandidateCommand(
            context: mutationContext(document: document, operationID: operationID),
            projectID: document.project.id,
            branchID: document.branches[0].id,
            transactionID: transactionID,
            candidateID: candidateID,
            proposedChapterVersionID: proposedVersionID,
            checkpointID: checkpointID,
            expectedWorkingRevision: document.branches[0].workingRevision
        )
    }

    func restoreCommand(
        document: NovelProjectDocumentV1,
        targetVersionID: NovelChapterVersionID
    ) -> NovelRestoreChapterVersionCommand {
        NovelRestoreChapterVersionCommand(
            context: mutationContext(document: document),
            projectID: document.project.id,
            branchID: document.branches[0].id,
            targetChapterVersionID: targetVersionID,
            proposedChapterVersionID: NovelChapterVersionID(),
            checkpointID: NovelCheckpointID(),
            expectedWorkingRevision: document.branches[0].workingRevision
        )
    }

    func mutationContext(
        document: NovelProjectDocumentV1,
        operationID: NovelOperationID = NovelOperationID()
    ) -> NovelMutationContext {
        NovelMutationContext(
            operationID: operationID,
            expectedProjectRevision: document.project.revision,
            expectedConfigRevision: document.project.configRevision,
            expectedBranchHeadRevision: document.branches[0].headRevision
        )
    }

    func sourceVersion(
        in document: NovelProjectDocumentV1,
        id: NovelChapterVersionID
    ) throws -> NovelChapterVersionRecord {
        try XCTUnwrap(document.chapterVersions.first { $0.id == id })
    }

    func canonicalEvidence(in document: NovelProjectDocumentV1) throws -> CanonicalEvidence {
        let branch = document.branches[0]
        let state = try XCTUnwrap(document.stateSnapshots.first {
            $0.id == branch.currentStateSnapshotID
        })
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let events = try encoder.encode(document.events).base64EncodedString()
        return CanonicalEvidence(
            eventsSHA256: NovelDocumentValidator.sha256(events),
            stateSummarySHA256: NovelDocumentValidator.sha256(state.summary),
            stateSnapshotID: state.id
        )
    }

    func assertFailClosed(
        _ failed: NovelProjectDocumentV1,
        baseline: NovelProjectDocumentV1,
        candidateID: NovelCandidateID,
        expectedCode: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws {
        let transaction = try XCTUnwrap(failed.polishTransactions.first, file: file, line: line)
        let assessment = try XCTUnwrap(failed.polishAssessments.first, file: file, line: line)
        XCTAssertEqual(transaction.status, .retryable, file: file, line: line)
        XCTAssertEqual(transaction.attemptCount, 1, file: file, line: line)
        XCTAssertEqual(transaction.lastFailure?.code, expectedCode, file: file, line: line)
        XCTAssertEqual(transaction.lastFailure?.isRetryable, true, file: file, line: line)
        XCTAssertEqual(assessment.failure?.code, expectedCode, file: file, line: line)
        XCTAssertNil(assessment.result, file: file, line: line)
        XCTAssertEqual(
            failed.candidates.first { $0.id == candidateID }?.status,
            .available,
            file: file,
            line: line
        )
        XCTAssertEqual(failed.chapterVersions, baseline.chapterVersions, file: file, line: line)
        XCTAssertEqual(failed.checkpoints, baseline.checkpoints, file: file, line: line)
        XCTAssertEqual(failed.events, baseline.events, file: file, line: line)
        XCTAssertEqual(failed.stateSnapshots, baseline.stateSnapshots, file: file, line: line)
        XCTAssertFalse(
            failed.appliedOperations.contains { $0.operationID == transaction.operationID },
            file: file,
            line: line
        )
    }

    func receiptByChangingJSON(
        _ receipt: NovelInjectionReceiptRecord,
        change: (inout [String: Any]) -> Void
    ) throws -> NovelInjectionReceiptRecord {
        let data = try JSONEncoder().encode(receipt)
        guard var object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw NovelError.invalidInput("Receipt JSON fixture is not an object.")
        }
        change(&object)
        return try JSONDecoder().decode(
            NovelInjectionReceiptRecord.self,
            from: JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        )
    }

    func exactDriftPlan(
        source: NovelChapterVersionRecord,
        candidate: NovelCandidateRecord,
        maxEstimatedInputTokens: Int
    ) -> NovelInjectionPlan {
        let prompt = NovelPromptCatalog.template(for: .polishDriftV1)
        let sections = driftMessages(source: source.content, candidate: candidate.content).map {
            message in
            let isSystem = message.role == .system
            return injectionSection(
                kind: isSystem ? .fixedPrompt : .userInput,
                label: "\(message.role.rawValue.uppercased()) MESSAGE",
                content: message.content,
                reason: isSystem ? .requiredPrompt : .requiredUserInput
            )
        }
        return injectionPlan(
            prompt: prompt,
            sections: sections,
            materialDecisions: [],
            maxEstimatedInputTokens: maxEstimatedInputTokens
        )
    }

    func driftMessages(source: String, candidate: String) -> [NovelModelMessage] {
        [
            NovelModelMessage(
                role: .system,
                content: NovelPromptCatalog.template(for: .polishDriftV1).systemText
            ),
            NovelModelMessage(
                role: .user,
                content: "SOURCE CHAPTER\n\(source)\n\nPOLISHED CANDIDATE\n\(candidate)"
            ),
        ]
    }

    func addingMaterial(
        id: NovelMaterialID,
        revisionID: NovelMaterialRevisionID,
        to plan: NovelInjectionPlan
    ) -> NovelInjectionPlan {
        let content = "Injected material must not be present in a drift check."
        let section = injectionSection(
            kind: .material(revisionID),
            label: "MATERIAL",
            content: content,
            reason: .always
        )
        let decision = NovelMaterialInjectionDecision(
            materialID: id,
            revisionID: revisionID,
            included: true,
            reason: .always,
            relevanceScore: 100,
            estimatedTokens: section.estimatedTokens,
            contentSHA256: section.contentSHA256
        )
        return injectionPlan(
            prompt: plan.prompt,
            sections: plan.sections + [section],
            materialDecisions: [decision],
            maxEstimatedInputTokens: plan.maxEstimatedInputTokens
        )
    }

    func injectionSection(
        kind: NovelInjectionSectionKind,
        label: String,
        content: String,
        reason: NovelInjectionSelectionReason
    ) -> NovelInjectionSection {
        NovelInjectionSection(
            kind: kind,
            label: label,
            content: content,
            reason: reason,
            estimatedTokens: max(1, (content.utf8.count + 3) / 4),
            contentSHA256: NovelDocumentValidator.sha256(content)
        )
    }

    func injectionPlan(
        prompt: NovelPromptTemplate,
        sections: [NovelInjectionSection],
        materialDecisions: [NovelMaterialInjectionDecision],
        maxEstimatedInputTokens: Int
    ) -> NovelInjectionPlan {
        let canonical = sections.map { "[\($0.label)]\n\($0.content)" }
            .joined(separator: "\n\n")
        return NovelInjectionPlan(
            prompt: prompt,
            sections: sections,
            materialDecisions: materialDecisions,
            maxEstimatedInputTokens: maxEstimatedInputTokens,
            estimatedInputTokens: max(1, (canonical.utf8.count + 3) / 4),
            contextText: canonical,
            canonicalInput: canonical,
            canonicalInputSHA256: NovelDocumentValidator.sha256(canonical)
        )
    }

    func receiptReplacingPlan(
        _ receipt: NovelInjectionReceiptRecord,
        plan: NovelInjectionPlan
    ) -> NovelInjectionReceiptRecord {
        NovelInjectionReceiptRecord(
            id: receipt.id,
            runID: receipt.runID,
            projectID: receipt.projectID,
            branchID: receipt.branchID,
            plan: plan,
            overrides: NovelInjectionOverrides(
                forceIncludeMaterialIDs: receipt.forceIncludeMaterialIDs,
                forceExcludeMaterialIDs: receipt.forceExcludeMaterialIDs
            ),
            providerID: receipt.providerID,
            ownerProviderID: receipt.ownerProviderID,
            modelID: receipt.modelID,
            wireModelID: receipt.wireModelID,
            parameters: receipt.parameters,
            requestedInputBudgetTokens: receipt.requestedInputBudgetTokens,
            factTransaction: receipt.factTransaction,
            createdAt: receipt.createdAt
        )
    }

    func replacingCheckpoint(
        _ checkpoint: NovelBranchCheckpointRecord,
        chapterSelections: [NovelChapterSelection]? = nil,
        stateSnapshotID: NovelStateSnapshotID? = nil,
        overrideRevisionIDs: [NovelMaterialRevisionID]? = nil
    ) -> NovelBranchCheckpointRecord {
        NovelBranchCheckpointRecord(
            id: checkpoint.id,
            kind: checkpoint.kind,
            createdOnBranchID: checkpoint.createdOnBranchID,
            parentCheckpointID: checkpoint.parentCheckpointID,
            chapterSelections: chapterSelections ?? checkpoint.chapterSelections,
            stateSnapshotID: stateSnapshotID ?? checkpoint.stateSnapshotID,
            sessionCursor: checkpoint.sessionCursor,
            branchOverrideRevisionIDs: overrideRevisionIDs ?? checkpoint.branchOverrideRevisionIDs,
            sourceCandidateID: checkpoint.sourceCandidateID,
            baseHeadRevision: checkpoint.baseHeadRevision,
            operationID: checkpoint.operationID,
            createdAt: checkpoint.createdAt
        )
    }

    func replacingCompatibility(
        _ version: NovelChapterVersionRecord,
        compatibilityID: UUID
    ) -> NovelChapterVersionRecord {
        NovelChapterVersionRecord(
            id: version.id,
            chapterID: version.chapterID,
            kind: version.kind,
            title: version.title,
            content: version.content,
            factCompatibilityID: compatibilityID,
            sourceChapterVersionID: version.sourceChapterVersionID,
            sourceCandidateID: version.sourceCandidateID,
            createdAt: version.createdAt,
            operationID: version.operationID
        )
    }

    func assertInvalidDocument(
        _ document: NovelProjectDocumentV1,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertThrowsError(
            try NovelDocumentValidator.validate(document),
            file: file,
            line: line
        ) { error in
            guard case .invalidDocument(let issues) = error as? NovelError else {
                return XCTFail("Expected invalid document, got \(error)", file: file, line: line)
            }
            XCTAssertFalse(issues.isEmpty, file: file, line: line)
        }
    }

    func waitForRequestCount(
        _ expected: Int,
        adapter: ScriptedNovelModelAdapter,
        timeout: TimeInterval = 1
    ) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if await adapter.requests.count >= expected { return true }
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
        return await adapter.requests.count >= expected
    }

    func waitForCancellation(
        runID: NovelRunID,
        adapter: ScriptedNovelModelAdapter,
        timeout: TimeInterval = 1
    ) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if await adapter.cancelledRunIDs.contains(runID) { return true }
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
        return await adapter.cancelledRunIDs.contains(runID)
    }
}

actor PolishReconciliationRepository: NovelProjectPersisting {
    private let base = InMemoryNovelProjectRepository()
    private var injectedAfterWrite = false
    private var failNextReconcileLoad = false
    private var reconcileReadFailureCount = 0

    func seed(_ document: NovelProjectDocumentV1) async throws {
        _ = try await base.createProject(document)
    }

    func didInjectAfterWriteFailure() -> Bool { injectedAfterWrite }

    func injectedReconcileReadFailures() -> Int { reconcileReadFailureCount }

    func listProjects() async throws -> [NovelProjectSummary] {
        try await base.listProjects()
    }

    func loadProject(id: NovelProjectID) async throws -> NovelLoadedProject {
        if failNextReconcileLoad {
            failNextReconcileLoad = false
            reconcileReadFailureCount += 1
            throw NovelError.repositoryFailure("Injected first reconciliation read failure.")
        }
        return try await base.loadProject(id: id)
    }

    func createProject(_ document: NovelProjectDocumentV1) async throws -> NovelLoadedProject {
        try await base.createProject(document)
    }

    func commitProject(
        _ document: NovelProjectDocumentV1,
        expectedRevision: Int64,
        authorization: NovelRepositoryCommitAuthorization?
    ) async throws -> NovelLoadedProject {
        let committed = try await base.commitProject(
            document,
            expectedRevision: expectedRevision,
            authorization: authorization
        )
        if !injectedAfterWrite,
           document.appliedOperations.contains(where: { $0.kind == .adoptPolishCandidate }) {
            injectedAfterWrite = true
            failNextReconcileLoad = true
            throw NovelError.storageIndeterminate(document.project.id)
        }
        return committed
    }

    func restorePreviousProject(
        id: NovelProjectID,
        expectedDocumentSHA256: String
    ) async throws -> NovelLoadedProject {
        try await base.restorePreviousProject(
            id: id,
            expectedDocumentSHA256: expectedDocumentSHA256
        )
    }

    func listRecoverySidecars() async throws -> [NovelRecoverySidecarV1] {
        try await base.listRecoverySidecars()
    }

    func writeRecoverySidecar(_ sidecar: NovelRecoverySidecarV1) async throws {
        try await base.writeRecoverySidecar(sidecar)
    }

    func removeRecoverySidecar(projectID: NovelProjectID, runID: NovelRunID) async throws {
        try await base.removeRecoverySidecar(projectID: projectID, runID: runID)
    }
}

actor PolishFinalAdoptionCommitGateRepository: NovelProjectPersisting {
    private let base: NovelFileProjectRepository
    private var armedOperationID: NovelOperationID?
    private var blockedAuthorization: NovelRepositoryCommitAuthorization?
    private var isFinalCommitBlocked = false
    private var blockedWaiters: [CheckedContinuation<Void, Never>] = []
    private var releaseContinuation: CheckedContinuation<Void, Never>?
    private var completedFinalCommitCount = 0
    private var shouldFailCancellationWriteAfterInstall = false
    private var didFailCancellationWriteAfterInstall = false

    init(rootDirectory: URL) {
        base = NovelFileProjectRepository(rootDirectory: rootDirectory)
    }

    func armFinalCommit(operationID: NovelOperationID) {
        armedOperationID = operationID
    }

    func waitUntilFinalCommitBlocked() async {
        guard !isFinalCommitBlocked else { return }
        await withCheckedContinuation { blockedWaiters.append($0) }
    }

    func finalCommitAuthorizationWasRevoked() -> Bool {
        blockedAuthorization?.isRevoked == true
    }

    func resumeFinalCommit() {
        let continuation = releaseContinuation
        releaseContinuation = nil
        isFinalCommitBlocked = false
        continuation?.resume()
    }

    func finalCommitCount() -> Int { completedFinalCommitCount }

    func failNextCancellationWriteAfterInstall() {
        shouldFailCancellationWriteAfterInstall = true
    }

    func failedCancellationWriteAfterInstall() -> Bool {
        didFailCancellationWriteAfterInstall
    }

    func listProjects() async throws -> [NovelProjectSummary] {
        try await base.listProjects()
    }

    func loadProject(id: NovelProjectID) async throws -> NovelLoadedProject {
        try await base.loadProject(id: id)
    }

    func createProject(_ document: NovelProjectDocumentV1) async throws -> NovelLoadedProject {
        try await base.createProject(document)
    }

    func commitProject(
        _ document: NovelProjectDocumentV1,
        expectedRevision: Int64,
        authorization: NovelRepositoryCommitAuthorization?
    ) async throws -> NovelLoadedProject {
        if isArmedFinalCommit(document) {
            armedOperationID = nil
            blockedAuthorization = authorization
            isFinalCommitBlocked = true
            blockedWaiters.forEach { $0.resume() }
            blockedWaiters.removeAll()
            await withCheckedContinuation { releaseContinuation = $0 }
        }
        let committed = try await base.commitProject(
            document,
            expectedRevision: expectedRevision,
            authorization: authorization
        )
        if shouldFailCancellationWriteAfterInstall,
           document.polishAssessments.contains(where: {
               $0.failure?.code == "cancelled" && $0.result == nil
           }) {
            shouldFailCancellationWriteAfterInstall = false
            didFailCancellationWriteAfterInstall = true
            throw NovelError.storageIndeterminate(document.project.id)
        }
        if document.appliedOperations.contains(where: {
            $0.kind == .adoptPolishCandidate && $0.outcome.isPolishCandidateAdoption
        }) {
            completedFinalCommitCount += 1
        }
        return committed
    }

    func restorePreviousProject(
        id: NovelProjectID,
        expectedDocumentSHA256: String
    ) async throws -> NovelLoadedProject {
        try await base.restorePreviousProject(
            id: id,
            expectedDocumentSHA256: expectedDocumentSHA256
        )
    }

    func listRecoverySidecars() async throws -> [NovelRecoverySidecarV1] {
        try await base.listRecoverySidecars()
    }

    func writeRecoverySidecar(_ sidecar: NovelRecoverySidecarV1) async throws {
        try await base.writeRecoverySidecar(sidecar)
    }

    func removeRecoverySidecar(projectID: NovelProjectID, runID: NovelRunID) async throws {
        try await base.removeRecoverySidecar(projectID: projectID, runID: runID)
    }

    private func isArmedFinalCommit(_ document: NovelProjectDocumentV1) -> Bool {
        guard let armedOperationID else { return false }
        return document.appliedOperations.contains {
            $0.operationID == armedOperationID &&
                $0.kind == .adoptPolishCandidate &&
                $0.outcome.isPolishCandidateAdoption
        }
    }
}

private extension NovelOutcome {
    var isPolishCandidateAdoption: Bool {
        if case .polishCandidateAdopted = self { return true }
        return false
    }
}
