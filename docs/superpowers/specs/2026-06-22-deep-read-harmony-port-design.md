# Deep Read HarmonyOS 独立 App — 设计文档

> Status: **Draft for review**
> Date: 2026-06-22
> Scope: 把 AmberAgent Android 的「深度阅读」功能完整迁移为 HarmonyOS NEXT 独立原生 app
> Path: **A — 完整版**(不是 MVP 子集,代码架构兼容并入未来完整鸿蒙 app)

## 0. 设计基准与关键事实校准

本设计基于对 Android 源码的逐行精读(subagent 报告 1)和 HarmonyOS NEXT 后台任务能力的权威调研(subagent 报告 2)。开始具体设计前,先固化几个**颠覆直觉的事实**,它们直接决定设计形状:

### 0.1 Android 生产代码的"verification tail"是死代码

review 1 担心"verification/coverage tail 被砍"。精读源码确认:
- `DeepReadResearchHarness.verifyCoverage()` **只在 test 里被调用**,生产 `runStageSupervisorLoop` 的 4 个调用点全是 `coverageReport = null`
- `verificationState: DeepReadSectionState` 生产代码只 reset 为默认值,**从不**赋 RUNNING/READY/FAILED
- `DeepReadGenerationPhase.VERIFYING` **从未被赋值**
- `DeepReadSectionQuality.VERIFIED` **从未被赋值**(只有 BASIC / STANDARD)
- `verificationWarningMessage()` 硬 `return null`

**结论**:鸿蒙版**不移植** verification tail / coverageReport 二轮 supervisor / verificationState。这不是降级,是忠实于 Android 生产语义。`VERIFYING` 阶段在鸿蒙 phase 枚举里**不出现**。

### 0.2 鸿蒙后台任务对"纯 LLM 长跑"是真硬约束

权威结论(引用见 §7):
- `ContinuousTask` 的 `BackgroundMode.DATA_TRANSFER` **要求**任务通过 `@ohos.request` 系统代理托管。LLM SSE 流不是 `@ohos.request` 上传/下载,**不符合**,会被挂起
- 其他 ContinuousTask 模式(audio/nav/location/...)需要真实对应活动,用作 LLM 保活是**合规违规**,华为上架审核会拒
- `ServiceExtensionAbility` 是**系统应用专用**,三方 app 不可用
- `WorkScheduler` 单次触发上限 **2 分钟**,且系统非实时调度(基于 app active group tier)
- `TransientTask` 单次上限 **3 分钟**,日配额 ~10 分钟,且**必须在切后台 5 秒内申请**
- 实践者一致结论:**纯后台 LLM 长跑在鸿蒙上不可行**

**结论**:鸿蒙版 Deep Read **不支持 WorkManager 式后台无感生成**。采用「**前台跑 + stage 边界暂停 + WorkScheduler 护航**」混合策略(用户选择,见 §0.4)。

### 0.3 stage 是顺序 `if`,不是循环

Android `generateStages` 用 4 个顺序 `if (OVERVIEW in stages) ...` 保证 stage 严格有序,因为后 stage 的 prompt 必须读前 stage 的 `writer.currentOutput()`。鸿蒙版**照搬**这个顺序结构,不用通用循环。

### 0.4 用户决策记录

| 决策点 | 选择 | 理由 |
|---|---|---|
| 范围 | 路径 A 完整版 | 不是 MVP,要 4 阶段 + visuals + diagram + 完整 schema |
| 后台行为 | 前台 + WorkScheduler 护航 | 平台硬约束下最接近 Android 体验的方案 |
| 入口 | share/deep link + 手动输入话题 | 不依赖 hot-list 上游 |
| 预取 | 全多 provider + Rust reader-extractor | 体验等价 Android |
| 原生库 | 带 Rust reader-extractor(NAPI) | 正文提取质量保证 |

---

## 1. 整体定位与范围边界

**是什么**:HarmonyOS NEXT 原生 app,完整复现 AmberAgent Android 的「深度阅读」功能生产子集。给定话题/URL,自动研究网页源、生成结构化中文杂志文章、杂志风 UI 呈现。

**保留的 Android 核心语义(原样平移)**:
- 4 阶段严格顺序生成:`OVERVIEW → NARRATIVE → ANALYSIS → EXTENDED_READING`
- 完整 `DeepReadOutput` schema(§3)
- per-stage supervisor loop(每 stage 最多 2 pass,per-stage timeout 90/110/150/90s)
- 7 个 writer tool(§4.4)
- `sectionsReady()` 三门闩锁完成判定(§4.6)
- 24h TTL RDB 缓存 + 7d 历史保留
- 杂志风文章 UI(cover hero / timeline / core_points / analysis / diagram / extended_reading)
- retry 分类(retryable HTTP 408/429/5xx vs permanent 400/401/403/quota/safety)

**明确不做的(忠实于 0.1,不是降级)**:
- ❌ verification tail / `verifyCoverage` / coverageReport 二轮 supervisor(Android 生产死代码)
- ❌ `verificationState` 字段(Android 生产只 reset)
- ❌ `DeepReadGenerationPhase.VERIFYING` 枚举值
- ❌ `DeepReadSectionQuality.VERIFIED` 枚举值
- ❌ WorkManager 式无感后台生成(平台硬约束,0.2)
- ❌ hot-list 板块、chat 工具入口(不在 Deep Read 功能范围)
- ❌ 模板 workbench / 自定义 CSS 模板编辑器(`DeepReadTemplateAgent`)
- ❌ `runPreview` template-demo 路径

**入口**:
1. share/deep link(系统分享面板或 URL scheme 收 URL)
2. app 内手动输入话题标题

---

## 2. 架构分层与模块划分

### 2.1 5 层架构

```
┌─────────────────────────────────────────────────────┐
│  UI Layer  (ArkUI pages)                             │
│  DeepReadHomePage / DeepReadArticlePage / HistoryPage│
└─────────────────────────────────────────────────────┘
              ↑ ViewModel (@State)
┌─────────────────────────────────────────────────────┐
│  Feature Layer  (Deep Read 业务编排)                 │
│  - DeepReadRunManager (orchestrator)                 │
│  - StageSupervisorLoop (4-stage 顺序状态机)          │
│  - DeepReadScheduler (前台调度 + WorkScheduler 护航) │
│  - PromptBuilder / OutputParser / ResearchHarness    │
│  - AgentLoopBudgetPrompt / RetryClassifier           │
│  - SectionWriterTools (7 个 tool)                    │
└─────────────────────────────────────────────────────┘
              ↑ interface
┌─────────────────────────────────────────────────────┐
│  Domain Layer  (纯 ArkTS, 平台无关)                  │
│  - DeepReadModels (DeepReadOutput 等 interface)      │
│  - DeepReadStages / Phase 枚举                       │
│  - UIMessage / UIMessagePart discriminated union     │
│  - Tool / GenerateResult contract types              │
└─────────────────────────────────────────────────────┘
              ↓ depends
┌─────────────────────────────────────────────────────┐
│  Platform Layer  (鸿蒙 SDK 封装, 可替换)             │
│  HttpClient(RCP+SSE)│Database(RDB)│Storage(Prefs)   │
│  NativeApi(NAPI)│AiClient│Search│BackgroundTask      │
│  Notifier│JsonSchemaValidator                        │
└─────────────────────────────────────────────────────┘
```

### 2.2 关键设计决策

**D1. Domain 层是纯 ArkTS interface,零平台依赖**。可单测,可被未来完整 AmberAgent 鸿蒙 app 复用。

**D2. Platform 层 interface 抽象,实现可替换**。测试时注入 mock;后续 SDK 有问题(如 RCP 流式坑)可换 `@ohos.net.http`。

**D3. DI 用手写 ServiceLocator**。鸿蒙生态没有成熟 DI 框架(不像 Koin)。简单、类型安全、ArkTS 兼容:
```typescript
class AppContext {
  readonly http: HttpClient
  readonly db: Database
  readonly storage: Storage
  readonly ai: AiClient
  readonly search: SearchProviderRegistry
  readonly background: BackgroundTaskRunner
  readonly notifier: Notifier
  // ...
}
```

**D4. ArkTS 约束的具体应对**:

