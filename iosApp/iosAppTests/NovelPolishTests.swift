import XCTest
@testable import iosApp

final class NovelPolishTests: NovelPolishTestCase {
    func testProjectPolishPreferenceIsNormalizedInjectedAndSubordinateToFixedV2Prompt() async throws {
        let harness = try await makeHarness(remainingScripts: [])
        let initial = try await document(in: harness)
        let preference = "  Favor spare dialogue, but add no events.  "
        let command = NovelSetPolishPreferenceCommand(
            context: mutationContext(document: initial),
            projectID: initial.project.id,
            preference: preference
        )

        let outcome = try await harness.creation.perform(.setPolishPreference(command))
        let configured = try await document(in: harness)

        XCTAssertEqual(configured.project.polishPreference, preference.trimmingCharacters(in: .whitespaces))
        XCTAssertEqual(configured.project.configRevision, initial.project.configRevision + 1)
        XCTAssertEqual(configured.project.revision, initial.project.revision + 1)
        XCTAssertEqual(
            outcome,
            .polishPreferenceChanged(
                projectID: configured.project.id,
                projectRevision: configured.project.revision,
                configRevision: configured.project.configRevision
            )
        )

        let candidateID = try await generatePolish(in: harness, document: configured)
        let generated = try await document(in: harness)
        let candidate = try XCTUnwrap(generated.candidates.first { $0.id == candidateID })
        let receipt = try XCTUnwrap(generated.injectionReceipts.last)
        let prompt = NovelPromptCatalog.template(for: .wholeChapterPolish)
        let expectedPlan = try NovelInjectionPlanner.plan(
            document: configured,
            request: NovelInjectionPlanningRequest(
                branchID: configured.branches[0].id,
                promptKind: .wholeChapterPolish,
                userText: "Polish this complete chapter without changing its story.",
                sourceChapterVersionID: harness.sourceVersionID
            )
        )

        XCTAssertEqual(prompt.version, "novel.whole-chapter-polish.v2")
        XCTAssertTrue(prompt.systemText.contains("must not add, remove, reorder"))
        XCTAssertTrue(prompt.systemText.contains("subordinate"))
        XCTAssertTrue(prompt.systemText.contains(NovelPromptCatalog.polishCompletionSentinel))
        XCTAssertEqual(receipt.promptVersion, prompt.version)
        XCTAssertEqual(receipt.sections.map(\.kind), expectedPlan.sections.map(\.kind))
        XCTAssertEqual(receipt.sections.map(\.contentSHA256), expectedPlan.sections.map(\.contentSHA256))
        XCTAssertEqual(receipt.canonicalInputSHA256, expectedPlan.canonicalInputSHA256)
        XCTAssertEqual(
            receipt.sections.first { $0.kind == .polishPreference }?.contentSHA256,
            NovelDocumentValidator.sha256(configured.project.polishPreference)
        )
        XCTAssertEqual(
            receipt.sections.first {
                $0.kind == .chapterContext(harness.sourceVersionID)
            }?.contentSHA256,
            expectedPlan.sections.first {
                $0.kind == .chapterContext(harness.sourceVersionID)
            }?.contentSHA256
        )
        XCTAssertEqual(candidate.content, harness.polishedContent)
        XCTAssertFalse(candidate.content.contains(NovelPromptCatalog.polishCompletionSentinel))

        let modelRequests = await harness.adapter.requests
        let modelRequest = try XCTUnwrap(modelRequests.first)
        let modelInput = modelRequest.messages.map(\.content).joined(separator: "\n")
        XCTAssertEqual(modelRequest.purpose, .polish)
        XCTAssertTrue(modelInput.contains(configured.project.polishPreference))
        XCTAssertTrue(modelInput.contains(harness.sourceContent))
        XCTAssertTrue(modelInput.contains(NovelPromptCatalog.polishCompletionSentinel))
        XCTAssertEqual(
            NovelPromptCatalog.completedPolishContent(
                from: "\(harness.polishedContent)\n\(NovelPromptCatalog.polishCompletionSentinel)"
            ),
            harness.polishedContent
        )
        XCTAssertNil(NovelPromptCatalog.completedPolishContent(from: harness.polishedContent))
        XCTAssertNil(NovelPromptCatalog.completedPolishContent(
            from: "\(NovelPromptCatalog.polishCompletionSentinel)\n\(NovelPromptCatalog.polishCompletionSentinel)"
        ))
    }

