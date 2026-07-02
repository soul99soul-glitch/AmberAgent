# AmberAgent iOS Chat List Handoff

> 仓库: `/Users/arquiel/Downloads/AI/amberagent-ios`  
> 主题: iOS chat 页面列表迁移、滚动稳定性、流式渲染与超长 session 性能  
> 当前日期: 2026-07-02  
> 交流要求: 全程中文; 先看真实代码再判断; 不要擅自删除无关代码; 报错如实说。

## 1. 当前背景

项目是 KMP + 原生 SwiftUI iOS。最近主要围绕 chat 页面做了高频修复和性能改造:

- 消息列表底座已从原 SwiftUI `ScrollView + LazyVStack` 迁到 `UICollectionView + ChatLayout`。
- SwiftUI row 仍通过 `UIHostingConfiguration` 承载，暂未原生 UIKit 化。
- 目标是解决超长 session 滑动卡顿、流式生成时上滑历史卡顿、消息高度跳变、滚动锚点乱跳等问题。
- 用户对交互和视觉精致度要求很高，尤其关注:
  - 进入 session 自动到底部但不能“拽住”用户滚动。
  - 流式生成时可以上滑看历史且保持高帧率。
  - 滑回底部后恢复实时渲染。
  - 思考卡、工具卡、图片卡、markdown 渲染不要错位或跳变。
  - 不要过度兜底、过度设计，要精准修。

## 2. 最近改动涉及的文件

当前工作区仍有未提交改动:

```text
M iosApp/iosApp/ChatCollectionMessageList.swift
M iosApp/iosApp/ChatMiscViews.swift
M iosApp/iosApp/ChatView.swift
M iosApp/iosApp/ChatViewportCoordinator.swift
M iosApp/iosApp/MessageBubbleView.swift
M iosApp/iosAppTests/ChatViewportPolicyTests.swift
```

大致改动:

- `ChatCollectionMessageList.swift`
  - 使用 `ChatLayout` 承载 chat 列表。
  - 关闭 `keepContentAtBottomOfVisibleArea`，避免短内容/载入期间把列表强制吸到底部。
  - changed item 从 `reloadItems` 改成 `reconfigureItems` + 精确 layout invalidation。
  - row `renderIdentity` 改为 `messageId + rendererMode`，避免每个 chunk 造成 SwiftUI hosting root 过度重建。
  - variant info 改为通过 message index 解析，避免 messageId 与分支信息错位。
  - 增加 `ChatRenderStateStore` 的 frozen markdown snapshot 支持。
  - 增加 `refreshLastAssistantRenderState()`，在离底部远/回到预热区时切换 live/frozen 渲染。
  - 最新修复: 增加 `ChatCollectionUpdateKey`，防止同一个 `conversationLoaded / conversationSwitched` 被外层 viewport state 更新反复重放。

- `ChatMiscViews.swift`
  - `ChatReasoningCard` 去掉内部固定 `ScrollView`，避免 reasoning 内容在列表 cell 中出现不可滚完整/高度不闭环的问题。

- `MessageBubbleView.swift`
  - 给 assistant markdown view 传入 `frozenMarkdownSnapshot`，用于离屏/远离底部时冻结文本渲染。

- `ChatView.swift`
  - 适配 collection list 的 viewport state、variant info、summary snapshot 等数据流。

- `ChatViewportCoordinator.swift`
  - 修正内容从不可滚动变为可滚动时的 viewport 状态转换。

- `ChatViewportPolicyTests.swift`
  - 增加 viewport 几何转换相关测试。

## 3. 刚修的关键 bug

用户反馈:

> 点进 session 之后滑动不了，一直被拽住。发了一条新消息之后，又能滑动查看历史消息了。

根因:

1. `ChatCollectionMessageList.updateUIViewController` 在外层 SwiftUI 任意刷新时都会调用 `controller.update(...)`。
2. `ChatCollectionViewController.update(...)` 原来无条件 `applyCurrentSnapshot()`。
3. 如果当前 signal 仍是 `.conversationLoaded` 或 `.conversationSwitched`，`ChatViewportReducer.reduce(...)` 会返回 `.initialAnchor` 或 `.resetForConversationSwitch`。
4. `execute(...)` 继续调用 `scrollToBottom(animated: false)`。
5. 用户拖动会触发 viewport state 回调，`ChatView` state 改变又让 wrapper 更新，于是同一个会话载入事件被反复重放，列表看起来被“拽住”。
6. 发新消息后 signal 变成 `.userMessageAppended`，重放 reset 的循环停止，所以用户感觉发消息后又恢复可滑动。

