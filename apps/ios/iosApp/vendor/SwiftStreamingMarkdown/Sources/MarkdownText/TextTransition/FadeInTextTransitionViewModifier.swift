//
//  Copyright (c) Microsoft Corporation. All rights reserved.
//  Licensed under the MIT License. See LICENSE in the project root for license information.
//

import Foundation
import SwiftUI

struct FadeInTextTransitionViewModifier: ViewModifier {

  @State private var show = false
  /// Vendored fix (AmberAgent): tear down the transition scaffolding once the
  /// one-shot fade completes. A mounted transition keeps a
  /// `content.transaction { animation = ... }` rewrite plus a `TextRenderer`
  /// attached to the text forever, so every transaction flowing through the
  /// subtree re-resolves and re-draws the text (`TextRendererBox.draw` /
  /// `makeRBDisplayList` dominated long-table streaming profiles, 2026-07-10).
  /// After the fade the content is fully opaque and static — rendering it bare
  /// is visually identical and costs nothing on later updates.
  @State private var settled = false
  let config: FadeInTransitionConfig

  func body(content: Content) -> some View {
    if #available(iOS 18.0, *) {
      if settled {
        content
      } else {
        ZStack {
          if show {
            content
              .transition(config.asTransition)
          }
        }
        .onAppear {
          show = true
        }
        .task {
          // Generous margin past the fade's total duration so the teardown can
          // never clip the tail of the animation.
          let holdNanos = UInt64((config.totalDuration + 0.25) * 1_000_000_000)
          try? await Task.sleep(nanoseconds: holdNanos)
          settled = true
        }
      }
    } else {
      content
        .transition(.opacity)
    }
  }
}

extension View {
  func fadeInTextTransition(config: FadeInTransitionConfig = .fixedDuration(duration: 2.0, glyphDelay: 0.02, glyphDuration: 0.2)) -> some View {
    modifier(FadeInTextTransitionViewModifier(config: config))
  }
}

enum FadeInTransitionConfig {
  case fixedDuration(duration: TimeInterval, glyphDelay: TimeInterval, glyphDuration: TimeInterval)
  case variableDuration(glyphCount: Int, glyphDelay: TimeInterval, glyphDuration: TimeInterval)

  /// Vendored fix (AmberAgent): total wall time of the one-shot fade, used to
  /// schedule the post-completion scaffolding teardown.
  var totalDuration: TimeInterval {
    switch self {
    case .fixedDuration(let duration, _, _):
      return duration
    case .variableDuration(let glyphCount, let glyphDelay, let glyphDuration):
      return max(0, Double(glyphCount - 1) * glyphDelay) + glyphDuration
    }
  }

  @available(iOS 18.0, *)
  var asTransition: AnyTransition {
    switch self {
    case .fixedDuration(let duration, let glyphDelay, let glyphDuration):
      AnyTransition(FixedDurationFadeInTextTransition(duration: duration, glyphDelay: glyphDelay, glyphDuration: glyphDuration))
    case .variableDuration(let glyphCount, let glyphDelay, let glyphDuration):
      AnyTransition(VariableDurationFadeInTextTransition(totalGlyphs: glyphCount, glyphDelay: glyphDelay, glyphDuration: glyphDuration))
    }
  }
}

#if DEBUG

struct WrapperView: View {

  @State var text: String = "Welcome to Copilot!"
  @State var show: Bool = false

  var body: some View {
    VStack {
      if show {
        Text(text)
          .font(.largeTitle)
          .fadeInTextTransition()
      }
      Spacer()
    }
    .task {
      var count = 0
      while true {
        do {
          try await Task.sleep(ms: 4000)
        } catch {}
        show.toggle()
        count += 1
      }
    }
  }
}

#Preview("Text", body: {
  WrapperView()
})

#endif
