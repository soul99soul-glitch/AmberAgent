import XCTest
@testable import iosApp

final class NovelGenerationReducerTests: XCTestCase {
    private let startTime = Date(timeIntervalSince1970: 1_700_000_100)
    private let terminalTime = Date(timeIntervalSince1970: 1_700_000_200)

    func testBeginPersistsUserRunReceiptsAndStartLedgerAtomically() throws {
        let document = try NovelTestFixtures.document()
        let request = makeRequest(
            document: document,
            kind: .prose,
            granularity: .wholeChapter
        )
        let artifacts = try makeArtifacts(document: document, request: request)

        let reduced = try NovelGenerationReducer.begin(
            request,
            artifacts: artifacts,
            in: document,
            now: startTime
        )

        XCTAssertEqual(reduced.document.project.revision, document.project.revision + 1)
        XCTAssertEqual(reduced.document.project.configRevision, document.project.configRevision)
        XCTAssertEqual(reduced.document.project.lastGenerationGranularity, .wholeChapter)
        XCTAssertEqual(reduced.document.sessions[0].messages.count, 1)
        XCTAssertEqual(reduced.document.sessions[0].messages[0].id, request.userMessageID)
        XCTAssertEqual(reduced.document.sessions[0].messages[0].kind, .userInput)
        XCTAssertEqual(reduced.document.sessions[0].messages[0].runID, request.id)
        XCTAssertEqual(reduced.document.branches[0].activeRunID, request.id)
        XCTAssertEqual(reduced.document.activeRuns.first?.status, .running)
        XCTAssertEqual(reduced.document.injectionReceipts, [artifacts.injectionReceipt])
        XCTAssertEqual(reduced.document.generationReceipts, [artifacts.generationReceipt])
        XCTAssertEqual(reduced.document.appliedOperations.last?.kind, .startRun)
        XCTAssertEqual(
            reduced.outcome,
            .runStarted(
                projectID: document.project.id,
                branchID: document.branches[0].id,
                runID: request.id,
                receiptID: request.generationReceiptID,
                revision: document.project.revision + 1
            )
        )
        try NovelDocumentValidator.validateTransition(from: document, to: reduced.document)
    }

    func testContinuationAndWholeChapterBothProduceCollectableProseCandidates() throws {
        for granularity in NovelGenerationGranularity.allCases {
            let original = try NovelTestFixtures.document()
            let request = makeRequest(
                document: original,
                kind: .prose,
                granularity: granularity
            )
            let started = try begin(request, in: original)
            let completed = try NovelGenerationReducer.complete(
                runID: request.id,
                content: "Candidate for \(granularity.rawValue)",
                in: started,
                now: terminalTime
            )

            XCTAssertEqual(completed.document.project.revision, started.project.revision + 1)
            XCTAssertEqual(completed.message?.message.kind, .proseCandidate)
            XCTAssertEqual(completed.document.candidates.count, 1)
            XCTAssertEqual(completed.document.candidates[0].id, request.candidateID)
            XCTAssertEqual(completed.document.candidates[0].kind, .prose)
            XCTAssertEqual(completed.document.candidates[0].status, .available)
            XCTAssertNil(completed.document.candidates[0].collectedCheckpointID)
            XCTAssertNil(completed.document.branches[0].activeRunID)
            XCTAssertEqual(completed.document.activeRuns[0].status, .completed)
            XCTAssertEqual(completed.document.project.lastGenerationGranularity, granularity)
        }
    }

    func testCandidateCompletionDoesNotChangeManuscriptOrStoryState() throws {
        let original = try NovelTestFixtures.documentWithForkableCheckpoint()
        let request = makeRequest(
            document: original,
            kind: .prose,
            granularity: .continuation
        )
        let started = try begin(request, in: original)

        let completed = try NovelGenerationReducer.complete(
            runID: request.id,
            content: "A draft that is not canonical yet.",
            in: started,
            now: terminalTime
        ).document

        XCTAssertEqual(completed.chapters, original.chapters)
        XCTAssertEqual(completed.chapterVersions, original.chapterVersions)
        XCTAssertEqual(completed.events, original.events)
        XCTAssertEqual(completed.stateSnapshots, original.stateSnapshots)
        XCTAssertEqual(completed.checkpoints, original.checkpoints)
        XCTAssertEqual(completed.branches[0].headCheckpointID, original.branches[0].headCheckpointID)
        XCTAssertEqual(completed.branches[0].headRevision, original.branches[0].headRevision)
        XCTAssertEqual(completed.branches[0].workingRevision, original.branches[0].workingRevision)
        XCTAssertEqual(
            completed.branches[0].currentStateSnapshotID,
            original.branches[0].currentStateSnapshotID
        )
    }

