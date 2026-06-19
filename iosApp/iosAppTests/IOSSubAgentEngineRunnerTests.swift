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
        init(_ script: [UIMessage]) { self.script = script }

        func generateText(
            providerSetting: ProviderSetting.OpenAI,
            messages: [UIMessage],
            params: TextGenerationParams
        ) async throws -> MessageChunk {
            callCount += 1
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
            parts: [UIMessagePart.Tool(toolCallId: "s-1", toolName: "search_web", input: "{\"query\":\"x\"}", output: [], approvalState: ToolApprovalState.Auto.shared, metadata: nil)]
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
}
