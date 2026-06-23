# @amber/deepread-domain

Deep Read 鸿蒙版的 Domain 层。纯 ArkTS interface + 纯函数,零鸿蒙 SDK 依赖,零运行时 mutation(符合 ArkTS 约束)。

## 内容

- `models.ts` — `DeepReadOutput` 及全部子 interface
- `enums.ts` — stage/phase/status/quality 类型 + 常量(timeout/limits)
- `helpers.ts` — `isComplete`/`sectionsReady`/`hasAnyReadySection`/`withInferredSectionStates`/`displayHeroImageUrl`/`displayHeroCaption`/`hasReadableArticle`/`hasEnoughChinese` 等
- `topic_id.ts` — `deriveTopicId`(URL 优先 hash)

## 测试

```bash
cd harmony/deepread
npm install
npm test
```

测试用 node + tsx,不需要 DevEco/模拟器。

## ArkTS 约束

- 禁 `any`/`unknown`(catch clause 除外)
- 禁运行时增删属性 → 用 `{...obj, field: newVal}` 不可变更新
- enum-keyed Map → `Record<Stage, T>`
- 所有 interface 用工厂函数(`makeEmptyDeepReadOutput`)而非 class

## 设计决策

- **不移植 verificationState / VERIFYING phase / VERIFIED quality**:Android 生产代码里这些是死代码(只在 test 调用,生产从不赋值)。鸿蒙版忠实于 Android 生产语义,不发明新字段。
- **topic_id 用 URL 优先 hash**:Android 两条路径(hot-list / template-demo)都不适合独立 app,鸿蒙版统一规则:URL 入口基于 URL,话题入口基于 title,故意不共享 cache。
- **hasReadableArticle 用 AND 逻辑**:timeline AND corePoints AND analysis 三项都必须通过(Android DeepReadModels.kt:321)。

## 后续

Plan 1.2 处理 Platform skeleton(interfaces + RDB schema + SSE assembler + JsonSchemaValidator)。
crypto adapter 替换 node `crypto` 为 `@ohos.security.cryptoHash` 在 Platform 层注入。

## Platform 层(Phase 1.2 — 已完成)

- `platform/http.ts` — HttpClient interface(RCP 封装契约,实现留后续)
- `platform/database.ts` — Database interface + `DEEP_READ_SCHEMA_SQL`
- `platform/storage.ts` — Storage interface(preferences + Asset Store)
- `platform/notifier.ts` — Notifier interface(3 类通知契约)
- `platform/search.ts` — SearchProvider interface
- `platform/ai_client.ts` — AiClient interface(UIMessage 占位待 Plan 2)
- `platform/repository.ts` — DeepReadRepository interface
- `platform/observable.ts` — `Observable<T>` + 轮询实现 ✅
- `platform/sse_assembler.ts` — `SseAssembler`(ArrayBuffer chunk 边界 buffer) ✅
- `platform/json_schema_validator.ts` — `JsonSchemaValidator`(轻量) ✅

后续:RcpHttpClient / RdbDatabase / AssetStorage / 真实 SearchProvider 实现等 DevEco 工具链就绪。

## Agent 层(Phase 2 — 已完成)

- `agent/message.ts` — `UIMessage` + `UIMessagePart`(kind discriminated union:text/image/reasoning/tool)
- `agent/append_chunk.ts` — `appendChunk`(流式 delta 合并 + Tool merge)
- `agent/budget_prompt.ts` — `AgentLoopBudgetPrompt`(WARN/TIGHT/FINAL 步数预算)
- `agent/retry_classifier.ts` — `RetryClassifier`(retryable/permanent 分类 + 指数退避)
- `agent/tool_execution.ts` — `executeToolsAndMerge`(tool result 写回原 Tool part,闭合 agent loop)

## Feature/Orchestration 层(Phase 4-5 — 已完成)

照搬 Android `DeepReadSectionWriterTools.kt` + `DeepReadAgentRunManager.kt`。整个 Deep Read 生成逻辑闭环(从 prefetch 到完整 `DeepReadOutput`)纯 node 可测。

- `agent/section_writer_tools.ts` — `createSectionWriterTools` factory(闭包状态,ArkTS 友好)。7 个 tool:overview/narrative/analysis/extended_reading(段落 writer,hasXxxContent gate → READY+STANDARD)+ visuals(P1-1 hero gate:hero_url 仅当候选池 confidence===hero 才接受)+ diagram(type 白名单/nodes≥2/edge 校验)+ finish(三门闩锁)。cleanText/safeTake(UTF-16 代理对安全截断)/merge 辅助。
- `agent/supervisor_loop.ts` — `runStageSupervisorLoop`(2-pass 循环,cardsFor(stage,plan) evidence 路由,withTimeout race,supplementWritten gate,reminder,no-write→fallback)+ `buildPrompt`(10 段 prompt 组装)+ `buildWriterReminder`+ `collectRun`(`AiClient.generateText` 薄封装,可注入)。
- `agent/try_fallback.ts` — `tryFallbackAfterStageFailure`(latestAssistantText → writeFallbackSection → READY BASIC)。
- `agent/run_manager.ts` — `run`/`runSection`/`generateStages`(顺序 if:OVERVIEW→NARRATIVE→ANALYSIS→EXTENDED_READING,不是循环)/`createRunContext`(空 prefetch → hard fail P1-9)/`finishIfPossible`。依赖全注入(prefetcher/aiClient/repository/collectRun/onWriterCreated)。
- `agent/scheduler.ts` — `DeepReadScheduler` interface + 前台主路径实现(topicMutex REPLACE 语义、runningJobs Map、abort、observeRunning derive、detectInterrupted、setBackgrounded)。后台 `@kit` API(ContinuousTask/notificationManager/UIAbility 生命周期)留接口,标注需运行时(Phase 6-7 接)。

