# amber iOS Agent 框架补强设计 —— 耐久边界 · fail-closed · 配置快照

> 状态:v1 设计稿(2026-07-29)
> 范围:iOS 侧 agent 执行链路(`ChatGenerationCoordinator` / `ChatToolRuntime` / `IOSAgentToolEngine` / `SubAgentRunner` / `CouncilRunner`)+ 共享层 `ai-core` 的一处小改
> 关系:与 `REFACTOR_PLAN_AGENT_KERNEL_v2.md`(Android Kernel 通车)互补,不重叠。本文只动 iOS 的**不变量层**,不迁移引擎。
> 思想来源:Pi / Codex / Grok Build 三家 harness 的内核原则(以下简称"三家分析"),按 amber 的真实病史取舍,而非照搬。

---

## 0. 一句话

**amber iOS 的 agent 循环在"流的正确性"上已经用真实 bug 换来了三道守卫,现在欠的是"效果的正确性":工具执行前不记账、坏参数静默放行、运行中配置有裂缝。本方案用五个手术(两个 P0、两个 P1、一个 P2)补齐,全部落在现有代码的既有缝隙上,不重建任何引擎。**

补强后的核心体感变化,一句话版本:

> **杀进程从"半轮对话蒸发、状态成谜"变成"对话保留到工具调用点、每个未完成动作有明确的已知状态";坏参数从"工具带空参数瞎跑一轮"变成"一次自动纠错";生成中改设置从"行为看运气"变成"当前轮雷打不动"。**

---

## 1. 为什么是"补强"而不是"重建"

### 1.1 三家分析的正确用法

三家 harness 拆解(Pi 薄内核 / Codex 可靠性骨架 / Grok Build 长任务运行时)给出的 S 级清单,本质是一组**内核不变量**:

1. 不可变的请求级配置快照(Codex StepSnapshot)
2. assistant tool-call 先持久化、后执行(durable-before-effect)
3. 截断/损坏的工具参数 fail-closed(Pi)
4. 并行执行、按调用顺序提交结果
5. 结果未知的非幂等工具绝不自动重跑
6. 流读取与下游消费解耦(Pi)

它的施工图(13 步绿地 kernel)不适合 amber——那是给可嵌入 SDK 做的。**对一个已有生产代码的 app,正确姿势是拿不变量当审计清单,逐条对照,只修不满足的。**

### 1.2 审计结果:amber 已经用血换到了一半

对照后发现,六条不变量里 amber iOS 已经独立满足四条,而且每条背后都有一次真实事故:

| 不变量 | amber 现状 | 换来它的事故 |
|---|---|---|
| ③ 截断 fail-closed | ✅ `handleCompletedStream` 的 `hitOutputLimit` 检查**先于**工具分支返回(`ChatGenerationCoordinator.swift:1472-1485`),注释明确写了"截断点可能落在 tool_calls 参数中途" | 半截 JSON 被当合法参数的风险,主动防住 |
| ④ 顺序提交 | ✅ 通过设计消解:两端 provider 都显式关闭并行工具(`parallel_tool_calls=false` / `disable_parallel_tool_use=true`),每轮恰好一个工具、串行执行 | 无事故,是正确的简化决策 |
| ⑤ 不重跑非幂等 | ✅ `IOSRunRecovery` 文档注释直接写着 "tools are never replayed",恢复只重分类不重放 | — |
| ⑥ 流解耦 | ✅ chat 性能战役的 L0-L5 分层、单一写者原则 | 2026-07 跟随器振荡事故 |
| 流式工具组装 | ✅ `canMergeDelta` / `argsAreComplete`(`Message.kt:575-625`)按 id 优先、streamIndex 兜底,注释里就是事故报告 | subagent HTTP 500:网关复用 streamIndex 0 污染参数(2026-06-23) |
| ① 配置快照 | ⚠️ 半满足(见 W4) | dual-source provider bug:执行走 `IOSSharedSettingsStore`、设置 UI 走 registry(2026-06) |
| ② 先持久化后执行 | ❌ **不对称**:审批工具满足,自动工具不满足(见 W1) | 尚未爆,但移动端生命周期决定它必然会爆 |
| 参数 fail-closed(完整版) | ❌ `inputAsJson()` 对坏 JSON 静默回退 `{}`(见 W2) | subagent 500 的同族残余风险 |

