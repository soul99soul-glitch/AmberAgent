//
//  Copyright (c) Microsoft Corporation. All rights reserved.
//  Licensed under the MIT License. See LICENSE in the project root for license information.
//

import Markdown
@testable import SwiftStreamingMarkdown
import SwiftUI
import UIKit
import XCTest

@MainActor
final class MarkdownTextTests: XCTestCase {

  let parser: MarkdownParser = MarkdownParserImpl()

  /// Tests regular citation format (old format) by directly testing the convert method
  func testRegularCitationFormat() async throws {
    let markdown = """
    [Microsoft](http://example.com?citationMarker=9F742443)
    """

    let document = await parser.parse(text: markdown)

    // Find the link in the parsed document
    var link: Markdown.Link?
    for child in document.children {
      if let paragraph = child as? Markdown.Paragraph {
        for paragraphChild in paragraph.children {
          if let foundLink = paragraphChild as? Markdown.Link {
            link = foundLink
            break
          }
        }
      }
      if link != nil { break }
    }

    guard let link = link else {
      XCTFail("Expected to find a link in the parsed markdown")
      return
    }

    // Verify this is NOT an attachment citation (it's a regular citation)
    XCTAssertFalse(link.isInlineCitation(coder: .default), "Link should NOT be detected as attachment citation")

    // Test the convert method directly
    let attributeContainer: [NSAttributedString.Key: Any] = [:]
    let convertedString = link.convert(
      attributeContainer: attributeContainer,
      config: .default
    )

    // Regular citations should show the link text "Microsoft", not the internal marker
    XCTAssertTrue(
      convertedString.string.contains("Microsoft"),
      "DIRECT convert() call should return the link text for regular citations. Got: '\(convertedString.string)'"
    )
    XCTAssertFalse(
      convertedString.string.contains("9F742443"),
      "Regular citations should not show the internal marker UUID"
    )
  }

  /// Tests attachment citation format by directly testing the convert method
  func testAttachmentCitationFormat() async throws {
    let markdown = """
    [9F742443](http://example.com?citationMarker=9F742443&citationTitle=Microsoft&citationA11yValue=Microsoft)
    """

    let document = await parser.parse(text: markdown)

    // Find the link in the parsed document - need to traverse children properly
    var link: Markdown.Link?
    for child in document.children {
      if let paragraph = child as? Markdown.Paragraph {
        for paragraphChild in paragraph.children {
          if let foundLink = paragraphChild as? Markdown.Link {
            link = foundLink
            break
          }
        }
      }
      if link != nil { break }
    }

    guard let link = link else {
      XCTFail("Expected to find a link in the parsed markdown")
      return
    }

    // Verify this is an attachment citation
    XCTAssertTrue(link.isInlineCitation(coder: .default), "Link should be detected as attachment citation")

    // Test the convert method directly - this should expose the bug
    let attributeContainer: [NSAttributedString.Key: Any] = [:]
    let convertedString = link.convert(
      attributeContainer: attributeContainer,
      config: .default
    )

    // Verify that we get an attachment, not plain text
    XCTAssertEqual(convertedString.length, 1, "Should have exactly one attachment character")

    // Get the attachment and verify it contains the correct data
    var attachmentFound = false
    convertedString.enumerateAttribute(.attachment, in: NSRange(location: 0, length: convertedString.length), options: []) { (attachment, _, _) in
      if let citationAttachment = attachment as? InlineCitationAttachment,
         let citationData = citationAttachment.citationData {
        XCTAssertEqual(citationData.title, "Microsoft", "Citation attachment should contain title 'Microsoft'")
        XCTAssertEqual(citationData.accessibilityLabel, "Microsoft", "Citation attachment should have accessibility label 'Microsoft'")
        attachmentFound = true
      }
    }

    XCTAssertTrue(attachmentFound, "Should find a citation attachment with proper data")
  }

  /// Tests fallback behavior when attachment citation data is malformed
  func testAttachmentCitationFallbackBehavior() async throws {
    // Malformed attachment citation - missing citationTitle parameter
    let markdown = """
    [9F742443](http://example.com?citationMarker=9F742443&brokenParam=value)
    """

    let document = await parser.parse(text: markdown)

    // Find the link in the parsed document
    var link: Markdown.Link?
    for child in document.children {
      if let paragraph = child as? Markdown.Paragraph {
        for paragraphChild in paragraph.children {
          if let foundLink = paragraphChild as? Markdown.Link {
            link = foundLink
            break
          }
        }
      }
      if link != nil { break }
    }

    guard let link = link else {
      XCTFail("Expected to find a link in the parsed markdown")
      return
    }

    // Verify this is an attachment citation (UUID in link text)
    XCTAssertTrue(link.isInlineCitation(coder: .default), "Link should be detected as attachment citation")

    // Test the convert method - should return empty for malformed attachment citations
    let attributeContainer: [NSAttributedString.Key: Any] = [:]
    let convertedString = link.convert(
      attributeContainer: attributeContainer,
      config: .default
    )

    // Should return empty string when attachment data extraction fails (better UX than showing UUID)
    XCTAssertEqual(
      convertedString.string,
      "",
      "Malformed attachment citations should return empty string rather than showing confusing UUIDs to users"
    )
  }

