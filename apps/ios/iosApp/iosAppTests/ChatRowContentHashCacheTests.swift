import XCTest
import Shared
@testable import iosApp

/// `ChatRowContentHashCache` 指纹假设锁定测试。
///
/// 这个缓存的正确性建立在两条经过代码走查确认的假设上:
/// 1. 历史(非 last / 非 streaming)行的内容一旦离开这两个状态就不再原地变化,
///    所以可以放心记忆化,靠 `parts.count` + tool output 指纹兜底失效检测。
/// 2. 目前唯一已知的"内容原地变更但 parts.count 不变"路径是 tool output
///    从空数组回填成非空(后台补全 / 审批恢复完成后),所以指纹里专门编码了
///    每个 Tool part 的 output 是否非空 + 条数。
/// 任何人改这个类之前,先确认这三个用例仍然成立。
final class ChatRowContentHashCacheTests: XCTestCase {

    private func makeRow(
        message: UIMessage,
        isLast: Bool = false,
        isStreaming: Bool = false
    ) -> ChatMessageRowModel {
        let messageId = ChatMessageProjector.messageId(for: message)
        return ChatMessageRowModel(
            rowId: messageId,
            messageId: messageId,
            message: message,
            role: message.role,
            parts: message.parts,
            index: 0,
            isLast: isLast,
            isStreaming: isStreaming,
            hasEverStreamed: isStreaming,
            canAnimateInsertion: false
        )
    }

