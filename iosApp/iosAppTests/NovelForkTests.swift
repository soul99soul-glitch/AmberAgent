import Foundation
import XCTest
@testable import iosApp

final class NovelForkTests: XCTestCase {
    func testValidatorRejectsDanglingCandidateIdentityInForkableSessionPrefix() throws {
        var document = try NovelBranchTestFixtures.documentWithCollectedCandidate()
        let sessionIndex = 0
        let messageIndex = 0
        let original = document.sessions[sessionIndex].messages[messageIndex]
        XCTAssertNil(original.candidateID)
        document.sessions[sessionIndex].messages[messageIndex] = NovelSessionMessageRecord(
            id: original.id,
            sequence: original.sequence,
            role: original.role,
            mode: original.mode,
            kind: original.kind,
            content: original.content,
            createdAt: original.createdAt,
            runID: original.runID,
            candidateID: NovelCandidateID()
        )

        XCTAssertThrowsError(try NovelDocumentValidator.validate(document)) { error in
            guard case .invalidDocument(let issues) = error as? NovelError else {
                return XCTFail("Expected invalidDocument, got \(error)")
            }
            XCTAssertFalse(issues.isEmpty)
        }
    }

    func testForkCopiesExactCheckpointSessionPrefixWithFreshReadOnlyIdentities() throws {
        let blank = try NovelTestFixtures.document(
            now: Date(timeIntervalSince1970: 1_710_000_000)
        )
        let parentID = blank.branches[0].id
        let prose = try NovelBranchTestFixtures.appendCompletedRun(
            to: blank,
            branchID: parentID,
            kind: .prose,
            content: "Mara opened the sealed door."
        )
        let discussed = try NovelBranchTestFixtures.appendCompletedRun(
            to: prose.document,
            branchID: parentID,
            kind: .discussion,
            content: "This discussion happened after the candidate but before its late collection."
        ).document
        let collected = try NovelBranchTestFixtures.collectCandidate(
            try XCTUnwrap(prose.candidateID),
            in: discussed,
            title: "Chapter One"
        )
        let checkpoint = try NovelBranchTestFixtures.checkpoint(
            collected.branches[0].headCheckpointID,
            in: collected
        )
        let sourceBranch = try NovelBranchTestFixtures.branch(parentID, in: collected)
        let sourceSession = try NovelBranchTestFixtures.session(sourceBranch.sessionID, in: collected)
        let sourceCandidates = collected.candidates.filter { $0.branchID == parentID }
        let prefix = sourceSession.messages.filter { message in
            guard case .through(let sequence) = checkpoint.sessionCursor else { return false }
            return message.sequence <= sequence
        }
        XCTAssertEqual(checkpoint.sessionCursor, .through(sequence: prefix.last!.sequence))
        XCTAssertNotEqual(
            checkpoint.sessionCursor,
            .through(sequence: sourceSession.messages.last!.sequence)
        )
        let allSourceMessageIDs = Set(collected.sessions.flatMap { $0.messages.map(\.id) })
        let allSourceCandidateIDs = Set(collected.candidates.map(\.id))
        let childID = NovelBranchID()
        let childSessionID = NovelSessionID()
        let command = NovelBranchTestFixtures.forkCommand(
            document: collected,
            sourceBranchID: parentID,
            checkpointID: checkpoint.id,
            branchID: childID,
            sessionID: childSessionID,
            name: "Alternate"
        )

        let forked = try NovelReducer.apply(.forkBranch(command), to: collected).document
        let child = try NovelBranchTestFixtures.branch(childID, in: forked)
        let childSession = try NovelBranchTestFixtures.session(childSessionID, in: forked)
        let inheritedCandidates = forked.candidates.filter { $0.branchID == childID }

        XCTAssertEqual(child.forkOrigin, NovelForkOrigin(
            parentBranchID: parentID,
            checkpointID: checkpoint.id
        ))
        XCTAssertEqual(child.headCheckpointID, checkpoint.id)
        XCTAssertEqual(child.currentStateSnapshotID, checkpoint.stateSnapshotID)
        XCTAssertEqual(child.workingChapterSelections, checkpoint.chapterSelections)
        XCTAssertEqual(child.overrideRevisionIDs, checkpoint.branchOverrideRevisionIDs)
        XCTAssertEqual(child.headRevision, 0)
        XCTAssertEqual(child.workingRevision, 0)
        XCTAssertEqual(child.syncStatus, .synchronized)
        XCTAssertNil(child.activeRunID)
        XCTAssertEqual(childSession.revision, 0)
        XCTAssertEqual(childSession.messages.count, prefix.count)
        XCTAssertEqual(childSession.messages.map(\.sequence), prefix.map(\.sequence))
        XCTAssertEqual(childSession.messages.map(\.role), prefix.map(\.role))
        XCTAssertEqual(childSession.messages.map(\.mode), prefix.map(\.mode))
        XCTAssertEqual(childSession.messages.map(\.kind), prefix.map(\.kind))
        XCTAssertEqual(childSession.messages.map(\.content), prefix.map(\.content))
        XCTAssertEqual(childSession.messages.map(\.createdAt), prefix.map(\.createdAt))
        XCTAssertTrue(childSession.messages.allSatisfy { $0.runID == nil })
        XCTAssertTrue(Set(childSession.messages.map(\.id)).isDisjoint(with: allSourceMessageIDs))
        XCTAssertFalse(childSession.messages.contains {
            $0.content.contains("before its late collection")
        })

        XCTAssertEqual(inheritedCandidates.count, sourceCandidates.count)
        XCTAssertTrue(inheritedCandidates.allSatisfy { candidate in
            candidate.status == .inheritedReadOnly &&
                candidate.collectedCheckpointID == nil &&
                candidate.branchID == childID &&
                candidate.sessionID == childSessionID
        })
        XCTAssertTrue(Set(inheritedCandidates.map(\.id)).isDisjoint(with: allSourceCandidateIDs))
        let inherited = try XCTUnwrap(inheritedCandidates.first)
        let source = try XCTUnwrap(sourceCandidates.first)
        XCTAssertEqual(inherited.content, source.content)
        XCTAssertEqual(inherited.kind, source.kind)
        XCTAssertEqual(inherited.baseCheckpointID, source.baseCheckpointID)
        XCTAssertEqual(inherited.baseHeadRevision, source.baseHeadRevision)
        XCTAssertEqual(
            childSession.messages.first(where: { $0.id == inherited.sourceMessageID })?.candidateID,
            inherited.id
        )

        XCTAssertEqual(try NovelBranchTestFixtures.branch(parentID, in: forked), sourceBranch)
        XCTAssertEqual(try NovelBranchTestFixtures.session(sourceBranch.sessionID, in: forked), sourceSession)
        XCTAssertEqual(forked.candidates.filter { $0.branchID == parentID }, sourceCandidates)
        NovelBranchTestFixtures.assertGlobalImmutableRecordsEqual(
            collected,
            forked,
            file: #filePath,
            line: #line
        )
        XCTAssertNoThrow(try NovelDocumentValidator.validate(forked))
    }

