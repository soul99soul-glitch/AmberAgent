import XCTest
@testable import iosApp

final class NovelCollectionTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_700_100_000)

    func testParagraphIDsAreStableAndDuplicateParagraphsDoNotCollide() throws {
        let content = "First paragraph.\r\n\r\nRepeated.\r\n\r\nRepeated."
        let first = NovelParagraphParser.paragraphs(in: content)
        let second = NovelParagraphParser.paragraphs(
            in: "First paragraph.\n\nRepeated.\n\nRepeated."
        )

        XCTAssertEqual(first, second)
        XCTAssertEqual(first.count, 3)
        XCTAssertEqual(Set(first.map(\.id)).count, 3)
        XCTAssertNotEqual(first[1].id, first[2].id)
        XCTAssertEqual(
            try NovelParagraphParser.selectedText(
                for: NovelParagraphSelection(paragraphIDs: [first[2].id, first[0].id]),
                in: content
            ),
            "First paragraph.\n\nRepeated."
        )
    }

    func testPrepareDoesNotLeakAndFinalizeAtomicallyCommitsAllFactKinds() throws {
        let candidateDocument = try documentWithCandidate(
            "Mara opened the sealed door.\n\nIvo promised to return."
        )
        let command = collectCommand(document: candidateDocument)
        let prepared = try NovelFactTransactionReducer.prepareCollection(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: candidateDocument,
            now: now
        )

        XCTAssertEqual(prepared.document.chapters, candidateDocument.chapters)
        XCTAssertEqual(prepared.document.chapterVersions, candidateDocument.chapterVersions)
        XCTAssertEqual(prepared.document.events, candidateDocument.events)
        XCTAssertEqual(prepared.document.stateSnapshots, candidateDocument.stateSnapshots)
        XCTAssertEqual(prepared.document.checkpoints, candidateDocument.checkpoints)
        XCTAssertEqual(prepared.document.materials, candidateDocument.materials)
        XCTAssertEqual(prepared.pending.selectedText, candidateDocument.candidates[0].content)
        XCTAssertEqual(prepared.pending.proposedCheckpointID, command.checkpointID)
        XCTAssertEqual(prepared.pending.proposedStateSnapshotID, command.stateSnapshotID)
        XCTAssertNil(prepared.pending.proposedChapterVersion?.sourceChapterVersionID)
        XCTAssertNoThrow(try NovelInjectionPlanner.plan(
            document: prepared.document,
            request: NovelInjectionPlanningRequest(
                branchID: command.branchID,
                promptKind: .discussion,
                userText: "Discuss while extraction is pending."
            )
        ))
        XCTAssertThrowsError(try NovelInjectionPlanner.plan(
            document: prepared.document,
            request: NovelInjectionPlanningRequest(
                branchID: command.branchID,
                promptKind: .proseContinuation,
                userText: "Do not generate from stale state."
            )
        )) { error in
            guard case .branchRequiresSync(let branchID) = error as? NovelInjectionPlanningError else {
                return XCTFail("Expected branchRequiresSync, got \(error)")
            }
            XCTAssertEqual(branchID, command.branchID)
        }

        let committed = try NovelFactTransactionReducer.finalizeCollection(
            pendingID: command.pendingID,
            delta: stateDelta(),
            artifacts: try NovelTestFixtures.factTransactionArtifacts(
                document: prepared.document,
                pendingID: command.pendingID
            ),
            in: prepared.document,
            now: now.addingTimeInterval(1)
        )
        let checkpoint = try XCTUnwrap(committed.document.checkpoints.last)
        let snapshot = try XCTUnwrap(committed.document.stateSnapshots.last)

        XCTAssertTrue(committed.document.pendingOperations.isEmpty)
        XCTAssertEqual(committed.document.chapters.count, 1)
        XCTAssertEqual(committed.document.chapterVersions.count, 1)
        XCTAssertEqual(snapshot.eventIDs.count, 4)
        XCTAssertEqual(committed.document.events.map(\.kind), [
            "discovery", "character.resolve", "relationship.ally", "foreshadowing.introduced"
        ])
        XCTAssertEqual(Set(committed.document.events.map(\.sequence)).count, 4)
        XCTAssertEqual(checkpoint.kind, .collection)
        XCTAssertEqual(checkpoint.sourceCandidateID, command.candidateID)
        XCTAssertEqual(
            checkpoint.sessionCursor,
            .through(sequence: candidateDocument.sessions[0].messages.last!.sequence)
        )
        XCTAssertEqual(committed.document.candidates[0].status, .collected)
        XCTAssertEqual(committed.document.candidates[0].collectedCheckpointID, checkpoint.id)
        XCTAssertEqual(
            committed.document.sessions[0].revision,
            candidateDocument.sessions[0].revision + 1
        )
        XCTAssertEqual(committed.document.settingProposals.count, 1)
        XCTAssertEqual(committed.document.settingProposals[0].origin, .derivedState)
        XCTAssertTrue(committed.document.materials.isEmpty)
        XCTAssertTrue(committed.document.materialRevisions.isEmpty)
        XCTAssertEqual(committed.document.project.configRevision, candidateDocument.project.configRevision)
    }

    func testStoryEventSequenceFollowsEvidenceAcrossFactCategories() throws {
        let candidateDocument = try documentWithCandidate(
            "Mara trembled. Ivo offered his hand. The bell rang."
        )
        let command = collectCommand(document: candidateDocument)
        let prepared = try NovelFactTransactionReducer.prepareCollection(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: candidateDocument,
            now: now
        )
        let delta = NovelStateDeltaV1(
            schemaVersion: 1,
            stateSummary: "Mara accepts Ivo's help before the bell rings.",
            events: [NovelStateEventV1(
                id: "event-bell",
                kind: "signal",
                summary: "The bell rang.",
                entityReferences: [],
                evidence: "The bell rang."
            )],
            characterChanges: [NovelCharacterStateFactV1(
                id: "character-mara",
                characterName: "Mara",
                attribute: "composure",
                value: "shaken",
                evidence: "Mara trembled."
            )],
            relationshipChanges: [NovelRelationshipFactV1(
                id: "relationship-help",
                sourceEntity: "Ivo",
                targetEntity: "Mara",
                relationship: "ally",
                state: "offered help",
                evidence: "Ivo offered his hand."
            )],
            foreshadowingChanges: [],
            unresolvedEntityNames: ["Mara", "Ivo"],
            branchOutlinePatch: "Mara and Ivo respond to the bell together.",
            settingProposals: []
        )

        let committed = try NovelFactTransactionReducer.finalizeCollection(
            pendingID: command.pendingID,
            delta: delta,
            artifacts: try NovelTestFixtures.factTransactionArtifacts(
                document: prepared.document,
                pendingID: command.pendingID
            ),
            in: prepared.document,
            now: now.addingTimeInterval(1)
        ).document
        let appended = Array(committed.events.suffix(3))

        XCTAssertEqual(appended.map(\.kind), [
            "character.composure", "relationship.ally", "signal"
        ])
        XCTAssertEqual(appended.map(\.sequence), appended.map(\.sequence).sorted())
    }

    func testCollectionDerivesMissingUnresolvedAliasesFromEvidenceBackedReferences() throws {
        let manuscript = "朱重九醒来后，朱重八让他照看伤兵。"
        let document = try documentWithCandidate(manuscript)
        let command = collectCommand(document: document)
        let prepared = try NovelFactTransactionReducer.prepareCollection(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: document,
            now: now
        )
        let delta = NovelStateDeltaV1(
            schemaVersion: 1,
            stateSummary: "朱重九开始照看伤兵。",
            events: [NovelStateEventV1(
                id: "care-for-wounded",
                kind: "assignment",
                summary: "朱重八让朱重九照看伤兵。",
                entityReferences: ["朱重八", "朱重九"],
                evidence: manuscript
            )],
            characterChanges: [],
            relationshipChanges: [],
            foreshadowingChanges: [],
            unresolvedEntityNames: [],
            branchOutlinePatch: nil,
            settingProposals: []
        )

        let committed = try NovelFactTransactionReducer.finalizeCollection(
            pendingID: command.pendingID,
            delta: delta,
            artifacts: try NovelTestFixtures.factTransactionArtifacts(
                document: prepared.document,
                pendingID: command.pendingID
            ),
            in: prepared.document,
            now: now.addingTimeInterval(1)
        ).document

        XCTAssertEqual(committed.stateSnapshots.last?.unresolvedEntityNames, ["朱重八", "朱重九"])
        XCTAssertTrue(committed.pendingOperations.isEmpty)
    }

    func testRetryKeepsDurablePayloadAndRecordsOriginalAndRetryLedgers() throws {
        let candidateDocument = try documentWithCandidate("Mara crossed the threshold.")
        let command = collectCommand(document: candidateDocument)
        let prepared = try NovelFactTransactionReducer.prepareCollection(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: candidateDocument,
            now: now
        )
        let duplicatePrepare = try NovelFactTransactionReducer.prepareCollection(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: prepared.document,
            now: now.addingTimeInterval(1)
        )
        XCTAssertEqual(duplicatePrepare.document, prepared.document)

        let retryable = try NovelFactTransactionReducer.markRetryable(
            pendingID: command.pendingID,
            message: "The project write failed.",
            in: prepared.document,
            now: now.addingTimeInterval(2)
        )
        let retained = try XCTUnwrap(retryable.pendingOperations.first)
        XCTAssertEqual(retained.status, .retryable)
        XCTAssertEqual(retained.selectedText, prepared.pending.selectedText)
        XCTAssertEqual(retained.proposedChapterVersion, prepared.pending.proposedChapterVersion)
        XCTAssertEqual(retained.proposedCheckpointID, prepared.pending.proposedCheckpointID)
        XCTAssertEqual(retained.proposedStateSnapshotID, prepared.pending.proposedStateSnapshotID)

        let staleRetry = NovelRetryPendingCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: retryable.project.revision - 1,
                expectedConfigRevision: retryable.project.configRevision,
                expectedBranchHeadRevision: retryable.branches[0].headRevision
            ),
            projectID: retryable.project.id,
            pendingID: command.pendingID
        )
        XCTAssertThrowsError(try NovelFactTransactionReducer.validateRetryCommand(
            staleRetry,
            in: retryable
        )) { error in
            guard case .staleProjectRevision = error as? NovelError else {
                return XCTFail("Expected staleProjectRevision, got \(error)")
            }
        }

        let retry = NovelRetryPendingCommand(
            context: mutationContext(
                document: retryable,
                operationID: NovelOperationID()
            ),
            projectID: retryable.project.id,
            pendingID: command.pendingID
        )
        XCTAssertEqual(
            try NovelFactTransactionReducer.validateRetryCommand(retry, in: retryable).id,
            command.pendingID
        )
        let committed = try NovelFactTransactionReducer.finalizeCollection(
            pendingID: command.pendingID,
            delta: singleEventDelta(evidence: "Mara crossed the threshold."),
            retryCommand: retry,
            artifacts: try NovelTestFixtures.factTransactionArtifacts(
                document: retryable,
                pendingID: command.pendingID,
                retryCommand: retry
            ),
            in: retryable,
            now: now.addingTimeInterval(3)
        )

        XCTAssertEqual(
            committed.document.appliedOperations.suffix(2).map(\.kind),
            [.collectCandidate, .retryPending]
        )
        XCTAssertEqual(
            committed.document.appliedOperations.suffix(2).map(\.outcome),
            [committed.outcome, committed.outcome]
        )
        XCTAssertTrue(committed.document.pendingOperations.isEmpty)
        XCTAssertEqual(
            committed.document.sessions[0].revision,
            retryable.sessions[0].revision + 1
        )
    }

    func testEffectiveBranchOverrideAndCustomMaterialTitlesAreKnownEntities() throws {
        var document = try documentWithCandidate(
            "The Red Queen placed the Moon Key on the altar."
        )
        let characterID = NovelMaterialID()
        let baseRevisionID = NovelMaterialRevisionID()
        document = try NovelReducer.apply(
            .reviseMaterial(NovelReviseMaterialCommand(
                context: NovelMutationContext(
                    operationID: NovelOperationID(),
                    expectedProjectRevision: nil,
                    expectedConfigRevision: document.project.configRevision,
                    expectedBranchHeadRevision: nil
                ),
                projectID: document.project.id,
                materialID: characterID,
                revisionID: baseRevisionID,
                kind: .character,
                title: "Blue Queen",
                content: "The base-branch identity.",
                tags: [],
                injectionMode: .off
            )),
            to: document,
            now: now
        ).document
        let overrideRevisionID = NovelMaterialRevisionID()
        document = try NovelReducer.apply(
            .reviseMaterial(NovelReviseMaterialCommand(
                context: NovelMutationContext(
                    operationID: NovelOperationID(),
                    expectedProjectRevision: nil,
                    expectedConfigRevision: document.project.configRevision,
                    expectedBranchHeadRevision: nil
                ),
                projectID: document.project.id,
                materialID: characterID,
                revisionID: overrideRevisionID,
                kind: .character,
                title: "Red Queen",
                content: "The effective branch identity.",
                tags: [],
                injectionMode: .off
            )),
            to: document,
            now: now
        ).document
        document = try NovelReducer.apply(
            .reviseMaterial(NovelReviseMaterialCommand(
                context: NovelMutationContext(
                    operationID: NovelOperationID(),
                    expectedProjectRevision: nil,
                    expectedConfigRevision: document.project.configRevision,
                    expectedBranchHeadRevision: nil
                ),
                projectID: document.project.id,
                materialID: characterID,
                revisionID: NovelMaterialRevisionID(),
                kind: .character,
                title: "Blue Queen",
                content: "The current project identity.",
                tags: [],
                injectionMode: .off
            )),
            to: document,
            now: now
        ).document
        let artifactID = NovelMaterialID()
        document = try NovelReducer.apply(
            .reviseMaterial(NovelReviseMaterialCommand(
                context: NovelMutationContext(
                    operationID: NovelOperationID(),
                    expectedProjectRevision: nil,
                    expectedConfigRevision: document.project.configRevision,
                    expectedBranchHeadRevision: nil
                ),
                projectID: document.project.id,
                materialID: artifactID,
                revisionID: NovelMaterialRevisionID(),
                kind: .custom("artifact"),
                title: "Moon Key",
                content: "A custom material that names a story entity.",
                tags: [],
                injectionMode: .off
            )),
            to: document,
            now: now
        ).document
        document.branches[0].overrideRevisionIDs = [overrideRevisionID]
        let headIndex = try XCTUnwrap(document.checkpoints.firstIndex {
            $0.id == document.branches[0].headCheckpointID
        })
        let head = document.checkpoints[headIndex]
        document.checkpoints[headIndex] = NovelBranchCheckpointRecord(
            id: head.id,
            kind: head.kind,
            createdOnBranchID: head.createdOnBranchID,
            parentCheckpointID: head.parentCheckpointID,
            chapterSelections: head.chapterSelections,
            stateSnapshotID: head.stateSnapshotID,
            sessionCursor: head.sessionCursor,
            branchOverrideRevisionIDs: [overrideRevisionID],
            sourceCandidateID: head.sourceCandidateID,
            baseHeadRevision: head.baseHeadRevision,
            operationID: head.operationID,
            createdAt: head.createdAt
        )

        let command = collectCommand(document: document)
        let prepared = try NovelFactTransactionReducer.prepareCollection(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: document,
            now: now
        )
        let evidence = document.candidates[0].content
        let delta = NovelStateDeltaV1(
            schemaVersion: 1,
            stateSummary: evidence,
            events: [NovelStateEventV1(
                id: "known-materials",
                kind: "placement",
                summary: evidence,
                entityReferences: ["Red Queen", "Moon Key"],
                evidence: evidence
            )],
            characterChanges: [],
            relationshipChanges: [],
            foreshadowingChanges: [],
            unresolvedEntityNames: [],
            branchOutlinePatch: nil,
            settingProposals: []
        )

        XCTAssertNoThrow(try NovelFactTransactionReducer.finalizeCollection(
            pendingID: command.pendingID,
            delta: delta,
            artifacts: try NovelTestFixtures.factTransactionArtifacts(
                document: prepared.document,
                pendingID: command.pendingID
            ),
            in: prepared.document,
            now: now.addingTimeInterval(1)
        ))
    }

    func testCollectionKeepsBaseStateWhenModelReturnsNoEvidenceBackedFacts() throws {
        let document = try documentWithCandidate("A quiet paragraph with no extracted fact.")
        let command = collectCommand(document: document)
        let prepared = try NovelFactTransactionReducer.prepareCollection(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: document,
            now: now
        )
        let unsupported = NovelStateDeltaV1(
            schemaVersion: 1,
            stateSummary: "An unsupported new state.",
            events: [],
            characterChanges: [],
            relationshipChanges: [],
            foreshadowingChanges: [],
            unresolvedEntityNames: ["Unseen Stranger"],
            branchOutlinePatch: "An unsupported plot turn.",
            settingProposals: []
        )

        let committed = try NovelFactTransactionReducer.finalizeCollection(
            pendingID: command.pendingID,
            delta: unsupported,
            artifacts: try NovelTestFixtures.factTransactionArtifacts(
                document: prepared.document,
                pendingID: command.pendingID
            ),
            in: prepared.document,
            now: now.addingTimeInterval(1)
        ).document

        XCTAssertTrue(committed.pendingOperations.isEmpty)
        XCTAssertEqual(committed.chapters.count, 1)
        XCTAssertEqual(committed.stateSnapshots.last?.summary, prepared.document.stateSnapshots[0].summary)
        XCTAssertEqual(
            committed.stateSnapshots.last?.branchOutline,
            prepared.document.stateSnapshots[0].branchOutline
        )
        XCTAssertEqual(
            committed.stateSnapshots.last?.unresolvedEntityNames,
            prepared.document.stateSnapshots[0].unresolvedEntityNames
        )

        let proposalOnly = NovelStateDeltaV1(
            schemaVersion: 1,
            stateSummary: "A proposal must not advance branch state.",
            events: [],
            characterChanges: [],
            relationshipChanges: [],
            foreshadowingChanges: [],
            unresolvedEntityNames: [],
            branchOutlinePatch: "A proposal-only plot rewrite.",
            settingProposals: [NovelSettingProposalDraftV1(
                id: "proposal-only",
                title: "Possible world rule",
                content: "Keep this as a suggestion only.",
                evidence: "A quiet paragraph with no extracted fact."
            )]
        )
        let proposalCommitted = try NovelFactTransactionReducer.finalizeCollection(
            pendingID: command.pendingID,
            delta: proposalOnly,
            artifacts: try NovelTestFixtures.factTransactionArtifacts(
                document: prepared.document,
                pendingID: command.pendingID
            ),
            in: prepared.document,
            now: now.addingTimeInterval(1)
        ).document
        XCTAssertEqual(proposalCommitted.settingProposals.count, 1)
        XCTAssertEqual(
            proposalCommitted.stateSnapshots.last?.summary,
            prepared.document.stateSnapshots[0].summary
        )
    }

    func testCollectionDropsOnlyFactsWhoseEvidenceIsNotInTheManuscript() throws {
        let manuscript = "Mara opened the sealed door. Ivo waited outside."
        let document = try documentWithCandidate(manuscript)
        let command = collectCommand(document: document)
        let prepared = try NovelFactTransactionReducer.prepareCollection(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: document,
            now: now
        )
        let delta = NovelStateDeltaV1(
            schemaVersion: 1,
            stateSummary: "Mara entered while Ivo remained outside.",
            events: [
                NovelStateEventV1(
                    id: "opened-door",
                    kind: "discovery",
                    summary: "Mara opened the sealed door.",
                    entityReferences: ["Mara"],
                    evidence: "Mara opened the sealed door."
                ),
                NovelStateEventV1(
                    id: "invented-event",
                    kind: "conflict",
                    summary: "Ivo fought a guard.",
                    entityReferences: ["Ivo"],
                    evidence: "Ivo fought a guard."
                )
            ],
            characterChanges: [],
            relationshipChanges: [],
            foreshadowingChanges: [],
            unresolvedEntityNames: [],
            branchOutlinePatch: nil,
            settingProposals: []
        )

        let committed = try NovelFactTransactionReducer.finalizeCollection(
            pendingID: command.pendingID,
            delta: delta,
            artifacts: try NovelTestFixtures.factTransactionArtifacts(
                document: prepared.document,
                pendingID: command.pendingID
            ),
            in: prepared.document,
            now: now.addingTimeInterval(1)
        ).document

        XCTAssertEqual(committed.events.count, 1)
        XCTAssertEqual(committed.events[0].summary, "Mara opened the sealed door.")
        XCTAssertEqual(committed.stateSnapshots.last?.unresolvedEntityNames, ["Mara"])
        XCTAssertTrue(committed.pendingOperations.isEmpty)
    }

    /// Exercises the *full* chapter-collection production entry point
    /// (`finalizeCollection`, which chains `sanitizedCollectionDelta` into
    /// `validateStateFacts` internally — see
    /// `NovelFactTransactions.swift:457-470`) with paraphrased evidence that
    /// anchors on a long, high-coverage literal run of the manuscript. This
    /// is the collection-path counterpart to
    /// `NovelFactTransactionLifecycleTests
    /// .testManualChunkOutputAcceptsParaphrasedEvidenceAnchoredInManuscriptThroughFullChain`:
    /// before the evidence-matching unification, `sanitizedCollectionDelta`
    /// already tolerated this paraphrase, but the immediately following
    /// `validateStateFacts` call re-checked the same fact with a stricter
    /// literal-only rule and threw `NovelError.invalidInput`, so the whole
    /// collection would hard-fail instead of committing the paraphrased fact.
    func testCollectionAcceptsParaphrasedEvidenceAnchoredInManuscript() throws {
        let manuscript = "Mara opened the sealed door and stepped into the darkness beyond it."
        let document = try documentWithCandidate(manuscript)
        let command = collectCommand(document: document)
        let prepared = try NovelFactTransactionReducer.prepareCollection(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: document,
            now: now
        )
        // Reworded tail, but shares a 32-character verbatim prefix with the
        // manuscript (well above the 8-character floor and the 40% ratio
        // floor of this 65-character string).
        let paraphrased = "Mara opened the sealed door and walked into the dark room beyond."
        let delta = singleEventDelta(evidence: paraphrased)

        let committed = try NovelFactTransactionReducer.finalizeCollection(
            pendingID: command.pendingID,
            delta: delta,
            artifacts: try NovelTestFixtures.factTransactionArtifacts(
                document: prepared.document,
                pendingID: command.pendingID
            ),
            in: prepared.document,
            now: now.addingTimeInterval(1)
        ).document

        XCTAssertEqual(committed.events.map(\.summary), [paraphrased])
    }

    /// Companion to the acceptance test above: proves the full
    /// chapter-collection entry point still rejects evidence with no
    /// meaningful anchor in the manuscript, so the anchor relaxation did not
    /// weaken anti-fabrication protection on this path either.
    func testCollectionStillRejectsFabricatedEvidenceWithNoAnchor() throws {
        let manuscript = "Mara opened the sealed door and stepped into the darkness beyond it."
        let document = try documentWithCandidate(manuscript)
        let command = collectCommand(document: document)
        let prepared = try NovelFactTransactionReducer.prepareCollection(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: document,
            now: now
        )
        // Shares no meaningful contiguous run with the manuscript beyond a
        // handful of incidental characters — effectively fabricated.
        let fabricated = "Ivo stormed the castle gates and vanquished twelve guards before dawn."
        let delta = singleEventDelta(evidence: fabricated)

        XCTAssertThrowsError(try NovelFactTransactionReducer.finalizeCollection(
            pendingID: command.pendingID,
            delta: delta,
            artifacts: try NovelTestFixtures.factTransactionArtifacts(
                document: prepared.document,
                pendingID: command.pendingID
            ),
            in: prepared.document,
            now: now.addingTimeInterval(1)
        )) { error in
            guard let failure = error as? NovelStructuredModelExecutionFailure else {
                return XCTFail("Expected a structured-output evidence failure, got \(error)")
            }
            XCTAssertEqual(failure.failure.code, "state_facts_evidence_unmatched")
            XCTAssertTrue(failure.failure.isRetryable)
        }
    }

    func testPendingCollectionSurvivesFileRepositoryRestart() async throws {
        let document = try documentWithCandidate("Mara crossed the threshold.")
        let command = collectCommand(document: document)
        let prepared = try NovelFactTransactionReducer.prepareCollection(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: document,
            now: now
        )
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = NovelFileProjectRepository(rootDirectory: root)
        _ = try await repository.createProject(document)
        _ = try await repository.commitProject(
            prepared.document,
            expectedRevision: document.project.revision
        )

        let restarted = NovelFileProjectRepository(rootDirectory: root)
        let loaded = try await restarted.loadProject(id: document.project.id)
        XCTAssertEqual(loaded.document, prepared.document)
        XCTAssertEqual(loaded.document.pendingOperations, [prepared.pending])
        XCTAssertEqual(loaded.access, .readWrite)
    }

    func testStaleHeadAndDuplicateOperationWithDifferentPayloadAreRejected() throws {
        let document = try documentWithCandidate("A stable paragraph.")
        let command = collectCommand(document: document)
        let stale = NovelCollectCandidateCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: document.project.revision,
                expectedConfigRevision: document.project.configRevision,
                expectedBranchHeadRevision: document.branches[0].headRevision + 1
            ),
            projectID: command.projectID,
            branchID: command.branchID,
            pendingID: NovelPendingOperationID(),
            candidateID: command.candidateID,
            selection: command.selection,
            target: command.target,
            proposedChapterVersionID: NovelChapterVersionID(),
            checkpointID: NovelCheckpointID(),
            stateSnapshotID: NovelStateSnapshotID(),
            factCompatibilityID: UUID()
        )
        XCTAssertThrowsError(try NovelFactTransactionReducer.prepareCollection(
            stale,
            payloadSHA256: stale.canonicalPayloadSHA256(),
            in: document
        )) { error in
            guard case .staleBranchHeadRevision = error as? NovelError else {
                return XCTFail("Expected staleBranchHeadRevision, got \(error)")
            }
        }

        let prepared = try NovelFactTransactionReducer.prepareCollection(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: document
        )
        let conflicting = NovelCollectCandidateCommand(
            context: command.context,
            projectID: command.projectID,
            branchID: command.branchID,
            pendingID: NovelPendingOperationID(),
            candidateID: command.candidateID,
            selection: command.selection,
            target: command.target,
            proposedChapterVersionID: NovelChapterVersionID(),
            checkpointID: NovelCheckpointID(),
            stateSnapshotID: NovelStateSnapshotID(),
            factCompatibilityID: UUID()
        )
        XCTAssertThrowsError(try NovelFactTransactionReducer.prepareCollection(
            conflicting,
            payloadSHA256: conflicting.canonicalPayloadSHA256(),
            in: prepared.document
        )) { error in
            XCTAssertEqual(error as? NovelError, .idempotencyConflict(command.context.operationID))
        }
    }
}