    private func makeToolMessage(id: KotlinUuid, outputEmpty: Bool) -> UIMessage {
        let toolPart = UIMessagePart.Tool(
            toolCallId: "tool-1",
            toolName: "search_web",
            input: "{\"query\":\"x\"}",
            output: outputEmpty ? [] : [UIMessagePart.Text(text: "结果", metadata: nil)],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        return UIMessage(
            id: id,
            role: MessageRole.assistant,
            parts: [toolPart],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    /// 契约 1:非 last 行(已"定型"的历史行)缓存生效 —— 同 id、同 parts.count、
    /// 同 tool 指纹但**文本已变**的第二次求值,必须返回第一次的缓存值,证明第二次
    /// 没有重新遍历 parts(若只对同一 row 求两次哈希,Hasher 的进程级种子会让断言
    /// 恒真,测不出记忆化)。这同时把记忆化边界写成文档:纯文本原地变更不会被
    /// 指纹察觉,其安全性依赖"历史行文本不会原地变化"这一上游不变量。
    func testNonLastRowReturnsCachedHashWithoutRescanningParts() {
        let cache = ChatRowContentHashCache()
        let id = KotlinUuid.companion.random()
        let original = makeTextMessage(id: id, text: "原始文本")
        let mutated = makeTextMessage(id: id, text: "被原地改掉的文本")

        let first = cache.contentHash(for: makeRow(message: original))
        let second = cache.contentHash(for: makeRow(message: mutated))
        XCTAssertEqual(first, second, "应命中缓存返回旧值,而不是重扫新文本")

        // 对照组:全新缓存对变更后文本算出的哈希必须不同,证明上面的相等
        // 只能来自缓存命中,而非两段文本恰好同哈希。
        let fresh = ChatRowContentHashCache().contentHash(for: makeRow(message: mutated))
        XCTAssertNotEqual(first, fresh)
    }

    private func makeTextMessage(id: KotlinUuid, text: String) -> UIMessage {
        UIMessage(
            id: id,
            role: MessageRole.assistant,
            parts: [UIMessagePart.Text(text: text, metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    /// 契约 2:tool output 空 -> 非空(已知的原地变更路径)必须让指纹变化,
    /// 从而触发重算,而不是错误地复用旧的"空输出"哈希。
    func testToolOutputBackfillChangesHash() {
        let cache = ChatRowContentHashCache()
        let messageId = KotlinUuid.companion.random()

        let pendingMessage = makeToolMessage(id: messageId, outputEmpty: true)
        let pendingRow = makeRow(message: pendingMessage, isLast: false, isStreaming: false)
        let hashBeforeBackfill = cache.contentHash(for: pendingRow)

        let completedMessage = makeToolMessage(id: messageId, outputEmpty: false)
        let completedRow = makeRow(message: completedMessage, isLast: false, isStreaming: false)
        let hashAfterBackfill = cache.contentHash(for: completedRow)

        XCTAssertNotEqual(
            hashBeforeBackfill,
            hashAfterBackfill,
            "tool output 从空回填为非空后,parts.count 不变但内容确实变了,指纹必须跟着变化"
        )
    }

    /// 契约 3:isLast / isStreaming 的行永不入缓存 —— 同一个 messageId 在
    /// isLast=true 时两次调用,即使底层内容变了,也必须返回新的哈希值
    /// (不能被"记忆化"卡在第一次算出的旧哈希上)。
    func testLastRowNeverCaches() {
        let cache = ChatRowContentHashCache()
        let messageId = KotlinUuid.companion.random()

        func message(text: String) -> UIMessage {
            UIMessage(
                id: messageId,
                role: MessageRole.assistant,
                parts: [UIMessagePart.Text(text: text, metadata: nil)],
                annotations: [],
                createdAt: chatNowLocalDateTime(),
                finishedAt: nil,
                modelId: nil,
                usage: nil,
                translation: nil
            )
        }

        let streamingRowV1 = makeRow(message: message(text: "流式增量 1"), isLast: true, isStreaming: true)
        let hashV1 = cache.contentHash(for: streamingRowV1)

        let streamingRowV2 = makeRow(message: message(text: "流式增量 1 又追加了更多文字"), isLast: true, isStreaming: true)
        let hashV2 = cache.contentHash(for: streamingRowV2)

        XCTAssertNotEqual(
            hashV1,
            hashV2,
            "isLast/isStreaming 行必须永不缓存,否则流式增量会被卡在第一次算出的哈希上"
        )
    }

    func testStreamingTailLayoutTokenChangesWithAppendedText() {
        let cache = ChatRowContentHashCache()
        let messageId = KotlinUuid.companion.random()

        func message(text: String) -> UIMessage {
            UIMessage(
                id: messageId,
                role: MessageRole.assistant,
                parts: [UIMessagePart.Text(text: text, metadata: nil)],
                annotations: [],
                createdAt: chatNowLocalDateTime(),
                finishedAt: nil,
                modelId: nil,
                usage: nil,
                translation: nil
            )
        }

        let first = makeRow(message: message(text: "正在生成"), isLast: true, isStreaming: true)
        let second = makeRow(message: message(text: "正在生成更长的内容"), isLast: true, isStreaming: true)

        XCTAssertNotEqual(
            cache.streamingTailLayoutToken(for: first),
            cache.streamingTailLayoutToken(for: second)
        )
    }

    func testSuspendedStreamingTailLayoutTokenIgnoresInvisibleTextGrowth() {
        let cache = ChatRowContentHashCache()
        let messageId = KotlinUuid.companion.random()
        let first = makeRow(
            message: makeTextMessage(id: messageId, text: "正在生成"),
            isLast: true,
            isStreaming: true
        )
        let second = makeRow(
            message: makeTextMessage(id: messageId, text: "正在生成更长且当前不可见的内容"),
            isLast: true,
            isStreaming: true
        )

        XCTAssertEqual(
            cache.suspendedStreamingTailLayoutToken(for: first),
            cache.suspendedStreamingTailLayoutToken(for: second),
            "用户查看历史时，不可见流式尾行不应被每个文本 delta 反复失效。"
        )
        XCTAssertNotEqual(
            cache.streamingTailLayoutToken(for: first),
            cache.streamingTailLayoutToken(for: second),
            "恢复可见后仍需用实时 token 接收最新累计全文。"
        )
    }

    func testStreamingTailLayoutTokenChangesWhenCitationArrivesWithoutTextDelta() {
        let cache = ChatRowContentHashCache()
        let messageId = KotlinUuid.companion.random()
        let base = makeTextMessage(id: messageId, text: "带来源的回答")
        let cited = UIMessage(
            id: messageId,
            role: base.role,
            parts: base.parts,
            annotations: [UIMessageAnnotation.UrlCitation(title: "来源", url: "https://example.com")],
            createdAt: base.createdAt,
            finishedAt: base.finishedAt,
            modelId: base.modelId,
            usage: base.usage,
            translation: base.translation
        )

        XCTAssertNotEqual(
            cache.streamingTailLayoutToken(for: makeRow(message: base, isLast: true, isStreaming: true)),
            cache.streamingTailLayoutToken(for: makeRow(message: cited, isLast: true, isStreaming: true)),
            "Late citation annotations are visible rows and must invalidate the equatable bubble."
        )
    }

    func testStreamingTailLayoutTokenChangesWithToolInputAndOutputContent() {
        let cache = ChatRowContentHashCache()
        let messageId = KotlinUuid.companion.random()

        func message(input: String, output: String) -> UIMessage {
            UIMessage(
                id: messageId,
                role: MessageRole.assistant,
                parts: [UIMessagePart.Tool(
                    toolCallId: "tool-1",
                    toolName: "search_web",
                    input: input,
                    output: output.isEmpty ? [] : [UIMessagePart.Text(text: output, metadata: nil)],
                    approvalState: ToolApprovalState.Auto.shared,
                    streamIndex: nil,
                    metadata: nil
                )],
                annotations: [],
                createdAt: chatNowLocalDateTime(),
                finishedAt: nil,
                modelId: nil,
                usage: nil,
                translation: nil
            )
        }

        let partialInput = makeRow(message: message(input: #"{"query":"sw"}"#, output: ""), isLast: true, isStreaming: true)
        let completeInput = makeRow(message: message(input: #"{"query":"swift"}"#, output: ""), isLast: true, isStreaming: true)
        XCTAssertNotEqual(
            cache.streamingTailLayoutToken(for: partialInput),
            cache.streamingTailLayoutToken(for: completeInput)
        )

        let firstOutput = makeRow(message: message(input: #"{"query":"swift"}"#, output: "结果 A"), isLast: true, isStreaming: true)
        let changedOutput = makeRow(message: message(input: #"{"query":"swift"}"#, output: "结果 B"), isLast: true, isStreaming: true)
        XCTAssertNotEqual(
            cache.streamingTailLayoutToken(for: firstOutput),
            cache.streamingTailLayoutToken(for: changedOutput)
        )
    }

    func testSwiftUICleanListKeepsHistoricalStreamedRowsOnStreamingRenderer() {
        let message = makeTextMessage(id: KotlinUuid.companion.random(), text: "已完成的流式 Markdown")
        let streamedHistory = makeRow(message: message, isLast: false, isStreaming: false)
        let row = ChatMessageRowModel(
            rowId: streamedHistory.rowId,
            messageId: streamedHistory.messageId,
            message: streamedHistory.message,
            role: streamedHistory.role,
            parts: streamedHistory.parts,
            index: streamedHistory.index,
            isLast: false,
            isStreaming: false,
            hasEverStreamed: true,
            canAnimateInsertion: false
        )

        let state = ChatSwiftUICleanListRenderPolicy.nonLiveTailState(for: row)

        XCTAssertEqual(state?.rendererMode, .streamingMarkdown)
        XCTAssertEqual(state?.hasEverStreamed, true)
        XCTAssertEqual(state?.liveRenderingEnabled, true)
        XCTAssertNil(state?.frozenMarkdownSnapshot)
    }

    func testSwiftUICleanListKeepsLiveTailRendererStableWhileUpdatesAreSuspended() {
        let row = makeRow(
            message: makeTextMessage(id: KotlinUuid.companion.random(), text: "正在流式生成"),
            isLast: true,
            isStreaming: true
        )

        let state = ChatSwiftUICleanListRenderPolicy.liveTailState(for: row)

        XCTAssertEqual(state?.rendererMode, .streamingMarkdown)
        XCTAssertEqual(state?.hasEverStreamed, true)
        XCTAssertEqual(state?.liveRenderingEnabled, true)
        XCTAssertNil(state?.frozenMarkdownSnapshot)
    }

    func testSwiftUICleanListRowsUseDigestEquatableWrapper() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let appDirectory = testDirectory.deletingLastPathComponent().appendingPathComponent("iosApp")
        let source = try String(
            contentsOf: appDirectory.appendingPathComponent("ChatCollectionMessageList.swift"),
            encoding: .utf8
        )

        XCTAssertTrue(
            source.contains("private struct ChatSwiftUIMessageBubble: View, @MainActor Equatable"),
            "Default SwiftUI clean-list rows must have their own Equatable wrapper; native-only row gating does not protect the default path."
        )
        XCTAssertTrue(
            source.contains("ChatSwiftUIMessageBubble(") && source.contains(".equatable()"),
            "ChatSwiftUIMessageList.messageRow should render through the Equatable wrapper."
        )
        XCTAssertTrue(
            source.contains("@State private var swiftUIContentHashCache = ChatRowContentHashCache()") &&
                source.contains("@State private var swiftUIRenderStateStore = ChatRenderStateStore()") &&
                source.contains("ChatRowDigests.digest("),
            "Default SwiftUI rows must use the shared digest/content-hash gate instead of comparing full UIMessage values."
        )
        XCTAssertTrue(
            source.contains("ChatSwiftUIStreamingTailRenderPolicy.shouldSuspend(") &&
                source.contains("isLastAssistant: row.isLast && row.role == MessageRole.assistant") &&
                source.contains("hasEverStreamed: row.hasEverStreamed") &&
                source.contains("swiftUIContentHashCache.suspendedStreamingTailLayoutToken(for: row)") &&
                source.contains("proxy.bounds(of: .scrollView)"),
            "The default clean-list may suspend streaming-tail invalidation only after that tail row is confirmed offscreen."
        )
        XCTAssertTrue(
            source.contains("if lhs.updatesSuspended") &&
                source.contains("lhs.updatesSuspended == rhs.updatesSuspended"),
            "Streaming deltas must keep a confirmed-offscreen tail bubble stable until it becomes visible."
        )
        XCTAssertFalse(
            source.contains("row.isStreaming && viewportState.liveRenderingFarFromBottom"),
            "Distance-to-bottom is not proof that a very tall streaming tail is invisible."
        )
    }
}
