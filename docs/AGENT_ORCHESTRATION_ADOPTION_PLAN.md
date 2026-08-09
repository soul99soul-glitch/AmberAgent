# Agent Orchestration Adoption Plan（借鉴 codex 的编排体系落地计划）

Status: **P0–P3 全部完成**（2026-08-08，iOS 侧；Android 接线为后续专项）
Created: 2026-08-08
Supplements: `IOS_AGENT_HARDENING_PLAN_2026-07-29.md`、`MODEL_AUTONOMY_GUARDRAILS_PLAN.md`（不取代任何文档）
Evidence base: ① openai/codex 源码深挖（HEAD `2e3a1702`，2026-06-01 以来 2046 个提交，五个方向均有 file:line 证据）；② 本仓库四路只读复核（工具体系 / 会话运行编排 / 记忆系统 / JS 运行时与 Android 链路，2026-08-08 实时代码）。**开工前必须按仓库 AGENTS.md 重新核对所引行号——本计划记录的是复核时点事实，不是永久事实。**

---

## 0. 背景与总原则

### 0.1 背景

codex CLI 近两个月的主线验证了"编排渗透 harness"的判断：子代理编排（multi-agent v1）演进为**线程编排**（multi-agent v2：mailbox + 可寻址持久线程 + 六个扁平工具）、**工具调用编排**（code mode：模型写 JS 编排嵌套调用）、**上下文编排**（tool_search / 压缩 / 记忆污染态）。可迁移的四个范式：

1. **逻辑身份与运行时驻留分离**：agent/线程/任务有持久身份和寻址，运行时只是可换出的缓存。
2. **模型可见面 = 扁平工具 + harness 状态机时机**：编排能力暴露为少量扁平工具；"何时投递、何时唤醒、何时打断"由 harness 状态机决定，不写进 prompt 求模型自律。
3. **模型与 harness 之间用隐藏通道传结构化数据**：模型的 wire format 与 UI 渲染分层。
4. **策略活在配置层，降级降的是渲染不是能力**：不做硬编码门控。

### 0.2 Amber 移植总原则

- **抄范式，不抄 API**。codex 这批功能多数仍在 dogfood（multi-agent v2 标 stable 但默认关、code mode 默认关、工具两次改名）。
- **多 provider 现实**：codex 依赖 OpenAI 服务端三处能力（encrypted 参数、`defer_loading` 按需加载、远程加密压缩），Amber 必须用客户端等价物替代。本计划逐处标注。
- **能力可见性哲学不变**：本计划不引入任何"对模型藏能力"的门控；deferred 工具的语义是"schema 不前置、运行时全部可调"，与既有 guardrail 移除方向一致。
- **小闭环**：每条线拆成独立可验证的阶段，任一阶段完成即产生可用价值，不做大爆炸式落地。
- **红测试优先**：每个阶段先写失败契约测试再实现（仓库 AGENTS.md 方法）。

### 0.3 依赖顺序与理由

| 序 | 线 | 代号 | 理由 |
|---|---|---|---|
| 1 | tool_search / ToolExposure | **P0** | 独立高收益；Android 已有 KMP 实现，iOS 接线即可；同时补齐 PROJECT_STATE 明列的 `mcp__*` 展开缺口 |
| 2 | 线程编排 mailbox | **P1** | 主线；依赖 P0 的工具暴露机制（子线程工具面裁剪） |
| 3 | 记忆 polluted + citation | **P2** | 小切口顺手做；polluted 置位点与 P1 工具链收口共用 |
| 4 | code mode | **P3** | 依赖 P0 的 DEFERRED 元数据（`ALL_TOOLS` 发现）与 P1 的 wait/中断语义 |

---

## P0. tool_search / 工具暴露编排

### P0.1 复核结论（2026-08-08 实时代码）

**codex 机制**（参考实现，不照抄）：

- `tool_search` 工具（`query` 必填 + `limit` 默认 8），BM25 检索 deferred 工具元数据（Rust `bm25 = "2.3.2"` crate），命中工具带 `defer_loading` 由**服务端**下轮加载 schema（`codex-rs/core/src/tools/handlers/tool_search_spec.rs:21-95`）。
- `ToolExposure` bitflag：`DIRECT | DEFERRED | CODE_MODE` 一套元数据三个消费面；MCP 工具默认全部 Deferred（`c53b1dae`）。

**Amber 现状（关键发现：Android 已有完整实现，iOS 未接线）**：

| 事实 | 位置 |
|---|---|
| KMP 已有 `createToolSearchTool` / `ToolSearchIndex`（关键词加权打分 + 中文别名表）/ `ToolExposureState`（懒模式自动触发：工具数 >40 且存在 tool_search） | `feature/tools/api/src/commonMain/kotlin/app/amber/feature/tools/ToolSearch.kt:18-77,145-209,211-246,269-365,299-310` |
| Android 已接线：每步 `toolsForStep()` / `exposeToolNames()` / `observeExecutedTools()` | `app/src/main/java/app/amber/core/ai/GenerationHandler.kt:159-188,366` |
| `:feature:tools:api` 已导出进 iOS Shared framework | `shared/build.gradle.kts:45,73` |
| iOS 每轮**静态全量**声明，无任何按轮裁剪 | `iosApp/iosApp/ChatViewModel.swift:2735-2863`（`makeTextGenerationParams`） |
| MCP 仅单一 `mcp_call` 入口，未展开 `mcp__*`（Tool.kt 注释自认是占位） | `ai-core/src/commonMain/kotlin/app/amber/ai/core/Tool.kt:541-561` |
| MCP 发现工具 + inputSchema 已持久化缓存，展开所需数据已就绪 | `iosApp/iosApp/IOSMcpManager.swift:22,203-214`；`iosApp/iosApp/IOSMcpConfigStore.swift:68-83` |
| provider 转换层逐轮从 `params.tools` 逐条转换，客户端完全控制工具列表 | `ai-provider-openai/.../OpenAIKmpProvider.kt:331-350,802-829`；`ai-provider-claude/.../ClaudeKmpProvider.kt:394-412` |
| iOS 工具执行分发是静态 switch | `iosApp/iosApp/ChatToolRuntime.swift:1417-1486`（`dispatchAdvancedToolCall`） |

**关键推论**：codex 靠服务端 `defer_loading` 实现的"命中后下轮加载"，Amber 在客户端自己做更简单——请求是客户端构造的，命中工具下轮直接塞进 `params.tools` 即可。Android 的 `ToolExposureState` 已经是这个语义。

### P0.2 目标与成功标准

**目标**：iOS 与 Android 都有"工具数量超阈值时，模型经 `tool_search` 按需发现工具"的生产路径；MCP 工具展开为独立声明且默认 deferred；不因工具数量增长线性膨胀每轮 token。

**成功标准（可验证）**：

1. iOS 会话中声明工具数 > 阈值时，初始请求只含常驻工具 + `tool_search`；模型调用 `tool_search(query)` 后，命中工具在**下一轮**请求的 `tools` 中出现（请求体抓包/日志可证）。
2. 任一已启用 MCP server 的每个发现工具都有 `mcp__{server}__{tool}` 独立声明，且默认不进初始列表、可被 `tool_search` 命中、命中后可直接被模型调用（不再经 `mcp_call` 透传）。
3. 工具数 < 阈值时行为与现状逐字节一致（回归红线）。
4. Android 既有路径行为不回退（既有测试全绿）。

### P0.3 设计

**不新建检索器**。复用 KMP `ToolSearchIndex`（含中文别名表，比裸 BM25 更贴合中文场景）；BM25 升级列为后续度量驱动的可选项，不在本计划。

**（a）iOS 接线既有 KMP 机制（P0 第一刀）**

