# 三界面流式生成审查与修复记录 — 2026-07-22

> 分支:`feat/ios-provider-parity-claude` · 基线 commit:`d8eda9f5b`
> 范围:标准 Chat、模型议会(Council)、小说创作(NovelCreation)三个界面的流式生成体验
> 维度:向上滚动的自然无缝性 / 长文本生成性能 / 视觉抖动与闪烁
> 状态:审查完成 + 修复批次落地(未 commit),合并回归门禁 255 passed / 1 expected skip / 0 failed
>
> ⚠️ 本次工作全程有**另一个并行会话**在同一工作区落地「`.sizeChanges` 底锚统一」批次(未提交)。
> 两批改动互相配套(见 §6),装机验收必须作为同一批次,不要拆开。

---

## 1. 工作方法

按 `AGENT_WORKING_PRINCIPLES.md` 执行:先分层画机制地图,再按维度深挖嫌疑,关键结论逐条亲验代码定罪,修复走红→绿铁序。

- **探查阶段**:3 个只读探查代理并行绘制三条流式管线的机制地图(链路、容器、滚动所有权、动画、开关)。
- **审查阶段**:4 个只读审查代理(标准 Chat / 议会 / 小说 / 共享 Markdown 渲染层)按「滚动 / 性能 / 视觉」三维深挖,每条发现标注置信度(坐实 | 嫌疑)。
- **复核阶段**:所有进入修复清单的发现由主会话亲读代码复核证据链;两条审查结论在此阶段被**证伪**(见 §5)。
- **修复阶段**:3 个实施代理并行执行自包含任务书(背景事实 + 精确规格 + 红→绿验收 + 禁区 + 矛盾保险丝),每个 diff 亲自过目后合跑回归门禁。

## 2. 三界面机制异同(审查时点)

| 维度 | 标准 Chat(默认 clean-list) | 模型议会 | 小说创作 |
|---|---|---|---|
| 发布节奏 | 48ms + 每拍 ≤12 字符(`ChatStreamPresentationPacer`) | 48ms flush,无字符节流 | 48ms flush,无字符节流 |
| 列表容器 | ScrollView + LazyVStack(历史)+ eager 尾部 | ScrollView + **非 lazy** VStack(消息数有界:≤8 席 × ≤5 轮) | ScrollView + LazyVStack(历史窗口裁剪)+ eager 尾部 |
| 底部跟随 | 语义重锚 `scrollTo(id:)` + `distanceToBottom > 1` 去重 | 测量增长 → 0.08s 线性动画滚底(审查时**无**已到底去重) | `.defaultScrollAnchor(.bottom, for: .sizeChanges)`(07-21 收敛,最成熟) |
| 上滑降级 | 有(可见性驱动 `shouldSuspend` 冻结) | 审查时**没有** | 有(历史窗口 + 冻结快照) |
| 文本渲染 | 三界面共用 `ChatAssistantMarkdownView` + vendor SwiftStreamingMarkdown(TextKit1 append 快路径) | 同左(namespace `council:uuid`) | 同左(namespace `novel:session:<messageID>`) |

成熟度排序:小说 > 标准 Chat > 议会。议会是三者中最薄弱的界面。

## 3. 审查发现全清单

### 3.1 坐实并已修复(本批次,详见 §4)

| # | 严重度 | 界面 | 问题 |
|---|---|---|---|
| F-1 | P0 | 小说 | 讨论模式多步工具循环丢失前序步骤 assistant 文本(显示消失 + 不落库) |
| F-2 | P1 | Chat | `.error` 终态绕过 pacer,积压文本整段砸下 |
| F-3 | P1 | 议会 | 测量增长跟随缺「已到底」短路,与新加 `.sizeChanges` 锚构成双机制无互斥 |
| F-4 | P1 | 议会 | speaking 行在用户浏览历史时无渲染降级,48ms 全速解析+淡入 |
| F-5 | P1 | 小说 | fork 分支把 runID 置 nil → `hasEverStreamed` 推导失真,整分支继承正文退化到主线程同步解析冷路径 |
| F-6 | P2 | 共享 | `renderableCache`(12 槽)命中不进队尾,纯插入序驱逐会挤出活跃流条目 |

