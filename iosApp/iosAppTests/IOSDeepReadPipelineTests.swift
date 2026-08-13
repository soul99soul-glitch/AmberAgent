import XCTest
@preconcurrency import Shared
@testable import iosApp

/// Deep Read LLM pipeline tests. The real multi-stage synthesis quality needs a
/// real model (manual smoke); these verify the stage loop MECHANICS with a
/// scripted provider — 1 planning call + 4 sequential synthesis calls
/// (overview/narrative/analysis/extended-reading, Android parity), each seeded
/// with the prior stage's output, per-stage retry/repair, and the offline
/// fallback when the provider fails.
@MainActor
final class IOSDeepReadPipelineTests: XCTestCase {

    func testRunRegistryCancelsExactGenerationAndRejectsStaleFinish() async throws {
        let registry = IOSDeepReadRunRegistry()
        let firstGeneration = try XCTUnwrap(registry.reserve(taskId: "task"))
        var observedCancellation = false
        let firstTask = Task { @MainActor in
            do {
                try await Task.sleep(nanoseconds: 1_000_000_000)
            } catch is CancellationError {
                observedCancellation = true
            } catch {}
        }
        registry.attach(firstTask, taskId: "task", generationID: firstGeneration)

        XCTAssertTrue(registry.cancel(taskId: "task", generationID: firstGeneration))
        await firstTask.value
        XCTAssertTrue(observedCancellation)

        let retryGeneration = try XCTUnwrap(registry.reserve(taskId: "task"))
        XCTAssertFalse(registry.finish(taskId: "task", generationID: firstGeneration))
        XCTAssertTrue(registry.isCurrent(taskId: "task", generationID: retryGeneration))
        XCTAssertTrue(registry.finish(taskId: "task", generationID: retryGeneration))
    }

    func testOnlySystemExpirationCancelsDeepReadOwner() {
        var cancellationCount = 0
        let handlers = IOSDeepReadExpirationHandlers.cancelOwnerOnlyOnSystemExpiration {
            cancellationCount += 1
        }

        XCTAssertNil(handlers.onShortWindowExpiration)
        XCTAssertEqual(cancellationCount, 0)
        handlers.onSystemTaskExpiration()
        XCTAssertEqual(cancellationCount, 1)
    }

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

    /// A plan reply for the planning call (call #1 of every run).
    private let planReply = #"{"overview_angle":"从产品与生态角度解读","narrative_slots":["背景与触发","关键进展","后续观察"],"analysis_questions":["核心矛盾是什么","影响哪些群体"],"stakeholders":["用户","开发者","监管机构"],"risk_or_uncertainty":["未印证的事实需降格表达"],"required_source_ids":[1,2]}"#

    /// A gate-passing (≥24 chars) overview summary.
    private let goodSummaryReply = #"{"topic_type":"product","summary":"两个来源共同描述了一个支持聊天、工具与深度阅读的 iOS 应用产品。","key_entities":["AmberAgent"]}"#

    private func makeTask() -> IOSDeepReadTask {
        makeTask(sources: [
            IOSDeepReadSource(kind: .manualText, title: "Source A", content: "AmberAgent is an iOS app with chat and tools."),
            IOSDeepReadSource(kind: .manualText, title: "Source B", content: "It supports deep reading and subagents.")
        ])
    }

    private func makeTask(sources: [IOSDeepReadSource]) -> IOSDeepReadTask {
        IOSDeepReadTask(
            id: "test-task",
            title: "Test Deep Read",
            status: .running,
            templateId: IOSDeepReadTemplate.analysis.id,
            sources: sources,
            resultMarkdown: "",
            failureMessage: nil,
            createdAt: 1,
            updatedAt: 1,
            completedAt: nil,
            retryCount: 0
        )
    }

    /// A scripted provider that returns one canned reply per call and records
    /// every call so we can assert the stage loop ran 1 plan + 4 stage calls.
    /// `throwAtCalls` makes the given 1-based call indexes throw (transient-
    /// failure simulation for the in-stage retry).
    final class StageProvider: IOSAgentTextProvider, @unchecked Sendable {
        private let replies: [String]
        private let throwAtCalls: Set<Int>
        private(set) var callCount = 0
        private(set) var userPrompts: [String] = []
        init(_ replies: [String], throwAtCalls: Set<Int> = []) {
            self.replies = replies
            self.throwAtCalls = throwAtCalls
        }

