# AmberAgent 重构方案：Agent Kernel + Surfaces

> 状态：v1.2.1 最终修订版 + main 现状二次审计（2026-05-22 晚）
> 作用：从 rikkahub 深度 fork 演化为独立 Agent 平台架构的执行手册
> 团队规模假设：1-2 人
> 估计总周期：6-9 个月（其中"事实上脱 fork"约 4-6 个月）
>
> v1.2 关键修订：Assistant↔Agent 映射明文化、Agent 接口 handler 拆分、AgentEvent Final/Transient 双轨、agent_run 加 message_node_id、Surface/MessagePipeline/PermissionBroker 一等公民化、Phase A.5 NPF + 远端 flag 通道拆分、Phase D escape hatch、跨库备份 sanitizer。
>
> v1.2.1 增量：新增 §2.6 main 现状二次审计——DeepRead 已自演化出"准 Runner"（DeepReadAgentRunManager）、§1.5 的 Assistant 复用模式已有生产实例 (`DeepReadHiddenAssistantFactory`)、`AgentRuntimeModels.kt` 已存在的 `AgentRun` 类型衔接策略明文化、ChatService 基线行数 2347→2618、data/agent 子目录 19→23。

---

## 0. 一句话目标

**不靠重命名宣布独立，靠把 ChatService 不再是宇宙中心 + 把 AgentRun 变成持久化事件流 + 把 19 个 agent 子系统从 `:app` 拆出去，让 AmberAgent 在工程上真正成为自己。**

---

## 1. 非目标（明确不做）

| 不做 | 为什么 |
|---|---|
| 一次性全包名重命名 | 会撞 Room schema 路径 + JNI 符号绑定，**Phase 0 阻塞器** |
| 替换 Room → SQLDelight | Room 没痛点，13 个 migration 是真资产 |
| 替换 Compose → Compose Multiplatform | UI KMP 还在 1.x 阶段，agent 应用先 KMP 逻辑层就够 |
| 重写 PDF / Markdown / Highlight 的 Rust 实现 | 已经是 MuPDF/pulldown-cmark/syntect 级别，重写=降级 |
| Phase 0 切 DI（Koin → Metro/Kotlin Inject） | 跟脱 fork 无关，独立技术决策，留到 Phase F |
| 为了"diff 行数"重写工具层（`common/highlight/search/tts/web/document`） | 重写零架构价值 |
| 改磁盘 DB 文件名 / DataStore name / 备份格式 keyword | 用户升级会无声丢数据 |

---

## 1.5 核心概念词汇表与映射 ★ v1.2 新增

> v1.1 的最大盲点：1444 行里 **Assistant** 一次都没出现，但它是用户面对的核心概念（system prompt、模型参数、tool 启用集、transformer、memory 的承载体）。Kernel 引入的 Agent / AgentRun 必须显式映射到 Assistant / Conversation / MessageNode，否则 Phase C 落地时会出现"用户面对一套概念、引擎内部另一套"的撕裂。

### 1.5.1 用户面对的概念（不变，全部保留）

| 概念 | 定义 | 当前存储 |
|---|---|---|
| **Assistant** | 用户配置的"助手"：system prompt + 模型参数 + tool 启用集 + transformer 配置 + memory option + lorebook | Room `assistant` 表 + DataStore 关联 prefs |
| **Conversation** | 持久化对话线程，含 MessageNode 树 | Room `conversation` 表 |
| **MessageNode** | 对话树节点，含 N 个候选 UIMessage 实现 branching | Room（embedded in conversation） |
| **UIMessage** | 一条消息，含 N 个 content parts（text/image/document/reasoning/tool_call/tool_result） | MessageNode 内嵌 |

### 1.5.2 Kernel 引入的概念

| 概念 | 定义 | 存储 |
|---|---|---|
| **Agent** | 工程概念：一种"会做某类事情"的能力定义（ChatTurnAgent / DeepReadAgent / WorkspaceAgent / ...）。**不是 user-facing**。 | 代码 + AgentRegistry 内存注册 |
| **AgentRun** | 一次 Agent 执行的生命周期记录 | `AgentRuntimeDatabase.agent_run` |
| **AgentEvent** | run 上的有序事件流。**Final 入库，Transient 仅 SharedFlow**。 | `agent_event`（仅 Final） |
| **TraceSpan** | 开发者视角的粗粒度时间线（RUN / MODEL_TURN / TOOL_CALL / HANDOFF / PERMISSION） | `trace_span` |
| **Surface** | 消费 AgentEvent + 派发 Command 的边界对象（ChatPage VM、DeepReadScreen VM、Notification、ShareReceiver…） | `:core:agent-runtime` 接口 |

> **★ v1.2.1 现有同名类型冲突说明**：
> - `me.rerere.rikkahub.data.agent.AgentRun` (data class, `AgentRuntimeModels.kt:30`) — **现有**，给 ChatService 路径用。保留不动。
> - `me.rerere.rikkahub.data.agent.AgentRunStatus` (enum, 同文件 L9) — **现有**，保留不动。
> - `me.rerere.rikkahub.data.agent.subagent.SubAgentRunStatus` (`SubAgentModels.kt:145`) — 平行类型，保留不动。
> - **新引入** `app.amber.core.agent.runtime.AgentRunRecord` (Room entity) — Phase A TA.2 落地，存于 `agent_runtime.db`。
> - **新引入** `app.amber.core.agent.runtime.AgentRunSnapshot` (in-memory 投影) — Phase A TA.1。
>
> 两套类型在 Phase A-C 共存。Phase D-E 才考虑把现有 `AgentRun` data class 逐步标 `@Deprecated` 收敛到新模型，但 plan 不强制收敛。

### 1.5.3 关键映射（v1.2 锁定）

```
Assistant (user-facing)  ──配置注入──►  ChatTurnAgent (engine)
                                        每次 launch 都把 assistantId 塞进 ChatTurnInput
                                        ChatTurnAgent 是单一 Agent 实例，所有 Assistant 共享

Conversation  ──持有 N 个──►  MessageNode  ──持有 N 个候选──►  UIMessage
                                  ▲                              ▲
                                  │  message_node_id              │  produces_message_id
                                  │                               │
                                  └──────────  AgentRun  ─────────┘
```

**关键不变量**（v1.2 落库前必须落 ADR-0002）：

1. **ChatTurnAgent 是唯一的"对话型 Agent"**，所有 Assistant 共享它。区分通过 `ChatTurnInput.assistantId`，**不为每个 Assistant 注册新 Agent**。
2. **一个 MessageNode 的 N 个 UIMessage candidate 对应 N 个 AgentRun**（按 `produces_message_id` 关联）。
3. **regenerate / branch switch 不创建新 MessageNode**，只在已有 node 里新增 AgentRun + UIMessage candidate。
4. **AgentSession** 不引入为独立持久化概念。一个 Conversation 就是它的 session 边界；handoff 不跨 Conversation。
5. **删除 Assistant 不级联删除其历史 AgentRun**——run 是审计记录。但 Assistant 软删除后 UI 隐藏关联 run。

### 1.5.4 概念禁忌

- ❌ 不引入 `AssistantAgent` / `AgentInstance` 之类的中间类——Assistant 是配置，Agent 是能力，不要造第三个。
- ❌ 不让 Assistant 直接持有 Agent 引用——Assistant 仍然是数据类，Agent 通过 registry 解析。
- ❌ 不在 AgentRun 表里冗余 Assistant 字段——只存 assistantId，display name 等通过 join 取最新。

---

## 2. 架构目标（recap）

### 现在（rikkahub DNA）

```
RouteActivity → ChatService（2347 行 god class）→ ai/Provider
                       │
                       └──► data/agent/* 211 个文件"贴"在 :app 旁边
```

### 目标（AmberAgent DNA）

```
┌─── UI Surfaces（可插拔）─────────────────────────┐
│ ChatSurface  BoardSurface  DeepReadSurface ...   │
└─────────────────┬────────────────────────────────┘
                  │ observe(runId) / dispatch(input)
┌─────────────────▼────────────────────────────────┐
│ Agent Kernel                                     │
│   AgentRegistry · AgentRunner · ToolRegistry     │
│   PermissionBroker · EventBus                    │
│   AgentEventStore (持久化, append-only)          │
└─────────────────┬────────────────────────────────┘
                  │
   ┌──────┬──────┼──────┬──────┬──────┬──────┐
   ▼      ▼      ▼      ▼      ▼      ▼      ▼
 :chat :board :dread :mapp :wmnt :mcouncil :term
   │      │      │      │      │      │      │
   └──────┴──────┴──────┴──────┴──────┴──────┘
                  │
┌─────────────────▼────────────────────────────────┐
│ Platform                                         │
│  :core:agent-runtime（纯 Kotlin, KMP-ready）     │
│  :core:agent-store-room                          │
│  :core:native:*（UniFFI bindings）                │
│  :amber-llm（原 ai/，演化版）                    │
│  其它工具层（不动）                              │
└──────────────────────────────────────────────────┘
```

**关键反转**：Chat 不再是中心，是 23+ agent 之一（v1.2.1 数字更新；plan v1.1 写 19，main 已增到 23：board / cron / history / icloud / live / miniapp / modelcouncil / office / prompts / runtime / subagent / system / task / terminal / tools / webmount / webview / workspace + 零散根目录文件）。Kernel 是中心，Surface 订阅事件流。

---

## 2.5 Rust 现状审计（2026-05-21）

写这份 plan 之前发现一个反常事实：**当前发版的 AmberAgent 里没有任何 Rust 代码在跑**。

### 三条铁证

1. **4 个 native bridge 类零调用方**

   ```bash
   grep -rn "import.*MarkdownParserNative|RegexTransformerNative|HighlighterNative|OfficeParserNative"
   # → 空
   ```
   `internal object MarkdownParserNative` 等声明孤立在 codebase 里，无任何文件 import。

2. **PR #9 合并 commit (`ad303128`) 明文声明这是结构性 prototype**

   > Confirms this PR merges to main as a structural Phase 1 prototype.
   > **§8.4 explicitly listing what's NOT**: any production-caller switch, feature flag wiring, Crashlytics, byte-equal acceptance evidence.
   >
   > **§8.3 Phase 2 HARD GATE** — before any production caller is allowed to call into `nativebridge.*Native`, the PR doing that switch MUST ship: feature flag / kill switch (default JVM), gradual rollout cohorts, Crashlytics tagging for native failures, JVM-vs-Rust output divergence sampling, and a single-step revert plan.

3. **APK 实际打包的 .so 不包含任何 Rust crate 产物**

   `app/src/main/jniLibs/` 里只有：
   - `libsimple.so`（SQLite FTS 扩展）
   - `libproot-loader.so`（terminal）
   - `libmupdf_java.so`（PDF，from document/）

   **没有** `libmarkdown_parser.so` / `libregex_transformer.so` / `libhighlight_parser.so` / `liboffice_parsers.so`。Codex Round 3 follow-up 指出："the current onlyIf guard would silently skip native build" —— 即便要打包，build pipeline 可能悄悄跳过。

### 含义

| 维度 | 真实状态 |
|---|---|
| 用户感知 | 0 Rust 加速 |
| Rust 代码质量 | 跑过 cross-component review V2 + Round 1/2/3 sub-agent fixes，**production-grade** |
| 接线工作量 | 不大（bridge 已写好），主要是 HARD GATE 5 件套 |
| 风险 | 已有完整 spike + review，接线风险已知 |

**这是健康工程纪律**（没把没经过 gate 的 native 塞给用户），但也意味着所有"Rust 加速"的话术目前是**潜在收益**而非已交付收益。

### 对 plan 的影响

新增 **Phase A.5（§4.5）**：在 Kernel 合同立起来后、DeepRead extractor 之前，先把现有 markdown + regex 两个 spike crate 接线上线，**用户首次真实体验 Rust 加速**。HARD GATE 5 件套同时立成 reusable 模板（**ADR-0004**），后面所有 native crate（tokenizer/extractor/schema/imaging/...）上线都过这一套。

---

## 2.6 main 现状二次审计（2026-05-22 晚，★ v1.2.1 新增）

v1.2 plan 完成后，从 GitHub `origin/main` 拉取最新代码做对照检查，发现五个事实，影响 Phase A / B / D 的执行细节。

### 2.6.1 plan 撰写后 main 的演化

| 时间 | commit | 主题 |
|---|---|---|
| 2026-05-22 00:06 | `8fc858ab` | chore(deepread): drop legacy DeepReadAgent, add timeouts（**净减 1440 行**） |
| 2026-05-22 00:27 | `77bd808b` | feat(deepread): pre-fetch sources outside the model loop（Phase F） |
| 2026-05-22 00:47 | `c84c8b33` | feat(deepread): run sections in parallel where independent（Phase B） |
| 2026-05-22 00:56 | `d26565ce` | feat(deepread): cache pre-fetched sources within a session（Phase E） |
| 2026-05-22 01:03 | `064642fb` | fix(deepread): release fullRunInFlight guard on cancel or exception |
| 2026-05-22 15:42 | `c95ed0d1` | feat(deepread): add playbook visuals and template workbench |
| 2026-05-22 20:07 | `3942a8c7` | **Make deep reading durable enough to revisit and retry** |

> **DeepRead 在 plan v1.2 写作的同一时间窗口内自己做了一次 mini-refactor**：drop 了 4-stage legacy agent (1400 行)、引入 supervisor loop、加 section-level retry、加 history、加 verified status。这跟 plan §5 想做的 90% 重叠。

### 2.6.2 五个关键事实

**事实 1 — §2.5 Rust 审计 100% 仍成立**（不需要改）

| 维度 | 复核结果 |
|---|---|
| `grep -r "import.*MarkdownParserNative" --include="*.kt"` | 0（不含 nativebridge 自身） |
| `RegexTransformerNative` | 0 |
| `HighlighterNative` | 0 |
| `OfficeParserNative` | 0 |
| `app/src/main/jniLibs/arm64-v8a/` | 只有 `libsimple.so`、`libproot-loader.so`，**0 个 Rust 产物** |

§2.5 / §4.5 Phase A.5 的所有论证不变。

**事实 2 — DeepRead 已经自演化出"准 Runner"**

`app/src/main/java/me/rerere/rikkahub/data/agent/board/hotlist/deepread/DeepReadAgentRunManager.kt` 含：

