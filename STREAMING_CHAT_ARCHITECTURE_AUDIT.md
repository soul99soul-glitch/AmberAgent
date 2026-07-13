# 流式聊天交互链路:分层架构 + 审计报告(2026-07-04)

> 由三个独立 subagent 从零审计(不受修复历史污染),汇总而成。

---

## 一、分层架构(对齐 Android L1-L4)

### Android 参考分层

| 层 | 职责 | Android 实现 |
|---|---|---|
| **L1 缓冲层** | token 进入缓冲池,平滑释放(不快不慢) | ChatViewModel 流式合并 |
| **L2 Markdown 渲染层** | 流式增量解析,partial 容错 | Compose 原生 + cmark partial |
| **L3 滚动跟随层** | 三态机(Idle/Following/Paused) | LazyListState + scrollBy/scrollToItem |
| **L4 动画/视觉层** | 逐词淡入、高度过渡、胶囊展开 | Compose animation |

### iOS 当前分层现状

| 层 | 职责 | iOS 实现 | 健康度 |
|---|---|---|---|
| **L1 缓冲** | delta 到达后合并/节流 | `scheduleScrollToBottom` 24ms coalescing + `ChatStableStreamingMarkdownController` 160ms 节流 | ⚠️ 节流存在丢任务(见 L1-2) |
| **L2 Markdown 渲染** | 流式解析→格式化→显示 | `ChatStableStreamingMarkdownView` + vendor `MarkdownParserImpl` | 🔴 多处硬伤(见 L2 节) |
| **L3 滚动跟随** | 三态机 + pause/resume + scrollToBottom | `FollowMode` + `onScrollGeometryChange` + `ScrollViewProxy` | 🔴 假 pause + 不归位(见 L3 节) |
| **L4 动画/视觉** | 逐词淡入、胶囊展开、消息过渡 | vendor CADisplayLink + SwiftUI withAnimation | ⚠️ 色彩破坏 + 不同步(见 L4 节) |

---

## 二、硬伤清单(按严重度排序)

### 🔴 严重(破坏核心功能)

#### S1. 流式 delta 触发假 pause(距离误判为用户上滑)

**位置**: `ChatCollectionMessageList.swift:195-224`(onScrollGeometryChange action)

**根因**: `onScrollGeometryChange` 只检测"距底部距离",无法区分"用户上滑"与"内容增长"。流式 delta 上屏时内容一帧长出 >180pt → distance 超阈值 → 误判为用户上滑 → 转 pausedForUser → 跟随停止。**用户根本没碰屏幕就跟不动了。**

`onScrollGeometryChange` 的 action 没读 `programmaticScrollActive`、没读 `pendingBottomFollowTask`、没读 `isGenerationActive` 做校验。

**对照**: Android 在 streamChunk 和 scrollProgressChanged 里都校验 `userScrollInTimeline && !programmaticScrollInProgress`。

**修复方向**: pause 判定叠加 (a) `programmaticScrollActive`/pending task 状态;(b) distance 持续 >阈值达 N ms(消抖);(c) 真正的 drag 信号(`onScrollPhaseChange` iOS 18+)。

---

#### S2. 会话切换/进入不归位(guard 静默吞掉滚动)

**位置**: `ChatCollectionMessageList.swift:382-388`(conversationLoaded/Switched)

**根因**: `followMode = .idle` 后调 `scheduleScrollToBottom(animated:false)`,但 guard `force || canAutoFollow || animated` 三者全 false(idle 态 canAutoFollow=false)→ **不滚**。首次进入/切换会话时归位被吞。

叠加:onAppear 时刻几何未就绪(viewportHeight=0, bottomAnchor 未实例化),proxy.scrollTo 找不到目标 → no-op。非生成中的历史会话 `isGenerationActive=false` → 永不补发归位。

**修复方向**: conversationLoaded/Switched 的 `scheduleScrollToBottom` 传 `force: true` + 加重试/延迟(等 LazyVStack 实例化 bottomAnchor 后再滚)。

---

#### S3. 流式 Markdown partial 重写完全未生效

**位置**: `MessageBubbleView.swift:705` → vendor `MarkdownParser.swift:25`