    func testDiscussionIsAllowedWhileNeedsSyncAndNeverCreatesCandidate() throws {
        var document = try NovelTestFixtures.document()
        document.branches[0].syncStatus = .needsSync
        try NovelDocumentValidator.validate(document)

        let request = makeRequest(document: document, kind: .discussion)
        let started = try begin(request, in: document)
        let completed = try NovelGenerationReducer.complete(
            runID: request.id,
            content: "We could reveal the secret one chapter later.",
            in: started,
            now: terminalTime
        )

        XCTAssertEqual(completed.message?.message.kind, .discussion)
        XCTAssertNil(completed.message?.message.candidateID)
        XCTAssertTrue(completed.document.candidates.isEmpty)
        XCTAssertEqual(completed.document.branches[0].syncStatus, .needsSync)
    }

    func testAskUserQuestionAndAnswerPersistAcrossDiscussionRuns() throws {
        let original = try NovelTestFixtures.document()
        let questionRun = makeRequest(document: original, kind: .discussion)
        let started = try begin(questionRun, in: original)
        let prompt = NovelAskUserPrompt(
            question: "朱重八和朱元璋是同一个角色吗？",
            options: ["是", "不是"]
        )

        let awaiting = try NovelGenerationReducer.completeAwaitingUser(
            runID: questionRun.id,
            prompt: prompt,
            preface: "先确认一个会影响人物经历归属的问题。",
            in: started,
            now: terminalTime
        ).document

        XCTAssertEqual(awaiting.sessions[0].messages.last?.interaction, .askUser(prompt))
        XCTAssertNil(awaiting.branches[0].activeRunID)
        XCTAssertEqual(awaiting.activeRuns.last?.status, .completed)

        let response = NovelAskUserResponse(
            promptMessageID: questionRun.assistantMessageID,
            answer: "是"
        )
        let answerRun = makeRequest(
            document: awaiting,
            kind: .discussion,
            askUserResponse: response
        )
        let resumed = try begin(answerRun, in: awaiting)

        XCTAssertEqual(resumed.sessions[0].messages.last?.interaction, .askUserAnswer(response))
        XCTAssertEqual(resumed.branches[0].activeRunID, answerRun.id)
    }

    func testPolishCompletionBindsCandidateToCurrentSourceVersion() throws {
        let fixture = try documentWithChapter()
        let request = makeRequest(
            document: fixture.document,
            kind: .polish,
            sourceChapterVersionID: fixture.versionID
        )
        let started = try begin(request, in: fixture.document)
        let completed = try NovelGenerationReducer.complete(
            runID: request.id,
            content: "Polished chapter text with the same facts.",
            in: started,
            now: terminalTime
        )

        XCTAssertEqual(completed.message?.message.kind, .polishCandidate)
        XCTAssertEqual(completed.document.candidates[0].kind, .polish)
        XCTAssertEqual(completed.document.candidates[0].sourceChapterVersionID, fixture.versionID)
        XCTAssertEqual(completed.document.chapterVersions, fixture.document.chapterVersions)
        XCTAssertEqual(completed.document.checkpoints, fixture.document.checkpoints)
    }