- `suspend fun runPreview(...)`
- `suspend fun run(...)`
- `suspend fun runSection(...)`
- `suspend fun runWith(stream: Boolean)`
- `suspend fun runStageSupervisorLoop(stage: DeepReadGenerationStage)`
- `suspend fun runVerificationSupervisorLoop()`
- `private fun scheduleBackgroundFill(...)`
- 含 retry / cache / fresh / missing-stage / verified status / playbook 等机制

**这是 plan §5 Phase B TB.3 几乎一对一的目标功能**。意味着：
- DeepRead 不再是"白纸第一个 agent 样板"——它已经是有自己完整执行框架的、活跃在改的业务子系统
- Plan §5 假设的"3-4 周从零做 DeepRead extractor + 接 Kernel" 时间表偏乐观
- 不能简单替换 RunManager，要适配（衔接策略见 §2.6.3）

**事实 3 — Assistant 复用模式已有生产实例（强力背书 §1.5）**

`DeepReadHiddenAssistantFactory.kt`：

```kotlin
internal object DeepReadHiddenAssistantFactory {
    fun create(settings: Settings): Assistant {
        val base = settings.getCurrentAssistant()
        return base.copy(
            id = Uuid.random(),
            name = "Deep Read Agent",
            systemPrompt = systemPrompt(),
            streamOutput = true,
            enableMemory = false,
            // ... 大量 override
        )
    }
}
```

**这就是 §1.5 锁定的"Assistant 是配置，Agent 是能力"模式的生产实现**：复用现有 `Assistant` data class、`copy()` override 部分字段、不引入新概念。Plan §1.5 不再是空想，main 上有可参考的样板。

**事实 4 — `AgentRuntimeModels.kt` 已存在 `AgentRun` 类型**

`app/src/main/java/me/rerere/rikkahub/data/agent/AgentRuntimeModels.kt`:
- L9：`enum class AgentRunStatus`
- L30：`data class AgentRun`

Plan §4 TA.2 的 `AgentRunRecord` 与现有 `AgentRun` 同名不同形——**plan 必须明文化衔接策略**（见 §2.6.3 衔接 #2）。`subagent/SubAgentModels.kt` 还有 `SubAgentRunStatus` 是平行类型，也要在 §1.5 词汇表里 cross-reference。

**事实 5 — ChatService 基线已恶化**

| 指标 | plan v1.1 写时 | v1.2.1 复核 | 趋势 |
|---|---|---|---|
| `ChatService.kt` 行数 | 2347 | **2618 (+271)** | god class 持续膨胀 |
| `data/agent/` 子目录数 | 19 | **23 (+4)** | board/cron/history/live/prompts/webview 等增长 |
| 净增主要在 | — | board / tools 子树（mtime 2026-05-22 15:59 仍活跃） | Phase D 排序需调整 |

Phase C TC.3 的退化目标基线（"< 700 行"已经在 v1.2 改成行为性断言）暗示的削减量从 1647 行变成 1918 行，但断言形态不变。

### 2.6.3 对 plan 的具体调整

#### 调整 #1：Phase B 选型重审

**问题**：DeepRead 已经不是白纸，强行重写会跟当前活跃开发冲突。

**两个选项**：

| 选项 | 含义 | 推荐度 |
|---|---|---|
| **A. 适配而非重写** | 保留 `DeepReadAgentRunManager` 本体，把它包装成 `Agent<DeepReadInput, DeepReadArtifact>.handler`。RunManager 内部已有 retry/cache/supervisor loop **全部保留**。只做：(a) RunScope wiring、(b) AgentEvent 派生、(c) projector 写 chat.db | **★ 推荐** |
| B. 换更小的 agent 做样板 | 候选：`subagent`、`modelcouncil`、内部 title-summary、Workspace `runtime/` | 备选 |
| C. 推迟 Phase B | 等 DeepRead 这轮自演化稳定再迁 | 不推荐——拖延窗口期 |

**选 A 的理由**：
- DeepRead 的复杂业务编排本身就是 Kernel 接口的好压力测试
- `DeepReadHiddenAssistantFactory` 已经是 §1.5 模式的生产样板，迁移成本低
- v1.2 的 Final/Transient event 双轨在 DeepRead 的 supervisor loop 上能立即验证

**Phase B 估时调整**：从 3-4 周改为 **4-5 周**（含 RunManager 适配 vs 重写权衡 0.5 周 + supervisor loop 接 RunScope 1 周）。

#### 调整 #2：现有 `AgentRuntimeModels.kt` 的衔接策略（Phase A TA.1 子任务）

**不动现有文件，但在 §1.5 词汇表加注释 + Phase A 内做名字空间分离**：

| 当前类型 | Phase A 处理 | 命名 |
|---|---|---|
| `me.rerere.rikkahub.data.agent.AgentRun` (data class) | 保留，rename in-memory snapshot 类型 | `LegacyAgentRunSnapshot` 或同等 |
| `me.rerere.rikkahub.data.agent.AgentRunStatus` (enum) | 保留作为现有 ChatService 路径用 | 不动 |
| `me.rerere.rikkahub.data.agent.subagent.SubAgentRunStatus` | 保留 | 不动 |
| **新引入** `app.amber.core.agent.runtime.AgentRunRecord` (Room entity) | Phase A TA.2 落地 | **新名字避免命名冲突** |
| **新引入** `app.amber.core.agent.runtime.AgentRunSnapshot` (in-memory) | Phase A TA.1 | 新名字 |

**修订 §4 TA.2**：表名保持 `agent_run`，但 Kotlin entity 类名用 `AgentRunRecord` 而非 `AgentRunRecord`，避免跟现有 `AgentRun` data class 视觉混淆。**这是 v1.2.1 对 v1.2 的一处命名修订**。

#### 调整 #3：Phase D 优先级排序更新

v1.1/v1.2 §7 给的优先级：board → miniapp → webmount → modelcouncil → subagent → workspace → terminal → office → memory → icloud → settings

v1.2.1 调整理由：board / tools 在 2026-05-22 还在每天改。机会主义"顺手迁"的前提是"动到这个子系统"——这两个高频改的不缺迁移时机；反而是冷门子系统（`cron / history / live / prompts / webview / system / task`）应该提前预热，因为它们一旦动起来就没人愿意搬。

新优先级（v1.2.1）：

```
高频活跃（机会主义自然会触发）：
  1. :feature:tools         ← 每天改
  2. :feature:board         ← 每天改（含 DeepRead，Phase B 已部分迁完）

中频：
  3. :feature:miniapp
  4. :feature:webmount

低频但复杂（按 v1.1/v1.2 节奏）：
  5. :feature:modelcouncil
  6. :feature:subagent
  7. :feature:workspace
  8. :feature:terminal
  9. :feature:office
  10. :feature:memory       ← 带 vector-index

★ 冷门优先预热（v1.2.1 提前）：
  11. :feature:cron / :history / :live / :prompts / :webview / :system / :task
       └── Phase D 中段集中做一个小 sprint 一起拆出去，避免长尾僵尸化

最后做：
  12. :feature:icloud / :sync
  13. :feature:settings     ← 牵全局 prefs，留尾
```

#### 调整 #4：Phase B 衔接 RunManager 的具体步骤

新增 TB.3.5（v1.2.1）—— `DeepReadAgentRunManager` 适配到 `AgentHandler`：

```kotlin
class DeepReadAgent(
    private val runManager: DeepReadAgentRunManager,        // ★ 复用现有
    private val hiddenAssistantFactory: DeepReadHiddenAssistantFactory,
) : Agent<DeepReadInput, DeepReadArtifact> {

    override val descriptor = DeepReadDescriptor.value

    override val handler = AgentHandler<DeepReadInput, DeepReadArtifact> { input, scope ->
        // 1. RunScope wiring：把 scope 注入到 RunManager 的回调
        val collector = DeepReadRunScopeCollector(scope)

        // 2. RunManager 内部的 retry / supervisor / cache 全部不动
        val output = runManager.run(
            seedUrl = input.url,
            topicId = input.topicId,
            topicTitle = input.title,
            stages = input.stages,
            force = input.force,
            onProgress = collector::emit,                   // ★ Transient
            onSectionComplete = collector::commit,          // ★ Final
        )

        // 3. 派生 artifact
        output.toArtifact()
    }
}
```

**关键约束**：
- `DeepReadAgentRunManager` 不动算法、不动 supervisor loop
- 新增 `DeepReadRunScopeCollector` 桥接 RunManager 内部进度信号到 AgentEvent
- 现有 history/retry/verified 等机制保留——不在 Kernel 层重新发明

**Effort**：1-1.5 周（含 collector 抽象 + 现有调用方迁移）

### 2.6.4 v1.2.1 不变的事项

下列 v1.2 决策经 main 复核后**不需要任何修订**：

- §2.5 Rust 审计的所有数字
- §1.5 Assistant↔Agent 映射（main 上已有生产样板背书）
- §4 TA.1 接口骨架（handler / Final/Transient / Surface / PermissionBroker / handoff 预留）
- §4 TA.2 `agent_run` 加 message_node_id / produces_message_id / assistant_id（schema 决策）
- §4 TA.3 UniFFI pioneer 拆分（TA.3a + TA.3b）
- §4.5 NPF + 远端 flag 拆分
- §6 TC.6 projector property-based test gating
- §10.4 跨库版本联动 + backup sanitizer
- Phase 0 / E / F 全部内容

---

## 3. Phase 0 — 防御性奠基（2-4 天）

### Why

为后续所有 phase 建立"不破坏现有用户数据 + 防止新代码回流 legacy 包"的护栏。**这一步是阻塞器**：不做完，Phase A 写新代码会污染 legacy 包，Phase E 清理时痛苦十倍。

### Deliverables

1. `app.amber.*` 包结构骨架（空目录占位）
2. `me.rerere.*` legacy 白名单文件
3. detekt + CI 防回流闸
4. Phase 0 不变性清单文档（DB 名 / DataStore key / 备份格式 / JNI 符号）

### Tasks

#### T0.1 — 建立 `app.amber.*` 包骨架

```
app/src/main/java/app/amber/
├── .gitkeep
├── core/.gitkeep
├── feature/.gitkeep
└── platform/.gitkeep
```

**验收**：目录存在；任何新 Kotlin 文件可以选择落 `app.amber.*` 或 legacy。

**Effort**：30 分钟

#### T0.2 — 写 legacy 白名单 `config/legacy-package-allowlist.txt`

```
# 必须保留在 me.rerere.* 包名的文件（破除任何一条都会导致用户数据丢失或运行时崩溃）

# Room schema 路径绑定 @Database 类的 FQN
app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt

# JNI 符号名硬编码 Java 包路径（Java_me_rerere_*_*）
# 见 native/markdown-parser/src/lib.rs:59
# 见 native/regex-transformer/src/lib.rs:34
app/src/main/java/me/rerere/rikkahub/ui/components/richtext/nativebridge/MarkdownParserNative.kt
app/src/main/java/me/rerere/rikkahub/data/model/nativebridge/RegexTransformerNative.kt

# 整个 highlight 模块的 Kotlin 包（HighlighterNative 在内）
# 见 native/highlight-parser/src/lib.rs:24
highlight/src/main/java/me/rerere/highlight/**

# 整个 document 模块的 Kotlin 包（OfficeParserNative + PdfParser 在内）
# 见 native/office-parsers/src/lib.rs:90
document/src/main/java/me/rerere/document/**
```

**验收**：文件存在；后续所有重命名/迁移操作前先 grep 这份清单。

**Effort**：30 分钟

#### T0.3 — detekt 自定义规则 `LegacyPackageMustNotGrow`

新建 `config/detekt/custom-rules/src/main/kotlin/LegacyPackageMustNotGrow.kt`：

```kotlin
class LegacyPackageMustNotGrow(config: Config) : Rule(config) {
    override val issue = Issue(
        "LegacyPackageMustNotGrow",
        Severity.Defect,
        "新文件不允许添加进 me.rerere.* 包（除白名单外）",
        Debt.TWENTY_MINS,
    )

    private val allowlist by lazy {
        File("config/legacy-package-allowlist.txt").readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }
            .toSet()
    }

    override fun visitKtFile(file: KtFile) {
        val pkg = file.packageFqName.asString()
        if (!pkg.startsWith("me.rerere")) return
        val relPath = file.virtualFilePath.substringAfter("AmberAgent/")
        if (allowlist.any { matches(relPath, it) }) return
        report(CodeSmell(issue, Entity.from(file),
            "$relPath 必须放进 app.amber.* —— legacy 包冻结，新代码禁止进入"))
    }
}
```

**验收**：故意往 `me.rerere.rikkahub.foo` 加一个新文件 → `./gradlew detekt` fail；移到 `app.amber.foo` → pass。

**Effort**：3-4 小时（含 detekt convention plugin 配置）

#### T0.4 — CI 加防回流 job

`.github/workflows/legacy-package-guard.yml`：

```yaml
name: Legacy Package Guard
on: [pull_request]
jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - name: New files must not land in me.rerere.*
        run: |
          git diff --name-only --diff-filter=A origin/${{ github.base_ref }}...HEAD \
            | grep -E "^(app|ai|common|highlight|search|tts|document|web)/src/.*/java/me/rerere/" \
            | grep -vFf config/legacy-package-allowlist.txt > /tmp/violations.txt || true
          if [ -s /tmp/violations.txt ]; then
            echo "::error::以下新文件落进 legacy 包，必须移到 app.amber.*："
            cat /tmp/violations.txt; exit 1
          fi
```

**验收**：PR 加新 legacy 文件 → CI fail；加新 `app.amber.*` 文件 → CI pass。

**Effort**：1-2 小时

#### T0.5 — Phase 0 不变性清单 `docs/adr/0001-legacy-frozen-surfaces.md`

明文列出：
- Room DB 文件名（保持 `chat.db` 或当前名）
- DataStore name（`UIPrefs / ProviderPrefs / AssistantPrefs / ChatPrefs` 等）
- 备份格式中的 keyword（`rikkahub_backup_v*` 之类）
- JNI 符号前缀映射表（4 个 crate 各自的 `Java_me_rerere_*`）
- `applicationId = me.rerere.amberagent`（已经是了，不动）
- `android:namespace = me.rerere.rikkahub`（暂时不动，Phase E 再考虑）
- ★ **AppDatabase 与未来 AgentRuntimeDatabase 的版本号联动表**：两库各自只能向后兼容 2 个版本；CI 跑 N×M migration 矩阵测试（详见 §10.4）。
- ★ **NativeAccelFlags DataStore key 永久保留**：`useNativeMarkdown / useNativeRegex / useNativeTokenizer / ...` 即使废弃也不删除 key，避免 kill switch 被改名打断。

