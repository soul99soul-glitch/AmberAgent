# Novel Session Streaming Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把小说创作会话的流式呈现对齐 Chat 当前生产标准（节奏、思考可见、滚动稳定），同时用硬隔离保证思考流永不污染候选稿 / 正文 / 结构化输出。

**Architecture:** 拆成四条可独立验收的垂直切片——(A) 可见文本单一来源与终态排空、(B) 思考呈现通道（presentation-only）、(C) 列表容器与单一滚动 owner、(D) 控制面与 chrome 高度稳定。领域权威仍是 `DefaultNovelCreation` + manuscript documents；UI 只消费 `NovelRunEvent` / transient 呈现态。

**Tech Stack:** iOS SwiftUI session list、`NativeTimelineScrollDriver` / `NativeTimelineScrollCore`、共享 `StreamPresentationPacingPolicy` / Chat 终态排空公式、`ChatAssistantMarkdownView` / `ChatReasoningCard`、`NovelLiveModelAdapter` → `NovelGenerationLifecycle` → `NovelSessionViewModel`。

**Status:** Active · Code complete pending device（A1–A2 / B / C1–C3 / D 最小收口已落地；真机 D1–D6 未验）  
**Baseline evidence:** 2026-08-10 代码对照（Chat 终态连续排空已落地；小说仍 36 字×48ms 终态；reasoning 在 adapter 被折叠为 `.activity`；列表为 ScrollView+LazyVStack 历史+UIKit 双写 offset）  
**Non-goals:** 把小说列表重写成完整 `NativeChatTimelineView`/UICollectionView；改 Android；改 provider 协议；把思考写入 durable session message 当正式正文。

---

## 0. 产品与污染防火墙（先于任何代码）

### 0.1 用户可见目标

| 场景 | 现在 | 目标 |
| --- | --- | --- |
| 推理模型先想后写 | 只有「思考中 N 秒」，无思考正文 | 有 Chat 同构思考卡流式；正文仍走 pacer |
| 整章贴底生成 | 上下窜、中间空白 | 贴底欠账 ≤2pt 级；历史/当前之间无整屏空洞 |
| 完成瞬间 | 慢排空或高度台阶 + 位移 | 连续节奏追平；chrome 不造成二次整页跳 |
| 收录 / 润色 adopt | 仅 manuscript partial | **不变**：思考永不进入 candidate / session durable prose |

### 0.2 污染防火墙（硬约束，测试必须锁死）

以下任一路径出现 reasoning 正文 → **P0 失败**：

1. `NovelActiveRunRecord.partialContent`
2. `NovelCandidateRecord.content` / collect / polish adopt 输入
3. `NovelSessionMessage` durable content（discussion/prose/polish/interruptedDraft）
4. Quick Start / characterProposal **结构化解码输入**（JSON 源）
5. Recovery sidecar `partialContent` / SHA
6. `NovelPromptCatalog.normalizedCandidateProse` 的输入若已是 manuscript，不得因思考通道被改写

**允许存在的思考数据：**

| 层 | 允许 | 禁止 |
| --- | --- | --- |
| Adapter → Lifecycle | `NovelModelEvent.reasoningDelta` / `reasoningFinished` | 写入 `runtime.partialContent` |
| Lifecycle → UI | `NovelRunEvent.reasoningDelta`（或独立 presentation stream） | 写入 durable run.partialContent |
| Session VM | `transientTail.reasoningContent`（或并列 `transientReasoning`） | 写入 `presentationBuffer.targetContent` |
| Bubble | `ChatReasoningCard` 只读 presentation | 把 reasoning 拼进 `displayMarkdown` |
| Durable | 可选：仅 metadata 标记 `hadReasoning=true` / 秒数（若产品需要） | 存全文思考当消息 |

**身份隔离：** reasoning 与 manuscript 共用 `runID`，但 **content channel 分叉**（`manuscript` vs `reasoning`）。任何 `enqueuePresentationDelta/Replacement` 只接收 manuscript channel。

### 0.3 对齐 Chat 的「抄什么 / 不抄什么」

| Chat 能力 | 小说对齐方式 |
| --- | --- |
| `StreamPresentationPacingPolicy` 流式 12–36 | 已共享，保持 |
| `terminalDrainAdvance` + 动态间隔 | **必须抄**到 `NovelSessionPresentationPacer` |
| `ChatReasoningCard` | **复用组件**，数据源换 novel presentation |
| `NativeChatTimelineView` 全量 | **不抄**；在现有 ScrollView 上消灭双 owner + Lazy 空洞 |
| Chat multi-part message model | **不引入**完整 UIMessage 列表；只加 reasoning presentation 字段 |
| 思考混入 assistant text | **禁止**（防火墙） |

