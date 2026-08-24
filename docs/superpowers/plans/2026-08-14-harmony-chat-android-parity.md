# Harmony Chat 交互与 Android 语义等价计划（6 阶段）

> 计划日期：2026-08-14
> 分支：`harmony-preview`
> 仓库 HEAD：`bb223dc840e9be300dd2a505db7919d520fa9e0e`（Harmony 侧，Android 源码只读基准）
> 权威入口：`docs/superpowers/plans/2026-08-14-harmony-chat-android-parity.md`
> 治理索引：本计划与 `MIGRATION_STATE.yaml` / `DECISION_LOG.md` / `PARITY_DEBT.md` 同步；每阶段验收必须更新以上文件。

---

## 0. 执行摘要

本计划把 Harmony Chat 从「能跑」推进到「与 Android 生产语义等价」，拆为 6 个连续 phase，按依赖排序：

| Phase | 主题 | 核心目标 |
|---|---|---|
| P1 | 滚动跟随 / 用户暂停 / 跳底 / 首屏定位 | 流式期间列表贴底跟随，用户上滑暂停跟随，跳底按钮，首屏定位到目标消息 |
| P2 | 停止生成状态收口 + 流式 checkpoint + 生命周期 | stop 语义与 Android `stopGeneration` 对齐；进程杀/页面销毁的 checkpoint 收口；lifecycle 归口 |
| P3 | streaming pacing / 安全切片 / Markdown 性能 / reasoning 稳定 | 把已移植的 pacing 纯逻辑接上 UI；Markdown 增量解析缓存；reasoning 稳定性 |
| P4 | 输入栏 / 键盘 / 安全区 / 生成中排队 / 模型刷新 / Context 用量 / 错误提示 | ChatInput 增强 + IME/safe-area + 队列状态 + 错误可见性 |
| P5 | 历史分页 / 懒加载 / 长会话性能 | 时间线分页 + LazyForEach + markdown 预取 |
| P6 | 抽屉 / 消息手势 / 附件与剩余产品完善 | ChatDrawer 接入 + user 消息长按菜单 + 附件闭环 + 剩余缺口 |

**总体原则（每个 phase 必须遵守）**：
1. **精准修改**：只改目标函数/文件，不做架构级重构，不引入新的抽象层。
2. **复用 Android 已验证语义**：所有常量、状态机、文案、时序以 Android 源码为准（仓库只读，禁止改 Android）。
3. **复用 Harmony 现有能力**：`harmony/chat` HAR 已移植的纯逻辑（`streaming_display.ts` pacing、`stream_accumulator.ts`、`pending_queue.ts`、`reasoning_display.ts`、`markdown` 解析）优先接线，不重写。
4. **诚实标注**：需要设备验证（模拟器）的效果类验收，标注「待设备」，不宣称完成。
5. 每个 phase 自带「非目标」，防止范围蔓延。

**当前基线事实（已核对源码）**：
- `harmony/entry/src/main/ets/pages/ChatPage.ets`（3335 行）是唯一聊天页，消息列表用 `List + ForEach`（LazyForEach 已注释为待 IDataSource 重构），`Scroller` 只提供 `scrollEdge(Edge.Bottom)` 一种跳底方式，无用户暂停/距离判定。
- `ChatInput.ets`（213 行）已支持发送/停止切换、长按仅发送、图片、斜杠命令；无键盘/safe-area 处理。
- `ChatDrawer.ets`（127 行）是独立组件，目前只有 `ChatListGrouped.ets` 引用，**ChatPage 未接线**（ChatPage 内无 drawer 调用）。
- chat HAR 已有 `streaming_display.ts`（196 行，pacing 纯逻辑）、`stream_accumulator.ts`（414 行）、`pending_queue.ts` / `pending_queue_store.ts`（D-048/D-052 已落地）、`reasoning_display.ts`（D-053）。
- 当前设备验证状态：Gate 0 已解锁（模拟器 `127.0.0.1:5557`），SSE/渲染已验证（见 `docs/harmony-project-status-and-handoff.md`）。

---

## 1. 共享基线：现状证据锚点

### 1.1 Harmony 现状关键文件

| 文件 | 行数 | 关键事实 |
|---|---|---|
| `harmony/entry/src/main/ets/pages/ChatPage.ets` | 3335 | `List({ scroller })` + `ForEach(this.rows)`；`scrollToBottom()` 只调 `scroller.scrollEdge(Edge.Bottom)`；`onDidScroll` 只用于头部玻璃化（y>4）；`@State streamingTail` / `sending` / `generationToken`；`aboutToDisappear` 里 abort + 清 tail |
| `harmony/entry/src/main/ets/components/ChatInput.ets` | 213 | 发送/停止键切换、长按仅发送、图片、斜杠面板；无 IME/safe-area |
| `harmony/entry/src/main/ets/components/ChatDrawer.ets` | 127 | 独立组件，未被 ChatPage 使用 |
| `harmony/entry/src/main/ets/components/MarkdownText.ets` | 416 | 原生 ArkUI 组件树渲染 Markdown（替代 RichText 的 P0 修复），无增量解析缓存 |
| `harmony/entry/src/main/ets/components/ChatMessageReasoning.ets` | — | D-053 三态卡片，流式期间 reasoning 稳定性的承载点 |
| `harmony/chat/src/main/ets/chat/streaming_display.ts` | 196 | pacing 纯逻辑已移植（`StreamingCharRevealClock`、`streamingDisplayTargetSpeed`、`safeStreamingDisplayEnd` 等），**entry 侧未接线**（grep 无引用） |
| `harmony/chat/src/main/ets/chat/stream_accumulator.ts` | 414 | 流式合并，含 reasoning `finishedAt` 两条路径（与 Android 内部不一致处各自忠实） |
| `harmony/chat/src/main/ets/chat/pending_queue.ts` + `pending_queue_store.ts` | — | D-048/D-052 已完成，含 KV 持久化 |
| `harmony/chat/src/main/ets/chat/chat_turn.ts` | ~346 | `generateAssistantOnce`：abort 部分快照终态化 + D-050 重试 + `flushIntervalMs` 节流 |
| `harmony/entry/src/main/ets/platform_impl/EntryAbortController.ets` | ~52 | `AbortControllerLike` 实现 |

### 1.2 Android 基准锚点

