import XCTest
@testable import iosApp

final class NovelModelAdapterTests: XCTestCase {
    func testResolveAndStartRecordPortableRequest() async throws {
        let model = makeModel()
        let request = makeRequest(model: model)
        let adapter = ScriptedNovelModelAdapter(
            resolvedModel: model,
            scripts: [NovelModelScript(steps: [.delta("Hello"), .complete])]
        )

        let resolved = try await adapter.resolveModel(for: .global)
        let stream = try await adapter.start(request)
        let events = await collect(stream)

        XCTAssertEqual(resolved, model)
        XCTAssertEqual(events, [.textDelta("Hello"), .completed])
        let policies = await adapter.resolvedPolicies
        let requests = await adapter.requests
        XCTAssertEqual(policies, [.global])
        XCTAssertEqual(requests, [request])
    }

    func testScriptPreservesReplacementUsageDuplicateTerminalAndLateEvents() async throws {
        let usage = NovelModelUsage(
            promptTokens: 120,
            completionTokens: 30,
            cachedTokens: 20,
            totalTokens: 150
        )
        let failure = NovelModelFailure(
            code: "late_failure",
            message: "late callback",
            isRetryable: true
        )
        let adapter = ScriptedNovelModelAdapter(
            resolvedModel: makeModel(),
            scripts: [NovelModelScript(steps: [
                .delta("draft"),
                .replacement("final"),
                .usage(usage),
                .complete,
                .complete,
                .fail(failure),
                .delta("late")
            ])]
        )

        let events = try await collect(adapter.start(makeRequest()))

        XCTAssertEqual(events, [
            .textDelta("draft"),
            .textReplacement("final"),
            .usage(usage),
            .completed,
            .completed,
            .failed(failure),
            .textDelta("late")
        ])
    }

    func testCancelStopsCooperativePausedScriptWithoutInventingTerminal() async throws {
        let request = makeRequest()
        let adapter = ScriptedNovelModelAdapter(
            resolvedModel: request.model,
            scripts: [NovelModelScript(steps: [.pause, .delta("too late"), .complete])]
        )
        let stream = try await adapter.start(request)

        await adapter.cancel(runID: request.runID)
        let events = await collect(stream)

        XCTAssertEqual(events, [])
        let cancellations = await adapter.cancelledRunIDs
        XCTAssertEqual(cancellations, [request.runID])
    }

    func testCancelReturnsWithoutWaitingWhenProviderIgnoresCancellation() async throws {
        let request = makeRequest()
        let adapter = ScriptedNovelModelAdapter(
            resolvedModel: request.model,
            scripts: [NovelModelScript(
                steps: [.pause, .delta("late"), .complete],
                ignoresCancellation: true
            )]
        )
        let stream = try await adapter.start(request)
        let cancelReturned = expectation(description: "best effort cancel returned")

        Task {
            await adapter.cancel(runID: request.runID)
            cancelReturned.fulfill()
        }
        await fulfillment(of: [cancelReturned], timeout: 0.2)

        await adapter.resume(runID: request.runID)
        let events = await collect(stream)
        XCTAssertEqual(events, [.textDelta("late"), .completed])
        let cancellations = await adapter.cancelledRunIDs
        XCTAssertEqual(cancellations, [request.runID])
    }

    func testQueuedScriptsAreConsumedPerRequestAndMissingScriptFailsHonestly() async throws {
        let adapter = ScriptedNovelModelAdapter(
            resolvedModel: makeModel(),
            scripts: [NovelModelScript(steps: [.complete])]
        )

        _ = try await collect(adapter.start(makeRequest()))

        do {
            _ = try await adapter.start(makeRequest())
            XCTFail("Expected the exhausted scripted adapter to fail")
        } catch {
            XCTAssertEqual(error as? NovelScriptedModelError, .scriptUnavailable)
        }
    }

    func testDuplicateRunIDIsRejectedEvenAfterTheFirstStreamFinished() async throws {
        let request = makeRequest()
        let adapter = ScriptedNovelModelAdapter(
            resolvedModel: request.model,
            scripts: [
                NovelModelScript(steps: [.complete]),
                NovelModelScript(steps: [.complete])
            ]
        )

        _ = try await collect(adapter.start(request))

        do {
            _ = try await adapter.start(request)
            XCTFail("Expected duplicate run ID to fail")
        } catch {
            XCTAssertEqual(error as? NovelModelAdapterError, .duplicateRunID(request.runID))
        }
    }

    func testResolutionFailureIsObservableAndDoesNotConsumeScript() async throws {
        let failure = NovelModelFailure(
            code: "missing_model",
            message: "The selected model no longer exists.",
            isRetryable: false
        )
        let adapter = ScriptedNovelModelAdapter(
            resolvedModel: makeModel(),
            resolutionFailure: failure,
            scripts: [NovelModelScript(steps: [.complete])]
        )

        do {
            _ = try await adapter.resolveModel(for: .fixed(providerID: "p", modelID: "m"))
            XCTFail("Expected model resolution to fail")
        } catch {
            XCTAssertEqual(error as? NovelModelFailure, failure)
        }

        let events = try await collect(adapter.start(makeRequest()))
        XCTAssertEqual(events, [.completed])
    }

    private func makeModel() -> NovelResolvedModel {
        NovelResolvedModel(
            providerID: "provider-uuid",
            ownerProviderID: "provider-uuid",
            modelID: "model-uuid",
            wireModelID: "novel-model-v1",
            displayName: "Novel Model",
            contextWindowTokens: 128_000
        )
    }

    private func makeRequest(model: NovelResolvedModel? = nil) -> NovelModelRequest {
        NovelModelRequest(
            runID: NovelRunID(),
            model: model ?? makeModel(),
            purpose: .prose,
            messages: [
                NovelModelMessage(role: .system, content: "Write fiction."),
                NovelModelMessage(role: .user, content: "Continue the chapter.")
            ],
            parameters: NovelModelParameters(
                temperature: 0.8,
                topP: 0.95,
                maxOutputTokens: 4_096,
                reasoningLevel: .off
            )
        )
    }

    private func collect(_ stream: AsyncStream<NovelModelEvent>) async -> [NovelModelEvent] {
        var events: [NovelModelEvent] = []
        for await event in stream {
            events.append(event)
        }
        return events
    }
}
