# Native Chat Timeline Pipeline Plan (2026-07-06)

> 目标：在保留 AmberAgent iOS 现有聊天全部能力的前提下，引入一条 behind-flag 的原生聊天时间线管线，让流式生成的“向上生长”、底部跟随、键盘跟随、历史滑动性能，最终接近 ChatGPT iOS 这类原生体验。
>
> 本文是执行前裁决文档，不是立即替换主路径的实现清单。任何未通过 parity 与真机验收的 Native 路径，都不能切为默认路径。

## 0. 本次审计结论

本计划先经过两条只读审计：

- Hubble：滚动、键盘、状态机、调用链闭环审计。
- Helmholtz：功能 parity、渲染复用、数据投影链路审计。

两个审计给出的共同结论是：

1. 继续在 SwiftUI `ScrollView` 上叠补丁，能缓解局部问题，但很难同时满足“高帧率历史滑动”和“持续流式向上生长动画”。
2. Native 方向值得做，但必须 behind flag，且必须是“替换滚动和高度补偿内核”，不是重写一个缩水聊天页。
3. 最大风险不是 API 不会写，而是链路断裂：消息投影、渲染状态、LOD、分支动作、后台生成、键盘、bottom follow 任何一环丢掉，都会表现成错位、卡顿、偷滚动、按钮状态不一致或功能消失。
4. 旧的 `ChatScrollArbiter` 外壳不能原样复活；可借鉴 reducer、token、防过期命令和收敛校验，但 Native 管线必须自己拥有 `UIScrollView`，并保证单一 offset 写者。

### 0.1 Phase2 复审裁决（2026-07-06）

Phase2 的目标不是上屏，而是建立一条 behind-flag、可审计、无视觉副作用的 mirror 管线。上一轮只读复审指出三个阻塞项：

1. mirror 输入里有 `viewportState` / `scrollToBottomTrigger`，但调用点只覆盖 appear 与 message signal，用户滚动、点向下按钮、输入框 focus 等事件没有同步记录。
2. variant 只记录 `hasMultipleVariants`，缺少 `variantCount` / `selectedIndex`，Phase3 直接接入会断 variant switcher 参数链。
3. diff 使用粗略 render token，没有纳入 `ChatRowDigest` / `ChatRowContentHashCache` 语义，Phase2 绿灯不等于 Phase3 高度缓存和 row reconfigure 语义闭环。

本轮修正：

- `NativeTimelineEntry` 增加 `NativeTimelineVariantInfo(variantCount, selectedIndex)` 与 `renderDigest(ChatRowDigest)`。
- `NativeTimelineProjector` 接入 `viewportState`、display/generative/reasoning 签名和 content hash provider。
- `NativeChatTimelineMirror` 复用 `ChatRowContentHashCache`，流式尾行使用 `streamingTailLayoutToken(for:)`，历史行使用 `contentHash(for:)`，并在 reset/retain 时同步清缓存。
- `ChatView` 在 message signal、appear、向下按钮、composer focus、viewport state 变化时都调用 mirror record；该调用由 `chat.nativeTimeline.mirror.enabled` 保护，默认关闭。
- 误加入的 Phase3 Native render 分支已移除；主路径仍只在 `ChatSwiftUIMessageList` 与 `ChatCollectionMessageList` 之间选择。

验证：

- `ChatMessageProjectionTests`：22 tests，0 failures。

### 0.2 Phase2 二次复审修正（2026-07-06）

二次只读复审继续挡住 Phase3，指出两个 P1：

1. `followGeneration` 是独立 `@AppStorage`，切换时不会主动 record mirror。
2. mirror 的 render state 虽接入 `ChatRowDigest`，但仍只按 `liveRenderingFarFromBottom` 推导 live/frozen，没有复用现有 `ChatRenderStateStore` 的 `markVisible` / `freeze` 生命周期语义。

本轮继续收敛：

- `ChatView` 增加 `followGeneration` 变更监听，只记录 mirror，不写滚动、不改 UI。
- `ChatRenderStateStore` 从 `private` 放为模块内可见；返回 private `ChatLiveTailModel` 的方法收窄为 `fileprivate`，避免扩大无关 API。
- `NativeChatTimelineMirror` 持有同款 `ChatRenderStateStore`，projection 通过该 store 计算 `ChatRenderState`，并在 reset/branch/retain 时同步清理/裁剪。
- 新增测试覆盖 shared render state store 的 freeze lifecycle 会改变 native projection 的 `renderDigest`。

验证：

- `ChatMessageProjectionTests`：23 tests，0 failures。
- `ChatStreamReplayTests`：17 executed，1 skipped，0 failures。

### 0.3 Phase2 生命周期闭环补丁（2026-07-06）

第三轮复审指出：`ChatRenderStateStore` 虽已同源，但 mirror 没有显式接收 `markVisible` / `freeze` 生命周期事件，Phase3 仍缺 Native renderer 的 willDisplay/didEndDisplaying 等价接入点。

本轮继续收敛：

- `NativeChatTimelineMirror` 增加 `markVisible(messageID:)` 与 `freeze(messageID:latestText:)`。
- 这两个方法只更新 mirror 内部的 `ChatRenderStateStore`，不写 UI、不写滚动。
- Phase3 的 Native renderer 必须在 cell/row 可见与离屏时调用这两个入口，不能绕过 render lifecycle。
- 新增 `testNativeTimelineMirrorAcceptsVisibleAndFreezeLifecycleEvents`，验证 mirror 本体接收 freeze 后下一次 projection 的 `renderDigest` 改变，markVisible 后恢复 live digest。

验证：

- `ChatMessageProjectionTests`：24 tests，0 failures。

Phase3 进入门槛：

- 复审必须确认 app/tests 中没有 Native render 上屏残留。
- 复审必须确认 Phase2 mirror 默认关闭时不改变现有视觉和滚动。
- 复审必须确认 viewport、scroll trigger、focus、follow、settings、variant、render digest、cache 这些链路没有 P0/P1 断裂风险。
- 若复审仍给出阻塞项，Phase3 继续暂停。

## 1. 决策边界

### 1.1 必须做

- 新增一条 `NativeChatTimeline` behind-flag 管线。
- 旧 SwiftUI 路径继续作为默认主路径。
- Native 路径复用现有消息投影、渲染状态、动作路由、缓存策略，不复制出一套语义不同的聊天逻辑。
- Native 管线只在达到 feature parity、性能和真机视觉验收后才允许扩大灰度。
- 所有滚动写入集中到一个 `ScrollDriver`，不能再出现多个对象同时写 `contentOffset`。

### 1.2 不能做

- 不能把 Native 做成“只显示文本和 Markdown”的缩水版本。
- 不能为了性能降级屏幕内动画效果。屏幕外内容可以降级；屏幕内只能升级，不能退化。
- 不能复活 `scrollTo(y:)` 或 `scrollTo(edge:)` 作为主路径。该方向已经被真机否证。
- 不能让 `keepContentOffsetAtBottomOnBatchUpdates`、display-link driver、键盘 delayed snap、按钮 scroll、多处 `scrollTo` 同时写滚动位置。
- 不能用 index 路由用户动作。所有 message action 必须继续以 `messageId` 为核心。

### 1.3 非目标

- 不在第一阶段解决所有 Markdown 主线程解析问题。
- 不立即改造 `MessageBubbleView` 的内部渲染结构。
- 不立刻删除旧 SwiftUI/UICollectionView 路径。
- 不改 provider、KMP、消息存储语义。