| Kotlin 模式 | ArkTS 对策 |
|---|---|
| `data class` + `.copy()` | `interface` + 工厂函数 + `withXxx()` 纯函数 |
| `Map<Enum, T>` | 显式 `Record<Stage, T>` + keyof 守卫 |
| `when` + sealed | `switch(discriminant)` + default 分支告警 + 单测覆盖 |
| Kotlin 扩展函数 | 顶层函数或静态类方法 |
| nullable `T?` | `T \| null`,但 `@Sendable` 类禁 null |
| sealed class | string literal union + `kind` discriminant + 接口 |
| `JsonObject?` metadata | 强类型 `Record<string, string>` 或显式 interface,禁 `unknown` |
| enum-keyed Map 类型安全丢失 | 用 `Record<Stage, _>` + 单测断言所有 key |

**D5. Stage 顺序用顺序 `if`**(照搬 Android `generateStages`),不用通用循环。理由:后 stage 的 prompt 必须读前 stage 的 `writer.currentOutput()`。

---

## 3. 数据层(Domain + Platform)

### 3.1 `DeepReadOutput` 完整 schema(对应 Android `DeepReadModels.kt:6-43`)

```typescript
export interface DeepReadOutput {
  topicType: string;                         // 默认 "event"
  generationComplete: boolean;               // 默认 false
  generationPhase: DeepReadGenerationPhase;  // 默认 IDLE
  summary: string;                           // 默认 ""
  keyEntities: string[];                     // 默认 []
  timeline: TimelineEvent[] | null;          // nullable
  corePoints: CorePoint[] | null;            // nullable
  analysis: DeepAnalysis;                    // 默认 {}
  extendedReading: ReadingLink[];            // 默认 []
  heroImageQuery: string | null;
  heroImageUrl: string | null;
  heroCaption: string | null;
  heroImageConfidence: string | null;        // "hero" | "inline" | "reject" | null
  imageAssets: DeepReadImageAsset[];         // 默认 []
  diagram: DeepReadDiagram | null;
  visualDiagnostics: DeepReadVisualDiagnostics | null;
  references: ReadingLink[];                 // 默认 []
  sectionStates: Record<DeepReadGenerationStage, DeepReadSectionState>;
  sectionQualities: Record<DeepReadGenerationStage, DeepReadSectionQuality>;
  // 注意:不移植 verificationState(Android 生产死代码,见 0.1)
}
```

子结构:
```typescript
export interface TimelineEvent {
  date: string;
  event: string;
  isHighlight: boolean;
  imageUrl: string | null;
  imageCaption: string | null;
}
export interface CorePoint { point: string; supporting: string | null; imageUrl: string | null; imageCaption: string | null; }
export interface ReadingLink { title: string; url: string; source?: string; publishedAt?: string; }
export interface DeepAnalysis {
  coreDispute: string | null;
  perspectives: Perspective[];
  implications: string | null;
  quotes: Quote[];
}
export interface Perspective { holder: string | null; viewpoint: string; }
export interface Quote { text: string; attribution: string | null; }
export interface DeepReadImageAsset {
  url: string;
  caption: string | null;
  confidence: string;          // "hero"|"inline"|"reject"
  score: number;
  source: string | null;
  qualityHint: string | null;
  selectionReason: string | null;
}
export interface DeepReadDiagram {
  type: string;                // "causal_chain"|"process_flow"|"stakeholder_map"|"system_structure"|"comparison_matrix"
  title: string;
  reason: string | null;
  nodes: DiagramNode[];        // 2..6, distinctBy id
  edges: DiagramEdge[];        // 5 (linear) or 6 (relation)
  caption: string | null;
}
export interface DiagramNode { id: string; label: string; note: string | null; group: string | null; }
export interface DiagramEdge { from: string; to: string; label: string | null; }
export interface DeepReadVisualDiagnostics {
  candidateCount: number;
  heroSelection: { url: string; reason: string } | null;
  inlineSelections: { url: string; reason: string }[];
  rejectedImages: { url: string; score: number; reason: string }[];
}
export interface DeepReadSectionState {
  status: DeepReadSectionStatus;  // PENDING | RUNNING | READY | FAILED
  errorMessage: string | null;
}
```

### 3.2 枚举(照搬 Android)

```typescript
export type DeepReadGenerationStage = 'OVERVIEW' | 'NARRATIVE' | 'ANALYSIS' | 'EXTENDED_READING';
// 顺序由声明顺序保证,单测断言
export type DeepReadGenerationPhase = 'IDLE' | 'COLLECTING' | 'PLANNING' | 'WRITING' | 'COMPLETE';
// 注意:无 VERIFYING(0.1),无 FAILED(失败 = IDLE + per-section FAILED)
export type DeepReadSectionStatus = 'PENDING' | 'RUNNING' | 'READY' | 'FAILED';
export type DeepReadSectionQuality = 'BASIC' | 'STANDARD';
// 注意:无 VERIFIED(0.1)
export const STAGE_ORDER: DeepReadGenerationStage[] = ['OVERVIEW', 'NARRATIVE', 'ANALYSIS', 'EXTENDED_READING'];
export const STAGE_LABELS: Record<DeepReadGenerationStage, string> = {
  OVERVIEW: '概览', NARRATIVE: '时间轴叙事', ANALYSIS: '深度分析', EXTENDED_READING: '扩展阅读',
};
export const IMAGE_CONFIDENCE = { HERO: 'hero', INLINE: 'inline', REJECT: 'reject' } as const;
```

### 3.3 辅助函数(纯函数,对应 Android helpers)

```typescript
export function isComplete(o: DeepReadOutput): boolean {
  return o.generationComplete && sectionsReady(o);
}
export function sectionsReady(o: DeepReadOutput): boolean {
  // 严格:4 个 stage 都必须存在且 READY
  return STAGE_ORDER.every(s => o.sectionStates[s]?.status === 'READY');
}
export function hasAnyReadySection(o: DeepReadOutput): boolean {
  return STAGE_ORDER.some(s => o.sectionStates[s]?.status === 'READY');
}
export function statusOf(o: DeepReadOutput, s: DeepReadGenerationStage): DeepReadSectionStatus {
  return o.sectionStates[s]?.status ?? 'PENDING';
}
export function withInferredSectionStates(o: DeepReadOutput): DeepReadOutput {
  // 老数据迁移路径:sectionStates 为空时从内容字段推断
  // 新数据 sectionStates 已填充,此函数是 no-op
  if (Object.keys(o.sectionStates).length > 0) return o;
  // 推断逻辑(照搬 Android DeepReadModels.kt:115-141):
  //   overviewReady   = summary.trim().length > 0
  //   narrativeReady  = (timeline?.filter(e => e.event.length >= 20).length >= 1)
  //                   || (corePoints?.filter(p => p.point.length >= 10 && (p.supporting?.length ?? 0) >= 30).length >= 1)
  //   analysisReady   = (analysis.coreDispute?.trim() || analysis.implications?.trim() || '').length >= 40
  //                   || analysis.perspectives.filter(p => p.viewpoint.trim().length >= 30).length >= 2
  //   readingReady    = extendedReading.length > 0 || references.length > 0
  //   每个 stage:legacyComplete(generationComplete) || <stage>Ready → READY
  //   最后重算 generationComplete = legacyComplete && sectionsReady(merged)
  // 实现时直接 port,不要猜
}
export function displayHeroImageUrl(o: DeepReadOutput): string | null { /* 照搬 */ }
export function displayHeroCaption(o: DeepReadOutput): string | null { /* 照搬 */ }
```

### 3.4 RDB schema

```sql
CREATE TABLE IF NOT EXISTS deep_read_cache (
  topic_id    TEXT PRIMARY KEY,
  title       TEXT NOT NULL,
  source_url  TEXT,
  output_json TEXT NOT NULL,
  phase       TEXT NOT NULL,
  attempt_count INTEGER NOT NULL DEFAULT 0,    -- 鸿蒙新增:WorkManager 无 runAttemptCount,自己存
  last_error  TEXT,                             -- 鸿蒙新增:retry 分类用
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL                  -- created_at + 24h
);
CREATE INDEX IF NOT EXISTS idx_drc_expires ON deep_read_cache(expires_at);
CREATE INDEX IF NOT EXISTS idx_drc_topic   ON deep_read_cache(topic_id);
CREATE INDEX IF NOT EXISTS idx_drc_title   ON deep_read_cache(title);   -- title-based fallback
```

