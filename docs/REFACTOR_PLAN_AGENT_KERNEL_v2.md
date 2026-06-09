# AmberAgent 架构重构方案 v2 — Agent Kernel 通车 + 上帝类拆解 + 彻底脱钩 rikkahub

> 状态：v2 草案（2026-06-03）
> 取代：`REFACTOR_PLAN_AGENT_KERNEL.md`（v1.2.1，多处与 main 现状脱节，见 §1）
> 团队规模：1-2 人 · 估计墙钟 7-9 个月（边发版边迁，无冻结窗口）
> 本文所有结论均已对照 main 当前代码逐条核实，关键行号见正文与附录 A。

---

## 0. 一句话与决断

**Agent Kernel 的轨道早就铺好了，但它是死的——`ChatService.useKernelPath` 默认 `false` 且全代码库无一处把它设成 `true`，零生产流量经过 `AgentRunner`。** 这次重构不是"从零搭 Kernel"，是"让这条已建好但通电就会少几个轮子的轨道真正能载客"，并以它为脊柱，逐步掏空两个上帝类、把还堆在 `:app` 里的子系统迁成独立模块。

**路线决断：渐进（Strangler-fig），不是拆除重写。** 拆除路线（删掉 `GenerationHandler`、在 Kernel 的干净合同上重建对话循环）已被代码证伪——Kernel 的 wire 类型载不动真实的工具/UI 循环（§4.5）。

**最高杠杆第一步：把 `ChatTurnAgent` 做到与 `ChatService.handleMessageComplete` 行为对等**（约 4-6 周），而不是翻 flag、也不是先抽 `GenerationHandler`。今天翻 flag 会给所有用户静默关掉自动标题、建议、记忆抽取、FTS 索引、资源清理，并在第一个需要审批的工具上断流（§6 Phase 6）。

---

## 1. 现状速写（先把"我们到底在哪"钉死）

v1.2.1 那份 plan 写于鸟瞰视角，很多前提已经被 main 走过去了。核实后的真实现状：

| 维度 | v1.2.1 plan 的说法 | main 当前真相（已核实） |
|---|---|---|
| 脱 fork / 改名 | 当作未来的 Phase 0/E | **基本已完成**：包全部 `app.amber.*`，DB 文件 `amber_agent`，JNI 符号 `Java_app_amber_*`；`me.rerere.*` 只剩 2 个冻结 FQN |
| Kernel | 待 Phase A 建合同 | **合同 + InProcess 实现全建好**，13 个接口齐全，`ChatTurnAgent`/`DeepReadAgentAdapter` 已注册 |
| Kernel 流量 | — | **零**。`useKernelPath=false`（`ChatService.kt:732`），永不为真 → 两个注册的 agent 都是死代码 |
| ChatService | 2347→2618 行 | **2532 行**，17 个职责，仍是头号上帝类 |
| 模块数 | Phase D 目标"逐月拆出" | **48 个 Gradle 模块已存在**，超前于计划 |
| Rust crate | 4 个 spike、零调用方 | **11 个 crate、全部有生产调用方** |

**结论：基础设施层（Kernel、模块骨架、脱钩、Rust）已经超额完成；真正没动的是"让 Kernel 接管生产流量"和"把两个上帝类拆开"。** 这份 v2 就只解决这两件事，外加收尾脱钩。

---

## 2. 现在的问题

### 2.1 调模型有 13 套方式，各写各的

代码里能调到 LLM 的入口有 **13 条**，分两层：

**Tier 1（5 条，走 `GenerationHandler`，拿全套 agent-loop）**
ChatService 老路径 · `ChatTurnAgent`（kernel，死的）· `DeepReadAgentRunManager.collectRun`（supervisor 循环）· `SubAgentRunner` · `BoardTaskRunner`。

**Tier 2（8 条，全部绕开 `GenerationHandler`，直接 `providerManager.getProviderByType().generateText/streamText`）**
`AiAuxiliaryGenerator`（标题+建议）· `MemoryExtractor` · `BoardAgent`（自带解析失败重试）· `ConversationContextEngine.streamCompactSummary`（手写 33ms flush + 质量重试）· `ProviderModelCouncilTextRunner`（手写 32ms flush + 参数回退重试）· `LiveModeManager` · `MiniAppV3Runtime` · `HotListTitleLocalizer`/`MemoryDreamPlanner`。