**这个审计结果本身就是方案的合法性论证:三家分析的原则不是纸上谈兵——amber 每违反一条,现实就用一次事故收账;已满足的四条,每条都换来了一类 bug 的绝迹。剩下三个缺口,应该在下一次收账之前主动补上。**

### 1.3 移动端的威胁模型和 CLI 内核不一样,这决定了优先级

三家都是桌面/服务端 CLI,它们的"进程被杀"是事故;**iOS 的进程被杀是日常**——用户切走 app、系统 jetsam 回收、灵动岛挂着任务锁屏,每天发生几十次。所以:

- **"先持久化后执行"在 iOS 上的权重远高于在 CLI 上**(W1 是 P0 的原因);
- 而 Codex 那套进程沙箱、PolicyEngine、Executor 隔离在 iOS 上权重为零——平台沙箱已兜底,工具全是进程内 API 调用(不做的原因,见 §5)。

---

## 2. 补强后的五条 iOS 内核不变量(合同)

以下五条是本方案交付后必须成立、且有对应测试守护的合同。每个工作流对应修复其中一条。

> **I-1(耐久边界)** 带工具调用的 assistant 消息,必须在工具产生任何副作用之前落盘;每次工具执行的开始与结束必须有账本记录。 → W1
> **I-2(fail-closed)** 无法解析为合法 JSON 的工具参数,绝不以任何降级形式(空对象、部分字段)执行;必须以结构化错误交还模型。 → W2
> **I-3(已知的未知)** 进程死亡后,每个进行中的工具调用必须收敛到三态之一:已完成 / 未开始 / **结果未知**;非幂等工具处于"结果未知"时绝不自动重跑,但必须对用户可见。 → W3
> **I-4(快照契约)** 一个 run 内,模型可见的配置(provider、model、参数、工具集、压缩策略)来自 run 开始时的同一冻结快照;允许 live 读取的字段必须显式声明并 single-flight。 → W4
> **I-5(可解释终态)** run 的每个终态(completed / truncated / failed / interrupted / guard_stopped)都真实反映发生了什么;守护器触发不得伪装成正常完成。 → W5(现有代码已大部分满足,W5 补最后一块)

---

## 3. 五个工作流

### W1 · 工具执行耐久边界:"先记账,后动手" 【P0,核心】

#### 借鉴自哪里,巧在哪里

这是三家共识里唯一 amber 完全没做的 S 级项。它的巧妙之处在于**用一次廉价的顺序写,把"崩溃"从灾难降级为普通状态**:

- 崩溃发生在记账前 → 工具没跑过,世界干净,这轮等于没发生;
- 崩溃发生在记账后 → 账本知道"曾发起过调用 X",恢复时把它标为"结果未知"即可。

没有这次写,崩溃后是**失忆**——连"是否动过手"都无从判断;有了这次写,崩溃后是**已知的未知**——这在工程上是本质区别:前者只能装死,后者可以对用户诚实、可以按幂等性分类处理、可以提供重试。Grok Build 的 Plan 审批跨重启恢复、Codex 的 rollout 日志,底层都是同一个思想:**fail-closed 的持久状态机,先写意图,再产生效果。**

#### amber 的病灶

现状是**同一个协调器里两条路径纪律不一致**:

