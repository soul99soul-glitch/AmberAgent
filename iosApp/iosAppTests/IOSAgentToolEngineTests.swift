import XCTest
@preconcurrency import Shared
@testable import iosApp

/// Unit tests for the reusable multi-turn tool execution engine.
///
/// These exercise the engine's loop mechanics with a scripted provider (no
/// live HTTP). The engine generalizes ChatViewModel's single-tool-per-round
/// loop; real provider + real tool wiring is validated by the chat/SubAgent/
/// Deep Read integration tests.
final class IOSAgentToolEngineTests: XCTestCase {

    // MARK: - Fixtures

    private func makeProviderSetting() -> ProviderSetting.OpenAI {
        // Full initializer: the KMP ProviderSetting.OpenAI bridge does not
        // expose Kotlin default args (same pattern as IOSLiveProviderSmokeTests).
        ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "engine-test",
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

    private func makeParams(tools: [String]) -> TextGenerationParams {
        // The engine decides whether to continue based on the tool calls the
        // model returns in each chunk, NOT on params.tools (the schema is only
        // sent to the model, which is scripted in tests). So an empty tool list
        // is fine here. Kotlin `Tool` cannot be constructed directly in Swift
        // anyway (its function-type fields don't bridge), so we pass [].
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
        // KMP UIMessage bridge does not expose Kotlin default args; pass the
        // full initializer (same pattern as ChatViewModel).
        let now = Kotlinx_datetimeLocalDateTime(
            year: 2026, month: 6, day: 19, hour: 0, minute: 0, second: 0, nanosecond: 0
        )
        return UIMessage(
            id: KotlinUuid.companion.random(),
            role: role,
            parts: parts,
            annotations: [],
            createdAt: now,
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

    private func toolCallMessage(toolCallId: String, toolName: String, input: String) -> UIMessage {
        makeMessage(
            role: MessageRole.assistant,
            parts: [UIMessagePart.Tool(
                toolCallId: toolCallId,
                toolName: toolName,
                input: input,
                output: [],
                approvalState: ToolApprovalState.Auto.shared,
                streamIndex: nil,
                metadata: nil
            )]
        )
    }

    private func chunk(with message: UIMessage?) -> MessageChunk {
        MessageChunk(
            id: "chunk-\(UUID().uuidString)",
            model: "test-model",
            choices: [UIMessageChoice(index: 0, delta: nil, message: message, finishReason: "stop")],
            usage: nil
        )
    }

    /// A scripted provider that returns a queue of assistant turns. The last
    /// turn is repeated if the engine keeps calling after the queue empties
    /// (lets us assert the loop terminated for the right reason).
    final class ScriptedProvider: IOSAgentTextProvider, @unchecked Sendable {
        private var script: [UIMessage]
        private(set) var callCount = 0
        init(_ script: [UIMessage]) { self.script = script }

        func generateText(
            providerSetting: ProviderSetting,
            messages: [UIMessage],
            params: TextGenerationParams
        ) async throws -> MessageChunk {
            callCount += 1
            if !script.isEmpty {
                return chunk(with: script.removeFirst())
            }
            // If the engine over-runs, return a plain text turn (no tools) so
            // the loop stops cleanly rather than looping forever.
            return chunk(with: UIMessage(
                id: KotlinUuid.companion.random(),
                role: MessageRole.assistant,
                parts: [UIMessagePart.Text(text: "stop", metadata: nil)],
                annotations: [],
                createdAt: Kotlinx_datetimeLocalDateTime(year: 2026, month: 6, day: 19, hour: 0, minute: 0, second: 0, nanosecond: 0),
                finishedAt: nil,
                modelId: nil,
                usage: nil,
                translation: nil
            ))
        }

        private func chunk(with message: UIMessage?) -> MessageChunk {
            MessageChunk(
                id: "chunk-\(UUID().uuidString)",
                model: "test-model",
                choices: [UIMessageChoice(index: 0, delta: nil, message: message, finishReason: "stop")],
                usage: nil
            )
        }
    }

    /// A scripted executor that returns a fixed result per tool name, and
    /// records every call so tests can assert dispatch.
    final class RecordingExecutor: IOSToolExecutor {
        let result: IOSAgentToolOutcome
        private(set) var calls: [(name: String, arguments: String, isUserInitiated: Bool)] = []
        init(_ result: IOSAgentToolOutcome) { self.result = result }

        func execute(name: String, arguments: String, isUserInitiated: Bool) async -> IOSAgentToolOutcome {
            calls.append((name, arguments, isUserInitiated))
            return result
        }
    }

    // MARK: - Tests

    func testLoopExecutesThenTerminatesWhenNoToolCallsRemain() async {
        // script: [tool_call(echo, "{}"), text("done")]
        let provider = ScriptedProvider([
            toolCallMessage(toolCallId: "tc-1", toolName: "echo", input: "{}"),
            assistantText("done")
        ])
        let executor = RecordingExecutor(.filled("{\"ok\":true}"))
        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: ["echo": executor],
            configuration: .init(maxSteps: 4)
        )

        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("hi")],
            params: makeParams(tools: ["echo"])
        )

        XCTAssertEqual(provider.callCount, 2, "engine should call provider once for the tool turn and once for the final text turn")
        XCTAssertEqual(executor.calls.count, 1)
        XCTAssertEqual(executor.calls.first?.name, "echo")
        XCTAssertNil(result.pendingApproval)
        XCTAssertFalse(result.hitStepLimit)
        XCTAssertEqual(result.stepsExecuted, 2)
        // Final message is the "done" assistant turn.
        let last = result.messages.last
        XCTAssertEqual(last?.role, MessageRole.assistant)
        XCTAssertTrue(last?.parts.first is UIMessagePart.Text)
    }

