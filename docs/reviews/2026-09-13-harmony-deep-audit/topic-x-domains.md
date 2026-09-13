# topic-x-domains — 扩展专题域(DeepRead / Search / Council / Novel / Memory)与 H 稳定性

- 审查对象：分支 `harmony-preview`，HEAD `bdce551dd373f3d7e4c393b194997cae19289c40`，工作树（含未提交改动）。业务代码只读。
- 本报告只读定位/完整阅读/跨模块追踪/隔离验证的等级均在每条候选与覆盖表中逐项标注。
- 隔离验证：`docs/reviews/2026-09-13-harmony-deep-audit/probes-h/`（真实 `.ts` 域逻辑 + 注入 mock，无真实模型/网络/文件/用户数据）。
- 历史资料：已重读并逐条在当前实现重新确认 `docs/plans/2026-08-25-harmony-review-round3.md`、`...-fix-plan.md`、`.workflow/harmony-novel-completion/final-report.md` 与 `results/C6/C7-integration.md`。

---

## 0. 结论摘要

| ID | 域 | 严重度/置信度 | 一句话 |
|---|---|---|---|
| H-1 | DeepRead research | P1 / 高 | `buildEvidencePack` 用「按 STAGE_EVIDENCE_MAX 连续切片」而非 Android 的「全量按 tag 打分各段独立挑选」，MAX_SOURCES=12 时常态下 ANALYSIS/EXTENDED_READING 证据为 0，段落无据可依。 |
| H-2 | DeepRead agent/scheduler | P2 / 高 | 预抓（含 article plan 前的 collect/prefetch）不吃 signal；取消落在预抓窗口时仍持久化 `COLLECTING`/`WRITING+RUNNING`，并以 rejection 逃出 `scheduler.run` 的 `RunResult` 契约。 |
| H-3 | DeepRead agent | P2 / 高 | 空预抓不再 hard-fail，而是伪造 `sourceId='fallback-title'` / `topic://<id>` 的"来源"继续跑；与文件头自述 P1-9 和 Android 硬失败契约相反，可产出零真实来源的"完成"文章。 |
| H-4 | DeepRead research(entry) | P2 / 高 | `SearchRegistry` 无视 `searchBuiltin*` 开关、恒开 DuckDuckGo/HackerNews/Jina 等外部源；构造期异步加载与 `enabled()` 同步读存在就绪竞态。 |
| H-5 | Council | P3 / 高 | `outputBudgetChars` 只在本地 `truncate`，从不换算为请求 `max_tokens`，服务端仍可超发。 |
| H-6 | Council | P3 / 中 | 运行结束的 `finally` 删除 `listeners/liveText`；此后再 `subscribeLive` 的订阅永远留在 map（只增不减）。 |
| H-7 | Novel | P3 / 高 | `setChapterDiscarded` / `restoreChapterVersion` 用 `Date.now()` 而非注入的 `nowMs()`，时钟注入不一致。 |
| H-8 | Memory(entry store) | P3 / 中 | `readJsonArray` 对 IO 异常仍静默 `return []`，且读路径不在 `enqueueWrite` 队列内；极端 IO 错误下有"空快照覆盖"与撕裂读窗口（parse 损坏已由 quarantine 修复）。 |
| H-9 | Novel(entry scheduler) | P3 / 中 | `novelPolishWorkId` 为 32 位字符串哈希，不同 jobId 可碰撞后 `startWork` 互相覆盖/取消。 |
| H-10 | DeepRead scheduler | P3 / 高 | 被 REPLACE 抢占的旧轮 `finally` 无条件 `notifier.cancelRunning(topicId)`，会取消正在运行的新轮通知。 |

---

## 1. 文件覆盖表（本 owner 范围）

等级：`完整阅读` / `部分阅读(注明)` / `仅定位`。