修复:

- 在 `ChatCollectionViewController` 中新增:

```swift
private var lastAppliedUpdateKey: ChatCollectionUpdateKey?
```

- `update(...)` 中只在真实输入 key 改变时调用 `applyCurrentSnapshot()`。
- 显式 `scrollToBottomTrigger` 仍独立处理，确保“回到底部”按钮/外部触发不被拦。

注意:

- `ChatMessageUpdateSignal` 本身是 `Equatable`，包含 `revision + reason`，所以真实消息更新不会被误拦。
- 这个修复不碰 scroll delegate、不加新的手势锁，副作用面较窄。

## 4. 已验证结果

已执行:

```bash
xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build

xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -only-testing:iosAppTests/ChatViewportPolicyTests test

git diff --check
```

结果:

- simulator build 通过。
- `ChatViewportPolicyTests` 通过。
- `git diff --check` 通过。
- 存在既有 warning:
  - AmberNative simulator archive built for newer iOS-simulator version。
  - 若干测试文件既有 Swift warning。

真机:

- `iosAppExperimentalGPL` 通过提权后已能解析 SPM，但设备构建失败在签名:
  - 缺 `app.amber.ios.experimental-gpl`
  - 缺 `app.amber.ios.experimental-gpl.activity`
  - 不是代码编译失败。
- 已改用 stable target 构建:

```bash
cd /Users/arquiel/Downloads/AI/amberagent-ios/iosApp
xcodebuild -project AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS,id=00008150-000A594E0AF8401C' \
  -allowProvisioningUpdates DEVELOPMENT_TEAM=89QRFX9548 CODE_SIGN_STYLE=Automatic \
  -derivedDataPath build/DeviceBuild build
```

结果明确包含:

```text
** BUILD SUCCEEDED **
```

并已安装:

```bash
xcrun devicectl device install app --device 94918570-0680-5B93-8E38-7E6B355D4426 \
  build/DeviceBuild/Build/Products/Debug-iphoneos/iosApp.app
```

安装成功。自动 launch 失败是因为设备锁屏:

```text
Unable to launch app.amber.ios because the device was not, or could not be, unlocked
```

解锁后手动打开 Amber 即可复测。

## 5. 当前最需要人工复测的点

请在真机上重点测:

1. 进入一个已有长 session 后，立刻上滑查看历史消息。
   - 预期: 不再被拉回底部。
   - 预期: 进入时仍会自动到底部一次。

2. 不发新消息，反复上滑/下滑。
   - 预期: 不出现“刚进会话时滑不动”的吸附感。
   - 预期: 不出现消息错位、reasoning 卡覆盖正文、user bubble 漂到奇怪位置。

3. 发新消息并开始流式生成。
   - 预期: 靠近底部时跟随生成。
   - 预期: 用户主动上滑后暂停跟随。
   - 预期: 滑回底部附近后恢复 live markdown rendering。

4. 有 reasoning 的消息。
   - 预期: reasoning 卡内容高度完整，列表负责滚动，不在卡片内部截断。

5. 有工具/图片/widget 的消息。
   - 预期: 工具详情、图片大图、保存、修改、widget 展开仍指向正确消息，不因 cell 复用串 index。

## 6. 已知风险与后续建议

优先级高:

- 如果用户仍报告进入 session 后被吸住，重点看:
  - `ChatCollectionViewController.update(...)` 是否仍被同一 `ChatCollectionUpdateKey` 反复 apply。
  - `publishViewportStateIfNeeded()` 是否导致 `ChatView` 产生了其它影响 `ChatCollectionUpdateKey` 的变化。
  - `ChatListSummarySnapshot`、title seed、composer 状态是否在进入 session 时频繁变化并传入 list。

- 如果流式过程中滑动仍卡:
  - 先 profile，不要猜。
  - 热点很可能还在 SwiftUI row / markdown parsing / hosting configuration reconfigure。
  - 不要立刻把所有 row 改 UIKit 原生，先只优化最后 assistant row 和 markdown renderer 切换。