    func testForkRejectsInitialButAllowsCommittedCheckpointWhileWorkingCopyNeedsSync() throws {
        let collected = try NovelBranchTestFixtures.documentWithCollectedCandidate()
        let parent = collected.branches[0]
        let initial = try XCTUnwrap(collected.checkpoints.first { $0.kind == .initial })
        let initialFork = NovelBranchTestFixtures.forkCommand(
            document: collected,
            sourceBranchID: parent.id,
            checkpointID: initial.id,
            name: "Invalid Initial Fork"
        )

        XCTAssertThrowsError(try NovelReducer.apply(.forkBranch(initialFork), to: collected)) { error in
            guard case .invalidInput(let message) = error as? NovelError else {
                return XCTFail("Expected invalidInput, got \(error)")
            }
            XCTAssertTrue(message.contains("not forkable"))
        }

        let committedHead = try NovelBranchTestFixtures.checkpoint(parent.headCheckpointID, in: collected)
        let edited = try NovelBranchTestFixtures.saveManualEdit(
            in: collected,
            branchID: parent.id,
            content: "Mara opened a different door in the unsynchronized working copy."
        )
        let editedParent = try NovelBranchTestFixtures.branch(parent.id, in: edited)
        XCTAssertEqual(editedParent.syncStatus, .needsSync)
        XCTAssertNotEqual(editedParent.workingChapterSelections, committedHead.chapterSelections)
        XCTAssertEqual(editedParent.headCheckpointID, committedHead.id)
        let fork = NovelBranchTestFixtures.forkCommand(
            document: edited,
            sourceBranchID: parent.id,
            checkpointID: committedHead.id,
            name: "Committed History"
        )

        let result = try NovelReducer.apply(.forkBranch(fork), to: edited).document
        let child = try NovelBranchTestFixtures.branch(fork.branchID, in: result)

        XCTAssertEqual(child.syncStatus, .synchronized)
        XCTAssertEqual(child.workingChapterSelections, committedHead.chapterSelections)
        XCTAssertNotEqual(child.workingChapterSelections, editedParent.workingChapterSelections)
        XCTAssertEqual(try NovelBranchTestFixtures.branch(parent.id, in: result), editedParent)
        XCTAssertNoThrow(try NovelDocumentValidator.validate(result))
    }

