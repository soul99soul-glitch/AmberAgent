# iOS ChatView 流式滚动修复方案

> 文件:`iosApp/iosApp/ChatView.swift`
> 目标:修复流式生成时的聊天滚动行为,使其同时满足锚定、历史可读、用户可中断跟随三类需求。
> 部署目标:iOS 26.0(`iosApp/project.yml`),可直接使用 iOS 18+ 的 `onScrollGeometryChange` / `onScrollPhaseChange`。

---

## 1. 需求

### 视觉/锚定
- **R1**:流式生成时,正在增长的助手回复底部稳定锚定在输入框顶部附近,不被底部 composer / 输入框遮住,也不滚到额外空白区导致视觉硬切。
- **R2**:往上滑动查看历史消息时,内容不在画面中间被截断,正常显示流动到屏幕边缘。

### 交互
- **R3**:流式生成过程中,触控屏幕往下滑动时能正常查看历史消息,不会被持续拽回底部强制更新。当用户滑回底部时,自动跟随应恢复。

### 显式约束(不得重新引入此前失败方案)
- 不新增单独的 `bottomFollowAnchorID`。
- 不用 `GeometryReader + PreferenceKey` 动态测量 **inputBar 高度**。
- 不监听 `showsComposerMeta` / `isInputFocused` 反复强制滚动。
- 不让输入栏高度变化参与滚动 state 循环。

> 说明:本方案使用的 `onScrollGeometryChange` 读取的是**滚动几何**(content size / visible rect),不是被禁的「测 inputBar 高度」。inputBar 高度始终不参与滚动 state,约束不违反。

---

## 2. 现状代码定位

`iosApp/iosApp/ChatView.swift`:

- 消息列表:`ScrollViewReader { proxy in ScrollView { LazyVStack { ForEach ... } } }`(约 183–236 行)。
- 自动跟随:`onChange(of: viewModel.messageRevision)` 内 `withAnimation { proxy.scrollTo(lastId, anchor: .bottom) }`(227–234 行)。
- 每条消息 `.id(message.id)` 只挂在 `MessageBubbleView` 上(219 行)。
- inputBar 通过 `.safeAreaInset(edge: .bottom, spacing: 0)` 插入(57–59 行)。
- 布局常量:`ChatLayout.contentHorizontalInset = 22`(1194 行)。
- 焦点状态:`@FocusState private var isInputFocused`(22 行);跟随开关:`@AppStorage(...) followGeneration`(25 行)。
- 生成态:`viewModel.isGenerationActive`(已用于 217 行)。

---

## 3. 关键原理

### 3.1 锚定(R1):`scrollTo(.bottom)` + 固定避让区

`proxy.scrollTo(id, anchor: .bottom)` 把 **id 所标 view 的底边**对齐到 ScrollView **viewport 底**。因为 inputBar 用 `safeAreaInset(edge: .bottom)` 插入,会收缩 viewport,使 viewport 底正好落在 inputBar 顶。

把 `.id(message.id)` 从 `MessageBubbleView` 移到一个 **`VStack { 气泡 + 固定高度避让 Spacer }`** 的 wrapper 上后,scrollTo 对齐的是「消息 + 避让区」的底边 → 消息正文底部稳定停在 inputBar 顶部上方一个固定间距处。

- 避让区用 `Color.clear.frame(height:)`,不用 `Spacer`(LazyVStack 中 `Spacer` 高度推断不稳)。
- 避让区高度是**恒定常量**,刻意不随 inputBar / 键盘高度变化 → 与滚动 state 完全解耦,杜绝反馈循环。
- 避让区**仅在「最后一条 + 正在生成」时插入**,避免短会话空闲态被 `anchor:.bottom` 顶出顶部留白。

### 3.2 用户可中断跟随(R3):区分「内容增长位移」与「用户手指位移」

最直觉的「只有在底部才跟随」会**自毁**:每来一个 chunk,contentSize 变大 → 旧底不再是底 → 此刻判「不在底」→ 跳过跟随 → 跟随永久失效。

正确做法是只让**用户主动拖动离开底部**才暂停跟随,内容自己长高永不暂停:

- `onScrollPhaseChange`:判断当前是否「用户手指主导」(`userDragging`)。
- `onScrollGeometryChange`:判断是否「回到底部」(atBottom)。
- 仅当 `userDragging == true` 时,才用 atBottom 去切换 `followPaused`。
- 跟随逻辑加 `!followPaused` 门控。