    func testCompatibleAdoptionPersistsExactReceiptsBeforeDispatchAndReplaysAfterRestart() async throws {
        let harness = try await makeHarness(remainingScripts: [
            NovelModelScript(steps: [.pause, .delta(compatibleDriftJSON), .complete]),
        ])
        let candidateID = try await generatePolish(in: harness)
        let baseline = try await document(in: harness)
        let command = adoptionCommand(document: baseline, candidateID: candidateID)
        let source = try sourceVersion(in: baseline, id: harness.sourceVersionID)
        let candidate = try XCTUnwrap(baseline.candidates.first { $0.id == candidateID })
        let evidenceBefore = try canonicalEvidence(in: baseline)

        let adoption = Task {
            try await harness.creation.perform(.adoptPolishCandidate(command))
        }
        let reachedDriftRequest = await waitForRequestCount(2, adapter: harness.adapter)
        XCTAssertTrue(reachedDriftRequest)

        let requestsBeforeResume = await harness.adapter.requests
        let request = try XCTUnwrap(requestsBeforeResume.last)
        let pending = try await document(in: harness)
        let transaction = try XCTUnwrap(pending.polishTransactions.first {
            $0.id == command.transactionID
        })
        let attempt = try XCTUnwrap(pending.polishAttempts.first)
        let injection = try XCTUnwrap(pending.injectionReceipts.first {
            $0.id == attempt.injectionReceiptID
        })
        let generation = try XCTUnwrap(pending.generationReceipts.first {
            $0.id == attempt.generationReceiptID
        })

        XCTAssertEqual(request.runID, attempt.runID)
        XCTAssertEqual(request.purpose, .driftCheck)
        XCTAssertEqual(transaction.status, .pending)
        XCTAssertEqual(transaction.attemptCount, 1)
        XCTAssertEqual(pending.chapterVersions, baseline.chapterVersions)
        XCTAssertEqual(pending.checkpoints, baseline.checkpoints)
        XCTAssertEqual(pending.events, baseline.events)
        XCTAssertEqual(pending.stateSnapshots, baseline.stateSnapshots)
        XCTAssertEqual(pending.injectionReceipts.count, baseline.injectionReceipts.count + 1)
        XCTAssertEqual(pending.generationReceipts.count, baseline.generationReceipts.count + 1)
        XCTAssertEqual(generation.injectionReceiptID, injection.id)
        XCTAssertEqual(generation.runID, injection.runID)
        XCTAssertEqual(injection.promptVersion, NovelPromptCatalog.template(for: .polishDriftV1).version)
        let expectedMessages = driftMessages(source: source.content, candidate: candidate.content)
        XCTAssertEqual(request.messages, expectedMessages)
        let expectedSections = expectedMessages.map { message in
            (
                kind: message.role == .system
                    ? NovelInjectionSectionKind.fixedPrompt
                    : NovelInjectionSectionKind.userInput,
                label: "\(message.role.rawValue.uppercased()) MESSAGE",
                reason: message.role == .system
                    ? NovelInjectionSelectionReason.requiredPrompt
                    : NovelInjectionSelectionReason.requiredUserInput,
                hash: NovelDocumentValidator.sha256(message.content)
            )
        }
        XCTAssertEqual(injection.sections.map(\.kind), expectedSections.map { $0.kind })
        XCTAssertEqual(injection.sections.map(\.label), expectedSections.map { $0.label })
        XCTAssertEqual(injection.sections.map(\.reason), expectedSections.map { $0.reason })
        XCTAssertEqual(injection.sections.map(\.contentSHA256), expectedSections.map { $0.hash })
        let expectedDriftInput = zip(expectedSections, expectedMessages).map { section, message in
            "[\(section.label)]\n\(message.content)"
        }.joined(separator: "\n\n")
        XCTAssertEqual(
            injection.canonicalInputSHA256,
            NovelDocumentValidator.sha256(expectedDriftInput)
        )
        let driftInput = request.messages.map(\.content).joined(separator: "\n")
        XCTAssertTrue(driftInput.contains(source.content))
        XCTAssertTrue(driftInput.contains(candidate.content))

        await harness.adapter.resume(runID: request.runID)
        let outcome = try await adoption.value
        let adopted = try await document(in: harness)
        let adoptedCandidate = try XCTUnwrap(adopted.candidates.first { $0.id == candidateID })
        let adoptedVersion = try XCTUnwrap(adopted.chapterVersions.first {
            $0.id == command.proposedChapterVersionID
        })
        let checkpoint = try XCTUnwrap(adopted.checkpoints.first { $0.id == command.checkpointID })
        let sourceMessage = try XCTUnwrap(adopted.sessions[0].messages.first {
            $0.id == candidate.sourceMessageID
        })

        XCTAssertEqual(
            outcome,
            .polishCandidateAdopted(
                projectID: adopted.project.id,
                branchID: adopted.branches[0].id,
                candidateID: candidateID,
                checkpointID: checkpoint.id,
                chapterVersionID: adoptedVersion.id,
                revision: adopted.project.revision
            )
        )
        XCTAssertEqual(adoptedCandidate.status, .adopted)
        XCTAssertEqual(adoptedCandidate.collectedCheckpointID, checkpoint.id)
        XCTAssertEqual(adoptedVersion.kind, .polish)
        XCTAssertEqual(adoptedVersion.content, candidate.content)
        XCTAssertEqual(adoptedVersion.factCompatibilityID, source.factCompatibilityID)
        XCTAssertEqual(adoptedVersion.sourceChapterVersionID, source.id)
        XCTAssertEqual(adoptedVersion.sourceCandidateID, candidate.id)
        XCTAssertEqual(checkpoint.parentCheckpointID, baseline.branches[0].headCheckpointID)
        XCTAssertEqual(checkpoint.stateSnapshotID, baseline.branches[0].currentStateSnapshotID)
        XCTAssertEqual(checkpoint.sessionCursor, .through(sequence: sourceMessage.sequence))
        XCTAssertEqual(adopted.polishTransactions[0].status, .completed)
        XCTAssertEqual(adopted.polishAssessments.first?.result?.compatible, true)
        XCTAssertEqual(try canonicalEvidence(in: adopted), evidenceBefore)
        XCTAssertEqual(adopted.events, baseline.events)
        XCTAssertEqual(adopted.stateSnapshots, baseline.stateSnapshots)

        let restarted = DefaultNovelCreation(repository: harness.repository)
        let replay = try await restarted.perform(.adoptPolishCandidate(command))
        let afterReplay = try await document(in: harness)
        XCTAssertEqual(replay, outcome)
        XCTAssertEqual(afterReplay, adopted)
        let requestCount = await harness.adapter.requests.count
        XCTAssertEqual(requestCount, 2)
    }