**验收**：文档存在；任何 PR 触碰这些项需要在描述里显式标注 "modifies frozen surface"。

**Effort**：1-2 小时

### Exit criteria

```bash
# 全部为真即通过
[ -d "app/src/main/java/app/amber" ]
[ -f "config/legacy-package-allowlist.txt" ]
./gradlew detekt    # 通过
# 故意制造违规：
mkdir -p app/src/main/java/me/rerere/rikkahub/test_violation
echo 'package me.rerere.rikkahub.test_violation' > .../X.kt
./gradlew detekt    # 应该 fail
rm -rf .../test_violation
```

### Risks

- **Risk**: detekt custom rule 跟现有项目集成不顺
  **Mitigation**: 退化到 git pre-commit hook + CI 双保险，detekt 集成留作改进
- **Risk**: 团队不习惯写 `app.amber.*`
  **Mitigation**: IDE live template + 一份 `CLAUDE.md`/`AGENTS.md` 里加 contributor note

### Rollback

`git revert` Phase 0 提交即可，不影响任何运行时行为。

---

## 4. Phase A — Kernel 合同 + 首个 UniFFI 试水（2-3 周）

### Why

定义"什么是 Agent / 什么是 Run / 什么是 RunScope"的合同，让后续所有 agent 都能往同一个抽象迁移。**这一步不搬代码**，只立合同 + 让 `GenerationHandler` 成为合同的第一个消费者证明合同可用。

### Deliverables

1. `:core:agent-runtime` 模块（纯 Kotlin，零 Android 依赖）
2. `:core:agent-store-room` 模块（Room 实现 `AgentEventStore`）
3. `:core:native:tokenizer` crate（UniFFI 模板示范）
4. `GenerationHandler` 改造成 `RunScope` 第一个消费者（行为不变）
5. ADR-0002《Agent Kernel 接口设计》

### Tasks

#### TA.1 — 新建 `:core:agent-runtime` 模块（纯 Kotlin）

`settings.gradle.kts` 加入 `include(":core:agent-runtime")`。

`core/agent-runtime/build.gradle.kts`：

```kotlin
plugins {
    id("amber.kotlin.library")  // Phase D 会建 convention plugin；现在内联 kotlin-jvm + 必要插件
    kotlin("plugin.serialization")
}
dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
}
```

**包路径**：`app.amber.core.agent.runtime`（强制，detekt 会拦截）

接口最小集（v1.2 修订版，OpenAI Agents SDK 视角校准过）：

```kotlin
// ============================================================
// app/amber/core/agent/runtime/Agent.kt
// ★ v1.2 改：Agent 是声明，handler 是函数式属性；唯一执行路径是 AgentRunner
// ============================================================
interface Agent<INPUT : AgentInput, ARTIFACT : AgentArtifact> {
    val descriptor: AgentDescriptor
    val handler: AgentHandler<INPUT, ARTIFACT>   // ★ 不再有 suspend fun run()
}

fun interface AgentHandler<INPUT : AgentInput, ARTIFACT : AgentArtifact> {
    suspend fun handle(input: INPUT, scope: RunScope): ARTIFACT
}

interface AgentInput
interface AgentArtifact

data class AgentDescriptor(
    val id: AgentDescriptorId,
    val version: String,
    val displayName: String,
    val capabilities: Set<AgentCapability>,
)

@JvmInline value class AgentDescriptorId(val value: String)
@JvmInline value class AgentRunId(val value: String) {
    companion object { fun new(): AgentRunId = AgentRunId(UUID.randomUUID().toString()) }
}

// ============================================================
// app/amber/core/agent/runtime/RunScope.kt
// ★ v1.2 改：tracing / permission / messagePipeline / handoff 升级为一等公民
// ============================================================
interface RunScope {
    val runId: AgentRunId
    val parentRunId: AgentRunId?
    val conversationId: ConversationId?
    val messageNodeId: MessageNodeId?         // ★ ChatTurn 必填；DeepRead 可空
    val coroutineContext: CoroutineContext    // ★ 取消走标准协程，删 CancellationToken

    // 事件（用户叙事流 + 持久化双轨）
    val events: AgentEventWriter

    // 工具调用（含 permission gate + version stamp）
    val tools: ToolSession

    // 模型调用（不直连 Provider）
    val llm: LlmSession

    // ★ 新：开发者视角粗粒度时间线
    val tracing: TraceRecorder

    // ★ 新：权限/审批（可挂起，跨进程恢复）
    val permission: PermissionBroker

    // ★ 新：消息 transformer pipeline（ChatAgent 用，其它 agent 不必接）
    val messagePipeline: MessagePipeline

    // 子 run：agent-as-tool（父 run 继续）
    suspend fun <T> child(
        descriptor: AgentDescriptor,
        block: suspend RunScope.() -> T,
    ): T

    // ★ 新：handoff —— 控制权转移，父 run 以 Handoff event 结束
    //   Phase B 不实现，Phase C 末或 Phase D 落地；接口先预留避免后期合同破坏
    suspend fun handoff(
        target: AgentDescriptorId,
        payload: HandoffPayload,
    ): Nothing

    fun ensureActive()                        // 协程取消检查的 ergonomic 包装
}

// ============================================================
// app/amber/core/agent/runtime/AgentEvent.kt
// ★ v1.2 改：Final / Transient 双轨；流式 chunk 不入库
// ============================================================
sealed interface AgentEventPayload {
    /** 用户叙事流的离散事实，落 Room；agent 完成的快照可从 Final 流 replay 出来 */
    interface Final : AgentEventPayload

    /** 流式中间态（每秒 30+ 的 token chunk），仅 SharedFlow，不落库 */
    interface Transient : AgentEventPayload
}

interface AgentEventWriter {
    /** Transient 走 in-memory ring buffer，fire-and-forget */
    fun emit(transient: AgentEventPayload.Transient)

    /** Final 持久化，suspend 因为 IO；返回后保证已落 Room */
    suspend fun commit(final: AgentEventPayload.Final)

    /** 显式 flush 边界，用于 turn 结束等 checkpoint */
    suspend fun flush()

    suspend fun commitError(throwable: Throwable, recoverable: Boolean)
}

// ============================================================
// app/amber/core/agent/runtime/TraceRecorder.kt
// ★ v1.2 新增：与 AgentEvent 分离的开发者视角时间线
// ============================================================
interface TraceRecorder {
    suspend fun <T> span(
        name: String,
        kind: SpanKind,
        attributes: SpanAttrs = SpanAttrs.empty(),
        block: suspend SpanScope.() -> T,
    ): T
}

enum class SpanKind { RUN, MODEL_TURN, TOOL_CALL, HANDOFF, PERMISSION }
// 严禁 per-token / per-chunk span；超过 5 种 kind 需要 ADR。

// ============================================================
// app/amber/core/agent/runtime/PermissionBroker.kt
// ★ v1.2 新增：审批是 run 层级可中断状态，必须能跨进程恢复
// ============================================================
interface PermissionBroker {
    suspend fun request(intent: PermissionIntent): PermissionDecision
}

data class PermissionIntent(
    val kind: PermissionKind,                 // TOOL_INVOKE / FILE_ACCESS / DESTRUCTIVE_OP / ...
    val toolId: ToolId?,
    val payloadDigest: String,                // 不带敏感原文
    val reason: String,
    val channel: ApprovalChannel,             // IN_CHAT / NOTIFICATION / SYSTEM_DIALOG
)

sealed interface PermissionDecision {
    object Allow : PermissionDecision
    data class Deny(val reason: String) : PermissionDecision
    data class TimedOut(val after: Duration) : PermissionDecision
}

// ============================================================
// app/amber/core/agent/runtime/MessagePipeline.kt
// ★ v1.2 新增：transformer pipeline 从 ChatService god class 抽离
// 复用现有 InputMessageTransformer / OutputMessageTransformer 实现
// ============================================================
interface MessagePipeline {
    suspend fun transformInput(
        messages: List<UIMessage>,
        ctx: PipelineCtx,
    ): List<UIMessage>

    fun transformOutput(
        streaming: Flow<TurnEvent>,
        ctx: PipelineCtx,
    ): Flow<TurnEvent>
}

data class PipelineCtx(
    val assistantId: AssistantId,
    val conversationId: ConversationId,
    val variables: Map<String, String>,       // TemplateTransformer 等用
)

// ============================================================
// app/amber/core/agent/runtime/ToolSession.kt
// ★ v1.2 加：ToolDescriptor 含 version / source / isStable
// ============================================================
interface ToolSession {
    fun listAvailable(): List<ToolDescriptor>
    suspend fun invoke(toolId: ToolId, args: JsonElement): ToolCallResult
}

data class ToolDescriptor(
    val id: ToolId,
    val version: ToolVersion,                 // ★ 语义版本，stamp 进 ToolCallPart
    val source: ToolSource,                   // BUILTIN / MCP_SERVER(serverId) / CUSTOM
    val schema: JsonSchema,
    val isStable: Boolean,                    // ★ unstable tool 的产出不进 Final event
    val permission: ToolPermission,           // AUTO / REQUIRE_APPROVAL / DESTRUCTIVE
)

sealed interface ToolSource {
    object Builtin : ToolSource
    data class McpServer(val serverId: String) : ToolSource
    data class Custom(val ownerId: String) : ToolSource
}

@JvmInline value class ToolId(val value: String)
@JvmInline value class ToolVersion(val value: String)

// ============================================================
// app/amber/core/agent/runtime/Surface.kt
// ★ v1.2 新增：Kernel ↔ UI 边界的可编译期断言
// ============================================================
interface Surface<STATE, COMMAND> {
    val supportedAgents: Set<AgentDescriptorId>
    fun stateFor(runId: AgentRunId): StateFlow<STATE>
    suspend fun dispatch(command: COMMAND)
}
// detekt 规则：Surface 实现类只能依赖 :api + :core:agent-runtime；
//             禁止依赖 :feature:*:impl。Phase E exit 的可编译期断言。

// ============================================================
// app/amber/core/agent/runtime/AgentRegistry.kt
// ============================================================
interface AgentRegistry {
    fun <I : AgentInput, A : AgentArtifact> register(
        descriptor: AgentDescriptor,
        inputClass: KClass<I>,                // ★ v1.2 加：runtime 类型校验
        inputSerializer: KSerializer<I>,
        artifactSerializer: KSerializer<A>,
        factory: () -> Agent<I, A>,
    )
    fun resolve(id: AgentDescriptorId): RegisteredAgent<*, *>?
}

// ============================================================
// app/amber/core/agent/runtime/AgentRunner.kt
// ★ v1.2 改：launch 返回 Result，把 type mismatch 显式化
// ============================================================
interface AgentRunner {
    fun <I : AgentInput> launch(
        descriptorId: AgentDescriptorId,
        input: I,
    ): Result<AgentRunHandle>                 // ★ AgentMismatchError 不抛 ClassCastException

    fun observe(runId: AgentRunId): StateFlow<AgentRunSnapshot>
    fun cancel(runId: AgentRunId)
    suspend fun listUnfinishedRuns(): List<AgentRunSnapshot>
}

interface AgentEventStore {
    suspend fun appendRun(run: AgentRunRecord)
    suspend fun appendEvent(event: AgentEventRecord)
    suspend fun appendSpan(span: TraceSpanRecord)        // ★ v1.2 加
    fun observeRun(runId: AgentRunId): Flow<AgentRunSnapshot>
    suspend fun listUnfinishedRuns(): List<AgentRunRecord>
    suspend fun markInterrupted(runId: AgentRunId, reason: String)
}

// ============================================================
// app/amber/core/agent/runtime/ModelRouter.kt
// ============================================================
interface ModelRouter {
    fun session(policy: ModelSelectionPolicy): LlmSession
}

interface LlmSession {
    val descriptor: ModelDescriptor
    suspend fun countTokens(parts: List<TokenizableSegment>): TokenBudget
    suspend fun streamTurn(turn: ChatTurn): Flow<TurnEvent>
}
```

**v1.2 接口设计 ADR 要点**（写进 ADR-0002）：

1. **为什么 Agent 拆 handler 而不是保留 `suspend fun run()`**：避免 Kotlin extension function 和 Koin 注入诱导出多入口（`agent.runStreaming()` / `agent.runWith(retry)`），让 AgentRunner 是唯一执行路径。代价是声明多一行。
2. **为什么 AgentEvent 拆 Final/Transient**：流式 chunk 每秒 30+ 条，全部入库一次长对话 1MB+。Final 才是用户叙事事实，Transient 只服务于 in-flight UI。
3. **为什么 handoff 接口先预留再实现**：等 Phase C 末或 Phase D 才用，但接口不预留 → `child()` 语义会被悄悄拉伸成两用。
4. **为什么 permission 是 RunScope 一等公民而不是 ToolSession 子**：Workspace / Terminal 等非工具调用也有"是否允许"的 gate，permission 不应该寄生在 ToolSession 里。
5. **为什么 launch 返回 Result**：泛型 `Agent<I,A>` 在 registry 端星投影成 `<*,*>`，必然要 cast。把失败显式化避免 ClassCastException 漏诊。
6. **为什么 ToolDescriptor 含 version + isStable**：MCP server 升级 schema、老对话 replay、unstable tool 不入 Final stream，都需要描述符级别的版本与稳定性标记。
7. **为什么 Surface 是 Kotlin interface 不是文档概念**：Phase E exit criteria 要能用 detekt 断言"Surface 不依赖 feature impl"。文档概念无法编译期保证。

**验收**：
- `./gradlew :core:agent-runtime:compileKotlin` 通过
- 模块零 Android 依赖（`./gradlew :core:agent-runtime:dependencies | grep android` 为空）

**Effort**：3-5 天（接口设计 + 序列化器 + 单测）

#### TA.2 — 新建 `:core:agent-store-room` 模块

