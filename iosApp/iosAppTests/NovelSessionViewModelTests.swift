import XCTest
@testable import iosApp

@MainActor
final class NovelSessionViewModelTests: XCTestCase {
    func testDiscussionCompletesAsOneDurableAssistantBubble() async throws {
        let harness = try await makeHarness(scripts: [NovelModelScript(steps: [
            .delta("零散"),
            .replacement("完整讨论建议"),
            .complete,
        ])])
        harness.session.mode = .discussPlan

        let didStart = await harness.session.send(text: "下一步该怎么规划？")
        XCTAssertTrue(didStart)
        let didFinish = await eventually { !harness.session.isRunning }
        XCTAssertTrue(didFinish)

        XCTAssertEqual(harness.session.durableMessages.map(\.kind), [.userInput, .discussion])
        XCTAssertEqual(harness.session.durableMessages.last?.content, "完整讨论建议")
        XCTAssertNil(harness.session.durableMessages.last?.candidateID)
        XCTAssertNil(harness.session.transientTail)
        let requests = await harness.adapter.requests
        let request = try XCTUnwrap(requests.first)
        XCTAssertEqual(request.purpose, .discussion)
    }

    func testWholeChapterUsesOneMonotonicTransientTailThenPersistsCandidate() async throws {
        let longBody = String(repeating: "长章正文。", count: 2_000)
        let harness = try await makeHarness(scripts: [NovelModelScript(steps: [
            .delta(longBody),
            .pause,
            .delta("结尾。"),
            .complete,
        ])])
        harness.session.mode = .writeProse
        harness.session.granularity = .wholeChapter

        let didStart = await harness.session.send(text: "生成这一整章")
        XCTAssertTrue(didStart)
        let sawLongTail = await eventually { harness.session.transientTail?.content == longBody }
        XCTAssertTrue(sawLongTail)
        XCTAssertFalse(harness.workspace.canMutate)
        let runID = try XCTUnwrap(harness.session.activeRunID)
        let firstRevision = try XCTUnwrap(harness.session.transientTail?.renderRevision)
        XCTAssertEqual(harness.session.durableMessages.count, 1)
        XCTAssertEqual(harness.session.transientTail?.kind, .proseCandidate)

        await harness.adapter.resume(runID: runID)
        let didFinish = await eventually { !harness.session.isRunning }
        XCTAssertTrue(didFinish)
        XCTAssertEqual(harness.session.availableProseCandidates.first?.content, longBody + "结尾。")
        XCTAssertNil(harness.session.transientTail)
        XCTAssertTrue(harness.workspace.canMutate)
        XCTAssertGreaterThan(firstRevision, 0)
        let requests = await harness.adapter.requests
        let request = try XCTUnwrap(requests.first)
        XCTAssertEqual(request.purpose, .prose)
        XCTAssertEqual(request.parameters.maxOutputTokens, 8_192)
    }