- **审批工具(做对了)**:`pauseForApproval`(`ChatGenerationCoordinator.swift:1813-1886`)先 `markRunAwaitingPermission(runId, toolCallId)` 把审批归属写进 `agent_run.inputSnapshotRef`("tool_call:<id>"),再 `persistMessagesSnapshot` 落盘可见消息,**两步都确认成功后**才释放后台保活租约。注释写得很清楚:"必须等可见 baseMessages 已经耐久保存后再还租约,避免挂起/杀进程后连待确认节点都丢失。"
- **自动工具(欠账)**:`executeToolCall`(同文件 `:1711-1810`)直接 `toolRuntime.execute`,执行前**零持久化**。带工具调用的 assistant 消息只活在 `bindings.setMessages` 的内存里;`refreshBackgroundHandoff`(`:1749`)只是给后台引擎的内存交接快照,不落盘。app 死在工具的 HTTP 调用中,重启后这半轮对话**整体蒸发**,`IOSRunRecovery` 只能把 run 标成 interrupted——它连"死前正在执行哪个工具"都不知道。

`SubAgentRunner`/`IOSAgentToolEngine` 同样裸奔;Council 反而最接近合规(`CouncilRoomArchiveStore` 在运行中有节流增量 checkpoint,`CouncilChatRuntimeView.swift:2797-2932`)。

#### 设计

**载体决策:账本直接写共享 Room 库的 `agent_event` 表,不发明新机制。**

`AgentRuntimeDao.insertEvent(AgentEventEntity)` 在 commonMain(`core/agent-store-room/.../AgentRuntimeDao.kt:19`),iOS 经 `IosDatabaseFactory` 可直接调用——目前 iOS 只用了 `agent_run` 表做粗粒度记账,`agent_event` 表在 iOS 侧是零流量的现成基础设施。用它有三重收益:
1. 零新 schema、零迁移;
2. 事件语义与 Android 生产端(`RoomAgentEventStore`)天然一致;
3. 这是 iOS 与 Android 运行时**数据面收敛**的第一步——不收敛引擎面(那是 v2 plan 的 Android 战场,且 Android 自己的 Kernel 路径尚零流量,iOS 没有理由抢跑上一条还没通车的轨道),但让两端的 run 留痕说同一种语言。

**执行序列(改造 `executeToolCall`):**

```text
1. persistMessagesSnapshot(baseMessages)      ← 含 tool-call 的 assistant 消息落盘
2. insertEvent(ToolCallStarted {runId, toolCallId,
     toolName, argsDigest, effectClass})       ← 记账:意图 + 参数摘要 + 副作用等级
3. toolRuntime.execute(...)                    ← 此后才允许副作用
4. 结果 append 进消息 → insertEvent(ToolCallFinished {toolCallId, ok})
5. (结果落盘沿用现有轮末 persist,不加写)
```

步骤 1、2 任一失败 → 本轮按现有 `presentStreamError` 路径失败,**不执行工具**(与 `pauseForApproval` 对持久化失败的处理完全对齐,复用其错误文案模式)。

**工具副作用分级(`effectClass`)**:在 `ChatToolRuntime` 的工具描述上加一个三值枚举,一次性人工标定:

| 等级 | amber 工具 | 恢复语义 |
|---|---|---|
| `pure` | search、web fetch、memory 读 | 可安全重试,恢复时给"重试"按钮 |
| `idempotent` | memory edit/delete(按稳定 id 收敛) | 可重试 |
| `sideEffect` | memory create/add/write、workspace 写、ish 执行、webMount 操作、MCP 调用 | 结果未知时**绝不自动重跑**,只标注 |

这是三家分析工具元数据模型(`effect` + `idempotency`)裁剪到 amber 工具面后的最小版:amber 每轮单工具、无并发,不需要 `resourceKey`、读写锁那一半,只需要恢复语义这一半。

**成本论证**:每个工具轮多一次消息快照写 + 一次事件行插入。工具轮被 `maxToolResumeCount = 4` 封顶,且每个工具本身就是一次网络往返(几百 ms 到几十 s),两次本地 SQLite 写(<5ms)完全淹没在噪音里。**这正是 Pi"流解耦"原则的镜像:不让持久化阻塞流式,但在效果边界上,持久化必须阻塞副作用——两条原则不矛盾,分别站在各自的正确位置。**

