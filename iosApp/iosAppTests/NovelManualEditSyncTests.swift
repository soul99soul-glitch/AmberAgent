import XCTest
@testable import iosApp

final class NovelManualEditSyncTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_700_200_000)

    func testManualSyncDropsUnsupportedFactsWithoutRejectingTheWholeRebuild() throws {
        let document = try NovelTestFixtures.document()
        let rebuild = NovelStateRebuildV1(
            schemaVersion: 1,
            stateSummary: "Mara has entered the archive.",
            branchOutline: "Mara begins investigating the archive.",
            events: [
                NovelStateEventV1(
                    id: "supported",
                    kind: "entry",
                    summary: "Mara opened the archive.",
                    entityReferences: [],
                    evidence: "Mara opened the archive."
                ),
                NovelStateEventV1(
                    id: "paraphrased",
                    kind: "discovery",
                    summary: "Mara found the hidden records.",
                    entityReferences: [],
                    evidence: "She discovered secret records inside."
                )
            ],
            characterStates: [],
            relationships: [],
            foreshadowing: [],
            unresolvedEntityNames: ["Unsupported name"],
            settingProposals: []
        )

        let sanitized = try NovelFactTransactionReducer.validateManualChunkOutput(
            rebuild,
            evidenceSource: "Mara opened the archive.",
            accumulated: nil,
            baseState: document.stateSnapshots[0],
            branchID: document.branches[0].id,
            in: document
        )

        XCTAssertEqual(sanitized.stateSummary, rebuild.stateSummary)
        XCTAssertEqual(sanitized.branchOutline, rebuild.branchOutline)
        XCTAssertEqual(sanitized.events.map(\.id), ["supported"])
        XCTAssertTrue(sanitized.unresolvedEntityNames.isEmpty)
    }

    func testManualSyncKeepsProjectedStateWhenNoReliableFactsSurvive() throws {
        let document = try NovelTestFixtures.document()
        let baseState = document.stateSnapshots[0]
        let projected = NovelStateRebuildV1(
            schemaVersion: 1,
            stateSummary: "The first chunk established the archive.",
            branchOutline: "Mara continues through the archive.",
            events: [],
            characterStates: [],
            relationships: [],
            foreshadowing: [],
            unresolvedEntityNames: [],
            settingProposals: []
        )
        let rebuild = NovelStateRebuildV1(
            schemaVersion: 1,
            stateSummary: "An unsupported new state.",
            branchOutline: "An unsupported new direction.",
            events: [NovelStateEventV1(
                id: "unsupported",
                kind: "discovery",
                summary: "Mara found hidden records.",
                entityReferences: ["Mara"],
                evidence: "This evidence is absent from the manuscript."
            )],
            characterStates: [],
            relationships: [],
            foreshadowing: [],
            unresolvedEntityNames: ["Mara"],
            settingProposals: []
        )

        // 2026-07-25 契约变更(按证据逐条裁决,非放宽):原契约是「证据全部对不上时
        // 静默丢弃、保留投影状态、照常返回」。真机取证表明该行为会让同步显示成功、
        // 实际一条事实都没写入,用户毫不知情(见 NovelFactOutputValidation 中
        // requireEvidenceNotAllDiscarded 的注释)。现改为:模型**给了**事实但全部
        // 无据 → 抛可重试失败;模型本来就没提取到事实(原始即为空)仍正常返回。
        // 本用例正属前者,故断言从「静默返回投影状态」改为「抛出可重试失败」。
        XCTAssertThrowsError(
            try NovelFactTransactionReducer.validateManualChunkOutput(
                rebuild,
                evidenceSource: "Mara opened the archive.",
                accumulated: projected,
                baseState: baseState,
                branchID: document.branches[0].id,
                in: document
            )
        ) { error in
            guard let failure = error as? NovelStructuredModelExecutionFailure else {
                return XCTFail("Expected a structured execution failure, got \(error)")
            }
            XCTAssertEqual(failure.failure.code, "state_facts_evidence_unmatched")
            XCTAssertTrue(failure.failure.isRetryable)
        }
    }

    func testDirectManualSyncFinalizeRejectsDerivedStateWithoutReliableFacts() throws {
        let document = try documentWithPendingManualSync()
        let pending = try XCTUnwrap(document.pendingOperations.first(where: {
            $0.kind == .manualSync
        }))
        let input = try NovelFactTransactionReducer.manualRebuildInput(
            pendingID: pending.id,
            in: document
        )
        let rebuild = NovelStateRebuildV1(
            schemaVersion: 1,
            stateSummary: "Unsupported state summary.",
            branchOutline: "Unsupported branch direction.",
            events: [],
            characterStates: [],
            relationships: [],
            foreshadowing: [],
            unresolvedEntityNames: input.baseStateSnapshot.unresolvedEntityNames,
            settingProposals: []
        )

        XCTAssertThrowsError(try NovelFactTransactionReducer.finalizeManualSync(
            pendingID: pending.id,
            rebuild: rebuild,
            artifacts: NovelTestFixtures.factTransactionArtifacts(
                document: document,
                pendingID: pending.id
            ),
            in: document,
            now: now.addingTimeInterval(2)
        )) { error in
            guard case .invalidInput(let message) = error as? NovelError else {
                return XCTFail("Expected invalidInput, got \(error)")
            }
            XCTAssertTrue(message.contains("without evidence-backed facts"))
        }
    }

    func testChunkIDsStayBoundedAndRepeatedEvidenceKeepsChunkChronology() throws {
        let longRelationshipID = String(repeating: "r", count: 128)
        let longEventID = String(repeating: "e", count: 128)
        let firstRebuild = NovelStateRebuildV1(
            schemaVersion: 1,
            stateSummary: "Mara waited.",
            branchOutline: "Mara continues to wait.",
            events: [],
            characterStates: [],
            relationships: [NovelRelationshipFactV1(
                id: longRelationshipID,
                sourceEntity: "Mara",
                targetEntity: "Ivo",
                relationship: "ally",
                state: "waited together",
                evidence: "Mara waited."
            )],
            foreshadowing: [],
            unresolvedEntityNames: ["Mara", "Ivo"],
            settingProposals: []
        )
        let secondRebuild = NovelStateRebuildV1(
            schemaVersion: 1,
            stateSummary: "Mara waited twice.",
            branchOutline: "The second wait ends the scene.",
            events: [NovelStateEventV1(
                id: longEventID,
                kind: "pause",
                summary: "Mara waited again.",
                entityReferences: ["Mara"],
                evidence: "Mara waited."
            )],
            characterStates: [],
            relationships: [],
            foreshadowing: [],
            unresolvedEntityNames: ["Mara", "Ivo"],
            settingProposals: []
        )
        let afterFirst = NovelManualSyncProgressReducer.merge(
            firstRebuild,
            into: nil,
            chunkIndex: 0
        )
        let accumulated = NovelManualSyncProgressReducer.merge(
            secondRebuild,
            into: afterFirst,
            chunkIndex: 1
        )
        let manuscript = "Mara waited. Mara waited."
        let firstChunk = "Mara waited. "
        let firstEnd = firstChunk.count
        let attemptID = NovelOperationID()
        let attemptHash = NovelDocumentValidator.sha256("manual-attempt")
        let chunks = [
            NovelManualSyncCompletedChunk(
                index: 0,
                startCharacterOffset: 0,
                endCharacterOffset: firstEnd,
                manuscriptSHA256: NovelDocumentValidator.sha256(firstChunk),
                modelInputSHA256: NovelDocumentValidator.sha256("input-0"),
                rebuild: firstRebuild,
                attemptOperationID: attemptID,
                attemptPayloadSHA256: attemptHash,
                injectionReceiptID: NovelReceiptID(),
                generationReceiptID: NovelReceiptID()
            ),
            NovelManualSyncCompletedChunk(
                index: 1,
                startCharacterOffset: firstEnd,
                endCharacterOffset: manuscript.count,
                manuscriptSHA256: NovelDocumentValidator.sha256("Mara waited."),
                modelInputSHA256: NovelDocumentValidator.sha256("input-1"),
                rebuild: secondRebuild,
                attemptOperationID: attemptID,
                attemptPayloadSHA256: attemptHash,
                injectionReceiptID: NovelReceiptID(),
                generationReceiptID: NovelReceiptID()
            )
        ]
        let progress = NovelManualSyncProgress(
            chunkingVersion: NovelManualSyncProgress.currentChunkingVersion,
            modelPolicy: .global,
            resolvedModel: NovelResolvedModel(
                providerID: "provider",
                ownerProviderID: "provider",
                modelID: "model",
                wireModelID: "wire",
                displayName: "Model",
                contextWindowTokens: 128_000
            ),
            parameters: NovelModelParameters(
                temperature: 0.1,
                topP: 0.8,
                maxOutputTokens: 8_192,
                reasoningLevel: .automatic
            ),
            requestedInputBudgetTokens: 64_000,
            effectiveInputBudgetTokens: 64_000,
            completedChunks: chunks,
            accumulatedRebuild: accumulated
        )

        let encoded = try JSONEncoder().encode(accumulated)
        XCTAssertNoThrow(try NovelStructuredOutputDecoder.decodeStateRebuild(from: encoded))
        let mergedIDs = accumulated.events.map(\.id) +
            accumulated.relationships.map(\.id)
        XCTAssertEqual(mergedIDs.count, 2)
        XCTAssertTrue(mergedIDs.allSatisfy { $0.count <= 128 })
        XCTAssertEqual(Set(mergedIDs).count, mergedIDs.count)

        let document = try NovelTestFixtures.document()
        let drafts = try NovelFactTransactionReducer.storyEventDrafts(
            progress,
            manuscript: manuscript
        )
        let events = try NovelFactTransactionReducer.storyEvents(
            drafts,
            namespace: NovelOperationID(),
            baseState: document.stateSnapshots[0],
            in: document,
            now: now
        )
        XCTAssertEqual(events.map(\.kind), ["relationship.ally", "pause"])
    }

    func testManualEditCreatesImmutableWorkingVersionAndBlocksCollectionUntilSync() throws {
        var document = try documentWithTwoCollectedChapters()
        document = try documentWithCandidate("A third candidate.", in: document)
        let oldHead = document.branches[0].headCheckpointID
        let oldHeadRevision = document.branches[0].headRevision
        let oldState = document.branches[0].currentStateSnapshotID
        let oldVersions = document.chapterVersions
        let firstChapter = document.branches[0].workingChapterSelections[0].chapterID
        let sourceVersionID = document.branches[0].workingChapterSelections[0].versionID
        let edit = manualEditCommand(
            document: document,
            chapterID: firstChapter,
            title: "Chapter One",
            content: "Mara rewrote the first fact."
        )

        let saved = try NovelFactTransactionReducer.saveManualEdit(
            edit,
            payloadSHA256: edit.canonicalPayloadSHA256(),
            in: document,
            now: now
        )

        XCTAssertEqual(saved.document.chapterVersions.count, oldVersions.count + 1)
        XCTAssertEqual(Array(saved.document.chapterVersions.dropLast()), oldVersions)
        XCTAssertEqual(saved.document.chapterVersions.last?.kind, .manualEdit)
        XCTAssertEqual(
            saved.document.chapterVersions.last?.sourceChapterVersionID,
            sourceVersionID
        )
        XCTAssertEqual(saved.document.branches[0].headCheckpointID, oldHead)
        XCTAssertEqual(saved.document.branches[0].headRevision, oldHeadRevision)
        XCTAssertEqual(saved.document.branches[0].currentStateSnapshotID, oldState)
        XCTAssertEqual(saved.document.branches[0].syncStatus, .needsSync)
        XCTAssertEqual(
            saved.document.branches[0].workingChapterSelections[0].versionID,
            edit.versionID
        )

        let candidate = try XCTUnwrap(saved.document.candidates.last)
        let collect = collectCommand(document: saved.document, candidate: candidate)
        XCTAssertThrowsError(try NovelFactTransactionReducer.prepareCollection(
            collect,
            payloadSHA256: collect.canonicalPayloadSHA256(),
            in: saved.document
        )) { error in
            guard case .invalidInput(let message) = error as? NovelError else {
                return XCTFail("Expected invalidInput, got \(error)")
            }
            XCTAssertTrue(message.contains("synchronized"))
        }
    }

    func testImmediateCollectionCanFollowManualEditWithoutLegacyPending() throws {
        var document = try documentWithTwoCollectedChapters()
        let firstChapter = document.branches[0].workingChapterSelections[0].chapterID
        let edit = manualEditCommand(
            document: document,
            chapterID: firstChapter,
            title: "Chapter One",
            content: "Mara rewrote the first fact."
        )
        document = try NovelFactTransactionReducer.saveManualEdit(
            edit,
            payloadSHA256: edit.canonicalPayloadSHA256(),
            in: document,
            now: now
        ).document
        document = try documentWithCandidate("A third candidate.", in: document)
        let candidate = try XCTUnwrap(document.candidates.last)
        let collect = collectCommand(document: document, candidate: candidate)

        let committed = try NovelFactTransactionReducer.commitCollectionWithoutStateSync(
            collect,
            payloadSHA256: collect.canonicalPayloadSHA256(),
            in: document,
            now: now.addingTimeInterval(1)
        ).document

        XCTAssertTrue(committed.pendingOperations.isEmpty)
        XCTAssertEqual(committed.branches[0].syncStatus, .needsSync)
        XCTAssertEqual(committed.branches[0].workingChapterSelections[0].versionID, edit.versionID)
        XCTAssertEqual(committed.chapterVersions.last?.content, candidate.content)
    }

    func testWorkingRevisionGuardsDoNotChangeSemanticPayloadHashes() throws {
        let document = try documentWithTwoCollectedChapters()
        let branch = document.branches[0]
        let chapterID = branch.workingChapterSelections[0].chapterID
        let edit = manualEditCommand(
            document: document,
            chapterID: chapterID,
            title: "Chapter One",
            content: "Mara rewrote the first fact."
        )
        let editWithDifferentGuards = NovelSaveManualEditCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: document.project.revision + 10,
                expectedConfigRevision: document.project.configRevision + 10,
                expectedBranchHeadRevision: branch.headRevision + 10
            ),
            projectID: edit.projectID,
            branchID: edit.branchID,
            chapterID: edit.chapterID,
            versionID: edit.versionID,
            title: edit.title,
            content: edit.content,
            factCompatibilityID: edit.factCompatibilityID,
            expectedWorkingRevision: edit.expectedWorkingRevision + 10
        )
        XCTAssertEqual(
            try edit.canonicalPayloadSHA256(),
            try editWithDifferentGuards.canonicalPayloadSHA256()
        )

        let pendingID = NovelPendingOperationID()
        let checkpointID = NovelCheckpointID()
        let stateID = NovelStateSnapshotID()
        let sync = NovelSyncManualEditsCommand(
            context: edit.context,
            projectID: document.project.id,
            branchID: branch.id,
            pendingID: pendingID,
            checkpointID: checkpointID,
            stateSnapshotID: stateID,
            expectedWorkingRevision: branch.workingRevision
        )
        let syncWithDifferentGuards = NovelSyncManualEditsCommand(
            context: editWithDifferentGuards.context,
            projectID: sync.projectID,
            branchID: sync.branchID,
            pendingID: pendingID,
            checkpointID: checkpointID,
            stateSnapshotID: stateID,
            expectedWorkingRevision: branch.workingRevision + 10
        )
        XCTAssertEqual(
            try sync.canonicalPayloadSHA256(),
            try syncWithDifferentGuards.canonicalPayloadSHA256()
        )
    }

    func testFirstChapterManualSyncReplacesDerivedSuffixWithoutMutatingOldHistory() throws {
        let collected = try documentWithTwoCollectedChapters()
        let oldEvents = collected.events
        let oldSnapshots = collected.stateSnapshots
        let oldHead = collected.branches[0].headCheckpointID
        let initialCheckpoint = try XCTUnwrap(collected.checkpoints.first(where: {
            $0.kind == .initial
        }))
        let firstChapter = collected.branches[0].workingChapterSelections[0].chapterID
        let edit = manualEditCommand(
            document: collected,
            chapterID: firstChapter,
            title: "Chapter One",
            content: "Mara rewrote the first fact."
        )
        let edited = try NovelFactTransactionReducer.saveManualEdit(
            edit,
            payloadSHA256: edit.canonicalPayloadSHA256(),
            in: collected,
            now: now
        ).document
        let editedWorkingRevision = edited.branches[0].workingRevision
        let sync = NovelSyncManualEditsCommand(
            context: mutationContext(document: edited, operationID: NovelOperationID()),
            projectID: edited.project.id,
            branchID: edited.branches[0].id,
            pendingID: NovelPendingOperationID(),
            checkpointID: NovelCheckpointID(),
            stateSnapshotID: NovelStateSnapshotID(),
            expectedWorkingRevision: edited.branches[0].workingRevision
        )
        let prepared = try NovelFactTransactionReducer.prepareManualSync(
            sync,
            payloadSHA256: sync.canonicalPayloadSHA256(),
            in: edited,
            now: now.addingTimeInterval(1)
        )
        let input = try NovelFactTransactionReducer.manualRebuildInput(
            pendingID: sync.pendingID,
            in: prepared.document
        )

        XCTAssertEqual(input.rebuildBaseCheckpoint.id, initialCheckpoint.id)
        XCTAssertEqual(input.baseStateSnapshot.id, initialCheckpoint.stateSnapshotID)
        XCTAssertEqual(input.chapters.count, 2)
        XCTAssertEqual(input.chapters[0].content, "Mara rewrote the first fact.")
        XCTAssertEqual(input.chapters[1].content, "Ivo kept the second fact.")
        XCTAssertTrue(input.manuscript.contains("# Chapter One"))
        XCTAssertEqual(prepared.pending.baseWorkingRevision, edited.branches[0].workingRevision)
        XCTAssertEqual(prepared.pending.rebuildBaseCheckpointID, initialCheckpoint.id)
        XCTAssertEqual(prepared.document.events, oldEvents)
        XCTAssertEqual(prepared.document.stateSnapshots, oldSnapshots)

        let retryable = try NovelFactTransactionReducer.markRetryable(
            pendingID: sync.pendingID,
            message: "Atomic write failed.",
            in: prepared.document,
            now: now.addingTimeInterval(2)
        )
        let retry = NovelRetryPendingCommand(
            context: mutationContext(document: retryable, operationID: NovelOperationID()),
            projectID: retryable.project.id,
            pendingID: sync.pendingID
        )
        let committed = try NovelFactTransactionReducer.finalizeManualSync(
            pendingID: sync.pendingID,
            rebuild: rebuildOutput(),
            retryCommand: retry,
            artifacts: try NovelTestFixtures.factTransactionArtifacts(
                document: retryable,
                pendingID: sync.pendingID,
                retryCommand: retry
            ),
            in: retryable,
            now: now.addingTimeInterval(3)
        )
        let newSnapshot = try XCTUnwrap(committed.document.stateSnapshots.last)
        let newEvents = Array(committed.document.events.dropFirst(oldEvents.count))

        XCTAssertEqual(Array(committed.document.stateSnapshots.prefix(oldSnapshots.count)), oldSnapshots)
        XCTAssertEqual(Array(committed.document.events.prefix(oldEvents.count)), oldEvents)
        XCTAssertEqual(committed.document.events.count, oldEvents.count + 4)
        XCTAssertEqual(newEvents.map(\.sequence), [2, 3, 4, 5])
        XCTAssertEqual(Set(committed.document.events.map(\.sequence)).count, 6)
        XCTAssertTrue(Set(newSnapshot.eventIDs).isDisjoint(with: Set(oldEvents.map(\.id))))
        XCTAssertEqual(newSnapshot.eventIDs, newEvents.map(\.id))
        XCTAssertEqual(committed.document.branches[0].syncStatus, .synchronized)
        XCTAssertEqual(
            committed.document.branches[0].workingRevision,
            editedWorkingRevision
        )
        XCTAssertEqual(committed.document.branches[0].headCheckpointID, sync.checkpointID)
        XCTAssertNotEqual(committed.document.branches[0].headCheckpointID, oldHead)
        XCTAssertEqual(committed.document.checkpoints.last?.parentCheckpointID, oldHead)
        XCTAssertEqual(committed.document.checkpoints.last?.kind, .manualSync)
        XCTAssertEqual(
            committed.document.checkpoints.last?.chapterSelections,
            edited.branches[0].workingChapterSelections
        )
        XCTAssertEqual(
            committed.document.appliedOperations.suffix(2).map(\.kind),
            [.syncManualEdits, .retryPending]
        )
        XCTAssertEqual(committed.document.settingProposals.count, 1)
        XCTAssertTrue(committed.document.materials.isEmpty)
        XCTAssertTrue(committed.document.pendingOperations.isEmpty)
    }

    func testManualFinalizeRejectsWorkingRevisionDrift() throws {
        let collected = try documentWithTwoCollectedChapters()
        let firstChapter = collected.branches[0].workingChapterSelections[0].chapterID
        let edit = manualEditCommand(
            document: collected,
            chapterID: firstChapter,
            title: "Chapter One",
            content: "Mara rewrote the first fact."
        )
        let edited = try NovelFactTransactionReducer.saveManualEdit(
            edit,
            payloadSHA256: edit.canonicalPayloadSHA256(),
            in: collected
        ).document
        let sync = NovelSyncManualEditsCommand(
            context: mutationContext(document: edited, operationID: NovelOperationID()),
            projectID: edited.project.id,
            branchID: edited.branches[0].id,
            pendingID: NovelPendingOperationID(),
            checkpointID: NovelCheckpointID(),
            stateSnapshotID: NovelStateSnapshotID(),
            expectedWorkingRevision: edited.branches[0].workingRevision
        )
        let prepared = try NovelFactTransactionReducer.prepareManualSync(
            sync,
            payloadSHA256: sync.canonicalPayloadSHA256(),
            in: edited
        )
        var stale = prepared.document
        stale.branches[0].workingRevision += 1

        XCTAssertThrowsError(try NovelFactTransactionReducer.finalizeManualSync(
            pendingID: sync.pendingID,
            rebuild: rebuildOutput(),
            artifacts: try NovelTestFixtures.factTransactionArtifacts(
                document: stale,
                pendingID: sync.pendingID
            ),
            in: stale
        )) { error in
            guard case .invalidInput(let message) = error as? NovelError else {
                return XCTFail("Expected invalidInput, got \(error)")
            }
            XCTAssertTrue(message.contains("working manuscript changed"))
        }
    }

    func testManualSyncIncludesEarlierChapterAppendAfterChosenBase() throws {
        var document = try documentWithTwoCollectedChapters()
        let firstChapter = document.branches[0].workingChapterSelections[0].chapterID
        let secondChapter = document.branches[0].workingChapterSelections[1].chapterID
        document = try documentWithCandidate(
            "Mara added a late first-chapter fact.",
            in: document
        )
        document = try appendLastCandidate(
            in: document,
            chapterID: firstChapter,
            evidence: "Mara added a late first-chapter fact."
        )
        let edit = manualEditCommand(
            document: document,
            chapterID: secondChapter,
            title: "Chapter Two",
            content: "Ivo rewrote the second fact."
        )
        let edited = try NovelFactTransactionReducer.saveManualEdit(
            edit,
            payloadSHA256: edit.canonicalPayloadSHA256(),
            in: document
        ).document
        let initial = try XCTUnwrap(edited.checkpoints.first(where: { $0.kind == .initial }))
        let sync = NovelSyncManualEditsCommand(
            context: mutationContext(document: edited, operationID: NovelOperationID()),
            projectID: edited.project.id,
            branchID: edited.branches[0].id,
            pendingID: NovelPendingOperationID(),
            checkpointID: NovelCheckpointID(),
            stateSnapshotID: NovelStateSnapshotID(),
            expectedWorkingRevision: edited.branches[0].workingRevision
        )
        let prepared = try NovelFactTransactionReducer.prepareManualSync(
            sync,
            payloadSHA256: sync.canonicalPayloadSHA256(),
            in: edited
        )
        let input = try NovelFactTransactionReducer.manualRebuildInput(
            pendingID: sync.pendingID,
            in: prepared.document
        )

        XCTAssertEqual(input.rebuildBaseCheckpoint.id, initial.id)
        XCTAssertEqual(input.chapters.map(\.chapterID), [firstChapter, secondChapter])
        XCTAssertTrue(input.chapters[0].content.contains("Mara kept the first fact."))
        XCTAssertTrue(input.chapters[0].content.contains("Mara added a late first-chapter fact."))
        XCTAssertEqual(input.chapters[1].content, "Ivo rewrote the second fact.")
    }

    func testManualSyncRejectsShortLongAndReorderedWorkingShapes() throws {
        let document = try documentWithTwoCollectedChapters()
        var variants: [NovelProjectDocumentV1] = []

        var short = document
        short.branches[0].workingChapterSelections.removeLast()
        variants.append(short)

        var long = document
        long.branches[0].workingChapterSelections.append(
            document.branches[0].workingChapterSelections[0]
        )
        variants.append(long)

        var reordered = document
        reordered.branches[0].workingChapterSelections.swapAt(0, 1)
        variants.append(reordered)

        for var variant in variants {
            variant.branches[0].syncStatus = .needsSync
            variant.branches[0].workingRevision += 1
            let command = NovelSyncManualEditsCommand(
                context: mutationContext(document: variant, operationID: NovelOperationID()),
                projectID: variant.project.id,
                branchID: variant.branches[0].id,
                pendingID: NovelPendingOperationID(),
                checkpointID: NovelCheckpointID(),
                stateSnapshotID: NovelStateSnapshotID(),
                expectedWorkingRevision: variant.branches[0].workingRevision
            )
            XCTAssertThrowsError(try NovelFactTransactionReducer.prepareManualSync(
                command,
                payloadSHA256: command.canonicalPayloadSHA256(),
                in: variant
            )) { error in
                guard case .invalidInput = error as? NovelError else {
                    return XCTFail("Expected invalidInput, got \(error)")
                }
            }
        }
    }

    func testDocumentValidationRejectsMalformedOrMisalignedDurableManualPayload() throws {
        let valid = try documentWithPendingManualSync()
        let pending = try XCTUnwrap(valid.pendingOperations.first)
        let head = try XCTUnwrap(valid.checkpoints.first { $0.id == pending.baseCheckpointID })

        var wrongBase = valid
        wrongBase.pendingOperations[0] = copyPending(
            pending,
            selectedText: pending.selectedText,
            rebuildBaseCheckpointID: head.id,
            sessionCursor: pending.sessionCursor
        )
        XCTAssertThrowsError(try NovelDocumentValidator.validate(wrongBase))
        XCTAssertThrowsError(try NovelFactTransactionReducer.manualRebuildInput(
            pendingID: pending.id,
            in: wrongBase
        ))

        let data = Data(pending.selectedText.utf8)
        var payload = try XCTUnwrap(
            try JSONSerialization.jsonObject(with: data) as? [String: Any]
        )
        var chapters = try XCTUnwrap(payload["chapters"] as? [[String: Any]])
        chapters.removeFirst()
        payload["chapters"] = chapters
        let missingPrefix = try XCTUnwrap(String(
            data: JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]),
            encoding: .utf8
        ))
        var wrongSuffix = valid
        wrongSuffix.pendingOperations[0] = copyPending(
            pending,
            selectedText: missingPrefix,
            rebuildBaseCheckpointID: pending.rebuildBaseCheckpointID,
            sessionCursor: pending.sessionCursor
        )
        XCTAssertThrowsError(try NovelDocumentValidator.validate(wrongSuffix))

        var malformed = valid
        malformed.pendingOperations[0] = copyPending(
            pending,
            selectedText: "{not-json",
            rebuildBaseCheckpointID: pending.rebuildBaseCheckpointID,
            sessionCursor: pending.sessionCursor
        )
        XCTAssertThrowsError(try NovelDocumentValidator.validate(malformed))

        var missingCursor = valid
        missingCursor.pendingOperations[0] = copyPending(
            pending,
            selectedText: pending.selectedText,
            rebuildBaseCheckpointID: pending.rebuildBaseCheckpointID,
            sessionCursor: nil
        )
        XCTAssertThrowsError(try NovelDocumentValidator.validate(missingCursor))
    }

    func testManualSyncDropsReplacedSuffixProposalsFromTheNewHeadState() throws {
        var collected = try documentWithTwoCollectedChapters()
        let staleProposal = NovelSettingProposalRecord(
            id: NovelProposalID(),
            branchID: collected.branches[0].id,
            title: "Removed world fact",
            content: "This suggestion came from prose that will be rewritten.",
            createdAt: now,
            isResolved: false
        )
        collected.settingProposals.append(staleProposal)
        let headStateIndex = try XCTUnwrap(collected.stateSnapshots.firstIndex {
            $0.id == collected.branches[0].currentStateSnapshotID
        })
        let headState = collected.stateSnapshots[headStateIndex]
        collected.stateSnapshots[headStateIndex] = NovelStateSnapshotRecord(
            id: headState.id,
            eventIDs: headState.eventIDs,
            summary: headState.summary,
            branchOutline: headState.branchOutline,
            unresolvedEntityNames: headState.unresolvedEntityNames,
            createdAt: headState.createdAt,
            settingProposalIDs: headState.settingProposalIDs + [staleProposal.id]
        )
        XCTAssertEqual(
            collected.activeSettingProposals(for: collected.branches[0].id).map(\.id),
            [staleProposal.id]
        )

        let firstChapter = collected.branches[0].workingChapterSelections[0].chapterID
        let edit = manualEditCommand(
            document: collected,
            chapterID: firstChapter,
            title: "Chapter One",
            content: "Mara rewrote the first fact."
        )
        let edited = try NovelFactTransactionReducer.saveManualEdit(
            edit,
            payloadSHA256: edit.canonicalPayloadSHA256(),
            in: collected,
            now: now.addingTimeInterval(1)
        ).document
        let sync = NovelSyncManualEditsCommand(
            context: mutationContext(document: edited, operationID: NovelOperationID()),
            projectID: edited.project.id,
            branchID: edited.branches[0].id,
            pendingID: NovelPendingOperationID(),
            checkpointID: NovelCheckpointID(),
            stateSnapshotID: NovelStateSnapshotID(),
            expectedWorkingRevision: edited.branches[0].workingRevision
        )
        let prepared = try NovelFactTransactionReducer.prepareManualSync(
            sync,
            payloadSHA256: sync.canonicalPayloadSHA256(),
            in: edited,
            now: now.addingTimeInterval(2)
        )
        let committed = try NovelFactTransactionReducer.finalizeManualSync(
            pendingID: sync.pendingID,
            rebuild: rebuildOutput(),
            artifacts: try NovelTestFixtures.factTransactionArtifacts(
                document: prepared.document,
                pendingID: sync.pendingID
            ),
            in: prepared.document,
            now: now.addingTimeInterval(3)
        ).document

        XCTAssertTrue(committed.settingProposals.contains { $0.id == staleProposal.id })
        XCTAssertFalse(
            committed.activeSettingProposals(for: committed.branches[0].id)
                .contains { $0.id == staleProposal.id }
        )
        XCTAssertEqual(
            committed.activeSettingProposals(for: committed.branches[0].id).map(\.title),
            ["Fact rules"]
        )
    }

    func testCompletedFactTransactionPendingIDCannotBeReused() throws {
        let completed = try documentWithTwoCollectedChapters()
        let usedPendingID = try XCTUnwrap(
            completed.injectionReceipts.compactMap { $0.factTransaction?.pendingID }.first
        )
        let document = try documentWithCandidate(
            "Nia discovered a third fact.",
            in: completed
        )
        let candidate = try XCTUnwrap(document.candidates.last)
        let command = collectCommand(
            document: document,
            candidate: candidate,
            pendingID: usedPendingID
        )

        XCTAssertThrowsError(try NovelFactTransactionReducer.prepareCollection(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: document
        )) { error in
            guard case .immutableRecordConflict(let label) = error as? NovelError else {
                return XCTFail("Expected immutableRecordConflict, got \(error)")
            }
            XCTAssertTrue(label.contains("pending operation"))
        }
    }

    // 2026-07-25 fact receipt Prompt version regression: a manual-sync fact injection
    // receipt records the Prompt version *at request time*, an immutable historical fact.
    // See NovelPromptCatalog.acceptedVersions for the full incident context.
    func testFactInjectionReceiptAcceptsHistoricalManualSyncPromptVersion() throws {
        let document = try documentWithPendingManualSync()
        let pending = try XCTUnwrap(document.pendingOperations.first { $0.kind == .manualSync })
        // NovelTestFixtures.factTransactionArtifacts always builds a manual-sync fact link
        // with chunkIndex nil, which only validates once the owning operation is applied.
        // A still-pending manual-sync receipt (this test's scenario) must carry chunkIndex 0
        // (the first/only chunk) to satisfy NovelGenerationDocumentValidator's pending-branch
        // check, so this constructs the receipts directly instead of reusing that fixture.
        let input = try NovelFactTransactionReducer.manualRebuildInput(
            pendingID: pending.id,
            in: document
        )
        let plan = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: pending.branchID,
                promptKind: .manualSyncV1,
                userText: input.manuscript,
                stateSnapshotIDOverride: input.baseStateSnapshot.id,
                sessionCursorLimit: input.sessionCursor,
                includeUnsynchronizedStateWarning: false
            )
        )
        let runID = NovelRunID()
        let link = NovelFactReceiptLink(
            pendingID: pending.id,
            ownerOperationID: pending.operationID,
            attemptOperationID: pending.operationID,
            attemptPayloadSHA256: pending.payloadSHA256,
            kind: .manualRebuild,
            chunkIndex: 0
        )
        let injection = NovelInjectionReceiptRecord(
            id: NovelReceiptID(),
            runID: runID,
            projectID: document.project.id,
            branchID: pending.branchID,
            plan: plan,
            overrides: .none,
            providerID: "fact-test-provider",
            modelID: "fact-test-model",
            parameters: [:],
            factTransaction: link,
            createdAt: now
        )
        let generation = NovelGenerationReceiptRecord(
            id: NovelReceiptID(),
            runID: runID,
            providerID: injection.providerID,
            modelID: injection.modelID,
            promptVersion: injection.promptVersion,
            injectionReceiptID: injection.id,
            parameters: [:],
            requestSHA256: NovelTestFixtures.hashA,
            createdAt: now,
            factTransaction: link
        )
        let artifacts = NovelFactTransactionReceiptArtifacts(
            injectionReceipt: injection,
            generationReceipt: generation
        )
        let reserved = try NovelFactRequestReceiptReducer.reserve(
            artifacts,
            pendingID: pending.id,
            in: document
        )
        let injectionIndex = try XCTUnwrap(reserved.injectionReceipts.firstIndex {
            $0.id == artifacts.injectionReceipt.id
        })
        let generationIndex = try XCTUnwrap(reserved.generationReceipts.firstIndex {
            $0.id == artifacts.generationReceipt.id
        })
        var mutated = reserved
        let historicalVersion = "novel.manual-sync.v2"
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
}

