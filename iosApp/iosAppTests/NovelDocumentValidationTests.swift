import XCTest
@testable import iosApp

final class NovelDocumentValidationTests: XCTestCase {
    func testLegacySessionWithoutDiscussionArchiveFieldsKeepsPreviousBehavior() throws {
        let document = try NovelTestFixtures.document()
        let encoded = try JSONEncoder().encode(document)
        var object = try XCTUnwrap(
            try JSONSerialization.jsonObject(with: encoded) as? [String: Any]
        )
        var sessions = try XCTUnwrap(object["sessions"] as? [[String: Any]])
        sessions[0].removeValue(forKey: "archiveCursor")
        sessions[0].removeValue(forKey: "discussionArchives")
        object["sessions"] = sessions
        let legacyData = try JSONSerialization.data(
            withJSONObject: object,
            options: [.sortedKeys]
        )

        let decoded = try JSONDecoder().decode(NovelProjectDocumentV1.self, from: legacyData)

        XCTAssertNil(decoded.sessions[0].archiveCursor)
        XCTAssertNil(decoded.sessions[0].discussionArchives)
        XCTAssertNoThrow(try NovelDocumentValidator.validate(decoded))
    }

    func testDocumentDecodeDefaultsMissingFactAttemptsToEmpty() throws {
        let document = try NovelTestFixtures.document()
        let encoded = try JSONEncoder().encode(document)
        var object = try XCTUnwrap(
            try JSONSerialization.jsonObject(with: encoded) as? [String: Any]
        )
        object.removeValue(forKey: "factAttempts")
        let legacyData = try JSONSerialization.data(
            withJSONObject: object,
            options: [.sortedKeys]
        )

        let decoded = try JSONDecoder().decode(
            NovelProjectDocumentV1.self,
            from: legacyData
        )

        XCTAssertTrue(decoded.factAttempts.isEmpty)
        XCTAssertNoThrow(try NovelDocumentValidator.validate(decoded))
    }

    func testStoryEventSequencesAreProjectGlobalUniqueAndIncreasing() throws {
        var document = try NovelTestFixtures.document()
        let first = NovelStoryEventRecord(
            id: NovelEventID(),
            sequence: 0,
            kind: "event",
            summary: "First event",
            entityReferences: [],
            createdAt: document.project.updatedAt
        )
        let second = NovelStoryEventRecord(
            id: NovelEventID(),
            sequence: 0,
            kind: "event",
            summary: "Second event",
            entityReferences: [],
            createdAt: document.project.updatedAt
        )
        document.events = [first, second]
        let state = document.stateSnapshots[0]
        document.stateSnapshots[0] = NovelStateSnapshotRecord(
            id: state.id,
            eventIDs: [first.id, second.id],
            summary: state.summary,
            branchOutline: state.branchOutline,
            unresolvedEntityNames: state.unresolvedEntityNames,
            createdAt: state.createdAt
        )
        assertInvalid(document, containing: "project-global and unique")

        document.events[0] = NovelStoryEventRecord(
            id: first.id,
            sequence: 1,
            kind: first.kind,
            summary: first.summary,
            entityReferences: first.entityReferences,
            createdAt: first.createdAt
        )
        assertInvalid(document, containing: "history is not strictly increasing")
    }

    func testValidFixturePasses() throws {
        XCTAssertNoThrow(try NovelDocumentValidator.validate(NovelTestFixtures.document()))
    }

    func testDuplicateIDsReturnValidationErrorInsteadOfTrapping() throws {
        var document = try NovelTestFixtures.documentWithForkableCheckpoint()
        document.branches.append(document.branches[0])

        assertInvalid(document, containing: "Duplicate branch ID")
    }

    func testMissingCurrentStateIsRejected() throws {
        var document = try NovelTestFixtures.documentWithForkableCheckpoint()
        document.stateSnapshots.removeAll()

        assertInvalid(document, containing: "missing state snapshot")
    }

    func testCheckpointCycleIsRejected() throws {
        var document = try NovelTestFixtures.document()
        let checkpoint = document.checkpoints[0]
        document.checkpoints[0] = NovelBranchCheckpointRecord(
            id: checkpoint.id,
            kind: checkpoint.kind,
            createdOnBranchID: checkpoint.createdOnBranchID,
            parentCheckpointID: checkpoint.id,
            chapterSelections: checkpoint.chapterSelections,
            stateSnapshotID: checkpoint.stateSnapshotID,
            sessionCursor: checkpoint.sessionCursor,
            branchOverrideRevisionIDs: checkpoint.branchOverrideRevisionIDs,
            sourceCandidateID: checkpoint.sourceCandidateID,
            baseHeadRevision: checkpoint.baseHeadRevision,
            operationID: checkpoint.operationID,
            createdAt: checkpoint.createdAt
        )

        assertInvalid(document, containing: "cycle")
    }

    func testSessionCannotBeSharedAcrossBranches() throws {
        var document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let root = document.branches[0]
        document.branches.append(NovelBranchRecord(
            id: NovelBranchID(),
            name: "Other",
            sessionID: root.sessionID,
            createdAt: root.createdAt,
            updatedAt: root.updatedAt,
            forkOrigin: NovelForkOrigin(
                parentBranchID: root.id,
                checkpointID: root.headCheckpointID
            ),
            headCheckpointID: root.headCheckpointID,
            currentStateSnapshotID: root.currentStateSnapshotID,
            headRevision: root.headRevision,
            workingRevision: root.workingRevision,
            syncStatus: .synchronized,
            lifecycle: .active,
            overrideRevisionIDs: [],
            workingChapterSelections: [],
            activeRunID: nil
        ))

        assertInvalid(document, containing: "shared by multiple branches")
    }