| 文件 | 等级 | 关键点 |
|---|---|---|
| deepread/src/main/ets/agent/scheduler.ts | 完整阅读 | runningJobs/token REPLACE、admission lock、withTopicLock、abort/waitForAbort、notifier |
| deepread/.../agent/run_manager.ts | 完整阅读 | createRunContext 降级来源、markCollecting、generateStages、finishIfPossible、run/runSection |
| deepread/.../agent/supervisor_loop.ts | 完整阅读 | withTimeout/StageAbortSignal、per-pass abort、tryFallback、markFailed |
| deepread/.../agent/agent_loop.ts | 完整阅读 | stopReason、budget hideTools、resumable tools |
| deepread/.../agent/tool_execution.ts | 完整阅读 | resolveDecision/审批、并行执行按引用回写、status 工厂 |
| deepread/.../agent/section_writer_tools.ts | 完整阅读 | 7 tool、READY 判定、executeFinish 三门闩锁、fallback 段落 |
| deepread/.../agent/try_fallback.ts | 完整阅读 | fallback → BASIC READY |
| deepread/.../research/source_prefetcher.ts | 完整阅读 | 36s→15s wall budget、LRU、seed fetch、MAX_SOURCES 去重 cap、withDeadline |
| deepread/.../research/url_filter.ts | 完整阅读 | inet_aton 全形态 IPv4、IPv4-mapped、userinfo 剥离、私网 gate |
| deepread/.../research/search_provider.ts | 完整阅读 | Tavily 多 query 合并 |
| deepread/.../research/evidence_pack.ts | 完整阅读 | 分桶、cardsFor、requiredSourceTarget |
| deepread/.../research/search_merger.ts | 完整阅读 | round-robin interleave + MAX_SEARCH_RESULTS |
| deepread/.../research/article_plan.ts | 完整阅读 | parse/normalize/fallbackPlan |
| deepread/.../domain/enums.ts | 完整阅读 | 超时/上限常量（MAX_SOURCES=12、STAGE_EVIDENCE_MAX=6） |
| deepread/.../council/{manager,validator,semaphore,models,runner,prompts}.ts | 完整阅读 | 编排/校验/限流/裁决解析/超时与取消 |
| deepread/.../novel/repository.ts | 完整阅读(2717 行分 3 段) | CAS/ledger/staging swap、ghostwrite/polish job、分支/undo |
| deepread/.../novel/creation.ts | 完整阅读 | run 单飞、collectWithEvents、checkpoint、driver、recover |
| deepread/.../novel/{polish,polish_runner,ghostwrite,ghostwrite_runner,mutations}.ts | 完整阅读 | stage 机、claim/lease、receipt、review 绑定 |
| entry/.../pages/DeepReadArticlePage.ets | 完整阅读 | loadArticle/observe/runSection/取消/确认门 |
| entry/.../platform_impl/RdbRepository.ets | 完整阅读 | mutation tail、section update RMW、observe |
| entry/.../platform_impl/OpenAiCompatibleAiClient.ets | 完整阅读 | 非流式 tool loop、abort、空 key 回空 |
| entry/.../di/AppContainer.ets | 部分阅读：容器/DeepRead scheduler 装配/council/novel/subagent | DeepReadRunRepositoryAdapter hydrate、collectRun auto-approve 全开 |
| entry/.../platform_impl/{NovelWorkspaceService,NovelPolishWorkScheduler,NovelPolishWorkAbility,NovelChatRunAdapter,ModelCouncilRunner}.ets | 完整阅读 | WorkScheduler 壳、单章推进、runner 空 key 抛错 |
| entry/.../pages/{CouncilPage,CouncilRunPage}.ets | CouncilPage 完整；CouncilRunPage 1–160 | 持久化 setting→makeRuntimeSetting、live 订阅/poll |
| entry/.../pages/NovelWorkspacePage.ets | 部分阅读：生命周期/poll/attachRun/工作区锁 | unsub、reload token、ghostwrite/polish poll |
| entry/.../platform_impl/SearchRegistry.ets | 完整阅读 | 多 provider 构造与异步加载 |
| entry/.../pages/HotListSourceSettingsPage.ets | 完整阅读 | `source_<id>` 持久化已修 |
| entry/.../platform_impl/MemoryStore.ets | 部分阅读：read/write/touch、candidate DAO、dream DAO | 原子写 + quarantine、enqueueWrite、排序 |
| chat/.../chat/memory_extractor.ts | 完整阅读 | 门序列、单飞、auto-write、float 严格解析 |
| chat/.../chat/memory_dream_ops.ts | 完整阅读 | plan/apply/dismiss 串行、候选操作 |
| chat/.../chat/memory_dream_run.ts | 完整阅读 | 夜窗延迟、worker 重排 |
| chat/.../chat/memory_dream_plan_store.ts | 完整阅读 | savePending 原子 replace、decode 严格枚举 |
| chat/.../search/search_aggregator.ts | 完整阅读 | canonicalize、标题别名、状态 |
| chat/.../search/search_orchestrator.ts | 完整阅读 | 变体/内置源/状态/webview |
| chat/.../search/search_tools.ts | 部分阅读 | raceAbort signal 贯穿 |
| chat/.../search/html_dom.ts | 部分阅读（解析器修复区） | 引号感知 findTagEnd、`<` 文本判定 |

