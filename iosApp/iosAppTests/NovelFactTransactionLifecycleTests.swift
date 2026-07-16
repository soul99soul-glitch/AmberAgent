import CryptoKit
import XCTest
@testable import iosApp

final class NovelFactTransactionLifecycleTests: XCTestCase {
    func testCollectionCommitsImmediatelyWithoutStartingFactModel() async throws {
        let fixture = try candidateDocument()
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [NovelModelScript(steps: [.delta(validDeltaJSON()), .complete])]
        )
        let command = collectCommand(
            document: fixture.document,
            candidate: fixture.candidate
        )

        guard case .candidateCollected = try await harness.creation.perform(
            .collectCandidate(command)
        ) else {
            return XCTFail("Expected collection outcome")
        }

        let final = try await harness.repository.document(command.projectID)
        XCTAssertEqual(final.candidates[0].status, .collected)
        XCTAssertEqual(final.chapterVersions.last?.content, fixture.candidate.content)
        XCTAssertTrue(final.pendingOperations.isEmpty)
        XCTAssertEqual(final.branches[0].syncStatus, .needsSync)
        XCTAssertTrue(final.injectionReceipts.isEmpty)
        XCTAssertTrue(final.generationReceipts.isEmpty)
        let requests = await harness.adapter.requests
        XCTAssertTrue(requests.isEmpty)
    }

    func testDeferredSyncRebuildsAnImmediatelyCollectedChapter() async throws {
        var fixture = try candidateDocument()
        fixture.document.project.modelPolicy = .fixed(
            providerID: "creative-provider",
            modelID: "creative-model"
        )
        fixture.document.project.stateSyncModelPolicy = .fixed(
            providerID: "sync-provider",
            modelID: "sync-model"
        )
        let rebuild = """
        {
          "schemaVersion": 1,
          "stateSummary": "Mara entered the archive and heard the bell.",
          "branchOutline": "Mara investigates the archive.",
          "events": [{
            "id": "event-rebuilt-archive",
            "kind": "discovery",
            "summary": "Mara entered the archive.",
            "entityReferences": ["Mara"],
            "evidence": "Mara opened the archive."
          }, {
            "id": "event-paraphrased",
            "kind": "discovery",
            "summary": "Mara found hidden records.",
            "entityReferences": ["Mara"],
            "evidence": "She discovered secret records inside."
          }],
          "characterStates": [],
          "relationships": [],
          "foreshadowing": [],
          "unresolvedEntityNames": ["Mara"],
          "settingProposals": []
        }
        """
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [NovelModelScript(steps: [.delta(rebuild), .complete])]
        )
        let collect = collectCommand(
            document: fixture.document,
            candidate: fixture.candidate
        )
        _ = try await harness.creation.perform(.collectCandidate(collect))
        let collected = try await harness.repository.document(collect.projectID)
        let branch = collected.branches[0]
        let sync = NovelSyncManualEditsCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: collected.project.revision,
                expectedConfigRevision: collected.project.configRevision,
                expectedBranchHeadRevision: branch.headRevision
            ),
            projectID: collected.project.id,
            branchID: branch.id,
            pendingID: NovelPendingOperationID(),
            checkpointID: NovelCheckpointID(),
            stateSnapshotID: NovelStateSnapshotID(),
            expectedWorkingRevision: branch.workingRevision
        )

        guard case .manualSyncCommitted = try await harness.creation.perform(
            .syncManualEdits(sync)
        ) else {
            return XCTFail("Expected deferred material synchronization to complete")
        }

        let final = try await harness.repository.document(collect.projectID)
        XCTAssertEqual(final.branches[0].syncStatus, .synchronized)
        XCTAssertEqual(final.stateSnapshots.last?.summary, "Mara entered the archive and heard the bell.")
        XCTAssertEqual(final.events.map(\.summary), ["Mara entered the archive."])
        let requests = await harness.adapter.requests
        XCTAssertEqual(requests.count, 1)
        let policies = await harness.adapter.resolvedPolicies
        XCTAssertEqual(policies, [.fixed(providerID: "sync-provider", modelID: "sync-model")])
    }

    func testProjectedManualStateSizeDoesNotGrowWithAccumulatedFactArrays() throws {
        let baseState = try XCTUnwrap(NovelTestFixtures.document().stateSnapshots.first)
        func rebuild(eventCount: Int) -> NovelStateRebuildV1 {
            NovelStateRebuildV1(
                schemaVersion: 1,
                stateSummary: "Compact summary.",
                branchOutline: "Compact outline.",
                events: (0..<eventCount).map { index in
                    NovelStateEventV1(
                        id: "event-\(index)",
                        kind: "fact",
                        summary: String(repeating: "Long historical fact. ", count: 20),
                        entityReferences: [],
                        evidence: String(repeating: "Historical evidence. ", count: 20)
                    )
                },
                characterStates: [],
                relationships: [],
                foreshadowing: [],
                unresolvedEntityNames: [],
                settingProposals: []
            )
        }

        let one = try NovelManualSyncChunker.projectedStateContext(
            baseState: baseState,
            accumulated: rebuild(eventCount: 1)
        )
        let many = try NovelManualSyncChunker.projectedStateContext(
            baseState: baseState,
            accumulated: rebuild(eventCount: 1_000)
        )

        XCTAssertLessThan(many.count - one.count, 32)
    }

    func testCollectionDoesNotDependOnModelContextWindow() async throws {
        let fixture = try candidateDocument()
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [NovelModelScript(steps: [.delta(validDeltaJSON()), .complete])],
            resolvedModel: NovelResolvedModel(
                providerID: "small-provider",
                ownerProviderID: "small-provider",
                modelID: "small-model",
                wireModelID: "small-wire-model",
                displayName: "Small Model",
                contextWindowTokens: 5_000
            )
        )
        let command = collectCommand(
            document: fixture.document,
            candidate: fixture.candidate
        )

        _ = try await harness.creation.perform(.collectCandidate(command))

        let requests = await harness.adapter.requests
        XCTAssertTrue(requests.isEmpty)
        let durable = try await harness.repository.document(command.projectID)
        XCTAssertEqual(durable.candidates[0].status, .collected)
        XCTAssertTrue(durable.pendingOperations.isEmpty)
        XCTAssertTrue(durable.injectionReceipts.isEmpty)
        XCTAssertTrue(durable.generationReceipts.isEmpty)
    }

    func testRestartCanRetryDurablePendingWithoutTheOriginalProviderTask() async throws {
        let fixture = try candidateDocument()
        let command = collectCommand(
            document: fixture.document,
            candidate: fixture.candidate
        )
        let prepared = try NovelFactTransactionReducer.prepareCollection(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: fixture.document,
            now: Date(timeIntervalSince1970: 1_700_002_050)
        )
        let harness = try await makeHarness(
            document: prepared.document,
            scripts: [NovelModelScript(steps: [.delta(validDeltaJSON()), .complete])]
        )
        let retry = retryCommand(
            document: prepared.document,
            pendingID: command.pendingID
        )

        guard case .candidateCollected = try await harness.creation.perform(
            .retryPending(retry)
        ) else {
            return XCTFail("Expected the restarted pending transaction to commit")
        }
        let final = try await harness.repository.document(command.projectID)
        XCTAssertTrue(final.pendingOperations.isEmpty)
        XCTAssertEqual(final.checkpoints.count, fixture.document.checkpoints.count + 1)
        XCTAssertEqual(
            final.appliedOperations.suffix(2).map(\.kind),
            [.collectCandidate, .retryPending]
        )
        XCTAssertEqual(final.branches[0].syncStatus, .needsSync)
        let requests = await harness.adapter.requests
        XCTAssertTrue(requests.isEmpty)
    }

    func testLegacyCollectionRecoveryPreservesEarlierFailedAttempt() async throws {
        let fixture = try candidateDocument()
        let command = collectCommand(
            document: fixture.document,
            candidate: fixture.candidate
        )
        let prepared = try NovelFactTransactionReducer.prepareCollection(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: fixture.document
        ).document
        let retryable = try NovelFactTransactionReducer.markRetryable(
            pendingID: command.pendingID,
            message: "Initial provider failed.",
            in: prepared
        )
        let failedRetry = retryCommand(
            document: retryable,
            pendingID: command.pendingID
        )
        let withFailedAttempt = try NovelManualSyncProgressReducer.reserveRetryAttempt(
            failedRetry,
            pending: try XCTUnwrap(retryable.pendingOperations.first),
            in: retryable
        )
        let harness = try await makeHarness(
            document: withFailedAttempt,
            scripts: [NovelModelScript(steps: [.delta(validDeltaJSON()), .complete])]
        )
        let recovery = retryCommand(
            document: withFailedAttempt,
            pendingID: command.pendingID
        )
        guard case .candidateCollected = try await harness.creation.perform(
            .retryPending(recovery)
        ) else {
            return XCTFail("Expected legacy collection recovery to publish the prose.")
        }
        let final = try await harness.repository.document(command.projectID)
        XCTAssertEqual(
            final.factAttempts.map(\.attemptOperationID),
            [failedRetry.context.operationID]
        )
        XCTAssertTrue(final.appliedOperations.contains(where: {
            $0.operationID == failedRetry.context.operationID &&
                $0.kind == .retryPending
        }))
        let collidingRename = NovelRenameProjectCommand(
            context: NovelMutationContext(
                operationID: failedRetry.context.operationID,
                expectedProjectRevision: final.project.revision,
                expectedConfigRevision: nil,
                expectedBranchHeadRevision: nil
            ),
            projectID: final.project.id,
            name: "Conflicting Rename"
        )
        XCTAssertThrowsError(try NovelReducer.apply(
            .renameProject(collidingRename),
            to: final
        )) { error in
            guard case .idempotencyConflict(let operationID) = error as? NovelError else {
                return XCTFail("Expected idempotencyConflict, got \(error)")
            }
            XCTAssertEqual(operationID, failedRetry.context.operationID)
        }
        let requests = await harness.adapter.requests
        XCTAssertTrue(requests.isEmpty)
        XCTAssertNoThrow(try NovelDocumentValidator.validate(final))
    }

    func testRetryingLegacyCollectionCompletesWithoutAProviderRequest() async throws {
        let fixture = try candidateDocument()
        let command = collectCommand(
            document: fixture.document,
            candidate: fixture.candidate
        )
        let prepared = try NovelFactTransactionReducer.prepareCollection(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: fixture.document
        ).document
        let retryable = try NovelFactTransactionReducer.markRetryable(
            pendingID: command.pendingID,
            message: "Initial provider timeout.",
            in: prepared
        )
        let harness = try await makeHarness(
            document: retryable,
            scripts: [NovelModelScript(steps: [.pause])]
        )
        let retry = retryCommand(document: retryable, pendingID: command.pendingID)
        guard case .candidateCollected = try await harness.creation.perform(.retryPending(retry)) else {
            return XCTFail("Expected the legacy collection to publish immediately.")
        }

        let durable = try await harness.repository.document(command.projectID)
        XCTAssertTrue(durable.pendingOperations.isEmpty)
        XCTAssertEqual(durable.candidates[0].status, .collected)
        XCTAssertEqual(durable.branches[0].syncStatus, .needsSync)
        let requests = await harness.adapter.requests
        let cancelledRunIDs = await harness.adapter.cancelledRunIDs
        XCTAssertTrue(requests.isEmpty)
        XCTAssertTrue(cancelledRunIDs.isEmpty)
    }

    func testFileRepositoryCollectionRetrySurvivesTwoActorRestarts() async throws {
        let fixture = try candidateDocument()
        let command = collectCommand(
            document: fixture.document,
            candidate: fixture.candidate
        )
        let prepared = try NovelFactTransactionReducer.prepareCollection(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: fixture.document
        )
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let firstRepository = NovelFileProjectRepository(rootDirectory: root)
        _ = try await firstRepository.createProject(fixture.document)
        _ = try await firstRepository.commitProject(
            prepared.document,
            expectedRevision: fixture.document.project.revision
        )

        let secondRepository = NovelFileProjectRepository(rootDirectory: root)
        let loadedPending = try await secondRepository.loadProject(id: command.projectID).document
        let retry = retryCommand(document: loadedPending, pendingID: command.pendingID)
        let adapter = ScriptedNovelModelAdapter(
            resolvedModel: NovelResolvedModel(
                providerID: "file-provider",
                ownerProviderID: "file-provider",
                modelID: "file-model",
                wireModelID: "file-wire-model",
                displayName: "File Model",
                contextWindowTokens: 128_000
            ),
            scripts: [NovelModelScript(steps: [.delta(validDeltaJSON()), .complete])]
        )
        let creation = DefaultNovelCreation(
            repository: secondRepository,
            modelRunner: adapter
        )
        _ = try await creation.perform(.retryPending(retry))

        let thirdRepository = NovelFileProjectRepository(rootDirectory: root)
        let final = try await thirdRepository.loadProject(id: command.projectID).document
        XCTAssertTrue(final.pendingOperations.isEmpty)
        XCTAssertEqual(final.checkpoints.filter { $0.kind == .collection }.count, 1)
        XCTAssertEqual(final.appliedOperations.filter {
            $0.operationID == command.context.operationID ||
                $0.operationID == retry.context.operationID
        }.map(\.kind), [.collectCandidate, .retryPending])
        XCTAssertEqual(Set(final.injectionReceipts.map(\.id)).count, final.injectionReceipts.count)
        XCTAssertEqual(Set(final.generationReceipts.map(\.id)).count, final.generationReceipts.count)
        XCTAssertEqual(final.branches[0].syncStatus, .needsSync)
        let requests = await adapter.requests
        XCTAssertTrue(requests.isEmpty)
        XCTAssertNoThrow(try NovelDocumentValidator.validate(final))
    }

    func testCollectionCommitFailureLeavesTheCandidateUnchanged() async throws {
        let fixture = try candidateDocument()
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: []
        )
        let command = collectCommand(
            document: fixture.document,
            candidate: fixture.candidate
        )
        await harness.repository.failNextCommits(1)
        do {
            _ = try await harness.creation.perform(.collectCandidate(command))
            XCTFail("Expected collection commit failure")
        } catch let error as NovelError {
            guard case .repositoryFailure = error else {
                return XCTFail("Unexpected collection write error: \(error)")
            }
        }

        let durable = try await harness.repository.document(command.projectID)
        XCTAssertEqual(durable, fixture.document)
        let requests = await harness.adapter.requests
        XCTAssertTrue(requests.isEmpty)
    }

    func testManualRebuildPlannerUsesRebuildBaseState() async throws {
        var fixture = try candidateDocument()
        let recentDiscussion = "RECENT-DIALOGUE-MUST-NOT-BE-IN-STATE-SYNC"
        fixture.document.sessions[0].messages.append(NovelSessionMessageRecord(
            id: NovelMessageID(),
            sequence: 1,
            role: .user,
            mode: .discussPlan,
            kind: .userInput,
            content: recentDiscussion,
            createdAt: fixture.document.project.updatedAt,
            runID: nil,
            candidateID: nil
        ))
        fixture.document.sessions[0].revision = 2
        try NovelDocumentValidator.validate(fixture.document)
        let rebuildJSON = validRebuildJSON()
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [NovelModelScript(steps: [.delta(rebuildJSON), .complete])]
        )
        let collect = collectCommand(
            document: fixture.document,
            candidate: fixture.candidate
        )
        _ = try await harness.creation.perform(.collectCandidate(collect))
        let collected = try await harness.repository.document(collect.projectID)
        let branch = collected.branches[0]
        let selection = try XCTUnwrap(branch.workingChapterSelections.first)
        let version = try XCTUnwrap(collected.chapterVersions.first(where: {
            $0.id == selection.versionID
        }))
        let edit = NovelSaveManualEditCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: collected.project.revision,
                expectedConfigRevision: collected.project.configRevision,
                expectedBranchHeadRevision: branch.headRevision
            ),
            projectID: collected.project.id,
            branchID: branch.id,
            chapterID: version.chapterID,
            versionID: NovelChapterVersionID(),
            title: version.title,
            content: "Mara forced open the archive door.",
            factCompatibilityID: UUID(),
            expectedWorkingRevision: branch.workingRevision
        )
        _ = try await harness.creation.perform(.saveManualEdit(edit))
        let edited = try await harness.repository.document(collect.projectID)
        let editedBranch = edited.branches[0]
        let sync = NovelSyncManualEditsCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: edited.project.revision,
                expectedConfigRevision: edited.project.configRevision,
                expectedBranchHeadRevision: editedBranch.headRevision
            ),
            projectID: edited.project.id,
            branchID: editedBranch.id,
            pendingID: NovelPendingOperationID(),
            checkpointID: NovelCheckpointID(),
            stateSnapshotID: NovelStateSnapshotID(),
            expectedWorkingRevision: editedBranch.workingRevision
        )
        _ = try await harness.creation.perform(.syncManualEdits(sync))

        let requests = await harness.adapter.requests
        XCTAssertEqual(requests.count, 1)
        let rebuildRequest = requests[0]
        let system = rebuildRequest.messages.first?.content ?? ""
        let user = rebuildRequest.messages.last?.content ?? ""
        XCTAssertFalse(system.contains("potentially stale"))
        XCTAssertTrue(user.contains("Mara forced open the archive door."))
        XCTAssertFalse(rebuildRequest.messages.contains {
            $0.content.contains(recentDiscussion)
        })
        let final = try await harness.repository.document(sync.projectID)
        XCTAssertEqual(final.branches[0].syncStatus, .synchronized)
        XCTAssertEqual(final.branches[0].currentStateSnapshotID, sync.stateSnapshotID)
        XCTAssertEqual(final.injectionReceipts.count, 1)
        XCTAssertEqual(final.generationReceipts.count, 1)
        let manualInjection = try XCTUnwrap(final.injectionReceipts.last)
        XCTAssertFalse(manualInjection.sections.contains {
            if case .sessionMessage = $0.kind { return true }
            return false
        })
        XCTAssertEqual(manualInjection.runID, rebuildRequest.runID)
        XCTAssertEqual(manualInjection.factTransaction, NovelFactReceiptLink(
            pendingID: sync.pendingID,
            ownerOperationID: sync.context.operationID,
            attemptOperationID: sync.context.operationID,
            attemptPayloadSHA256: try sync.canonicalPayloadSHA256(),
            kind: .manualRebuild,
            chunkIndex: 0
        ))
    }

    func testFileBackedManualChunksResumeSameRetryWithoutResolvingAgain() async throws {
        let scenario = try preparedLongManualSync()
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let firstRepository = NovelFileProjectRepository(rootDirectory: root)
        _ = try await firstRepository.createProject(scenario.editedDocument)
        _ = try await firstRepository.commitProject(
            scenario.pendingDocument,
            expectedRevision: scenario.editedDocument.project.revision
        )
        let retry = retryCommand(
            document: scenario.pendingDocument,
            pendingID: scenario.command.pendingID
        )
        let lockedModel = NovelResolvedModel(
            providerID: "chunk-provider",
            ownerProviderID: "chunk-provider",
            modelID: "chunk-model",
            wireModelID: "chunk-wire-model",
            displayName: "Chunk Model",
            contextWindowTokens: 12_000
        )
        let firstAdapter = ScriptedNovelModelAdapter(
            resolvedModel: lockedModel,
            scripts: [
                NovelModelScript(steps: [.delta(validChunkRebuildJSON()), .complete]),
                NovelModelScript(steps: [.fail(NovelModelFailure(
                    code: "chunk_failure",
                    message: "Retry the next chunk.",
                    isRetryable: true
                ))])
            ]
        )
        let firstCreation = DefaultNovelCreation(
            repository: firstRepository,
            modelRunner: firstAdapter
        )
        do {
            _ = try await firstCreation.perform(.retryPending(retry))
            XCTFail("Expected the second chunk to fail")
        } catch let failure as NovelStructuredModelExecutionFailure {
            XCTAssertEqual(failure.failure.code, "chunk_failure")
        }

        let durable = try await firstRepository.loadProject(
            id: scenario.command.projectID
        ).document
        let durableProgress = try XCTUnwrap(
            durable.pendingOperations.first?.manualSyncProgress
        )
        XCTAssertEqual(durableProgress.completedChunks.count, 1)
        XCTAssertEqual(durable.pendingOperations.first?.status, .retryable)
        XCTAssertEqual(durable.events, scenario.pendingDocument.events)
        XCTAssertEqual(durable.stateSnapshots, scenario.pendingDocument.stateSnapshots)
        XCTAssertEqual(durable.checkpoints, scenario.pendingDocument.checkpoints)
        let durableManualReceiptIDs = durable.injectionReceipts.compactMap { receipt in
            receipt.factTransaction?.kind == .manualRebuild ? receipt.id : nil
        }
        XCTAssertEqual(durableManualReceiptIDs.count, 2)
        XCTAssertEqual(durable.factAttempts.map(\.attemptOperationID), [retry.context.operationID])
        let collidingRename = NovelRenameProjectCommand(
            context: NovelMutationContext(
                operationID: retry.context.operationID,
                expectedProjectRevision: durable.project.revision,
                expectedConfigRevision: nil,
                expectedBranchHeadRevision: nil
            ),
            projectID: durable.project.id,
            name: "Conflicting Rename"
        )
        XCTAssertThrowsError(try NovelReducer.apply(
            .renameProject(collidingRename),
            to: durable
        )) { error in
            guard case .idempotencyConflict(let operationID) = error as? NovelError else {
                return XCTFail("Expected idempotencyConflict, got \(error)")
            }
            XCTAssertEqual(operationID, retry.context.operationID)
        }

        let secondRepository = NovelFileProjectRepository(rootDirectory: root)
        let continuationAdapter = ScriptedNovelModelAdapter(
            resolvedModel: lockedModel,
            resolutionFailure: NovelModelFailure(
                code: "resolver_should_not_run",
                message: "Durable progress must use its locked model.",
                isRetryable: false
            ),
            scripts: Array(repeating: NovelModelScript(steps: [
                .delta(validChunkRebuildJSON()), .complete
            ]), count: 40)
        )
        let secondCreation = DefaultNovelCreation(
            repository: secondRepository,
            modelRunner: continuationAdapter
        )
        _ = try await secondCreation.perform(.retryPending(retry))
        let resolvedPolicies = await continuationAdapter.resolvedPolicies
        XCTAssertTrue(resolvedPolicies.isEmpty)

        let thirdRepository = NovelFileProjectRepository(rootDirectory: root)
        let final = try await thirdRepository.loadProject(
            id: scenario.command.projectID
        ).document
        XCTAssertTrue(final.pendingOperations.isEmpty)
        XCTAssertEqual(final.checkpoints.filter { $0.kind == .manualSync }.count, 1)
        XCTAssertEqual(final.stateSnapshots.count, scenario.pendingDocument.stateSnapshots.count + 1)
        XCTAssertEqual(final.appliedOperations.filter {
            $0.operationID == scenario.command.context.operationID ||
                $0.operationID == retry.context.operationID
        }.map(\.kind), [.syncManualEdits, .retryPending])
        let manualLinks: [NovelFactReceiptLink] = final.injectionReceipts.compactMap { receipt in
            guard let link = receipt.factTransaction,
                  link.kind == .manualRebuild else { return nil }
            return link
        }
        XCTAssertGreaterThan(manualLinks.count, 2)
        let manualChunkIndices = manualLinks.compactMap(\.chunkIndex)
        XCTAssertEqual(manualChunkIndices.count, manualLinks.count)
        XCTAssertEqual(
            Set(manualChunkIndices).sorted(),
            Array(0...manualChunkIndices.max()!)
        )
        XCTAssertTrue(Set(durableManualReceiptIDs).isSubset(of: Set(final.injectionReceipts.map(\.id))))
        XCTAssertEqual(Set(final.injectionReceipts.map(\.id)).count, final.injectionReceipts.count)
        XCTAssertEqual(Set(final.generationReceipts.map(\.id)).count, final.generationReceipts.count)
        XCTAssertNoThrow(try NovelDocumentValidator.validate(final))
    }

    func testCancellationAfterChunkCommitStopsBeforeNextProviderAndResumes() async throws {
        let scenario = try preparedLongManualSync()
        let repository = FactObservingRepository()
        try await repository.seed(scenario.pendingDocument)
        await repository.pauseAfterNextManualProgressCommit()
        let retry = retryCommand(
            document: scenario.pendingDocument,
            pendingID: scenario.command.pendingID
        )
        let lockedModel = NovelResolvedModel(
            providerID: "cancel-provider",
            ownerProviderID: "cancel-provider",
            modelID: "cancel-model",
            wireModelID: "cancel-wire-model",
            displayName: "Cancel Model",
            contextWindowTokens: 12_000
        )
        let firstAdapter = ScriptedNovelModelAdapter(
            resolvedModel: lockedModel,
            scripts: Array(repeating: NovelModelScript(steps: [
                .delta(validChunkRebuildJSON()), .complete
            ]), count: 4)
        )
        let firstCreation = DefaultNovelCreation(
            repository: repository,
            modelRunner: firstAdapter
        )
        let task = Task {
            try await firstCreation.perform(.retryPending(retry))
        }
        let progressCommitted = await eventually(timeout: 3) {
            await repository.isManualProgressCommitPaused()
        }
        XCTAssertTrue(progressCommitted)
        let committed = try await repository.document(scenario.command.projectID)
        XCTAssertEqual(
            committed.pendingOperations.first?.manualSyncProgress?.completedChunks.count,
            1
        )

        task.cancel()
        await repository.resumeManualProgressCommit()
        do {
            _ = try await task.value
            XCTFail("Expected cancellation after the first durable chunk.")
        } catch {
            // Cancellation is expected after the repository releases the durable commit.
        }
        let firstRequestCount = await firstAdapter.requests.count
        XCTAssertEqual(firstRequestCount, 1)
        let retryable = try await repository.document(scenario.command.projectID)
        XCTAssertEqual(retryable.pendingOperations.first?.status, .retryable)
        XCTAssertEqual(
            retryable.pendingOperations.first?.manualSyncProgress?.completedChunks.count,
            1
        )

        let continuationAdapter = ScriptedNovelModelAdapter(
            resolvedModel: lockedModel,
            resolutionFailure: NovelModelFailure(
                code: "resolver_should_not_run",
                message: "Resume must use the durable locked model.",
                isRetryable: false
            ),
            scripts: Array(repeating: NovelModelScript(steps: [
                .delta(validChunkRebuildJSON()), .complete
            ]), count: 40)
        )
        let restarted = DefaultNovelCreation(
            repository: repository,
            modelRunner: continuationAdapter
        )
        guard case .manualSyncCommitted = try await restarted.perform(.retryPending(retry)) else {
            return XCTFail("Expected the durable second chunk to resume and finalize.")
        }
        let resolvedPolicies = await continuationAdapter.resolvedPolicies
        XCTAssertTrue(resolvedPolicies.isEmpty)
        let final = try await repository.document(scenario.command.projectID)
        XCTAssertTrue(final.pendingOperations.isEmpty)
        XCTAssertEqual(final.checkpoints.filter { $0.kind == .manualSync }.count, 1)
        XCTAssertNoThrow(try NovelDocumentValidator.validate(final))
    }

    func testBackgroundExpirationCancelsInFlightManualSyncIntoDurableRetry() async throws {
        let scenario = try preparedLongManualSync(repetitionCount: 4)
        let repository = InMemoryNovelProjectRepository()
        _ = try await repository.createProject(scenario.editedDocument)
        let adapter = ScriptedNovelModelAdapter(
            resolvedModel: NovelResolvedModel(
                providerID: "background-provider",
                ownerProviderID: "background-provider",
                modelID: "background-model",
                wireModelID: "background-wire-model",
                displayName: "Background Model",
                contextWindowTokens: 32_000
            ),
            scripts: [NovelModelScript(steps: [.pause])]
        )
        let creation = DefaultNovelCreation(
            repository: repository,
            modelRunner: adapter
        )
        let syncTask = Task {
            try await creation.perform(.syncManualEdits(scenario.command))
        }
        let requestStarted = await eventually {
            await adapter.requests.count == 1
        }
        XCTAssertTrue(requestStarted)
        let requests = await adapter.requests
        let request = try XCTUnwrap(requests.first)

        await creation.interruptForBackground(
            projectID: scenario.command.projectID,
            deadline: Date().addingTimeInterval(1),
            runID: nil
        )
        let providerWasCancelled = await eventually(timeout: 0.5) {
            await adapter.cancelledRunIDs.contains(request.runID)
        }
        if !providerWasCancelled {
            syncTask.cancel()
        }
        XCTAssertTrue(providerWasCancelled)
        do {
            _ = try await syncTask.value
            XCTFail("Expected background expiration to cancel state synchronization.")
        } catch {
            // Cancellation is persisted as the existing retryable pending operation.
        }

        let durable = try await repository.loadProject(id: scenario.command.projectID).document
        XCTAssertEqual(durable.pendingOperations.first?.id, scenario.command.pendingID)
        XCTAssertEqual(durable.pendingOperations.first?.status, .retryable)
        XCTAssertEqual(durable.branches[0].syncStatus, .needsSync)
        XCTAssertTrue(durable.checkpoints.allSatisfy { $0.kind != .manualSync })
    }

    func testManualFinalCommitFailureRetriesWithoutAnotherProviderRequest() async throws {
        let scenario = try preparedLongManualSync(repetitionCount: 4)
        let repository = FactObservingRepository()
        try await repository.seed(scenario.editedDocument)
        await repository.pauseAfterNextManualProgressCommit()
        let adapter = ScriptedNovelModelAdapter(
            resolvedModel: NovelResolvedModel(
                providerID: "finalize-provider",
                ownerProviderID: "finalize-provider",
                modelID: "finalize-model",
                wireModelID: "finalize-wire-model",
                displayName: "Finalize Model",
                contextWindowTokens: 32_000
            ),
            scripts: [NovelModelScript(steps: [
                .delta(validChunkRebuildJSON()), .complete
            ])]
        )
        let creation = DefaultNovelCreation(
            repository: repository,
            modelRunner: adapter
        )
        let first = Task {
            try await creation.perform(.syncManualEdits(scenario.command))
        }
        let progressCommitted = await eventually(timeout: 3) {
            await repository.isManualProgressCommitPaused()
        }
        XCTAssertTrue(progressCommitted)
        await repository.failNextCommits(1)
        await repository.resumeManualProgressCommit()
        do {
            _ = try await first.value
            XCTFail("Expected the final checkpoint write to fail.")
        } catch let error as NovelError {
            guard case .repositoryFailure = error else {
                return XCTFail("Unexpected final checkpoint error: \(error)")
            }
        }

        let durable = try await repository.document(scenario.command.projectID)
        let progress = try XCTUnwrap(durable.pendingOperations.first?.manualSyncProgress)
        let rebuildInput = try NovelFactTransactionReducer.manualRebuildInput(
            pendingID: scenario.command.pendingID,
            in: durable
        )
        XCTAssertTrue(NovelManualSyncProgressReducer.isComplete(
            progress,
            manuscript: rebuildInput.manuscript
        ))
        XCTAssertEqual(durable.pendingOperations.first?.status, .retryable)
        let requestCountBeforeRetry = await adapter.requests.count
        XCTAssertEqual(requestCountBeforeRetry, 1)

        let retry = retryCommand(
            document: durable,
            pendingID: scenario.command.pendingID
        )
        guard case .manualSyncCommitted = try await creation.perform(.retryPending(retry)) else {
            return XCTFail("Expected the completed progress to publish its checkpoint.")
        }
        let requestCountAfterRetry = await adapter.requests.count
        XCTAssertEqual(requestCountAfterRetry, 1)
        let final = try await repository.document(scenario.command.projectID)
        XCTAssertTrue(final.pendingOperations.isEmpty)
        XCTAssertFalse(final.factAttempts.contains {
            $0.attemptOperationID == retry.context.operationID
        })
        XCTAssertEqual(final.appliedOperations.filter {
            $0.operationID == scenario.command.context.operationID ||
                $0.operationID == retry.context.operationID
        }.map(\.kind), [.syncManualEdits, .retryPending])
        XCTAssertNoThrow(try NovelDocumentValidator.validate(final))
    }
}

