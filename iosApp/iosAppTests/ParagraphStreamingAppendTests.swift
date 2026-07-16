import XCTest
import UIKit
@testable import SwiftStreamingMarkdown
@testable import iosApp

/// vendor TextKit 1 流式 append 快路径的行为契约。
///
/// 长文流式的每次发布此前会整段替换 `attributedText` 并在测量时抖动
/// text container,导致 TextKit 每次发布都 O(全文) 重排版(真机上表现为
/// 「四五行攒一批再上移」)。快路径只在「新内容是旧内容的 attributed 前缀
/// 扩展」时把尾部 delta 插入 textStorage;本套件锁定两条不可放宽的契约:
/// 1. 增量路径累计后的测量与文本必须与一次性全量布局完全一致;
/// 2. 前缀判定必须在字符或属性发生任何改写时拒绝,回退全量替换。
@MainActor
final class ParagraphStreamingAppendTests: XCTestCase {

    private let attributes: [NSAttributedString.Key: Any] = [
        .font: UIFont.systemFont(ofSize: 17),
        .foregroundColor: UIColor.label
    ]

    private func makeLongParagraph(targetUTF16: Int) -> String {
        let sentence = "他沿着长街一直走,雨点敲在瓦当上,远处的钟声混着人声一层一层漫过来,"
        var text = "连续正文开始。"
        while text.utf16.count < targetUTF16 {
            text += sentence
        }
        return text
    }

    // MARK: - appendedTailRange 判定

    func testRemountedStreamingParagraphSuppressesWholeParagraphFade() {
        XCTAssertFalse(
            ParagraphInitialFadePolicy.shouldAnimate(
                configShouldAnimateText: true,
                animateInitialText: true,
                suppressesInitialFade: true
            )
        )
        XCTAssertTrue(
            ParagraphInitialFadePolicy.shouldAnimate(
                configShouldAnimateText: true,
                animateInitialText: true,
                suppressesInitialFade: false
            )
        )
    }

    func testAppendedTailRangeDetectsPureAppend() {
        let old = NSAttributedString(string: "前缀文本", attributes: attributes)
        let new = NSAttributedString(string: "前缀文本追加", attributes: attributes)
        XCTAssertEqual(
            old.appendedTailRange(toBecome: new),
            NSRange(location: 4, length: 2)
        )
    }

    func testAppendedTailRangeRejectsChangedCharacters() {
        let old = NSAttributedString(string: "前缀文本", attributes: attributes)
        let new = NSAttributedString(string: "前改文本追加", attributes: attributes)
        XCTAssertNil(old.appendedTailRange(toBecome: new))
    }

    func testAppendedTailRangeRejectsChangedPrefixAttributes() {
        let old = NSAttributedString(string: "前缀文本", attributes: attributes)
        // 模拟 speculative rewrite 改写了前缀属性(如半开强调闭合成粗体)。
        let mutated = NSMutableAttributedString(string: "前缀文本追加", attributes: attributes)
        mutated.addAttribute(
            .font,
            value: UIFont.systemFont(ofSize: 17, weight: .semibold),
            range: NSRange(location: 0, length: 2)
        )
        XCTAssertNil(old.appendedTailRange(toBecome: mutated))
    }

    func testAppendedTailRangeRejectsEqualOrShorterText() {
        let old = NSAttributedString(string: "前缀文本", attributes: attributes)
        XCTAssertNil(old.appendedTailRange(toBecome: old))
        XCTAssertNil(old.appendedTailRange(toBecome: NSAttributedString(string: "前缀", attributes: attributes)))
        XCTAssertNil(NSAttributedString().appendedTailRange(toBecome: old))
    }

    // MARK: - 增量 == 全量

    func testIncrementalStreamingAppendsMatchBatchLayoutExactly() {
        let width: CGFloat = 361
        let measureSize = CGSize(width: width, height: .greatestFiniteMagnitude)
        let base = makeLongParagraph(targetUTF16: 24_000)

        let streamingView = ParagraphUIView.makeTextKit1View()
        streamingView.frame = CGRect(x: 0, y: 0, width: width, height: 10)
        streamingView.setParagraphContents(
            NSMutableAttributedString(string: base, attributes: attributes),
            lineSpacing: 4,
            animatedByWord: false
        )
        _ = streamingView.sizeThatFits(measureSize)

        var text = base
        var incrementalSize: CGSize = .zero
        for _ in 0..<10 {
            text += "流式追加的十二个字符字符字"
            streamingView.setParagraphContents(
                NSMutableAttributedString(string: text, attributes: attributes),
                lineSpacing: 4,
                animatedByWord: true
            )
            incrementalSize = streamingView.sizeThatFits(measureSize)
        }

        let batchView = ParagraphUIView.makeTextKit1View()
        batchView.frame = CGRect(x: 0, y: 0, width: width, height: 10)
        batchView.setParagraphContents(
            NSMutableAttributedString(string: text, attributes: attributes),
            lineSpacing: 4,
            animatedByWord: false
        )
        let batchSize = batchView.sizeThatFits(measureSize)

        XCTAssertEqual(incrementalSize, batchSize, "增量 append 的测量必须与一次性全量布局一致")
        XCTAssertEqual(
            streamingView.textStorage.string,
            batchView.textStorage.string,
            "增量 append 后的文本必须与全量一致"
        )
        XCTAssertTrue(streamingView.usesTextKit1)
        XCTAssertEqual(streamingView.paragraphContents.string, text)
    }