- 声明点：`ChatViewModel.makeTextGenerationParams()`（`ChatViewModel.swift:2807-2846`）——组装完静态全量后，过一遍 KMP `ToolExposureState.from(tools)`；超阈值时只输出 `toolsForStep()` 的常驻子集 + `tool_search` 声明。阈值初值与 Android 对齐（40），设置项不做新 UI（先吃 KMP 默认）。
- 执行点：`tool_search` 是**纯本地工具**（不需要 LLM/网络）。在 `ChatToolRuntime` 分类路由（`ChatToolRuntime.swift:354-383`）新增 `.toolSearch` kind，本地调 `ToolSearchIndex.search`，把命中工具名列表作为工具输出回灌；同一轮状态写入 `ToolExposureState.exposeToolNames()`。
- 轮次推进点：`ChatGenerationCoordinator.continueAfterToolResult`（`ChatGenerationCoordinator.swift:2798`）重新 `prepareAndStartStreaming` 时，`makeTextGenerationParams` 自然重算，命中工具进入下一轮声明。无需改循环结构。
- 状态归属：`ToolExposureState` 实例归 `ChatGenerationCoordinator` 的 run 快照（`ChatRunSnapshot`，`ChatGenerationCoordinator.swift:735`），一个 run 一份，随 run 终态销毁；后台交接（`backgroundHandoff`）时随快照移交 `IOSAgentToolEngine`（其 while 循环 `IOSAgentToolEngine.swift:715` 同样每轮重算）。
- **红线**：阈值未触发时输出与现状完全一致（契约测试断言工具名单序列相等）。

**（b）MCP `mcp__*` 展开 + 默认 deferred（P0 第二刀）**

- 命名：扁平 `mcp__{server}__{tool}`（MCP 社区与 Claude Code 惯例；amber provider 转换层全是扁平 function 名，不引入 codex 的 namespace 对象）。
- 声明生成：从 `IOSMcpManager.tools`（iOS）/ `McpManager` 发现缓存（Android）动态构造 `Tool`（`Tool.kt:16-31` 的 `parameters` 闭包喂发现的 inputSchema）。注意 `InputSchema` 目前只有 `Obj(properties, required)`（`Tool.kt:1420-1428`），MCP schema 的非 object 根/嵌套 `$ref` 需要先做**schema 规整化**（拍平为 object 根 + 原样透传 properties，不做递归解析）。
- 暴露策略：展开的 MCP 工具默认 `Deferred`（进 `ToolSearchIndex` 文档集，不进初始列表）；`mcp_call` 保留为兜底透传入口（常驻），二者不互斥。工具数未超阈值时 MCP 工具也**不**展开进初始列表（避免行为突变），由设置项 `mcpToolExposure = auto | always_direct` 兜底（默认 auto）。
- 执行路由：iOS `dispatchAdvancedToolCall` 当前是静态 switch（`ChatToolRuntime.swift:1433`），改为**前缀路由**：`name.hasPrefix("mcp__")` → 解析出 server/tool → `mcpManager.callTool`；Android `AgentToolDispatcher` 同。审批/账本语义与 `mcp_call` 一致（`IOSAgentRunLedger` 效果分类沿用）。
- 双轨收敛：展开的 MCP 工具不需要进任何 Swift 静态目录（`IOSMcpManagementToolCatalog` 只管管理类工具），这是减少双轨而非增加。

**（c）CODE_MODE 暴露位（占位，P3 用）**

- 在 KMP 侧给 `Tool`/目录条目预留 `exposure` 位标志（`DIRECT | DEFERRED | CODE_MODE`），本阶段只定义不消费，避免 P3 再改数据结构。

### P0.4 阶段拆分与验收

| 阶段 | 内容 | 验收标准 | 门禁 |
|---|---|---|---|
| P0-a | iOS 接线 `ToolExposureState` + 本地 `tool_search` 执行 | 红测试先行：超阈值时首轮请求不含 deferred 工具、含 `tool_search`；模拟模型调用后次轮请求含命中工具。绿后：阈值下行为与现状逐字节一致 | 新增 `IOSToolSearchExposureTests`；回归 `ChatViewModelGenerationParamsTests` |
| P0-b | MCP 展开 + 前缀路由 + schema 规整 | 契约：两个 server 各两工具的 fixture 产生 4 条 `mcp__*` 声明且默认 deferred；`mcp__srv__tool` 调用路由到正确 server（mock MCP client 断言）；非法前缀名诚实报错 | 新增 `IOSMcpExpandedToolTests`、`McpToolExposureTest`（KMP）；`IOSSkillMcpToolsTests` 全绿 |
| P0-c | Android 对齐与度量 | 双端阈值/常驻名单一致；产出一次真实会话的 token 对比（展开前后首轮请求 tools 段字节数）记入 PR 描述 | `:feature:tools:api` JVM 测试；`GenerationHandler` 相关 Android 测试 |

iOS 定点测试命令（任一 iOS 阶段）：

```bash
xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -parallel-testing-enabled NO \
  -only-testing:iosAppTests/IOSToolSearchExposureTests \
  -only-testing:iosAppTests/IOSMcpExpandedToolTests \
  -only-testing:iosAppTests/ChatViewModelGenerationParamsTests \
  -only-testing:iosAppTests/IOSSkillMcpToolsTests test
```

### P0.5 风险

- **Swift/Kotlin 互操作**：`ToolExposureState` 的 Kotlin 类型（sealed/默认值）导出 ObjC 后可能变形（先例：`AUTO`→`.auto_`）。开工第一步先写一个最小 Swift 调用冒烟测试验证可用性，不可用则在 KMP 侧加一层 `@ObjCName` 友好的 facade。
- **模型不按预期使用 tool_search**：弱模型可能无视它。缓解：`tool_search` 描述里写明"部分工具未预先提供"（抄 codex 文案思路）；不删任何既有能力兜底。
- **schema 规整化损耗**：MCP 工具 schema 含 `$ref`/anyOf 时透传可能失败。策略：规整失败的工具保留 `mcp_call` 透传通道并记日志，不阻塞整体展开。

### P0 完成记录（2026-08-08）

P0-a/P0-b 已按"实现代理 + 独立 checker 对抗复核 + 精准修复"闭环，全部门禁绿。

- **实现**：KMP `IosToolExposureBridge`（feature/tools/api，iOS 常驻名单 24 名钉死）；`ToolExposureState.from` 加 `residentPolicy` 默认参数（Android 字节级不变）；iOS run 级桥接线（`ChatViewModel.makeTextGenerationParams` → `ChatGenerationCoordinator` → `ChatToolRuntime` 本地执行）；MCP `mcp__{server}__{tool}` 展开（ai-core 命名/规整化/碰撞去重纯函数 + iOS 前缀路由，审批/账本/后台与 `mcp_call` 逐路径一致）。
- **度量**（20 个 MCP 展开工具、56 静态基线）：全量目录足迹 27170→32330 字符，**首轮可见足迹 13212→13212（+0）**；轻配置（≤40）保持 bypass 全量可见。
- **checker 两轮复核后修复**：A. lazy 模式注入 discovery 引导（iOS 此前不消费 `Tool.systemPrompt`，模型可直接调未暴露工具硬失败整轮）；B. 目录内未暴露工具调用软失败（写失败输出 + tool_search 引导、消耗 resume 预算，未知名保持硬失败）；C. 后台闭环（handoff 携 `fullToolNames`、后台桥全目录重建、引擎按轮 `replacingTools`）；残余：后台桥暴露种子（防第 2 轮丢前台已暴露工具）、JSON Schema `type` 数组崩溃（Python MCP SDK 会产出）。
- **落地偏差**（相对上文设计，均为实测驱动）：后台桥目录改 `additionalDeclarations` 参数（按名重建会丢 `mcp__*`）；`McpDiscoveredToolSpec` 用字符串 schema 次构造器（`JsonObject` 不可从 Swift 构造）；KMP `systemPrompt` 文本经桥 `discoveryGuidance()` 复用而非新写。
- **门禁**：`xcodebuild` 四套件（IOSToolSearchExposureTests/IOSMcpExpandedToolTests/IOSSkillMcpToolsTests/IOSAgentToolEngineTests）+ `./gradlew :ai-core:jvmTest :feature:tools:api:jvmTest :shared:jvmTest` 全绿（JDK 21，`~/.gradle/jdks` 工具链）；未放宽任何断言。
- **未完成/跟进**：Android app 侧接线（本机无 SDK；KMP 纯函数与 jvmTest 双端就绪，接线点为 `GenerationHandler` 声明组装 + `AgentToolDispatcher` 前缀路由）；真实 MCP server schema 多样性、真机后台续跑属模拟器外验证。

