import XCTest
@testable import iosApp

final class NovelStructuredModelExecutorTests: XCTestCase {
    func testDiscussionArchiveUsesContinuousNoOutputTimeoutInsteadOfAbsoluteDeadline() async throws {
        let archiveJSON = """
        {"schemaVersion":1,"decisions":[{"topic":"揭示时点","decision":"第三章末揭示。","relatedMaterialID":null}],"summary":"确认第三章末揭示身世。"}
        """
        let split = archiveJSON.index(archiveJSON.startIndex, offsetBy: 40)
        let adapter = makeAdapter(scripts: [NovelModelScript(steps: [
            .delta(String(archiveJSON[..<split])),
            .pause,
            .delta(String(archiveJSON[split...])),
            .pause,
            .complete,
        ])])
        let executor = NovelStructuredModelExecutor(modelRunner: adapter)
        let runID = NovelRunID()
        let executionTask = Task {
            try await executor.executeWithEvidence(.init(
                runID: runID,
                modelPolicy: .global,
                task: .discussionArchive(discussion: "用户：主角何时揭示身世？")
            ), noOutputTimeout: 0.2)
        }

        try await Task.sleep(nanoseconds: 120_000_000)
        await adapter.resume(runID: runID)
        try await Task.sleep(nanoseconds: 120_000_000)
        await adapter.resume(runID: runID)
        let execution = try await executionTask.value

        guard case .discussionArchive(let archive) = execution.output else {
            return XCTFail("Expected a discussion archive")
        }
        XCTAssertEqual(archive.decisions.map(\.topic), ["揭示时点"])
        let requests = await adapter.requests
        let cancelledRunIDs = await adapter.cancelledRunIDs
        XCTAssertEqual(requests.first?.purpose, .stateExtraction)
        XCTAssertTrue(cancelledRunIDs.isEmpty)
    }

    func testDiscussionArchiveNoOutputTimeoutCancelsProviderAndRemainsRetryable() async throws {
        let adapter = makeAdapter(scripts: [NovelModelScript(steps: [.pause])])
        let executor = NovelStructuredModelExecutor(modelRunner: adapter)
        let runID = NovelRunID()

        do {
            _ = try await executor.executeWithEvidence(.init(
                runID: runID,
                modelPolicy: .global,
                task: .discussionArchive(discussion: "用户：确认第三章揭示身世。")
            ), noOutputTimeout: 0.05)
            XCTFail("Expected a continuous no-output timeout")
        } catch let failure as NovelStructuredModelExecutionFailure {
            XCTAssertEqual(failure.failure.code, "structured_no_output_timeout")
            XCTAssertTrue(failure.failure.isRetryable)
            XCTAssertFalse(failure.allowsOutputRepair)
            XCTAssertNil(failure.rawText)
        }

        let cancelledRunIDs = await adapter.cancelledRunIDs
        XCTAssertTrue(cancelledRunIDs.contains(runID))
    }

    func testDiscussionArchiveUsageOnlyDoesNotRefreshNoOutputTimeout() async throws {
        let adapter = makeAdapter(scripts: [NovelModelScript(steps: [
            .pause,
            .usage(.init(
                promptTokens: 12,
                completionTokens: 0,
                cachedTokens: 0,
                totalTokens: 12
            )),
            .pause,
        ])])
        let executor = NovelStructuredModelExecutor(modelRunner: adapter)
        let runID = NovelRunID()
        let executionTask = Task {
            try await executor.executeWithEvidence(.init(
                runID: runID,
                modelPolicy: .global,
                task: .discussionArchive(discussion: "用户：确认第三章揭示身世。")
            ), noOutputTimeout: 0.4)
        }

        try await Task.sleep(nanoseconds: 250_000_000)
        await adapter.resume(runID: runID)
        try await Task.sleep(nanoseconds: 300_000_000)

        let cancelledRunIDs = await adapter.cancelledRunIDs
        XCTAssertTrue(cancelledRunIDs.contains(runID))
        executionTask.cancel()
        _ = await executionTask.result
    }