private extension NovelManualEditSyncTests {
    func documentWithPendingManualSync() throws -> NovelProjectDocumentV1 {
        let collected = try documentWithTwoCollectedChapters()
        let firstChapter = collected.branches[0].workingChapterSelections[0].chapterID
        let edit = manualEditCommand(
            document: collected,
            chapterID: firstChapter,
            title: "Chapter One",
            content: "Mara rewrote the first fact."
        )
        let edited = try NovelFactTransactionReducer.saveManualEdit(
            edit,
            payloadSHA256: edit.canonicalPayloadSHA256(),
            in: collected,
            now: now
        ).document
        let command = NovelSyncManualEditsCommand(
            context: mutationContext(document: edited, operationID: NovelOperationID()),
            projectID: edited.project.id,
            branchID: edited.branches[0].id,
            pendingID: NovelPendingOperationID(),
            checkpointID: NovelCheckpointID(),
            stateSnapshotID: NovelStateSnapshotID(),
            expectedWorkingRevision: edited.branches[0].workingRevision
        )
        return try NovelFactTransactionReducer.prepareManualSync(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: edited,
            now: now.addingTimeInterval(1)
        ).document
    }

    func copyPending(
        _ pending: NovelPendingOperationRecord,
        selectedText: String,
        rebuildBaseCheckpointID: NovelCheckpointID?,
        sessionCursor: NovelSessionCursor?
    ) -> NovelPendingOperationRecord {
        NovelPendingOperationRecord(
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
            selectedText: selectedText,
            proposedChapterVersion: pending.proposedChapterVersion,
            proposedCheckpointID: pending.proposedCheckpointID,
            proposedStateSnapshotID: pending.proposedStateSnapshotID,
            rebuildBaseCheckpointID: rebuildBaseCheckpointID,
            sessionCursor: sessionCursor,
            createdAt: pending.createdAt,
            lastError: pending.lastError
        )
    }