  // MARK: - BlockQuote Citation Integration Tests

  /// Tests that BlockQuote correctly renders attachment citations without showing UUIDs
  func testBlockQuoteWithAttachmentCitations() async throws {
    let markdown = """
    > This quote contains an attachment citation [9F742443](http://example.com?citationMarker=9F742443&citationTitle=Microsoft&citationA11yValue=Microsoft) and regular citation [Google](http://example.com?citationMarker=9F742443)
    """

    let document = await parser.parse(text: markdown)

    // Find the BlockQuote in the parsed document
    var blockQuote: BlockQuote?
    for child in document.children {
      if let foundBlockQuote = child as? BlockQuote {
        blockQuote = foundBlockQuote
        break
      }
    }

    guard let blockQuote = blockQuote else {
      XCTFail("Expected to find a BlockQuote in the parsed markdown")
      return
    }

    // Test the quoteTypes property (this was the main bug)
    let quoteTypes = blockQuote.quoteTypes

    // Extract the text from the quote types
    var extractedText = ""
    switch quoteTypes {
    case .nested(let types):
      for type in types {
        switch type {
        case .text(let text):
          extractedText = text
        default:
          break
        }
      }
    default:
      XCTFail("Expected nested quote types")
    }

    // Verify that the extracted text contains the citation titles, not UUIDs
    XCTAssertTrue(
      extractedText.contains("Microsoft"),
      "BlockQuote should show attachment citation title 'Microsoft', not UUID. Got: '\(extractedText)'"
    )
    XCTAssertTrue(
      extractedText.contains("Google"),
      "BlockQuote should show regular citation title 'Google'. Got: '\(extractedText)'"
    )
    XCTAssertFalse(
      extractedText.contains("9F742443"),
      "BlockQuote should NOT show the UUID marker in plain text. Got: '\(extractedText)'"
    )
  }

  /// Tests that plain text extraction works correctly for both citation types
  func testPlainTextExtractionForCitations() async throws {
    let markdownWithBothTypes = """
    Regular citation: [Microsoft](http://example.com?citationMarker=9F742443)

    Attachment citation: [9F742443](http://example.com?citationMarker=9F742443&citationTitle=Google&citationA11yValue=Google)
    """

    let plainText = await markdownWithBothTypes.markdownToPlainText()

    // Verify both citation types show proper titles
    XCTAssertTrue(
      plainText.contains("Microsoft"),
      "Plain text should show regular citation title. Got: '\(plainText)'"
    )
    XCTAssertTrue(
      plainText.contains("Google"),
      "Plain text should show attachment citation title extracted from URL. Got: '\(plainText)'"
    )
    XCTAssertFalse(
      plainText.contains("9F742443"),
      "Plain text should NOT contain UUID marker. Got: '\(plainText)'"
    )
  }

