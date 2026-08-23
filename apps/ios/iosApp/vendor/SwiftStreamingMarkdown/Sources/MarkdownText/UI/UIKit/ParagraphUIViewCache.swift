//
//  Copyright (c) Microsoft Corporation. All rights reserved.
//  Licensed under the MIT License. See LICENSE in the project root for license information.
//

import UIKit

class ParagraphUIViewCache {
  @WithLock
  private var cachedViews: [ParagraphUIView] = []
  private let maxCacheSize = 50 // Limit cache size
  private init() {}

  static let shared: ParagraphUIViewCache = .init()

  func createOrReuseParagraphUIView(
    contents: NSMutableAttributedString,
    lineSpacing: CGFloat?,
    usesTextKit1: Bool = false
  ) -> ParagraphUIView {
    if let availableView = takeAvailableCachedView(usesTextKit1: usesTextKit1) {
      // Vendored fix (AmberAgent): a reused view may carry a TextKit2 text container
      // sized for a previous, wider context (e.g. an unconstrained table cell).
      // Without a reset the stale layout fragments render wider than the new bounds.
      availableView.resetForReuse()
      return availableView
    }

    return usesTextKit1 ? ParagraphUIView.makeTextKit1View() : ParagraphUIView()
  }

  func recycle(_ view: ParagraphUIView) {
    // A view becomes cache-owned only after SwiftUI dismantles its representable.
    // Checked-out views are removed from this array, so a detached-but-still-owned
    // view can never be handed to a second paragraph.
    $cachedViews.mutate { cachedViews in
      guard cachedViews.count < maxCacheSize,
            !cachedViews.contains(where: { $0 === view }) else { return }
      cachedViews.append(view)
    }
  }

  private func takeAvailableCachedView(usesTextKit1: Bool) -> ParagraphUIView? {
    $cachedViews.mutate { cachedViews in
      guard let index = cachedViews.firstIndex(where: { view in
        view.superview == nil && view.window == nil && view.usesTextKit1 == usesTextKit1
      }) else {
        return nil
      }
      return cachedViews.remove(at: index)
    }
  }
}
