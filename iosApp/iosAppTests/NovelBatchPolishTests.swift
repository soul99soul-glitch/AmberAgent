import XCTest
@testable import iosApp

/// 批量整章润色(`NovelSessionViewModel.startBatchPolish`)的契约测试。
///
/// 复用与 `NovelSessionViewModelTests` 相同的「脚本化 provider + 内存仓库 + 真实
/// workspace/session」组装,只是 fixture 扩成多章,并注入小的等待宽限窗加快失败用例。
/// 每章在批量中走「生成候选 → 采用(含漂移校验)」两次模型调用,脚本按 FIFO 消费。
@MainActor
final class NovelBatchPolishTests: XCTestCase {
    struct Harness {
        let repository: any NovelProjectPersisting
        let adapter: ScriptedNovelModelAdapter
        let workspace: NovelCreationViewModel
        let session: NovelSessionViewModel
        let projectID: NovelProjectID
    }

    // MARK: - 组装

    func makeHarness(
        document: NovelProjectDocumentV1,
        scripts: [NovelModelScript],
        batchPolishSettleGrace: TimeInterval = 0.2,
        batchPolishCandidateTimeout: TimeInterval = 10
    ) async throws -> Harness {
        let repository = InMemoryNovelProjectRepository()
        _ = try await repository.createProject(document)
        let adapter = ScriptedNovelModelAdapter(
            resolvedModel: NovelResolvedModel(
                providerID: "batch-provider",
                ownerProviderID: "batch-owner",
                modelID: "batch-model",
                wireModelID: "batch-wire",
                displayName: "Batch Model",
                contextWindowTokens: 128_000
            ),
            scripts: scripts
        )
        let creation = DefaultNovelCreation(
            repository: repository,
            modelRunner: adapter,
            now: { Date(timeIntervalSince1970: 1_700_800_000) }
        )
        let workspace = NovelCreationViewModel(creation: creation)
        await workspace.loadProjects(selecting: document.project.id)
        let session = NovelSessionViewModel(
            workspace: workspace,
            terminalQuietDelay: 0,
            batchPolishSettleGrace: batchPolishSettleGrace,
            batchPolishCandidateTimeout: batchPolishCandidateTimeout
        )
        await session.bindToCurrentSelection()
        return Harness(
            repository: repository,
            adapter: adapter,
            workspace: workspace,
            session: session,
            projectID: document.project.id
        )
    }

    func documentWithChapters(_ count: Int) throws -> (
        document: NovelProjectDocumentV1,
        chapterIDs: [NovelChapterID]
    ) {
        var document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let branch = document.branches[0]
        let operationID = document.appliedOperations[0].operationID
        var chapterIDs: [NovelChapterID] = []
        var selections: [NovelChapterSelection] = []
        for index in 0..<count {
            let chapterID = NovelChapterID()
            let versionID = NovelChapterVersionID()
            chapterIDs.append(chapterID)
            document.chapters.append(NovelChapterRecord(
                id: chapterID,
                createdAt: document.project.updatedAt
            ))
            document.chapterVersions.append(NovelChapterVersionRecord(
                id: versionID,
                chapterID: chapterID,
                kind: .collected,
                title: "第\(index + 1)章",
                content: "Chapter \(index + 1) original. The gate stayed closed.",
                factCompatibilityID: UUID(),
                sourceCandidateID: nil,
                createdAt: document.project.updatedAt,
                operationID: operationID
            ))
            selections.append(NovelChapterSelection(chapterID: chapterID, versionID: versionID))
        }
        let checkpointIndex = try XCTUnwrap(document.checkpoints.firstIndex {
            $0.id == branch.headCheckpointID
        })
        let checkpoint = document.checkpoints[checkpointIndex]
        document.checkpoints[checkpointIndex] = NovelBranchCheckpointRecord(
            id: checkpoint.id,
            kind: checkpoint.kind,
            createdOnBranchID: checkpoint.createdOnBranchID,
            parentCheckpointID: checkpoint.parentCheckpointID,
            chapterSelections: selections,
            stateSnapshotID: checkpoint.stateSnapshotID,
            sessionCursor: checkpoint.sessionCursor,
            branchOverrideRevisionIDs: checkpoint.branchOverrideRevisionIDs,
            sourceCandidateID: checkpoint.sourceCandidateID,
            baseHeadRevision: checkpoint.baseHeadRevision,
            operationID: checkpoint.operationID,
            createdAt: checkpoint.createdAt
        )
        document.branches[0].workingChapterSelections = selections
        try NovelDocumentValidator.validate(document)
        return (document, chapterIDs)
    }