| 文件 | 行数 | 提供语义 |
|---|---|---|
| `app/src/main/java/app/amber/feature/ui/pages/chat/ChatStreamingFollowPolicy.kt` | 92 | 跟随状态机纯逻辑：`TimelineFollowMode`（Idle/FollowingBottom/PausedForUser）、`GenerationEndEffectPlan`、`isCloseEnoughToBottom` |
| `app/src/main/java/app/amber/feature/ui/pages/chat/ChatList.kt` | 265 | `TimelineFollowMode` 枚举、`FollowLayoutSignal`、常量：`TimelineBottomSafetyPadding=28.dp`、`TimelineItemSpacing=14.dp`、`ScrollBottomSpacerHeight=5.dp`、`TailIndicatorDotBottomPadding=18.dp` |
| `app/src/main/java/app/amber/feature/ui/pages/chat/ChatListNormalSection.kt` | ~1600 | 跟随状态机主体：`bottomFollowBufferPx=24.dp`、`scrollToTimelineBottom`（smooth 18dp/帧、maxFrames 8）、`deferStreamingMarkdownParse`、`snapshotFlow` 布局信号、历史分页加载触发 |
| `app/src/main/java/app/amber/feature/ui/pages/chat/ChatPage.kt` | 1709 | 首屏定位：`initialChatListIndex` → `rememberLazyListState(initialFirstVisibleItemIndex)`；`onJumpToMessage`；`ImeLazyListAutoScroller` 挂载 |
| `app/src/main/java/app/amber/feature/ui/hooks/ImeAutoScroller.kt` | 55 | IME 升起时 `scrollBy(delta)`，仅 delta>0 且 `shouldScroll`；收起不反向滚 |
| `app/src/main/java/app/amber/core/service/ChatService.kt` | 2554 | `stopGeneration`（:2408-2455）：cancel job → finishPendingTools → assistant 消息补 `finishedAt` → finishReasoning → save → 队列继续；`stopGenerationKeepAlive` |
| `app/src/main/java/app/amber/feature/chat/impl/ChatStreamCheckpointRecorder.kt` + `StreamCheckpoints.kt` | 146+72 | 流式 checkpoint：`streamContentLength()`（512 字符合并信号）、`streamPartsHash()`（SHA-256 部分哈希）、`toolStateSnapshots()` |
| `app/src/main/java/app/amber/feature/ui/components/richtext/StreamingDisplay.kt` | 373 | pacing 驱动 + `rememberStreamingDisplayText`（sawStreaming / drainingAfterStream / settled correction 语义） |
| `app/src/main/java/app/amber/feature/ui/components/richtext/StreamingCharReveal.kt` | 95 | 逐字符出现时钟（已移植为 `streaming_display.ts`） |
| `app/src/main/java/app/amber/feature/ui/pages/chat/ChatVM.kt` | ~350 | `stopGeneration()`（:337）、`ensureTimelineLoaded`/`loadOlderTimelinePage`（:349/:352）、`onCleared` |
| `app/src/main/java/app/amber/feature/ui/components/message/ChatMessage.kt` | 240 | user 消息：单击编辑、长按 ActionsSheet（:148） |
| `app/src/main/java/app/amber/feature/ui/pages/chat/ChatDrawer.kt` | 634 | ModalNavigationDrawer + ConversationList |
| `app/src/main/java/app/amber/feature/ui/components/ai/ChatInputUsage.kt` | 663 | ContextUsage 环：`estimatedTokens`、`contextWindow`、`ratio`、`compactActive` |
| `app/src/main/java/app/amber/feature/ui/pages/setting/SettingDisplayPage.kt` | — | DisplaySetting 字段（enableAutoScroll/showBottomFollowAnimation 等，Harmony `display_setting.ts` 已有 32 字段） |

### 1.3 构建与测试门禁（沿用 MIGRATION_STATE.yaml）

```bash
cd harmony/chat && npm test && npx tsc --noEmit     # chat 测试 + 类型
cd harmony/deepread && npm test && npx tsc --noEmit # deepread 测试 + 类型
cd harmony && hvigorw assembleHar --mode module -p product=default   # HAR
cd harmony && hvigorw assembleHap --mode module -p product=default   # HAP
git diff --check clean
# 设备验证（Gate 0 已解锁）：
# hdc 安装 entry-default-signed.hap → bm install → aa start EntryAbility
```

---

## 2. Phase 1 — 滚动跟随 / 用户暂停 / 跳底 / 首屏定位

### 2.1 目标
- 流式生成期间，当用户未上滑时，列表自动贴底跟随（每 chunk 更新后跳底）。
- 用户上滑离开底部（距离 > 阈值）时暂停跟随；用户回到底部附近或点跳底按钮时恢复。
- 空态/尾部增加「跳到底部」按钮（含新消息计数徽标可选，P1 不做计数仅做按钮）。
- 首屏定位：带 `initialMessageIndex`/`nodeId` 进入时定位到目标消息（`firstLazyIndexForMessage` 等价语义）。

### 2.2 非目标
- 不做流式尾光标/逐字 reveal（P3）。
- 不做 smooth 大段滚动动画（`smoothLargeMove` 可先以 snap 实现，动画待设备验证后按需补）。
- 不做消息跳转工具条（`TimelineSelectionToolbarOffset`，P6）。
- 不做 LazyForEach / 历史分页（P5）。

### 2.3 现状证据
- `ChatPage.ets:690` 只有一个 `private scroller: Scroller`；`scrollToBottom()`（:934）只 `scrollEdge(Bottom)`，在 `onUpdate`/then/catch/drain 多处无条件调用（:1077/:2270/:2364/:2443/:2681）。
- `onDidScroll`（:2972）只读 `currentOffset().yOffset` 驱动头部玻璃化，无跟随判定。
- 无用户暂停状态、无「距离底部」计算。
- Android 基准语义在 `ChatStreamingFollowPolicy.kt`（纯逻辑）+ `ChatListNormalSection.kt`（驱动）。

### 2.4 具体文件与函数
| 动作 | 文件 | 位置 |
|---|---|---|
| 新增跟随状态机纯逻辑（domain 侧，可单测） | `harmony/chat/src/main/ets/chat/timeline_follow.ts` | 新建；从 `ChatStreamingFollowPolicy.kt` + `TimelineFollowMode` 移植 |
| ChatPage 接入跟随状态 | `harmony/entry/src/main/ets/pages/ChatPage.ets` | `@State followMode`、`@State userScrollInTimeline`、`scrollToBottom` 改造、`onDidScroll` 扩展、`onAreaChanged`/滚动监听 |
| 跳底按钮 | `harmony/entry/src/main/ets/pages/ChatPage.ets` | 新增 `@State showJumpBottom`，叠加层（复用 `BottomFade` 位置，`Alignment.Bottom`） |

