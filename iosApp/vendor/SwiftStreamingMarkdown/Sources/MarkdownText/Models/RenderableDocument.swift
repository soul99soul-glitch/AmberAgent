//
//  Copyright (c) Microsoft Corporation. All rights reserved.
//  Licensed under the MIT License. See LICENSE in the project root for license information.
//

import Foundation
import Markdown
import SwiftUI

/// A `MarkdownRenderConfig`-aware snapshot of a parsed markdown `Document`,
/// ready to be handed to a `MarkdownView` for rendering. Producing one is
/// the heavyweight step; rendering it is cheap.
public struct RenderableDocument: Equatable, Sendable {
  let renderables: [MarkdownRenderable]

  var containsCodeBlock: Bool {
    return renderables.contains(where: { $0.isCodeBlock })
  }

  var containsBlockQuote: Bool {
    return renderables.contains(where: { $0.isBlockQuote })
  }

  var isEmpty: Bool {
    return renderables.isEmpty
  }

  /// Convert a parsed `Document` into a `RenderableDocument` using the supplied config.
  /// - Parameters:
  ///   - document: The parsed markdown tree.
  ///   - config: Styling and behavior used during conversion.
  public init(document: Document, config: MarkdownRenderConfig) async {
    self.renderables = document.convert(with: config)
  }

  /// Construct a renderable wrapping a single plain-text paragraph styled
  /// with `config.paragraphStyle`. Useful for showing non-markdown text in a
  /// `MarkdownView` without round-tripping through the parser.
  public init(plainText: String, config: MarkdownRenderConfig) {
    self.init(plainText: plainText, id: UUID().uuidString, config: config)
  }

  /// Construct a single plain-text paragraph with a caller-owned stable id.
  /// Streaming callers should keep this id stable across text updates so SwiftUI
  /// updates the existing paragraph view instead of recreating it from alpha 0.
  ///
  /// `splittingParagraphsOnBlankLines` opts a placeholder document into one
  /// paragraph renderable per blank-line-separated chunk, so its measured height
  /// tracks the parsed document's blockSpacing-based layout instead of rendering
  /// every blank line as a full text line. Default keeps the single-paragraph
  /// behavior unchanged. Chunk ids derive from `id` plus the chunk index, which
  /// stays stable for append-only text updates.
  public init(
    plainText: String,
    id: String,
    config: MarkdownRenderConfig,
    splittingParagraphsOnBlankLines: Bool = false
  ) {
    var attributes: [NSAttributedString.Key: Any] = [
      .font: config.paragraphStyle.textFonts.normal,
      .foregroundColor: config.paragraphStyle.textColor
    ]
    if let kern = config.paragraphStyle.textFonts.preferredLetterSpacing {
      attributes[.kern] = kern
    }
    if splittingParagraphsOnBlankLines {
      // Match cmark's blank-line semantics: CRLF newlines and lines containing
      // only spaces/tabs also separate paragraphs. Single pass over lines.
      var chunks: [String] = []
      var currentLines: [String] = []
      let normalized = plainText.replacingOccurrences(of: "\r\n", with: "\n")
      for line in normalized.components(separatedBy: "\n") {
        if line.trimmingCharacters(in: .whitespaces).isEmpty {
          if !currentLines.isEmpty {
            chunks.append(currentLines.joined(separator: "\n"))
            currentLines = []
          }
        } else {
          currentLines.append(line)
        }
      }
      if !currentLines.isEmpty {
        chunks.append(currentLines.joined(separator: "\n"))
      }
      if chunks.count > 1 {
        self.init(renderables: chunks.enumerated().map { index, chunk in
          .paragraph(id: "\(id)-\(index)", content: NSMutableAttributedString(string: chunk, attributes: attributes))
        })
        return
      }
    }
    let content = NSMutableAttributedString(string: plainText, attributes: attributes)
    self.init(renderables: [.paragraph(id: id, content: content)])
  }

  init(renderables: [MarkdownRenderable]) {
    self.renderables = renderables
  }

  /// An empty document, equivalent to `RenderableDocument(plainText: "", …)`
  /// but allocation-free.
  public static let empty = RenderableDocument(renderables: [])

  /// Preserves object identity for the unchanged leading renderables of an
  /// append-only document. Parsing and conversion still produce the complete
  /// document; this only keeps stable SwiftUI/TextKit subtrees from receiving
  /// freshly allocated attributed strings on every streamed suffix.
  public func reusingUnchangedPrefix(from previous: RenderableDocument) -> RenderableDocument {
    var merged = renderables
    let sharedCount = min(merged.count, previous.renderables.count)
    var index = 0
    while index < sharedCount,
          merged[index].id == previous.renderables[index].id,
          merged[index] == previous.renderables[index] {
      merged[index] = previous.renderables[index]
      index += 1
    }
    if index == merged.count, merged.count == previous.renderables.count {
      return previous
    }
    return RenderableDocument(renderables: merged)
  }

}

extension RenderableDocument {
  var attributedStrings: [NSAttributedString] {
    return renderables.flatMap { $0.extractAttributedStrings() }
  }
}

extension MarkdownRenderable {
  func extractAttributedStrings() -> [NSAttributedString] {
    switch self {
    case .paragraph(_, let str):
      return [str]
    case .orderedList(_, let items):
      return items.flatMap { $0.attributedStrings() }
    case .unorderedList(_, let items, _):
      return items.flatMap { $0.attributedStrings() }
    case .table(_, let headers, let rows, _):
      return headers.map(NSAttributedString.init) + rows.flatMap { $0.map(\.attributedString) }
    default:
      return []
    }
  }
}

extension MarkdownListItem {
  func attributedStrings() -> [NSAttributedString] {
    return self.children.flatMap { $0.extractAttributedStrings() }
  }
}
