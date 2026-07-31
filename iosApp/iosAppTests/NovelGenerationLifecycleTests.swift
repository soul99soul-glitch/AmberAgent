import XCTest
@testable import iosApp

final class NovelGenerationLifecycleTests: XCTestCase {
    func testDiscussionDoesNotImposeAnOutputLimit() async throws {
        let document = try NovelTestFixtures.document()
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.delta("Detailed discussion."), .complete])]
        )

        _ = await capturedEvents(try await harness.creation.start(
            makeRequest(document: document, kind: .discussion)
        ).events)

        let requests = await harness.adapter.requests
        let request = try XCTUnwrap(requests.first)
        XCTAssertNil(request.parameters.maxOutputTokens)
    }

    func testGenerationUsesCreativeModelInsteadOfStateSyncModel() async throws {
        var document = try NovelTestFixtures.document()
        document.project.modelPolicy = .fixed(providerID: "creative-provider", modelID: "creative-model")
        document.project.stateSyncModelPolicy = .fixed(providerID: "sync-provider", modelID: "sync-model")
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.delta("A scene."), .complete])]
        )

        _ = await capturedEvents(try await harness.creation.start(
            makeRequest(document: document, kind: .prose, granularity: .continuation)
        ).events)

        let policies = await harness.adapter.resolvedPolicies
        XCTAssertEqual(policies.first, .fixed(providerID: "creative-provider", modelID: "creative-model"))
        XCTAssertFalse(policies.contains(.fixed(providerID: "sync-provider", modelID: "sync-model")))
    }

    func testMarkerUserMessageAndReceiptsAreDurableBeforeProviderResume() async throws {
        let document = try NovelTestFixtures.document()
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.pause, .delta("Planned beat"), .complete])]
        )
        let request = makeRequest(document: document, kind: .discussion)

        let run = try await harness.creation.start(request)
        let persisted = try await harness.repository.document(request.projectID)

        XCTAssertEqual(persisted.project.revision, document.project.revision + 1)
        XCTAssertEqual(persisted.branches[0].activeRunID, request.id)
        XCTAssertEqual(persisted.activeRuns.first?.status, .running)
        XCTAssertEqual(persisted.sessions[0].messages.map(\.id), [request.userMessageID])
        XCTAssertEqual(persisted.sessions[0].messages.first?.kind, .userInput)
        XCTAssertEqual(persisted.injectionReceipts.map(\.id), [request.injectionReceiptID])
        XCTAssertEqual(persisted.generationReceipts.map(\.id), [request.generationReceiptID])
        XCTAssertEqual(persisted.appliedOperations.last?.kind, .startRun)
        XCTAssertTrue(persisted.candidates.isEmpty)

        let requests = await harness.adapter.requests
        XCTAssertEqual(requests.count, 1)
        XCTAssertEqual(requests.first?.runID, request.id)
        XCTAssertEqual(requests.first?.model.providerID, persisted.generationReceipts[0].providerID)
        XCTAssertEqual(
            requests.first?.model.ownerProviderID,
            persisted.generationReceipts[0].ownerProviderID
        )
        XCTAssertEqual(requests.first?.model.modelID, persisted.generationReceipts[0].modelID)
        XCTAssertEqual(requests.first?.model.wireModelID, persisted.generationReceipts[0].wireModelID)
        let commits = await harness.repository.commitHistory()
        XCTAssertEqual(commits.first?.activeRuns.first?.status, .running)

        await harness.adapter.resume(runID: request.id)
        let events = await capturedEvents(run.events)
        XCTAssertEqual(events.first, .started(request.id))
        XCTAssertEqual(events.dropFirst().first, .delta("Planned beat"))
        guard case .completed(let snapshot) = events.last else {
            return XCTFail("Expected a completed discussion")
        }
        XCTAssertEqual(snapshot.message.kind, .discussion)
    }

    func testAskUserEndsTheRunAndPersistsAnAnswerableMessage() async throws {
        let document = try NovelTestFixtures.document()
        let prompt = NovelAskUserPrompt(
            question: "他此刻更害怕失去谁？",
            options: ["家人", "同伴"]
        )
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [
                .askUser(prompt, preface: "这个选择会改变下一步建议。")
            ])]
        )
        let request = makeRequest(document: document, kind: .discussion)

        let events = await capturedEvents(try await harness.creation.start(request).events)
        guard case .completed(let snapshot) = events.last else {
            return XCTFail("Expected Ask User to persist as a completed discussion turn.")
        }
        let persisted = try await harness.repository.document(request.projectID)

        XCTAssertEqual(snapshot.message.interaction, .askUser(prompt))
        XCTAssertEqual(snapshot.message.content, "这个选择会改变下一步建议。")
        XCTAssertNil(persisted.branches[0].activeRunID)
        XCTAssertEqual(persisted.activeRuns.last?.status, .completed)
    }

    func testAskUserFallbackCompletesAsAnAnswerableMessage() async throws {
        let prompt = NovelAskUserPrompt(question: "选择哪条线？", options: ["主线", "支线"])
        let document = try NovelTestFixtures.document()
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [
                .replacement(#"{"amberAskUser":{"question":"选择哪条线？","options":["主线","支线"]}}"#),
                .complete,
            ])]
        )

        let events = await capturedEvents(try await harness.creation.start(
            makeRequest(document: document, kind: .discussion)
        ).events)
        guard case .completed(let snapshot) = events.last else {
            return XCTFail("Expected fallback Ask User to complete the discussion turn.")
        }
        XCTAssertEqual(snapshot.message.interaction, .askUser(prompt))
    }

    func testDiscussionProseGranularitiesAndPolishCompleteWithoutChangingCanonicalState() async throws {
        let cases: [CompletionCase] = [
            CompletionCase(kind: .discussion, granularity: nil, content: "Delay the reveal.", expectedMessage: .discussion),
            CompletionCase(kind: .prose, granularity: .continuation, content: "A short continuation.", expectedMessage: .proseCandidate),
            CompletionCase(kind: .prose, granularity: .wholeChapter, content: "A complete next chapter.", expectedMessage: .proseCandidate),
            CompletionCase(
                kind: .polish,
                granularity: nil,
                content: "The polished chapter.\n\(NovelPromptCatalog.polishCompletionSentinel)",
                expectedMessage: .polishCandidate
            )
        ]

        for testCase in cases {
            let fixture: (NovelProjectDocumentV1, NovelChapterVersionID?)
            if testCase.kind == .polish {
                fixture = try documentWithChapter()
            } else {
                fixture = (try NovelTestFixtures.document(), nil)
            }
            let baseline = fixture.0
            let harness = try await makeHarness(
                document: baseline,
                scripts: [NovelModelScript(steps: [.delta(testCase.content), .complete])]
            )
            let request = makeRequest(
                document: baseline,
                kind: testCase.kind,
                granularity: testCase.granularity,
                sourceChapterVersionID: fixture.1
            )

            let events = await capturedEvents(try await harness.creation.start(request).events)
            guard case .completed(let snapshot) = events.last else {
                XCTFail("Expected completion for \(testCase.kind)")
                continue
            }
            XCTAssertEqual(snapshot.message.kind, testCase.expectedMessage)
            XCTAssertEqual(
                snapshot.message.content,
                testCase.kind == .polish ? "The polished chapter." : testCase.content
            )

            let final = try await harness.repository.document(request.projectID)
            XCTAssertEqual(final.chapters, baseline.chapters)
            XCTAssertEqual(final.chapterVersions, baseline.chapterVersions)
            XCTAssertEqual(final.events, baseline.events)
            XCTAssertEqual(final.stateSnapshots, baseline.stateSnapshots)
            XCTAssertEqual(final.checkpoints, baseline.checkpoints)
            XCTAssertEqual(final.branches[0].headRevision, baseline.branches[0].headRevision)
            if testCase.kind == .discussion {
                XCTAssertTrue(final.candidates.isEmpty)
                XCTAssertNil(snapshot.message.candidateID)
            } else {
                XCTAssertEqual(final.candidates.count, 1)
                XCTAssertEqual(final.candidates[0].id, request.candidateID)
                XCTAssertEqual(final.candidates[0].status, .available)
                XCTAssertEqual(final.candidates[0].sourceChapterVersionID, fixture.1)
            }
        }
    }

    func testEmptyProviderCompletionClosesAsFailureInsteadOfBlockingPersistence() async throws {
        let document = try NovelTestFixtures.document()
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.complete])]
        )
        let request = makeRequest(document: document, kind: .discussion)

        let events = await capturedEvents(try await harness.creation.start(request).events)
        let failure = NovelFailure(
            code: "empty_completion",
            message: "The model completed without returning any text.",
            isRetryable: true
        )
        XCTAssertEqual(events.last, .failed(failure))

        let final = try await harness.repository.document(request.projectID)
        XCTAssertEqual(final.activeRuns.first?.status, .failed)
        XCTAssertEqual(final.activeRuns.first?.terminalFailure, failure)
        XCTAssertNil(final.branches.first?.activeRunID)
        XCTAssertTrue(final.candidates.isEmpty)
    }

    func testInvalidQuickStartJSONFailsBeforeAnyProposalIsCommitted() async throws {
        let document = try quickStartDocument()
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.delta("{not-json"), .complete])]
        )
        let request = makeRequest(document: document, kind: .quickStart)

        let events = await capturedEvents(try await harness.creation.start(request).events)
        let failure = NovelFailure(
            code: "invalid_quick_start_output",
            message: "The quick-start suggestions were not valid structured output.",
            isRetryable: true
        )
        XCTAssertEqual(events.last, .failed(failure))
        let final = try await harness.repository.document(request.projectID)
        XCTAssertTrue(final.settingProposals.isEmpty)
        XCTAssertTrue(final.materials.isEmpty)
        XCTAssertEqual(final.activeRuns.first?.status, .failed)
        XCTAssertEqual(final.activeRuns.first?.terminalFailure, failure)
    }

    func testCancelPersistsOptionalPartialAndForcedSidecarWithoutWaitingForProvider() async throws {
        for partial in ["", "Keep this partial"] {
            let document = try NovelTestFixtures.document()
            let steps: [NovelModelScriptStep] = partial.isEmpty
                ? [.pause]
                : [.delta(partial), .pause]
            let policy = NovelGenerationPolicy(
                sidecarByteThreshold: 1_000_000,
                sidecarInterval: 10_000,
                chapterTailCharacterLimit: 6_000,
                maximumRecentSessionMessages: 12
            )
            let harness = try await makeHarness(
                document: document,
                scripts: [NovelModelScript(steps: steps)],
                policy: policy
            )
            let request = makeRequest(document: document, kind: .prose, granularity: .continuation)
            let run = try await harness.creation.start(request)
            var iterator = run.events.makeAsyncIterator()
            guard case .started? = await iterator.next() else {
                return XCTFail("Expected start event")
            }
            if !partial.isEmpty {
                guard case .delta(partial)? = await iterator.next() else {
                    return XCTFail("Expected partial delta")
                }
            }

            let running = try await harness.repository.document(request.projectID)
            let outcome = try await harness.creation.perform(.cancelRun(cancelCommand(
                document: running,
                runID: request.id
            )))
            guard case .runInterrupted(_, let runID, .user, _) = outcome else {
                return XCTFail("Expected interruption outcome")
            }
            XCTAssertEqual(runID, request.id)
            guard case .interrupted(let message)? = await iterator.next() else {
                return XCTFail("Expected interrupted event")
            }
            XCTAssertEqual(message?.message.content, partial.isEmpty ? nil : partial)
            let streamEnd = await iterator.next()
            XCTAssertNil(streamEnd)

            let final = try await harness.repository.document(request.projectID)
            XCTAssertEqual(final.activeRuns[0].status, .interrupted)
            XCTAssertEqual(final.activeRuns[0].partialContent, partial)
            XCTAssertEqual(final.sessions[0].messages.count, partial.isEmpty ? 1 : 2)
            XCTAssertEqual(final.candidates.count, partial.isEmpty ? 0 : 1)
            XCTAssertEqual(final.candidates.first?.status, partial.isEmpty ? nil : .interrupted)
            XCTAssertNil(final.branches[0].activeRunID)

            let wroteSidecar = await eventually {
                let writes = await harness.repository.sidecarWrites()
                return writes.contains { $0.runID == request.id }
            }
            XCTAssertTrue(wroteSidecar)
            let removedSidecar = await eventually {
                let removals = await harness.repository.sidecarRemovals()
                return removals.contains(request.id)
            }
            XCTAssertTrue(removedSidecar)
            let cancelledProvider = await eventually {
                let cancelledRunIDs = await harness.adapter.cancelledRunIDs
                return cancelledRunIDs.contains(request.id)
            }
            XCTAssertTrue(cancelledProvider)
        }
    }

    func testInterruptClaimsRuntimeBeforeCancellingSuspendedModelStart() async throws {
        let document = try NovelTestFixtures.document()
        let repository = ObservingNovelRepository()
        try await repository.seed(document)
        let adapter = CancellableStartNovelModelAdapter()
        let creation = DefaultNovelCreation(
            repository: repository,
            modelRunner: adapter,
            now: { Date(timeIntervalSince1970: 1_700_001_000) }
        )
        let request = makeRequest(document: document, kind: .discussion)
        let startTask = Task { try await creation.start(request) }
        let enteredModelStart = await eventually { await adapter.startDidBegin() }
        XCTAssertTrue(enteredModelStart)

        try await creation.interruptRun(NovelCancelRunCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: nil,
                expectedConfigRevision: nil,
                expectedBranchHeadRevision: nil
            ),
            projectID: request.projectID,
            runID: request.id,
            reason: .routeExit
        ))
        _ = try? await startTask.value

        let final = try await repository.document(request.projectID)
        let terminal = try XCTUnwrap(final.activeRuns.first { $0.id == request.id })
        XCTAssertEqual(terminal.status, .interrupted)
        XCTAssertEqual(terminal.interruptionReason, .routeExit)
        XCTAssertNil(final.branches[0].activeRunID)
        let providerCancelled = await eventually {
            await adapter.cancelledRunIDs().contains(request.id)
        }
        XCTAssertTrue(providerCancelled)
    }

    func testInterruptDoesNotAwaitAnUncooperativeModelStart() async throws {
        let document = try NovelTestFixtures.document()
        let repository = ObservingNovelRepository()
        try await repository.seed(document)
        let adapter = BlockingStartNovelModelAdapter()
        let creation = DefaultNovelCreation(
            repository: repository,
            modelRunner: adapter,
            now: { Date(timeIntervalSince1970: 1_700_001_000) }
        )
        let request = makeRequest(document: document, kind: .discussion)
        let startTask = Task { try await creation.start(request) }
        let enteredModelStart = await eventually { await adapter.startDidBegin() }
        XCTAssertTrue(enteredModelStart)
        let safetyRelease = Task {
            try? await Task.sleep(for: .milliseconds(500))
            await adapter.releaseStart()
        }

        let interruptStartedAt = Date()
        try await creation.interruptRun(NovelCancelRunCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: nil,
                expectedConfigRevision: nil,
                expectedBranchHeadRevision: nil
            ),
            projectID: request.projectID,
            runID: request.id,
            reason: .routeExit
        ))
        XCTAssertLessThan(Date().timeIntervalSince(interruptStartedAt), 0.25)
        safetyRelease.cancel()
        await adapter.releaseStart()
        _ = try? await startTask.value

        let final = try await repository.document(request.projectID)
        XCTAssertEqual(final.activeRuns.first { $0.id == request.id }?.status, .interrupted)
        XCTAssertEqual(
            final.activeRuns.first { $0.id == request.id }?.interruptionReason,
            .routeExit
        )
    }

    func testInterruptBeforeStartPreventsTheLateRunFromBecomingDurable() async throws {
        let document = try NovelTestFixtures.document()
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.pause])]
        )
        let request = makeRequest(document: document, kind: .discussion)

        try await harness.creation.interruptRun(NovelCancelRunCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: nil,
                expectedConfigRevision: nil,
                expectedBranchHeadRevision: nil
            ),
            projectID: request.projectID,
            runID: request.id,
            reason: .routeExit
        ))
        do {
            _ = try await harness.creation.start(request)
            XCTFail("A pre-start interruption must cancel the late start.")
        } catch is CancellationError {
            // Expected.
        }

        let final = try await harness.repository.document(request.projectID)
        let requests = await harness.adapter.requests
        XCTAssertEqual(final, document)
        XCTAssertTrue(requests.isEmpty)
    }

    func testInterruptPublishesPreStartCancellationBeforeRepositoryLoadResumes() async throws {
        let document = try NovelTestFixtures.document()
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.pause])]
        )
        let request = makeRequest(document: document, kind: .discussion)
        await harness.repository.blockNextLoad()

        let interruptTask = Task {
            try await harness.creation.interruptRun(NovelCancelRunCommand(
                context: NovelMutationContext(
                    operationID: NovelOperationID(),
                    expectedProjectRevision: nil,
                    expectedConfigRevision: nil,
                    expectedBranchHeadRevision: nil
                ),
                projectID: request.projectID,
                runID: request.id,
                reason: .routeExit
            ))
        }
        let loadBlocked = await eventually { await harness.repository.loadIsBlocked() }
        XCTAssertTrue(loadBlocked)

        do {
            _ = try await harness.creation.start(request)
            XCTFail("The late start must observe cancellation while interrupt is loading state.")
        } catch is CancellationError {
            // Expected.
        }
        await harness.repository.resumeBlockedLoad()
        try await interruptTask.value

        let final = try await harness.repository.document(request.projectID)
        let requests = await harness.adapter.requests
        XCTAssertEqual(final, document)
        XCTAssertTrue(requests.isEmpty)
    }

    func testBackgroundBeforeStartPreventsTheLateRunFromBecomingDurable() async throws {
        let document = try NovelTestFixtures.document()
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.pause])]
        )
        let request = makeRequest(document: document, kind: .discussion)

        await harness.creation.interruptForBackground(
            projectID: request.projectID,
            deadline: Date().addingTimeInterval(5),
            runID: request.id
        )
        do {
            _ = try await harness.creation.start(request)
            XCTFail("A pre-start background interruption must cancel the late start.")
        } catch is CancellationError {
            // Expected.
        }

        let final = try await harness.repository.document(request.projectID)
        let requests = await harness.adapter.requests
        XCTAssertEqual(final, document)
        XCTAssertTrue(requests.isEmpty)
    }

    func testIgnoreCancelDuplicateAndLateProviderCallbacksCannotRewriteFirstTerminal() async throws {
        let document = try NovelTestFixtures.document()
        let lateFailure = NovelModelFailure(code: "late", message: "late", isRetryable: false)
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(
                steps: [.delta("Durable partial"), .pause, .delta(" late"), .complete, .complete, .fail(lateFailure)],
                ignoresCancellation: true
            )]
        )
        let request = makeRequest(document: document, kind: .prose, granularity: .wholeChapter)
        let run = try await harness.creation.start(request)
        var iterator = run.events.makeAsyncIterator()
        _ = await iterator.next()
        _ = await iterator.next()

        let running = try await harness.repository.document(request.projectID)
        _ = try await harness.creation.perform(.cancelRun(cancelCommand(document: running, runID: request.id)))
        guard case .interrupted(let snapshot)? = await iterator.next() else {
            return XCTFail("Expected interruption")
        }
        XCTAssertEqual(snapshot?.message.content, "Durable partial")
        let firstTerminal = try await harness.repository.document(request.projectID)

        await harness.adapter.resume(runID: request.id)
        try? await Task.sleep(nanoseconds: 40_000_000)
        let afterLateCallbacks = try await harness.repository.document(request.projectID)
        XCTAssertEqual(afterLateCallbacks, firstTerminal)
        XCTAssertEqual(afterLateCallbacks.activeRuns[0].status, .interrupted)
        XCTAssertEqual(afterLateCallbacks.candidates.first?.status, .interrupted)
        XCTAssertEqual(afterLateCallbacks.sessions[0].messages.filter { $0.role == .assistant }.count, 1)
    }

    func testRestartRecoversLatestThresholdSidecarExactlyOnce() async throws {
        let document = try NovelTestFixtures.document()
        let policy = NovelGenerationPolicy(
            sidecarByteThreshold: 1,
            sidecarInterval: 10_000,
            chapterTailCharacterLimit: 6_000,
            maximumRecentSessionMessages: 12
        )
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(
                steps: [.delta("Recover this draft"), .pause, .complete],
                ignoresCancellation: true
            )],
            policy: policy
        )
        let request = makeRequest(document: document, kind: .prose, granularity: .wholeChapter)
        let run = try await harness.creation.start(request)
        var iterator = run.events.makeAsyncIterator()
        _ = await iterator.next()
        _ = await iterator.next()

        let wroteRecoverySidecar = await eventually {
            let writes = await harness.repository.sidecarWrites()
            return writes.contains {
                $0.runID == request.id && $0.partialContent == "Recover this draft"
            }
        }
        XCTAssertTrue(wroteRecoverySidecar)

        let restarted = DefaultNovelCreation(repository: harness.repository)
        _ = try await restarted.snapshot(.project(request.projectID))
        let recovered = try await harness.repository.document(request.projectID)
        XCTAssertEqual(recovered.activeRuns[0].status, .interrupted)
        XCTAssertEqual(recovered.activeRuns[0].interruptionReason, .recovery)
        XCTAssertEqual(recovered.activeRuns[0].partialContent, "Recover this draft")
        XCTAssertEqual(recovered.sessions[0].messages.last?.kind, .interruptedDraft)
        XCTAssertEqual(recovered.sessions[0].messages.last?.content, "Recover this draft")
        XCTAssertEqual(recovered.candidates.first?.status, .interrupted)
        let remainingSidecars = try await harness.repository.listRecoverySidecars()
        XCTAssertTrue(remainingSidecars.isEmpty)

        _ = try await restarted.snapshot(.project(request.projectID))
        let documentAfterSecondSnapshot = try await harness.repository.document(request.projectID)
        XCTAssertEqual(documentAfterSecondSnapshot, recovered)
        await harness.adapter.resume(runID: request.id)
    }

    func testMaterialRevisionWhileProviderAwaitsIsPreservedWithCandidateTerminal() async throws {
        let document = try NovelTestFixtures.document()
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.pause, .delta("Candidate after config edit"), .complete])]
        )
        let request = makeRequest(document: document, kind: .prose, granularity: .continuation)
        let run = try await harness.creation.start(request)
        let afterMarker = try await harness.repository.document(request.projectID)
        let materialID = NovelMaterialID()
        let revisionID = NovelMaterialRevisionID()

        _ = try await harness.creation.perform(NovelTestFixtures.materialAction(
            document: afterMarker,
            materialID: materialID,
            revisionID: revisionID,
            content: "The river remembers every name."
        ))
        await harness.adapter.resume(runID: request.id)
        let events = await capturedEvents(run.events)
        guard case .completed(let snapshot) = events.last else {
            return XCTFail("Expected candidate completion")
        }
        XCTAssertEqual(snapshot.message.candidateID, request.candidateID)

        let final = try await harness.repository.document(request.projectID)
        XCTAssertEqual(final.materials.first(where: { $0.id == materialID })?.currentRevisionID, revisionID)
        XCTAssertEqual(final.materialRevisions.first(where: { $0.id == revisionID })?.content, "The river remembers every name.")
        XCTAssertEqual(final.candidates.first(where: { $0.id == request.candidateID })?.content, "Candidate after config edit")
        XCTAssertEqual(final.activeRuns.first(where: { $0.id == request.id })?.status, .completed)
        XCTAssertEqual(final.checkpoints, document.checkpoints)
        XCTAssertEqual(final.stateSnapshots, document.stateSnapshots)
    }

    func testBackgroundDeadlineClaimsOnceAndDropsLateCallbacks() async throws {
        let cases: [(TimeInterval, NovelRunInterruptionReason)] = [
            (5, .background),
            (-1, .expiration)
        ]
        for (deadlineOffset, expectedReason) in cases {
            let document = try NovelTestFixtures.document()
            let harness = try await makeHarness(
                document: document,
                scripts: [NovelModelScript(
                    steps: [.delta("Kept"), .pause, .delta(" late"), .complete],
                    ignoresCancellation: true
                )]
            )
            let request = makeRequest(
                document: document,
                kind: .prose,
                granularity: .continuation
            )
            let run = try await harness.creation.start(request)
            var iterator = run.events.makeAsyncIterator()
            _ = await iterator.next()
            _ = await iterator.next()

            await harness.creation.interruptForBackground(
                projectID: request.projectID,
                deadline: Date(timeIntervalSince1970: 1_700_001_000 + deadlineOffset)
            )
            guard case .interrupted(let snapshot)? = await iterator.next() else {
                return XCTFail("Expected a background interruption")
            }
            XCTAssertEqual(snapshot?.message.content, "Kept")
            let firstTerminal = try await harness.repository.document(request.projectID)
            XCTAssertEqual(firstTerminal.activeRuns[0].interruptionReason, expectedReason)

            await harness.adapter.resume(runID: request.id)
            try? await Task.sleep(nanoseconds: 30_000_000)
            let afterLateCallbacks = try await harness.repository.document(request.projectID)
            XCTAssertEqual(afterLateCallbacks, firstTerminal)
            XCTAssertEqual(afterLateCallbacks.activeRuns[0].partialContent, "Kept")
        }
    }

    func testTerminalPersistenceFailureIsObservableAndRetryable() async throws {
        let document = try NovelTestFixtures.document()
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.delta("Retry this"), .pause, .complete])]
        )
        let request = makeRequest(document: document, kind: .discussion)
        let run = try await harness.creation.start(request)
        var iterator = run.events.makeAsyncIterator()
        _ = await iterator.next()
        _ = await iterator.next()

        await harness.repository.failNextCommits(3)
        await harness.adapter.resume(runID: request.id)
        guard case .persistenceBlocked(let failure)? = await iterator.next() else {
            return XCTFail("Expected an observable persistence failure")
        }
        XCTAssertEqual(failure.code, "terminal_persist_failed")
        let stillRunning = try await harness.repository.document(request.projectID)
        XCTAssertEqual(stillRunning.activeRuns[0].status, .running)

        let reattached = try await harness.creation.start(request)
        var reattachedIterator = reattached.events.makeAsyncIterator()
        guard case .started? = await reattachedIterator.next(),
              case .replaced("Retry this")? = await reattachedIterator.next(),
              case .persistenceBlocked(let replayedFailure)? = await reattachedIterator.next() else {
            return XCTFail("Expected blocked persistence to replay on reattach")
        }
        XCTAssertEqual(replayedFailure, failure)

        try await harness.creation.retryPendingTerminal(runID: request.id)
        guard case .completed(let snapshot)? = await iterator.next() else {
            return XCTFail("Expected retry to publish the durable terminal")
        }
        XCTAssertEqual(snapshot.message.content, "Retry this")
        guard case .completed(let reattachedSnapshot)? = await reattachedIterator.next() else {
            return XCTFail("Expected retry to finish the reattached stream")
        }
        XCTAssertEqual(reattachedSnapshot, snapshot)
    }

    func testProjectListDefersCrashRecoveryUntilProjectSnapshot() async throws {
        let document = try NovelTestFixtures.document()
        let seedHarness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.pause])]
        )
        let request = makeRequest(document: document, kind: .discussion)
        let seedRun = try await seedHarness.creation.start(request)
        let runningDocument = try await seedHarness.repository.document(request.projectID)

        let repository = ObservingNovelRepository()
        try await repository.seed(runningDocument)
        let restarted = DefaultNovelCreation(repository: repository)

        guard case .projects(let summaries) = try await restarted.snapshot(.projects) else {
            return XCTFail("Expected a project list snapshot")
        }
        XCTAssertEqual(summaries.map(\.id), [request.projectID])
        let afterList = try await repository.document(request.projectID)
        XCTAssertEqual(afterList.activeRuns.first?.status, .running)

        _ = try await restarted.snapshot(.project(request.projectID))
        let afterOpen = try await repository.document(request.projectID)
        XCTAssertEqual(afterOpen.activeRuns.first?.status, .interrupted)
        XCTAssertEqual(afterOpen.activeRuns.first?.interruptionReason, .recovery)

        await seedHarness.adapter.resume(runID: request.id)
        _ = await capturedEvents(seedRun.events)
    }

    func testRecoveryRejectsMismatchedSidecarIdentity() async throws {
        let document = try NovelTestFixtures.document()
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.pause])]
        )
        let request = makeRequest(document: document, kind: .discussion)
        _ = try await harness.creation.start(request)
        let running = try await harness.repository.document(request.projectID)
        let run = try XCTUnwrap(running.activeRuns.first(where: { $0.id == request.id }))
        let untrusted = "Content from another run"
        try await harness.repository.writeRecoverySidecar(NovelRecoverySidecarV1(
            schemaVersion: NovelRecoverySidecarV1.currentSchemaVersion,
            projectID: request.projectID,
            runID: request.id,
            branchID: run.branchID,
            sessionID: run.sessionID,
            messageID: NovelMessageID(),
            baseProjectRevision: running.project.revision,
            sequence: 1,
            partialContent: untrusted,
            partialSHA256: NovelDocumentValidator.sha256(untrusted),
            updatedAt: Date(timeIntervalSince1970: 1_700_001_001)
        ))

        let restarted = DefaultNovelCreation(repository: harness.repository)
        _ = try await restarted.snapshot(.project(request.projectID))
        let recovered = try await harness.repository.document(request.projectID)
        XCTAssertEqual(recovered.activeRuns[0].status, .interrupted)
        XCTAssertEqual(recovered.activeRuns[0].interruptionReason, .recovery)
        XCTAssertEqual(recovered.activeRuns[0].partialContent, "")
        XCTAssertFalse(recovered.sessions[0].messages.contains {
            $0.content == untrusted
        })
    }

    func testRecoveryFailureLeavesRecoveryRetryable() async throws {
        let document = try NovelTestFixtures.document()
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.pause])]
        )
        let request = makeRequest(document: document, kind: .discussion)
        _ = try await harness.creation.start(request)
        await harness.repository.failNextCommits(3)

        let restarted = DefaultNovelCreation(repository: harness.repository)
        do {
            _ = try await restarted.snapshot(.project(request.projectID))
            XCTFail("Expected the first recovery attempt to fail")
        } catch let error as NovelError {
            guard case .repositoryFailure = error else {
                return XCTFail("Unexpected recovery error: \(error)")
            }
        }
        let stillRunning = try await harness.repository.document(request.projectID)
        XCTAssertEqual(stillRunning.activeRuns[0].status, .running)

        _ = try await restarted.snapshot(.project(request.projectID))
        let recovered = try await harness.repository.document(request.projectID)
        XCTAssertEqual(recovered.activeRuns[0].status, .interrupted)
        XCTAssertEqual(recovered.activeRuns[0].interruptionReason, .recovery)
    }

    func testRecoveryFailureInOneProjectDoesNotBlockAnotherProjectSnapshot() async throws {
        let firstProjectID = NovelProjectID(UUID(uuidString: "00000000-0000-0000-0000-000000000001")!)
        let secondProjectID = NovelProjectID(UUID(uuidString: "00000000-0000-0000-0000-000000000002")!)
        let firstDocument = try NovelTestFixtures.document(projectID: firstProjectID)
        let secondDocument = try NovelTestFixtures.document(projectID: secondProjectID)
        let firstHarness = try await makeHarness(
            document: firstDocument,
            scripts: [NovelModelScript(steps: [.pause])]
        )
        let secondHarness = try await makeHarness(
            document: secondDocument,
            scripts: [NovelModelScript(steps: [.pause])]
        )
        let firstRequest = makeRequest(document: firstDocument, kind: .discussion)
        let secondRequest = makeRequest(document: secondDocument, kind: .discussion)
        let firstRun = try await firstHarness.creation.start(firstRequest)
        let secondRun = try await secondHarness.creation.start(secondRequest)

        let repository = ObservingNovelRepository()
        try await repository.seed(firstHarness.repository.document(firstProjectID))
        try await repository.seed(secondHarness.repository.document(secondProjectID))
        await repository.failCommits(for: firstProjectID)
        let restarted = DefaultNovelCreation(repository: repository)

        guard case .project(let secondSnapshot) = try await restarted.snapshot(.project(secondProjectID)) else {
            return XCTFail("Expected the healthy project snapshot")
        }
        XCTAssertEqual(secondSnapshot.activeRuns.first?.status, .interrupted)
        XCTAssertEqual(secondSnapshot.activeRuns.first?.interruptionReason, .recovery)
        let firstAfterSecondRecovery = try await repository.document(firstProjectID)
        XCTAssertEqual(
            firstAfterSecondRecovery.activeRuns.first?.status,
            .running,
            "The failed project's marker must not be treated as recovered."
        )

        do {
            _ = try await restarted.snapshot(.project(firstProjectID))
            XCTFail("Expected the failed project to remain fail-closed")
        } catch let error as NovelError {
            guard case .repositoryFailure = error else {
                return XCTFail("Unexpected recovery error: \(error)")
            }
        }
        let firstAfterFailedOpen = try await repository.document(firstProjectID)
        XCTAssertEqual(firstAfterFailedOpen.activeRuns.first?.status, .running)

        await repository.allowCommits(for: firstProjectID)
        guard case .project(let firstSnapshot) = try await restarted.snapshot(.project(firstProjectID)) else {
            return XCTFail("Expected the failed project recovery to remain retryable")
        }
        XCTAssertEqual(firstSnapshot.activeRuns.first?.status, .interrupted)
        XCTAssertEqual(firstSnapshot.activeRuns.first?.interruptionReason, .recovery)

        await firstHarness.adapter.resume(runID: firstRequest.id)
        await secondHarness.adapter.resume(runID: secondRequest.id)
        _ = await capturedEvents(firstRun.events)
        _ = await capturedEvents(secondRun.events)
    }

    func testConcurrentProjectSnapshotWaitsForCurrentRecoveryThenRecoversItsOwnMarker() async throws {
        let firstDocument = try NovelTestFixtures.document()
        let secondDocument = try NovelTestFixtures.document()
        let firstHarness = try await makeHarness(
            document: firstDocument,
            scripts: [NovelModelScript(steps: [.pause])]
        )
        let secondHarness = try await makeHarness(
            document: secondDocument,
            scripts: [NovelModelScript(steps: [.pause])]
        )
        let firstRequest = makeRequest(document: firstDocument, kind: .discussion)
        let secondRequest = makeRequest(document: secondDocument, kind: .discussion)
        let firstRun = try await firstHarness.creation.start(firstRequest)
        let secondRun = try await secondHarness.creation.start(secondRequest)

        let repository = ObservingNovelRepository()
        try await repository.seed(firstHarness.repository.document(firstDocument.project.id))
        try await repository.seed(secondHarness.repository.document(secondDocument.project.id))
        await repository.blockNextCommit()
        let restarted = DefaultNovelCreation(repository: repository)
        let firstSnapshotTask = Task {
            try await restarted.snapshot(.project(firstDocument.project.id))
        }
        let firstCommitBlocked = await eventually { await repository.commitIsBlocked() }
        XCTAssertTrue(firstCommitBlocked)

        let secondCompletion = NovelSnapshotCompletionProbe()
        let secondSnapshotTask = Task {
            await secondCompletion.markStarted()
            do {
                let snapshot = try await restarted.snapshot(.project(secondDocument.project.id))
                await secondCompletion.markCompleted()
                return snapshot
            } catch {
                await secondCompletion.markCompleted()
                throw error
            }
        }
        let secondStarted = await eventually { await secondCompletion.isStarted }
        XCTAssertTrue(secondStarted)
        let secondReturnedBeforeFirstRecovery = await eventually(timeout: 0.1) {
            await secondCompletion.isCompleted
        }
        XCTAssertFalse(
            secondReturnedBeforeFirstRecovery,
            "A concurrent project request must not cross an active recovery barrier."
        )

        await repository.resumeBlockedCommit()
        guard case .project(let firstSnapshot) = try await firstSnapshotTask.value,
              case .project(let secondSnapshot) = try await secondSnapshotTask.value else {
            return XCTFail("Expected both recovered project snapshots")
        }
        XCTAssertEqual(firstSnapshot.activeRuns.first?.status, .interrupted)
        XCTAssertEqual(secondSnapshot.activeRuns.first?.status, .interrupted)

        await firstHarness.adapter.resume(runID: firstRequest.id)
        await secondHarness.adapter.resume(runID: secondRequest.id)
        _ = await capturedEvents(firstRun.events)
        _ = await capturedEvents(secondRun.events)
    }

    func testScopedRecoveryLoadsRequiredProjectWhenInventoryOmitsIt() async throws {
        let document = try NovelTestFixtures.document()
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.pause])]
        )
        let request = makeRequest(document: document, kind: .discussion)
        let run = try await harness.creation.start(request)
        let repository = ObservingNovelRepository()
        try await repository.seed(harness.repository.document(document.project.id))
        await repository.omitFromProjectList(document.project.id)

        let restarted = DefaultNovelCreation(repository: repository)
        guard case .project(let snapshot) = try await restarted.snapshot(.project(document.project.id)) else {
            return XCTFail("Expected a project snapshot")
        }
        XCTAssertEqual(snapshot.activeRuns.first?.status, .interrupted)
        XCTAssertEqual(snapshot.activeRuns.first?.interruptionReason, .recovery)

        await harness.adapter.resume(runID: request.id)
        _ = await capturedEvents(run.events)
    }

    func testImportPreviewRecoversOnlyDecodedProject() async throws {
        let existingDocument = try NovelTestFixtures.document()
        let seedHarness = try await makeHarness(
            document: existingDocument,
            scripts: [NovelModelScript(steps: [.pause])]
        )
        let existingRequest = makeRequest(document: existingDocument, kind: .discussion)
        let existingRun = try await seedHarness.creation.start(existingRequest)
        let importedDocument = try NovelTestFixtures.document()
        let package = try NovelProjectPackageCodec.encode(importedDocument)

        let repository = ObservingNovelRepository()
        try await repository.seed(seedHarness.repository.document(existingDocument.project.id))
        await repository.failCommits(for: existingDocument.project.id)
        let restarted = DefaultNovelCreation(repository: repository)

        guard case .projectImportPreview(let preview) = try await restarted.snapshot(
            .projectImportPreview(package.data)
        ) else {
            return XCTFail("Expected an import preview")
        }
        XCTAssertEqual(preview.sourceProjectID, importedDocument.project.id)
        let existingAfterPreview = try await repository.document(existingDocument.project.id)
        XCTAssertEqual(existingAfterPreview.activeRuns.first?.status, .running)

        await seedHarness.adapter.resume(runID: existingRequest.id)
        _ = await capturedEvents(existingRun.events)
    }

    func testScopedRecoveryIsRememberedAcrossProjectAndBranchSnapshots() async throws {
        let document = try NovelTestFixtures.document()
        let repository = ObservingNovelRepository()
        try await repository.seed(document)
        let restarted = DefaultNovelCreation(repository: repository)

        _ = try await restarted.snapshot(.project(document.project.id))
        let loadCountAfterProject = await repository.loadCount(for: document.project.id)
        _ = try await restarted.snapshot(.branch(
            projectID: document.project.id,
            branchID: document.branches[0].id
        ))

        let loadCountAfterBranch = await repository.loadCount(for: document.project.id)
        XCTAssertEqual(loadCountAfterBranch, loadCountAfterProject)
    }

    func testCancelCannotClaimSameRunIDFromAnotherProject() async throws {
        let first = try NovelTestFixtures.document()
        let second = try NovelTestFixtures.document()
        let harness = try await makeHarness(
            document: first,
            scripts: [NovelModelScript(steps: [.pause])]
        )
        try await harness.repository.seed(second)
        let request = makeRequest(document: first, kind: .discussion)
        _ = try await harness.creation.start(request)

        do {
            _ = try await harness.creation.perform(.cancelRun(cancelCommand(
                document: second,
                runID: request.id
            )))
            XCTFail("Expected a cross-project cancel to be rejected")
        } catch let error as NovelError {
            XCTAssertEqual(error, .runNotFound(request.id))
        }

        let firstAfterCancel = try await harness.repository.document(first.project.id)
        let secondAfterCancel = try await harness.repository.document(second.project.id)
        let cancelledRunIDs = await harness.adapter.cancelledRunIDs
        XCTAssertEqual(firstAfterCancel.activeRuns[0].status, .running)
        XCTAssertTrue(secondAfterCancel.activeRuns.isEmpty)
        XCTAssertFalse(cancelledRunIDs.contains(request.id))
    }

    func testStartPreservesRequiredBudgetItemDetails() async throws {
        let document = try NovelTestFixtures.document()
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.pause])]
        )
        let request = makeRequest(
            document: document,
            kind: .discussion,
            inputBudgetTokens: 1
        )

        do {
            _ = try await harness.creation.start(request)
            XCTFail("Expected protected context to exceed the budget")
        } catch let error as NovelError {
            guard case .injectionBudgetExceeded(
                let required,
                let limit,
                let items
            ) = error else {
                return XCTFail("Unexpected budget error: \(error)")
            }
            XCTAssertGreaterThan(required, limit)
            XCTAssertEqual(limit, 1)
            XCTAssertFalse(items.isEmpty)
            XCTAssertTrue(items.allSatisfy {
                !$0.label.isEmpty && $0.estimatedTokens > 0
            })
        }
    }

    func testModelWindowClampsPlannerBudgetInsteadOfRejectingSmallRequest() async throws {
        let document = try NovelTestFixtures.document()
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.delta("A small response."), .complete])],
            contextWindowTokens: 8_192
        )
        let request = makeRequest(
            document: document,
            kind: .discussion,
            inputBudgetTokens: 16_000
        )

        let run = try await harness.creation.start(request)
        let events = await capturedEvents(run.events)

        XCTAssertTrue(events.contains { event in
            if case .completed = event { return true }
            return false
        })
        let stored = try await harness.repository.document(document.project.id)
        let expectedBudget = 8_192 - NovelRunKind.discussion.outputReservationTokens - 1_024
        XCTAssertEqual(stored.injectionReceipts.count, 1)
        XCTAssertEqual(stored.injectionReceipts[0].requestedInputBudgetTokens, 16_000)
        XCTAssertEqual(stored.injectionReceipts[0].maxEstimatedInputTokens, expectedBudget)
        XCTAssertLessThan(stored.injectionReceipts[0].estimatedInputTokens, expectedBudget)
    }

    func testConcurrentExactStartUsesOneReservationAndTwoSubscribers() async throws {
        let document = try NovelTestFixtures.document()
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.pause, .delta("One run"), .complete])]
        )
        let request = makeRequest(document: document, kind: .discussion)
        await harness.repository.blockNextCommit()
        let firstTask = Task { try await harness.creation.start(request) }
        let firstCommitBlocked = await eventually {
            await harness.repository.commitIsBlocked()
        }
        XCTAssertTrue(firstCommitBlocked)
        let secondTask = Task { try await harness.creation.start(request) }
        try? await Task.sleep(nanoseconds: 10_000_000)
        await harness.repository.resumeBlockedCommit()

        let firstRun = try await firstTask.value
        let secondRun = try await secondTask.value
        await harness.adapter.resume(runID: request.id)
        let eventsA = await capturedEvents(firstRun.events)
        let eventsB = await capturedEvents(secondRun.events)
        let requests = await harness.adapter.requests
        XCTAssertEqual(eventsA.last, eventsB.last)
        XCTAssertEqual(requests.count, 1)
        let persisted = try await harness.repository.document(request.projectID)
        XCTAssertEqual(persisted.generationReceipts.filter { $0.runID == request.id }.count, 1)
    }

    func testConcurrentCrossProjectRunIDCannotOverwriteReservation() async throws {
        let first = try NovelTestFixtures.document()
        let second = try NovelTestFixtures.document()
        let harness = try await makeHarness(
            document: first,
            scripts: [
                NovelModelScript(steps: [.pause]),
                NovelModelScript(steps: [.pause])
            ]
        )
        try await harness.repository.seed(second)
        let sharedRunID = NovelRunID()
        let firstRequest = makeRequest(
            document: first,
            kind: .discussion,
            runID: sharedRunID
        )
        let secondRequest = makeRequest(
            document: second,
            kind: .discussion,
            runID: sharedRunID
        )
        await harness.repository.blockNextCommit()
        let firstTask = Task { try await harness.creation.start(firstRequest) }
        let firstCommitBlocked = await eventually {
            await harness.repository.commitIsBlocked()
        }
        XCTAssertTrue(firstCommitBlocked)

        do {
            _ = try await harness.creation.start(secondRequest)
            XCTFail("Expected the reserved run ID to reject another project")
        } catch let error as NovelError {
            XCTAssertEqual(error, .idempotencyConflict(secondRequest.operationID))
        }
        await harness.repository.resumeBlockedCommit()
        _ = try await firstTask.value
        let secondAfterStart = try await harness.repository.document(second.project.id)
        let requests = await harness.adapter.requests
        XCTAssertTrue(secondAfterStart.activeRuns.isEmpty)
        XCTAssertEqual(requests.count, 1)
    }

    func testDegradedRecoveryKeepsRunningMarkerAndValidSidecar() async throws {
        let document = try NovelTestFixtures.document()
        let policy = NovelGenerationPolicy(
            sidecarByteThreshold: 1,
            sidecarInterval: 10_000,
            chapterTailCharacterLimit: 6_000,
            maximumRecentSessionMessages: 12
        )
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.delta("Keep safely"), .pause])],
            policy: policy
        )
        let request = makeRequest(document: document, kind: .discussion)
        let run = try await harness.creation.start(request)
        var iterator = run.events.makeAsyncIterator()
        _ = await iterator.next()
        _ = await iterator.next()
        let wroteSidecar = await eventually {
            (try? await harness.repository.listRecoverySidecars().isEmpty) == false
        }
        XCTAssertTrue(wroteSidecar)
        await harness.repository.setLoadAccess(
            .degradedPrevious(primaryFailure: "Injected primary failure."),
            projectID: request.projectID
        )

        let restarted = DefaultNovelCreation(repository: harness.repository)
        _ = try await restarted.snapshot(.project(request.projectID))
        let durable = try await harness.repository.document(request.projectID)
        let remainingSidecars = try await harness.repository.listRecoverySidecars()
        XCTAssertEqual(durable.activeRuns[0].status, .running)
        XCTAssertEqual(remainingSidecars.count, 1)

        await harness.repository.setLoadAccess(nil, projectID: request.projectID)
        let writableRestart = DefaultNovelCreation(repository: harness.repository)
        _ = try await writableRestart.snapshot(.project(request.projectID))
        let recovered = try await harness.repository.document(request.projectID)
        XCTAssertEqual(recovered.activeRuns[0].status, .interrupted)
        XCTAssertEqual(recovered.activeRuns[0].interruptionReason, .recovery)
        XCTAssertEqual(recovered.activeRuns[0].partialContent, "Keep safely")
        XCTAssertEqual(recovered.sessions[0].messages.last?.content, "Keep safely")
        let sidecarsAfterRecovery = try await harness.repository.listRecoverySidecars()
        XCTAssertTrue(sidecarsAfterRecovery.isEmpty)
        _ = try await writableRestart.snapshot(.project(request.projectID))
        let afterSecondRecovery = try await harness.repository.document(request.projectID)
        XCTAssertEqual(afterSecondRecovery, recovered)
    }

    func testBackgroundInterruptionReturnsAtDeadlineWhilePersistenceAndCancelAreBlocked() async throws {
        let document = try NovelTestFixtures.document()
        let repository = ObservingNovelRepository()
        try await repository.seed(document)
        let adapter = BlockingCancelNovelModelAdapter()
        let creation = DefaultNovelCreation(
            repository: repository,
            modelRunner: adapter,
            now: { Date(timeIntervalSince1970: 1_700_001_000) }
        )
        let request = makeRequest(document: document, kind: .discussion)
        let run = try await creation.start(request)
        var iterator = run.events.makeAsyncIterator()
        _ = await iterator.next()
        await repository.blockNextCommit()

        let wallStart = Date()
        await creation.interruptForBackground(
            projectID: request.projectID,
            deadline: Date(timeIntervalSince1970: 1_700_001_000.02)
        )
        XCTAssertLessThan(Date().timeIntervalSince(wallStart), 0.5)
        let commitBlocked = await eventually { await repository.commitIsBlocked() }
        let cancelStarted = await eventually { await adapter.cancelDidStart() }
        XCTAssertTrue(commitBlocked)
        XCTAssertTrue(cancelStarted)

        await creation.interruptForBackground(
            projectID: request.projectID,
            deadline: Date(timeIntervalSince1970: 1_700_000_999)
        )
        await repository.resumeBlockedCommit()
        guard case .interrupted? = await iterator.next() else {
            return XCTFail("Expected the original background claim to finish")
        }
        let terminal = try await repository.document(request.projectID)
        XCTAssertEqual(terminal.activeRuns[0].interruptionReason, .background)
        await adapter.releaseCancel()
    }

    func testRestartCoversTerminalWrittenSidecarPresentAndSidecarDeletedCrashPoints() async throws {
        for leaveSidecar in [true, false] {
            let document = try NovelTestFixtures.document()
            let policy = NovelGenerationPolicy(
                sidecarByteThreshold: 1,
                sidecarInterval: 10_000,
                chapterTailCharacterLimit: 6_000,
                maximumRecentSessionMessages: 12
            )
            let harness = try await makeHarness(
                document: document,
                scripts: [NovelModelScript(steps: [.delta("Terminal"), .pause, .complete])],
                policy: policy
            )
            let request = makeRequest(document: document, kind: .discussion)
            let run = try await harness.creation.start(request)
            var iterator = run.events.makeAsyncIterator()
            _ = await iterator.next()
            _ = await iterator.next()
            let wroteSidecar = await eventually {
                (try? await harness.repository.listRecoverySidecars().isEmpty) == false
            }
            XCTAssertTrue(wroteSidecar)
            if leaveSidecar {
                await harness.repository.failNextSidecarRemovals(1)
            }
            await harness.adapter.resume(runID: request.id)
            guard case .completed? = await iterator.next() else {
                return XCTFail("Expected a durable terminal")
            }
            let terminal = try await harness.repository.document(request.projectID)
            let sidecarsAtCrash = try await harness.repository.listRecoverySidecars()
            XCTAssertEqual(sidecarsAtCrash.isEmpty, !leaveSidecar)

            let restarted = DefaultNovelCreation(repository: harness.repository)
            _ = try await restarted.snapshot(.project(request.projectID))
            let afterRestart = try await harness.repository.document(request.projectID)
            let sidecarsAfterRestart = try await harness.repository.listRecoverySidecars()
            XCTAssertEqual(afterRestart, terminal)
            XCTAssertTrue(sidecarsAfterRestart.isEmpty)
        }
    }

    func testStartupRecoveryWaitsForConcurrentProjectMutationAfterLedgerDiscovery() async throws {
        let seedDocument = try NovelTestFixtures.document()
        let seedHarness = try await makeHarness(
            document: seedDocument,
            scripts: [NovelModelScript(steps: [.pause, .delta("Cleanup"), .complete])]
        )
        let request = makeRequest(document: seedDocument, kind: .discussion)
        let seedRun = try await seedHarness.creation.start(request)
        let runningDocument = try await seedHarness.repository.document(request.projectID)
        await seedHarness.adapter.resume(runID: request.id)
        _ = await capturedEvents(seedRun.events)

        let repository = ObservingNovelRepository()
        try await repository.seed(runningDocument)
        let restarted = DefaultNovelCreation(repository: repository)
        await repository.blockNextLoad()

        let recoverySnapshot = Task {
            try await restarted.snapshot(.project(request.projectID))
        }
        let loadBlocked = await eventually { await repository.loadIsBlocked() }
        XCTAssertTrue(loadBlocked)

        await repository.blockNextCommit()
        let rename = NovelAction.renameProject(NovelRenameProjectCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: runningDocument.project.revision,
                expectedConfigRevision: nil,
                expectedBranchHeadRevision: nil
            ),
            projectID: request.projectID,
            name: "Recovered After Rename"
        ))
        let renameTask = Task { try await restarted.perform(rename) }
        let commitBlocked = await eventually { await repository.commitIsBlocked() }
        XCTAssertTrue(commitBlocked)

        await repository.resumeBlockedLoad()
        let recoveryCommittedBeforeRename = await eventually(timeout: 0.1) {
            await repository.commitHistory().isEmpty == false
        }
        XCTAssertFalse(recoveryCommittedBeforeRename)

        await repository.resumeBlockedCommit()
        _ = try await renameTask.value
        guard case .project(let snapshot) = try await recoverySnapshot.value else {
            return XCTFail("Expected a recovered project snapshot")
        }
        XCTAssertEqual(snapshot.project.name, "Recovered After Rename")
        XCTAssertEqual(snapshot.project.revision, runningDocument.project.revision + 2)
        XCTAssertEqual(snapshot.activeRuns.first?.status, .interrupted)
        XCTAssertNil(snapshot.branches.first?.activeRunID)
    }
}