## 2. 为什么是 Native 管线

这两天的问题本质上是同一类：

- 流式尾行高度不断变化。
- 用户可能正在看底部，也可能滑到历史。
- 键盘可能随时改变 viewport。
- SwiftUI 的 `ScrollPosition`、`defaultScrollAnchor`、`onScrollGeometryChange`、`LazyVStack` 自测高并不是为“每几十毫秒持续增高并保持视觉基线”这个聊天场景提供强确定性的。
- 只要定位形态切换、layout pass 晚到、estimated height 抖动、键盘 inset 变动、用户拖动判定不闭环，就会出现：侵入输入框、整屏跳白、短内容疯狂抖动、底部跟随失效、横向漂移、历史滑动卡顿。

ChatGPT iOS 这类体验更像是：

- 原生滚动容器拥有唯一 `contentOffset`。
- diff、测高、offset 补偿、键盘 inset、用户拖动抢占在同一个控制器闭环内裁决。
- 屏幕内可见内容保持高质量渲染；屏幕外内容使用缓存、快照或降级策略。
- 流式尾行按高度 delta 做连续补偿，而不是依赖“定时跳到底”。

所以最终方向应是原生滚动容器架构，而不是继续把 SwiftUI scroll API 当成底层滚动引擎。

## 3. 必须保留的现有产品契约

### 3.1 聊天内容能力

Native 路径必须保留：

- 用户消息、助手消息、系统/工具相关展示。
- Markdown 渲染。
- 流式 Markdown 渲染。
- reasoning / thinking 胶囊与展开状态。
- 没有 reasoning part 时的 fallback thinking 表现。
- tool call / tool result timeline。
- tool detail sheet。
- URL citation。
- 用户图片附件。
- 生成图片卡片，包括 loading、failure、preview、保存、分享、修改。
- generative widget。
- context compact marker。
- pending assistant 占位。
- 图片识别/处理指示。
- 配置错误卡。
- 空会话欢迎页。
- 底部 spacer / bottom anchor。

### 3.2 消息动作能力

必须保留：

- 复制。
- 用户消息编辑。
- 助手消息重新生成。
- 删除。
- variant 切换。
- 保存助手消息到 Workspace。
- widget prompt 继续生成。
- 图片修改继续生成。
- tool approval 相关操作。

所有动作必须继续走 `ChatListAction`，并以 `messageId` 路由，不允许改成 index 路由。

### 3.3 生成状态能力

必须保留：

- 首 token 前等待状态。
- 流式 delta。
- tool call / tool result 插入。
- tool approval pause。
- cancel。
- failure。
- complete。
- 后台生成回填。
- 当前会话前台生成中不错误消费后台通知。

### 3.4 Composer 与键盘能力

必须保留：

- 输入框。
- focus 后滚到底。
- suggestions chips。
- 图片、相机、文件附件。
- pending approval cards。
- 配置错误入口。
- 模型、思考、context controls。
- 键盘弹出/收起时，底部内容与输入框联动。
- 点击/拖动非输入区域可收键盘。

### 3.5 设置与外观能力

必须保留：

- 字体 scale / chatFont。
- autoCloseThinking。
- generativeUi。
- followGeneration。
- Markdown renderer preferences。
- 主题和 glass 外观。
- 新对话欢迎页和动态装饰。

## 4. 当前代码中的关键事实

这些事实决定了 Native 管线不能随便绕路。

### 4.1 父层 viewport 状态已经参与产品行为

[ChatView.swift](iosApp/iosApp/ChatView.swift:115) 维护 `viewportState` 和 `scrollToBottomTrigger`。向下按钮、focus 滚底、后台内容处理、发送消息等行为都读写它。

风险：Native 如果自己维护一份 viewport 状态，但不回写父层，按钮显示、真实滚动、跟随模式会分裂。

裁决：Native 必须把几何状态 publish 回 `ChatView`，父层仍是 UI 状态展示的消费者；滚动写入则由 Native 的 `ScrollDriver` 独占。

### 4.2 Composer 通过 safeAreaInset 固定到底部

[ChatView.swift](iosApp/iosApp/ChatView.swift:194) 用 `.safeAreaInset(edge: .bottom)` 承载 composer。

风险：Native 如果同时计算 SwiftUI safe area、composer height、keyboard overlap，会出现底部悬空、侵入输入框、或键盘弹起后内容不抬。

裁决：Native 必须定义唯一的 bottom obstruction 模型：

```text
bottomObstruction = composerHeight + keyboardOverlap + transientAccessoryHeight
visibleViewport = scrollView.bounds.height - adjustedInsets.top - bottomObstruction
```

具体实现时必须真机记录 `contentInset.bottom`、`adjustedContentInset.bottom`、composer frame、keyboard frame，确认没有双算。

### 4.3 旧 collection 路径仍用 UIHostingConfiguration

[ChatCollectionMessageList.swift](iosApp/iosApp/ChatCollectionMessageList.swift:1068) 的 cell 内容仍由 SwiftUI hosting 渲染。

风险：只换成 `UICollectionView` 并不自动获得 ChatGPT 级性能。如果每个 delta 仍触发全量 SwiftUI row 重建、Markdown 主线程全量解析，历史滑动仍会卡。

裁决：Native 第一期目标先获得滚动 owner 和 offset 补偿确定性；性能目标必须同步保留 tail fast path、digest/cache、LOD freeze，后续再逐步拆 Markdown 渲染热点。

### 4.4 现有防过期命令很重要

[ChatCollectionMessageList.swift](iosApp/iosApp/ChatCollectionMessageList.swift:1336) 一类逻辑用 `sourceRevision` / `sourceUpdateKey` 防止旧 scroll command 落到新 snapshot。

风险：Native 如果 apply completion 后无 token 写 offset，会把旧会话、旧分支、旧键盘 settle 的命令写到当前视图。

裁决：所有 scroll command 必须带 token：

```text
conversationToken
snapshotToken
generationToken
keyboardToken
layoutPassToken
```

命令执行前必须验证 token 仍匹配。

### 4.5 旧 arbiter 不是可直接复活的答案

[ChatScrollArbiterCore.swift](iosApp/iosApp/ChatScrollArbiterCore.swift:6) 的 core 思路有价值，但 [ChatScrollArbiter.swift](iosApp/iosApp/ChatScrollArbiter.swift:4) 外壳默认关闭，且已有 field testing 失败背景。

裁决：

- 可借鉴：reducer、virtual offset、display link、forward adoption、收敛阈值。
- 不可复活：旧外壳直接接进现有 collection path。
- 新 Native 管线若用 display link，也必须是唯一 offset writer。

## 5. Native 总体架构

目标调用链：

```text
ChatView
  -> NativeChatTimelineView(flag on)
    -> NativeTimelineViewController.update(configuration)
      -> ChatTimelineProjection.build(...)
        -> ChatTimelinePlanner
        -> ChatRenderStateStore
        -> ChatRowDigest / height cache / live tail token
      -> SnapshotApplier.apply(token)
      -> TimelineReducer.handle(snapshotApplied/layoutSettled/geometryChanged)
      -> ScrollDriver.writeOffset(command)
      -> ViewportPublisher.publish(ChatViewportState)
```

### 5.1 单一职责

`ChatView`

