//
//  Vendored regression test (AmberAgent): the paragraph text layout must never
//  be wider than the view's actual bounds, even when SwiftUI measurement passes
//  (sizeThatFits at the column width) and the final frame (narrower) interleave
//  with streaming appends.
//
//  Invalidation contract (2026-08-09): `layoutSubviews` is the single
//  authoritative writer of `textContainer.size` (bounds-derived) and the only
//  invalidation source that runs on every layout pass. `sizeThatFits` only
//  re-sizes + re-lays-out on a real width transition (>= 0.5pt), a
//  height-sentinel repair, or the first measurement — and it restores the
//  container width afterwards, so no transient measurement width survives the
//  call (side-effect-free measurement; the table cell measurement chain can no
//  longer swing the container between its unspecified-width and column-width
//  passes). Fragments always settle at the bounds width at rest: either the
//  measurement itself re-lays them at the proposal, or `layoutSubviews` re-syncs
//  the container to bounds and the next layout/draw re-lays at that width.
//

import XCTest
import UIKit
@testable import SwiftStreamingMarkdown

@MainActor
final class ParagraphFragmentWidthClampTests: XCTestCase {

    private func makeContents(text: String) -> NSMutableAttributedString {
        NSMutableAttributedString(
            string: text,
            attributes: [.font: UIFont.preferredFont(forTextStyle: .body)]
        )
    }

    private var longCJK: String {
        String(repeating: "侠皇帝的丰富历史形象源远流长其魅力经久不衰这个称谓背后凝聚了武侠叙事中最具辨识度的角色原型", count: 6)
    }

    /// 生产时序：SwiftUI 测量 pass 以列宽（370）测量，随后流式 append 触发
    /// TextKit 在“当前容器宽”重新排版，最后视图 frame 收窄并 layout —— 任何
    /// 时刻 lineFragment 都不允许宽于视图 bounds，否则宽 fragments 被视图裁掉
    /// （真机“段落左缘贴边/半裁字符”的布局级形态）。
    ///
    /// 单写者契约：测量回报提案宽（调用内 fragments 已按提案宽重排），但容器
    /// 还原为测量前值（side-effect-free）；随后任何收窄 placement 由
    /// layoutSubviews 把容器同步到 bounds，fragments 在下次排版时落回真实宽度。
    func testFragmentsRelayoutAfterMeasureThenAppendWhenBoundsShrink() {
        let view = ParagraphUIView.makeTextKit1View()
        let text = longCJK
        view.setParagraphContents(makeContents(text: text), lineSpacing: 4, animatedByWord: false)
        view.frame = CGRect(x: 0, y: 0, width: 354, height: 200)
        view.setNeedsLayout()
        view.layoutIfNeeded()

        // 测量 pass：回报提案宽 370（调用内 fragments 重排到 370），
        // 容器还原为测量前的 bounds 宽（354），不残留测量宽。
        let fitted = view.sizeThatFits(CGSize(width: 370, height: CGFloat.greatestFiniteMagnitude))
        XCTAssertEqual(fitted.width, 370, accuracy: 1, "测量必须回报提案宽，实际=\(fitted.width)")
        XCTAssertEqual(
            view.textContainer.size.width, 354, accuracy: 0.5,
            "测量后容器必须还原为测量前值，实际=\(view.textContainer.size.width)"
        )

        // 流式 append：文本变化 → TextKit 在“当前容器宽”（354）重新排版。
        let appended = makeContents(text: text + "后续流式追加的内容继续延续这段长文本的宽度以逼近真实场景")
        view.setParagraphContents(appended, lineSpacing: 4, animatedByWord: true)

        // 视图收窄到 344 后的 layout pass：layoutSubviews（唯一权威写者）把
        // 容器同步到 344 并失效，fragments 重排到真实宽度，不越界。
        view.frame = CGRect(x: 0, y: 0, width: 344, height: 400)
        view.setNeedsLayout()
        view.layoutIfNeeded()

        let maxFragmentWidth = Self.maxLineFragmentWidth(in: view)
        print("PROBE maxFragmentWidth=\(maxFragmentWidth) bounds=\(view.bounds.width)")
        XCTAssertLessThanOrEqual(
            maxFragmentWidth,
            view.bounds.width + 1,
            "lineFragment 宽 \(maxFragmentWidth) 超过视图 bounds \(view.bounds.width)，段落被裁"
        )
        XCTAssertLessThanOrEqual(
            maxFragmentWidth,
            345,
            "fragments 必须重排到收窄后的 bounds \(view.bounds.width)，不能停留在测量宽 370"
        )
    }