**设计点**:
- `output_json` 存完整 `DeepReadOutput` JSON。`phase` 列冗余但方便 SQL 直接查"生成中"列表
- 新增 `attempt_count` / `last_error`:WorkManager 没有 runAttemptCount,鸿蒙自己存(§6.2)
- `topic_id` 生成规则(§3.5)
- **TTL 双常量**:fresh 查询用 `expires_at < now`(24h),历史保留用 `expires_at < now - 7d`(7d)

### 3.5 `topic_id` 生成规则(修正 review P0-5)

Android 有两条路径,且彼此不共享 cache。鸿蒙独立 app 只有手动输入 + deep link 两个入口,采用**统一规则**:

```typescript
export function deriveTopicId(input: { url?: string | null; title: string }): string {
  if (input.url && /^https?:\/\//.test(input.url)) {
    return sha256(normalizeUrl(input.url)).substring(0, 32);  // URL 入口
  }
  // 话题入口:规范化 title(lowercase + 单空格 + trim),照搬 Android topicId()
  const normalized = input.title.toLowerCase().replace(/\s+/g, ' ').trim();
  return sha256(normalized).substring(0, 32);
}
function normalizeUrl(url: string): string {
  // 去 fragment,小写 host,去常见 tracking param(utm_*, spm 等)
  // 同一篇文章不同 tracking param 应该映射到同一 topic_id
}
```

**关键**:URL 入口和话题入口的 topic_id 永远不同(前者基于 URL,后者基于 title)。这是**故意的**——同一个新闻,URL 入口(用户从浏览器分享)和话题入口(用户手打标题)是两次独立的深读,不共享 cache。这跟 Android hot-list 模式不同,但更符合 deep link 直觉。

**title-based fallback**(`materializeFreshDeepRead` 对应):查不到 `topic_id` 时,用 title 反查(允许跨 topic_id 复用同标题的历史结果)。`DeepReadRepository` 接口含 `getByTitle(title)`。

### 3.6 `DeepReadRepository` interface

```typescript
export interface DeepReadRepository {
  get(topicId: string): Promise<DeepReadCacheEntry | null>;
  getByUrl(url: string): Promise<DeepReadCacheEntry | null>;
  getByTitle(title: string): Promise<DeepReadCacheEntry | null>;      // title fallback
  materializeFresh(topicId: string, title: string, url: string | null): Promise<DeepReadCacheEntry | null>;
  upsert(entry: DeepReadCacheEntry): Promise<void>;
  updatePhase(topicId: string, phase: DeepReadGenerationPhase): Promise<void>;
  updateSectionState(topicId: string, stage: DeepReadGenerationStage, state: DeepReadSectionState): Promise<void>;
  incrementAttempt(topicId: string, error: string): Promise<void>;    // 鸿蒙新增
  resetAttempt(topicId: string): Promise<void>;                       // 鸿蒙新增
  observe(topicId: string): Observable<DeepReadCacheEntry | null>;    // 1s 轮询实现
  listHistory(limit: number): Promise<DeepReadCacheEntry[]>;
  delete(topicId: string): Promise<void>;
  purgeExpired(): Promise<number>;                                    // 24h TTL
  purgeHistoryRetention(): Promise<number>;                           // 7d 保留
}

export interface DeepReadCacheEntry {
  topicId: string; title: string; sourceUrl: string | null;
  output: DeepReadOutput;  // parsed from output_json
  phase: DeepReadGenerationPhase;
  attemptCount: number; lastError: string | null;
  createdAt: number; updatedAt: number; expiresAt: number;
}
```

### 3.7 `Observable` 实现(MVP 轮询)

```typescript
export interface Observable<T> {
  subscribe(cb: (value: T) => void): () => void;  // 返回 unsubscribe
}
// 实现:1s setInterval 查 RDB,值变化才回调
```

UI 通过 `subscribe` 拿到更新,组件卸载时调 unsubscribe。MVP 不上事件总线,§6.5 说明何时升级。

### 3.8 Preferences + Asset Store

Preferences(`@kit.ArkData preferences`)存配置:
```typescript
'deepread.search.enabled_providers'      // string[], e.g. ['tavily','zhipu']
'deepread.search.<provider>.base_url'    // string
'deepread.llm.provider'                  // 'openai_compatible' | ...
'deepread.llm.model_id'
'deepread.llm.temperature'               // number
'deepread.llm.max_steps'                 // number, 默认 32
'deepread.llm.board_model_id'            // 对应 Android todayBoard.boardModelId
'deepread.llm.board_headers_json'        // custom headers JSON
'deepread.llm.board_bodies_json'         // custom bodies JSON
'deepread.playbook.markdown'             // 对应 DeepReadPlaybookRepository
'deepread.secret.<provider>.key_set'     // boolean
```

真密钥走 **Asset Store Kit**(`@kit.AssetStoreService`),强制要求。鸿蒙审核会查敏感数据存储位置。

---

## 4. Pipeline 架构(Feature 层)

对应 Android `DeepReadAgentRunManager` + `DeepReadSourcePrefetcher` + `DeepReadResearchHarness`。

### 4.1 `DeepReadRunManager.run()` 完整算法

照搬 Android `DeepReadAgentRunManager.kt:88-130`,修正 review 暴露的 5 处偏差:

```
async run(topicId, title, opts: { force?, seedUrl?, deferMissingStages?, propagateFailuresWithPartial? }):
  ACQUIRE topicMutex(topicId)              // 每话题 Mutex(自建 Map<string, Mutex>)
  if opts.force: await repository.delete(topicId)

  cached = opts.force ? null : await repository.materializeFresh(topicId, title, opts.seedUrl)
                                       // 含 withInferredSectionStates
  if cached && isComplete(cached.output):     // 用 isComplete(),不是 phase==COMPLETE (修正 P0-5)
      return { ok: cached.output }            // SHORT-CIRCUIT #1

  missing = STAGE_ORDER.filter(s => statusOf(cached?.output ?? {}, s) !== 'READY')
  if shouldDeferMissingStages(opts, cached, missing):    // deferMissingStages && !force && hasAnyReadySection && missing 非空
      await scheduleBackgroundFill(topicId, title, missing, opts.seedUrl)
      return { ok: cached!.output }           // SHORT-CIRCUIT #2:返回部分输出,后台补

  if !opts.force && cached && sectionsReady(cached.output):   // sections ready 但 generationComplete 未 latch
      completed = withPhase(cached.output, 'COMPLETE', { generationComplete: true })
      await repository.upsert(fromOutput(topicId, title, completed))
      return { ok: completed }                // SHORT-CIRCUIT #3

  stagesToGenerate = missing.length > 0 ? missing : STAGE_ORDER
  return generateStages(topicId, title, stagesToGenerate, opts)
```

### 4.2 `generateStages()` 顺序结构(照搬 Android 顺序 if)

```
async generateStages(topicId, title, stages, opts):
  ctx = await createRunContext(topicId, title, opts)   // §4.3
  try:
    await ctx.writer.markRunning(stages)               // phase=WRITING, 各 missing stage=RUNNING, 重置 verification(无)

    // 顺序 if,不用循环(D5)
    if stages.includes('OVERVIEW'):         await runStageSupervisorLoop(ctx, 'OVERVIEW')
    if stages.includes('NARRATIVE'):        await runStageSupervisorLoop(ctx, 'NARRATIVE')
    if stages.includes('ANALYSIS'):         await runStageSupervisorLoop(ctx, 'ANALYSIS')
    if stages.includes('EXTENDED_READING'): await runStageSupervisorLoop(ctx, 'EXTENDED_READING')

    allTargetedReady = stages.every(s => statusOf(ctx.writer.current(), s) === 'READY')
    allSectionsReady = sectionsReady(ctx.writer.current())
    if allTargetedReady && allSectionsReady:
        return finishIfPossible(ctx.writer)            // §4.6
    if allTargetedReady:
        await ctx.writer.markPhase('IDLE')
        return { ok: ctx.writer.current() }
    // 未 READY 的 stage 标 FAILED
    missingStages = stages.filter(s => statusOf(ctx.writer.current(), s) !== 'READY')
    await ctx.writer.markMissingFailed(missingStages, 'Agent 未按约定调用分段写入工具,该段未写入。')
    await ctx.writer.markPhase('IDLE')
    return { ok: ctx.writer.current() }
  catch error:
    if isAbort(error): throw                  // 取消透传
    log(error)
    if opts.propagateFailuresWithPartial:
      await ctx.writer.markPhase('IDLE'); return { error }
    await ctx.writer.markMissingFailed(stages, error.message)
    output = await ctx.writer.markPhase('IDLE')
    return hasAnyReadySection(output) ? { ok: output } : { error }
```

