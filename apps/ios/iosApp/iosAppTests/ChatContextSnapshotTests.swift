import XCTest
@preconcurrency import Shared
@testable import iosApp

@MainActor
final class ChatContextSnapshotTests: XCTestCase {

    func testLastAssistantTurnIgnoresEarlierTurnsAndUserMessages() {
        let first = assistant(
            promptTokens: 1_000,
            completionTokens: 200,
            cachedTokens: 0,
            startSecond: 0,
            durationSeconds: 10
        )
        let second = assistant(
            promptTokens: 5_000,
            completionTokens: 100,
            cachedTokens: 4_000,
            startSecond: 20,
            durationSeconds: 1
        )
        let trailingUser = UIMessage.companion.user(prompt: "follow up")
        let metrics = ChatContextUsageMetrics.lastAssistantTurn(in: [
            UIMessage.companion.user(prompt: "hello"),
            first,
            UIMessage.companion.user(prompt: "continue"),
            second,
            trailingUser,
        ])

        XCTAssertEqual(metrics.promptTokens, 5_000)
        XCTAssertEqual(metrics.completionTokens, 100)
        XCTAssertEqual(metrics.cachedTokens, 4_000)
        XCTAssertEqual(metrics.currentContextTokens, 5_000)
        XCTAssertEqual(metrics.tokensPerSecond ?? -1, 100, accuracy: 0.01)
    }

    func testUsageLessAssistantDoesNotReplacePreviousTurn() {
        let completed = assistant(
            promptTokens: 2_400,
            completionTokens: 80,
            cachedTokens: 1_200,
            startSecond: 0,
            durationSeconds: 2
        )
        let seed = UIMessage.companion.assistant(prompt: "failed")
        let notice = UIMessage(
            id: seed.id,
            role: seed.role,
            parts: seed.parts,
            annotations: seed.annotations,
            createdAt: localDateTime(second: 10),
            finishedAt: localDateTime(second: 10),
            modelId: seed.modelId,
            usage: nil,
            translation: seed.translation
        )

        let metrics = ChatContextUsageMetrics.lastAssistantTurn(in: [completed, notice])
        XCTAssertEqual(metrics.promptTokens, 2_400)
        XCTAssertEqual(metrics.cachedTokens, 1_200)
        XCTAssertEqual(metrics.currentContextTokens, 2_400)
    }

    func testCacheHitRateUsesLastTurnOnlyAndHidesUnknownCache() {
        let first = assistant(
            promptTokens: 1_000,
            completionTokens: 200,
            cachedTokens: 0,
            startSecond: 0,
            durationSeconds: 2
        )
        let second = assistant(
            promptTokens: 5_000,
            completionTokens: 100,
            cachedTokens: 4_000,
            startSecond: 10,
            durationSeconds: 1
        )
        let lastTurn = snapshot(from: ChatContextUsageMetrics.lastAssistantTurn(in: [first, second]))
        XCTAssertEqual(lastTurn.cacheHitRateText, "80%")

        let noCache = snapshot(
            from: ChatContextUsageMetrics.lastAssistantTurn(in: [first])
        )
        XCTAssertEqual(noCache.cacheHitRateText, "—")
    }

    func testSpeedPrefersVisibleDecodeDurationOverWallClock() {
        let wallClock = assistant(
            promptTokens: 1_200,
            completionTokens: 90,
            cachedTokens: 700,
            startSecond: 0,
            durationSeconds: 10,
            generationDurationMs: 2_000
        )
        let metrics = ChatContextUsageMetrics.lastAssistantTurn(in: [wallClock])
        XCTAssertEqual(metrics.tokensPerSecond ?? -1, 45, accuracy: 0.01)
        XCTAssertEqual(snapshot(from: metrics).speedText, "45.0 token/s")
    }

    func testSpeedFallsBackToWallClockWhenDecodeDurationIsMissing() {
        let slow = assistant(
            promptTokens: 800,
            completionTokens: 200,
            cachedTokens: 0,
            startSecond: 0,
            durationSeconds: 10
        )
        let fast = assistant(
            promptTokens: 1_200,
            completionTokens: 90,
            cachedTokens: 700,
            startSecond: 20,
            durationSeconds: 2
        )
        let metrics = ChatContextUsageMetrics.lastAssistantTurn(in: [slow, fast])
        XCTAssertEqual(metrics.tokensPerSecond ?? -1, 45, accuracy: 0.01)
    }

    func testFreshUsageAddsMessagesAfterLastAssistant() {
        let reply = assistant(
            promptTokens: 8_000,
            completionTokens: 1_500,
            cachedTokens: 6_000,
            startSecond: 0,
            durationSeconds: 3
        )
        let followUp = UIMessage.companion.user(prompt: "你好世界")
        let occupancy = ContextCompactionEditTestSupport.nextTurnOccupancyTokens(
            messages: [UIMessage.companion.user(prompt: "hello"), reply, followUp],
            compactSourceIds: [],
            compactCreatedAt: 0,
            draftText: ""
        )
        XCTAssertEqual(occupancy, 9_500 + ContextCompactionEditTestSupport.estimatedTokens([followUp]))
    }

    func testFreshUsageOccupancyUsesPromptPlusCompletionAndDraft() {
        let reply = assistant(
            promptTokens: 8_000,
            completionTokens: 1_500,
            cachedTokens: 6_000,
            startSecond: 0,
            durationSeconds: 3
        )
        let occupancy = ContextCompactionEditTestSupport.nextTurnOccupancyTokens(
            messages: [UIMessage.companion.user(prompt: "hello"), reply],
            compactSourceIds: [],
            compactCreatedAt: 0,
            draftText: "你好世界"
        )
        XCTAssertEqual(occupancy, 9_500 + 4)
    }