### 3.2 坐实但本批不修(记录在案,理由见 §7)

- **Chat 历史行同步主线程解析**:非最后一条 assistant 的历史行走 native fallback,`AmberMarkdownAstCache.result()` 在 SwiftUI body 内同步 parse(vendor 流式路径才有 `Task.detached`)。切会话回来滚动看长历史时逐条同步解析。
- **Chat LOD 死代码**:`liveMarkdownRenderingEnabled(for:)` 全仓零调用;真正生效的是可见性驱动的 `ChatSwiftUIStreamingTailRenderPolicy.shouldSuspend`。调 `liveRenderingFarFromBottom` 阈值无任何效果(调试黑洞)。按守则只标记不删。
- **小说 `project()` 全量投影**:每 48ms flush 投影缓存必失效,几百条历史全量重投影,`historyWindowLimit` 只裁视图层不裁计算量。
- **小说长章节无增量 AST**:`parseNow` 每 delta 对全量累计文本重新 parse;单次解析超 48ms 后 latest-wins 令可见更新退化为「越来越大的整段跳」。已知基线散文 44.7ms/delta @24KB,长章节按比例恶化。
- **表格流式结构性成本**:`Table.convert` 每次 parse 无条件 `self.format()` 全表反序列化(仅为 copy 用的 rawMarkdown,但在后台线程);SwiftUI `Layout` 协议无增量测量,任何一行增减级联全表 `sizeThatFits` 重跑(表格 79.3ms/delta 的构成)。
- **议会输出预算上限 40,000 字符** 高于已知 24KB 性能拐点近一倍(放大共享渲染成本,非独立缺陷)。
- **vendor `Markup.id` 潜伏缺陷**:循环体重复 append `self.indexInParent`,与注释不符;已穷举当前所有消费点均为真兄弟节点,不碰撞,零现实影响。
- **代码高亮全局单 JSContext**:规避多 JSContext OOM 的有意设计(注释引用 crash ID),三界面并发高亮串行排队。
- **`ChatStreamingMarkdownTableView` 死代码**(零调用),只标记。

### 3.3 嫌疑级(需真机/探针定罪后再动)

- Chat 12 字/拍对 CJK 的「≤一行」假设失效(12 个汉字视觉宽度≈整行,逐字退化逐行)。
- Chat clean-list 无键盘专属处理,`shouldReanchorAfterViewportShrink` 前置条件要求弹键盘前恰好贴底。
- 小说 workspace 分区切换(创作↔手稿↔设定)销毁 `NovelSessionView` 结构化身份 → 滚动/跟随/历史窗口状态清零;原生开关开启时存在「`.sizeChanges` 已关、driver 尚未 async attach」的一拍空隙。
- `.sizeChanges` 锚 × 用户 fling 减速中内容持续增长的时序交叠,现有探针(离散突发 33/120pt)未覆盖。
- 小说显式回底 0.2s 动画窗口内 `.sizeChanges` 同时活跃的轻微顿挫。
- Chat 背景完成/重进会话的滚动与流式尾态恢复时序(本轮未深入)。

### 3.4 审查阶段排除项(核实无问题)

行级 digest 均 O(1) 不哈希内容;议会非 lazy VStack 因消息数有界可接受;议会缓存 namespace 无 cell 复用不受占位误差影响;`isStreaming` 翻转的渲染树连续性三界面均已继承既有修复;三种淡入机制成本不随文档长度累积;小说 `followGeneration=false` 有底部按钮兜底不静默;小说历史窗口位移补偿逻辑自洽;`ChatWidthDrivenAspectRatio` 已覆盖图片路径;vendor `hasPrefix` 扫描仍是冷路径专属。