---

## P1. 线程编排：mailbox + 可寻址持久线程

### P1.1 复核结论

**codex v2 机制**（`codex-rs/core/src/agent/`、`tools/handlers/multi_agents_spec.rs`、`state/turn.rs`）：

- agent = 持久逻辑实体（`AgentPath` 树，`/root/task1/task_3`），运行时驻留是 LRU 实现细节；六个扁平工具：`spawn_agent`（`fork_turns=none/all/N`、role）、`send_message`（只排队）、`followup_task`（排队+唤醒）、`wait_agent`（无 target 等 mailbox 任何活动、可被 steer 打断、超时 clamp）、`interrupt_agent`（打断 turn 不销毁）、`list_agents`。
- mailbox：typed 信封（MESSAGE/NEW_TASK/FINAL_ANSWER）投递由相位状态机控制——`CurrentTurn` 时邮件在下一 sampling 边界折入当前 turn；输出过可见终态后转 `NextTurn` 留到下一轮（`state/turn.rs:42-55`）。
- 并发限"活跃执行 turn 数"而非"agent 数"；app-server 拒绝客户端直接 steer 子线程（控制面统一走父 agent 工具）。

**Amber 现状（关键发现：Android 已有 steer mailbox，iOS 没有；KMP 已有 spawn/handoff 原语）**：

| 事实 | 位置 |
|---|---|
| Android steer 队列完整：生成中发消息入队（STEER/FOLLOWUP）、落盘持久化、每步循环末尾消费 | `app/.../core/service/ChatService.kt:685-712`；`ConversationSession.kt:110-158`；`GenerationHandler.kt:388-392` |
| iOS 生成中 composer 直接禁用，只能 cancel | `iosApp/iosApp/ChatViewModel.swift:482-493`；`ChatGenerationCoordinator.swift:1225-1311` |
| KMP agent-runtime 已有 `RunScope.child`（spawn 原语）与 `handoff` | `core/agent-runtime/.../RunScope.kt:21-29` |
| `agent_run` 账本（Room）含 parentRunId/status，`listUnfinished` 可作并发计数源 | `core/agent-store-room/.../Entities.kt:8-35`；`AgentRuntimeDao.kt:53-54` |
| 会话存储：`{id}.json` canonical + `index.json` 派生；**无 fork/DAG**；变体机制（`MessageNode.selectIndex`）与 `truncateAfter` 可用 | `core/conversation-storage/.../JsonConversationStorage.kt:17-26`；`iosApp/iosApp/IOSConversationStore.swift:1030,1102` |
| 会话模型有 `assistantId` 字段——天然对应 codex 的 role | `core/types/.../Conversation.kt:13-28` |
| iOS 工具循环检查点（可插 mailbox 消费） | 前台 `ChatGenerationCoordinator.swift:2798`（`continueAfterToolResult`）、`:1784-1828`（`startStreaming` 头）；后台 `IOSAgentToolEngine.swift:715` |
| 议会/DeepRead 是各自为政的编排 surface | `feature/modelcouncil/.../ModelCouncilManager.kt`；`app/.../deepread/DeepReadAgentRunManager.kt` |

### P1.2 目标与成功标准

**目标**：会话即线程、线程可寻址；任意两个线程间可通过 mailbox 投递 typed 信封；模型经六个工具编排线程；iOS 补齐 steer 基础能力。

**成功标准（可验证）**：

1. iOS 生成中发送的消息进入持久化队列，在当前工具循环边界折入下一轮模型请求（与 Android 行为一致）；进程被杀后队列不丢。
2. 模型调用 `spawn_agent(task_name, fork_turns=3, role=某 assistant)` 后：产生新会话（持久化 spawn 边、路径 `/root/.../task_name`、历史截断为最近 3 轮），并收到初始任务信封开始运行。
3. 运行中的父线程在工具循环边界收到子线程的 `FINAL_ANSWER` 信封并折入下一轮请求；`wait_agent(timeout)` 在 mailbox 活动时提前返回、被 steer 打断时立即返回、超时被 clamp 到下限而非报错。
4. `interrupt_agent` 中断目标当前 run 但线程保持可寻址（可再 `followup_task`）；`list_agents` 返回活线程树。
5. UI 不提供任何直接向子线程发输入的入口（控制面纪律）；并发活跃 run 数超限时 `spawn_agent` 收到可读的 `AgentLimitReached` 错误。
6. 无双线程会话的全部既有行为不回退。

### P1.3 设计

**（a）存储与身份（KMP，`core/agent-store-room` 扩表）**

- 新表 `thread_edge`（`parentThreadId, childThreadId, agentPath, nickname, roleAssistantId, forkTurns, status(Open/Closed), createdAt`）与 `mailbox_envelope`（`id, authorPath, recipientPath, type(MESSAGE/NEW_TASK/FINAL_ANSWER), payload, triggerTurn, parentTurnId, createdAt, deliveredAt`）。复用 Room（iOS 已有 `IosDatabaseFactory`）。信封**明文**（codex 的 encrypted 是 OpenAI 服务端能力，客户端无等价物；typed header 保留）。
- 线程 = conversation。`Conversation` 新增可选字段 `parentThreadId/agentPath/orchestrationRole`（缺省 null，旧 JSON/Room 兼容）。**不动** `MessageNode` 变体结构。
- 路径：root 会话路径 `/root`；子线程 `{parentPath}/{task_name}`（task_name 校验 `[a-z0-9_]+`，冲突自动加后缀）。

**（b）fork 语义**

- `fork_turns=none`：新会话只带系统上下文与初始信封；`all`：完整复制 `messageNodes`；`N`：复制后按**用户轮次**截断保留最近 N 轮（含其 assistant 应答；变体只保留 `selectIndex` 当前项——避免把未选中候选带给子线程）。实现为 `JsonConversationStorage` 层的纯函数 `forkConversation(id, spec) -> Conversation`，iOS/Android 共用，契约测试覆盖三种模式 + 变体折叠 + 工具 part 未闭合裁剪（截断点不得留下 output 为空的 tool part）。

**（c）投递相位状态机（harness 拥有时机）**

- 每 run 一个 `MailboxPhase`（`CurrentTurn | NextTurn`）：run 内流式完成且产出可见终态文本 → `NextTurn`；同 run 因工具/信封续作 → 重新打开 `CurrentTurn`。
- 消费点（三处工具循环边界，复核已确认）：iOS 前台 `continueAfterToolResult`（`ChatGenerationCoordinator.swift:2798`）与 `startStreaming` 头部（`:1806-1828`）；iOS 后台 `IOSAgentToolEngine.run` 循环体（`IOSAgentToolEngine.swift:816-883`）；Android `GenerationHandler` 每步末尾既有 `consumeSteerMessages()` 处（`GenerationHandler.kt:388-392`）泛化为 `consumePendingInputs()`（steer + mailbox 统一）。
- 折入形式：信封渲染为带结构头的 user 角色消息（`[mailbox MESSAGE from /root/a → /root]\n{payload}`），与普通 steer 消息同通道进下一轮请求；持久化进会话 JSON（可回放、可审计）。
- `triggerTurn=true` 的信封投递给 idle 线程时：iOS 走 `IOSChatBackgroundGenerationCoordinator` 的可恢复 run 路径（durable 先行，遵守"已提交系统任务 ≠ 系统保证运行"的局部规则）；Android 走前台服务 keepAlive 路径。