    func testIncompatibleVerdictSupersedesCandidateAndManualConversionRequiresSync() async throws {
        let harness = try await makeHarness(remainingScripts: [
            NovelModelScript(steps: [.delta(incompatibleDriftJSON), .complete]),
            NovelModelScript(steps: [.delta(manualRebuildJSON), .complete]),
        ])
        let candidateID = try await generatePolish(in: harness)
        let baseline = try await document(in: harness)
        let candidate = try XCTUnwrap(baseline.candidates.first { $0.id == candidateID })
        let source = try sourceVersion(in: baseline, id: harness.sourceVersionID)
        let command = adoptionCommand(document: baseline, candidateID: candidateID)

        let outcome = try await harness.creation.perform(.adoptPolishCandidate(command))
        let rejected = try await document(in: harness)

        XCTAssertEqual(
            outcome,
            .polishCandidateRejected(
                projectID: rejected.project.id,
                branchID: rejected.branches[0].id,
                candidateID: candidateID,
                transactionID: command.transactionID,
                revision: rejected.project.revision
            )
        )
        XCTAssertEqual(rejected.candidates.first { $0.id == candidateID }?.status, .superseded)
        XCTAssertEqual(rejected.polishTransactions[0].status, .incompatible)
        XCTAssertEqual(rejected.polishAssessments.first?.result?.compatible, false)
        XCTAssertFalse(rejected.polishAssessments.first?.result?.differences.isEmpty ?? true)
        XCTAssertEqual(rejected.chapterVersions, baseline.chapterVersions)
        XCTAssertEqual(rejected.checkpoints, baseline.checkpoints)

        let compatibilityID = UUID()
        let manualVersionID = NovelChapterVersionID()
        let manual = NovelSaveManualEditCommand(
            context: mutationContext(document: rejected),
            projectID: rejected.project.id,
            branchID: rejected.branches[0].id,
            chapterID: source.chapterID,
            versionID: manualVersionID,
            title: source.title,
            content: candidate.content,
            factCompatibilityID: compatibilityID,
            expectedWorkingRevision: rejected.branches[0].workingRevision
        )
        _ = try await harness.creation.perform(.saveManualEdit(manual))
        let needsSync = try await document(in: harness)
        let manualVersion = try XCTUnwrap(needsSync.chapterVersions.first { $0.id == manualVersionID })

        XCTAssertEqual(needsSync.branches[0].syncStatus, .needsSync)
        XCTAssertEqual(needsSync.branches[0].workingRevision, rejected.branches[0].workingRevision + 1)
        XCTAssertEqual(manualVersion.kind, .manualEdit)
        XCTAssertEqual(manualVersion.content, candidate.content)
        XCTAssertEqual(manualVersion.factCompatibilityID, compatibilityID)
        XCTAssertEqual(manualVersion.sourceChapterVersionID, source.id)

        let sync = NovelSyncManualEditsCommand(
            context: mutationContext(document: needsSync),
            projectID: needsSync.project.id,
            branchID: needsSync.branches[0].id,
            pendingID: NovelPendingOperationID(),
            checkpointID: NovelCheckpointID(),
            stateSnapshotID: NovelStateSnapshotID(),
            expectedWorkingRevision: needsSync.branches[0].workingRevision
        )
        _ = try await harness.creation.perform(.syncManualEdits(sync))
        let synchronized = try await document(in: harness)
        XCTAssertEqual(synchronized.branches[0].syncStatus, .synchronized)

        let restore = restoreCommand(
            document: synchronized,
            targetVersionID: source.id
        )
        await NovelXCTAssertThrowsErrorAsync(
            try await harness.creation.perform(.restoreChapterVersion(restore))
        ) { error in
            guard case .invalidInput(let message) = error as? NovelError else {
                return XCTFail("Expected incompatible restore rejection, got \(error)")
            }
            XCTAssertTrue(message.contains("manual edit"))
        }
        let afterRejectedRestore = try await document(in: harness)
        XCTAssertEqual(afterRejectedRestore, synchronized)
    }

