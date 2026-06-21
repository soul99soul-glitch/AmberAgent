# Amber Agent iOS ChatView handoff for Claude

Date: 2026-06-21
Workspace: `/Users/arquiel/Downloads/AI/amberagent-ios`

This handoff summarizes the current iOS ChatView UI state after a long UI iteration session. The user is frustrated, so the next pass should be careful, small, and verification-driven.

## User-facing goal

The user wants the iOS chat screen to feel stable and native while messages stream:

- When opening a conversation from the Session list, ChatView should automatically show the latest messages at the bottom.
- During streaming generation, the bottom of the actively growing assistant response should stay visually anchored just above the input field/composer area. It must not appear under the input field, jump between high and low positions, or jitter.
- While browsing history, messages should still scroll naturally behind/under the top and bottom glass areas. Do not solve the streaming anchor by shrinking/clipping the whole ScrollView viewport.
- The bottom boundary should have native iOS 26 Liquid Glass / blur behavior similar in spirit to the top bar, but the input field and composer buttons themselves must stay crisp. The blur should not wash out or blur the input controls.
- Tapping the input field must reveal the floating composer controls: model chip, thinking button, and context ring button.

## Current important files

- `iosApp/iosApp/ChatView.swift`
  - Main screen, message list, input bar, composer controls, streaming follow logic.
  - Current streaming follow state is around lines 25-29:
    - `followGeneration`
    - `pendingInitialScrollToBottom`
    - `followPaused`
    - `userDragging`
  - Message list and scroll logic are around lines 190-279.
  - Input bar and current misplaced bottom glass background are around lines 315-565.
  - `StreamingBottomBoundaryGlass` is around lines 687-712.
  - `ChatLayout` constants are around lines 1317-1324.
- `iosApp/iosApp/MessageBubbleView.swift`
  - Assistant Markdown body rendering.
  - Current Microsoft Markdown toggle wrapper is around lines 301-315.
- `iosApp/iosApp/DisplayFontSettingsView.swift`
  - Added display option: `使用微软流式 MD 渲染库`.
- `iosApp/iosApp/PlaceholderViews.swift`
  - Added AppStorage key `IOSDisplayPreferenceKeys.microsoftStreamingMarkdown`.
- `iosApp/project.yml`
  - Added Swift package dependency `microsoft/SwiftStreamingMarkdown`.
- `docs/ios-chatview-streaming-scroll-plan.md`
  - Claude's previous scroll plan. It has already been implemented partially in `ChatView.swift`; still audit it against the current UI.

## Current code state

### Streaming scroll follow

The current implementation follows the plan in `docs/ios-chatview-streaming-scroll-plan.md`:

- `pendingInitialScrollToBottom` is set on appear and conversation revision changes, so opening/switching a session should scroll to the latest message.
- Each message is wrapped in a `VStack`; for the last streaming/loading message, a clear `Color.clear.frame(height: ChatLayout.followBottomGap)` is appended.
- `.id(message.id)` is on that wrapper, so `proxy.scrollTo(lastId, anchor: .bottom)` aligns the bottom of `message + gap`.
- `onScrollPhaseChange` and `onScrollGeometryChange` track whether the user dragged away from the bottom and pause follow with `followPaused`.
- `onChange(of: viewModel.messageRevision)` scrolls to latest unless follow is paused.
- `onChange(of: isInputFocused)` does one delayed scroll after keyboard/input focus.

Current constants:

```swift
static let streamingBoundaryFadeHeight: CGFloat = 220
static let streamingBoundaryBottomOffset: CGFloat = 54
static let followBottomGap: CGFloat = 96
static let bottomStickThreshold: CGFloat = 40
```

This compiled and installed successfully in the simulator, but the user still saw unstable/incorrect visual behavior. Do not assume this is solved just because it builds.

### Bottom Liquid Glass / blur

There is currently a `StreamingBottomBoundaryGlass` view attached as a `.background(alignment: .bottom)` on `inputBar`.

This is not satisfactory. The user reported:

- The blur is not actually at the bottom boundary they expected.
- It blurs or washes out the input/composer controls.
- Text still visibly leaks below/behind the composer in a visually wrong way.

Treat the current bottom glass placement as suspect. It may be best to remove or disable it first to restore a stable baseline, then reintroduce a correct layer order.

### Microsoft SwiftStreamingMarkdown experiment

Added package:

```yaml
SwiftStreamingMarkdown:
  url: https://github.com/microsoft/SwiftStreamingMarkdown
  branch: main
```

Added a display toggle:

- `使用微软流式 MD 渲染库`
- Key: `app.amber.ios.display.microsoftStreamingMarkdown`

Current rendering wrapper:

```swift
if microsoftStreamingMarkdown {
    SwiftStreamingMarkdown.MarkdownView(text: markdown)
        .frame(maxWidth: .infinity, alignment: .leading)
} else {
    MarkdownView(markdown: markdown, displaySetting: displaySetting)
}
```

Important: this currently uses Microsoft `MarkdownView(text:)`, not necessarily the library's true streaming/incremental pipeline. The user observed that the first Microsoft rendering attempt felt smoother/stabler, but later scroll/bottom-follow changes caused regressions. Audit this separately from scroll behavior.