private extension NovelGenerationLifecycleTests {
    struct Harness {
        let repository: ObservingNovelRepository
        let adapter: ScriptedNovelModelAdapter
        let creation: DefaultNovelCreation
    }

    struct CompletionCase {
        let kind: NovelRunKind
        let granularity: NovelGenerationGranularity?
        let content: String
        let expectedMessage: NovelSessionMessageKind
    }

    enum CapturedEvent: Equatable {
        case started(NovelRunID)
        case delta(String)
        case replaced(String)
        case completed(NovelSessionMessageSnapshot)
        case interrupted(NovelSessionMessageSnapshot?)
        case failed(NovelFailure)
        case persistenceBlocked(NovelFailure)
    }

    func makeHarness(
        document: NovelProjectDocumentV1,
        scripts: [NovelModelScript],
        policy: NovelGenerationPolicy = .standard,
        contextWindowTokens: Int = 128_000
    ) async throws -> Harness {
        let repository = ObservingNovelRepository()
        try await repository.seed(document)
        let adapter = ScriptedNovelModelAdapter(
            resolvedModel: NovelResolvedModel(
                providerID: "provider-id",
                ownerProviderID: "provider-id",
                modelID: "model-uuid",
                wireModelID: "novel-model-v1",
                displayName: "Novel Model",
                contextWindowTokens: contextWindowTokens
            ),
            scripts: scripts
        )
        return Harness(
            repository: repository,
            adapter: adapter,
            creation: DefaultNovelCreation(
                repository: repository,
                modelRunner: adapter,
                generationPolicy: policy,
                now: { Date(timeIntervalSince1970: 1_700_001_000) }
            )
        )
    }

