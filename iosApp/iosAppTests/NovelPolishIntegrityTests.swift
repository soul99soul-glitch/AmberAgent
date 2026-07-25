import XCTest
@testable import iosApp

final class NovelPolishIntegrityTests: NovelPolishTestCase {
    func testHeadChangeDuringAssessmentBlocksAdoptionWithoutApplyingLateCompatibleVerdict() async throws {
        let harness = try await makeHarness(remainingScripts: [
            NovelModelScript(steps: [.pause, .delta(compatibleDriftJSON), .complete]),
        ])
        let candidateID = try await generatePolish(in: harness)
        let baseline = try await document(in: harness)
        let command = adoptionCommand(document: baseline, candidateID: candidateID)
        let adoption = Task {
            try await harness.creation.perform(.adoptPolishCandidate(command))
        }

        let reachedDriftRequest = await waitForRequestCount(2, adapter: harness.adapter)
        XCTAssertTrue(reachedDriftRequest)
        let requests = await harness.adapter.requests
        let driftRequest = try XCTUnwrap(requests.last)
        let pending = try await document(in: harness)
        XCTAssertEqual(pending.polishTransactions.first?.status, .pending)

        let competing = DefaultNovelCreation(repository: harness.repository)
        let restore = restoreCommand(
            document: pending,
            targetVersionID: harness.sourceVersionID
        )
        _ = try await competing.perform(.restoreChapterVersion(restore))
        let moved = try await document(in: harness)
        XCTAssertNotEqual(moved.branches[0].headCheckpointID, baseline.branches[0].headCheckpointID)

        await harness.adapter.resume(runID: driftRequest.runID)
        do {
            _ = try await adoption.value
            XCTFail("A compatible verdict must not adopt after the source head changes")
        } catch {
            guard case .staleBranchHeadRevision = error as? NovelError else {
                return XCTFail("Expected stale head rejection, got \(error)")
            }
        }

        let blocked = try await document(in: harness)
        XCTAssertEqual(blocked.polishTransactions.first?.status, .blocked)
        XCTAssertEqual(blocked.polishTransactions.first?.lastFailure?.isRetryable, true)
        XCTAssertEqual(blocked.polishAssessments.count, 1)
        XCTAssertNil(blocked.polishAssessments.first?.result)
        XCTAssertNotNil(blocked.polishAssessments.first?.failure)
        XCTAssertEqual(blocked.candidates.first { $0.id == candidateID }?.status, .available)
        XCTAssertFalse(blocked.chapterVersions.contains { $0.id == command.proposedChapterVersionID })
        XCTAssertFalse(blocked.checkpoints.contains { $0.id == command.checkpointID })
        XCTAssertFalse(blocked.appliedOperations.contains {
            $0.operationID == command.context.operationID
        })
    }

    func testDriftReceiptTamperingAndExtraMaterialFailDocumentValidation() async throws {
        let harness = try await makeHarness(remainingScripts: [
            NovelModelScript(steps: [.pause]),
        ])
        let candidateID = try await generatePolish(in: harness)
        let baseline = try await document(in: harness)
        let command = adoptionCommand(document: baseline, candidateID: candidateID)
        let adoption = Task {
            try await harness.creation.perform(.adoptPolishCandidate(command))
        }
        let reachedRequest = await waitForRequestCount(2, adapter: harness.adapter)
        XCTAssertTrue(reachedRequest)
        let pending = try await document(in: harness)
        let attempt = try XCTUnwrap(pending.polishAttempts.first)
        let receiptIndex = try XCTUnwrap(pending.injectionReceipts.firstIndex {
            $0.id == attempt.injectionReceiptID
        })
        let receipt = pending.injectionReceipts[receiptIndex]
        let source = try sourceVersion(in: pending, id: harness.sourceVersionID)
        let candidate = try XCTUnwrap(pending.candidates.first { $0.id == candidateID })

        var sectionHashTamper = pending
        sectionHashTamper.injectionReceipts[receiptIndex] = try receiptByChangingJSON(receipt) {
            var sections = $0["sections"] as? [[String: Any]] ?? []
            sections[1]["contentSHA256"] = NovelTestFixtures.hashC
            $0["sections"] = sections
        }
        assertInvalidDocument(sectionHashTamper)

        var canonicalHashTamper = pending
        canonicalHashTamper.injectionReceipts[receiptIndex] = try receiptByChangingJSON(receipt) {
            $0["canonicalInputSHA256"] = NovelTestFixtures.hashC
        }
        assertInvalidDocument(canonicalHashTamper)

        let materialID = NovelMaterialID()
        let revisionID = NovelMaterialRevisionID()
        var materialPlan = exactDriftPlan(
            source: source,
            candidate: candidate,
            maxEstimatedInputTokens: receipt.maxEstimatedInputTokens
        )
        materialPlan = addingMaterial(
            id: materialID,
            revisionID: revisionID,
            to: materialPlan
        )
        var extraMaterial = pending
        extraMaterial.injectionReceipts[receiptIndex] = receiptReplacingPlan(
            receipt,
            plan: materialPlan
        )
        assertInvalidDocument(extraMaterial)

        let requests = await harness.adapter.requests
        if let request = requests.last {
            await harness.adapter.resume(runID: request.runID)
        }
        _ = try? await adoption.value
    }