### 4.3 `createRunContext()` 外部调用顺序

照搬 Android `DeepReadAgentRunManager.kt:291-381`:

1. 读 settings(provider/model/boardModel/playbook)
2. `resolveModel`:优先 board_model_id,fallback chat_model_id,**必须含 TOOL ability**(否则 throw "深度阅读需要配置支持工具调用的模型")
3. `withBoardRequestOptions`:注入 board headers/bodies,**清空 model.tools**
4. if markCollecting:写 phase=COLLECTING 到 RDB
5. **Prefetch**(§4.5):`sourcePrefetcher.collect(...)`,36s budget。空 → 写 phase=IDLE + throw "没有抓到足够的来源..."(修正 P1-9:Android 是 hard-fail,不是 evidence_quality:LOW)
6. `buildEvidencePack`:本地计算,dedupe → tag → score → bucket
7. `new EvidenceRegistry()` + `seedEvidenceRegistry`:用 prefetch 结果 + evidence card excerpt 初始化
8. 构造 `SectionWriterTools(repository, topicId, title, imageCandidates = flatMap(prefetched, c => c.imageCandidates), allowTitleFallback = !seedUrl)`
9. 读 settings.toIsolatedSubAgentSettings()(子代理隔离)
10. 读 playbook markdown(限 12000 字符)
11. 写 phase=PLANNING
12. `generateArticlePlan`(§4.4):1 次非流式 LLM 调用,失败 fallback 本地 plan

### 4.4 `generateArticlePlan` + `DeepReadArticlePlan` schema

**算法**(照搬 Android `DeepReadAgentRunManager.kt:572-610`):
```
async generateArticlePlan(ctx):
  fallback = researchHarness.fallbackPlan(title, evidencePack)    // 本地
  try:
    messages = await collectRun({
      ..., stream: false, tools: [],          // 无 tool,非流式,1 次调用
      userMessage: researchHarness.buildPlanningPrompt(title, pack, playbookMd)
    })
    parsed = researchHarness.parsePlan(messages.latestAssistantText())   // 容忍 JSON 提取
    return researchHarness.normalizePlan(parsed ?? fallback, title, pack)
  catch error:
    if isAbort(error): throw
    log.warn('planning failed, using fallback')
    return fallback
```

**`DeepReadArticlePlan` schema**(照搬,8 个字段全保留):
```typescript
export interface DeepReadArticlePlan {
  overviewAngle: string;
  narrativeSlots: string[];
  analysisQuestions: string[];
  stakeholders: string[];
  riskOrUncertainty: string[];
  requiredSourceIds: string[];
  stageSourceIds: Record<string, string[]>;   // key 是 "overview"/"narrative"/...
  coverageChecks: string[];                    // 字段保留但不用(0.1)
}
// sourceIdsFor(stage) 查找顺序:stageKey → stage.name.toLowerCase() → stage.name
```

`normalizePlan`:每个 stageSourceIds list clamp 到 [4, 6],requiredSourceIds 取 union(target = cards>=12 ? 10 : cards>=8 ? 8 : cards.length)。

### 4.5 Prefetch(对应 `DeepReadSourcePrefetcher`)

照搬 Android 9 个关键步骤:

```
async collect(topicId, title, seedUrl, force): Promise<DeepReadSource[]>
  cacheKey = `${topicId}|${seedUrl ?? ''}`
  if !force:
    cached = lruCache.get(cacheKey)            // 10min / 16 entries LRU(自建)
    if cached: return cached

  // 36s wall budget
  queries = buildDeepReadQueries(title, seedUrl)   // ~13 变体
  searchResults = await Promise.all(
    enabledProviders.map(p => p.search(queries))    // 多 provider 并行
  ).withTimeout(36_000)
  interleaved = interleaveSearchResults(searchResults)  // round-robin,cap 14

  // seed URL 单独 fetch(用更低阈值 MIN_SEED_SOURCE_CHARS=15 vs MIN_SOURCE_CHARS=280)
  seedSource = seedUrl ? await fetchDirect(seedUrl) : null

  // 每个 URL:scrape + reader-extractor + OG 提取 + 图片质量探测
  sources = await Promise.all(interleaved.map(scrapeAndExtract)).withTimeout(...)
  // scrapeAndExtract:
  //   1. RCP fetch html
  //   2. reader-extractor(NAPI→Rust),失败/正文<18 字符 → ArkTS regex fallback(修正 P0-8)
  //   3. OG image / <img> 提取
  //   4. DeepReadImageScorer.score():confidence ∈ {hero, inline, reject}
  //   5. 私网 gate:urlAllowedForBackgroundFetch(修正 P1-8)
  deduped = dedup(sources).slice(0, 12)
  lruCache.set(cacheKey, deduped)
  return deduped
```

**Provider 优先级 + fallback**(照搬 Android `DeepReadSourcePrefetcher.kt:741-812`):按 api key 存在性排序的 14+ provider,**始终追加** BingLocal + Jina 作 fallback(即使没配 key)。

**reader-extractor NAPI**(§5):Rust readability + ArkTS regex fallback双路径,跟 Android JVM fallback 对齐。

### 4.6 `runStageSupervisorLoop` 完整算法

照搬 Android `DeepReadAgentRunManager.kt:412-527`(去掉 coverageReport 参数,0.1):

```
async runStageSupervisorLoop(ctx, stage):     // 注意:无 coverageReport 参数
  stageTimeoutMs = STAGE_TIMEOUT[stage]       // 90_000 / 110_000 / 150_000 / 90_000
  writerToolsForStage = ctx.writer.tools().filter(t => t.name === writerToolName(stage))
  stageTools = toolSetFactory.forDeepRead({
    writerTools: writerToolsForStage,
    alwaysOn: ['deep_read_write_visuals', 'deep_read_write_diagram', 'deep_read_finish'],
    searchTools: [searchWebTool, scrapeWebTool].map(t => withEvidenceRecording(t, ctx.evidenceRegistry)),
    stageLabel: STAGE_LABELS[stage],
    stageTimeoutSeconds: stageTimeoutMs / 1000,
  })

  stageEvidence = ctx.evidencePack.cardsFor(stage, ctx.articlePlan)   // 读 plan.stageSourceIds
  stageSources = stageEvidence.map(c => c.source)
  messages = [UIMessage.user(buildPrompt(stage, ctx, stageEvidence))]

  initialRequiredWrites = ctx.writer.requiredWriteCount
  try:
    for pass in [0, 1]:                        // MAX_SUPERVISOR_PASSES = 2
      beforeWrites = ctx.writer.requiredWriteCount
      messages = await withTimeout(stageTimeoutMs, () =>
        collectRun({ ctx, messages, tools: stageTools, writerToolNames: writerToolForStageNames,
                     statusLabel: `深度阅读 ${STAGE_LABELS[stage]}` }))

      stageReady = statusOf(ctx.writer.current(), stage) === 'READY'
      supplementWritten = ctx.writer.requiredWriteCount > initialRequiredWrites   // 0.1: 永远 true(无 coverageReport)
      if stageReady && supplementWritten: return

      // 该 pass 没新写入 → 加 reminder user message
      if ctx.writer.requiredWriteCount === beforeWrites:
        messages.push(UIMessage.user(buildWriterReminder(stage, pass)))

    // 2 pass 都没成功
    if statusOf(ctx.writer.current(), stage) !== 'READY':
      await tryFallbackAfterStageFailure(ctx, stage, messages, stageSources, 'missing writer tool')
  catch TimeoutError:
    log.warn('stage timeout')
    recovered = await tryFallbackAfterStageFailure(ctx, stage, messages, stageSources, 'timeout')
    if !recovered && statusOf(ctx.writer.current(), stage) !== 'READY':
      await ctx.writer.markFailed(stage, `${STAGE_LABELS[stage]}超时未完成(本段预算约 ${stageTimeoutMs/1000} 秒)。`)
  catch AbortError: throw
  catch error:
    log.error('stage failed', error)
    recovered = await tryFallbackAfterStageFailure(ctx, stage, messages, stageSources,
      isDeepReadTimeoutLike(error) ? 'provider timeout' : 'failure')
    if !recovered && statusOf(ctx.writer.current(), stage) !== 'READY':
      await ctx.writer.markFailed(stage, stageFailureMessage(stage, error, stageTimeoutMs))
```

