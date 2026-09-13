# Batch 4 — DeepRead 取消贯穿 / 通知所有权 / 降级证据 / 重进与显示设置

范围:R20(P2)、R22(P2)、R24(P3)、UI-F30(P1)、UI-F29(P2)、UI-X01(P2)。
只改本批分配文件;未动 `deepread/index.ts`、`AppContainer.ets`、`ChatPage.ets`(主代理独占)。

## 修改文件

| 文件 | 修复 | 变更概要 |
|---|---|---|
| `harmony/deepread/src/main/ets/agent/run_manager.ts` | R20/P2-2/P3-1 | prefetch/plan 接 signal;取消只认 `signal.aborted`/`AbortError.name`(删 `/abort\|cancel/i` 宽判);持久化守卫 `saveGuarded`;取消统一返回 `{ok:false,error:'aborted'}`;非 force 续跑只生成非 READY 段;markCollecting 移入 try |
| `harmony/deepread/src/main/ets/agent/scheduler.ts` | R20/R22/UI-F30/P2-3 | run/runSection catch 归一 aborted;完成通知 `await` 且**仅成功**才不撤同 id(失败仍撤);`cancelRunning` 带 job token;新增 `decideReentryAction` |
| `harmony/deepread/src/main/ets/research/source_prefetcher.ts` | R20 | `collect(..., signal?)`;provider/HTTP 透传 signal;`withDeadline` 支持 abort 快速拒绝;取消后不写 LRU |
| `harmony/deepread/src/main/ets/research/article_plan.ts` | R20/R24 | `generateArticlePlan(..., signal?)` 接 signal;`fallbackPlan` 从全局卡池补满各段 |
| `harmony/deepread/src/main/ets/research/search_provider.ts` | R20 | Tavily `search(queries, signal?)`,HTTP fetch 带 signal,取消即停不再当 provider 失败 |
| `harmony/deepread/src/main/ets/platform/search.ts` | R20 | Port:`SearchProvider.search(queries, signal?)` |
| `harmony/deepread/src/main/ets/platform/notifier.ts` | R22 | Port:`notify*`/`cancelRunning` 增可选 `runToken` |
| `harmony/entry/src/main/ets/platform_impl/NotificationNotifier.ets` | R22/P2-3 | `notify*` await 真实 publish;按 topic 记录 ownerToken;`cancelRunning` token 不匹配即 no-op;`cancelNotification` 返回 Promise;新增 `publishBasicNotificationStrict`,仅 `notifyCompleted` 用严格发布(失败 reject),普通通知 best-effort 不变 |
| `harmony/entry/src/main/ets/platform_impl/SearchRegistry.ets` | R20 | 7 个 provider(Bocha/Brave/Exa/SearXNG/DuckDuckGo/HackerNews/Jina)加 `search(queries, signal?)`,循环前 `isAborted` 早退、HTTP 传 signal、取消后 catch 不再当 provider 失败 |
| `harmony/entry/src/main/ets/platform_impl/NewsNowProvider.ets` | R20 | `search(queries, signal?)`/`fetchSource(.., signal?)`/`fetchAllSources(signal?)`,HTTP 传 signal;既有调用方不传参不变 |
| `harmony/entry/src/main/ets/platform_impl/DeepReadTemplate.ets` | UI-F29 | 字号改 `calc(px * var(--ds))`;`renderEditorialSlantHtml(title,o,opts?)` 注入 `--ds` 与 `body.ds-serif` |
| `harmony/entry/src/main/ets/pages/DeepReadArticlePage.ets` | UI-F30/X01/F29 | 显示设置先于缓存早退;重进仅订阅/显式继续;`awaitingContinue` UI;模板 zoom 重载 HTML(保持 `javaScriptAccess(false)`) |
| `harmony/deepread/src/test/scheduler_signal.test.ts` | 回归 | 取消归一、REPLACE token 守卫(改法)、完成不误撤、`decideReentryAction` |
| `harmony/deepread/src/test/run_manager.test.ts` | 回归 | +R20 预抓窗口取消 |
| `harmony/deepread/src/test/article_plan.test.ts` | 回归 | +R24 降级计划各段非空(经真实 `cardsFor`) |
| `docs/.../probes/probe-batch4-notifier.mjs` | R22 | 真实 `.ets` 转译 + deferred 平台,7 断言 |
| `docs/.../probes/probe-batch4-template.mjs` | UI-F29 | 真实 `.ets` 转译,9 断言 |

`harmony/deepread/src/main/ets/platform/http.ts` 的改动是**其他 owner 既有**的 R18 `shouldStop`,本批未触碰。