**（d）工具面（声明进 `Tool.kt`，执行新增 iOS `IOSThreadOrchestrationToolService` / Android 经 `AgentToolDispatcher`）**

| 工具 | 参数 | 语义要点 |
|---|---|---|
| `spawn_agent` | `task_name`（必填）、`message`、`fork_turns(none/all/N)`、`role_assistant_id?` | 建 fork 会话 + 写 spawn 边 + 投递 `NEW_TASK(triggerTurn=true)`；并发限额检查在创建前（`listUnfinished` 计数，上限可配置，初值 3） |
| `send_message` | `target`、`message` | 只入队（`triggerTurn=false`） |
| `followup_task` | `target`、`message` | 入队 + idle 则唤醒 |
| `wait_agent` | `timeout_ms?` | 挂起当前工具调用，等本线程 mailbox 任何活动；steer 到达立即返回；`timeout_ms` clamp 到 [5s, 300s]；返回 `{message, timed_out}` |
| `interrupt_agent` | `target` | 走既有 `cancel(runId)` 语义（`ChatGenerationCoordinator.cancel` / Android runner cancel）但**保留线程**；返回 `previous_status` |
| `list_agents` | `path_prefix?` | 从 `thread_edge` + run 状态投影活线程树 |

- 工具暴露：默认不进普通会话（`ExplicitRequestOnly` 语义）——用户消息或 assistant system prompt 显式提到"子代理/并行/线程"时才声明（先做一个保守的开启条件：设置开关 + 关键词触发，与现有 capability 声明开关同机制）；子线程默认继承父工具面（含编排工具，`DEFAULT_MAX_DEPTH=2` 防无限嵌套）。
- **控制面纪律**：UI 层禁止向子线程直接发消息（子线程在会话列表只读展示，标注"由 /root 编排"）；所有输入经父线程工具。这条直接减少 state-flow audit 的写入者数量。

**（e）wait_agent 与 steer 打断（依赖 P1 第一刀）**

- iOS 需先有 steer 队列（见 P1 阶段 a）。`wait_agent` 执行体 `await` 一个 `MutableStateFlow<MailboxActivity>` 的 `first { 命中 }` 与 `timeout` 竞速；steer 入队时向同一 flow 发射，实现"打断 wait"。Android 直接复用 `ConversationSession` 的 pending 队列信号。

**（f）并发与驻留**

- 限额 = 活跃 run 数（`agent_run` 表 `listUnfinished` 为权威计数源——**前置依赖**：先修 Android `InProcessAgentRunner` 账本 `runCatching` 吞异常（`InProcessAgentRunner.kt:82-84,102-104`），否则计数不可信）。
- 驻留：不活跃子线程只释放内存中的 ViewModel/runner，持久层身份永续；再次投递时按需重建（iOS 参照既有后台脱离恢复，Android 参照 `ConversationSession` 重建路径）。不做 codex 式 LRU 换出（移动端活跃数小，YAGNI）。

### P1.4 阶段拆分与验收

| 阶段 | 内容 | 验收标准 | 门禁 |
|---|---|---|---|
| P1-a | **iOS steer 队列对齐**（前置基础，独立价值） | 红测试：生成中 `send` 不再被 `composerSendBlockReason` 拦截而入队（含落盘）；工具循环边界折入；进程重启后队列恢复。UI：生成中发送按钮变"排队"态而非禁用 | 新增 `IOSSteerQueueTests`；`ChatViewModelGenerationParamsTests`、`IOSConversationStoreTests` 全绿 |
| P1-b | mailbox 存储 + 相位状态机 + 三处消费点 | KMP 契约：FIFO、`triggerTurn` 唯一父 turn 归约、`NextTurn` 迟到信封不丢；iOS：运行中 run 边界折入信封并持久化进会话 JSON | `core/agent-store-room` JVM 测试；新增 `IOSMailboxDeliveryTests` |
| P1-c | `spawn_agent`（fork 三模式）+ `list_agents` + `interrupt_agent` | fork 契约全绿；spawn 边持久化 + 进程重启后 `list_agents` 可重建；interrupt 后线程可再派活 | 新增 `IOSThreadForkTests`；`IOSRunRecoveryTests` 不回归 |
| P1-d | `send_message`/`followup_task`/`wait_agent` + steer 打断 wait + idle 唤醒 | 三义隔离：send 不唤醒、followup 唤醒 idle、wait 三出口（活动/steer/超时 clamp）各有行为测试 | 新增 `IOSWaitAgentTests`；Android `GenerationHandler` 相关测试 |
| P1-e | 并发限额 + 控制面纪律 + Android 账本吞异常修复 | 限额到达时 `spawn_agent` 返回结构化错误并被模型可见；UI 审查无子线程直发入口；`InProcessAgentRunner` 账本失败走可见错误通道 | Android `app` 模块相关测试；`IOSParityRedLightTests` |
| P1-f | （可选，后期）议会/DeepRead 接入统一线程抽象 | 单独立项评估，不在本计划验收内 | — |

通用门禁（每阶段）：上述定点 xcodebuild + `./gradlew :core:agent-store-room:test :feature:tools:api:test`（按触及模块收窄）。

### P1.5 风险

- **iOS steer 是行为变更**：composer 从"禁用"变"排队"，需要 taste 评审（生成中发送按钮状态、排队消息可见可撤销）。这是 P1-a 的最大非技术风险。
- **会话 JSON 双写者**：mailbox 折入会写会话文件，与既有消息写路径（`operationMutex` 串行化）天然兼容，但必须复用 `JsonConversationStorage` 的既有锁，不在 Swift 侧另建写通道。
- **唤醒 idle 线程的后台限制**：iOS `BGContinuedProcessingTask` 不保证运行时长，`followup_task` 唤醒必须 durable 先行（落盘 + 可恢复记录），UI 如实表达"已排队"而非"运行中"（iosApp/AGENTS.md 明令）。
- **深度嵌套失控**：`DEFAULT_MAX_DEPTH=2` + 并发限额双保险；usage hint 文案明确"N 个并发槽含你自己"（抄 codex 思路）。

### P1-a 完成记录（2026-08-08）

- **实现**：生成激活期间 composer 发送改为入队（v1 仅文本 STEER；队列上限 20 与 Android `MAX_PENDING_USER_MESSAGES` 同值，满时新增 block reason `.steerQueueFull`）。内存队列唯一 owner 为 `ChatViewModel`（`steerQueue`，@Observable），`ChatGenerationCoordinator` 经 bindings `drainSteerQueue`/`restoreSteerQueueLeftover` 消费，不持有第二份队列；消费点 = `continueAfterToolResult`（下一轮 upload 折入）+ `startStreaming` 头兜底（`displayMessagesOverride == nil` 时才消费，补绘重试轮不消费——该轮展示基线是显式快照，折入会让排队消息从展示/落盘谱系丢失，统一走终态 composer 恢复）；run 终态（`finishStreaming`/`cancel`）leftover 按顺序拼接恢复进 composer（可见、不静默丢、不自动发起新生成）。持久化：`IOSSteerQueueStore` per-conversation sidecar `Documents/steer-queue/{conversationId}.json`（原子写、空删文件，沿用 list-previews.json 写法）；冷启动恢复只进队列 UI 不自动发送。UI：`ChatSteerQueueStrip`（composer 上方排队条，单行截断 + 44pt 撤销 + 计数，ScaledMetric/Reduce Motion/VoiceOver 标签），生成中发送键有文本时翻转为「加入队列」（无文本保持「停止」）。
- **落地偏差**（相对 P1.3 设计，均为代码现状驱动）：审批恢复路径实测走 `continueAfterToolResult`（非直接 `startStreaming`），`startStreaming` 头消费作为兜底保留；后台引擎（`IOSAgentToolEngine`）本轮不消费队列（v1 前台边界专用，后台 run 的 leftover 留在队列 UI）；sendMessage 的图片/待发文件路径在生成中维持原拦截（v1 队列条目仅文本，不静默丢附件）。
- **门禁**：新增 `IOSSteerQueueTests` 8/8（入队不直发/边界折入+持久化+清空/撤销同步删 sidecar/上限 block reason/终态恢复顺序/无边界不消费/store 往返+冷启动/会话重开恢复），回归 ChatViewModelGenerationParamsTests + IOSConversationStoreTests + IOSToolSearchExposureTests 共 60/60 全绿；`git diff --check` 通过；模拟器截图四态（两条排队/accessibility 大字号/深色/空队列零占位）已存仓库外 `/tmp/amberagent-steer-screens/`。
- **未验证/跟进**：真实 provider 端到端生成中排队；后台 run 队列消费（P1-b 三消费点补）；真机。