`tryFallbackAfterStageFailure` → `writeFallbackSection`(§4.7 Tool 对应):把 assistant 自由文本 + sources 转成 BASIC 质量 READY section(若产出可用内容)。

### 4.7 7 个 SectionWriterTool(照搬 Android 完整 schema)

所有 tool 共享 `update(transform)` 原语:`writeMutex` 串行 + `repository.upsert`。

| Tool | input schema(required) | 写入字段 | section 状态 |
|---|---|---|---|
| `deep_read_write_overview` | topic_type, **summary**(required), key_entities[], references[] | topicType, summary(≤1200), keyEntities(≤12), references(merge≤12) | hasOverviewContent(summary≥24) → READY+STANDARD |
| `deep_read_write_narrative` | timeline[], core_points[], references[](全 optional) | timeline(每条 event≤600,take 8), corePoints(point≤280, support≤700, take 8), references | hasNarrativeContent → READY+STANDARD |
| `deep_read_write_analysis` | core_dispute, perspectives[], implications, quotes[], references[](全 optional) | analysis 一次性替换(空字段保留旧值), references | hasAnalysisContent → READY+STANDARD |
| `deep_read_write_extended_reading` | **links**(required), image_assets[] | extendedReading(merge≤10), references(merge≤12), imageAssets(withCandidateEvidence, ≤8) | hasExtendedReadingContent(有 http url) → READY+STANDARD |
| `deep_read_write_visuals` | hero_image_url?, hero_caption?, hero_reason?, image_assets[] | heroImageUrl(仅当 candidate.confidence=='hero'), heroCaption(≤180), imageAssets(withCandidateEvidence≤8), visualDiagnostics | 不改 section 状态(markVisibleWrite) |
| `deep_read_write_diagram` | **type, title, nodes**(required), edges[], caption?, reason? | diagram 一次性替换 | 不改 section 状态 |
| `deep_read_finish` | {}(空) | generationPhase=COMPLETE iff sectionsReady, generationComplete=sectionsReady | 不改 section 状态 |

**Hero 强制 gate**(修正 review P1-1):`deep_read_write_visuals` 接受 `hero_image_url` **当且仅当** `imageCandidates` 池里存在该 URL 且 `confidence === 'hero'`。否则丢弃 hero 字段。这是防止模型把 logo 当 hero 图的唯一闸门。

**imageCandidates 池**:在 `createRunContext` 里 `flatMap(prefetchedSources, s => s.imageCandidates)` 传给 `SectionWriterTools` 构造器。

**`_writeCount` / `_requiredWriteCount`**:ArkTS 用闭包变量 + `writeMutex` 保护(不用 AtomicInteger,单线程 async)。

### 4.8 `collectRun` + AiClient(修正 review P0-6)

`AiClient` 接口必须承载 Android `GenerationHandler` 的核心行为:

```typescript
export interface AiClient {
  generateText(params: {
    model: ModelConfig
    messages: UIMessage[]
    tools?: Tool[]
    maxSteps?: number                    // 默认 32
    autoApproveTools?: boolean
    autoApproveHighRiskTools?: boolean
    autoApprovedToolNames?: string[]
    budgetPrompt?: (stepIndex: number, maxSteps: number, hasResumable: boolean) => BudgetStage | null
    retryClassifier?: GenerationFailureClassifier
    stream?: boolean
    onMessagesUpdate?: (messages: UIMessage[]) => void    // 33ms 节流后的快照
    signal?: AbortSignal
  }): Promise<UIMessage[]>
}
```

**实现必须包含**(修正 P0-6):
1. **AgentLoopBudgetPrompt**:`stage(stepIndex, maxSteps)` → WARN/TIGHT/FINAL,FINAL 且无 resumable tool 时隐藏所有 tool(防模型烧光 step 不调 writer tool)
2. **ToolExposureState**:DeepRead 用 eager 模式(tool 少,全部暴露)
3. **RetryClassifier**(照搬 Android `GenerationRetry.kt:50-166`):retryable = 408/429/5xx/timeout/network;permanent = 400/401/403/quota/safety/auth/context_length
4. **MessageStreamAccumulator + 33ms flush**:`STREAM_UI_FLUSH_INTERVAL_MS = 33`
5. **Tool-result 构造路径**(§4.9)
6. **Transformers**:DeepRead 传 emptyList,但接口保留位置

### 4.9 Tool-result message 构造(修正 review P0-12)

**关键**:tool result **不**新建 `role=TOOL` message。executor 把结果填回**原 assistant message 的 `UIMessagePart.Tool.output`**(按 `toolCallId` 匹配),然后 GenerationHandler 用新 parts 替换该 message。

```
// 伪代码
async function executeToolsAndMerge(messages, tools, defs, autoApproveMatrix): UIMessage[] {
  lastMessage = messages[messages.length - 1]
  toolParts = lastMessage.parts.filter(p => p.kind === 'tool' && !p.isExecuted && p.canResumeExecution)
  decisions = toolParts.map(t => resolveDecision(defs[t.toolName], t, autoApproveMatrix))
  // 若有 ASK → break,等用户审批
  if (decisions.some(d => d.action === 'ASK')) return messages

  executed = await Promise.all(toolParts.map(t => executeOne(t, defs, decisions)))
  // executeOne 返回 { toolCallId, output: UIMessagePart[] }   // output 总是 [Text(jsonString)]
  updatedParts = lastMessage.parts.map(p => {
    if (p.kind === 'tool') {
      const exec = executed.find(e => e.toolCallId === p.toolCallId)
      return exec ? { ...p, output: exec.output } : p
    }
    return p
  })
  newLast = { ...lastMessage, parts: updatedParts }
  return [...messages.slice(0, -1), newLast]
}
```

**tool output 始终是 `[UIMessagePart.Text(jsonString)]`**,JSON 形如:
- 成功:`{status: "ok", section: "overview", feedback: {...}}`
- 失败:`{status: "failed", message: "...", recoverable: false, permission_trace: ...}`
- 拒绝:`{status: "denied", message: "Tool execution denied by user. Reason: ..."}`

### 4.10 `finishIfPossible` 三门闩锁

照搬 Android `DeepReadAgentRunManager.kt:612-616` + `DeepReadSectionWriterTools.kt:457-477`:

```
async finishIfPossible(writer): DeepReadOutput {
  // 调 deep_read_finish tool(空参数)
  await writer.executeFinish()
  return writer.current()
}
// deep_read_finish 内部:
//   generationPhase = sectionsReady() ? 'COMPLETE' : current.phase    // gate 1
//   generationComplete = sectionsReady()                               // gate 2
//   return [{ kind:'text', text: JSON.stringify({
//     status: missing.isEmpty() ? 'complete' : 'missing_sections',
//     missing: stages.where(statusOf !== 'READY').map(lowercase)
//   })}]                                                              // gate 3:返回 missing list 给模型
```

**注意**:`isComplete()` = `generationComplete && sectionsReady()`,**不依赖** verificationState(0.1)。

### 4.11 `runSection` 单 stage 重试(修正 review P0-7)

照搬 Android `DeepReadAgentRunManager.kt:132-149`:

```
async runSection(topicId, title, stage, seedUrl?): Promise<Result> {
  ACQUIRE topicMutex(topicId)
  await markSectionRunning(topicId, title, seedUrl, stage)   // stage=RUNNING,READY 不动
  return generateStages({
    stages: [stage], seedUrl,
    markCollecting: false,                // 不发 COLLECTING phase
    planningPhase: 'WRITING',             // 跳过 PLANNING 标记
  })
}
```

UI 的"重试该段"按钮调 `scheduler.runSection(topicId, title, stage, seedUrl)`。

### 4.12 Prompt 组装(`buildPrompt`)