#### 补强后的变化

- **体感**:用户在工具执行中切走 app 被杀,回来后对话**保留到工具调用点**——能看到模型说了什么、正要调什么工具、以及一条明确的中断标记;而不是最后半轮凭空消失、用户怀疑自己没发出去过。
- **工程**:每个 run 从此有一条可查询的工具执行流水,线上"工具到底跑没跑"的争议从猜测变成查表;同时为 W3 的恢复 UX 提供全部数据。
- **一致性**:审批路径与自动路径终于遵守同一条纪律,`ChatGenerationCoordinator` 内部消除一个"为什么这边持久化那边不持久化"的认知陷阱。

---

### W2 · 工具参数 fail-closed:堵住最后一个静默降级 【P0,最小手术】

#### 借鉴自哪里,巧在哪里

Pi 的"截断时拒绝执行整批工具"背后是一条更普适的原则:**参数的完整性存疑时,执行永远是错的选择——因为坏参数的失败模式不是报错,而是"以错误的语义成功"。**(`{"path": "/x", "recursive": false}` 截成 `{"path": "/x"}`,JSON 依然合法,语义已翻转。)这条原则的巧妙在于它承认一个不对称:拒绝执行的代价是一次可见的重试,错误执行的代价是不可见的错误结果——前者永远更便宜。

#### amber 的病灶

amber 对这条原则执行了三分之二:截断防住了(`hitOutputLimit`),流式组装污染防住了(`canMergeDelta`/`argsAreComplete`)。但最后一关是敞开的:

```kotlin
// ai-core/.../Message.kt:514-516
fun inputAsJson(): JsonElement = runCatching {
    json.parseToJsonElement(input.ifBlank { "{}" })
}.getOrElse { JsonObject(emptyMap()) }   // ← 坏 JSON 静默变 {}
```

穿过前两道守卫的畸形参数(网关双写 `{...}{...}`、编码损坏、模型吐了非 JSON),在这里被静默"修复"成空对象,然后工具**带着空参数真的执行**:search 查空串、workspace 写空路径。用户看到的是一次成功执行的工具返回了莫名其妙的结果,模型据此继续推理——错误被洗白成了事实。这与 subagent 500 是同族问题:那次是坏参数打爆网关(可见,反而好查),这次是坏参数被本地吞掉(不可见,更糟)。

#### 设计

1. **共享层**加严格解析(不动现有 `inputAsJson`,渲染路径继续宽容):
   ```kotlin
   fun UIMessagePart.Tool.parseInputStrict(): Result<JsonElement>
   // input 为空白 → 成功返回空对象(无参工具合法)
   // 非空白且不可解析 / 解析后非 object → 失败,携带错误与原文摘要
   ```
2. **执行闸门**:`ChatToolRuntime` 的执行入口与 `IOSAgentToolEngine` 的对应步骤,在 dispatch 前调 `parseInputStrict()`。失败 → 不执行,把该 tool part 置为失败态,`output` 写入结构化错误:
   ```json
   {"error": "tool_arguments_invalid",
    "message": "arguments were not valid JSON",
    "raw_prefix": "<原文前 200 字符>"}
   ```
   然后走现有的续流路径把错误交还模型(占用一次 `maxToolResumeCount` 名额,防止坏参数无限循环)。
3. Council 的 host/seat 工具路径同样过这道闸门。

手术极小:共享层 ~20 行 + 每个执行入口一个 guard。

#### 补强后的变化

- **体感**:少一类最诡异的失败——"工具明明跑了,结果驴唇不对马嘴"。用户看到的最坏情况变成:工具卡一下、模型自己说"参数有误,我重试一次",然后正常继续。**错误从洗白变成自愈。**
- **工程**:三道参数守卫(截断/组装/解析)合龙,I-2 成为闭合不变量;网关兼容性问题从"偶发灵异"变成日志里一条明确的 `tool_arguments_invalid`。