- 负责页面结构、composer、顶部栏、sheet、父层 UI 状态。
- 不直接写 Native scroll offset。
- 通过 trigger/request 发出“用户想到底部”的意图。

`NativeChatTimelineView`

- SwiftUI wrapper。
- 接收 messages、signals、settings、actions、viewport callback。
- 不拥有复杂滚动逻辑。

`NativeTimelineViewController`

- 拥有 `UICollectionView` / `UIScrollView`。
- 拥有 snapshot apply。
- 拥有几何采样。
- 把 UIKit delegate 事件转成 reducer input。

`TimelineReducer`

- 纯状态机。
- 接收输入，输出 scroll command 或 render policy。
- 不直接访问 UIKit。

`ScrollDriver`

- 唯一写 `contentOffset` 的对象。
- 所有 snap、follow、keyboard settle、button bottom、initial anchor 都必须经过它。

`TimelineProjection`

- 复用现有 `ChatTimelinePlanner` 和 render model。
- 负责把 UIMessage 树投影为 Native entries。
- 不引入第二套消息语义。

## 6. NativeTimelineEntry 数据模型

Native entry 不应只是 `message + text`。至少需要：

```swift
struct NativeTimelineEntry: Identifiable, Equatable {
    // identity
    var id: String
    var itemId: ChatListItemID
    var messageId: String?
    var messageIndex: Int?
    var nodeIndex: Int?
    var role: ChatRole?
    var isLastMessage: Bool

    // content
    var message: UIMessage?
    var variantInfo: ChatVariantInfo?

    // render
    var rendererKind: ChatRendererKind
    var renderState: ChatMessageRenderState
    var renderIdentity: ChatRenderIdentity
    var renderToken: Int
    var hasEverStreamed: Bool
    var isStreaming: Bool
    var isGenerating: Bool
    var liveMarkdownRenderingEnabled: Bool
    var frozenMarkdownSnapshot: ChatFrozenMarkdownSnapshot?

    // cache and layout
    var contentHash: Int
    var streamingTailToken: Int
    var rowDigest: ChatRowDigest
    var heightSignature: ChatHeightSignature
    var widthBucket: Int
    var measuredHeight: CGFloat?
    var isMonotonicHeightCandidate: Bool

    // decoration
    var decoration: NativeTimelineDecoration

    // environment
    var displaySettingSignature: Int
    var generativeUiSettingSignature: Int
    var reasoningLevelLabel: String?
}
```

实际实现可以拆分类型，但字段语义不能丢。

## 7. 滚动状态机

建议状态：

```text
idle
anchoring(conversationToken)
following(generationToken)
pausedForUser
settlingAfterGeneration(generationToken, stableBottomFrames)
keyboardSettling(keyboardToken)
```

### 7.1 输入事件

必须显式建模：

- `conversationLoaded(token)`
- `conversationSwitched(token)`
- `messageEvent(ChatEvent)`
- `snapshotApplied(snapshotToken)`
- `layoutInvalidated(snapshotToken)`
- `layoutSettled(snapshotToken)`
- `geometryChanged(geometry)`
- `dragBegan`
- `dragEnded`
- `decelerationEnded`
- `keyboardWillChange(keyboardToken, frame, duration, curve)`
- `keyboardDidChange(keyboardToken, frame)`
- `explicitBottomRequest(source)`
- `sceneBackground`
- `backgroundContentLanded(revision)`
- `settingsChanged(signature)`
- `branchChanged(token)`

### 7.2 硬规则

1. 用户拖动优先级最高。
2. `pausedForUser` 下，delta、tool result、generation completed、keyboard settle 都不能偷滚动。
3. 用户发送消息、点向下按钮、输入框 focus 是明确回到底部意图。
4. 短内容未超过 viewport 时，不做强制底锚 offset 写入。
5. 一旦超过一屏临界点，要进入“首屏到历史增长过渡”模式，避免内容先侵入输入框再晚几秒抬上去。
6. `distanceToBottom <= epsilon` 不是唯一成功指标；还必须检查 `offsetX == 0`、无横向裁切、无视觉基线跳变。
7. 所有程序滚动都必须带 reason 和 token。

## 8. 向上生长动画策略

### 8.1 不推荐的方式

不推荐在消息 cell 外层做大范围 `scaleEffect` 或整体 transform：

- 会被 clipping 裁切。
- 会与 Markdown/table/code block 自测高冲突。
- 会让文本边缘、selection、tool sheet anchor 出现错位。

也不推荐用 SwiftUI `withAnimation` 包住每个 delta：

- 每个 delta 频率不稳定。
- 动画堆积会产生一行一行跳或延迟追尾。
- 用户拖动时难以可靠取消。

### 8.2 推荐的 Native 连续补偿

核心不是“给文字加动画”，而是“让 viewport 的底部基线连续”：

```text
oldBottom = contentSize.height - contentOffset.y - visibleHeight
apply snapshot / measure height
heightDelta = newContentSize.height - oldContentSize.height
if followingBottom:
    targetOffsetY = currentOffsetY + heightDelta
    ScrollDriver 在 1-2 帧内连续收敛到 targetOffsetY
```

这会让可见内容像被持续向上托住，而不是每 80ms 跳一次。

### 8.3 屏幕内与屏幕外渲染策略

屏幕内：

- 不能降低 Markdown 质量。
- 不能减少动画质量。
- 不能用粗糙 snapshot 替换可见尾行。
- 尾行必须尽量 live。

屏幕外：

- 可以 freeze Markdown。
- 可以使用 cached height。
- 可以降低 parsing 频率。
- 可以延后复杂 tool card / table 的重测高。

### 8.4 表格特殊处理

用户已观察到：纯文字边界较好，但表格容易往下侵入一行后再抬。

Native 管线需要给 table/code block 这类“晚测高、高度突变”的内容单独策略：

- 高度变化超过阈值时，不能等下一轮普通 follow tick。
- 在 followingBottom 下，同一 layout pass 后立即补偿 height delta。
- 对表格尾部使用 monotonic height：流式期间高度只允许增加，不允许因中间解析状态短暂减少再增加。
- 表格解析未闭合时，可使用 conservative minimum height，避免最后突然撑开。

## 9. 性能策略

### 9.1 第一阶段必须保留的现有优化

- `ChatRowDigest` 的 layout / presentation 区分。
- height cache / width bucket。
- streaming tail cheap token。
- `hasEverStreamed` / renderer memory。
- LOD freeze。
- user-scroll pause。
- `liveMarkdownRenderingEnabled`。
- `frozenMarkdownSnapshot`。
- settings signature 驱动的精确失效。

### 9.2 历史滑动高帧率目标

用户查看历史时：

- 流式尾行仍可后台更新数据，但不能强迫所有历史 row 重建。
- 屏幕外复杂 Markdown 可 freeze。
- 历史可见 row 不应因尾行 delta 重算。
- cell reconfigure 范围应收敛到尾行和必要 decoration。

目标指标：

```text
历史滑动期间：
- 没有明显掉帧。
- delta 到达时，非尾行 visible cells 不出现重建闪动。
- 主线程 Markdown parse 不随每个 delta 全量触发。
- userDragging=true 时 ScrollDriver 不写 offset。
```

### 9.3 后续可拆的热点

在 Native scroll owner 成立后，再逐步处理：

- Markdown parse 移出主线程或增量化。
- table/code block 的 streaming parser 特化。
- SwiftUI hosting row 减少 invalidation。
- 对稳定历史消息用 pre-render/cache。