## 最小机制

### R20 — 取消贯穿 prefetch→plan→HTTP,无原始 abort message 宽判
- Signal 链路:`scheduler.abort(topic)` → per-job controller.signal → `run`/`runSection` opts.signal → `generateStages` → `createRunContext` → `prefetcher.collect(..., signal)` → `SearchProvider.search(queries, signal)` / `HttpClient.fetch(req, {signal})`;规划期 `generateArticlePlan(..., signal)` → `ai.generateText({signal})`。
- `withDeadline(deadline, p, signal)`:signal 已 abort 立即 reject `AbortError`;等待中监听 abort 快速拒绝,settle 后 `removeEventListener` 防泄漏。`collect` 在每个 await 窗口后复查 `signal.aborted`,取消即返回 `[]`,不写缓存。
- **下游 provider 真取消(不只 deadline race)**:entry 的 `SearchRegistry.ets` 7 个 provider 与 `NewsNowProvider.ets` 均加 `search(queries, signal?)`,循环首行 `if (isAborted(signal)) break;`,HTTP 用 `http.fetch(req, signal === undefined ? undefined : { signal: signal })` 透传(与 `OpenAiCompatibleAiClient.ets` 既有写法一致);catch 内 `isAborted(signal)` 时不当作 provider 失败继续。`fetchSource/fetchAllSources` 同步加可选 signal,既有调用方(BoardPage 不传)不变。**未引入 `fetchOpts` 返回对象字面量的 helper**(ArkTS 匿名类型报错),改为两处内联三元。
- **判定收窄**:删除 `isAbortError` 的 `/abort|cancel/i.test(message)` 宽判。仅 `signal.aborted` 或 `error.name === 'AbortError'` 视为取消;普通业务失败 message 含 "cancel" 不再被误吞。
- **持久化前守卫**:`saveGuarded` 每次 `repository.save` 前复查 signal,已取消则抛 `AbortError`,不落 `COLLECTING`/`WRITING+RUNNING` 等中间态(消除了原报告的 `COLLECTING:- / WRITING:RUNNING` 残留写)。
- **统一 aborted 结果**:`scheduler.run/runSection` catch + `run_manager.run/generateStages` 均返回 `{ok:false, output:取消前已持久化态, error:'aborted'}`,不再以 rejection 泄露。

### R22 — 通知所有权
- `NotificationNotifierImpl` 为每 topic 记录 `ownerToken`(`notifyRunning/notifyCompleted/notifyFailed` 带 token 时 claim);`cancelRunning(topicId, runToken)` 在 `owner !== runToken` 时直接返回 —— 旧 run 迟到的 finally **不能**撤新 run 同 id 通知。
- `notifyCompleted` **await** 真实 publish(平台落地后才 resolve);scheduler 仅在其**成功**时置 `completedPublished`,`finally` 也**只在成功**时不撤同 id。取消 `'aborted'`、notifyRunning 抛错、以及**完成发布失败(P2-3)** 都会撤掉 ongoing,不留永久「正在深度阅读」。
- **P2-3 严格/尽力分离**:新增 `publishBasicNotificationStrict`(失败 reject,带日志),**仅** `notifyCompleted` 用它;`publishBasicNotification` 保持吞错 resolve 的 best-effort,`notifyGenerationDone/Failed/Cron*/dream/MemoryStore/novel-polish` 等 fire-and-forget 调用方语义不变(仍不会 unhandled)。
- scheduler 侧 `try { await notifyCompleted; completedPublished = true } catch { completedPublished = false }`;通知失败**不影响**深读结果(仍返回原 `finalResult`)。
- `cancelNotification` 由 `void` 改为返回 `Promise<void>`,取消也 await。

### R24 — 降级计划全局补证
`fallbackPlan` 先取本段 bucket,再按 `STAGE_ORDER` 汇总的全局池补到 `STAGE_EVIDENCE_MAX`;`requiredSourceIds` 随之来自补满后的并集。规划 LLM 失败(走 fallback)时,后段(ANALYSIS/EXTENDED_READING)不再 0 证据。复用 `mergeWithFallbackIds` 同一全局池语义,未新增框架。