    func testReasoningActivityRefreshesHeartbeatWithoutEnteringStructuredJSON() async throws {
        let archiveJSON = """
        {"schemaVersion":1,"decisions":[{"topic":"既定方案","decision":"继续沿用。","relatedMaterialID":null}],"summary":"继续沿用既定方案。"}
        """
        let adapter = makeAdapter(scripts: [NovelModelScript(steps: [
            .activity,
            .pause,
            .activity,
            .pause,
            .delta(archiveJSON),
            .complete,
        ])])
        let executor = NovelStructuredModelExecutor(modelRunner: adapter)
        let runID = NovelRunID()
        let executionTask = Task {
            try await executor.executeWithEvidence(.init(
                runID: runID,
                modelPolicy: .global,
                task: .discussionArchive(discussion: "用户：继续沿用既定方案。")
            ), noOutputTimeout: 0.2)
        }

        try await Task.sleep(nanoseconds: 120_000_000)
        await adapter.resume(runID: runID)
        try await Task.sleep(nanoseconds: 120_000_000)
        await adapter.resume(runID: runID)

        let execution = try await executionTask.value
        guard case .discussionArchive(let archive) = execution.output else {
            return XCTFail("Expected a discussion archive")
        }
        XCTAssertEqual(archive.summary, "继续沿用既定方案。")
    }