    func testForkFromImmediatelyCollectedCheckpointPreservesNeedsSync() throws {
        let collected = try NovelBranchTestFixtures.documentWithImmediatelyCollectedCandidate()
        let parent = collected.branches[0]
        let checkpoint = try NovelBranchTestFixtures.checkpoint(
            parent.headCheckpointID,
            in: collected
        )
        XCTAssertEqual(checkpoint.kind, .collection)
        XCTAssertEqual(parent.syncStatus, .needsSync)

        let command = NovelBranchTestFixtures.forkCommand(
            document: collected,
            sourceBranchID: parent.id,
            checkpointID: checkpoint.id,
            name: "Unsynchronized Fork"
        )
        let forked = try NovelReducer.apply(.forkBranch(command), to: collected).document
        let child = try NovelBranchTestFixtures.branch(command.branchID, in: forked)

        XCTAssertEqual(child.headCheckpointID, checkpoint.id)
        XCTAssertEqual(child.workingChapterSelections, checkpoint.chapterSelections)
        XCTAssertEqual(child.currentStateSnapshotID, checkpoint.stateSnapshotID)
        XCTAssertEqual(child.syncStatus, .needsSync)
        XCTAssertNoThrow(try NovelDocumentValidator.validate(forked))
    }

    func testPackageDecodeNormalizesHistoricalForkSyncStatus() throws {
        let collected = try NovelBranchTestFixtures.documentWithImmediatelyCollectedCandidate()
        let parent = collected.branches[0]
        let command = NovelBranchTestFixtures.forkCommand(
            document: collected,
            sourceBranchID: parent.id,
            checkpointID: parent.headCheckpointID,
            name: "Historical Fork"
        )
        var historical = try NovelReducer.apply(.forkBranch(command), to: collected).document
        let childIndex = try XCTUnwrap(historical.branches.firstIndex {
            $0.id == command.branchID
        })
        historical.branches[childIndex].syncStatus = .synchronized
        XCTAssertNoThrow(try NovelDocumentValidator.validate(historical))

        let package = try NovelProjectPackageCodec.encode(historical)
        let decoded = try NovelProjectPackageCodec.decode(package.data).document

        XCTAssertEqual(
            decoded.branches.first { $0.id == command.branchID }?.syncStatus,
            .needsSync
        )
        XCTAssertNoThrow(try NovelDocumentValidator.validate(decoded))
    }