    // 2026-07-25 fact receipt Prompt version regression: `acceptedVersions` must also cover
    // the polish-drift receipt path, not just the state-delta/manual-sync ones. Only one
    // version has ever shipped for this kind, so this locks in that the set-based judgement
    // still accepts the current version (the collection-based check does not silently pass
    // everything).
    func testPolishDriftInjectionReceiptAcceptsItsCurrentPromptVersion() async throws {
        let harness = try await makeHarness(remainingScripts: [
            NovelModelScript(steps: [.pause]),
        ])
        let candidateID = try await generatePolish(in: harness)
        let baseline = try await document(in: harness)
        let command = adoptionCommand(document: baseline, candidateID: candidateID)
        let adoption = Task {
            try await harness.creation.perform(.adoptPolishCandidate(command))
        }
        let reachedDriftRequest = await waitForRequestCount(2, adapter: harness.adapter)
        XCTAssertTrue(reachedDriftRequest)
        let pending = try await document(in: harness)
        let attempt = try XCTUnwrap(pending.polishAttempts.first)
        let receipt = try XCTUnwrap(pending.injectionReceipts.first {
            $0.id == attempt.injectionReceiptID
        })

        XCTAssertEqual(receipt.promptVersion, NovelPromptCatalog.template(for: .polishDriftV1).version)
        XCTAssertTrue(
            NovelPromptCatalog.acceptedVersions(for: .polishDriftV1).contains(receipt.promptVersion)
        )
        XCTAssertNoThrow(try NovelDocumentValidator.validate(pending))

        let requests = await harness.adapter.requests
        if let request = requests.last {
            await harness.adapter.resume(runID: request.runID)
        }
        _ = try? await adoption.value
    }

    func testTerminalPolishMustAssessLatestAttemptAndCannotLeaveItUnassessed() async throws {
        let harness = try await makeHarness(remainingScripts: [
            NovelModelScript(steps: [.delta("not-json"), .complete]),
            NovelModelScript(steps: [.delta(compatibleDriftJSON), .complete]),
        ])
        let candidateID = try await generatePolish(in: harness)
        let baseline = try await document(in: harness)
        let command = adoptionCommand(document: baseline, candidateID: candidateID)
        _ = try? await harness.creation.perform(.adoptPolishCandidate(command))
        _ = try await harness.creation.perform(.adoptPolishCandidate(command))
        let completed = try await document(in: harness)
        XCTAssertEqual(completed.polishAttempts.map(\.attemptIndex), [0, 1])
        XCTAssertEqual(completed.polishTransactions.first?.status, .completed)

        var nonLatestTerminal = completed
        let firstAttempt = try XCTUnwrap(completed.polishAttempts.first)
        nonLatestTerminal.polishAssessments = [NovelPolishAssessmentRecord(
            transactionID: firstAttempt.transactionID,
            attemptIndex: firstAttempt.attemptIndex,
            runID: firstAttempt.runID,
            result: NovelPolishDriftV1(
                schemaVersion: NovelPolishDriftV1.currentSchemaVersion,
                compatible: true,
                differences: []
            ),
            failure: nil,
            createdAt: now
        )]
        assertInvalidDocument(nonLatestTerminal)

        var unassessedTerminal = completed
        unassessedTerminal.polishAssessments.removeAll()
        assertInvalidDocument(unassessedTerminal)
    }

    func testTwoPolishTransactionsCannotReuseProposedVersionOrCheckpointIdentity() async throws {
        let secondOutput = "A second wording-only polish."
        let harness = try await makeHarness(remainingScripts: [
            NovelModelScript(steps: [
                .delta("\(secondOutput)\n\(NovelPromptCatalog.polishCompletionSentinel)"),
                .complete,
            ]),
        ])
        let firstCandidateID = try await generatePolish(in: harness)
        let afterFirst = try await document(in: harness)
        let secondCandidateID = try await generatePolish(in: harness, document: afterFirst)
        let twoCandidates = try await document(in: harness)
        let first = adoptionCommand(document: twoCandidates, candidateID: firstCandidateID)
        let prepared = try NovelPolishTransactionReducer.prepareAdoption(
            first,
            payloadSHA256: try NovelAction.adoptPolishCandidate(first).canonicalPayloadSHA256(),
            in: twoCandidates,
            now: now
        )
        let second = adoptionCommand(
            document: prepared.document,
            candidateID: secondCandidateID,
            proposedVersionID: first.proposedChapterVersionID,
            checkpointID: first.checkpointID
        )

        XCTAssertThrowsError(try NovelPolishTransactionReducer.prepareAdoption(
            second,
            payloadSHA256: try NovelAction.adoptPolishCandidate(second).canonicalPayloadSHA256(),
            in: prepared.document,
            now: now
        ))
    }