    /// 机制门禁(红→绿):流式增量发布的主线程成本必须显著低于同内容的
    /// 一次性全量布局。旧实现每次发布整段替换 + 全量重排版,两者成本相当
    /// (比值 ≈ 1);增量路径下发布只排版新增尾行(比值 ≥ 4)。用比值而非
    /// 绝对时间,避免 CI 机器速度差把门禁变成噪声。
    func testIncrementalPublishIsMuchCheaperThanFullRelayout() {
        let width: CGFloat = 361
        let measureSize = CGSize(width: width, height: .greatestFiniteMagnitude)
        let base = makeLongParagraph(targetUTF16: 24_000)

        let streamingView = ParagraphUIView.makeTextKit1View()
        streamingView.frame = CGRect(x: 0, y: 0, width: width, height: 10)
        streamingView.setParagraphContents(
            NSMutableAttributedString(string: base, attributes: attributes),
            lineSpacing: 4,
            animatedByWord: false
        )
        _ = streamingView.sizeThatFits(measureSize)

        var text = base
        var publishDurations: [Double] = []
        for _ in 0..<10 {
            text += "流式追加的十二个字符字符字"
            let contents = NSMutableAttributedString(string: text, attributes: attributes)
            let start = CFAbsoluteTimeGetCurrent()
            streamingView.setParagraphContents(contents, lineSpacing: 4, animatedByWord: true)
            _ = streamingView.sizeThatFits(measureSize)
            publishDurations.append(CFAbsoluteTimeGetCurrent() - start)
        }

        var fullDurations: [Double] = []
        for _ in 0..<3 {
            let batchView = ParagraphUIView.makeTextKit1View()
            batchView.frame = CGRect(x: 0, y: 0, width: width, height: 10)
            let start = CFAbsoluteTimeGetCurrent()
            batchView.setParagraphContents(
                NSMutableAttributedString(string: text, attributes: attributes),
                lineSpacing: 4,
                animatedByWord: false
            )
            _ = batchView.sizeThatFits(measureSize)
            fullDurations.append(CFAbsoluteTimeGetCurrent() - start)
        }

        let medianPublish = publishDurations.sorted()[publishDurations.count / 2]
        let medianFull = fullDurations.sorted()[fullDurations.count / 2]
        XCTAssertGreaterThan(
            medianFull / medianPublish,
            2.5,
            String(
                format: "24KB 段落的流式发布不能退回 O(全文) 重排版:每次发布 %.1fms vs 全量 %.1fms",
                medianPublish * 1000,
                medianFull * 1000
            )
        )
    }

    func testRewriteFlipFallsBackToFullReplacement() {
        let width: CGFloat = 361
        let measureSize = CGSize(width: width, height: .greatestFiniteMagnitude)

        let view = ParagraphUIView.makeTextKit1View()
        view.frame = CGRect(x: 0, y: 0, width: width, height: 10)
        view.setParagraphContents(
            NSMutableAttributedString(string: "普通开头 加粗中", attributes: attributes),
            lineSpacing: 4,
            animatedByWord: false
        )
        _ = view.sizeThatFits(measureSize)

        // 前缀属性被改写(粗体扩散到旧字符):必须整体替换而不是错误地 append。
        let rewritten = NSMutableAttributedString(string: "普通开头 加粗中继续", attributes: attributes)
        rewritten.addAttribute(
            .font,
            value: UIFont.systemFont(ofSize: 17, weight: .semibold),
            range: NSRange(location: 5, length: 5)
        )
        view.setParagraphContents(rewritten, lineSpacing: 4, animatedByWord: true)

        XCTAssertEqual(view.textStorage.string, "普通开头 加粗中继续")
        var effectiveRange = NSRange()
        let font = view.textStorage.attribute(.font, at: 5, effectiveRange: &effectiveRange) as? UIFont
        XCTAssertEqual(font?.fontDescriptor.symbolicTraits.contains(.traitBold), true)
    }

    func testAttachmentFreeAppendUpdatesPlainAccessibilityWithoutCustomActions() {
        let view = ParagraphUIView.makeTextKit1View()
        view.setParagraphContents(
            NSMutableAttributedString(string: "第一段", attributes: attributes),
            animatedByWord: false
        )
        view.setParagraphContents(
            NSMutableAttributedString(string: "第一段继续", attributes: attributes),
            animatedByWord: true
        )

        XCTAssertEqual(view.accessibilityLabel, "第一段继续")
        XCTAssertNil(view.accessibilityCustomActions)
    }
}