    func documentWithTwoCollectedChapters() throws -> NovelProjectDocumentV1 {
        var document = try NovelTestFixtures.document()
        document = try documentWithCandidate("Mara kept the first fact.", in: document)
        document = try collectLastCandidate(
            in: document,
            title: "Chapter One",
            evidence: "Mara kept the first fact."
        )
        document = try documentWithCandidate("Ivo kept the second fact.", in: document)
        return try collectLastCandidate(
            in: document,
            title: "Chapter Two",
            evidence: "Ivo kept the second fact."
        )
    }

    func collectLastCandidate(
        in document: NovelProjectDocumentV1,
        title: String,
        evidence: String
    ) throws -> NovelProjectDocumentV1 {
        let candidate = try XCTUnwrap(document.candidates.last)
        let command = collectCommand(document: document, candidate: candidate, title: title)
        let prepared = try NovelFactTransactionReducer.prepareCollection(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: document,
            now: now
        )
        return try NovelFactTransactionReducer.finalizeCollection(
            pendingID: command.pendingID,
            delta: NovelStateDeltaV1(
                schemaVersion: 1,
                stateSummary: evidence,
                events: [NovelStateEventV1(
                    id: "event-\(document.checkpoints.count)",
                    kind: "fact",
                    summary: evidence,
                    entityReferences: [],
                    evidence: evidence
                )],
                characterChanges: [],
                relationshipChanges: [],
                foreshadowingChanges: [],
                unresolvedEntityNames: [],
                branchOutlinePatch: nil,
                settingProposals: []
            ),
            artifacts: try NovelTestFixtures.factTransactionArtifacts(
                document: prepared.document,
                pendingID: command.pendingID
            ),
            in: prepared.document,
            now: now.addingTimeInterval(1)
        ).document
    }