---

## 2. 候选（含准确行号、调用链、根因、验证）

### H-1 Evidence Pack 连续分片导致后段证据饥饿（P1 / 置信度高 / 隔离验证）

- 位置：
  - `harmony/deepread/src/main/ets/research/evidence_pack.ts:104-115`（credibility 排序后 `sorted.slice(idx, idx+STAGE_EVIDENCE_MAX); idx += STAGE_EVIDENCE_MAX`）
  - `harmony/deepread/src/main/ets/research/evidence_pack.ts:136-167`（`cardsFor` 的 planned 为空时回退到该 stage bucket）
  - `harmony/deepread/src/main/ets/domain/enums.ts:120`（`MAX_SOURCES = 12`）、`:130`（`STAGE_EVIDENCE_MAX = 6`）、`:131`（`DEFAULT_STAGE_EVIDENCE_TARGET` 定义但未被 evidence_pack 使用）
  - `harmony/deepread/src/main/ets/research/source_prefetcher.ts:162-170`（去重后 cap `MAX_SOURCES`）
  - `harmony/deepread/src/main/ets/research/article_plan.ts:31-39`（`fallbackPlan` 的 `stageSourceIds` 直接取同一批 bucket）
- 调用链：`scheduler.run → run_manager.run → generateStages → createRunContext → buildEvidencePack` →`runStageSupervisorLoop`（`supervisor_loop.ts:238 cardsFor(...)`）→ `buildPrompt/appendEvidenceCards`。
- 根因：这是「分区（partition）」而非 Android 的「各段从全量卡池按 tag 打分独立挑选」。
  - Android 事实源 `feature/board/impl/src/main/kotlin/app/amber/feature/board/hotlist/deepread/DeepReadResearchHarness.kt:227-242` `distributeStageCards` + `:244-300` `pickForStage/stageScore`：每个 stage 从**全量** `cards` 里按 `stageScore` 取 `target`（>6 时 `DEFAULT_STAGE_EVIDENCE_TARGET=5`），并用 `alreadyUsed` 只影响打分、不影响可见性。
  - Harmony 的 `cardsByStage` 一旦被切成 6 条/段，`cardsFor` 回退就只看到该段切片；`fallbackPlan`/`normalizePlan` 的 `allIds` 也只覆盖已切片段。
- 复现（`probes-h/probe-evidence-pack.ts`，真实 evidence_pack + enums，输入合成 DeepReadSource）：

  ```
  n=4   bucket[OVERVIEW=4 NARRATIVE=0 ANALYSIS=0 EXTENDED_READING=0]
  n=6   bucket[OVERVIEW=6 NARRATIVE=0 ANALYSIS=0 EXTENDED_READING=0]
  n=12  bucket[OVERVIEW=6 NARRATIVE=6 ANALYSIS=0 EXTENDED_READING=0]
  n=18  bucket[OVERVIEW=6 NARRATIVE=6 ANALYSIS=6 EXTENDED_READING=0]
  ```
  （`cardsFor` 结果与 bucket 完全一致，证明回退路径看不到被丢弃的卡。）
- 影响与前提：预抓 cap 为 12，因此**最常见区间（≤12 条来源）下 ANALYSIS 与 EXTENDED_READING 恒为 0 证据**，NARRATIVE 在 ≤6 时也为 0。三段 writer 只能靠模型自由发挥，`buildPrompt` 会出现"本段没有可用证据（理论上不会到这里）"。不丢数据，但直接违反"每段基于证据写作"的产品契约，并抬高幻觉率。
- 现有保护为何不成立：`STAGE_EVIDENCE_MAX` 只做上限，不做跨段复用；`normalizePlan` 的 `mergeWithFallbackIds` 只能从 `allIds`（即已切片集合）里补，无法恢复被丢弃的卡。
- 最小修复：改为 Android 语义——`buildEvidencePack` 保留全量 `cards`（或在 pack 内加 `allCards`），`cardsFor` 在 planned 为空时从**全量**按 stage 相关性排序取 `STAGE_EVIDENCE_MAX`，并让 `fallbackPlan.stageSourceIds` 同样基于全量。保留现有 `cardsByStage` 仅作兼容。
- 回归测试：在 `harmony/deepread/src/test/evidence_pack.test.ts` 增加"≤12 来源时每段 `cardsFor` 非空""全量池跨段复用且 distinct"断言；现有测试 `buildEvidencePack: distributes across stages by STAGE_EVIDENCE_MAX chunks`（`evidence_pack.test.ts` 第 88 行起）恰好**钉住了错误行为**，需同步改。
- 历史关系：round3 报告未提此项（此前只审到 prefetch 预算/Tavily 单 query）；属新发现。`evidence_pack.ts:3-6` 自认是 "MVP(简化版)"，本报告把它从"已知简化"升级为实证 P1。

