# Zcode Handoff: Chat 滚动 / 流式 Markdown 平滑性

日期：2026-06-30  
仓库：`/Users/mi/Downloads/AI/AmberAgent-iOS`  
分支：`feat/ios-provider-parity-claude`  
当前 commit：`1166d7d34`  
状态：未提交、未 push；请不要 reset，不要覆盖用户改动。

## 用户目标

用户认为 iOS Chat 页面的核心难点在于：流式生成、Markdown 渲染、滚动、顶部/底部回弹、底部跟随、动态块高度变化必须视觉上无缝衔接，不能互相抢逻辑。

最近暴露的问题包括：

- Microsoft SwiftStreamingMarkdown 完成后重组时有一瞬间块消失，导致高度归零，最后一条 user 消息掉下来闪一下。
- 上滑查看历史消息时，列表会乱跳到接近开头的位置；下滑相对正常。
- user 消息原先类似 iMessage 的上屏动画一度丢失。
- 滑动到顶部/底部时系统回弹会抖动，不像正常 rubber-band。
- 需要继续以“精准修复、不要过度防御、不要过度设计”为原则推进。

## 已做改动

### 1. 修复流式 Markdown 完成瞬间块消失

相关文件：

- `ai-core/src/commonMain/kotlin/app/amber/ai/ui/MessageStreamAccumulator.kt`
- `ai-core/src/commonTest/kotlin/app/amber/ai/ui/MessageStreamAccumulatorIdentityTest.kt`
- `iosApp/iosApp/MessageBubbleView.swift`

要点：

- `MessageStreamAccumulator.replaceActive` 在同 role 的 assistant final message 替换 active message 时保留 active id，避免 SwiftUI 认为整块消息被删除再新增。
- `MessageBubbleView` 的 `withShouldAnimateText` 改为只在 `isStreaming` 时启用，避免完成态重组继续触发文字动画/布局扰动。
- 新增 KMP 测试锁住 final replacement 保留 identity。

已验证：

- `./gradlew :ai-core:jvmTest :ai-provider-openai:jvmTest` 已通过。

### 2. 修复上滑历史消息乱跳

相关文件：

- `iosApp/iosApp/ChatView.swift`

要点：

- 移除持续绑定的 `ScrollPosition`，避免它和用户拖拽、系统惯性、流式 size changes 抢滚动位置。
- 初次/切会话仍通过 `.defaultScrollAnchor(..., for: .initialOffset)` 先从底部实现，再做一次非动画底部校正。
- 自动跟随增加 `!userDragging` 条件，用户正在拖拽/减速期间不自动拉回底部。

### 3. 恢复 user 消息 iMessage 式上屏动画

相关文件：

- `iosApp/iosApp/ChatViewModel.swift`
- `iosApp/iosApp/ChatView.swift`

要点：

- `appendUserMessage` 中 `messages.append(userMsg)` 和 revision/update signal 放在同一个 spring animation transaction 里。
- 批量加载/切会话仍走整体赋值，不触发逐条入场动画。

### 4. 修复顶部/底部回弹抖动来源之一

相关文件：

- `iosApp/iosApp/ChatView.swift`

要点：

- “回到底部”悬浮按钮从 `.safeAreaInset(edge: .bottom)` 内移到主 `ZStack` overlay。
- 现在 safeAreaInset 只包含 input bar，按钮显隐不再改变 ScrollView 可视区域，减少和系统 rubber-band 的布局冲突。

### 5. 新增薄的滚动策略层，减少逻辑冲突

相关文件：

- `iosApp/iosApp/ChatViewportCoordinator.swift`
- `iosApp/iosApp/ChatViewportPolicyTests.swift`
- `iosApp/iosApp/ChatView.swift`
- `iosApp/iosApp/ChatViewModel.swift`
- `iosApp/iosApp/ChatGenerationCoordinator.swift`
- `iosApp/iosAppTests/ChatViewModelSelectedFileContextTests.swift`

要点：

- 新增 `ChatMessageUpdateReason`：
  - `.initialLoad`
  - `.conversationSwitch`
  - `.userAppend`
  - `.streamDelta`
  - `.streamFinish`
  - `.toolDelta`
  - `.branchChange`
  - `.settingsRefresh`