---

### W3 · 中断恢复的体感闭环:把"结果未知"交给用户 【P1,骑在 W1 上】

#### 借鉴自哪里,巧在哪里

三家分析崩溃恢复规则的精髓不是"能恢复",而是**"诚实地分类"**:pure 可重跑、幂等可重试、非幂等标记 `outcome_unknown` 并禁止自动重跑。它巧在承认了分布式系统的根本限制——执行中死亡后,"到底成没成"在本地是不可知的——然后不试图用魔法掩盖这个不可知,而是把它变成一等状态呈现出来。绝大多数 agent 产品在这里选择装死(半轮消失)或撒谎(默默重跑),两者都在某个时刻背叛用户。

#### amber 的病灶

`IOSRunRecovery` 的哲学是对的(never replay),但它是**哑巴恢复**:把 run 标成 interrupted 就结束了。因为没有 W1 的账本,它没有任何素材告诉用户发生了什么;审批恢复路径(`recoverPendingApprovalDescriptors`)也只是显式终止。用户视角:任务凭空死了,没有解释,没有出路。

#### 设计

启动恢复流程(现有 `recoverInterruptedRuns` 的位置)升级为三步:

1. **查账**:对每个 interrupted run,查 `agent_event` 中 `ToolCallStarted` 无配对 `ToolCallFinished` 的调用;
2. **标注**:在对应会话的该 tool part 上写入终态——
   - `sideEffect` 类 → "⚠️ 应用中断,此操作是否完成未知"(I-3,绝不重跑);
   - `pure`/`idempotent` 类 → "应用中断,此操作未完成" + **重试按钮**(点击即以原参数重新入队该工具轮);
   - 无未完成调用(死在纯生成中)→ 沿用现有中断标记;
3. **收尾**:同步关闭孤儿 Live Activity / Watch 任务态(现状:杀进程后灵动岛/Watch 可能残留过期状态,`WatchTaskCoordinator.publish` 的最后状态没人纠正)。

Council 侧对齐:`CouncilRoomArchiveStore.markInterrupted` 已把 `speaking` 消息标为中断,补充同样的"发言未完成"可视文案即可,机制不动。

#### 补强后的变化

- **体感**:这是整个方案最大的可感知交付。**中断从"神秘失踪"变成"透明事故报告 + 出路"**:用户看得到断在哪、哪些动作是安全的、哪些需要自己确认(比如去 workspace 看看文件写没写成)、哪些一键就能续。对一个主打 agent 长任务的 app,这是信任感的分水岭——用户敢不敢把十分钟的任务交给它然后锁屏,取决于中断后回来看到什么。
- **工程**:灵动岛/Watch 状态与真实状态强一致,消除一类"任务明明死了界面还转圈"的投诉。

---

### W4 · RunSnapshot 配置契约:冻结什么、放行什么,写成类型 【P1】

#### 借鉴自哪里,巧在哪里

Codex 的 StepContext 是三家中最值得偷的单项设计,但它的精髓常被误读为"把配置存一份"。真正的巧点是**同源性**:模型看到的工具定义、解析参数用的路由器、权限判断用的工具身份,来自同一个不可变对象,于是"描述与执行错位"这类 bug **在类型系统层面不可表达**——不是靠 code review 防住的,是靠构造防住的。配置变更不丢失,只是被推迟到下一个安全点生效,当前请求的世界观保持自洽。

#### amber 的病灶

amber 处在"事实上半冻结、契约上不存在"的状态:

