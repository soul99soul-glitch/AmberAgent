# AmberAgent iOS/KMP 流式滚动/底部跟随 handoff - 2026-07-04

## 0. 接手前先读

Repo:

```bash
cd /Users/arquiel/Downloads/AI/amberagent-ios
```

分支:

```text
feat/ios-provider-parity-claude
```

纪律:

- 全程中文。
- 工作区有大量未提交改动，全部视为有效工作；不许 `commit/push/stash/reset/checkout`，除非用户明确要求。
- 先读根目录 `CLAUDE_HANDOFF_STREAM_SCROLL_2026-07-03.md`、`CLAUDE.md`、`AGENTS.md`。
- 碰滚动/布局文件，装机前必须跑 `ChatStreamReplayTests`，并先向用户报备改了什么。
- 不要再用“已经解决”这种语气。只能说“已修某个断点，等待真机验证”。

## 1. 用户当前反馈

用户已经多轮真机复测，核心问题长期未闭环。最新反馈:

- “没修好，跟之前毫无区别的灾难。”
- 随后又说“解决了一点点，但是感觉好累”，说明最新安装版可能有局部改善，但底部跟随/流式体验仍未达标。

历史主症状:

- 流式生成开始或一屏铺满前后，上下跳变/抖动。
- 用户手动滑到接近底部时，不自动恢复底部跟随。
- 只有点向下箭头时才会突然跳出一大段内容。
- 生成中查看历史性能仍差。
- 曾出现正文流式不可见、结束后闪现、箭头失效、点击箭头闪烁等回归。

当前最重要的一条: **不要继续猜。下一轮必须先拿运行时证据，确认真机到底跑哪条链路、状态机卡在哪个变量。**

## 2. 当前代码状态概览

`git status --short` 显示大量 dirty 文件，包括但不限于:

```text
M iosApp/iosApp/ChatCollectionMessageList.swift
M iosApp/iosApp/ChatComposerViews.swift
M iosApp/iosApp/ChatMessageListSupport.swift
M iosApp/iosApp/ChatMessageProjection.swift
M iosApp/iosApp/ChatMiscViews.swift
M iosApp/iosApp/ChatScrollArbiterCore.swift
M iosApp/iosApp/ChatView.swift
M iosApp/iosApp/ChatViewportCoordinator.swift
M iosApp/iosApp/MarkdownView.swift
M iosApp/iosApp/MessageBubbleView.swift
M iosApp/iosAppTests/ChatMessageProjectionTests.swift
M iosApp/iosAppTests/ChatScrollArbiterCoreTests.swift
M iosApp/iosAppTests/ChatStreamReplayTests.swift
M iosApp/iosAppTests/ChatViewportPolicyTests.swift
M iosApp/vendor/SwiftStreamingMarkdown/...
?? CLAUDE_HANDOFF_STREAM_SCROLL_2026-07-03.md
?? iosApp/iosApp/ChatScrollArbiter.swift
```

不要删除、回滚或“整理”这些文件。

## 3. 最新一轮底部跟随改动

主要落点:

```text
iosApp/iosApp/ChatCollectionMessageList.swift
```

关键点:

1. `ChatSwiftUIMessageListFeatureFlags.isEnabled` 现在强制返回 `true`。
   - 目的: 避免设备残留 `UserDefaults["chat.swiftui.cleanList.enabled"] = false`，导致真机根本不跑 clean SwiftUI list。
   - 这是一个临时验证性硬切，后续若要产品化需恢复成可控开关。

2. `ChatSwiftUIMessageList` 新增运行时状态:

```swift
@State private var lastObservedScrollOffsetY: CGFloat?
@State private var userScrolledTowardBottomDuringDrag = false
@State private var followDebugTick = 0
```

3. 新增统一恢复入口:

```swift
restoreBottomFollowIfNeeded(...)
```

它在这些入口被调用:

- `.assistantStreamDelta`
- 流式结束/工具事件等收尾事件
- `markUserDragEnded`
- `publishGeometry`

意图:

- 不再让 `followPaused == true` 永久拦死后续 delta。
- 手动往底部方向滑动过时，允许进入更大的 catch-up 窗口，而不是要求瞬间像素距离已经真正到底。

4. 新增方向识别:

```swift
updateUserScrollIntent(offsetY:isUserScrolling:bottomDistance:)
```

逻辑:

- `delta > 3` 认为用户在往底部方向滑。
- 若同时 `bottomDistance <= bottomFollowCatchUpThreshold`，设置 `bottomFollowResumeArmed = true`。
- `delta < -12` 且离底超过普通阈值，则清掉 toward-bottom 意图。