    func testInvalidJSONFailsClosedThenRestartRetryUsesFreshDurableAttempt() async throws {
        let harness = try await makeHarness(remainingScripts: [
            NovelModelScript(steps: [.delta("not-json"), .complete]),
            NovelModelScript(steps: [.delta(compatibleDriftJSON), .complete]),
        ])
        let candidateID = try await generatePolish(in: harness)
        let baseline = try await document(in: harness)
        let command = adoptionCommand(document: baseline, candidateID: candidateID)

        await NovelXCTAssertThrowsErrorAsync(
            try await harness.creation.perform(.adoptPolishCandidate(command))
        )
        let failed = try await document(in: harness)
        try assertFailClosed(
            failed,
            baseline: baseline,
            candidateID: candidateID,
            expectedCode: "invalid_structured_output"
        )

        let firstAttempt = try XCTUnwrap(failed.polishAttempts.first)
        let firstAssessment = try XCTUnwrap(failed.polishAssessments.first)
        let restarted = DefaultNovelCreation(
            repository: harness.repository,
            modelRunner: harness.adapter,
            polishAssessmentTimeout: 1,
            now: { Date(timeIntervalSince1970: 1_700_500_100) }
        )
        let outcome = try await restarted.perform(.adoptPolishCandidate(command))
        let retried = try await document(in: harness)

        guard case .polishCandidateAdopted = outcome else {
            return XCTFail("Expected a compatible retry to adopt the candidate")
        }
        XCTAssertEqual(retried.polishTransactions[0].status, .completed)
        XCTAssertEqual(retried.polishTransactions[0].attemptCount, 2)
        XCTAssertEqual(retried.polishAttempts.map(\.attemptIndex), [0, 1])
        XCTAssertEqual(Set(retried.polishAttempts.map(\.runID)).count, 2)
        XCTAssertEqual(Set(retried.polishAttempts.map(\.injectionReceiptID)).count, 2)
        XCTAssertEqual(Set(retried.polishAttempts.map(\.generationReceiptID)).count, 2)
        XCTAssertEqual(retried.polishAssessments.count, 2)
        XCTAssertEqual(retried.polishAssessments[0], firstAssessment)
        XCTAssertEqual(retried.polishAttempts[0], firstAttempt)
        XCTAssertEqual(retried.polishAssessments[1].result?.compatible, true)
        XCTAssertNil(retried.polishAssessments[1].failure)
    }