### 0.4 成功标准（可证伪）

1. **污染：** 带 reasoning 的脚本化 adapter 跑完整章 → candidate/durable/sidecar 全文 **零** reasoning 子串；结构化 JSON 解码输入零 reasoning。
2. **思考可见：** 同脚本在 UI projection 上可见非空 `reasoningContent`，气泡渲染 `ChatReasoningCard`，正文开始后可软收起。
3. **终态排空：** 24k 字符 backlog 的 terminal drain 墙钟 ≤0.5s 量级（对标 Chat 契约，允许小说 String 形状的等价断言）。
4. **滚动：** 贴底流式 3s 窗口底部欠账稳定；完成瞬间无「整页闪白 / 中部空洞」；契约测试覆盖 history window 与 eager 尾不搬家闪断。
5. **门禁：** 见 §6 命令；不静默放宽阈值。

---

## 1. 文件地图

### 1.1 领域 / 适配（污染边界）

| 文件 | 职责 |
| --- | --- |
| `iosApp/iosApp/NovelCreation/NovelModelAdapter.swift` | 扩展 `NovelModelEvent` / frame events：reasoning 分支 |
| `iosApp/iosApp/NovelCreation/NovelLiveModelAdapter.swift` | chunk → 事件：reasoning **抽正文**发事件，**永不**进 text 拼接 |
| `iosApp/iosApp/NovelCreation/NovelGenerationLifecycle.swift` | 消费 reasoning 只 broadcast 给 UI；`partialContent` 仅 manuscript |
| `iosApp/iosApp/NovelCreation/NovelGenerationReducer.swift` | 确认终态 claim 仍只读 manuscript partial |
| `iosApp/iosApp/NovelCreation/AGENTS.md` | 固化「reasoning presentation-only」规则 |

### 1.2 会话呈现

| 文件 | 职责 |
| --- | --- |
| `iosApp/iosApp/NovelCreation/NovelSessionViewModel.swift` | buffer/pacer/terminal drain；`reasoning` 态；enqueue 只收 manuscript |
| `iosApp/iosApp/NovelCreation/NovelSessionPresentation.swift` | row model 带 optional reasoning 投影；digest 含 reasoning revision |
| `iosApp/iosApp/NovelCreation/NovelSessionBubble.swift` | 思考卡 + 正文；display 文本单一来源 |
| `iosApp/iosApp/NovelCreation/NovelSessionPendingPresentation.swift` | 有思考流时文案降级（避免「假卡死」双信号） |
| `iosApp/iosApp/ChatGenerationCoordinator.swift` | **只读复用** `StreamPresentationPacingPolicy` / 终态公式；必要时抽 shared helper 避免再分叉 |

### 1.3 滚动 / 列表

| 文件 | 职责 |
| --- | --- |
| `iosApp/iosApp/NovelCreation/NovelSessionView.swift` | 列表容器策略、单一 follow 路径、chrome 高度槽 |
| `iosApp/iosApp/NativeTimelineScrollCore.swift` | 小说路径若复用：流式 follow 钉底选项（或 novel 专用 snap 分支） |
| `iosApp/iosApp/NativeTimelineScrollDriver.swift` | 与 SwiftUI follow 的互斥；禁止双写 |

### 1.4 测试

| 文件 | 职责 |
| --- | --- |
| `iosApp/iosAppTests/NovelLiveModelAdapterTests.swift` | reasoning → event，不进 text |
| `iosApp/iosAppTests/NovelSessionViewModelTests.swift` | 呈现/终态/污染 |
| `iosApp/iosAppTests/NovelSessionReplayTests.swift` | pacer / history window / scroll policy |
| `iosApp/iosAppTests/NovelGenerationLifecycleTests.swift`（或现有 lifecycle 套件） | partialContent 隔离 |
| 新建（如需要）`NovelReasoningPresentationTests.swift` | 投影 + 防火墙集成 |
| `iosApp/iosAppTests/IOSParityRedLightTests.swift` | 终态公式跨面一致性（可选共享 helper 测试） |

**不碰（除非任务证明必须）：** Android `app/`、KMP conversation 存储、Chat 默认 timeline 生产路径大改、vendor 默认值。