    func makeRequest(
        document: NovelProjectDocumentV1,
        kind: NovelRunKind,
        granularity: NovelGenerationGranularity? = nil,
        sourceChapterVersionID: NovelChapterVersionID? = nil,
        runID: NovelRunID = NovelRunID(),
        operationID: NovelOperationID = NovelOperationID(),
        inputBudgetTokens: Int = 16_000
    ) -> NovelRunRequest {
        let branch = document.branches[0]
        let mode: NovelSessionMode = switch kind {
        case .quickStart, .discussion: .discussPlan
        case .prose, .polish, .regenerate: .writeProse
        }
        let candidateID: NovelCandidateID? = switch kind {
        case .quickStart, .discussion: nil
        case .prose, .polish, .regenerate: NovelCandidateID()
        }
        return NovelRunRequest(
            id: runID,
            operationID: operationID,
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
            inputBudgetTokens: inputBudgetTokens,
            expectedProjectRevision: document.project.revision,
            expectedConfigRevision: document.project.configRevision,
            expectedBranchHeadRevision: branch.headRevision
        )
    }

    func quickStartDocument() throws -> NovelProjectDocumentV1 {
        try NovelReducer.createProject(NovelCreateProjectCommand(
            context: NovelTestFixtures.context(operationID: NovelOperationID()),
            projectID: NovelProjectID(),
            branchID: NovelBranchID(),
            sessionID: NovelSessionID(),
            initialStateSnapshotID: NovelStateSnapshotID(),
            initialCheckpointID: NovelCheckpointID(),
            name: "Quick Start",
            branchName: "Main",
            creationMode: .quickStart,
            quickStartSeed: NovelQuickStartSeed(
                genre: "Mystery",
                coreIdea: "Memories can testify."
            )
        ), now: Date(timeIntervalSince1970: 1_700_000_000)).document
    }

