import XCTest
@preconcurrency import Shared
@testable import iosApp

/// Deep Read LLM pipeline tests. The real multi-stage synthesis quality needs a
/// real model (manual smoke); these verify the stage loop MECHANICS with a
/// scripted provider — 3 sequential synthesis calls, each seeded with the prior
/// stage's output, and the offline fallback when the provider fails.
@MainActor
final class IOSDeepReadPipelineTests: XCTestCase {

    private func makeProviderSetting() -> ProviderSetting.OpenAI {
        ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "deepread-test",
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

    private func makeTask() -> IOSDeepReadTask {
        IOSDeepReadTask(
            id: "test-task",
            title: "Test Deep Read",
            status: .running,
            templateId: IOSDeepReadTemplate.analysis.id,
            sources: [
                IOSDeepReadSource(kind: .manualText, title: "Source A", content: "AmberAgent is an iOS app with chat and tools."),
                IOSDeepReadSource(kind: .manualText, title: "Source B", content: "It supports deep reading and subagents.")
            ],
            resultMarkdown: "",
            failureMessage: nil,
            createdAt: 1,
            updatedAt: 1,
            completedAt: nil,
            retryCount: 0
        )
    }

    /// A scripted provider that returns one canned reply per call and records
    /// every call so we can assert the stage loop ran 3 times (overview,
    /// narrative, analysis).
    final class StageProvider: IOSAgentTextProvider, @unchecked Sendable {
        private let replies: [String]
        private(set) var callCount = 0
        private(set) var userPrompts: [String] = []
        init(_ replies: [String]) { self.replies = replies }

        func generateText(
            providerSetting: ProviderSetting.OpenAI,
            messages: [UIMessage],
            params: TextGenerationParams
        ) async throws -> MessageChunk {
            callCount += 1
            if let user = messages.last(where: { $0.role == MessageRole.user }) {
                userPrompts.append(user.toText())
            }
            let reply = replies[(callCount - 1) % replies.count]
            let message = UIMessage(
                id: KotlinUuid.companion.random(),
                role: MessageRole.assistant,
                parts: [UIMessagePart.Text(text: reply, metadata: nil)],
                annotations: [],
                createdAt: Kotlinx_datetimeLocalDateTime(year: 2026, month: 6, day: 20, hour: 0, minute: 0, second: 0, nanosecond: 0),
                finishedAt: nil,
                modelId: nil,
                usage: nil,
                translation: nil
            )
            return MessageChunk(
                id: "chunk-\(callCount)",
                model: "test",
                choices: [UIMessageChoice(index: 0, delta: nil, message: message, finishReason: "stop")],
                usage: nil
            )
        }
    }

    func testPipelineRunsThreeStagesAndAssemblesMarkdown() async {
        let provider = StageProvider([
            "OVERVIEW: two sources describe an iOS agent app.",
            "NARRATIVE: the app has chat, then tools, then deep reading.",
            "ANALYSIS: gap — no mention of release date; risk — early stage."
        ])
        let draft = await IOSDeepReadDraftGenerator.generateViaLLM(
            task: makeTask(),
            providerSetting: makeProviderSetting(),
            modelId: "test-model",
            provider: provider
        )

        // The pipeline must make exactly 3 synthesis calls (overview/narrative/analysis).
        XCTAssertEqual(provider.callCount, 3)
        // The assembled draft must contain all three section headers and the
        // synthesized text.
        XCTAssertTrue(draft.contains("# Test Deep Read"))
        XCTAssertTrue(draft.contains("## 摘要"))
        XCTAssertTrue(draft.contains("OVERVIEW:"))
        XCTAssertTrue(draft.contains("## 脉络"))
        XCTAssertTrue(draft.contains("NARRATIVE:"))
        XCTAssertTrue(draft.contains("## 分析"))
        XCTAssertTrue(draft.contains("ANALYSIS:"))
    }

    func testLaterStagesSeededWithEarlierStageOutput() async {
        // The narrative prompt must include the overview text; analysis must
        // include the narrative. Verify via the recorded user prompts.
        let provider = StageProvider(["overview-text", "narrative-text", "analysis-text"])
        _ = await IOSDeepReadDraftGenerator.generateViaLLM(
            task: makeTask(),
            providerSetting: makeProviderSetting(),
            modelId: "test-model",
            provider: provider
        )
        XCTAssertEqual(provider.userPrompts.count, 3)
        // Stage 2 (narrative) prompt should reference the overview output.
        XCTAssertTrue(provider.userPrompts[1].contains("overview-text"))
        // Stage 3 (analysis) prompt should reference the narrative output.
        XCTAssertTrue(provider.userPrompts[2].contains("narrative-text"))
    }

    func testOfflineFallbackDeterministicDraftStillWorks() {
        // The deterministic offline generator must still produce a structured
        // draft (used when no provider/key is configured, and as the fallback).
        let draft = IOSDeepReadDraftGenerator.generate(task: makeTask())
        XCTAssertTrue(draft.contains("# Test Deep Read"))
        XCTAssertTrue(draft.contains("## 摘要"))
        XCTAssertTrue(draft.contains("## 脉络"))
        XCTAssertTrue(draft.contains("## 分析"))
    }
}