### 2.5 调用链（目标终态）
```
onUpdate(msgs) → 更新 streamingTail
  → shouldFollowBottom(): followMode==FollowingBottom && !userScrollInTimeline && !scrollInProgress
  → 是 → scrollToBottom()
onDidScroll/滚动事件 → 计算 distanceToBottom(=totalHeight - currentOffset)
  → distance > bottomFollowBufferPx(24dp) → userScrollInTimeline=true; followMode=PausedForUser
  → distance <= buffer 且 用户滚动事件归因 → userScrollInTimeline=false; resumeBottomFollow()
跳底按钮 onClick → beginProgrammaticScroll → scrollToBottom → endProgrammaticScroll
```

### 2.6 实现步骤
1. 在 `harmony/chat` 新建 `timeline_follow.ts`：移植 `TimelineFollowMode`、`GenerationEndEffectPlan`、`effectPlan()`、`canSettleNow()`、`canAttemptSettle()`、`isCloseEnoughToBottom()`（常量 `RequiredStableBottomFrames=2`、`MaxSettleFrames=16`）。单元测试照抄 Android 行为断言。
2. `ChatPage.ets`：新增 `@State followMode: string = 'Idle'`、`@State userScrollInTimeline: boolean = false`、`@State programmaticScrollInProgress: boolean = false`、`@State showJumpBottom: boolean = false`。
3. 改造 `scrollToBottom()`：按 `beginProgrammaticScroll`/`endProgrammaticScroll` 语义包裹（ArkUI 无 `withFrameNanos`，用 `setTimeout(0)` 或 `postFrame` 等一帧后复位）。
4. `List.onDidScroll` 里计算 `distanceToBottom`（ArkUI 可用 `scroller.currentOffset().yOffset` + `contentSize` 或 `onAreaChange` 换算；若 API 不直接暴露内容总高，用 `List` 的 `onScrollIndex` + 末 item 位置近似，登记偏差）。更新 `followMode`/`userScrollInTimeline`。
5. `onUpdate` 中跟随判定改为调用 `shouldFollowBottom()` 守卫；then/catch 的强制 `scrollToBottom()` 改为仅当 `FollowingBottom` 时执行（对齐 `effectPlan` 的 end-settle 语义，先以简化版：生成结束 if `userScrollInTimeline` 则不动）。
6. 跳底按钮：`showJumpBottom` 在 `userScrollInTimeline` 为真时显示；按钮 40x40vp 圆角 999、SURFACE2 底 + LINE 边框、`Icon({name:'arrow_down', ...})`，位置 `bottom: 安全区 + 108vp`（参考输入区高度）。点击恢复跟随 + 跳底。
7. 首屏定位：`aboutToAppear` 读 `router.getParams()` 的 `initialMessageIndex`/`nodeId`；`onReady` 或首帧后 `scroller.scrollToIndex(index, false)`；无目标则 `scrollEdge(Bottom)`。对齐 Android `ChatPage.kt:303-312` 的 `initialChatListIndex` 语义。
8. 设备验证：流式过程中上滑/回底/跳底按钮全交互走查。

### 2.7 状态模型
```
followMode: 'Idle' | 'FollowingBottom' | 'PausedForUser'
userScrollInTimeline: boolean
programmaticScrollInProgress: boolean
showJumpBottom: boolean
distanceToBottomPx: number（派生）
```

### 2.8 UI 尺寸 / 边距规范（对齐 ChatList.kt 常量）
| 常量 | 值 | 出处 |
|---|---|---|
| `bottomFollowBufferPx` | 24vp（≈1 行 CJK） | ChatListNormalSection.kt:178 |
| `TimelineBottomSafetyPadding` | 28vp | ChatList.kt:105 |
| `TimelineItemSpacing` | 14vp | ChatList.kt:108 |
| `ScrollBottomSpacerHeight` | 5vp | ChatList.kt:114 |
| `TailIndicatorDotBottomPadding` | 18vp | ChatList.kt:115 |
| 跳底按钮 | 40x40vp，radius 999，SURFACE2/LINE，距底 = 安全区 + 108vp | 参考现有 FAB 风格 |

### 2.9 测试与构建验收
- [ ] `timeline_follow.ts` 单测：`effectPlan` 四象限（autoScroll 关/生成结束/进行中）、`canSettleNow` 门、`isCloseEnoughToBottom` 边界。
- [ ] chat `npm test` + `tsc --noEmit` 全绿；assembleHar/assembleHap SUCCESS；`git diff --check` clean。
- [ ] 设备：流式上滑暂停 → 回底恢复 → 跳底按钮出现/点击生效 → 首屏定位到 `initialMessageIndex`。

### 2.10 subagent review 清单
- [ ] 跟随判定是否只在 `FollowingBottom` 且非用户滚动时触发（不打扰用户阅读）。
- [ ] `programmaticScrollInProgress` 是否在程序化滚动后一帧复位（避免误判用户）。
- [ ] 跳底按钮是否只在 `userScrollInTimeline` 时显示（不常显）。
- [ ] 新状态是否全部为 ChatPage 局部 `@State`（不引入全局 store）。

### 2.11 回滚边界
- 本次只改 `ChatPage.ets` 的滚动相关部分 + 新增 `timeline_follow.ts`；不触碰 `streamingTail`/`sending`/`drainPendingQueue` 既有逻辑。
- 若 ArkUI 无法精确计算 `distanceToBottom`，回退为「末 item 可见即认为在底部」的近似，并在 PARITY_DEBT 登记。
- 回滚 = revert 本 phase commit；行为回到「无条件 scrollEdge(Bottom)」。

---

## 3. Phase 2 — 停止生成状态收口 + 流式 checkpoint + 生命周期

### 3.1 目标
- 停止生成与 Android `ChatService.stopGeneration`（:2408-2455）语义对齐：abort 后部分快照终态化（`finishedAt` 补齐 + reasoning 关闭 + pending tool 收口）+ 会话持久化 + 队列继续。
- 页面销毁/进程杀场景的 checkpoint 收口：停止时把流式尾部 512 字符合并信号落库（对齐 `ChatStreamCheckpointRecorder` 的 force 语义）。
- 生命周期归口：`aboutToDisappear` 与 `aboutToAppear` 的生成/订阅状态统一收口，消除多个 `sending=false` 散点。

### 3.2 非目标
- 不做跨进程恢复的完整事件投影（`ChatEventProjector`/`ProjectingEventWriter` 事件仓），只做「当前会话 checkpoint 落库 + 重启后可见」。
- 不做 `InterruptedRunProjection` 的完整 run 生命周期（run/ended 事件仓）——登记为 P1 后继续。
- 不改 provider/HTTP 层 abort 语义（D-035 已落地）。