照搬 Android `DeepReadAgentRunManager.kt:661-739`,10 个段落:
1. 日期 + 话题标题 + 目标段落(当前 → 目标)
2. `## Deep Read Playbook(本地规则,只读)` — playbook markdown 截断 12000 字符
3. seedUrl hint(若有)
4. `appendArticlePlan(plan)` — overviewAngle/narrativeSlots/analysisQuestions/stakeholders/riskOrUncertainty/requiredSourceIds
5. ~~coverage block~~(0.1,不移植)
6. `appendEvidenceCards(stageEvidence, excerptLimit = STAGE_EXCERPT_LIMIT[stage])` — sourceId/title/url/source/tags/credibility/freshness/claim_summary/published_at/image_candidates(非 reject, take 4)/evidence_excerpt(截断)
7. `appendArticleContext(writer.current())` — section_states 行 + summary + key_entities(≤12)+ hero block + diagnostics + inline assets(≤8)+ timeline(≤8)+ core_points(≤8)+ analysis + diagram + extended_reading(≤10)+ references(≤12)
8. `appendDeadlineGuidance(stage, stageTimeoutMs)` — 硬预算约束文本
9. `## 研究顺序` — 8 条规则
10. `## 段落要求` — stage 特定要求 + visual + diagram 约束

**per-stage 常量**(照搬 Android `DeepReadAgentRunManager.kt:1064-1123`):
```typescript
const MAX_GENERATION_STEPS = 32
const MAX_SUPERVISOR_PASSES = 2
const STAGE_TIMEOUT: Record<Stage, number> = { OVERVIEW: 90_000, NARRATIVE: 110_000, ANALYSIS: 150_000, EXTENDED_READING: 90_000 }
const STAGE_SOURCE_LIMIT: Record<Stage, number>  = { OVERVIEW: 6,  NARRATIVE: 9, ANALYSIS: 8, EXTENDED_READING: 12 }
const STAGE_EXCERPT_LIMIT: Record<Stage, number> = { OVERVIEW: 1000, NARRATIVE: 1400, ANALYSIS: 1400, EXTENDED_READING: 700 }
const PROMPT_SOURCE_LIMIT = 12
const PROMPT_SOURCE_EXCERPT_LIMIT = 2_000
const PLAYBOOK_PROMPT_LIMIT = 12_000
const OVERVIEW_SUMMARY_MIN_CHARS = 24
const OVERVIEW_SUMMARY_STORAGE_MAX_CHARS = 1_200
const MAX_DIAGRAM_NODES = 6
const MAX_LINEAR_DIAGRAM_EDGES = 5    // causal_chain / process_flow
const MAX_RELATION_DIAGRAM_EDGES = 6  // 其他
```

### 4.13 错误处理与重试

照搬 Android retry 分类(`GenerationRetry.kt:50-166` + `DeepReadWorker.kt:241-329`):

| 场景 | 处理 |
|---|---|
| Prefetch 全空 | hard-fail:phase=IDLE,throw "没有抓到足够的来源..."(修正 P1-9,不用 evidence_quality:LOW) |
| 单 stage timeout | 2 pass + tryFallbackAfterStageFailure,都失败 markFailed + 继续下 stage |
| LLM 网络/超时 | retryClassifier 决定:retryable(408/429/5xx/timeout/network)按指数退避重试 max 5 次;permanent(400/401/403/quota/safety)直接失败 |
| 流式失败 | 自动 fallback 非流式重试 1 次(collectRun 内) |
| stage 全失败 | per-section FAILED,phase=IDLE,保留已生成部分(hasAnyReadySection 仍返回 ok) |

### 4.14 取消

`AbortController` 贯穿(修正 review P1-10:**保留** partial output,不是"不写半截"):

```
abortController = new AbortController()
// 所有 RCP fetch / LLM 调用传 abortController.signal
// 取消时:
//   1. abortController.abort()
//   2. 当前 stage 的 writer tool 调用已经 atomic saveDeepRead(§4.7),所以 partial output 已落库
//   3. phase 不强写回 IDLE(保留 WRITING,让 UI 显示"已中断")
//      下次 run() 检测到 cached 不是 isComplete() 且 hasAnyReadySection → 走 SHORT-CIRCUIT #2 后台补
```

**与 Android 差异**:Android 用结构化并发自动向下传播 cancellation。ArkTS `AbortController` 不自动传播到下游 Promise,所以**每个 async 调用点都要显式检查 `signal.aborted`** 并 throw `AbortError`。这是 ArkTS 的额外样板,不可省略。

---

## 5. Rust reader-extractor NAPI 集成

### 5.1 NAPI 接口设计

Android JNI 签名(`native/reader-extractor/src/lib.rs:7`):
```rust
extract(html: JString, base_url: JString) -> ExtractedArticle { title, content_html, content_text, section_count }
```

鸿蒙 NAPI(`harmony/native/rust-napi/reader_extractor/`)对应:
```typescript
// ArkTS 调用面
export const readerExtractorNative = {
  extract(html: string, baseUrl: string): ExtractedArticle | null
}
export interface ExtractedArticle {
  title: string
  contentHtml: string
  contentText: string
  sectionCount: number
}
```

### 5.2 实现(分阶段)

**阶段 1(MVP)**:**纯 ArkTS readability**(照搬 Android JVM fallback `extractReadableTextJvm`,14 行 regex)。不依赖 NAPI,最快跑通。质量略低(无 title 提取、无 section 切分)。

**阶段 2**:Rust + NAPI,`ExtractedArticle` 完整 4 字段。需要:
- Rust 侧 `cargo build --target aarch64-linux-ohos`(鸿蒙 NDK target)
- NAPI 模块注册(`napi_register_module_v1`)
- ArkTS 侧 `import { readerExtractorNative } from 'libreaderextractor.so'`
- 失败检测:load 失败 → 走阶段 1 ArkTS fallback

**双路径逻辑**(照搬 Android `DeepReadSourcePrefetcher.kt:651-661`):
```
function extractReadableText(html: string, url: string | null): string {
  const nativeResult = url ? tryNative(() => readerExtractorNative.extract(html, url)) : null
  if (nativeResult && nativeResult.contentText.length >= 18) return nativeResult.contentText
  return extractReadableTextArkTs(html)   // regex fallback
}
```

### 5.3 已知风险

- **NAPI + Rust + aarch64-linux-ohos 交叉编译**:`docs/harmony-detailed-porting-plan.md` 标了 R-NATIVE-1..3 风险,需要真机 spike
- 阶段 1 ArkTS fallback 让这个风险**不阻塞 MVP**

---

## 6. 后台任务:前台 + WorkScheduler 护航

### 6.1 策略总览

| 路径 | 触发 | 行为 |
|---|---|---|
| **前台主路径** | 用户在 app 内点"开始深读" | UIAbility 内 async 跑完整 pipeline;切后台 → 下个 stage 边界暂停 + RDB 持久化 + 通知 |
| **回前台续跑** | 用户从通知/launcher 回到 app | 检测 phase ∈ {COLLECTING,PLANNING,WRITING} 且无活跃 run → 从最后完成 stage 续跑 |
| **WorkScheduler 护航** | 切后台时,为每个未完成 stage 注册一个 WorkScheduler job | 每个 job 单次 ≤2min 跑一个 stage;非实时调度(可能等几分钟到几小时);网络约束 |

### 6.2 WorkManager → 鸿蒙映射

| WorkManager 特性 | 鸿蒙状态 | 补偿方案 |
|---|---|---|
| `ExistingWorkPolicy.REPLACE`/`KEEP` | 无 | 自建 `Map<topicId, AbortController>` + topicMutex;REPLACE = abort 现有 + 启新 |
| `Result.retry()` + 指数退避 | 无 | RDB 存 `attempt_count` + `last_error`,resume 时按 retryClassifier 决定是否重试 |
| `runAttemptCount` | 无 | `deep_read_cache.attempt_count` 列 |
| `getWorkInfosForUniqueWorkFlow` (reactive) | 仅 `getWorkStatus`(one-shot) | derive 自 RDB observe(topicId)的 phase + sectionStates |
| 长无界 run(6-10min) | 2min cap | stage 分片(每 stage 已独立可跑,§4.11) |
| `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` | 无 | 退化为普通 ongoing notification(不再绑 fg-service 义务) |
| `NetworkType.CONNECTED` 约束 | WorkScheduler 支持 | 保留,前台路径自检 connectivity |

### 6.3 `DeepReadScheduler` interface(修正 review P0-7)

