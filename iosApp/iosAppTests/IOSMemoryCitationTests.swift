import Foundation
import XCTest
@preconcurrency import Shared
@testable import iosApp

/// P2-c：记忆引用隐藏标记 `<amber-mem-cite>{"ids":[1,2],"note":"..."}</amber-mem-cite>`
/// 的契约测试。
///
/// 状态机（`IOSMemoryCitationStripper`，语义镜像 codex `InlineHiddenTagParser`）：
/// 完整标签剥离 / 跨 chunk 拆分 / 未闭合 EOF auto-close 且提取 / 相似前缀不吞字 /
/// 多标签同消息 / 空 ids / body 非 JSON 剥离但不记录 / 字面不嵌套。
///
/// 接入（`IOSMemoryCitationTracker` + 真实 `MessageStreamAccumulator`）：
/// fake provider 流式输出含标签 → 落盘 canonical 文本无标签、正文字节级保留、
/// citations 收集、`IOSMemoryPersistence.markUsed`（P2-b 复用）写盘后读回
/// `lastUsedAt`。
///
/// 引导：记忆注入 prompt 恰好一行说明隐藏标记（hidden/stripped）。
@MainActor
final class IOSMemoryCitationTests: XCTestCase {

    // MARK: - 状态机：完整标签剥离