    func testPendingAndRetryablePolishBlockBranchDeletion() async throws {
        let harness = try await makeHarness(remainingScripts: [
            NovelModelScript(steps: [.pause, .delta("not-json"), .complete]),
        ])
        let initial = try await document(in: harness)
        let sourceBranch = initial.branches[0]
        let childID = NovelBranchID()
        _ = try await harness.creation.perform(.forkBranch(NovelForkBranchCommand(
            context: mutationContext(document: initial),
            projectID: initial.project.id,
            sourceBranchID: sourceBranch.id,
            checkpointID: sourceBranch.headCheckpointID,
            branchID: childID,
            sessionID: NovelSessionID(),
            name: "Keep"
        )))
        let forked = try await document(in: harness)
        _ = try await harness.creation.perform(.setMainBranch(NovelSetMainBranchCommand(
            context: mutationContext(document: forked),
            projectID: forked.project.id,
            branchID: childID
        )))
        let withMainChild = try await document(in: harness)
        let candidateID = try await generatePolish(in: harness, document: withMainChild)
        let generated = try await document(in: harness)
        let adoptionCommand = adoptionCommand(document: generated, candidateID: candidateID)
        let adoption = Task {
            try await harness.creation.perform(.adoptPolishCandidate(adoptionCommand))
        }
        let reachedRequest = await waitForRequestCount(2, adapter: harness.adapter)
        XCTAssertTrue(reachedRequest)
        let pending = try await document(in: harness)
        XCTAssertEqual(pending.polishTransactions.first?.status, .pending)

        let competing = DefaultNovelCreation(repository: harness.repository)
        let pendingDelete = NovelDeleteBranchCommand(
            context: mutationContext(document: pending),
            projectID: pending.project.id,
            branchID: sourceBranch.id
        )
        await NovelXCTAssertThrowsErrorAsync(
            try await competing.perform(.deleteBranch(pendingDelete))
        ) { error in
            XCTAssertEqual(error as? NovelError, .projectBusy(pending.project.id))
        }

        let requests = await harness.adapter.requests
        let request = try XCTUnwrap(requests.last)
        await harness.adapter.resume(runID: request.runID)
        _ = try? await adoption.value
        let retryable = try await document(in: harness)
        XCTAssertEqual(retryable.polishTransactions.first?.status, .retryable)

        let reloaded = DefaultNovelCreation(repository: harness.repository)
        let retryableDelete = NovelDeleteBranchCommand(
            context: mutationContext(document: retryable),
            projectID: retryable.project.id,
            branchID: sourceBranch.id
        )
        await NovelXCTAssertThrowsErrorAsync(
            try await reloaded.perform(.deleteBranch(retryableDelete))
        ) { error in
            XCTAssertEqual(error as? NovelError, .projectBusy(retryable.project.id))
        }
    }

    func testPolishAndRestoreCheckpointInvariantTamperingFailsValidation() async throws {
        let harness = try await makeHarness(remainingScripts: [
            NovelModelScript(steps: [.delta(compatibleDriftJSON), .complete]),
        ])
        let candidateID = try await generatePolish(in: harness)
        let baseline = try await document(in: harness)
        let adoption = adoptionCommand(document: baseline, candidateID: candidateID)
        _ = try await harness.creation.perform(.adoptPolishCandidate(adoption))
        let adopted = try await document(in: harness)
        let restore = restoreCommand(document: adopted, targetVersionID: harness.sourceVersionID)
        _ = try await harness.creation.perform(.restoreChapterVersion(restore))
        let restored = try await document(in: harness)

        for (document, checkpointID, versionID) in [
            (adopted, adoption.checkpointID, adoption.proposedChapterVersionID),
            (restored, restore.checkpointID, restore.proposedChapterVersionID),
        ] {
            let checkpointIndex = try XCTUnwrap(document.checkpoints.firstIndex {
                $0.id == checkpointID
            })
            let checkpoint = document.checkpoints[checkpointIndex]

            var stateTamper = document
            stateTamper.checkpoints[checkpointIndex] = replacingCheckpoint(
                checkpoint,
                stateSnapshotID: NovelStateSnapshotID()
            )
            assertInvalidDocument(stateTamper)

            var overrideTamper = document
            overrideTamper.checkpoints[checkpointIndex] = replacingCheckpoint(
                checkpoint,
                overrideRevisionIDs: [NovelMaterialRevisionID()]
            )
            assertInvalidDocument(overrideTamper)

            var compatibilityTamper = document
            let versionIndex = try XCTUnwrap(compatibilityTamper.chapterVersions.firstIndex {
                $0.id == versionID
            })
            compatibilityTamper.chapterVersions[versionIndex] = replacingCompatibility(
                compatibilityTamper.chapterVersions[versionIndex],
                compatibilityID: UUID()
            )
            assertInvalidDocument(compatibilityTamper)
        }

        var multiChapter = adopted
        let extraChapter = NovelChapterRecord(id: NovelChapterID(), createdAt: now)
        let extraVersion = NovelChapterVersionRecord(
            id: NovelChapterVersionID(),
            chapterID: extraChapter.id,
            kind: .collected,
            title: "Extra",
            content: "An unrelated chapter.",
            factCompatibilityID: UUID(),
            sourceCandidateID: nil,
            createdAt: now,
            operationID: multiChapter.appliedOperations[0].operationID
        )
        let extraSelection = NovelChapterSelection(
            chapterID: extraChapter.id,
            versionID: extraVersion.id
        )
        multiChapter.chapters.append(extraChapter)
        multiChapter.chapterVersions.append(extraVersion)
        let polishCheckpointIndex = try XCTUnwrap(multiChapter.checkpoints.firstIndex {
            $0.id == adoption.checkpointID
        })
        multiChapter.checkpoints[polishCheckpointIndex] = replacingCheckpoint(
            multiChapter.checkpoints[polishCheckpointIndex],
            chapterSelections: multiChapter.checkpoints[polishCheckpointIndex].chapterSelections +
                [extraSelection]
        )
        multiChapter.branches[0].workingChapterSelections.append(extraSelection)
        assertInvalidDocument(multiChapter)
    }

