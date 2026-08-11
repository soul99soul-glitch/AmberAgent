# 小说创作 Agent 字段写能力改造 · 工作记录

> 时间：2026-08-11 ~ 2026-08-12
> 基线：`origin/feat/ios-provider-parity-claude @ 8e5a222d7`（开工前已 fast-forward 同步远端，含「一键根据前文生成本章计划草稿」）
> 状态：**已实现 + 三轮复核收口，全部未提交**；真机与真实 provider 未验证

## 一、起源与目标

用户提出产品方向：**让 agent 尽可能改动应用内一切以数据形式存在的内容**。盘点（四代理只读审计，结论存项目记忆 `agent-self-modification-surface-audit-2026-08`）发现四层缺口，其中用户亲痛点是：**小说创作域对 agent 全部只读**——连「帮我润色小说标题」都无法落盘，唯一写路径是 UI 按钮 → `DefaultNovelCreation` → reducer 事务。

**改造目标**：小说会话讨论循环内，agent 可直接修改项目字段；所有写入只走既有 reducer 事务，不新建平行写通道；不碰 pipeline 专属数据（正文/快照/候选稿）。

## 二、整体 Plan（按核实后的代码事实制定）

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 基建（run 上下文注入）+ `novel_rename_project` | ✅ 落地 |
| P1 | `novel_set_polish_preference` / `novel_upsert_upcoming_arc` / `novel_clear_upcoming_arc` | ✅ 落地 |
| P2 | `novel_revise_material`（资料卡新建/更新） | ✅ 落地 |
| P3 | `novel_propose_chapter_plan`（恒草稿，确认永远走面板人工） | ✅ 落地 |

**写入语义分级**（设计时固定）：
- 直接写：标题、润色偏好、下一弧（reducer 记 operation+event 可审计）
- 草稿写：本章合同（强制 `.draft`，复用 `proposeNextChapterPlanDraft` 语义）
- 不给：deleteMaterial、正文、快照、模式/模型策略

**关键代码事实（2026-08-11 核实 @ 8e5a222d7）**：
- executor 协议 `IOSToolExecutor`（`IOSAgentToolEngine.swift:52-58`），outcome 五态
- 讨论 transport 的 executor 工厂原为**无参闭包拿不到 projectID**（`NovelLiveModelAdapter.swift`）——唯一需要的基建改动
- 非 UI 调 perform 先例：`NovelChapterPlanProposalLifecycle.swift:101`
- 小说声明不走 `Tool.kt` 的 `iosToolDeclaration` 注册表，直接在 `makeParameters` 组装（仅小说作用域先例）

## 三、实现（双代理并行，按文件所有权切分零冲突）

### 代理 A · 核心实现
- **KMP**：`Tool.kt` 新增 6 个 `createNovel*ToolDeclaration()`（JSON schema + 英文描述），不进注册表/ToolSearch；`NovelProjectToolDeclarationsTest` 7 项契约
- **iOS 新文件**：`IOSNovelProjectToolExecutor.swift`——持 `DefaultNovelCreation` 弱引用 + projectID + branchID；JSON 解码 → 校验 → 翻译为 `NovelAction` → `perform`；成功返回「旧值→新值」人读回执
- **基建**：`NovelModelRequest`/`NovelLiveTransportRequest` 加可选 projectID/branchID；`startGenerationCore` 透传；executor 工厂改带 `NovelProjectToolRunContext`；`makeParameters` 加 `includeProjectTools` 门控（仅 discussion、非 GrokWeb）
- **守卫**：`novel_revise_material` / `novel_propose_chapter_plan` 在代笔相位 ∈ {writing/accepting/collecting/syncing/planning/revising} 时拒绝（对齐 UI `isGhostwriting`）

### 代理 B · 提示词
- 讨论模板 **v6→v7**：新增 PROJECT WRITE TOOLS 段（六工具契约 + 使用纪律：先收敛再一次写入、未明令时 rename/revise_material 先确认且与写入分轮、chapter plan 恒草稿须面板人工确认、代笔中拒绝、1-8 字标题轻指引）
- v6 全文归档进 acceptedVersions/systemText，旧 receipt 哈希校验不受影响

## 四、三轮复核与精准修复

### 第一轮（checker 对抗复核）→ FAIL，1 严重 + 3 一般
- **CRITICAL 自锁**：守卫查 `activeRuns` 把调用方自己的 discussion run（`.running`）当成「进行中的任务」→ 两个核心工具生产上必被拒。修：守卫只查代笔相位；补**真实 paused discussion run 回归锁**
- **N1**：已确认合同会被静默降级为草稿（reducer 只按 planID 判撞）。修：遇 `.confirmed` 拒绝并指引去面板
- **N3**：上限文案与执行层不一致。修：v7 补 preference ≤8000 / plan 各字段上限
- **N4**：声明与 executor 注册改共用同一谓词（`novelProjectContext != nil && discussionTransport != nil`），防悬空声明

