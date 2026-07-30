import XCTest
@preconcurrency import Shared
@testable import iosApp

/// I-5 (可解释终态 / 打转守护) coverage: see
/// `docs/IOS_AGENT_HARDENING_PLAN_2026-07-29.md` §W5. Two layers, mirroring
/// `IOSToolArgumentsFailClosedTests`' style (own self-contained fixtures):
///
///  1. `IOSToolLoopGuard` itself — pure signature counting, in isolation from
///     any engine/coordinator plumbing.
///  2. `IOSAgentToolEngine.run`/`executeBatch` — the engine is the shared
///     wiring point for SubAgent / background-handoff / Novel, so proving the
///     guard trips correctly there backs all three callers at once. The
///     `ChatGenerationCoordinator` (foreground) wiring reuses the exact same
///     `IOSToolLoopGuard` type and the same `appendingToolLoopReminder`
///     helper tested in layer 1/pure form below; constructing a full
///     `ChatGenerationCoordinator` run in a unit test would require the same
///     heavyweight bindings/dependencies scaffolding `ChatGenerationCoordinator`
///     itself has no dedicated unit test target for elsewhere in this suite,
///     so its wiring correctness is backed by these two layers plus manual
///     code reading rather than a third, coordinator-level test here.
final class IOSToolLoopGuardTests: XCTestCase {

    // MARK: - Layer 1: IOSToolLoopGuard pure signature counting

    func testFirstCallProceeds() {
        var guardian = IOSToolLoopGuard()
        XCTAssertEqual(guardian.check(toolName: "search", input: "{\"query\":\"a\"}"), .proceed)
    }

    func testSecondIdenticalCallReminds() {
        var guardian = IOSToolLoopGuard()
        _ = guardian.check(toolName: "search", input: "{\"query\":\"a\"}")
        let verdict = guardian.check(toolName: "search", input: "{\"query\":\"a\"}")
        guard case .proceedAndRemind(let reminder) = verdict else {
            return XCTFail("Expected proceedAndRemind on the 2nd identical call, got \(verdict)")
        }
        XCTAssertFalse(reminder.isEmpty)
    }

    func testThirdIdenticalCallStops() {
        var guardian = IOSToolLoopGuard()
        _ = guardian.check(toolName: "search", input: "{\"query\":\"a\"}")
        _ = guardian.check(toolName: "search", input: "{\"query\":\"a\"}")
        let verdict = guardian.check(toolName: "search", input: "{\"query\":\"a\"}")
        guard case .stop(let reason) = verdict else {
            return XCTFail("Expected stop on the 3rd identical call, got \(verdict)")
        }
        XCTAssertTrue(reason.contains("search"), "stop reason should name the tool, was: \(reason)")
    }

    func testFourthAndBeyondIdenticalCallsAlsoStop() {
        var guardian = IOSToolLoopGuard()
        for _ in 0..<3 {
            _ = guardian.check(toolName: "search", input: "{\"query\":\"a\"}")
        }
        let verdict = guardian.check(toolName: "search", input: "{\"query\":\"a\"}")
        guard case .stop = verdict else {
            return XCTFail("Expected stop to persist past the 3rd repeat, got \(verdict)")
        }
    }

    func testDifferentArgumentsDoNotTriggerRemindOrStop() {
        var guardian = IOSToolLoopGuard()
        _ = guardian.check(toolName: "search", input: "{\"query\":\"a\"}")
        _ = guardian.check(toolName: "search", input: "{\"query\":\"b\"}")
        let verdict = guardian.check(toolName: "search", input: "{\"query\":\"c\"}")
        XCTAssertEqual(verdict, .proceed, "distinct arguments must never be treated as repeats")
    }

    func testDifferentToolsWithSameArgumentsDoNotTrigger() {
        var guardian = IOSToolLoopGuard()
        _ = guardian.check(toolName: "search", input: "{\"query\":\"a\"}")
        let verdict = guardian.check(toolName: "scrape_web", input: "{\"query\":\"a\"}")
        XCTAssertEqual(verdict, .proceed, "same arguments under a different tool name must not count as a repeat")
    }