private extension NovelFactTransactionLifecycleTests {
    struct Harness {
        let repository: FactObservingRepository
        let adapter: ScriptedNovelModelAdapter
        let creation: DefaultNovelCreation
    }

    struct PreparedManualSyncScenario {
        let editedDocument: NovelProjectDocumentV1
        let pendingDocument: NovelProjectDocumentV1
        let command: NovelSyncManualEditsCommand
    }

    func makeHarness(
        document: NovelProjectDocumentV1,
        scripts: [NovelModelScript],
        resolvedModel: NovelResolvedModel? = nil,
        factRequestTimeout: TimeInterval = 60
    ) async throws -> Harness {
        let repository = FactObservingRepository()
        try await repository.seed(document)
        let adapter = ScriptedNovelModelAdapter(
            resolvedModel: resolvedModel ?? NovelResolvedModel(
                providerID: "provider-id",
                ownerProviderID: "provider-id",
                modelID: "model-id",
                wireModelID: "novel-model",
                displayName: "Novel Model",
                contextWindowTokens: 128_000
            ),
            scripts: scripts
        )
        return Harness(
            repository: repository,
            adapter: adapter,
            creation: DefaultNovelCreation(
                repository: repository,
                modelRunner: adapter,
                factRequestTimeout: factRequestTimeout,
                now: { Date(timeIntervalSince1970: 1_700_002_000) }
            )
        )
    }

