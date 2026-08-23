//
//  Vendored addition (AmberAgent). Not part of upstream SwiftStreamingMarkdown.
//

import Foundation
import UIKit

extension RenderableDocument {

  /// Merges every maximal run of adjacent attachment-free `.paragraph`
  /// renderables into a single `.coalescedText` renderable.
  ///
  /// Upstream renders one `ParagraphView` (its own `UITextView` + TextKit stack)
  /// per markdown block, so a 60-paragraph answer costs 60 text views inside one
  /// message. Every streamed publish then walks all 60 SwiftUI subtrees even
  /// though only the last one changed, and each newly opened paragraph mounts a
  /// brand-new view that fades in from alpha 0. Merging the run collapses that to
  /// one text storage whose growth is append-only, which is what keeps
  /// `ParagraphUIView`'s append fast path (and its per-word fade of just the
  /// appended range) live *across* paragraph boundaries.
  ///
  /// Why the merge stays append-only, and therefore keeps the fast path:
  /// - Paragraph `i`'s characters and attributes are written once and never
  ///   revisited. Block spacing is expressed as `paragraphSpacingBefore` on
  ///   paragraph `i`, never as `paragraphSpacing` on paragraph `i - 1`, so
  ///   opening a new paragraph does not retroactively restyle the previous one.
  /// - Line spacing is baked into the same `NSParagraphStyle` here rather than
  ///   applied by the view, so callers pass `lineSpacing: nil` and
  ///   `setParagraphContents` neither re-applies a style over the appended
  ///   suffix nor trips its `self.lineSpacing == lineSpacing` guard.
  ///
  /// A run of length 1 is coalesced too. That looks redundant, but it is what
  /// keeps the case (and therefore the SwiftUI view identity) stable as a
  /// streaming message grows from one paragraph to two — otherwise the first
  /// paragraph would switch from `.paragraph` to `.coalescedText` mid-stream and
  /// re-fade from alpha 0.
  ///
  /// The merged block is identified by its *run ordinal*, not by the id of its
  /// first member. A placeholder document does not parse markdown, so it turns
  /// the whole reply into one run whose first member is chunk `0`; the parsed
  /// document may open with a heading, putting the same prose in a run whose
  /// first member is chunk `1`. Keying on the first member's id would hand the
  /// prose a brand-new SwiftUI identity at the placeholder→parsed handoff and
  /// re-fade the entire body. Run ordinals are stable under both that handoff
  /// and speculative rewrites that retype a leading block.
  ///
  /// Non-paragraph blocks (headings, lists, tables, code, math, quotes, rules)
  /// terminate the run and pass through untouched, as do paragraphs containing an
  /// attachment — those need TextKit 2 and are excluded so the merged block stays
  /// TextKit 1 eligible.
  func coalescingAdjacentTextBlocks(config: MarkdownRenderConfig) -> RenderableDocument {
    guard renderables.contains(where: { $0.isCoalescableParagraph }) else { return self }

    var output: [MarkdownRenderable] = []
    output.reserveCapacity(renderables.count)
    var run: [NSMutableAttributedString] = []
    var runOrdinal = 0

    func flushRun() {
      guard !run.isEmpty else { return }
      output.append(
        .coalescedText(
          id: "coalesced-\(runOrdinal)",
          content: Self.mergeParagraphContents(run, config: config)
        )
      )
      runOrdinal += 1
      run.removeAll(keepingCapacity: true)
    }

    for renderable in renderables {
      if case .paragraph(_, let content) = renderable, renderable.isCoalescableParagraph {
        run.append(content)
      } else {
        flushRun()
        output.append(renderable)
      }
    }
    flushRun()

    return RenderableDocument(renderables: output)
  }

  private static func mergeParagraphContents(
    _ contents: [NSMutableAttributedString],
    config: MarkdownRenderConfig
  ) -> NSMutableAttributedString {
    let merged = NSMutableAttributedString()
    for (index, content) in contents.enumerated() {
      let start = merged.length
      merged.append(content)
      if index < contents.count - 1 {
        // The paragraph separator must carry the preceding paragraph's own
        // attributes: an unattributed newline would fall back to the system
        // default font and inflate that paragraph's last line height.
        let trailingAttributes = content.length > 0
          ? content.attributes(at: content.length - 1, effectiveRange: nil)
          : [:]
        merged.append(NSAttributedString(string: "\n", attributes: trailingAttributes))
      }
      // Mirrors `ParagraphUIView.setLineSpacing`.
      let body = NSMutableParagraphStyle()
      body.lineSpacing = config.paragraphLineSpacing
      body.alignment = .left
      merged.addAttribute(
        .paragraphStyle,
        value: body,
        range: NSRange(location: start, length: merged.length - start)
      )
      guard index > 0 else { continue }
      // TextKit treats *every* `\n` as a paragraph boundary, and a markdown
      // paragraph may contain its own (soft or hard) line breaks. So the block
      // gap must be attached only to this paragraph's first TextKit paragraph —
      // painting it over the whole range would repeat the gap at each inner
      // break, and would make a paragraph's inner line spacing depend on
      // whether anything precedes it in the run.
      let lead = NSMutableParagraphStyle()
      lead.lineSpacing = config.paragraphLineSpacing
      lead.alignment = .left
      lead.paragraphSpacingBefore = config.blockSpacing
      let firstBreak = (content.string as NSString).range(of: "\n")
      let leadLength = firstBreak.location == NSNotFound
        ? content.length
        : firstBreak.location + firstBreak.length
      merged.addAttribute(
        .paragraphStyle,
        value: lead,
        range: NSRange(location: start, length: leadLength)
      )
    }
    return merged
  }
}

private extension MarkdownRenderable {
  var isCoalescableParagraph: Bool {
    guard case .paragraph(_, let content) = self else { return false }
    var containsAttachment = false
    content.enumerateAttribute(
      .attachment,
      in: NSRange(location: 0, length: content.length)
    ) { value, _, stop in
      guard value != nil else { return }
      containsAttachment = true
      stop.pointee = true
    }
    return !containsAttachment
  }
}