- 如果切换 live/frozen 后 markdown 跳变:
  - 检查 `frozenMarkdownSnapshot` 是否来自最新完整文本。
  - 检查 `renderIdentityForRow` 是否过于粗，导致 renderer mode 切换不重建，或过于细导致每 chunk 重建。

优先级中:

- 当前 `ChatCollectionUpdateKey` 对 `displaySetting` / `generativeUiSetting` 使用 `String(describing:)` 做 signature，够用但不够优雅。
  - 若这些类型后续稳定支持 `Equatable` 或专门 signature，可替换。
  - 现在不要为了洁癖扩展大范围设置模型。

- `iosAppExperimentalGPL` 真机签名失败不是代码问题。
  - 若要恢复 experimental GPL 真机安装，需要创建/修复对应 bundle id 的 provisioning profile。

## 7. 下一个 AI 的执行 Prompt

请把下面 prompt 直接给下一个 AI:

```text
你接手仓库 /Users/arquiel/Downloads/AI/amberagent-ios（KMP + 原生 SwiftUI）。全程中文交流，先看真实代码再下结论，不要臆想，不擅自删无关代码。先完整阅读仓库根目录 CODEX_HANDOFF.md 和 CHAT_LIST_HANDOFF.md，再开始工作。

当前任务背景：
- 最近 chat 页面列表已迁到 UICollectionView + ChatLayout，SwiftUI row 用 UIHostingConfiguration 承载。
- 当前工作区有未提交改动，主要在：
  - iosApp/iosApp/ChatCollectionMessageList.swift
  - iosApp/iosApp/ChatMiscViews.swift
  - iosApp/iosApp/ChatView.swift
  - iosApp/iosApp/ChatViewportCoordinator.swift
  - iosApp/iosApp/MessageBubbleView.swift
  - iosApp/iosAppTests/ChatViewportPolicyTests.swift
- 刚修了一个关键 bug：进入已有 session 后列表滑不动、一直被拽到底部；发新消息后又能滑。根因是同一个 conversationLoaded/conversationSwitched 被 viewport state 更新反复重放，触发 scrollToBottom 循环。修复是在 ChatCollectionViewController.update 里加 ChatCollectionUpdateKey，只在真实输入变化时 apply snapshot，scrollToBottomTrigger 仍独立处理。

请你先 review 当前改动，重点看：
1. 逻辑是否闭环：进入 session 自动到底部一次，但用户立刻上滑不再被拉回；发新消息和流式 delta 仍正常更新；显式回到底部仍有效。
2. 调用链路是否有断裂风险：ChatView -> ChatCollectionMessageList -> ChatCollectionViewController -> ChatViewportReducer -> DataSource apply -> row render state。
3. cell 复用是否会导致 action 指向旧 index 或 presentation state 串扰。
4. live/frozen markdown renderer 是否会在离底部、回到底部、willDisplay/didEndDisplaying 时恢复正确。
5. reasoning/tool/image/widget 行的高度 invalidation 是否足够精确，是否会引入跳变或截断。

不要大改，不要过度设计。请优先用最小复现和最小 patch 修真实 bug。

验证要求：
- 至少运行：
  xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
  xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -only-testing:iosAppTests/ChatViewportPolicyTests test
  git diff --check
- 如需真机安装 stable target：
  cd /Users/arquiel/Downloads/AI/amberagent-ios/iosApp
  xcodebuild -project AmberAgent.xcodeproj -scheme iosApp \
    -destination 'platform=iOS,id=00008150-000A594E0AF8401C' \
    -allowProvisioningUpdates DEVELOPMENT_TEAM=89QRFX9548 CODE_SIGN_STYLE=Automatic \
    -derivedDataPath build/DeviceBuild build
  xcrun devicectl device install app --device 94918570-0680-5B93-8E38-7E6B355D4426 \
    build/DeviceBuild/Build/Products/Debug-iphoneos/iosApp.app
- 判断真机构建成败必须看日志里的 ** BUILD SUCCEEDED **，不要只看 echo $?。

注意：
- iosAppExperimentalGPL 当前可能因缺 provisioning profile 失败；这不是代码编译失败。没有明确要求时可用 stable iosApp target。
- 如果要新增 Swift 文件，先 cd iosApp && xcodegen generate。
- 不要碰 codex OAuth 诊断线，除非用户明确切回那个任务。
```