### 3.3 现状证据
- `ChatPage.ets:689-690` `abortController` 创建于 send/regenerate/continue 三路径（:1007/:2205/:2574），stop 按钮只调 `abort()`（:3197）+ `convertSteerToFollowup`，**不做终态化 UI 收口**；真正收口发生在 `chat_turn.generateAssistantOnce` 的 catch 分支（`isAborted() break` → `applyOnGenerationFinish`），随后 `runChatTurn` 尾部把 `finalMessages` 入列保存（:219-224）。
- `aboutToDisappear`（:766-790）abort + `sending=false` + 清 tail，但**不持久化部分快照**——用户切页后部分回复可能丢失。
- Android：`ChatService.stopGeneration` 在 `stopGenerationKeepAlive` 之后 `finishPendingTools` + `finishedAt` + `finishReasoning` + `saveConversation`。

### 3.4 具体文件与函数
| 动作 | 文件 | 位置 |
|---|---|---|
| 移植停止收口纯逻辑 | `harmony/chat/src/main/ets/chat/stop_generation.ts` | 新建；从 ChatService.kt:2408-2455 的 `updatedMessage` 构造（finishPendingTools/finishedAt/finishReasoning 判定）移植 |
| 移植 checkpoint 纯逻辑 | `harmony/chat/src/main/ets/chat/stream_checkpoint.ts` | 新建；移植 `streamContentLength()`（512 信号）、`streamPartsHash()`（SHA-256）、`toolStateSnapshots()` |
| ChatPage 停止处理器收口 | `harmony/entry/src/main/ets/pages/ChatPage.ets` | `onStop`（:3196）改为 abort → 终态化 → 刷新 rows → persist → drain；`aboutToDisappear` 增加 checkpoint 落库 |
| checkpoint 存储 Port | `harmony/entry/src/main/ets/platform_impl/ChatKvStore.ets`（复用） | 键 `checkpoint/<conversationId>` 存 parts 快照 + hash |

### 3.5 调用链（目标终态）
```
onStop() → abortController.abort()
  → provider 抛 CancellationException 等价 → generateAssistantOnce catch isAborted() → break
  → applyOnGenerationFinish(lastAccumulator.snapshot()) → finalMessages
  → runChatTurn 尾部 appendNode + store.save（已有）
  → ChatPage.then → rows = buildRows(out); sending=false; drainPendingQueue()
  → 附加：onStop 同步把「当前 streamingTail 末条」写入 checkpoint 键（防 then 未达即销毁）
aboutToDisappear() → 若 sending → 读 abortController.aborted? 部分快照 : 写 checkpoint 键 → 清理订阅
```

### 3.6 实现步骤
1. `stop_generation.ts`：移植 `finishPendingTools`、`finishReasoning`、`assistant finishedAt 补齐`的纯函数，输入 `UIMessage`，输出终态 `UIMessage`。单测覆盖「abort 时 reasoning 打开/工具 pending/无内容」三情形。
2. `stream_checkpoint.ts`：`streamContentLength`/`streamPartsHash`/`toolStateSnapshots` 纯函数 + 512 字符合并阈值常量。单测锁定 hash 稳定性。
3. `ChatPage.ets onStop`：在 `abort()` 后立即构造终态 `UIMessage`（用当前 `streamingTail` 末条 + `stop_generation` 函数），`rows` 刷新 + `getChatRepository().save` 会话，随后 `drainPendingQueue()`。保持 `convertSteerToFollowup` 既有逻辑。
4. `aboutToDisappear`：在 abort 后，若 `sending` 且存在部分快照，把 `streamPartsHash + contentLength + parts 计数`写入 `checkpoint/<conversationId>` KV；`aboutToAppear` 读回并展示「上次生成中断，可继续」提示（文案对齐 Android 中断提示，若 Android 无此文案则用诚实占位并登记）。
5. 生命周期归口：抽 `private teardownGeneration()` 供 onStop/失败 catch/页面销毁三处复用，消除散点 `sending=false`。
6. 设备验证：生成中切页 → 回页确认部分内容已持久化。

### 3.7 状态模型
```
sending: boolean（既有）
abortController: EntryAbortController | null（既有）
checkpoint: { conversationId, contentLength, partsHash, ts } | null（新增 @State）
lastInterrupted: boolean（新增，用于「上次中断」提示）
```

### 3.8 UI 尺寸 / 边距规范
- 「上次生成中断」提示条：高 36vp，INK3 文案 13sp，SURFACE2 底，圆角 8，位于消息区顶部（`padding top: statusBarHeight+58+8`），可点击关闭。

### 3.9 测试与构建验收
- [ ] `stop_generation.ts` / `stream_checkpoint.ts` 单测全绿。
- [ ] 设备：生成中按停止 → 部分文本保留且 reasoning 卡片闭合（时长显示）；切页后回页内容不丢。
- [ ] chat `tsc` clean + 双构建 SUCCESS + `git diff --check` clean。

### 3.10 subagent review 清单
- [ ] `finishedAt` 是否只在 assistant 消息且为 null 时补齐（对齐 ChatService:2434-2439）。
- [ ] checkpoint 键写入是否吞掉异常（checkpoint 永不阻断主流程）。
- [ ] `aboutToDisappear` 是否仍保持幂等（多次触发不重复写）。

### 3.11 回滚边界
- 仅改 stop 相关路径；`chat_turn.ts` 不修改（其 abort 语义 D-035 已固化）。
- 若 checkpoint 落库导致性能问题，仅移除「写入」环节，保留 `stop_generation` 收口。

---

## 4. Phase 3 — streaming pacing / 安全切片 / Markdown 性能 / reasoning 稳定

### 4.1 目标
- 把已移植的 `streaming_display.ts` pacing 纯逻辑接入 `ChatPage` 渲染：逐字 reveal、catch-up、终态 drain（对齐 `StreamingDisplay.kt` 的 `rememberStreamingDisplayText` 语义）。
- 流式文本使用安全切片（`safeStreamingDisplayEnd`/`streamingImmediateDisplayText`），避免截断 emoji/ZWJ/combining mark。
- Markdown 增量解析缓存：流式期间避免每 chunk 全量 `parseMarkdown`，对齐 Android `MessageRenderCache.markdownParseResult` + `deferStreamingMarkdownParse`。
- Reasoning 稳定性：reasoning 流式期间卡片状态不抖动、结束闭合时 `finishedAt` 稳定。

### 4.2 非目标
- 不做 Mermaid / LaTeX / WebView（登记设备后）。
- 不做 AST 渲染器替换（保持 `MarkdownText.ets` 原生组件树方案）。
- 不做 `StreamingCharRevealClock` 的 Compose 帧驱动等价物之外的动画参数调优（设备验证后按需）。

