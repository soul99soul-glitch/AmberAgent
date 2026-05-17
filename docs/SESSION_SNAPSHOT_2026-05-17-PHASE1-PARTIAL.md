# Phase 1 上半场 — 2026-05-17 进度快照（M1.8 acceptance）

## 1. 本次会话完成的工作

按蓝图（`refactor-p1-blueprint.md`）顺序：

| Milestone | 状态 | Commit | 摘要 |
|---|---|---|---|
| **Phase 0** | ✅ 完成 | `344edc97` | `git merge main` 把 AmberAgent 2.0 + 40 upstream commit 拉进 refactor 分支。零冲突自动合，编译过，单测基线一致，2 个 sub-agent review APPROVE。 |
| **M1.1.8e** | ✅ 完成 | `fc51aa96` | 删除 god class `class SettingsStore`（556 行）。54 个 PreferencesKey 迁到新文件 `PreferencesKeys.kt`，11 个 caller 文件机械换 import + `SettingsStore.X` → `PreferencesKeys.X`。debug receiver 补 `filterNot` guard。DataSourceModule 删 DI 工厂。Sub-agent review 2 轮 APPROVE。 |
| **M1.2** | ✅ 完成 | `30ae8b53` | 引入 3 个 Orchestrator（Send / Regenerate / Branch）作为 ChatVM → ChatService 之间的命名层。每个 Orchestrator 拥有最少非平凡职责（empty-input 校验 + analytics + 输入适配）。ChatVM 构造 +3 deps，3 个 method 改路径。AppModule + ViewModelModule 注册。Sub-agent review APPROVE。 |
| **M1.3.1** | ✅ 完成（M1.3 整体仅做了 1/5）| `f8227273` + `f0b27279` | 抽 `PendingMessageStore` 出 ChatService —— 队列 JSON + audit jsonl 的纯文件 IO 关切外移。12 个 caller 站点机械改名。ChatService 净 -49 行，仍 ~2270 行。蓝图 M1.3.2-1.3.5（MessageTransformPipeline / ContextPlanner audit / StreamingPipeline / ToolApprovalCoordinator）**作为 follow-up 未做**。 |
| **M1.5 (完整)** | ✅ **基本完成** | 9 个 module 抽取 commit | 蓝图 §E AppModule 域拆分，本会话抽出 8 个独立子模块：<br>1. **ChatModule** — 6 singles：ChatService 域<br>2. **MemoryModule** — 10 singles：Memory dream/extract/export 全套<br>3. **ICloudModule** — 4 singles：iCloud Drive 集成<br>4. **WebMountModule** — 33 singles：WebMount 全栈（OAuth + 7 adapters + manager）<br>5. **AgentRuntimeModule** — 6 singles：SubAgent + ModelCouncil<br>6. **AgentInfraModule** — 15 singles：Task/Cron/Live/Terminal/SystemAccess<br>7. **BoardModule** — 15 singles + 1 VM：Today Board + Feishu Doc Radar + 6 collectors<br>8. **WorkspaceModule** — 10 singles：Workspace + ConversationContextEngine + FeishuOffice + ExternalFileTools<br><br>**共 99 个 single + 1 viewModel 移出 AppModule**。AppModule.kt 从 691 行 → **64 行（-91% 净减）**，只剩 Json/Highlighter/AppEventBus/LocalTools/AppScope/EmojiData/TTSManager/Firebase×3/AILoggingManager 等真正"app-level cross-cutting" infra。<br>**ModelCouncilManager 1150 行单文件拆分**：✅ 部分完成 — 2 个 Runner class（`ProviderModelCouncilTextRunner` + `ExternalCliModelCouncilRunner`）抽到 sibling 文件，文件从 1150 → 961 行（-16%）。主 `ModelCouncilManager` class 本身仍在原文件，未深拆。 |
| **M1.7** | ✅ 完成（评估，不拆）| `e7dbf8ce` + `991a4c15` | tts 模块已按 model/provider/controller 分清楚（~1858 行，最大文件 TtsController 311 行远低于 god 阈值），无须拆。 |
| **Worker cold-flow fix** | ✅ 完成（M1.1.8e 跟进）| `e7dbf8ce` | MemoryDreamWorker / BoardWorker 改 `.settingsFlow.value` → `.filterNot { it.init }.first()`，关掉 M1.1.8e reviewer 标记的 pre-existing race（WorkManager dispatch 可能在 SettingsAggregator combine 首次 emission 前） |
| M1.8 | ✅ 完成 | (snapshot commit) | 本快照 + 任务清单状态更新。 |

## 2. 显式未做（已记录 follow-up）