private extension NovelCollectionTests {
    func collectCommand(document: NovelProjectDocumentV1) -> NovelCollectCandidateCommand {
        let candidate = document.candidates[0]
        return NovelCollectCandidateCommand(
            context: mutationContext(document: document, operationID: NovelOperationID()),
            projectID: document.project.id,
            branchID: candidate.branchID,
            pendingID: NovelPendingOperationID(),
            candidateID: candidate.id,
            selection: NovelParagraphSelection(
                paragraphIDs: NovelParagraphParser.paragraphs(in: candidate.content).map(\.id)
            ),
            target: .createNextChapter(chapterID: NovelChapterID(), title: "Chapter One"),
            proposedChapterVersionID: NovelChapterVersionID(),
            checkpointID: NovelCheckpointID(),
            stateSnapshotID: NovelStateSnapshotID(),
            factCompatibilityID: UUID()
        )
    }

    func mutationContext(
        document: NovelProjectDocumentV1,
        operationID: NovelOperationID
    ) -> NovelMutationContext {
        NovelMutationContext(
            operationID: operationID,
            expectedProjectRevision: document.project.revision,
            expectedConfigRevision: document.project.configRevision,
            expectedBranchHeadRevision: document.branches[0].headRevision
        )
    }

    func documentWithCandidate(_ content: String) throws -> NovelProjectDocumentV1 {
        let document = try NovelTestFixtures.document()
        let branch = document.branches[0]
        let request = NovelRunRequest(
            id: NovelRunID(),
            operationID: NovelOperationID(),
            projectID: document.project.id,
            branchID: branch.id,
            kind: .prose,
            mode: .writeProse,
            granularity: .wholeChapter,
            userText: "Write the next chapter.",
            userMessageID: NovelMessageID(),
            assistantMessageID: NovelMessageID(),
            candidateID: NovelCandidateID(),
            generationReceiptID: NovelReceiptID(),
            injectionReceiptID: NovelReceiptID(),
            sourceChapterVersionID: nil,
            expectedProjectRevision: document.project.revision,
            expectedConfigRevision: document.project.configRevision,
            expectedBranchHeadRevision: branch.headRevision
        )
        let plan = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: branch.id,
                promptKind: .proseWholeChapter,
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
            overrides: .none,
            providerID: "provider-id",
            modelID: "model-id",
            parameters: [:],
            createdAt: now
        )
        let generation = NovelGenerationReceiptRecord(
            id: request.generationReceiptID,
            runID: request.id,
            providerID: injection.providerID,
            modelID: injection.modelID,
            promptVersion: injection.promptVersion,
            injectionReceiptID: injection.id,
            parameters: [:],
            requestSHA256: NovelDocumentValidator.sha256(plan.canonicalInput + "\nMODEL REQUEST"),
            createdAt: now
        )
        let started = try NovelGenerationReducer.begin(
            request,
            artifacts: NovelGenerationStartArtifacts(
                injectionReceipt: injection,
                generationReceipt: generation
            ),
            in: document,
            now: now
        ).document
        return try NovelGenerationReducer.complete(
            runID: request.id,
            content: content,
            in: started,
            now: now.addingTimeInterval(1)
        ).document
    }

    func stateDelta() -> NovelStateDeltaV1 {
        NovelStateDeltaV1(
            schemaVersion: 1,
            stateSummary: "Mara opened the door and Ivo promised to return.",
            events: [NovelStateEventV1(
                id: "event-door",
                kind: "discovery",
                summary: "Mara opened the sealed door.",
                entityReferences: ["Mara"],
                evidence: "Mara opened the\nsealed door."
            )],
            characterChanges: [NovelCharacterStateFactV1(
                id: "character-mara",
                characterName: "Mara",
                attribute: "resolve",
                value: "committed",
                evidence: "Mara opened the sealed door."
            )],
            relationshipChanges: [NovelRelationshipFactV1(
                id: "relationship-promise",
                sourceEntity: "Ivo",
                targetEntity: "Mara",
                relationship: "ally",
                state: "promised to return",
                evidence: "Ivo promised to return."
            )],
            foreshadowingChanges: [NovelForeshadowingFactV1(
                id: "foreshadow-return",
                thread: "Ivo's return",
                status: .introduced,
                summary: "Ivo may return later.",
                evidence: "Ivo promised to return."
            )],
            unresolvedEntityNames: ["Mara", "Ivo"],
            branchOutlinePatch: "Mara explores what lies beyond the door.",
            settingProposals: [NovelSettingProposalDraftV1(
                id: "proposal-door",
                title: "The sealed door",
                content: "Define what the sealed door protects.",
                evidence: "Mara opened the sealed door."
            )]
        )
    }

    func singleEventDelta(evidence: String) -> NovelStateDeltaV1 {
        NovelStateDeltaV1(
            schemaVersion: 1,
            stateSummary: evidence,
            events: [NovelStateEventV1(
                id: "event-only",
                kind: "movement",
                summary: evidence,
                entityReferences: ["Mara"],
                evidence: evidence
            )],
            characterChanges: [],
            relationshipChanges: [],
            foreshadowingChanges: [],
            unresolvedEntityNames: ["Mara"],
            branchOutlinePatch: nil,
            settingProposals: []
        )
    }
}