不要在第一阶段同时做这些大重构，否则定位滚动问题会失真。

## 10. 键盘与输入框闭环

### 10.1 目标行为

- 用户点输入框，无论当前在历史哪里，都代表“我要输入并看到底部上下文”。
- 因此 focus 应先发 explicit bottom intent，再跟随键盘弹起。
- 键盘弹起过程中，底部内容要随 composer 一起抬上来，可以慢一点，但不能没反应。
- 用户在非输入区域点击或拖动，应能收键盘。

### 10.2 Native 处理顺序

```text
composerFocusBegan
  -> explicitBottomRequest(source: composerFocus)
  -> reducer enters anchoring/following
  -> keyboardWillChange
  -> update obstruction model
  -> apply inset/viewport
  -> layoutSettled
  -> ScrollDriver keeps bottom above composer
```

### 10.3 首次进入 session 的特殊风险

用户已多次观察到“第一次不行，第二次可以”。这通常说明首次链路里存在未初始化状态：

- 初始 snapshot 尚未 apply 完。
- estimated height 未收敛。
- viewport geometry 尚未 publish。
- bottom anchor 尚不可见。
- keyboard event 早于第一次 layout settled。
- scroll trigger 被消费一次后没有在 keyboard 期间继续收敛。

Native 必须把首次 focus 视为一个完整事务，不允许只发一次 scroll：

```text
focus transaction = bottom intent + snapshot token + keyboard token + stable layout confirmation
```

事务完成条件：

```text
distanceToBottom <= 2pt
bottom content frame is above composer top
layout stable for at least 2 frames
no user drag began during transaction
```

## 11. 后台生成与会话切换

必须保持当前语义：

- 退出 chat 页面，生成应继续。
- 列表页显示正在生成状态。
- 回到 session 时不闪退。
- 后台内容落地应上屏，但不能偷用户阅读位置。
- 当前前台生成中不能错误消费后台 pending；生成结束后补查。

Native 风险：

- 把 background reload 当成 initial load，导致偷滚动。
- 新 view controller 创建时丢 generation token。
- snapshot completion 引用已销毁 controller 导致 crash。
- 回到 session 时同时执行 initial anchor 和 streaming follow，造成双写。

裁决：

- 后台回流事件必须是独立 input：`backgroundContentLanded(revision)`。
- 它默认只刷新 snapshot/cache，不触发 bottom intent。
- 只有当前 session 处于 explicit follow/generating bottom intent 时，才允许继续跟随。

## 12. 分阶段实施计划

### Phase 0：文档与验收基线

产物：

- 本文档。
- Native flag 名称。
- 日志字段规范。
- parity checklist。

不改主路径。

### Phase 1：抽 shared timeline projection

目标：把 SwiftUI 路径当前已经在用的消息投影、render model、digest/cache 输入抽成共享层。

要求：

- 不改变 UI 行为。
- SwiftUI 旧路径仍消费同一份结果。
- 新增 projection parity tests。

验收：

- `ChatStreamReplayTests` 通过。
- projection 输出 entry 数、id、messageId、decoration、digest 与旧路径一致。

### Phase 2：Native mirror，不上屏

目标：创建 Native pipeline 的数据镜像，但不显示给用户。

要求：

- Native mirror 接收同样 messages/signals/settings。
- 只记录 entries、diff、height signature、scroll command proposal。
- 不写真实 offset。

验收：

- 长会话、流式、分支、后台回填下，mirror 不 crash。
- mirror command proposal 无过期 token。

### Phase 3：Native 静态历史上屏

目标：behind flag 显示非流式历史消息。

要求：

- 复用 `MessageBubbleView` 或等价 wrapper，但参数必须完整。
- 所有 message actions 可用。
- 空页、配置错误、pending、context marker、bottom anchor 可用。

验收：

- 静态会话功能 parity。
- 编辑/重生成/variant/delete/copy/tool detail/image preview 不断。

### Phase 4：接入流式尾行

目标：Native 路径能承载 live streaming tail。

要求：

- `hasEverStreamed` 语义保留。
- live/frozen renderer 策略保留。
- tail cheap token 保留。
- 完成态 renderer 不产生高度跳变。

验收：

- 60+ chunks 连续生成。
- 尾行可见时 live。
- 用户滑远后历史滑动不卡，尾行可冻结。
- 回到底部后恢复 live。

### Phase 5：接入滚动、键盘、底部跟随

目标：Native scroll owner 正式工作。

要求：

- `ScrollDriver` 成为唯一 offset writer。
- reducer 管所有程序滚动。
- 键盘 focus transaction 闭环。
- 用户拖动抢占。
- 首屏到跨屏临界过渡稳定。

验收：

- 首屏短内容不抖。
- 跨屏临界不侵入输入框。
- 表格不会先侵入一行再晚抬。
- `distanceToBottom <= 2pt`。
- `contentOffset.x == 0`。
- 用户上滑后 delta 不拽回。
- 点底部按钮恢复跟随。

### Phase 6：后台、分支、设置全量 parity

目标：补齐真实用户路径。

要求：

- 后台生成不中断。
- 列表生成指示正确。
- 回 session 不 crash。
- branch/settings 不偷滚动。
- 字体/主题/renderer 设置变更正确失效 cache。

验收：

- 退出 session 继续生成，回到 session 正常接续。
- 编辑重发、生成中断只有用户气泡、单消息会话等边界 case 正常。
- 首次进入中段点输入框能到底部并随键盘抬起。

### Phase 7：性能优化与扩大灰度

目标：接近 ChatGPT iOS 的历史滑动性能。

要求：

- profile 主线程热点。
- 针对 Markdown/table/code block 做增量或缓存优化。
- 屏幕外降级，屏幕内不降级。

验收：

- 真机录屏高帧率。
- Instruments 证明主线程 parse/reconfigure 明显下降。
- 长会话流式时历史滑动无明显卡顿。

## 13. 日志与真机验收

Native 日志至少包含：

```text
[AA-NATIVE-SCROLL]
writer
reason
state
conversationToken
snapshotToken
generationToken
keyboardToken
offsetX
offsetY
targetY
contentHeight
visibleHeight
insetTop
insetBottom
keyboardOverlap
composerHeight
distanceToBottom
isTracking
isDragging
isDecelerating
userDragging
followPaused
```

视觉验收必须包含：

- 首屏短内容生成。
- 首屏到跨屏临界。
- 长 Markdown。
- 表格。
- code block。
- reasoning 胶囊长内容。
- 工具调用插入。
- 用户滑历史后回到底部。
- 键盘首次 focus。
- 键盘收起。
- 后台生成回 session。
- 生成中断后只有用户气泡的会话。

## 14. 测试清单

新增或扩展：

- `NativeTimelineProjectionParityTests`
- `NativeTimelineActionRoutingTests`
- `NativeTimelineRenderStateTests`
- `NativeTimelineDigestCacheTests`
- `NativeTimelineHeightCompensationTests`
- `NativeTimelineViewportReducerTests`
- `NativeTimelineKeyboardFocusTests`
- `NativeTimelineBackgroundGenerationTests`
- `NativeTimelineFeatureFlagFallbackTests`

每次触碰滚动/布局生产代码，装机前继续跑：

```bash
xcodebuild ... -only-testing:iosAppTests/ChatStreamReplayTests test
```

