import XCTest
@testable import iosApp

/// `ChatReasoningCard` 可见性判断的行为契约与热路径特征。
///
/// `hasBodyText` 经由 `showsBody` 在一次 body 求值中被求值约 10 次
/// (`showsBody` 出现在圆角/chevron/高度/mask/animation 等多处),流式期间
/// 每 48ms 一轮。因此这个判断必须是零分配、可提前退出的——它一旦按全文
/// 分配副本,上万字符的推理就会每秒产生 MB 级主线程临时分配。
@MainActor
final class ChatReasoningCardTests: XCTestCase {

    // MARK: - 行为契约

    func testVisibleTextRecognisesEmptyAndBlankBodies() {
        XCTAssertFalse(ChatReasoningCard.hasVisibleText(""))
        XCTAssertFalse(ChatReasoningCard.hasVisibleText(" "))
        XCTAssertFalse(ChatReasoningCard.hasVisibleText("\n\n"))
        XCTAssertFalse(ChatReasoningCard.hasVisibleText(" \t\r\n "))
    }

    func testVisibleTextRecognisesRealContent() {
        XCTAssertTrue(ChatReasoningCard.hasVisibleText("a"))
        XCTAssertTrue(ChatReasoningCard.hasVisibleText("推理"))
        XCTAssertTrue(ChatReasoningCard.hasVisibleText("  前导空白后有内容"))
        XCTAssertTrue(ChatReasoningCard.hasVisibleText("内容后有尾随空白  \n"))
    }

    /// 与被替换的 `trimmingCharacters(in: .whitespacesAndNewlines).isEmpty`
    /// 逐例等价——包含 CharacterSet 与 Unicode `White_Space` 都覆盖的边界字符。
    func testVisibleTextMatchesTrimmingSemantics() {
        let cases = [
            "", " ", "\n", "\t", "\r\n", "\u{000B}", "\u{000C}", "\u{0085}",
            "\u{00A0}", "\u{2028}", "\u{2029}", "\u{3000}",
            "x", " x ", "\u{00A0}x", "。", "\u{3000}。\u{3000}",
        ]
        for text in cases {
            XCTAssertEqual(
                ChatReasoningCard.hasVisibleText(text),
                !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                "可见性判断必须与旧的 trimming 语义一致:\(text.debugDescription)"
            )
        }
    }

    // MARK: - 热路径特征(红→绿判别)

    /// 正文以换行开头时,判断的成本不得随正文长度增长。
    ///
    /// fixture 刻意以 `\n\n` 开头:这是模型 thinking 的常见形态,也是
    /// `trimmingCharacters` 唯一会真的分配全文副本的形态(首字符非空白时
    /// Foundation 走零拷贝快路径,掩盖问题)。规模对应约 1M 字符的推理正文
    /// × 5 轮 body 求值 × 10 处 `showsBody`/`hasBodyText` 引用。
    ///
    /// 实测(1M 字符,Debug):全文拷贝实现 ~90µs/次 → 50 次约 4.5ms;
    /// 提前退出实现 ~0.3µs/次 → 50 次约 0.02ms。阈值取 1.5ms,两侧各有
    /// 三倍以上余量,不依赖具体机器性能。
    ///
    /// 每次迭代取用独立实例,防止编译器把循环不变的调用外提。
    func testVisibleTextCostDoesNotScaleWithLeadingWhitespaceBody() {
        let iterations = 50
        let filler = String(repeating: "推理内容。", count: 200_000)
        let bodies = (0..<iterations).map { "\n\n" + filler + String($0) }

        let start = Self.mainThreadCPUNanos()
        var truthy = 0
        for body in bodies where ChatReasoningCard.hasVisibleText(body) {
            truthy += 1
        }
        let elapsedNanos = Self.mainThreadCPUNanos() - start

        XCTAssertEqual(truthy, iterations)
        let elapsedMillis = Double(elapsedNanos) / 1_000_000
        print(String(
            format: "[PERF] hasVisibleText chars=%d iterations=%d total=%.2fms",
            bodies[0].count, iterations, elapsedMillis
        ))
        XCTAssertLessThan(
            elapsedMillis,
            1.5,
            "以空白开头的推理正文不得让可见性判断按全文分配副本"
        )
    }

    private static func mainThreadCPUNanos() -> UInt64 {
        clock_gettime_nsec_np(CLOCK_THREAD_CPUTIME_ID)
    }
}