    func polishGenScript(_ content: String) -> NovelModelScript {
        NovelModelScript(steps: [
            .delta("\(content)\n\(NovelPromptCatalog.polishCompletionSentinel)"),
            .complete,
        ])
    }

    func driftScript(_ json: String) -> NovelModelScript {
        NovelModelScript(steps: [.delta(json), .complete])
    }

    func eventually(
        timeout: TimeInterval = 10,
        _ condition: @MainActor () async -> Bool
    ) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if await condition() { return true }
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
        return await condition()
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

    // MARK: - 契约

    func testBatchPolishAdoptsAllCompatibleChapters() async throws {
        let fixture = try documentWithChapters(3)
        var scripts: [NovelModelScript] = []
        for index in 0..<3 {
            scripts.append(polishGenScript("Polished chapter \(index + 1)."))
            scripts.append(driftScript(compatibleDriftJSON))
        }
        let harness = try await makeHarness(document: fixture.document, scripts: scripts)

        harness.session.startBatchPolish(chapterIDs: fixture.chapterIDs)
        let done = await eventually { harness.session.batchPolishProgress?.phase == .done }
        XCTAssertTrue(done)

        let progress = try XCTUnwrap(harness.session.batchPolishProgress)
        XCTAssertEqual(progress.total, 3)
        XCTAssertEqual(progress.adoptedCount, 3)
        XCTAssertEqual(progress.skippedCount, 0)
        XCTAssertEqual(progress.failedCount, 0)
        XCTAssertFalse(harness.session.isBatchPolishing)
        XCTAssertFalse(harness.session.isBusy)

        let final = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(final.chapterVersions.filter { $0.kind == .polish }.count, 3)
        for chapterID in fixture.chapterIDs {
            XCTAssertTrue(final.chapterVersions.contains {
                $0.chapterID == chapterID && $0.kind == .polish
            })
        }
    }

    func testBatchPolishSkipsIncompatibleChapterAndContinues() async throws {
        let fixture = try documentWithChapters(3)
        let scripts = [
            polishGenScript("Polished 1."), driftScript(compatibleDriftJSON),
            polishGenScript("Mara opened the gate."), driftScript(incompatibleDriftJSON),
            polishGenScript("Polished 3."), driftScript(compatibleDriftJSON),
        ]
        let harness = try await makeHarness(document: fixture.document, scripts: scripts)

        harness.session.startBatchPolish(chapterIDs: fixture.chapterIDs)
        let done = await eventually { harness.session.batchPolishProgress?.phase == .done }
        XCTAssertTrue(done)

        let progress = try XCTUnwrap(harness.session.batchPolishProgress)
        XCTAssertEqual(progress.adoptedCount, 2)
        XCTAssertEqual(progress.skippedCount, 1)
        XCTAssertEqual(progress.failedCount, 0)
        XCTAssertEqual(
            progress.results.first { $0.chapterID == fixture.chapterIDs[1] }?.outcome,
            .skippedDrift
        )

        let final = try await harness.repository.loadProject(id: harness.projectID).document
        // 漂移改剧情的中间章不出新版本,首尾照常被采用。
        XCTAssertFalse(final.chapterVersions.contains {
            $0.chapterID == fixture.chapterIDs[1] && $0.kind == .polish
        })
        XCTAssertTrue(final.chapterVersions.contains {
            $0.chapterID == fixture.chapterIDs[0] && $0.kind == .polish
        })
        XCTAssertTrue(final.chapterVersions.contains {
            $0.chapterID == fixture.chapterIDs[2] && $0.kind == .polish
        })
    }

    func testBatchPolishRecordsGenerationFailureAndContinues() async throws {
        let fixture = try documentWithChapters(3)
        let failure = NovelModelFailure(code: "provider_down", message: "生成失败", isRetryable: true)
        let scripts = [
            polishGenScript("Polished 1."), driftScript(compatibleDriftJSON),
            NovelModelScript(steps: [.fail(failure)]),  // 第 2 章生成失败,不会有漂移调用
            polishGenScript("Polished 3."), driftScript(compatibleDriftJSON),
        ]
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: scripts,
            batchPolishSettleGrace: 0.2
        )