## 15. P0/P1 风险表

| 风险 | 等级 | 表现 | 预防 |
| --- | --- | --- | --- |
| 多个 writer 写 offset | P0 | 抖动、回弹、侵入输入框 | `ScrollDriver` 单写者 |
| 键盘 inset 双算 | P0 | 尾部悬空或被键盘盖住 | 单一 obstruction 模型 |
| Native 丢现有功能 | P0 | 两天改动白费 | parity checklist + flag |
| 过期 scroll command | P0 | 切会话后乱跳或 crash | token 校验 |
| 用户拖动被 delta 抢回 | P0 | 看历史被拽回底部 | `pausedForUser` 硬规则 |
| 首次 focus 未初始化 | P1 | 第一次不跳底，第二次正常 | focus transaction |
| 表格晚测高 | P1 | 侵入一行再抬 | 同 pass height delta 补偿 |
| 后台回流误锚 | P1 | 回 session 偷滚动 | background event 不等于 initial load |
| SwiftUI hosting 仍卡 | P1 | Native 滚动仍掉帧 | 保留 LOD/cache，后续 profile |
| 测试覆盖错位 | P1 | 测试绿但真机炸 | 真机日志 + 录屏验收 |

## 16. 推荐的第一步实现切片

下一步不直接写 Native UI。先做最小、可验证、无视觉风险的切片：

1. 新增 `ChatTimelineProjection` 共享投影层。
2. 让现有 SwiftUI 路径仍然使用旧 UI，但可选择读取共享 projection 结果。
3. 新增 projection parity tests。
4. 新增 Native flag，但默认 off，且不接 UI。
5. 为未来 Native mirror 准备 entry 模型和日志结构。

这个切片的价值：

- 不触碰滚动行为，风险低。
- 先证明“数据和功能不会丢”。
- 后续 Native 上屏时，不会出现只剩文本的缩水聊天页。
- 让所有功能 parity 问题在滚动重构前暴露。

## 17. 最终裁决

Native 原生滚动容器是正确的长期方向，但必须按“共享投影 -> mirror -> 静态上屏 -> 流式尾行 -> 滚动键盘 -> 后台分支 -> 性能扩大”的顺序推进。

任何试图跳过共享投影和 parity，直接把一个新 `UICollectionView` 接进聊天页的方案，都应视为高风险方案。

任何试图继续在 SwiftUI scroll API 上寻找 `scrollBy`、`scrollTo(y:)`、`edge preserving` 魔法开关的方案，都不应再进入主路径。

下一步执行建议：从 Phase 1 开始，不改视觉、不改滚动，只抽共享投影和测试。

## 18. Phase 1 起步记录

本次已落下 Phase 1 的第一块低风险地基：

- 在 `ChatMessageProjection.swift` 增加 `NativeTimelineEntry` / `NativeTimelineProjection` / `NativeTimelineProjector`。
- Native projection 只镜像现有 `ChatTimelinePlanner`，不创造第二套消息语义。
- 增加测试确保 Native projection 保留 item identity、pending assistant、bottom anchor、streaming renderer memory。

## 19. Phase 2 / Phase 3 起步记录

Phase 2 已完成一条默认关闭的 mirror 管线：

- `chat.nativeTimeline.mirror.enabled` 默认关闭。
- mirror 接收 messages/signals/settings/follow/viewport/scroll trigger/variant/render digest/cache/lifecycle。
- mirror 不写 UI、不写真实 scroll offset。
- `NativeChatTimelineMirror.markVisible` / `freeze` 是 Phase3+ renderer lifecycle 的唯一入口。

Phase 3 已开始一个默认关闭的静态上屏切片：

- flag：`chat.nativeTimeline.staticRender.enabled`，默认关闭。
- 只在 `NativeChatTimelineStaticRenderEligibility` 允许时启用；active streaming 或 `assistantStreamDelta` 一律回落旧路径。
- `NativeChatTimelineView` 复用 `NativeTimelineProjector` 输出，完整传递 `MessageBubbleView` 所需的 display/generative/reasoning/variant/action 参数。
- `NativeChatTimelineView` 已补齐实验分支内的显式到底触发消费与 `ChatViewportState` 发布，避免向下按钮/LOD/viewport 链路在 flag-on 静态路径中断裂。
- Subagent 复审指出的 4 个 P1 已收敛：完成态 renderer memory、LOD 消费、用户消息上屏 transition、键盘/viewport 收缩后的静态重锚。
- 当前阶段不接管 streaming tail、键盘跟随、生产级 bottom-follow 或生产级 scroll reducer；这些仍是 Phase4/Phase5 范围。
- 注意：flag-on 的 `NativeChatTimelineView` 使用一个临时静态 `ScrollView` 承载历史内容，因此它会拥有实验分支内的基础滚动容器；但它只做静态 viewport/显式到底闭环，不执行 streaming delta 补偿、生产级键盘跟随或 Native scroll driver。不要用 Phase3 flag-on 的滚动表现判断最终 Native scroll owner 质量。

验证：

- `ChatMessageProjectionTests`：28 tests，0 failures。
- `ChatStreamReplayTests`：17 executed，1 skipped，0 failures。

这一步只接入默认关闭的静态 Native UI 切片；默认主路径未改变。

## 20. Phase 4 起步记录

Phase 4 已进入一个更窄的默认关闭实验切片：只让 Native 路径在明确打开流式尾行 flag 时承载 live streaming tail，不接管生产级滚动。

- 新增 flag：`chat.nativeTimeline.streamingTail.enabled`，默认关闭。
- streaming tail 必须同时满足 `chat.nativeTimeline.staticRender.enabled == true` 与 streaming tail flag；否则仍回落旧 SwiftUI 主路径。
- `NativeChatTimelineStreamingTailEligibility` 只允许两类场景：
  - active streaming / `assistantStreamDelta` 且尾行是 assistant。
  - loading 后只剩用户尾行，用于覆盖“用户刚发送、assistant 尚未出现”的安全过渡。
- Native projection 保留 `hasEverStreamed`、`streamingAssistantMarkdown` renderer identity、streaming tail cheap token、`ChatRenderStateStore` 的 live/frozen 生命周期。
- Subagent 复审指出的 P1 已收敛：Native UI 不再二次推导 live/frozen，而是由 `NativeTimelineProjector` 把 `ChatRenderStateStore` 产出的 `ChatRenderState` 带到 `NativeTimelineEntry`，再由 `NativeChatTimelineView` 直接传给 `MessageBubbleView`。
- `NativeChatTimelineView` 继续消费 LOD、viewport state、explicit bottom trigger、用户消息 transition 与 renderer memory。
- 本阶段仍不实现生产级 `ScrollDriver`、键盘 focus transaction、底部跟随 reducer、后台回流滚动策略；这些仍属于 Phase 5/6。也就是说 Phase4 验证的是“流式尾行能被 Native 管线完整承载”，不是“Native 滚动体验已经可替换主路径”。

风险裁决：

- 不允许因为 streaming tail flag 打开而默认改变现有用户路径。
- 不允许引入第二套 Markdown/消息动作语义；所有 row 参数仍从 `NativeTimelineProjector` 与现有 `MessageBubbleView` 链路进入。
- 如果 Phase4 flag-on 视觉出现滚动问题，应优先判定为“临时 SwiftUI ScrollView 承载 live tail 的限制”，不能据此修改默认主路径，也不能把补丁堆到生产滚动链路上。