### H-2 取消落在预抓窗口：持久化中断态 + rejection 逃出 RunResult 契约（P2 / 置信度高 / 隔离验证）

- 位置/调用链：
  - `scheduler.ts:103-106` `abort(topicId)` → per-job controller；`runImpl:147` 把 `signal` 传给 `run`。
  - `run_manager.ts:94` `deps.prefetcher.collect(topicId, topicTitle, seedUrl, force)` **不接受 signal**（`source_prefetcher.ts:107-112` collect 签名无 signal），因此预抓期间无法取消。
  - `run_manager.ts:168-170` `markCollecting` 持久化 `generationPhase:'COLLECTING'`。
  - `run_manager.ts:231-236` `runStage` 先持久化 `WRITING + section= RUNNING`，再进 `supervisor_loop.ts:258-262` 检查 `ctx.signal.aborted` 并 `throw new Error('aborted')`。
  - `run_manager.ts:208-210` `isAbortError` → 原样重抛；`scheduler.ts:138-160` `try { await withTopicLock(...) } finally {…}` **无 catch** → `scheduler.run` 以 rejection 结束，违反其 `Promise<RunResult>` 契约。
- 复现（`probes-h/probe-abort-prefetch.mts`，真实 scheduler + run_manager，mock prefetcher/aiClient）：

  ```
  running before abort = true
  saves = ["COLLECTING:-","WRITING:RUNNING"]      // 取消后仍写入仓库
  isRunning after = false
  result = {"threw":"Error: aborted"}              // 不是 {ok:false,error:'aborted'}
  ```
- 影响与前提：用户在"收集来源/规划"窗口点取消后，(a) 仓库留下 `COLLECTING`/`WRITING+RUNNING`，`detectInterrupted` 会判为中断态，UI 需靠 `cancelled` 标志掩盖；(b) `DeepReadArticlePage.loadArticle` 走 `.catch` 分支（页面内被吞），但任何按契约 `if (!result.ok)` 判定的调用方会拿到 rejection（未处理拒绝风险）。
- 现有保护为何不成立：`waitForAbort` 的 5s 兜底只解决并发 REPLACE；`scheduler.runImpl` 没有把 abort rejection 归一成 `RunResult`；预抓层注释自认 "signal 贯穿 run → supervisor → collectRun"，实际**没有覆盖 prefetch 与 generateArticlePlan**。
- 最小修复：给 `SourcePrefetcher.collect` 与 `createRunContext` 增加可选 signal（或至少在 `generateStages` 的 `createRunContext` 前后各查一次、并在 `scheduler.runImpl/runSectionImpl` 捕获 `isAbortError` 归一为 `{ok:false, error:'aborted', output: 当前仓库态}`）。
- 回归测试：`scheduler_signal.test.ts` 增补"预抓窗口 abort → 返回 aborted 且不残留 RUNNING 段状态"。
- 历史关系：round3 未覆盖；与 round3「deepread run_manager/repository 契约分叉」属同一族（契约在装配边界失真），本次给出可复现证据。

### H-3 空预抓伪造 fallback-title 来源，与 hard-fail 契约矛盾（P2 / 置信度高 / 完整阅读）

