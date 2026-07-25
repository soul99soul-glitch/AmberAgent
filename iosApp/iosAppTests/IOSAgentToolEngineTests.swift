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

    private enum GrokStubError: Error {
        case invoked
    }

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

    private func makeGrokProviderSetting() -> ProviderSetting.OpenAI {
        ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "Grok Web",
            models: [],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: "",
            baseUrl: IOSGrokWebConstants.webBaseUrl,
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

    final class ThrowingProvider: IOSAgentTextProvider, @unchecked Sendable {
        func generateText(
            providerSetting: ProviderSetting,
            messages: [UIMessage],
            params: TextGenerationParams
        ) async throws -> MessageChunk {
            throw NSError(
                domain: "AgentToolEngineTests",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "upstream unavailable"]
            )
        }
    }

    final class ScriptedStreamingProvider: IOSAgentTextProvider, IOSAgentStreamingProvider, @unchecked Sendable {
        let chunks: [MessageChunk]

        init(_ chunks: [MessageChunk]) {
            self.chunks = chunks
        }

        func generateText(
            providerSetting: ProviderSetting,
            messages: [UIMessage],
            params: TextGenerationParams
        ) async throws -> MessageChunk {
            chunks.last ?? MessageChunk(id: "empty", model: "test-model", choices: [], usage: nil)
        }

        func streamText(
            providerSetting: ProviderSetting,
            messages: [UIMessage],
            params: TextGenerationParams,
            onChunk: @escaping @Sendable (MessageChunk) -> Void,
            onComplete: @escaping @Sendable () -> Void,
            onError: @escaping @Sendable (KotlinThrowable) -> Void
        ) -> Kotlinx_coroutines_coreJob? {
            chunks.forEach(onChunk)
            onComplete()
            return nil
        }
    }

    final class DelayedCompletingStreamingProvider: IOSAgentTextProvider, IOSAgentStreamingProvider, @unchecked Sendable {
        let delayNanos: UInt64

        init(delayNanos: UInt64) {
            self.delayNanos = delayNanos
        }

        func generateText(
            providerSetting: ProviderSetting,
            messages: [UIMessage],
            params: TextGenerationParams
        ) async throws -> MessageChunk {
            MessageChunk(id: "unused", model: "test-model", choices: [], usage: nil)
        }

        func streamText(
            providerSetting: ProviderSetting,
            messages: [UIMessage],
            params: TextGenerationParams,
            onChunk: @escaping @Sendable (MessageChunk) -> Void,
            onComplete: @escaping @Sendable () -> Void,
            onError: @escaping @Sendable (KotlinThrowable) -> Void
        ) -> Kotlinx_coroutines_coreJob? {
            Task {
                try? await Task.sleep(nanoseconds: delayNanos)
                onComplete()
            }
            return nil
        }
    }

    final class PreparingStreamingProvider: IOSAgentTextProvider, IOSAgentStreamingProvider, @unchecked Sendable {
        private(set) var streamedAPIKey: String?

        func prepareRequest(
            providerSetting: ProviderSetting,
            params: TextGenerationParams
        ) async throws -> (ProviderSetting, TextGenerationParams) {
            guard let openAI = providerSetting as? ProviderSetting.OpenAI else {
                return (providerSetting, params)
            }
            return (
                ProviderSetting.OpenAI(
                    id: openAI.id,
                    enabled: openAI.enabled,
                    name: openAI.name,
                    models: openAI.models,
                    balanceOption: openAI.balanceOption,
                    builtIn: openAI.builtIn,
                    descriptionText: openAI.descriptionText,
                    shortDescriptionText: openAI.shortDescriptionText,
                    apiKey: "prepared-token",
                    baseUrl: openAI.baseUrl,
                    chatCompletionsPath: openAI.chatCompletionsPath,
                    useResponseApi: openAI.useResponseApi,
                    authMode: openAI.authMode,
                    brand: openAI.brand
                ),
                params
            )
        }

        func generateText(
            providerSetting: ProviderSetting,
            messages: [UIMessage],
            params: TextGenerationParams
        ) async throws -> MessageChunk {
            Self.chunk(text: "fallback")
        }

        func streamText(
            providerSetting: ProviderSetting,
            messages: [UIMessage],
            params: TextGenerationParams,
            onChunk: @escaping @Sendable (MessageChunk) -> Void,
            onComplete: @escaping @Sendable () -> Void,
            onError: @escaping @Sendable (KotlinThrowable) -> Void
        ) -> Kotlinx_coroutines_coreJob? {
            streamedAPIKey = (providerSetting as? ProviderSetting.OpenAI)?.apiKey
            onChunk(Self.chunk(text: "streamed"))
            onComplete()
            return nil
        }

        private static func chunk(text: String) -> MessageChunk {
            let message = UIMessage(
                id: KotlinUuid.companion.random(),
                role: MessageRole.assistant,
                parts: [UIMessagePart.Text(text: text, metadata: nil)],
                annotations: [],
                createdAt: Kotlinx_datetimeLocalDateTime(
                    year: 2026,
                    month: 6,
                    day: 19,
                    hour: 0,
                    minute: 0,
                    second: 0,
                    nanosecond: 0
                ),
                finishedAt: nil,
                modelId: nil,
                usage: nil,
                translation: nil
            )
            return MessageChunk(
                id: "chunk-\(UUID().uuidString)",
                model: "test-model",
                choices: [
                    UIMessageChoice(
                        index: 0,
                        delta: nil,
                        message: message,
                        finishReason: "stop"
                    ),
                ],
                usage: nil
            )
        }
    }

    final class AssistantTextRecorder: @unchecked Sendable {
        private let lock = NSLock()
        private var values: [String] = []

        func append(_ value: String) {
            lock.lock()
            values.append(value)
            lock.unlock()
        }

        var snapshot: [String] {
            lock.lock()
            defer { lock.unlock() }
            return values
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

    func testProviderFailureIsExposedWithoutParsingTheTranscript() async {
        let engine = IOSAgentToolEngine(provider: ThrowingProvider(), executors: [:])

        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("hello")],
            params: makeParams(tools: [])
        )

        XCTAssertEqual(result.providerFailureMessage, "upstream unavailable")
        XCTAssertTrue(result.messages.last?.toText().contains("[engine] provider error") == true)
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

    func testStreamingAssistantTextDoesNotSnapshotOnEveryChunk() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let appDirectory = testDirectory.deletingLastPathComponent().appendingPathComponent("iosApp")
        let source = try String(
            contentsOf: appDirectory.appendingPathComponent("IOSAgentToolEngine.swift"),
            encoding: .utf8
        )

        guard let onChunkStart = source.range(of: "onChunk: { chunk in"),
              let onCompleteStart = source.range(of: "onComplete:", range: onChunkStart.upperBound..<source.endIndex) else {
            return XCTFail("Expected streamStep to have onChunk before onComplete")
        }
        let onChunkBody = source[onChunkStart.upperBound..<onCompleteStart.lowerBound]
        XCTAssertFalse(
            onChunkBody.contains("accumulator.snapshot()"),
            "Background/sub-agent streaming must not snapshot+join the full accumulator on every chunk; long streams make this O(n²)."
        )
        XCTAssertTrue(
            source.contains("appendAssistantTextDelta"),
            "streamStep should maintain cumulative assistant text incrementally and publish that text without a per-chunk full snapshot."
        )
    }

    func testStreamingAssistantTextPublishesCumulativeTextAndHonorsFinalReplacement() async {
        func delta(_ text: String) -> MessageChunk {
            MessageChunk(
                id: UUID().uuidString,
                model: "test-model",
                choices: [UIMessageChoice(
                    index: 0,
                    delta: assistantText(text),
                    message: nil,
                    finishReason: nil
                )],
                usage: nil
            )
        }
        let final = MessageChunk(
            id: "final",
            model: "test-model",
            choices: [UIMessageChoice(
                index: 0,
                delta: nil,
                message: assistantText("Hello!"),
                finishReason: "stop"
            )],
            usage: nil
        )
        let recorder = AssistantTextRecorder()
        let engine = IOSAgentToolEngine(
            provider: ScriptedStreamingProvider([delta("Hel"), delta("lo"), final]),
            executors: [:]
        )

        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("ask")],
            params: makeParams(tools: []),
            onAssistantText: { recorder.append($0) }
        )

        XCTAssertEqual(recorder.snapshot, ["Hel", "Hello", "Hello!"])
        XCTAssertEqual(result.messages.last?.toText(), "Hello!")
    }

    func testStreamingOutputLimitIsSurfacedAsProviderFailure() async {
        let limited = MessageChunk(
            id: "limited",
            model: "test-model",
            choices: [UIMessageChoice(
                index: 0,
                delta: assistantText("未写完"),
                message: nil,
                finishReason: "length"
            )],
            usage: nil
        )
        let recorder = AssistantTextRecorder()
        let engine = IOSAgentToolEngine(
            provider: ScriptedStreamingProvider([limited]),
            executors: [:]
        )

        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("ask")],
            params: makeParams(tools: []),
            onAssistantText: { recorder.append($0) }
        )

        XCTAssertEqual(recorder.snapshot, ["未写完"])
        XCTAssertEqual(result.providerFailureMessage, "模型回复达到输出上限，请重试。")
    }

    func testCancellingEngineTaskStopsWaitingForStreamingTerminal() async {
        let engine = IOSAgentToolEngine(
            provider: DelayedCompletingStreamingProvider(delayNanos: 400_000_000),
            executors: [:]
        )
        let providerSetting = makeProviderSetting()
        let params = makeParams(tools: [])
        let messages = [userMessage("ask")]
        let finished = expectation(description: "cancelled engine returns without provider terminal")
        let task = Task {
            let result = await engine.run(
                providerSetting: providerSetting,
                messages: messages,
                params: params
            )
            finished.fulfill()
            return result
        }

        await Task.yield()
        task.cancel()
        await fulfillment(of: [finished], timeout: 0.15)
        let result = await task.value
        XCTAssertTrue(result.wasCancelled)
    }

    func testStreamingRequestPreparationRunsBeforeDispatch() async {
        let provider = PreparingStreamingProvider()
        let engine = IOSAgentToolEngine(provider: provider, executors: [:])

        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("ask")],
            params: makeParams(tools: [])
        )

        XCTAssertEqual(provider.streamedAPIKey, "prepared-token")
        XCTAssertEqual(result.messages.last?.toText(), "streamed")
    }

    func testGrokWebRouteDoesNotEnterKMPStreamingDispatch() {
        let adapter = OpenAIKmpProviderAdapter()

        XCTAssertFalse(adapter.supportsStreaming(providerSetting: makeGrokProviderSetting()))
    }

    func testGrokWebGenerationUsesNativeGenerator() async {
        let adapter = OpenAIKmpProviderAdapter { _, _, _ in
            throw GrokStubError.invoked
        }

        do {
            _ = try await adapter.generateText(
                providerSetting: makeGrokProviderSetting(),
                messages: [userMessage("ask")],
                params: makeParams(tools: [])
            )
            XCTFail("Expected the native Grok generator to be invoked")
        } catch GrokStubError.invoked {
            // Expected: Grok must not fall through to the OpenAI KMP adapter.
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
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
        XCTAssertEqual(provider.callCount, 2, "the engine must continue to the provider's final text turn")
        XCTAssertEqual(result.messages.last?.toText(), "done")
    }

    func testCompletedToolEchoIsRemovedWhenSameTurnAlsoContainsTextAndANewTool() async {
        let mixedTurn = makeMessage(
            role: MessageRole.assistant,
            parts: [
                UIMessagePart.Text(text: "I will search next.", metadata: nil),
                UIMessagePart.Tool(
                    toolCallId: "img-1",
                    toolName: "generate_image",
                    input: "{\"prompt\":\"cat\"}",
                    output: [],
                    approvalState: ToolApprovalState.Auto.shared,
                    streamIndex: nil,
                    metadata: nil
                ),
                UIMessagePart.Tool(
                    toolCallId: "search-1",
                    toolName: "search_web",
                    input: "{\"query\":\"cat care\"}",
                    output: [],
                    approvalState: ToolApprovalState.Auto.shared,
                    streamIndex: nil,
                    metadata: nil
                )
            ]
        )
        let provider = ScriptedProvider([mixedTurn, assistantText("done")])
        let imageExecutor = RecordingExecutor(.filled("{\"image\":true}"))
        let searchExecutor = RecordingExecutor(.filled("{\"results\":[]}"))
        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: [
                "generate_image": imageExecutor,
                "search_web": searchExecutor,
            ],
            configuration: .init(maxSteps: 4)
        )
        let input: [UIMessage] = [
            userMessage("draw, then research"),
            toolCallMessage(
                toolCallId: "img-1",
                toolName: "generate_image",
                input: "{\"prompt\":\"cat\"}"
            )
        ]

        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: input,
            params: makeParams(tools: ["generate_image", "search_web"])
        )

        XCTAssertEqual(imageExecutor.calls.count, 1)
        XCTAssertEqual(searchExecutor.calls.count, 1)
        XCTAssertEqual(provider.callCount, 2)
        XCTAssertEqual(result.messages.last?.toText(), "done")
        XCTAssertTrue(result.messages.contains { $0.toText().contains("I will search next.") })
        let toolParts = result.messages.flatMap { message in
            message.parts.compactMap { $0 as? UIMessagePart.Tool }
        }
        XCTAssertEqual(toolParts.filter { $0.toolCallId == "img-1" }.count, 1)
        XCTAssertEqual(toolParts.filter { $0.toolCallId == "search-1" }.count, 1)
        XCTAssertFalse(
            toolParts.first(where: { $0.toolCallId == "search-1" })?.output.isEmpty ?? true
        )
    }
}