    func testSynchronizedWorkingManuscriptMustMatchHead() throws {
        var document = try NovelTestFixtures.document()
        let chapterID = NovelChapterID()
        let versionID = NovelChapterVersionID()
        document.chapters.append(NovelChapterRecord(id: chapterID, createdAt: Date()))
        document.chapterVersions.append(NovelChapterVersionRecord(
            id: versionID,
            chapterID: chapterID,
            kind: .collected,
            title: "Chapter One",
            content: "Text",
            factCompatibilityID: UUID(),
            sourceCandidateID: nil,
            createdAt: Date(),
            operationID: document.appliedOperations[0].operationID
        ))
        document.branches[0].workingChapterSelections = [NovelChapterSelection(
            chapterID: chapterID,
            versionID: versionID
        )]

        assertInvalid(document, containing: "working manuscript differs")
    }

    func testNeedsSyncWorkingManuscriptCannotContainDanglingVersions() throws {
        var document = try NovelTestFixtures.document()
        document.branches[0].syncStatus = .needsSync
        document.branches[0].workingChapterSelections = [NovelChapterSelection(
            chapterID: NovelChapterID(),
            versionID: NovelChapterVersionID()
        )]

        assertInvalid(document, containing: "working manuscript has an invalid chapter version")
    }

    func testInvalidOperationHashIsRejected() throws {
        var document = try NovelTestFixtures.document()
        let operation = document.appliedOperations[0]
        document.appliedOperations[0] = NovelAppliedOperationRecord(
            operationID: operation.operationID,
            kind: operation.kind,
            payloadSHA256: "not-a-hash",
            outcome: operation.outcome,
            appliedProjectRevision: operation.appliedProjectRevision,
            appliedAt: operation.appliedAt
        )

        assertInvalid(document, containing: "invalid payload hash")
    }

    func testOperationKindMustMatchPersistedOutcome() throws {
        var document = try NovelTestFixtures.document()
        let operation = document.appliedOperations[0]
        document.appliedOperations[0] = NovelAppliedOperationRecord(
            operationID: operation.operationID,
            kind: .renameProject,
            payloadSHA256: operation.payloadSHA256,
            outcome: operation.outcome,
            appliedProjectRevision: operation.appliedProjectRevision,
            appliedAt: operation.appliedAt
        )

        assertInvalid(document, containing: "kind does not match")
    }