    func testQuickStartAtomicallyCreatesTypedProposalsWithoutMutatingMaterialsOrBranchFacts() throws {
        let document = try quickStartDocument()
        let request = makeRequest(document: document, kind: .quickStart)
        let started = try begin(request, in: document)
        let completed = try NovelGenerationReducer.complete(
            runID: request.id,
            content: quickStartSuggestionsJSON,
            in: started,
            now: terminalTime
        )

        XCTAssertEqual(completed.message?.message.kind, .discussion)
        XCTAssertTrue(completed.message?.message.content.contains("## 世界观" ) == true)
        XCTAssertTrue(completed.document.materials.isEmpty)
        XCTAssertTrue(completed.document.materialRevisions.isEmpty)
        XCTAssertTrue(completed.document.candidates.isEmpty)
        XCTAssertEqual(completed.document.settingProposals.count, 5)
        XCTAssertEqual(
            completed.document.settingProposals.compactMap { proposal -> NovelMaterialKind? in
                guard case .some(.quickStart(let runID, let kind)) = proposal.origin,
                      runID == request.id else {
                    return nil
                }
                return kind
            },
            [.world, .character, .character, .masterOutline, .writingRequirements]
        )
        XCTAssertEqual(
            completed.document.settingProposals.filter {
                $0.suggestedMaterialKind == .character
            }.map(\.title),
            ["Mara", "Ivo"]
        )
        XCTAssertEqual(completed.document.checkpoints, document.checkpoints)
        XCTAssertEqual(completed.document.stateSnapshots, document.stateSnapshots)
        XCTAssertEqual(
            completed.document.branches[0].headCheckpointID,
            document.branches[0].headCheckpointID
        )
        XCTAssertEqual(completed.document.branches[0].headRevision, document.branches[0].headRevision)

        var missingProposals = completed.document
        missingProposals.settingProposals.removeAll()
        XCTAssertThrowsError(try NovelDocumentValidator.validate(missingProposals)) { error in
            guard case .some(.invalidDocument(let issues)) = error as? NovelError else {
                return XCTFail("Expected invalid completed quick-start document.")
            }
            XCTAssertTrue(issues.contains(where: {
                $0.contains("does not own one fixed proposal per section")
            }))
        }
    }

    func testStaleRevisionsFailButNeedsSyncStillAllowsProse() throws {
        let document = try NovelTestFixtures.document()
        let stale = makeRequest(
            document: document,
            kind: .prose,
            granularity: .wholeChapter,
            expectedProjectRevision: document.project.revision - 1
        )
        let staleArtifacts = try makeArtifacts(document: document, request: stale)

        XCTAssertThrowsError(try NovelGenerationReducer.begin(
            stale,
            artifacts: staleArtifacts,
            in: document,
            now: startTime
        )) { error in
            XCTAssertEqual(
                error as? NovelError,
                .staleProjectRevision(
                    expected: document.project.revision - 1,
                    actual: document.project.revision
                )
            )
        }
        XCTAssertTrue(document.sessions[0].messages.isEmpty)
        XCTAssertTrue(document.activeRuns.isEmpty)
        XCTAssertTrue(document.injectionReceipts.isEmpty)

        var needsSync = document
        needsSync.branches[0].syncStatus = .needsSync
        let prose = makeRequest(
            document: needsSync,
            kind: .prose,
            granularity: .continuation
        )
        let started = try NovelGenerationReducer.begin(
            prose,
            artifacts: makeArtifacts(document: needsSync, request: prose),
            in: needsSync,
            now: startTime
        )
        XCTAssertTrue(needsSync.sessions[0].messages.isEmpty)
        XCTAssertTrue(needsSync.activeRuns.isEmpty)
        XCTAssertEqual(started.document.activeRuns.map(\.id), [prose.id])
        XCTAssertEqual(started.document.branches[0].syncStatus, .needsSync)
    }

    func testInterruptionPersistsOptionalDraftAndLateTerminalsAreNoOps() throws {
        let document = try NovelTestFixtures.document()
        let request = makeRequest(
            document: document,
            kind: .prose,
            granularity: .wholeChapter
        )
        let started = try begin(request, in: document)
        let interrupted = try NovelGenerationReducer.interrupt(
            runID: request.id,
            reason: .background,
            partialContent: "Durable partial draft",
            in: started,
            now: terminalTime
        )

        XCTAssertEqual(interrupted.document.project.revision, started.project.revision + 1)
        XCTAssertEqual(interrupted.document.activeRuns[0].status, .interrupted)
        XCTAssertEqual(interrupted.document.activeRuns[0].interruptionReason, .background)
        XCTAssertEqual(interrupted.message?.message.kind, .interruptedDraft)
        XCTAssertEqual(interrupted.message?.message.candidateID, request.candidateID)
        XCTAssertEqual(interrupted.document.candidates.first?.status, .interrupted)

        let lateComplete = try NovelGenerationReducer.complete(
            runID: request.id,
            content: "late complete",
            in: interrupted.document,
            now: terminalTime.addingTimeInterval(1)
        )
        let lateFailure = try NovelGenerationReducer.fail(
            runID: request.id,
            failure: NovelFailure(code: "late", message: "late", isRetryable: false),
            partialContent: "late failure",
            in: lateComplete.document,
            now: terminalTime.addingTimeInterval(2)
        )
        let duplicateInterrupt = try NovelGenerationReducer.interrupt(
            runID: request.id,
            reason: .expiration,
            partialContent: "late interrupt",
            in: lateFailure.document,
            now: terminalTime.addingTimeInterval(3)
        )

        XCTAssertNil(lateComplete.message)
        XCTAssertNil(lateFailure.message)
        XCTAssertNil(duplicateInterrupt.message)
        XCTAssertEqual(lateComplete.document, interrupted.document)
        XCTAssertEqual(lateFailure.document, interrupted.document)
        XCTAssertEqual(duplicateInterrupt.document, interrupted.document)
    }