```kotlin
// ★ v1.2.1：类名用 AgentRunRecord 而非 AgentRunRecord，避免跟现有
//   me.rerere.rikkahub.data.agent.AgentRun (data class) 视觉混淆。
//   表名仍为 agent_run。衔接策略详见 §2.6.3 调整 #2。
@Entity(
    tableName = "agent_run",
    indices = [
        Index("status"),
        Index("agent_descriptor_id"),
        Index("conversation_id"),
        Index("message_node_id"),                 // ★ v1.2 新增
        Index("assistant_id"),                    // ★ v1.2 新增
    ]
)
data class AgentRunRecord(
    @PrimaryKey val runId: String,
    val parentRunId: String?,
    val agentDescriptorId: String,
    val agentVersion: String,
    val conversationId: String?,
    val messageNodeId: String?,                   // ★ v1.2：ChatTurn 必填，关联到 MessageNode
    val producesMessageId: String?,               // ★ v1.2：此 run 产出的 UIMessage candidate id
    val assistantId: String?,                     // ★ v1.2：ChatTurn 必填，便于按 Assistant 聚合统计
    val status: String,                           // running / awaiting_permission / completed / failed / interrupted / cancelled
    val inputDigest: String,
    val inputSnapshotRef: String?,                // ★ v1.2：改成引用，见下文 attachment URI 策略
    val inputSchemaVersion: Int,
    val startedAt: Long,
    val finishedAt: Long?,
    val interruptedReason: String?,
)

@Entity(
    tableName = "agent_event",
    indices = [Index(value = ["run_id", "seq"], unique = true), Index("run_id"), Index("ts")]
)
data class AgentEventEntity(
    @PrimaryKey val eventId: String,
    val runId: String,
    val parentRunId: String?,
    val seq: Long,
    val type: String,
    val payloadType: String,
    val payload: String,             // JsonElement.toString()
    val payloadSchemaVersion: Int,
    val agentDescriptorId: String,
    val agentVersion: String,
    val isFinal: Boolean,            // ★ v1.2：恒为 true（Transient 不入表）；保留字段防 schema 演化
    val ts: Long,
)

// ★ v1.2 新增：开发者向 trace span，与 AgentEvent 分离
@Entity(
    tableName = "trace_span",
    indices = [
        Index("run_id"),
        Index("parent_span_id"),
        Index("kind"),
        Index("started_at"),
    ]
)
data class TraceSpanEntity(
    @PrimaryKey val spanId: String,
    val runId: String,
    val parentSpanId: String?,
    val name: String,
    val kind: String,                // RUN / MODEL_TURN / TOOL_CALL / HANDOFF / PERMISSION
    val status: String,              // ok / error
    val startedAt: Long,
    val endedAt: Long?,
    val attributesJson: String,      // 不含敏感 payload，只放尺寸/计数/模型名
)

// ★ v1.2 新增：审批意图持久化（跨进程恢复的 source of truth）
@Entity(
    tableName = "permission_intent",
    indices = [Index("run_id"), Index("decision"), Index("created_at")]
)
data class PermissionIntentEntity(
    @PrimaryKey val intentId: String,
    val runId: String,
    val kind: String,
    val toolId: String?,
    val payloadDigest: String,
    val reason: String,
    val channel: String,
    val decision: String,            // pending / allow / deny / timed_out
    val createdAt: Long,
    val decidedAt: Long?,
    val decidedBy: String?,
)

@Dao interface AgentRuntimeDao {
    @Insert suspend fun insertRun(run: AgentRunRecord)
    @Update suspend fun updateRun(run: AgentRunRecord)
    @Insert suspend fun insertEvent(event: AgentEventEntity)
    @Insert suspend fun insertSpan(span: TraceSpanEntity)                    // ★ v1.2
    @Insert suspend fun insertPermissionIntent(p: PermissionIntentEntity)    // ★ v1.2

    @Query("SELECT * FROM agent_run WHERE runId = :id") fun observeRun(id: String): Flow<AgentRunRecord?>
    @Query("SELECT * FROM agent_event WHERE runId = :id ORDER BY seq ASC") fun observeEvents(id: String): Flow<List<AgentEventEntity>>
    @Query("SELECT * FROM agent_run WHERE status IN ('running', 'awaiting_permission')") suspend fun listUnfinished(): List<AgentRunRecord>

    // ★ v1.2：按 MessageNode 反查 run 历史（regenerate 的全部 candidate）
    @Query("SELECT * FROM agent_run WHERE messageNodeId = :id ORDER BY startedAt ASC")
    suspend fun listRunsForMessageNode(id: String): List<AgentRunRecord>

    // ★ v1.2：retention 清理（30 天前已完成 run 的 trace_span）
    @Query("DELETE FROM trace_span WHERE runId IN (SELECT runId FROM agent_run WHERE status='completed' AND finishedAt < :cutoff)")
    suspend fun pruneOldSpans(cutoff: Long)
}
```

**三个关键 schema 决策**：

1. **`agent_run` / `agent_event` / `trace_span` / `permission_intent` 是新表**，**不进 `AppDatabase`**。新建 `AgentRuntimeDatabase`，独立 DB 文件 `agent_runtime.db`。
   **理由**：避免触碰 `AppDatabase` 的 13/30 个 migration；agent runtime 状态跟 chat history 是不同 lifecycle 的数据，物理分库合理。两库版本号联动表见 §10.4。
2. **payload 用 JSON 不用 protobuf**：可读、可演进（schemaVersion 字段保护）、序列化器复用 kotlinx.serialization。代价是体积大 30-50%，但单条 event 几百字节级别可接受。
3. **★ v1.2 `inputSnapshotRef` 改成引用而非内联 JSON**：
   - input 含 attachment URI（ContentResolver）的，URI 权限随 host activity 销毁失效——存原 URI 等于半年后 replay 不出来
   - 改成：`inputSnapshotRef` 指向 `internal_storage/agent_runtime_snapshots/<runId>.json`，attachment 由 ChatTurnAgent 在 launch 前复制到 internal storage 并替换 URI
   - DB 只存 path + sha256，文件落 internal cache（备份时按 §10.4 sanitizer 处理）
   - input 编译期由 `kotlinx.serialization` 校验可序列化，不可序列化字段编译失败

**验收**：
- `./gradlew :core:agent-store-room:test` 跑过 in-memory Room 测试
- 单测覆盖：appendEvent 严格 seq 递增、并发 append 无重复 seq、observeRun 实时投影

**Effort**：3-4 天

#### TA.3 — UniFFI 接入 + tokenizer crate（v1.2 拆分版）

> v1.1 把这一步估为 5-7 天。实测 UniFFI on Android 多 ABI 头一次跑通要 10-14 天（cargo-ndk × 4 ABI、UniFFI Gradle plugin、ProGuard keep 规则、release minify、CI cross-compile），后续 crate 复用模板才 3 天。v1.2 拆成 TA.3a / TA.3b 分别估算。

#### TA.3a — UniFFI Android Pioneer（10-14 天，一次性投入）

新建 `build-logic/convention/AmberNativeRustConventionPlugin.kt`（**提前**到 Phase A，不再延后到 Phase D §7 TD.X.1）：

- cargo-ndk 4 ABI 配置（`arm64-v8a / armv7 / x86_64 / x86`）
- UniFFI Gradle plugin 集成（UDL → Kotlin scaffolding 自动生成）
- ProGuard / R8 keep 规则模板（UniFFI 反射调用）
- jniLibs 打包规则
- CI（Linux runner）cross-compile pipeline
- release build minify 验证

**验收**：用一个 hello-world UDL（一个函数返回字符串）端到端跑通：debug + release 双构建、4 ABI .so 进 APK、JVM unit test 调用 binding 成功。

**Effort**：10-14 天（pipeline 调通工作量，跟业务实现解耦）

#### TA.3b — `:core:native:tokenizer` 业务实现（3 天，复用 TA.3a 模板）

新建 `core/native/tokenizer/`：

```
core/native/tokenizer/
├── Cargo.toml
├── tokenizer.udl                    # UniFFI 接口定义
├── src/
│   ├── lib.rs                       # rust 实现
│   └── tokenizers/
│       ├── tiktoken_o200k.rs
│       ├── tiktoken_cl100k.rs
│       ├── claude_approx.rs         # Anthropic 没公开，近似
│       └── gemini_sentencepiece.rs
└── build.rs                          # uniffi_build::generate_scaffolding
```

`tokenizer.udl`：

```idl
namespace tokenizer {
    sequence<u32> count_batch(sequence<TokenizableSegment> segments);
};

dictionary TokenizableSegment {
    string tokenizer_id;
    string text;
};

[Error]
enum TokenizerError {
    "UnknownTokenizer",
    "InvalidInput",
};
```

**关键设计决策**（吸收 Codex 反馈 + v1.2 修订）：

1. **batch API 而不是单条**：一次跨 JNI 处理 N 段文本，amortize marshal cost
2. **按 `tokenizer_id` 不按 `model_id`**：model → tokenizer 的映射放 Kotlin 侧 `ModelToTokenizerResolver`，Rust 只关心 tokenizer 自己
3. **同步 API**：tokenizer 是 CPU-bound 短任务（< 50ms），Kotlin 调用方自己 `withContext(Dispatchers.IO)`，不用 UniFFI async 的 tokio runtime
4. **APK 体积**：cl100k 词表 ~1.6MB，o200k ~2MB，Claude 近似 < 100KB，Gemini SP 模型 ~2-4MB。**总计 5-8MB**
   - ★ v1.2 决策点（Q11）：默认走 `assets/tokenizer/` 打包，**按需 lazy load 到 Rust 内存**
   - 若 APK base size 接近 Play 上传上限（150MB），切换 **Play Asset Delivery on-demand**：用户首次切到对应 provider 时下载词表，1-2 秒延迟掩盖在 model dropdown 加载里
   - PAD 切换工作量预算 5 天，超出则维持 assets 直到下个 APK size 危机

Kotlin 端调用：

```kotlin
// app/amber/core/llm/TokenCounter.kt
class TokenCounter(private val nativeImpl: tokenizer.Tokenizer) {
    suspend fun count(segments: List<TokenizableSegment>): List<Int> =
        withContext(Dispatchers.Default) {
            nativeImpl.countBatch(segments.map { it.toFfi() })
        }
}
```

**验收**：
- `cargo build` + `./gradlew :core:native:tokenizer:assemble` 双绿
- JVM 单测（UniFFI 生成的 binding 可以在 JVM 跑）覆盖 4 个 tokenizer
- benchmark：1000 段 100-token 文本 batch 调用 < 200ms（包含 marshal）

**Effort**：3 天（基于 TA.3a 的 pipeline，业务实现成本）

#### TA.4 — `GenerationHandler` 改造成 `RunScope` 第一个消费者

**不搬家、不改行为**。只把 `AgentToolDispatcher / SpeculativeToolRunner / PermissionDecisionResolver` 这三件套从 `GenerationHandler` 的直接持有改为通过 `RunScope.tools` 拿。

中间适配层：

```kotlin
// app/amber/core/agent/runtime/adapter/LegacyRunScope.kt
// 给老 ChatService/GenerationHandler 用，让它们能"假装"在 RunScope 里跑
class LegacyRunScope(
    private val dispatcher: AgentToolDispatcher,
    private val speculative: SpeculativeToolRunner,
    private val permission: PermissionDecisionResolver,
    override val llm: LlmSession,
) : RunScope {
    override val tools: ToolSession = LegacyToolSession(dispatcher, speculative, permission)
    // 其它字段先 stub
}
```

**验收**：
- ChatService 流程行为完全不变（手工冒烟：发消息、工具调用、权限审批、取消）
- `GenerationHandler` 内不再直接 `new AgentToolDispatcher(...)`，全部走 `scope.tools.*`
- 单元测试：用 fake RunScope 喂 GenerationHandler，验证它不再依赖具体实现类

**Effort**：3-4 天

#### TA.5 — ADR-0002《Agent Kernel 接口设计》（v1.2 扩充）

落档：
- 为什么 `Agent<INPUT, ARTIFACT>` 而不是 `Agent`（typed registry 收益 vs 复杂度）
- ★ **为什么 Agent 拆 `handler: AgentHandler` 而不是保留 `suspend fun run()`**（OpenAI Agents SDK 视角教训）
- 为什么 RunScope 而不是裸 CoroutineScope（生命周期/取消/写事件需要 owner）
- ★ **为什么 RunScope 含 tracing / permission / messagePipeline / handoff 四个一等公民**（vs 寄生在 ToolSession 里）
- ★ **为什么 AgentEvent 拆 Final / Transient 双轨**（流式 chunk 入库的容量代价）
- ★ **为什么 ChatTurnInput 含 assistantId 而 ChatTurnAgent 是单例**（Assistant 是配置，Agent 是能力）
- ★ **为什么 agent_run 含 messageNodeId / producesMessageId**（branching + regenerate 的关联）
- ★ **为什么 launch 返回 Result 而不是直接返回 Handle**（星投影 cast 失败显式化）
- 为什么 `AgentRuntimeDatabase` 独立而不是塞 `AppDatabase`
- 为什么 batch tokenizer 而不是单条
- 为什么 UniFFI 而不是手写 JNI（参考 ADR-0003）
- ★ **为什么 Surface 是 Kotlin interface 不是文档概念**（Phase E 可编译期断言）

**Effort**：2 天（v1.2 涉及条目翻倍）

### Exit criteria

- 上述 5 个 task 全部完成
- `:core:agent-runtime` 和 `:core:agent-store-room` 模块发布到 maven local 可被 `:app` 消费
- ChatService 行为完全没变，但 `GenerationHandler` 已经在用 `RunScope` 接口（中间通过 LegacyRunScope adapter）
- tokenizer 在新 LlmSession 接口里可用，但**还不强制**所有调用方迁移

### Risks

- **Risk**: `AgentRuntimeDatabase` 跟 `AppDatabase` 有跨库事务需求
  **Mitigation**: Agent run 跟 chat message 通过 ID 关联，不需要事务一致性；最坏情况是 run 完成但 message 没保存 → 可恢复（Phase B 处理）
- **Risk**: UniFFI gradle 集成跟现有 cargo-ndk + Exec task 冲突
  **Mitigation**: 新 crate 走 UniFFI gradle plugin，老 4 个 crate 保持 Exec task，并存
- **Risk**: TokenCounter 准确性差（Claude/Gemini 近似）
  **Mitigation**: 文档明确"近似 ±5%"，UI 显示用"约 N tokens"

### Rollback