### 第二轮（checker + UI 审查）→ PASS_WITH_NOTES + 2 高 3 中 1 低
- **D1（高）**：agent 能建 `decisionLog` 卡但 UI 四 tab 全不可见、编辑器无法保存——「写了就失踪」。修：四处（KMP schema/executor/prompt/测试）移出白名单，留作 pipeline 专用。**原则：agent 能写的数据必须 UI 可表示**
- **custom 卡更新（中）**：`.custom("魔法体系")` 具名卡被关联值判等误拦。修：更新保留既有显示名
- UI 几何逐项核查（错位/对齐/边距/大小）：**未发现问题**；问题全在数据可见性

### 第三轮（用户点名修剩余项，精准不过度设计）
- **R1+R2（刷新链路断）**：根因=agent 写入绕过 VM perform wrapper（唯一刷新路径）。修：`NovelCreation.mutationEvents()` 领域广播（每次成功 perform 发布 `{projectID, operationID}`，协议默认空流、8+ 测试替身免实现），VM 订阅后复用 `refreshCurrentSelection`（一处同刷列表+快照）
  - **过程教训**：首版未抑制回声→VM 自身写入的异步回流与其流程中间状态竞争，13 个 VM 测试翻红。修：VM 两个 perform 入口登记 operationID，订阅跳过回声
- **R3（会话流零工具痕迹）**：prompt 级修复（v7 补「写入后须在回复里说明改动，回执不进 UI」），不新建工具胶囊 UI
- **D2**：`custom_name` 可选参数端到端（新建 custom 卡可命名）
- **D3 判非 bug 不修**：run 中「字段禁用+预览拒显假数据+终态自愈」行为自洽

## 五、验证

| 门禁 | 结果 |
|---|---|
| 定点五套件（executor 17 / VM 49 / lifecycle 53 / adapter 27 / catalog 6） | **152/152 绿** |
| KMP `:ai-core:jvmTest` | **BUILD SUCCESSFUL** |
| 组装链端到端契约测试 | ✅ 真实走 `adapter.start` 断言 6 声明在列（S1 教训） |
| 回归 218 项 | 14 失败全在**既有基线集**（纯 HEAD syncFailed 1、SessionVM 1、wiring 源码断言 3——断言对象是用户并发 WIP 改的 NovelMaterialsView，本 diff 未触碰） |

**未验证**：真实 provider 下模型真实调用 6 工具；真机手感。

## 六、改动文件清单（全部未提交）

- KMP：`Tool.kt`、新 `NovelProjectToolDeclarationsTest.kt`
- iOS 生产：新 `IOSNovelProjectToolExecutor.swift`、`ChatToolRuntime.swift`、`NovelCreationComposition.swift`、`NovelGenerationLifecycle.swift`、`NovelLiveModelAdapter.swift`、`NovelModelAdapter.swift`、`NovelPromptCatalog.swift`、`NovelActions.swift`（协议+mutationEvents）、`NovelCreation.swift`（广播）、`NovelCreationViewModel.swift`（订阅+回声抑制）
- iOS 测试：新 `IOSNovelProjectToolExecutorTests.swift`、`NovelLiveModelAdapterTests.swift`、`NovelPromptCatalogTests.swift`、`NovelCreationViewModelTests.swift`
- 文档：`docs/PROJECT_STATE.md`（原地更新）

**边界说明**：工作区另有用户并发 WIP（连续性审计 scope 等 8 文件），本轮全程未触碰。

## 七、行为变化（用户视角）

在小说会话**讨论模式**里可直接下令：
- 「帮我想几个更好的标题，选一个换上」→ 候选 → 选定 → 落盘，列表即时变
- 「以后润色都走简练冷峻风」→ 写入润色偏好，下次整章润色生效
- 「把刚聊的往后三章记下来」→ 写下一弧（≤8 条 ×160 字）
- 「把刚定的世界观沉淀成设定卡」→ 新建/更新资料卡（custom 卡可命名；revision 链可恢复）
- 「把讨论的计划存成本章草稿」→ 落草稿，确认永远由你在面板点

**保护边界**：代笔推进中改资料/拟计划被拒并说明；已确认合同拒绝降级；未明令的改名/改卡 agent 会先问（提示词纪律）。

## 八、后续候选（按盘点缺口地图排序）

1. 主题包 agent 自创（缺 `theme_pack_import` 工具，格式已是纯 JSON）
2. cron iOS 接线（Android `AgentCronTools` 是声明蓝本；`reconcileOnStartup` 无生产调用者待救活）
3. persona/assistant 编辑（`updateCurrentAssistantParams` 写路径零调用者、UI 占位页）
4. DeepRead/议会房间 agent 发起
5. decisionLog 卡在 UI 补可见性后可重新开放进工具白名单
