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

    private func assistantReasoning(_ text: String) -> UIMessage {
        let instant = KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: 0)
        return makeMessage(
            role: MessageRole.assistant,
            parts: [UIMessagePart.Reasoning(
                reasoning: text,
                createdAt: instant,
                finishedAt: nil,
                metadata: nil
            )]
        )
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

    /// A scripted provider that also records every `params` it was called
    /// with, so tests can assert what the engine declared on later rounds
    /// (P0-a: tool_search hits must be visible on the NEXT round's params).
    final class ParamsRecordingProvider: IOSAgentTextProvider, @unchecked Sendable {
        private var script: [UIMessage]
        private(set) var recordedParams: [TextGenerationParams] = []
        init(_ script: [UIMessage]) { self.script = script }

        func generateText(
            providerSetting: ProviderSetting,
            messages: [UIMessage],
            params: TextGenerationParams
        ) async throws -> MessageChunk {
            recordedParams.append(params)
            if !script.isEmpty {
                return chunk(with: script.removeFirst())
            }
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

    /// Executes tool_search through a real KMP exposure bridge (local, no
    /// network) so the hit becomes visible inside the bridge.
    final class BridgeToolSearchExecutor: IOSToolExecutor {
        private let bridge: IosToolExposureBridge
        init(bridge: IosToolExposureBridge) { self.bridge = bridge }

        func execute(
            name: String,
            arguments: String,
            isUserInitiated: Bool
        ) async -> IOSAgentToolOutcome {
            .filled(bridge.executeToolSearch(argumentsJson: arguments))
        }
    }

    /// Every tool name `iosToolDeclaration` can materialize (>40 → lazy mode).
    private func fullIosDeclarations() -> [Tool] {
        let names =
            IOSWorkspaceToolCatalog.supportedToolNames
            .union(IOSIshToolCatalog.supportedToolNames)
            .union(IOSEmbeddedIshToolCatalog.supportedToolNames)
            .union(IOSWebMountToolCatalog.supportedToolNames)
            .union(IOSSkillToolCatalog.toolNames)
            .union(IOSMcpManagementToolCatalog.toolNames)
            .union([
                "search_web", "scrape_web", "memory_tool", "generate_image",
                "mcp_call", "subagent_dispatch", "model_council_run", "ask_user",
            ])
        return ToolKt.iosToolDeclarations(names: Array(names).sorted())
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

    final class AssistantStageRecorder: @unchecked Sendable {
        private let lock = NSLock()
        private var values: [AgentActivityStage] = []

        func append(_ value: AgentActivityStage) {
            lock.lock()
            values.append(value)
            lock.unlock()
        }

        var snapshot: [AgentActivityStage] {
            lock.lock()
            defer { lock.unlock() }
            return values
        }
    }

    actor ToolStageGate {
        private var entered = false
        private var continuation: CheckedContinuation<Void, Never>?

        func suspend() async {
            entered = true
            await withCheckedContinuation { continuation in
                self.continuation = continuation
            }
        }

        func waitUntilEntered() async {
            while !entered {
                await Task.yield()
            }
        }

        func resume() {
            continuation?.resume()
            continuation = nil
        }
    }

    actor RecordingLedger: IOSAgentRunLedgering {
        private var finishedOutcomes: [String] = []

        func recordToolCallStarted(
            runId: String,
            toolCallId: String,
            toolName: String,
            argsDigest: String,
            effectClass: IOSToolEffectClass
        ) async -> Bool {
            true
        }

        func recordToolCallFinished(
            runId: String,
            toolCallId: String,
            outcome: String
        ) async {
            await recordToolCallFinished(
                runId: runId, toolCallId: toolCallId, outcome: outcome,
                artifactId: nil, artifactVersion: nil, outcomeKind: nil, errorCode: nil, sourceRef: nil
            )
        }

        func recordToolCallFinished(
            runId: String,
            toolCallId: String,
            outcome: String,
            artifactId: String? = nil,
            artifactVersion: String? = nil,
            outcomeKind: String? = nil,
            errorCode: String? = nil,
            sourceRef: String? = nil
        ) async {
            finishedOutcomes.append(outcome)
        }

        func recordApprovalDenied(
            runId: String,
            toolCallId: String,
            toolName: String,
            reason: String,
            capabilityId: String?
        ) async {
        }

        func outcomes() -> [String] {
            finishedOutcomes
        }
    }

    /// A scripted executor that returns a fixed result per tool name, and
    /// records every call so tests can assert dispatch.
    final class RecordingExecutor: IOSToolExecutor {
        let result: IOSAgentToolOutcome
        private let events: AssistantTextRecorder?
        private(set) var calls: [(name: String, arguments: String, isUserInitiated: Bool)] = []
        init(_ result: IOSAgentToolOutcome, events: AssistantTextRecorder? = nil) {
            self.result = result
            self.events = events
        }

        func execute(name: String, arguments: String, isUserInitiated: Bool) async -> IOSAgentToolOutcome {
            calls.append((name, arguments, isUserInitiated))
            events?.append("execute:\(name)")
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
        XCTAssertFalse(result.hitOutputLimit)
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
        // Arguments differ per call (`{"i":N}`) so this exercises step-limit
        // exhaustion specifically — with identical arguments, IOSToolLoopGuard
        // (I-5) would now stop the run on the 3rd repeat before maxSteps is
        // ever reached, which is a different (and separately tested) terminal.
        let endless = (0..<10).map { toolCallMessage(toolCallId: "tc-\($0)", toolName: "echo", input: "{\"i\":\($0)}") }
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

    func testApprovalPausePreservesEarlierOutputsFromSameBatch() async {
        let mixedToolMessage = makeMessage(
            role: MessageRole.assistant,
            parts: [
                UIMessagePart.Tool(
                    toolCallId: "search",
                    toolName: "search_web",
                    input: "{}",
                    output: [],
                    approvalState: ToolApprovalState.Auto.shared,
                    streamIndex: nil,
                    metadata: nil
                ),
                UIMessagePart.Tool(
                    toolCallId: "approval",
                    toolName: "ask_user",
                    input: "{}",
                    output: [],
                    approvalState: ToolApprovalState.Auto.shared,
                    streamIndex: nil,
                    metadata: nil
                )
            ]
        )
        let provider = ScriptedProvider([mixedToolMessage, assistantText("should-not-reach")])
        let searchExecutor = RecordingExecutor(.filled("{\"results\":[\"source\"]}"))
        let approvalExecutor = RecordingExecutor(.needsApproval("requires user choice"))
        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: [
                "search_web": searchExecutor,
                "ask_user": approvalExecutor
            ],
            configuration: .init(maxSteps: 4, honorApprovalPause: true)
        )

        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("search, then ask")],
            params: makeParams(tools: ["search_web", "ask_user"])
        )

        XCTAssertEqual(provider.callCount, 1)
        XCTAssertEqual(searchExecutor.calls.count, 1)
        XCTAssertEqual(approvalExecutor.calls.count, 1)
        XCTAssertEqual(result.pendingApproval?.toolName, "ask_user")

        let toolParts = result.messages[1].parts.compactMap { $0 as? UIMessagePart.Tool }
        let searchOutput = toolParts
            .first { $0.toolCallId == "search" }?
            .output.compactMap { $0 as? UIMessagePart.Text }.first?.text
        let approvalOutput = toolParts.first { $0.toolCallId == "approval" }?.output
        XCTAssertEqual(searchOutput, "{\"results\":[\"source\"]}")
        XCTAssertTrue(approvalOutput?.isEmpty == true)
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

    func testToolSearchHitIsDeclaredOnNextEngineRoundViaRunBridge() async {
        let declarations = fullIosDeclarations()
        let bridge = IosToolExposureBridge(tools: declarations)
        XCTAssertTrue(bridge.lazyModeEnabled())
        XCTAssertFalse(bridge.visibleTools().map(\.name).contains("wm_type"))

        // Round 1: the model calls tool_search (executed locally through the
        // real bridge, which exposes wm_type). Round 2: plain text "done".
        let provider = ParamsRecordingProvider([
            toolCallMessage(toolCallId: "ts-1", toolName: "tool_search", input: #"{"query":"wm_type","limit":1}"#),
            assistantText("done")
        ])
        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: ["tool_search": BridgeToolSearchExecutor(bridge: bridge)]
        )
        let model = Model(
            modelId: "engine-exposure-test",
            displayName: "engine-exposure-test",
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
        let params = TextGenerationParams(
            model: model,
            temperature: nil,
            topP: nil,
            maxTokens: nil,
            tools: bridge.visibleTools(),
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )

        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("use wm_type")],
            params: params,
            toolExposureBridge: bridge
        )

        XCTAssertNil(result.pendingApproval)
        XCTAssertFalse(result.wasCancelled)
        XCTAssertFalse(result.hitStepLimit)
        XCTAssertGreaterThanOrEqual(provider.recordedParams.count, 2, "engine must run a second model round after tool_search")
        let secondRoundNames = Set(provider.recordedParams[1].tools.map(\.name))
        XCTAssertTrue(
            secondRoundNames.contains("wm_type"),
            "the tool_search hit must be declared on the NEXT engine round's params"
        )
        XCTAssertFalse(
            result.messages.contains { message in
                message.parts.contains { ($0 as? UIMessagePart.Tool)?.output.isEmpty == true }
            },
            "all tool calls must be filled before the engine stops"
        )
    }

    // MARK: - M2: 每轮 replacingTools 后 executor 表重建（命中工具下一轮可执行）

    /// 红测试对应缺陷：executors 在 job 启动时按初始 params 注册一次，P0-a Fix C
    /// 每轮刷新 params 后命中工具「可声明不可执行」（[engine] no executor registered）。
    /// 修复后：传了 toolExposureBridge + executorRebuilder 的路径每轮重建表，
    /// mock 宿主断言第二轮 wm_type 真实被调用。
    func testExposedToolGetsExecutorOnNextRoundViaRebuilder() async {
        let declarations = fullIosDeclarations()
        let bridge = IosToolExposureBridge(tools: declarations)
        XCTAssertTrue(bridge.lazyModeEnabled())
        XCTAssertFalse(bridge.visibleTools().map(\.name).contains("wm_type"))

        // 第一轮 tool_search 命中 wm_type；第二轮模型直接调 wm_type；第三轮收尾。
        let provider = ParamsRecordingProvider([
            toolCallMessage(toolCallId: "ts-1", toolName: "tool_search", input: #"{"query":"wm_type","limit":1}"#),
            toolCallMessage(toolCallId: "wm-1", toolName: "wm_type", input: #"{"keys":"hello"}"#),
            assistantText("done")
        ])
        let wmTypeExecutor = RecordingExecutor(.filled("{\"ok\":true,\"typed\":true}"))
        // 与后台协调器同构的注册入口：按当轮 params.tools 注册（闭包捕获当轮 params）。
        let rebuilder: (TextGenerationParams) -> [String: any IOSToolExecutor] = { params in
            var executors: [String: any IOSToolExecutor] = [:]
            for tool in params.tools {
                if tool.name == "tool_search" {
                    executors["tool_search"] = BridgeToolSearchExecutor(bridge: bridge)
                } else if tool.name == "wm_type" {
                    executors["wm_type"] = wmTypeExecutor
                }
            }
            return executors
        }
        // 初始 params = 首轮可见工具（后台 handoff 同构：tool_search 在列）。
        let initialParams = TextGenerationParams(
            model: Model(
                modelId: "engine-m2-test",
                displayName: "engine-m2-test",
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
            ),
            temperature: KotlinFloat(value: 0.7),
            topP: nil,
            maxTokens: nil,
            tools: bridge.visibleTools(),
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: rebuilder(initialParams),
            configuration: .init(maxSteps: 6, honorApprovalPause: false),
            executorRebuilder: rebuilder
        )

        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("use wm_type")],
            params: initialParams,
            toolExposureBridge: bridge
        )

        XCTAssertNil(result.pendingApproval)
        XCTAssertFalse(result.wasCancelled)
        XCTAssertFalse(result.hitStepLimit)
        XCTAssertGreaterThanOrEqual(provider.recordedParams.count, 3, "引擎必须跑三轮：tool_search → wm_type → 终答")
        XCTAssertEqual(
            wmTypeExecutor.calls.count, 1,
            "tool_search 命中后下一轮该工具必须声明且可执行（mock 宿主断言真实调用），实际: \(wmTypeExecutor.calls.count)"
        )
        XCTAssertEqual(wmTypeExecutor.calls.first?.name, "wm_type")
        XCTAssertFalse(
            result.messages.contains { message in
                message.parts.contains { ($0 as? UIMessagePart.Tool)?.output.isEmpty == true }
            },
            "所有工具调用必须填满（wm_type 不得落 [engine] no executor registered）"
        )
        let allOutput = result.messages.flatMap { message in
            message.parts.compactMap { ($0 as? UIMessagePart.Tool)?.output.compactMap { ($0 as? UIMessagePart.Text)?.text } }
        }.flatMap { $0 }.joined()
        XCTAssertFalse(allOutput.contains("no executor registered"), "命中工具不得走未注册失败")
        XCTAssertTrue(allOutput.contains("\"typed\":true"), "wm_type 必须真实执行并回灌结果")
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
            source.contains("appendAssistantDelta"),
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

    func testStreamingAssistantStagePublishesReasoningThenResponse() async {
        func delta(_ message: UIMessage) -> MessageChunk {
            MessageChunk(
                id: UUID().uuidString,
                model: "test-model",
                choices: [UIMessageChoice(
                    index: 0,
                    delta: message,
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
                message: assistantText("答案"),
                finishReason: "stop"
            )],
            usage: nil
        )
        let recorder = AssistantStageRecorder()
        let engine = IOSAgentToolEngine(
            provider: ScriptedStreamingProvider([
                delta(assistantReasoning("先分析")),
                delta(assistantText("答")),
                final,
            ]),
            executors: [:]
        )

        _ = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("ask")],
            params: makeParams(tools: []),
            onAssistantStage: { recorder.append($0) }
        )

        XCTAssertEqual(recorder.snapshot, [.thinking, .generating])
    }

    func testStreamingAssistantReasoningCallbackAccumulates() async {
        func delta(_ message: UIMessage) -> MessageChunk {
            MessageChunk(
                id: UUID().uuidString,
                model: "test-model",
                choices: [UIMessageChoice(
                    index: 0,
                    delta: message,
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
                message: assistantText("答案"),
                finishReason: "stop"
            )],
            usage: nil
        )
        let recorder = AssistantTextRecorder()
        let engine = IOSAgentToolEngine(
            provider: ScriptedStreamingProvider([
                delta(assistantReasoning("先")),
                delta(assistantReasoning("分析")),
                delta(assistantText("答")),
                final,
            ]),
            executors: [:]
        )

        _ = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("ask")],
            params: makeParams(tools: []),
            onAssistantReasoning: { recorder.append($0) }
        )

        XCTAssertEqual(recorder.snapshot, ["先", "先分析"])
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
        XCTAssertTrue(result.hitOutputLimit)
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

    func testExecutionCallbacksBracketPreExistingAndFreshToolsInOrder() async {
        let provider = ScriptedProvider([
            toolCallMessage(
                toolCallId: "search-1",
                toolName: "search_web",
                input: "{\"query\":\"cat care\"}"
            ),
            assistantText("done")
        ])
        let events = AssistantTextRecorder()
        let imageExecutor = RecordingExecutor(.filled("{\"image\":true}"), events: events)
        let searchExecutor = RecordingExecutor(.filled("{\"results\":[]}"), events: events)
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
                toolCallId: "image-1",
                toolName: "generate_image",
                input: "{\"prompt\":\"cat\"}"
            ),
        ]

        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: input,
            params: makeParams(tools: ["generate_image", "search_web"]),
            onAssistantTurnStarted: {
                events.append("assistant")
            },
            onToolExecutionStarted: { toolName in
                events.append("tool:\(toolName)")
            }
        )

        XCTAssertEqual(
            events.snapshot,
            [
                "tool:generate_image",
                "execute:generate_image",
                "assistant",
                "tool:search_web",
                "execute:search_web",
                "assistant",
            ]
        )
        XCTAssertEqual(imageExecutor.calls.count, 1)
        XCTAssertEqual(searchExecutor.calls.count, 1)
        XCTAssertEqual(result.messages.last?.toText(), "done")
    }

    func testCancellationDuringToolStageCallbackDoesNotStartExecutor() async {
        let provider = ScriptedProvider([assistantText("should not run")])
        let executor = RecordingExecutor(.filled("{\"ok\":true}"))
        let ledger = RecordingLedger()
        let gate = ToolStageGate()
        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: ["generate_image": executor],
            configuration: .init(maxSteps: 4),
            ledger: ledger,
            ledgerRunId: "cancel-before-execution"
        )
        let input: [UIMessage] = [
            userMessage("draw a cat"),
            toolCallMessage(
                toolCallId: "image-1",
                toolName: "generate_image",
                input: "{\"prompt\":\"cat\"}"
            ),
        ]
        let providerSetting = makeProviderSetting()
        let params = makeParams(tools: ["generate_image"])
        let task = Task {
            await engine.run(
                providerSetting: providerSetting,
                messages: input,
                params: params,
                onToolExecutionStarted: { _ in
                    await gate.suspend()
                }
            )
        }

        await gate.waitUntilEntered()
        task.cancel()
        await gate.resume()
        let result = await task.value
        let ledgerOutcomes = await ledger.outcomes()

        XCTAssertTrue(result.wasCancelled)
        XCTAssertEqual(executor.calls.count, 0)
        XCTAssertEqual(provider.callCount, 0)
        XCTAssertEqual(ledgerOutcomes, ["cancelled_before_execution"])
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

    /// P3-a: exec 工具循环集成——脚本化 provider 让模型调用 exec，真实
    /// JavaScriptCore 沙箱求值（IOSJsSandboxEngine），工具输出以
    /// `{result, logs}` 回填并折入下一轮（完整 engine 循环，fake provider）。
    func testExecToolRunsThroughEngineLoopWithRealSandbox() async {
        let provider = ScriptedProvider([
            toolCallMessage(
                toolCallId: "tc-exec",
                toolName: "exec",
                input: #"{"code":"console.log('hi'); 6 * 7"}"#
            ),
            assistantText("done")
        ])
        let executor = RealJsSandboxExecutor()
        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: ["exec": executor],
            configuration: .init(maxSteps: 4)
        )

        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("run js")],
            params: makeParams(tools: ["exec"])
        )

        XCTAssertEqual(provider.callCount, 2, "engine should run the tool turn then the final text turn")
        XCTAssertEqual(executor.calls.count, 1)
        XCTAssertEqual(executor.calls.first?.name, "exec")
        let toolMessage = result.messages[1]
        let toolPart = toolMessage.parts.compactMap { $0 as? UIMessagePart.Tool }.first
        let outputText = toolPart?.output.compactMap { $0 as? UIMessagePart.Text }.first?.text ?? ""
        XCTAssertTrue(outputText.contains("\"result\":\"42\""), "tool output must carry the evaluated result: \(outputText)")
        XCTAssertTrue(outputText.contains("[LOG] hi"), "tool output must carry console capture: \(outputText)")
    }

    // MARK: - P2-c: background engine citation stripping

    /// P2-c 修复 1：后台引擎（`IOSAgentToolEngine`）流式输出含
    /// `<amber-mem-cite>` 隐藏标记时，必须像前台一样在持久化前剥离——终态消息
    /// 无标记、正文保留；引用 id 经 `markUsed(force:)`（bg 接线同款调用）落盘
    /// 可读回。脚本化 streaming provider 驱动两轮：第一轮标签跨 chunk 拆分 +
    /// 工具调用，第二轮再一个标签。
    @MainActor
    func testBackgroundEngineStripsCitationTagsAcrossRoundsAndMarksUsed() async throws {
        try await withIsolatedPersistence { persistence, fileURL in
            IosMemoryFactory.shared.replaceAll(records: [
                makeMemoryRecord(id: 1, content: "favorite color blue", updatedAt: 10),
                makeMemoryRecord(id: 2, content: "green project", updatedAt: 20),
                makeMemoryRecord(id: 3, content: "untouched", updatedAt: 30),
            ])

            let round1Text = #"Your favorite color is blue. <amber-mem-cite>{"ids":[1]}</amber-mem-cite> And green too."#
            let round2Text = #"<amber-mem-cite>{"ids":[2],"note":"fav"}</amber-mem-cite> Done."#
            let engine = IOSAgentToolEngine(
                provider: TwoRoundScriptedStreamingProvider(
                    round1: [
                        streamingDelta(#"Your favorite color is blue. <amber-mem-cite>{"ids":[1]}"#),
                        streamingDelta(#"</amber-mem-cite> And green too."#),
                        streamingFinal(makeMessage(
                            role: MessageRole.assistant,
                            parts: [
                                UIMessagePart.Text(text: round1Text, metadata: nil),
                                UIMessagePart.Tool(
                                    toolCallId: "tc-1",
                                    toolName: "echo",
                                    input: "{}",
                                    output: [],
                                    approvalState: ToolApprovalState.Auto.shared,
                                    streamIndex: nil,
                                    metadata: nil
                                ),
                            ]
                        )),
                    ],
                    round2: [
                        streamingDelta(#"<amber-mem-cite>{"id"#),
                        streamingDelta(#"s":[2],"note":"fav"}</amber-mem-cite> Done."#),
                        streamingFinal(assistantText(round2Text)),
                    ]
                ),
                executors: ["echo": RecordingExecutor(.filled("{\"ok\":true}"))],
                configuration: .init(maxSteps: 4)
            )
            let tracker = IOSMemoryCitationTracker()
            let result = await engine.run(
                providerSetting: makeProviderSetting(),
                messages: [userMessage("ask")],
                params: makeParams(tools: ["echo"]),
                citationTracker: tracker
            )

            // 两轮都跑完、工具输出已填、正常终态。
            XCTAssertEqual(result.stepsExecuted, 2)
            XCTAssertFalse(result.hitStepLimit)
            XCTAssertNil(result.pendingApproval)
            XCTAssertFalse(result.wasCancelled)

            // 终态消息链（要持久化的内容）无标签；剥离后正文保留。
            let assistantTexts = result.messages
                .filter { $0.role == MessageRole.assistant }
                .flatMap { message in message.parts.compactMap { ($0 as? UIMessagePart.Text)?.text } }
                .joined()
            XCTAssertFalse(assistantTexts.contains("<amber-mem-cite>"))
            XCTAssertTrue(assistantTexts.contains("Your favorite color is blue."))
            XCTAssertTrue(assistantTexts.contains("And green too."))
            XCTAssertTrue(assistantTexts.contains(" Done."))

            // 引用 id 收集齐全 → 与前台同款 markUsed（bg 接线 force: true）落盘读回。
            XCTAssertEqual(tracker.citationIds, Set<Int32>([1, 2]))
            XCTAssertTrue(persistence.markUsed(ids: tracker.citationIds, now: 999, force: true))
            let reader = IOSMemoryPersistence(fileURL: fileURL)
            reader.load()
            XCTAssertEqual(reader.loadState, .loaded)
            XCTAssertEqual(reader.records.first { $0.id == 1 }?.lastUsedAt?.int64Value, 999)
            XCTAssertEqual(reader.records.first { $0.id == 2 }?.lastUsedAt?.int64Value, 999)
            XCTAssertNil(reader.records.first { $0.id == 3 }?.lastUsedAt)
        }
    }

    // MARK: - P2-c fixtures

    /// 每轮（一次 streamStep）回放一组 chunk 的两轮 streaming provider。
    private final class TwoRoundScriptedStreamingProvider: IOSAgentTextProvider, IOSAgentStreamingProvider, @unchecked Sendable {
        private let round1: [MessageChunk]
        private let round2: [MessageChunk]
        private var roundIndex = 0

        init(round1: [MessageChunk], round2: [MessageChunk]) {
            self.round1 = round1
            self.round2 = round2
        }

        func generateText(
            providerSetting: ProviderSetting,
            messages: [UIMessage],
            params: TextGenerationParams
        ) async throws -> MessageChunk {
            roundIndex += 1
            return (roundIndex == 1 ? round1 : round2).last
                ?? MessageChunk(id: "empty", model: "test-model", choices: [], usage: nil)
        }

        func streamText(
            providerSetting: ProviderSetting,
            messages: [UIMessage],
            params: TextGenerationParams,
            onChunk: @escaping @Sendable (MessageChunk) -> Void,
            onComplete: @escaping @Sendable () -> Void,
            onError: @escaping @Sendable (KotlinThrowable) -> Void
        ) -> Kotlinx_coroutines_coreJob? {
            roundIndex += 1
            let chunks = roundIndex == 1 ? round1 : round2
            chunks.forEach(onChunk)
            onComplete()
            return nil
        }
    }

    private func streamingDelta(_ text: String) -> MessageChunk {
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

    private func streamingFinal(_ message: UIMessage) -> MessageChunk {
        MessageChunk(
            id: UUID().uuidString,
            model: "test-model",
            choices: [UIMessageChoice(
                index: 0,
                delta: nil,
                message: message,
                finishReason: "stop"
            )],
            usage: nil
        )
    }

    @MainActor
    private func withIsolatedPersistence(
        _ body: (IOSMemoryPersistence, URL) async throws -> Void
    ) async throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSAgentToolEngineTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let fileURL = root.appendingPathComponent("memories.json")
        defer {
            try? FileManager.default.removeItem(at: root)
        }
        let originalRecords = IosMemoryFactory.shared.snapshotRecords()
        defer { IosMemoryFactory.shared.replaceAll(records: originalRecords) }
        IosMemoryFactory.shared.replaceAll(records: [])
        let persistence = IOSMemoryPersistence(fileURL: fileURL)
        persistence.load()
        try await body(persistence, fileURL)
    }

    private func makeMemoryRecord(id: Int32, content: String, updatedAt: Int64 = 0) -> MemoryRecord {
        MemoryRecord(
            id: id,
            content: content,
            scope: MemoryScope.core,
            kind: MemoryKind.user,
            assistantId: "__global__",
            sourceConversationId: nil,
            sourceMessageIds: [],
            supersedesIds: [],
            expiresAt: nil,
            confidence: 1,
            pinned: false,
            archived: false,
            createdAt: updatedAt,
            updatedAt: updatedAt,
            lastUsedAt: nil
        )
    }
}

/// P3-a: executor that runs `exec` calls through the real JS sandbox engine
/// (same contract as the production dispatch: parse code from arguments,
/// clamp timeout, format the payload with truncation).
private final class RealJsSandboxExecutor: IOSToolExecutor {
    private(set) var calls: [(name: String, arguments: String)] = []

    func execute(name: String, arguments: String, isUserInitiated: Bool) async -> IOSAgentToolOutcome {
        calls.append((name, arguments))
        let sandbox = IOSJsSandboxEngine()
        let args = ChatToolCallParsing.jsonObject(arguments)
        let code = (args?["code"] as? String) ?? "undefined"
        let timeoutMs = IOSJsSandboxEngine.clampTimeoutMs(
            (args?["timeout_ms"] as? Int) ?? IOSJsSandboxEngine.defaultTimeoutMs
        )
        let result = await sandbox.evaluate(
            code: code,
            timeoutMs: timeoutMs,
            maxOutputChars: IOSJsSandboxEngine.defaultMaxOutputChars
        )
        return .filled(IOSJsSandboxEngine.toolPayload(
            result,
            maxOutputChars: IOSJsSandboxEngine.defaultMaxOutputChars
        ))
    }
}