- 做对的:`providerSetting`/`params` 在 run 内作为不可变值穿透传递(`generateResponse` → `prepareAndStartStreaming` → `executeToolCall` → 下一轮),不回读 store;Council 的 `IOSCouncilRoomRunRequest` 本身就是冻结结构体。
- 裂缝:
  1. `prepareAndStartStreaming` 每轮重跑 `IOSCodexProviderResolver.resolved(providerSetting)`(`:1013`)——意图是 token 刷新(合理的 live 需求),但实现上是**整个 setting 的重解析**,且无 single-flight;
  2. 压缩配置每轮 live 读 `dependencies.sharedSettings.snapshot`(`:1044,1082`)——用户生成中途改压缩设置,会在**同一个 run 的轮间**改变行为;
  3. 历史上已经收过账:dual-source provider bug 正是"两处取值"的产物,且 settings-UI 的 picker 至今还挂在 registry 上(已知 follow-up)。

**没有契约的后果不是某个具体 bug,而是每次加配置项都要重新人肉判断"这里该读哪份、什么时候读",判断错一次就是下一个 dual-source。**

#### 设计

引入一个纯值类型,把判断固化成结构:

```swift
struct ChatRunSnapshot: Sendable {
    // ── 冻结区:run 开始时定格,轮间绝不变 ──
    let providerSetting: ProviderSetting
    let params: TextGenerationParams
    let enabledTools: ChatToolAvailability     // 工具开关集合
    let compactionPolicy: CompactionPolicy     // 从 sharedSettings 定格(裂缝②收口)
    // ── 声明式 live 区:唯一允许的运行中读取,并说明为什么 ──
    let auth: RunAuthProvider                  // token 可能过期,这是唯一合法的 live 理由
}

actor RunAuthProvider {                        // single-flight:并发/连续刷新合并为一次
    func currentToken() async throws -> String
}
```

改造点:
1. `generateResponse` 构造 snapshot,替换现在散装传递的两个参数(签名收敛,不是加参数);
2. 删除 `:1044/:1082` 的轮间 `sharedSettings.snapshot` 读取,改用 `snapshot.compactionPolicy`;
3. `:1013` 的每轮重解析改为 `snapshot.auth.currentToken()`——Grok Build 的"批次内 single-flight 认证恢复"在这里落地(它巧在把'谁触发刷新'与'刷新执行几次'解耦,N 个触发者合并等待同一次刷新);
4. `SubAgentRunner`/`IOSAgentToolEngine` 复用同一结构;
5. 顺势清掉已知 follow-up:settings-UI picker 从 registry 迁到 `IOSSharedSettingsStore`,让"读配置只有一个源"在 UI 层也成立;
6. Council 无需改——`IOSCouncilRoomRunRequest` 已是冻结快照,追问轮重建 request 是**合法的新轮次边界**(相当于新 TurnSnapshot),在代码注释里把这一点写明即可。

#### 补强后的变化

- **体感**:生成中途切模型、关工具、改压缩设置,当前回合行为**雷打不动**,下一回合才生效——和用户直觉一致,"我明明设了 A 怎么用的是 B"这类反馈断根。
- **工程**:新增配置项时,作者被类型逼着回答"冻结还是 live"——dual-source 这一类 bug 从"靠 review 防"变成"靠编译器防"。token 刷新从每轮一次盲刷变成按需 single-flight,顺带减少一类 429/竞态。

---

### W5 · ToolLoopGuard:一个守护器,不是一套官僚系统 【P2】

#### 借鉴自哪里,巧在哪里

Grok Build 的 Action Stationarity(相同工具+参数签名哈希,先提醒后强停)是个好设计;但三家分析里最有价值的其实是那条**反面教训**:Grok 同时养了 Goal/TodoGate/StopGate/Laziness Detector 等一堆重叠的自治循环,以至于"谁有权让 agent 继续、谁有权让它停"都说不清。所以这条的借鉴姿势是双重的——**抄它的检测器,更要抄住"只许有一个"的克制。**

#### amber 的病灶

现有守护全是盲数数:`maxToolResumeCount = 4`、engine `maxSteps = 8`、council 轮数 1-5。上限收得很紧(好事),但对**浪费方式**不敏感:模型用相同参数连搜 4 次同一个词,会安静烧完全部预算,然后用户收到"已达到工具循环上限"——预算没花在推进上,失败信息也没告诉模型(和用户)真正的问题是**它在原地打转**。上限越紧,单次重复的代价占比越大:4 次预算里重复 1 次就烧掉 25%。

