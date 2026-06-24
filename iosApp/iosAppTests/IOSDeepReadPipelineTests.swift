import XCTest
@preconcurrency import Shared
@testable import iosApp

/// Deep Read LLM pipeline tests. The real multi-stage synthesis quality needs a
/// real model (manual smoke); these verify the stage loop MECHANICS with a
/// scripted provider — 4 sequential synthesis calls (overview/narrative/analysis/
/// extended-reading, Android parity), each seeded with the prior stage's output,
/// and the offline fallback when the provider fails.
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
    /// every call so we can assert the stage loop ran 4 times (overview,
    /// narrative, analysis, extended reading).
    final class StageProvider: IOSAgentTextProvider, @unchecked Sendable {
        private let replies: [String]
        private(set) var callCount = 0
        private(set) var userPrompts: [String] = []
        init(_ replies: [String]) { self.replies = replies }

        func generateText(
            providerSetting: ProviderSetting,
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

    func testPipelineRunsFourJSONStagesAndAssemblesStructured() async throws {
        let provider = StageProvider([
            #"{"topic_type":"product","summary":"两个来源描述一个 iOS agent 应用。","key_entities":["AmberAgent"]}"#,
            #"{"timeline":[{"date":"早期","event":"先有聊天，再有工具，再有深度阅读。"}],"core_points":[{"point":"能力分层"}]}"#,
            #"{"analysis":{"core_dispute":"是否已到产品化拐点","perspectives":[{"viewpoint":"还早","holder":"观察者"}],"implications":"需要更多验证"}}"#,
            #"{"extended_reading":[{"title":"发布时间线","url":"https://example.com","source":"Amber"}]}"#
        ])
        let result = await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: makeTask(),
            providerSetting: makeProviderSetting(),
            modelId: "test-model",
            provider: provider
        )

        // 4 synthesis calls (overview/narrative/analysis/extended-reading — Android parity).
        XCTAssertEqual(provider.callCount, 4)
        XCTAssertFalse(result.didFail)

        // The merged structured output carries every stage's fields.
        let json = try XCTUnwrap(result.structuredJSON)
        let output = try JSONDecoder().decode(IOSDeepReadOutput.self, from: Data(json.utf8))
        XCTAssertEqual(output.topicType, "product")
        XCTAssertEqual(output.summary, "两个来源描述一个 iOS agent 应用。")
        XCTAssertEqual(output.timeline.count, 1)
        XCTAssertEqual(output.corePoints.first?.point, "能力分层")
        XCTAssertEqual(output.analysis.coreDispute, "是否已到产品化拐点")
        XCTAssertEqual(output.extendedReading.first?.url, "https://example.com")

        // The serialized markdown (for share / fallback) carries the section headings.
        XCTAssertTrue(result.markdown.contains("## 摘要"))
        XCTAssertTrue(result.markdown.contains("## 时间轴"))
        XCTAssertTrue(result.markdown.contains("## 关键脉络"))
        XCTAssertTrue(result.markdown.contains("## 深度分析"))
        XCTAssertTrue(result.markdown.contains("## 扩展阅读"))
    }

    func testLaterStagesSeededWithEarlierStructuredJSON() async {
        // Each stage's prompt must carry the merged prior-stage JSON.
        let provider = StageProvider([
            #"{"summary":"概览摘要内容"}"#,
            #"{"core_points":[{"point":"叙事要点"}]}"#,
            #"{"analysis":{"core_dispute":"分析分歧"}}"#,
            #"{"extended_reading":[]}"#
        ])
        _ = await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: makeTask(),
            providerSetting: makeProviderSetting(),
            modelId: "test-model",
            provider: provider
        )
        XCTAssertEqual(provider.userPrompts.count, 4)
        // Stage 2 prompt references the overview summary (merged JSON).
        XCTAssertTrue(provider.userPrompts[1].contains("概览摘要内容"))
        // Stage 3 references the narrative core point.
        XCTAssertTrue(provider.userPrompts[2].contains("叙事要点"))
        // Stage 4 references the analysis dispute.
        XCTAssertTrue(provider.userPrompts[3].contains("分析分歧"))
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
