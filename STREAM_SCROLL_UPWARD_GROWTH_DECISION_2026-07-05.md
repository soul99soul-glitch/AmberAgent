# 流式上移丝滑 + 历史查看性能 —— 裁决文档

> 日期: 2026-07-05 · 分支: `feat/ios-provider-parity-claude`
> 定位: 承接 handoff「流式滚动/底部跟随/动画与性能优化」，在**动手改代码之前**给出经过 SDK 与代码双重核实的判断、裁决与最小方案。
> 本文只做判断与方案，不含任何代码改动、不 commit/push/stash/reset/checkout。

> ⚠️ **2026-07-05 晚重大更新：§1-§6 的主方案 A（`scrollTo(y:)` 几何补偿）已被真机实测否证并回滚。**
> §1-§8 保留为决策过程存档；**当前有效裁决见 §9（真机否证与重裁决）**，行号以 §7（已刷新）为准。

---

## 0. 先摆事实（判断的地基）

判断与裁决必须建立在证据上，而不是体感。以下事实均已就地核实（SDK swiftinterface + 仓库源码）。

### 0.1 SDK 事实（本机 iOS 26.5 SDK）

`ScrollPosition`（`SwiftUICore`，iOS 18+ 起，本机 SDK 为 iOS 26.5）：

- `scrollTo(id:anchor:)`、`scrollTo(edge:)`、`scrollTo(point:)`、`scrollTo(x:y:)`、`scrollTo(x:)`、`scrollTo(y:)`
  —— **全部是绝对定位，没有 `scrollBy` / 相对偏移方法。**
- 可读属性：`isPositionedByUser`（可读写）、`edge`、`point`、`viewID`；
  **iOS 26 新增 `x` / `y` getter**（可读"解析后"的当前偏移）。

`ScrollGeometry`（`onScrollGeometryChange` 在滚动几何变化时提供，非 `CADisplayLink` 每帧 tick 语义）：

- 含 `contentOffset: CGPoint`、`contentSize: CGSize`、`containerSize`、`visibleRect`、`contentInsets`
  —— **当前偏移与内容高度在几何变化（含流式增长引起的 `contentSize` 变化）时可读。**

`ScrollAnchorRole` 三值：`.initialOffset`、`.alignment`、`.sizeChanges`

- `.sizeChanges` = "内容尺寸变化时保持指定边缘不动"的**原生边缘保持锚**（`.defaultScrollAnchor(.bottom, for: .sizeChanges)`）。

**由此得到的第一结论**：原生没有"相对滚动"一键方法，但
`onScrollGeometryChange` 读 `contentOffset` + `contentSize`、配 `scrollTo(y:)` 写绝对 Y
= **纯 SwiftUI 就能合成等价于 `scrollBy` 的相对增量补偿，无需回退 UIKit。**

### 0.2 代码现状事实

- **活跃路径**：`ChatView.swift:574` 按 `ChatSwiftUIMessageListFeatureFlags.isEnabled`（默认 `true`）
  选 SwiftUI clean-list（`ChatSwiftUIMessageList`）；否则回落 UICollectionView（`ChatCollectionMessageList`）。当前用户反馈都是针对 SwiftUI 路径。
- **丝滑瓶颈定位**：`ChatCollectionMessageList.swift:480` `scheduleStreamBottomFollow()`（80ms Task 节流）
  → `scrollToBottomAnchor()`（`:511`）→ `scrollPosition.scrollTo(id: bottomAnchorID, anchor: .bottom)`。
  即"每 80ms 一次**绝对语义重锚**"。步进感来源：token 连续增长，但校正每 80ms 才发生一次，
  两拍之间底部漂出视口、下一拍被整截拽回 = 视觉上"一截截抬"。**根因是"定时离散校正"，不是"绝对 vs 相对"。**
- **`.sizeChanges` 是被作者刻意避开的**（`ChatCollectionMessageList.swift:182-188` 注释）：理由为"布局帧与滚动帧互相抢锚"，
  且明确"不随 followMode 动态切换 default anchor"（避免 paused→following 把用户位置拽回底部）。