`:app` 不依赖新模块也能编译（适配层在 `:app` 内）。回滚 = `git revert`，Room 不会创建新 DB 文件（懒初始化），不影响用户数据。

---

## 4.5 Phase A.5 — 接线现有 markdown + regex（HARD GATE 模板首跑，1-2 周）

### Why

§2.5 揭示当前用户没用上任何 Rust。在 Phase A 立完 Kernel 合同后、Phase B 写新 extractor 之前插入这一步：

- markdown 和 regex 是已有 spike 产物，**0 设计成本**
- 跑通 PR #9 自己规定的 HARD GATE 5 件套一次，**templated process 立起来**，后面 tokenizer/extractor 复用
- **用户在 1-2 周内首次真实体验到 Rust 加速**（markdown 渲染 + 消息 regex 转换肉眼可感）
- 把"Rust 加速"从概念变成已交付价值，给后续 Rust 投资建立信心

### Deliverables

1. `useNativeMarkdown` / `useNativeRegex` 远端可控的 feature flag
2. Markdown / Regex 调用方加 dispatch 分支（flag on → bridge，flag off → JVM）
3. Divergence sampling 框架（采样流量双跑对比）
4. Crashlytics native-panic 专属 tagging
5. CI 断言（cargo-ndk 真的产出 .so，不是 silently skip）
6. **ADR-0004《HARD GATE 模板》**—— 后续所有 native crate 上线复用
7. 100% prod rollout 达成且至少 7 天无 regress

### HARD GATE 5 件套（来自 PR #9 §8.3）

每个 native crate 上线 production-caller 前必须 ship：

1. **Feature flag / kill switch**：默认 JVM，远端可推关
2. **Gradual rollout cohorts**：staging 100% → prod 1% → 10% → 50% → 100%
3. **Crashlytics tagging**：`Native.<crate>.<error_kind>` 专门 tag
4. **JVM-vs-Rust divergence sampling**：选定 cohort 双跑，输出对比
5. **Single-step revert plan**：通过 merge commit 一键回滚

### Tasks

#### TA5.1 — Feature flag 基础设施（v1.2 拆分）

> v1.1 估 1 天，但"远端可推"假设了 Remote Config 或自建后端已就绪。如果没有，TA5.1 整个塌掉，Phase A.5 HARD GATE 第一条（kill switch）就是纸面。v1.2 显式拆分。

##### TA5.1a — 本地 DataStore flag + dev settings 屏（0.5 天）

```kotlin
object NativeAccelFlags {
    val useNativeMarkdown: StateFlow<Boolean>   // DataStore-backed
    val useNativeRegex: StateFlow<Boolean>
    val markdownDivergenceSampleRate: Float     // 0.0-1.0
    val regexDivergenceSampleRate: Float
}
```

DataStore key 进入 §3 不变性清单（永久保留 key）。Phase A.5 在远端通道未就绪时，**只能按发版灰度**（install_time hash + 版本号 cohort），不能依赖动态 kill switch。

##### TA5.1b — 远端通道（2-4 天，独立决策）

候选：
- Firebase Remote Config（最快，但要接 Firebase SDK 全家桶）
- 自建端点（OkHttp + 简单 JSON + 缓存策略，更可控）
- 顺带做 backend：开 plan §9 Q12

**这个决策不阻塞 TA5.1a**——TA5.1a 完成即可继续 TA5.2。TA5.1b 在 TA5.6 灰度阶段前必须就位（要远端能 kill switch），否则 rollout 改成"发版灰度"模式，每 cohort 一个 release。

**ADR-0005《远端 Flag 通道选型》**：见 §10/§12 Q12。

**验收**（TA5.1a）：本地切 flag → 两套实现都跑通；dev settings 屏可见 flag 当前状态。
**验收**（TA5.1b）：远端推送可即时关闭单个 native crate，无需发版。

**Effort**：TA5.1a 0.5 天 + TA5.1b 2-4 天（取决于选型）

#### TA5.2 — Markdown 调用方加 dispatch

定位现有调用：`app/.../richtext/Markdown.kt`、`app/.../richtext/MarkdownNew.kt`、`MessageContent.kt` 等所有渲染入口。

```kotlin
fun parseMarkdown(text: String): MarkdownTree =
    if (NativeAccelFlags.useNativeMarkdown.value) {
        runCatching { MarkdownParserNative.parse(text) }
            .getOrElse {
                Crashlytics.recordNativeFailure("markdown", it)
                LegacyMarkdownParser.parse(text)
            }
    } else {
        LegacyMarkdownParser.parse(text)
    }
```

**关键约束**：native 失败 **自动 fallback 到 JVM**，不让 native 故障变成用户可见崩溃。

**验收**：手工 flag 切换 → 两套实现都跑通；故意触发 native panic → fallback 生效 + Crashlytics 上报。

**Effort**：1-2 天

#### TA5.3 — Divergence sampling 框架（v1.2 含 NPF）

##### TA5.3a — Normalized Presentation Form (NPF) 设计（2-3 天）★ v1.2 新增

> v1.1 写"compare: markdown 比 PackedAstNode 树结构 + 文本内容"。**这在源头上不可行**：pulldown-cmark 和 JVM 端（intellij-markdown / commonmark-java）对同一段 markdown 产出的 AST 结构**根本不同形**——粗体 `**x**` 一边是 `Strong(Text)`，另一边是 `StrongOpen + Text + StrongClose` 三 token。直接比 AST → divergence rate 100%，HARD GATE 第一关卡死。

定义 markdown 的 **Normalized Presentation Form (NPF)**：

```kotlin
sealed interface NpfNode {
    data class Text(val content: String, val styles: Set<InlineStyle>) : NpfNode  // bold/italic/code/strike
    data class Link(val href: String, val title: String?, val children: List<NpfNode>) : NpfNode
    data class Image(val src: String, val alt: String) : NpfNode
    data class CodeBlock(val lang: String?, val content: String) : NpfNode
    data class Heading(val level: Int, val children: List<NpfNode>) : NpfNode
    data class List(val ordered: Boolean, val items: List<List<NpfNode>>) : NpfNode
    data class Quote(val children: List<NpfNode>) : NpfNode
    data class Table(val headers: List<List<NpfNode>>, val rows: List<List<List<NpfNode>>>) : NpfNode
    data class HorizontalRule(val width: Int = 1) : NpfNode
}

fun normalize(pulldownAst: PackedAstNode): List<NpfNode>     // Rust 侧也可写
fun normalize(jvmAst: JvmMarkdownNode): List<NpfNode>
```

**比较规则**：相邻 inline Text 合并；空 Text 删除；whitespace 折叠为单空格；List items trim。

**验收**：30 篇代表性 markdown（中文 / 英文 / 含表格 / 含代码块 / 含图片 / 含嵌套引用），两侧 NPF 相等的样本 ≥ 95%。

##### TA5.3b — Sampler 框架（2 天）

```kotlin
class DivergenceSampler(
    private val sampleRate: () -> Float,
    private val analytics: AnalyticsBus,
) {
    inline fun <T, N> sample(
        operation: String,
        nativeResult: () -> T,
        jvmResult: () -> T,
        normalize: (T) -> N,                 // ★ 走 NPF 归一化
        compareNormalized: (N, N) -> Divergence,
    ): T {
        val native = nativeResult()
        if (Random.nextFloat() < sampleRate()) {
            val jvm = runCatching { jvmResult() }.getOrNull()
            jvm?.let {
                val d = compareNormalized(normalize(native), normalize(it))
                if (d.differs) analytics.log("divergence/$operation", d.toMap())
            }
        }
        return native
    }
}
```

`normalize` for markdown 走 TA5.3a 的 NPF；for regex 直接比输出字符串等同性（regex 输出本就规范化）。

**验收**：staging 跑 1 天，分析后台 divergence rate < 0.01%（基于 NPF）。

**Effort**：TA5.3a 2-3 天 + TA5.3b 2 天 = 4-5 天

#### TA5.4 — Crashlytics native panic tagging

Rust 端 `catch_unwind` 包住所有 JNI 入口，panic 信息序列化回 Kotlin。Kotlin 端按 `Native.markdown.panic` / `Native.markdown.invalid_input` 等粒度上报。

**验收**：故意触发 Rust panic（feature flag 隐藏的 debug menu），Crashlytics 收到 tagged event 且 stack trace 完整。

**Effort**：1 天

#### TA5.5 — CI 断言 .so 真存在（Codex Round 3 follow-up）

`.github/workflows/native-build-check.yml`：

```yaml
- name: Assert native .so artifacts exist
  run: |
    ./gradlew :app:assembleDebug
    for lib in libmarkdown_parser libregex_transformer; do
      find app/build -name "${lib}.so" -path "*/jniLibs/*" | grep -q . \
        || { echo "::error::${lib}.so missing from APK"; exit 1; }
    done
```

**验收**：故意删一个 cargo target → CI fail；正常 build → CI pass。

**Effort**：0.5 天

#### TA5.6 — Gradual rollout (markdown)

- Day 0: staging 100%（飞行员 + 内部用户）
- Day 3: prod 1% cohort
- Day 6: prod 10%（若 1% 阶段无 regress）
- Day 10: prod 50%
- Day 14: prod 100%

每个 stage 监控：crash rate / divergence rate / 首屏渲染时间 P50/P95。出问题立刻远端推 flag 关闭。

**验收**：100% 流量稳定运行至少 7 天，divergence < 0.01%，无 native crash 增量。

**Effort**：跨 2-3 周自然时间，主动工作量约 2 天（监控 + 决策）

#### TA5.7 — Regex 接线（复用模板）

TA5.2-TA5.6 流程对 regex 重跑一遍。**这一遍跑下来 ADR-0004 模板成型**。

**Effort**：2-3 天（复用 TA5.3-TA5.5 基础设施）

#### TA5.8 — ADR-0004《HARD GATE 模板》

把 5 件套写成正式 ADR，附 markdown / regex 两次实战经验、踩到的坑、推荐时长。后续所有 native crate 直接引用此 ADR 即可，不重复论证。

**Effort**：1 天

### 不接的 2 个

| Crate | 为什么不接 |
|---|---|
| `highlight-parser` | 当前 syntect/Kotlin highlighter 无性能痛点；接线收益小，留到"反正要改 highlight"时一起做 |
| `office-parsers` | docx/pptx/epub 不在主链路热点；优先级低，挪到 Phase D 机会主义 |

### Exit criteria

- markdown + regex 两个 crate 在 100% prod 流量上稳定运行 ≥ 7 天
- divergence rate < 0.01%（采样数据可查）
- Crashlytics 无 native panic 数量上升
- ADR-0004《HARD GATE 模板》落档
- **用户首次真实体验到 Rust 加速**（典型场景：DeepRead 中长文章渲染、长聊天历史 regex 替换）

### Risks

- **Risk**: native panic 在生产环境出现非预期错误模式
  **Mitigation**: fallback 到 JVM + Crashlytics tag + 远端 kill switch，三道保险
- **Risk**: divergence rate 高于 0.01%（native 跟 JVM 输出不一致）
  **Mitigation**: 暂停 rollout，分析具体差异样本，决定是 native 修 bug 还是 JVM 行为本就有问题
- **Risk**: cargo-ndk 在某些开发机环境编不出 .so，CI assertion 频繁失败
  **Mitigation**: TA5.5 加完后先在 fork 上验证 1 周再合并；提供 dockerized build 环境兜底

### Rollback

每一步都可独立回滚：
- **Flag 关闭**：远端推 → 几分钟内 100% 流量回 JVM
- **TA5.2 dispatch 代码回滚**：单 commit revert
- **CI assertion 移除**：单 commit revert
- **完全回到 Phase A 状态**：revert 整个 Phase A.5 合并即可

---

## 5. Phase B — DeepRead 作为第一个 Kernel Agent + Reader Extractor（3-4 周）

### Why

证明 Kernel 合同能跑通真实 agent；同时把 DeepRead 这个产品标志性 feature 从"LLM + 正则剥 HTML"升级成真数据管线。**单一最高价值的 Rust 投资点**。

### Deliverables

1. `:core:native:reader-extractor` Rust crate
2. `:feature:deepread:api` + `:feature:deepread:impl` 模块（首个 feature 模块样板）
3. `DeepReadAgent` 实现 `Agent<DeepReadInput, DeepReadArtifact>`
4. DeepRead UI 通过 `runner.observe(runId)` 订阅事件流
5. 进程被杀重启后 UI 状态恢复（**不要求 run 自动继续执行**）

### Tasks

#### TB.1 — `:core:native:reader-extractor` Rust crate

```
core/native/reader-extractor/
├── Cargo.toml
├── reader_extractor.udl
└── src/
    ├── lib.rs
    ├── readability.rs       # 用 dom_smoothie 或 readability crate
    ├── section_split.rs     # 按 h1/h2/h3 切段
    ├── metadata.rs          # OpenGraph + JSON-LD + meta tags
    └── reading_time.rs      # word count → 分钟估算（中英文权重不同）
```

`reader_extractor.udl`：

```idl
namespace reader_extractor {
    [Throws=ExtractError]
    Article extract(string html, string base_url, ExtractOptions options);
};

dictionary Article {
    string? title;
    string? byline;
    string? site_name;
    string content_html;        // 清洗后的可读 HTML
    string content_text;        // 纯文本版本
    sequence<Section> sections;
    Metadata metadata;
    u32 reading_time_seconds;
    u32 word_count;
};

dictionary Section {
    u32 level;                  // h1=1, h2=2, h3=3
    string heading;
    string content_html;
    string content_text;
    u32 word_count;
};

dictionary Metadata {
    string? lang;
    string? author;
    string? published_date;
    string? og_image_url;
    record<string, string> extra;
};

dictionary ExtractOptions {
    boolean preserve_images;
    boolean preserve_links;
    string? language_hint;      // "zh" / "en" / null
};
```

**关键设计**：
- HTTP fetch **不**在 Rust 这边做，OkHttp 在 Kotlin 拿到 HTML 后再传 Rust（保留 cookie / proxy / 重试 / SSO 能力在熟悉的栈里）
- 替换 `DeepReadAgent.kt:551` 的 `fetchReadableText()` 和 `DeepReadAgent.kt:869` 的正则后处理
- **Section 切分是 DeepRead 流式输出的基础**：Phase B 的 DeepReadAgent 按 section 依次喂 LLM，UI 流式渲染