    func testModelFailureAndTimeoutFailClosedWithRetryableEvidenceAndNoAdoptionRecords() async throws {
        let cases: [(name: String, script: NovelModelScript, timeout: TimeInterval, code: String)] = [
            (
                "model failure",
                NovelModelScript(steps: [.fail(NovelModelFailure(
                    code: "provider_failed",
                    message: "Provider failed.",
                    isRetryable: true
                ))]),
                1,
                "provider_failed"
            ),
            (
                "timeout",
                NovelModelScript(steps: [.pause], ignoresCancellation: true),
                0.02,
                "polish_assessment_timeout"
            ),
        ]

        for testCase in cases {
            let harness = try await makeHarness(
                remainingScripts: [testCase.script],
                polishAssessmentTimeout: testCase.timeout
            )
            let candidateID = try await generatePolish(in: harness)
            let baseline = try await document(in: harness)
            let command = adoptionCommand(document: baseline, candidateID: candidateID)

            await NovelXCTAssertThrowsErrorAsync(
                try await harness.creation.perform(.adoptPolishCandidate(command))
            )
            let failed = try await document(in: harness)
            try assertFailClosed(
                failed,
                baseline: baseline,
                candidateID: candidateID,
                expectedCode: testCase.code
            )
            XCTAssertEqual(failed.polishAttempts.count, 1, testCase.name)
            XCTAssertEqual(failed.polishAssessments.count, 1, testCase.name)
            XCTAssertEqual(
                failed.injectionReceipts.count,
                baseline.injectionReceipts.count + 1,
                testCase.name
            )
            XCTAssertEqual(
                failed.generationReceipts.count,
                baseline.generationReceipts.count + 1,
                testCase.name
            )

            if testCase.code == "polish_assessment_timeout" {
                let requests = await harness.adapter.requests
                let request = try XCTUnwrap(requests.last)
                let cancellationArrived = await waitForCancellation(
                    runID: request.runID,
                    adapter: harness.adapter
                )
                XCTAssertTrue(cancellationArrived)
                await harness.adapter.resume(runID: request.runID)
            }
        }
    }