    func appendLastCandidate(
        in document: NovelProjectDocumentV1,
        chapterID: NovelChapterID,
        evidence: String
    ) throws -> NovelProjectDocumentV1 {
        let candidate = try XCTUnwrap(document.candidates.last)
        let command = NovelCollectCandidateCommand(
            context: mutationContext(document: document, operationID: NovelOperationID()),
            projectID: document.project.id,
            branchID: candidate.branchID,
            pendingID: NovelPendingOperationID(),
            candidateID: candidate.id,
            selection: NovelParagraphSelection(
                paragraphIDs: NovelParagraphParser.paragraphs(in: candidate.content).map(\.id)
            ),
            target: .appendToChapter(chapterID),
            proposedChapterVersionID: NovelChapterVersionID(),
            checkpointID: NovelCheckpointID(),
            stateSnapshotID: NovelStateSnapshotID(),
            factCompatibilityID: UUID()
        )
        let prepared = try NovelFactTransactionReducer.prepareCollection(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: document,
            now: now
        )
        XCTAssertEqual(
            prepared.pending.proposedChapterVersion?.sourceChapterVersionID,
            document.branches[0].workingChapterSelections.first(where: {
                $0.chapterID == chapterID
            })?.versionID
        )
        return try NovelFactTransactionReducer.finalizeCollection(
            pendingID: command.pendingID,
            delta: NovelStateDeltaV1(
                schemaVersion: 1,
                stateSummary: evidence,
                events: [NovelStateEventV1(
                    id: "append-event",
                    kind: "fact",
                    summary: evidence,
                    entityReferences: [],
                    evidence: evidence
                )],
                characterChanges: [],
                relationshipChanges: [],
                foreshadowingChanges: [],
                unresolvedEntityNames: [],
                branchOutlinePatch: nil,
                settingProposals: []
            ),
            artifacts: try NovelTestFixtures.factTransactionArtifacts(
                document: prepared.document,
                pendingID: command.pendingID
            ),
            in: prepared.document,
            now: now.addingTimeInterval(1)
        ).document
    }

