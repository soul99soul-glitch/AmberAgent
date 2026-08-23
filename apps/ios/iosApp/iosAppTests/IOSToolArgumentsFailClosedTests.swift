import XCTest
@preconcurrency import Shared
@testable import iosApp

/// I-2 (fail-closed) coverage: a tool call whose `input` cannot be parsed as a
/// JSON object must never execute in any degraded form (empty object, partial
/// fields) — it must be turned into a structured error and handed back to the
/// model. See `docs/IOS_AGENT_HARDENING_PLAN_2026-07-29.md` §W2.
///
/// Two layers are exercised:
///  1. `UIMessagePart.Tool.parseInputStrict()` itself (the shared Kotlin/Swift
///     boundary type), with the four canonical malformed-argument shapes.
///  2. The `IOSAgentToolEngine.executeBatch` gate, which is the actual dispatch
///     choke point for the subagent/background-handoff tool loop: a scripted
///     executor records whether it was ever invoked, so these tests prove
///     "not executed" rather than merely "produced an error string".
final class IOSToolArgumentsFailClosedTests: XCTestCase {

    // MARK: - Fixtures (mirrors IOSAgentToolEngineTests' style)

    private func makeProviderSetting() -> ProviderSetting.OpenAI {
        ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "fail-closed-test",
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

    private func makeMessage(role: MessageRole, parts: [UIMessagePart]) -> UIMessage {
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

    private func userMessage(_ text: String) -> UIMessage {
        makeMessage(role: MessageRole.user, parts: [UIMessagePart.Text(text: text, metadata: nil)])
    }

    private func assistantText(_ text: String) -> UIMessage {
        makeMessage(role: MessageRole.assistant, parts: [UIMessagePart.Text(text: text, metadata: nil)])
    }

    private func toolPart(toolCallId: String, toolName: String, input: String) -> UIMessagePart.Tool {
        UIMessagePart.Tool(
            toolCallId: toolCallId,
            toolName: toolName,
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
    }

    private func toolCallMessage(toolCallId: String, toolName: String, input: String) -> UIMessage {
        makeMessage(role: MessageRole.assistant, parts: [toolPart(toolCallId: toolCallId, toolName: toolName, input: input)])
    }

    /// Scripted provider returning a fixed queue of assistant turns, falling
    /// back to a plain "stop" text turn if the engine over-runs the script.
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

    /// Records every call so a test can assert the executor was never reached.
    private final class RecordingExecutor: IOSToolExecutor {
        private(set) var calls: [(name: String, arguments: String, isUserInitiated: Bool)] = []
        private let result: IOSAgentToolOutcome
        init(_ result: IOSAgentToolOutcome = .filled("{\"ok\":true}")) { self.result = result }

        func execute(name: String, arguments: String, isUserInitiated: Bool) async -> IOSAgentToolOutcome {
            calls.append((name, arguments, isUserInitiated))
            return result
        }
    }

    // MARK: - Layer 1: parseInputStrict() itself

    func testDoubleWrittenJsonObjectsAreInvalid() {
        let tool = toolPart(toolCallId: "tc-1", toolName: "search", input: "{\"a\":1}{\"b\":2}")

        guard let invalid = tool.parseInputStrict() as? ToolInputParse.Invalid else {
            return XCTFail("Expected ToolInputParse.Invalid for a gateway double-write")
        }
        XCTAssertFalse(invalid.message.isEmpty)
        XCTAssertEqual(invalid.rawPrefix, "{\"a\":1}{\"b\":2}")
    }

    func testTruncatedTrailingJsonIsInvalid() {
        let tool = toolPart(toolCallId: "tc-2", toolName: "search", input: "{\"query\":\"ab")

        guard let invalid = tool.parseInputStrict() as? ToolInputParse.Invalid else {
            return XCTFail("Expected ToolInputParse.Invalid for truncated JSON")
        }
        XCTAssertFalse(invalid.message.isEmpty)
        XCTAssertEqual(invalid.rawPrefix, "{\"query\":\"ab")
    }

    func testBareNonJsonTextIsInvalid() {
        let tool = toolPart(toolCallId: "tc-3", toolName: "search", input: "not-json")

        // `json` in ai-core is configured `isLenient = true`, which parses a bare
        // word as a *string* JsonPrimitive rather than throwing — so this fixture
        // specifically exercises the "parsed but not an object" branch, not the
        // try/catch branch exercised by the two fixtures above.
        guard let invalid = tool.parseInputStrict() as? ToolInputParse.Invalid else {
            return XCTFail("Expected ToolInputParse.Invalid for bare non-JSON text")
        }
        XCTAssertFalse(invalid.message.isEmpty)
        XCTAssertEqual(invalid.rawPrefix, "not-json")
    }

    func testBlankInputIsValidEmptyObjectForNoArgTools() {
        let tool = toolPart(toolCallId: "tc-4", toolName: "no_arg_tool", input: "")

        // Only assert the case (Valid, not Invalid) — `JsonObject`'s bridged Swift
        // surface is a Kotlin collection wrapper, not a native Dictionary, and the
        // execution-gate tests below already exercise the "no-arg tool executes
        // normally" behavior end to end.
        XCTAssertNotNil(
            tool.parseInputStrict() as? ToolInputParse.Valid,
            "Expected ToolInputParse.Valid for blank input (no-arg tool call)"
        )
    }

    func testWhitespaceOnlyInputIsAlsoValidEmptyObject() {
        let tool = toolPart(toolCallId: "tc-5", toolName: "no_arg_tool", input: "   \n")

        guard (tool.parseInputStrict() as? ToolInputParse.Valid) != nil else {
            return XCTFail("Expected ToolInputParse.Valid for whitespace-only input")
        }
    }

    // MARK: - Layer 2: IOSAgentToolEngine.executeBatch gate

    func testEngineRefusesToDispatchDoubleWrittenArguments() async {
        let provider = ScriptedProvider([
            toolCallMessage(toolCallId: "tc-1", toolName: "search", input: "{\"a\":1}{\"b\":2}"),
            assistantText("done")
        ])
        let executor = RecordingExecutor()
        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: ["search": executor],
            configuration: .init(maxSteps: 4)
        )

        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("look this up")],
            params: makeParams()
        )

        XCTAssertEqual(executor.calls.count, 0, "a tool call with malformed arguments must never reach the executor")
        let toolPart = result.messages[1].parts.compactMap { $0 as? UIMessagePart.Tool }.first
        let outputText = toolPart?.output.compactMap { $0 as? UIMessagePart.Text }.first?.text ?? ""
        XCTAssertTrue(outputText.contains("\"error\":\"tool_arguments_invalid\""), "output was: \(outputText)")
        XCTAssertTrue(outputText.contains("\"raw_prefix\""), "output was: \(outputText)")
        // F5 fix: `ChatToolOutputFormatter.failureReason` only recognizes
        // failure via an explicit `"ok":false` (or denied/status/exit_code) —
        // without it this notice rendered as a succeeded timeline step.
        XCTAssertTrue(outputText.contains("\"ok\":false"), "output was: \(outputText)")
        // The engine must still resume the loop (the error is handed back to the
        // model as a normal tool result, not a hard stop).
        XCTAssertEqual(provider.callCount, 2)
        XCTAssertEqual(result.messages.last?.toText(), "done")
    }