    func testSameLineageRestoreIsSafeAndOperationOrReservedIDReuseIsRejected() async throws {
        let harness = try await makeHarness(remainingScripts: [
            NovelModelScript(steps: [.delta(compatibleDriftJSON), .complete]),
        ])
        let candidateID = try await generatePolish(in: harness)
        let baseline = try await document(in: harness)
        let adoption = adoptionCommand(document: baseline, candidateID: candidateID)
        _ = try await harness.creation.perform(.adoptPolishCandidate(adoption))
        let adopted = try await document(in: harness)
        let polished = try XCTUnwrap(adopted.chapterVersions.first {
            $0.id == adoption.proposedChapterVersionID
        })
        let source = try sourceVersion(in: adopted, id: harness.sourceVersionID)
        let evidence = try canonicalEvidence(in: adopted)
        let restore = restoreCommand(document: adopted, targetVersionID: source.id)

        let outcome = try await harness.creation.perform(.restoreChapterVersion(restore))
        let restored = try await document(in: harness)
        let restoredVersion = try XCTUnwrap(restored.chapterVersions.first {
            $0.id == restore.proposedChapterVersionID
        })

        guard case .chapterVersionRestored = outcome else {
            return XCTFail("Expected a same-lineage restore")
        }
        XCTAssertEqual(restoredVersion.kind, .restore)
        XCTAssertEqual(restoredVersion.content, source.content)
        XCTAssertEqual(restoredVersion.factCompatibilityID, polished.factCompatibilityID)
        XCTAssertEqual(restoredVersion.sourceChapterVersionID, source.id)
        XCTAssertEqual(try canonicalEvidence(in: restored), evidence)

        let replay = try await harness.creation.perform(.restoreChapterVersion(restore))
        XCTAssertEqual(replay, outcome)
        let afterReplay = try await document(in: harness)
        XCTAssertEqual(afterReplay, restored)

        let changedPayload = NovelRestoreChapterVersionCommand(
            context: restore.context,
            projectID: restore.projectID,
            branchID: restore.branchID,
            targetChapterVersionID: polished.id,
            proposedChapterVersionID: NovelChapterVersionID(),
            checkpointID: NovelCheckpointID(),
            expectedWorkingRevision: restore.expectedWorkingRevision
        )
        await NovelXCTAssertThrowsErrorAsync(
            try await harness.creation.perform(.restoreChapterVersion(changedPayload))
        ) { error in
            XCTAssertEqual(error as? NovelError, .idempotencyConflict(restore.context.operationID))
        }

        let current = try await document(in: harness)
        let reusedReservedIDs = NovelRestoreChapterVersionCommand(
            context: mutationContext(document: current),
            projectID: current.project.id,
            branchID: current.branches[0].id,
            targetChapterVersionID: source.id,
            proposedChapterVersionID: restore.proposedChapterVersionID,
            checkpointID: restore.checkpointID,
            expectedWorkingRevision: current.branches[0].workingRevision
        )
        await NovelXCTAssertThrowsErrorAsync(
            try await harness.creation.perform(.restoreChapterVersion(reusedReservedIDs))
        ) { error in
            guard case .immutableRecordConflict = error as? NovelError else {
                return XCTFail("Expected reserved restore ID conflict, got \(error)")
            }
        }

        let reusedTransaction = NovelAdoptPolishCandidateCommand(
            context: mutationContext(document: current),
            projectID: current.project.id,
            branchID: current.branches[0].id,
            transactionID: adoption.transactionID,
            candidateID: adoption.candidateID,
            proposedChapterVersionID: NovelChapterVersionID(),
            checkpointID: NovelCheckpointID(),
            expectedWorkingRevision: current.branches[0].workingRevision
        )
        await NovelXCTAssertThrowsErrorAsync(
            try await harness.creation.perform(.adoptPolishCandidate(reusedTransaction))
        ) { error in
            XCTAssertEqual(
                error as? NovelError,
                .idempotencyConflict(reusedTransaction.context.operationID)
            )
        }
    }
}