- 新增 `ChatMessageUpdateSignal`，保留原 `messageRevision`，但 ChatView 主要观察带原因的 signal。
- 新增 `ChatViewportPolicy`：
  - 初次加载/切会话：非动画、deferred、底部锚点落底。
  - user append / stream delta / stream finish / tool delta：只有 `canAutoFollow && isContentScrollable` 时无动画贴底。
  - branch change / settings refresh：不偷滚动位置。
  - 内容刚变成可滚时：只有正在流式且可自动跟随时无动画贴底。
- `ChatGenerationCoordinator` 给 stream/tool/final/error 更新打原因标签，避免 View 继续靠 “revision 变了” 猜。

已验证：

- 红灯：新增 `ChatViewportPolicyTests` 后先运行失败，失败原因是 `ChatViewportPolicy` 等策略类型不存在。
- 绿灯：`ChatViewportPolicyTests` 5 个测试通过，0 failures。

## 当前工作区状态

当前 `git status --short`：

```text
 M ai-core/src/commonMain/kotlin/app/amber/ai/ui/MessageStreamAccumulator.kt
 M iosApp/iosApp/ChatGenerationCoordinator.swift
 M iosApp/iosApp/ChatView.swift
 M iosApp/iosApp/ChatViewModel.swift
 M iosApp/iosApp/MessageBubbleView.swift
 M iosApp/iosAppTests/ChatViewModelSelectedFileContextTests.swift
?? ai-core/src/commonTest/kotlin/app/amber/ai/ui/MessageStreamAccumulatorIdentityTest.kt
?? iosApp/iosApp/ChatViewportCoordinator.swift
?? iosApp/iosAppTests/ChatViewportPolicyTests.swift
?? ZCODE_HANDOFF_CHAT_SCROLL_2026-06-30.md
```

注意：`git diff --stat` 不显示 untracked 文件，review 时要记得看新增文件。

## 已运行验证

### KMP 测试

```bash
./gradlew :ai-core:jvmTest :ai-provider-openai:jvmTest
```

结果：通过。

### iOS 策略单测

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
xcodebuild test -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,id=C830D3C4-6806-4C5F-931B-6B8262F4DA56' \
  -only-testing:iosAppTests/ChatViewportPolicyTests \
  -skipMacroValidation -skipPackagePluginValidation
```

结果：

```text
Executed 5 tests, with 0 failures (0 unexpected)
** TEST SUCCEEDED **
```

测试中有后台任务注册相关日志：

```text
BGContinuedProcessingTask registration failed for app.amber.ios.deepread.*
BGContinuedProcessingTask registration failed for app.amber.ios.chat.*
```

这些是现有 Info.plist/测试环境噪声，不是本次策略测试失败。

### 真机构建

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS,id=94918570-0680-5B93-8E38-7E6B355D4426' \
  -allowProvisioningUpdates DEVELOPMENT_TEAM=89QRFX9548 CODE_SIGN_STYLE=Automatic \
  -skipMacroValidation -skipPackagePluginValidation build
```

结果：

```text
** BUILD SUCCEEDED **
```

### 真机安装

```bash
xcrun devicectl device install app \
  --device 94918570-0680-5B93-8E38-7E6B355D4426 \
  /Users/mi/Library/Developer/Xcode/DerivedData/AmberAgent-gfeldsdglrkbjmebgswcebkbpeiy/Build/Products/Debug-iphoneos/iosApp.app
```

结果：

```text
App installed:
• bundleID: app.amber.ios
```

## 仍需 Zcode 重点检查

请在真机上做手感验证，这些目前自动化覆盖不足：

1. 长对话中向上滑动查看历史，确认不会突然跳到接近开头。
2. 流式生成中上滑离开底部，确认不会被自动拉回。
3. 点“回到底部”按钮，确认能平滑回到底。
4. 短回复从不可滚变成可滚时，确认不会抖。
5. 回复完成、Markdown 从 streaming renderer 切到 final renderer 时，确认没有块消失和高度塌陷闪烁。
6. 顶部/底部 rubber-band 回弹，确认没有按钮显隐导致的可视区域变化抖动。
7. user 消息发送时，确认右侧气泡还有类似 iMessage 的上屏动效。

如果仍有问题，优先检查：

- `ChatViewportPolicy` 的事件规则是否过宽或过窄。
- `ChatView` 的 `userDragging`、`followPaused`、`isContentScrollable` 是否在某个滚动阶段切换过早。
- 是否还有 View 内局部 `.animation` 或 safeArea/inset 变化在影响 ScrollView 可视区域。
- 不要重新引入持续绑定型 `ScrollPosition`，除非能证明它不和用户拖拽/流式 size change 抢控制权。