### UI-F30 — 重进不隐式 REPLACE
`loadArticle` 顺序:读缓存 → **先读显示设置** → 首用确认 → 订阅 → 判定:
- `force`(用户点重新生成)→ 跑(唯一覆盖旧轮路径);
- 完整缓存(`COMPLETE`/`isComplete`)→ 仅展示;
- `scheduler.isRunning` → 仅订阅,等自己收口;
- 有进度痕迹的部分/取消缓存 → 置 `awaitingContinue`,**不** 自动发起新 run;UI 提供「继续生成 / 重新生成」二选一;
- 无进度空壳/无缓存 → `runNow(force=false)` 正常启动。

**首次流程未被裁剪(明确澄清)**:`first → idle` 不等于「不生成」。首用确认门在重进判定**之前**:`confirmed !== 'true'` 时 `running=false` 返回,只显示首用确认页(与改动前一致,**无生成、无通知**);用户点「开始使用」写入 KV 后重跑 `loadArticle`,`confirmed` 为真且无缓存 → 走 idle 分支 → `runNow(token,false)` **生成**。即「用户明确选择(首用确认点击)之后才生成」的语义完整保留;只有显式的部分/取消缓存才会停在 `awaitingContinue` 等待用户再点「继续生成」。

`decideReentryAction(isRunning, cached)` 作为纯函数契约导出(`subscribe | complete | idle`),判定顺序与页面一致(先完整缓存,再活动 run,再进度痕迹),供未来页面复用/测试。**页面当前不 import 它**(内联等价逻辑),故不存在 missing import;`DeepReadRepository` 已由 index `export type` 导出,页面 import 正常。

### P2-2 — 「继续生成」只补非 READY 段
复核确认:非 force 续跑原先无条件跑全 4 段,已 READY 段仍会各发一次 collectRun 且可能被模型改写。修法在 `run_manager.run`(非 force 且有缓存)内:
```ts
const missing = STAGE_ORDER.filter(s => statusOf(inferred, s) !== 'READY');
if (missing.length > 0) stagesToRun = missing;
```
`inferred = withInferredSectionStates(cached)`,复用既有 `statusOf`/`STAGE_ORDER`,**不 overwrite READY 段**;`force`(用户「重新生成」)仍传全 `STAGE_ORDER` 全量;`sectionsReady`/`isComplete` 早退保护保留在前。首次(无缓存)与全未完成缓存 `missing` 即 4 段,语义不变。

### P3-1 — markCollecting 守卫移入 try
`generateStages` 的 `saveGuarded(COLLECTING)` 移入 `try` 块,catch 的 `isAborted → abortedResult()` 兜住该窗口;直调 `run_manager.run` 也不再以 rejection 出栈(不再只依赖 scheduler 兜底)。

### UI-X01 — 显示设置先于缓存早退
`loadDisplaySettings()` 上移到缓存分支之前;完整缓存返回前也已应用 `fontScale/fontSerif/templateId`。完整缓存路径不再触发任何生成。

### UI-F29 — 模板消费显示偏好(安全 CSS,保持 js=false)
`CSS_EDITORIAL` 全部字号改为 `calc(px * var(--ds))`,默认 `--ds:1` 与原样式逐字等价;`renderEditorialSlantHtml` 注入 `:root{--ds:<n>}` 与 `body.ds-serif`。`fontScale` 经 `clamp(0.8,1.3)` + `toFixed(3)` 只作数值注入,无字符串注入面;字体模式只切预定义 class。页面 zoom 按钮在 Web attach 后重 `loadData` 刷新,`javaScriptAccess(false)` 不变。

## 上游 Port 签名变更(给主代理 / 其他 owner)

1. `SearchProvider.search(queries: string[], signal?: AbortSignalLike): Promise<SearchHit[]>` — **+可选 signal**。
   - deepread 侧:`search_provider.ts` Tavily 已接 signal 并透传 HTTP。
   - entry 侧:`SearchRegistry.ets` 7 个 provider 与 `NewsNowProvider.ets` 已全部接 signal + 透传 HTTP(本批 4B 追加;见上 R20)。既有仅接 `(queries)` 的第三方实现仍是合法实现(TS 结构少参可赋值)。
2. `SourcePrefetcher.collect(topicId, title, seedUrl, force, signal?)` — **+可选 signal**。唯一生产调用方 `run_manager.createRunContext` 已传。
3. `NewsNowProvider.fetchSource(preset, signal?)` / `fetchAllSources(signal?)` — **+可选 signal**;`BoardPage`(不传)/`HotListAggregator` 行为不变。
4. `Notifier.notifyRunning/notifyCompleted/notifyFailed(topicId, title, ..., runToken?)`、`cancelRunning(topicId, runToken?)` — **+可选 runToken**。`createNotificationNotifier()` 已实现;其他实现(若有)少参仍兼容。
5. `SchedulerDeps.notifier` 三方法同步加 `runToken?`(deepread 内部类型,`AppContainer` 传 `container.notifier` 无需改)。
6. `generateArticlePlan(ai, model, title, pack, playbookMd, signal?)` — **+可选 signal**;既有调用/测试不传仍可用。
7. `HttpClient.fetch` 早有 `opts.signal`,无变更。