    func testSemanticallyIdenticalJSONObjectFormattingCountsAsTheSameSignature() {
        var guardian = IOSToolLoopGuard()
        XCTAssertEqual(
            guardian.check(toolName: "search", input: #"{"query":"a","limit":3}"#),
            .proceed
        )
        guard case .proceedAndRemind = guardian.check(
            toolName: "search",
            input: #"{ "limit" : 3, "query" : "a" }"#
        ) else {
            return XCTFail("JSON whitespace and key order must not bypass the second-call reminder")
        }
        guard case .stop = guardian.check(
            toolName: "search",
            input: #"{"limit":3,"query":"a"}"#
        ) else {
            return XCTFail("semantically identical JSON must stop on the third call")
        }
    }

    func testInterleavedCallsCountIndependentlyPerSignature() {
        var guardian = IOSToolLoopGuard()
        // A, B, A, B, A — A reaches its 3rd occurrence on the last call and
        // must stop; B has only reached its 2nd occurrence and must remind.
        XCTAssertEqual(guardian.check(toolName: "search", input: "a"), .proceed)
        XCTAssertEqual(guardian.check(toolName: "search", input: "b"), .proceed)
        guard case .proceedAndRemind = guardian.check(toolName: "search", input: "a") else {
            return XCTFail("Expected A's 2nd occurrence to remind")
        }
        guard case .proceedAndRemind = guardian.check(toolName: "search", input: "b") else {
            return XCTFail("Expected B's 2nd occurrence to remind")
        }
        guard case .stop = guardian.check(toolName: "search", input: "a") else {
            return XCTFail("Expected A's 3rd occurrence to stop")
        }
    }

    // MARK: - Layer 1b: appendingToolLoopReminder (pure append-not-replace helper)

    private func toolPart(toolCallId: String, toolName: String, input: String, output: [UIMessagePart] = []) -> UIMessagePart.Tool {
        UIMessagePart.Tool(
            toolCallId: toolCallId,
            toolName: toolName,
            input: input,
            output: output,
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
    }

    private func message(role: MessageRole, parts: [UIMessagePart]) -> UIMessage {
        UIMessage(
            id: KotlinUuid.companion.random(),
            role: role,
            parts: parts,
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    func testAppendingToolLoopReminderAppendsWithoutReplacingOriginalOutput() {
        let original = toolPart(
            toolCallId: "tc-1",
            toolName: "search",
            input: "{\"query\":\"a\"}",
            output: [UIMessagePart.Text(text: "{\"ok\":true}", metadata: nil)]
        )
        let messages = [message(role: MessageRole.assistant, parts: [original])]

        let result = appendingToolLoopReminder("please stop repeating", toToolCallId: "tc-1", in: messages)

        guard let updated = result.first?.parts.first as? UIMessagePart.Tool else {
            return XCTFail("Expected the tool part to survive")
        }
        XCTAssertEqual(updated.output.count, 2, "reminder must be appended, not replace the original output")
        XCTAssertEqual((updated.output.first as? UIMessagePart.Text)?.text, "{\"ok\":true}")
        XCTAssertEqual((updated.output.last as? UIMessagePart.Text)?.text, "please stop repeating")
    }

    func testAppendingToolLoopReminderLeavesOtherToolCallsUntouched() {
        let target = toolPart(toolCallId: "tc-1", toolName: "search", input: "a", output: [UIMessagePart.Text(text: "r1", metadata: nil)])
        let other = toolPart(toolCallId: "tc-2", toolName: "search", input: "b", output: [UIMessagePart.Text(text: "r2", metadata: nil)])
        let messages = [message(role: MessageRole.assistant, parts: [target, other])]

        let result = appendingToolLoopReminder("reminder", toToolCallId: "tc-1", in: messages)
        let parts = result.first?.parts.compactMap { $0 as? UIMessagePart.Tool } ?? []

        XCTAssertEqual(parts.first(where: { $0.toolCallId == "tc-2" })?.output.count, 1, "untouched tool call must keep its original output count")
    }

    // MARK: - Layer 2: IOSAgentToolEngine integration

    private func makeProviderSetting() -> ProviderSetting.OpenAI {
        ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "loop-guard-test",
            models: [],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: "sk-test",
            baseUrl: "https://example.test",
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
    }

    private func makeParams() -> TextGenerationParams {
        let model = Model(
            modelId: "test-model",
            displayName: "test-model",
            id: KotlinUuid.companion.random(),
            type: ModelType.chat,
            customHeaders: [],
            customBodies: [],
            inputModalities: [],
            outputModalities: [],
            abilities: [],
            tools: Set<BuiltInTools>(),
            contextWindowTokens: nil,
            providerOverwrite: nil
        )
        return TextGenerationParams(
            model: model,
            temperature: KotlinFloat(value: 0.7),
            topP: nil,
            maxTokens: nil,
            tools: [],
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
    }

    private func userMessage(_ text: String) -> UIMessage {
        message(role: MessageRole.user, parts: [UIMessagePart.Text(text: text, metadata: nil)])
    }

    private func toolCallMessage(toolCallId: String, toolName: String, input: String) -> UIMessage {
        message(role: MessageRole.assistant, parts: [toolPart(toolCallId: toolCallId, toolName: toolName, input: input)])
    }

    /// Scripted provider returning a fixed queue of assistant turns. Each
    /// script entry stands in for one model round-trip; the engine calls this
    /// once per loop iteration.
    private final class ScriptedProvider: IOSAgentTextProvider, @unchecked Sendable {
        private var script: [UIMessage]
        private(set) var callCount = 0
        init(_ script: [UIMessage]) { self.script = script }

        func generateText(
            providerSetting: ProviderSetting,
            messages: [UIMessage],
            params: TextGenerationParams
        ) async throws -> MessageChunk {
            callCount += 1
            let message = script.isEmpty
                ? UIMessage(
                    id: KotlinUuid.companion.random(),
                    role: MessageRole.assistant,
                    parts: [UIMessagePart.Text(text: "stop", metadata: nil)],
                    annotations: [],
                    createdAt: chatNowLocalDateTime(),
                    finishedAt: nil,
                    modelId: nil,
                    usage: nil,
                    translation: nil
                )
                : script.removeFirst()
            return MessageChunk(
                id: "chunk-\(UUID().uuidString)",
                model: "test-model",
                choices: [UIMessageChoice(index: 0, delta: nil, message: message, finishReason: "stop")],
                usage: nil
            )
        }
    }

    /// Records every call so a test can assert the executor was (or was not)
    /// reached on the 3rd identical call.
    private final class RecordingExecutor: IOSToolExecutor {
        private(set) var calls: [(name: String, arguments: String)] = []
        private let result: IOSAgentToolOutcome
        init(_ result: IOSAgentToolOutcome = .filled("{\"ok\":true}")) { self.result = result }

        func execute(name: String, arguments: String, isUserInitiated: Bool) async -> IOSAgentToolOutcome {
            calls.append((name, arguments))
            return result
        }
    }

    /// The model calls `search` with the exact same arguments 3 times in a
    /// row (a fresh `toolCallId` each time, as a real provider would issue,
    /// but identical `toolName`+`input`). Expected: 1st executes normally,
    /// 2nd executes AND carries the reminder, 3rd never reaches the executor
    /// and the run terminates with `guardStopped == true` rather than a
    /// disguised normal completion.
    func testEngineRemindsOnSecondRepeatAndStopsOnThird() async {
        let repeatedInput = "{\"query\":\"same thing\"}"
        let provider = ScriptedProvider([
            toolCallMessage(toolCallId: "tc-1", toolName: "search", input: repeatedInput),
            toolCallMessage(toolCallId: "tc-2", toolName: "search", input: repeatedInput),
            toolCallMessage(toolCallId: "tc-3", toolName: "search", input: repeatedInput)
        ])
        let executor = RecordingExecutor(.filled("{\"ok\":true}"))
        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: ["search": executor],
            configuration: .init(maxSteps: 8)
        )

        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("look this up three times")],
            params: makeParams()
        )

        XCTAssertEqual(executor.calls.count, 2, "the 3rd identical call must never reach the executor")
        XCTAssertTrue(result.guardStopped, "run must report guardStopped rather than a silent completion")
        XCTAssertFalse(result.hitStepLimit, "guard stop is a distinct terminal from the step-limit terminal")
        XCTAssertEqual(provider.callCount, 3, "the engine must not ask the model for a 4th turn after stopping")

        let toolParts = result.messages
            .flatMap(\.parts)
            .compactMap { $0 as? UIMessagePart.Tool }

        let firstOutput = toolParts.first(where: { $0.toolCallId == "tc-1" })?.output
            .compactMap { $0 as? UIMessagePart.Text }.map(\.text) ?? []
        XCTAssertEqual(firstOutput, ["{\"ok\":true}"], "1st call must execute normally with no reminder attached")

        let secondOutput = toolParts.first(where: { $0.toolCallId == "tc-2" })?.output
            .compactMap { $0 as? UIMessagePart.Text }.map(\.text) ?? []
        XCTAssertEqual(secondOutput.count, 2, "2nd call executes AND carries an appended reminder")
        XCTAssertEqual(secondOutput.first, "{\"ok\":true}")
        XCTAssertEqual(secondOutput.last, IOSToolLoopGuard.reminderText)

        let thirdOutput = toolParts.first(where: { $0.toolCallId == "tc-3" })?.output
            .compactMap { $0 as? UIMessagePart.Text }.first?.text ?? ""
        XCTAssertTrue(thirdOutput.contains("\"error\":\"tool_loop_guard_stopped\""), "output was: \(thirdOutput)")
        XCTAssertTrue(thirdOutput.contains("search"), "output was: \(thirdOutput)")
        // F5 fix: without `"ok":false`, this stop notice rendered as a
        // succeeded step in the tool timeline (`failureReason` only checks
        // `ok`/`denied`/`status`/`exit_code`).
        XCTAssertTrue(thirdOutput.contains("\"ok\":false"), "output was: \(thirdOutput)")
    }

    func testEngineDoesNotTripGuardForDistinctArguments() async {
        let provider = ScriptedProvider([
            toolCallMessage(toolCallId: "tc-1", toolName: "search", input: "{\"query\":\"a\"}"),
            toolCallMessage(toolCallId: "tc-2", toolName: "search", input: "{\"query\":\"b\"}"),
            toolCallMessage(toolCallId: "tc-3", toolName: "search", input: "{\"query\":\"c\"}"),
            message(role: MessageRole.assistant, parts: [UIMessagePart.Text(text: "done", metadata: nil)])
        ])
        let executor = RecordingExecutor(.filled("{\"ok\":true}"))
        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: ["search": executor],
            configuration: .init(maxSteps: 8)
        )

        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("look up three different things")],
            params: makeParams()
        )