---

## 2. Phase A — 可见文本单一来源 + 终态排空对齐

**目的：** 去掉「buffer 原文 vs bubble 再 strip」与「终态 36×48ms」两个已知分叉。

### Task A1: 共享终态排空公式落到小说 pacer

**Files:**
- Modify: `iosApp/iosApp/NovelCreation/NovelSessionViewModel.swift` (`NovelSessionPresentationPacer`, `publishTerminalPresentation`)
- Modify: `iosApp/iosApp/ChatGenerationCoordinator.swift`（仅当需要把 `terminalDrainAdvance/DelayNanos` 抽到 `StreamPresentationPacingPolicy` 旁，避免复制常量）
- Test: `iosApp/iosAppTests/NovelSessionReplayTests.swift`, `NovelSessionViewModelTests.swift`

- [ ] **Step 1: 红测试 — 大 backlog 终态有界时间/拍数**

锁定：
- `terminalDrainAdvance(24000)` 与 Chat 同值
- 固定 advance 时拍间隔连续曲线（8–48ms）
- `publishTerminalPresentation` 对 720/24k 目标：**不得**在第一拍 dump 全文；**必须**在有界 wall time 内 caught up

对标：`IOSParityRedLightTests` 里 Chat 终态契约；小说侧用 String pacer 镜像。

- [ ] **Step 2: 实现 `NovelSessionPresentationPacer.terminalStep` 真·终态模式**

```swift
// 语义（实现时对齐 Chat 命名）:
// - streaming: StreamPresentationPacingPolicy.textAdvance (max 36)
// - terminalDrain: terminalDrainAdvance(backlog) + 可选 fixedAdvance 整轮锚
// publishTerminalPresentation: 完成时 backlog 一次定锚；sleep 用 terminalDrainDelayNanos
```

- [ ] **Step 3: 绿测 + 不破坏「Stop 在纯 UI 排空时不可用」**

保持现有契约：`canStop == false` 当仅剩 presentation drain；`isBusy` 仍锁到 durable 接管。可选：状态条文案「正在呈现…」。

- [ ] **Step 4: 提交切片 A1**（仅用户要求 commit 时）

### Task A2: Manuscript 可见文本单一来源

**Files:**
- Modify: `NovelSessionViewModel` presentation buffer / terminalStep
- Modify: `NovelSessionBubble.displayMarkdown`
- Modify: lifecycle `claimTerminal` 去围栏时机（保持幂等）
- Test: `testFencedTerminalProseContinuesFromVisiblePrefixInsteadOfSnapping` 及新用例

- [ ] **Step 1: 红测试**

