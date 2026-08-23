import XCTest
@preconcurrency import Shared
@testable import iosApp

/// P1 memory recall relevance-scoring tests that don't need a real model.
/// P1-1 annotation render and P1-13 nickname remain UI/bridge behavior verified
/// by the build and their existing data flow.
@MainActor
final class IOSP1BatchTests: XCTestCase {

    // MARK: - P1-3 Memory recall relevance scoring

    func testRecallTokensSplitsLatinAndCJK() {
        let tokens = ChatMemoryContextBuilder.recallTokens(from: "Hello world 你好 记忆")
        // Latin words stay whole; CJK runs use bigrams so unrelated phrases do
        // not match merely because they share one common character.
        XCTAssertTrue(tokens.contains("hello"))
        XCTAssertTrue(tokens.contains("world"))
        XCTAssertTrue(tokens.contains("你好"))
        XCTAssertTrue(tokens.contains("记忆"))
        XCTAssertFalse(tokens.contains("你"))
        XCTAssertFalse(tokens.contains("记"))
        // Single-char latin + stopwords dropped.
        XCTAssertFalse(tokens.contains("a"))
    }

    func testRecallTokensDropsStopwords() {
        let tokens = ChatMemoryContextBuilder.recallTokens(from: "the user likes coffee")
        XCTAssertFalse(tokens.contains("the"))
        XCTAssertTrue(tokens.contains("user"))
        XCTAssertTrue(tokens.contains("likes"))
        XCTAssertTrue(tokens.contains("coffee"))
    }

    func testRelevanceScoringBoostsQueryOverlapAndPinned() {
        // Build three records: pinned, query-relevant, and unrelated.
        let now: Int64 = 1_700_000_000_000
        let pinned = makeMemoryRecord(content: "irrelevant old note", pinned: true, updatedAt: now - 60_000_000)
        let relevant = makeMemoryRecord(content: "user prefers dark mode and swift", pinned: false, updatedAt: now - 1_000_000)
        let unrelated = makeMemoryRecord(content: "weather is nice today", pinned: false, updatedAt: now - 1_000_000)

        let scored = ChatMemoryContextBuilder.scoredByRelevance(
            [pinned, relevant, unrelated],
            queryText: "what dark mode settings does the user prefer",
            now: now
        ).sorted { $0.score > $1.score }
        // Pinned must rank first (large constant boost).
        XCTAssertEqual(scored.first?.record, pinned)
        // Relevant must outrank unrelated (keyword overlap).
        let relevantScore = scored.first { $0.record === relevant }?.score ?? -1
        let unrelatedScore = scored.first { $0.record === unrelated }?.score ?? -1
        XCTAssertGreaterThan(relevantScore, unrelatedScore)
    }

    private func makeMemoryRecord(content: String, pinned: Bool, updatedAt: Int64) -> MemoryRecord {
        MemoryRecord(
            id: Int32.random(in: 1...1_000_000),
            content: content,
            scope: MemoryScope.longTerm,
            kind: MemoryKind.note,
            assistantId: "test",
            sourceConversationId: nil,
            sourceMessageIds: [],
            supersedesIds: [],
            expiresAt: nil,
            confidence: 1.0,
            pinned: pinned,
            archived: false,
            createdAt: updatedAt,
            updatedAt: updatedAt,
            lastUsedAt: KotlinLong(value: updatedAt)
        )
    }
}