```typescript
export interface DeepReadScheduler {
  // 主入口:UI 调
  run(topicId: string, title: string, opts: { force?: boolean; seedUrl?: string | null }): Promise<Result>
  // 单 stage 重试:UI"重试该段"按钮调
  runSection(topicId: string, title: string, stage: DeepReadGenerationStage, seedUrl?: string | null): Promise<Result>
  // 取消
  abort(topicId: string): void
  // 观察运行状态(derive 自 RDB)
  observeRunning(topicId: string): Observable<boolean>
  // 检测中断态(UI 回前台时调)
  detectInterrupted(topicId: string): Promise<boolean>
}
```

### 6.4 前台主路径实现

```
async run(topicId, title, opts):
  // dedup:REPLACE 语义
  if runningJobs.has(topicId):
    abort(topicId)
    await waitForAbort(topicId)
  controller = new AbortController()
  runningJobs.set(topicId, controller)

  // 申请 ContinuousTask?—— 不申请(0.2:LLM 不符合任何合法 BackgroundMode)
  // 只显示 ongoing notification
  await notifier.notifyRunning(topicId, title)

  try:
    result = await runManager.run(topicId, title, { ...opts, signal: controller.signal })
    await notifier.notifyCompleted(topicId, title, isComplete(result.output))
    // 切过后台?注册 WorkScheduler 护航剩余 stage
    if wasBackgrounded && !isComplete(result.output):
      await scheduleWorkSchedulerGuard(topicId, title, missingStages(result.output))
  finally:
    runningJobs.delete(topicId)
    if wasBackgrounded: await notifier.cancelRunning(topicId)
```

### 6.5 中断检测与续跑

UIAbility `onForeground` 回调时:
```
async onForeground():
  for each topicId in recentActiveTopics:
    if await scheduler.detectInterrupted(topicId):
      // phase ∈ {COLLECTING,PLANNING,WRITING} 且 runningJobs 无此 topicId
      showDialog('上次深读未完成,是否继续?')
      if user confirms:
        await scheduler.run(topicId, ...)   // 走 SHORT-CIRCUIT #2/#3 或继续 missing stages
```

### 6.6 WorkScheduler 护航实现

```
async scheduleWorkSchedulerGuard(topicId, title, stages: Stage[]):
  for stage of stages:
    workId = `deepread_${topicId}_${stage}`
    await workScheduler.addWork(workId, {
      type: WorkSchedulerType.NETWORK_AVAILABLE,
      callbackAbility: 'DeepReadWorkSchedulerAbility',
      extras: { topicId, title, stage, seedUrl: null },
      // 注意:单次 onWorkStart ≤ 2min,系统非实时调度
    })

// DeepReadWorkSchedulerAbility.onWorkStart(workInfo):
async onWorkStart(workInfo):
  const { topicId, title, stage } = workInfo.extras
  // 2min budget
  const controller = new AbortController()
  setTimeout(() => controller.abort(), 110_000)   // 110s,留 10s 余量
  try:
    await runManager.runSection(topicId, title, stage, { signal: controller.signal })
  catch error:
    if isRetryable(error):
      await repository.incrementAttempt(topicId, error.message)
      // 不自动 re-enqueue(WorkScheduler 无 retry),等下次前台或下个 WorkScheduler 周期
```

**已知限制**:WorkScheduler 非实时调度,可能等很久才触发。**用户预期**:切后台后文章可能不会自动完成,回 app 才能续跑。WorkScheduler 是"尽力而为"的补充,不是保证。

### 6.7 通知

照搬 Android `DeepReadNotifier` 3 类通知:
- **Running**(ongoing):"正在深度阅读:..." → 点击回 app 文章页
- **Completed**:"深度阅读完成:..." → 点击回 app 文章页
- **Failed**:"深度阅读失败:..." → 点击回 app 文章页

**Want extras 契约**(修正 review P2-1):
```typescript
Want.params = {
  'extra_topic_id': topicId,
  'extra_title': title,
  'extra_source_url': sourceUrl ?? '',
  'extra_from_history': false,
}
```
UIAbility `onNewWant` 解析这些参数,路由到 `DeepReadArticlePage`。

---

## 7. UI 层(ArkUI)

### 7.1 页面

| 页面 | 职责 |
|---|---|
| `DeepReadHomePage` | 输入框(话题标题)+ "从分享/链接开始"按钮 + 最近历史列表 |
| `DeepReadArticlePage` | 生成进度(phase + per-stage 状态)+ 文章渲染(hero/timeline/core_points/analysis/diagram/extended_reading/references)+ 取消/重试按钮 |
| `HistoryPage` | 7d 历史列表,点击进入 `DeepReadArticlePage`(只读) |

### 7.2 入口路由

- **Share/deep link**:UIAbility `onCreate`/`onNewWant` 收 URL → `deriveTopicId({url})` → 跳 `DeepReadArticlePage`
- **手动输入**:`DeepReadHomePage` 输入标题 → `deriveTopicId({title})` → 跳 `DeepReadArticlePage`
- **通知**:§6.7

### 7.3 文章渲染组件

ArkUI 声明式组件,杂志风:
- `HeroImageBlock`:`displayHeroImageUrl(output)` + `displayHeroCaption(output)`
- `TimelineBlock`:`output.timeline`(若非 null)
- `CorePointsBlock`:`output.corePoints`(若非 null)
- `AnalysisBlock`:coreDispute + perspectives + implications + quotes
- `DiagramBlock`:`output.diagram`(2-6 节点,5-6 边)
- `ExtendedReadingBlock`:`output.extendedReading`
- `ReferencesBlock`:`output.references`

Markdown 渲染:用 ArkUI `RichText` 组件 + 自建 markdown→ArkUI node 转换(MVP),或找鸿蒙 markdown 库。**风险**:鸿蒙 markdown 生态不成熟,可能要自建轻量 parser。

### 7.4 进度 UI

`DeepReadArticlePage` 订阅 `repository.observe(topicId)`(1s 轮询),渲染:
- phase badge(IDLE/COLLECTING/PLANNING/WRITING/COMPLETE)
- 4 stage 状态卡(PENDING/RUNNING/READY/FAILED + quality BASIC/STANDARD)
- 当前正在生成的 stage 内容流式追加(summary / timeline 边生成边显示)

---

## 8. Platform 层接口契约

### 8.1 `HttpClient`(RCP 封装)

```typescript
export interface HttpClient {
  fetch(req: HttpRequest, opts?: { signal?: AbortSignal }): Promise<HttpResponse>
  fetchStream(req: HttpRequest, opts: {
    onChunk: (chunk: ArrayBuffer, end: boolean) => void
    signal?: AbortSignal
  }): Promise<HttpResponse>
}
export interface HttpRequest { url: string; method: string; headers: Record<string, string>; body?: string }
export interface HttpResponse { status: number; headers: Record<string, string>; body: string }
```

**实现**:`RcpHttpClient` 用 `@kit.NetworkKit` 的 `rcp.createSession` + `session.fetch` + `onReceiveDataBlock`。

### 8.2 SSE Parser(修正 review P0-10)

**关键**:RCP 给的是 `ArrayBuffer`,不是 string。需要:
1. `TextDecoder` 解码 ArrayBuffer → string
2. **chunk 边界 buffer**:一个 SSE event 可能跨多个 chunk(一行 `data:` 被切到两个 ArrayBuffer)。必须累积未完成的行到 buffer,下一个 chunk 拼接后再按 `\n` 切。

```typescript
export class SseAssembler {
  private buffer = ''
  feed(chunk: ArrayBuffer): SseEvent[] {  // end=false 时也可能产出 0 或多个完整 event
    this.buffer += new TextDecoder('utf-8').decode(chunk)
    const events: SseEvent[] = []
    // 按空行(\n\n)切 event;最后一个不完整的留在 buffer
    let idx
    while ((idx = this.buffer.indexOf('\n\n')) >= 0) {
      const raw = this.buffer.slice(0, idx)
      this.buffer = this.buffer.slice(idx + 2)
      const evt = parseSseEvent(raw)   // 累积 data: 行,检测 [DONE]
      if (evt) events.push(evt)
    }
    return events
  }
  flush(): SseEvent[] { /* 处理 buffer 残留 */ }
}
export interface SseEvent { data: string; done: boolean }
```

