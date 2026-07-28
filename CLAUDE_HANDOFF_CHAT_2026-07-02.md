# Claude Handoff - AmberAgent iOS Chat Stabilization

Date: 2026-07-02  
Repo: `/Users/mi/Downloads/AI/AmberAgent-iOS`  
Branch: `feat/ios-provider-parity-claude`  
HEAD: `31cd271f9 Fix iOS chat list replay loop, frozen snapshot drift, and image sizing`

## Ground Rules

- 全程中文交流。
- 先读真实代码和项目文档再判断，不要臆想实现。
- 不要 `git reset`、不要覆盖用户改动、不要删除无关文件。
- 先不要 commit / push，除非用户明确要求。
- 只做与当前 chat 稳定性、流式渲染、滚动、图片卡体验直接相关的改动。
- 修 bug 后必须验证；能跑相关测试/构建就跑，失败如实说明。
- 不要过度防御、过度兜底、过度设计；精准修真实问题。

## Current Workspace

当前本地已拉到 GitHub 远端最新：

```text
31cd271f9 Fix iOS chat list replay loop, frozen snapshot drift, and image sizing
```

`git status --short --branch` 当前只有未跟踪 handoff 文档：

```text
## feat/ios-provider-parity-claude...origin/feat/ios-provider-parity-claude
?? CODEX_HANDOFF_CHAT_REVIEW_2026-07-01.md
?? ZCODE_HANDOFF_CHAT_SCROLL_2026-06-30.md
```

不要动这两个未跟踪文件，除非用户要求。

## Must Read First

请先读：

- `AGENTS.md`
- `CODEX_HANDOFF.md`
- `CHAT_LIST_HANDOFF.md`
- 本文件：`CLAUDE_HANDOFF_CHAT_2026-07-02.md`

`CHAT_LIST_HANDOFF.md` 里有一处旧路径 `/Users/arquiel/...`，本机实际路径以本文件顶部为准：

```text
/Users/mi/Downloads/AI/AmberAgent-iOS
```

## What The Latest Remote Commit Focused On

最新远端提交几乎全部集中在 iOS chat 页面体验，不是 provider / Codex / 生图后端。

### 1. Chat list replay loop / 被吸回底部

核心文件：

- `iosApp/iosApp/ChatCollectionMessageList.swift`
- `iosApp/iosApp/ChatViewportCoordinator.swift`
- `iosApp/iosApp/ChatView.swift`

重点变化：

- 新增 `ChatCollectionUpdateKey`。
- `ChatCollectionViewController.update(...)` 不再每次 SwiftUI update 都无条件 `applyCurrentSnapshot()`。
- 同一个 `conversationLoaded / conversationSwitched` signal 不会因为 viewport state 回调被反复重放。
- 修的是：进入已有长 session 后，上滑历史消息时列表一直被拖回底部；发新消息后才恢复可滑。

关键行为：

- 进入 session 仍应自动到底部一次。
- 用户开始上滑后不应再被强制拉回底部。

### 2. Viewport / follow 逻辑

核心文件：

- `iosApp/iosApp/ChatViewportCoordinator.swift`
- `iosApp/iosAppTests/ChatViewportPolicyTests.swift`

重点变化：

- `.userMessageAppended` 在 reducer 中会清 `followPaused` 和 `userDragging`，保证发送消息后恢复底部跟随。
- 内容从不可滚动变为可滚动时，若正在生成且允许 follow，会触发无动画贴底。
- 若用户已经 paused / dragging，不偷视口，只显示 scroll-to-bottom 按钮。
- 增加了几何转换测试。

### 3. Streaming markdown / frozen snapshot / 高度稳定

核心文件：

- `iosApp/iosApp/ChatCollectionMessageList.swift`
- `iosApp/iosApp/MessageBubbleView.swift`

重点变化：

- 抽出 `reconfigureLastAssistantRow(...)`，tail stream delta 和 viewport LOD refresh 共用同一条最后 assistant row 更新路径。
- signature 没变化时跳过 reconfigure，避免高度缓存被无意义清掉。
- `ChatRenderStateStore` 增加 `frozenMarkdownSnapshot`。
- 离屏/远离底部时冻结 markdown snapshot，防止流式 markdown 继续增长导致离屏 row 反复解析和高度漂移。
- 冻结文本只在恰好一个非空 text part 时消费，避免 reasoning/tool part 被 `toText()` 拼出多余空行。