  func testMarkdownNestedFormatting() async throws {
    let text = """
    # Header with *italic* and **bold** and ***both***

    Normal text with ***bold italic*** and **nested *italic* inside** bold.
    """

    let document = await parser.parse(text: text)
    let renderableDoc = await RenderableDocument(document: document, config: .default)
    let renderables = renderableDoc.renderables

    // Verify it parses without error
    XCTAssertEqual(renderables.count, 2)

    let headingFonts = Typography.extraLargeTextFonts
    let paragraphFonts = Typography.baseTextFonts

    // 1. Inspect Heading
    guard case let .heading(_, level, headingContent) = renderables[0] else {
      XCTFail("First renderable should be a heading")
      return
    }
    XCTAssertEqual(level, 1)

    // Check that "italic" has italic font
    let italicRange = (headingContent.string as NSString).range(of: "italic")
    let italicFont = headingContent.attribute(.font, at: italicRange.location, effectiveRange: nil) as? UIFont
    XCTAssertEqual(italicFont, headingFonts.italic)

    // Check that "bold" has bold font
    let boldRange = (headingContent.string as NSString).range(of: "bold")
    let boldFont = headingContent.attribute(.font, at: boldRange.location, effectiveRange: nil) as? UIFont
    XCTAssertEqual(boldFont, headingFonts.bold)

    // Check that "both" has boldItalic font (nested Strong(Emphasis) resolves to boldItalic)
    let bothRange = (headingContent.string as NSString).range(of: "both")
    let bothFont = headingContent.attribute(.font, at: bothRange.location, effectiveRange: nil) as? UIFont
    XCTAssertEqual(bothFont, headingFonts.boldItalic)

    // 2. Inspect Paragraph
    guard case let .paragraph(_, paragraphContent) = renderables[1] else {
      XCTFail("Second renderable should be a paragraph")
      return
    }

    // Check "nested " (part of **nested *italic* inside**)
    let nestedRange = (paragraphContent.string as NSString).range(of: "nested ")
    let nestedFont = paragraphContent.attribute(.font, at: nestedRange.location, effectiveRange: nil) as? UIFont
    XCTAssertEqual(nestedFont, paragraphFonts.bold)

    // Check "italic" (nested inside bold) -> boldItalic
    let nestedItalicRange = (paragraphContent.string as NSString).range(of: "italic")
    let nestedItalicFont = paragraphContent.attribute(.font, at: nestedItalicRange.location, effectiveRange: nil) as? UIFont
    XCTAssertEqual(nestedItalicFont, paragraphFonts.boldItalic)
  }

  func testRenderableDocumentReusesOnlyUnchangedPrefixObjects() async throws {
    let initial = await parser.parse(text: "First paragraph.\n\nSecond paragraph.")
    let updated = await parser.parse(text: "First paragraph.\n\nSecond paragraph grows.")
    let initialRenderable = await RenderableDocument(document: initial, config: .default)
    let updatedRenderable = await RenderableDocument(document: updated, config: .default)
      .reusingUnchangedPrefix(from: initialRenderable)

    guard case let .paragraph(_, initialFirst) = initialRenderable.renderables[0],
          case let .paragraph(_, updatedFirst) = updatedRenderable.renderables[0],
          case let .paragraph(_, initialSecond) = initialRenderable.renderables[1],
          case let .paragraph(_, updatedSecond) = updatedRenderable.renderables[1] else {
      return XCTFail("Expected two paragraphs")
    }

    XCTAssertTrue(initialFirst === updatedFirst)
    XCTAssertFalse(initialSecond === updatedSecond)
    XCTAssertEqual(updatedSecond.string, "Second paragraph grows.")
  }

  func testReusingUnchangedPrefixKeepsFreshContentWhenSingleParagraphGrows() async throws {
    let initial = await parser.parse(text: "A single growing paragraph")
    let updated = await parser.parse(text: "A single growing paragraph gains more text.")
    let initialRenderable = await RenderableDocument(document: initial, config: .default)
    let updatedRenderable = await RenderableDocument(document: updated, config: .default)
      .reusingUnchangedPrefix(from: initialRenderable)

    guard case let .paragraph(_, initialOnly) = initialRenderable.renderables[0],
          case let .paragraph(_, updatedOnly) = updatedRenderable.renderables[0] else {
      return XCTFail("Expected single paragraphs")
    }

    XCTAssertFalse(initialOnly === updatedOnly)
    XCTAssertEqual(updatedOnly.string, "A single growing paragraph gains more text.")
  }