## Changes already made in this session

### Provider detail page

File: `iosApp/iosApp/ProviderDetailView.swift`

- Removed the `使用范围 / 预置模板` row.
- Removed the static top-right `模板` label.

### Chat composer controls

File: `iosApp/iosApp/ChatView.swift`

- Thinking control icon changed to `brain.head.profile`.
- Context ring button no longer contains the inner pie icon; it is just a ring.
- Context ring diameter was reduced inside the floating button, while keeping the button size.
- Context popover panel was changed to a left/right layout: ring on the left, stats on the right.
- Context panel stats were simplified to:
  - total messages
  - total tokens
  - token/s speed
  - cache hit rate
- Context ring stroke was made much thicker in the panel.

File: `iosApp/iosApp/ChatViewModel.swift`

- `ChatContextSnapshot` now includes `tokensPerSecond: Double?`.
- `contextSnapshot` computes token speed from `createdAt` / `finishedAt` when available.

### Assistant message margins

File: `iosApp/iosApp/ChatView.swift`

- `ChatLayout.contentHorizontalInset = 22`.
- Assistant content was adjusted to use equal left/right screen margins, since assistant messages do not have bubbles.

## Failed or risky approaches already tried

Avoid repeating these without a very explicit reason:

- Do not shrink the entire ScrollView or wrap it in a shorter clipped container just to keep the streaming bottom above the input. It breaks history scrolling because messages get cut off instead of extending naturally behind glass boundaries.
- Do not add a separate bottom-follow anchor ID as the primary mechanism. Earlier plans explicitly avoided this.
- Do not measure input bar height with `GeometryReader + PreferenceKey` and feed it back into scroll state. It risks layout/scroll feedback loops.
- Do not listen to every composer meta/focus/input height change and repeatedly force scroll. This caused visible jumping and instability.
- Do not attach the bottom blur in a way that becomes part of the input field's own material/background. The input controls must remain visually sharp above the blur.
- Do not assume the problem is the Microsoft Markdown package. The user specifically noted that the Microsoft rendering initially seemed fine, and the breakage appeared after bottom-follow changes.

## Recommended next steps

1. Audit the current `ChatView.swift` state before editing.
   - Confirm the scroll logic really matches `docs/ios-chatview-streaming-scroll-plan.md`.
   - Confirm whether `StreamingBottomBoundaryGlass` should be removed from `inputBar` first.

2. Restore a stable baseline.
   - If needed, temporarily remove/disable the current bottom glass background so input focus, composer controls, and normal rendering are not affected.
   - Verify tapping the input field still shows model chip, thinking button, and context ring.

3. Fix streaming anchor before redoing blur.
   - The target is not "make a smaller viewport".
   - The target is: while actively streaming, `scrollTo(lastMessage, anchor: .bottom)` should align the last message wrapper so the visible bottom of the growing assistant content sits just above the input field.
   - Tune `ChatLayout.followBottomGap` only after verifying the math in Simulator.
   - Make sure opening an existing session scrolls to bottom once.
   - Make sure user-dragged history browsing pauses follow and returning to bottom resumes follow.

4. Reintroduce bottom Liquid Glass only after scroll is stable.
   - The blur/glass layer should be visually at the bottom boundary / behind the composer region, but not blur the input field and buttons.
   - Keep input controls in a higher z layer than the blur.
   - History content should be able to pass behind/under the blur, similar to the top bar behavior.
   - Prefer a sibling overlay/layering solution over modifying ScrollView height.

5. Build and install after changes.
   - Preferred tool: XcodeBuildMCP `build_run_sim` with `extraArgs: ["JAVA_HOME=/opt/homebrew/opt/openjdk@17"]`.
   - Active simulator used in this session: iPhone 17, iOS 26.5.

## Verification checklist

- Open app fresh in simulator. No white screen.
- From Session list, tap a session with history. ChatView lands near the latest message, not older history.
- Tap input field. Floating controls appear and keep their expected shapes:
  - model chip
  - brain/thinking button
  - context ring button
- Send a long streaming prompt. The growing response bottom stays near the input field top and does not get covered by the input field.
- During streaming, manually scroll up. It should stop auto-following.
- Scroll back to bottom during streaming. Follow should resume.
- Switch the display option `使用微软流式 MD 渲染库` on/off. The app should hot-switch rendering without restart and without breaking message width.
- Confirm input field and buttons are crisp. Any bottom blur must not blur the input controls.
- Build succeeds and the simulator has the updated app installed.

## Suggested test prompt for streaming behavior

Use a prompt that forces long mixed Markdown and code streaming:

```text
请用中文写一份很长的 Markdown 流式渲染测试文档，不要写入 workspace。内容包含：一级到四级标题、长段落、无序列表、有序列表、引用块、表格、Swift 代码块、JSON 代码块、数学公式文字说明、分隔线，以及最后的总结。每一节都写得稍微长一点，方便我观察 iOS 聊天界面在流式生成时底部是否稳定停在输入框上方，以及历史消息滚动时是否会被截断。
```