### P1-b/c/d/e 完成记录（2026-08-08）

- **P1-b mailbox 基础设施**：Room `mailbox_envelope` + `MailboxDao`（`drainPending` 事务化 exactly-once + 并发加固 loser 返回空）；前台两边界消费（`continueAfterToolResult` 末尾 + `prepareAndStartStreaming` 头，门控 `displayMessagesOverride == nil`），mailbox 先于 steer；终态未消费信封留 Room 不回 composer；Room v1→v2 正式迁移（v1 数据保全测试 + 未配迁移拒绝打开）。
- **P1-c 编排工具**：`spawn_agent`（`fork_turns=none/all/N` + 变体折叠与空 tool part 裁剪、`thread_edge`（v2→v3 迁移）、深度上限 2、并发限额、NEW_TASK bootstrap、durable 后台启动）、`list_agents`、`interrupt_agent`（打断不销毁、edge 保留）；子终态 `FINAL_ANSWER` 回传（`final-{runId}` 幂等，空转录也回传结构化信封）。checker 复核修复：后台 runtime 注入编排服务（孙线程死路径）、编排锚定 run 会话（防生成中切会话污染边树）、前台 cancel 挂终态回传、spawn 失败回收孤儿 running 行、ABORT 去重崩溃（改 `enqueueIfAbsent`/IGNORE）。
- **P1-d 消息/等待**：`send_message`（入队不唤醒）、`followup_task`（idle 走 spawn 同款 bootstrap 唤醒、运行中仅入队）、`wait_agent`（五出口：已有 pending/mailbox 活动/steer 打断/超时 clamp [5s,300s]/取消）；`IOSMailboxActivityCenter` 进程内 actor 广播（先订阅后查 pending 两窗口不丢）；后台引擎 `IOSAgentToolEngine.run` 可选 `mailboxDrain`（默认 nil 零影响），调用点与 replacingTools 同点。
- **P1-e 限额与控制面**：并发计数从全局账本改活注册表（前台 run + 后台 activeJobs + 在途 bootstrap，检查+占槽 MainActor 原子）——崩溃残留 running 行不再误伤 spawn；子线程会话只读（composer `.orchestratedThread` 拦截 + 编辑/重生成/删除/变体四守卫，缓存异步刷新，切换窗口毫秒级为已接受取舍——未决期拦截方案会引入 Room 挂起时 composer 死锁，弃用）；会话列表徽标未做（ConversationsView 当时在用户 WIP 中，VM 层 `isOrchestratedChild` 查询就绪）；Android `InProcessAgentRunner` 账本吞异常修复（`onLedgerError` 回调接线 `ChatService.addError`，无 SDK 未编译验证）。
- **主线闭环**（checker 逐环读码确认）：父 spawn → 子后台 run（引擎含 mailboxDrain）→ 子终态 FINAL_ANSWER + signal → 父 wait_agent 唤醒 → 父边界 drain 折入 → 父模型可见。

---

## P2. 记忆：polluted 污染态 + citation 隐藏标记

### P2.1 复核结论

**codex 机制**：

- `<oai-mem-citation>` 隐藏标记：流式 `InlineHiddenTagParser` 从可见文本剥离（EOF 自动闭合容错），解析为结构化引用、记录使用情况、UI 渲染（`codex-rs/utils/stream-parser/src/citation.rs:11-30`）。
- `memory_mode` 三态（`enabled|disabled|polluted`，SQL 直写）；触发源全是 **harness 对工具输出的分类**（MCP `pollutes_memory` 元数据 / 输出含外部上下文 / ToolSearch、WebSearch 响应项），不经过模型；polluted 线程排除出记忆合并输入并触发"遗忘"重算。

**Amber 现状**：

| 事实 | 位置 |
|---|---|
| 记忆注入已是 `<memory-context>`（iOS）/`<memory_context>`（Android）system 消息，且 iOS 已附"不可信上下文"指令 | `iosApp/iosApp/ChatContextSupport.swift:63-72,334-338`；`app/.../core/memory/recall/MemoryPromptBuilder.kt:6-38` |
| 召回双轨（iOS `ChatMemoryContextBuilder` vs Android `MemoryRecallStore`），规则同构实现独立 | `ChatContextSupport.swift:19-108`；`MemoryRecallStore.kt:59-149` |
| **iOS 无抽取/dream 管线**（只有工具写+手动）；Android 有 `MemoryExtractor.extractAfterConversation` + dream | `app/.../core/service/ChatService.kt:1473` |
| 工具输出唯一收口（"外部上下文进会话"挂钩点） | iOS `ChatToolRuntime.swift:725-745`（`messagesByFinishingToolCall`）；Android `AgentToolDispatcher`→`GenerationHandler` |
| `Conversation` 无自由 metadata 字段；`isPinned`/`autoApproveToolCalls` 是会话级标记先例 | `core/types/.../Conversation.kt:14-28` |
| vendor markdown 有未接线的 Citation 模块与 `InlineConvertible` 扩展点 | `iosApp/vendor/SwiftStreamingMarkdown/Sources/MarkdownText/Citation/` |
| `MemoryRecord.lastUsedAt` 字段已存在（召回侧使用记录零模型依赖） | `core/memory/api/.../MemoryModels.kt:78-95` |

### P2.2 目标与成功标准

1. **polluted（主目标）**：碰过外部上下文的会话由 harness 置 `memoryMode=polluted`；polluted 会话不进 Android 抽取管线；状态持久化、可在设置页查看/手动复位。
2. **citation（实验性副目标）**：模型引用记忆时可带隐藏标记，harness 剥离、结构化记录、UI 渲染引用来源；同时落地零模型依赖的 `lastUsedAt` 召回使用记录。
3. 成功标准：web 搜索或 MCP 调用完成后会话即 polluted（可断言）；`MemoryExtractor.extractAfterConversation` 对 polluted 会话短路；citation 标记不出现在渲染文本中（流式与落盘双路径断言）。

### P2.3 设计

**（a）polluted 三态（P2 第一刀，一天级）**