    func testCollectionBaseBridgeRejectsCandidateThatPredatesManualSync() throws {
        let document = try NovelTestFixtures.document()
        let branch = document.branches[0]
        let generated = try NovelBranchTestFixtures.appendCompletedRun(
            to: document,
            branchID: branch.id,
            kind: .prose,
            content: "Mara opened the archive."
        )
        let candidateID = try XCTUnwrap(generated.candidateID)
        let candidate = try NovelBranchTestFixtures.candidate(
            candidateID,
            in: generated.document
        )
        let sourceMessage = try XCTUnwrap(generated.document.sessions[0].messages.first {
            $0.id == candidate.sourceMessageID
        })
        let base = try NovelBranchTestFixtures.checkpoint(
            candidate.baseCheckpointID,
            in: generated.document
        )
        let synchronizedHead = NovelBranchCheckpointRecord(
            id: NovelCheckpointID(),
            kind: .manualSync,
            createdOnBranchID: branch.id,
            parentCheckpointID: base.id,
            chapterSelections: base.chapterSelections,
            stateSnapshotID: NovelStateSnapshotID(),
            sessionCursor: .through(sequence: sourceMessage.sequence),
            branchOverrideRevisionIDs: base.branchOverrideRevisionIDs,
            sourceCandidateID: nil,
            baseHeadRevision: candidate.baseHeadRevision,
            operationID: NovelOperationID(),
            createdAt: sourceMessage.createdAt.addingTimeInterval(1)
        )

        XCTAssertFalse(NovelCandidateSemantics.collectionBaseMatches(
            candidate,
            targetCheckpointID: synchronizedHead.id,
            targetHeadRevision: candidate.baseHeadRevision + 1,
            checkpoints: generated.document.checkpoints + [synchronizedHead],
            sourceMessage: sourceMessage
        ))
    }

    func testParentAndChildWorkingStateEvolveIndependentlyAndFileRoundTrip() async throws {
        let collected = try NovelBranchTestFixtures.documentWithCollectedCandidate()
        let parentID = collected.branches[0].id
        let fork = NovelBranchTestFixtures.forkCommand(
            document: collected,
            sourceBranchID: parentID,
            checkpointID: collected.branches[0].headCheckpointID,
            name: "Child"
        )
        let forked = try NovelReducer.apply(.forkBranch(fork), to: collected).document
        let childBeforeParentEdit = try NovelBranchTestFixtures.branch(fork.branchID, in: forked)

        let parentEdited = try NovelBranchTestFixtures.saveManualEdit(
            in: forked,
            branchID: parentID,
            content: "Mara kept the door open on the parent branch."
        )
        let parentAfterOwnEdit = try NovelBranchTestFixtures.branch(parentID, in: parentEdited)
        XCTAssertEqual(
            try NovelBranchTestFixtures.branch(fork.branchID, in: parentEdited),
            childBeforeParentEdit
        )

        let childEdited = try NovelBranchTestFixtures.saveManualEdit(
            in: parentEdited,
            branchID: fork.branchID,
            content: "Mara sealed the door forever on the child branch."
        )
        let childAfterOwnEdit = try NovelBranchTestFixtures.branch(fork.branchID, in: childEdited)

        XCTAssertEqual(try NovelBranchTestFixtures.branch(parentID, in: childEdited), parentAfterOwnEdit)
        XCTAssertNotEqual(
            parentAfterOwnEdit.workingChapterSelections,
            childAfterOwnEdit.workingChapterSelections
        )
        XCTAssertEqual(parentAfterOwnEdit.syncStatus, .needsSync)
        XCTAssertEqual(childAfterOwnEdit.syncStatus, .needsSync)
        XCTAssertNoThrow(try NovelDocumentValidator.validate(childEdited))

        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = NovelFileProjectRepository(rootDirectory: root)
        _ = try await repository.createProject(childEdited)
        let restarted = NovelFileProjectRepository(rootDirectory: root)
        let loaded = try await restarted.loadProject(id: childEdited.project.id)

        XCTAssertEqual(loaded.access, .readWrite)
        XCTAssertEqual(loaded.document, childEdited)
        XCTAssertNoThrow(try NovelDocumentValidator.validate(loaded.document))
    }
}

enum NovelBranchTestFixtures {
    private static let epoch = Date(timeIntervalSince1970: 1_710_000_000)

    static func documentWithCollectedCandidate(
        content: String = "Mara opened the sealed door."
    ) throws -> NovelProjectDocumentV1 {
        let document = try NovelTestFixtures.document(now: epoch)
        let branchID = document.branches[0].id
        let generated = try appendCompletedRun(
            to: document,
            branchID: branchID,
            kind: .prose,
            content: content
        )
        return try collectCandidate(
            generated.candidateID!,
            in: generated.document,
            title: "Chapter One"
        )
    }

