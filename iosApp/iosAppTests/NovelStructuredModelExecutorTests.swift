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
        XCTAssertEqual(execution.parameters["maxOutputTokens"], "4096")
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