- 字段：`Conversation.memoryMode: MemoryMode = ENABLED`（`ENABLED | DISABLED | POLLUTED`，wireName 稳定，旧 JSON 缺省 ENABLED；Android `ConversationEntity` Room 迁移加列缺省 0）。
- 置位点（harness 拥有，不经模型）：iOS `messagesByFinishingToolCall`（`ChatToolRuntime.swift:725`）收口处判定工具名 ∈ {`search_web`, `scrape_web`, `mcp_call`, `mcp__*`, `wm_*`（外部站点读取类）} → 置位；Android 在 `GenerationHandler` 工具结果回填处同。置位幂等、只升不降（ENABLED→POLLUTED，不可自动回退）。
- 效果：①Android `extractAfterConversation` 对 polluted 会话短路（`ChatService.kt:1473` 前置 gate）；②iOS 无抽取管线，仅持久化 + 展示；③设置页记忆区显示"此会话已因外部内容暂停记忆抽取"+ 手动复位按钮（复位是用户操作，允许 POLLUTED→ENABLED）。
- **语义边界**：污染的是"会话作为抽取源"的资格，不是既有记忆本身；召回注入不受 polluted 影响（与 codex 一致：codex 只把它排除出合并输入）。

**（b）citation 隐藏标记（P2 第二刀，实验性）**

- 格式：`<amber-mem-cite>{"ids":["..."],"note":"..."}</amber-mem-cite>`（JSON body，便于解析容错）。
- 模型侧引导：记忆注入文案追加一句"引用上述记忆时请附 `<amber-mem-cite>` 标记"。**诚实预期**：非微调模型遵循率有限，本功能按"有则增值、无则无害"设计——标记缺失不影响任何现有行为。
- harness 侧：①流式剥离——iOS 参照 vendor `Citation/CitationCoder.swift` 与 `InlineConvertible` 扩展点做隐藏 inline 元素（遵循 vendor 局部规则：默认关闭，只在 AmberAgent 调用点启用）；Android 在消息投影层剥离；②落盘前剥离（存储的 canonical 文本不含标记，标记结构化存 message metadata）；③记录 `lastUsedAt`；④渲染为轻量引用 chip。
- **同时做零模型依赖版**：召回注入时把本次注入的记忆 id 列表记入 run 上下文，生成结束即更新这些记录的 `lastUsedAt`（不依赖模型配合，立即可得"哪些记忆真被注入后使用了"的下半环数据）。

### P2.4 阶段拆分与验收

| 阶段 | 内容 | 验收标准 | 门禁 |
|---|---|---|---|
| P2-a | polluted 三态 + 置位 + 抽取 gate + 设置页复位 | 红测试：search_web 完成后 `memoryMode==POLLUTED` 且持久化；抽取短路；复位回 ENABLED。旧会话 JSON 无损升级 | 新增 `IOSMemoryPollutionTests`、`MemoryPollutionTest`（KMP/Android）；`IOSMemoryRecallPolicyTests` 全绿；`:core:conversation-storage` JVM 测试 |
| P2-b | `lastUsedAt` 召回侧记录 | 注入过的记忆在 run 完成后 `lastUsedAt` 更新；无注入不更新 | 双端召回测试扩断言 |
| P2-c | citation 标记（实验） | 流式/落盘剥离契约；渲染 chip 定点截图；标记缺失零影响回归 | `ChatSwiftUIStreamReplayTests` 等渲染门禁（iosApp/AGENTS.md 规定组合） |

### P2.5 风险

- **置位误伤**：`wm_*` 工具族既有读内网也有读外网，误标会扩大停抽范围。策略：第一刀只标 `search_web/scrape_web/mcp_call/mcp__*`（明确外部），`wm_*` 待有 URL 分类后再纳入。
- **双轨漂移**：iOS/Android 置位判定各自实现，需共享 KMP 常量表（污染工具名集合）避免漂移。

---

## P3. code mode：模型写 JS 编排工具调用

### P3.1 复核结论

**codex 机制**（详见调研报告）：freeform `exec` 工具（Lark grammar 约束 raw JS）、V8 isolate（独立 host 进程）、`tools` 全局对象嵌套调用（dispatch broker 每调用独立 tokio task 并发）、`wait`+cell 跨轮、`store`/`load` 跨 cell 状态、`ALL_TOOLS` deferred 发现。

**Amber 现状（关键发现：Android 已有无头 JS 工具先例，iOS 零基础）**：

| 事实 | 位置 |
|---|---|
| Android 已有 `eval_javascript`：QuickJS（`wang.harlon.quickjs:wrapper-android:3.2.3`）、ES2020、无 DOM/Node、console 捕获、逐助手开关 | `app/.../core/ai/tools/JavascriptTool.kt:26-85`；`gradle/libs.versions.toml:28,131`；`AssistantLocalToolPage.kt:104` |
| iOS **无任何 JS 引擎**（无 `import JavaScriptCore`；MiniApp 走 WKWebView postMessage 桥） | 全 iosApp 源码复核（2026-08-08） |
| KMP 无 JS expect/actual 抽象（零基础即零迁移成本） | `shared/`、`ai-core/`、`core/` 复核 |
| iOS 工具接入点：前台静态 switch + 后台 executor 注册表 | `ChatToolRuntime.swift:1417-1486,165-352` |
| Android 工具接入点：`LocalTools` 注册即全链路生效（审批/hooks/重试/投机预执行） | `app/.../core/ai/LocalTools.kt:52,107` |
| 后台预算三重约束：工具续跑 12 次（默认）、引擎 maxSteps 8/后台 6、挂起恢复 2 次 | `SettingsStore.swift:97`；`IOSAgentToolEngine.swift:506-509`；`IOSChatBackgroundSuspension.swift:22` |
| MiniApp 三层 gate（声明∩全局∩逐次授权）+ 审计可复用 | `IOSMiniAppBridgeRuntime.swift:516-557` |

### P3.2 目标与成功标准

**目标**：模型可写 JS 在一次 `exec` 调用内编排多个工具（循环、过滤、聚合），嵌套调用走与顶层调用完全相同的审批/账本/执行链；长时间运行的脚本可 yield 并经 `wait` 续取。

**成功标准**：

1. 模型提交 `exec({code: "const r = await tools.search_web({query:'x'}); return r.length"})` 在 iOS 与 Android 都执行成功，嵌套的 `search_web` 在工具账本中留有独立的 started/finished 记录（与顶层调用同链路）。
2. JS 环境无 fs/网络/console 以外的宿主见不到的对象；死循环脚本被 watchdog 终止并返回模型可读错误（不卡死 run）。
3. 脚本可 `store`/`load` 跨 `exec` 调用传递状态（同会话内）；长脚本 yield 后模型用 `wait(cell_id)` 续取，每次 wait 消耗一次工具续跑预算并在预算耗尽时诚实失败。
4. 嵌套调用的审批语义与顶层一致（需审批的工具在嵌套中同样触发审批暂停）。
5. 未开启时（设置开关默认关、逐助手开关）无任何行为变化。

### P3.3 设计

**（a）多 provider 现实下的输入形态（与 codex 的关键差异）**

codex 用 Lark grammar 让模型输出 raw source；amber 的 provider 全是 JSON 参数工具，**无 grammar 约束能力**。采用 `{code: string}` JSON 参数 + 描述文案强引导（"raw JavaScript, no markdown fences"）+ 解析容错（剥离首行 `// @exec:` pragma 与 markdown fence）。这是明确的保真度妥协，写入工具描述与系统提示。

**（b）KMP JS 引擎抽象（新建）**

```
core/js-sandbox (新模块)
  commonMain: JsSandboxEngine (expect) — evaluate(code, globals): JsResult; setInterruptHandler; terminate()
  androidMain: QuickJsSandboxEngine（复用既有 quickjs 依赖与 JavascriptTool 经验）
  iosMain:     无（Kotlin/Native 无法直接绑 JavaScriptCore）→ iOS 侧引擎放 Swift 实现
```

**决策**：不做强制 KMP 统一。Android actual 用 QuickJS；iOS 引擎在 Swift 层新建 `IOSJsSandboxEngine`（JavaScriptCore，系统框架零依赖）。KMP 只定义工具声明（`ToolKt.createExecToolDeclaration` / `createWaitToolDeclaration`）与 cell 状态数据模型。理由：iOS 的 JSC 绑定经 KMP 绕一层只会增加 ObjC 互操作风险（P0 风险表已立先例），而执行链路本来就在原生侧。