    func candidateDocument(
        content: String = "Mara opened the archive.\n\nThe bell rang twice."
    ) throws -> (
        document: NovelProjectDocumentV1,
        candidate: NovelCandidateRecord
    ) {
        var document = try NovelTestFixtures.document()
        let branch = document.branches[0]
        let candidateID = NovelCandidateID()
        let messageID = NovelMessageID()
        let message = NovelSessionMessageRecord(
            id: messageID,
            sequence: 0,
            role: .assistant,
            mode: .writeProse,
            kind: .proseCandidate,
            content: content,
            createdAt: document.project.updatedAt,
            runID: nil,
            candidateID: candidateID
        )
        document.sessions[0].messages = [message]
        document.sessions[0].revision = 1
        let candidate = NovelCandidateRecord(
            id: candidateID,
            kind: .prose,
            branchID: branch.id,
            sessionID: branch.sessionID,
            sourceMessageID: messageID,
            baseCheckpointID: branch.headCheckpointID,
            baseHeadRevision: branch.headRevision,
            status: .available,
            content: content,
            sourceChapterVersionID: nil,
            collectedCheckpointID: nil,
            createdAt: document.project.updatedAt
        )
        document.candidates = [candidate]
        try NovelDocumentValidator.validate(document)
        return (document, candidate)
    }