    func collectCommand(
        document: NovelProjectDocumentV1,
        candidate: NovelCandidateRecord,
        title: String = "Chapter Three",
        pendingID: NovelPendingOperationID = NovelPendingOperationID()
    ) -> NovelCollectCandidateCommand {
        NovelCollectCandidateCommand(
            context: mutationContext(document: document, operationID: NovelOperationID()),
            projectID: document.project.id,
            branchID: candidate.branchID,
            pendingID: pendingID,
            candidateID: candidate.id,
            selection: NovelParagraphSelection(
                paragraphIDs: NovelParagraphParser.paragraphs(in: candidate.content).map(\.id)
            ),
            target: .createNextChapter(chapterID: NovelChapterID(), title: title),
            proposedChapterVersionID: NovelChapterVersionID(),
            checkpointID: NovelCheckpointID(),
            stateSnapshotID: NovelStateSnapshotID(),
            factCompatibilityID: UUID()
        )
    }

    func manualEditCommand(
        document: NovelProjectDocumentV1,
        chapterID: NovelChapterID,
        title: String,
        content: String
    ) -> NovelSaveManualEditCommand {
        NovelSaveManualEditCommand(
            context: mutationContext(document: document, operationID: NovelOperationID()),
            projectID: document.project.id,
            branchID: document.branches[0].id,
            chapterID: chapterID,
            versionID: NovelChapterVersionID(),
            title: title,
            content: content,
            factCompatibilityID: UUID(),
            expectedWorkingRevision: document.branches[0].workingRevision
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

    func documentWithCandidate(
        _ content: String,
        in document: NovelProjectDocumentV1
    ) throws -> NovelProjectDocumentV1 {
        let branch = document.branches[0]
        let request = NovelRunRequest(
            id: NovelRunID(),
            operationID: NovelOperationID(),
            projectID: document.project.id,
            branchID: branch.id,
            kind: .prose,
            mode: .writeProse,
            granularity: .wholeChapter,
            userText: "Write a chapter.",
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

    func rebuildOutput() -> NovelStateRebuildV1 {
        NovelStateRebuildV1(
            schemaVersion: 1,
            stateSummary: "Mara rewrote the first fact while Ivo kept the second.",
            branchOutline: "Mara and Ivo continue from the rewritten opening.",
            events: [NovelStateEventV1(
                id: "rebuilt-event",
                kind: "revision",
                summary: "Mara rewrote the first fact.",
                entityReferences: ["Mara"],
                evidence: "Mara rewrote the first fact."
            )],
            characterStates: [NovelCharacterStateFactV1(
                id: "rebuilt-character",
                characterName: "Mara",
                attribute: "agency",
                value: "rewrote the fact",
                evidence: "Mara rewrote the first fact."
            )],
            relationships: [NovelRelationshipFactV1(
                id: "rebuilt-relationship",
                sourceEntity: "Mara",
                targetEntity: "Ivo",
                relationship: "continuity",
                state: "their facts coexist",
                evidence: "Ivo kept the second fact."
            )],
            foreshadowing: [NovelForeshadowingFactV1(
                id: "rebuilt-thread",
                thread: "second fact",
                status: .advanced,
                summary: "Ivo preserves the second fact.",
                evidence: "Ivo kept the second fact."
            )],
            unresolvedEntityNames: ["Mara", "Ivo"],
            settingProposals: [NovelSettingProposalDraftV1(
                id: "rebuilt-proposal",
                title: "Fact rules",
                content: "Clarify how facts can be rewritten.",
                evidence: "Mara rewrote the first fact."
            )]
        )
    }
}