        harness.session.startBatchPolish(chapterIDs: fixture.chapterIDs)
        let done = await eventually(timeout: 15) {
            harness.session.batchPolishProgress?.phase == .done
        }
        XCTAssertTrue(done)

        let progress = try XCTUnwrap(harness.session.batchPolishProgress)
        XCTAssertEqual(progress.adoptedCount, 2)
        XCTAssertEqual(progress.failedCount, 1)
        XCTAssertEqual(
            progress.results.first { $0.chapterID == fixture.chapterIDs[1] }?.outcome,
            .failed
        )
    }

    func testBatchPolishCancellationStopsBeforeNextChapter() async throws {
        let fixture = try documentWithChapters(3)
        let scripts = [
            polishGenScript("Polished 1."), driftScript(compatibleDriftJSON),
            NovelModelScript(steps: [.delta("partial 2"), .pause]),  // 第 2 章生成挂起等取消
        ]
        let harness = try await makeHarness(document: fixture.document, scripts: scripts)

        harness.session.startBatchPolish(chapterIDs: fixture.chapterIDs)

        // 等第 1 章采用完、第 2 章生成挂起(批量循环正卡在等待候选)。
        let ch1Adopted = await eventually {
            harness.session.batchPolishProgress?.adoptedCount == 1
        }
        XCTAssertTrue(ch1Adopted)
        let sawPausedRun = await eventually { harness.session.activeRunID != nil }
        XCTAssertTrue(sawPausedRun)
        let pausedRunID = harness.session.activeRunID

        harness.session.cancelBatchPolish()
        let cancelled = await eventually {
            harness.session.batchPolishProgress?.phase == .cancelled
        }
        XCTAssertTrue(cancelled)

        let progress = try XCTUnwrap(harness.session.batchPolishProgress)
        XCTAssertEqual(progress.adoptedCount, 1)
        XCTAssertEqual(
            progress.cancelledCount,
            2,
            "第 2、3 章都应标为未处理，实际结果：\(progress.results.map { String(describing: $0.outcome) })"
        )
        XCTAssertFalse(harness.session.isBatchPolishing)
        XCTAssertNotNil(pausedRunID)
        let runStopped = await eventually {
            harness.session.activeRunID == nil
        }
        XCTAssertTrue(runStopped, "停止批量润色必须同时终止当前批量章节的 run，不能把项目留在生成中")
    }

    func testBatchPolishStopDuringDriftAssessmentSettlesAsCancelled() async throws {
        let fixture = try documentWithChapters(2)
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [
                polishGenScript("Polished 1."),
                NovelModelScript(steps: [.pause]),
            ]
        )

        harness.session.startBatchPolish(chapterIDs: fixture.chapterIDs)
        let driftStarted = await eventually {
            await harness.adapter.requests.count == 2
        }
        XCTAssertTrue(driftStarted)

        harness.session.cancelBatchPolish()
        let cancelled = await eventually {
            harness.session.batchPolishProgress?.phase == .cancelled
        }
        XCTAssertTrue(cancelled)

        let progress = try XCTUnwrap(harness.session.batchPolishProgress)
        XCTAssertEqual(progress.cancelledCount, 2)
        XCTAssertEqual(progress.failedCount, 0)
        let persisted = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(persisted.polishTransactions.first?.status, .retryable)
    }

    func testBatchPolishStopsAtFirstUnresolvedAdoptionTransaction() async throws {
        let fixture = try documentWithChapters(3)
        let driftFailure = NovelModelFailure(
            code: "provider_down",
            message: "漂移检查失败",
            isRetryable: true
        )
        let scripts = [
            polishGenScript("Polished 1."),
            NovelModelScript(steps: [.fail(driftFailure)]),
            // 修复前批量会把这份脚本误消费成下一章生成；修复后留给用户点“重试检查”。
            driftScript(compatibleDriftJSON),
        ]
        let harness = try await makeHarness(document: fixture.document, scripts: scripts)

        harness.session.startBatchPolish(chapterIDs: fixture.chapterIDs)
        let done = await eventually { harness.session.batchPolishProgress?.phase == .done }
        XCTAssertTrue(done)

        let progress = try XCTUnwrap(harness.session.batchPolishProgress)
        XCTAssertEqual(progress.failedCount, 1)
        XCTAssertEqual(progress.cancelledCount, 2)
        let requestCount = await harness.adapter.requests.count
        XCTAssertEqual(requestCount, 2, "出现未解决事务后不得继续生成下一章")

        let persisted = try await harness.repository.loadProject(id: harness.projectID).document
        let unresolved = persisted.polishTransactions.filter {
            $0.status == .pending || $0.status == .retryable || $0.status == .blocked
        }
        XCTAssertEqual(unresolved.count, 1, "批量一次最多留下一个需要用户处理的润色事务")
        XCTAssertEqual(unresolved.first?.status, .retryable)
        XCTAssertFalse(harness.session.canStartBatchPolish)

        harness.session.clearBatchPolish()
        XCTAssertNotNil(
            harness.session.batchPolishProgress,
            "未解决检查仍存在时不能清空批量报告，否则失败与未处理章节会丢失"
        )

        let transactionID = try XCTUnwrap(unresolved.first?.id)
        await harness.session.retryPolishTransaction(transactionID)
        XCTAssertTrue(harness.session.unresolvedBranchPolishTransactions.isEmpty)
        XCTAssertEqual(harness.session.batchPolishProgress?.adoptedCount, 1)
        XCTAssertTrue(harness.session.canStartBatchPolish)
    }

    func testBatchPolishGuardsAndBusyFlag() async throws {
        let fixture = try documentWithChapters(2)
        let scripts = [
            NovelModelScript(steps: [.delta("partial"), .pause]),  // 第 1 章挂起,停在运行态
            NovelModelScript(steps: [.delta("x"), .complete]),
        ]
        let harness = try await makeHarness(document: fixture.document, scripts: scripts)

        // 空选择是 no-op。
        harness.session.startBatchPolish(chapterIDs: [])
        XCTAssertNil(harness.session.batchPolishProgress)

        // 正常启动后折进 isBusy。
        harness.session.startBatchPolish(chapterIDs: fixture.chapterIDs)
        XCTAssertNotNil(harness.session.batchPolishProgress)
        let running = await eventually { harness.session.isBatchPolishing }
        XCTAssertTrue(running)
        XCTAssertTrue(harness.session.isBusy)

        // 运行中再次启动被忽略(total 不变)。
        harness.session.startBatchPolish(chapterIDs: fixture.chapterIDs)
        XCTAssertEqual(harness.session.batchPolishProgress?.total, 2)

        // 取消收口。
        harness.session.cancelBatchPolish()
        let settled = await eventually {
            harness.session.batchPolishProgress?.phase != .running
        }
        XCTAssertTrue(settled)
        XCTAssertFalse(harness.session.isBatchPolishing)

        let pausedRunID = harness.session.activeRunID
        if let pausedRunID {
            await harness.adapter.resume(runID: pausedRunID)
        }
    }

    func testBatchPolishReportDoesNotCrossBranchOrRetryThere() async throws {
        let fixture = try documentWithChapters(1)
        let failure = NovelModelFailure(code: "provider_down", message: "生成失败", isRetryable: true)
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [NovelModelScript(steps: [.fail(failure)])],
            batchPolishSettleGrace: 0.05
        )
        let sourceBranchID = fixture.document.branches[0].id
        let createdForkID = await harness.workspace.forkBranch(
            from: sourceBranchID,
            checkpointID: fixture.document.branches[0].headCheckpointID,
            name: "报告隔离线"
        )
        let forkID = try XCTUnwrap(createdForkID)
        await harness.workspace.selectBranch(sourceBranchID)
        await harness.session.bindToCurrentSelection()

        harness.session.startBatchPolish(chapterIDs: fixture.chapterIDs)
        let reportFinished = await eventually {
            harness.session.batchPolishProgress?.phase == .done
        }
        XCTAssertTrue(reportFinished)
        XCTAssertEqual(harness.session.batchPolishProgress?.failedCount, 1)
        let requestCount = await harness.adapter.requests.count

        await harness.workspace.selectBranch(forkID)
        XCTAssertNil(
            harness.session.batchPolishProgress,
            "workspace 已切到别的分支时，旧分支报告不能短暂显示在新分支"
        )
        harness.session.retryFailedBatchPolish()
        try await Task.sleep(for: .milliseconds(100))
        let requestCountAfterRetry = await harness.adapter.requests.count
        XCTAssertEqual(requestCountAfterRetry, requestCount)

        await harness.session.bindToCurrentSelection()
        XCTAssertEqual(harness.session.binding?.branchID, forkID)
        XCTAssertNil(harness.session.batchPolishProgress, "切 binding 后应清理旧分支的已结束报告")
    }

    func testBindingChangeCancelsRunningBatchWithoutStartingNextChapter() async throws {
        let fixture = try documentWithChapters(2)
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [
                NovelModelScript(steps: [.delta("partial"), .pause]),
                polishGenScript("must not start"),
            ]
        )
        let sourceBranchID = fixture.document.branches[0].id
        let createdForkID = await harness.workspace.forkBranch(
            from: sourceBranchID,
            checkpointID: fixture.document.branches[0].headCheckpointID,
            name: "切换目标线"
        )
        let forkID = try XCTUnwrap(createdForkID)
        await harness.workspace.selectBranch(sourceBranchID)
        await harness.session.bindToCurrentSelection()

        harness.session.startBatchPolish(chapterIDs: fixture.chapterIDs)
        let didStart = await eventually { harness.session.activeRunID != nil }
        XCTAssertTrue(didStart)
        let ownedRunID = try XCTUnwrap(harness.session.activeRunID)

        await harness.workspace.selectBranch(forkID)
        await harness.session.bindToCurrentSelection()

        XCTAssertEqual(harness.session.binding?.branchID, forkID)
        XCTAssertNil(harness.session.batchPolishProgress)
        let requestCount = await harness.adapter.requests.count
        XCTAssertEqual(requestCount, 1, "旧批次不能在新 binding 继续下一章")
        let persisted = try await harness.repository.loadProject(id: harness.projectID).document
        let stoppedRun = try XCTUnwrap(persisted.activeRuns.first { $0.id == ownedRunID })
        XCTAssertNotEqual(stoppedRun.status, .running)
    }

    func testBackgroundCancelsBatchAndDoesNotContinueNextChapter() async throws {
        let fixture = try documentWithChapters(2)
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [
                NovelModelScript(steps: [.delta("partial"), .pause]),
                polishGenScript("must not start"),
            ]
        )

        harness.session.startBatchPolish(chapterIDs: fixture.chapterIDs)
        let didStart = await eventually { harness.session.activeRunID != nil }
        XCTAssertTrue(didStart)
        let ownedRunID = try XCTUnwrap(harness.session.activeRunID)

        await harness.session.interruptBatchPolishForBackground()

        let didStop = await eventually {
            harness.session.batchPolishProgress?.phase == .cancelled &&
                harness.session.activeRunID == nil
        }
        XCTAssertTrue(didStop)
        try await Task.sleep(for: .milliseconds(100))
        let requestCount = await harness.adapter.requests.count
        XCTAssertEqual(requestCount, 1, "进入后台后不能继续发起下一章")
        let persisted = try await harness.repository.loadProject(id: harness.projectID).document
        let stoppedRun = try XCTUnwrap(persisted.activeRuns.first { $0.id == ownedRunID })
        XCTAssertEqual(stoppedRun.interruptionReason, .background)
    }

    func testAllDiscardedChaptersCannotStartBatchPolish() async throws {
        var fixture = try documentWithChapters(2)
        for index in fixture.document.chapters.indices {
            fixture.document.chapters[index].discardedAt = fixture.document.project.updatedAt
        }
        try NovelDocumentValidator.validate(fixture.document)
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [NovelModelScript(steps: [.delta("must not start"), .pause])]
        )

        XCTAssertFalse(harness.session.canStartBatchPolish)
        harness.session.startBatchPolish(chapterIDs: fixture.chapterIDs)
        XCTAssertNil(harness.session.batchPolishProgress)
        let requestCount = await harness.adapter.requests.count
        XCTAssertEqual(requestCount, 0)
    }
}