- **短内容门槛必须保留**：`streamFollowContentCanScroll`（`:554`，`contentHeight > visibleHeight`）已压住第一屏抖动。
- **bridge 脚手架已存在但未接线**：`ChatSwiftUIScrollViewResolver`（`:602-701`）——通过隐藏 `UIView` 找到底层
  `UIScrollView`，用 `CADisplayLink` 观察 `contentOffset` / `contentSize` / `isDragging`。**全仓库无引用**，
  即为"最小 UIKit bridge"预留、尚未启用。
- **另有一套完整 UIKit 仲裁器 `ChatScrollArbiter`**（`setContentOffset(animated:false)` + `CADisplayLink` + `followTail()`），
  但它绑在**默认关闭**的 UICollectionView 路径，且实测暴露 follow 错位 / re-entry 乱跳 / 卡住
  （`ChatScrollArbiter.swift:7-16` feature flag 默认 OFF 的注释即证据）。
- **方向二的 LOD 已与"可见不降级"对齐，但存在双份实现**：活跃 SwiftUI 路径的判定**内联**在
  `onScrollGeometryChange` action 里（`ChatCollectionMessageList.swift:260-261`，`distance > 阈值 && (followPaused || userDragging)`），
  经 `publish`（`:562`）→ `ChatView.swift:591` 下发，消费点为 `liveMarkdownRenderingEnabled`（`:524-527`）；
  `ChatViewportCoordinator.swift:345-346` 的同款收紧属 `ChatViewportReducer`，**只被 flag-OFF 的 UICollectionView
  控制器调用**（`ChatCollectionMessageList.swift:961/1019/1293`）。方向二一切核实以活跃链路为准；
  双份政策实现违背单一所有者，只标记、不顺手合并。

---

## 1. 计划（执行总览）

分两阶段，方向一优先，全程遵守 handoff 禁令清单与"可见动画不降级只升级"约束。

**阶段一 · 让往上生长更丝滑（最小 patch，纯 SwiftUI，不引 UIKit）**

1. 保留短内容门槛与三态机用户保护（`followingBottom && !userScrollActive && contentScrollable`）。
2. 把底部跟随的**触发源**从 80ms Task 节流改为**几何增长事件驱动**：在 `onScrollGeometryChange` 的 action 里，
   当处于跟随、非用户滚动、内容可滚且 `contentHeight` 相比上帧增长时，用**同帧几何的相对增量**补到底部：
   `targetY = geometry.contentOffset.y + max(0, geometry.contentSize.height - geometry.visibleRect.maxY)`
   （`scrollTo(y: targetY)`，无动画）。相对增量形式对 insets / safe area / 负 offset 口径天然自洽，
   `max(0, …)` 避免与底部 bounce 对抗。实现前提：扩展 `ChatSwiftUIScrollGeometry`（`:213-217`）捕获
   `contentOffset.y`，并真机核实 `scrollTo(y:)` 与 `ScrollGeometry.contentOffset` 坐标口径一致。
   事件驱动到点、每次 delta 仅一行级别 = 视觉连续。
3. 用"目标已到位则不写"（`|current - target| ≤ ε` 跳过）打断 `scrollTo → geometry → scrollTo` 自反馈回路。
4. 语义归位（会话入场 / 用户发消息 / 点击向下按钮）继续走绝对 `scrollTo(id:anchor:)`——一次性到位更稳。
5. 跑 `ChatStreamReplayTests` → 装机前说明改了什么 → 真机看 `[AA-FOLLOW]`。

**阶段二 · 生成中查看历史的帧率与开销（先量后调）**

1. **先核实活跃链路本身**：SwiftUI clean-list 路径的 `onScrollGeometryChange` action（`ChatCollectionMessageList.swift:212-267`）
   → `publish(ChatViewportState)`（`:559-563`）→ `ChatView.swift:591` 是否正确驱动 `liveRenderingFarFromBottom`，
   消费点为 `liveMarkdownRenderingEnabled`（`:524-527`）。**不要**对着 `ChatViewportCoordinator` 的 reducer 调优——那是 flag-OFF 路径。