    func testConcurrentRebindsKeepOneConsumerAndApplyEachDeltaOnce() async throws {
        let harness = try await makeHarness(
            scripts: [NovelModelScript(steps: [
                .pause,
                .delta("只追加一次"),
                .pause,
            ])],
            usesAttachGate: true
        )
        harness.session.mode = .discussPlan
        let started = await harness.session.send(text: "建立可恢复订阅")
        XCTAssertTrue(started)
        let runID = try XCTUnwrap(harness.session.activeRunID)
        let durable = await eventually {
            harness.workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.id == runID && $0.status == .running
            }) == true
        }
        XCTAssertTrue(durable)
        harness.session.detachConsumer()
        let gate = try XCTUnwrap(harness.attachGate)
        await gate.blockNextStart()

        let firstBind = Task { @MainActor in
            await harness.session.bindToCurrentSelection()
        }
        let attachBlocked = await eventually { await gate.startIsBlocked() }
        XCTAssertTrue(attachBlocked)
        let secondBind = Task { @MainActor in
            await harness.session.bindToCurrentSelection()
        }
        await secondBind.value
        await gate.resumeBlockedStart()
        await firstBind.value

        await harness.adapter.resume(runID: runID)
        let received = await eventually {
            harness.session.transientTail?.content == "只追加一次"
        }
        XCTAssertTrue(received)
        try? await Task.sleep(for: .milliseconds(30))
        XCTAssertEqual(harness.session.transientTail?.content, "只追加一次")
        await harness.session.stop()
    }

    func testStaleAttachCannotOverwriteANewerBranchBinding() async throws {
        let document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let sourceBranchID = document.branches[0].id
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.pause])],
            usesAttachGate: true
        )
        await harness.workspace.forkBranch(
            from: sourceBranchID,
            checkpointID: document.branches[0].headCheckpointID,
            name: "新分支"
        )
        let destinationBranchID = try XCTUnwrap(harness.workspace.selectedBranchID)
        await harness.workspace.selectBranch(sourceBranchID)
        await harness.session.bindToCurrentSelection()
        harness.session.mode = .discussPlan
        let started = await harness.session.send(text: "旧分支运行")
        XCTAssertTrue(started)
        let runID = try XCTUnwrap(harness.session.activeRunID)
        let durable = await eventually {
            harness.workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.id == runID && $0.status == .running
            }) == true
        }
        XCTAssertTrue(durable)
        harness.session.detachConsumer()
        let gate = try XCTUnwrap(harness.attachGate)
        await gate.blockNextStart()

        let staleAttach = Task { @MainActor in
            await harness.session.bindToCurrentSelection()
        }
        let attachBlocked = await eventually { await gate.startIsBlocked() }
        XCTAssertTrue(attachBlocked)
        await harness.workspace.selectBranch(destinationBranchID)
        await harness.session.bindToCurrentSelection()
        await gate.resumeBlockedStart()
        await staleAttach.value

        XCTAssertEqual(harness.session.binding?.branchID, destinationBranchID)
        XCTAssertNil(harness.session.transientTail)
        XCTAssertNil(harness.session.refreshErrorMessage)
    }

    func testRebindCanonicalizesMultipleInjectionOverridesWithoutChangingRunIdentity() async throws {
        let fixture = try documentWithMaterials()
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [NovelModelScript(steps: [.pause])]
        )
        harness.session.mode = .discussPlan
        let overrides = NovelInjectionOverrides(
            forceIncludeMaterialIDs: [fixture.materialIDs[1], fixture.materialIDs[0], fixture.materialIDs[1]],
            forceExcludeMaterialIDs: []
        )

        let didStart = await harness.session.send(
            text: "比较两份设定",
            injectionOverrides: overrides
        )
        XCTAssertTrue(didStart)
        let runID = try XCTUnwrap(harness.session.activeRunID)
        let refreshedReceipt = await eventually {
            harness.workspace.projectSnapshot?.injectionReceipts.contains(where: {
                $0.runID == runID
            }) == true
        }
        XCTAssertTrue(refreshedReceipt)
        let receipt = try XCTUnwrap(harness.workspace.projectSnapshot?.injectionReceipts.last)
        XCTAssertEqual(receipt.forceIncludeMaterialIDs, fixture.materialIDs.sorted {
            $0.description < $1.description
        })

        harness.session.detachConsumer()
        await harness.session.bindToCurrentSelection()
        let reattached = await eventually {
            harness.session.activeRunID == runID && !harness.session.hasRefreshError
        }
        XCTAssertTrue(reattached)
        XCTAssertNil(harness.session.refreshErrorMessage)
        await harness.session.stop()
    }

    func testExplicitStopPersistsPartialAndRouteExitDoesNotDependOnConsumerCancellation() async throws {
        let harness = try await makeHarness(scripts: [NovelModelScript(
            steps: [.delta("保留的半段正文"), .pause, .delta("迟到内容"), .complete],
            ignoresCancellation: true
        )])
        harness.session.mode = .writeProse
        harness.session.granularity = .continuation

        let didStart = await harness.session.send(text: "先写一小段")
        XCTAssertTrue(didStart)
        let sawPartial = await eventually {
            harness.session.transientTail?.content == "保留的半段正文"
        }
        XCTAssertTrue(sawPartial)
        let runID = try XCTUnwrap(harness.session.activeRunID)
        await harness.session.interruptForRouteExit()

        let document = try await harness.repository.loadProject(id: harness.projectID).document
        let run = try XCTUnwrap(document.activeRuns.first { $0.id == runID })
        XCTAssertEqual(run.status, .interrupted)
        XCTAssertEqual(run.interruptionReason, .routeExit)
        XCTAssertEqual(run.partialContent, "保留的半段正文")
        XCTAssertEqual(document.sessions[0].messages.last?.kind, .interruptedDraft)
        XCTAssertTrue(document.candidates.isEmpty)
        let cancelledRunIDs = await harness.adapter.cancelledRunIDs
        XCTAssertTrue(cancelledRunIDs.contains(runID))
    }

    func testBranchSwitchGatewayTerminatesOldRunBeforeRebinding() async throws {
        let document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let sourceBranchID = document.branches[0].id
        let checkpointID = try XCTUnwrap(document.checkpoints.last?.id)
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.delta("旧分支内容"), .pause])]
        )
        await harness.workspace.forkBranch(
            from: sourceBranchID,
            checkpointID: checkpointID,
            name: "另一条线"
        )
        let destinationBranchID = try XCTUnwrap(harness.workspace.selectedBranchID)
        XCTAssertNotEqual(destinationBranchID, sourceBranchID)
        await harness.workspace.selectBranch(sourceBranchID)
        await harness.session.bindToCurrentSelection()
        harness.session.mode = .discussPlan

        let didStart = await harness.session.send(text: "继续旧分支")
        XCTAssertTrue(didStart)
        let sawPartial = await eventually { harness.session.transientTail?.content == "旧分支内容" }
        XCTAssertTrue(sawPartial)
        let oldRunID = try XCTUnwrap(harness.session.activeRunID)

        let maySwitch = await harness.session.interruptForRouteExit()
        XCTAssertTrue(maySwitch)
        await harness.workspace.selectBranch(destinationBranchID)
        await harness.session.bindToCurrentSelection()

        XCTAssertEqual(harness.session.binding?.branchID, destinationBranchID)
        let final = try await harness.repository.loadProject(id: harness.projectID).document
        let oldRun = try XCTUnwrap(final.activeRuns.first { $0.id == oldRunID })
        XCTAssertEqual(oldRun.status, .interrupted)
        XCTAssertEqual(oldRun.interruptionReason, .routeExit)
    }

    func testFailedBranchSnapshotKeepsLoadedSelectionAndCanRebindAfterRunStops() async throws {
        let document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let sourceBranchID = document.branches[0].id
        let checkpointID = try XCTUnwrap(document.checkpoints.last?.id)
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.pause])],
            usesSnapshotGate: true
        )
        await harness.workspace.forkBranch(
            from: sourceBranchID,
            checkpointID: checkpointID,
            name: "加载失败目标"
        )
        let destinationBranchID = try XCTUnwrap(harness.workspace.selectedBranchID)
        await harness.workspace.selectBranch(sourceBranchID)
        await harness.session.bindToCurrentSelection()
        harness.session.mode = .discussPlan

        let didStart = await harness.session.send(text: "先停下再切分支")
        XCTAssertTrue(didStart)
        let oldRunID = try XCTUnwrap(harness.session.activeRunID)
        let maySwitch = await harness.session.interruptForRouteExit()
        XCTAssertTrue(maySwitch)
        await harness.snapshotGate?.failNextSnapshots(1)
        await harness.workspace.selectBranch(destinationBranchID)
        await harness.session.bindToCurrentSelection()

        XCTAssertEqual(harness.workspace.selectedBranchID, sourceBranchID)
        XCTAssertEqual(harness.workspace.branchSnapshot?.branch.id, sourceBranchID)
        XCTAssertNotNil(harness.workspace.errorMessage)
        XCTAssertEqual(harness.session.binding?.branchID, sourceBranchID)
        let final = try await harness.repository.loadProject(id: harness.projectID).document
        let oldRun = try XCTUnwrap(final.activeRuns.first { $0.id == oldRunID })
        XCTAssertEqual(oldRun.status, .interrupted)
        XCTAssertEqual(oldRun.interruptionReason, .routeExit)
    }

    func testCommittedForkWithRefreshFailureKeepsCoherentSelectionWithoutOfferingReplay() async throws {
        let document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let sourceBranchID = document.branches[0].id
        let checkpointID = try XCTUnwrap(document.checkpoints.last?.id)
        let harness = try await makeHarness(
            document: document,
            scripts: [],
            usesSnapshotGate: true
        )
        await harness.snapshotGate?.failNextBranchSnapshots(1)

        let forkedBranchID = await harness.workspace.forkBranch(
            from: sourceBranchID,
            checkpointID: checkpointID,
            name: "已提交但待重载"
        )

        XCTAssertNil(forkedBranchID)
        XCTAssertEqual(harness.workspace.selectedBranchID, sourceBranchID)
        XCTAssertEqual(harness.workspace.branchSnapshot?.branch.id, sourceBranchID)
        XCTAssertNil(harness.workspace.errorMessage)
        XCTAssertNotNil(harness.workspace.reloadNoticeMessage)
        XCTAssertTrue(harness.workspace.requiresReload)
        let persisted = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(persisted.branches.filter { $0.lifecycle == .active }.count, 2)
        XCTAssertEqual(persisted.appliedOperations.filter { $0.kind == .forkBranch }.count, 1)

        let otherProject = try NovelTestFixtures.document()
        _ = try await harness.repository.createProject(otherProject)
        await harness.workspace.loadProjects(selecting: otherProject.project.id)
        XCTAssertEqual(harness.workspace.selectedProjectID, otherProject.project.id)
        XCTAssertFalse(harness.workspace.requiresReload)
        XCTAssertTrue(harness.workspace.canMutate)
        XCTAssertTrue(harness.workspace.hasReloadRequirement)

        await harness.workspace.retryCommittedMutationReload()
        XCTAssertFalse(harness.workspace.hasReloadRequirement)
        XCTAssertEqual(harness.workspace.selectedProjectID, otherProject.project.id)
        let reloaded = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(reloaded.branches.filter { $0.lifecycle == .active }.count, 2)
        XCTAssertEqual(reloaded.appliedOperations.filter { $0.kind == .forkBranch }.count, 1)
    }

    func testCommittedKeepBothImportWithRefreshFailureDoesNotReportOldSelectionAsSuccess() async throws {
        let source = try NovelTestFixtures.document()
        let harness = try await makeHarness(
            document: source,
            scripts: [],
            usesSnapshotGate: true
        )
        let package = try NovelProjectPackageCodec.encode(source)
        await harness.snapshotGate?.failNextProjectSnapshots(1)

        let result = await harness.workspace.importProject(package.data, choice: .keepBoth)

        guard case .committedNeedsReload(let destinationID) = result else {
            return XCTFail("Expected committedNeedsReload, got \(String(describing: result))")
        }
        XCTAssertNotEqual(destinationID, source.project.id)
        XCTAssertEqual(harness.workspace.selectedProjectID, source.project.id)
        XCTAssertEqual(harness.workspace.projectSnapshot?.project.id, source.project.id)
        XCTAssertNil(harness.workspace.errorMessage)
        XCTAssertTrue(harness.workspace.hasReloadRequirement)
        XCTAssertFalse(harness.workspace.requiresReload)
        let projects = try await harness.repository.listProjects()
        XCTAssertEqual(projects.count, 2)
    }

    func testFreshProjectSelectionKeepsAnotherProjectsScopedReloadRequirement() async throws {
        let repository = InMemoryNovelProjectRepository()
        let first = try NovelTestFixtures.documentWithForkableCheckpoint()
        let second = try NovelTestFixtures.document()
        let harness = try await makeHarness(
            repository: repository,
            document: first,
            scripts: [],
            usesSnapshotGate: true
        )
        _ = try await repository.createProject(second)
        await harness.snapshotGate?.failNextBranchSnapshots(1)
        await harness.workspace.forkBranch(
            from: first.branches[0].id,
            checkpointID: first.branches[0].headCheckpointID,
            name: "待重载分支"
        )
        XCTAssertTrue(harness.workspace.requiresReload)

        await harness.workspace.selectProject(second.project.id)

        XCTAssertEqual(harness.workspace.selectedProjectID, second.project.id)
        XCTAssertEqual(harness.workspace.projectSnapshot?.project.id, second.project.id)
        XCTAssertFalse(harness.workspace.requiresReload)
        XCTAssertNotNil(harness.workspace.reloadNoticeMessage)
        XCTAssertTrue(harness.workspace.hasReloadRequirement)
        XCTAssertTrue(harness.workspace.canMutate)
    }

    func testLoadProjectsPreservesNestedBranchSelectionFailureForRetry() async throws {
        let harness = try await makeHarness(
            scripts: [],
            usesSnapshotGate: true
        )
        let gate = try XCTUnwrap(harness.snapshotGate)
        await gate.failNextBranchSnapshots(1)

        await harness.workspace.loadProjects(selecting: harness.projectID)

        XCTAssertEqual(harness.workspace.selectedProjectID, harness.projectID)
        XCTAssertEqual(harness.workspace.projectSnapshot?.project.id, harness.projectID)
        XCTAssertNil(harness.workspace.branchSnapshot)
        XCTAssertNotNil(harness.workspace.errorMessage)

        await harness.workspace.loadProjects(selecting: harness.projectID)
        XCTAssertNil(harness.workspace.errorMessage)
        XCTAssertNotNil(harness.workspace.branchSnapshot)
    }

    func testRouteExitDuringSuspendedStartClosesCrossedDurableRun() async throws {
        let repository = NovelSessionFailingRepository()
        let harness = try await makeHarness(
            repository: repository,
            scripts: [NovelModelScript(steps: [.pause])]
        )
        harness.session.mode = .discussPlan
        await repository.blockNextCommit()

        let sendTask = Task { @MainActor in
            await harness.session.send(text: "在落盘前取消")
        }
        let startSuspended = await eventually {
            await repository.commitIsBlocked() && harness.session.isStarting
        }
        XCTAssertTrue(startSuspended)
        XCTAssertTrue(harness.session.canStop)

        let exitTask = Task { @MainActor in
            await harness.session.interruptForRouteExit()
        }
        let cancellationStarted = await eventually {
            harness.session.isPerformingAction
        }
        XCTAssertTrue(cancellationStarted)
        await repository.resumeBlockedCommit()

        let mayExit = await exitTask.value
        let didStart = await sendTask.value
        XCTAssertTrue(mayExit)
        XCTAssertFalse(didStart)
        XCTAssertFalse(harness.session.isStarting)
        XCTAssertFalse(harness.workspace.isPerforming)
        XCTAssertNil(harness.session.transientTail)

        let final = try await repository.loadProject(id: harness.projectID).document
        let run = try XCTUnwrap(final.activeRuns.first)
        XCTAssertEqual(run.status, .interrupted)
        XCTAssertEqual(run.interruptionReason, .routeExit)
        XCTAssertNil(final.branches[0].activeRunID)
        let requests = await harness.adapter.requests
        XCTAssertTrue(requests.isEmpty)
    }

    func testBackgroundDuringSuspendedStartClosesCrossedDurableRun() async throws {
        let repository = NovelSessionFailingRepository()
        let harness = try await makeHarness(
            repository: repository,
            scripts: [NovelModelScript(steps: [.pause])]
        )
        harness.session.mode = .discussPlan
        await repository.blockNextCommit()

        let sendTask = Task { @MainActor in
            await harness.session.send(text: "切到后台前仍在落盘")
        }
        let startSuspended = await eventually {
            await repository.commitIsBlocked() && harness.session.isStarting
        }
        XCTAssertTrue(startSuspended)

        let backgroundTask = Task { @MainActor in
            await harness.session.interruptForBackground(
                deadline: Date().addingTimeInterval(2)
            )
        }
        let cancellationStarted = await eventually {
            harness.session.isPerformingAction
        }
        XCTAssertTrue(cancellationStarted)
        await repository.resumeBlockedCommit()
        await backgroundTask.value
        let didStart = await sendTask.value
        XCTAssertFalse(didStart)

        let final = try await repository.loadProject(id: harness.projectID).document
        let run = try XCTUnwrap(final.activeRuns.first)
        XCTAssertEqual(run.status, .interrupted)
        XCTAssertEqual(run.interruptionReason, .background)
        XCTAssertNil(final.branches[0].activeRunID)
        XCTAssertNil(harness.session.transientTail)
        let requests = await harness.adapter.requests
        XCTAssertTrue(requests.isEmpty)
    }

    func testBackgroundBeforeActorStartCannotLeaveAHiddenRun() async throws {
        let harness = try await makeHarness(
            scripts: [NovelModelScript(steps: [.pause])],
            usesAttachGate: true
        )
        let gate = try XCTUnwrap(harness.attachGate)
        await gate.blockNextStart()
        let sendTask = Task { @MainActor in
            await harness.session.send(text: "在 actor 接收前切到后台")
        }
        let startBlocked = await eventually {
            await gate.startIsBlocked() && harness.session.isStarting
        }
        XCTAssertTrue(startBlocked)

        await harness.session.interruptForBackground(
            deadline: Date().addingTimeInterval(2)
        )
        await gate.resumeBlockedStart()
        let didStart = await sendTask.value

        XCTAssertFalse(didStart)
        XCTAssertFalse(harness.workspace.isPerforming)
        XCTAssertNil(harness.session.transientTail)
        let final = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertTrue(final.activeRuns.isEmpty)
        XCTAssertNil(final.branches[0].activeRunID)
        XCTAssertTrue(final.sessions[0].messages.isEmpty)
        let requests = await harness.adapter.requests
        XCTAssertTrue(requests.isEmpty)
    }

    func testPersistenceBlockedTailRetriesTheSameRun() async throws {
        let repository = NovelSessionFailingRepository()
        let harness = try await makeHarness(
            repository: repository,
            scripts: [NovelModelScript(steps: [.delta("等待落盘"), .pause, .complete])]
        )
        harness.session.mode = .discussPlan
        let didStart = await harness.session.send(text: "给我一个建议")
        XCTAssertTrue(didStart)
        let sawPartial = await eventually { harness.session.transientTail?.content == "等待落盘" }
        XCTAssertTrue(sawPartial)
        let runID = try XCTUnwrap(harness.session.activeRunID)

        await repository.failNextCommits(3)
        await harness.adapter.resume(runID: runID)
        let sawBlockedTerminal = await eventually { harness.session.canRetryPendingTerminal }
        XCTAssertTrue(sawBlockedTerminal)
        XCTAssertFalse(harness.session.canSend)
        let blockedTail = harness.session.transientTail
        let secondStart = await harness.session.send(text: "不应覆盖待保存回复")
        XCTAssertFalse(secondStart)
        XCTAssertEqual(harness.session.transientTail, blockedTail)
        let blockedError = harness.session.operationErrorMessage
        await harness.session.interruptForRouteExit()
        XCTAssertEqual(harness.session.operationErrorMessage, blockedError)
        guard case .persistenceBlocked = harness.session.transientTail?.phase else {
            return XCTFail("Route exit must preserve the persistence-blocked terminal claim.")
        }
        await harness.session.retryPendingTerminal()
        let didFinish = await eventually { !harness.session.isRunning }
        XCTAssertTrue(didFinish)
        XCTAssertTrue(harness.session.canSend)

        let final = try await repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(final.activeRuns.first { $0.id == runID }?.status, .completed)
        XCTAssertEqual(final.sessions[0].messages.last?.content, "等待落盘")
    }

    func testQuickStartTerminalBubbleRetriesThroughWorkspaceFlow() async throws {
        let retryableFailure = NovelModelFailure(
            code: "quick_start_failed",
            message: "快速开始暂时失败",
            isRetryable: true
        )
        let harness = try await makeHarness(
            document: try quickStartDocument(),
            scripts: [
                NovelModelScript(steps: [.fail(retryableFailure)]),
                NovelModelScript(steps: [.delta(quickStartSuggestionsJSON), .complete]),
            ]
        )

        await harness.workspace.startQuickStartSuggestions()
        let firstFailed = await eventually {
            harness.workspace.projectSnapshot?.activeRuns.last?.status == .failed
        }
        XCTAssertTrue(firstFailed)
        let failedRunID = try XCTUnwrap(harness.workspace.projectSnapshot?.activeRuns.last?.id)
        await harness.session.bindToCurrentSelection()

        let retried = await harness.session.retryGeneration(runID: failedRunID)
        XCTAssertTrue(retried)
        let completed = await eventually {
            harness.workspace.projectSnapshot?.settingProposals.count == 4 &&
                harness.workspace.projectSnapshot?.activeRuns.last?.status == .completed
        }
        XCTAssertTrue(completed)
        XCTAssertNotEqual(harness.workspace.projectSnapshot?.activeRuns.last?.id, failedRunID)
    }

    func testInitialPausedQuickStartRefreshesAndAttachesSession() async throws {
        let harness = try await makeHarness(
            document: try quickStartDocument(),
            scripts: [NovelModelScript(steps: [.pause])]
        )

        let startedRunID = await harness.workspace.startQuickStartSuggestions()
        let runID = try XCTUnwrap(startedRunID)
        await harness.session.bindToCurrentSelection()

        XCTAssertEqual(harness.session.activeRunID, runID)
        XCTAssertTrue(harness.session.canStop)
        XCTAssertNotNil(harness.session.transientTail)
        await harness.session.stop()
    }

    func testQuickStartPlaceholderHandsOffToDurableStreamingConsumer() async throws {
        let harness = try await makeHarness(
            document: try quickStartDocument(),
            scripts: [NovelModelScript(steps: [
                .pause,
                .delta(quickStartSuggestionsJSON),
                .pause,
                .complete,
            ])]
        )

        let startedRunID = await harness.workspace.startQuickStartSuggestions()
        let runID = try XCTUnwrap(startedRunID)
        await harness.session.bindToCurrentSelection()
        XCTAssertEqual(harness.session.activeRunID, runID)
        let becameDurable = await eventually {
            harness.workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.id == runID && $0.status == .running
            }) == true && harness.workspace.quickStartStartingRun == nil
        }
        XCTAssertTrue(becameDurable)
        harness.session.detachConsumer()
        let firstBind = Task { @MainActor in
            await harness.session.bindToCurrentSelection()
        }
        let secondBind = Task { @MainActor in
            await harness.session.bindToCurrentSelection()
        }
        await firstBind.value
        await secondBind.value

        await harness.adapter.resume(runID: runID)
        let receivedDelta = await eventually {
            harness.session.transientTail?.phase == .streaming
        }
        XCTAssertTrue(receivedDelta)
        XCTAssertEqual(harness.session.transientTail?.content, "")
        await harness.session.stop()
    }

    func testPreBindRouteExitCancelsSuspendedQuickStart() async throws {
        let repository = NovelSessionFailingRepository()
        let harness = try await makeHarness(
            repository: repository,
            document: try quickStartDocument(),
            scripts: [NovelModelScript(steps: [.pause])]
        )
        let unboundSession = NovelSessionViewModel(workspace: harness.workspace)
        await repository.blockNextCommit()
        let startedRunID = await harness.workspace.startQuickStartSuggestions()
        let runID = try XCTUnwrap(startedRunID)
        let commitBlocked = await eventually { await repository.commitIsBlocked() }
        XCTAssertTrue(commitBlocked)

        let exitTask = Task { @MainActor in
            await unboundSession.interruptForRouteExit()
        }
        let cancellationStarted = await eventually { unboundSession.isPerformingAction }
        XCTAssertTrue(cancellationStarted)
        await repository.resumeBlockedCommit()

        let mayExit = await exitTask.value
        XCTAssertTrue(mayExit)
        let final = try await repository.loadProject(id: harness.projectID).document
        let run = try XCTUnwrap(final.activeRuns.first { $0.id == runID })
        XCTAssertEqual(run.status, .interrupted)
        XCTAssertEqual(run.interruptionReason, .routeExit)
        XCTAssertNil(final.branches[0].activeRunID)
        XCTAssertNil(harness.workspace.quickStartStartingRun)
    }

    func testQuickStartPreDurableFailureClearsSessionPlaceholder() async throws {
        let failure = NovelModelFailure(
            code: "resolve_failed",
            message: "模型暂时不可用",
            isRetryable: true
        )
        let harness = try await makeHarness(
            document: try quickStartDocument(),
            scripts: [],
            resolutionFailure: failure
        )

        let startedRunID = await harness.workspace.startQuickStartSuggestions()
        let runID = try XCTUnwrap(startedRunID)
        await harness.session.bindToCurrentSelection()
        XCTAssertEqual(harness.session.activeRunID, runID)
        let failed = await eventually {
            if case .failed = harness.workspace.quickStartStatus { return true }
            return false
        }
        XCTAssertTrue(failed)
        await harness.session.bindToCurrentSelection()
        XCTAssertNil(harness.session.transientTail)
        XCTAssertFalse(harness.session.isRunning)
        XCTAssertTrue(harness.workspace.projectSnapshot?.activeRuns.isEmpty == true)
    }

    func testQuickStartReloadAfterStartRefreshFailureCanAttachPausedRun() async throws {
        let harness = try await makeHarness(
            document: try quickStartDocument(),
            scripts: [NovelModelScript(steps: [.pause])],
            usesSnapshotGate: true
        )
        let gate = try XCTUnwrap(harness.snapshotGate)
        await gate.failNextSnapshots(2)

        let startedRunID = await harness.workspace.startQuickStartSuggestions()
        let runID = try XCTUnwrap(startedRunID)
        let refreshFailed = await eventually {
            if case .refreshFailed = harness.workspace.quickStartStatus { return true }
            return false
        }
        XCTAssertTrue(refreshFailed)
        XCTAssertFalse(harness.workspace.projectSnapshot?.activeRuns.contains(where: {
            $0.id == runID && $0.status == .running
        }) == true)

        await harness.workspace.reloadQuickStartProject()
        await harness.session.bindToCurrentSelection()
        XCTAssertEqual(harness.session.activeRunID, runID)
        XCTAssertTrue(harness.session.canStop)
        XCTAssertNotNil(harness.session.transientTail)
        await harness.session.stop()
    }

    func testQuickStartStopReleasesBusyStateWhenTerminalRefreshFails() async throws {
        let harness = try await makeHarness(
            document: try quickStartDocument(),
            scripts: [NovelModelScript(steps: [.pause])],
            usesSnapshotGate: true
        )
        let startedRunID = await harness.workspace.startQuickStartSuggestions()
        let runID = try XCTUnwrap(startedRunID)
        let becameDurable = await eventually {
            harness.workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.id == runID && $0.status == .running
            }) == true
        }
        XCTAssertTrue(becameDurable)
        await harness.session.bindToCurrentSelection()
        let gate = try XCTUnwrap(harness.snapshotGate)
        await gate.failNextSnapshots(10)

        await harness.session.stop()
        XCTAssertFalse(harness.workspace.isPerforming)
        XCTAssertNil(harness.workspace.quickStartStartingRun)
        guard case .refreshFailed = harness.workspace.quickStartStatus else {
            return XCTFail("A failed terminal refresh must leave reload reachable.")
        }
    }

    func testStaleQuickStartInterruptReconcileCannotClearANewerOwner() async throws {
        let harness = try await makeHarness(
            document: try quickStartDocument(),
            scripts: [
                NovelModelScript(steps: [.pause]),
                NovelModelScript(steps: [
                    .delta(quickStartSuggestionsJSON),
                    .pause,
                    .complete,
                ]),
            ],
            usesSnapshotGate: true
        )
        let gate = try XCTUnwrap(harness.snapshotGate)
        let firstStartedRunID = await harness.workspace.startQuickStartSuggestions()
        let firstRunID = try XCTUnwrap(firstStartedRunID)
        let firstBecameDurable = await eventually {
            harness.workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.id == firstRunID && $0.status == .running
            }) == true
        }
        XCTAssertTrue(firstBecameDurable)

        await gate.blockInterruptReturn()
        let oldInterrupt = Task { @MainActor () -> Error? in
            do {
                try await harness.workspace.interruptSessionRun(NovelCancelRunCommand(
                    context: NovelMutationContext(
                        operationID: NovelOperationID(),
                        expectedProjectRevision: nil,
                        expectedConfigRevision: nil,
                        expectedBranchHeadRevision: nil
                    ),
                    projectID: harness.projectID,
                    runID: firstRunID,
                    reason: .user
                ))
                return nil
            } catch {
                return error
            }
        }
        let interruptReturnedFromBase = await eventually {
            await gate.interruptReturnIsBlocked()
        }
        XCTAssertTrue(interruptReturnedFromBase)
        try await harness.workspace.refreshCurrentSelection(projectID: harness.projectID)
        XCTAssertTrue(harness.workspace.projectSnapshot?.activeRuns.contains(where: {
            $0.id == firstRunID && $0.status == .interrupted
        }) == true)

        await gate.blockNextSnapshot()
        await gate.resumeBlockedInterruptReturn()
        let oldRefreshBlocked = await eventually {
            await gate.snapshotIsBlocked()
        }
        XCTAssertTrue(oldRefreshBlocked)

        let secondStartedRunID = await harness.workspace.startQuickStartSuggestions()
        let secondRunID = try XCTUnwrap(secondStartedRunID)
        let secondBecameDurable = await eventually {
            harness.workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.id == secondRunID && $0.status == .running
            }) == true
        }
        XCTAssertTrue(secondBecameDurable)

        await gate.resumeBlockedSnapshot()
        let oldInterruptError = await oldInterrupt.value
        XCTAssertNil(oldInterruptError)
        XCTAssertEqual(harness.workspace.projectSnapshot?.branches.first?.activeRunID, secondRunID)
        guard case .generating(let ownerID) = harness.workspace.quickStartStatus else {
            return XCTFail("The newer Quick Start must remain the active owner.")
        }
        XCTAssertEqual(ownerID, secondRunID)

        await harness.adapter.resume(runID: secondRunID)
        let secondCompleted = await eventually {
            harness.workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.id == secondRunID && $0.status == .completed
            }) == true && harness.workspace.projectSnapshot?.settingProposals.count == 4
        }
        XCTAssertTrue(secondCompleted)
    }

    func testQuickStartTerminalCleanupDoesNotReleaseAnotherMutationBusyState() async throws {
        let harness = try await makeHarness(
            document: try quickStartDocument(),
            scripts: [NovelModelScript(steps: [
                .delta(quickStartSuggestionsJSON),
                .pause,
                .complete,
            ])],
            usesSnapshotGate: true
        )
        let gate = try XCTUnwrap(harness.snapshotGate)
        let startedRunID = await harness.workspace.startQuickStartSuggestions()
        let runID = try XCTUnwrap(startedRunID)
        let becameDurable = await eventually {
            harness.workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.id == runID && $0.status == .running
            }) == true
        }
        XCTAssertTrue(becameDurable)

        await gate.blockNextSnapshot()
        await harness.adapter.resume(runID: runID)
        let terminalRefreshBlocked = await eventually {
            await gate.snapshotIsBlocked()
        }
        XCTAssertTrue(terminalRefreshBlocked)

        await gate.blockNextPerform()
        let renameTask = Task { @MainActor in
            await harness.workspace.renameProject("并行改名")
        }
        let renameBlocked = await eventually {
            await gate.performIsBlocked()
        }
        XCTAssertTrue(renameBlocked)
        XCTAssertTrue(harness.workspace.isPerforming)

        await gate.resumeBlockedSnapshot()
        let quickStartFinished = await eventually {
            harness.workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.id == runID && $0.status == .completed
            }) == true
        }
        XCTAssertTrue(quickStartFinished)
        XCTAssertTrue(
            harness.workspace.isPerforming,
            "Quick Start cleanup must not release a newer mutation's busy state."
        )

        await gate.resumeBlockedPerform()
        await renameTask.value
        XCTAssertFalse(harness.workspace.isPerforming)
    }

    func testTerminalRefreshFailureKeepsFinalContentWithoutPretendingToStream() async throws {
        let harness = try await makeHarness(
            scripts: [NovelModelScript(steps: [.delta("已经完成的正文"), .pause, .complete])],
            usesSnapshotGate: true
        )
        harness.session.mode = .writeProse
        let didStart = await harness.session.send(text: "写一个片段")
        XCTAssertTrue(didStart)
        let sawPartial = await eventually { harness.session.transientTail?.content == "已经完成的正文" }
        XCTAssertTrue(sawPartial)
        let runID = try XCTUnwrap(harness.session.activeRunID)
        let gate = try XCTUnwrap(harness.snapshotGate)

        await gate.failNextSnapshots(1)
        await harness.adapter.resume(runID: runID)
        let keptTerminalTail = await eventually {
            harness.session.refreshErrorMessage != nil && !harness.session.isRunning
        }
        XCTAssertTrue(keptTerminalTail)
        XCTAssertEqual(harness.session.transientTail?.content, "已经完成的正文")
        XCTAssertEqual(harness.session.transientTail?.phase, .terminalAwaitingRefresh)
        XCTAssertNil(harness.session.activeRunID)
        XCTAssertFalse(harness.session.canStop)

        let didRefresh = await harness.session.refresh()
        XCTAssertTrue(didRefresh)
        XCTAssertNil(harness.session.transientTail)
        XCTAssertEqual(harness.session.durableMessages.last?.content, "已经完成的正文")
    }

    func testSelectedStableParagraphCollectsAndCommitsFacts() async throws {
        let prose = "Mara opened the archive.\n\nShe found a map."
        let harness = try await makeHarness(scripts: [
            NovelModelScript(steps: [.delta(prose), .complete]),
            NovelModelScript(steps: [.delta(validDeltaJSON), .complete]),
        ])
        harness.session.mode = .writeProse
        harness.session.granularity = .continuation
        let didStart = await harness.session.send(text: "续写档案馆")
        XCTAssertTrue(didStart)
        let sawCandidate = await eventually { !harness.session.availableProseCandidates.isEmpty }
        XCTAssertTrue(sawCandidate)
        let candidate = try XCTUnwrap(harness.session.availableProseCandidates.first)
        let paragraphs = harness.session.paragraphs(candidateID: candidate.id)
        XCTAssertEqual(paragraphs.count, 2)

        let collected = await harness.session.collectCandidate(
            candidate.id,
            selection: NovelParagraphSelection(paragraphIDs: [paragraphs[0].id]),
            target: .createNextChapter(chapterID: NovelChapterID(), title: "第一章")
        )
        XCTAssertTrue(collected)

        let final = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(final.chapterVersions.last?.content, paragraphs[0].text)
        XCTAssertEqual(final.candidates.first { $0.id == candidate.id }?.status, .collected)
        XCTAssertTrue(final.pendingOperations.isEmpty)
        XCTAssertEqual(final.checkpoints.last?.kind, .collection)

        await harness.workspace.undoBranchHead()
        await harness.session.bindToCurrentSelection()
        let clonedCandidateID = await harness.session.cloneCollectedProse(candidate.id)
        let clonedID = try XCTUnwrap(clonedCandidateID)
        var clonedDocument = try await harness.repository.loadProject(id: harness.projectID).document
        clonedDocument.project.lastGenerationGranularity = .wholeChapter
        harness.workspace.projectSnapshot = NovelProjectSnapshot(loaded: NovelLoadedProject(
            document: clonedDocument,
            access: .readWrite
        ))

        XCTAssertEqual(harness.session.collectionGranularity(for: clonedID), .continuation)
    }

    func testExactRunRetryDoesNotRetryAStillNewerTerminalBubble() async throws {
        let retryableFailure = NovelModelFailure(
            code: "first_failed",
            message: "第一次失败",
            isRetryable: true
        )
        let harness = try await makeHarness(scripts: [
            NovelModelScript(steps: [.fail(retryableFailure)]),
            NovelModelScript(steps: [.delta("第二次也中断"), .pause]),
            NovelModelScript(steps: [.delta("只重试第一条"), .complete]),
        ])
        harness.session.mode = .discussPlan
        let firstStarted = await harness.session.send(text: "第一条")
        XCTAssertTrue(firstStarted)
        let firstFinished = await eventually { !harness.session.isRunning }
        XCTAssertTrue(firstFinished)
        let firstRunID = try XCTUnwrap(harness.workspace.projectSnapshot?.activeRuns.first?.id)

        let secondStarted = await harness.session.send(text: "第二条")
        XCTAssertTrue(secondStarted)
        let sawSecondPartial = await eventually {
            harness.session.transientTail?.content == "第二次也中断"
        }
        XCTAssertTrue(sawSecondPartial)
        await harness.session.stop()
        let secondFinished = await eventually { !harness.session.isRunning }
        XCTAssertTrue(secondFinished)
        let secondRunID = try XCTUnwrap(harness.workspace.projectSnapshot?.activeRuns.last?.id)

        let didRetryFirst = await harness.session.retryGeneration(runID: firstRunID)
        XCTAssertTrue(didRetryFirst)
        let retryFinished = await eventually { !harness.session.isRunning }
        XCTAssertTrue(retryFinished)
        let final = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(final.sessions[0].messages.last?.content, "只重试第一条")
        XCTAssertEqual(final.sessions[0].messages.last?.runID, final.activeRuns.last?.id)
        XCTAssertNotEqual(final.activeRuns.last?.id, secondRunID)
        let inputs = final.sessions[0].messages.filter { $0.role == .user }.map(\.content)
        XCTAssertEqual(inputs, ["第一条", "第二条", "第一条"])
    }

    func testProseRetryFailsClosedAfterBranchHeadMoves() async throws {
        let failure = NovelModelFailure(code: "retryable", message: "暂时失败", isRetryable: true)
        let harness = try await makeHarness(
            document: try NovelTestFixtures.documentWithForkableCheckpoint(),
            scripts: [
                NovelModelScript(steps: [.fail(failure)]),
                NovelModelScript(steps: [.delta("不应生成"), .complete]),
            ]
        )
        harness.session.mode = .writeProse
        let started = await harness.session.send(text: "续写")
        XCTAssertTrue(started)
        let finished = await eventually { !harness.session.isRunning }
        XCTAssertTrue(finished)
        let runID = try XCTUnwrap(harness.workspace.projectSnapshot?.activeRuns.last?.id)

        await harness.workspace.undoBranchHead()
        XCTAssertFalse(harness.session.canRetryLastTerminal)
        let retried = await harness.session.retryGeneration(runID: runID)
        let bannerRetried = await harness.session.retryLastTerminal()
        let requests = await harness.adapter.requests
        XCTAssertFalse(retried)
        XCTAssertFalse(bannerRetried)
        XCTAssertEqual(requests.count, 1)
    }

    func testDiscussionRetryRemainsAllowedAfterBranchHeadMoves() async throws {
        let failure = NovelModelFailure(code: "retryable", message: "暂时失败", isRetryable: true)
        let harness = try await makeHarness(
            document: try NovelTestFixtures.documentWithForkableCheckpoint(),
            scripts: [
                NovelModelScript(steps: [.fail(failure)]),
                NovelModelScript(steps: [.delta("新 head 上的讨论"), .complete]),
            ]
        )
        harness.session.mode = .discussPlan
        let started = await harness.session.send(text: "讨论一下")
        XCTAssertTrue(started)
        let firstFinished = await eventually { !harness.session.isRunning }
        XCTAssertTrue(firstFinished)
        let runID = try XCTUnwrap(harness.workspace.projectSnapshot?.activeRuns.last?.id)

        await harness.workspace.undoBranchHead()
        let retried = await harness.session.retryGeneration(runID: runID)
        XCTAssertTrue(retried)
        let retryFinished = await eventually {
            harness.session.durableMessages.last?.content == "新 head 上的讨论"
        }
        XCTAssertTrue(retryFinished)
        let requests = await harness.adapter.requests
        XCTAssertEqual(requests.count, 2)
    }

    func testPolishRetryFailsClosedAfterSourceChapterChanges() async throws {
        let fixture = try documentWithChapter()
        let failure = NovelModelFailure(code: "retryable", message: "暂时失败", isRetryable: true)
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [
                NovelModelScript(steps: [.fail(failure)]),
                NovelModelScript(steps: [.delta("不应润色"), .complete]),
            ]
        )
        let started = await harness.session.startWholeChapterPolish(chapterID: fixture.chapterID)
        XCTAssertTrue(started)
        let finished = await eventually { !harness.session.isRunning }
        XCTAssertTrue(finished)
        let runID = try XCTUnwrap(harness.workspace.projectSnapshot?.activeRuns.last?.id)

        let saved = await harness.workspace.saveManualRewrite(
            chapterID: fixture.chapterID,
            title: "第一章",
            content: "剧情已被手动改写。"
        )
        XCTAssertTrue(saved)
        let retried = await harness.session.retryGeneration(runID: runID)
        let requests = await harness.adapter.requests
        XCTAssertFalse(retried)
        XCTAssertEqual(requests.count, 1)
    }

    func testWholeChapterPolishAdoptsCompatibleCandidate() async throws {
        let fixture = try documentWithChapter()
        let polished = "Mara crossed the quiet hall."
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [
                NovelModelScript(steps: [
                    .delta("\(polished)\n\(NovelPromptCatalog.polishCompletionSentinel)"),
                    .complete,
                ]),
                NovelModelScript(steps: [.delta(compatibleDriftJSON), .complete]),
            ]
        )
        let baselineState = harness.workspace.branchSnapshot?.currentState

        let didStart = await harness.session.startWholeChapterPolish(chapterID: fixture.chapterID)
        XCTAssertTrue(didStart)
        let sawCandidate = await eventually { !harness.session.availablePolishCandidates.isEmpty }
        XCTAssertTrue(sawCandidate)
        let candidate = try XCTUnwrap(harness.session.availablePolishCandidates.first)
        await harness.session.adoptPolishCandidate(candidate.id)

        let final = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(final.candidates.first { $0.id == candidate.id }?.status, .adopted)
        XCTAssertEqual(
            final.polishTransactions.first { $0.candidateID == candidate.id }?.status,
            .completed
        )
        XCTAssertEqual(final.chapterVersions.last?.kind, .polish)
        XCTAssertEqual(final.chapterVersions.last?.content, polished)
        XCTAssertEqual(final.chapterVersions.last?.sourceCandidateID, candidate.id)
        XCTAssertEqual(harness.workspace.branchSnapshot?.currentState, baselineState)
    }

    func testIncompatiblePolishCanConvertToManualRewriteAndNeedsSync() async throws {
        let fixture = try documentWithChapter()
        let rewritten = "Mara opened the gate and changed the plot."
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [
                NovelModelScript(steps: [
                    .delta("\(rewritten)\n\(NovelPromptCatalog.polishCompletionSentinel)"),
                    .complete,
                ]),
                NovelModelScript(steps: [.delta(incompatibleDriftJSON), .complete]),
            ],
            usesSnapshotGate: true
        )

        let didStart = await harness.session.startWholeChapterPolish(chapterID: fixture.chapterID)
        XCTAssertTrue(didStart)
        let sawCandidate = await eventually { !harness.session.availablePolishCandidates.isEmpty }
        XCTAssertTrue(sawCandidate)
        let candidate = try XCTUnwrap(harness.session.availablePolishCandidates.first)
        await harness.session.adoptPolishCandidate(candidate.id)
        let beforeConversion = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(
            beforeConversion.candidates.first { $0.id == candidate.id }?.status,
            .superseded
        )
        XCTAssertEqual(
            beforeConversion.polishTransactions.first { $0.candidateID == candidate.id }?.status,
            .incompatible
        )
        let manualVersionCount = beforeConversion.chapterVersions.filter { $0.kind == .manualEdit }.count
        await harness.snapshotGate?.failNextBranchSnapshots(2)
        let converted = await harness.session.convertPolishCandidateToManualRewrite(candidate.id)
        XCTAssertTrue(converted)

        let final = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(final.branches[0].syncStatus, .needsSync)
        XCTAssertEqual(final.chapterVersions.last?.kind, .manualEdit)
        XCTAssertEqual(final.chapterVersions.last?.content, rewritten)
        XCTAssertEqual(
            final.chapterVersions.filter { $0.kind == .manualEdit }.count,
            manualVersionCount + 1
        )
        XCTAssertNil(harness.workspace.errorMessage)
        XCTAssertNotNil(harness.workspace.reloadNoticeMessage)
        XCTAssertTrue(harness.workspace.requiresReload)
        await harness.workspace.retryCommittedMutationReload()
        XCTAssertFalse(harness.workspace.requiresReload)
        let afterReload = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(
            afterReload.chapterVersions.filter { $0.kind == .manualEdit }.count,
            manualVersionCount + 1
        )
    }
}