**（c）exec / wait 工具与嵌套桥**

- 声明：`exec({code, timeout_ms?, max_output_chars?})` 与 `wait({cell_id, timeout_ms?, terminate?})` 进 `Tool.kt`；默认不进普通会话——设置总开关（默认关）+ 逐助手开关（对齐 `eval_javascript` 既有模式）。
- JS 全局：隔离全局对象，只注入 `tools`、`store`/`load`、`text`/`notify`（对齐 codex 语义，命名保持一致降低模型迁移成本）；**无** console（输出只经 `text`/`notify` 回传）、无 setTimeout（第一刀）、无 fetch/无模块导入。
- 嵌套桥：`tools.{name}(args)` → JS 引擎 native callback → 宿主工具执行链（iOS：`ChatToolRuntime.execute` 复用，含审批暂停；Android：`AgentToolDispatcher.execute`，含 hooks/重试）。**可调用白名单 = 当轮已暴露给模型的工具集**（含 P0 的 deferred 命中工具；编排工具 `spawn_agent` 等默认排除，对齐 codex "collaboration tools cannot be called from exec"）。
- 并发：同一 cell 内多个并发 `await` 各起独立协程/task（对齐 codex dispatch broker）；`exec` 不可自调用。
- `ALL_TOOLS`：注入当轮工具集的 `{name, description}` 数组（**依赖 P0 的 DEFERRED 元数据**，否则没有"可发现但未声明"的工具层）。

**（d）cell 生命周期与 wait（依赖 P1 的中断/等待语义）**

- cell = 一次 `exec` 的执行句柄（`cell_id`），状态机 `Running → Completed | Terminated | Failed`（第一刀不做 codex 的 Tombstone/Claimed 细粒度）。
- yield 语义：`timeout_ms` 到期仍未完成 → 返回 `Script running with cell ID ...`，cell 继续跑；模型 `wait(cell_id)` 续取（消耗一次工具续跑预算，预算 12 默认兜底）；`terminate: true` 杀 cell。
- 持久化：cell 状态（`store` KV + 最后输出 frontier）落盘（会话目录 sidecar JSON），进程死亡后 cell 标记 `interrupted` 不假 completion（对齐仓库终态纪律）。iOS 后台交接时活跃 cell 随 run 快照移交，受 BGTask 窗口约束如实收口。
- `store`/`load` 作用域 = 会话（conversationId），容量上限（单 key 64KB，总会话 1MB）。

**（e）安全边界**

- 引擎层：QuickJS/JSC 均无 fs/网络；JSC 注意**无公开 terminateExecution API**——用独立 `JSVirtualMachine` + watchdog 线程（超时置取消标志，QuickJS 用原生 interrupt handler；JSC 侧超时则整个 context 释放重建，正在执行的 JS 由 `JSContext` 异常回调收口为失败）。
- 工具层：嵌套调用继承全部既有审批/限流/审计（白名单即当轮工具面，不新开口子）；`exec` 本身按高风险工具处理（`needsApproval=true` 默认，逐助手开关）。
- 资源：单 cell CPU 时间（默认 10s）、输出（默认 10K 字符）、并发 cell（每会话 4）硬上限。

### P3.4 阶段拆分与验收

| 阶段 | 内容 | 验收标准 | 门禁 |
|---|---|---|---|
| P3-a | iOS JavaScriptCore 引擎 + 纯求值 `exec`（无 tools 桥，对齐 Android `eval_javascript` 能力面） | 红测试：求值/console 替代输出/超时 watchdog/死循环收口；开关默认关零影响 | 新增 `IOSJsSandboxEngineTests`；`IOSToolLoopGuardTests` 不回归 |
| P3-b | tools 嵌套桥（双端）+ 白名单 + 审批/账本继承 | 嵌套 `search_web` 账本双记录；需审批工具嵌套触发审批暂停；白名单外工具调用返回可读错误 | 新增 `IOSExecNestedToolTests`；Android `AgentToolDispatcher` 相关测试 |
| P3-c | cell + wait + store/load + 持久化 + 后台约束 | yield/wait/terminate 三路径契约；进程死亡 cell 标记 interrupted；预算耗尽诚实失败 | 新增 `IOSExecCellTests`；`IOSChatBackgroundSuspensionTests` 不回归 |
| P3-d | `ALL_TOOLS` 发现（接 P0 元数据）+ 文案打磨 + 安全审查 | deferred 工具经 `ALL_TOOLS` 可发现可调用；安全清单逐项核对（无 fs/网络/导入、资源上限、审批继承） | 上述全部 + `IOSParityRedLightTests` |

### P3.5 风险

- **JSC 终止语义**：无公开强杀 API 是最大技术风险，watchdog + context 重建方案需要真机验证（模拟器证据不足，明确标注待真机）。
- **模型写 JS 的质量方差**：弱模型可能写出低质量编排脚本反而浪费轮次。缓解：默认关、逐助手、描述文案给 2-3 个 few-shot 模式（搜索+聚合、多工具并行 `Promise.all`）。
- **与 `eval_javascript` 的关系**：Android 既有工具保留不动（行为兼容），`exec` 是新工具；稳定后评估是否合并（不在本计划）。

### P2 完成记录（2026-08-08）

- **P2-a 记忆 polluted**：`ConversationMemoryMode` 三态挂 KMP `Conversation`（缺省 ENABLED 兼容旧 JSON，不动 Android `ConversationEntity`）；iOS 置位于 `messagesByFinishingToolCall`（search_web/scrape_web/mcp_call/mcp__* 的**成功**输出才标，失败/审批拒绝不标；顺带修复：MCP 失败输出从纯文本改结构化 `toolFailureJSON`，时间线失败芯片与污染判定同步修正）；`updateMemoryMode` 锁内原子 RMW（POLLUTED 唯一出口是 ENABLED 复位，P→DISABLED 显式拒绝）；设置页「受外部内容影响的会话」小节 + 复位（UI 细节过 checker 专项）；Android 文件级 polluted 集合 + `extractAfterConversation` 前置 gate（无 SDK 未编译验证）；召回注入不受 polluted 影响（污染的是会话作为抽取源的资格）。
- **P2-b lastUsedAt**：前提修正——召回注入标记接线已存在于已提交代码（`58b473837`），本轮只补去抖（注入集合变化才写盘，persist 成功后才记录去抖状态）+ 测试；Android 侧查证已写（`MemoryRecallStore.buildPrompt → touchMemories`）。
- **P2-c citation 隐藏标记**（实验性）：`<amber-mem-cite>` 流式状态机剥离（字面不嵌套、跨 chunk 缓冲、EOF auto-close 提取、相似前缀不吞字；渲染管线零改动——剥离在 `dispatchStream.onChunk` 进 event sink 之前）；前后台四终结边界统一 flush（后台引擎经可选 `citationTracker` 接线，P1 子线程路径覆盖）；引用标记经 `markUsed(force: true)` 绕过同集去抖（与召回侧标记语义分层）；memory-context 注入一行英文引导。模型遵循率预期诚实：有则增值、无则无害（无标签流字节级一致有测试锁定）。

### P3 完成记录（2026-08-08）