    func preparedLongManualSync(
        repetitionCount: Int = 6_000
    ) throws -> PreparedManualSyncScenario {
        let fixture = try candidateDocument(content: "Mara waited.")
        let collect = collectCommand(
            document: fixture.document,
            candidate: fixture.candidate
        )
        let preparedCollection = try NovelFactTransactionReducer.prepareCollection(
            collect,
            payloadSHA256: collect.canonicalPayloadSHA256(),
            in: fixture.document
        )
        let collected = try NovelFactTransactionReducer.finalizeCollection(
            pendingID: collect.pendingID,
            delta: NovelStateDeltaV1(
                schemaVersion: 1,
                stateSummary: "Mara waited.",
                events: [NovelStateEventV1(
                    id: "waited",
                    kind: "pause",
                    summary: "Mara waited.",
                    entityReferences: [],
                    evidence: "Mara waited."
                )],
                characterChanges: [],
                relationshipChanges: [],
                foreshadowingChanges: [],
                unresolvedEntityNames: [],
                branchOutlinePatch: "Mara waits.",
                settingProposals: []
            ),
            artifacts: try NovelTestFixtures.factTransactionArtifacts(
                document: preparedCollection.document,
                pendingID: collect.pendingID
            ),
            in: preparedCollection.document
        ).document
        let branch = collected.branches[0]
        let selection = try XCTUnwrap(branch.workingChapterSelections.first)
        let version = try XCTUnwrap(collected.chapterVersions.first(where: {
            $0.id == selection.versionID
        }))
        let longContent = Array(
            repeating: "Mara waited.",
            count: repetitionCount
        ).joined(separator: " ")
        let edit = NovelSaveManualEditCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: collected.project.revision,
                expectedConfigRevision: collected.project.configRevision,
                expectedBranchHeadRevision: branch.headRevision
            ),
            projectID: collected.project.id,
            branchID: branch.id,
            chapterID: version.chapterID,
            versionID: NovelChapterVersionID(),
            title: version.title,
            content: longContent,
            factCompatibilityID: UUID(),
            expectedWorkingRevision: branch.workingRevision
        )
        let edited = try NovelFactTransactionReducer.saveManualEdit(
            edit,
            payloadSHA256: edit.canonicalPayloadSHA256(),
            in: collected
        ).document
        let editedBranch = edited.branches[0]
        let sync = NovelSyncManualEditsCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: edited.project.revision,
                expectedConfigRevision: edited.project.configRevision,
                expectedBranchHeadRevision: editedBranch.headRevision
            ),
            projectID: edited.project.id,
            branchID: editedBranch.id,
            pendingID: NovelPendingOperationID(),
            checkpointID: NovelCheckpointID(),
            stateSnapshotID: NovelStateSnapshotID(),
            expectedWorkingRevision: editedBranch.workingRevision
        )
        let prepared = try NovelFactTransactionReducer.prepareManualSync(
            sync,
            payloadSHA256: sync.canonicalPayloadSHA256(),
            in: edited
        ).document
        return PreparedManualSyncScenario(
            editedDocument: edited,
            pendingDocument: prepared,
            command: sync
        )
    }

    func collectCommand(
        document: NovelProjectDocumentV1,
        candidate: NovelCandidateRecord
    ) -> NovelCollectCandidateCommand {
        let branch = document.branches[0]
        let paragraphIDs = NovelParagraphParser.paragraphs(
            in: candidate.content
        ).map(\.id)
        return NovelCollectCandidateCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: document.project.revision,
                expectedConfigRevision: document.project.configRevision,
                expectedBranchHeadRevision: branch.headRevision
            ),
            projectID: document.project.id,
            branchID: branch.id,
            pendingID: NovelPendingOperationID(),
            candidateID: candidate.id,
            selection: NovelParagraphSelection(paragraphIDs: paragraphIDs),
            target: .createNextChapter(
                chapterID: NovelChapterID(),
                title: "Chapter One"
            ),
            proposedChapterVersionID: NovelChapterVersionID(),
            checkpointID: NovelCheckpointID(),
            stateSnapshotID: NovelStateSnapshotID(),
            factCompatibilityID: UUID()
        )
    }

    func retryCommand(
        document: NovelProjectDocumentV1,
        pendingID: NovelPendingOperationID
    ) -> NovelRetryPendingCommand {
        NovelRetryPendingCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: document.project.revision,
                expectedConfigRevision: document.project.configRevision,
                expectedBranchHeadRevision: document.branches[0].headRevision
            ),
            projectID: document.project.id,
            pendingID: pendingID
        )
    }

    func validDeltaJSON(summary: String = "Mara entered the archive.") -> String {
        """
        {
          "schemaVersion": 1,
          "stateSummary": "\(summary)",
          "events": [{
            "id": "event-archive",
            "kind": "discovery",
            "summary": "Mara entered the archive.",
            "entityReferences": ["Mara"],
            "evidence": "Mara opened the archive."
          }],
          "characterChanges": [],
          "relationshipChanges": [],
          "foreshadowingChanges": [],
          "unresolvedEntityNames": ["Mara"],
          "branchOutlinePatch": "Mara investigates the archive.",
          "settingProposals": []
        }
        """
    }

    func validRebuildJSON(summary: String = "The edited archive scene is canonical.") -> String {
        """
        {
          "schemaVersion": 1,
          "stateSummary": "\(summary)",
          "branchOutline": "Mara investigates the revised archive scene.",
          "events": [{
            "id": "event-rebuilt-archive",
            "kind": "discovery",
            "summary": "Mara entered the revised archive.",
            "entityReferences": ["Mara"],
            "evidence": "Mara forced open the archive door."
          }],
          "characterStates": [],
          "relationships": [],
          "foreshadowing": [],
          "unresolvedEntityNames": ["Mara"],
          "settingProposals": []
        }
        """
    }

    func validChunkRebuildJSON() -> String {
        """
        {
          "schemaVersion": 1,
          "stateSummary": "Mara continues waiting.",
          "branchOutline": "Mara remains in place while time passes.",
          "events": [{
            "id": "waited",
            "kind": "pause",
            "summary": "Mara waited.",
            "entityReferences": [],
            "evidence": "Mara waited."
          }],
          "characterStates": [],
          "relationships": [],
          "foreshadowing": [],
          "unresolvedEntityNames": [],
          "settingProposals": []
        }
        """
    }

    func canonicalSHA256<T: Encodable>(_ value: T) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(value)
        return SHA256.hash(data: data)
            .map { String(format: "%02x", $0) }
            .joined()
    }

    func eventually(
        timeout: TimeInterval = 1,
        condition: () async -> Bool
    ) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if await condition() { return true }
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
        return await condition()
    }
}