    static func documentWithImmediatelyCollectedCandidate(
        content: String = "Mara opened the sealed door."
    ) throws -> NovelProjectDocumentV1 {
        let document = try NovelTestFixtures.document(now: epoch)
        let branchID = document.branches[0].id
        let generated = try appendCompletedRun(
            to: document,
            branchID: branchID,
            kind: .prose,
            content: content
        )
        let candidateID = try require(generated.candidateID, "Missing prose candidate.")
        let command = try collectCommand(
            candidateID,
            document: generated.document,
            title: "Chapter One"
        )
        return try NovelFactTransactionReducer.commitCollectionWithoutStateSync(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: generated.document,
            now: timestamp(for: generated.document, offset: 3)
        ).document
    }

    static func appendCompletedRun(
        to document: NovelProjectDocumentV1,
        branchID: NovelBranchID,
        kind: NovelRunKind,
        content: String,
        userText: String = "Continue the story."
    ) throws -> (document: NovelProjectDocumentV1, candidateID: NovelCandidateID?) {
        let request = try runRequest(
            document: document,
            branchID: branchID,
            kind: kind,
            userText: userText
        )
        let started = try NovelGenerationReducer.begin(
            request,
            artifacts: try generationArtifacts(document: document, request: request),
            in: document,
            now: timestamp(for: document, offset: 1)
        ).document
        let completed = try NovelGenerationReducer.complete(
            runID: request.id,
            content: content,
            in: started,
            now: timestamp(for: started, offset: 2)
        ).document
        return (completed, request.candidateID)
    }

    static func beginDiscussionRun(
        in document: NovelProjectDocumentV1,
        branchID: NovelBranchID
    ) throws -> NovelProjectDocumentV1 {
        let request = try runRequest(
            document: document,
            branchID: branchID,
            kind: .discussion,
            userText: "Discuss the branch while it is running."
        )
        return try NovelGenerationReducer.begin(
            request,
            artifacts: generationArtifacts(document: document, request: request),
            in: document,
            now: timestamp(for: document, offset: 1)
        ).document
    }

    static func prepareCollection(
        _ candidateID: NovelCandidateID,
        in document: NovelProjectDocumentV1,
        title: String = "Pending Chapter"
    ) throws -> NovelPendingFactTransactionResult {
        let command = try collectCommand(
            candidateID,
            document: document,
            title: title
        )
        return try NovelFactTransactionReducer.prepareCollection(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: document,
            now: timestamp(for: document, offset: 3)
        )
    }

    static func collectCandidate(
        _ candidateID: NovelCandidateID,
        in document: NovelProjectDocumentV1,
        title: String
    ) throws -> NovelProjectDocumentV1 {
        let command = try collectCommand(
            candidateID,
            document: document,
            title: title
        )
        let prepared = try NovelFactTransactionReducer.prepareCollection(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: document,
            now: timestamp(for: document, offset: 3)
        )
        let candidate = try candidate(candidateID, in: document)
        let delta = NovelStateDeltaV1(
            schemaVersion: 1,
            stateSummary: candidate.content,
            events: [NovelStateEventV1(
                id: "event-\(command.context.operationID.description)",
                kind: "progress",
                summary: candidate.content,
                entityReferences: ["Mara"],
                evidence: candidate.content
            )],
            characterChanges: [],
            relationshipChanges: [],
            foreshadowingChanges: [],
            unresolvedEntityNames: ["Mara"],
            branchOutlinePatch: nil,
            settingProposals: []
        )
        return try NovelFactTransactionReducer.finalizeCollection(
            pendingID: command.pendingID,
            delta: delta,
            artifacts: NovelTestFixtures.factTransactionArtifacts(
                document: prepared.document,
                pendingID: command.pendingID,
                now: timestamp(for: prepared.document, offset: 4)
            ),
            in: prepared.document,
            now: timestamp(for: prepared.document, offset: 5)
        ).document
    }

    static func saveManualEdit(
        in document: NovelProjectDocumentV1,
        branchID: NovelBranchID,
        content: String
    ) throws -> NovelProjectDocumentV1 {
        let targetBranch = try branch(branchID, in: document)
        let selection = try require(targetBranch.workingChapterSelections.first, "Missing chapter selection.")
        let sourceVersion = try require(
            document.chapterVersions.first { $0.id == selection.versionID },
            "Missing selected chapter version."
        )
        let command = NovelSaveManualEditCommand(
            context: mutationContext(document: document, branchID: branchID),
            projectID: document.project.id,
            branchID: branchID,
            chapterID: selection.chapterID,
            versionID: NovelChapterVersionID(),
            title: sourceVersion.title,
            content: content,
            factCompatibilityID: UUID(),
            expectedWorkingRevision: targetBranch.workingRevision
        )
        return try NovelFactTransactionReducer.saveManualEdit(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: document,
            now: timestamp(for: document, offset: 6)
        ).document
    }