### index.ts 接线(主代理,本批未改 index)
`decideReentryAction` / `DeepReadReentryAction` 目前由 `harmony/deepread/src/main/ets/agent/scheduler.ts` 导出。若要让 entry 页面复用,请在 `harmony/deepread/src/main/ets/index.ts` 第 73-74 行改为:
```ts
export { createScheduler, isInterruptedPhase, decideReentryAction } from './agent/scheduler.ts';
export type { DeepReadScheduler, SchedulerRunOptions, DeepReadReentryAction } from './agent/scheduler.ts';
```
页面当前不 import 该符号(内联逻辑),故**不改 index 也不影响构建**。

## 测试与校验

```
cd harmony/deepread
npx tsc --noEmit -p tsconfig.json                 # 0 error
npx tsx --test "src/test/**/*.test.ts"            # 782 tests, 782 pass, 0 fail
node ../docs/reviews/2026-09-13-harmony-repair/probes/probe-batch4-notifier.mjs   # ALL PASS (9)
node ../docs/reviews/2026-09-13-harmony-repair/probes/probe-batch4-template.mjs   # ALL PASS (9)
```

新增/改写用例(不复制测试框架):
- `scheduler_signal.test.ts`:取消归一为 aborted resolved 结果(替换原 reject 断言);REPLACE token 守卫改为「A 迟到 finally 后 B 仍注册且无可取消 token」;+完成通知不被同 id cancel;+**P2-3 完成发布失败仍撤 ongoing 且不污染 run 结果**;+`decideReentryAction` 四态。
- `run_manager.test.ts`:+R20 预抓窗口取消 → `{error:'aborted'}` 且仓库**零写入**(不落 RUNNING);+**P2-2 续跑只补非 READY 段(OVERVIEW 不重跑)**、**force 仍全 4 段**。
- `article_plan.test.ts`:改写原「analysis 空」断言为补满;+R24 六卡仅首段时四段 `cardsFor` 均非空。
- `probe-batch4-notifier.mjs`:+2 用例(平台 publish reject → `notifyCompleted` reject 且不标 visible)。

静态检查(`harmony/scripts/lint-arkts.sh` 逐文件):`scheduler.ts`/`run_manager.ts`/`source_prefetcher.ts`/`article_plan.ts`/`search_provider.ts`/`platform/search.ts`/`platform/notifier.ts`/`NotificationNotifier.ets`/`DeepReadTemplate.ets`/`DeepReadArticlePage.ets`/`SearchRegistry.ets`/`NewsNowProvider.ets` 均 `No defects found`,error 级 0。
按契约**未跑** hvigor 构建/全仓 lint/设备(由主代理统一串行);未改 `deepread/index.ts`。

## 未解决点

- **`decideReentryAction` 未经 index 导出**:`deepread/index.ts` 主代理独占,本批未改;页面内联等价逻辑,无功能缺口。接线片段见上「index.ts 接线」。
- UI-F30/F29 未做设备截图验证(契约要求主代理统一验证),不声称 UI 已通过。
- **entry provider 单测**:`SearchRegistry.ets`/`NewsNowProvider.ets` 无对应 node 测试套件(entry 侧为 ArkTS),其取消行为未加自动化回归;与既有页面/provider 同属「主代理统一构建+设备」验证范畴。

## 复核修复(第二轮,review-batch-45 P2-2/P2-3/P3-1)

- **P2-2**:`run()` 非 force 续跑只传非 READY 段(见上);两条回归:OVERVIEW 已 READY 时 collectRun 不含概览、force 仍 4 段。
- **P2-3**:`publishBasicNotificationStrict` + `notifyCompleted` 严格发布 + scheduler `completedPublished` 仅在成功时置位;完成发布失败 → finally 仍撤 ongoing。回归:probe 9/9、scheduler +1。
- **P3-1**:markCollecting `saveGuarded` 移入 `try`,取消归一为 aborted 不再依赖 scheduler 兜底。
- 说明:以上均为「修得不完整」补齐,**未改变** API12/V1、未新增框架、未动 AppContainer/index;`publishBasicNotification`(best-effort)既有 fire-and-forget 调用方全部保持原语义。