## 4. 修复批次(全部红→绿)

### 4.1 [P0] 小说讨论模式跨步文本丢失 — `NovelCreation/NovelLiveModelAdapter.swift`

**根因链(亲验)**:`IOSAgentToolEngine.swift:385` 每个 `streamStep` 新建 `StreamStepState`,`assistantText` 每步从空累积;`NovelLiveModelAdapter` 的 `discussionSearchTransport` 把每步文本包成 `replacementChunk`(全量替换语义)发布;终态只取最后一条 assistant(`lastAssistantText`)。三点叠加:模型在调 `search_web` 前说的话被下一步整体覆盖,且不落库。

**修复**(全部收在 adapter 层,引擎契约未动——它另有 `SubAgentRunner`、`IOSChatBackgroundGenerationCoordinator` 两个调用方):
- 新增 `DiscussionStepTextAccumulator`(NSLock 保护;`onAssistantTurnStarted` 在 MainActor、`onAssistantText` 在 KMP bridge 线程):利用引擎现成的 `onAssistantTurnStarted` 步骤边界提交前缀,每次发布 `(committedSteps + [currentStep])` 过滤空串后 `\n\n` 连接。
- `lastAssistantText(in:)` → `joinedAssistantText(in:)`(所有 assistant 消息按序拼接),`ask_user` 与 finalText 两个调用点同步替换,空串守卫保留。

**复核加验的一个隐患**:`result.messages` 含引擎输入。经查 `NovelGenerationLifecycle.modelMessages`(:1556)讨论请求只构造 `[system, user]` 两条、会话历史以文本注入 system prompt——joined 不会拼到历史回合。隐患不成立。

**红→绿**:新增 `testDiscussionTransportPreservesPreToolTextAcrossStreamingSteps`(脚本:step1 文本+工具调用 → step2 流式终文)。旧代码 4 条断言红(step2 流式把 step1 冲掉、终文缺 step1);修后 `NovelLiveModelAdapterTests` 17/17,四套件合跑 118/118。

### 4.2 [P1] Chat `.error` 终态 drain 对称化 — `ChatGenerationCoordinator.swift`

**根因(亲验)**:`.complete`(:1050)先 `drainStreamPresentation` 按 48ms/12 字逐拍追平积压再发终态;`.error`(:1069)直接 `setMessages(完整快照)`——长回复中途报错时,积压文本瞬间全量上屏 + 错误气泡 + 无动画滚底三重跳变。

**修复**:`.error` 分支插入与 `.complete` 完全对称的 `guard await drainStreamPresentation(to: snapshot, runId: runId) else { return }`(5 行)。原代码 setMessages 前无 await 不存在 run 被接管的窗口;引入 drain 后复用其 `currentRunId == runId` 循环条件作守卫——drain 返回 false 即 run 已被取消/切换,终态所有权在取消路径,不得再写 messages。

**红→绿**:`IOSParityRedLightTests` 新增顺序断言测试,旧代码红、修后 42/42。
**记账**:该测试类是**纯源码文本断言** harness(既有先例如此);`ChatGenerationCoordinator` 无事件注入测试座,drain 中途取消的行为级竞态无法在现有基础设施覆盖,需真机或后续新增测试座(需授权)。

### 4.3 [P1] 议会跟随「已到底」短路 — `CouncilChatRuntimeView.swift`

**根因(亲验)**:`CouncilTranscriptFollowPolicy` 只比较高度差 ±0.5pt,无距底判据(Chat 对照实现有 `distanceToBottom > 1` + `explicitBottomAnimationActive` 互斥)。并行会话给议会加上 `.sizeChanges` 锚后,锚已在同一布局事务内钉底,每次 48ms flush 仍无条件再发 0.08s 线性动画滚底——小说页 07-21 修复点名的「二次滚动命令重造底部欠账」反模式。