2. 加轻量 instrumentation（见 §6 风险缓解与 handoff E 项），不盲调。
3. 真机复现"生成中上滑看历史"，用运行时证据定位瓶颈（parse / 布局 / 跟随写 offset）。
4. 只对**确定不可见**的尾部启用 freeze / parse throttle / delta coalescing；沿用活跃路径现有收紧逻辑。
5. 回到底部（主动滑回 or 点向下按钮）一次性清 `followPaused/userDragging/liveRenderingFarFromBottom/showScrollToBottom`
   并恢复 live 渲染——现有 resume 分支与 `onChange(scrollToBottomTrigger)` 已做，验证可靠即可。

---

## 2. 判断

**问题一（能否用 SwiftUI 原生能力替代 80ms 绝对 `scrollTo(bottomAnchor)`）：能，且不必回退 UIKit。**

- 原生 `ScrollPosition` **没有**现成的"相对滚动 / offset 补偿"方法，所有 `scrollTo` 都是绝对定位。
- 但**纯 SwiftUI 已具备做相对增量补偿的全部原料**：`onScrollGeometryChange` 在几何变化时给 `contentOffset` 与 `contentSize`
  （流式增长恰以 `contentSize` 变化触发回调，校正天然与增长同步），
  `scrollTo(y:)` 写绝对 Y —— 二者合起来等价于 `scrollBy`。
- 真正的病灶是"每 80ms 一次离散校正"这个**触发机制**，不是"绝对 vs 相对"这个 API 形态。
  把触发从定时器改成"内容增长事件"，逐帧到点补偿，步进感即从正向机制消除。

**关于 `.sizeChanges` 原生底锚：理论最优，但不能做主机制。**

- 它是 SwiftUI 内部在同一布局 pass 里保持底边不动，帧级连续、零程序滚动——正是 handoff 设想的"edge preserving API"。
- 但它是**静态、无条件**修饰符，无法按三态机逐帧关停。用户上滑看历史时它仍会保持底边 → 把人拽回底部，
  直接违反 handoff 硬约束「用户查看历史不被拉回底部」；而"抢锚"隐患也是代码作者已识别的真实风险。**排除作主机制。**

---

## 3. 裁决

> ⚠️ 本节主方案已被真机否证，当前有效裁决见 §9。

- **方向一主方案**：用**纯 SwiftUI 几何驱动的到底基线补偿**替换 80ms 绝对语义重锚。
  不引入 UIKit bridge、不启用 `.sizeChanges`、不动 UICollectionView 路径。
- **方向一后备**：仅当纯 SwiftUI 实测仍不够丝滑（自反馈无法根除或帧预算不足）时，才**接线现成的 `ChatSwiftUIScrollViewResolver`**，
  做无动画 `setContentOffset` 补偿（最小 bridge，只读写底层 scrollView 的 offset/size/触摸态）。
  **不**启用整套 `ChatScrollArbiter`、**不**回 UICollectionView。
- **方向二**：不改机制，**先量后调**。LOD/freeze 只作用于确定不可见的尾部，回底立即恢复——与"可见不降级"天然一致，现有逻辑已大体到位，重在验证与补 instrumentation。

---

## 4. 想法（候选方案对照）

| 方案 | 机制 | 结论 |
|---|---|---|
| A. 几何驱动到点补偿（纯 SwiftUI） | 在 `onScrollGeometryChange` 里按内容增长即时 `scrollTo(y: 底部目标)`，无动画 | ~~选为主方案~~ **已被真机否证（见 §9）**：横向漂移、文字越界、底部失稳 |
| B. `.defaultScrollAnchor(.bottom, for: .sizeChanges)` | 交给 SwiftUI 内部保持底边不动 | 理论最优但不可条件化 → 会拽回看历史的用户，**排除** |
| C. 最小 UIKit bridge（`ChatSwiftUIScrollViewResolver`） | 拿到底层 `UIScrollView`，`setContentOffset(animated:false)` 逐帧补偿 | **仅作后备**：更可控但引入 UIKit 依赖与祖先解析脆弱性 |
| D. 完整 `ChatScrollArbiter` / 回 UICollectionView | displaylink offset 仲裁器整套 | 实测有 follow 错位/乱跳/卡住，默认 OFF，**排除** |
| E. 给 `scrollTo` 包动画硬糊丝滑 | `withAnimation { scrollTo }` | handoff 明令禁止（侵入 composer/跳白/抢锚），**排除** |