**重复实现的东西（同一逻辑被抄了 N 遍）：**
- **流式 chunk 累积循环**（collect → 取 delta → append → ≤33ms flush）在至少 3 处各写一份，各自的 `lastFlushAt` 和 flush 间隔常量（`GenerationHandler.kt:498-538`、`ConversationContextEngine.kt:684-714`、`ProviderModelCouncilTextRunner.kt:54-82`）。
- **错误重试** 有 5 套互不共享分类器的策略（transport 重试、解析失败重试、schema 质量重试、参数回退重试、调用方 backoff）。
- **transformer 管线** 只存在于 `GenerationHandler` 里，所有 Tier 2 路径**静默缺失**（模板变量、think 标签、正则替换、OCR 全都不生效）。
- **模型解析 + provider 查找样板** 在每个 Tier 2 调用方各抄一遍，模型不可用时各处理各的。

### 2.2 两个上帝类

**#1 `ChatService`（`app/amber/core/service/ChatService.kt`，2532 行，17 个职责）**
会话生命周期、窗口化时间线分页、pending 消息队列持久化、消息分发循环（kernel/legacy 双路）、idle-tool 阻塞解析、流式生成编排、完整工具装配工厂、记忆工具接线、工具审批协调、会话消息变更（edit/delete/branch/fork）、重生成、标题/建议委托、手动上下文压缩、通知/前台保活、AgentTask 生命周期追踪、流式 checkpoint 持久化、会话内队列工具定义——全在一个类里。最难拆的是 `launchPendingMessageLoop`/`handleMessageComplete` 那个 400 行的方法，把会话状态、pending 队列、工具装配、通知、checkpoint、记忆抽取全缠在一起。

**#2 `DeepReadAgentRunManager`（`...board/hotlist/deepread/`，1181 行，10 个职责）**
管线编排、全部 prompt 构造（380+ 行 StringBuilder 扩展）、阶段 supervisor 循环（带超时/回退）、验证 supervisor 循环、规划 LLM 调用、后台调度、per-topic mutex 管理、超时策略数学、模型解析、证据记录装饰。没有接口，无法 stub 测试；加一个新阶段要同时改 `buildPrompt`、`collectRunTimeoutFor`、`DeepReadGenerationStage.writerToolName` 和阶段分发块。

> 澄清一个容易砍错的点：`GenerationHandler`（1141 行）和 `DeepReadSectionWriterTools`（1194 行）**不是**上帝类。前者是内聚的生成管线、实现 `Generator` 接口；后者是规矩的工具定义 + 事务性状态写入，剩下的 580 行是文件私有 helper。别动它们的职责。

### 2.3 模块割裂 + Kernel 是死轨道

48 个模块已存在，但 `:app` 里仍物理塞着 ~641 个 Kotlin 文件、14 个 core 子系统 + 13 个 feature 子系统。缺失的关键模块：`:core:generation:impl`（装 `GenerationHandler`）、`:core:db`（装 `AppDatabase`）、`:feature:chat:impl`、`:feature:runtime:impl` 等。

更要命的是 **Kernel 通了电却没载客**：`ChatTurnAgent` 和 `DeepReadAgentAdapter` 在 `InMemoryAgentRegistry` 里注册了，但 `useKernelPath=false`，所有对话仍走 `ChatService.launchPendingMessageLoop → GenerationHandler`。`BoardAgent`/`DailyReviewAgent` 连 Kernel 都没接，直连 `ProviderManager`。

而且 `ChatTurnAgent` 目前是 **happy-path 骨架**：streaming 走 `conversationAccess.updateConversation`、emit 的 delta 是空串、`toolCallCount` 硬编码为 0、收尾停在 `AssistantMessageFinalized`——`handleMessageComplete` 的 onSuccess 钩子（`generateTitle`/`generateSuggestion`/`memoryExtractor`/FTS 索引/资源清理/压缩）**一个都没跑**。所以"翻 flag = 行为不变"是假的。

### 2.4 还有一个结构性 DI 环

`ChatTurnAgent` 的构造器字面上吃 `get<ChatService>()`（`AgentRuntimeModule.kt:50`），而 `AgentRuntimeModule.kt:69-71` 的注释自己写明了 `ChatService → AgentRunner → ChatEventProjector → ChatService` 这个环。`ChatService` 就是 `ConversationAccess` 的实现。这个环不破，`:feature:chat:impl` 永远无法独立编译——它和上帝类拆分**耦合**，不是一个独立的晚期步骤。

### 2.5 脱钩 rikkahub 的最后残留