验证：

- `ChatMessageProjectionTests`：32 tests，0 failures。
- `ChatStreamReplayTests`：17 executed，1 skipped，0 failures。

这一步只接入默认关闭的 streaming tail Native UI 切片；默认主路径未改变。

## 21. Phase 5 接线与复审记录

Phase 5 已从“纯内核骨架”推进到 behind-flag UI 接线：`NativeTimelineScrollCore` 仍保持纯 reducer，`NativeTimelineScrollDriver` 负责把 reducer action 落到真实 `UIScrollView`，`NativeChatTimelineView` 在 `chat.nativeTimeline.scrollDriver.enabled` 开启时把底部 intent、stream growth、键盘通知、layout metrics 与用户拖拽接入 driver。默认主路径仍不改变，flag 默认关闭。

已接入：

- `chat.nativeTimeline.scrollDriver.enabled`：默认关闭。
- `NativeTimelineScrollCore`：纯函数裁决 `(state, intent, geometry) -> (state, actions)`。
- `NativeTimelineScrollDriver`：唯一执行 `setContentOffset`、`CADisplayLink` frame driver、键盘 notification 采样、layout convergence、用户拖拽取消程序动画。
- `NativeChatTimelineView`：flag-on 时通过 `ChatSwiftUIScrollViewResolver` attach 底层 `UIScrollView`，并把以下事件转为 native scroll intent：
  - explicit bottom：底部按钮、composer focus、viewport shrink。
  - stream content growth：流式内容增长。
  - keyboard will change / layout settled：键盘 focus transaction。
  - user drag began / ended：用户抢占与恢复。
  - conversation reset：会话边界清理。

Subagent 复审结论：

- 两个 subagent 均未发现影响默认主路径的 P0；默认路径仍由 flag 隔离。
- 复审抓到的 P1 已收敛：
  - flag-on 时移除 SwiftUI `ScrollPosition` / `defaultScrollAnchor` 对同一 scroll view 的参与，避免 SwiftUI position 与 driver 双 writer。
  - `layoutSettled` 改为 token 化；driver-owned convergence 才能完成 keyboard focus transaction，resolver metrics 回调不再递增 generation、不再让旧 layout 自我完成。
  - convergence 不再由 reducer action 与 driver fallback 双边重入调度；not-at-bottom 的 layout settled 只重置稳定帧，由 driver 单一路径继续收敛。
  - `updateObstruction` 接入 driver 状态，`sampleGeometry()` 不再固定传 0。
  - 用户拖拽开始时取消已发出的 UIKit programmatic motion，保证 `pausedForUser` 不被动画继续拖回。
  - settled following 停止 frame driver 但保留 bottom ownership；后续 stream delta 会重新启动 frame driver，避免 0.3s idle 后流式增长断链。
  - `branchChanged` 不再强制 explicit bottom，避免编辑/后台回流期间抢用户历史视口。
- 二轮复审追加抓到的 P1 也已收敛：
  - composer focus 允许穿透 active drag / deceleration guard，避免用户在惯性滚动中点击输入框时 bottom intent 被吞。
  - driver attach 后会 replay 初始 bottom intent，避免 `onAppear` trigger 为 0 或 conversation event 早于 resolver attach 时首屏底锚断链。
  - keyboard focus convergence 增加 tokened stable layout pass；到达底部但仍处于 keyboardFocus 时不会提前停止，避免卡在 `stableFrames == 1`。
  - obstruction 不再只是记录字段，`keyboardOverlap + composerHeight` 参与 effective bottom inset、bottom target 与 distance 计算。
- 三轮复审继续抓到的 P1 已收敛：
  - `onMetricsChanged -> layoutSettled(nil)` 不再只服务 keyboard transaction；当 state 已经是 `followingBottom` 时，layout/markdown/table 晚测高会刷新 bottom target 并重启 frame driver。
  - 该补偿只在 `followingBottom` 生效，`pausedForUser` / 历史阅读位置不会因为 layout settled 被重新拽到底部。

当前仍明确不做：

- 不打开 Native scroll driver flag 给默认主路径。
- 不把 Phase5 视为真机可灰度完成；必须先过真机录屏与 `[AA-FOLLOW]` / offsetX / keyboard timeline 证据。
- 不在 Phase5 内扩大 Markdown renderer 架构改造。

验证：

- `NativeTimelineScrollCoreTests`：18 tests，0 failures。
- `ChatMessageProjectionTests`：32 tests，0 failures。
- `ChatStreamReplayTests`：17 executed，1 skipped，0 failures。

下一步进入真机验证前必须再次确认：

- flag-on 时 `ScrollPosition` 没有任何 programmatic writer 残留。
- composer focus、keyboard frame、layout settled、user drag 的 token/generation 日志闭环。
- stream delta 高于一帧频率时 frame driver 不断链、不重入、不横向写 offset。
- 真机录屏指标必须包含：底部基线连续、offsetX 恒 0、用户上滑不被拽回、键盘首次 focus 从历史中可立即到底部并随键盘抬起。

## 22. Phase 6 灰度回退壳与复审记录

Phase 6 已完成 Native scroll driver 的自动回退壳，目标不是默认启用，而是让 flag-on 实验具备“发现不可信运行时状态后立即退出 Native writer”的边界。默认主路径仍不改变。

已接入：

- 新增 `NativeTimelineScrollFallbackReason`：
  - `horizontalOffsetDrift`：检测到 `contentOffset.x` 非零漂移。
  - `nonFiniteOffset`：即将写入非有限纵向 offset。
  - `bottomConvergenceExhausted`：多轮底部收敛仍未到位。
- `NativeTimelineScrollDriver` fallback 后会：
  - 递增 `generation`，让旧 convergence task 全部失效。
  - `cancelProgrammaticMotion()`，停止 UIKit 动画残留。
  - `stopFrameDriver()`，停止 `CADisplayLink`。
  - 清空 `scrollView`，阻止继续写 offset。
  - 保留 `fallbackReason` 闸门，阻止旧 resolver async closure 重新 attach。
- `NativeChatTimelineView` fallback 后会：
  - 设置 `nativeScrollFallbackReason`，使 `isNativeScrollDriverActive == false`。
  - 停止安装 `ChatSwiftUIScrollViewResolver`。
  - 恢复 SwiftUI `.scrollPosition` + `.defaultScrollAnchor(.bottom)` fallback path。
  - 只有当 driver 当前拥有 bottom（`followingBottom` / `keyboardFocus`）且用户没有拖拽/减速时，才 replay 一次 SwiftUI bottom intent；历史浏览/用户交互中不 replay，避免把用户拽回底部。

Subagent 复审结论：

- 未发现 P0/P1/P2 阻塞问题，允许进入 Phase 7。
- 已确认 fallback replay、旧 resolver reattach、UIKit animation cancellation、pending convergence task、SwiftUI fallback path、用户交互中不 replay 这几条调用链闭环。
- 复审提出的两个补测已补齐：
  - `keyboardFocus` fallback 必须 replay bottom。
  - 用户正在交互时即使 native driver 处于 bottom ownership，也不能 replay bottom。

验证：

- `NativeTimelineScrollCoreTests`：23 tests，0 failures。
- `ChatStreamReplayTests`：17 executed，1 skipped，0 failures。

风险裁决：