    func testCallerCancellationPersistsCancelledFailureCancelsProviderAndRethrowsCancellation() async throws {
        let harness = try await makeHarness(remainingScripts: [
            NovelModelScript(steps: [.pause]),
        ])
        let candidateID = try await generatePolish(in: harness)
        let baseline = try await document(in: harness)
        let command = adoptionCommand(document: baseline, candidateID: candidateID)
        let task = Task {
            try await harness.creation.perform(.adoptPolishCandidate(command))
        }
        let providerStarted = await waitForRequestCount(2, adapter: harness.adapter)
        XCTAssertTrue(providerStarted)
        let requests = await harness.adapter.requests
        let request = try XCTUnwrap(requests.last)

        task.cancel()
        do {
            _ = try await task.value
            XCTFail("Expected polish adoption cancellation")
        } catch is CancellationError {
            // Caller cancellation remains distinguishable from model failure.
        } catch {
            XCTFail("Expected CancellationError, got \(error)")
        }

        let durable = try await document(in: harness)
        let transaction = try XCTUnwrap(durable.polishTransactions.first)
        let assessment = try XCTUnwrap(durable.polishAssessments.first)
        XCTAssertEqual(transaction.status, .retryable)
        XCTAssertEqual(transaction.lastFailure?.code, "cancelled")
        XCTAssertEqual(transaction.lastFailureAttemptIndex, 0)
        XCTAssertEqual(assessment.failure?.code, "cancelled")
        XCTAssertNil(assessment.result)
        XCTAssertEqual(durable.polishAttempts.count, 1)
        XCTAssertEqual(durable.chapterVersions, baseline.chapterVersions)
        XCTAssertEqual(durable.checkpoints, baseline.checkpoints)
        XCTAssertEqual(durable.candidates.first { $0.id == candidateID }?.status, .available)
        let providerCancelled = await waitForCancellation(
            runID: request.runID,
            adapter: harness.adapter
        )
        XCTAssertTrue(providerCancelled)
    }

    func testBackgroundInterruptCancelsInFlightPolishAndIgnoresLateCompatibleVerdict() async throws {
        let harness = try await makeHarness(
            remainingScripts: [
                NovelModelScript(
                    steps: [.pause, .delta(compatibleDriftJSON), .complete],
                    ignoresCancellation: true
                ),
            ],
            polishAssessmentTimeout: 5
        )
        let candidateID = try await generatePolish(in: harness)
        let baseline = try await document(in: harness)
        let command = adoptionCommand(document: baseline, candidateID: candidateID)
        let adoption = Task {
            try await harness.creation.perform(.adoptPolishCandidate(command))
        }
        let providerStarted = await waitForRequestCount(2, adapter: harness.adapter)
        XCTAssertTrue(providerStarted)
        let requests = await harness.adapter.requests
        let request = try XCTUnwrap(requests.last)

        await harness.creation.interruptForBackground(
            projectID: harness.projectID,
            deadline: now.addingTimeInterval(1)
        )
        let providerCancelled = await waitForCancellation(
            runID: request.runID,
            adapter: harness.adapter
        )
        if !providerCancelled {
            // Keep the red-path canary finite while the production hook is absent.
            adoption.cancel()
        }
        XCTAssertTrue(providerCancelled)

        let outcome = try? await adoption.value
        if let outcome, case .polishCandidateAdopted = outcome {
            XCTFail("A late compatible verdict adopted after the project entered background")
        }

        let durableAfterInterrupt = try await document(in: harness)
        let transaction = try XCTUnwrap(durableAfterInterrupt.polishTransactions.first {
            $0.id == command.transactionID
        })
        XCTAssertEqual(transaction.status, .retryable)
        XCTAssertEqual(transaction.lastFailure?.code, "cancelled")
        XCTAssertEqual(transaction.lastFailureAttemptIndex, 0)
        XCTAssertEqual(
            durableAfterInterrupt.polishAssessments.first {
                $0.transactionID == transaction.id && $0.attemptIndex == 0
            }?.failure?.code,
            "cancelled"
        )
        XCTAssertEqual(
            durableAfterInterrupt.candidates.first { $0.id == candidateID }?.status,
            .available
        )
        XCTAssertFalse(durableAfterInterrupt.chapterVersions.contains {
            $0.id == command.proposedChapterVersionID
        })
        XCTAssertFalse(durableAfterInterrupt.checkpoints.contains { $0.id == command.checkpointID })
        XCTAssertFalse(durableAfterInterrupt.appliedOperations.contains {
            $0.operationID == command.context.operationID &&
                $0.kind == .adoptPolishCandidate
        })
        XCTAssertNoThrow(try NovelDocumentValidator.validate(durableAfterInterrupt))

        await harness.adapter.resume(runID: request.runID)
        try? await Task.sleep(nanoseconds: 50_000_000)
        let durableAfterLateVerdict = try await document(in: harness)
        XCTAssertEqual(durableAfterLateVerdict, durableAfterInterrupt)
    }