        func generateText(
            providerSetting: ProviderSetting,
            messages: [UIMessage],
            params: TextGenerationParams
        ) async throws -> MessageChunk {
            callCount += 1
            if throwAtCalls.contains(callCount) {
                throw NSError(domain: "deepread-test", code: 1, userInfo: [NSLocalizedDescriptionKey: "transient failure"])
            }
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

    /// Like `StageProvider` but the given 1-based call sleeps before replying
    /// (timeout-path simulation).
    final class SlowOnceProvider: IOSAgentTextProvider, @unchecked Sendable {
        private let replies: [String]
        private let slowCall: Int
        private(set) var callCount = 0
        private(set) var userPrompts: [String] = []
        init(replies: [String], slowCall: Int) {
            self.replies = replies
            self.slowCall = slowCall
        }

        func generateText(
            providerSetting: ProviderSetting,
            messages: [UIMessage],
            params: TextGenerationParams
        ) async throws -> MessageChunk {
            callCount += 1
            if callCount == slowCall {
                try await Task.sleep(nanoseconds: 1_000_000_000)
            }
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

    private let timelineReply = #"{"timeline":[{"date":"今天","event":"事件发生并逐步展开。"}],"core_points":[{"point":"能力分层"}]}"#
    private let analysisReply = #"{"analysis":{"core_dispute":"是否已到产品化拐点","perspectives":[{"viewpoint":"还早","holder":"观察者"}],"implications":"需要更多验证","quotes":[{"text":"这只是开始。","attribution":"观察者"}]}}"#
    private let extendedReply = #"{"extended_reading":[{"title":"发布时间线","url":"https://example.com","source":"Amber"}],"references":[{"title":"参考来源","url":"https://example.org","source":"Amber"}],"hero_image_url":"","hero_caption":""}"#

    func testPipelineRunsPlanAndFourJSONStagesAndAssemblesStructured() async throws {
        let provider = StageProvider([
            planReply,
            goodSummaryReply,
            timelineReply,
            analysisReply,
            extendedReply
        ])
        let result = await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: makeTask(),
            providerSetting: makeProviderSetting(),
            modelId: "test-model",
            provider: provider
        )

        // 1 planning call + 4 synthesis calls (overview/narrative/analysis/extended-reading).
        XCTAssertEqual(provider.callCount, 5)
        XCTAssertFalse(result.didFail)
        XCTAssertTrue(result.missingSections.isEmpty, "no stage should be missing: \(result.missingSections)")

        // The merged structured output carries every stage's fields.
        let json = try XCTUnwrap(result.structuredJSON)
        let output = try JSONDecoder().decode(IOSDeepReadOutput.self, from: Data(json.utf8))
        XCTAssertEqual(output.topicType, "product")
        XCTAssertTrue(output.summary.count >= 24)
        XCTAssertEqual(output.timeline.count, 1)
        XCTAssertEqual(output.corePoints.first?.point, "能力分层")
        XCTAssertEqual(output.analysis.coreDispute, "是否已到产品化拐点")
        XCTAssertEqual(output.analysis.quotes.first?.attribution, "观察者")
        XCTAssertEqual(output.extendedReading.first?.url, "https://example.com")
        XCTAssertEqual(output.references.first?.url, "https://example.org")

        // The serialized markdown (for share / fallback) carries the section headings.
        XCTAssertTrue(result.markdown.contains("## 摘要"))
        XCTAssertTrue(result.markdown.contains("## 时间轴"))
        XCTAssertTrue(result.markdown.contains("## 关键脉络"))
        XCTAssertTrue(result.markdown.contains("## 深度分析"))
        XCTAssertTrue(result.markdown.contains("## 扩展阅读"))
        XCTAssertTrue(result.markdown.contains("## 参考来源"))
    }

    func testLaterStagesSeededWithEarlierStructuredJSON() async {
        // Each stage's prompt must carry the merged prior-stage JSON.
        let provider = StageProvider([
            planReply,
            #"{"summary":"这是一个足够长的概览摘要，用来通过门闩并传递给后续段落。"}"#,
            #"{"core_points":[{"point":"叙事要点"}]}"#,
            #"{"analysis":{"core_dispute":"分析分歧"}}"#,
            #"{"extended_reading":[{"title":"链接","url":"https://example.com","source":"示例"}]}"#
        ])
        _ = await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: makeTask(),
            providerSetting: makeProviderSetting(),
            modelId: "test-model",
            provider: provider
        )
        XCTAssertEqual(provider.userPrompts.count, 5)
        // Prompt 0 is the planning call; stage 1 is prompt 1.
        XCTAssertTrue(provider.userPrompts[0].contains("结构规划"))
        // Stage 2 prompt references the overview summary (merged JSON).
        XCTAssertTrue(provider.userPrompts[2].contains("足够长的概览摘要"))
        // Stage 3 references the narrative core point.
        XCTAssertTrue(provider.userPrompts[3].contains("叙事要点"))
        // Stage 4 references the analysis dispute.
        XCTAssertTrue(provider.userPrompts[4].contains("分析分歧"))
    }

    // MARK: - Planning (P1-a)

    func testPlanIsParsedAndInjectedIntoStagePrompts() async {
        let provider = StageProvider([
            planReply,
            goodSummaryReply,
            timelineReply,
            analysisReply,
            extendedReply
        ])
        _ = await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: makeTask(),
            providerSetting: makeProviderSetting(),
            modelId: "test-model",
            provider: provider
        )
        // Every stage prompt carries the Article Plan section.
        for index in 1..<5 {
            XCTAssertTrue(provider.userPrompts[index].contains("Article Plan"), "prompt \(index) must carry the plan")
            XCTAssertTrue(provider.userPrompts[index].contains("监管机构"), "prompt \(index) must carry stakeholders")
        }
        // The analysis stage also sees the plan's analysis questions.
        XCTAssertTrue(provider.userPrompts[3].contains("核心矛盾是什么"))
    }