**验收**：
- 30 篇代表性文章测试集（中文知乎/微信、英文 Medium/blog/news、技术文档、PDF link 页等）
- 抽取准确率 vs 当前 Jsoup 实现 +30% 以上（人工评估）
- 处理时延：100KB HTML < 50ms

**Effort**：6-8 天

#### TB.2 — 新建 `:feature:deepread:api` + `:feature:deepread:impl`

```
feature/deepread/
├── api/
│   ├── build.gradle.kts
│   └── src/main/java/app/amber/feature/deepread/api/
│       ├── DeepReadInput.kt          # @Serializable
│       ├── DeepReadArtifact.kt
│       ├── DeepReadEventPayload.kt   # sealed: SectionStarted, SectionCompleted, etc.
│       └── DeepReadDescriptor.kt
└── impl/
    ├── build.gradle.kts
    └── src/main/java/app/amber/feature/deepread/impl/
        ├── DeepReadAgent.kt          # implements Agent<DeepReadInput, DeepReadArtifact>
        ├── DeepReadPipeline.kt
        ├── DeepReadModule.kt         # Koin module
        └── nativebridge/
            └── ReaderExtractor.kt    # UniFFI 生成的 binding 包装器
```

**`:api` 只导出**：input/artifact/event payload data classes + descriptor + 必要的常量。**零实现**。

**`:impl` 私有**：DeepReadAgent + pipeline + extractor 调用 + LLM 调用编排。

**`:app` 只依赖 `:api`**，运行时通过 Koin 注入 `:impl`。

**Build 规则**（用 detekt + Gradle test）：`./gradlew :feature:deepread:api:dependencies | grep impl` 必须为空（API 模块不能依赖 impl）。

**Effort**：3 天

#### TB.3 — DeepReadAgent 实现

```kotlin
class DeepReadAgent(
    private val httpClient: OkHttpClient,
    private val extractor: ReaderExtractor,    // UniFFI binding
    private val modelRouter: ModelRouter,
) : Agent<DeepReadInput, DeepReadArtifact> {

    override val descriptor = DeepReadDescriptor.value

    override suspend fun run(
        input: DeepReadInput,
        scope: RunScope,
    ): DeepReadArtifact {
        // 1. fetch
        val html = fetchHtml(input.url, scope)
        scope.events.write(DeepReadEventPayload.Fetched(input.url, html.length))

        // 2. extract (Rust)
        val article = extractor.extract(html, input.url, defaultOptions())
        scope.events.write(DeepReadEventPayload.Extracted(article.toEventDigest()))

        // 3. 按 section 流式分析
        val sectionAnalyses = article.sections.map { section ->
            scope.events.write(DeepReadEventPayload.SectionStarted(section.heading))
            val analysis = analyzeSection(section, scope.llm, scope)
            scope.events.write(DeepReadEventPayload.SectionCompleted(section.heading, analysis))
            analysis
        }

        // 4. 总结
        val summary = synthesizeSummary(article, sectionAnalyses, scope.llm, scope)
        scope.events.write(DeepReadEventPayload.SummaryCompleted(summary))

        return DeepReadArtifact(article, sectionAnalyses, summary)
    }
}
```

**关键设计**（v1.2 更新）：
- 每个有意义的子步骤用 `scope.events.commit(Final...)` —— UI 通过 observe 实时渲染
- LLM 流式 chunk 用 `scope.events.emit(Transient...)` —— 不入 Room，只走 SharedFlow
- LLM 完成一个 section 后用 `scope.events.commit(SectionCompleted...)` Final 入库
- 进程被杀 → 当前 run 在 store 里 status=running，重启后 `listUnfinishedRuns()` 找到它，**先尝试 projector 从 Final events replay 出 partial artifact**，replay 失败才标记 `interrupted` 让用户重跑（详见 Risk 2 缓解）
- input 含 attachment URI 的：launch 前由 wrapper 复制到 internal storage（`agent_runtime_snapshots/<runId>/attachments/`），URI 替换为 local path，`inputSnapshotRef` 指向归一化 JSON

**验收**：
- DeepRead 一篇文章全流程跑通，Final events 持久化到 `agent_event` 表，Transient chunk 不入库（断言：表行数 < N×3 其中 N 是 section 数）
- 杀进程重启 → UI 自动恢复看到之前的进度（"中断于 section 3 / 5"）
- 取消 run → 协程取消（`scope.coroutineContext.cancel()`）触发，section 分析协程被打断
- ★ projector replay property test：100 次随机生成的 Final event 流，`project(events) == project(events ++ events)` 恒成立
- 用 fake `RunScope` 写单测，验证 agent 逻辑跟 RunScope 实现解耦

**Effort**：8-10 天

#### TB.4 — DeepReadSurface 改造

`DeepReadScreen.kt` 当前 1844 行，**不要求一次拆完**。本步骤只做：

1. 引入 `DeepReadSurfaceViewModel`（或 Circuit Presenter，看 Phase F 决策），输入是 `runId: AgentRunId`，输出是 `StateFlow<DeepReadUiState>`
2. ViewModel 内部 `runner.observe(runId).map { snapshot -> snapshot.events.foldToUiState() }`
3. 用户点"开始 DeepRead"→ `runner.launch(DeepReadDescriptor.id, DeepReadInput(url))` 拿到 `runId`，进 Surface
4. **god class 拆分本身留给后续**，本 phase 不背

**验收**：
- DeepRead 流程不再调用 `DeepReadAgent` 的具体方法，只跟 `AgentRunner` 交互
- 杀进程 → 重启 → 进 DeepRead 历史 → 看到之前 run 的状态

**Effort**：4-5 天

#### TB.5 — ADR-0003《UniFFI vs 手写 JNI》

记录决策：
- 新 crate 默认 UniFFI
- 老 4 crate 等"反正要改"时迁
- JNI 符号锁死问题用 legacy 白名单防御
- 异步策略：短任务同步 + Kotlin 端调度；长任务 UniFFI async 但谨慎 cancellation

**Effort**：半天

### Exit criteria

- DeepRead **完全不经过 ChatService** 也能跑完（重要！这是"Chat 不再是宇宙中心"的第一个证据）
- `:feature:deepread:api` + `:impl` 物理分离，跨边界引用零违规
- AgentRuntimeDatabase 里能查到完整 DeepRead run 历史，可按 conversation_id 关联
- DeepReadAgent 单测覆盖率 > 70%（fake RunScope 喂数据）
- 用户感知：DeepRead 解析速度比 Phase 0 之前快至少 2x（Rust extractor 的功劳）

### Risks

- **Risk**: dom_smoothie / readability 对部分中文站点效果差
  **Mitigation**: 测试集中至少 50% 中文站点；不达标考虑切 `mozilla-readability` 的 C 绑定或自己写中文优化层
- **Risk**: section 切分粒度不合适（太细 LLM 调用太多 / 太粗丢上下文）
  **Mitigation**: section_split 算法支持参数化阈值；先按 h2 切，配置可调到 h1 或 h3
- **Risk**: UI 状态恢复跟当前 ViewModel 状态机冲突
  **Mitigation**: 优先保证"打开 DeepRead 页 → 看到正确状态"，过程中断的实时动画退化为"已中断"toast
- ★ **Risk (HIGH): 跨库事务恢复——run 完成但产物 message 没落 chat.db**（v1.2 新增）
  **场景**：ChatTurn 流式输出到一半 → AgentEvent 已 commit 80 条 Final → `conversationRepo.appendMessage(assistantMessage)` 还没跑完 → 进程被杀。重启后 agent_runtime.db 有 80 条事件，chat.db 没那条 assistant message。"用户重跑"会重复扣 token / 重复发模型请求。**Phase B DeepRead 幂等（重跑无副作用）所以问题轻；Phase C ChatTurn 不幂等，必须解决**。
  **Mitigation**:
  - **projector 是唯一写 chat.db 的路径**：从 AgentEvent.Final 流派生 `MessageNode` candidate，幂等 INSERT OR REPLACE
  - 启动时 `listUnfinishedRuns()` 不只 mark interrupted，**先尝试 projector 重放** → 重放成功就完成 run，失败才标 interrupted
  - 写 kotest property-based test 断言：`project(events) == project(events ++ events.tail())` 任意尾部重复幂等
  - 这是 Phase B 必须先把 projector 接口立起来的硬约束，Phase C TC.6 才能写 property test gating

### Rollback

- DeepRead surface 保留旧 entrypoint，feature flag 切回老 `DeepReadAgent.kt` 实现
- AgentRuntime DB 单独，删了不影响 chat 数据
- Rust extractor 故障可 fallback 回 Jsoup（保留 Kotlin 实现一段时间）

---

## 6. Phase C — ChatService 降级 + ChatTurnAgent（4-6 周）

### Why

`ChatService.kt` 2347 行是 rikkahub DNA 的最后据点。让 Chat 主链路也跑在 Kernel 上，ChatService 退化成 Android 前台服务壳。这一步做完，**事实上脱 fork**。

### Deliverables

1. `:feature:chat:api` + `:feature:chat:impl` 模块
2. `ChatTurnAgent` 实现 `Agent<ChatTurnInput, ChatTurnArtifact>`
3. ChatService 退化到 500-700 行（Codex 的现实数字）
4. `SendMessageOrchestrator` / `RegenerateMessageOrchestrator` 删除或并入 ChatTurnAgent
5. Conversation 数据模型在 `:feature:chat:api` 内重新组织（不动 Room schema）

### Tasks

#### TC.1 — 设计 ChatTurnInput / ChatTurnArtifact（v1.2 含 Assistant 映射）

```kotlin
@Serializable
data class ChatTurnInput(
    val conversationId: ConversationId,
    val messageNodeId: MessageNodeId,             // ★ v1.2：关联到 MessageNode
    val assistantId: AssistantId,                 // ★ v1.2：Assistant 配置注入
    val userMessage: UIMessage,
    val regenerateOf: UIMessageId?,               // ★ v1.2：非空 = regenerate 已有 candidate
    val toolChoice: ToolChoicePolicy,
    val modelOverride: ModelSelectionPolicy?,     // ★ v1.2：null 表示用 Assistant 默认
    val retryPolicy: RetryPolicy,
) : AgentInput

@Serializable
data class ChatTurnArtifact(
    val assistantMessageId: UIMessageId,
    val producedInNode: MessageNodeId,
    val finalUsage: TokenUsage,
    val toolCallsCount: Int,
    val toolsUsed: List<Pair<ToolId, ToolVersion>>,   // ★ v1.2：版本快照
) : AgentArtifact
```

**关键设计**（v1.2 锁定）：
- ChatTurnAgent 是**单一 Agent 实例**，不为每个 Assistant 注册新 Agent
- `assistantId` 在 handler 内部解析为：system prompt + 模型参数 + tool 启用集 + MessagePipeline 配置
- `regenerateOf` 非空时：在同 `messageNodeId` 内新增 candidate，不创建新 MessageNode
- `toolsUsed` 含版本：MCP server 升级 schema 后 replay 此 turn 能识别"工具版本变了，结果按只读处理"

**Effort**：2 天（接口设计 + Assistant 解析层）

#### TC.2 — ChatTurnAgent 实现（核心难点）

把当前 `ChatService.sendMessage()` + `SendMessageOrchestrator` 的核心逻辑搬进来（v1.2 修订伪代码）：

```kotlin
class ChatTurnAgent(
    private val conversationRepo: ConversationRepository,
    private val assistantRepo: AssistantRepository,           // ★ v1.2
    private val toolSessionFactory: ChatToolSessionFactory,
    private val pipelineFactory: MessagePipelineFactory,      // ★ v1.2
) : Agent<ChatTurnInput, ChatTurnArtifact> {

    override val descriptor = ChatTurnDescriptor.value

    override val handler = AgentHandler<ChatTurnInput, ChatTurnArtifact> { input, scope ->
        // 1. 解析 Assistant 配置
        val assistant = assistantRepo.require(input.assistantId)
        val pipeline = pipelineFactory.forAssistant(assistant)
        val pipelineCtx = PipelineCtx(input.assistantId, input.conversationId, defaultVariables())

        // 2. 加载对话上下文 + 应用 InputTransformer
        val rawHistory = conversationRepo.loadHistoryForNode(input.messageNodeId)
        val transformedHistory = pipeline.transformInput(rawHistory + input.userMessage, pipelineCtx)

        // 3. user message 通过 projector 入库（不直接写）
        scope.events.commit(ChatEventPayload.UserMessageAccepted(
            messageNodeId = input.messageNodeId,
            messageId = input.userMessage.id,
        ))

        // 4. 工具循环
        var iteration = 0
        var assistantMessage = UIMessage.assistantEmpty(
            id = UIMessageId.new(),
            inNode = input.messageNodeId,
        )
        val toolsUsed = mutableListOf<Pair<ToolId, ToolVersion>>()

        while (iteration < assistant.maxToolIterations) {
            scope.ensureActive()
            scope.tracing.span("model_turn", SpanKind.MODEL_TURN) {
                val turn = ChatTurn(transformedHistory + assistantMessage, scope.tools.listAvailable())
                val outputStream = pipeline.transformOutput(scope.llm.streamTurn(turn), pipelineCtx)

                outputStream.collect { event ->
                    when (event) {
                        is TurnEvent.TextChunk -> {
                            assistantMessage = assistantMessage.appendText(event.delta)
                            // ★ Transient：仅 SharedFlow，不入库
                            scope.events.emit(ChatEventPayload.AssistantTextDelta(
                                assistantMessage.id, event.delta,
                            ))
                        }
                        is TurnEvent.ToolCall -> {
                            val descriptor = scope.tools.listAvailable().first { it.id == event.toolId }
                            val result = scope.tools.invoke(event.toolId, event.args)
                            toolsUsed += descriptor.id to descriptor.version
                            // ★ Final：工具结果是用户叙事事实
                            if (descriptor.isStable) {
                                scope.events.commit(ChatEventPayload.ToolInvoked(
                                    descriptor.id, descriptor.version, result.preview,
                                ))
                            }
                            assistantMessage = assistantMessage.appendToolResult(result)
                        }
                        is TurnEvent.Done -> Unit
                    }
                }
            }
            if (!assistantMessage.hasPendingToolCalls()) break
            iteration++
        }

        // 5. Final commit：projector 看到这条会写 chat.db
        scope.events.commit(ChatEventPayload.AssistantMessageFinalized(
            messageNodeId = input.messageNodeId,
            messageId = assistantMessage.id,
            content = assistantMessage.parts,
            usage = assistantMessage.usage,
            regenerateOf = input.regenerateOf,
        ))
        scope.events.flush()

        ChatTurnArtifact(
            assistantMessageId = assistantMessage.id,
            producedInNode = input.messageNodeId,
            finalUsage = assistantMessage.usage ?: TokenUsage.zero(),
            toolCallsCount = toolsUsed.size,
            toolsUsed = toolsUsed,
        )
    }
}
```