5. 新增 catch-up 阈值:

```swift
private var bottomFollowCatchUpThreshold: CGFloat {
    let viewportBased = viewportHeight > 0 ? viewportHeight * 1.4 : 900
    return max(600, min(1_400, viewportBased))
}
```

6. 新增运行时日志:

```text
[AA-FOLLOW]
```

日志内容包括:

- messages
- active/loading/followGeneration
- followPaused/userDragging/isAtBottom
- bottomFollowResumeArmed
- userScrolledTowardBottomDuringDrag
- currentBottomDistance/resume threshold/catch threshold
- UIScrollView offset/target/content/bounds/isDragging/isTracking/isDecelerating

下一轮如果真机还坏，第一件事不是改代码，而是抓 `[AA-FOLLOW]`。

## 4. Android 对照结论

上一轮用 subagent 独立看了 Android，结论如下。

核心文件:

```text
app/src/main/java/app/amber/feature/ui/pages/chat/ChatListSupport.kt
app/src/main/java/app/amber/feature/ui/pages/chat/ChatListNormalSection.kt
app/src/main/java/app/amber/feature/ui/pages/chat/ChatStreamingFollowPolicy.kt
```

Android 判底:

```kotlin
LazyListState.isNearListEnd(bufferItems = 2)
LazyListState.isAtTimelineBottom(bufferPx = 0)
```

实际聊天页使用:

```kotlin
bottomFollowBufferPx = 24.dp
```

Android 状态机:

```kotlin
TimelineFollowMode.Idle
TimelineFollowMode.FollowingBottom
TimelineFollowMode.PausedForUser
```

关键行为:

- 用户滚动中，如果 active generation，则进入 `PausedForUser`。
- 用户滚动停止后，如果 `isAtTimelineBottom(bottomFollowBufferPx)`，调用 `resumeBottomFollow()`。
- 流式 chunk 到来时，只有 `followMode == FollowingBottom && !userScrollInTimeline` 才发跟随事件。
- 跟随事件走 `MutableSharedFlow(extraBufferCapacity = 1, DROP_OLDEST)` + `conflate()`。
- 真正滚动不是每 token 动画，而是 `scrollToTimelineBottom(... smoothLargeMove = false)`，优先 `scrollBy(distance)`，不可见时 `scrollToItem(totalItems - 1)` 后下一帧补 `scrollBy(settleDistance)`。
- 生成结束后有 `settleAfterGenerationEnd()`，最多 16 帧，要求 2 个稳定底部帧。

iOS 当前与 Android 的关键差异:

- iOS clean SwiftUI 主要靠 `bottomAnchor` + `UIScrollView.contentOffset/contentSize` 算像素距离；之前缺 Android 的 item 粗判和明确 `PausedForUser -> FollowingBottom` 闭环。
- iOS 没有 Android 那种 generation-end settle loop。
- iOS 的 delta 入口曾经 `guard canAutoFollow else { return }`，导致 `followPaused` 后 delta 永久被吞。

## 5. 已跑测试

最近一次门禁:

```bash
env JAVA_HOME=/opt/homebrew/opt/openjdk@17 xcodebuild test \
  -project iosApp/AmberAgent.xcodeproj \
  -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -skipMacroValidation \
  -skipPackagePluginValidation \
  -derivedDataPath /tmp/AmberAgentSimTestDerivedData \
  -only-testing:iosAppTests/ChatStreamReplayTests
```

结果:

```text
Executed 17 tests, with 1 test skipped and 0 failures.
** TEST SUCCEEDED **
```

重要: 这只是滚动/布局装机门禁，不等于真机问题已解决。

## 6. 最近真机构建/安装

构建命令:

```bash
env JAVA_HOME=/opt/homebrew/opt/openjdk@17 xcodebuild \
  -project iosApp/AmberAgent.xcodeproj \
  -scheme iosApp \
  -destination 'platform=iOS,id=00008150-000A594E0AF8401C' \
  -allowProvisioningUpdates \
  DEVELOPMENT_TEAM=89QRFX9548 \
  CODE_SIGN_STYLE=Automatic \
  -skipMacroValidation \
  -skipPackagePluginValidation \
  -derivedDataPath /tmp/AmberAgentDeviceLiquidGlassDerivedData \
  build
```

结果:

```text
** BUILD SUCCEEDED **
```

安装命令:

```bash
xcrun devicectl device install app \
  --device 94918570-0680-5B93-8E38-7E6B355D4426 \
  /tmp/AmberAgentDeviceLiquidGlassDerivedData/Build/Products/Debug-iphoneos/iosApp.app
```

结果:

```text
App installed:
bundleID: app.amber.ios
```

启动命令:

```bash
xcrun devicectl device process launch \
  --device 94918570-0680-5B93-8E38-7E6B355D4426 \
  --terminate-existing app.amber.ios
```

最近一次失败原因:

```text
Unable to launch app.amber.ios because the device was not, or could not be, unlocked.
```

也就是说: 安装成功，但自动启动被锁屏拦截。

## 7. 下一轮优先级

不要先改代码。先要运行时证据:

1. 让用户真机复现“滑到底不跟随/箭头才跟随/流式内容跑到屏幕外”。
2. 抓 `[AA-FOLLOW]` 日志。
3. 根据日志判断:
   - 是否出现 `appear`，确认 clean SwiftUI list 真的在跑。
   - delta 是否持续出现。
   - `followPaused` 是否被解除。
   - `userDragging` 是否卡住为 true。
   - `distance` 是否一直远大于 `catch`。
   - `offset` 是否没有变而 `target` 持续增大。
   - `setContentOffset` 是否被调用后又被 SwiftUI/Layout 拉回。

如果没有 `[AA-FOLLOW] appear`:

- 说明根本没跑 clean list，要查入口或安装版本。

如果有 delta，但没有 restore:

- 查 `isUserActivelyTouchingScroll` 是否长期 true，尤其 `isTracking` 是否卡住。

如果 restore.follow 出现，但 offset 不到 target:

- 查 `scrollUIScrollViewToBottom` 是否目标计算错误，或 SwiftUI `ScrollViewReader`/layout pass 把 offset 覆盖。

如果 restore.follow 出现，offset 到 target，但视觉仍不跟:

- 可能不是滚动状态机，而是内容行高度/渲染延迟/Markdown table 布局引发“实际内容增长未纳入 contentSize”。

## 8. 给下一轮 Codex 的 prompt

复制下面这段给新会话:

```text
你接手 AmberAgent iOS/KMP 流式滚动/底部跟随战役。

Repo: /Users/arquiel/Downloads/AI/amberagent-ios
Branch: feat/ios-provider-parity-claude
禁令: 不 commit/push/stash/reset/checkout；工作区大量未提交改动全是有效工作，不许回滚。全程中文。

先按顺序读:
1. 根目录 CLAUDE_HANDOFF_STREAM_SCROLL_2026-07-03.md
2. 根目录 HANDOFF_STREAM_SCROLL_BOTTOM_FOLLOW_2026-07-04.md
3. CLAUDE.md / AGENTS.md

当前用户反馈:
- 多轮修复后仍没彻底解决，最新说“解决了一点点，但是很累”。
- 主要问题仍是流式生成中底部跟随/手动滑到底恢复跟随不可靠，过去表现包括: 必须点向下箭头才跳出内容、手动滑到底不恢复、生成内容跑到屏幕外、抖动/闪烁。

当前代码最新状态:
- iOS clean SwiftUI list 已强制启用: ChatSwiftUIMessageListFeatureFlags.isEnabled == true。
- ChatCollectionMessageList.swift 里新增了 [AA-FOLLOW] 日志。
- 最新思路是: 不再只靠像素近底；用户往底部方向滑动过时，进入 catch-up 恢复窗口，避免流式内容持续增长导致永远追不上移动底部。

本会话第一件事:
不要先改代码。先帮我抓/看真机运行时证据:
1. 确认当前安装版是否跑到 clean SwiftUI list，日志里应有 [AA-FOLLOW] appear。
2. 复现“滑到底不跟随/只有点箭头才跟随”时，分析 [AA-FOLLOW] 日志里的 paused/userDragging/atBottom/armed/towardBottom/distance/resume/catch/offset/target。
3. 给出明确判断: 是入口没跑、delta 没到、手势状态卡住、距离阈值不合理、setContentOffset 被覆盖，还是渲染/高度更新导致 contentSize 不可信。

只有拿到运行时证据后再改。触碰滚动/布局文件，装机前必须跑 ChatStreamReplayTests，并在装机前报备改了什么。
```

## 9. 心态提醒

用户已经被这个问题折磨很久。下一轮不要解释“理论上应该好”，也不要把测试通过说成解决。必须承认:

- 现有测试覆盖不到真机交互全貌。
- 这个问题此前多次“看似命中”但真机无效。
- 下一步的胜负手是运行时证据，而不是再堆一个推测性 patch。