        XCTAssertEqual(executor.calls.count, 3, "distinct arguments must never trip the guard")
        XCTAssertFalse(result.guardStopped)
    }

    func testPreExistingBatchPropagatesGuardStopAndResolvesRemainingTools() async {
        let repeatedInput = #"{"query":"same"}"#
        let preExisting = message(role: MessageRole.assistant, parts: [
            toolPart(toolCallId: "tc-1", toolName: "search", input: repeatedInput),
            toolPart(toolCallId: "tc-2", toolName: "search", input: repeatedInput),
            toolPart(toolCallId: "tc-3", toolName: "search", input: repeatedInput),
            toolPart(toolCallId: "tc-4", toolName: "workspace_write", input: #"{"path":"a"}"#),
        ])
        let provider = ScriptedProvider([])
        let searchExecutor = RecordingExecutor()
        let writeExecutor = RecordingExecutor()
        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: [
                "search": searchExecutor,
                "workspace_write": writeExecutor,
            ],
            configuration: .init(maxSteps: 8)
        )

        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [preExisting],
            params: makeParams()
        )

        XCTAssertTrue(result.guardStopped)
        XCTAssertEqual(result.stepsExecuted, 0)
        XCTAssertEqual(provider.callCount, 0, "a pre-existing guard stop must end before another model request")
        XCTAssertEqual(searchExecutor.calls.count, 2)
        XCTAssertEqual(writeExecutor.calls.count, 0, "tools after the terminal guard stop must never execute")

        let outputs = result.messages
            .flatMap(\.parts)
            .compactMap { $0 as? UIMessagePart.Tool }
        XCTAssertTrue(outputs.allSatisfy { !$0.output.isEmpty })
        let skipped = outputs.first { $0.toolCallId == "tc-4" }?.output
            .compactMap { $0 as? UIMessagePart.Text }
            .first?.text ?? ""
        XCTAssertTrue(skipped.contains("tool_not_executed"), "remaining tools need an explicit non-executed result")
    }

    // MARK: - F7: `ChatToolStepModel.firstJSONObject` survives "JSON + appended reminder text"

    /// `appendingToolLoopReminder` (tested above) appends the reminder as a
    /// SEPARATE `UIMessagePart.Text`, after the tool's own JSON output part —
    /// it never mutates that first part. The timeline summary/failure-
    /// detection helpers used to `joined(separator: "\n")` every Text part
    /// and parse the whole blob as one JSON object, which broke the instant a
    /// reminder was attached (valid JSON + a Chinese sentence is not valid
    /// JSON). `firstJSONObject` must instead find the tool's own JSON by
    /// trying each part independently.
    func testFirstJSONObjectParsesJSONPartEvenWithAppendedReminderText() {
        let parts: [UIMessagePart] = [
            UIMessagePart.Text(text: "{\"ok\":true,\"path\":\"/tmp/a\"}", metadata: nil),
            UIMessagePart.Text(text: IOSToolLoopGuard.reminderText, metadata: nil)
        ]

        let object = ChatToolStepModel.firstJSONObject(in: parts)
        XCTAssertEqual(object?["ok"] as? Bool, true, "must still recognize the tool's own JSON result")
        XCTAssertEqual(object?["path"] as? String, "/tmp/a")
    }

    func testFirstJSONObjectReturnsNilWhenNoPartParsesAsJSON() {
        let parts: [UIMessagePart] = [
            UIMessagePart.Text(text: "not json at all", metadata: nil)
        ]
        XCTAssertNil(ChatToolStepModel.firstJSONObject(in: parts))
    }

    func testFirstJSONObjectUnchangedForTheCommonSingleJSONPartCase() {
        let parts: [UIMessagePart] = [
            UIMessagePart.Text(text: "{\"exit_code\":0,\"stdout\":\"hi\"}", metadata: nil)
        ]
        let object = ChatToolStepModel.firstJSONObject(in: parts)
        XCTAssertEqual(object?["exit_code"] as? Int, 0)
        XCTAssertEqual(object?["stdout"] as? String, "hi")
    }
}