- 位置：`run_manager.ts:91-111`（`catch(_e){}` 吞预抓异常；`length===0` 时构造 `sourceId:'fallback-title'`, `url: seedUrl ?? topic://<id>`, `evidenceText: topicTitle`）。
- 矛盾点：同文件 `run_manager.ts:8` 头注释明确 "空 prefetch → hard-fail(P1-9)"，而 `:98-111` 注释改为 "降级…而非 hard fail"。两处契约自相矛盾，当前**实现是降级**。Android 基准（round3 报告 P1-9 记载）为 `Result.failure`。
- 影响与前提：未配置任何搜索源、或所有源失败/超时时，Harmony 仍会走 plan→4 段写作；模型只要写出 ≥24 字 summary 即标 `OVERVIEW READY`，`executeFinish` 可把文章落 `COMPLETE`。用户得到一篇**没有任何真实来源支撑**的"深度阅读"，且 `sourceCountLabel` 显示来源为 0，UI 无显式"证据缺失"警示。`url='topic://…'` 会被 `isHttpUrl` 过滤，不会进 references，因此更不显眼。
- 现有保护为何不成立：`createRunContext` 的空数组分支先于任何失败上报；`buildEvidencePack` 对 1 条 fallback 卡照常分桶；`runStageSupervisorLoop` 的 fallback 段落也会把 `sources`（含 fallback 卡）写入 references。
- 最小修复：恢复 Android hard-fail（返回 `{ok:false,error:'来源收集失败'}` 且不写 READY），若产品确需降级，则必须在 `DeepReadOutput.visualDiagnostics` 或独立字段标记 `evidenceEmpty` 并在 `DeepReadArticlePage` 顶部给出显式降级横幅。
- 回归测试：`run_manager.test.ts` 增加"prefetch 抛错/空 → ok=false 且无 READY 段"。
- 历史关系：round3 的 P1-9 记载为 hard-fail；当前实现已偏离该结论，属**历史结论需重新确认后判为回归**。

### H-4 DeepRead 来源注册表无视内置源开关 + 异步就绪竞态（P2 / 置信度高 / 完整阅读）

- 位置：`entry/src/main/ets/platform_impl/SearchRegistry.ets:328-387`；装配点 `entry/.../di/AppContainer.ets:129,130`（`createTavilyAndFallbackRegistry(httpClient, storage)`，**未传任何 settings**）。
- 事实：`constructor:337-341` 只放 NewsNow，随后 fire-and-forget `loadKeyedProviders()`；`:344-377` 无条件加入 `DuckDuckGoProvider`、`HackerNewsProvider`、`JinaProvider`（含免费外部网络源），仅对有 key 的付费源做 key 判断。全程不读 `searchBuiltinDuckDuckGoEnabled/searchBuiltinBingEnabled/searchBuiltinJinaEnabled/...`（这些设置在 `search_orchestrator.ts:177-192` 是生效的，但只作用于聊天搜索栈，不作用于 DeepRead 预抓栈）。
- 影响与前提：(a) 用户在"搜索设置"里关掉内置源/避免外网，DeepRead 预抓仍会访问 DDG/HN/Jina（隐私与网络策略不一致，且国内环境易超时占预算）；(b) 若 DeepRead 预抓在 `loadKeyedProviders()` 完成前触发，`enabled()` 只返回 NewsNow（单源召回骤降），这是同步读与异步初始化的状态竞态。
- 现有保护为何不成立：`enabled()` 无就绪信号；`source_prefetcher.ts:131` 直接 `searchRegistry.enabled()`。
- 最小修复：把 registry 初始化改为 `ready(): Promise<...>`（或 Promise 单例）后再供 prefetch 使用；把 `searchBuiltin*` 开关与 key 配置显式注入 registry（与 orchestrator 共用同一 settings 来源）。
- 回归测试：entry 无自动化测试；建议在 `harmony/deepread` 层加"provider 就绪前调用返回挂起/重试"的端口契约测试，entry 侧留静态接线说明。
- 历史关系：round3 #54 只处理了 HotListSourceSettingsPage（已修，见 `HotListSourceSettingsPage.ets:1-6,40,58`）；DeepRead 侧注册表是另一条链，本轮新发现。

### H-5 Council outputBudgetChars 不落 `max_tokens`（P3 / 置信度高 / 完整阅读）

- 位置：`deepread/.../council/manager.ts:364`（把 `outputBudgetChars` 传给 runner）、`:379` `truncate(res.text, seat.outputBudgetChars)`；`entry/.../ModelCouncilRunner.ets:55-59` 请求体只有 `model/messages/stream/temperature`，**无 `max_tokens`**。
- 影响：服务端可无限超发，客户端再截断 → 浪费 token/费用与延迟，且 `truncate` 后内容被硬切断（`prompts.ts:8-11` 直接 `slice + '…'`）。
- 最小修复：runner 侧按 `outputBudgetChars` 估算并写入 `max_tokens`（保守换算），或在文档/设置中明确"预算仅本地截断"。
- 历史关系：round3 P2 已记载（"outputBudgetChars 不换算 max_tokens"），当前**未修**，本次确认仍存；非假阳性。