#### 设计

一个极简、无状态机的检测器,run 内生效:

```text
signature = hash(toolName + canonicalizedArgsJson)
第 2 次相同签名 → 在工具结果后追加提醒:
   "你刚以完全相同的参数调用过此工具,结果如上。请改变参数或改变策略,不要重复调用。"
第 3 次相同签名 → 终止本轮,终态为 guard_stopped(I-5:可见、可持久化,
   不伪装成 completed),用户文案说明"模型在重复相同操作,已停止"。
```

阈值比 Grok(8/16)激进得多,因为 amber 的轮次预算(4/8)本来就小,没有慢慢提醒的空间。实现为一个 ~50 行的独立类型,**同一份**接入 `ChatToolRuntime`、`IOSAgentToolEngine`、council seat 循环三处。明确不做:Todo 完成度检查、"懒惰"检测、目标续跑——一个检测器,一种停法。

#### 补强后的变化

- **体感**:subagent/深读/议会里"卡住反复搜索然后失败"的场景,变成要么第 2 次就被点醒自愈(用户只看到多了一次工具调用),要么快速停止并**说清卡在哪**——省 token、省时间,失败也失败得明白。
- **工程**:`guard_stopped` 成为可统计的终态,线上能第一次量化"模型打转"发生率,为将来调 prompt/换模型提供数据。

---

## 4. 实施顺序与依赖

```text
Phase 1(先修闸门,再修账本)
  W2 参数 fail-closed        ← 最小手术,独立合入,立即生效
  W1 耐久边界                ← 核心;effectClass 标定 + 执行序列改造 + agent_event 接入
Phase 2(把账本变成体验)
  W3 恢复闭环                ← 依赖 W1 的账本;含 Live Activity/Watch 孤儿收尾
Phase 3(把纪律变成类型)
  W4 RunSnapshot             ← 独立于 W1/W3,可并行;含 settings picker 收尾
Phase 4
  W5 ToolLoopGuard           ← 独立,最后做
```

W2+W1 合起来是一次聚焦的 PR 序列,全部落在 `ChatGenerationCoordinator.executeToolCall`、`ChatToolRuntime`、`IOSAgentToolEngine` 和 `ai-core/Message.kt` 四个文件的既有缝隙上,不动流式管线、不动 UI 层、不碰 council 主体。

### 验收(每条不变量一组测试,放 `iosAppTests/`)

沿用 `IOSCouncilRunnerMechanicsTests` 的 mechanics 测试风格,新增 `IOSToolBoundaryTests`:

- **I-1/I-3 杀进程矩阵**(注入式,模拟持久化完成点):
  死于快照落盘前 / 落盘后记账前 / 记账后执行中 / 执行后 Finished 前 —— 分别断言:无副作用无痕迹 / 无副作用有痕迹 / outcome_unknown 且 sideEffect 类不重跑、pure 类出重试 / 结果不丢失。
- **I-2 坏参数夹具**:`{...}{...}` 双写、截断非法尾、非 JSON 文本、空白 —— 断言:前三者不执行且产出结构化错误、模型收到错误后续流、空白按无参工具正常执行。
- **I-4 中途改配置**:run 进行中修改 sharedSettings 的压缩/模型配置 —— 断言当前 run 轮间行为不变、下一 run 生效;并发两次 token 过期 —— 断言刷新只发生一次。
- **I-5 打转夹具**:预录相同工具调用的流 —— 断言第 2 次注入提醒、第 3 次 `guard_stopped`、终态不是 completed。

---

## 5. 明确不做什么(与理由)