改名已基本完成，但要做到 `git grep -i rikkahub` 归零（除 LICENSE 归属与 git 历史），还剩：

- **2 个故意冻结的 `me.rerere.*` FQN**：`AgentNotificationActionReceiver`（广播 action 串绑死）、`PreferenceStoreV1Migration`（旧备份迁移类，FQN 不能改否则老用户备份读不出）。
- **4 个上游 Maven 坐标**：`com.github.rikkahub:{jlatexmath-android, markdown, hugeicons-compose, sqlite-android}`——都是用户自己 fork 的 GitHub 仓库，改名要重新发布 fork。
- **LICENSE 里的 "Forked from rikkahub" 归属** + **git 历史里的 commit message**（不可变）。

---

## 3. 为什么要重构（不动的代价）

1. **每加一个调模型的地方就多一份重复**——新功能想要 transformer/重试/流式，要么再抄一遍 Tier 2 的样板，要么硬接 `GenerationHandler` 的下行依赖。技术债复利增长。
2. **`ChatService` 还在涨**（2347→2618→2532，靠零散修剪勉强压住），任何对话相关改动都要在 2500 行里穿针，回归风险高、无法单测。
3. **Kernel 是沉没成本**——已经投入建好了 13 个接口、Room event store、projector，却产生零价值，因为没人敢翻那个会掉轮子的 flag。
4. **`DeepReadAgentRunManager` 无接口、无法测**，DeepRead 的失败率问题（见另一份 harness 分析）很大程度上是这个上帝类里 supervisor 循环 + prompt 构造 + 超时数学全缠在一起、改一处动全身造成的。
5. **脱钩差最后一公里**——残留的 2 个 FQN + 4 个 fork 坐标让"独立项目"这件事在工程上还没真正闭环。

---

## 4. 架构方案（目标态）

### 4.1 总图