### H-6 Council `subscribeLive` 在运行结束后残留订阅（P3 / 置信度中 / 完整阅读）

- 位置：`manager.ts:292-304` `finally` 删除 `liveText/listeners`；`manager.ts:201-217` `subscribeLive` 在任何时刻都会 `listeners.set(runId, perRun)` 新建 map。
- 场景：`CouncilRunPage.aboutToAppear` 在 run 已终结后进入（快速完成/从历史进入）→ 订阅写入的 `listeners[runId]` 永不被清理（finally 已执行），`runs` 又保留 run，形成只增不减的泄漏。
- 最小修复：`subscribeLive` 对已终结 run 立即回放并返回 no-op unsub（或终结时打 `completed` 标记，subscribe 检查后不入 map）。
- 历史关系：round3 #60（"council liveText/listeners 永不清理"）已修运行时清理，但**终结后新订阅**这一分支未覆盖，属残留边界。

### H-7 Novel 章节废弃/版本恢复的时钟注入不一致（P3 / 置信度高 / 完整阅读）

- 位置：`deepread/.../novel/creation.ts:855-863`（`setChapterDiscarded` 内两处 `Date.now()`）、`:866-881`（`restoreChapterVersion` 的 `const now: number = Date.now()`）。
- 影响：`NovelCreationDeps.nowMs` 是注入时钟（`:268`），同文件其它 mutation 均走 `this.nowMs()`；这两处绕过注入，导致测试可控时钟下 `updatedAt` 不可预测，也让"按时间排序/撤销"的语义在客户端时钟漂移时不一致。
- 最小修复：改用 `this.nowMs()`。
- 历史关系：round3 未覆盖；新增。

### H-8 MemoryStore 读路径 IO 异常静默空库 + 不在写队列（P3 / 置信度中 / 部分阅读）

- 位置：`entry/.../platform_impl/MemoryStore.ets:46-58`（`fileIo.access/readText` 抛错 → `return []`，无 quarantine、无错误态）；`:63-70` `writeJsonArray` 用 `writeTextFileAtomic`（P0-1 已修）；`:73-88` `enqueueWrite` 只串行写路径，`readAll` 不经队列。
- 影响：parse 损坏已被 `quarantineIfStillInvalid` 兜住（P0-1 修复已确认落地）；但平台 IO 读失败仍会被当"合法空库"，随后任一写操作（如 `touchMemories`/`addMemory`）以 `[]` 为基准原子覆盖 → 记忆永久丢失。读不在写队列，与写并发存在撕裂读。
- 最小修复：读失败与"文件不存在"区分，IO 失败进入错误态并拒绝后续写；`readAll` 纳入 `enqueueWrite`。
- 历史关系：round3 P0-1 的修复计划为"解析失败保留 .bad 后按空库处理"，实现如此；IO 失败分支是同族残留，明确记为**未覆盖边界**。

### H-9 Novel Polish WorkScheduler workId 32 位哈希碰撞（P3 / 置信度中 / 完整阅读）

- 位置：`entry/.../platform_impl/NovelPolishWorkScheduler.ets:20-26`（`djb2` 后 `& 0x7fffffff`）。
- 影响：两个不同 `jobId` 可映射同一 `workId`；`workScheduler.startWork` 以 workId 为主键，后到者会覆盖/关联错误工作，`cancelNovelPolishWork` 也会误停另一个 job 的后台恢复信号。概率极低但后果是"幽灵任务/漏恢复"。
- 最小修复：workId 从持久化 job 序号派生（或维护 jobId→workId 注册表），并在 `startWork` 前校验既有 work 的 `parameters.jobId`。
- 历史关系：C7 报告声称 WorkScheduler 已验证，但未涉及 workId 唯一性；新增。

### H-10 被 REPLACE 抢占的旧轮 `finally` 误取消新轮通知（P3 / 置信度高 / 隔离验证）

- 位置：`deepread/.../agent/scheduler.ts:152-160`（finally 中 token 匹配才删 `runningJobs`，但 `await deps.notifier?.cancelRunning(topicId)` **无条件执行**）。
- 复现（`probes-h/probe-scheduler-replace.mts`，真实 scheduler + mock 运行 5.5s 以触发 5s waitForAbort 截止）：

  ```
  notifierLog = ["notifyRunning:topic-1","notifyRunning:topic-1","cancelRunning:topic-1","notifyCompleted:topic-1","cancelRunning:topic-1"]
  ```
  第 3 条即 A 轮 `finally` 在新轮 B 已 `notifyRunning` 之后取消通知 → B 运行期间的 ongoing 通知被抹掉。