**修复**:`CouncilTranscriptScrollGeometry` 加 `isAtBottom: Bool`(`distanceToBottom <= 1`;**不**把原始 distance 放进 Equatable 结构,布尔在 1pt 边界翻转才触发 action,不放大回调频率);`shouldFollowMeasuredGrowth` / `shouldFollowViewportShrink` 各加 `alreadyAtBottom` 参数并追加 `&& !alreadyAtBottom`。

**红→绿**:先加签名与两条新用例(锚已钉底 + 增长 → 必须 false),策略体暂不加门控跑红;补门控转绿,旧用例补 `alreadyAtBottom: false` 语义不变。

### 4.4 [P1] 议会 speaking 行浏览历史时冻结渲染 — 同文件

**机制依托(亲验)**:`ChatAssistantMarkdownView` 内建冻结边界——`.onChange(of: liveRenderingEnabled)` 翻 false 时截当前 markdown 为内部快照,`renderedMarkdownText` 在 `isStreaming && !liveRenderingEnabled` 时返回快照(解析停更),翻回 true 自动追平。议会调用点原本不传该参数(恒 live)。

**修复**:
- 纯函数 `CouncilTranscriptFollowPolicy.liveRenderingEnabled(isSpeakingRow:followPaused:) = !isSpeakingRow || !followPaused`,调用点按 `message.status == .speaking` 传入。
- **关键约束**:只冻结 speaking 行。历史行若翻 false 会触发 `hasEverStreamed && liveRenderingEnabled` 渲染器切换(本仓反复修过的重淡入病),而用户正在读的恰是历史行。
- `CouncilMessageRow` 增 `followPaused` 字段,`==` 追加 `(lhs.message.status != .speaking || lhs.followPaused == rhs.followPaused)`——只有 speaking 行参与该值比较,用户拖动不会导致全列表重渲。

**红→绿**:四象限纯函数用例,函数体临时 `return true` 确认红,改回转绿。

### 4.5 [P1] fork 分支 `hasEverStreamed` 推导失真 — `NovelCreation/NovelSessionView.swift`(一行)

**根因(亲验)**:`NovelBranchOperations.swift:233` fork 复制消息时 `runID: nil` 是**有意的**——run 记录不随分支复制,`NovelDocumentValidator.swift:955` 会校验 runID 引用(悬空引用会破坏 interruptedDraft 校验)。因此不能保留 runID;病灶在视图层用 `row.runID != nil` 推导 `hasEverStreamed`,fork 分支上全为 false → 数万字继承正文退化到 native fallback 的主线程同步解析冷路径。

**修复**:`hasEverStreamed: row.runID != nil` → `hasEverStreamed: row.role == .assistant`(小说会话中 assistant 行必然是模型生成)。恢复 fork 前的渲染路径一致性,不触碰持久化与 validator。

### 4.6 [P2] `renderableCache` LRU touch — `MessageBubbleView.swift`(主会话亲改)

**根因(亲验)**:静态缓存 12 槽,fetch 命中不移队尾(纯插入序驱逐);三界面并发流式时活跃条目会被其他界面的插入挤出,复出成本是整段全文重解析。
**修复**:`cachedRenderable` 精确命中时把 key 移到 `renderableCacheOrder` 队尾(真 LRU),4 行。
**同时裁决**:审查建议的「缓存按 namespace 分区」判定为错误方向——缓存 key 已含全文内容,跨界面同内容共享是收益不是污染;真病灶只是驱逐策略。不加分区、不调容量(无采样不拍魔法数)。

## 5. 证伪记录(死路与结论同样值钱)