### 4.3 现状证据
- `streaming_display.ts` 已含全部 pacing 纯函数，但 entry 无任何引用（grep 验证）。
- `ChatPage.ets:443-461` text part 直接 `MarkdownText({content})`，无 pacing、无切片、无缓存。
- `MarkdownText.ets` 每帧 `parseMarkdown` 全量重解析（无缓存）；Android 用 `MessageRenderCache.markdownParseResult` + `deferStreamingMarkdownParse`（ChatListNormalSection.kt:591-640）。
- `reasoning_display.ts` D-053 已就绪；`ChatMessageReasoning` 流式期间状态迁移有基础但需与 pacing 配合。

### 4.4 具体文件与函数
| 动作 | 文件 | 位置 |
|---|---|---|
| streaming pacing 驱动 | `harmony/entry/src/main/ets/components/MarkdownText.ets` | 新增 `@Prop streaming`，接 `streamingDisplayTargetSpeed`/`safeStreamingDisplayEnd`；用 `setInterval` 定时器驱动可见长度（ArkUI 无 `withFrameNanos`，登记等价物） |
| 切片接入 | `harmony/entry/src/main/ets/pages/ChatPage.ets` | `MessageRow` 文本渲染传入 `streaming ? streamingImmediateDisplayText(...)` 后的安全子串 |
| Markdown 解析缓存 | `harmony/chat/src/main/ets/chat/markdown_cache.ts` | 新建：`{key: messageId+offset, blocks}` 缓存 + `streamingImmediateDisplayText` 前缀命中判定；从 `MessageRenderCache` 语义移植 |
| defer 解析 | `harmony/entry/src/main/ets/pages/ChatPage.ets` | `userScrollInTimeline && followMode==PausedForUser` 时暂停解析（对齐 ChatListNormalSection.kt:591-640） |
| reasoning 稳定 | `harmony/entry/src/main/ets/components/ChatMessageReasoning.ets` | 流式 `finishedAt` 以 `stream_accumulator` 终态为准；duration 用 `finishedAt-createdAt` 计算，不随 chunk 抖动 |

### 4.5 调用链（目标终态）
```
onUpdate → streamingTail 更新
  → MessageRow(streaming=true) → MarkdownText streaming pacing：
      visibleLen = pacing(visibleLen, backlog=content.len-visibleLen, streaming)
      slice = safeStreamingDisplayEnd(content, visibleLen)
      render(slice)
  → 生成结束 streaming=false → 终态 drain（FINAL_TARGET_DRAIN_SECONDS=0.18）
  → markdown_cache 命中前缀 → 只解析增量块
```

### 4.6 实现步骤
1. 先接 pacing：`MarkdownText.ets` 加 `@Prop streaming: boolean`，定时器每 ~16ms 调 `streamingDisplayTargetSpeed` 计算可见长度并 `safeStreamingDisplayEnd` 切片；组件销毁清定时器。纯文本行场景优先（代码块/表格块仍全量渲染，登记）。
2. `markdown_cache.ts` 纯逻辑 + 单测：前缀命中、失效、容量上限（对齐 `MarkdownPrewarmMaxTexts=32` 的规模语义）。
3. ChatPage 把 `streaming` 传透到 MessageRow/MarkdownText；`deferStreamingMarkdownParse` 判定接入（复用 P1 的 `followMode`）。
4. `ChatMessageReasoning` 校验：`duration` 字段在流式期间显示「…」秒，终态后显示固定秒数；`finishedAt` 用终态 snapshot。
5. 性能设备验证：长消息流式下 UI 不掉帧、切 tab 回来不闪。

### 4.7 状态模型
```
MarkdownText 局部：visibleLen、timer 句柄
markdown_cache：Map<key, blocks>（模块级单例或 KV，容量 32 条）
deferStreamingParse: boolean（派生自 followMode/userScrollInTimeline）
```

### 4.8 UI 尺寸 / 边距规范
- 无新增尺寸常量；沿用 `TimelineItemSpacing=14vp`、正文 `fontSize 15 / lineHeight 1.6`（MarkdownText.ets 既有）。
- pacing 常量沿用 `streaming_display.ts` 逐字：BASE 72 cps、MAX 1500/2400 cps、TARGET_DRAIN 0.90/0.18s、BACKLOG 1200、EMIT 8/20。

### 4.9 测试与构建验收
- [ ] `markdown_cache.ts` 单测：前缀命中返回、offset 递增失效、容量上限。
- [ ] `streaming_display.ts` 既有测试全绿（不改纯逻辑）。
- [ ] 设备：流式长文本逐字出现且 emoji 不截断；reasoning 卡片 duration 终态稳定。
- [ ] 双构建 SUCCESS + `git diff --check` clean。

### 4.10 subagent review 清单
- [ ] pacing 是否只在 `streaming=true` 时激活（settled correction 立即显示，不重新 reveal）。
- [ ] 切片是否总是走 `safeStreamingDisplayEnd`（无裸 `substring`）。
- [ ] `deferStreamingParse` 是否只在 `PausedForUser` 时暂停（`FollowingBottom` 继续解析）。
- [ ] 定时器是否在 `aboutToDisappear` 清理（无泄漏）。

### 4.11 回滚边界
- 仅改渲染层；不改 `stream_accumulator.ts`/`chat_turn.ts`。
- 若 pacing 定时器在真机造成卡顿，可回退为「非流式切片 + 无逐字」（性能登记），保留安全切片与缓存。

---

## 5. Phase 4 — 输入栏 / 键盘 / 安全区 / 生成中排队 / 模型刷新 / Context 用量 / 错误提示

### 5.1 目标
- 键盘升起时列表跟随（对齐 `ImeAutoScroller`：delta>0 且 shouldScroll 才滚，收起不反向滚）。
- 沉浸式安全区：消息区/输入栏避开状态栏 + 底部导航条（`expandSafeArea` 语义修正，当前 `EntryAbility.setWindowLayoutFullScreen(true)` 后输入栏可能被底部条遮挡）。
- 生成中排队状态完整可见：pending chips 可逐条取消、队列持久化（D-048/D-052 已就绪，补齐取消/计数 UI）。
- 模型切换后刷新当前会话的 context 窗口/用量（`resolveChatProviderChoice` 变化时刷新 `contextWindowTokens`）。
- Context 用量指示器补齐：`ChatInputUsage`/`ContextRing` 接入既有 `contextUsageRatio`（ChatPage 已计算）。
- 错误提示可见性：`errorMsg` 以 Android 文案为准 + 可关闭。