  /// 布局级判别实验：占位与解析结果的实测高度差是"历史行首次实例化时整章位移"
  /// 的直接来源。拆段占位必须显著比单段占位更贴近解析后的真实高度。
  /// 用纯散文种子隔离"空行按整行渲染 vs blockSpacing 分段"这一项，
  /// 比值断言保持机器无关。
  @MainActor
  func testSplitPlaceholderTracksParsedLayoutHeightFarCloserThanLegacyPlaceholder() async throws {
    let paragraphs = (0..<24).map { index in
      "第\(index)段：主角沿着长街走了很久，霓虹在雨水里化开，他想起许多旧事，却没有停下脚步，也没有回头。"
    }
    let text = paragraphs.joined(separator: "\n\n")
    // 生产等价参数：默认 blockSpacing(30) 恰与空行渲染高度相消，测不出差距；
    // AmberAgent 生产用 blockSpacing 8 + 行距 4，占位偏高在此参数下才会显现。
    let config = MarkdownRenderConfig.default
      .withBlockSpacing(value: 8)
      .withParagraphLineSpacing(value: 4)
      .withCollapsesSoftBreaks(value: true)
    let parsedDocument = await parser.parse(text: text)
    let parsed = await RenderableDocument(document: parsedDocument, config: config)
    let legacy = RenderableDocument(plainText: text, id: "0", config: config)
    let split = RenderableDocument(
      plainText: text,
      id: "0",
      config: config,
      splittingParagraphsOnBlankLines: true
    )

    func measuredHeight(_ document: RenderableDocument) -> CGFloat {
      let host = UIHostingController(rootView: DocumentView(
        renderableDocument: document,
        config: config,
        animateInitialText: false,
        usesTextKit1ForAttachmentFreeText: true
      ))
      return host.sizeThatFits(
        in: CGSize(width: 360, height: CGFloat.greatestFiniteMagnitude)
      ).height
    }

    let parsedHeight = measuredHeight(parsed)
    let legacyHeight = measuredHeight(legacy)
    let splitHeight = measuredHeight(split)
    let legacyDelta = abs(legacyHeight - parsedHeight)
    let splitDelta = abs(splitHeight - parsedHeight)
    print(
      "[placeholder-metrics] parsed=\(parsedHeight) legacy=\(legacyHeight) " +
        "split=\(splitHeight) legacyDelta=\(legacyDelta) splitDelta=\(splitDelta)"
    )

    XCTAssertGreaterThan(parsedHeight, 0)
    XCTAssertGreaterThan(
      legacyDelta, 100,
      "单段占位与解析高度本应有显著差距；若此处失败说明实验前提已变化，需要重新评估占位策略。"
    )
    XCTAssertLessThan(
      splitDelta * 4, legacyDelta,
      "拆段占位的高度误差必须至少比单段占位小 4 倍，否则冷行实例化仍会产生可见位移。"
    )
  }

  func testPlainTextPlaceholderSplitsParagraphsOnBlankLinesOnlyWhenOptedIn() {
    let text = "第一段\n\n第二段\n\n\n第三段"

    let legacy = RenderableDocument(plainText: text, id: "0", config: .default)
    XCTAssertEqual(legacy.renderables.count, 1)
    guard case let .paragraph(legacyID, legacyContent) = legacy.renderables[0] else {
      return XCTFail("Expected a single paragraph by default")
    }
    XCTAssertEqual(legacyID, "0")
    XCTAssertEqual(legacyContent.string, text)

    let split = RenderableDocument(
      plainText: text,
      id: "0",
      config: .default,
      splittingParagraphsOnBlankLines: true
    )
    XCTAssertEqual(split.renderables.count, 3)
    // Vendored fix (AmberAgent): chunk ids are the bare chunk index, not
    // "\(id)-\(index)" — see the id-generation comment in
    // RenderableDocument.swift for why (placeholder chunk ids must coincide
    // with the parsed top-level `Markup.id` namespace, which has no `id`
    // prefix, so a first-parse handoff updates paragraphs in place instead of
    // replacing every one of them).
    let expected = [("0", "第一段"), ("1", "第二段"), ("2", "第三段")]
    for (index, expectation) in expected.enumerated() {
      guard case let .paragraph(id, content) = split.renderables[index] else {
        return XCTFail("Expected paragraph chunk at index \(index)")
      }
      XCTAssertEqual(id, expectation.0)
      XCTAssertEqual(content.string, expectation.1)
    }
  }

  func testPlainTextPlaceholderSplitsOnCRLFAndWhitespaceOnlyBlankLines() {
    // cmark 把 CRLF 空行与仅含空格/Tab 的空行都视为段落分隔；
    // 占位拆段必须覆盖同样的空行语义，否则这些输入仍退回单段占位。
    let crlf = RenderableDocument(
      plainText: "第一段\r\n\r\n第二段",
      id: "0",
      config: .default,
      splittingParagraphsOnBlankLines: true
    )
    XCTAssertEqual(crlf.renderables.count, 2)

    let whitespaceBlank = RenderableDocument(
      plainText: "第一段\n \t \n第二段",
      id: "0",
      config: .default,
      splittingParagraphsOnBlankLines: true
    )
    XCTAssertEqual(whitespaceBlank.renderables.count, 2)
    guard whitespaceBlank.renderables.count == 2,
          case let .paragraph(_, first) = whitespaceBlank.renderables[0],
          case let .paragraph(_, second) = whitespaceBlank.renderables[1] else {
      return XCTFail("Expected two paragraph chunks")
    }
    XCTAssertEqual(first.string, "第一段")
    XCTAssertEqual(second.string, "第二段")
  }

}