### 4. Cell reuse / user insertion animation

核心文件：

- `iosApp/iosApp/ChatCollectionMessageList.swift`

重点变化：

- `CellRegistration` 开头调用 `resetCellAnimationState(cell)`。
- `willDisplay` 执行 user insertion animation 前后不让旧 alpha/transform 泄到复用 cell。
- 非 `.userMessageAppended` 事件会清理 stale animated insertion ids。

### 5. Generated image card sizing

核心文件：

- `iosApp/iosApp/MessageBubbleView.swift`

重点变化：

- 新增 `ChatWidthDrivenAspectRatio`。
- 图片卡和 loading placeholder 用实测宽度推高度，不再用 `.aspectRatio(.fit)`。
- 修的是 `UIHostingConfiguration` self-sizing 下，estimated cell height 把生成图压成小缩略图的问题。

### 6. Reasoning card

核心文件：

- `iosApp/iosApp/ChatMiscViews.swift`

重点变化：

- `ChatReasoningCard` 去掉内部固定高度和内部 ScrollView。
- 列表外层负责滚动，reasoning 内容不再在卡片内部截断或和 chat scroll 嵌套打架。

## Current Risk Areas To Review

请优先真实复核这些点，不要凭感觉重写：

1. `ChatCollectionUpdateKey` 是否包含了所有会改变 list 输出的输入。
   - 如果漏掉某个输入，UI 可能不更新。
   - 如果包含了会频繁变化但不影响 list 的输入，可能重新引入 replay / jitter。

2. `reconfigureLastAssistantRow(...)` 与全量 `ChatListSnapshotBuilder.build(...)` 的 row/signature 口径是否一致。
   - 不一致会导致 tail delta 和 full build 之间 signature 来回翻转，引发高度缓存抖动。

3. `frozenMarkdownSnapshot` 是否只在真正适合冻结的情况下使用。
   - 多 text part / reasoning + text / tool + text 的消息不要被错误冻结成丢内容的单文本。

4. `ChatWidthDrivenAspectRatio` 的 fallback height 是否会导致首帧布局跳动。
   - 当前 fallback 是 220，宽度测到后按实际 aspect ratio 修正。
   - 如果用户还看到图片闪动，需要在这里精准看，不要动整个 list 架构。

5. reasoning card 去掉内部 ScrollView 后，超长 reasoning 是否导致单个 cell 非常高。
   - 这是有意选择：列表拥有滚动控制。
   - 只有用户明确反馈体验问题时再调整。

## Suggested Commands

每次改 Swift 文件或新增文件后，先生成工程：

```bash
cd /Users/mi/Downloads/AI/AmberAgent-iOS/iosApp
/opt/homebrew/bin/xcodegen generate
```

相关测试优先跑：

```bash
cd /Users/mi/Downloads/AI/AmberAgent-iOS
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
xcodebuild test -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:iosAppTests/ChatViewportPolicyTests \
  -skipMacroValidation -skipPackagePluginValidation \
  -derivedDataPath /tmp/AmberAgentChatTestsDerivedData
```

如果改了 projection / insertion animation，还跑：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
xcodebuild test -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:iosAppTests/ChatMessageProjectionTests \
  -skipMacroValidation -skipPackagePluginValidation \
  -derivedDataPath /tmp/AmberAgentChatTestsDerivedData
```

基础检查：

```bash
git diff --check
```

真机构建安装按主 `iosApp` scheme：

```bash
cd /Users/mi/Downloads/AI/AmberAgent-iOS
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS,id=94918570-0680-5B93-8E38-7E6B355D4426' \
  -allowProvisioningUpdates DEVELOPMENT_TEAM=89QRFX9548 CODE_SIGN_STYLE=Automatic \
  -skipMacroValidation -skipPackagePluginValidation build