    static func forkCommand(
        document: NovelProjectDocumentV1,
        sourceBranchID: NovelBranchID,
        checkpointID: NovelCheckpointID,
        branchID: NovelBranchID = NovelBranchID(),
        sessionID: NovelSessionID = NovelSessionID(),
        name: String
    ) -> NovelForkBranchCommand {
        let source = document.branches.first { $0.id == sourceBranchID }
        return NovelForkBranchCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: document.project.revision,
                expectedConfigRevision: document.project.configRevision,
                expectedBranchHeadRevision: source?.headRevision
            ),
            projectID: document.project.id,
            sourceBranchID: sourceBranchID,
            checkpointID: checkpointID,
            branchID: branchID,
            sessionID: sessionID,
            name: name
        )
    }

    static func branch(
        _ id: NovelBranchID,
        in document: NovelProjectDocumentV1
    ) throws -> NovelBranchRecord {
        try require(document.branches.first { $0.id == id }, "Missing branch \(id).")
    }

    static func session(
        _ id: NovelSessionID,
        in document: NovelProjectDocumentV1
    ) throws -> NovelSessionRecord {
        try require(document.sessions.first { $0.id == id }, "Missing Session \(id).")
    }

    static func checkpoint(
        _ id: NovelCheckpointID,
        in document: NovelProjectDocumentV1
    ) throws -> NovelBranchCheckpointRecord {
        try require(document.checkpoints.first { $0.id == id }, "Missing checkpoint \(id).")
    }

    static func candidate(
        _ id: NovelCandidateID,
        in document: NovelProjectDocumentV1
    ) throws -> NovelCandidateRecord {
        try require(document.candidates.first { $0.id == id }, "Missing candidate \(id).")
    }

    static func mutationContext(
        document: NovelProjectDocumentV1,
        branchID: NovelBranchID,
        operationID: NovelOperationID = NovelOperationID()
    ) -> NovelMutationContext {
        let target = document.branches.first { $0.id == branchID }
        return NovelMutationContext(
            operationID: operationID,
            expectedProjectRevision: document.project.revision,
            expectedConfigRevision: document.project.configRevision,
            expectedBranchHeadRevision: target?.headRevision
        )
    }

    static func assertGlobalImmutableRecordsEqual(
        _ lhs: NovelProjectDocumentV1,
        _ rhs: NovelProjectDocumentV1,
        file: StaticString,
        line: UInt
    ) {
        XCTAssertEqual(lhs.materialRevisions, rhs.materialRevisions, file: file, line: line)
        XCTAssertEqual(lhs.chapters, rhs.chapters, file: file, line: line)
        XCTAssertEqual(lhs.chapterVersions, rhs.chapterVersions, file: file, line: line)
        XCTAssertEqual(lhs.events, rhs.events, file: file, line: line)
        XCTAssertEqual(lhs.stateSnapshots, rhs.stateSnapshots, file: file, line: line)
        XCTAssertEqual(lhs.checkpoints, rhs.checkpoints, file: file, line: line)
        XCTAssertEqual(lhs.injectionReceipts, rhs.injectionReceipts, file: file, line: line)
        XCTAssertEqual(lhs.generationReceipts, rhs.generationReceipts, file: file, line: line)
        XCTAssertEqual(lhs.factAttempts, rhs.factAttempts, file: file, line: line)
        XCTAssertEqual(lhs.settingProposals, rhs.settingProposals, file: file, line: line)
    }
}