### 5.2 非目标
- 不做 `/usage` 命令、用量配额卡（`ComposerUsageMetric`/五小时配额，后端缺失）。
- 不做 `accentColor/fontFamily` 选择器（设备渲染验证项）。
- 不做 TTS 引擎。

### 5.3 现状证据
- `ChatPage.ets` 无任何 `expandSafeArea`/`keyboardAvoidMode` 处理（grep 无命中）；`EntryAbility.ets:79` 已 `setWindowLayoutFullScreen(true)`，状态栏内边距用手动 `statusBarHeight`。
- 排队 chips 已有（D-048，:3102-3170 `Scroll()`），取消接线在 `syncPendingRows`/`pendingQueue`；持久化已有 `pending_queue_store.ts`。
- `contextUsageRatio`（:959-975）+ `contextWindowTokens`（:823）已计算，但 `ChatInputUsage`/`ContextRing` 组件是否接入需核对（有独立组件文件，ChatPage 内为 5 段柱 HeaderMeter）。
- `errorMsg` 在 `errorMsg.length > 0` 时显示文本（:3050 附近），无可关闭按钮。

### 5.4 具体文件与函数
| 动作 | 文件 | 位置 |
|---|---|---|
| IME 跟随 | `harmony/entry/src/main/ets/pages/ChatPage.ets` | 监听 `window.on('keyboardHeightChange')` 或 `avoidArea`；delta>0 时 `scroller.scrollBy` 对齐 ImeAutoScroller |
| 安全区 | `harmony/entry/src/main/ets/pages/ChatPage.ets` + `ChatInput.ets` | 输入区 `expandSafeArea([SafeAreaType.SYSTEM])` 或底部 padding 加导航条高度；消息区 `List` 底部 padding 含 `navBarHeight` |
| 排队 chips 增强 | `ChatPage.ets`（pending rows 区） | 每条 chip 加取消按钮（复用 D-048 结构），空队列自动收起 |
| 模型刷新 | `ChatPage.ets` | `TopModelMenu`/`selectModelFromMenu` 回调后重跑 `resolveChatProviderChoice` → 更新 `contextWindowTokens` + `refreshContextCompacts` + `contextUsageRatio` |
| 用量指示 | `ChatPage.ets` | 5 段柱点击弹 ContextUsage 详情（对齐 `ChatInputUsage.kt` 的 percent/detail）；组件复用 `ContextRing` |
| 错误提示 | `ChatPage.ets` | `errorMsg` 改为可关闭条（右上 ×），关闭后 `errorMsg=''` |

### 5.5 调用链（目标终态）
```
键盘升起 → window keyboardHeight 变化 → delta>0 && canSendFocus? → scrollBy(delta)（ImeAutoScroller 语义）
模型切换 → selectModelFromMenu → resolveChatProviderChoice → contextWindowTokens 更新
  → contextUsageRatio 重算 → ContextMeter/详情更新
生成中发送 → enqueuePendingParts → pendingQueue 更新 → syncPendingRows（既有）
  → chip × 点击 → cancelPending(id) → persistQueue + syncPendingRows
错误 → errorMsg 设置（既有）→ 用户点 × → errorMsg=''
```

### 5.6 实现步骤
1. IME：`EntryAbility` 或 `ChatPage.aboutToAppear` 注册 `keyboardHeightChange` 监听，增量 `scrollBy`；`aboutToDisappear` 注销。
2. 安全区：核对 `win.setWindowLayoutFullScreen(true)` 下 `getWindowAvoidArea` 的 bottom 值，输入栏容器加 `expandSafeArea(SafeAreaType.SYSTEM)`；消息区底部 padding = `navBarHeight + TimelineBottomSafetyPadding(28vp)`。
3. 排队：确认取消接线完整，chip 加删除态；队列为空时区段隐藏（`pendingQueue.length>0` 条件）。
4. 模型刷新：在 `selectModelFromMenu`/`handleReasoningLevelChange` 成功回调后重取 choice，刷新 context window；同步刷新 modelLabel。
5. 用量：把既有 5 段柱改为可点击，弹 `bindSheet` 显示 percent + 详情文本（对齐 ChatInputUsage.kt 的 title/metrics/percent/detail 结构子集）。
6. 错误：加关闭按钮 + 入场动画（`TransitionEffect.OPACITY` 或 animateTo）。

### 5.7 状态模型
```
keyboardHeight: number（监听）
navBarHeight: number（一次读取）
pendingQueue: PendingUserMessage[]（既有）
contextWindowTokens: number | null（既有）
errorMsg: string（既有）+ errorDismissed 派生
```

### 5.8 UI 尺寸 / 边距规范
| 项 | 值 | 出处 |
|---|---|---|
| 输入栏 bottom padding | `安全区底部(导航条) + 10vp` | Android ChatInput 沉浸式基准 |
| 消息区底部 padding | `navBarHeight + 28vp` | TimelineBottomSafetyPadding |
| 排队 chip | 高 30vp，radius 15，SURFACE2/LINE，取消图标 14 | 既有 chips 风格 |
| 错误条 | 高 40vp，ERROR 文字 13sp，右 × 20vp | Android ErrorCard 语义 |
| ContextMeter | 5 段柱，高 14vp，间距 2vp | ChatPage 既有（D-110） |

### 5.9 测试与构建验收
- [ ] 设备：键盘升起消息区跟随；输入栏不被底部导航条遮挡；模型切换后用量/窗口刷新；排队 chip 取消生效；错误条可关闭。
- [ ] 双构建 SUCCESS + `git diff --check` clean + chat 测试全绿。

### 5.10 subagent review 清单
- [ ] IME 只滚增量不反向（对齐 ImeAutoScroller 注释的「发送后关闭键盘不回跳」）。
- [ ] 排队取消调用 `persistQueue` 并处理 KV 失败。
- [ ] 模型刷新不打断正在进行的生成（`sending` 时跳过刷新或仅更新展示）。
- [ ] 安全区改动不影响既有头部 `statusBarHeight` 计算。

### 5.11 回滚边界
- 仅 UI 层；不改 `pending_queue.ts` 纯逻辑。
- 若键盘监听 API 在模拟器不可用，降级为「不滚动」（登记 ArkUI 限制），安全区/排队/用量仍交付。

---

## 6. Phase 5 — 历史分页 / 懒加载 / 长会话性能

### 6.1 目标
- 会话打开默认只加载尾部窗口（如最近 50 条），向上滚动加载更早历史（对齐 Android `loadOlderTimelinePage`/`ensureTimelineLoaded` + `ConversationTimelineLoadState`）。
- `ForEach` 全量构建替换为 `LazyForEach`（IDataSource），消除长会话首帧全量渲染。
- 流式期间延迟解析更早历史消息的 Markdown（`deferStreamingMarkdownParse` 已接 P3，此处扩展到历史页）。
- 首屏定位到 `initialMessageIndex` 时跨越分页边界自动加载（对齐 `ChatPage.kt:327-337`）。