    func testCompactMakesStaleUsageFallBackToEstimate() {
        let old = assistant(
            promptTokens: 80_000,
            completionTokens: 400,
            cachedTokens: 70_000,
            startSecond: 0,
            durationSeconds: 3
        )
        let recentUser = UIMessage.companion.user(prompt: "继续")
        let occupancy = ContextCompactionEditTestSupport.nextTurnOccupancyTokens(
            messages: [UIMessage.companion.user(prompt: "long history"), old, recentUser],
            compactSourceIds: [
                String(describing: old.id),
            ],
            compactCreatedAt: ChatContextSnapshot.epochMillis(from: localDateTime(second: 30)) ?? 1,
            compactSummary: "handoff",
            draftText: ""
        )
        XCTAssertLessThan(occupancy, 5_000)
        XCTAssertGreaterThan(occupancy, 0)
    }

    func testDurationStampSkipsUsageLessNotice() {
        let reply = assistant(
            promptTokens: 100,
            completionTokens: 40,
            cachedTokens: 0,
            startSecond: 0,
            durationSeconds: 2
        )
        let notice = UIMessage.companion.assistant(prompt: "stopped")
        let stamped = [reply, notice].applyingLastAssistantGenerationDuration(0.5)
        XCTAssertEqual(stamped[0].usage?.generationDurationMs, 500)
        XCTAssertNil(stamped[1].usage)
    }

    func testGenerationClockIgnoresToolOnlyChunksAndMeasuresVisibleWindow() {
        var clock = ChatGenerationSpeedClock()
        let start = Date(timeIntervalSince1970: 1_000)
        XCTAssertFalse(ChatGenerationSpeedClock.chunkHasVisibleContent(MessageChunk(
            id: "usage",
            model: "m",
            choices: [],
            usage: TokenUsage(
                promptTokens: 10,
                completionTokens: 1,
                cachedTokens: 0,
                totalTokens: 11,
                generationDurationMs: 0
            )
        )))
        clock.noteVisibleDelta(at: start)
        XCTAssertEqual(clock.duration(until: start.addingTimeInterval(2)) ?? -1, 2, accuracy: 0.001)
        clock.resetRound()
        XCTAssertNil(clock.duration(until: start.addingTimeInterval(4)))
    }

    func testKnownModelFallsBackToRegistryWindowWhenModelOmitsIt() {
        XCTAssertEqual(
            ChatContextSnapshot.resolvedContextWindowTokens(modelWindow: nil, modelId: "claude-sonnet-4-5"),
            1_000_000
        )
        XCTAssertEqual(
            ChatContextSnapshot.resolvedContextWindowTokens(modelWindow: nil, modelId: "gpt-4o"),
            128_000
        )
        XCTAssertEqual(
            ChatContextSnapshot.resolvedContextWindowTokens(modelWindow: 32_000, modelId: "claude-sonnet-4-5"),
            32_000
        )
        XCTAssertNil(
            ChatContextSnapshot.resolvedContextWindowTokens(modelWindow: nil, modelId: "totally-unknown-model-xyz")
        )
    }

    func testOccupancyTextUsesResolvedWindow() {
        let snapshot = ChatContextSnapshot(
            messageCount: 4,
            modelId: "claude-sonnet-4-5",
            supportsReasoning: true,
            pendingSelectedFileName: nil,
            pendingSelectedFileBytesText: nil,
            promptTokens: 80_000,
            completionTokens: 400,
            totalTokens: 80_400,
            cachedTokens: 70_000,
            tokensPerSecond: 32.5,
            contextWindowTokens: 1_000_000,
            currentContextTokens: 80_000
        )
        XCTAssertEqual(snapshot.occupancyText, "80K / 1M")
        XCTAssertEqual(snapshot.contextFillFraction, 0.08, accuracy: 0.0001)
        XCTAssertEqual(snapshot.cacheHitRateText, "88%")
        XCTAssertEqual(snapshot.speedText, "32.5 token/s")
    }

    private func snapshot(from metrics: ChatContextUsageMetrics) -> ChatContextSnapshot {
        ChatContextSnapshot(
            messageCount: 0,
            modelId: "",
            supportsReasoning: false,
            pendingSelectedFileName: nil,
            pendingSelectedFileBytesText: nil,
            promptTokens: metrics.promptTokens,
            completionTokens: metrics.completionTokens,
            totalTokens: metrics.promptTokens + metrics.completionTokens,
            cachedTokens: metrics.cachedTokens,
            tokensPerSecond: metrics.tokensPerSecond,
            contextWindowTokens: nil,
            currentContextTokens: metrics.currentContextTokens
        )
    }

    private func assistant(
        promptTokens: Int32,
        completionTokens: Int32,
        cachedTokens: Int32,
        startSecond: Int32,
        durationSeconds: Int32,
        generationDurationMs: Int32 = 0
    ) -> UIMessage {
        let seed = UIMessage.companion.assistant(prompt: "reply")
        return UIMessage(
            id: seed.id,
            role: seed.role,
            parts: seed.parts,
            annotations: seed.annotations,
            createdAt: localDateTime(second: startSecond),
            finishedAt: localDateTime(second: startSecond + durationSeconds),
            modelId: seed.modelId,
            usage: TokenUsage(
                promptTokens: promptTokens,
                completionTokens: completionTokens,
                cachedTokens: cachedTokens,
                totalTokens: promptTokens + completionTokens,
                generationDurationMs: generationDurationMs
            ),
            translation: seed.translation
        )
    }

    private func localDateTime(second: Int32) -> Kotlinx_datetimeLocalDateTime {
        Kotlinx_datetimeLocalDateTime(
            year: 2026,
            month: 8,
            day: 14,
            hour: 12,
            minute: 0,
            second: second,
            nanosecond: 0
        )
    }
}