---

## 5. 思考（关键权衡）

- **步进 = 采样率问题，不是 API 形态问题。** 内容以布局帧速率连续增长，而校正以 80ms（约 12Hz）离散发生，
  两者错拍就产生"截跳"。把校正提到与增长同频（几何事件），delta 变小、视觉即连续。这是从正向机制修，而非补丁叠加。
- **为什么不用最优的 `.sizeChanges`。** 它最丝滑，但把"是否跟随"的控制权交给了 SwiftUI，而本项目的核心契约恰恰是
  "用户上滑后在主动回底前绝不跟随"。控制权不能交出去，所以宁可自己按几何事件到点，也不用不可关停的原生锚。
- **纯 SwiftUI 优先于 bridge。** bridge（`ChatSwiftUIScrollViewResolver`）依赖"隐藏 UIView 找 UIScrollView 祖先"，
  是 SwiftUI 版本升级会失配的实现细节。能不引 UIKit 就不引，符合"最小实现 / 单一所有者 / 不过度工程"。
- **方向二先量后调是守则要求。** 读码只产生嫌疑，运行时证据才能定罪。没有 frame p95 / parse 耗时 / recompute 次数的
  真机数据前，任何 freeze/throttle 都是猜。且活跃路径的 LOD 已被 `(followPaused || userDragging)` 收紧
  （`ChatCollectionMessageList.swift:260-261`），未必需要新机制。
- **replay 绿 ≠ 真机安全。** `ChatStreamReplayTests` 只喂纯文本 delta，测不到卡片插入/思考块收起等结构性流式更新
  （见记忆 `ios-stream-replay-blind-spot`）。门禁跑通只是必要条件，真机 agent 流验证才是充分条件。

---

## 6. 方案说明

### 6.1 最终处理方案

**方向一（主）**：在 `ChatSwiftUIMessageList` 内，把 `scheduleStreamBottomFollow()` 的 80ms 定时绝对重锚，
改为在 `onScrollGeometryChange` action 中做**几何驱动的到底基线补偿**——
跟随态 + 非用户滚动 + 内容可滚 + `contentHeight` 增长时，用同帧几何的相对增量
`scrollTo(y: geometry.contentOffset.y + max(0, geometry.contentSize.height - geometry.visibleRect.maxY))`（无动画），
并以"目标已到位阈值"跳过冗余写入。语义归位路径保持绝对 `scrollTo(id:anchor:)` 不变。

**方向一（后备）**：纯 SwiftUI 若实测不达标，接线 `ChatSwiftUIScrollViewResolver`，改为在底层 `UIScrollView` 上做无动画 offset 补偿。

**方向二**：先补 instrumentation → 真机复现 → 只冻结确定不可见尾部 → 回底立即恢复 live 渲染。

**门禁**：任何触碰滚动/布局的改动，装机前先跑 `ChatStreamReplayTests`（当前基线 17 tests / 1 skipped / 0 failures），
装机前说明改了什么，真机看 `[AA-FOLLOW]` 的 `mode/distance/userScrollActive/isAtBottom/isContentScrollable/liveRenderingFarFromBottom`。

### 6.2 实施原因

- 步进感的根因是"定时离散校正"，把触发改成几何增长事件才是从正向机制消除，而非给 `scrollTo` 包动画硬糊（禁令）。
- 纯 SwiftUI 就够，不引入新 UIKit 依赖 = 最小改动、最小回归面，符合"不过度工程 / 单一所有者"。
- `.sizeChanges` 虽最优但不可条件化，会违反"看历史不被拽回"硬约束，直接排除以免引入更难定位的抢锚问题。
- 方向二"先量后调"是工作守则（读码生嫌疑、运行时证据定罪）；现有 LOD 收紧逻辑已与"可见不降级"对齐，无需推倒重来。

### 6.3 方案优势