    func testInterruptedProsePersistsCandidateFromTerminalPartialSnapshot() throws {
        let document = try NovelTestFixtures.document()
        let request = makeRequest(
            document: document,
            kind: .prose,
            granularity: .wholeChapter
        )
        let started = try begin(request, in: document)

        let interrupted = try NovelGenerationReducer.interrupt(
            runID: request.id,
            reason: .user,
            partialContent: "只收录这份终态快照",
            in: started,
            now: terminalTime
        )

        let candidate = try XCTUnwrap(interrupted.document.candidates.first)
        XCTAssertEqual(candidate.id, request.candidateID)
        XCTAssertEqual(candidate.kind, .prose)
        XCTAssertEqual(candidate.status, .interrupted)
        XCTAssertEqual(candidate.content, "只收录这份终态快照")
        XCTAssertEqual(interrupted.message?.message.candidateID, candidate.id)
        XCTAssertNoThrow(try NovelDocumentValidator.validate(interrupted.document))
    }

    func testFailureStoresFailureAndPartialWithoutCreatingCandidate() throws {
        let document = try NovelTestFixtures.document()
        let request = makeRequest(
            document: document,
            kind: .prose,
            granularity: .continuation
        )
        let started = try begin(request, in: document)
        let failure = NovelFailure(
            code: "provider_timeout",
            message: "The provider timed out.",
            isRetryable: true
        )
        let failed = try NovelGenerationReducer.fail(
            runID: request.id,
            failure: failure,
            partialContent: "A partial response",
            in: started,
            now: terminalTime
        )

        XCTAssertEqual(failed.document.activeRuns[0].status, .failed)
        XCTAssertEqual(failed.document.activeRuns[0].terminalFailure, failure)
        XCTAssertEqual(failed.document.activeRuns[0].partialContent, "A partial response")
        XCTAssertEqual(failed.message?.message.kind, .interruptedDraft)
        XCTAssertTrue(failed.document.candidates.isEmpty)
        XCTAssertNil(failed.document.branches[0].activeRunID)
    }