private extension NovelBranchTestFixtures {
    static func runRequest(
        document: NovelProjectDocumentV1,
        branchID: NovelBranchID,
        kind: NovelRunKind,
        userText: String
    ) throws -> NovelRunRequest {
        let targetBranch = try branch(branchID, in: document)
        let mode: NovelSessionMode
        let granularity: NovelGenerationGranularity?
        let candidateID: NovelCandidateID?
        switch kind {
        case .discussion:
            mode = .discussPlan
            granularity = nil
            candidateID = nil
        case .prose:
            mode = .writeProse
            granularity = .wholeChapter
            candidateID = NovelCandidateID()
        case .quickStart, .polish, .regenerate:
            throw NovelError.invalidInput("The branch test fixture only supports discussion and prose runs.")
        }
        return NovelRunRequest(
            id: NovelRunID(),
            operationID: NovelOperationID(),
            projectID: document.project.id,
            branchID: branchID,
            kind: kind,
            mode: mode,
            granularity: granularity,
            userText: userText,
            userMessageID: NovelMessageID(),
            assistantMessageID: NovelMessageID(),
            candidateID: candidateID,
            generationReceiptID: NovelReceiptID(),
            injectionReceiptID: NovelReceiptID(),
            sourceChapterVersionID: nil,
            expectedProjectRevision: document.project.revision,
            expectedConfigRevision: document.project.configRevision,
            expectedBranchHeadRevision: targetBranch.headRevision
        )
    }

    static func generationArtifacts(
        document: NovelProjectDocumentV1,
        request: NovelRunRequest
    ) throws -> NovelGenerationStartArtifacts {
        let promptKind: NovelPromptKind
        switch request.kind {
        case .discussion:
            promptKind = .discussion
        case .prose:
            promptKind = .proseWholeChapter
        case .quickStart, .polish, .regenerate:
            throw NovelError.invalidInput("Unsupported branch test run kind.")
        }
        let plan = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: request.branchID,
                promptKind: promptKind,
                userText: request.userText,
                budget: NovelInjectionBudget(
                    maxEstimatedInputTokens: request.inputBudgetTokens,
                    chapterTailCharacterLimit: 6_000,
                    maximumRecentSessionMessages: 12
                )
            )
        )
        let injection = NovelInjectionReceiptRecord(
            id: request.injectionReceiptID,
            runID: request.id,
            projectID: request.projectID,
            branchID: request.branchID,
            plan: plan,
            overrides: request.injectionOverrides,
            providerID: "branch-test-provider",
            modelID: "branch-test-model",
            parameters: [:],
            createdAt: timestamp(for: document, offset: 1)
        )
        let generation = NovelGenerationReceiptRecord(
            id: request.generationReceiptID,
            runID: request.id,
            providerID: injection.providerID,
            modelID: injection.modelID,
            promptVersion: injection.promptVersion,
            injectionReceiptID: injection.id,
            parameters: injection.parameters,
            requestSHA256: NovelDocumentValidator.sha256(plan.canonicalInput + "\nMODEL REQUEST"),
            createdAt: timestamp(for: document, offset: 1)
        )
        return NovelGenerationStartArtifacts(
            injectionReceipt: injection,
            generationReceipt: generation
        )
    }

    static func collectCommand(
        _ candidateID: NovelCandidateID,
        document: NovelProjectDocumentV1,
        title: String
    ) throws -> NovelCollectCandidateCommand {
        let prose = try candidate(candidateID, in: document)
        return NovelCollectCandidateCommand(
            context: mutationContext(document: document, branchID: prose.branchID),
            projectID: document.project.id,
            branchID: prose.branchID,
            pendingID: NovelPendingOperationID(),
            candidateID: prose.id,
            selection: NovelParagraphSelection(
                paragraphIDs: NovelParagraphParser.paragraphs(in: prose.content).map(\.id)
            ),
            target: .createNextChapter(chapterID: NovelChapterID(), title: title),
            proposedChapterVersionID: NovelChapterVersionID(),
            checkpointID: NovelCheckpointID(),
            stateSnapshotID: NovelStateSnapshotID(),
            factCompatibilityID: UUID()
        )
    }

    static func timestamp(
        for document: NovelProjectDocumentV1,
        offset: TimeInterval
    ) -> Date {
        max(epoch, document.project.updatedAt).addingTimeInterval(offset)
    }

    static func require<Value>(_ value: Value?, _ message: String) throws -> Value {
        guard let value else { throw NovelError.invalidInput(message) }
        return value
    }
}