- 影响：UI/系统通知在新轮刚开始就消失；并发 REPLACE 场景下已有实证。属通知一致性 P3。
- 最小修复：`cancelRunning/notifyCompleted` 与删除注册同门——仅当 `current.token === job.token`（或 `runningJobs.get(topicId) === job`）时才调用。
- 历史关系：round3 #73/#66 等处理过 scheduler 相关收口，但未涉及 notifier 的 token 门。

---

## 3. 排除项 / 假阳性复核（当前实现重新确认，勿再报）

1. **Council `rounds=2` 无 finalPositionPrompt**（`manager.ts:343-344` `round>=3`）：与 Android 同款刻意（fix-plan 明列为假阳性），且 `rounds` 默认 2 时用 `responsePrompt` 符合"辩论两轮"产品语义 → 保留。
2. **`recency_days` 在内置源上忽略**（`search_orchestrator.ts:573-581` 只对 configured 调 `buildServiceParams`）：Android 同款 → 保留。
3. **aggregator 部分失败顶层仍 `'empty'/'ok'`**（`search_aggregator.ts:462-463`）：该函数契约与 orchestrator 不同（无 `partial`），fix-plan 判为刻意 → 保留。
4. **`assistant.enableMemory` 无运行时门 / extraction 去抖与配额顺序 / touchMemories 失败阻断 / expiresInDays 负数 / 中文年月正则**：fix-plan 列为"同款假阳性"；本次复核 `memory_extractor.ts:245-359`（含单飞去抖与严格 floatOrNull）确认未引入回归 → 保留。
5. **搜索请求不随 Stop 取消**：已在 `search_tools.ts:127-143,203-215,252-259` 用 `raceAbort` 消费 signal → 该项已修复，不再是问题。
6. **`NovelWorkspacePage` 终结后不退订**：`NovelWorkspacePage.ets:217-221` 明确 `currentUnsub` 调用 unsub；`attachNovelRun:912-948` 终结分支置 `currentUnsub=null` → 已修。
7. **novel repository RMW 丢更新 / 非原子覆盖写**：`repository.ts:979-990` 进程内 `updateTails` + `fileStore.withExclusiveLock(joinPath(LOCK_ROOT, id+'.lock'))`；`installSnapshot:1340-1367` staging→backup→rename 原子替换；`FileStore.ets:176-190` 真实 `file.lock(true)` → 已修（跨进程锁）。
8. **novel 流启动 TDZ / timer 残留 / replace 绕过 MAX_OUTPUT_CHARS**：`creation.ts:1353-1424`（noop unsub 前置、start subscribe 各自 try/catch、idle timer clear）与 `:1390` 超限 `outputTooLarge` → 已修。
9. **deepread url_filter 整数 IPv4 绕过**：`url_filter.ts:79-146` 已实现 inet_aton 全形态 + IPv4-mapped + userinfo 剥离 → 已修。
10. **prefetch deadline 到期返回原 promise**：`source_prefetcher.ts:245-263` 到期即 reject 并消化败者 → 已修。
11. **council live/listeners 泄漏、seat 超时不 abort、totalTimeoutMs 不生效**：`manager.ts:292-304` 清理、`:359` seat 子 signal abort、`:246-253` 运行级 deadline；隔离 probe 两个场景均得 `status=timed_out` → 已修。
12. **council 无 key/非 200 记 completed / 重复 seatId**：`ModelCouncilRunner.ets:41-44,99-103` 抛错；`validator.ts:99-110` 查重 → 已修。
13. **搜索标题去重失效 / orchestrator 标题合并遗留**：`search_aggregator.ts:317-349`、`search_orchestrator.ts:394-432` 均有 titleKey 别名与 canonical 回写 → 已修。
14. **html_dom 引号/`<`/数字实体**：`html_dom.ts:100-160` 引号感知 `findTagEnd` + `looksLikeTagStart` → 已修。
15. **HotListSourceSettingsPage 假 id / 不持久化**：`HotListSourceSettingsPage.ets:1-6,38-61` 用 `NEWSNOW_PRESETS` + `source_<id>` + 失败回滚 → 已修。

---

## 4. 待验证 / 盲区（不做事实断言）