    func testCancelActionRecordsLedgerAndExactReplayDoesNotWriteAgain() throws {
        let document = try NovelTestFixtures.document()
        let request = makeRequest(document: document, kind: .discussion)
        let started = try begin(request, in: document)
        let command = NovelCancelRunCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: started.project.revision,
                expectedConfigRevision: started.project.configRevision,
                expectedBranchHeadRevision: started.branches[0].headRevision
            ),
            projectID: started.project.id,
            runID: request.id,
            reason: .user
        )

        let cancelled = try NovelGenerationReducer.interrupt(
            command,
            partialContent: "Keep this thought",
            in: started,
            now: terminalTime
        )

        XCTAssertEqual(cancelled.document.project.revision, started.project.revision + 1)
        XCTAssertEqual(cancelled.document.appliedOperations.last?.kind, .cancelRun)
        XCTAssertEqual(
            cancelled.outcome,
            .runInterrupted(
                projectID: started.project.id,
                runID: request.id,
                reason: .user,
                revision: started.project.revision + 1
            )
        )

        let replay = try NovelGenerationReducer.interrupt(
            command,
            partialContent: "different ignored late partial",
            in: cancelled.document,
            now: terminalTime.addingTimeInterval(1)
        )
        XCTAssertEqual(replay.document, cancelled.document)
        XCTAssertEqual(replay.outcome, cancelled.outcome)
        XCTAssertNil(replay.message)
    }

    func testEmptyCompletionThrowsWithoutClosingRun() throws {
        let document = try NovelTestFixtures.document()
        let request = makeRequest(document: document, kind: .discussion)
        let started = try begin(request, in: document)

        XCTAssertThrowsError(try NovelGenerationReducer.complete(
            runID: request.id,
            content: "  \n",
            in: started,
            now: terminalTime
        ))
        XCTAssertEqual(started.activeRuns[0].status, .running)
        XCTAssertEqual(started.branches[0].activeRunID, request.id)
        XCTAssertEqual(started.sessions[0].messages.count, 1)
    }

    func testExactCompletedTerminalReplayReturnsDurableMessageWithoutWriting() throws {
        let document = try NovelTestFixtures.document()
        let request = makeRequest(document: document, kind: .discussion)
        let started = try begin(request, in: document)
        let completed = try NovelGenerationReducer.complete(
            runID: request.id,
            content: "Durable answer",
            in: started,
            now: terminalTime
        )

        let replay = try NovelGenerationReducer.complete(
            runID: request.id,
            content: "Durable answer",
            in: completed.document,
            now: terminalTime.addingTimeInterval(1)
        )

        XCTAssertEqual(replay.document, completed.document)
        XCTAssertEqual(replay.message, completed.message)
    }

    func testCancellationIgnoresUnrelatedProjectAndConfigRevisionDrift() throws {
        let document = try NovelTestFixtures.document()
        let request = makeRequest(document: document, kind: .discussion)
        let started = try begin(request, in: document)
        let materialAction = NovelTestFixtures.materialAction(document: started)
        let changed = try NovelReducer.apply(materialAction, to: started).document
        let command = NovelCancelRunCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: started.project.revision,
                expectedConfigRevision: started.project.configRevision,
                expectedBranchHeadRevision: started.branches[0].headRevision
            ),
            projectID: changed.project.id,
            runID: request.id,
            reason: .user
        )

        let cancelled = try NovelGenerationReducer.interrupt(
            command,
            partialContent: "",
            in: changed,
            now: terminalTime
        )

        XCTAssertEqual(cancelled.document.activeRuns[0].status, .interrupted)
        XCTAssertEqual(cancelled.document.materials, changed.materials)
        XCTAssertNotNil(cancelled.outcome)
    }

    func testReceiptBuiltFromDifferentUserInputIsRejected() throws {
        let document = try NovelTestFixtures.document()
        let request = makeRequest(document: document, kind: .discussion)
        let forged = try makeArtifacts(
            document: document,
            request: request,
            planningUserText: "Different hidden request"
        )

        XCTAssertThrowsError(try NovelGenerationReducer.begin(
            request,
            artifacts: forged,
            in: document,
            now: startTime
        )) { error in
            guard let novelError = error as? NovelError,
                  case .invalidInput(let message) = novelError else {
                return XCTFail("Expected invalidInput, got \(error)")
            }
            XCTAssertTrue(message.contains("Prompt or user input"))
        }
    }

    func testValidatorRequiresExactFailedOutputForEmptyAndPartialFailures() throws {
        for partial in ["", "A recoverable partial"] {
            let original = try NovelTestFixtures.document()
            let request = makeRequest(
                document: original,
                kind: .prose,
                granularity: .continuation
            )
            let started = try begin(request, in: original)
            let failure = NovelFailure(
                code: "provider_failed",
                message: "Provider failed.",
                isRetryable: true
            )
            let valid = try NovelGenerationReducer.fail(
                runID: request.id,
                failure: failure,
                partialContent: partial,
                in: started,
                now: terminalTime
            ).document
            XCTAssertNoThrow(try NovelDocumentValidator.validate(valid))

            var missing = valid
            missing.sessions[0].messages.removeAll { $0.id == request.assistantMessageID }
            assertInvalid(missing, containing: "invalid terminal message")

            var forged = valid
            let messageIndex = try XCTUnwrap(forged.sessions[0].messages.firstIndex {
                $0.id == request.assistantMessageID
            })
            let message = forged.sessions[0].messages[messageIndex]
            forged.sessions[0].messages[messageIndex] = NovelSessionMessageRecord(
                id: message.id,
                sequence: message.sequence,
                role: message.role,
                mode: message.mode,
                kind: partial.isEmpty ? .interruptedDraft : .error,
                content: "forged content",
                createdAt: message.createdAt,
                runID: message.runID,
                candidateID: request.candidateID
            )
            assertInvalid(forged, containing: "invalid terminal message")
        }
    }

    func testValidatorRejectsCandidateAttachedToFailedRun() throws {
        let original = try NovelTestFixtures.document()
        let request = makeRequest(
            document: original,
            kind: .prose,
            granularity: .wholeChapter
        )
        let started = try begin(request, in: original)
        var failed = try NovelGenerationReducer.fail(
            runID: request.id,
            failure: NovelFailure(code: "failed", message: "Failed.", isRetryable: false),
            partialContent: "Partial",
            in: started,
            now: terminalTime
        ).document
        let run = try XCTUnwrap(failed.activeRuns.first)
        let candidateID = try XCTUnwrap(run.candidateID)
        failed.candidates.append(NovelCandidateRecord(
            id: candidateID,
            kind: .prose,
            branchID: run.branchID,
            sessionID: run.sessionID,
            sourceMessageID: run.messageID,
            baseCheckpointID: run.baseCheckpointID,
            baseHeadRevision: run.baseHeadRevision,
            status: .available,
            content: run.partialContent,
            sourceChapterVersionID: nil,
            collectedCheckpointID: nil,
            createdAt: terminalTime
        ))

        assertInvalid(failed, containing: "unexpectedly persisted a candidate")
    }

    func testValidatorRechecksPromptAndUserHashesAfterDiskDecode() throws {
        let original = try NovelTestFixtures.document()
        let request = makeRequest(document: original, kind: .discussion)
        let started = try begin(request, in: original)

        var changedUser = started
        let message = changedUser.sessions[0].messages[0]
        changedUser.sessions[0].messages[0] = NovelSessionMessageRecord(
            id: message.id,
            sequence: message.sequence,
            role: message.role,
            mode: message.mode,
            kind: message.kind,
            content: "Disk-edited request",
            createdAt: message.createdAt,
            runID: message.runID,
            candidateID: message.candidateID
        )
        assertInvalid(changedUser, containing: "user input receipt evidence")

        let changedPrompt = try mutateEncodedDocument(started) { object in
            var receipts = try XCTUnwrap(object["injectionReceipts"] as? [[String: Any]])
            var sections = try XCTUnwrap(receipts[0]["sections"] as? [[String: Any]])
            XCTAssertFalse(sections.isEmpty)
            sections[0]["contentSHA256"] = NovelTestFixtures.hashB
            receipts[0]["sections"] = sections
            object["injectionReceipts"] = receipts
        }
        assertInvalid(changedPrompt, containing: "fixed Prompt receipt evidence")
    }

    func testValidatorAcceptsHistoricalDiscussionPromptEvidence() throws {
        let original = try NovelTestFixtures.document()
        let request = makeRequest(document: original, kind: .discussion)
        let started = try begin(request, in: original)
        let version = "novel.discussion.v1"
        let historicalPrompt = try XCTUnwrap(NovelPromptCatalog.systemText(
            for: .discussion,
            version: version
        ))
        let historical = try mutateEncodedDocument(started) { object in
            var injections = try XCTUnwrap(object["injectionReceipts"] as? [[String: Any]])
            var sections = try XCTUnwrap(injections[0]["sections"] as? [[String: Any]])
            sections[0]["contentSHA256"] = NovelDocumentValidator.sha256(historicalPrompt)
            injections[0]["sections"] = sections
            injections[0]["promptVersion"] = version
            object["injectionReceipts"] = injections

            var generations = try XCTUnwrap(object["generationReceipts"] as? [[String: Any]])
            generations[0]["promptVersion"] = version
            object["generationReceipts"] = generations
        }

        XCTAssertNoThrow(try NovelDocumentValidator.validate(historical))
    }

    func testValidatorAcceptsHistoricalQuickStartPromptEvidence() throws {
        let original = try quickStartDocument()
        let request = makeRequest(document: original, kind: .quickStart)
        let started = try begin(request, in: original)
        let version = "novel.quick-start.v2"
        let historicalPrompt = try XCTUnwrap(NovelPromptCatalog.systemText(
            for: .quickStart,
            version: version
        ))
        let historical = try mutateEncodedDocument(started) { object in
            var injections = try XCTUnwrap(object["injectionReceipts"] as? [[String: Any]])
            var sections = try XCTUnwrap(injections[0]["sections"] as? [[String: Any]])
            sections[0]["contentSHA256"] = NovelDocumentValidator.sha256(historicalPrompt)
            injections[0]["sections"] = sections
            injections[0]["promptVersion"] = version
            object["injectionReceipts"] = injections

            var generations = try XCTUnwrap(object["generationReceipts"] as? [[String: Any]])
            generations[0]["promptVersion"] = version
            object["generationReceipts"] = generations
        }

        XCTAssertNoThrow(try NovelDocumentValidator.validate(historical))
    }

    func testValidatorRejectsRunPointingAtReceiptOwnedByAnotherRun() throws {
        let original = try NovelTestFixtures.document()
        let request = makeRequest(document: original, kind: .discussion)
        let started = try begin(request, in: original)
        let wrongReceiptID = NovelReceiptID()
        let wrongRunID = NovelRunID()
        let wrongReceiptJSON = try encodedJSONValue(wrongReceiptID)
        let wrongRunJSON = try encodedJSONValue(wrongRunID)

        let swapped = try mutateEncodedDocument(started) { object in
            var receipts = try XCTUnwrap(object["generationReceipts"] as? [[String: Any]])
            var wrong = receipts[0]
            wrong["id"] = wrongReceiptJSON
            wrong["runID"] = wrongRunJSON
            receipts.append(wrong)
            object["generationReceipts"] = receipts

            var runs = try XCTUnwrap(object["activeRuns"] as? [[String: Any]])
            runs[0]["receiptID"] = wrongReceiptJSON
            object["activeRuns"] = runs
        }
        assertInvalid(swapped, containing: "owned by another run")
    }
}