- 流式带 ` ```markdown ` 围栏：UI 可见内容与 pacer `displayedContent` **同一 strip 规则**
- 终态去围栏：不得因 prefix 失败整章 snap（已有用例，补「display 与 buffer 同源」断言）

- [ ] **Step 2: 实现**

策略（选一，推荐 2）：

1. **Buffer 存 raw，bubble 不再 strip** — 围栏可能闪绿代码卡（差）
2. **进入 presentation 前 strip 到 display 形态**（`normalizedStreamingCandidateProse`），pacer 与 bubble 同读；lifecycle 终态 normalize 后若与 display 形态一致则单调推进（**推荐**）
3. Bubble strip + terminalStep 补偿 — 现状，继续会抖

- [ ] **Step 3: Replacement 语义收紧**

`step` 在 `!hasPrefix` 时 **禁止**默认全文 dump，除非：
- runKind 为 structured projection 且显式 `allowSnapReplacement`，或
- 真 divergence 且 backlog 小于一行（需测试定义）

默认：尝试用 display-normalized 两侧再比一次 prefix；仍失败则 **限速** 切到 target（走 terminalDrain 节奏），禁止单帧整章。

---

## 3. Phase B — 思考流（presentation-only）

**目的：** 有 Chat 同构思考卡；**零污染**。

### Task B1: 事件协议与适配层

**Files:**
- Modify: `NovelModelAdapter.swift` — `NovelModelEvent` / `NovelModelFrameEvent`
- Modify: `NovelLiveModelAdapter.swift` — `events(from:)` / `frameEvents(from:)`
- Test: `NovelLiveModelAdapterTests.swift`

- [ ] **Step 1: 红测试**

脚本 chunk：仅 `UIMessagePart.Reasoning` 非空 → 得到 `.reasoningDelta`（或等价），**无** `.textDelta`。  
脚本 chunk：reasoning + text 同帧 → 先/后顺序稳定（建议 reasoning 事件与 text 事件分离；同帧可先 reasoning 后 text）。  
`joinedAssistantText` / manuscript 拼接路径 **不含** reasoning。

- [ ] **Step 2: 实现**

```swift
// NovelModelEvent 增量（示意）
case activity                 // 保留：无正文心跳
case reasoningDelta(String)   // 新增：可累积的思考增量或快照策略二选一
case reasoningFinished        // 可选：思考段结束
case textDelta(String)
// ...
```

`hasReasoningActivity` 升级为提取 `reasoningText(in:)`；**删除**「有 reasoning 只发 activity」作为唯一路径（activity 可在无增量时保留）。

提取策略：
- **累计快照**（Chat 常见）：每次 chunk 给完整 reasoning → VM 侧 `replace` reasoning buffer
- **真 delta**：仅当 provider 保证增量

以当前 `UIMessagePart.Reasoning` 行为为准（实现前读 Chat accumulator 如何处理）；优先与 Chat 一致，避免小说独造。

- [ ] **Step 3: 绿测**

### Task B2: Lifecycle 广播与 partialContent 隔离

**Files:**
- Modify: `NovelGenerationLifecycle.swift` (`consumeModelEvent`, `broadcast`, `claimTerminal`, sidecar flush)
- Test: lifecycle / document validator 相关套件

- [ ] **Step 1: 红测试**

- 连续 reasoningDelta 后 complete：`runtime.partialContent` 仍为空或仅 manuscript
- sidecar 落盘 partial 不含 reasoning
- `claimTerminal` 后 candidate/message 不含 reasoning

- [ ] **Step 2: 实现**

```swift
case .reasoningDelta(let text):
    // 只 broadcast(.reasoningDelta(text))
    // 可刷新 background lease / 无输出计时（与 activity 相同）
    // 禁止 runtime.partialContent += text
case .textDelta(let text):
    // 现有 manuscript 路径
