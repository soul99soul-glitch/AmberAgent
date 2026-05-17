# Phase 1 拆分蓝图 v1

> 基于 2026-05-15 三个 Explore subagent 的深度调研
> 目标：消灭非 UI 的所有 god 文件，建立清晰边界
> 周期预估：3-4 周 ｜ 风险：中

## 1. 范围与不范围

### 在 Phase 1 范围内
- 拆 PreferencesStore.kt (1131 行)
- 拆 ChatService.kt (2261) + GenerationHandler.kt (943) 一起做
- 引入按需 Orchestrator（SendMessage / Regenerate / Branch）
- 拆 Tools 4 个 god 文件 (LocalTools 1336 / SystemAccessTools 1441 / FeishuOfficeTools 1107 / WebMountPrimitiveTools 1541)
- 拆 ModelCouncilManager (998)
- 拆 AppModule.kt (664) 按域分模块
- Repository 解耦（每个只依赖 DAO + 网络，不依赖其他 Repository）
- **tts/ 模块去留评估**（plan v2 误判后移到这里，本 Phase 评估，不一定拆）

### 不在 Phase 1
- namespace `me.rerere.rikkahub` 不动（Phase 3）
- DataStore key 名 / 深链 scheme / Intent action 不动
- UI 组件不拆（Phase 2）
- Rust / 栈升级（下半场）

---

## 2. 各 god class 拆分对照表

### A. PreferencesStore.kt (1131 行) → 7 个域 Prefs

| 子组件 | 字段数 | 行数预估 | 主要 Caller | 依赖 |
|---|---|---|---|---|
| UIPrefs | 6 (dynamicColor / themeId / developerMode / displaySetting / launchCount / sponsorAlertDismissedAt) | 150 | RouteActivity / Theme | 无 |
| SearchPrefs | 10 (searchServices / searchEnabledIds / 6 个 builtin bool) | 180 | SearchPicker | 无 |
| AgentPrefs | 1 顶层 (agentRuntime — 嵌套 19 子字段) | 200 | RikkaHubApp / Workers / Transformers | 无 |
| ExtensionPrefs | 15 (modeInjections / lorebooks / quickMessages / mcpServers / ttsProviders / webDav / s3 / webServer / backup / sync) | 280 | PromptVM / QuickMessagesVM | AssistantPrefs（ID 反向） |
| ProviderPrefs | 2 (providers / SEEDED_IMAGE_MODELS_V1) | 220 | ModelList / DefaultProviders | 无 |
| ChatPrefs | 16 (chatModelId / titleModelId / 5 个 model 选择 / 5 个 prompt / 1 favoriteModels / 1 modelGroupSessionDefaults / enableWebSearch) | 250 | ChatVM / TranslatorVM / ImgGenVM | ProviderPrefs（model 验证） |
| AssistantPrefs | 3 (assistants / assistantId / assistantTags) | 280 | AssistantVM / ChatVM | ChatPrefs / ProviderPrefs / ExtensionPrefs（聚合中枢） |
| **PreferencesStore（薄包装）** | aggregator | 100 | 兼容旧 caller | 上述 7 个 |

**总计**：1660 行（vs 现在 1131）—— 表面上多了 50%，但每个文件 < 300 行，可读性翻倍。

**Migration 分配**：
- V1Migration（MCP JSON 类型迁移）→ ExtensionPrefs
- V2Migration（UIMessagePart 类型迁移）→ AssistantPrefs
- V3Migration（QuickMessages 提取）→ ExtensionPrefs + AssistantPrefs 协作

### B. ChatService.kt + GenerationHandler.kt (合计 3204 行) → 5 个目标组件 + 瘦身版

| 子组件 | 行数预估 | 职责 | Risk |
|---|---|---|---|
| **ConversationStateHolder** | 200-250 | 对话状态 / pending messages / processing status / job 引用 | LOW |
| **MessageTransformPipeline** | 150-200 | input/output transformer 链统一管理 | LOW |
| **ContextPlanner** | 200-300 | 上下文窗口预算 / token 计算 / memory recall 注入 / 系统提示构建 | MED |
| **StreamingPipeline** | 250-350 | SSE → MessageStreamAccumulator → flush 节奏（合并 :ai 的 Accumulator） | MED |
| **ToolApprovalCoordinator** | 200-280 | 工具批准生命周期（decision → pending → approve/deny → execution）| HIGH |
| **ChatService（瘦身后）** | 400-500 | 会话生命周期 / 消息队列 / 路由（保留 god class 外壳，内部委托） | — |
| **GenerationHandler（瘦身后）** | 200-300 | 生成主循环（步骤编排），其他全委托新组件 | — |

**MessageStreamAccumulator** (:ai 模块 167 行) **不移动**，由 StreamingPipeline 在 :app 包装调用。

### C. 4 个 god Tools 文件 → tools/<category>/<ToolName>.kt

