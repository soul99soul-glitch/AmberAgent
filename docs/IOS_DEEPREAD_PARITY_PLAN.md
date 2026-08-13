# iOS 深度阅读对齐 Android 实施计划

状态：**Active**（2026-08-14 定稿；P0/P1/P2 已落地并通过定点门禁，P3 为决策门）

## 结论先行

- 用户可复现症状：iOS 深读多次只生成 overview，没有时间轴 / 关键脉络 / 深度分析（各方立场）/ 扩展阅读等模块。
- 根因不是模型能力，是 iOS 管线结构：4 次独立 JSON 补全，任一阶段 throw / JSON 不可解析 / 缺字段即**静默跳过**，且提示词诱导整段重发合并 JSON、超 maxTokens 截断后不可解析；只要 overview 成功任务就标「成功」。
- Android 对等物 `DeepReadAgentRunManager` 是「预抓取 → 证据分桶 → LLM 文章规划 → 每段 supervisor 循环（带工具与 writer tool、超时、重试、自由文本兜底、每段 FAILED 状态）」。
- 计划分 P0–P3：P0 已收口（阶段鲁棒性 + 如实上报），P1 在不引入 agent loop 的前提下把单次调用管线的深度补齐，P2 补单段重试与超时，P3 是「工具型 agent loop」决策门，不进则不做。

## 现状事实（2026-08-14 核对）

### Android（对等目标）

- `app/.../deepread/DeepReadAgentRunManager.kt`：`generateStages` 顺序跑 OVERVIEW → NARRATIVE → ANALYSIS → EXTENDED_READING；每段 `runStageSupervisorLoop`（最多 2 pass，`withTimeout` 90/110/150/90s，缺写提醒，失败后 `tryFallbackAfterStageFailure` 自由文本兜底，未达 READY 标 FAILED）。
- `generateArticlePlan`（LLM 规划 angle / narrative_slots / analysis_questions / stakeholders / risk_or_uncertainty / required_source_ids，失败走 `researchHarness.fallbackPlan` 本地兜底）。
- `evidencePack.cardsFor(stage:)` 按段分桶（每段源数量上限 6/9/8/12，摘录上限分段）；`buildPrompt` 注入 Playbook（≤12k 字）、Article Plan、段证据包、既有正文、硬时间预算。
- writer tool 校验：overview summary ≥24 字（`OVERVIEW_SUMMARY_MIN_CHARS`）；narrative/analysis/diagram/links 各有 required 字段校验。
- 注意：docs/REVIEW_DEEP_READ_OPTIMIZATION_PLAN_V3.md 描述的 coverage→verification→finish tail 在**当前 Android 代码里并未实现**（`coverageReport` 参数恒定 nil，全仓无 `DeepReadCoverageReport` 构造点），不作为对齐目标。

### iOS（现状，2026-08-14 更新）

- `iosApp/iosApp/IOSBoardPersistence.swift` `IOSDeepReadDraftGenerator.generateViaLLMResult`：规划调用 + 4 阶段顺序调用，每段按 `IOSDeepReadArticlePlan` 分桶来源（≤6/9/8/12 条 × 1000/1400/1400/700 字摘录）+ 上一阶段合并 JSON + 每段超时（90/110/150/90s）；无 Playbook、无工具（agent loop 未引入，P3 决策门）。
- 结构体 `DeepReadStructures.swift`：analysis 已含 quotes；references 由扩展阅读阶段产出并渲染；diagram 由扩展阅读阶段可选产出。
- 任务模型 `IOSDeepReadTask` 有 missingSections（可选字段）；单段重试已落地（P2-a），整篇重跑仅作为兜底。

## P0（已完成 2026-08-14）

阶段鲁棒性 + 如实上报。已落地：

1. 每阶段失败自动重试 1 次，按失败类型注入纠正提示（调用失败 / 无法解析 / 缺字段）。
2. 截断 JSON 栈式修复（`repairTruncatedJSON`：补闭合括号/引号、去悬空逗号），修复成功不耗重试。
3. 提示词改「只输出本阶段新增字段，系统自动合并」（消除整段重发截断）。
4. analysis 阶段要求 3–5 个当事方/利益方立场；扩展阅读阶段补可选 diagram spec。
5. 每阶段 DEBUG NSLog（stage/attempt/错误/响应头）。
6. `GenerationResult.missingSections` → 任务持久化 `missingSections`（可选字段，旧 tasks.json 兼容）→ 详情页琥珀横幅「部分段落未完成 + 重新生成」+ 完成 toast。

门禁：`IOSDeepReadPipelineTests` 14/14；`IOSParityRedLightTests` DeepRead 5 项全绿（其余 3 项失败为既有基线，与本轮文件无交集）。

## P1：单次调用管线补齐深度（不引入 agent loop）

目标：P0 保证「模块出现」，P1 保证「模块内容接近 Android 深度」。**已完成 2026-08-14。**