```

`attachSubscriber` 恢复：可 yield 当前 manuscript `replaced`；**默认不**回放思考全文（避免重进页巨量思考撑爆；若回放，仅 UI 态且仍不进 partial）。

- [ ] **Step 3: 绿测**

### Task B3: Session VM + 投影 + 气泡

**Files:**
- Modify: `NovelSessionViewModel.swift`, `NovelSessionPresentation.swift`, `NovelSessionBubble.swift`, `NovelSessionPendingPresentation.swift`
- Modify: `NovelSessionView.swift` 仅当 list signal digest 需含 reasoning
- Test: `NovelSessionViewModelTests` / 新建 `NovelReasoningPresentationTests`

- [ ] **Step 1: 红测试**

- `transientTail.reasoningContent` 随事件增长
- `projectedListModel` row 可观察 reasoning
- `enqueuePresentationDelta` 输入与 reasoning 通道分离的类型/API 级测试
- 完成/中断/失败后：思考卡结束态；manuscript 终态排空不受 reasoning 长度影响

- [ ] **Step 2: UI**

- 复用 `ChatReasoningCard`（参数对齐 Chat：流式、时长、软收起）
- 正文 `ChatAssistantMarkdownView` 仍只吃 manuscript
- 空正文 + 有思考：显示思考卡，pending 文案改为轻量或隐藏秒数（避免双进度）
- 正文出现后：思考卡可自动软收起（对齐 Chat 完成收起语义；用户手动展开保留）

- [ ] **Step 3: 污染集成测试（必过）**

端到端 harness：reasoning 长文 + manuscript 短文 →  
`availableProseCandidates.first?.content`、`durableMessages` assistant、repository load run.partialContent 均 **等于 manuscript**，且 `range(of: reasoningSnippet) == nil`。

### Task B4: AGENTS / 规格一句

**Files:**
- Modify: `iosApp/iosApp/NovelCreation/AGENTS.md`
- Optional: `docs/NOVEL_CREATION_SPEC.md` 用户可见一句

- [ ] 写明：reasoning 仅 UI；partial/candidate/collect 禁用；测试套件名

---

## 4. Phase C — 列表与滚动（空白 / 乱跳）

**目的：** 消中部空洞与上下窜；不重写 collection timeline。

### Task C1: 可见窗口 eager 化

**Files:**
- Modify: `NovelSessionView.swift` transcript 结构
- Modify: `NovelSessionHistoryWindowPolicy`（若需区分 cold/hot）
- Test: `NovelSessionReplayTests` history window

- [ ] **Step 1: 红测试 / 结构契约**

源码或行为契约：
- `historyWindowLimit` 内的行 **不**放在会卸载的 Lazy 容器中；或
- Lazy 仅用于 `hiddenHistoryCount` 展开前的更冷数据（当前设计是按钮加载更早，窗口内应 eager）

推荐结构：

```text
ScrollView {
  VStack {
    [加载更早按钮]
    VStack { // eager: 窗口内 historicalRows
      ForEach(visibleHistoricalRows)
    }
    VStack { // eager: activeRunRows + character cards
      ForEach(activeRunRows)
      ...
    }
    bottomAnchor
  }
}
```

若历史窗口未来放到 50+ 长文仍卡，再引入「窗口外 cold lazy」，但 **默认窗口保持 eager**。

- [ ] **Step 2: 完成时禁止 eager→lazy 搬家闪断**

`activeRunRows` 在 `terminalAwaitingRefresh` / quiet retire 期间仍算 active（或 sticky 容器），直到 tail 退役与 durable 行稳定同一父容器。  
红测试：完成瞬间 `activeRunRows` 不空窗导致同一 id 跨父容器销毁。

- [ ] **Step 3: 绿测**

### Task C2: 单一滚动 owner + 流式钉底

**Files:**
- Modify: `NovelSessionView.swift` (`dispatchFollowEvent` / `executeFollowCommand` / geometry)
- Modify: `NativeTimelineScrollCore.swift` 或 novel 侧 follow 策略
- Test: scroll policy / native core tests；必要时扩展 `NativeTimelineScrollCoreTests`

- [ ] **Step 1: 定规则**

| 状态 | 谁写 offset |
| --- | --- |
| Native driver attached + following | **仅** driver（`streamContentGrew` / frame tick） |
| Native fallback | **仅** SwiftUI `scrollPosition.scrollTo` |
| 禁止 | 同一帧既 `setContentOffset` 又 `scrollTo` |

- [ ] **Step 2: 流式 follow 去掉欠账缓动（小说路径）**

Chat 终态已钉底；小说整章流式同样需要：`followingBottom` 在 novel 使用场景下 **writeOffsetY(bottomTarget)** 或提高追赶速率，避免 τ=0.06 指数欠账与 contentSize 锯齿共振。

实现选项（择一，写进代码注释）：
1. `NativeTimelineScrollCore` 增加 `followMode: .eased | .snap`，小说 attach 时 `.snap`
2. 小说不走 frame eased path，geometry 增长时直接 `explicitBottom(animated:false)` 收敛

- [ ] **Step 3: 红→绿**

- stream 增长：offset 单调贴 `bottomTarget`（允许 1–2pt epsilon）
- 用户上滑：`pausedForUser`，不强制拉回
- 回底：release suspended tail 后 **paced** 或钉底一次，不连环弹

### Task C3: 高度 chrome 稳定

**Files:**
- Modify: `NovelSessionView` generation status strip / composer safeAreaInset
- Modify: `NovelSessionBubble` 流式期 action/status 策略（已部分做）

- [ ] 生成状态条：**固定高度槽**（隐藏时保留占位或动画高度连续），避免 inset 突变
- [ ] 终态 action bar：在 terminal drain **caught up 之后**再插入，或预留 minHeight
- [ ] composer 高度 preference 变化：native driver `viewportChanged` 一次收敛

---

## 5. Phase D — Structured 流与控制面（次要但一并收口）

### Task D1: Quick Start / 人物建议 非单调 markdown

**Files:** `NovelSessionViewModel` structured presentation；`NovelStructuredOutput.swift`

- [ ] 投影 markdown 尽量字段追加单调；无法单调时走 **限速 replacement**（A2 的 drain），禁止单帧整卡闪
- [ ] 思考若存在于 structured 模型：同样只进 reasoning 通道，JSON 源仍是 raw model text without reasoning parts

### Task D2: 控制面文案

- [ ] 纯 UI 排空：`isRunning=false` 保持；status「正在呈现全文…」
- [ ] 仅有思考、无正文：status / pending 与思考卡一致
- [ ] `supportsReasoning` 上下文环：若项目创作模型 abilities 含 reasoning 则 true（展示用，不改请求）

---

## 6. 验证门禁

### 6.1 每 Phase 最小命令

```bash
# A 节奏 / 终态 / 围栏
xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -parallel-testing-enabled NO \
  -only-testing:iosAppTests/NovelSessionViewModelTests \
  -only-testing:iosAppTests/NovelSessionReplayTests \
  test