    func testPlanFailureFallsBackToLocalPlan() async {
        let provider = StageProvider([
            planReply,
            goodSummaryReply,
            timelineReply,
            analysisReply,
            extendedReply
        ], throwAtCalls: [1])
        let result = await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: makeTask(),
            providerSetting: makeProviderSetting(),
            modelId: "test-model",
            provider: provider
        )
        XCTAssertEqual(provider.callCount, 5)
        XCTAssertFalse(result.didFail)
        XCTAssertTrue(result.missingSections.isEmpty)
        // The fallback angle is injected (Android fallbackPlan wording).
        XCTAssertTrue(provider.userPrompts[1].contains("从已核查来源解释"))
    }

    func testUnparseablePlanFallsBackToLocalPlan() async {
        let provider = StageProvider([
            "规划失败，随便写点文字。",
            goodSummaryReply,
            timelineReply,
            analysisReply,
            extendedReply
        ])
        let result = await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: makeTask(),
            providerSetting: makeProviderSetting(),
            modelId: "test-model",
            provider: provider
        )
        XCTAssertFalse(result.didFail)
        XCTAssertTrue(provider.userPrompts[1].contains("从已核查来源解释"))
    }

    // MARK: - Per-stage source bucketing (P1-b)

    func testStageSourceBucketingPrefersRequiredIdsAndCapsSources() async {
        let sources = (1...10).map { index in
            IOSDeepReadSource(kind: .manualText, title: "Source \(index)", content: "Source \(index) content.")
        }
        let provider = StageProvider([
            #"{"overview_angle":"角度","required_source_ids":[10,9]}"#,
            goodSummaryReply,
            timelineReply,
            analysisReply,
            extendedReply
        ])
        _ = await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: makeTask(sources: sources),
            providerSetting: makeProviderSetting(),
            modelId: "test-model",
            provider: provider
        )
        // Overview (cap 6): required ids 10,9 come first, then 1..4 — 5..8 absent.
        let overviewPrompt = provider.userPrompts[1]
        XCTAssertTrue(overviewPrompt.contains("[10]"), "required id 10 must be prioritized")
        XCTAssertTrue(overviewPrompt.contains("[9]"))
        XCTAssertFalse(overviewPrompt.contains("[7]"), "overview is capped at 6 sources")
        // Extended reading (cap 12 > 10 sources) sees all ten.
        XCTAssertTrue(provider.userPrompts[4].contains("[7]"))
    }

    // MARK: - Completion gates (P1-d)

    func testOverviewBelowMinimumLengthIsNotFoldedIntoArticle() async {
        let provider = StageProvider([
            planReply,
            #"{"summary":"短"}"#,
            #"{"summary":"短"}"#
        ])
        let result = await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: makeTask(),
            providerSetting: makeProviderSetting(),
            modelId: "test-model",
            provider: provider
        )
        // The sub-24-char summary fails the gate on both attempts, is not merged,
        // and the run honestly fails instead of shipping a two-character lede.
        XCTAssertTrue(result.didFail)
        XCTAssertEqual(result.missingSections, ["概览", "时间轴叙事", "深度分析", "扩展阅读"])
        XCTAssertNil(result.structuredJSON)
    }

    // MARK: - Stage robustness (retry / repair / honest partial reporting)

    func testTransientThrowIsRetriedWithinStage() async throws {
        // Call 3 (narrative first attempt; call 1 = plan, call 2 = overview) throws;
        // its retry consumes the next reply, so the fixture duplicates the
        // narrative reply at index 3. Call count grows from 5 to 6 and no section
        // is missing.
        let provider = StageProvider([
            planReply,
            goodSummaryReply,
            timelineReply,
            timelineReply,
            analysisReply,
            extendedReply
        ], throwAtCalls: [3])
        let result = await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: makeTask(),
            providerSetting: makeProviderSetting(),
            modelId: "test-model",
            provider: provider
        )
        XCTAssertEqual(provider.callCount, 6)
        XCTAssertFalse(result.didFail)
        XCTAssertTrue(result.missingSections.isEmpty, "retried stage must not be reported missing: \(result.missingSections)")
        let json = try XCTUnwrap(result.structuredJSON)
        let output = try JSONDecoder().decode(IOSDeepReadOutput.self, from: Data(json.utf8))
        XCTAssertEqual(output.timeline.count, 1)
    }

    func testUnparseableOutputIsRetriedWithStricterNote() async {
        // Narrative first attempt returns prose without any JSON object; the retry
        // must carry the corrective retry note and the valid reply is accepted.
        let provider = StageProvider([
            planReply,
            goodSummaryReply,
            "我觉得时间线应该这样写，但没有数据。",
            timelineReply,
            analysisReply,
            extendedReply
        ])
        let result = await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: makeTask(),
            providerSetting: makeProviderSetting(),
            modelId: "test-model",
            provider: provider
        )
        XCTAssertEqual(provider.callCount, 6)
        XCTAssertFalse(result.didFail)
        XCTAssertTrue(result.missingSections.isEmpty)
        // The narrative retry prompt (call 4, index 3) carries the strict-JSON note.
        XCTAssertTrue(provider.userPrompts[3].contains("重试要求"))
        XCTAssertTrue(provider.userPrompts[3].contains("无法解析为合法 JSON"))
    }

    func testMissingStageFieldsAreRetriedWithFieldReminder() async throws {
        // Narrative first attempt returns a valid but irrelevant object (no
        // timeline/core_points); the retry reminder names the missing fields.
        let provider = StageProvider([
            planReply,
            goodSummaryReply,
            #"{"summary":"重复概览，但没有叙事字段，长度足够。"}"#,
            timelineReply,
            analysisReply,
            extendedReply
        ])
        let result = await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: makeTask(),
            providerSetting: makeProviderSetting(),
            modelId: "test-model",
            provider: provider
        )
        XCTAssertEqual(provider.callCount, 6)
        XCTAssertFalse(result.didFail)
        XCTAssertTrue(result.missingSections.isEmpty)
        XCTAssertTrue(provider.userPrompts[3].contains("timeline"))
        let json = try XCTUnwrap(result.structuredJSON)
        let output = try JSONDecoder().decode(IOSDeepReadOutput.self, from: Data(json.utf8))
        XCTAssertEqual(output.timeline.count, 1)
    }

    func testTruncatedJSONIsRepairedWithoutRetry() async throws {
        // Narrative output is cut mid-array: repair balances it, the stage is
        // accepted, and no retry call is spent.
        let provider = StageProvider([
            planReply,
            goodSummaryReply,
            #"{"timeline":[{"date":"今天","event":"事件发生"}"#,
            analysisReply,
            extendedReply
        ])
        let result = await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: makeTask(),
            providerSetting: makeProviderSetting(),
            modelId: "test-model",
            provider: provider
        )
        XCTAssertEqual(provider.callCount, 5)
        XCTAssertFalse(result.didFail)
        XCTAssertTrue(result.missingSections.isEmpty)
        let json = try XCTUnwrap(result.structuredJSON)
        let output = try JSONDecoder().decode(IOSDeepReadOutput.self, from: Data(json.utf8))
        XCTAssertEqual(output.timeline.first?.event, "事件发生")
    }

    func testPersistentlyFailingStageIsReportedNotSilentlyDropped() async {
        // Analysis returns prose on both attempts (calls 4 and 5); the run still
        // completes with the other sections and reports the missing one.
        let provider = StageProvider([
            planReply,
            goodSummaryReply,
            timelineReply,
            "分析部分我想写一段长文……",
            "分析部分我想写一段长文……",
            extendedReply
        ])
        let result = await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: makeTask(),
            providerSetting: makeProviderSetting(),
            modelId: "test-model",
            provider: provider
        )
        XCTAssertEqual(provider.callCount, 6) // analysis consumed its retry
        XCTAssertFalse(result.didFail, "partial content is a completed draft")
        XCTAssertEqual(result.missingSections, ["深度分析"])
        XCTAssertTrue(result.markdown.contains("## 时间轴"))
        XCTAssertTrue(result.markdown.contains("## 扩展阅读"))
        XCTAssertFalse(result.markdown.contains("## 深度分析"))
    }

    func testStageTimeoutFallsToRetry() async {
        // The overview call (call 2) sleeps past the injected 0.2s budget; the
        // retry answers instantly and the run completes.
        let provider = SlowOnceProvider(
            replies: [planReply, goodSummaryReply, goodSummaryReply, timelineReply, analysisReply, extendedReply],
            slowCall: 2
        )
        let result = await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: makeTask(),
            providerSetting: makeProviderSetting(),
            modelId: "test-model",
            provider: provider,
            stageTimeouts: ["概览": 0.2]
        )
        XCTAssertEqual(provider.callCount, 6)
        XCTAssertFalse(result.didFail)
        XCTAssertTrue(result.missingSections.isEmpty)
        // The retry prompt surfaces the timeout as the failure reason. The timed-
        // out call throws before recording its prompt, so the retry is prompt #2.
        XCTAssertTrue(provider.userPrompts[1].contains("超时"), "retry prompt head: \(provider.userPrompts[1].prefix(300))")
    }

    // MARK: - Targeted retry (P2-a)

    private func priorOutput() -> IOSDeepReadOutput {
        var output = IOSDeepReadOutput()
        output.summary = "这是一个已有的概览摘要，长度足够通过任何门闩。"
        output.timeline = [IOSDeepReadTimelineEvent(date: "今天", event: "既有事件")]
        output.extendedReading = [IOSDeepReadLink(title: "既有链接", url: "https://example.com")]
        return output
    }

    func testTargetedRetryRegeneratesOnlyTargetedStage() async throws {
        let provider = StageProvider([
            planReply,
            analysisReply
        ])
        let result = await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: makeTask(),
            providerSetting: makeProviderSetting(),
            modelId: "test-model",
            provider: provider,
            initialOutput: priorOutput(),
            targetStages: ["深度分析"]
        )
        // Plan + the single targeted stage; the other sections are untouched.
        XCTAssertEqual(provider.callCount, 2)
        XCTAssertFalse(result.didFail)
        XCTAssertTrue(result.missingSections.isEmpty)
        let json = try XCTUnwrap(result.structuredJSON)
        let output = try JSONDecoder().decode(IOSDeepReadOutput.self, from: Data(json.utf8))
        XCTAssertEqual(output.timeline.first?.event, "既有事件")
        XCTAssertEqual(output.analysis.coreDispute, "是否已到产品化拐点")
    }

    func testTargetedRetryFailureKeepsPriorSectionsAndReportsMissing() async {
        let provider = StageProvider([
            planReply,
            "分析部分我想写一段长文……",
            "分析部分我想写一段长文……"
        ])
        let result = await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: makeTask(),
            providerSetting: makeProviderSetting(),
            modelId: "test-model",
            provider: provider,
            initialOutput: priorOutput(),
            targetStages: ["深度分析"]
        )
        XCTAssertEqual(provider.callCount, 3)
        XCTAssertFalse(result.didFail, "prior sections keep the run completed")
        XCTAssertEqual(result.missingSections, ["深度分析"])
        XCTAssertTrue(result.markdown.contains("## 时间轴"))
    }

    // MARK: - Repair helper contracts

    func testRepairTruncatedJSONHandlesNestedArraysAndDanglingComma() {
        let repaired = IOSDeepReadDraftGenerator.repairTruncatedJSON(
            #"{"timeline":[{"date":"今天","event":"事件发生"},"#
        )
        XCTAssertEqual(
            repaired,
            #"{"timeline":[{"date":"今天","event":"事件发生"}]}"#
        )
        let output = IOSDeepReadDraftGenerator.parseStageJSON(repaired ?? "")
        XCTAssertEqual(output?.timeline.count, 1)
    }

    func testRepairTruncatedJSONClosesUnterminatedString() {
        let repaired = IOSDeepReadDraftGenerator.repairTruncatedJSON(
            #"{"summary":"截断的摘要"#
        )
        XCTAssertEqual(repaired, #"{"summary":"截断的摘要"}"#)
        XCTAssertNotNil(IOSDeepReadDraftGenerator.parseStageJSON(repaired ?? ""))
    }

    func testRepairTruncatedJSONReturnsNilForBalancedJSON() {
        XCTAssertNil(IOSDeepReadDraftGenerator.repairTruncatedJSON(#"{"summary":"完整"}"#))
        XCTAssertNil(IOSDeepReadDraftGenerator.repairTruncatedJSON("没有花括号的散文"))
    }

    // MARK: - Launcher

    func testFailedRetryRestoresPriorCompletionInsteadOfDestroyingArticle() async throws {
        // A single-section retry wipes the task up front (progress UI). If the
        // retry then cannot run (no usable model), the last good draft must be
        // reinstated — never leave a completed article as failed with empty content.
        let base = FileManager.default.temporaryDirectory
            .appendingPathComponent("DeepReadRestorePrior-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: base) }

        let store = IOSDeepReadStore(baseDirectory: base)
        let source = try IOSDeepReadSourceNormalizer.manualText(
            title: "Manual",
            text: "A source with enough text for an offline deep-read draft.",
            now: 100
        )
        let task = try store.createTask(
            title: "Restore prior",
            sources: [source],
            templateId: IOSDeepReadTemplate.analysis.id,
            now: 1_000
        )
        store.markRunning(id: task.id)
        store.complete(
            id: task.id,
            markdown: "# Prior article",
            structuredJSON: #"{"summary":"prior"}"#,
            missingSections: ["深度分析"]
        )
        // Mirror IOSDeepReadLauncher.retry: snapshot first, then wipe + run.
        let prior = IOSDeepReadPriorCompletion(
            markdown: "# Prior article",
            structuredJSON: #"{"summary":"prior"}"#,
            missingSections: ["深度分析"]
        )
        store.prepareRetry(id: task.id)
        store.markRunning(id: task.id)

        let didComplete = await IOSDeepReadLauncher.runExistingTask(
            taskId: task.id,
            sharedSettings: IOSSharedSettingsStore(userDefaults: UserDefaults(suiteName: "deepread-\(UUID().uuidString)")!),
            store: store,
            targetStages: ["深度分析"],
            initialOutput: nil,
            priorCompletion: prior
        )

        XCTAssertFalse(didComplete, "a retry that cannot run must report failure")
        let reloaded = try XCTUnwrap(IOSDeepReadStore(baseDirectory: base).task(id: task.id))
        XCTAssertEqual(reloaded.status, .succeeded, "the last good draft must survive the failed retry")
        XCTAssertEqual(reloaded.resultMarkdown, "# Prior article")
        XCTAssertEqual(reloaded.missingSections, ["深度分析"])
    }

    func testRunExistingTaskPersistsWorkspaceSyncFailureWithoutStatusHandler() async throws {
        enum SaveFailure: LocalizedError {
            case denied
            var errorDescription: String? { "workspace unavailable" }
        }

        let base = FileManager.default.temporaryDirectory
            .appendingPathComponent("DeepReadWorkspaceSyncFailure-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: base) }

        let store = IOSDeepReadStore(baseDirectory: base)
        let source = try IOSDeepReadSourceNormalizer.manualText(
            title: "Manual",
            text: "A source with enough text for an offline deep-read draft.",
            now: 100
        )
        let task = try store.createTask(
            title: "Workspace sync",
            sources: [source],
            templateId: IOSDeepReadTemplate.analysis.id,
            now: 1_000
        )

        store.markRunning(id: task.id)
        let didComplete = await IOSDeepReadLauncher.runExistingTask(
            taskId: task.id,
            sharedSettings: IOSSharedSettingsStore(userDefaults: UserDefaults(suiteName: "deepread-\(UUID().uuidString)")!),
            store: store,
            workspaceArtifactSaver: { _, _, _, _, _ in throw SaveFailure.denied }
        )

        XCTAssertTrue(didComplete)
        let reloaded = try XCTUnwrap(IOSDeepReadStore(baseDirectory: base).task(id: task.id))
        XCTAssertEqual(reloaded.status, .succeeded)
        XCTAssertEqual(reloaded.workspaceSyncFailed, "Workspace 保存失败，请稍后重试。")
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