**关键设计**（v1.2 锁定）：
- ChatTurnAgent 不知道前台服务、不知道 notification、不知道 ChatService 任何细节
- 通过 `scope.tools` 访问工具（Phase A 已经把 dispatcher 抽到 RunScope）
- 通过 `scope.llm.streamTurn` 调 LLM（不再直接持有 OkHttp Provider）
- ★ **流式 chunk 走 `events.emit(Transient)` 不落库**
- ★ **AssistantMessageFinalized 是唯一 Final commit，projector 监听此 event 写 chat.db**
- ★ **MessagePipeline 是一等公民**，input/output transformer 全程不写在 ChatTurnAgent 内部
- ★ **conversationRepo 不在 ChatTurnAgent 直接写**——所有 chat.db 写入由 projector 派生自 AgentEvent.Final

**验收**：
- 现有所有聊天场景（普通对话、工具调用、流式取消、重试、模型切换、regenerate、Assistant 切换）行为完全一致
- ChatTurnAgent 单测能在 in-memory store + fake LlmSession 下跑（不需要 Android emulator）
- ★ projector property test：随机 Final event 流 → project → MessageNode 与 chat.db 状态等价，重复 commit 同事件幂等

**Effort**：14-18 天（v1.2 含 MessagePipeline 接入 + projector 实现，比 v1.1 估的 12-15 天多 2-3 天）

#### TC.3 — ChatService 退化

ChatService 保留这些职责：
- Android 前台服务生命周期
- Notification 显示和更新
- 接收来自 UI 的"发送消息"intent → 调 `runner.launch(ChatTurnDescriptor.id, input)` 拿 runId → 返回给 UI
- 观察 unfinished runs，应用启动时显示"X 个对话正在生成"

删除：
- `sendMessage()` 内部的具体编排
- `SendMessageOrchestrator` / `RegenerateMessageOrchestrator`
- 直接持有的 `Provider` / `ToolDispatcher` / `SpeculativeToolRunner`

**验收**（v1.2 去硬指标，改成行为性断言）：
- ★ ChatService 只 import `app.amber.core.agent.runtime.*`、`androidx.*`、Android framework 包；不 import `Provider` / `AgentToolDispatcher` / `SpeculativeToolRunner` / `PermissionDecisionResolver`
- ★ ChatService 内 `suspend fun` 仅含 lifecycle 处理与 intent dispatch，不含业务编排（grep `streamTurn` / `dispatchTool` / `appendMessage` 为空）
- ★ 所有 `data/agent/runtime/*` 不再被 ChatService 直接 import
- 行数 `wc -l` 仅作参考 sanity check（不作 gate，避免拆类凑数）

**Effort**：5-7 天

#### TC.4 — ChatPage UI 改造

类似 Phase B 的 DeepReadSurface：
- `ChatPageViewModel` 观察 `runner.observe(runId)` + `conversationRepo.observeMessages(conversationId)`
- 不再直接订阅 ChatService 的内部 StateFlow
- god class 拆分**不在本步骤背**

**Effort**：4-6 天

#### TC.5 — schema-utils Rust crate（可选，按 ToolSession 复杂度决定）

如果 Phase C 过程中 ToolSession 的 InputSchema 真的变复杂了（出现 nested object / oneOf / refs），引入 `:core:native:schema-utils`。否则**推迟到 Phase D**。

**Effort**：3-4 天（如果做）

#### TC.6 — Projector property-based test（★ v1.2 新增，Phase C 合并 gating）

`AgentEventProjector` 是把 `AgentEvent.Final` 流投影到 `MessageNode` / `Conversation` 状态的纯函数。这是 §5 跨库事务恢复 Risk 的核心防线。

用 kotest property testing 写：

```kotlin
class AgentEventProjectorTest : StringSpec({
    "projector 是确定性的" {
        checkAll(eventStreamGen) { events ->
            project(events) shouldBe project(events)
        }
    }
    "尾部重复幂等" {
        checkAll(eventStreamGen, Arb.int(0..10)) { events, n ->
            project(events) shouldBe project(events + events.takeLast(n))
        }
    }
    "尾部截断不破坏前缀语义" {
        checkAll(eventStreamGen) { events ->
            val prefix = events.dropLast(1)
            project(prefix).messageNodes.values.all { node ->
                node.candidates.all { it.id in project(events).knownMessageIds }
            }
        }
    }
    "regenerate 不丢候选" {
        checkAll(regenerateScenarioGen) { (originalEvents, regenEvents) ->
            val full = originalEvents + regenEvents
            project(full).messageNodes[regenEvents.messageNodeId]!!.candidates.size shouldBe 2
        }
    }
})
```

`eventStreamGen` 是 `Arb<List<AgentEventPayload.Final>>` 的合法事件流生成器（含 UserAccepted → 多个 ToolInvoked → AssistantFinalized 的合法序列）。

**这是 Phase C 合并主分支的 hard gate**：上述 4 个 property 任何一条不过，TC.2 / TC.4 都不能合并。

**Effort**：2-3 天

### Exit criteria

- 聊天功能全场景不退化（手工冒烟 + Espresso 测试覆盖关键路径）
- ChatService.kt < 700 行
- `:feature:chat:api`/`:impl` 物理分离
- 杀进程重启后：未完成的 turn 显示 "interrupted"，用户可一键重试
- ChatPage 不再 import 任何 `data/agent/runtime/` 类

### Risks

- **Risk**: 多 turn 工具循环的状态管理跟原 ChatService 实现差异导致细微 bug
  **Mitigation**: Phase C 全程开 feature flag，新老两套实现并存 2-3 周，灰度切换
- **Risk**: 通知和前台服务行为变化（电池优化、process death）
  **Mitigation**: 真机测试矩阵：MIUI / EMUI / OneUI / 原生 Pixel；记录所有发现的厂商行为差异
- **Risk**: ChatTurnAgent 单测覆盖不到的 Android 行为（context handle / lifecycle）
  **Mitigation**: 在 ChatService 壳层加 instrumentation 测试

### Rollback

Feature flag：
```kotlin
if (FeatureFlags.useChatKernel) {
    runner.launch(ChatTurnDescriptor.id, input)
} else {
    legacyChatService.sendMessage(input)  // 老代码保留 1-2 个版本
}
```

---

## 7. Phase D — Feature 模块拆分 + build-logic + 剩余 Rust（2-4 个月，滚动）

### Why

把剩下 17 个 `data/agent/*` 子系统都按 `:feature:*` 模式迁出去；把 build.gradle.kts 重复代码收敛到 convention plugins；按需引入剩余的 Rust crate。

### 关键原则

**不专门停下来做 D，每次做新需求时顺手迁一个**。优先级按"PR 冲突率 + 改动频率"排：

#### ★ v1.2 Escape Hatch（避免长尾 module 永远不动）

机会主义节奏的隐藏风险：
- 半年没新需求 → 长尾 module 永远不动
- 新需求 80% 落在已迁完的 Chat / DeepRead → `:feature:terminal` / `:feature:office` 5 年不会被碰
- Phase E 的"理想 1 行 legacy 白名单"永远达不到

**应对**：每季度末做一次 Phase D 进度 review：

| 触发条件 | 动作 |
|---|---|
| 季度内迁出 0 个 feature module | 下一季度预算 1 个集中迁移 sprint（不背 KPI，但解除阻塞） |
| 季度末 `data/agent/` 子目录数下降 0 | 同上 |
| 6 个月内 Phase D 进度未过 50% | 触发 plan v1.3 review，重评估方案而不是硬推 |
| 任一长尾 module 阻塞跨切关注点（observability / sync） | 提前迁移该 module，不等机会 |

这是 ADR-0007《Phase D 滚动迁移的 escape hatch》要落档的内容。

**关键：进度阈值不是 KPI**——它是触发讨论的 signal，不是绩效指标。


```
1. :feature:board        ← 改动最频繁，先迁
2. :feature:miniapp       ← 复杂度中等，独立性好
3. :feature:webmount      ← 顺便做进程隔离（看 Phase F）
4. :feature:modelcouncil
5. :feature:subagent
6. :feature:workspace
7. :feature:terminal
8. :feature:office
9. :feature:memory        ← 这步带 :core:native:vector-index
10. :feature:icloud / :feature:sync
11. :feature:settings     ← 最后，牵全局 prefs
```

### Deliverables（滚动）

每个 feature 迁完交付：
- `:feature:<name>:api` + `:feature:<name>:impl` 模块
- 用 `amber.android.feature` convention plugin 配置 build.gradle.kts
- 实现 `Agent<...>` 接口（如适用）
- 单测覆盖率 ≥ 60%
- 在 `docs/feature-modules.md` 加一条迁移记录

### 横向 task

#### TD.X.1 — `build-logic/convention/` 约定插件

```
build-logic/
└── convention/
    ├── build.gradle.kts
    └── src/main/kotlin/
        ├── AmberKotlinLibraryConventionPlugin.kt       # 纯 Kotlin
        ├── AmberAndroidLibraryConventionPlugin.kt      # Android library 基础
        ├── AmberAndroidComposeConventionPlugin.kt      # + Compose
        ├── AmberAndroidFeatureConventionPlugin.kt      # 上面 + Koin + 测试栈
        ├── AmberNativeRustConventionPlugin.kt          # cargo-ndk + UniFFI
        └── AmberDetektConventionPlugin.kt              # detekt + LegacyPackageMustNotGrow
```

**验收**：随便挑一个 feature module，`build.gradle.kts` 缩到 < 15 行。

**Effort**：3-5 天（一次性投入）

#### TD.X.2 — 剩余 Rust crate（按需）

| Crate | 触发条件 |
|---|---|
| `:core:native:schema-utils` | ToolSession 出现 nested schema / oneOf / refs |
| `:core:native:imaging` | 多模态消息体积 / OOM 问题 profile 出来 |
| `:core:native:vector-index` | Memory 模块决定走本地 embedding 索引 |
| `:core:native:fetcher` | Board 信号采集出现"用 WebView 抓 RSS 太重"的痛点 |
| `:core:native:compress` | iCloud/Drive sync 数据量上来 |
| `:core:native:diff` | sync 模块要做真冲突合并 |

每个 crate 进来都用 UniFFI + amber.native.rust convention plugin，模板就是 Phase A 的 tokenizer。

#### TD.X.3 — 老 4 个 native bridge 的 UniFFI 迁移（机会主义）

每次因为其它原因要改 `MarkdownParserNative` / `RegexTransformerNative` / `HighlighterNative` / `OfficeParserNative` 时，**顺手**迁到 UniFFI。一旦迁完，从 legacy 白名单移除对应条目。

**预期**：2-3 个月内 4 个 crate 全部迁完。

### Exit criteria（整个 Phase D）

- `app/src/main/java/.../data/agent/` 目录**消失**（最后一个子系统迁出）
- `:app` 模块代码量 < 5000 行（现在 100k+）
- `:app` 只剩：Activity + Application class + 主 DI 装配 + 主路由 + 主题 + Notification channel 配置
- 4 个老 native bridge 全部 UniFFI 化，legacy 白名单只剩 `AppDatabase.kt`

---

## 8. Phase E — Legacy 清理（1-2 周）

### Why

Phase D 结束后，绝大多数代码已经在 `app.amber.*` 包里。这一步收尾：把 legacy 包压缩到只剩"必须冻结"的最小集，让架构独立性"看起来"也独立。

### Tasks

#### TE.1 — 评估 legacy 白名单可以缩到什么程度

理想终态：
```
# legacy-package-allowlist.txt 最终内容（理想）
app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt
```

只剩 1 行：因为 Room schema 路径绑死。

如果 Phase D 内 4 个老 native crate 都已经 UniFFI 化，那么 `me.rerere.highlight.*` / `me.rerere.document.*` / `me.rerere.rikkahub.*.nativebridge.*` 都可以清理了。

#### TE.2 — 迁移 `:highlight` 和 `:document` 模块的 Kotlin 包

前提：Phase D 内 `HighlighterNative` 和 `OfficeParserNative` 已经走 UniFFI（JNI 符号不再绑 Java 包）。

```bash
# 重命名
:highlight  → app.amber.platform.highlight
:document   → app.amber.platform.document
```

**验收**：`./gradlew assembleDebug && ./gradlew test` 全绿

#### TE.3 — 整体文档与 README 改写

- README.md / README_ZH_CN.md / README_ZH_TW.md：明确写"AmberAgent 是 Agent 平台。原始 LLM/markdown/highlight 渲染基础设施 fork 自 rikkahub (Apache 2.0)，自 2026-05 起独立演化"
- LICENSE attribution：rikkahub 原作者署名保留
- `docs/adr/0001` 系列更新为最终决策
- `docs/architecture.md`：写一份现状架构图给新人

#### TE.4 — 心理仪式：宣布脱 fork

- 在项目内部更新 CLAUDE.md / AGENTS.md，把"AmberAgent 是 rikkahub fork"的措辞改成"AmberAgent 受 rikkahub 启发的独立 Agent 平台"
- 不动 `android:namespace`（继续 me.rerere.rikkahub），**不影响用户**，可在下一个大版本（Phase F 时机）一起改

### Exit criteria

- `git grep -l "me.rerere.rikkahub" -- "*.kt" | wc -l` ≤ 5（理想 = 1）
- README 更新完成
- ADR 系列归档

---

## 9. Phase F — 战略级（可选，独立决策）

按产品节奏决定做不做、何时做。