由此:内容增长(`userDragging == false`)永远不改 `followPaused`,跟随不自毁;用户上滑(`userDragging == true` 且离底)→ `followPaused = true` 停滚;用户滑回底 → `followPaused = false` 自动恢复。

### 3.3 历史可读不截断(R2)

`followPaused == true` 时**完全不再程序化滚动**,内容按手势自然流动到屏幕边缘,无强制位移、无中途截断。

---

## 4. 完整实现(仅改 `ChatView.swift`)

### 4.1 新增状态

与其它 `@State` 放在一起:

```swift
@State private var followPaused = false      // 用户上滑离开底部 → true,跟随暂停
@State private var userDragging = false      // 当前滚动是否由用户手指主导
```

### 4.2 布局常量(`enum ChatLayout`,约 1193 行)

```swift
enum ChatLayout {
    static let contentHorizontalInset: CGFloat = 22
    static let userMaxWidth: CGFloat = 300

    /// 流式跟随时,末条正文底部与输入框顶部之间的固定避让间距。
    /// 恒定值,刻意不随 inputBar / 键盘高度变化 —— 避免滚动反馈循环。
    static let followBottomGap: CGFloat = 96

    /// 距底多少点以内算「在底部」(R3 复位灵敏度)。真机可在 32–48 间微调。
    static let bottomStickThreshold: CGFloat = 40
}
```

### 4.3 末条消息包裹避让区(替换 207–220 行的 `ForEach`)

```swift
ForEach(Array(viewModel.messages.enumerated()), id: \.element.id) { index, message in
    let isLast = index == viewModel.messages.count - 1
    VStack(spacing: 0) {
        MessageBubbleView(
            message: message,
            messageIndex: index,
            variantInfo: viewModel.variantInfo(atMessageIndex: index),
            displaySetting: sharedSettings.displaySetting,
            onRegenerate: { viewModel.regenerate(atMessageIndex: index) },
            onEdit: { newText in viewModel.editMessage(atMessageIndex: index, newText: newText) },
            onDelete: { viewModel.deleteMessage(atMessageIndex: index) },
            onSelectVariant: { variantIndex in viewModel.selectVariant(messageIndex: index, variantIndex: variantIndex) },
            isGenerating: viewModel.isGenerationActive
        )
        // 仅「最后一条 + 正在生成」插入避让区。固定高度 Color.clear,而非 Spacer。
        if isLast && viewModel.isGenerationActive {
            Color.clear.frame(height: ChatLayout.followBottomGap)
        }
    }
    .id(message.id)   // .id 移到 wrapper 上:scrollTo 对齐「消息 + 避让区」底边
}
```

### 4.4 ScrollView 观察 + 改造跟随(替换 `messageList` 中 ScrollView 的修饰链)

在 `ScrollView { ... }` 后挂以下修饰符,并替换原 `onChange(of: messageRevision)`:

```swift
// A. 区分「用户拖动」与「内容增长」
.onScrollPhaseChange { _, phase in
    switch phase {
    case .interacting:
        userDragging = true            // 仅用户手指接触时置真
    case .idle:
        userDragging = false
    default:
        break                          // decelerating/animating(momentum)沿用既有值
    }
}
// B. 仅用户主导滚动时,按是否回到底部切换 followPaused;
//    内容自动增长(userDragging == false)永不改 followPaused → 跟随不自毁
.onScrollGeometryChange(for: Bool.self) { geo in
    geo.contentSize.height - geo.visibleRect.maxY <= ChatLayout.bottomStickThreshold
} action: { _, atBottom in
    if userDragging {
        followPaused = !atBottom
    }
}
// C. 跟随:暂停时彻底不滚(R3),也就不会在画面中间截断历史(R2)
.onChange(of: viewModel.messageRevision) { _, _ in
    guard followGeneration, !followPaused, !viewModel.messages.isEmpty else { return }
    guard let lastId = viewModel.messages.last?.id else { return }
    withAnimation { proxy.scrollTo(lastId, anchor: .bottom) }
}
// D. 键盘:聚焦后单次延迟补滚;用户正在看历史(followPaused)则不打扰
.onChange(of: isInputFocused) { _, focused in
    guard focused, !followPaused, !viewModel.messages.isEmpty else { return }
    guard let lastId = viewModel.messages.last?.id else { return }
    Task { @MainActor in
        try? await Task.sleep(for: .milliseconds(300))   // 略大于键盘动画 ~250ms
        withAnimation { proxy.scrollTo(lastId, anchor: .bottom) }
    }
}
```