    func testToolOutputFilledInPlaceWithExecutorResult() async {
        let provider = ScriptedProvider([
            toolCallMessage(toolCallId: "tc-1", toolName: "echo", input: "{\"q\":\"x\"}"),
            assistantText("after")
        ])
        let executor = RecordingExecutor(.filled("{\"answer\":42}"))
        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: ["echo": executor],
            configuration: .init(maxSteps: 4)
        )

        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("ask")],
            params: makeParams(tools: ["echo"])
        )

        // The tool-call assistant message (now second in the list, after the
        // user message) must have its tool output filled.
        let toolMessage = result.messages[1]
        let toolPart = toolMessage.parts.compactMap { $0 as? UIMessagePart.Tool }.first
        XCTAssertNotNil(toolPart)
        XCTAssertFalse(toolPart?.output.isEmpty ?? true, "tool output must be filled after execution")
        let outputText = toolPart?.output.compactMap { $0 as? UIMessagePart.Text }.first?.text
        XCTAssertEqual(outputText, "{\"answer\":42}")
    }

    func testMultipleToolCallsInOneTurnAreAllExecutedBeforeNextRound() async {
        // One assistant turn carrying TWO tool calls, then a final text turn.
        let twoToolMessage = makeMessage(
            role: MessageRole.assistant,
            parts: [
                UIMessagePart.Tool(toolCallId: "a", toolName: "echo", input: "{}", output: [], approvalState: ToolApprovalState.Auto.shared, streamIndex: nil, metadata: nil),
                UIMessagePart.Tool(toolCallId: "b", toolName: "echo", input: "{}", output: [], approvalState: ToolApprovalState.Auto.shared, streamIndex: nil, metadata: nil)
            ]
        )
        let provider = ScriptedProvider([twoToolMessage, assistantText("done")])
        let executor = RecordingExecutor(.filled("{\"ok\":true}"))
        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: ["echo": executor],
            configuration: .init(maxSteps: 4)
        )

        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("both")],
            params: makeParams(tools: ["echo"])
        )

        XCTAssertEqual(executor.calls.count, 2, "both tool calls in the turn must execute")
        let toolMessage = result.messages[1]
        let toolParts = toolMessage.parts.compactMap { $0 as? UIMessagePart.Tool }
        XCTAssertEqual(toolParts.count, 2)
        XCTAssertTrue(toolParts.allSatisfy { !$0.output.isEmpty }, "both tool outputs must be filled")
    }

    func testHitStepLimitWhenModelKeepsEmittingToolCalls() async {
        // Script always returns a fresh tool call (queue of 10 > maxSteps 3).
        let endless = (0..<10).map { toolCallMessage(toolCallId: "tc-\($0)", toolName: "echo", input: "{}") }
        let provider = ScriptedProvider(endless)
        let executor = RecordingExecutor(.filled("{}"))
        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: ["echo": executor],
            configuration: .init(maxSteps: 3)
        )

        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("loop")],
            params: makeParams(tools: ["echo"])
        )

        XCTAssertTrue(result.hitStepLimit, "engine must stop at maxSteps when tool calls keep coming")
        XCTAssertEqual(result.stepsExecuted, 3)
    }

    func testApprovalPauseSurfacesPendingApprovalAndStopsLoop() async {
        let provider = ScriptedProvider([
            toolCallMessage(toolCallId: "tc-1", toolName: "sensitive", input: "{}"),
            assistantText("should-not-reach")
        ])
        let executor = RecordingExecutor(.needsApproval("requires foreground confirmation"))
        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: ["sensitive": executor],
            configuration: .init(maxSteps: 4, honorApprovalPause: true)
        )

        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("ask")],
            params: makeParams(tools: ["sensitive"])
        )

        XCTAssertEqual(provider.callCount, 1, "loop must stop after the approval-requesting turn")
        XCTAssertEqual(result.pendingApproval?.toolName, "sensitive")
        XCTAssertEqual(result.pendingApproval?.reason, "requires foreground confirmation")
        XCTAssertFalse(result.hitStepLimit)
    }

    func testDeniedAndFailedToolsProduceHonestOutputNotApproval() async {
        let twoToolMessage = makeMessage(
            role: MessageRole.assistant,
            parts: [
                UIMessagePart.Tool(toolCallId: "d", toolName: "deniedTool", input: "{}", output: [], approvalState: ToolApprovalState.Auto.shared, streamIndex: nil, metadata: nil),
                UIMessagePart.Tool(toolCallId: "f", toolName: "failedTool", input: "{}", output: [], approvalState: ToolApprovalState.Auto.shared, streamIndex: nil, metadata: nil)
            ]
        )
        let provider = ScriptedProvider([twoToolMessage, assistantText("done")])
        let deniedExecutor = RecordingExecutor(.denied("policy blocks this"))
        let failedExecutor = RecordingExecutor(.failed("network down"))
        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: ["deniedTool": deniedExecutor, "failedTool": failedExecutor],
            configuration: .init(maxSteps: 4)
        )

        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("ask")],
            params: makeParams(tools: ["deniedTool", "failedTool"])
        )

        XCTAssertNil(result.pendingApproval, "denial/failure must not pause the loop")
        let toolMessage = result.messages[1]
        let toolParts = toolMessage.parts.compactMap { $0 as? UIMessagePart.Tool }
        let deniedText = toolParts.first { $0.toolCallId == "d" }?.output.compactMap { $0 as? UIMessagePart.Text }.first?.text
        let failedText = toolParts.first { $0.toolCallId == "f" }?.output.compactMap { $0 as? UIMessagePart.Text }.first?.text
        XCTAssertEqual(deniedText, "{\"denied\":\"policy blocks this\"}")
        XCTAssertEqual(failedText, "{\"error\":\"network down\"}")
    }

    func testUnregisteredToolProducesFailureOutput() async {
        let provider = ScriptedProvider([
            toolCallMessage(toolCallId: "tc-1", toolName: "ghost", input: "{}"),
            assistantText("done")
        ])
        // No executor registered for "ghost".
        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: [:],
            configuration: .init(maxSteps: 4)
        )

        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("ask")],
            params: makeParams(tools: ["ghost"])
        )

        let toolPart = result.messages[1].parts.compactMap { $0 as? UIMessagePart.Tool }.first
        XCTAssertNotNil(toolPart)
        let output = toolPart?.output.compactMap { $0 as? UIMessagePart.Text }.first?.text
        XCTAssertEqual(output, "{\"error\":\"[engine] no executor registered for tool `ghost`\"}")
    }

    func testImageFailureReasonIgnoresSuccessfulImageOutputJSON() {
        let output: [UIMessagePart] = [
            UIMessagePart.Image(url: "amber-image://image-generation/file.png", metadata: nil),
            UIMessagePart.Text(
                text: #"{"count":1,"files":[{"url":"amber-image://image-generation/file.png"}],"source":"generate_image","status":"ok"}"#,
                metadata: nil
            )
        ]

        XCTAssertNil(ChatToolOutputFormatter.imageFailureReason(from: output))
    }

    func testImageFailureReasonKeepsExplicitFailureJSON() {
        let output: [UIMessagePart] = [
            UIMessagePart.Text(
                text: #"{"ok":false,"reason":"图片生成服务没有返回图片。","tool":"generate_image"}"#,
                metadata: nil
            )
        ]

        XCTAssertEqual(ChatToolOutputFormatter.imageFailureReason(from: output), "图片生成服务没有返回图片。")
    }

    // MARK: - Background handoff parity: pre-existing empty-output tools

    /// When a run hands off to background, the input messages may already carry
    /// an assistant turn with an empty-output tool call (the model decided to
    /// generate_image, but the foreground executor was interrupted before it ran).
    /// The engine must execute that pre-existing tool ONCE before streaming, so
    /// the background run does NOT cause the model to re-issue (and re-run) the
    /// same call — e.g. wasting a Codex image-generation. This is the
    /// "generate_image must not re-run in background" fix.
    func testPreExistingEmptyOutputToolIsExecutedBeforeStreaming() async {
        // Input already contains the assistant tool-call turn (output empty).
        // Provider script: only a final text turn — the model should NOT be
        // asked to re-emit the tool (it already exists in history).
        let provider = ScriptedProvider([assistantText("image is ready")])
        let executor = RecordingExecutor(.filledParts([
            UIMessagePart.Image(url: "data:image/png;base64,AAA", metadata: nil)
        ]))
        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: ["generate_image": executor],
            configuration: .init(maxSteps: 4)
        )

        let input: [UIMessage] = [
            userMessage("draw a cat"),
            toolCallMessage(toolCallId: "img-1", toolName: "generate_image", input: "{\"prompt\":\"cat\"}")
        ]

        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: input,
            params: makeParams(tools: ["generate_image"])
        )

        // The pre-existing tool must have been executed exactly once.
        XCTAssertEqual(executor.calls.count, 1, "pre-existing empty-output tool must execute once before streaming")
        XCTAssertEqual(executor.calls.first?.name, "generate_image")
        XCTAssertEqual(executor.calls.first?.arguments, "{\"prompt\":\"cat\"}")

        // Its output must be filled in place in the returned message list.
        let toolMessage = result.messages[1]
        let toolPart = toolMessage.parts.compactMap { $0 as? UIMessagePart.Tool }.first
        XCTAssertFalse(toolPart?.output.isEmpty ?? true, "pre-existing tool output must be filled")

        // The model must not be asked to re-emit the tool: the provider should
        // only have been called for the single scripted final text turn.
        XCTAssertEqual(provider.callCount, 1, "engine must not re-prompt the model to regenerate an already-present tool call")
    }

    /// Guard against double execution: a pre-existing empty-output tool that the
    /// model ALSO re-issues in its next turn (provider quirk) must not be
    /// executed twice — the in-place fill from pre-execution makes the model's
    /// re-issued copy collapse into the already-filled one rather than running
    /// the executor a second time.
    func testPreExecutedToolNotReRunIfModelReIssuesIt() async {
        // Provider re-emits the SAME tool call id in its first turn (a buggy /
        // streaming-merged provider echo), then a final text turn.
        let provider = ScriptedProvider([
            toolCallMessage(toolCallId: "img-1", toolName: "generate_image", input: "{\"prompt\":\"cat\"}"),
            assistantText("done")
        ])
        let executor = RecordingExecutor(.filledParts([
            UIMessagePart.Image(url: "data:image/png;base64,AAA", metadata: nil)
        ]))
        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: ["generate_image": executor],
            configuration: .init(maxSteps: 4)
        )

        let input: [UIMessage] = [
            userMessage("draw a cat"),
            toolCallMessage(toolCallId: "img-1", toolName: "generate_image", input: "{\"prompt\":\"cat\"}")
        ]

        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: input,
            params: makeParams(tools: ["generate_image"])
        )

        // Even though the model re-issued img-1, the executor must run only once.
        XCTAssertEqual(executor.calls.count, 1, "a tool whose output is already filled must not be re-executed")
    }
}
