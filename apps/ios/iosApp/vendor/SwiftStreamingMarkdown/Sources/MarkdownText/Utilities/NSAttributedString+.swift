//
//  Copyright (c) Microsoft Corporation. All rights reserved.
//  Licensed under the MIT License. See LICENSE in the project root for license information.
//

import Foundation
import UIKit

extension NSAttributedString.Key {
  /// Attributes TextKit reads with `integerValue` while drawing glyphs.
  fileprivate static let textKitIntegerKeys: Set<NSAttributedString.Key> = [
    .underlineStyle,
    .strikethroughStyle,
    .ligature,
    .verticalGlyphForm
  ]

  /// Attributes TextKit reads with `floatValue` while drawing glyphs.
  fileprivate static let textKitFloatKeys: Set<NSAttributedString.Key> = [
    .kern,
    .baselineOffset,
    .strokeWidth,
    .obliqueness,
    .expansion
  ]
}

extension Dictionary where Key == NSAttributedString.Key, Value == Any {
  /// Coerce TextKit numeric attributes to NSNumber. Swift arrays, OptionSets,
  /// and foreign boxed objects crash `NSLayoutManager` with `integerValue`.
  func sanitizedForTextKit() -> [NSAttributedString.Key: Any] {
    var result = self
    for key in NSAttributedString.Key.textKitIntegerKeys {
      guard let value = result[key] else { continue }
      result[key] = NSAttributedString.textKitIntegerNumber(from: value)
    }
    for key in NSAttributedString.Key.textKitFloatKeys {
      guard let value = result[key] else { continue }
      result[key] = NSAttributedString.textKitFloatNumber(from: value)
    }
    return result
  }
}

extension NSMutableAttributedString {
  func sanitizeNumericTextKitAttributes() {
    guard length > 0 else { return }
    enumerateAttributes(in: NSRange(location: 0, length: length), options: []) { attributes, range, _ in
      for key in NSAttributedString.Key.textKitIntegerKeys {
        guard let value = attributes[key], !(value is NSNumber) else { continue }
        addAttribute(key, value: NSAttributedString.textKitIntegerNumber(from: value), range: range)
      }
      for key in NSAttributedString.Key.textKitFloatKeys {
        guard let value = attributes[key], !(value is NSNumber) else { continue }
        addAttribute(key, value: NSAttributedString.textKitFloatNumber(from: value), range: range)
      }
    }
  }
}

extension NSAttributedString {
  fileprivate static func textKitIntegerNumber(from value: Any) -> NSNumber {
    if let number = value as? NSNumber { return number }
    if let int = value as? Int { return NSNumber(value: int) }
    if let int32 = value as? Int32 { return NSNumber(value: int32) }
    if let style = value as? NSUnderlineStyle { return NSNumber(value: style.rawValue) }
    return NSNumber(value: 0)
  }

  fileprivate static func textKitFloatNumber(from value: Any) -> NSNumber {
    if let number = value as? NSNumber { return number }
    if let cgFloat = value as? CGFloat { return NSNumber(value: Double(cgFloat)) }
    if let double = value as? Double { return NSNumber(value: double) }
    if let float = value as? Float { return NSNumber(value: float) }
    if let int = value as? Int { return NSNumber(value: int) }
    return NSNumber(value: 0)
  }

  func splitIntoWords(withIn range: NSRange) -> [NSRange] {
    var words: [NSRange] = []
    let string = self.string as NSString

    guard range.location != NSNotFound,
          range.location >= 0,
          NSMaxRange(range) <= string.length else {
      return words
    }

    string.enumerateSubstrings(
      in: range,
      options: [.byWords, .localized, .substringNotRequired]
    ) { (_, substringRange, _, _) in

      // Add any separator/whitespace before this word
      if let lastWord = words.last {
        let gapStart = NSMaxRange(lastWord)
        let gapLength = substringRange.location - gapStart

        if gapLength > 0 {
          let gapRange = NSRange(location: gapStart, length: gapLength)
          words.append(gapRange)
        }
      } else {
        // Handle any leading separators/whitespace
        let leadingGapLength = substringRange.location - range.location
        if leadingGapLength > 0 {
          let leadingGapRange = NSRange(location: range.location, length: leadingGapLength)
          words.append(leadingGapRange)
        }
      }

      // Add the word range
      words.append(substringRange)
    }

    // Handle any trailing separators/whitespace
    if let lastWord = words.last {
      let trailingStart = NSMaxRange(lastWord)
      let trailingLength = NSMaxRange(range) - trailingStart

      if trailingLength > 0 {
        let trailingRange = NSRange(location: trailingStart, length: trailingLength)
        words.append(trailingRange)
      }
    } else {
      // If no words were found, return entire range
      if range.length > 0 {
        words.append(range)
      }
    }

    return words
  }
}
