//
//  Vendored regression test (AmberAgent): ParagraphUIView must never report a
//  fitting width wider than the width it was asked to measure against.
//

import XCTest
@testable import SwiftStreamingMarkdown
import UIKit

@MainActor
final class ParagraphUIViewWidthClampTests: XCTestCase {

  func testSizeThatFitsClampsUnbreakableRunToProposedWidth() {
    let view = ParagraphUIView.makeTextKit1View()
    let text = String(repeating: "session_id=\"ios_wm_44D388B2\"<", count: 40)
    let contents = NSMutableAttributedString(
      string: text,
      attributes: [
        .font: UIFont.preferredFont(forTextStyle: .body)
      ]
    )
    view.setParagraphContents(contents, lineSpacing: 4, animatedByWord: false)

    let proposed: CGFloat = 349
    let fitted = view.sizeThatFits(CGSize(width: proposed, height: .greatestFiniteMagnitude))
    print("PROBE sizeThatFits fitted=\(fitted) proposed=\(proposed) textChars=\(text.count)")
    XCTAssertLessThanOrEqual(
      fitted.width,
      proposed + 1,
      "sizeThatFits 回报宽 \(fitted.width) > 提案 \(proposed)"
    )
  }

  func testIntrinsicContentSizeDoesNotAdvertiseScreenWidthWithoutSuperview() {
    let view = ParagraphUIView.makeTextKit1View()
    let text = String(repeating: "A", count: 500)
    let contents = NSMutableAttributedString(
      string: text,
      attributes: [
        .font: UIFont.preferredFont(forTextStyle: .body)
      ]
    )
    view.setParagraphContents(contents, lineSpacing: 4, animatedByWord: false)
    // bounds 无效且无 superview → 不得把整屏宽当成 ideal（否则聊天列对称裁切）
    view.bounds = .zero
    view.invalidateIntrinsicContentSize()
    let intrinsic = view.intrinsicContentSize
    XCTAssertEqual(
      intrinsic.width,
      UIView.noIntrinsicMetric,
      "无列宽时应声明 flexible width，实际=\(intrinsic)"
    )
    XCTAssertGreaterThan(intrinsic.height, 0, "高度仍需可测，实际=\(intrinsic)")
  }
}