    func testCompleteTagIsStrippedAndRecorded() {
        let stripper = IOSMemoryCitationStripper()

        let visible = stripper.feed(#"Hello <amber-mem-cite>{"ids":[1,2],"note":"blue"}</amber-mem-cite> world"#)

        XCTAssertEqual(visible, "Hello  world")
        XCTAssertEqual(stripper.citations, [IOSMemoryCitation(ids: [1, 2], note: "blue")])
        XCTAssertEqual(stripper.finish(), "")
    }

    // MARK: - 状态机：跨 chunk 拆分标签

    func testTagSplitAcrossChunks() {
        let stripper = IOSMemoryCitationStripper()

        let first = stripper.feed("Hello <amber-mem-")
        XCTAssertEqual(first, "Hello ")

        let second = stripper.feed(#"cite>{"ids":[1]}</amber-mem-"#)
        XCTAssertEqual(second, "")

        let third = stripper.feed(#"cite> world"#)
        XCTAssertEqual(third, " world")

        XCTAssertEqual(stripper.citations, [IOSMemoryCitation(ids: [1], note: nil)])
        XCTAssertEqual(stripper.finish(), "")
    }

    // MARK: - 状态机：未闭合标签 EOF auto-close

    func testUnclosedTagAutoClosesAtEOFAndExtractsBody() {
        let stripper = IOSMemoryCitationStripper()

        let visible = stripper.feed(#"x<amber-mem-cite>{"ids":[3]}"#)
        XCTAssertEqual(visible, "x")

        XCTAssertEqual(stripper.finish(), "")
        XCTAssertEqual(stripper.citations, [IOSMemoryCitation(ids: [3], note: nil)])
        // finish 幂等：再次调用不再产生输出或新记录。
        XCTAssertEqual(stripper.finish(), "")
        XCTAssertEqual(stripper.citations, [IOSMemoryCitation(ids: [3], note: nil)])
    }

    // MARK: - 状态机：相似前缀不吞字

    func testPartialOpenTagPrefixAtEOFIsPreserved() {
        let stripper = IOSMemoryCitationStripper()

        let visible = stripper.feed("hello <amber-mem-")
        XCTAssertEqual(visible, "hello ")

        XCTAssertEqual(stripper.finish(), "<amber-mem-")
        XCTAssertTrue(stripper.citations.isEmpty)
    }

    func testSimilarPrefixWithBreakingCharacterIsNotSwallowed() {
        let stripper = IOSMemoryCitationStripper()
        let input = "abc <amber-mem-cit" + "e " + "x"

        let first = stripper.feed("abc <amber-mem-cit")
        XCTAssertEqual(first, "abc ")

        let second = stripper.feed("e ")
        let third = stripper.feed("x")
        _ = stripper.finish()

        XCTAssertEqual(first + second + third, input)
        XCTAssertTrue(stripper.citations.isEmpty)
    }

    // MARK: - 状态机：多标签 / 空 ids / 非 JSON / 不嵌套

    func testMultipleTagsInOneMessage() {
        let stripper = IOSMemoryCitationStripper()

        let visible = stripper.feed(#"a<amber-mem-cite>{"ids":[1]}</amber-mem-cite>b<amber-mem-cite>{"ids":[2,3],"note":"n"}</amber-mem-cite>c"#)

        XCTAssertEqual(visible, "abc")
        XCTAssertEqual(stripper.citations, [
            IOSMemoryCitation(ids: [1], note: nil),
            IOSMemoryCitation(ids: [2, 3], note: "n"),
        ])
    }

    func testEmptyIdsAreStrippedAndRecordedWithoutUsageIds() {
        let stripper = IOSMemoryCitationStripper()

        XCTAssertEqual(stripper.feed(#"<amber-mem-cite>{"ids":[]}</amber-mem-cite>"#), "")
        XCTAssertEqual(stripper.citations, [IOSMemoryCitation(ids: [], note: nil)])
        // 空 ids 交给 markUsed 时是 no-op（P2-b 的 !ids.isEmpty 守卫）。
        XCTAssertTrue(stripper.citations.allSatisfy { $0.ids.isEmpty })
    }

    func testNonJsonBodyIsStrippedButNotRecorded() {
        let stripper = IOSMemoryCitationStripper()

        let visible = stripper.feed("<amber-mem-cite>not json at all</amber-mem-cite> text")

        // 设计决策：body 非 JSON 时标签仍剥离（不泄漏到可见/持久化文本），
        // 但不产生结构化引用——不把垃圾 body 记成一次记忆使用。
        XCTAssertEqual(visible, " text")
        XCTAssertTrue(stripper.citations.isEmpty)
    }

    func testIdsAsNumericStringsAreTolerated() {
        let stripper = IOSMemoryCitationStripper()

        XCTAssertEqual(stripper.feed(#"<amber-mem-cite>{"ids":["1","2"]}</amber-mem-cite>"#), "")
        XCTAssertEqual(stripper.citations, [IOSMemoryCitation(ids: [1, 2], note: nil)])
    }

    func testLiteralNonNestedMatching() {
        // codex 同款语义：字面匹配、不嵌套——内层 open 进入 body，
        // 第一个 close 闭合外层，剩余 close 原样出现在可见文本。
        let stripper = IOSMemoryCitationStripper()

        let visible = stripper.feed("a<amber-mem-cite>x<amber-mem-cite>y</amber-mem-cite>z</amber-mem-cite>b")

        XCTAssertEqual(visible, "az</amber-mem-cite>b")
        XCTAssertTrue(stripper.citations.isEmpty)
    }

    // MARK: - 接入：fake provider 流 → 剥离 → 落盘文本 + markUsed

    func testStreamedAssistantMessagePersistsStrippedTextAndMarksUsed() throws {
        try withIsolatedPersistence { persistence, fileURL in
            IosMemoryFactory.shared.replaceAll(records: [
                makeRecord(id: 1, content: "favorite color blue", scope: .core, kind: .user, updatedAt: 10),
                makeRecord(id: 2, content: "green project", scope: .core, kind: .user, updatedAt: 20),
                makeRecord(id: 3, content: "untouched", scope: .core, kind: .user, updatedAt: 30),
            ])

            let tracker = IOSMemoryCitationTracker()
            let accumulator = MessageStreamAccumulator(
                initialMessages: [UIMessage.companion.user(prompt: "remember colors")],
                model: nil
            )
            // fake provider 流式输出：标签跨 chunk 拆分，与生产 chunk 形状一致。
            let stream = [
                #"Your favorite color is blue. <amber-mem-cite>{"ids":[1]}"#,
                #"</amber-mem-cite> And green too. <amber-mem-"#,
                #"cite>{"ids":[2],"note":"fav"}</amber-mem-cite>"#,
            ]
            for text in stream {
                accumulator.append(chunk: tracker.stripped(assistantTextChunk(text)))
            }

            // 终态收口（.complete 同款）：flush 剩余可见文本 + 收集 citations。
            let remainder = tracker.finish()
            var finalMessages = accumulator.snapshot()
            finalMessages = IOSMemoryCitationTracker.appendingCitationRemainder(remainder, to: finalMessages)

            // 持久化 canonical 文本无标签、正文保留。
            let persistedText = finalMessages.last?.toText() ?? ""
            XCTAssertFalse(persistedText.contains("<amber-mem-cite>"))
            XCTAssertTrue(persistedText.contains("Your favorite color is blue."))
            XCTAssertTrue(persistedText.contains("And green too."))
            XCTAssertEqual(remainder, "")

            // citations 收集 → 复用 P2-b markUsed（生产接线同款调用）。
            XCTAssertEqual(tracker.citationIds, Set<Int32>([1, 2]))
            XCTAssertTrue(persistence.markUsed(ids: tracker.citationIds, now: 999))

            // 持久化读回 lastUsedAt：引用到的记录已标记、未引用记录不受影响。
            let reader = IOSMemoryPersistence(fileURL: fileURL)
            reader.load()
            XCTAssertEqual(reader.loadState, .loaded)
            XCTAssertEqual(reader.records.first { $0.id == 1 }?.lastUsedAt?.int64Value, 999)
            XCTAssertEqual(reader.records.first { $0.id == 2 }?.lastUsedAt?.int64Value, 999)
            XCTAssertNil(reader.records.first { $0.id == 3 }?.lastUsedAt)
        }
    }

    func testNonAssistantChunksPassThroughUnstripped() {
        let tracker = IOSMemoryCitationTracker()
        let seed = UIMessage.companion.user(prompt: "seed")
        let accumulator = MessageStreamAccumulator(initialMessages: [seed], model: nil)

        // 工具结果 / 用户回显文本（role != assistant）不经过 stripper。
        let toolText = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.tool,
            parts: [UIMessagePart.Text(text: #"<amber-mem-cite>{"ids":[9]}</amber-mem-cite> user content"#, metadata: nil)],
            annotations: [],
            createdAt: testNow(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
        accumulator.append(chunk: MessageChunk(
            id: "tool",
            model: "m",
            choices: [UIMessageChoice(index: 0, delta: nil, message: toolText, finishReason: "stop")],
            usage: nil
        ))
        XCTAssertTrue(tracker.citationIds.isEmpty)
    }

    func testPlainStreamWithoutTagsIsByteIdentical() {
        let tracker = IOSMemoryCitationTracker()
        let chunks = ["plain text ", "with unicode 中文 🎉 ", "and < not a tag", " ending"]
        let accumulator = MessageStreamAccumulator(
            initialMessages: [UIMessage.companion.user(prompt: "seed")],
            model: nil
        )
        let joined = chunks.joined()
        for text in chunks {
            accumulator.append(chunk: tracker.stripped(assistantTextChunk(text)))
        }
        let remainder = tracker.finish()

        XCTAssertEqual(remainder, "")
        XCTAssertEqual(accumulator.snapshot().last?.toText() ?? "", joined)
        XCTAssertTrue(tracker.citationIds.isEmpty)
    }

    // MARK: - 引导：注入 prompt 一行说明

    func testMemoryContextPromptIncludesOneHiddenCitationGuidanceLine() {
        let result = ChatMemoryContextBuilder.contextPromptResult(
            records: [makeRecord(id: 1, content: "favorite color blue", scope: .core, kind: .user, updatedAt: 10)],
            runtime: nil,
            queryText: "blue",
            now: 100
        )
        let prompt = result.prompt ?? ""
        let citeLines = prompt.components(separatedBy: "\n").filter { $0.contains("amber-mem-cite") }
        XCTAssertEqual(citeLines.count, 1, "引导必须恰好一行")
        XCTAssertTrue(citeLines.first?.contains("hidden") == true, "文案注明 hidden/stripped")
        XCTAssertFalse(result.records.isEmpty)
    }

    // MARK: - Fixtures

    private var persistenceFileURL: URL!

    private func withIsolatedPersistence(
        _ body: (IOSMemoryPersistence, URL) throws -> Void
    ) throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSMemoryCitationTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let fileURL = root.appendingPathComponent("memories.json")
        persistenceFileURL = fileURL
        defer {
            try? FileManager.default.removeItem(at: root)
            persistenceFileURL = nil
        }

        let originalRecords = IosMemoryFactory.shared.snapshotRecords()
        defer { IosMemoryFactory.shared.replaceAll(records: originalRecords) }
        IosMemoryFactory.shared.replaceAll(records: [])

        let persistence = IOSMemoryPersistence(fileURL: fileURL)
        persistence.load()
        try body(persistence, fileURL)
    }

    private func makeRecord(
        id: Int32,
        content: String,
        scope: MemoryScope,
        kind: MemoryKind,
        updatedAt: Int64 = 0
    ) -> MemoryRecord {
        MemoryRecord(
            id: id,
            content: content,
            scope: scope,
            kind: kind,
            assistantId: scope == .longTerm ? "__long_term__" : "__global__",
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

    private func assistantTextChunk(_ text: String) -> MessageChunk {
        let delta = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [UIMessagePart.Text(text: text, metadata: nil)],
            annotations: [],
            createdAt: testNow(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
        return MessageChunk(
            id: "chunk",
            model: "test-model",
            choices: [UIMessageChoice(index: 0, delta: delta, message: nil, finishReason: nil)],
            usage: nil
        )
    }

    private func testNow() -> Kotlinx_datetimeLocalDateTime {
        Kotlinx_datetimeLocalDateTime(
            year: 2026, month: 8, day: 8, hour: 12, minute: 0, second: 0, nanosecond: 0
        )
    }
}
