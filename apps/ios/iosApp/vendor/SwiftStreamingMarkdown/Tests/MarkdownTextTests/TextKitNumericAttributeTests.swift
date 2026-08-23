//
//  Vendored addition (AmberAgent).
//  TextKit calls `integerValue` on underline/strikethrough/ligature attributes
//  while drawing a UITextView. A Swift empty array, OptionSet, or other
//  non-NSNumber boxed into those keys abort the process:
//  `-[...] integerValue]: unrecognized selector`.
//

import Markdown
@testable import SwiftStreamingMarkdown
import UIKit
import XCTest

@MainActor
final class TextKitNumericAttributeTests: XCTestCase {
  func testMarkdownLinkUnderlineStyleIsNSNumber() async {
    guard let attributed = await paragraphContents(from: "[Grok](https://x.ai)") else { return }
    let range = NSRange(location: 0, length: attributed.length)
    var underlineValue: Any?
    attributed.enumerateAttribute(.underlineStyle, in: range) { value, _, _ in
      if let value {
        underlineValue = value
      }
    }
    XCTAssertNotNil(underlineValue, "a markdown link must set underlineStyle")
    XCTAssertTrue(
      underlineValue is NSNumber,
      "TextKit draws underlineStyle via integerValue; got \(type(of: underlineValue as Any))"
    )
  }

  func testDrawingMarkdownLinkDoesNotThrow() async {
    guard let attributed = await paragraphContents(from: "[Grok](https://x.ai)") else { return }
    drawInTextView(attributed)
  }

  func testEmptyArrayUnderlineStyleIsNotNSNumber() {
    let string = NSMutableAttributedString(string: "link")
    string.addAttribute(.underlineStyle, value: [], range: NSRange(location: 0, length: 4))
    let value = string.attribute(.underlineStyle, at: 0, effectiveRange: nil)
    XCTAssertFalse(
      value is NSNumber,
      "reproduces the vendor link converter storing [] as underlineStyle"
    )
  }

  func testSanitizerCoercesEmptyArrayUnderlineStyleToNSNumber() {
    let string = NSMutableAttributedString(string: "link")
    string.addAttribute(.underlineStyle, value: [], range: NSRange(location: 0, length: 4))
    string.sanitizeNumericTextKitAttributes()
    let value = string.attribute(.underlineStyle, at: 0, effectiveRange: nil)
    XCTAssertTrue(value is NSNumber)
    XCTAssertEqual((value as? NSNumber)?.intValue, 0)
    drawInTextView(string)
  }

  private func paragraphContents(from markdown: String) async -> NSMutableAttributedString? {
    let document = await MarkdownParserImpl().parse(text: markdown)
    guard let paragraph = document.child(at: 0) as? Paragraph else {
      XCTFail("expected a paragraph")
      return nil
    }
    let renderable = paragraph.convert(attributeContainer: .init(), config: .default)
    guard case .paragraph(_, let contents) = renderable else {
      XCTFail("expected paragraph renderable")
      return nil
    }
    return contents
  }

  private func drawInTextView(_ contents: NSMutableAttributedString) {
    let view = ParagraphUIView.makeTextKit1View()
    view.frame = CGRect(x: 0, y: 0, width: 320, height: 44)
    view.setParagraphContents(contents, lineSpacing: 4, animatedByWord: false)
    view.layoutIfNeeded()
    UIGraphicsBeginImageContextWithOptions(view.bounds.size, false, 1)
    defer { UIGraphicsEndImageContext() }
    view.drawHierarchy(in: view.bounds, afterScreenUpdates: true)
  }
}