- **视觉连续**：逐帧到点补偿看起来是从 composer 上方基线连续生长，而非整截抬。
- **边界不退化**：底部真值统一为同帧几何的 `distanceToBottom = contentSize.height - visibleRect.maxY` 归零
  （即 `targetY = contentOffset.y + distanceToBottom`），硬边界不松。
- **零新依赖 / 最小回归面**：不碰 `.sizeChanges`、不碰 UICollectionView、不引 UIKit bridge（除非后备）。
- **完全兼容禁令与三态机**：短内容门槛、用户保护、语义归位路径全部保留。
- **可见不降级**：方向一只改跟随触发，不动可见 markdown/reasoning/tool 动画；方向二只降不可见尾部。

### 6.4 潜在风险与缓解

1. **自反馈回路**：几何驱动补偿可能 `scrollTo → geometry → scrollTo` 抖。
   → 缓解：目标已到位阈值 skip；只在 `contentHeight` 增长时补；沿用 `transaction.animation = nil`。
2. **与 SwiftUI 内部布局帧抢锚**：逐帧写 offset 可能与 relayout 竞争 → 抖白/回跳（正是当初避开 `.sizeChanges` 的原因）。
   → 缓解：先纯 SwiftUI 小步试 + 真机 + `ChatStreamReplayTests`；不行再退最小 bridge（无动画 `setContentOffset` 更可控）。
3. **短内容/跨屏临界**：越过一屏瞬间易复发第一屏抖动或内容侵入 composer。
   → 缓解：短内容门槛必须先于任何补偿判断；跨屏首次补偿仍走"绝对到点"。
4. **replay 盲区**：`ChatStreamReplayTests` 只喂纯文本 delta，测不到结构性流式（卡片插入/思考块收起），sim 绿 ≠ 真机安全。
   → 缓解：真机 agent 流验证，含工具卡片 + reasoning 展开/收起 + 生成中上滑回底。
5. **方向二盲调**：不加 instrumentation 直接调 freeze 会摞补丁。
   → 缓解：先量后调，真机证据定罪。
6. **后备 bridge 引入面**：`ChatSwiftUIScrollViewResolver` 依赖"隐藏 UIView 找 UIScrollView 祖先"，SwiftUI 版本升级可能失配。
   → 缓解：仅在纯 SwiftUI 确证不可行时启用，并对祖先解析失败加降级回退。
7. **Insets 口径错位**：补偿目标 `y` 若与 `contentInsets`（composer 高度 / 键盘 / 安全区）取值口径不一致，会产生固定偏差——表现为尾部始终压住或悬空于输入框。现有几何链路以 `visibleRect` 为口径（`distanceToBottom = contentSize.height - visibleRect.maxY`，`ChatCollectionMessageList.swift:214`）。
   → 缓解：补偿目标只从 `onScrollGeometryChange` 同一帧的 `ScrollGeometry` 推导（`visibleRect` / `contentInsets` 自洽），禁止混用外部测量的 composer 高度；到位判定与目标推导用同一组值。

---

## 7. 关键文件与行号索引（2026-07-05 晚回滚后已刷新）

- 活跃路径切换：`iosApp/iosApp/ChatView.swift:574`
- 跟随触发（80ms 现行机制）：`iosApp/iosApp/ChatCollectionMessageList.swift:477`（`scheduleStreamBottomFollow`）、`:505`（`scrollToBottomAnchor`）
- 几何回调：`ChatCollectionMessageList.swift:212`（`onScrollGeometryChange`，几何结构体 `:95`）
- 短内容门槛：`ChatCollectionMessageList.swift:548`（`streamFollowContentCanScroll`）
- 刻意不设 `.sizeChanges` 的注释：`ChatCollectionMessageList.swift:184`
- 未接线的最小 bridge 脚手架：`ChatCollectionMessageList.swift:596`（`ChatSwiftUIScrollViewResolver`）
- LOD 收紧（可见不降级）：活跃 SwiftUI 路径 `ChatCollectionMessageList.swift:260-261`（消费点 `:518`，publish `:553`）；
  flag-OFF UICollectionView 路径 reducer `iosApp/iosApp/ChatViewportCoordinator.swift:345-346`（调用点 `:955/:1013/:1287`）