private extension NovelGenerationReducerTests {
    func assertInvalid(
        _ document: NovelProjectDocumentV1,
        containing expected: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertThrowsError(
            try NovelDocumentValidator.validate(document),
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

    func mutateEncodedDocument(
        _ document: NovelProjectDocumentV1,
        mutation: (inout [String: Any]) throws -> Void
    ) throws -> NovelProjectDocumentV1 {
        let encoded = try JSONEncoder().encode(document)
        var object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: encoded) as? [String: Any]
        )
        try mutation(&object)
        return try JSONDecoder().decode(
            NovelProjectDocumentV1.self,
            from: JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        )
    }

    func encodedJSONValue<Value: Encodable>(_ value: Value) throws -> Any {
        try JSONSerialization.jsonObject(
            with: JSONEncoder().encode(value),
            options: [.fragmentsAllowed]
        )
    }

    func begin(
        _ request: NovelRunRequest,
        in document: NovelProjectDocumentV1
    ) throws -> NovelProjectDocumentV1 {
        try NovelGenerationReducer.begin(
            request,
            artifacts: makeArtifacts(document: document, request: request),
            in: document,
            now: startTime
        ).document
    }

    func makeRequest(
        document: NovelProjectDocumentV1,
        kind: NovelRunKind,
        granularity: NovelGenerationGranularity? = nil,
        sourceChapterVersionID: NovelChapterVersionID? = nil,
        askUserResponse: NovelAskUserResponse? = nil,
        expectedProjectRevision: Int64? = nil
    ) -> NovelRunRequest {
        let branch = document.branches[0]
        let mode: NovelSessionMode = switch kind {
        case .quickStart, .discussion: .discussPlan
        case .prose, .polish: .writeProse
        }
        let candidateID: NovelCandidateID? = switch kind {
        case .prose, .polish: NovelCandidateID()
        case .quickStart, .discussion: nil
        }
        return NovelRunRequest(
            id: NovelRunID(),
            operationID: NovelOperationID(),
            projectID: document.project.id,
            branchID: branch.id,
            kind: kind,
            mode: mode,
            granularity: granularity,
            userText: "Help with the next beat.",
            userMessageID: NovelMessageID(),
            assistantMessageID: NovelMessageID(),
            candidateID: candidateID,
            generationReceiptID: NovelReceiptID(),
            injectionReceiptID: NovelReceiptID(),
            sourceChapterVersionID: sourceChapterVersionID,
            askUserResponse: askUserResponse,
            inputBudgetTokens: 16_000,
            expectedProjectRevision: expectedProjectRevision ?? document.project.revision,
            expectedConfigRevision: document.project.configRevision,
            expectedBranchHeadRevision: branch.headRevision
        )
    }