    func testBackgroundInterruptRevokesFinalAdoptionBeforeRepositoryInstall() async throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("NovelPolishFinalCommit-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = PolishFinalAdoptionCommitGateRepository(rootDirectory: root)
        let harness = try await makeHarness(
            repository: repository,
            remainingScripts: [NovelModelScript(steps: [
                .delta(compatibleDriftJSON),
                .complete,
            ])],
            polishAssessmentTimeout: 5
        )
        let candidateID = try await generatePolish(in: harness)
        let baseline = try await document(in: harness)
        let baselineBranch = try XCTUnwrap(baseline.branches.first)
        let command = adoptionCommand(document: baseline, candidateID: candidateID)
        await repository.armFinalCommit(operationID: command.context.operationID)

        let adoption = Task {
            try await harness.creation.perform(.adoptPolishCandidate(command))
        }
        await repository.waitUntilFinalCommitBlocked()

        let backgroundDeadline = now.addingTimeInterval(1)
        let interruption = Task {
            await harness.creation.interruptForBackground(
                projectID: harness.projectID,
                deadline: backgroundDeadline
            )
        }
        let revokeDeadline = Date().addingTimeInterval(1)
        while Date() < revokeDeadline,
              !(await repository.finalCommitAuthorizationWasRevoked()) {
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
        let authorizationWasRevoked = await repository.finalCommitAuthorizationWasRevoked()
        XCTAssertTrue(authorizationWasRevoked)
        await repository.resumeFinalCommit()
        await interruption.value

        do {
            _ = try await adoption.value
            XCTFail("Background cancellation must win before the final repository install")
        } catch is CancellationError {
            // The revoked commit is durably recorded as a retryable cancellation.
        } catch {
            XCTFail("Expected CancellationError, got \(error)")
        }

        let durable = try await repository.loadProject(id: harness.projectID).document
        let transaction = try XCTUnwrap(durable.polishTransactions.first {
            $0.id == command.transactionID
        })
        let assessment = try XCTUnwrap(durable.polishAssessments.first {
            $0.transactionID == command.transactionID && $0.attemptIndex == 0
        })
        let durableBranch = try XCTUnwrap(durable.branches.first {
            $0.id == baselineBranch.id
        })
        XCTAssertEqual(transaction.status, .retryable)
        XCTAssertEqual(transaction.lastFailure?.code, "cancelled")
        XCTAssertNil(assessment.result)
        XCTAssertEqual(assessment.failure?.code, "cancelled")
        XCTAssertEqual(durable.candidates.first { $0.id == candidateID }?.status, .available)
        XCTAssertEqual(durable.chapterVersions, baseline.chapterVersions)
        XCTAssertEqual(durable.checkpoints, baseline.checkpoints)
        XCTAssertEqual(durableBranch.headCheckpointID, baselineBranch.headCheckpointID)
        XCTAssertEqual(durableBranch.workingChapterSelections, baselineBranch.workingChapterSelections)
        XCTAssertFalse(durable.appliedOperations.contains {
            $0.operationID == command.context.operationID &&
                $0.kind == .adoptPolishCandidate
        })
        let finalCommitCount = await repository.finalCommitCount()
        XCTAssertEqual(finalCommitCount, 0)
        XCTAssertNoThrow(try NovelDocumentValidator.validate(durable))

        let restarted = DefaultNovelCreation(repository: repository)
        guard case .project(let snapshot) = try await restarted.snapshot(.project(harness.projectID)) else {
            return XCTFail("Expected a restarted project snapshot")
        }
        XCTAssertEqual(
            snapshot,
            NovelProjectSnapshot(loaded: NovelLoadedProject(
                document: durable,
                access: .readWrite
            ))
        )
    }

    func testCancellingJoinedAdoptionWaiterDoesNotCancelTheOwner() async throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("NovelPolishJoinedWaiter-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = PolishFinalAdoptionCommitGateRepository(rootDirectory: root)
        let harness = try await makeHarness(
            repository: repository,
            remainingScripts: [NovelModelScript(steps: [
                .delta(compatibleDriftJSON),
                .complete,
            ])],
            polishAssessmentTimeout: 5
        )
        let candidateID = try await generatePolish(in: harness)
        let baseline = try await document(in: harness)
        let command = adoptionCommand(document: baseline, candidateID: candidateID)
        await repository.armFinalCommit(operationID: command.context.operationID)

        let owner = Task {
            try await harness.creation.perform(.adoptPolishCandidate(command))
        }
        await repository.waitUntilFinalCommitBlocked()
        let joinedWaiter = Task {
            try await harness.creation.perform(.adoptPolishCandidate(command))
        }
        await Task.yield()
        joinedWaiter.cancel()
        try? await Task.sleep(nanoseconds: 20_000_000)

        let ownerAuthorizationWasRevoked = await repository.finalCommitAuthorizationWasRevoked()
        await repository.resumeFinalCommit()
        XCTAssertFalse(ownerAuthorizationWasRevoked)
        do {
            _ = try await joinedWaiter.value
            XCTFail("A cancelled joined waiter should detach from the owner mutation")
        } catch is CancellationError {
            // Cancelling a subscriber does not own or cancel the shared mutation.
        } catch {
            XCTFail("Expected CancellationError, got \(error)")
        }

        let outcome = try await owner.value
        guard case .polishCandidateAdopted(
            _, _, let adoptedCandidateID, _, let versionID, _
        ) = outcome else {
            return XCTFail("The owner should complete the single adoption")
        }
        let durable = try await repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(adoptedCandidateID, candidateID)
        XCTAssertEqual(versionID, command.proposedChapterVersionID)
        XCTAssertEqual(durable.candidates.first { $0.id == candidateID }?.status, .adopted)
        XCTAssertEqual(durable.appliedOperations.filter {
            $0.operationID == command.context.operationID &&
                $0.kind == .adoptPolishCandidate
        }.count, 1)
        let finalCommitCount = await repository.finalCommitCount()
        XCTAssertEqual(finalCommitCount, 1)
        XCTAssertNoThrow(try NovelDocumentValidator.validate(durable))
    }

    func testRevokedFinalCommitReconcilesIndeterminateCancellationWriteIntoCache() async throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("NovelPolishCancellationReconcile-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = PolishFinalAdoptionCommitGateRepository(rootDirectory: root)
        let harness = try await makeHarness(
            repository: repository,
            remainingScripts: [NovelModelScript(steps: [
                .delta(compatibleDriftJSON),
                .complete,
            ])],
            polishAssessmentTimeout: 5
        )
        let candidateID = try await generatePolish(in: harness)
        let baseline = try await document(in: harness)
        let command = adoptionCommand(document: baseline, candidateID: candidateID)
        await repository.armFinalCommit(operationID: command.context.operationID)
        await repository.failNextCancellationWriteAfterInstall()

        let adoption = Task {
            try await harness.creation.perform(.adoptPolishCandidate(command))
        }
        await repository.waitUntilFinalCommitBlocked()
        let backgroundDeadline = now.addingTimeInterval(1)
        let interruption = Task {
            await harness.creation.interruptForBackground(
                projectID: harness.projectID,
                deadline: backgroundDeadline
            )
        }
        let revokeDeadline = Date().addingTimeInterval(1)
        while Date() < revokeDeadline,
              !(await repository.finalCommitAuthorizationWasRevoked()) {
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
        await repository.resumeFinalCommit()
        await interruption.value
        do {
            _ = try await adoption.value
            XCTFail("The revoked final commit should remain cancelled")
        } catch is CancellationError {
            // The outward cancellation remains authoritative.
        } catch {
            XCTFail("Expected CancellationError, got \(error)")
        }

        let injected = await repository.failedCancellationWriteAfterInstall()
        XCTAssertTrue(injected)
        let durable = try await repository.loadProject(id: harness.projectID)
        let expected = NovelProjectSnapshot(loaded: durable)
        guard case .project(let sameProcess) = try await harness.creation.snapshot(
            .project(harness.projectID)
        ) else {
            return XCTFail("Expected a same-process project snapshot")
        }
        XCTAssertEqual(sameProcess, expected)
        XCTAssertEqual(
            sameProcess.polishTransactions.first { $0.id == command.transactionID }?.status,
            .retryable
        )
        XCTAssertEqual(
            sameProcess.polishAssessments.first {
                $0.transactionID == command.transactionID && $0.attemptIndex == 0
            }?.failure?.code,
            "cancelled"
        )

        let restarted = DefaultNovelCreation(repository: repository)
        guard case .project(let restartedSnapshot) = try await restarted.snapshot(
            .project(harness.projectID)
        ) else {
            return XCTFail("Expected a restarted project snapshot")
        }
        XCTAssertEqual(restartedSnapshot, expected)
    }

    func testCancelledAndTimedOutPolishCanBeAbandonedReplayedAndNoLongerBlockBranchLifecycle() async throws {
        let cancellationHarness = try await makeHarness(remainingScripts: [
            NovelModelScript(steps: [.pause]),
        ])
        let sourceBeforeFork = try await document(in: cancellationHarness)
        let sourceBranch = sourceBeforeFork.branches[0]
        let childID = NovelBranchID()
        _ = try await cancellationHarness.creation.perform(.forkBranch(NovelForkBranchCommand(
            context: mutationContext(document: sourceBeforeFork),
            projectID: sourceBeforeFork.project.id,
            sourceBranchID: sourceBranch.id,
            checkpointID: sourceBranch.headCheckpointID,
            branchID: childID,
            sessionID: NovelSessionID(),
            name: "Keep"
        )))
        let forked = try await document(in: cancellationHarness)
        _ = try await cancellationHarness.creation.perform(.setMainBranch(
            NovelSetMainBranchCommand(
                context: mutationContext(document: forked),
                projectID: forked.project.id,
                branchID: childID
            )
        ))
        let readyToGenerate = try await document(in: cancellationHarness)
        let cancelledCandidateID = try await generatePolish(
            in: cancellationHarness,
            document: readyToGenerate
        )
        let generated = try await document(in: cancellationHarness)
        let cancelledAdoption = adoptionCommand(
            document: generated,
            candidateID: cancelledCandidateID
        )
        let adoption = Task {
            try await cancellationHarness.creation.perform(.adoptPolishCandidate(cancelledAdoption))
        }
        let cancellationProviderStarted = await waitForRequestCount(
            2,
            adapter: cancellationHarness.adapter
        )
        XCTAssertTrue(cancellationProviderStarted)
        adoption.cancel()
        _ = try? await adoption.value

        let retryableAfterCancel = try await document(in: cancellationHarness)
        let cancelledTransaction = try XCTUnwrap(retryableAfterCancel.polishTransactions.first {
            $0.id == cancelledAdoption.transactionID
        })
        XCTAssertEqual(cancelledTransaction.status, .retryable)
        let abandonCancelled = NovelAbandonPolishTransactionCommand(
            context: mutationContext(document: retryableAfterCancel),
            projectID: retryableAfterCancel.project.id,
            branchID: sourceBranch.id,
            transactionID: cancelledTransaction.id
        )
        let cancelledOutcome = try await cancellationHarness.creation.perform(
            .abandonPolishTransaction(abandonCancelled)
        )
        guard case .polishTransactionAbandoned = cancelledOutcome else {
            return XCTFail("Expected the cancelled polish transaction to be abandoned")
        }
        let abandonedAfterCancel = try await document(in: cancellationHarness)
        XCTAssertEqual(
            abandonedAfterCancel.polishTransactions.first {
                $0.id == cancelledTransaction.id
            }?.status,
            .abandoned
        )

        let restarted = DefaultNovelCreation(repository: cancellationHarness.repository)
        let replay = try await restarted.perform(.abandonPolishTransaction(abandonCancelled))
        XCTAssertEqual(replay, cancelledOutcome)
        let afterCancelledReplay = try await document(in: cancellationHarness)
        XCTAssertEqual(afterCancelledReplay, abandonedAfterCancel)

        let undoOutcome = try await restarted.perform(.undoBranchHead(
            NovelUndoBranchHeadCommand(
                context: mutationContext(document: abandonedAfterCancel),
                projectID: abandonedAfterCancel.project.id,
                branchID: sourceBranch.id,
                expectedWorkingRevision: abandonedAfterCancel.branches[0].workingRevision
            )
        ))
        guard case .branchHeadMoved = undoOutcome else {
            return XCTFail("Abandoned polish must not block undo")
        }
        let afterUndo = try await document(in: cancellationHarness)
        let deleteOutcome = try await restarted.perform(.deleteBranch(
            NovelDeleteBranchCommand(
                context: mutationContext(document: afterUndo),
                projectID: afterUndo.project.id,
                branchID: sourceBranch.id
            )
        ))
        guard case .branchDeleted = deleteOutcome else {
            return XCTFail("Abandoned polish must not block branch deletion")
        }
        let afterDelete = try await document(in: cancellationHarness)
        let secondRestart = DefaultNovelCreation(repository: cancellationHarness.repository)
        guard case .project(let restartedSnapshot) = try await secondRestart.snapshot(
            .project(afterDelete.project.id)
        ) else {
            return XCTFail("Expected a project snapshot after restart")
        }
        XCTAssertEqual(
            restartedSnapshot.branches.first { $0.id == sourceBranch.id }?.lifecycle,
            .deleted
        )

        let timeoutHarness = try await makeHarness(
            remainingScripts: [NovelModelScript(steps: [.pause])],
            polishAssessmentTimeout: 0.02
        )
        let timeoutCandidateID = try await generatePolish(in: timeoutHarness)
        let timeoutGenerated = try await document(in: timeoutHarness)
        let timeoutAdoption = adoptionCommand(
            document: timeoutGenerated,
            candidateID: timeoutCandidateID
        )
        _ = try? await timeoutHarness.creation.perform(
            .adoptPolishCandidate(timeoutAdoption)
        )
        let retryableAfterTimeout = try await document(in: timeoutHarness)
        let timedOutTransaction = try XCTUnwrap(retryableAfterTimeout.polishTransactions.first {
            $0.id == timeoutAdoption.transactionID
        })
        XCTAssertEqual(timedOutTransaction.status, .retryable)
        XCTAssertEqual(timedOutTransaction.lastFailure?.code, "polish_assessment_timeout")
        let abandonTimedOut = NovelAbandonPolishTransactionCommand(
            context: mutationContext(document: retryableAfterTimeout),
            projectID: retryableAfterTimeout.project.id,
            branchID: retryableAfterTimeout.branches[0].id,
            transactionID: timedOutTransaction.id
        )
        let timedOutOutcome = try await timeoutHarness.creation.perform(
            .abandonPolishTransaction(abandonTimedOut)
        )
        guard case .polishTransactionAbandoned = timedOutOutcome else {
            return XCTFail("Expected the timed-out polish transaction to be abandoned")
        }
        let abandonedAfterTimeout = try await document(in: timeoutHarness)
        XCTAssertEqual(
            abandonedAfterTimeout.polishTransactions.first {
                $0.id == timedOutTransaction.id
            }?.status,
            .abandoned
        )
        let timeoutRestart = DefaultNovelCreation(repository: timeoutHarness.repository)
        let timeoutReplay = try await timeoutRestart.perform(
            .abandonPolishTransaction(abandonTimedOut)
        )
        XCTAssertEqual(timeoutReplay, timedOutOutcome)
        let afterTimeoutReplay = try await document(in: timeoutHarness)
        XCTAssertEqual(afterTimeoutReplay, abandonedAfterTimeout)
    }

    func testSourceChangesAtomicallyBlockRetryWithoutPermanentlyBlockingDeleteOrUndo() async throws {
        let deleteHarness = try await makeHarness(remainingScripts: [
            NovelModelScript(steps: [.delta("not-json"), .complete]),
        ])
        let initial = try await document(in: deleteHarness)
        let sourceBranch = initial.branches[0]
        let childID = NovelBranchID()
        _ = try await deleteHarness.creation.perform(.forkBranch(NovelForkBranchCommand(
            context: mutationContext(document: initial),
            projectID: initial.project.id,
            sourceBranchID: sourceBranch.id,
            checkpointID: sourceBranch.headCheckpointID,
            branchID: childID,
            sessionID: NovelSessionID(),
            name: "Keep"
        )))
        let forked = try await document(in: deleteHarness)
        _ = try await deleteHarness.creation.perform(.setMainBranch(NovelSetMainBranchCommand(
            context: mutationContext(document: forked),
            projectID: forked.project.id,
            branchID: childID
        )))
        let deleteRetryable = try await makeRetryablePolish(in: deleteHarness)
        let source = try sourceVersion(
            in: deleteRetryable.document,
            id: deleteHarness.sourceVersionID
        )
        let candidate = try XCTUnwrap(deleteRetryable.document.candidates.first {
            $0.id == deleteRetryable.candidateID
        })
        let edit = NovelSaveManualEditCommand(
            context: mutationContext(document: deleteRetryable.document),
            projectID: deleteRetryable.document.project.id,
            branchID: sourceBranch.id,
            chapterID: source.chapterID,
            versionID: NovelChapterVersionID(),
            title: source.title,
            content: candidate.content,
            factCompatibilityID: UUID(),
            expectedWorkingRevision: deleteRetryable.document.branches[0].workingRevision
        )
        _ = try await deleteHarness.creation.perform(.saveManualEdit(edit))
        let edited = try await document(in: deleteHarness)
        let attemptCount = edited.polishAttempts.count
        let receiptCount = edited.injectionReceipts.count
        await NovelXCTAssertThrowsErrorAsync(
            try await deleteHarness.creation.perform(
                .adoptPolishCandidate(deleteRetryable.command)
            )
        )
        let deleteBlocked = try await document(in: deleteHarness)
        XCTAssertEqual(deleteBlocked.polishTransactions.first?.status, .blocked)
        XCTAssertEqual(deleteBlocked.polishTransactions.first?.lastFailure?.code, "polish_source_changed")
        XCTAssertNil(deleteBlocked.polishTransactions.first?.lastFailureAttemptIndex)
        XCTAssertEqual(deleteBlocked.polishAttempts.count, attemptCount)
        XCTAssertEqual(deleteBlocked.injectionReceipts.count, receiptCount)
        let deleteOutcome = try await deleteHarness.creation.perform(.deleteBranch(
            NovelDeleteBranchCommand(
                context: mutationContext(document: deleteBlocked),
                projectID: deleteBlocked.project.id,
                branchID: sourceBranch.id
            )
        ))
        guard case .branchDeleted = deleteOutcome else {
            return XCTFail("Blocked polish must not permanently block branch deletion")
        }

        let undoHarness = try await makeHarness(remainingScripts: [
            NovelModelScript(steps: [.delta("not-json"), .complete]),
        ])
        let undoRetryable = try await makeRetryablePolish(in: undoHarness)
        let restore = restoreCommand(
            document: undoRetryable.document,
            targetVersionID: undoHarness.sourceVersionID
        )
        _ = try await undoHarness.creation.perform(.restoreChapterVersion(restore))
        let restored = try await document(in: undoHarness)
        let undoAttemptCount = restored.polishAttempts.count
        await NovelXCTAssertThrowsErrorAsync(
            try await undoHarness.creation.perform(.adoptPolishCandidate(undoRetryable.command))
        )
        let undoBlocked = try await document(in: undoHarness)
        XCTAssertEqual(undoBlocked.polishTransactions.first?.status, .blocked)
        XCTAssertEqual(undoBlocked.polishTransactions.first?.lastFailure?.code, "polish_source_changed")
        XCTAssertEqual(undoBlocked.polishAttempts.count, undoAttemptCount)
        let undoOutcome = try await undoHarness.creation.perform(.undoBranchHead(
            NovelUndoBranchHeadCommand(
                context: mutationContext(document: undoBlocked),
                projectID: undoBlocked.project.id,
                branchID: undoBlocked.branches[0].id,
                expectedWorkingRevision: undoBlocked.branches[0].workingRevision
            )
        ))
        guard case .branchHeadMoved = undoOutcome else {
            return XCTFail("Blocked polish must not permanently block branch undo")
        }
    }

    func testAfterWriteIndeterminateAndFirstReconcileReadFailureStillReturnReplayOutcome() async throws {
        let fixture = try documentWithChapterAndState()
        let repository = PolishReconciliationRepository()
        try await repository.seed(fixture.document)
        let polishedContent = "Mara crossed the courtyard. The sealed gate stayed behind her."
        let adapter = ScriptedNovelModelAdapter(
            resolvedModel: resolvedModel,
            scripts: [
                NovelModelScript(steps: [
                    .delta("\(polishedContent)\n\(NovelPromptCatalog.polishCompletionSentinel)"),
                    .complete,
                ]),
                NovelModelScript(steps: [.delta(compatibleDriftJSON), .complete]),
            ]
        )
        let harness = NovelPolishTestCase.Harness(
            repository: repository,
            adapter: adapter,
            creation: DefaultNovelCreation(
                repository: repository,
                modelRunner: adapter,
                polishAssessmentTimeout: 1,
                now: { Date(timeIntervalSince1970: 1_700_500_000) }
            ),
            projectID: fixture.document.project.id,
            sourceVersionID: fixture.sourceVersionID,
            sourceContent: fixture.sourceContent,
            polishedContent: polishedContent
        )
        let candidateID = try await generatePolish(in: harness)
        let generated = try await document(in: harness)
        let command = adoptionCommand(document: generated, candidateID: candidateID)

        let outcome = try await harness.creation.perform(.adoptPolishCandidate(command))
        guard case .polishCandidateAdopted = outcome else {
            return XCTFail("Expected durable replay of the adopted polish outcome")
        }
        let durable = try await document(in: harness)
        XCTAssertEqual(durable.polishTransactions.first?.status, .completed)
        XCTAssertEqual(durable.appliedOperations.filter {
            $0.operationID == command.context.operationID
        }.count, 1)
        let didInject = await repository.didInjectAfterWriteFailure()
        let reconcileFailures = await repository.injectedReconcileReadFailures()
        XCTAssertTrue(didInject)
        XCTAssertEqual(reconcileFailures, 1)
    }
}