1. **「思考块(Reasoning)高度无约束跳变」不成立**。审查代理判为 P1(pacer 对非 Text part 不消耗预算属实),但 `ChatReasoningCard`(`ChatMiscViews.swift:206-235`)的正文是 `maxHeight: isThinking ? 180 : 260` 封顶 + 内部 ScrollView 自动滚底:思考爆发最多引起一次 ≤180pt 的展开,之后高度固定、内容在容器内滚动,对聊天列表布局无持续冲击。而给 reasoning 套 12 字/拍会在大爆发思考(万字级)下造成几十秒的回放延迟。**裁决:不修**。pacer 绕过对 Tool/Image 的部分保留结论(卡片插入本质离散,高度突变由 `.sizeChanges` 锚吸收)。
2. **「议会 `.speaking` 状态恢复不清洗」是过期事实**。`CouncilMessageStatus(rawKey:)`(:2390)早在 07-17 提交 `9aa0154a5` 就把 `"speaking"` 解码为 `.failed`(git blame 定位)。实施代理在旧代码上写验证用例**直接绿**,按守则拒绝叠加功能重复的症状补丁,仅保留 `testRestoredSpeakingStatusIsCleanedToFailed` 作为回归锁。
3. **审查中期的一个过程性误判**:曾怀疑 `joinedAssistantText` 会把会话历史拼进终态(engine working 数组含输入),经查讨论请求只含 `[system, user]`、历史以文本注入 system,不成立(见 §4.1)。

## 6. 工作区状态(逐文件,均未 commit)

### 本批修复(9 文件)

| 文件 | 改动 |
|---|---|
| `iosApp/iosApp/NovelCreation/NovelLiveModelAdapter.swift` | §4.1:DiscussionStepTextAccumulator + joinedAssistantText |
| `iosApp/iosApp/NovelCreation/NovelSessionView.swift` | §4.5:hasEverStreamed 推导一行 |
| `iosApp/iosApp/ChatGenerationCoordinator.swift` | §4.2:.error 分支 drain 守卫 5 行 |
| `iosApp/iosApp/CouncilChatRuntimeView.swift` | §4.3 + §4.4(与并行会话的 pin modifier 同文件不同区域) |
| `iosApp/iosApp/MessageBubbleView.swift` | §4.6:LRU touch 4 行 |
| `iosApp/iosAppTests/NovelLiveModelAdapterTests.swift` | 新增跨步测试 + ScriptedStreamingNovelDiscussionProvider |
| `iosApp/iosAppTests/IOSParityRedLightTests.swift` | 新增 error-drain 顺序断言 |
| `iosApp/iosAppTests/IOSCouncilRunnerMechanicsTests.swift` | 短路用例 ×2 + 四象限用例 + 旧用例补参 |
| `iosApp/iosAppTests/IOSCouncilRoomArchiveStoreTests.swift` | speaking→failed 回归锁 |

### 并行会话批次(4 文件,非本次工作,原样保留勿动)

- `iosApp/iosApp/ChatCollectionMessageList.swift`:Chat clean-list 与 NativeTimeline 补 `.sizeChanges` 底锚(clean-list 门控 `followGeneration`,经查该路径无 scrollDriver 引用,门控成立);行对齐 `.trailing` → `.topTrailing/.topLeading`。
- `iosApp/iosApp/CouncilChatRuntimeView.swift`(同文件):议会 pin modifier(门控 `followGeneration && !isNativeScrollDriverDesired`)。
- `iosApp/iosAppTests/ChatSwiftUIStreamReplayTests.swift`:旧 measured-growth 中间帧断言按 sizeChanges 新契约移除。
- `iosApp/vendor/SwiftStreamingMarkdown/Sources/MarkdownText/UI/ParagraphView.swift`:`setParagraphContents` 后清 `sizeCache`(修 sizeThatFits 量到旧内容的高度残留)。

**配套关系**:§4.3 的短路修复以议会 pin 锚存在为前提(锚钉底 → `isAtBottom` → 不发冗余动画)。两批必须一起装机验收。

## 7. 明确不修清单(附理由)