- **P1-a 文章规划段（已落地）**：overview 前一次规划调用 `synthesizePlan`：输出 overview_angle / narrative_slots / analysis_questions / stakeholders / risk_or_uncertainty / required_source_ids（1-based 来源编号）；失败或不可解析走 `fallbackPlan` 本地兜底（照搬 Android fallback 文案），`IOSDeepReadArticlePlan.normalized` 填空并过滤非法编号、列表截断（6/8/8/8）。规划内容经 `buildStagePrompt` 的「Article Plan」节注入每个阶段（analysis 段拿到 stakeholders 与 analysis_questions）。
- **P1-b 分段证据分桶（已落地）**：`stageSourcesBlock` 按段选源（overview 6 / narrative 9 / analysis 8 / extended 12，摘录上限 1000/1400/1400/700，对齐 Android），required_source_ids 优先排序，总量仍 9000 字封顶。
- **P1-c analysis quotes（已落地）**：`IOSDeepReadAnalysis.quotes`（可选解码，兼容旧 JSON），阶段 3 schema 与指令要求 quotes(text+attribution)；渲染器 quote 卡 + 新增 `.quote` CSS；markdown 兜底同步。
- **P1-d 完成门闩（已落地）**：overview summary ≥24 字（Android `OVERVIEW_SUMMARY_MIN_CHARS` 对等）；门闩不过的内容**不入稿**（merge 前 gate，Android writer-tool 语义），不足即重试、仍不足该段计缺失。
- **P1-e references 渲染（已落地）**：阶段 4 schema 补 references；`sectionsHTML` 新增「参考来源」区块；markdown 兜底同步。

## P2：单段重试与超时

**已完成 2026-08-14。**

- **P2-a 单段重试（已落地）**：`generateViaLLMResult` 增加 `targetStages`（按段名过滤）与 `initialOutput`（以任务已存的 structuredJSON 播种合并上下文）；`IOSDeepReadLauncher.retry` 读取任务 missingSections + structuredJSON，缺失段非空时只重跑缺失段（Android runSection 对等），否则整篇重跑；协调器/runExistingTask 全程穿透。重试成功后 `complete` 清空 missingSections，详情页横幅自动消失。重试未成功（无模型/无可用来源/系统中断）时按 `IOSDeepReadPriorCompletion` 快照恢复上一稿，不销毁已生成内容。
- **P2-b 每段超时（已落地）**：`withTimeout`（Result 型 task group，取消子任务不会顶掉胜者结果）+ `synthesizeJSON` 超时参数；概览/叙事/分析/扩展 90/110/150/90s、规划 120s，`stageTimeouts` 可注入供测试；超时计入可重试失败并把原因写进重试提示。
- **P2-c 运行摘要持久化**：本轮不做（DEBUG NSLog 已够取证；避免任务模型再膨胀）。

## P3：工具型 agent loop（决策门，默认不做）

Android 每段模型可调用 search_web / scrape_web + writer tool 自主补源，iOS 目前只喂预抓取来源块。是否引入取决于 P1/P2 落地后真实 provider 证据：

- 门：真机连续 N 次运行仍出现「段落缺失且重试后仍缺」或「分析段明显空洞（<3 立场）」，且日志证明原因是来源不足而非解析/调用失败。
- 若开门：复用 `IOSAgentToolEngine` 在深读上下文跑带工具的单段循环（search/scrape 白名单 + 段 writer 工具 + 预算），并对齐 Android 的提醒/兜底/FAILED 语义。
- 成本：审批链、账本、后台执行期语义都要重接，工作量大；闭门时在计划末尾明确记录决策依据。

## 刻意不做

- coverage→verification→finish tail：当前 Android 亦未实现，等 Android 落地再对齐。
- 每 claim 的 evidence_urls 注册表与验真 UI：Android-only 基础设施，iOS 无对应展示面。
- Playbook 编辑面（Android 经聊天工具编辑 Playbook）：iOS 无该入口；关键规则已烘焙进各段指令，P1 不引入静态 Playbook 文件。

## 验证策略

- 已过门禁（2026-08-14）：`IOSDeepReadPipelineTests` **23/23**（plan 解析/注入/兜底、分桶上限与 required 优先、quotes/references 合并、24 字门闩不入稿、超时→重试、targeted retry 只跑缺失段且保留既有段落、失败重试恢复上一稿、既有鲁棒性契约）+ `IOSDeepReadStructuredRendererTests` **9/9**（quote/references 渲染与旧 JSON 兼容）+ `IOSParityRedLightTests` 深读 5 项全绿（62 项中 3 项失败为 chat 后台/工具租约/MiniApp 域既有基线，与本轮文件无交集）。
- 真机：DEBUG 包跑真实 provider，`log stream` 过滤 `[AmberDeepRead]`，验收「规划+4 段全成功、模块齐全（时间轴/关键脉络/图解可选/深度分析含≥3立场与引语/扩展阅读/参考来源）、缺段时横幅出现且单段重试有效」。
- 未通过真实 provider 验证前，不宣称与 Android 深度对齐。

## 关联文件

- iOS 改动面：`IOSBoardPersistence.swift`（生成器/store）、`DeepReadStructures.swift`、`IOSDeepReadStructuredRenderer.swift`、`DeepReadCreateView.swift`（launcher）、`BoardView.swift`（详情页）。
- Android 参照：`app/.../feature/board/hotlist/deepread/DeepReadAgentRunManager.kt`、`DeepReadSectionWriterTools.kt`；`feature/board/impl/.../deepread/DeepReadResearchHarness.kt`（证据分桶/规划）、`DeepReadModels.kt`（DeepReadArticlePlan 等模型）、`DeepReadProgress.kt`（阶段状态）。