# B 思考 + 污染
xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -parallel-testing-enabled NO \
  -only-testing:iosAppTests/NovelLiveModelAdapterTests \
  -only-testing:iosAppTests/NovelGenerationLifecycleTests \
  -only-testing:iosAppTests/NovelSessionViewModelTests \
  test

# C 滚动 / 窗口（按实际测试类名调整）
xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -parallel-testing-enabled NO \
  -only-testing:iosAppTests/NovelSessionReplayTests \
  -only-testing:iosAppTests/NativeTimelineScrollCoreTests \
  test
```

### 6.2 全量小说回归（Phase 收口）

```bash
xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -parallel-testing-enabled NO \
  -only-testing:iosAppTests/NovelSessionViewModelTests \
  -only-testing:iosAppTests/NovelSessionReplayTests \
  -only-testing:iosAppTests/NovelGenerationLifecycleTests \
  -only-testing:iosAppTests/NovelLiveModelAdapterTests \
  -only-testing:iosAppTests/NovelCreationPresentationTests \
  -only-testing:iosAppTests/IOSNovelCreationWiringTests \
  test
```

### 6.3 真机清单（代码绿 ≠ 手感过）

| # | 场景 | 通过标准 |
| --- | --- | --- |
| D1 | 推理模型整章，先想后写 | 思考卡可见；收录正文无思考 |
| D2 | 贴底看完整章 | 无中部空白；无上下连续窜 |
| D3 | 生成中上滑再回底 | 不连环弹；回底后继续跟 |
| D4 | 完成瞬间 | 无整页闪白；操作按钮出现不明显二次跳 |
| D5 | Quick Start | 非整卡闪烁；JSON 落盘无 reasoning |
| D6 | 进后台再回 | manuscript 恢复正确；思考可不回放 |

---

## 7. 实施顺序与依赖

```text
A1 终态排空 ────────┐
A2 单一可见文本 ────┼──► C1 eager 窗口 ──► C2 单一滚动/钉底 ──► C3 chrome
B1 事件 ──► B2 lifecycle ──► B3 UI 思考卡 ──► B4 文档
D1/D2 可与 B3 后或并行
```

**推荐落地顺序（每刀可独立装机）：**

1. **A1**（终态手感，风险低）  
2. **B1→B2→B3**（思考可见 + 污染锁，产品感知强）  
3. **A2**（围栏/替换跳变）  
4. **C1→C2→C3**（空白/乱跳，最大结构面）  
5. **D** 收口 + 真机 D1–D6  

若只做一刀优先用户「乱跳/空白」：先 **C1+C2**，再 B。  
若只做一刀优先「有思考」：先 **B 全套**（带污染测试），滚动另开。

---

## 8. 风险与禁区

| 风险 | 缓解 |
| --- | --- |
| reasoning 误写入 partial | B2/B3 双层测试 + AGENTS 规则；code review 查 `partialContent +=` |
| 抽共享 pacer 时 Chat 回归 | 抽函数保持 Chat 测试全绿；不改 Chat 默认数值 |
| 钉底过猛与用户手势冲突 | `pausedForUser` / drag began 优先 |
| eager 窗口内存 | 保持 history window 有界（现策略）；不一次渲染全 session |
| 与未提交 WIP 冲突 | 只改本 plan 文件表；presentation WIP 上最小 diff |
| 把小说重写成 Chat timeline | **禁止**本 plan 范围 |

---

## 9. 文档与状态

完成后：

- [ ] 更新 `docs/PROJECT_STATE.md`：本 plan 状态、验证、残余真机项  
- [ ] `docs/README.md` 快速入口可链到本 plan（Active）  
- [ ] 不新建 handoff，除非用户要求交接  

---

## 10. 执行交接

Plan 路径：`docs/superpowers/plans/2026-08-10-novel-session-streaming-parity.md`

**两种执行方式：**

1. **Subagent-Driven（推荐）** — 每 Task 新代理，Task 间审查  
2. **Inline Execution** — 本会话按 Phase 连续做，每 Phase 门禁  

**污染防火墙不接受妥协：** 任何「先把 reasoning 拼进 partial 再 strip」的捷径都算出局。