private actor FactObservingRepository: NovelProjectPersisting {
    private let base = InMemoryNovelProjectRepository()
    private var committedDocuments: [NovelProjectDocumentV1] = []
    private var remainingCommitFailures = 0
    private var shouldPauseAfterFactRequestReceiptCommit = false
    private var factRequestReceiptCommitPaused = false
    private var factRequestReceiptCommitContinuation: CheckedContinuation<Void, Never>?
    private var shouldPauseAfterManualProgressCommit = false
    private var manualProgressCommitPaused = false
    private var manualProgressCommitContinuation: CheckedContinuation<Void, Never>?

    func seed(_ document: NovelProjectDocumentV1) async throws {
        _ = try await base.createProject(document)
    }

    func document(_ projectID: NovelProjectID) async throws -> NovelProjectDocumentV1 {
        try await base.loadProject(id: projectID).document
    }

    func commits() -> [NovelProjectDocumentV1] { committedDocuments }

    func failNextCommits(_ count: Int) {
        remainingCommitFailures = count
    }

    func pauseAfterNextFactRequestReceiptCommit() {
        shouldPauseAfterFactRequestReceiptCommit = true
    }

    func isFactRequestReceiptCommitPaused() -> Bool {
        factRequestReceiptCommitPaused
    }

    func resumeFactRequestReceiptCommit() {
        factRequestReceiptCommitContinuation?.resume()
        factRequestReceiptCommitContinuation = nil
        factRequestReceiptCommitPaused = false
    }

    func pauseAfterNextManualProgressCommit() {
        shouldPauseAfterManualProgressCommit = true
    }

    func isManualProgressCommitPaused() -> Bool {
        manualProgressCommitPaused
    }

    func resumeManualProgressCommit() {
        manualProgressCommitContinuation?.resume()
        manualProgressCommitContinuation = nil
        manualProgressCommitPaused = false
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
        if remainingCommitFailures > 0 {
            remainingCommitFailures -= 1
            throw NovelError.repositoryFailure("Injected fact transaction write failure.")
        }
        let previous = try await base.loadProject(id: document.project.id).document
        let previousChunkCount = previous.pendingOperations.reduce(0) {
            $0 + ($1.manualSyncProgress?.completedChunks.count ?? 0)
        }
        let nextChunkCount = document.pendingOperations.reduce(0) {
            $0 + ($1.manualSyncProgress?.completedChunks.count ?? 0)
        }
        let shouldPauseForFactRequest = shouldPauseAfterFactRequestReceiptCommit &&
            document.injectionReceipts.count == previous.injectionReceipts.count + 1 &&
            document.generationReceipts.count == previous.generationReceipts.count + 1 &&
            document.injectionReceipts.last?.factTransaction != nil &&
            document.pendingOperations == previous.pendingOperations
        let shouldPause = shouldPauseAfterManualProgressCommit &&
            nextChunkCount == previousChunkCount + 1
        let loaded = try await base.commitProject(
            document,
            expectedRevision: expectedRevision,
            authorization: authorization
        )
        committedDocuments.append(loaded.document)
        if shouldPauseForFactRequest {
            shouldPauseAfterFactRequestReceiptCommit = false
            factRequestReceiptCommitPaused = true
            await withCheckedContinuation { continuation in
                factRequestReceiptCommitContinuation = continuation
            }
        }
        if shouldPause {
            shouldPauseAfterManualProgressCommit = false
            manualProgressCommitPaused = true
            await withCheckedContinuation { continuation in
                manualProgressCommitContinuation = continuation
            }
        }
        return loaded
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
