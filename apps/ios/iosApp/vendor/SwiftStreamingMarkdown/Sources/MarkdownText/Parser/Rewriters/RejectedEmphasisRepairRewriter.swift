//
//  Copyright (c) Microsoft Corporation. All rights reserved.
//  Licensed under the MIT License. See LICENSE in the project root for license information.
//

import Foundation
import Markdown

/// Repairs strong emphasis that CommonMark flanking rules reject and therefore
/// leave behind as literal delimiter text.
///
/// cmark-gfm refuses to open/close `**`/`__` in several CJK-heavy shapes the
/// model output regularly contains, e.g.:
///
/// - `**（重点）**说明` — the opening run is followed by opening punctuation
///   and preceded by a word character, so it is neither cleanly left-flanking.
/// - `这是__重点__内容` — underscore runs between CJK characters are both
///   left- and right-flanking, and underscores additionally carry the
///   intraword restriction.
///
/// When emphasis parsing fails the delimiters survive inside `Text` nodes as
/// literal characters, so this rewriter is self-limiting: any `**…**`/`__…__`
/// span still present in a `Text` node is by definition something the parser
/// rejected. Escaped `\*\*` sequences and code spans never reach here as
/// complete delimiter pairs inside a single `Text` node, so they are not
/// touched. This mirrors the paired repair in AmberAgent's native
/// (pulldown-cmark) renderer — keep the delimiter patterns in sync with
/// `AmberMarkdownView.renderRejectedStrong`.
final class RejectedEmphasisRepairRewriter: MarkupRewriter {

  // Swift Regex does not support lookbehind, so "content must not end with
  // whitespace" is folded into the capture: the content's final character is
  // matched explicitly as non-whitespace (and non-delimiter). The leading
  // `(?!\s)` lookahead keeps the opening side symmetric.
  // AnyRegexOutput: a string-compiled regex with capture groups materializes
  // as Regex<(Substring, Substring)>; naming it Regex<Substring> makes the
  // runtime cast fail and silently nils the pattern.
  static let starStrong: Regex<AnyRegexOutput>? = {
    try? Regex("\\*\\*(?!\\s)([^*]*?[^\\s*])\\*\\*")
  }()

  static let underscoreStrong: Regex<AnyRegexOutput>? = {
    try? Regex("__(?!\\s)([^_]*?[^\\s_])__")
  }()

  func visitParagraph(_ paragraph: Paragraph) -> Markup? {
    repairIfNeeded(inlineContainer: paragraph) ?? paragraph
  }

  func visitHeading(_ heading: Heading) -> Markup? {
    repairIfNeeded(inlineContainer: heading) ?? heading
  }

  func visitTableCell(_ tableCell: Table.Cell) -> Markup? {
    repairIfNeeded(inlineContainer: tableCell) ?? tableCell
  }

  private func repairIfNeeded(inlineContainer: InlineContainer) -> InlineContainer? {
    guard inlineContainer.hasChildren else { return nil }

    var repairedChildren: [InlineMarkup] = []
    var changed = false
    for child in inlineContainer.children {
      if let text = child as? Text, let segments = Self.splitRejectedStrong(text.string) {
        changed = true
        for segment in segments {
          switch segment {
          case .plain(let value):
            repairedChildren.append(Text(value))
          case .strong(let value):
            repairedChildren.append(Strong([Text(value)]))
          }
        }
      } else if let convertible = child as? InlineMarkup {
        repairedChildren.append(convertible)
      } else {
        return nil
      }
    }
    guard changed else { return nil }

    var mutableContainer = inlineContainer
    mutableContainer.replaceChildrenInRange(0..<inlineContainer.childCount, with: repairedChildren)
    return mutableContainer
  }

  enum Segment {
    case plain(String)
    case strong(String)
  }

  /// Splits `text` into plain/strong segments when it contains a rejected
  /// strong span; returns nil when nothing needs repair.
  static func splitRejectedStrong(_ text: String) -> [Segment]? {
    guard let starStrong, let underscoreStrong else { return nil }

    var ranges: [Range<String.Index>] = []
    for match in text.matches(of: starStrong) { ranges.append(match.range) }
    for match in text.matches(of: underscoreStrong) { ranges.append(match.range) }
    guard !ranges.isEmpty else { return nil }
    ranges.sort { $0.lowerBound < $1.lowerBound }

    // Drop overlapping matches (keep the earliest) rather than guessing intent.
    var kept: [Range<String.Index>] = []
    for range in ranges {
      if let last = kept.last, range.lowerBound < last.upperBound { continue }
      kept.append(range)
    }

    var segments: [Segment] = []
    var cursor = text.startIndex
    for range in kept {
      if cursor < range.lowerBound {
        segments.append(.plain(String(text[cursor..<range.lowerBound])))
      }
      let inner = text[range]
      let trimmed = inner.dropFirst(2).dropLast(2)
      segments.append(.strong(String(trimmed)))
      cursor = range.upperBound
    }
    if cursor < text.endIndex {
      segments.append(.plain(String(text[cursor...])))
    }
    return segments
  }
}