### Phase 5 验收

用 mock prefetcher + mock collectRun(`onWriterCreated` 捕获 writer 触发写入)跑通完整 `run()` → 产出 `isComplete()=true` 的 `DeepReadOutput`(4 stages 全 READY,phase COMPLETE)。440 tests pass。

## Research 层(Phase 3 — 已完成)

研究阶段管线:多 provider 搜索 → 正文提取 → 图片评分 → evidence 分桶 → 文章规划。全部纯 node 可测(mock HttpClient / mock AiClient 注入)。

- `research/url_filter.ts` — `urlAllowedForBackgroundFetch` + `isPrivateUrl`(私网 SSRF gate,照搬 Android `ToolRegistry.isPrivateNetworkTarget`:loopback/LAN/CGNAT/IPv6 ULA/mDNS TLD)
- `research/reader_extractor.ts` — `extractReadableTextArkTs`(ArkTS regex fallback,照搬 Android `extractReadableTextJvm`:剥 script/style/svg/canvas、block 换行语义、HTML 实体反转义、≥18 字符行过滤 + distinct 去重)+ `extractReadableText`(双路径:优先 native NAPI,失败/空/短 → ArkTS fallback)。Plan 8 升级 Rust NAPI。
- `research/image_scorer.ts` — `scoreImageCandidate` / `scoreAndDedup`(MVP 评分模型,但 reject 路径忠实 Android `DeepReadImageScorer` 的 URL/quality risk flags + `hardRejectRisks` 集合:too_small/site_brand_asset/tracking/icon_format/tiny_file 等;hero gate 要求 score≥60 + 宽高比 0.75..2.0)
- `research/search_provider.ts` — `createTavilyProvider`(真实 Tavily /search POST,Bearer 认证)+ `createFallbackProviders`(jina_fallback,无 key,Android 兜底行为)
- `research/search_merger.ts` — `interleaveSearchResults`(多 query bucket round-robin 合并,跨/内 bucket URL 去重,cap `MAX_SEARCH_RESULTS`=14)
- `research/source_prefetcher.ts` — `createSourcePrefetcher` factory(36s wall budget 并行预取 + LRU cache 10min/16 entries + seed URL 低阈值优先 + 并行 scrape + URL 去重 + cap `MAX_SOURCES`=12;空 prefetch 返回空数组由 caller 决定 hard-fail)。ArkTS 友好(闭包捕获 cache,非 class mutation)
- `research/evidence_pack.ts` — `buildEvidencePack`(本地分桶 + `requiredSourceIds`)+ `cardsFor(stage, plan)`(P0-D:消费 `plan.stageSourceIds` 路由 evidence,`forced + planned.ifEmpty{stageBucket}`,distinct,cap `STAGE_EVIDENCE_MAX`=6)+ `sourceIdsFor`(stageKey/lowercase/UPPERCASE 三键匹配)
- `research/article_plan.ts` — `generateArticlePlan`(1 次非流式 LLM 调用 + fallback)+ `normalizePlan`(**过滤 LLM 幻觉 sourceId**,mergeWithFallbackIds 补足 `STAGE_EVIDENCE_MIN`)+ `parsePlanJson`(容忍解析:整文本 → 代码块 → 字符串/转义感知大括号平衡)+ `fallbackPlan` / `buildPlanningPrompt`

### 关键 Android 保真度决策

- **私网 gate(P1-8)**:完整移植 Android `isPrivateNetworkTarget`(CGNAT 100.64/10、IPv6 ULA fc/fd、`.local`/`.internal`/`.lan` TLD),非 plan 的简化子集
- **reader-extractor 双路径(P0-8)**:ArkTS fallback 就位,Plan 8 注入 NAPI;native 返回 null / <18 字符 / throw 时 fallback
- **LRU cache(P0-9)**:TTL 10min、16 entries、evict-oldest
- **cardsFor 消费 stageSourceIds(P0-D)**:忠实 Android `forced + planned.ifEmpty` 语义,跨 stage 查找
- **幻觉 id 过滤**:`normalizePlan` 过滤掉 LLM 编造的不在 pack 已知 sourceIds 中的 id
- **空 prefetch hard-fail(P1-9)**:`collect` 返回空数组,由 caller 决定是否 hard-fail(非发明 `evidence_quality:LOW`)

后续:RcpHttpClient / OpenAiCompatibleAiClient 真实实现(Plan 5)、Rust NAPI reader-extractor(Plan 8)、其他 search provider(Zhipu/Brave/Exa)、OG image 提取。
