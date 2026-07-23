//
//  Copyright (c) Microsoft Corporation. All rights reserved.
//  Licensed under the MIT License. See LICENSE in the project root for license information.
//

import Foundation
@testable import SwiftStreamingMarkdown
import Testing
import UIKit

/// Regression coverage for the word-fade animation bookkeeping in
/// `ParagraphUIView.setParagraphContents`. A full (non-append) replacement must
/// invalidate fades whose ranges address the previous text; the TextKit 1
/// append fast path must keep in-flight fades alive.
@MainActor
@Suite("ParagraphUIView Fade Animation Tests")
struct ParagraphUIViewFadeTests {

  @Test("Full content replacement drops stale word fades")
  func fullReplacementDropsStaleWordFades() {
    // A default UITextView uses TextKit 2, so every publish takes the
    // full-replacement path (the TextKit 1 append fast path is unavailable).
    let view = ParagraphUIView(frame: .zero)

    view.setParagraphContents(NSMutableAttributedString(string: "hello world"), animatedByWord: true)
    #expect(!view.activeAnimations.isEmpty, "Word fades should start for the initial contents")

    let oldLength = view.attributedText.length
    // Not a prefix extension of "hello world", so this replaces the whole text.
    view.setParagraphContents(NSMutableAttributedString(string: "hello brave new world"), animatedByWord: true)

    #expect(!view.activeAnimations.isEmpty, "The newly appended tail should still fade in")
    #expect(
      view.activeAnimations.allSatisfy { $0.range.location >= oldLength },
      "Fades from the replaced contents must not survive a full replacement"
    )
  }

  @Test("TextKit 1 append keeps in-flight fades running")
  func textKit1AppendKeepsInFlightFades() {
    let view = ParagraphUIView.makeTextKit1View()

    view.setParagraphContents(NSMutableAttributedString(string: "hello world"), animatedByWord: true)
    let initialFadeCount = view.activeAnimations.count
    #expect(initialFadeCount > 0, "Word fades should start for the initial contents")

    // A pure prefix extension takes the append fast path.
    view.setParagraphContents(NSMutableAttributedString(string: "hello world again"), animatedByWord: true)

    #expect(
      view.activeAnimations.count > initialFadeCount,
      "Appending must add fades for the new words without dropping in-flight ones"
    )
  }
}