- Phase 6 只提供灰度保护，不证明 Native driver 已可替换默认路径。
- 真机验证时必须重点看 fallback 日志：`[AA-NATIVE-SCROLL] fallback...` 与 `[AA-NATIVE-SCROLL] fallbackActivated...`。
- 若触发 fallback，正确行为是“当前 Native 实验退出，SwiftUI fallback 接管”，而不是继续补丁式修 native offset。

## 23. Phase 7 性能与动效切片记录

Phase 7 的第一刀落在 collection path 的流式尾行更新，不走 FLIP、不做缩放、不改 Markdown 视觉效果，避免再次产生裁切、重叠和“屏内视觉降级”。目标是减少每个 provider chunk 对布局系统的压力，同时保留屏内最新内容的完整渲染。

已接入：

- `ChatLiveTailModel` 继续作为流式尾行的活体数据入口，避免每个 delta 都重建整条 snapshot。
- live tail delta 命中时不再立即执行 `invalidateLayoutForItemIDs + layoutIfNeeded + follow command`。
- 新增 `CADisplayLink` 合并器：
  - 多个 provider chunk 落在同一显示帧时，只做一次 layout invalidation。
  - 多个 follow / viewport completion 合并为最新一次，避免旧 delta 的滚动命令追上来。
  - display link 使用 `.common` run loop mode，滚动交互期间仍能 flush。
  - view dismantle 时停止 display link 并清空 pending state，避免退出页面后继续回调。
- 旧的 `ChatLiveTailLayoutInvalidationBridge` 已移除；live tail layout invalidation 不再存在逐 token 即时旁路。
- display link target 使用弱捕获 wrapper，避免 `CADisplayLink -> target -> controller -> CADisplayLink` retain cycle。

这一步刻意不做：

- 不降低屏内 Markdown/表格/code block 的渲染质量。
- 不给消息气泡做 FLIP/scale 之类容易被 cell 边界裁切的视觉补丁。
- 不开启 Native scroll driver 默认 flag。
- 不修改后台生成、分支、设置 parity 的语义。

预期收益：

- 模型输出频率高于屏幕刷新率时，布局和 bottom follow 写入被压到“每帧至多一次”，不再随 chunk 数线性放大。
- 历史滑动时主线程 layout storm 会减少，和既有 LOD/frozen snapshot 形成互补。
- 内容往上生长仍由真实 collection layout 和滚动 offset 承担，不再靠屏内元素缩放/位移动画伪装。

验证：

- `ChatStreamReplayTests`：17 executed，1 skipped，0 failures。
- `NativeTimelineScrollCoreTests`：23 tests，0 failures。
- `ChatRowContentHashCacheTests`：4 tests，0 failures。
- `ChatStreamReplayTests` synthetic 指标：`frameP95=16.67ms`，作为本轮合并器切掉即时 bridge 旁路后的初步正向信号；真机仍需用 Instruments/录屏确认。

Subagent 复审结论：

- 第一轮复审抓到一个 P1：旧 `ChatLiveTailLayoutInvalidationBridge` 仍在逐 token 直接 invalidation，绕过 display-link 合并器。已删除该 bridge。
- 第一轮复审抓到一个 P2：display link target 可能与 controller 形成 retain cycle。已改为弱捕获 target wrapper。
- 二轮复审未发现 P0/P1，认可 Phase 7 第一刀。
- 剩余 P2：如果存在绕过 `UIViewControllerRepresentable.dismantleUIViewController` 的异常嵌入路径，run loop 可能短暂保留 no-op display link；正常 App 生命周期由 `prepareForDismantle()` 清理，不阻塞。

后续 Phase 7 仍可继续推进的方向：

- 对 `ChatLiveTailModel` 的 `objectWillChange` 做同帧合并，进一步减少 SwiftUI/Markdown redraw 频率；前提是实机确认不会影响屏内实时感。
- 给 live tail layout 合并器加轻量诊断计数，真机比对 chunk 数、layout flush 数、frame drops。
- 用 Instruments / ETTrace 验证 Markdown parse、SwiftUI diff、ChatLayout invalidation 三者的真实占比，再决定是否进入 renderer 级缓存或后台 parse。

## 24. Phase 1-8 收尾裁决

全链路复审修正：Phase 1-4 可以收尾；Phase 5-6 Native scroll driver 在独立复审中发现一个 P1 竞态，需要一个很窄的 Phase 8 hardening 后才能进入真机灰度验证。

Phase 8 hardening 已接入：

- 修复 `NativeTimelineScrollDriver.scheduleBottomConvergence` 的 pending convergence 竞态：
  - `layoutSettled(token:)` 可能在同一 async pass 内把 reducer 状态切到 `.pausedForUser`。
  - 旧实现随后只看 `distanceToBottom`，仍可能继续 `writeBottomTarget(animated: false)`，把刚开始拖拽的用户拽回底部。
  - 新增 `canContinueBottomConvergence(generationToken:layoutToken:in:)` 闸门，要求 async task 仍新鲜、未 fallback、scroll view 未 tracking/dragging/decelerating，且 reducer 状态仍拥有 bottom ownership（`.followingBottom` 或匹配 layout token 的 `.keyboardFocus`）。
- 新增测试覆盖“bottom convergence 已排队，用户随后开始拖拽”的竞态，确保 pending async pass 不再写回底部。
- 独立复审继续抓到一个 P1：`keyboardFocus` 期间的 `streamContentGrew` 会递增 generation，但不应替换 keyboard focus transaction token；若只用单一 token，async convergence 会被闸门错杀。
- 已将 convergence token 拆成两层：
  - `generationToken`：只判断 async task 是否仍新鲜，任何新 intent 都可以废掉旧 task。
  - `layoutToken`：只用于完成当前 keyboard focus transaction，`streamGrowth` 期间沿用原 keyboard token。
- 新增测试覆盖“keyboardFocus + streamGrowth”组合，确保 Native 实验路径不会只做一次 immediate bottom write 后断掉后续 convergence。

收尾判断：

- Native mirror / static render / streaming tail / scroll driver 均仍 behind flag。
- Native scroll driver 默认不接管用户主路径。
- Native 实验路径具备进入、运行、fallback、回到 SwiftUI fallback path 的完整链路。
- Phase 6 fallback 壳已覆盖横向漂移、非有限 offset、底部收敛耗尽三类风险，并阻止旧 resolver 继续写 offset。
- Phase 8 进一步补上 pending convergence 与用户交互之间的状态闸门，避免 async convergence 在 reducer 已暂停后继续写底部。
- Phase 7 已收掉 live tail 逐 token layout invalidation 旁路；collection path 的流式尾行布局合并为每显示帧最多一次。
- 当前测试门禁通过：
  - `ChatStreamReplayTests`：17 executed，1 skipped，0 failures。
  - `NativeTimelineScrollCoreTests`：25 tests，0 failures。
  - `ChatRowContentHashCacheTests`：4 tests，0 failures。

必须明确的口径：

- Phase 7 优化的是 `ChatCollectionMessageList` collection path。
- 当前 `ChatSwiftUIMessageListFeatureFlags` 默认仍是 clean-list SwiftUI path；因此不能把 Phase 7 描述成“所有默认用户路径已获得该性能收益”。
- 若要让 Phase 7 成为默认用户体验的一部分，需要单独裁决当前默认 path 选择，或通过灰度/真机验证把 collection path 切回默认；这不属于本轮 Phase 1-8 收尾范围。

