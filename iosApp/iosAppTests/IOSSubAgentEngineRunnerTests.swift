import XCTest
@preconcurrency import Shared
@testable import iosApp

/// SubAgent engine-runner tests. These verify the multi-turn loop, parent-tool
/// injection, and `subagent_report` capture MECHANICS with a scripted provider
/// (no real model). Real multi-turn behavior + tool execution quality is
/// validated via manual smoke with a real API key.
@MainActor
final class IOSSubAgentEngineRunnerTests: XCTestCase {

    private func makeProviderSetting() -> ProviderSetting.OpenAI {
        ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "subagent-test",
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

    /// A scripted provider that returns a queue of assistant turns. The engine
    /// drives the SubAgent loop; this lets us assert the report was captured and
    /// parent tools were invoked WITHOUT a real model.
    final class ScriptedProvider: IOSAgentTextProvider, @unchecked Sendable {
        private var script: [UIMessage]
        private(set) var callCount = 0
        private(set) var seenToolNames: [String] = []
        private(set) var seenModelId: String?
        private(set) var seenTemperature: Float?
        private(set) var seenMaxTokens: Int32?
        private(set) var seenCustomHeaderNames: [String] = []
        init(_ script: [UIMessage]) { self.script = script }

        func generateText(
            providerSetting: ProviderSetting,
            messages: [UIMessage],
            params: TextGenerationParams
        ) async throws -> MessageChunk {
            callCount += 1
            seenToolNames = params.tools.map(\.name)
            seenModelId = params.model.modelId
            seenTemperature = params.temperature.map { Float(truncating: $0) }
            seenMaxTokens = params.maxTokens.map { Int32(truncating: $0) }
            seenCustomHeaderNames = params.customHeaders.map(\.name)
            if !script.isEmpty {
                let msg = script.removeFirst()
                return chunk(with: msg)
            }
            return chunk(with: UIMessage(
                id: KotlinUuid.companion.random(),
                role: MessageRole.assistant,
                parts: [UIMessagePart.Text(text: "done", metadata: nil)],
                annotations: [],
                createdAt: Kotlinx_datetimeLocalDateTime(year: 2026, month: 6, day: 20, hour: 0, minute: 0, second: 0, nanosecond: 0),
                finishedAt: nil,
                modelId: nil,
                usage: nil,
                translation: nil
            ))
        }

        private func chunk(with message: UIMessage) -> MessageChunk {
            MessageChunk(
                id: "chunk-\(UUID().uuidString)",
                model: "test",
                choices: [UIMessageChoice(index: 0, delta: nil, message: message, finishReason: "stop")],
                usage: nil
            )
        }
    }

    private func makeMessage(role: MessageRole, parts: [UIMessagePart]) -> UIMessage {
        let now = Kotlinx_datetimeLocalDateTime(year: 2026, month: 6, day: 20, hour: 0, minute: 0, second: 0, nanosecond: 0)
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

    /// Scripted engine runner: injects a scripted provider + a recording parent
    /// tool executor so we can assert the loop ran and the report was captured.
    /// This bypasses the real OpenAIKmpProviderAdapter in runViaEngine by
    /// re-implementing the loop with the same executors the runner builds.
    func testReportIsCapturedWhenModelCallsSubagentReport() async {
        // Script: [assistant calls subagent_report, then final text].
        let reportCall = makeMessage(
            role: MessageRole.assistant,
            parts: [UIMessagePart.Tool(
                toolCallId: "rep-1",
                toolName: "subagent_report",
                input: "{\"summary\":\"found 3 sources\",\"findings\":[\"a\",\"b\"]}",
                output: [],
                approvalState: ToolApprovalState.Auto.shared,
                streamIndex: nil,
                metadata: nil
            )]
        )
        let finalText = makeMessage(role: MessageRole.assistant, parts: [UIMessagePart.Text(text: "Done.", metadata: nil)])
        let provider = ScriptedProvider([reportCall, finalText])

        let reportCapture = SubAgentReportCaptureExecutor()
        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: ["subagent_report": reportCapture],
            configuration: .init(maxSteps: 5, honorApprovalPause: false)
        )
        let params = TextGenerationParams(
            model: Model(modelId: "test", displayName: "test", id: KotlinUuid.companion.random(), type: ModelType.chat, customHeaders: [], customBodies: [], inputModalities: [], outputModalities: [], abilities: [], tools: Set<BuiltInTools>(), contextWindowTokens: nil, providerOverwrite: nil),
            temperature: KotlinFloat(value: 0.7),
            topP: nil,
            maxTokens: nil,
            tools: [],
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [makeMessage(role: MessageRole.user, parts: [UIMessagePart.Text(text: "find sources", metadata: nil)])],
            params: params
        )

        XCTAssertNotNil(reportCapture.captured, "report must be captured when the model calls subagent_report")
        XCTAssertTrue(reportCapture.captured?.contains("found 3 sources") ?? false)
        XCTAssertFalse(result.hitStepLimit)
    }

    /// Parent tools in the registry must be executable by the engine (the
    /// SubAgent runner passes the role's allowed tools as executors).
    func testParentToolExecutorIsInvokedWhenCalled() async {
        final class RecordingExecutor: IOSToolExecutor {
            private(set) var calls: [(name: String, args: String)] = []
            func execute(name: String, arguments: String, isUserInitiated: Bool) async -> IOSAgentToolOutcome {
                calls.append((name, arguments))
                return .filled("{\"results\":[]}")
            }
        }
        let parentExecutor = RecordingExecutor()
        let searchCall = makeMessage(
            role: MessageRole.assistant,
            parts: [UIMessagePart.Tool(toolCallId: "s-1", toolName: "search_web", input: "{\"query\":\"x\"}", output: [], approvalState: ToolApprovalState.Auto.shared, streamIndex: nil, metadata: nil)]
        )
        let finalText = makeMessage(role: MessageRole.assistant, parts: [UIMessagePart.Text(text: "Done.", metadata: nil)])
        let provider = ScriptedProvider([searchCall, finalText])

        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: ["search_web": parentExecutor],
            configuration: .init(maxSteps: 5)
        )
        let params = TextGenerationParams(
            model: Model(modelId: "test", displayName: "test", id: KotlinUuid.companion.random(), type: ModelType.chat, customHeaders: [], customBodies: [], inputModalities: [], outputModalities: [], abilities: [], tools: Set<BuiltInTools>(), contextWindowTokens: nil, providerOverwrite: nil),
            temperature: KotlinFloat(value: 0.7), topP: nil, maxTokens: nil, tools: [], reasoningLevel: .off, customHeaders: [], customBody: []
        )
        _ = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [makeMessage(role: MessageRole.user, parts: [UIMessagePart.Text(text: "search", metadata: nil)])],
            params: params
        )