private extension NovelSessionViewModelTests {
    struct Harness {
        let repository: any NovelProjectPersisting
        let adapter: ScriptedNovelModelAdapter
        let workspace: NovelCreationViewModel
        let session: NovelSessionViewModel
        let projectID: NovelProjectID
        let snapshotGate: NovelSessionSnapshotFailingCreation?
        let attachGate: NovelSessionAttachBlockingCreation?
    }

    func makeHarness(
        repository: (any NovelProjectPersisting)? = nil,
        document: NovelProjectDocumentV1? = nil,
        scripts: [NovelModelScript],
        resolutionFailure: NovelModelFailure? = nil,
        usesSnapshotGate: Bool = false,
        usesAttachGate: Bool = false
    ) async throws -> Harness {
        let document = try document ?? NovelTestFixtures.document()
        let repository = repository ?? InMemoryNovelProjectRepository()
        _ = try await repository.createProject(document)
        let adapter = ScriptedNovelModelAdapter(
            resolvedModel: NovelResolvedModel(
                providerID: "session-provider",
                ownerProviderID: "session-owner",
                modelID: "session-model",
                wireModelID: "session-wire",
                displayName: "Session Model",
                contextWindowTokens: 128_000
            ),
            resolutionFailure: resolutionFailure,
            scripts: scripts
        )
        let baseCreation = DefaultNovelCreation(
            repository: repository,
            modelRunner: adapter,
            now: { Date(timeIntervalSince1970: 1_700_800_000) }
        )
        let snapshotGate = usesSnapshotGate
            ? NovelSessionSnapshotFailingCreation(base: baseCreation)
            : nil
        let attachGate = usesAttachGate
            ? NovelSessionAttachBlockingCreation(base: baseCreation)
            : nil
        let creation: any NovelCreation
        if let snapshotGate {
            creation = snapshotGate
        } else if let attachGate {
            creation = attachGate
        } else {
            creation = baseCreation
        }
        let workspace = NovelCreationViewModel(creation: creation)
        await workspace.loadProjects(selecting: document.project.id)
        let session = NovelSessionViewModel(workspace: workspace)
        await session.bindToCurrentSelection()
        return Harness(
            repository: repository,
            adapter: adapter,
            workspace: workspace,
            session: session,
            projectID: document.project.id,
            snapshotGate: snapshotGate,
            attachGate: attachGate
        )
    }