    func cancelCommand(
        document: NovelProjectDocumentV1,
        runID: NovelRunID
    ) -> NovelCancelRunCommand {
        NovelCancelRunCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: document.project.revision,
                expectedConfigRevision: document.project.configRevision,
                expectedBranchHeadRevision: document.branches[0].headRevision
            ),
            projectID: document.project.id,
            runID: runID,
            reason: .user
        )
    }

    func capturedEvents(_ stream: AsyncStream<NovelRunEvent>) async -> [CapturedEvent] {
        var result: [CapturedEvent] = []
        for await event in stream {
            switch event {
            case .started(let receipt): result.append(.started(receipt.runID))
            case .delta(let text): result.append(.delta(text))
            case .replaced(let text): result.append(.replaced(text))
            case .completed(let message): result.append(.completed(message))
            case .interrupted(let message): result.append(.interrupted(message))
            case .failed(let failure): result.append(.failed(failure))
            case .persistenceBlocked(let failure): result.append(.persistenceBlocked(failure))
            }
        }
        return result
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

    func documentWithChapter() throws -> (NovelProjectDocumentV1, NovelChapterVersionID?) {
        var document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let chapterID = NovelChapterID()
        let versionID = NovelChapterVersionID()
        let operationID = document.appliedOperations[0].operationID
        let timestamp = document.project.updatedAt
        document.chapters.append(NovelChapterRecord(id: chapterID, createdAt: timestamp))
        document.chapterVersions.append(NovelChapterVersionRecord(
            id: versionID,
            chapterID: chapterID,
            kind: .collected,
            title: "Chapter One",
            content: "The witness entered the silent courtroom.",
            factCompatibilityID: UUID(),
            sourceCandidateID: nil,
            createdAt: timestamp,
            operationID: operationID
        ))
        let checkpointIndex = try XCTUnwrap(document.checkpoints.firstIndex {
            $0.id == document.branches[0].headCheckpointID
        })
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

private actor ObservingNovelRepository: NovelProjectPersisting {
    private let base = InMemoryNovelProjectRepository()
    private var commits: [NovelProjectDocumentV1] = []
    private var writtenSidecars: [NovelRecoverySidecarV1] = []
    private var removedSidecarRunIDs: [NovelRunID] = []
    private var remainingCommitFailures = 0
    private var failingCommitProjectIDs: Set<NovelProjectID> = []
    private var remainingSidecarRemovalFailures = 0
    private var forcedAccess: [NovelProjectID: NovelProjectLoadAccess] = [:]
    private var omittedSummaryProjectIDs: Set<NovelProjectID> = []
    private var projectLoadCounts: [NovelProjectID: Int] = [:]
    private var shouldBlockNextCommit = false
    private var blockedCommitContinuation: CheckedContinuation<Void, Never>?
    private var shouldBlockNextLoad = false
    private var blockedLoadContinuation: CheckedContinuation<Void, Never>?

    func seed(_ document: NovelProjectDocumentV1) async throws {
        _ = try await base.createProject(document)
    }

    func document(_ id: NovelProjectID) async throws -> NovelProjectDocumentV1 {
        let loaded = try await base.loadProject(id: id)
        return loaded.document
    }

    func commitHistory() -> [NovelProjectDocumentV1] { commits }
    func sidecarWrites() -> [NovelRecoverySidecarV1] { writtenSidecars }
    func sidecarRemovals() -> [NovelRunID] { removedSidecarRunIDs }
    func failNextCommits(_ count: Int) { remainingCommitFailures = count }
    func failCommits(for projectID: NovelProjectID) {
        failingCommitProjectIDs.insert(projectID)
    }
    func allowCommits(for projectID: NovelProjectID) {
        failingCommitProjectIDs.remove(projectID)
    }
    func omitFromProjectList(_ projectID: NovelProjectID) {
        omittedSummaryProjectIDs.insert(projectID)
    }
    func loadCount(for projectID: NovelProjectID) -> Int {
        projectLoadCounts[projectID, default: 0]
    }
    func failNextSidecarRemovals(_ count: Int) {
        remainingSidecarRemovalFailures = count
    }
    func setLoadAccess(_ access: NovelProjectLoadAccess?, projectID: NovelProjectID) {
        forcedAccess[projectID] = access
    }
    func blockNextCommit() { shouldBlockNextCommit = true }
    func commitIsBlocked() -> Bool { blockedCommitContinuation != nil }
    func resumeBlockedCommit() {
        let continuation = blockedCommitContinuation
        blockedCommitContinuation = nil
        continuation?.resume()
    }
    func blockNextLoad() { shouldBlockNextLoad = true }
    func loadIsBlocked() -> Bool { blockedLoadContinuation != nil }
    func resumeBlockedLoad() {
        let continuation = blockedLoadContinuation
        blockedLoadContinuation = nil
        continuation?.resume()
    }

    func listProjects() async throws -> [NovelProjectSummary] {
        let summaries = try await base.listProjects()
        return summaries.filter { !omittedSummaryProjectIDs.contains($0.id) }
    }

    func loadProject(id: NovelProjectID) async throws -> NovelLoadedProject {
        projectLoadCounts[id, default: 0] += 1
        let loaded = try await base.loadProject(id: id)
        if shouldBlockNextLoad {
            shouldBlockNextLoad = false
            await withCheckedContinuation { continuation in
                blockedLoadContinuation = continuation
            }
        }
        guard let access = forcedAccess[id] else { return loaded }
        return NovelLoadedProject(document: loaded.document, access: access)
    }

    func createProject(_ document: NovelProjectDocumentV1) async throws -> NovelLoadedProject {
        try await base.createProject(document)
    }

    func commitProject(
        _ document: NovelProjectDocumentV1,
        expectedRevision: Int64,
        authorization: NovelRepositoryCommitAuthorization?
    ) async throws -> NovelLoadedProject {
        if shouldBlockNextCommit {
            shouldBlockNextCommit = false
            await withCheckedContinuation { continuation in
                blockedCommitContinuation = continuation
            }
        }
        if failingCommitProjectIDs.contains(document.project.id) {
            throw NovelError.repositoryFailure("Injected project commit failure.")
        }
        if remainingCommitFailures > 0 {
            remainingCommitFailures -= 1
            throw NovelError.repositoryFailure("Injected commit failure.")
        }
        let loaded = try await base.commitProject(
            document,
            expectedRevision: expectedRevision,
            authorization: authorization
        )
        commits.append(loaded.document)
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
        writtenSidecars.append(sidecar)
    }

    func removeRecoverySidecar(projectID: NovelProjectID, runID: NovelRunID) async throws {
        if remainingSidecarRemovalFailures > 0 {
            remainingSidecarRemovalFailures -= 1
            throw NovelError.repositoryFailure("Injected sidecar removal failure.")
        }
        try await base.removeRecoverySidecar(projectID: projectID, runID: runID)
        removedSidecarRunIDs.append(runID)
    }
}

private actor NovelSnapshotCompletionProbe {
    private var started = false
    private var completed = false

    var isStarted: Bool { started }
    var isCompleted: Bool { completed }

    func markStarted() {
        started = true
    }

    func markCompleted() {
        completed = true
    }
}

private actor BlockingCancelNovelModelAdapter: NovelModelRunning {
    private var cancelContinuation: CheckedContinuation<Void, Never>?
    private var didStartCancel = false

    func resolveModel(for policy: NovelProjectModelPolicy) async throws -> NovelResolvedModel {
        _ = policy
        return NovelResolvedModel(
            providerID: "provider-id",
            ownerProviderID: "provider-id",
            modelID: "model-uuid",
            wireModelID: "novel-model-v1",
            displayName: "Novel Model",
            contextWindowTokens: 128_000
        )
    }

    func start(_ request: NovelModelRequest) async throws -> AsyncStream<NovelModelEvent> {
        _ = request
        return AsyncStream { _ in }
    }

    func cancel(runID: NovelRunID) async {
        _ = runID
        didStartCancel = true
        await withCheckedContinuation { continuation in
            cancelContinuation = continuation
        }
    }

    func cancelDidStart() -> Bool { didStartCancel }

    func releaseCancel() {
        let continuation = cancelContinuation
        cancelContinuation = nil
        continuation?.resume()
    }
}

private actor CancellableStartNovelModelAdapter: NovelModelRunning {
    private var didBegin = false
    private var cancellations: [NovelRunID] = []

    func resolveModel(for policy: NovelProjectModelPolicy) async throws -> NovelResolvedModel {
        _ = policy
        return NovelResolvedModel(
            providerID: "provider-id",
            ownerProviderID: "provider-id",
            modelID: "model-uuid",
            wireModelID: "novel-model-v1",
            displayName: "Novel Model",
            contextWindowTokens: 128_000
        )
    }

    func start(_ request: NovelModelRequest) async throws -> AsyncStream<NovelModelEvent> {
        _ = request
        didBegin = true
        try await Task.sleep(for: .seconds(30))
        return AsyncStream { _ in }
    }

    func cancel(runID: NovelRunID) async {
        cancellations.append(runID)
    }

    func startDidBegin() -> Bool { didBegin }
    func cancelledRunIDs() -> [NovelRunID] { cancellations }
}

private actor BlockingStartNovelModelAdapter: NovelModelRunning {
    private var didBegin = false
    private var startContinuation: CheckedContinuation<Void, Never>?

    func resolveModel(for policy: NovelProjectModelPolicy) async throws -> NovelResolvedModel {
        _ = policy
        return NovelResolvedModel(
            providerID: "provider-id",
            ownerProviderID: "provider-id",
            modelID: "model-uuid",
            wireModelID: "novel-model-v1",
            displayName: "Novel Model",
            contextWindowTokens: 128_000
        )
    }

    func start(_ request: NovelModelRequest) async throws -> AsyncStream<NovelModelEvent> {
        _ = request
        didBegin = true
        await withCheckedContinuation { continuation in
            startContinuation = continuation
        }
        return AsyncStream { _ in }
    }

    func cancel(runID: NovelRunID) async {
        _ = runID
    }

    func startDidBegin() -> Bool { didBegin }

    func releaseStart() {
        let continuation = startContinuation
        startContinuation = nil
        continuation?.resume()
    }
}