    func testStructuredCancellationUsesTaskNeutralChineseMessage() async throws {
        let adapter = makeAdapter(scripts: [NovelModelScript(steps: [.pause])])
        let executor = NovelStructuredModelExecutor(modelRunner: adapter)
        let runID = NovelRunID()
        let executionTask = Task {
            try await executor.executeWithEvidence(.init(
                runID: runID,
                modelPolicy: .global,
                task: .continuityAudit(priorFindings: "", manuscript: "第一章正文")
            ), noOutputTimeout: 5)
        }
        for _ in 0..<100 {
            if await adapter.requests.count == 1 { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        let requestCount = await adapter.requests.count
        XCTAssertEqual(requestCount, 1)

        executionTask.cancel()

        do {
            _ = try await executionTask.value
            XCTFail("Expected cancellation")
        } catch let failure as NovelStructuredModelExecutionFailure {
            XCTAssertEqual(failure.failure.code, "cancelled")
            XCTAssertEqual(failure.failure.message, "模型任务已取消，可以重试。")
        }
    }

    func testStateDeltaRunsVersionedPromptThroughProviderAndStrictDecoder() async throws {
        let adapter = makeAdapter(scripts: [NovelModelScript(steps: [
            .delta(String(validStateDelta.prefix(80))),
            .delta(String(validStateDelta.dropFirst(80))),
            .complete
        ])])
        let executor = NovelStructuredModelExecutor(modelRunner: adapter)
        let runID = NovelRunID()

        let execution = try await executor.executeWithEvidence(.init(
            runID: runID,
            modelPolicy: .global,
            task: .stateDelta(
                context: "Mara is the viewpoint character.",
                manuscript: "Mara opened the sealed door."
            )
        ))

        guard case .stateDelta(let delta) = execution.output else {
            return XCTFail("Expected a state delta")
        }
        XCTAssertEqual(delta.events.map(\.id), ["door-opened"])
        let requests = await adapter.requests
        XCTAssertEqual(requests.count, 1)
        XCTAssertEqual(requests[0].purpose, .stateExtraction)
        XCTAssertTrue(requests[0].messages[0].content.contains("NovelStateDeltaV1"))
        XCTAssertTrue(requests[0].messages[0].content.contains("AUTHORITATIVE CONTEXT"))
        XCTAssertFalse(requests[0].messages[0].content.contains("Memory"))
        XCTAssertEqual(execution.modelRequest, requests[0])
        XCTAssertEqual(execution.resolvedModel.providerID, "transport-provider")
        XCTAssertEqual(execution.resolvedModel.ownerProviderID, "owner-provider")
        XCTAssertEqual(execution.requestSHA256.count, 64)
        // 2026-07-26 契约变更(用户明确裁决):不再给模型设输出硬上限——人为上限会被
        // 推理 token 吃掉并触发「模型回复达到输出上限」的假失败。原断言锁的是 4096,
        // 现改为断言**根本不发送该参数**。输入预算的「留位」由 taskKind 的
        // outputReservationTokens 独立承担,是纯本地算术,不进 provider 参数。
        XCTAssertNil(execution.parameters["maxOutputTokens"])
        XCTAssertEqual(execution.modelRequest.runID, runID)
    }

    func testStateRebuildHonorsReplacementAndDecodesStrictly() async throws {
        let adapter = makeAdapter(scripts: [NovelModelScript(steps: [
            .delta("discarded"),
            .replacement(validStateRebuild),
            .complete
        ])])
        let executor = NovelStructuredModelExecutor(modelRunner: adapter)

        let output = try await executor.execute(.init(
            runID: NovelRunID(),
            modelPolicy: .fixed(providerID: "provider", modelID: "model"),
            task: .stateRebuild(
                baseContext: "No events have happened.",
                manuscript: "Chapter 1: Mara opened the sealed door."
            )
        ))

        guard case .stateRebuild(let rebuild) = output else {
            return XCTFail("Expected a state rebuild")
        }
        XCTAssertEqual(rebuild.stateSummary, "Mara entered the archive.")
        let requests = await adapter.requests
        XCTAssertEqual(requests[0].purpose, .stateRebuild)
    }

    func testPolishDriftRejectsDuplicateKeysFailClosed() async throws {
        let invalid = """
        {"schemaVersion":1,"schemaVersion":1,"compatible":true,"differences":[]}
        """
        let adapter = makeAdapter(scripts: [NovelModelScript(steps: [
            .delta(invalid),
            .complete
        ])])
        let executor = NovelStructuredModelExecutor(modelRunner: adapter)

        do {
            _ = try await executor.execute(.init(
                runID: NovelRunID(),
                modelPolicy: .global,
                task: .polishDrift(sourceChapter: "Source", candidate: "Candidate")
            ))
            XCTFail("Expected duplicate JSON keys to fail closed")
        } catch let error as NovelStructuredModelExecutionFailure {
            XCTAssertEqual(error.failure.code, "invalid_structured_output")
            XCTAssertEqual(error.structuredOutputFailure?.category, .duplicateKey)
            XCTAssertTrue(error.failure.isRetryable)
            XCTAssertTrue(error.allowsOutputRepair)
            XCTAssertEqual(error.rawText, invalid)
        }
    }

    func testProviderFailureRemainsUserVisibleAndRetryable() async throws {
        let adapter = makeAdapter(scripts: [NovelModelScript(steps: [
            .fail(NovelModelFailure(
                code: "rate_limited",
                message: "Try again later.",
                isRetryable: true
            ))
        ])])
        let executor = NovelStructuredModelExecutor(modelRunner: adapter)

        do {
            _ = try await executor.execute(.init(
                runID: NovelRunID(),
                modelPolicy: .global,
                task: .stateDelta(context: "Context", manuscript: "Text")
            ))
            XCTFail("Expected provider failure")
        } catch let error as NovelStructuredModelExecutionFailure {
            XCTAssertEqual(error.failure.code, "rate_limited")
            XCTAssertEqual(error.failure.message, "Try again later.")
            XCTAssertTrue(error.failure.isRetryable)
            XCTAssertNil(error.structuredOutputFailure)
        }
    }

    func testDataAfterCompletedTerminalIsRejected() async throws {
        let adapter = makeAdapter(scripts: [NovelModelScript(steps: [
            .delta(validStateDelta),
            .complete,
            .delta("late")
        ])])
        let executor = NovelStructuredModelExecutor(modelRunner: adapter)

        do {
            _ = try await executor.execute(.init(
                runID: NovelRunID(),
                modelPolicy: .global,
                task: .stateDelta(context: "Context", manuscript: "Text")
            ))
            XCTFail("Expected the duplicate terminal path to fail")
        } catch let error as NovelStructuredModelExecutionFailure {
            XCTAssertEqual(error.failure.code, "duplicate_model_terminal")
        }
    }

    // MARK: - 输出上限 / 输入留位的职责分离(2026-07-26 真机故障守护)

    /// 真机故障:状态同步报「模型回复达到输出上限」。根因是 `maxOutputTokens` 一个字段
    /// 同时承担「发给 provider 的硬上限」与「输入预算的本地留位」两个职责,推理 token
    /// 吃掉预算后结构化 JSON 未写完即撞线。现已拆分,本组测试锁住拆分后的契约。
    func testNoStructuredTaskSendsAnOutputCapToTheProvider() {
        for kind in NovelStructuredModelTaskKind.allCases {
            XCTAssertNil(
                kind.parameters.maxOutputTokens,
                "\(kind) 不得给模型设输出硬上限——人为上限会造成假失败"
            )
        }
    }

    func testNoGenerationRunKindSendsAnOutputCapToTheProvider() {
        // 生成侧同一契约:四类生成任务同样不得设硬上限。
        for kind in [NovelRunKind.quickStart, .discussion, .prose, .polish] {
            XCTAssertGreaterThan(
                kind.outputReservationTokens,
                0,
                "\(kind) 必须有正的输入留位,否则输入可能吃满窗口、挤没输出空间"
            )
        }
    }

    /// 红线:留位是**纯本地算术**,绝不能出现在发往 provider 的参数里。
    /// 这条防的是「把两个职责又合并回一个字段」——那等于故障原样复发。
    func testOutputReservationNeverLeaksIntoProviderParameters() {
        for kind in NovelStructuredModelTaskKind.allCases {
            let evidence = kind.parameters.evidenceDictionary
            XCTAssertNil(evidence["maxOutputTokens"], "\(kind) 泄漏了输出上限")
            XCTAssertNil(evidence["outputReservationTokens"], "\(kind) 把本地留位发给了 provider")
            XCTAssertFalse(
                evidence.values.contains(String(kind.outputReservationTokens)),
                "\(kind) 的留位数值出现在了 provider 参数里"
            )
        }
    }

    /// 留位必须仍在扣减输入预算(证明护栏没被拆掉,而不是简单地把上限删了了事)。
    func testInputBudgetStillReservesRoomForOutput() {
        for kind in NovelStructuredModelTaskKind.allCases {
            XCTAssertGreaterThan(kind.outputReservationTokens, 0, "\(kind) 缺少输入留位")
        }
        XCTAssertGreaterThan(
            NovelStructuredModelTaskKind.stateRebuild.outputReservationTokens,
            NovelStructuredModelTaskKind.stateDelta.outputReservationTokens,
            "整段状态重建的输出量级大于增量,留位应更大"
        )
    }

    /// 剧情同步默认关推理；设置打开后才用 automatic。
    func testStateSyncStructuredTasksRespectReasoningPreference() {
        XCTAssertEqual(
            NovelStructuredModelTaskKind.stateDelta.parameters(
                stateSyncReasoningEnabled: false
            ).reasoningLevel,
            .off
        )
        XCTAssertEqual(
            NovelStructuredModelTaskKind.stateRebuild.parameters(
                stateSyncReasoningEnabled: false
            ).reasoningLevel,
            .off
        )
        XCTAssertEqual(
            NovelStructuredModelTaskKind.stateDelta.parameters(
                stateSyncReasoningEnabled: true
            ).reasoningLevel,
            .automatic
        )
        XCTAssertEqual(
            NovelStructuredModelTaskKind.stateRebuild.parameters(
                stateSyncReasoningEnabled: true
            ).reasoningLevel,
            .automatic
        )
        // 其他任务不受该开关影响。
        XCTAssertEqual(
            NovelStructuredModelTaskKind.discussionArchive.parameters(
                stateSyncReasoningEnabled: false
            ).reasoningLevel,
            .automatic
        )
    }

    func testStateSyncReasoningPreferenceDefaultsOffAndRoundTrips() {
        let suite = "novel-state-sync-reasoning-\(UUID().uuidString)"
        let store = UserDefaults(suiteName: suite)!
        defer { store.removePersistentDomain(forName: suite) }
        let preferences = NovelCreationModelPreferences(userDefaults: store)
        XCTAssertFalse(preferences.stateSyncReasoningEnabled)
        preferences.stateSyncReasoningEnabled = true
        XCTAssertTrue(preferences.stateSyncReasoningEnabled)
        preferences.stateSyncReasoningEnabled = false
        XCTAssertFalse(preferences.stateSyncReasoningEnabled)
    }
}

private extension NovelStructuredModelExecutorTests {
    func makeAdapter(scripts: [NovelModelScript]) -> ScriptedNovelModelAdapter {
        ScriptedNovelModelAdapter(
            resolvedModel: NovelResolvedModel(
                providerID: "transport-provider",
                ownerProviderID: "owner-provider",
                modelID: "model-uuid",
                wireModelID: "wire-model",
                displayName: "Structured Model",
                contextWindowTokens: 64_000
            ),
            scripts: scripts
        )
    }

    var validStateDelta: String {
        """
        {
          "schemaVersion": 1,
          "stateSummary": "Mara entered the archive.",
          "events": [{
            "id": "door-opened",
            "kind": "discovery",
            "summary": "Mara opened the sealed door.",
            "entityReferences": ["Mara"],
            "evidence": "Mara opened the sealed door."
          }],
          "characterChanges": [],
          "relationshipChanges": [],
          "foreshadowingChanges": [],
          "unresolvedEntityNames": [],
          "branchOutlinePatch": null,
          "settingProposals": []
        }
        """
    }

    var validStateRebuild: String {
        """
        {
          "schemaVersion": 1,
          "stateSummary": "Mara entered the archive.",
          "branchOutline": "Mara searches the archive.",
          "events": [{
            "id": "door-opened",
            "kind": "discovery",
            "summary": "Mara opened the sealed door.",
            "entityReferences": ["Mara"],
            "evidence": "Mara opened the sealed door."
          }],
          "characterStates": [],
          "relationships": [],
          "foreshadowing": [],
          "unresolvedEntityNames": [],
          "settingProposals": []
        }
        """
    }
}