**根因**: `parseNow` 调 `parser.parse(text:config:)`,内部走 `parse(text:)` → `parse(text:option:.init(speculativeRewrite:false))`。`speculativeRewrite:false` 意味着 `PartialTableMarkupPostParsingRewriter`/`PartialStrongMarkupPostParsingRewriter` **永不执行**。

**后果**: 半截表格 `| Col1 | Col2\n|---` 不会降级成段落,被解析成含 `|` 字面量;`**未闭合加粗` 会把后续所有文字加粗。下一帧补齐后才整块重排 → **表格/强调出现瞬间大跳变**。

**修复方向**: 流式场景 `parseNow` 改用 `parser.parse(text:option:.init(speculativeRewrite:true))`。

---

#### S4. 流式↔完成态渲染器排版系统性不一致 → 完成时高度跳变

**位置**: `MessageBubbleView.swift:602`(流式用 SwiftStreamingMarkdown) vs `:618`(完成用 AmberMarkdownView)

**根因**: 两个渲染器对同一内容的排版参数系统性不同:

| 参数 | 流式(SwiftStreamingMarkdown) | 完成(AmberMarkdownView) |
|---|---|---|
| 表格列宽 | max(平均宽, 300) | min(ideal, 300) |
| 表格 cell 字号 | body(17pt) | header .subheadline / body .body |
| 列表 bullet | circle.fill 4pt + spacing 1 | Text("•") + spacing 8 |
| 代码块 | vendor CodeBlockView | AmberMarkdownView renderCodeBlock(完全不同) |

完成瞬间整棵子树替换 → **高度/排版瞬间跳变**。

**修复方向**: 统一两个渲染器参数,或完成态也继续用 SwiftStreamingMarkdown(取消渲染器切换)。

---

#### S5. 逐词淡入破坏 bold/link 强调色

**位置**: vendor `ParagraphUIView.swift:349-353`

**根因**: 淡入在 `.foregroundColor` alpha 上做。新增文字里若有 bold/link 各自带不同 foregroundColor,淡入期间这些颜色全被改成半透明版本 → **动画中链接蓝色变浅蓝、加粗色变淡**,结束才恢复。

---

#### S6. `programmaticScrollActive` 在活路径恒 false,守护失效

**位置**: `ChatCollectionMessageList.swift:126`(声明)、510/523(仅死分支 set/clear)

**根因**: `programmaticScrollActive` 只在 `scrollUIScrollViewToBottom`(死代码,resolvedScrollView=nil)内维护。主路径 `proxy.scrollTo` 完全不触碰它。`markUserDragStarted:553` 读它但本身也是死代码。

**后果**: S1 的假 pause 无法被 `programmaticScrollActive` 守护拦截。

---

### 🟡 中等(逻辑不闭环/显著死代码/性能问题)

#### M1. 生成结束永远不回 idle,无 end-settle

**位置**: `ChatCollectionMessageList.swift:424-429`

generationCompleted 等事件只发 scroll,不改 followMode。followMode 在首次生成后永久停在 followingBottom。对照 Android 有 16 帧 settle + 2 帧稳定底部才退出。**iOS 流式跟随稳定滞后真实底部 1-3 行。**

#### M2. 每个 delta 全量重解析,无增量

**位置**: `MessageBubbleView.swift:704-712` → `Document+.swift:12`

`parseNow` 每次 new `MarkdownParserImpl` + `Document(parsing:)` 全量 + 整树 `convert`。对几 KB 回复每 160ms 全量重做,主线程开销显著。

#### M3. 超长思考内容 isScrollEnabled=false 被裁无法查看

**位置**: `ChatMiscViews.swift:171`(isScrollEnabled=false) + `:125`(frame maxHeight)

短文本自适应 OK;长文本超过 maxHeight(180/260)被 frame 裁掉,UITextView 不滚动 → **内容永久裁切,用户无法读全文**。

#### M4. 文本流式增长无动画硬跳,下方消息瞬移

**位置**: `MessageBubbleView.swift:332`

`.animation(value: partAnimationKey)` 只覆盖 parts 结构变化(工具/图片增删),不覆盖 Markdown 文本内部高度增长。文本流式增长是**无动画硬跳**,下方消息瞬间下移。