### 6.2 非目标
- 不做 Android 的完整 `ConversationTimelineLoadState` 事件投影（只做加载状态四元组）。
- 不做虚拟化 item 拆分（`ChatMessageVirtualItems` 的头/正文/尾部三段化），保持整条 MessageRow 为 item。
- 不做 FTS 全文索引（Android 工具侧 P1）。

### 6.3 现状证据
- `ChatPage.ets:2923` `ForEach(this.rows)` + 注释「LazyForEach 替换 ForEach 需 IDataSource 重构（18+ 处 rows 赋值）」。
- `ChatRdbRepository.ets:244-258` 已支持 `limitAs(limit)` + `orderByDesc(update_at)`；`listRows` 无 offset 语义，需加分页参数。
- Android：`ChatVM.ensureTimelineLoaded`（:349）→ `chatService.loadOlderTimelinePage`（:352）；`ChatListNormalSection.kt` 顶部历史加载 item 触发 `onLoadOlderTimeline`。

### 6.4 具体文件与函数
| 动作 | 文件 | 位置 |
|---|---|---|
| 分页加载纯逻辑 | `harmony/chat/src/main/ets/chat/timeline_load.ts` | 新建：`ConversationTimelineLoadState`（initialized/fullyLoaded/oldestLoadedIndex/loadedNodeCount）移植 |
| RDB 分页 | `harmony/entry/src/main/ets/platform_impl/ChatRdbRepository.ets` | `listRows` 增加 `offset` 参数；新增 `listWindow(conversationId, offset, limit)` 读 node 窗口 |
| LazyForEach | `harmony/entry/src/main/ets/pages/ChatPage.ets` | `rows` 改 IDataSource（`BasicDataSource` 子类），`LazyForEach(dataSource, ...)` |
| 历史加载触发 | `ChatPage.ets` | `List.onReachStart` 或首 item 可见时 `loadOlderTimeline()` |
| 首屏跨界定位 | `ChatPage.ets` | 若目标 index < 已加载窗口起点，先加载历史页再定位 |

### 6.5 调用链（目标终态）
```
打开会话 → loadWindow(conversationId, 0, PAGE=50) → timelineLoadState.initialized=true
  → 向上滚动到首 item → onReachStart → loadOlderTimelinePage → offset += 50 → 前插 rows
  → 流式 onUpdate 仅在窗口尾部追加（不重载历史）
initialMessageIndex 定位 → 若 index < oldestLoadedIndex → 加载历史直至覆盖 → scrollToIndex
```

### 6.6 实现步骤
1. `timeline_load.ts` 纯逻辑 + 单测：状态四元组迁移（未初始化→加载中→已加载→fullyLoaded）。
2. `ChatRdbRepository` 加 `listWindow`（按 `update_at` 倒序窗口读取 node 行 + message JSON），注意与 `buildRows` 的映射。
3. ChatPage 引入 `@State timelineLoadState` + `@State rows` 改为 dataSource 容器；`LazyForEach` 替换 `ForEach`（list 结构不变，item 仍为 MessageRow）。
4. `onReachStart` 触发加载历史；加载态在首部渲染「加载更早消息…」占位 item（对齐 `HistoryLoadingItemKey`）。
5. 首屏定位跨界：aboutToAppear 时若 `initialMessageIndex >= 已加载窗口` 则先扩窗。
6. 设备验证：100+ 消息会话首帧流畅度、向上翻页加载无闪烁、流式期间不卡。

### 6.7 状态模型
```
timelineLoadState: { initialized, loadingOlder, fullyLoaded, oldestLoadedIndex, loadedNodeCount }
rows: BasicDataSource<MsgRow>（LazyForEach 数据源）
PAGE_SIZE = 50（常量，对齐 Android 会话分页规模）
```

### 6.8 UI 尺寸 / 边距规范
- 历史加载占位 item：高 48vp，居中 META 文案「加载更早消息…」+ 转圈（DotLoading）。
- 其余沿用 P1/P3 常量。

### 6.9 测试与构建验收
- [ ] `timeline_load.ts` 单测：窗口边界、fullyLoaded 判定、跨页定位。
- [ ] 设备：长会话（100+）首帧流畅；上滑加载历史；流式期间历史区不重渲染。
- [ ] 双构建 SUCCESS + `git diff --check` clean。

### 6.10 subagent review 清单
- [ ] `LazyForEach` key 稳定（nodeId 派生，不随分页重排漂移）。
- [ ] 历史加载不破坏 `streamingTail` 尾部追加逻辑。
- [ ] 定位跨越分页边界时加载逻辑不递归死循环。
- [ ] `cachedCount(5)` 保留，且新 dataSource 不破坏既有 `divider`/overlay。

### 6.11 回滚边界
- 若 IDataSource 重构风险过高，可分两步：先交付「RDB 窗口加载 + 上滑加载历史」（仍 ForEach），LazyForEach 单独 commit 验证；任一 commit 可独立 revert。

---

## 7. Phase 6 — 抽屉 / 消息手势 / 附件与剩余产品完善

### 7.1 目标
- `ChatDrawer` 接入 ChatPage（左上 ≡ 按钮打开），含导航入口 + 会话列表（复用 `ChatListGrouped`）+ 搜索 + 头像/设置。
- user 消息手势：单击编辑、长按打开 ActionsSheet（对齐 `ChatMessage.kt:148`）。
- 附件闭环：`file://` 私有目录存储评估 + encode 期压缩（PARITY_DEBT PD-007 残余）+ 会话删除附件清理接线。
- 剩余产品完善：`video/audio/document` 附件 chip 已在（M2.2），补 `ChatMessageActionsSheet`（复制/重生成/TTS/更多）组件化。

### 7.2 非目标
- 不做 Mermaid/LaTeX/WebView/GenerativeWidgetCard（登记设备后）。
- 不做 TTS 引擎、FloatingWindow。
- 不做 `accentColor/fontFamily` 选择器。

### 7.3 现状证据
- `ChatDrawer.ets` 组件已存在但 ChatPage 未接线；`ChatListGrouped.ets` 已实现分组会话列表（含置顶/日期/选中态/长按菜单）。
- ChatPage 消息操作按钮（复制/重生成/分叉/编辑）由 `ChatMessageActionFooter` 提供（:533），但 user 消息无单击编辑/长按 sheet。
- PD-007：选图 data: URL 直存消息体（不走 file:// 私有目录）；D-076 upload 落盘图 encode 期不压缩；会话删除清理已接线（D-096/D-097），composer removePart/avatar 清理仍 P1。