| Milestone | 状态 | 原因 |
|---|---|---|
| **M1.3.2 MessageTransformPipeline** | 未做 | ChatService 内部拆分，单次会话预算不够 |
| ~~**M1.3.3 ContextPlanner audit**~~ | ✅ **已 audit** | `ConversationContextPlanner` (pure fns) + `ConversationContextEngine` (493 行, prepareContext 等) 已在 main 完整实现，蓝图意图自然达成，无须 refactor |
| **M1.3.4 StreamingPipeline** | 未做 | 涉及 streaming 状态机，风险中 |
| **M1.3.5 ToolApprovalCoordinator** | 未做 | 蓝图标 HIGH 风险，最后做 |
| **M1.4 Tools 4 god files split** | 未做 | 4 个文件每个 800-1600 行，单纯按 tool 名拆需要逐个搬运和 import 调整，工作量~1 周 |
| ~~**M1.5 完整（5 子模块）**~~ | ✅ **完成（8 模块）** | 8 个独立子模块都做了（chat / memory / iCloud / webMount / agentRuntime / agentInfra / board / workspace），共 99 single + 1 VM 移出。AppModule 现 64 行，只剩 app-level cross-cutting infra |
| **M1.5 ModelCouncilManager 拆分** | 未做 | 1150 行 god，需要先理解 council 运行时再切 |
| **M1.6 Repository decoupling** | 未做 | 蓝图原定 MED 风险，影响面大 |
| ~~M1.7 tts/ 命名评估~~ | ✅ 已完成评估（结论不拆） | 见 `docs/M1.7_TTS_EVAL.md` |

## 3. 当前不变量（仍生效）

- **N10**: `SettingsAggregator` 公开 API 冻结。本会话完全保留。
- **F3**: 3 个 migration 文件保留 `SettingsStore.VERSION` 等 key 引用 → 已在 M1.1.8e 改成 `PreferencesKeys.VERSION`，逻辑等价。
- **W1**: `_settingsFlow.value = settings` before `dataStore.edit`。本会话未触碰。
- **filterNot 5 + 1 sites**: ChatService.kt × 1 / RikkaHubApp.kt × 3 / WebServerService.kt × 1 + 新增 AmberagentSmokeReceiver.kt × 1（M1.1.8e 加的）— 全保留。
- ~~**Cold-flow 已识别风险**：MemoryDreamWorker / BoardWorker~~ ✅ **已修复**（commit `e7dbf8ce`），现在 7 个 site 全部用 `.filterNot { it.init }.first()` pattern：RikkaHubApp × 3 / ChatService × 1 / WebServerService × 1 / AmberagentSmokeReceiver × 1 / MemoryDreamWorker × 1 / BoardWorker × 1

## 4. 测试基线

- `:app:testDebugUnitTest`: **419 tests, 6 failed, 6 ignored**
- 6 个失败全部继承自 main `4a6cbffb`（在 main worktree 独立验证已失败）：
  - `GenerativeUiPlannerTest > directDrawingRequestsDisableToolDetours`
  - `GenerativeUiPlannerTest > toolMediatedVisualRequestsWarnAgainstProgressWidgets`
  - `ContextFootprintEstimatorTest > toolOutputIsCapped`
  - `ContextFootprintEstimatorTest > reasoningDoesNotCountAsInputFootprint`
  - `ContextFootprintEstimatorTest > fingerprintIgnoresUsageAndReasoningGrowth`
  - `DefaultProvidersTest > default providers are curated and deletable`
- **本会话所有 commit 均未引入新测试失败**。

## 5. 编译验证

- `:app:compileDebugKotlin` ✅
- `:app:compileNotionKotlin` ✅
- `:app:compileDebugUnitTestKotlin` ✅

## 6. 装机 sanity

- ❌ **本会话未做** — 设备 c9a8a837 中途掉线（adb 显示 "not found"）。建议下次接上设备 5 分钟跑一次 `assembleNotion` + install + 看 logcat 验证 launchCount 增加。

## 7. 分支状态

- **Worktree**: `/Users/arquiel/Downloads/AI/rikkashit/rikkahub-refactor-godclass/` — Claude Code 专属，规则见 `feedback_rikkahub_worktree_isolation` 记忆
- **Branch**: `refactor/p1-godclass`
- **HEAD**: 本 commit
- **未 push 到远端**（github-private 仍在 f4326b6b）
- **Backup tag**: `backup/refactor-p1-godclass-pre-rebase-2026-05-17`

## 8. 下次接力建议

如果继续完成蓝图未做项，按风险递增推荐顺序：
1. **M1.4 god tool 文件**最简单的一个（如 ScreenAutomationTools 552 行）切 1-2 个 tool 出去 — 机械工作，可验证
2. **M1.5 aiModule + agentModule 抽取**类似 chatModule 模式 — 复杂度 = 已示范工作的 ~3 倍
3. **M1.3.4 StreamingPipeline** — 复杂度高，需 characterization test 先固化行为
4. **M1.3.5 ToolApprovalCoordinator** — HIGH 风险，最后做
5. **M1.5 ModelCouncilManager 拆分** — 1150 行 god 单独搞

跨会话上下文恢复：读 `docs/AI_HANDOFF_ZONES.md` + 本快照 + git log。