#### M5. 死变量:scrollOffsetY / scrollContentSizeHeight / lastObservedScrollOffsetY

**位置**: `ChatCollectionMessageList.swift:129,134,135`

`scrollOffsetY`/`scrollContentSizeHeight` 仅有声明,从未写入或读取。`lastObservedScrollOffsetY` 写 4 处但无读取点。纯死代码。

#### M6. SwiftUI 路径 LOD 冻结渲染整套失效

**位置**: `liveMarkdownRenderingEnabled`(435-437) 恒 true + `frozenMarkdownSnapshot: nil`(362)

用户上滑看历史时,流式尾行不会被冻结,持续为不可见的新 token 重排 Markdown → 上滑查看历史性能差。SwiftUI 路径丢失了 LOD 优化。

#### M7. branchChanged 两条路径行为相反

SwiftUI 路径(389-401) force:true 滚底;UIKit 路径(coordinator 164-172, 404-406) `.none` 不滚。feature flag 切换时行为会变。

#### M8. 键盘弹起/收起时 SwiftUI 路径无 settle 协调

**位置**: 无键盘 inset 观察(SwiftUI 路径)

键盘动画期间 outerGeometry.size.height 连续变化多帧,每帧 publish + scheduleScroll,与键盘动画抢帧 → 列表抖动。UIKit 路径用 settleDelay 避免,SwiftUI 路径无此节流。

#### M9. 思考胶囊三路径动画时长不一(0.22 vs 0.28)

用户 toggle 用 0.22,自动展开/收回用 0.28,`.animation(value:showsBody)` 用 0.28。连续触发时动画曲线叠加/跳变。

### 🟢 轻微

- L1: `followDebugTick` 在 Release 下整块死代码
- L2: `ChatLiveTailBubble` 双重渲染(UIKit 死路径潜伏 bug)
- L3: `ChatSwiftUIScrollViewResolver` CADisplayLink 常驻轮询(与 onScrollGeometryChange 功能重叠)
- L4: `viewportState` 父子同名歧义
- L5: 逐词淡入仅"纯追加"路径生效,重排时直接替换

---

## 三、调用链路断裂点汇总

| 断裂点 | 位置 | 后果 |
|---|---|---|
| DragGesture 被 ScrollView 吞掉 | 241-245 | markUserDragStarted/Ended 永不触发,userDragging 恒 false |
| resolvedScrollView 永远 nil | 124,229-240 | scrollUIScrollViewToBottom 死代码,programmaticScrollActive 恒 false |
| conversationLoaded 滚底被 guard 吞 | 382-388 | 进会话不归位 |
| onAppear 几何未就绪 | 257-261 | proxy.scrollTo 找不到 bottomAnchor |
| onScrollGeometryChange 无程序滚动守护 | 195-224 | 内容增长被误判为用户上滑(S1) |
| speculativeRewrite:false | vendor | partial markdown 不容错(S3) |
| 渲染器切换无过渡 | 602↔618 | 完成时高度跳变(S4) |
| ChatSelfSizingInvalidationBridge 未被调用 | MessageBubbleView:715-774 | 死代码(真正失效桥在 ChatCollectionMessageList:2330+) |

---

## 四、修复优先级建议

按"用户感知 × 修复成本"排序:

1. **S1 假 pause** — pause 判定加程序滚动态守护 + 去抖(中等改动,但解决最痛的"跟不动")
2. **S2 不归位** — conversationLoaded 传 force:true + 延迟重试(一行 + 小逻辑)
3. **S3 partial 重写** — 改 speculativeRewrite:true(一行,消除表格跳变)
4. **S6 programmaticScrollActive** — 在 proxy.scrollTo 前后设置(S1 的前置依赖)
5. **M1 end-settle** — 生成结束加 settle 循环(对齐 Android)
6. **S4 渲染器一致性** — 统一参数或取消切换(改动大但消除完成跳变)
7. **M3 胶囊超长内容** — 动态切 isScrollEnabled 或嵌 ScrollView
8. **M5/M6 死代码清理** — 删死变量,恢复 LOD(或显式承认不做)