| 文件 | 行数 | 工具数预估 | 拆后每文件目标 |
|---|---|---|---|
| LocalTools.kt | 1336 | ~15-20 | <100 行/工具 |
| SystemAccessTools.kt | 1441 | ~15-20 | <100 行/工具 |
| FeishuOfficeTools.kt | 1107 | ~10-15 | <100 行/工具 |
| WebMountPrimitiveTools.kt | 1541 | ~15-20 | <100 行/工具 |

注册改为列表 / 注解扫描。

### D. ModelCouncilManager.kt (998 行) → 3 个组件

- `ModelCouncilRouter` — 路由策略
- `VoteAggregator` — 投票合并
- `ModelDispatcher` — 模型分发

为 Phase 3 `:feature:agent` 模块做准备。

### E. AppModule.kt (664 行) → 6 个域模块

- `networkModule` — OkHttp / Ktor / interceptors
- `aiModule` — Provider / GenerationHandler / Orchestrators
- `agentModule` — McpManager / ToolDispatcher / Workers
- `webMountModule` — InlineLogin / OAuth / Adapters
- `dataModule` — Database / DAO / Repository / Prefs
- `uiModule` — ViewModel factories（极少，多数 VM 用 viewModel{}）

### F. Orchestrators

| Orchestrator | 入参 | 返回 | 依赖 |
|---|---|---|---|
| SendMessageOrchestrator | conversationId, parts, mode | Flow<SendMessageEvent> | ConversationRepository / MemoryRepository / ContextPlanner / StreamingPipeline / ToolApprovalCoordinator / generationHandler |
| RegenerateMessageOrchestrator | conversationId, truncateAt, regenAssistant | Flow<RegenerateEvent> | ConversationRepository / ContextPlanner / generationHandler |
| BranchMessageOrchestrator | sourceConvId, branchAt | Conversation（**不调 generation**） | ConversationRepository / FilesManager / ContextPlanner |

**关键发现**：Branch 实际**不调 GenerationHandler**（只复制对话），所以 BranchOrchestrator 最轻。

---

## 3. 迁移顺序（Milestones）

```
M1.1 — PreferencesStore 拆分 (1-1.5 周)
├─ 1.1.1 UIPrefs (0 依赖，最先做练手)
├─ 1.1.2 SearchPrefs (独立)
├─ 1.1.3 AgentPrefs (独立但体量大)
├─ 1.1.4 ProviderPrefs (单向被依赖)
├─ 1.1.5 ChatPrefs (依赖 ProviderPrefs)
├─ 1.1.6 ExtensionPrefs (与 AssistantPrefs 互相依赖最复杂)
├─ 1.1.7 AssistantPrefs (聚合中枢，最后)
└─ 1.1.8 PreferencesStore 薄包装（向后兼容 API）+ 删除原 god class

M1.2 — Orchestrator 三件套引入 (3-4 天)
├─ 1.2.1 BranchMessageOrchestrator (最简，不调 generation)
├─ 1.2.2 RegenerateMessageOrchestrator
└─ 1.2.3 SendMessageOrchestrator
   → 把 ChatVM 改成调 Orchestrator，不直接调 ChatService 的 high-level method

M1.3 — ChatService + GenerationHandler 5 件套拆分 (1.5-2 周)
├─ 1.3.1 ConversationStateHolder (LOW, 先拆)
├─ 1.3.2 MessageTransformPipeline (LOW)
├─ 1.3.3 ContextPlanner (MED, 需 characterization test)
├─ 1.3.4 StreamingPipeline (MED, 整合 MessageStreamAccumulator)
└─ 1.3.5 ToolApprovalCoordinator (HIGH, 最后)
   → ChatService / GenerationHandler 各瘦到 < 500 行

M1.4 — 4 个 god Tools 文件拆分 (1 周)
├─ 1.4.1 LocalTools → tools/local/<ToolName>.kt
├─ 1.4.2 SystemAccessTools → tools/system/<ToolName>.kt
├─ 1.4.3 FeishuOfficeTools → tools/feishu/<ToolName>.kt
└─ 1.4.4 WebMountPrimitiveTools → tools/webmount/<ToolName>.kt
   → 引入注册列表 / 注解扫描

M1.5 — ModelCouncilManager 拆 + AppModule 按域拆 (3-4 天)
M1.6 — Repository 解耦 (3-4 天)
   → 每个 Repository 只依赖 DAO + 网络，不依赖其他 Repository

M1.7 — tts/ 模块去留评估 (1 天)
   → 选项：A 保留作 me.rerere.tts；B 改名 me.rerere.amberagent.speech；C 拆迁到 :app
   → 评估完用最小改动落地，不强迫迁移

M1.8 — Phase 1 验收（assembleNotion + characterization test + review gate + 装机 + Perfetto 对比 baseline）
```

**总耗时**：~3-4 周，按 plan 估时一致。

---

## 4. Characterization Test 需求清单

每个 milestone 拆分前必须先写测试（保证拆完行为不变）：