OpenAI SSE parser 在此基础上解析 `data:` payload(JSON 增量,tool_call delta 合并等)。**fixture 数量订正**:实际 8 个(不是 9 个),且 streaming_contract.md 明确标 tool_calls merge / reasoning finishedAt / full-message replacement **NOT executable yet**——这些是 §4.9/§4.8 实现时必须补齐的契约。

### 8.3 `Database`(RDB 封装)

```typescript
export interface Database {
  getRdbStore(): Promise<relationalStore.RdbStore>
  execute(sql: string, params?: any[]): Promise<void>
  query(sql: string, params?: any[]): Promise<Row[]>
  transaction<T>(fn: (tx) => Promise<T>): Promise<T>
}
```

### 8.4 `Storage`(preferences + Asset Store)

```typescript
export interface Storage {
  get<T>(key: string, defaultValue: T): Promise<T>
  set<T>(key: string, value: T): Promise<void>
  getSecret(key: string): Promise<string | null>      // Asset Store
  setSecret(key: string, value: string): Promise<void>
  deleteSecret(key: string): Promise<void>
}
```

### 8.5 `AiClient`

见 §4.8。

### 8.6 `SearchProviderRegistry`

```typescript
export interface SearchProvider {
  name: string
  search(queries: string[]): Promise<SearchHit[]>
}
export interface SearchProviderRegistry {
  enabled(): SearchProvider[]   // 按 api key 存在性排序
  fallback(): SearchProvider[]  // BingLocal + Jina(始终)
}
```

### 8.7 `BackgroundTaskRunner`

```typescript
export interface BackgroundTaskRunner {
  scheduleWorkGuard(topicId: string, stages: Stage[]): Promise<void>
  cancelWorkGuard(topicId: string): Promise<void>
}
```

### 8.8 `Notifier`

照搬 Android 3 类通知,Wants 契约见 §6.7。

### 8.9 `JsonSchemaValidator`(修正 review P0-13)

```typescript
export interface JsonSchemaValidator {
  validate(value: unknown, schema: JsonSchema): ValidationResult
}
```
stage tool 参数校验用(§4.7)。鸿蒙无原生 JSON schema,自建轻量 validator(只支持 type/required/properties/items/enum/minLength/maxLength)或用 `ajv` ArkTS port。

---

## 9. 测试策略

### 9.1 Domain 层(纯函数,单测优先)

- `isComplete` / `sectionsReady` / `hasAnyReadySection` / `withInferredSectionStates`
- `deriveTopicId`(URL vs title + 规范化)
- `displayHeroImageUrl` / `displayHeroCaption` fallback
- retryClassifier(retryable/permanent 分类)
- budgetPrompt 阶段判定
- SSE assembler(chunk 边界、跨 chunk event、[DONE])

### 9.2 Feature 层(mock platform)

- `runStageSupervisorLoop`:2 pass 超时/fallback/READY 转移
- `finishIfPossible`:三门闩锁(缺 stage 时返回 missing_sections)
- cancel 保留 partial output
- scheduleBackgroundFill dedup

### 9.3 集成(模拟器)

- 端到端:URL deep link → 完整生成 → 文章渲染
- 中断续跑:切后台暂停 → 回前台续跑
- WorkScheduler 护航:注册 job → onWorkStart 跑单 stage

### 9.4 Fixture

复用 source-only 阶段的 SSE / UIMessage / transformer fixtures(订正 8 个 SSE),补充:
- DeepReadOutput 完整 schema fixture(含所有字段边界值)
- 7 个 writer tool 的 input/output fixture
- retryClassifier 的 retryable/permanent 用例

---

## 10. 风险与未决项

### 10.1 P0 风险

| ID | 风险 | 缓解 |
|---|---|---|
| R-BG-1 | 后台生成不可行(0.2) | 已转化为前台+WorkScheduler 策略(§6) |
| R-NATIVE-1 | Rust NAPI + aarch64-linux-ohos 交叉编译 | 阶段 1 用 ArkTS fallback,不阻塞 MVP(§5.2) |
| R-SSE-1 | RCP chunk 边界处理 | SseAssembler 显式 buffer(§8.2) |
| R-ARK-1 | ArkTS 禁运行时改对象布局,UIMessage streaming 合并复杂 | appendChunk/merge/findToolMergeTarget 用纯函数 + 不可变 interface |

### 10.2 P1 风险

| ID | 风险 | 缓解 |
|---|---|---|
| R-MD-1 | 鸿蒙 markdown 渲染生态不成熟 | MVP 用 RichText + 轻量 parser,或自建 |
| R-ABORT-1 | AbortController 不自动向下传播 | 每 async 调用点显式检查 signal.aborted |
| R-OBSERVE-1 | 1s 轮询延迟,section-by-section 体验不如 Flow | MVP 接受,后续可上事件总线 |
| R-WORK-1 | WorkScheduler 非实时调度,护航可能等很久 | UI 明示"回 app 才能继续",WorkScheduler 是 best-effort |

### 10.3 未决项(实现时再定)

- WorkScheduler 在 API 24 模拟器上的实际调度延迟(需 spike)
- ArkUI 杂志风文章排版的性能(长文章渲染卡顿?)
- 鸿蒙 markdown 库选型(若无合适,自建到什么粒度)

---

## 11. 实现阶段划分(给 writing-plans 的输入)

1. **Phase 1 - Domain + Platform skeleton**:DeepReadModels / 枚举 / 辅助函数 / RDB schema / preferences / HttpClient / SseAssembler / JsonSchemaValidator(全单测)
2. **Phase 2 - AiClient + UIMessage + tool loop**:UIMessagePart discriminated union / appendChunk / merge / AiClient 含 budgetPrompt+retryClassifier+accumulator / tool-result 构造路径
3. **Phase 3 - Prefetch + ResearchHarness**:SearchProvider 多实现 / reader-extractor ArkTS fallback / buildEvidencePack / generateArticlePlan
4. **Phase 4 - SectionWriterTools + SupervisorLoop**:7 个 tool 完整 schema / runStageSupervisorLoop / tryFallbackAfterStageFailure
5. **Phase 5 - RunManager + Scheduler**:run/runSection/generateStages/finishIfPossible/scheduleBackgroundFill + 前台主路径 + 中断检测
6. **Phase 6 - UI**:3 个 ArkUI 页面 + 文章渲染组件 + 进度 UI
7. **Phase 7 - WorkScheduler 护航 + 通知**:DeepReadWorkSchedulerAbility / notifier / Want 契约
8. **Phase 8 - Rust NAPI reader-extractor**:阶段 2 升级,替换 ArkTS fallback

---

## 附录 A:Android 源码引用

所有关键行为的源头 file:line(基于 harmony-preview worktree):

- 状态模型:`feature/board/impl/src/main/kotlin/app/amber/feature/board/hotlist/deepread/DeepReadModels.kt`
- supervisor loop:`app/src/main/java/app/amber/feature/board/hotlist/deepread/DeepReadAgentRunManager.kt`
- 7 个 tool:`app/src/main/java/app/amber/feature/board/hotlist/deepread/DeepReadSectionWriterTools.kt`
- GenerationHandler:`app/src/main/java/app/amber/core/ai/GenerationHandler.kt`
- UIMessage:`ai/src/main/java/me/rerere/ai/ui/Message.kt`
- retry classifier:`core/ai/api/.../GenerationRetry.kt`
- budget prompt:`feature/runtime/api/.../AgentLoopBudgetPrompt.kt`
- prefetch:`app/src/main/java/app/amber/feature/board/hotlist/deepread/DeepReadSourcePrefetcher.kt`
- worker/retry policy:`app/src/main/java/app/amber/feature/board/hotlist/deepread/DeepReadWorker.kt`
- reader-extractor JNI:`native/reader-extractor/src/lib.rs`

## 附录 B:HarmonyOS 文档引用

- ArkTS 约束:https://developer.huawei.com/consumer/cn/doc/HarmonyOS-Guides/typescript-to-arkts-migration-guide
- ArkData RDB:https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/data-persistence-by-rdb-store
- Remote Communication Kit(RCP):https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/remote-communication-introduction
- ContinuousTask:https://developer.huawei.com/consumer/cn/doc/HarmonyOS-Guides/continuous-task
- TransientTask:https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/transient-task
- WorkScheduler:https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/work-scheduler
- UIAbility 生命周期:https://developer.huawei.com/consumer/cn/doc/HarmonyOS-Guides/uiability-lifecycle