```
┌─── Surfaces（UI 边界，可插拔）──────────────────────────────┐
│ ChatSurface   BoardSurface   DeepReadSurface   ...          │
│   observe(runId) / dispatch(input)，只依赖 :api + :core     │
└─────────────────┬──────────────────────────────────────────┘
                  │
┌─────────────────▼──────────────────────────────────────────┐
│ Agent Kernel（:core:agent-runtime + -impl，已存在）        │
│   AgentRunner · AgentRegistry · RunScope · AgentEventStore  │
│   ChatEventProjector（写 amber_agent.db）                   │
└─────────────────┬──────────────────────────────────────────┘
                  │ 每个 agent 通过 RunScope.llm 调模型
┌─────────────────▼──────────────────────────────────────────┐
│ :core:generation:impl（GenerationHandler + transformers）  │
│   ProviderLlmSession : LlmSession  ← 唯一 turn 调用接缝     │
│   AmberModelRouter : ModelRouter   ← 收编模型解析样板       │
└─────────────────┬──────────────────────────────────────────┘
                  │
┌─────────────────▼──────────────────────────────────────────┐
│ :ai（Provider 抽象）· :core:db · :core:native:* · 工具层    │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 统一模型调用（目标先纠偏）

**别立"13 套统一成 1 套"的 flag。** 两件事证伪它：
- **重入**：`GenerationHandler.generateText` 在顶部**无条件**调 `prepareContext → compactInternal → streamCompactSummary`。把压缩摘要路由回引擎 = 无限递归。压缩是引擎**下面**的基础设施，永远直连。
- **非 turn 探针**（图像生成、Vision 健康检查、OCR、余额查询、连通性测试）本来就不该套 turn 外壳。

**诚实目标：turn 形状的调用统一成一条引擎；压缩和非 turn 探针按设计保持独立。**

```kotlin
// :core:generation:impl —— adapter，不删 GenerationHandler
class ProviderLlmSession(private val gh: GenerationHandler) : LlmSession {
    // 对 Kernel 暴露 coarse 的 Flow<TurnEvent>（仅作审计日志）
    override suspend fun streamTurn(turn: ChatTurn): Flow<TurnEvent> = ...
    // 对 chat/deepread/subagent/board 暴露内部 Flow<GenerationChunk>（真实富类型）
    fun streamRich(...): Flow<GenerationChunk> = gh.generateText(...)
}
class AmberModelRouter(...) : ModelRouter {
    // 收编 resolveTaskChatModel + findProvider 样板，统一模型不可用处理
    override fun session(policy: ModelSelectionPolicy): LlmSession = ...
}
```

**分类处置：**

| 调用方 | 处置 |
|---|---|
| chat / DeepRead(2 处) / SubAgent / BoardTask | 走 `ProviderLlmSession`（拿富类型 `GenerationChunk`） |
| 安全 one-shot：标题建议、MiniApp | `modelRouter.session().collect` 直接拿返回值（**不是** `AgentRunner.launch`，否则每个标题灌一行 `agent_runtime.db`） |
| JSON one-shot：MemoryExtractor、BoardAgent | 走 session 但 `transformers=emptyList`，**加字节级回归测试**断言与老的 `response.choices[0].message.toText()` 逐字节相等（`MessageStreamAccumulator` 会改空白撑爆 JSON 解析）|
| 压缩摘要 | **永远直连**（重入） |
| 模型议会 | 重试要改请求参数（drop reasoning/temperature），transport 分类器收编不了，**保留 Agent 层重试** |
| 9 个非 turn 探针 | **显式直连**，CI guard 白名单放行 |

> ⚠️ **设计稿藏起来的真实成本（必须明算）**：`GenerationHandler` **向下** import 了 `feature.tools`（9 类）、`feature.runtime`（`AgentToolDispatcher`/`SpeculativeToolRunner`/`AgentLoopBudgetPrompt`，而 `:feature:runtime:impl` 还不存在）、`feature.miniapp/board`，还用了 18 个 `R.string`。所以它**进不了"核心环"**，除非先注入 `ToolProvider`/`StringProvider` 接口把依赖方向倒过来 + 一轮 5 语言字符串迁移。这是 §6 Phase 1 的真实价签，约 3-4 周。

### 4.3 拆两个上帝类（顺接缝、每步可发、每步可回退）

**`ChatService` 2532 → ~700**（不是乐观的 550），按依赖安全的接缝顺序，一个 PR 一步：

1. `GenerationNotificationCoordinator` + `TaskTracker`（1649-1843，纯副作用，零共享状态）。保 `AgentNotificationActionReceiver` 广播串 + PendingIntent 字节稳定。
2. `ConversationQueueTools`→`feature/tools`、`RunToolsFactory`+`MemoryToolFactory`（纯工厂，产 `List<Tool>`）。
3. `IdleToolResolver`（870-971）+ `ToolApprovalCoordinator`（1121-1248）。注意 `trustedRunToolNames` 在 **4 处**被改（1143/1420/1488/2394），全部改道。
4. **`ConversationMutator`**（1542-2205，~400 行 edit/delete/fork/merge）。**最高数据丢失风险、零测试**。搬之前**强制**先补 characterization 快照测试（+1 周，不可省）——`selectIndex` 差一就静默毁聊天记录。
5. `TimelineLoader` + `PendingMessageQueue` actor。保 revision-guard（`op.revision==current`）和 `Channel.close()` 生命周期。
6. `ChatTransformers` holder（普通注入类，**不是** `MessagePipeline` 实现）。先 **diff** `handleMessageComplete` 与 `ChatSessionResolverImpl` 两份 transformer 列表——它们**现在不一致**，这步顺便消除分歧。
7. `onCompletion`（1360-1480）保持**一个有序协调块**，别打散。`checkpoint(force=true)` 在 task-finish **之前**是承重的。
8. dispatch/`handleMessageComplete` legacy seam **最后**删，只在 Phase 6 之后。

**`DeepReadAgentRunManager` 1181 → ~300**（adapter 签名不动，纯内部拆解）：
`TimingPolicy`（纯，~50 行最易）→ `ModelResolver`+`PromptBuilder`（~420 行无状态）→ **`StageSupervisor`+`VerificationSupervisor`**（硬接缝，1-1.5 周 + 真实选题回归跑）。后者是套在 ~12 个 per-run 局部上的嵌套闭包，要用显式 `DeepReadStageContext` 在 `run()` 里构造一次传进去，且逐字保住 `withTimeout(...) + isDeepReadTimeoutLike → markMissingFailed` 这条链（误拆会把软的阶段超时变成硬的整 run 取消）。

> 别把 kernel 的 `ToolSession`/`MessagePipeline`/`PermissionBroker` 当拆分目标——那些是形状不兼容的**平行未用抽象**，`GenerationHandler` 直接吃 `List<Tool>`+`List<*Transformer>`、根本不碰它们。当成普通去重做，别动参数签名。

### 4.4 目标模块图 + 依赖规则

模块创建顺序（按真实依赖方向，不是按直觉）：

1. **`:core:db` 第一**（搬 `AppDatabase` + DAO + entity，**只物理搬**，包名/DB 文件名/schema 目录全冻结）。`:core:memory:impl` 硬依赖它。
2. **`:core:generation:impl` 第二**，但只能在 `ToolProvider`/`StringProvider` 接口反转之后；隐藏前置是先抽出 `:feature:runtime:impl`（4 个文件）。
3. `:core:sync:impl` + `:core:automation:impl`：**唯一真正能并行**的一对（无 db/feature 耦合）。
4. `:core:context:impl` + `:core:memory:impl`：在 `:core:db` **之后**串行。
5. `:feature:chat:impl`：**被 DI 环阻塞**（§2.4），环破才能编译。
6. `:feature:chat:surface`（262 个 Compose 文件）**最后、最大**，挂 `USE_SPLIT_CHATPAGE_COMPOSABLES` flag。

**依赖规则（用 Gradle 原生模块图检查强制，不是子串启发式）：**
- Surface 只能依赖 `:api` + `:core:agent-runtime`，**禁止**依赖任何 `:feature:*:impl`。
- `board:impl` 只看 `Generator` 接口（`:core:ai:generation:api`），**不要**随手加 `:core:generation:impl` 依赖边（会把 53 个 transformer+mcp 文件拖进 board 编译图）。
- 守卫用 konsist `.architecture{}` 或自定义 dependency-verification task，按真实 `project()` 边判，半迁移期也有牙。

### 4.5 渐进 vs 拆除（为什么选渐进）

| 维度 | 渐进 / Strangler（★选） | 拆除 / Kernel-first 重写（弃） |
|---|---|---|
| 工作量(1-2人) | ~28-34 工程周聚焦，~7-9 月、边迁边发 | 35-50 工程周 / 9-14 月，超过 plan 自己的逃生阀 |
| 风险 | 低：每步翻 flag 或纯代码搬移，1 commit 可回退，老路全程兜底 | 高且前置：5 个月暗箱重写、bus-factor=1、冻结漂移、个人级 app 还交双推理的钱 |
| 收益 | 稳定切片，前 3 个月就把去重价值落袋 | 一条干净脊柱 + 删 `GenerationHandler`——但前提是合同能载 loop，代码证明不能 |
| 致命缺陷 | 无致命，真实成本是前置工（接口反转、破 DI 环、ChatTurnAgent 对等）| **致命**（见下） |

**拆除路线的致命缺陷（已用代码证实）：** `ModelRouter.kt:41` 的 `ChatTurn.messages: List<Any>`，`TurnEvent` 是字符串化的 `Delta(String)/ToolCall(id,name,args:String)/Done(String)`。而真实对话循环跑在 `UIMessagePart.Tool` 对象上、把有状态的 `SpeculativeToolRunner` 穿进 token 流、每步携带整个 `List<UIMessage>`、还有 ~190 行 GenerativeUi 回退作为流式控制流。要塞进 `Flow<TurnEvent>` 只能：(a) 加宽 wire 类型——这会改 `ChatEventProjector` 写进 `amber_agent.db` 的持久化类型，升级即 MessageNode 不兼容（踩冻结不变量）；或 (b) 把 `UIMessage` 走私进 `List<Any>` 强转——废掉"单一类型化通道"论点本身。拆除自己的 kill criterion 就是"不许动 projector 持久化类型"，和自己的核心手段自相矛盾。

**决断：渐进。** 只从拆除借**一个**想法——用单一 `ProviderLlmSession` 做 chat 唯一 provider 接缝——但做成包裹 `GenerationHandler` 的 adapter，永不删除。若将来一个 1 周的 contract-fitting spike 能证明 coarse 类型确实能端到端承载一条真实黄金转录，再在后续周期重议删除。

---

## 5. 为什么这样设计（关键决策的理由）

1. **为什么 adapter-over-rewrite**：`GenerationHandler` 累积了大量难以重新推导的行为（33ms flush、speculative tool、GenerativeUi 回退、重试分类器）。包裹它而非重写，把"统一接缝"的收益拿到手，同时不赌"能重新写对"。`DeepReadAgentAdapter` 已经是这个模式的生产样板。
2. **为什么富类型留内部、`TurnEvent` 只当审计**：持久化 wire 类型是冻结面（升级兼容性）。让 `GenerationChunk` 留在 `:core:generation:impl` 内部、`TurnEvent` 退化成 coarse 审计日志，是同时拿到"类型安全的真实循环"和"稳定的持久化契约"的唯一方式。
3. **为什么 ChatTurnAgent 对等是第一步而非翻 flag**：下游每个胜利（chat 路径统一模型、删 `launchPendingMessageLoop`、ChatService 缩成 facade、`:feature:chat:impl` 抽出）都 gated 在"kernel 真能载生产 chat"上，而今天翻 flag 会静默掉 5 个钩子 + 工具审批断流 + regenerate/branch 根本不走 kernel。先把骨架填成真脊柱，才解锁其余。
4. **为什么 one-shot 不走 `AgentRunner.launch`**：它 fire-and-forget 且丢弃 artifact（`InProcessAgentRunner.kt:90`）。标题/建议/MiniApp 每次调用灌一行 run 记录会把 `agent_runtime.db` 撑爆。`launch` 留给真正的多步 agent。
5. **为什么 JSON 调用方要禁 transformer**：对它们，transformer 管线是**危害**不是福利——会改空白破坏严格 JSON 解析。
6. **为什么先建 `:core:db`**：它是依赖图的底，`memory`/`context` 都 drag 进它；先落地它，上层模块才有地方依赖。
7. **为什么破 DI 环和拆 ChatService 耦合**：环的根因是 `ConversationAccess` 的实现就是 `ChatService` 本体；把会话窗口/持久化逻辑抽进 `:core:conversation:api` 既破环又是 ChatService 拆分的一部分，一举两得。

---

## 6. 迁移路径（Phase 0-9，~7-9 月，边发边迁）

| Phase | 周 | 内容 | 可验证里程碑 |
|---|---|---|---|
| **0** | 1-3 | 卫生 + 可观测：`gitignore .claude/worktrees`（下有 ~773 个陈旧 `me.rerere` 文件会污染守卫）+ `.playwright-mcp`；`check-refactor-state.sh` 的 `MIN_MODULES 19→48`；加 Gradle 原生模块图守卫；每个模型调用打 `GenerationRoute` Crashlytics tag + debug overlay；one-shot 字节 diff harness | 每个调用自报路径 |
| **1** | 3-7 | **接口反转前置工**：抽 `:feature:runtime:impl`（4 文件）；给 `GenerationHandler` 注入 `ToolProvider` 倒转 `feature.tools/runtime` 依赖；`R.string`（`generation_retry_status` + 17 个 `placeholder_*`）换成注入的 `StringProvider` 或迁到资源模块 + 5 语言 | GH 可上移 |
| **2** | 7-12 | 建 `:core:db`、`:core:generation:impl`（物理搬 GH + 13 transformer + McpManager，**保留包名** `app.amber.core.ai`，零调用方改动）；加 `ProviderLlmSession` + `AmberModelRouter` 接 DI；5 个 Tier-1 调用方仍直调 `generateText` | 编译绿、零行为变化 |
| **3** | 10-16 | DeepRead 上帝类内部拆解（TimingPolicy → ModelResolver+PromptBuilder → StageContext+StageSupervisor+VerificationSupervisor），adapter 签名不变；supervisor seam 后跑真实选题对比 | 文章结构对齐基线 |
| **4** | 14-22 | ChatService 按接缝顺序拆（ConversationMutator 前**先补 characterization 测试**），每步一个可回退 PR，dispatch seam 留最后 | ChatService 逐步变薄 |
| **5** | 20-26 | **破 DI 环**（下面一切的阻塞前置）：把 `ConversationAccess` 提成 `:core:conversation:api`，`ChatTurnAgent` 构造器从 `get<ChatService>()` 改 `get<ConversationAccess>()` | `:feature:chat:impl` 可独立编译 |
| **6** | 24-30 | **ChatTurnAgent 对等**（翻 flag 前唯一闸门）：onSuccess 钩子搬进 post-run `AgentEvent.Final` handler（标题/建议/记忆抽取含 windowed→full 重载/FTS/清理/压缩）；用现成 `ChatTurnInput.regenerateOf` 实现 kernel regenerate；修 `launchViaKernel` 的 observe 谓词让它扛过 `AWAITING_PERMISSION` 并接续工具审批；`ChatSessionResolverImpl` 指向 `runToolsFactory.build(settings, conversationId)`（注意 `createDebugRunTools==createRunTools(settings, NULL)`，这是补全工具不是 no-op）| kernel 真能载生产 chat |
| **7** | 30-34 | 翻 `useKernelPath=true` 挂**布尔 kill-switch**（`NativePathBootstrap` 只有 getBoolean，没 cohort 分桶；要么 +2-3 周建，要么单布尔翻 + 人盯 Crashlytics）。按**功能清单**验收：标题出现/建议出现/记忆写入/FTS 命中/审批弹窗/regenerate 出兄弟节点/分支切换/图文消息 | 默认走 kernel |
| **8** | 34-40 | 安全 turn one-shot（标题建议、MiniApp）走 `modelRouter.session()` 直接 collect；JSON 调用方空 transformer + 字节回归；压缩/议会/9 探针显式直连 | Tier 2 收敛 |
| **9** | 38-44（kernel 稳定 ≥4 周后）| 删 `launchPendingMessageLoop` + legacy 分支，ChatService 变薄 facade；加 CI guard 禁特定 `generateText/streamText` overload 出现在 `:core:generation:impl` 外（白名单非 turn 探针 + 冻结 FQN 文件）| 落锤 |
| — | Phase 5 起 | `:feature:chat:surface`（262 Compose）+ 冷门叶子（miniapp/cron/live/office/webmount-70）作**机会主义长尾并行**，永不阻塞发版 | — |

---

## 7. 好处

- **一个调模型接缝**：新功能拿 transformer/重试/流式/token 预算"开箱即用"，不再抄 Tier 2 样板；Tier 2 那 8 条静默缺失 transformer 的路径自动补上模板变量/think 标签/正则/OCR。
- **可测、可回退**：每步是纯搬移 + 委托调用，1 commit 回退；上帝类拆出的协作者可单测（`ConversationMutator`、`DeepReadStageSupervisor` 终于能 stub）。
- **Kernel 沉没成本变现**：已建好的 13 接口 + event store + projector 开始产生价值，对话变成可持久化、可重放、可观测的 run 事件流。
- **DeepRead 失败率下降**：拆开 supervisor/prompt/超时缠绕后，harness 那份分析里的"改一处动全身"消失，单点修复（验证门非阻塞化等）能安全落地。
- **`ChatService` 从 2532 → ~700**：对话相关改动不再在 2500 行里穿针。
- **编译期防回归**：模块图守卫 + CI guard 让"Surface 泄漏 impl""新调用方绕开统一接缝""新文件落 legacy 包"在 PR 阶段就被挡。

---

## 8. 未来怎么维护

**守卫（让架构不退化）：**
- **模块图守卫**（Gradle 原生）：Surface 不依赖 impl、board 不拖 generation:impl、新模块进 `MIN_MODULES` 计数。
- **CI guard**：禁 `generateText/streamText` 具体 overload 出现在 `:core:generation:impl` 外（白名单非 turn 探针 + 4 个 JNI 桥 + 冻结 FQN）。
- **`GenerationRoute` 遥测**：每个模型调用自报走的哪条路；上线后能看到"还有多少流量绕开统一接缝"。

**冻结面清单（任何 PR 碰到要在描述里显式标注 "modifies frozen surface"）：**
- DB 文件 `amber_agent` + schema FQN `app.amber.agent.data.db.AppDatabase`（不能改，否则用户升级丢数据）。
- DataStore name `settings` + 全部 key（含 `NativeAccelFlags` 的 `useNative*`，即使废弃也不删 key，否则 kill switch 断）。
- 备份魔数 `amber_agent_backups`。
- 4 个 `Java_app_amber_*` JNI 符号前缀（改 Kotlin 包要同步改 Rust 符号）。
- 2 个故意保留的 `me.rerere.*` FQN（`AgentNotificationActionReceiver`、`PreferenceStoreV1Migration`）。

**约定：**
- 新代码一律落 `app.amber.*`；新 agent 实现 `Agent<I,A>` 接口、注册进 registry，不再往 ChatService 加分支。
- 新的"调模型"需求先问：是 turn 形状吗？是 → 走 `ProviderLlmSession`；不是（探针/压缩）→ 直连并加进 CI guard 白名单。
- 两库版本号联动：`AppDatabase` 与 `AgentRuntimeDatabase` 各自只向后兼容 2 个版本，CI 跑 N×M migration 矩阵。

---

## 9. 如何跟 rikkahub 完全脱钩

目标：`git grep -i rikkahub` 与 `git grep "me\.rerere"` 归零（仅留 LICENSE 归属 + git 历史）。当前残留与处置：

| 残留 | 现状 | 彻底脱钩做法 |
|---|---|---|
| `AgentNotificationActionReceiver` 的 `me.rerere.*` 广播 action 串 | 冻结（外部 PendingIntent 绑死）| 发一个版本同时注册新旧 action，迁移期后只留新串；或接受它作为永久兼容残留 |
| `PreferenceStoreV1Migration` 的 FQN | 冻结（老备份反序列化绑死类 FQN）| 写一个一次性 data migration 把旧备份转成新魔数后，标 `@Deprecated` 并在 2 个大版本后删 |
| 4 个 `com.github.rikkahub:*` Maven 坐标 | 上游 fork 仓库 URL | 把 4 个 fork 重新发布到 `app-amber/*` GitHub org，改 `libs.versions.toml` 坐标（一次性、与代码无关）|
| `LICENSE` "Forked from rikkahub" | 归属义务 | **保留**（AGPL 上游归属义务，合规要求，不删）|
| git 历史 commit message | 不可变 | 不处理；如确需，单独决策（filter-repo 重写历史，破坏性）|
| `docs/*.md` 里的历史引用 | 文档 | 批量替换 + `refactor-progress.md` 加"改名前条目"免责头 |

**顺序**：先做与代码无关的（重发 4 个 fork、改坐标、文档替换）→ 再做 2 个 FQN 的 data migration（需版本节奏）→ 最后核验 `git grep` 归零（除 §8 例外）。脱钩不是这次重构的主线，可作为长尾穿插。

---

## 10. 承重墙 / 风险（任何一步被迫碰到 = 该步作废，不变量赢）

1. **重入死锁**：压缩摘要必须留引擎下面直连——`generateText` 顶部无条件 `prepareContext`，路由回引擎=无限递归。不可商量。
2. **冻结 wire 类型**：任何加宽 `ChatTurn/TurnEvent` 去载真实 UIMessage/tool 的尝试都改 `ChatEventProjector` 写盘的 `:feature:chat:api` payload → 升级即 MessageNode 不兼容。富类型留内部，`TurnEvent` 当 coarse 审计。
3. **DI 环是结构性的**，与上帝类拆分耦合，不是独立晚期步骤。低估它 → `:feature:chat:impl` 永远编不过。
4. **`createDebugRunTools==createRunTools(settings, NULL)`**：repoint resolver 是**行为变化**（补 context/history/queue/subagent 工具），当工具对等修复 + 冒烟测试，不是无脑搬。
5. **`ConversationMutator`（~400 行、零测试）是最高数据丢失风险**：characterization 测试**前置且不可省**，+1 周。
6. **JSON 字节对等**：MemoryExtractor/BoardAgent 严格解析 JSON，`MessageStreamAccumulator` 改空白就崩。空 transformer + 字节回归必备。
7. **Rollout 基础设施不存在**：`NativePathBootstrap` 是布尔 kill-switch，没 cohort 分桶。要么 +2-3 周建，要么单布尔翻 + 人盯 Crashlytics，并把上线安全性评级调低。
8. **Strangler 别永不收尾**：Phase 9 删除留硬里程碑 + kill criterion（kernel 稳定 ≥4 周）。若过 9 个月，以"kernel 默认 + legacy 保留"收尾，不为赶日期 rush-delete 兜底路径。

---

## 附录 A：本文已核实的代码事实

| 断言 | 证据 |
|---|---|
| `useKernelPath` 默认 false 且永不为真 | `ChatService.kt:732` `var useKernelPath = false`，唯一读点 `:724`，全库无赋值 true |
| Kernel wire 类型 coarse | `ModelRouter.kt:40-49` `ChatTurn.messages: List<Any>`，`TurnEvent.Delta(content: String)` 等 |
| DI 环存在且自述 | `AgentRuntimeModule.kt:50` `ChatTurnAgent(get(), get(), get<…ChatService>())`，注释 `:69-71` 写明 `ChatService → AgentRunner → ChatEventProjector → ChatService` |
| ChatService 规模 | 2532 行，`app/amber/core/service/ChatService.kt` |
| DeepReadAgentRunManager 规模 | 1181 行，10 职责，无接口 |
| GenerationHandler / SectionWriterTools 非上帝类 | 1141 / 1194 行，职责内聚（见 §2.2 末） |
| 13 条调模型路径 | 5 Tier-1（走 GenerationHandler）+ 8 Tier-2（直连 Provider）|
| 48 个 Gradle 模块 | `settings.gradle.kts` |
| 脱钩残留 | 2 个冻结 FQN + 4 个 maven fork 坐标 + LICENSE（`scorched-earth-audit.md`）|