    func makeArtifacts(
        document: NovelProjectDocumentV1,
        request: NovelRunRequest,
        planningDocument: NovelProjectDocumentV1? = nil,
        planningUserText: String? = nil
    ) throws -> NovelGenerationStartArtifacts {
        let promptKind: NovelPromptKind = switch request.kind {
        case .quickStart:
            .quickStart
        case .discussion:
            .discussion
        case .prose:
            request.granularity == .continuation ? .proseContinuation : .proseWholeChapter
        case .polish:
            .wholeChapterPolish
        }
        let budget = NovelInjectionBudget(
            maxEstimatedInputTokens: request.inputBudgetTokens,
            chapterTailCharacterLimit: 6_000,
            maximumRecentSessionMessages: 12
        )
        let plan = try NovelInjectionPlanner.plan(
            document: planningDocument ?? document,
            request: NovelInjectionPlanningRequest(
                branchID: request.branchID,
                promptKind: promptKind,
                userText: planningUserText ?? request.userText,
                sourceChapterVersionID: request.sourceChapterVersionID,
                overrides: request.injectionOverrides,
                budget: budget
            )
        )
        let parameters = ["temperature": "0.8", "topP": "0.95"]
        let injection = NovelInjectionReceiptRecord(
            id: request.injectionReceiptID,
            runID: request.id,
            projectID: request.projectID,
            branchID: request.branchID,
            plan: plan,
            overrides: request.injectionOverrides,
            providerID: "provider-id",
            modelID: "model-id",
            parameters: parameters,
            createdAt: startTime
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
            createdAt: startTime
        )
        return NovelGenerationStartArtifacts(
            injectionReceipt: injection,
            generationReceipt: generation
        )
    }