    /// 变体：bounds 先于测量变得可用（列宽 370），随后视图收窄到 354。
    func testFragmentsRelayoutWhenBoundsNarrowAfterWideLayout() {
        let view = ParagraphUIView.makeTextKit1View()
        view.frame = CGRect(x: 0, y: 0, width: 370, height: 400)
        view.setParagraphContents(makeContents(text: longCJK), lineSpacing: 4, animatedByWord: false)
        view.setNeedsLayout()
        view.layoutIfNeeded()
        let wideFragments = Self.maxLineFragmentWidth(in: view)
        XCTAssertGreaterThan(wideFragments, 354, "前置条件：370 宽容器下 fragments 应为整行宽 \(wideFragments)")

        // 视图收窄（生产里 SwiftUI 最终 frame 可以窄于测量宽）。
        view.frame = CGRect(x: 0, y: 0, width: 354, height: 400)
        view.setNeedsLayout()
        view.layoutIfNeeded()

        let maxFragmentWidth = Self.maxLineFragmentWidth(in: view)
        print("PROBE narrow: maxFragmentWidth=\(maxFragmentWidth) bounds=\(view.bounds.width)")
        XCTAssertLessThanOrEqual(
            maxFragmentWidth,
            view.bounds.width + 1,
            "bounds 收窄后 lineFragment 仍为旧宽 \(maxFragmentWidth) > \(view.bounds.width)"
        )
    }

    /// 窄 → 宽：段落先按窄宽排版（fragments 354），随后 SwiftUI 用列宽 370
    /// 测量。测量必须回报 370（调用内 fragments 已按 370 重排）——否则段落
    /// 永远按旧宽排版（聊天列右侧留空 / 行宽不足）。容器随后还原，不留测量宽。
    func testMeasurementRelayoutsFragmentsToProposedWidth() {
        let view = ParagraphUIView.makeTextKit1View()
        view.frame = CGRect(x: 0, y: 0, width: 354, height: 200)
        view.setParagraphContents(makeContents(text: longCJK), lineSpacing: 4, animatedByWord: false)
        view.setNeedsLayout()
        view.layoutIfNeeded()
        XCTAssertEqual(Self.maxLineFragmentWidth(in: view), 354, accuracy: 1)

        let proposed: CGFloat = 370
        let fitted = view.sizeThatFits(CGSize(width: proposed, height: .greatestFiniteMagnitude))
        print("PROBE narrow->wide: fitted=\(fitted) fragments=\(Self.maxLineFragmentWidth(in: view))")
        XCTAssertEqual(
            fitted.width, proposed, accuracy: 1,
            "sizeThatFits(\(proposed)) 应回报 \(proposed)，实际回报陈旧窄宽 \(fitted.width)"
        )
        XCTAssertEqual(
            view.textContainer.size.width, 354, accuracy: 1,
            "测量后容器应还原为测量前值（bounds 宽），实际=\(view.textContainer.size.width)"
        )
    }

    /// 宽 → 窄：视图从宽上下文（表格 cell / 屏幕宽测量）回收后，聊天列用窄宽
    /// 370 测量。测量必须回报 ≤ 370（调用内 fragments 重排到 370），且后续
    /// 真实 bounds 落位时 fragments 不得停留在陈旧宽（394）——否则行宽超出
    /// 列宽、文本被视界裁掉（真机「段落左缘贴边/半裁字符」的布局级形态）。
    func testStaleWideFragmentsRelayoutToProposedWidth() {
        let view = ParagraphUIView.makeTextKit1View()
        view.frame = CGRect(x: 0, y: 0, width: 394, height: 200)
        view.setParagraphContents(makeContents(text: longCJK), lineSpacing: 4, animatedByWord: false)
        view.setNeedsLayout()
        view.layoutIfNeeded()
        XCTAssertGreaterThan(Self.maxLineFragmentWidth(in: view), 370, "前置条件：宽上下文 fragments 应为整行宽")

        let proposed: CGFloat = 370
        let fitted = view.sizeThatFits(CGSize(width: proposed, height: .greatestFiniteMagnitude))
        print("PROBE wide->narrow: fitted=\(fitted) fragments=\(Self.maxLineFragmentWidth(in: view))")
        XCTAssertLessThanOrEqual(fitted.width, proposed + 1, "回报宽度不得超出提案 \(proposed)")

        // 落位到窄 bounds（370）：layoutSubviews 同步容器并失效，fragments
        // 重排到 370，不得停留在陈旧宽 394。
        view.frame = CGRect(x: 0, y: 0, width: proposed, height: 400)
        view.setNeedsLayout()
        view.layoutIfNeeded()
        let settledWidth = Self.maxLineFragmentWidth(in: view)
        XCTAssertEqual(
            settledWidth, proposed, accuracy: 1,
            "落位后 fragments 必须重排到 \(proposed)，仍为陈旧宽 \(settledWidth)"
        )
    }

    private static func maxLineFragmentWidth(in view: ParagraphUIView) -> CGFloat {
        guard view.usesTextKit1 else { return 0 }
        let layoutManager = view.layoutManager
        _ = layoutManager.glyphRange(for: view.textContainer)
        let glyphCount = layoutManager.numberOfGlyphs
        guard glyphCount > 0 else { return 0 }
        var maxWidth: CGFloat = 0
        var index = 0
        while index < glyphCount {
            var range = NSRange(location: 0, length: 0)
            let rect = layoutManager.lineFragmentRect(forGlyphAt: index, effectiveRange: &range)
            maxWidth = max(maxWidth, rect.width)
            guard range.length > 0 else { break }
            index = range.location + range.length
        }
        return maxWidth
    }
}