- 注：§0-§8 正文中的行号系实验前快照，回滚后个别漂移 3-7 行，一律以本节为准。
- 完整 UIKit 仲裁器（默认 OFF，排除）：`iosApp/iosApp/ChatScrollArbiter.swift:7-16`
- 门禁测试：`iosApp/iosAppTests/ChatStreamReplayTests.swift`

---

## 8. 复核记录

**2026-07-05 · 第二会话独立复核：本文全部关键事实成立，裁决维持不变。**

- SDK（本机 iOS 26.5 swiftinterface，`SwiftUICore` + `SwiftUI`）：
  `ScrollPosition` 仅有绝对 `scrollTo(id:/edge:/point:/x:y:/x:/y:)`，无 `scrollBy` / 相对偏移方法；
  `isPositionedByUser` 可读写，`edge` / `point` 可读，`x` / `y` getter 确为 iOS 26.0 新增；
  `ScrollAnchorRole` 三值 `.initialOffset` / `.sizeChanges` / `.alignment` 均在。
- 代码：`scheduleStreamBottomFollow` 80ms `Task.sleep` 节流 + 四重守卫（`ChatCollectionMessageList.swift:480-499`）；
  `scrollToBottomAnchor` 绝对语义重锚、事务禁动画（`:511-522`）；
  短内容门槛 `contentHeight > visibleHeight`（`:554-557`）；
  刻意不设 `.sizeChanges` 的注释（`:182-188`）；
  `ChatSwiftUIScrollViewResolver` 全仓库零引用（`:602`，确为未接线脚手架）；
  LOD 收紧 `&& (followPaused || userDragging)`（`ChatViewportCoordinator.swift:345-346`，注释明确写了防 `renderIdentity` 销毁重建丢失逐词淡入）；
  clean-list flag 默认 `true`（`ChatCollectionMessageList.swift:71-76`，`ChatView.swift:574` 分支）。
- 复核新增：风险 7（Insets 口径错位）——补偿目标必须与 `visibleRect` 口径同帧自洽，见 §6.4。

**2026-07-05 · Codex 外部评审裁定（第二会话处理，三条全部采纳）**

1. **必须改 1（公式前后不一致）成立**：§1 / §6.1 / §6.3 原 `y = contentSize.height - visibleHeight` 已统一替换为
   同帧相对增量形式 `targetY = geometry.contentOffset.y + max(0, geometry.contentSize.height - geometry.visibleRect.maxY)`。
   补充两点实现注意：需扩展 `ChatSwiftUIScrollGeometry`（`ChatCollectionMessageList.swift:213-217`，现只存
   `distanceToBottom/visibleHeight/contentHeight`）捕获 `contentOffset.y`；需真机核实 `scrollTo(y:)` 与
   `ScrollGeometry.contentOffset` 的坐标口径一致（insets 处理可能不同源），到位判定与目标推导用同一组同帧值。
2. **必须改 2（方向二定位）成立，且经代码核实比评审所述更具体**：`ChatViewportReducer.reduce/reduceGeometry`
   仅被 flag-OFF 的 UICollectionView 控制器调用（`ChatCollectionMessageList.swift:961/1019/1293`）；活跃 SwiftUI 路径的
   LOD 判定内联在 `onScrollGeometryChange` action（`:260-261`）并经 `publish`（`:562`）→ `ChatView.swift:591` 下发，
   语义与 reducer 同款收紧。§0.2 / §1 阶段二 / §7 已改为以活跃链路为准；双份政策实现（单一所有者违背）已标记、不顺手合并。
3. **可选微调成立**：`onScrollGeometryChange` 措辞由「每帧」改为「几何变化时」。对方案无削弱——流式增长恰好以
   `contentSize` 变化触发回调，校正天然与增长同步；但实现者不得假设它有 `CADisplayLink` 每帧 tick 语义。

---

## 9. 真机否证与重裁决（2026-07-05 晚 · 当前有效裁决）

### 9.1 事实：主方案 A 已被真机否证并回滚

**实施内容**（按裁决落地，实现无偏差）：`ChatSwiftUIScrollGeometry` 增加 `contentOffsetY`；
删除 80ms `scheduleStreamBottomFollow`；新增 `compensateStreamFollowIfNeeded`，在几何增长事件里执行
`scrollTo(y: contentOffsetY + distanceToBottom)`（禁动画事务，含跟随态/非用户滚动/短内容门槛/增长阈值/到位阈值全套守卫）。