    func quickStartDocument() throws -> NovelProjectDocumentV1 {
        let command = NovelCreateProjectCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: nil,
                expectedConfigRevision: nil,
                expectedBranchHeadRevision: nil
            ),
            projectID: NovelProjectID(),
            branchID: NovelBranchID(),
            sessionID: NovelSessionID(),
            initialStateSnapshotID: NovelStateSnapshotID(),
            initialCheckpointID: NovelCheckpointID(),
            name: "Quick Novel",
            branchName: "Main",
            creationMode: .quickStart,
            quickStartSeed: NovelQuickStartSeed(
                genre: "Mystery",
                coreIdea: "A memory can testify in court."
            )
        )
        return try NovelReducer.createProject(
            command,
            now: Date(timeIntervalSince1970: 1_700_000_000)
        ).document
    }

    var quickStartSuggestionsJSON: String {
        """
        {
          "schemaVersion": 2,
          "overview": "A courtroom mystery built around traded memories.",
          "world": {"title": "Memory law", "content": "A verified memory may testify once."},
          "characters": [
            {"title": "Mara", "content": "The advocate risks her last childhood memory."},
            {"title": "Ivo", "content": "The witness knows who forged the first memory."}
          ],
          "masterOutline": {"title": "The appeal", "content": "A false memory forces the case to reopen."},
          "writingRequirements": {"title": "Voice", "content": "Keep clues concrete and courtroom scenes brisk."}
        }
        """
    }

    func documentWithChapter() throws -> (
        document: NovelProjectDocumentV1,
        versionID: NovelChapterVersionID
    ) {
        var document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let chapterID = NovelChapterID()
        let versionID = NovelChapterVersionID()
        let operationID = document.appliedOperations[0].operationID
        let now = document.project.updatedAt
        document.chapters.append(NovelChapterRecord(id: chapterID, createdAt: now))
        document.chapterVersions.append(NovelChapterVersionRecord(
            id: versionID,
            chapterID: chapterID,
            kind: .collected,
            title: "Chapter One",
            content: "The witness entered the silent courtroom.",
            factCompatibilityID: UUID(),
            sourceCandidateID: nil,
            createdAt: now,
            operationID: operationID
        ))

        let checkpointID = document.branches[0].headCheckpointID
        let checkpointIndex = try XCTUnwrap(document.checkpoints.firstIndex(where: {
            $0.id == checkpointID
        }))
        let checkpoint = document.checkpoints[checkpointIndex]
        let selection = NovelChapterSelection(chapterID: chapterID, versionID: versionID)
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
        return (document, versionID)
    }
}