- **平台语义未在设备证明**：`fileIo.File.lock(true)` 的跨进程排他性与 API 12 可用性（C7 声称真机验证）；本 owner 无设备，仅读到封装调用。
- **WorkScheduler 真机调度**：`onWorkStart/onWorkStop` 与 `drivePolishInBackground` 的实际触发频率、独立进程 `app.amber.deepread:workScheduler` 的内存/生命周期，需设备验证（C6/C7 也仅受控 provider）。
- **RDB 实际事务/observable 时序**：`RdbRepository` 的 `mutationTail` 与 `queryOne` 并发、`observe` 版本门在真机 relationalStore 下的行为未验证。
- **H-1 的端到端影响量化**：需一次真实/受控模型的 deepread run 统计每段 `cardsFor` 长度与段落 READY 质量；本报告只有隔离单元级证据。
- **H-4 的隐私影响**：免费外部源是否对国内网络实际可达、超时占比，未实测。
- `probes-h/probe-novel-state.ts` 的 polish 段序列构造有误（我在合成 job 上先 `writing→reviewing` 再存 candidate，触发真实 `validateDurablePolishJob` 的 `reviewing job 缺少 candidate`）。该断言是**我 probe 的顺序错误**，不是产品缺陷；polish 域结论已由现有聚焦测试（`novel_polish_domain/repository/runner.test.ts`、C7 41/41）覆盖。ghostwrite 段已跑通（`reviewing→committing`、receipt 一致）。

---

## 5. 各域结论（不只挑两项）

- **DeepRead agent/scheduler**：并发 REPLACE 与 token 门整体设计正确（probe 证实单飞不被破坏），但**取消信号未覆盖 prefetch/article-plan 窗口**（H-2），且旧轮 `finally` 的通知清理无 token 门（H-10）。`isAbortError` 用 `/abort|cancel/i` 正则匹配 message 过宽（任何含 "cancel" 的供应商错误都会被当取消重抛），属需登记的脆弱面。
- **DeepRead research/prefetch**：SSRF gate、deadline、LRU、seed 阈值都到位；**证据分桶算法是明确的 Android 保真缺口**（H-1），是本次最高价值发现。`search_merger` 的跨 bucket `seen` 与 `MAX_SEARCH_RESULTS` 语义与 Android 等价。
- **Search（聊天栈 + DeepRead 栈）**：聊天栈 orchestrator/aggregator 的 canonicalize/标题别名/状态/signal 均已达 round3 计划；残余问题在 DeepRead 栈的来源注册表（H-4）。两栈 settings 来源不统一是结构性债务。
- **Council**：编排、校验、限流、deadline/cancel、裁决解析、清理整体健康（probe 两场景通过）；残余为预算不落 max_tokens（H-5）与终结后订阅泄漏（H-6），均为 P3。
- **Novel（域 + entry）**：durable job 的 CAS/receipt/claim/lease/冻结计划/原子 root swap 是全仓最严的部分，round3 的 RMW/TDZ/退订问题均已落地；残余为时钟注入不一致（H-7）与 workId 碰撞（H-9）。`creation.ts` 单飞与 `collectWithEvents` 的 settle/退订收口正确。
- **Memory（extractor/dream/context）**：单飞、去抖、日配额、严格 float 解析、dream apply 串行、plan store 原子 replace、候选显式排序均已修；残余是 store 层 IO 失败静默空库（H-8）与读不进写队列。context token 预算的 `fitMessagesToTokenBudget`（`context_compact.ts:1065-1087`）在 `chat_turn.ts:219`、`regenerate.ts:156`、`tool_loop.ts:244` 装配后调用，round3 的"system 块二次预算"已在装配点覆盖，未发现新缺口。
- **H 稳定性（调度/通知/锁）**：文件锁 + staging swap 的崩溃恢复路径（`recoverProject`）逻辑闭合；主要风险集中在 DeepRead 调度层通知（H-10）与 Novel WorkScheduler 身份标识（H-9）。

---

## 6. 建议修复批次

1. **第一批（正确性/契约）**：H-1 证据分桶改 Android 语义 + 改钉死测试；H-3 恢复 hard-fail 或显式降级标记；H-2 取消归一为 `RunResult` + signal 覆盖 prefetch/plan。
2. **第二批（一致性/边界）**：H-4 registry 就绪门 + 开关注入；H-10 notifier token 门；H-7 时钟注入；H-6 终结后订阅 no-op。
3. **第三批（低危/债务）**：H-5 max_tokens 换算；H-8 读错误态 + 读入队；H-9 workId 唯一化。
