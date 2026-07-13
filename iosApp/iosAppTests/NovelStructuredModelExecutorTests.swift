import XCTest
@testable import iosApp

final class NovelStructuredModelExecutorTests: XCTestCase {
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