    func testEngineRefusesToDispatchTruncatedArguments() async {
        let provider = ScriptedProvider([
            toolCallMessage(toolCallId: "tc-2", toolName: "search", input: "{\"query\":\"ab"),
            assistantText("done")
        ])
        let executor = RecordingExecutor()
        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: ["search": executor],
            configuration: .init(maxSteps: 4)
        )

        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("look this up")],
            params: makeParams()
        )

        XCTAssertEqual(executor.calls.count, 0, "a truncated tool call must never reach the executor")
        let toolPart = result.messages[1].parts.compactMap { $0 as? UIMessagePart.Tool }.first
        let outputText = toolPart?.output.compactMap { $0 as? UIMessagePart.Text }.first?.text ?? ""
        XCTAssertTrue(outputText.contains("\"error\":\"tool_arguments_invalid\""), "output was: \(outputText)")
    }

    func testEngineRefusesToDispatchBareNonJsonArguments() async {
        let provider = ScriptedProvider([
            toolCallMessage(toolCallId: "tc-3", toolName: "search", input: "not-json"),
            assistantText("done")
        ])
        let executor = RecordingExecutor()
        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: ["search": executor],
            configuration: .init(maxSteps: 4)
        )

        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("look this up")],
            params: makeParams()
        )

        XCTAssertEqual(executor.calls.count, 0, "bare non-JSON tool arguments must never reach the executor")
        let toolPart = result.messages[1].parts.compactMap { $0 as? UIMessagePart.Tool }.first
        let outputText = toolPart?.output.compactMap { $0 as? UIMessagePart.Text }.first?.text ?? ""
        XCTAssertTrue(outputText.contains("\"error\":\"tool_arguments_invalid\""), "output was: \(outputText)")
    }

    func testEngineExecutesNormallyForBlankNoArgToolCall() async {
        let provider = ScriptedProvider([
            toolCallMessage(toolCallId: "tc-4", toolName: "no_arg_tool", input: ""),
            assistantText("done")
        ])
        let executor = RecordingExecutor(.filled("{\"ok\":true}"))
        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: ["no_arg_tool": executor],
            configuration: .init(maxSteps: 4)
        )

        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("run the no-arg tool")],
            params: makeParams()
        )

        XCTAssertEqual(executor.calls.count, 1, "a no-arg tool call (blank input) must execute normally")
        XCTAssertEqual(executor.calls.first?.arguments, "")
        let toolPart = result.messages[1].parts.compactMap { $0 as? UIMessagePart.Tool }.first
        let outputText = toolPart?.output.compactMap { $0 as? UIMessagePart.Text }.first?.text
        XCTAssertEqual(outputText, "{\"ok\":true}")
    }
}