- **P3-a exec 沙箱**：JavaScriptCore（每求值新 VM+context+独立串行队列，失控脚本只毒化自己）；console shim 捕获（对齐 Android 前缀格式）；无 fs/网络/fetch/require/process；超时/取消 abandon 语义（JSC 无公开终止 API，CPU 风险注释 + 真机验证清单）；复核加固：pristine `JSON.stringify` 双处捕获（console shim + 结果序列化）、取消先于队列启动不执行脚本；`execJavaScriptEnabled` 设置开关默认关（声明/分类/执行三重 gate）；审批照 Android eval_javascript（needsApproval=false，P3-b 嵌套 gated 工具各自审批）。
- **P3-b 嵌套 tools 桥**：JS `tools.x()` 同步阻塞桥（信号量；JSC 无事件循环，无 Promise.all——描述明示）；白名单 = 当轮 `visibleTools()` − 排除集（exec/编排六工具/tool_search/ask_user，单一静态源）；嵌套走与顶层完全相同的分类/审批/账本链（审批暂停期间 JS 线程阻塞、批准后恢复，`exec-nested-` toolCallId 前缀 provenance）；死锁逐队列论证成立、teardown 全路径（`clearPendingApprovals`）无 JS 线程泄漏。Android 按逃逸条款未做（无 exec executor、审批在循环层、QuickJS runBlocking 死锁风险、无 SDK——4 个已分析阻塞点）。
- **P3-c cell 生命周期**：`wait` 工具（完成/terminate/超时三路径 + clamp [1s,60s]）；actor 注册表（单写者、read-once、并发上限 4、16 未读终态驱逐兜底）；exec 超时 yield 返回 "Script running with cell ID ..."（codex 文案）；`store`/`load`（会话作用域共享、单 key 64KB/总 1MB、sidecar 原子写空删文件）；冷启动 sweep Running→interrupted（不假 completion）；terminate=abandon+标 Terminated（JSC 限制诚实语义）；wait 天然占 `maxToolResumeCount` 预算（无新预算机制）；yield 后嵌套 tools 经 gate 诚实报错（不绑死 run）。
- **P3-d ALL_TOOLS + 安全审查**：`ALL_TOOLS` 冻结注入（与白名单同源的 `{name,description}`，Object.freeze + defineProperty 只读，后台"可发现不可调用"诚实分离）；8 项安全审查逐条取证，发现并修复 3 个 GAP：`max_output_chars` 无硬上限 → clamp [1,100000] 强制执行、16 未读驱逐补测试、`wait` 显式钉死 sideEffect 分类（固化既有 fail-safe 语义）。

### 全线验收与遗留

- **门禁终态**：全量终跑 21 个 iOS 套件 + 5 个 KMP 模块 jvmTest 全绿（数字见 `docs/PROJECT_STATE.md` 当日报）；各阶段均经独立 checker 对抗复核（PASS / PASS_WITH_NOTES 收口）。
- **整体复核轮（2026-08-08）**：两路独立审查（跨阶段状态链路审计 + 全 diff 新鲜眼审查）收敛发现 1 严重 + 5 一般级跨阶段问题——全部修复并经 checker 终验 PASS（KMP 135/135 + iOS 176/176）：S1 P1-d 三工具未进生产声明组装点（生产不可达，已补 + 端到端契约测试）；M1 后台 drain/终态保存谱系漂移（id 去重）；M2 后台 executor 表冻结（按轮重建）；M3 子线程目录截断（桥全目录）；M4 role_assistant_id 生效（+ K/N parse 终止进程雷拆除）；M5 tools_list 声明+本地执行；M6 cell wait 取消感知。教训记录：逐阶段绿 ≠ 组合绿，声明组装链必须有端到端契约测试。
- **Android 后续专项**（本机无 SDK，全部标注未编译验证）：P0-b 声明组装与前缀路由接线（`GenerationHandler` + `AgentToolDispatcher`）、P1 消费点与编排工具、P2 置位/gate 的编译验证、P3 exec executor 与 QuickJS 桥（4 个已分析阻塞点）。
- **真机验证清单**：JSC 终止限制（失控脚本 CPU 风险）；后台 BGTask 内 wait/cell/mailbox drain 端到端；真实 provider 下 tool_search/spawn/exec 全链；真实 MCP server schema 多样性；会话列表徽标 UI 接线（VM 查询就绪）。
- **有界取舍（已接受并文档化）**：后台嵌套 tools 恒不可用（诚实错误）；yield 后嵌套 gate 关闭；子线程 `fullToolNames` 为父可见子集（已被整体复核 M3 修复为桥全目录，保留桥缺回退）；wait_agent 无 preempt-sampling；只读缓存切换窗口（毫秒级）；`countUnfinishedRuns` 死代码（记录不删）。

---

## 附 A. 全局风险登记表

| 风险 | 影响线 | 缓解 |
|---|---|---|
| Kotlin→Swift 互操作变形（sealed/默认值/保留字） | P0/P1/P2 | 每线第一步写最小互操作冒烟测试；必要时 KMP 加 facade |
| 账本计数不可信（Android 吞异常未修） | P1 | P1-e 前置修复，未修前并发限额按保守值硬编码 |
| 行为变更的 taste 评审（steer 排队 UI、排队消息可撤销） | P1-a | 实现前过 `amberagent-ios-taste` 设计门禁 |
| iOS 后台不保证运行时长 | P1/P3 | durable 先行 + 诚实状态表达，不承诺"后台保证运行" |
| 弱模型不使用新工具（tool_search/exec/编排工具） | 全部 | 全部默认关或阈值触发；描述文案借鉴 codex 原文思路；零模型依赖的兜底路径（如 P2-b） |
| 会话 JSON schema 演进（fork 字段、memoryMode） | P1/P2 | 缺省兼容 + KMP 序列化契约测试（旧 JSON 无损读取） |

## 附 B. 开工前复核清单（按仓库 AGENTS.md，以下事实须在动工时重新核对）

1. `feature/tools/api` 的 `ToolExposureState` 在 Shared framework 的 ObjC 导出形态（P0 第一行代码前）。
2. `SettingsStore.defaultChatMaxToolResumeCount` 当前值（复核时为 12，`SettingsStore.swift:97`）。
3. `ChatGenerationCoordinator.continueAfterToolResult` 与 `IOSAgentToolEngine.run` 的循环结构是否仍如复核时（P1/P3 注入点）。
4. Android `InProcessAgentRunner` 账本吞异常是否已被其他工作修复（P1-e 前置）。
5. `JsonConversationStorage` 写路径锁与 index 派生语义（P1 fork 与 mailbox 落盘的基线）。

## 附 C. codex 参考实现索引（深挖用，不照抄）

| 主题 | 位置（codex 仓库内） |
|---|---|
| 六个编排工具 spec 与原文描述 | `codex-rs/core/src/tools/handlers/multi_agents_spec.rs:67-100,194-350,757-886` |
| mailbox 相位状态机 | `codex-rs/core/src/state/turn.rs:42-55,218-230`；`session/input_queue.rs:85-176,233-265` |
| spawn/fork（hint 清洗、role 应用） | `codex-rs/core/src/agent/control/spawn.rs:80-97,382-585,587-847` |
| residency/限额 | `codex-rs/core/src/agent/control/residency.rs:17-26,98-147`；`execution.rs:44-104` |
| tool_search spec/handler/缓存 | `codex-rs/core/src/tools/handlers/tool_search_spec.rs:21-95`；`tool_search.rs:35-224` |
| ToolExposure 三面 | `codex-rs/tools/src/tool_executor.rs:17-99` |
| code mode exec/wait 描述模板 | `codex-rs/code-mode-protocol/src/description.rs:14-38` |
| cell 状态机与 dispatch broker | `codex-rs/code-mode-runtime/src/cell_actor/types.rs:99-125`；`core/src/tools/code_mode/delegate.rs:57-184` |
| 记忆 citation 解析 | `codex-rs/utils/stream-parser/src/citation.rs:11-30` |
| polluted 置位与排除 | `codex-rs/state/src/runtime/memories.rs:604-635`；`core/src/stream_events_utils.rs:132-157` |
| Guardian（备选参考，未列入本计划） | `codex-rs/core/src/guardian/mod.rs:1-12`；`review_session.rs:1072-1104` |