        XCTAssertEqual(parentExecutor.calls.count, 1)
        XCTAssertEqual(parentExecutor.calls.first?.name, "search_web")
    }

    /// When the model never calls subagent_report, the capture executor stays
    /// nil — the runner falls back to visible text (tested at the engine level
    /// by asserting captured is nil after a plain-text-only run).
    func testNoReportCapturedWhenModelSkipsIt() async {
        let reportCapture = SubAgentReportCaptureExecutor()
        let provider = ScriptedProvider([
            makeMessage(role: MessageRole.assistant, parts: [UIMessagePart.Text(text: "just text, no report", metadata: nil)])
        ])
        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: ["subagent_report": reportCapture],
            configuration: .init(maxSteps: 3)
        )
        let params = TextGenerationParams(
            model: Model(modelId: "test", displayName: "test", id: KotlinUuid.companion.random(), type: ModelType.chat, customHeaders: [], customBodies: [], inputModalities: [], outputModalities: [], abilities: [], tools: Set<BuiltInTools>(), contextWindowTokens: nil, providerOverwrite: nil),
            temperature: KotlinFloat(value: 0.7), topP: nil, maxTokens: nil, tools: [], reasoningLevel: .off, customHeaders: [], customBody: []
        )
        _ = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [makeMessage(role: MessageRole.user, parts: [UIMessagePart.Text(text: "go", metadata: nil)])],
            params: params
        )
        XCTAssertNil(reportCapture.captured)
    }

    func testRunViaEngineDeclaresReportToolsListAndAllowlistTools() async {
        let toolsListCall = makeMessage(
            role: MessageRole.assistant,
            parts: [UIMessagePart.Tool(
                toolCallId: "tools-1",
                toolName: "tools_list",
                input: "{}",
                output: [],
                approvalState: ToolApprovalState.Auto.shared,
                streamIndex: nil,
                metadata: nil
            )]
        )
        let reportCall = makeMessage(
            role: MessageRole.assistant,
            parts: [UIMessagePart.Tool(
                toolCallId: "rep-1",
                toolName: "subagent_report",
                input: "{\"summary\":\"tools listed\"}",
                output: [],
                approvalState: ToolApprovalState.Auto.shared,
                streamIndex: nil,
                metadata: nil
            )]
        )
        let finalText = makeMessage(role: MessageRole.assistant, parts: [UIMessagePart.Text(text: "Done.", metadata: nil)])
        let provider = ScriptedProvider([toolsListCall, reportCall, finalText])
        let runner = SubAgentRunner(taskStore: IOSAdvancedTaskStore(userDefaults: UserDefaults(suiteName: "subagent-\(UUID().uuidString)")!, storageKey: "tasks"))

        let result = await runner.runViaEngine(
            objective: "List tools and report",
            roleId: "explorer",
            requestedToolScope: ["search_web"],
            providerSetting: makeProviderSetting(),
            modelId: "test-model",
            parentToolExecutors: [:],
            provider: provider
        )

        let declared = Set(provider.seenToolNames)
        XCTAssertTrue(declared.contains("subagent_report"))
        XCTAssertTrue(declared.contains("tools_list"))
        XCTAssertTrue(declared.contains("search_web"))
        XCTAssertTrue(result.contains("\"report_captured\":true"))
        XCTAssertGreaterThanOrEqual(provider.callCount, 2)
    }

    func testRunViaEngineDeniesRequestedWriteOrSensitiveToolsBeforeModelCall() async {
        let provider = ScriptedProvider([])
        let runner = SubAgentRunner(taskStore: IOSAdvancedTaskStore(userDefaults: UserDefaults(suiteName: "subagent-denied-\(UUID().uuidString)")!, storageKey: "tasks"))

        let result = await runner.runViaEngine(
            objective: "Write a file",
            roleId: "explorer",
            requestedToolScope: ["workspace_file_write", "wm_click"],
            providerSetting: makeProviderSetting(),
            modelId: "test-model",
            parentToolExecutors: [:],
            provider: provider
        )

        XCTAssertEqual(provider.callCount, 0)
        XCTAssertTrue(result.contains("\"denied\":true"))
        XCTAssertTrue(result.contains("workspace_file_write"))
        XCTAssertTrue(result.contains("wm_click"))
    }

    func testRunViaEngineInheritsParentGenerationParams() async {
        let reportCall = makeMessage(
            role: MessageRole.assistant,
            parts: [UIMessagePart.Tool(
                toolCallId: "rep-params",
                toolName: "subagent_report",
                input: "{\"summary\":\"params inherited\"}",
                output: [],
                approvalState: ToolApprovalState.Auto.shared,
                streamIndex: nil,
                metadata: nil
            )]
        )
        let provider = ScriptedProvider([reportCall])
        let runner = SubAgentRunner(taskStore: IOSAdvancedTaskStore(userDefaults: UserDefaults(suiteName: "subagent-params-\(UUID().uuidString)")!, storageKey: "tasks"))
        let baseParams = TextGenerationParams(
            model: Model(
                modelId: "parent-model",
                displayName: "Parent Model",
                id: KotlinUuid.companion.random(),
                type: ModelType.chat,
                customHeaders: [CustomHeader(name: "X-Parent", value: "1")],
                customBodies: [],
                inputModalities: [],
                outputModalities: [],
                abilities: [],
                tools: Set<BuiltInTools>(),
                contextWindowTokens: nil,
                providerOverwrite: nil
            ),
            temperature: KotlinFloat(value: 0.25),
            topP: nil,
            maxTokens: KotlinInt(value: 321),
            tools: [],
            reasoningLevel: .off,
            customHeaders: [CustomHeader(name: "X-Parent", value: "1")],
            customBody: []
        )

        _ = await runner.runViaEngine(
            objective: "Check inherited params",
            roleId: "explorer",
            requestedToolScope: [],
            providerSetting: makeProviderSetting(),
            modelId: "fallback-model",
            baseParams: baseParams,
            parentToolExecutors: [:],
            provider: provider
        )

        XCTAssertEqual(provider.seenModelId, "parent-model")
        XCTAssertEqual(Double(provider.seenTemperature ?? -1), 0.25, accuracy: 0.001)
        XCTAssertEqual(provider.seenMaxTokens, 321)
        XCTAssertTrue(provider.seenCustomHeaderNames.contains("X-Parent"))
        XCTAssertTrue(provider.seenToolNames.contains("subagent_report"))
    }
}