| Milestone | 必要测试 | 现状 |
|---|---|---|
| M1.1 PreferencesStore | ChatPrefsTest / AssistantPrefsTest（验证 settingsFlow 兼容） | 现有 DefaultProvidersTest pre-existing fail（已知），需补域级测试 |
| M1.2 Orchestrators | SendMessageOrchestratorTest（mock 各依赖）/ RegenerateOrchestratorTest / BranchOrchestratorTest | 无 — 必须新建 |
| M1.3 5 组件 | StreamingPipelineTest（33ms flush 时序）/ ToolApprovalCoordinatorTest（race 竞态）/ ContextPlannerTest（系统提示稳定） | 无 — 必须新建 |
| M1.4 Tools | 每个工具一个独立 unit test 文件 | 部分有（ToolFailureTest 等）|
| M1.5 ModelCouncil | ModelCouncilRouterTest / VoteAggregatorTest | 无 |
| M1.6 Repository | 每个 Repository 独立测试 | 部分有 |

**Phase 1 测试覆盖目标**：所有 5 个新组件 + 3 个 Orchestrator 必须 100% 单元测试覆盖（mock 依赖）。

---

## 5. 风险点（CRITICAL 标记）

### M1.1 PreferencesStore
1. **AssistantPrefs 聚合中枢** — Assistant 引用 modeInjectionIds / lorebookIds / mcpServers / quickMessageIds / chatModelId 跨 5 个 Prefs，拆分时 ID validation 复杂
2. **V3Migration 跨域** — QuickMessages 从 Assistant.quickMessages 提取到全局，需要 AssistantPrefs + ExtensionPrefs 同步迁移
3. **Image Model Seeding 标志** — SEEDED_IMAGE_MODELS_V1 驱动 per-load backfill，必须跟 ProviderPrefs 紧绑定
4. **settingsFlow 兼容性** — 现 15+ ViewModel 订阅，必须保留聚合 StateFlow

### M1.3 ChatService + GenerationHandler
1. **取消传播脆弱** — ChatService 手握 Job，GenerationHandler 无 cancel 权限。拆分后 ToolApprovalCoordinator + StreamingPipeline 必须显式 `ensureActive()`
2. **状态泄漏** — ConversationSession 强持 Conversation 树，旧对话内存不释放。ConversationStateHolder 分离后需独立生命周期
3. **工具执行并发竞态** — pendingTools.isEmpty 检查与 executeBatch 之间非原子，需 Mutex 保护
4. **WebSocket/SSE 连接泄漏** — OkHttp 清理依赖 onCompletion，cancel 不一定及时

### M1.4 Tools
1. **工具注册顺序** — 现有顺序可能有隐式依赖，注解扫描后顺序需稳定
2. **跨 Tools 共享 helper** — 4 个 god 文件可能有重复 helper，拆分时去重 vs 保留

---

## 6. Phase 1 验收标准（M1.8）

- 数据层单文件 < 500 行 ✓
- 无 Repository → Repository 调用 ✓
- 关键 Orchestrator 有单测 ✓
- `:app:assembleNotion` 通过 ✓
- 端到端冒烟（发送 / 重试 / 工具调用 / 分支切换 / 历史 / 设置）无回归 ✓
- review subagent gate ✓
- Perfetto 重录 baseline 与 2026-05-15 数字对比，**无性能回归**（冷启动 ±10% 内 / jank 率持平或更低）
- 装机验证升级路径无数据丢失 ✓

---

## 7. 下次 session 接手指南

下次接 Phase 1 工作时：
1. 读这份蓝图
2. 跑 `git log --oneline | head -5` 看 main 状态（确认是否在 `e33a5e08` 或后）
3. 切到 `refactor/p1-godclass` 分支
4. 按 M1.1.1 开始（UIPrefs 拆分，0 依赖，最稳）
5. 每个 milestone 完成做：build + characterization test + 装机 + review subagent gate
6. M1.1 / M1.2 / M1.3 各自完成后 squash 到 `refactor/p1-godclass`（不直接到 main），整 Phase 1 完成后再 ff-merge 到 main

**估时检查点**：M1.1 完成预计 1-1.5 周；如果实际超 2 周，停下来 push back 评估范围是否合理。

---

## 8. 待用户决策

蓝图给出后，请确认：

1. **整体方向**：8 个 milestone 是否合理？周期估计是否接受？
2. **优先级**：是否要先做 M1.1 PreferencesStore，还是先做 M1.2 Orchestrator？（后者也合理因为引入新抽象层）
3. **tts/ 模块**：M1.7 评估选项（A 保留 / B 改名 / C 拆迁）—— 你是否有偏好？
4. **测试投入**：Phase 1 测试覆盖目标"5 个新组件 + 3 Orchestrator 100% 单测"是否接受？或者降级为"关键路径覆盖"？
5. **风险点缓解**：5 个 CRITICAL 风险点中，AssistantPrefs 聚合中枢和工具并发竞态最重要——是否需要预先做"试拆"小实验来验证可行性？