```

```bash
xcrun devicectl device install app \
  --device 94918570-0680-5B93-8E38-7E6B355D4426 \
  /Users/mi/Library/Developer/Xcode/DerivedData/AmberAgent-gfeldsdglrkbjmebgswcebkbpeiy/Build/Products/Debug-iphoneos/iosApp.app
```

## Manual QA Checklist

请让用户在真机重点测：

1. 进入已有长 session 后，立即上滑历史。
   - 预期：进入时自动到底部一次。
   - 预期：用户上滑后不再被拽回底部。

2. 不发新消息，反复上滑/下滑。
   - 预期：不跳到接近开头。
   - 预期：不出现回弹抖动。
   - 预期：user bubble / assistant bubble 不错位。

3. 发送新消息。
   - 预期：user 消息上屏动画存在。
   - 预期：发送后贴底但滚动本身不和上屏动画打架。

4. 流式生成中上滑看历史。
   - 预期：用户主动上滑后暂停底部跟随。
   - 预期：离屏 assistant markdown 冻结，不持续拖慢历史滚动。
   - 预期：滑回底部附近后恢复 live rendering。

5. reasoning / tool / image / widget 混合消息。
   - 预期：reasoning 完整显示，列表滚动负责承载高度。
   - 预期：生成图片不塌成缩略图。
   - 预期：工具详情、图片预览、修改图片 action 不串消息。

## Claude Prompt

```text
你现在接手 AmberAgent iOS/KMP 项目。全程中文交流。

仓库路径：
/Users/mi/Downloads/AI/AmberAgent-iOS

目标分支：
feat/ios-provider-parity-claude

当前 HEAD：
31cd271f9 Fix iOS chat list replay loop, frozen snapshot drift, and image sizing

请先完整阅读：
1. AGENTS.md
2. CODEX_HANDOFF.md
3. CHAT_LIST_HANDOFF.md
4. CLAUDE_HANDOFF_CHAT_2026-07-02.md

注意：
- 本机真实路径是 /Users/mi/Downloads/AI/AmberAgent-iOS，CHAT_LIST_HANDOFF.md 里旧的 /Users/arquiel 路径不要照抄。
- 不要 commit，不要 push，不要 git reset，不要覆盖用户改动。
- 当前工作区只有未跟踪 handoff 文档：CODEX_HANDOFF_CHAT_REVIEW_2026-07-01.md 和 ZCODE_HANDOFF_CHAT_SCROLL_2026-06-30.md。不要动它们，除非用户明确要求。
- 先看真实代码再判断，不要臆想。
- 精准修真实问题，不要过度防御、过度兜底、过度设计。

当前最新远端改动 focus 在 iOS chat 页面：
- UICollectionView + ChatLayout 列表稳定性。
- 防止 conversationLoaded/conversationSwitched 被 SwiftUI update 反复重放导致列表被吸回底部。
- userMessageAppended 后恢复底部 follow。
- 内容变成可滚动时的 follow/no-steal viewport 策略。
- streaming markdown 的 frozen snapshot 和高度稳定。
- cell 复用时清理 user insertion animation 状态。
- generated image card 用宽度驱动 aspect ratio，避免缩略图塌陷。
- reasoning card 去掉内部 ScrollView，让外层列表负责滚动。

你的第一步任务：
1. 读上述文档和相关真实代码。
2. 看当前 diff/status，确认没有本地代码改动。
3. 对最新 chat list 改动做一轮精准 review，重点看：
   - ChatCollectionUpdateKey 是否漏输入或包含会引起重放的噪声输入。
   - reconfigureLastAssistantRow 与 full build 的 row/signature 口径是否一致。
   - frozenMarkdownSnapshot 是否可能丢内容或导致完成后闪烁。
   - width-driven image sizing 是否有首帧高度跳变。
   - viewport reducer 是否仍可能抢用户滚动。
4. 如果发现真实问题，先解释问题和最小修法，再精准修改。
5. 修改后运行 xcodegen generate、相关 xcodebuild tests、git diff --check；需要真机验证时按 handoff 里的主 iosApp scheme 命令构建安装。

最终汇报请包含：
- 你实际读了哪些文件。
- 发现并修复了哪些真实问题。
- 验证命令和结果。
- 仍需用户真机肉眼确认的体验点。
```