> 修饰符 A–D 挂在 `ScrollView` 上,且整体仍包在原 `ScrollViewReader { proxy in ... }` 内(D 需要 `proxy`)。

### 4.5 复位跟随意图

`followPaused` 是会话级意图,不可跨轮残留。在以下两处各加一行 `followPaused = false`:

- **用户点发送**(inputBar 的 send action 内):新一轮生成应重新贴底跟随。
- **会话重载 / 切换**(`reloadFromStore()` 调用点附近,约 94 / 104 行)。

```swift
followPaused = false   // 新一轮生成 / 新会话,重新贴底跟随
```

---

## 5. 需求对账

| 需求 | 满足方式 |
|---|---|
| **R1** 内容不掉到输入框下 | `safeAreaInset` 使 viewport 底 = inputBar 顶;`scrollTo(.bottom)` 对齐到「末条 + 96 避让区」底,正文稳定停在输入框上方固定间距处。 |
| **R2** 上滑看历史不中途截断 | 暂停跟随后(C 的 `!followPaused`)完全不再程序化滚动,内容按手势自然流动到屏幕边缘。 |
| **R3** 生成中下滑看历史、不被拽回 | A 把「用户手指上滑」识别为 `userDragging`,B 据此置 `followPaused = true`,C 随即停滚;chunk 再多也不动。用户滑回底部 → B 检测 atBottom → `followPaused = false`,跟随自动恢复。 |

约束符合性:无新增 `bottomFollowAnchorID`;不测 inputBar 高度;不监听 `showsComposerMeta` 强滚;输入栏高度不进入滚动 state。

---

## 6. 潜在风险与调参

1. **momentum 复位语义**:轻甩到底时序为 `interacting → decelerating → idle`。A 仅在 `interacting` 置 `userDragging = true` 且 decelerating 不清零,故甩动惯性到底时 B 仍能复位 `followPaused = false`。若实测甩到底未复位,把 B 的判定放宽为「`userDragging || phase 非 idle` 时允许复位」。
2. **`bottomStickThreshold` 取值**:太小→到底却不复位;太大→稍微上滑仍跟随。建议真机在 32–48 间调。仅影响复位灵敏度,不影响稳定性。
3. **`visibleRect.maxY` 与避让区**:生成中 contentSize 含 96 避让区,跟随到底时 `contentSize - visibleRect.maxY ≈ 0` 判在底,符合预期;生成结束避让区消失、contentSize 缩 96,因 `userDragging == false`,B 不会误触发暂停。
4. **生成结束瞬间下沉 96pt**:避让区随 `isGenerationActive` 变 false 而移除,刚完成的消息下移一个 gap。一般可接受(回归自然底部);若刺眼,可把该状态切换包进 `withAnimation`,或保留避让区至下次发送前。
5. **`followBottomGap = 96` 取值**:纯视觉常量,按真机微调(建议先试 80–110),不影响逻辑稳定性。
6. **「内容滚到玻璃栏之下贴真实屏幕边」**:若 R2 期望的是内容在半透明 glass inputBar/topBar 之下若隐若现,需让 ScrollView 内容延伸进 safe area(`ignoresSafeArea` + content inset 补偿),这会改变 viewport 底参照并破坏 R1 的 anchor 数学,**不能与当前 anchor 混用**,需单独权衡。本方案默认保持 `safeAreaInset` 不变。
7. **API 兼容**:`onScrollPhaseChange` / `onScrollGeometryChange` 为 iOS 18+。当前部署目标 26.0 无问题;若未来降级支持,这两处需回退到 sentinel-view 方案。

---

## 7. 改动清单(Checklist)

- [ ] 新增 `@State followPaused` / `@State userDragging`。
- [ ] `ChatLayout` 增加 `followBottomGap` / `bottomStickThreshold`。
- [ ] `ForEach` 末条包裹 `VStack { 气泡 + 条件避让区 }`,`.id` 上移到 wrapper。
- [ ] ScrollView 挂 A/B 两个 `onScroll*` 观察器。
- [ ] 改造 C(`messageRevision` 跟随,加 `!followPaused`)。
- [ ] 新增 D(键盘聚焦单次补滚)。
- [ ] send / reload 两处复位 `followPaused = false`。
- [ ] 真机验证 R1/R2/R3,并按需微调两个阈值常量。
