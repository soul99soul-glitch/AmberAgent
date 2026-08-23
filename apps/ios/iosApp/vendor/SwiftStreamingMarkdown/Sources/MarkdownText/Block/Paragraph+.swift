//
//  Copyright (c) Microsoft Corporation. All rights reserved.
//  Licensed under the MIT License. See LICENSE in the project root for license information.
//

import Foundation
import Markdown
import SwiftUI

extension Paragraph: BlockConvertible {

  func convert(attributeContainer: NSAttributeContainer, config: MarkdownRenderConfig) -> MarkdownRenderable {
    var container = attributeContainer
    container[.font] = config.paragraphStyle.textFonts.normal
    container[.typography] = config.paragraphStyle.textFonts
    if let kern = config.paragraphStyle.textFonts.preferredLetterSpacing {
      container[.kern] = kern
    }
    container[.foregroundColor] = config.paragraphStyle.textColor
    let paragraphContent: NSMutableAttributedString = self.buildParagraphContent(container: container, config: config)
    return MarkdownRenderable.paragraph(id: self.id, content: paragraphContent)
  }
}

extension BlockMarkup {

  func buildParagraphContent(container: NSAttributeContainer, config: MarkdownRenderConfig) -> NSMutableAttributedString {
    let result = NSMutableAttributedString()

    for child in self.children {
      guard let convertible = child as? InlineConvertible else {
        continue
      }

      let coder = config.citationConfig.coder
      if config.citationConfig.isEnabled,
         let link = child as? Markdown.Link,
         let destination = link.destination,
         link.isInlineCitation(coder: coder) {

        // Create citation attachment directly during parsing (as suggested by @hanzhouli_microsoft)
        let attachmentData = coder.decode(linkDestination: destination)
        if let attachmentData = attachmentData,
           let attachment = InlineCitationAttachment(citationData: attachmentData, citationConfig: config.citationConfig) {
          let attachmentString = NSMutableAttributedString(attachment: attachment)

          // Add link attribute for accessibility activation (space key)
          let url = attachmentData.url
          attachmentString.addAttribute(
            .link,
            value: url,
            range: NSRange(location: 0, length: attachmentString.length)
          )

          // Apply baseline offset to the attachment using the font from config
          attachmentString.addAttribute(
            .baselineOffset,
            value: config.paragraphStyle.textFonts.normal.descender,
            range: NSRange(location: 0, length: attachmentString.length)
          )

          // Add the citation directly to result
          result.append(attachmentString)
        }
      } else if config.collapsesSoftBreaks, child is Markdown.SoftBreak {
        // CommonMark semantics: a soft break renders as a space. For CJK text a
        // mid-sentence space reads wrong, so append nothing when the preceding
        // character is CJK (mirrors the host app's synchronous renderer).
        if let last = result.string.last, !last.isWhitespace, !last.isCJKCharacter {
          result.append(NSMutableAttributedString(string: " ").mergingAttributes(container))
        }
      } else {
        let stringPart = convertible.convert(attributeContainer: container, config: config)
        result.append(stringPart)
      }
    }

    return result
  }
}

extension Character {
  /// Whether the character belongs to a CJK script (or CJK/fullwidth
  /// punctuation); a collapsed soft break between such characters needs no
  /// joining space.
  fileprivate var isCJKCharacter: Bool {
    for scalar in unicodeScalars {
      switch scalar.value {
      case 0x3000...0x303F,   // CJK symbols & punctuation
           0x3040...0x30FF,   // Hiragana + Katakana
           0x3400...0x4DBF,   // CJK Unified Ideographs Ext A
           0x4E00...0x9FFF,   // CJK Unified Ideographs
           0xF900...0xFAFF,   // CJK Compatibility Ideographs
           0xFF00...0xFFEF:   // Halfwidth & Fullwidth forms
        return true
      default:
        continue
      }
    }
    return false
  }
}