    func testChapterDiscardAndRestoreAreValidAtomicTransitions() throws {
        let current = try documentWithDiscardableChapter()
        let chapterID = try XCTUnwrap(current.chapters.first?.id)
        let branch = try XCTUnwrap(current.branches.first)
        let discarded = try NovelReducer.apply(.discardChapter(NovelDiscardChapterCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: current.project.revision,
                expectedConfigRevision: nil,
                expectedBranchHeadRevision: branch.headRevision
            ),
            projectID: current.project.id,
            branchID: branch.id,
            chapterID: chapterID
        )), to: current, now: Date(timeIntervalSince1970: 1_700_000_010)).document

        XCTAssertNoThrow(try NovelDocumentValidator.validateTransition(from: current, to: discarded))

        let restored = try NovelReducer.apply(.restoreChapter(NovelRestoreChapterCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: discarded.project.revision,
                expectedConfigRevision: nil,
                expectedBranchHeadRevision: branch.headRevision
            ),
            projectID: current.project.id,
            branchID: branch.id,
            chapterID: chapterID
        )), to: discarded, now: Date(timeIntervalSince1970: 1_700_000_020)).document

        XCTAssertNoThrow(try NovelDocumentValidator.validateTransition(from: discarded, to: restored))
    }

    func testDeleteChapterFromManuscriptRemovesWorkingSelection() throws {
        let current = try documentWithDiscardableChapter()
        let chapterID = try XCTUnwrap(current.chapters.first?.id)
        let branch = try XCTUnwrap(current.branches.first)
        XCTAssertTrue(branch.workingChapterSelections.contains(where: { $0.chapterID == chapterID }))

        let deleted = try NovelReducer.apply(.deleteChapterFromManuscript(
            NovelDeleteChapterFromManuscriptCommand(
                context: NovelMutationContext(
                    operationID: NovelOperationID(),
                    expectedProjectRevision: current.project.revision,
                    expectedConfigRevision: nil,
                    expectedBranchHeadRevision: branch.headRevision
                ),
                projectID: current.project.id,
                branchID: branch.id,
                chapterID: chapterID,
                expectedWorkingRevision: branch.workingRevision
            )
        ), to: current, now: Date(timeIntervalSince1970: 1_700_000_030)).document

        XCTAssertNoThrow(try NovelDocumentValidator.validateTransition(from: current, to: deleted))
        let nextBranch = try XCTUnwrap(deleted.branches.first(where: { $0.id == branch.id }))
        XCTAssertFalse(nextBranch.workingChapterSelections.contains(where: { $0.chapterID == chapterID }))
        XCTAssertEqual(nextBranch.syncStatus, .needsSync)
        XCTAssertEqual(nextBranch.workingRevision, branch.workingRevision + 1)
        // Chapter record stays for checkpoint lineage; marked discarded.
        let chapter = try XCTUnwrap(deleted.chapters.first(where: { $0.id == chapterID }))
        XCTAssertNotNil(chapter.discardedAt)
        XCTAssertFalse(deleted.chapterVersions.filter { $0.chapterID == chapterID }.isEmpty)
    }

    func testChapterTransitionRequiresOneLedgerOperationPerStateFlip() throws {
        let current = try documentWithDiscardableChapter()
        let chapter = try XCTUnwrap(current.chapters.first)
        let branch = try XCTUnwrap(current.branches.first)
        let timestamp = Date(timeIntervalSince1970: 1_700_000_010)

        var operationWithoutFlip = current
        operationWithoutFlip.project.revision += 1
        operationWithoutFlip.project.updatedAt = timestamp
        operationWithoutFlip.appliedOperations.append(NovelAppliedOperationRecord(
            operationID: NovelOperationID(),
            kind: .discardChapter,
            payloadSHA256: NovelTestFixtures.hashA,
            outcome: .chapterDiscardStateChanged(
                projectID: current.project.id,
                branchID: branch.id,
                chapterID: chapter.id,
                isDiscarded: true,
                revision: operationWithoutFlip.project.revision
            ),
            appliedProjectRevision: operationWithoutFlip.project.revision,
            appliedAt: timestamp
        ))
        assertInvalidTransition(
            from: current,
            to: operationWithoutFlip,
            containing: "no matching state transition"
        )

        let discarded = try NovelReducer.apply(.discardChapter(NovelDiscardChapterCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: current.project.revision,
                expectedConfigRevision: nil,
                expectedBranchHeadRevision: branch.headRevision
            ),
            projectID: current.project.id,
            branchID: branch.id,
            chapterID: chapter.id
        )), to: current, now: timestamp).document
        var rewrittenTimestamp = discarded
        let secondTimestamp = Date(timeIntervalSince1970: 1_700_000_020)
        rewrittenTimestamp.project.revision += 1
        rewrittenTimestamp.project.updatedAt = secondTimestamp
        rewrittenTimestamp.chapters[0].discardedAt = secondTimestamp
        rewrittenTimestamp.appliedOperations.append(NovelAppliedOperationRecord(
            operationID: NovelOperationID(),
            kind: .discardChapter,
            payloadSHA256: NovelTestFixtures.hashB,
            outcome: .chapterDiscardStateChanged(
                projectID: current.project.id,
                branchID: branch.id,
                chapterID: chapter.id,
                isDiscarded: true,
                revision: rewrittenTimestamp.project.revision
            ),
            appliedProjectRevision: rewrittenTimestamp.project.revision,
            appliedAt: secondTimestamp
        ))
        assertInvalidTransition(
            from: discarded,
            to: rewrittenTimestamp,
            containing: "rewrote a chapter discard timestamp"
        )
    }

    func testMaterialRevisionCannotExistOutsideMaterialHistory() throws {
        let base = try NovelTestFixtures.document()
        var document = try NovelReducer.apply(
            NovelTestFixtures.materialAction(document: base),
            to: base
        ).document
        let first = document.materialRevisions[0]
        document.materialRevisions.append(NovelMaterialRevisionRecord(
            id: NovelMaterialRevisionID(),
            materialID: first.materialID,
            revision: 2,
            title: "Ghost",
            content: "Untracked",
            tags: [],
            injectionMode: .off,
            createdAt: first.createdAt,
            operationID: first.operationID
        ))

        assertInvalid(document, containing: "omitted from its material history")
    }

    func testProjectMustHaveOneInitialCheckpoint() throws {
        var document = try NovelTestFixtures.document()
        let initial = document.checkpoints[0]
        document.checkpoints.append(NovelBranchCheckpointRecord(
            id: NovelCheckpointID(),
            kind: .initial,
            createdOnBranchID: initial.createdOnBranchID,
            parentCheckpointID: nil,
            chapterSelections: [],
            stateSnapshotID: initial.stateSnapshotID,
            sessionCursor: .empty,
            branchOverrideRevisionIDs: [],
            sourceCandidateID: nil,
            baseHeadRevision: 0,
            operationID: initial.operationID,
            createdAt: initial.createdAt
        ))

        assertInvalid(document, containing: "exactly one initial checkpoint")
    }

    func testBranchHeadCannotPointIntoAnotherBranchLineage() throws {
        var document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let root = document.branches[0]
        let childID = NovelBranchID()
        let childSessionID = NovelSessionID()
        document.branches.append(NovelBranchRecord(
            id: childID,
            name: "Child",
            sessionID: childSessionID,
            createdAt: root.createdAt,
            updatedAt: root.updatedAt,
            forkOrigin: NovelForkOrigin(
                parentBranchID: root.id,
                checkpointID: root.headCheckpointID
            ),
            headCheckpointID: root.headCheckpointID,
            currentStateSnapshotID: root.currentStateSnapshotID,
            headRevision: 0,
            workingRevision: 0,
            syncStatus: .synchronized,
            lifecycle: .active,
            overrideRevisionIDs: [],
            workingChapterSelections: [],
            activeRunID: nil
        ))
        document.sessions.append(NovelSessionRecord(
            id: childSessionID,
            branchID: childID,
            revision: 0,
            messages: []
        ))
        let childCheckpoint = NovelBranchCheckpointRecord(
            id: NovelCheckpointID(),
            kind: .manualSync,
            createdOnBranchID: childID,
            parentCheckpointID: root.headCheckpointID,
            chapterSelections: [],
            stateSnapshotID: root.currentStateSnapshotID,
            sessionCursor: .empty,
            branchOverrideRevisionIDs: [],
            sourceCandidateID: nil,
            baseHeadRevision: 0,
            operationID: document.appliedOperations[0].operationID,
            createdAt: root.createdAt
        )
        document.checkpoints.append(childCheckpoint)
        document.branches[0].headCheckpointID = childCheckpoint.id
        document.branches[0].headRevision = 1

        assertInvalid(document, containing: "outside its checkpoint lineage")
    }

    func testPendingProposedVersionCannotLeakIntoFormalHistory() throws {
        var document = try NovelTestFixtures.document()
        let chapterID = NovelChapterID()
        let versionID = NovelChapterVersionID()
        document.chapters.append(NovelChapterRecord(id: chapterID, createdAt: Date()))
        document.chapterVersions.append(NovelChapterVersionRecord(
            id: versionID,
            chapterID: chapterID,
            kind: .collected,
            title: "Pending",
            content: "Not formal yet",
            factCompatibilityID: UUID(),
            sourceCandidateID: nil,
            createdAt: Date(),
            operationID: document.appliedOperations[0].operationID
        ))
        document.pendingOperations.append(NovelPendingOperationRecord(
            id: NovelPendingOperationID(),
            kind: .collection,
            status: .pending,
            branchID: document.branches[0].id,
            operationID: NovelOperationID(),
            payloadSHA256: NovelTestFixtures.hashB,
            baseCheckpointID: document.branches[0].headCheckpointID,
            baseHeadRevision: document.branches[0].headRevision,
            candidateID: nil,
            collectionTarget: nil,
            selectedText: "Not formal yet",
            proposedChapterVersion: document.chapterVersions[0],
            createdAt: Date(),
            lastError: nil
        ))

        assertInvalid(document, containing: "leaked its proposed version")
    }

    func testRunningRunMustMatchBranchSessionAndReceipt() throws {
        var document = try NovelTestFixtures.document()
        let branch = document.branches[0]
        let runID = NovelRunID()
        let receiptID = NovelReceiptID()
        let injectionReceiptID = NovelReceiptID()
        let injectionPlan = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: branch.id,
                promptKind: .discussion,
                userText: "Discuss"
            )
        )
        document.injectionReceipts.append(NovelInjectionReceiptRecord(
            id: injectionReceiptID,
            runID: runID,
            projectID: document.project.id,
            branchID: branch.id,
            plan: injectionPlan,
            overrides: .none,
            providerID: "provider",
            modelID: "model",
            parameters: [:],
            createdAt: Date()
        ))
        document.generationReceipts.append(NovelGenerationReceiptRecord(
            id: receiptID,
            runID: runID,
            providerID: "provider",
            modelID: "model",
            promptVersion: injectionPlan.prompt.version,
            injectionReceiptID: injectionReceiptID,
            parameters: [:],
            requestSHA256: NovelTestFixtures.hashA,
            createdAt: Date()
        ))
        document.activeRuns.append(NovelActiveRunRecord(
            id: runID,
            operationID: NovelOperationID(),
            requestPayloadSHA256: NovelTestFixtures.hashA,
            branchID: branch.id,
            sessionID: NovelSessionID(),
            kind: .discussion,
            mode: .discussPlan,
            granularity: nil,
            userMessageID: NovelMessageID(),
            messageID: NovelMessageID(),
            candidateID: nil,
            sourceChapterVersionID: nil,
            contextualCharacterMention: nil,
            baseCheckpointID: branch.headCheckpointID,
            baseHeadRevision: branch.headRevision,
            status: .running,
            partialContent: "",
            receiptID: receiptID,
            startedAt: Date(),
            terminalAt: nil,
            interruptionReason: nil,
            terminalFailure: nil,
            chapterPlanDigest: nil
        ))
        document.branches[0].activeRunID = runID

        assertInvalid(document, containing: "invalid branch or Session")
    }

    func testFactRequestReceiptsCannotReuseRunID() throws {
        var document = try documentWithPendingCollection()
        let pendingID = try XCTUnwrap(document.pendingOperations.first?.id)
        let runID = NovelRunID()
        let first = try NovelTestFixtures.factTransactionArtifacts(
            document: document,
            pendingID: pendingID,
            runID: runID
        )
        document = try NovelFactRequestReceiptReducer.reserve(
            first,
            pendingID: pendingID,
            in: document
        )
        let duplicate = try NovelTestFixtures.factTransactionArtifacts(
            document: document,
            pendingID: pendingID,
            runID: runID
        )
        document.injectionReceipts.append(duplicate.injectionReceipt)
        document.generationReceipts.append(duplicate.generationReceipt)

        assertInvalid(document, containing: "reuse a request run ID")
    }

    // MARK: - 2026-07-25 fact receipt Prompt version regression
    //
    // Root cause: the receipt records which Prompt version a past request actually used
    // (an immutable historical fact). Comparing it against the *current* template version
    // instead of the set of every version ever shipped judges every already-persisted
    // project as corrupted the moment the template version advances. These guard the fix
    // in `NovelPromptCatalog.acceptedVersions` and the two validators that consume it.

    func testFactInjectionReceiptAcceptsHistoricalStateDeltaPromptVersion() throws {
        let (document, injectionIndex, generationIndex) =
            try documentWithReservedStateDeltaFactReceipt()
        var mutated = document
        let historicalVersion = "novel.state-delta.v1"
        mutated.injectionReceipts[injectionIndex] = try receiptChangingPromptVersion(
            mutated.injectionReceipts[injectionIndex],
            to: historicalVersion
        )
        mutated.generationReceipts[generationIndex] = try receiptChangingPromptVersion(
            mutated.generationReceipts[generationIndex],
            to: historicalVersion
        )

        XCTAssertNoThrow(try NovelDocumentValidator.validate(mutated))
    }

    func testFactInjectionReceiptRejectsNeverPublishedPromptVersion() throws {
        let (document, injectionIndex, generationIndex) =
            try documentWithReservedStateDeltaFactReceipt()
        var mutated = document
        let garbageVersion = "novel.state-delta.v99"
        mutated.injectionReceipts[injectionIndex] = try receiptChangingPromptVersion(
            mutated.injectionReceipts[injectionIndex],
            to: garbageVersion
        )
        mutated.generationReceipts[generationIndex] = try receiptChangingPromptVersion(
            mutated.generationReceipts[generationIndex],
            to: garbageVersion
        )

        assertInvalid(mutated, containing: "wrong fact Prompt version")
    }

    func testAcceptedPromptVersionsAlwaysIncludeTheCurrentTemplateVersion() {
        for kind in NovelPromptKind.allCases {
            let current = NovelPromptCatalog.template(for: kind).version
            XCTAssertTrue(
                NovelPromptCatalog.acceptedVersions(for: kind).contains(current),
                "acceptedVersions(for: \(kind)) must contain its current template version \(current)"
            )
        }
    }

    private func documentWithReservedStateDeltaFactReceipt() throws -> (
        document: NovelProjectDocumentV1,
        injectionIndex: Int,
        generationIndex: Int
    ) {
        let base = try documentWithPendingCollection()
        let pendingID = try XCTUnwrap(base.pendingOperations.first?.id)
        let artifacts = try NovelTestFixtures.factTransactionArtifacts(
            document: base,
            pendingID: pendingID
        )
        let reserved = try NovelFactRequestReceiptReducer.reserve(
            artifacts,
            pendingID: pendingID,
            in: base
        )
        let injectionIndex = try XCTUnwrap(reserved.injectionReceipts.firstIndex {
            $0.id == artifacts.injectionReceipt.id
        })
        let generationIndex = try XCTUnwrap(reserved.generationReceipts.firstIndex {
            $0.id == artifacts.generationReceipt.id
        })
        return (reserved, injectionIndex, generationIndex)
    }

    private func receiptChangingPromptVersion<T: Codable>(
        _ receipt: T,
        to promptVersion: String
    ) throws -> T {
        let data = try JSONEncoder().encode(receipt)
        var object = try XCTUnwrap(
            try JSONSerialization.jsonObject(with: data) as? [String: Any]
        )
        object["promptVersion"] = promptVersion
        return try JSONDecoder().decode(
            T.self,
            from: JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        )
    }

    func testPendingCollectionPayloadCannotBeRewrittenAcrossTransition() throws {
        let current = try documentWithPendingCollection()
        var next = current
        let pending = next.pendingOperations[0]
        let proposed = try XCTUnwrap(pending.proposedChapterVersion)
        let rewrittenText = "Rewritten pending text"
        next.pendingOperations[0] = NovelPendingOperationRecord(
            id: pending.id,
            kind: pending.kind,
            status: .retryable,
            branchID: pending.branchID,
            operationID: pending.operationID,
            payloadSHA256: pending.payloadSHA256,
            baseCheckpointID: pending.baseCheckpointID,
            baseHeadRevision: pending.baseHeadRevision,
            baseWorkingRevision: pending.baseWorkingRevision,
            candidateID: pending.candidateID,
            collectionTarget: pending.collectionTarget,
            selectedText: rewrittenText,
            proposedChapterVersion: NovelChapterVersionRecord(
                id: proposed.id,
                chapterID: proposed.chapterID,
                kind: proposed.kind,
                title: proposed.title,
                content: rewrittenText,
                factCompatibilityID: proposed.factCompatibilityID,
                sourceCandidateID: proposed.sourceCandidateID,
                createdAt: proposed.createdAt,
                operationID: proposed.operationID
            ),
            proposedCheckpointID: pending.proposedCheckpointID,
            proposedStateSnapshotID: pending.proposedStateSnapshotID,
            rebuildBaseCheckpointID: pending.rebuildBaseCheckpointID,
            sessionCursor: pending.sessionCursor,
            createdAt: pending.createdAt,
            lastError: "retry"
        )
        next.project.revision += 1
        next.project.updatedAt = next.project.updatedAt.addingTimeInterval(1)

        XCTAssertNoThrow(try NovelDocumentValidator.validate(next))
        XCTAssertThrowsError(try NovelDocumentValidator.validateTransition(from: current, to: next)) { error in
            guard let novelError = error as? NovelError,
                  case .invalidDocument(let issues) = novelError else {
                return XCTFail("Expected invalidDocument, got \(error)")
            }
            XCTAssertTrue(issues.contains(where: { $0.contains("rewrote pending operation") }))
        }
    }

    func testPendingAppendStoresTheFullProposedChapterVersion() throws {
        let valid = try documentWithPendingAppend()
        XCTAssertNoThrow(try NovelDocumentValidator.validate(valid))
        var invalid = valid
        let pending = invalid.pendingOperations[0]
        let proposed = try XCTUnwrap(pending.proposedChapterVersion)
        invalid.pendingOperations[0] = NovelPendingOperationRecord(
            id: pending.id,
            kind: pending.kind,
            status: pending.status,
            branchID: pending.branchID,
            operationID: pending.operationID,
            payloadSHA256: pending.payloadSHA256,
            baseCheckpointID: pending.baseCheckpointID,
            baseHeadRevision: pending.baseHeadRevision,
            baseWorkingRevision: pending.baseWorkingRevision,
            candidateID: pending.candidateID,
            collectionTarget: pending.collectionTarget,
            selectedText: pending.selectedText,
            proposedChapterVersion: NovelChapterVersionRecord(
                id: proposed.id,
                chapterID: proposed.chapterID,
                kind: proposed.kind,
                title: proposed.title,
                content: pending.selectedText,
                factCompatibilityID: proposed.factCompatibilityID,
                sourceCandidateID: proposed.sourceCandidateID,
                createdAt: proposed.createdAt,
                operationID: proposed.operationID
            ),
            proposedCheckpointID: pending.proposedCheckpointID,
            proposedStateSnapshotID: pending.proposedStateSnapshotID,
            rebuildBaseCheckpointID: pending.rebuildBaseCheckpointID,
            sessionCursor: pending.sessionCursor,
            createdAt: pending.createdAt,
            lastError: pending.lastError
        )

        assertInvalid(invalid, containing: "invalid appended chapter version")
    }

    func testPendingCollectionsCannotReuseOperationCandidateOrProposedVersion() throws {
        var document = try documentWithPendingCollection()
        let pending = document.pendingOperations[0]
        document.pendingOperations.append(NovelPendingOperationRecord(
            id: NovelPendingOperationID(),
            kind: pending.kind,
            status: pending.status,
            branchID: pending.branchID,
            operationID: pending.operationID,
            payloadSHA256: pending.payloadSHA256,
            baseCheckpointID: pending.baseCheckpointID,
            baseHeadRevision: pending.baseHeadRevision,
            baseWorkingRevision: pending.baseWorkingRevision,
            candidateID: pending.candidateID,
            collectionTarget: pending.collectionTarget,
            selectedText: pending.selectedText,
            proposedChapterVersion: pending.proposedChapterVersion,
            proposedCheckpointID: pending.proposedCheckpointID,
            proposedStateSnapshotID: pending.proposedStateSnapshotID,
            rebuildBaseCheckpointID: pending.rebuildBaseCheckpointID,
            sessionCursor: pending.sessionCursor,
            createdAt: pending.createdAt,
            lastError: pending.lastError
        ))

        assertInvalid(document, containing: "repeat an operation ID")
        assertInvalid(document, containing: "multiple pending collection")
        assertInvalid(document, containing: "repeat a proposed chapter version")
    }

    func testBranchCannotForkFromInternalInitialCheckpoint() throws {
        var document = try NovelTestFixtures.document()
        let root = document.branches[0]
        let childID = NovelBranchID()
        let childSessionID = NovelSessionID()
        document.branches.append(NovelBranchRecord(
            id: childID,
            name: "Invalid Fork",
            sessionID: childSessionID,
            createdAt: root.createdAt,
            updatedAt: root.updatedAt,
            forkOrigin: NovelForkOrigin(
                parentBranchID: root.id,
                checkpointID: root.headCheckpointID
            ),
            headCheckpointID: root.headCheckpointID,
            currentStateSnapshotID: root.currentStateSnapshotID,
            headRevision: 0,
            workingRevision: 0,
            syncStatus: .synchronized,
            lifecycle: .active,
            overrideRevisionIDs: [],
            workingChapterSelections: [],
            activeRunID: nil
        ))
        document.sessions.append(NovelSessionRecord(
            id: childSessionID,
            branchID: childID,
            revision: 0,
            messages: []
        ))

        assertInvalid(document, containing: "cannot fork from the internal initial checkpoint")
    }

    func testAbandonedCheckpointMustRemainInItsCreatingBranchLineage() throws {
        var document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let root = document.branches[0]
        let childID = NovelBranchID()
        let childSessionID = NovelSessionID()
        document.branches.append(NovelBranchRecord(
            id: childID,
            name: "Child",
            sessionID: childSessionID,
            createdAt: root.createdAt,
            updatedAt: root.updatedAt,
            forkOrigin: NovelForkOrigin(
                parentBranchID: root.id,
                checkpointID: root.headCheckpointID
            ),
            headCheckpointID: root.headCheckpointID,
            currentStateSnapshotID: root.currentStateSnapshotID,
            headRevision: 0,
            workingRevision: 0,
            syncStatus: .synchronized,
            lifecycle: .active,
            overrideRevisionIDs: [],
            workingChapterSelections: [],
            activeRunID: nil
        ))
        document.sessions.append(NovelSessionRecord(
            id: childSessionID,
            branchID: childID,
            revision: 0,
            messages: []
        ))
        let initial = try XCTUnwrap(document.checkpoints.first(where: { $0.kind == .initial }))
        document.checkpoints.append(NovelBranchCheckpointRecord(
            id: NovelCheckpointID(),
            kind: .manualSync,
            createdOnBranchID: childID,
            parentCheckpointID: initial.id,
            chapterSelections: [],
            stateSnapshotID: root.currentStateSnapshotID,
            sessionCursor: .empty,
            branchOverrideRevisionIDs: [],
            sourceCandidateID: nil,
            baseHeadRevision: 0,
            operationID: document.appliedOperations[0].operationID,
            createdAt: root.updatedAt
        ))

        assertInvalid(document, containing: "outside its creating branch lineage")
    }

    func testAvailableCandidateBaseMustRemainInItsBranchLineage() throws {
        var document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let root = document.branches[0]
        let childID = NovelBranchID()
        let childSessionID = NovelSessionID()
        let childCheckpointID = NovelCheckpointID()
        document.branches.append(NovelBranchRecord(
            id: childID,
            name: "Child",
            sessionID: childSessionID,
            createdAt: root.createdAt,
            updatedAt: root.updatedAt,
            forkOrigin: NovelForkOrigin(
                parentBranchID: root.id,
                checkpointID: root.headCheckpointID
            ),
            headCheckpointID: childCheckpointID,
            currentStateSnapshotID: root.currentStateSnapshotID,
            headRevision: 1,
            workingRevision: 1,
            syncStatus: .synchronized,
            lifecycle: .active,
            overrideRevisionIDs: [],
            workingChapterSelections: [],
            activeRunID: nil
        ))
        document.sessions.append(NovelSessionRecord(
            id: childSessionID,
            branchID: childID,
            revision: 0,
            messages: []
        ))
        document.checkpoints.append(NovelBranchCheckpointRecord(
            id: childCheckpointID,
            kind: .manualSync,
            createdOnBranchID: childID,
            parentCheckpointID: root.headCheckpointID,
            chapterSelections: [],
            stateSnapshotID: root.currentStateSnapshotID,
            sessionCursor: .empty,
            branchOverrideRevisionIDs: [],
            sourceCandidateID: nil,
            baseHeadRevision: 0,
            operationID: document.appliedOperations[0].operationID,
            createdAt: root.updatedAt
        ))

        let candidateID = NovelCandidateID()
        let messageID = NovelMessageID()
        document.sessions[0].revision = 1
        document.sessions[0].messages = [NovelSessionMessageRecord(
            id: messageID,
            sequence: 0,
            role: .assistant,
            mode: .writeProse,
            kind: .proseCandidate,
            content: "Cross-branch prose",
            createdAt: root.updatedAt,
            runID: nil,
            candidateID: candidateID
        )]
        document.candidates.append(NovelCandidateRecord(
            id: candidateID,
            kind: .prose,
            branchID: root.id,
            sessionID: root.sessionID,
            sourceMessageID: messageID,
            baseCheckpointID: childCheckpointID,
            baseHeadRevision: root.headRevision,
            status: .available,
            content: "Cross-branch prose",
            sourceChapterVersionID: nil,
            collectedCheckpointID: nil,
            createdAt: root.updatedAt
        ))

        assertInvalid(document, containing: "base is outside its branch lineage")
    }

    func testPendingCollectionBaseMustMatchCurrentBranchHead() throws {
        var document = try documentWithPendingCollection()
        let candidate = document.candidates[0]
        document.candidates[0] = NovelCandidateRecord(
            id: candidate.id,
            kind: candidate.kind,
            branchID: candidate.branchID,
            sessionID: candidate.sessionID,
            sourceMessageID: candidate.sourceMessageID,
            baseCheckpointID: candidate.baseCheckpointID,
            baseHeadRevision: candidate.baseHeadRevision + 1,
            status: candidate.status,
            content: candidate.content,
            sourceChapterVersionID: candidate.sourceChapterVersionID,
            collectedCheckpointID: candidate.collectedCheckpointID,
            createdAt: candidate.createdAt
        )
        let pending = document.pendingOperations[0]
        document.pendingOperations[0] = NovelPendingOperationRecord(
            id: pending.id,
            kind: pending.kind,
            status: pending.status,
            branchID: pending.branchID,
            operationID: pending.operationID,
            payloadSHA256: pending.payloadSHA256,
            baseCheckpointID: pending.baseCheckpointID,
            baseHeadRevision: pending.baseHeadRevision + 1,
            baseWorkingRevision: pending.baseWorkingRevision,
            candidateID: pending.candidateID,
            collectionTarget: pending.collectionTarget,
            selectedText: pending.selectedText,
            proposedChapterVersion: pending.proposedChapterVersion,
            proposedCheckpointID: pending.proposedCheckpointID,
            proposedStateSnapshotID: pending.proposedStateSnapshotID,
            rebuildBaseCheckpointID: pending.rebuildBaseCheckpointID,
            sessionCursor: pending.sessionCursor,
            createdAt: pending.createdAt,
            lastError: pending.lastError
        )

        assertInvalid(document, containing: "stale for its branch head")
    }

    private func documentWithPendingCollection() throws -> NovelProjectDocumentV1 {
        var document = try NovelTestFixtures.document()
        let branch = document.branches[0]
        let candidateID = NovelCandidateID()
        let messageID = NovelMessageID()
        let operationID = NovelOperationID()
        let chapterID = NovelChapterID()
        let text = "Pending chapter text"
        document.sessions[0].messages = [NovelSessionMessageRecord(
            id: messageID,
            sequence: 0,
            role: .assistant,
            mode: .writeProse,
            kind: .proseCandidate,
            content: text,
            createdAt: document.project.updatedAt,
            runID: nil,
            candidateID: candidateID
        )]
        document.sessions[0].revision = 1
        document.candidates.append(NovelCandidateRecord(
            id: candidateID,
            kind: .prose,
            branchID: branch.id,
            sessionID: branch.sessionID,
            sourceMessageID: messageID,
            baseCheckpointID: branch.headCheckpointID,
            baseHeadRevision: branch.headRevision,
            status: .available,
            content: text,
            sourceChapterVersionID: nil,
            collectedCheckpointID: nil,
            createdAt: document.project.updatedAt
        ))
        document.pendingOperations.append(NovelPendingOperationRecord(
            id: NovelPendingOperationID(),
            kind: .collection,
            status: .pending,
            branchID: branch.id,
            operationID: operationID,
            payloadSHA256: NovelTestFixtures.hashB,
            baseCheckpointID: branch.headCheckpointID,
            baseHeadRevision: branch.headRevision,
            baseWorkingRevision: branch.workingRevision,
            candidateID: candidateID,
            collectionTarget: .createNextChapter(chapterID: chapterID, title: "Chapter One"),
            selectedText: text,
            proposedChapterVersion: NovelChapterVersionRecord(
                id: NovelChapterVersionID(),
                chapterID: chapterID,
                kind: .collected,
                title: "Chapter One",
                content: text,
                factCompatibilityID: UUID(),
                sourceCandidateID: candidateID,
                createdAt: document.project.updatedAt,
                operationID: operationID
            ),
            proposedCheckpointID: NovelCheckpointID(),
            proposedStateSnapshotID: NovelStateSnapshotID(),
            rebuildBaseCheckpointID: nil,
            sessionCursor: .through(sequence: 0),
            createdAt: document.project.updatedAt,
            lastError: nil
        ))
        try NovelDocumentValidator.validate(document)
        return document
    }

    private func documentWithPendingAppend() throws -> NovelProjectDocumentV1 {
        var document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let branch = document.branches[0]
        let checkpointIndex = try XCTUnwrap(document.checkpoints.firstIndex(where: {
            $0.id == branch.headCheckpointID
        }))
        let checkpoint = document.checkpoints[checkpointIndex]
        let chapterID = NovelChapterID()
        let baseVersionID = NovelChapterVersionID()
        let baseContent = "Existing chapter"
        let addition = "New selected prose"
        document.chapters.append(NovelChapterRecord(
            id: chapterID,
            createdAt: document.project.updatedAt
        ))
        document.chapterVersions.append(NovelChapterVersionRecord(
            id: baseVersionID,
            chapterID: chapterID,
            kind: .collected,
            title: "Chapter One",
            content: baseContent,
            factCompatibilityID: UUID(),
            sourceCandidateID: nil,
            createdAt: document.project.updatedAt,
            operationID: document.appliedOperations[0].operationID
        ))
        let selection = NovelChapterSelection(chapterID: chapterID, versionID: baseVersionID)
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

        let candidateID = NovelCandidateID()
        let messageID = NovelMessageID()
        let operationID = NovelOperationID()
        document.sessions[0].messages = [NovelSessionMessageRecord(
            id: messageID,
            sequence: 0,
            role: .assistant,
            mode: .writeProse,
            kind: .proseCandidate,
            content: addition,
            createdAt: document.project.updatedAt,
            runID: nil,
            candidateID: candidateID
        )]
        document.sessions[0].revision = 1
        document.candidates.append(NovelCandidateRecord(
            id: candidateID,
            kind: .prose,
            branchID: branch.id,
            sessionID: branch.sessionID,
            sourceMessageID: messageID,
            baseCheckpointID: branch.headCheckpointID,
            baseHeadRevision: branch.headRevision,
            status: .available,
            content: addition,
            sourceChapterVersionID: nil,
            collectedCheckpointID: nil,
            createdAt: document.project.updatedAt
        ))
        document.pendingOperations.append(NovelPendingOperationRecord(
            id: NovelPendingOperationID(),
            kind: .collection,
            status: .pending,
            branchID: branch.id,
            operationID: operationID,
            payloadSHA256: NovelTestFixtures.hashC,
            baseCheckpointID: branch.headCheckpointID,
            baseHeadRevision: branch.headRevision,
            baseWorkingRevision: branch.workingRevision,
            candidateID: candidateID,
            collectionTarget: .appendToChapter(chapterID),
            selectedText: addition,
            proposedChapterVersion: NovelChapterVersionRecord(
                id: NovelChapterVersionID(),
                chapterID: chapterID,
                kind: .collected,
                title: "Chapter One",
                content: NovelChapterText.appending(addition, to: baseContent),
                factCompatibilityID: UUID(),
                sourceChapterVersionID: baseVersionID,
                sourceCandidateID: candidateID,
                createdAt: document.project.updatedAt,
                operationID: operationID
            ),
            proposedCheckpointID: NovelCheckpointID(),
            proposedStateSnapshotID: NovelStateSnapshotID(),
            rebuildBaseCheckpointID: nil,
            sessionCursor: .through(sequence: 0),
            createdAt: document.project.updatedAt,
            lastError: nil
        ))
        try NovelDocumentValidator.validate(document)
        return document
    }

    private func assertInvalid(
        _ document: NovelProjectDocumentV1,
        containing expected: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertThrowsError(try NovelDocumentValidator.validate(document), file: file, line: line) { error in
            guard let novelError = error as? NovelError,
                  case .invalidDocument(let issues) = novelError else {
                return XCTFail("Expected invalidDocument, got \(error)", file: file, line: line)
            }
            XCTAssertTrue(
                issues.contains(where: { $0.localizedCaseInsensitiveContains(expected) }),
                "Expected an issue containing '\(expected)', got \(issues)",
                file: file,
                line: line
            )
        }
    }

    private func documentWithDiscardableChapter() throws -> NovelProjectDocumentV1 {
        var document = try NovelTestFixtures.document()
        let chapterID = NovelChapterID()
        let versionID = NovelChapterVersionID()
        document.chapters.append(NovelChapterRecord(
            id: chapterID,
            createdAt: document.project.updatedAt
        ))
        document.chapterVersions.append(NovelChapterVersionRecord(
            id: versionID,
            chapterID: chapterID,
            kind: .collected,
            title: "第一章",
            content: "雾从站台尽头涌来。",
            factCompatibilityID: UUID(),
            sourceCandidateID: nil,
            createdAt: document.project.updatedAt,
            operationID: document.appliedOperations[0].operationID
        ))
        // 正文删除测试需要它在工作稿目录中；废弃/恢复仅翻 discardedAt，不依赖选择。
        if let branchIndex = document.branches.indices.first {
            let selection = NovelChapterSelection(chapterID: chapterID, versionID: versionID)
            document.branches[branchIndex].workingChapterSelections = [selection]
            // 与 head 选择对齐，避免 synchronized 分支「工作稿与 head 不一致」。
            if let headIndex = document.checkpoints.firstIndex(where: {
                $0.id == document.branches[branchIndex].headCheckpointID
            }) {
                let head = document.checkpoints[headIndex]
                document.checkpoints[headIndex] = NovelBranchCheckpointRecord(
                    id: head.id,
                    kind: head.kind,
                    createdOnBranchID: head.createdOnBranchID,
                    parentCheckpointID: head.parentCheckpointID,
                    chapterSelections: [selection],
                    stateSnapshotID: head.stateSnapshotID,
                    sessionCursor: head.sessionCursor,
                    branchOverrideRevisionIDs: head.branchOverrideRevisionIDs,
                    sourceCandidateID: head.sourceCandidateID,
                    baseHeadRevision: head.baseHeadRevision,
                    operationID: head.operationID,
                    createdAt: head.createdAt
                )
            }
        }
        try NovelDocumentValidator.validate(document)
        return document
    }

    private func assertInvalidTransition(
        from current: NovelProjectDocumentV1,
        to next: NovelProjectDocumentV1,
        containing expected: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertThrowsError(
            try NovelDocumentValidator.validateTransition(from: current, to: next),
            file: file,
            line: line
        ) { error in
            guard let novelError = error as? NovelError,
                  case .invalidDocument(let issues) = novelError else {
                return XCTFail("Expected invalidDocument, got \(error)", file: file, line: line)
            }
            XCTAssertTrue(
                issues.contains(where: { $0.localizedCaseInsensitiveContains(expected) }),
                "Expected an issue containing '\(expected)', got \(issues)",
                file: file,
                line: line
            )
        }
    }
}