剩余非阻塞风险：

- `ChatLiveTailLayoutDisplayLinkTarget` 已避免 controller retain cycle；但如果存在绕过 `UIViewControllerRepresentable.dismantleUIViewController` 的非标准嵌入路径，run loop 仍可能短暂保留 no-op display link + target。正常 App 生命周期由 `prepareForDismantle()` 清理，不阻塞收尾。
- Phase 7 的 synthetic `frameP95` 只是测试环境诊断信号，本轮多次门禁观测到 `16.67ms` 与 `35.43ms`；它不等于真机长期性能结论，也不能替代 Instruments / 录屏验证。
- Native scroll driver 仍未获得真机灰度验收，不能切默认。

下一步是真机验证 / 灰度验证：

- flag off：确认当前用户主路径无回归。
- `chat.nativeTimeline.staticRender.enabled` on：确认静态 Native 上屏 parity。
- `chat.nativeTimeline.streamingTail.enabled` on：确认流式尾行 parity 与 LOD/freeze。
- `chat.nativeTimeline.scrollDriver.enabled` on：确认 Native scroll driver 的底部基线、键盘 focus、历史拖拽保护、offsetX 恒 0、fallback 率。
- 记录 `[AA-FOLLOW]`、`[AA-NATIVE-SCROLL] fallback...`、录屏、frame/drop、offsetX。

只有真机验证发现明确的新能力缺口时，才开后续 Phase。后续 Phase 必须由真机证据驱动，而不是继续泛泛改架构。

## 25. Fable5 review P1 修复记录

Fable5 对 Phase 1-8 的整体 review 结论采纳：无 P0，但有两处 P1 必须在收尾前修掉。两处都不是“大方向推翻”，而是调用链边界上的漏闸门。

已修 P1-1：Native fallback 不再通过 SwiftUI `if/else` 分支切换 `.scrollPosition` 修饰符。

- 旧问题：`nativeTimelineScrollPosition` 在 native driver active 时返回 `self`，fallback 后返回 `self.scrollPosition(...).defaultScrollAnchor(...)`，SwiftUI 会把它视为 `_ConditionalContent` 结构切换，可能重建 ScrollView。这样即使 driver 层判断 `shouldReplayBottom=false`，视图重建本身仍可能按 initial bottom anchor 把用户拖回底部。
- 新实现：`.scrollPosition($scrollPosition)` 与 `defaultScrollAnchor` 无条件挂载；native driver active 时仍由 UIKit driver 写 offset，SwiftUI 绑定只作为 fallback writer 存在。
- fallback replay 改为由 `nativeScrollFallbackReason` 的 `onChange` 触发；只有 driver 明确返回 `shouldReplayBottom=true` 时才补一次 bottom replay。用户交互中的 fallback 不 replay。
- Newton 复审继续抓到一条 P1：fallback 后仍停留在 `NativeChatTimelineView`，但后续 `assistantStreamDelta` 因 driver inactive 被 `submitNativeScrollIntent` 直接丢弃，SwiftUI fallback 没有接管 live follow。已补 `submitNativeSwiftUIFallbackScrollIntent`：fallback 状态下，若仍在底部且未 paused/未用户拖拽，后续 stream delta / finish 类事件继续用 SwiftUI `scrollPosition` 跟底。
- Newton 复审还抓到 replay 的 `Task.yield` 窄窗口：fallback 当刻允许 replay，但用户立刻开始拖拽时仍可能被补滚拉回。已补 `nativeScrollFallbackReplayToken`，fallback 后新手势会废掉 pending replay。
- 局限：这是结构性修复，单元测试无法直接证明 SwiftUI view identity 不重建；必须保留真机/录屏验证项。

已修 P1-2：默认 clean-list SwiftUI 路径的 conversation bottom anchor retry 加入用户手势闸门。

- 旧问题：`scheduleConversationBottomAnchor()` 的 50/100/200/350ms retry 只检查 task/token，不检查 `userScrollActive` / `followPaused`。用户进会话后立刻上滑时，pending retry 仍可能把人拽回底部。
- 新实现：`.tracking/.interacting` 立即 `cancelPendingConversationScroll()`；retry 循环每次写前还必须通过 `ChatViewportPolicy.canRunConversationBottomAnchorRetry(userScrollActive:followPaused:)`。
- 新增策略测试：`testConversationBottomAnchorRetryStopsDuringUserScrollOrPause`，覆盖“无手势允许 retry / 用户滚动禁止 retry / paused 禁止 retry”。

Fable5 第二轮增量复审结论：上述两条 P1 主链路闭合，无新 P0/P1；追加发现两处 P2 残留，已同步修掉。

已修 P2-1：fallback 不再强行清空 `nativeUserScrollActive`。

- 旧问题：用户贴底拖拽中触发 native fallback 时，driver 会正确给出 `shouldReplayBottom=false`，但宿主 `handleNativeScrollFallback` 把 `nativeUserScrollActive` 强制置 `false`。如果随后有 `assistantStreamDelta`，SwiftUI fallback writer 会误以为当前没有用户手势，可能在手指未离屏时继续写 bottom offset。
- 新实现：`handleNativeScrollFallback` 只记录 fallback reason、replay token 与 replay intent，不再接管手势状态。`nativeUserScrollActive` 只由 scroll phase handler 维护；fallback delta 跟随仍经过 `canRunNativeSwiftUIFallbackBottomFollow` 与 `requestNativeSwiftUIFallbackBottom` 的双重 `!nativeUserScrollActive` 闸门。

已修 P2-2：fallback 模式下会话进入/切换补齐归位与 viewport reset。

- 旧问题：native fallback 后仍停留在同一个 `NativeChatTimelineView` 实例中，`nativeScrollFallbackReason` 是 `@State`，可能跨会话残留；而 fallback 分支对 `conversationLoaded/conversationSwitched` 直接 `break`，新会话会沿用旧 offset/fallback viewport。
- 新实现：fallback 分支对 `conversationLoaded/conversationSwitched` 调用 `resetNativeSwiftUIFallbackViewportForConversationEntry()`，清理 paused/dragging/show-button/LOD 状态并废掉 pending replay，再无动画请求一次底部锚定。`branchChanged/settingsRefreshed` 仍保持不主动抢视口。

本轮验证：

- 合并门禁：`NativeTimelineScrollCoreTests` + `ChatViewportPolicyTests` + `ChatStreamReplayTests` 共 71 executed，1 skipped，0 failures。
- `ChatViewportPolicyTests`：29 tests，0 failures。
- `NativeTimelineScrollCoreTests`：25 tests，0 failures。
- `ChatStreamReplayTests`：17 executed，1 skipped，0 failures。
- `git diff --check`：无问题。

仍需真机验证：

- native fallback 在用户拖拽历史时触发，不得落底。
- native fallback 在用户贴底拖拽中触发后，后续 stream delta 不得在手指未离屏时写底部。
- native fallback 后切换/进入新会话，应无动画落到底部且不沿用旧会话 offset。
- fallback 后横向 offset 必须保持 0，不得出现文字越界或整块横移。
- 默认 clean-list 路径：进会话后 350ms 内立刻上滑，不得被锚定 retry 拽回底部。
- 键盘弹起、底部按钮、流式生成三条链路仍需看 `[AA-FOLLOW]` / `[AA-NATIVE-SCROLL]` 日志与录屏。