    func eventually(
        timeout: TimeInterval = 2,
        condition: @MainActor () async -> Bool
    ) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if await condition() { return true }
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
        return await condition()
    }

    func documentWithChapter() throws -> (
        document: NovelProjectDocumentV1,
        chapterID: NovelChapterID
    ) {
        var document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let branch = document.branches[0]
        let chapterID = NovelChapterID()
        let versionID = NovelChapterVersionID()
        document.chapters.append(NovelChapterRecord(id: chapterID, createdAt: document.project.updatedAt))
        document.chapterVersions.append(NovelChapterVersionRecord(
            id: versionID,
            chapterID: chapterID,
            kind: .collected,
            title: "第一章",
            content: "Mara crossed the hall. The gate stayed closed.",
            factCompatibilityID: UUID(),
            sourceCandidateID: nil,
            createdAt: document.project.updatedAt,
            operationID: document.appliedOperations[0].operationID
        ))
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
        return (document, chapterID)
    }

    func documentWithMaterials() throws -> (
        document: NovelProjectDocumentV1,
        materialIDs: [NovelMaterialID]
    ) {
        var document = try NovelTestFixtures.document()
        let materialIDs = [NovelMaterialID(), NovelMaterialID()]
        for (index, materialID) in materialIDs.enumerated() {
            document = try NovelReducer.apply(
                NovelTestFixtures.materialAction(
                    document: document,
                    materialID: materialID,
                    revisionID: NovelMaterialRevisionID(),
                    title: "资料 \(index + 1)",
                    content: "设定内容 \(index + 1)"
                ),
                to: document
            ).document
        }
        return (document, materialIDs)
    }

    func quickStartDocument() throws -> NovelProjectDocumentV1 {
        try NovelReducer.createProject(NovelCreateProjectCommand(
            context: NovelTestFixtures.context(operationID: NovelOperationID()),
            projectID: NovelProjectID(),
            branchID: NovelBranchID(),
            sessionID: NovelSessionID(),
            initialStateSnapshotID: NovelStateSnapshotID(),
            initialCheckpointID: NovelCheckpointID(),
            name: "快速开始",
            branchName: "主线",
            creationMode: .quickStart,
            quickStartSeed: NovelQuickStartSeed(genre: "悬疑", coreIdea: "记忆可以作证")
        ), now: Date(timeIntervalSince1970: 1_700_000_000)).document
    }

    var quickStartSuggestionsJSON: String {
        """
        {
          "schemaVersion": 1,
          "overview": "一座会保存证词记忆的城市。",
          "world": {"title": "记忆城", "content": "记忆可以被封存并出庭作证。"},
          "characters": {"title": "人物", "content": "调查员林遥追查一段伪造记忆。"},
          "masterOutline": {"title": "总纲", "content": "林遥逐步发现城市证词系统被篡改。"},
          "writingRequirements": {"title": "写作要求", "content": "克制、悬疑，保持线索公平。"}
        }
        """
    }

    var validDeltaJSON: String {
        """
        {
          "schemaVersion": 1,
          "stateSummary": "Mara entered the archive.",
          "events": [{
            "id": "archive-opened",
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

    var compatibleDriftJSON: String {
        """
        {"schemaVersion":1,"compatible":true,"differences":[]}
        """
    }

    var incompatibleDriftJSON: String {
        """
        {
          "schemaVersion": 1,
          "compatible": false,
          "differences": [{
            "id": "gate-opened",
            "category": "event",
            "summary": "The gate opened.",
            "sourceEvidence": "The gate stayed closed.",
            "candidateEvidence": "Mara opened the gate."
          }]
        }
        """
    }
}

private actor NovelSessionSnapshotFailingCreation: NovelCreation {
    private let base: any NovelCreation
    private var remainingSnapshotFailures = 0
    private var remainingBranchSnapshotFailures = 0
    private var remainingProjectSnapshotFailures = 0
    private var shouldBlockNextSnapshot = false
    private var blockedSnapshotContinuation: CheckedContinuation<Void, Never>?
    private var shouldBlockNextPerform = false
    private var blockedPerformContinuation: CheckedContinuation<Void, Never>?
    private var shouldBlockInterruptReturn = false
    private var blockedInterruptContinuation: CheckedContinuation<Void, Never>?

    init(base: any NovelCreation) {
        self.base = base
    }

    func failNextSnapshots(_ count: Int) {
        remainingSnapshotFailures = count
    }

    func failNextBranchSnapshots(_ count: Int) {
        remainingBranchSnapshotFailures = count
    }

    func failNextProjectSnapshots(_ count: Int) {
        remainingProjectSnapshotFailures = count
    }

    func blockNextSnapshot() {
        shouldBlockNextSnapshot = true
    }

    func snapshotIsBlocked() -> Bool {
        blockedSnapshotContinuation != nil
    }

    func resumeBlockedSnapshot() {
        let continuation = blockedSnapshotContinuation
        blockedSnapshotContinuation = nil
        continuation?.resume()
    }

    func blockNextPerform() {
        shouldBlockNextPerform = true
    }

    func performIsBlocked() -> Bool {
        blockedPerformContinuation != nil
    }

    func resumeBlockedPerform() {
        let continuation = blockedPerformContinuation
        blockedPerformContinuation = nil
        continuation?.resume()
    }

    func blockInterruptReturn() {
        shouldBlockInterruptReturn = true
    }

    func interruptReturnIsBlocked() -> Bool {
        blockedInterruptContinuation != nil
    }

    func resumeBlockedInterruptReturn() {
        let continuation = blockedInterruptContinuation
        blockedInterruptContinuation = nil
        continuation?.resume()
    }

    func snapshot(_ scope: NovelSnapshotScope) async throws -> NovelSnapshot {
        if shouldBlockNextSnapshot {
            shouldBlockNextSnapshot = false
            await withCheckedContinuation { continuation in
                blockedSnapshotContinuation = continuation
            }
        }
        if case .branch = scope, remainingBranchSnapshotFailures > 0 {
            remainingBranchSnapshotFailures -= 1
            throw NovelError.repositoryFailure("Injected branch snapshot refresh failure.")
        }
        if case .project = scope, remainingProjectSnapshotFailures > 0 {
            remainingProjectSnapshotFailures -= 1
            throw NovelError.repositoryFailure("Injected project snapshot refresh failure.")
        }
        if remainingSnapshotFailures > 0 {
            remainingSnapshotFailures -= 1
            throw NovelError.repositoryFailure("Injected snapshot refresh failure.")
        }
        return try await base.snapshot(scope)
    }

    func perform(_ action: NovelAction) async throws -> NovelOutcome {
        if shouldBlockNextPerform {
            shouldBlockNextPerform = false
            await withCheckedContinuation { continuation in
                blockedPerformContinuation = continuation
            }
        }
        return try await base.perform(action)
    }

    func start(_ request: NovelRunRequest) async throws -> NovelRun {
        try await base.start(request)
    }

    func interruptRun(_ command: NovelCancelRunCommand) async throws {
        try await base.interruptRun(command)
        if shouldBlockInterruptReturn {
            shouldBlockInterruptReturn = false
            await withCheckedContinuation { continuation in
                blockedInterruptContinuation = continuation
            }
        }
    }

    func interruptForBackground(
        projectID: NovelProjectID,
        deadline: Date,
        runID: NovelRunID?
    ) async {
        await base.interruptForBackground(
            projectID: projectID,
            deadline: deadline,
            runID: runID
        )
    }

    func retryPendingTerminal(runID: NovelRunID) async throws {
        try await base.retryPendingTerminal(runID: runID)
    }
}

private actor NovelSessionAttachBlockingCreation: NovelCreation {
    private let base: any NovelCreation
    private var shouldBlockNextStart = false
    private var blockedStartContinuation: CheckedContinuation<Void, Never>?

    init(base: any NovelCreation) {
        self.base = base
    }

    func blockNextStart() {
        shouldBlockNextStart = true
    }

    func startIsBlocked() -> Bool {
        blockedStartContinuation != nil
    }

    func resumeBlockedStart() {
        let continuation = blockedStartContinuation
        blockedStartContinuation = nil
        continuation?.resume()
    }

    func snapshot(_ scope: NovelSnapshotScope) async throws -> NovelSnapshot {
        try await base.snapshot(scope)
    }

    func perform(_ action: NovelAction) async throws -> NovelOutcome {
        try await base.perform(action)
    }

    func start(_ request: NovelRunRequest) async throws -> NovelRun {
        if shouldBlockNextStart {
            shouldBlockNextStart = false
            await withCheckedContinuation { continuation in
                blockedStartContinuation = continuation
            }
        }
        return try await base.start(request)
    }

    func interruptRun(_ command: NovelCancelRunCommand) async throws {
        try await base.interruptRun(command)
    }

    func interruptForBackground(
        projectID: NovelProjectID,
        deadline: Date,
        runID: NovelRunID?
    ) async {
        await base.interruptForBackground(
            projectID: projectID,
            deadline: deadline,
            runID: runID
        )
    }

    func retryPendingTerminal(runID: NovelRunID) async throws {
        try await base.retryPendingTerminal(runID: runID)
    }
}

private actor NovelSessionFailingRepository: NovelProjectPersisting {
    private let base = InMemoryNovelProjectRepository()
    private var remainingCommitFailures = 0
    private var shouldBlockNextCommit = false
    private var blockedCommitContinuation: CheckedContinuation<Void, Never>?

    func failNextCommits(_ count: Int) {
        remainingCommitFailures = count
    }

    func blockNextCommit() {
        shouldBlockNextCommit = true
    }

    func commitIsBlocked() -> Bool {
        blockedCommitContinuation != nil
    }

    func resumeBlockedCommit() {
        let continuation = blockedCommitContinuation
        blockedCommitContinuation = nil
        continuation?.resume()
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
        if shouldBlockNextCommit {
            shouldBlockNextCommit = false
            await withCheckedContinuation { continuation in
                blockedCommitContinuation = continuation
            }
        }
        if remainingCommitFailures > 0 {
            remainingCommitFailures -= 1
            throw NovelError.repositoryFailure("Injected session terminal failure.")
        }
        return try await base.commitProject(
            document,
            expectedRevision: expectedRevision,
            authorization: authorization
        )
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