**指标全绿**：`ChatStreamReplayTests` 17 executed / 1 skipped / 0 failures；`[AA-FOLLOW]` 日志
`mode=following`、`distance=0.0` 稳定，pause/resume 正常。

**真机视觉失败**（用户截图 + 肉眼）：
- 流式内容块**周期性向左偏移再回弹**，部分文字越过屏幕左边缘被裁切；
- 底部边界失稳：输入框上方时而大片空白、时而内容下沉——比 80ms 方案更差；
- 往上生长的丝滑度无改善；生成中查看历史的性能体感无改善（方向二本就未实施，不算否证）。

**已止血**（本会话已复核确认）：`compensateStreamFollowIfNeeded` / `contentOffsetY` / `scrollTo(y:` 全仓库零残留；
`scheduleStreamBottomFollow`（`:477`）恢复 80ms 语义重锚（仅补了 `Task.isCancelled` 守卫，语义不变）；
门禁复跑 17/1/0；已装回真机。

### 9.2 判断修正（推翻 §2 的部分结论）

- **被否证的核心假设**：「`ScrollPosition.scrollTo(y:)` ≈ UIKit `contentOffset.y += delta` 的纯纵向写」。
  真机证明它是**点定位语义**——会参与 SwiftUI 的 scroll positioning / 布局求解，扰动 LazyVStack 测量、
  Markdown 子树宽度与横向对齐。公式在数学上自洽，但 API 性质假设未经验证就上了主路径，这是本次事故的根因。
- **失败机理最可信解释**：每次几何事件都在 `id 定位`（语义重锚遗留）与 `point 定位`（`scrollTo(y:)`）之间**切换定位形态**，
  迫使 SwiftUI 反复对未稳定布局重新求解位置——横向漂移与底部失稳都是求解抖动的表象。
  （此为嫌疑级解释；定罪级结论只有一条：**该 API 在本结构里不可用作 `scrollBy`。**）
- **升级为铁律**：
  1. `ScrollPosition` 的任何定位形态切换（id ↔ edge ↔ point）都必须当成**布局事件**对待，不是 offset 写；
  2. `distance→0` 只是必要条件；成功指标必须含**横向稳定**（`contentOffset.x`、无越界裁切）与**视觉基线连续**（录屏）；
  3. 未经真机 spike 验证的 API 性质假设，不得直接替换主路径——先隔离验证，再接线。
- 门禁 + 纵向指标的盲区第三次得到确认（呼应 §5「replay 绿 ≠ 真机安全」，本次连 `[AA-FOLLOW]` 纵向日志也绿）。

### 9.3 重裁决：新优先级（回应反馈问题 1/3）

**P0 · 现状即底线**：80ms `scrollTo(id: bottomAnchor, anchor: .bottom)` 是**唯一被真机验证稳定**的机制，
边界可用性优先于丝滑度。任何候选未通过全套验证前，主路径不动。

**候选 A' · `scrollTo(edge: .bottom)` 持久边缘钉住——先做隔离 spike，绝不直接接线**
`ScrollPosition` 持有的是语义位置（id / edge / point）。假设：edge 位置可能由 SwiftUI 在布局 pass 内
随内容增长持续保持（等价 `.sizeChanges` 的连续性），但**可按三态机设置/撤销**（进 following 设一次 edge，
pause 即撤），且用户滚动会翻转 `isPositionedByUser` 自动打断——若成立，即为 handoff 寻找的
「可关停的原生 edge preserving」。**这是假设，不是结论**——上一轮就死在未验证的 API 假设上。
验证协议（先隔离 toy view、再真机、全过才允许接线主路径）：
1. 流式增长时底边是否被布局级连续保持（不再需要任何定时重锚）；
2. 用户上滑是否自动打断且**绝不拽回**（观察 `isPositionedByUser`）；
3. 短内容阶段与 `.top` alignment 锚是否抢锚 / 第一屏是否抖；
4. 横向恒稳（`contentOffset.x == 0`、无越界裁切）；
5. pause→resume 重设 edge 是否产生跳变。
**任何一条不过 → 整体放弃，不打补丁。**