### 7.4 具体文件与函数
| 动作 | 文件 | 位置 |
|---|---|---|
| 抽屉接入 | `harmony/entry/src/main/ets/pages/ChatPage.ets` | 头部左上加 ≡ 按钮；`@State drawerOpen`；叠 ChatDrawer（含 conversationList Builder 注入 ChatListGrouped 数据） |
| user 手势 | `harmony/entry/src/main/ets/pages/ChatPage.ets`（MessageRow） | user text 块加 `onClick`→`startEditMessage`、`LongPressGesture`→ActionsSheet |
| ActionsSheet | `harmony/entry/src/main/ets/components/ChatMessageActions.ets` | 已有组件（拆分自 ChatPage），补齐 sheet 完整项（复制全部/重生成/fork/编辑/TTS 占位） |
| 附件压缩评估 | `harmony/chat/src/main/ets/chat/image_compress.ts`（既有）+ ChatPage | 评估 file:// encode 期压缩（PD-007 消除条件）；评估私有目录存储替代 data: URL |
| 附件清理 | ChatPage `composer removePart` / avatar 场景 | 删除时清理关联本地文件（复用 `deleteChatFiles`/`FilesRepository`） |

### 7.5 调用链（目标终态）
```
≡ 点击 → drawerOpen=true → ChatDrawer（show=true）渲染
  → 会话点击 → router.pushUrl(ChatPage?conversationId=...) → drawerOpen=false
user 消息点击 → startEditMessage（编辑态回填）
user 消息长按 → ChatMessageActionsSheet 弹出（复制/重生成/fork/编辑/TTS 占位）
选图 → 压缩物化（既有）→ 可选 file:// 私有目录落盘 → encode 期压缩 → 消息体引用 file://
删除图片 part → deleteChatFiles(关联文件)
```

### 7.6 实现步骤
1. ChatPage 头部（`Icon chevron_left` 左侧）加 ≡ 按钮或复用现有头部左侧区域；接入 ChatDrawer，`conversationList` Builder 用 ChatListGrouped（数据来自 `repo.list()` 快照 + 进入刷新）。
2. MessageRow 的 user text 块包 `gesture(GestureGroup(Exclusive, LongPressGesture, TapGesture))`：tap→edit、long→actions sheet（对齐 ChatMessage.kt:148 语义）。
3. 完善 `ChatMessageActions.ets` sheet 项（对照 `ChatMessageActionsSheet` 的 Android 行为）；TTS 项保留占位（引擎 P1）。
4. 附件：spike 评估 `file://` 私有目录（`filesDir` + fileIo）替代 data: URL 的成本；若收益明确（会话 JSON 体积）则实施，否则登记 PD-007 决策。encode 期压缩：D-076 落盘图在 `encodeImageDetailed` 前走 `ImageCompress` 管线。
5. `composer removePart` 清理关联本地文件（若实施 file:// 存储）。
6. 设备验证：抽屉开合/导航/搜索过滤；user 长按 sheet；大图发送体量验证。

### 7.7 状态模型
```
drawerOpen: boolean
showActionsSheet: { nodeId } | null
pendingImages 删除回调 → deleteChatFiles
```

### 7.8 UI 尺寸 / 边距规范
| 项 | 值 | 出处 |
|---|---|---|
| 抽屉宽 | 70%（既有 ChatDrawer） | ChatDrawer.ets:103 |
| ≡ 按钮 | 24x24 icon，头部左缘 20vp gutter | 对齐 chevron_left 位置 |
| ActionsSheet | 底部 sheet，项高 52vp，分隔 hairline | ChatMessageActions Android 基准 |
| 附件 chip | 既有 ContentAttachmentChip 尺寸不变 | M2.2 |

### 7.9 测试与构建验收
- [ ] 设备：抽屉可开合 + 会话列表分组/置顶/搜索；user 长按 sheet 全项可用；附件发送/删除闭环。
- [ ] `image_compress` 单测保持全绿（若有 encode 期压缩改动则补测试）。
- [ ] 双构建 SUCCESS + `git diff --check` clean。

### 7.10 subagent review 清单
- [ ] 抽屉开关不阻塞主消息流操作（overlay 分离）。
- [ ] user 长按/单击互斥（GestureGroup Exclusive）。
- [ ] 附件 file:// 实施时消息体引用可序列化（不泄露绝对路径跨设备语义）。
- [ ] 清理链路幂等（重复删除不炸）。

### 7.11 回滚边界
- 抽屉接入独立 commit，可单独回滚（仅 UI 层）。
- 附件存储形态变更（data: URL → file://）是 PD-007 决策点，需在 PARITY_DEBT 更新后再实施；若评估否决则保持 data: URL 并只做 encode 期压缩。

---

## 8. 全局门禁与收尾

每 phase 结束时执行：
1. 门禁命令（§1.3）全绿。
2. 更新 `MIGRATION_STATE.yaml`（progress/parity_baseline）、`DECISION_LOG.md`（新 D- 编号）、`PARITY_DEBT.md`（涉及 PD-006/PD-007/PD-011 的状态更新）。
3. subagent 四维 review：语义等价（对照 Android 锚点）/ 精准修改（无越界抽象）/ ArkTS 禁忌（as unknown/对象 spread/for...in/保留名 enabled|size）/ 诚实标注（未设备验证项不宣称完成）。
4. `git diff --check` clean。

### 依赖关系
```
P1(滚动) ──→ P3(defer 解析依赖 followMode)
P2(停止/checkpoint) ──→ P4(排队/生命周期归口)
P4 ──→ P6(输入栏/附件收口)
P1 ──→ P5(分页依赖滚动跟随正确性)
P3 ──→ P5(历史 markdown 延迟解析)
```

### 风险与缓解
| 风险 | 缓解 |
|---|---|
| ArkUI 无 `withFrameNanos`/`snapshotFlow` 等价 | 用定时器/`setTimeout(0)`/`onDidScroll` 驱动；语义等价登记 |
| `LazyForEach` IDataSource 重构波及 18+ 处 rows | P5 分两步（先分页后 LazyForEach），各自可回滚 |
| RichText 布局孤岛问题复发 | 保持 `MarkdownText` 原生组件树；pacing 只作用于 text 行 |
| 键盘监听 API 模拟器受限 | P4 降级不滚动 + 登记 |
| 停止收口与既有 abort 语义冲突 | 不修改 `chat_turn.ts`；只做 ChatPage 侧收口 + checkpoint |