| 项 | 理由 |
|---|---|
| Reasoning 纳入 pacer | 已证伪(§5.1),修反而引入回放延迟 |
| CJK 12 字/拍 | 需真机校准出数,不拍魔法数 |
| clean-list 键盘启发式 / sizeChanges×fling / 显式回底动画交叠 | 嫌疑级,先真机复现或补探针 |
| 小说 workspace 分区切换状态丢失 | 修法是状态上提到 sessionViewModel 或视图保活,架构取舍**等用户拍板** |
| `project()` 增量投影 / 长章节增量 AST / 自适应节流 | 先量后调(建议 `ChatPerfTrace` 万字级章节采样定拐点) |
| `Table.format()` 惰性化 | 成本在后台线程,主线程收益未定罪;vendor 不轻动 |
| 缓存 namespace 分区 | 判定为错误方向(§4.6),真病灶已修 |
| `Markup.id` / 两处死代码 | 零现实影响;死代码只标记原则(LOD 死代码连标记注释都未加,因所在文件正被并行会话改动,避免冲突噪音,以本文档记录代替) |
| 小说 sizeChanges 门控用 desired 非 attached | 所有权按意图分配是有意设计;attach 空隙 1-2 帧,需真机证据再权衡 |

## 8. 验证记录

- 每项修复独立红→绿(证据见 §4 各节)。
- 合并回归门禁(iPhone 17 Pro / iOS 26.5 Simulator,工作区为两批改动的并集状态):

```bash
cd iosApp && xcodebuild test -project AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,id=F3D5299E-EEA6-4098-BF61-AB1C516933C0' \
  -only-testing:iosAppTests/ChatStreamReplayTests \
  -only-testing:iosAppTests/ChatSwiftUIStreamReplayTests \
  -only-testing:iosAppTests/ChatMessageProjectionTests \
  -only-testing:iosAppTests/IOSParityRedLightTests \
  -only-testing:iosAppTests/IOSCouncilRunnerMechanicsTests \
  -only-testing:iosAppTests/IOSCouncilRoomArchiveStoreTests \
  -only-testing:iosAppTests/NovelLiveModelAdapterTests \
  -only-testing:iosAppTests/NovelSessionReplayTests
```

结果:**255 executed / 1 expected skip / 0 failures,TEST SUCCEEDED**(日志 `/tmp/amber-fixbatch-gate.log`)。`git diff --check` 通过。

## 9. 待真机验证(预期管理)

模拟器绿只是必要条件。装机后重点复验:

1. **讨论模式丢文本**(P0):真实 provider + 开启搜索,诱导模型先输出说明再调 `search_web`——工具执行期间前序文本应保留,终态应包含全部步骤文本(`\n\n` 分隔)。
2. **错误终止**:长回复中途断网——正文应逐拍追平后再出错误气泡,不再整段砸下。
3. **议会**:贴底流式无「先欠账再追回」的二次动画;上滑读历史时发言席位停止实时解析(回底自动追平);长议会上滑滚动不再与解析抢主线程。
4. **小说 fork**:从 checkpoint fork 出带长正文的分支,浏览继承章节不再逐条卡顿。
5. **预期不变**:正常完成路径、逐词淡入、其余滚动手感。

## 10. 残余与后续方向

- 行为级测试缺口:`ChatGenerationCoordinator` 无事件注入测试座(`IOSParityRedLightTests` 为源码文本断言),error-drain 竞态与 pacer 节拍的运行时验证依赖真机;补测试座需单独授权评估。
- §3.2 的性能类残余(历史行同步解析、全量投影、增量 AST、表格结构性成本)统一走「先采样、后收敛」,入口建议从 `ChatPerfTrace` 的万字级 CJK 章节矩阵开始。
- §3.3 的嫌疑项转探针:优先补「fling 减速中持续 resize」与「分区切换状态丢失」两个真机场景。