**候选 B' · 同 API 提高采样率（最低风险的丝滑增量）**
保留 `scrollTo(id: bottomAnchor, anchor: .bottom)`——它是**已被真机证明无横向副作用**的唯一程序滚动形态，
且全程单一定位形态（不触发 id↔point 切换）。只把触发从「80ms 定时」改为「几何增长事件 + ε 到位跳过 +
最小间隔护栏（约一帧）」：步长从 80ms 的积累量缩到行级增量，离散感线性下降。
所有现有守卫（短内容门槛、三态机、非用户滚动）原样保留。**若 A' spike 不成立，B' 为默认方向。**

**候选 C' · 最小 UIKit bridge（最后手段，正面回答「真正不触发布局重解的机制」）**
`UIScrollView.setContentOffset(animated: false)` 是 `bounds.origin` 写——UIKit 层唯一真正的 `scrollBy`，
不参与 SwiftUI 布局求解。脚手架已在（`ChatSwiftUIScrollViewResolver` `:596`，零引用）。
代价是**双写者风险**（SwiftUI `ScrollPosition` vs UIKit offset），必须立单一所有者契约：
follow 期间只允许 bridge 写、语义归位时 bridge 停写；introspection 失败降级回 80ms。
**仅当 A' 否证且 B' 实测不达标时启动。**

**方向二不变**：先量后调（§1 阶段二，以活跃链路 `:212 → :260-261 → :553 → ChatView.swift:591` 为准）。
用户反馈"查看历史性能没变好"不构成否证——方向二尚未实施。

### 9.4 成功指标修正（回应反馈问题 2/4）

在原成功标准之上，任何滚动机制改动必须**同时**满足：
- 纵向：底部硬边界稳定、不侵入 composer、第一屏不抖、`distance→0`；
- **横向：`contentOffset.x` 恒为 0、无文字越界裁切**（建议 `[AA-FOLLOW]` 日志增加 `offsetX` 字段）；
- **视觉基线：真机录屏逐段检查**内容从 composer 上方基线连续生长——不接受纯日志判定；
- 门禁：`ChatStreamReplayTests` 0 失败仅为必要条件。

### 9.5 候选 A' 隔离 spike 初判（2026-07-05 · simulator）

已新增隔离测试 `iosApp/iosAppTests/ChatScrollEdgeSpikeTests.swift`，只跑 toy view，不接线生产聊天列表。
默认跳过，需显式设置 test host 环境 `AA_RUN_EDGE_SPIKE=1` 或创建 `/tmp/amberagent-run-edge-spike` 才执行。

初步 spike 结果：**A' 不满足“布局级连续底边保持”这个核心条件，应降级/放弃为主路径候选。**

关键证据（`edgeOnceOnAppear`，初始 36 行，16ms 追加 48 行）：
- `completed=1`、`pinned=1`、`finalDistance=0.00`：最终能回到底部；
- `maxDistanceAfterAppend=91.00`：每次新增行后会先出现约一行高度的距底缺口，再由 SwiftUI 后续 pass 拉回；
- 修正测试口径后 `maxAbsXDrift=0.00`：未复现 `scrollTo(y:)` 的横向漂移，但这不足以救 A'；
- tail 日志呈现 `dist=91 → dist=0 → dist=91 → dist=0` 的交替节奏，说明 `scrollTo(edge: .bottom)` 不是持续 edge-preserving，
  而是离散定位修正。

短内容用例（`edgeOnlyAfterScrollable`）验证：未在不可滚阶段主动 pin（`pinned=0`），短内容门槛方向正确。

结论：`scrollTo(edge: .bottom)` 不是隐藏的完美 `sizeChanges` 替代品；它仍是 `ScrollPosition` 语义定位，
不能解决“固定基线连续上生长”的核心问题。下一步默认转向候选 B'：**保持唯一真机稳定的
`scrollTo(id: bottomAnchor, anchor: .bottom)` 定位形态，只提高触发采样率与到位跳过策略。**