| 不做 | 理由 |
|---|---|
| 迁移 iOS 到共享 `AgentRunner`/Kernel 引擎 | Android 自己的 Kernel 路径至今零生产流量(v2 plan §1),iOS 没有理由抢跑一条未通车的轨道。本方案只做**数据面收敛**(agent_event),引擎面收敛留给两端都验证后的将来 |
| 进程隔离 Executor / 沙箱 / PolicyEngine | iOS 平台沙箱已兜底,工具全是进程内 API 调用,威胁模型不存在 |
| 并行工具 + 读写门闩调度 | `parallel_tool_calls=false` 是刻意的简化,它免费消灭了顺序提交、路径锁、结果竞态整整三类问题。除非产品明确需要并行工具,否则不还这笔债 |
| 完整 event-sourcing 重放 / state hash / golden trace 体系 | 那是给可嵌入 kernel SDK 的验收标准;iOS 取其精神做 §4 的杀进程矩阵即可 |
| 多套自治继续机制(Goal/TodoGate/...) | Grok 的反面教训:W5 之外不加任何"替模型决定继续/停止"的机制 |
| 多模式 compaction | 现有压缩路径不动;W4 只是把它的配置读取时机定格 |

---

## 6. 体感变化总表

| 场景 | 现在 | 补强后 | 归功 |
|---|---|---|---|
| 工具执行中切走 app 被杀 | 半轮对话蒸发,run 标 interrupted,无解释 | 对话保留到工具调用点;副作用类标"是否完成未知",只读类给一键重试;灵动岛/Watch 正确收尾 | W1+W3 |
| 网关/模型吐出畸形工具参数 | 静默变 `{}` 执行,返回莫名结果,模型据此继续错下去 | 不执行;模型收到结构化错误后自我纠正,用户最多看到一次自动重试 | W2 |
| 生成中途改模型/工具/压缩设置 | 部分设置在轮间悄悄生效,行为看运气 | 当前回合冻结,下一回合生效,和直觉一致 | W4 |
| 模型原地打转重复同一工具 | 安静烧完 4 轮上限后失败,不知道为什么 | 第 2 次被点醒自愈,或快速 guard_stopped 并说明原因 | W5 |
| token 过期撞上连续工具轮 | 每轮盲目重解析 provider,可能重复刷新 | single-flight,一次刷新全轮受益 | W4 |
| 线上排查"工具到底跑没跑" | 只能看日志碎片猜 | 查 agent_event 流水,每次调用有始有终 | W1 |
| 待审批工具跨杀进程 | ✅ 已正确(先落盘后等待) | 不变;且自动工具路径与它纪律统一 | — |

---

## 附录 A · 关键代码锚点

| 锚点 | 位置 | 在本方案中的角色 |
|---|---|---|
| `executeToolCall` 零持久化执行 | `iosApp/iosApp/ChatGenerationCoordinator.swift:1711-1810` | W1 主手术位 |
| `pauseForApproval` 正确范式 | 同文件 `:1813-1886` | W1 对齐的样板 |
| `hitOutputLimit` fail-closed | 同文件 `:1472-1485` | 已合规,I-2 的一半 |
| `maxToolResumeCount = 4` | 同文件 `:422, :1488` | W5 的预算背景 |
| 每轮 Codex resolve / 压缩 live 读 | 同文件 `:1013, :1044, :1082` | W4 三条裂缝 |
| `inputAsJson()` fail-open | `ai-core/src/commonMain/.../Message.kt:514-516` | W2 主手术位 |
| `canMergeDelta`/`argsAreComplete` | 同文件 `:575-625` | 已合规的前两道守卫 |
| `IOSRunRecovery`(never replay) | `iosApp/iosApp/IOSRunRecovery.swift` | W3 升级位 |
| `AgentRuntimeDao.insertEvent` | `core/agent-store-room/.../AgentRuntimeDao.kt:19` | W1 账本载体(现成) |
| Council 增量归档 | `iosApp/iosApp/CouncilChatRuntimeView.swift:2797-2932` | 已接近合规,W3 轻对齐 |
| engine `maxSteps = 8` | `iosApp/iosApp/IOSAgentToolEngine.swift:452-459` | W1/W2/W5 第二接入点 |