| 候选 | 价值 | 风险 | 推荐时机 |
|---|---|---|---|
| **DI 切换 Koin → Metro / Kotlin Inject** | 编译时安全；KMP-ready | 大重写，零产品价值 | 团队 ≥ 3 人后，bug 主要由 DI 错误引起时 |
| **KMP 化 `:core:agent-runtime` + `:amber-llm`** | 同代码跑 Desktop/iOS | 工具链复杂 | 真要做 Desktop/iOS 客户端时 |
| **WebMount 独立进程** | 崩溃隔离 / 内存隔离 | IPC 复杂度 | 出现 WebView 拖垮主进程的真实事故 |
| **god class 二次拆解**（ChatPage 1371 / DeepReadScreen 1844 / Markdown 1641） | 维护性 | 工作量大无亮点 | 这些屏出现 PR 冲突高峰时 |
| **on-device 小模型** | 产品差异化 | 体积 / 维护 / 模型迭代 | 想做"离线 agent"卖点时 |
| **真 Run 恢复执行**（不是 just 标记 interrupted） | UX 提升 | 需要每个 agent 设计 checkpoint | 用户反馈"中断后重做太烦"时 |
| **WASM 工具沙箱** | 第三方工具生态 | 安全 / 性能 / 复杂度 | 决定开 plugin marketplace 时 |

---

## 10. 横切关注点

### 10.1 测试策略（v1.2 扩充）

| 层 | 测试类型 | 跑在哪 |
|---|---|---|
| `:core:agent-runtime` 合同 | JVM unit | JVM (gradle test) |
| `:core:agent-store-room` | in-memory Room test | JVM |
| `:core:native:*` Rust | cargo test + UniFFI JVM binding test | JVM + native |
| `:feature:*:impl` | unit + fake RunScope | JVM |
| `:feature:*:impl` 集成 | Robolectric 或 Espresso | Android emulator |
| `:feature:chat:impl` projector | ★ kotest property-based（Phase C gating） | JVM |
| `:app` | 仅冒烟 / Espresso | 真机 + emulator |
| ★ Process death scenario | Espresso instrumentation + `adb shell am force-stop` | Firebase Test Lab / 自建 emulator farm |

**关键投资**：
- fake `RunScope` 实现放进 `:core:agent-runtime-testing` 模块，每个 feature 复用。
- ★ **fake `LlmSession` 能力清单**（v1.2 新增）：预定义 chunk 序列；注入中间被 cancel；注入 tool call 错误；注入模型超时；注入 SSE 断流；注入空响应。所有 fake 都跑 deterministic。
- ★ **Process death harness**：`adb shell am force-stop` + 重启 + 状态恢复断言。CI 在 Firebase Test Lab 上跑 Phase B/C/D 关键场景。没这个 harness 所有"resume" 验收都是 hope-driven。

### 10.2 Build pipeline

- Phase 0：detekt + legacy guard CI
- Phase D：build-logic convention plugins
- 长期：考虑 Gradle build cache（remote）+ configuration cache

### 10.3 Observability

- AgentEventStore 本身就是审计日志，无需额外
- 加 `:core:observability` 模块：crash report (Crashlytics?) + 性能 trace（每个 agent run 的 duration / token usage / tool calls）
- 用户可见的"agent 历史"页面（基于 agent_run 表）

### 10.4 发版策略（v1.2 扩充）

- Phase A / B / C 期间：每个 phase 至少 2 个发版灰度
- ChatTurnAgent feature flag 至少跑 4 周再删老代码
- 重大 schema 变更（agent_run/event）有 migration 测试

#### ★ v1.2 跨库版本联动表

`AppDatabase` 和 `AgentRuntimeDatabase` 各自独立，但版本互相**只能向后兼容 2 个版本**：

| AppDatabase.VERSION | AgentRuntimeDatabase.VERSION | 兼容性 |
|---|---|---|
| N | M | 必须满足 \|N-M\| ≤ 2 |

CI 跑 N×M 矩阵测试（每次任一 DB 升版都自动展开）：
1. 旧 N + 旧 M：升级到 (N, M) → assert 数据完整
2. 旧 N + 新 M：升级到 (N, M) → assert 数据完整
3. 新 N + 旧 M：升级到 (N, M) → assert 数据完整

#### ★ v1.2 备份 / 恢复 sanitizer

`agent_runtime.db` **必须进备份**（否则 "我之前做过的 DeepRead 怎么没了" 的反馈不可避免）。但要走 sanitizer：

**导出阶段 `AgentRuntimeSanitizer`**：
- `status IN ('running', 'awaiting_permission')` → 标 `interrupted` + 写 `interruptedReason='backup_during_run'` 再导出
- `permission_intent.payload_digest` 已经是 hash，正常导出
- `inputSnapshotRef` 指向的 attachment 文件按用户隐私设置选择性导出（敏感 attachment 可选择脱敏）
- Transient events 不在表里，不需要处理
- ★ 敏感 PermissionIntent decisions：用 KeyStore 派生密钥加密 at rest（EncryptedRoomBuild + MasterKey），备份导出的是密文，恢复时同一设备/同一账号才能解密

**恢复阶段 `AgentRuntimeRehydrator`**：
- 跨设备恢复时，重新关联 `conversation_id`：若用户在新设备删了对应 conversation → 该 run 标 `orphan`，UI 折叠隐藏（仍可在 dev settings 查到）
- attachment 路径回写本设备 internal storage（按 sha256 验证）

**版本演化**：
- 任何修改 `agent_runtime.db` schema 的 PR 必须附 sanitizer migration test
- 备份 zip 内含 `agent_runtime_schema_version` 字段，恢复时识别并走对应 migrator

---

## 11. 回滚策略汇总

| Phase | 回滚成本 | 方式 |
|---|---|---|
| 0 | 0 | `git revert`，零运行时影响 |
| A | 低 | 新模块未被消费即可 revert；ChatService 行为不变 |
| B | 中 | feature flag 切回老 DeepRead；新 DB 表保留无害 |
| C | 高 | feature flag 切回老 ChatService（保留 2-3 版本） |
| D | 低 | 单 feature 出问题可单独 revert |
| E | 低 | 重命名可一键 revert |
| F | 视具体决策 | 每项独立 ADR + 独立回滚方案 |

---

## 12. 决策日志（开放问题）

| # | 问题 | 当前倾向 | 何时决定 |
|---|---|---|---|
| Q1 | UI 状态管理用 ViewModel 还是 Circuit Presenter | ViewModel（保守） | Phase B 结束后评估 |
| Q2 | DI 切不切 | 不切（Phase F 再说） | Phase D 中期 |
| Q3 | KMP 化范围 | 仅 `:core:agent-runtime` | Phase B 结束后 |
| Q4 | Memory 模块走本地 embedding 还是远端 | 待决 | Phase D 排期到 memory 时 |
| Q5 | 是否做 plugin marketplace（牵 WASM 沙箱） | 暂不 | Phase F |
| Q6 | `android:namespace` 何时改 | Phase E 或下个大版本一起 | 看 Play Store / 渠道发版策略 |
| Q7 | 是否引入 GitHub Actions matrix 测试多 Android API level | 推荐 | Phase 0 末尾 |
| Q8 | 老的 4 个 native crate（markdown/regex/highlight/office）什么时候全部接线 | markdown + regex 走 Phase A.5；highlight + office 推到 Phase D 机会主义 | Phase D 排期到时 |
| Q9 | HARD GATE rollout cohort 划分用什么维度（设备 / 用户 ID / 安装时间 / 随机） | 倾向"安装时间 hash + 设备型号白名单"组合 | Phase A.5 TA5.1 |
| Q10 | Phase D 滚动迁移如果 6 个月进度 < 50% 怎么办 | 触发 plan v1.3 review；季度 0 进度则下季度预算 1 个集中 sprint | 每季度末 review |
| Q11 | tokenizer 词表走 assets 还是 Play Asset Delivery | 默认 assets；当 APK base size 接近 150MB 时切 PAD | Phase A TA.3b 完成时评估 |
| Q12 | Phase A.5 远端 flag 通道选型（Firebase Remote Config / 自建端点 / 暂不做） | 倾向自建端点（更可控，不接 Firebase 全家桶） | TA5.1b（TA5.6 灰度阶段前必须就位） |
| Q13 | Surface 是 ViewModel 实现还是 Circuit Presenter 实现 | 跟 Q1 合并，但接口本身（Surface\<STATE, COMMAND\>）跟具体实现技术解耦 | Phase B 结束后 |

---

## 13. 时间线总览

```
Month 1  ─┬─ Phase 0    (2-4 天)    护栏
         │
         └─ Phase A     (3-4 周)    Kernel 合同 + UniFFI pioneer + tokenizer
                                     │  v1.2: TA.3 拆 TA.3a (10-14d pipeline) + TA.3b (3d 实现)
                                     │       ADR-0002 接口要点翻倍
Month 2  ─┬───────────────────────────┘
         │
         ├─ ★ Phase A.5 (2-3 周)    接线现有 markdown + regex
         │                          v1.2: TA5.1 拆本地+远端；TA5.3 加 NPF 设计
         │                          **用户首次体验到 Rust 加速**
         │                          HARD GATE 模板首跑 → ADR-0004
         │
         └─ Phase B     (3-4 周)    DeepRead + extractor（套用 A.5 模板）
                                     │  v1.2: projector 接口立起来
Month 3  ─────────────────────────────┤
         │                            │
         ├─ Phase C     (5-7 周)    ChatService 降级（最难）
                                     │  v1.2: +TC.6 projector property test gating
Month 4  ─────────────────────────────┤
         │                            │
         └─ ★ "事实上脱 fork" milestone ───
         │
Month 5-8 ──── Phase D   (滚动)     feature 模块拆分 + 老 JNI 迁 UniFFI
         │                          v1.2: 季度末 escape hatch review
         │
Month 9   ──── Phase E   (1-2 周)   Legacy 清理 + 文档
         │
         └──── Phase F   (永远进行时) 战略级
```

**关键里程碑**：
- **M0**: Phase 0 完成 — 护栏立起
- **M0.5**: Phase A.5 完成 — **用户首次体验 Rust 加速** + HARD GATE 模板成型（ADR-0004）
- **M1**: Phase B 完成 — DeepRead 跑在 Kernel 上 = 架构存活证明 + projector 接口落地
- **M2**: Phase C 完成 — Chat 也跑在 Kernel 上 = **事实上脱 fork** + projector property test 全绿
- **M3**: Phase D 50% — 一半 feature 拆出 + 4 个老 native crate 全 UniFFI 化
- **M4**: Phase E 完成 — Legacy 收尾

**v1.2 总周期影响**：Phase A +1 周（UniFFI pioneer 真实 effort）+ Phase A.5 +0.5 周（NPF 设计 + 远端通道）+ Phase C +1 周（projector + property test）= **整体 +2.5 周 ≈ 总周期 6.5-9.5 个月**，"事实上脱 fork" 约 4.5-6.5 个月。

---

## 14. 一句话总结

**Phase 0 立护栏，Phase A 立合同，Phase A.5 用户首次体验 Rust，Phase B 证合同，Phase C 翻 ChatService，Phase D 长尾拆分，Phase E 心理收尾，Phase F 看产品。**

三个关键时刻：
- **M0.5（Phase A.5 结束）**：用户终于能感受到 Rust 加速 —— 4 个孤立 crate 中的 2 个上线
- **M1（Phase B 结束）**：DeepRead 跑在 Kernel 上 + projector 接口落地 = 架构存活证明 + 跨库恢复防线立起
- **M2（Phase C 结束）**：架构上事实上脱 fork —— Chat 也跑在 Kernel 上，projector property test 全绿守门

剩下都是收尾和优化。

### v1.2 三条核心信念

1. **Assistant 是用户面对的概念，Agent 是工程能力，不要造第三个**——ChatTurnAgent 单例 + assistantId 注入是唯一正解
2. **事件流是契约，AgentEvent.Final 是事实源**——projector 是 chat.db 的唯一写入者，幂等可重放
3. **Agent vs Runner 拆得越狠越好**——Agent 没有 `run()` 方法，只有 `handler` 属性；唯一执行路径是 AgentRunner，detekt + ADR 双重守门

---

## 15. 修订历史

| 日期 | 修订 | 触发 |
|---|---|---|
| 2026-05-21 | v1 初稿 | Claude × Codex 五轮 review 收敛 |
| 2026-05-21 | v1.1 加 §2.5 Rust 现状审计 + §4.5 Phase A.5 + Q8/Q9 + 时间线插入 M0.5 | 发现 4 个 native crate 全部没接线 + PR #9 自设 HARD GATE 未跑 |
| 2026-05-22 | v1.2 最终修订版：§1.5 Assistant↔Agent 映射、§4 TA.1 Agent.handler 拆分、AgentEvent Final/Transient 双轨、§4 TA.2 加 message_node_id/produces_message_id 和 trace_span/permission_intent 表、§4 TA.3 拆 TA.3a UniFFI pioneer + TA.3b、§4.5 TA5.1 拆本地+远端、§4.5 TA5.3 加 NPF、§6 TC.1 加 assistantId、§6 TC.2 改 handler 伪代码 + projector 派生写入、§6 TC.3 去硬指标、§6 +TC.6 property test gating、§7 escape hatch、§10.4 跨库版本联动 + backup sanitizer、Q10/Q11/Q12/Q13、Surface/MessagePipeline/PermissionBroker/TraceRecorder 一等公民化 | OpenAI Agents SDK 视角二次审视收敛——发现 Assistant 概念在 v1.1 中 0 命中、流式 event 全部落库的容量隐患、跨库事务恢复未建模等根本性盲点 |
| 2026-05-22（晚） | v1.2.1 main 现状二次审计：新增 §2.6 列出 plan 撰写后 main 上 8 个 DeepRead refactor commit + 5 个事实；§4 TA.2 entity 类名 `AgentRunEntity` → `AgentRunRecord` 避免跟现有 `me.rerere...AgentRun` 视觉混淆；§1.5.2 补现有同名类型说明；§2 god class 基线数字 2347 → 2618 / 子系统 19 → 23；Phase B 选型选 "适配而非重写"（新增 TB.3.5）；Phase D 优先级排序 v1.2.1 调整为 tools/board → 中频 → 冷门优先预热 → icloud/settings；§2.6.4 列出 v1.2 不变的事项 | 拉 origin/main 复核 plan 假设：4 个 native bridge 调用数仍为 0（§2.5 不变）、DeepReadAgentRunManager 已实现 plan §5 大部分目标、DeepReadHiddenAssistantFactory 是 §1.5 模式的生产样板、AgentRuntimeModels.kt 已有 AgentRun 类型、ChatService 持续膨胀 |
