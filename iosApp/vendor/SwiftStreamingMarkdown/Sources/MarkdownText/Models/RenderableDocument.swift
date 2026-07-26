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
    let converted = RenderableDocument(renderables: document.convert(with: config))
    self = config.coalescesAdjacentTextBlocks
      ? converted.coalescingAdjacentTextBlocks(config: config)
      : converted
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
  /// behavior unchanged. Chunk ids are the chunk's own index (see the
  /// `Vendored fix (AmberAgent)` note below for why `id` isn't part of them),
  /// which stays stable for append-only text updates.
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
        // Vendored fix (AmberAgent): use the bare chunk index as the paragraph
        // id instead of "\(id)-\(index)". A parsed top-level `Markdown.Paragraph`
        // gets its id from `Markup.id` (Markup+ID.swift), which is an
        // indexInParent path with no separator for top-level document
        // children — i.e. plain "0", "1", "2", … For any caller-supplied `id`
        // (this app always passes "0"), the dashed placeholder ids ("0-0",
        // "0-1", …) can never intersect that namespace, so the moment the
        // async parse for the same text lands, `BlockView`'s
        // `ForEach(renderables)` (keyed on this `id`) sees every chunk as a
        // brand-new identity and tears down/rebuilds the whole already-settled
        // prefix instead of updating it in place. Dropping the `id` prefix
        // makes placeholder chunk ids coincide with the real parsed ids for
        // the common flat-paragraph case, so the handoff is a content update,
        // not an identity change. This only affects the
        // `splittingParagraphsOnBlankLines` path (opt-in, AmberAgent's only
        // caller); the default single-paragraph behavior below is unchanged.
        self = Self.finalizing(
          chunks.enumerated().map { index, chunk in
            .paragraph(id: "\(index)", content: NSMutableAttributedString(string: chunk, attributes: attributes))
          },
          config: config
        )
        return
      }
    }
    let content = NSMutableAttributedString(string: plainText, attributes: attributes)
    self = Self.finalizing([.paragraph(id: id, content: content)], config: config)
  }

  /// Vendored addition (AmberAgent): placeholder documents must go through the
  /// same coalescing as parsed ones, or the placeholder→parsed handoff would flip
  /// `.paragraph` to `.coalescedText` and re-fade already-displayed text.
  private static func finalizing(
    _ renderables: [MarkdownRenderable],
    config: MarkdownRenderConfig
  ) -> RenderableDocument {
    let document = RenderableDocument(renderables: renderables)
    guard config.coalescesAdjacentTextBlocks else { return document }
    return document.coalescingAdjacentTextBlocks(config: config)
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
    case .coalescedText(_, let str):
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