## 开发环境提醒

生成工程：

```bash
cd iosApp
/opt/homebrew/bin/xcodegen generate
```

新增/删除 Swift 文件后必须重新生成工程；不要手改 pbxproj。

真机构建和安装沿用上面的命令。KMP `Shared.framework` 需要：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
```

固定参数：

- Team ID：`89QRFX9548`
- 真机 devicectl id：`94918570-0680-5B93-8E38-7E6B355D4426`
- DerivedData：`/Users/mi/Library/Developer/Xcode/DerivedData/AmberAgent-gfeldsdglrkbjmebgswcebkbpeiy`

## 给 Zcode 的启动 Prompt

```text
你现在接手 AmberAgent iOS/KMP 项目，仓库在：
/Users/mi/Downloads/AI/AmberAgent-iOS

目标分支：
feat/ios-provider-parity-claude

请全程中文交流。先读取 AGENTS.md、CODEX_HANDOFF.md 和 ZCODE_HANDOFF_CHAT_SCROLL_2026-06-30.md。不要 reset，不要覆盖用户改动，不要提交，不要 push，除非我明确要求。

当前工作区已有未提交改动，核心是在修 Chat 页面的流式 Markdown、滚动、回弹、底部跟随和 user 消息上屏动画的衔接问题。请先用 git status、git diff、rg 阅读真实代码，不要臆想。

已经做过的关键改动：
1. MessageStreamAccumulator final replacement 保留 assistant active message id，避免完成态块消失。
2. MessageBubbleView 完成后不再继续对 final renderer 开文字动画。
3. ChatView 移除了持续绑定 ScrollPosition，避免和用户拖拽、流式高度变化抢滚动。
4. 回到底部按钮移出 bottom safeAreaInset，作为 ZStack overlay，避免按钮显隐改变 ScrollView 可视区域。
5. ChatViewModel 新增 messageUpdateSignal，ChatGenerationCoordinator 给 userAppend/streamDelta/streamFinish/toolDelta/branchChange/settingsRefresh 打标签。
6. ChatViewportCoordinator.swift 新增薄的 ChatViewportPolicy，用纯函数决定是否滚动、是否动画、是否使用 bottom anchor。

已验证：
- ./gradlew :ai-core:jvmTest :ai-provider-openai:jvmTest 通过。
- ChatViewportPolicyTests 5 个 iOS 单测通过。
- 真机构建 ** BUILD SUCCEEDED **。
- 已安装到 iPhone Air，bundleID app.amber.ios。

你接下来要做：
1. 全面 review 当前未提交 diff，尤其是 ChatViewportPolicy 是否足够精准，是否有过度设计或漏标事件。
2. 重点在真机/代码逻辑层检查这些手感问题：
   - 上滑看历史不应乱跳到开头附近。
   - 流式生成中用户上滑后不应被自动拉回底部。
   - 回复完成重组 Markdown 时不应出现块消失/高度归零闪烁。
   - 顶部/底部 rubber-band 回弹不应抖。
   - user 消息发送仍应有 iMessage 式上屏动画。
3. 如果发现问题，精准修复；优先调整 ChatViewportPolicy 或少量 ChatView 状态衔接，不要重写整个 ChatView，不要引入大型状态机。
4. 修改后必须重新 xcodegen generate，跑相关测试/构建；能真机安装就安装。

构建命令：
cd iosApp && /opt/homebrew/bin/xcodegen generate

JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS,id=94918570-0680-5B93-8E38-7E6B355D4426' \
  -allowProvisioningUpdates DEVELOPMENT_TEAM=89QRFX9548 CODE_SIGN_STYLE=Automatic \
  -skipMacroValidation -skipPackagePluginValidation build

安装命令：
xcrun devicectl device install app \
  --device 94918570-0680-5B93-8E38-7E6B355D4426 \
  /Users/mi/Library/Developer/Xcode/DerivedData/AmberAgent-gfeldsdglrkbjmebgswcebkbpeiy/Build/Products/Debug-iphoneos/iosApp.app

请先汇报你读完文档后的理解、当前 dirty files、你认为最可能还残留的风险点，然后再动手。
```
