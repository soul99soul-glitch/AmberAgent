# review-xag — 独立反证复核：topic-x-domains.md + topic-ag-platform-ui.md

- 审查对象：workspace 当前工作树（含未提交改动），分支 `harmony-preview`，HEAD `bdce551dd373f3d7e4c393b194997cae19289c40`。业务代码只读；本报告只写入本目录。
- 复核方式：逐条重读原报告所述实现 → 亲读调用链上下游 → 对可离线复现项跑真实 `.ts` 逻辑隔离 probe（`probes-h/` 已有 + `probes-xag/` 新增）→ 无设备项只用本地 SDK `.d.ts` / 官方文档，不臆断 UI。
- 结论口径：`确认` / `降级` / `假阳性` / `待验证`。严重度按“真实用户影响 × 发生前提”重估，不因“与 Android 不同”单独判 bug。
- 新增 probe（真实 deepread 域逻辑，无网络/模型/文件）：
  - `probes-xag/probe-h1-normalize.ts`
  - `probes-xag/probe-h10-notifier.mts`（调用顺序，已被 §4 addendum 修订解读）
  - `probes-xag/probe-h10-real.mjs`（**H-10 正式证据**：本地 TypeScript transpile 真实 `NotificationNotifier.ets` + 注入 `@kit.*` mock）
  - `probes-xag/probe-h10-race.mts`（手写转写，**仅说明用途，不作证据**，见 §4）

---

## 0. 裁决总表

| 原 ID | 原判 | 复核裁决 | 重估严重度 | 一句话理由 |
|---|---|---|---|---|
| H-1 | P1 高 | **降级（≠常态）** | P3 | 只有 article-plan LLM 解析失败走 `fallbackPlan` 才饥饿；成功计划经 `normalizePlan` 每段从**全局 allIds 补满**，不饥饿。原报告只测了 fallback 分支。 |
| H-2 | P2 高 | **确认（范围收窄）** | P2 | 预抓无 signal + abort rejection 逃出 `RunResult` 契约属实；但唯一调用方 `DeepReadArticlePage` 有 `.catch`，无未处理拒绝实证；`detectInterrupted` 在 entry 无消费者。 |
| H-3 | P2 高 | **假阳性（有意降级）** | — | 即 PARITY_DEBT PD-001，已登记“降级文 > 直接失败”，不能只因 Android hard-fail 判 bug。残留：`run_manager.ts:8` 头注释过期（doc nit）。 |
| H-4 | P2 高 | **部分确认** | P2(开关) / P3(竞态) | 无视内置源开关属实（对上游 `searchEnabledServiceIds` 无对照）；就绪竞态窗口极小、无实例。 |
| H-5 | P3 高 | **确认（低）** | P3 | `outputBudgetChars` 不落 `max_tokens` 属实；但 Android 同款也只有本地预算，不构成鸿蒙独有 parity 缺口。 |
| H-6 | P3 中 | **确认（trivial）** | P3 | 终结后 `subscribeLive` 新建的 `listeners[runId]` 不被清理属实；影响仅为每看一次已结束议会驻留一个小 map。 |
| H-7 | P3 高 | **降级/不报** | — | 仅注入时钟一致性（测试可注入性），无用户可见实例；按要求不报时钟注入测试差异。 |
| H-8 | P3 中 | **待验证** | P3 低置信 | 代码路径成立（IO 失败静默空库），但未构造到实例，且 parse 损坏已有 quarantine。 |
| H-9 | P3 中 | **假阳性/不报** | — | 32 位哈希碰撞无实例；按要求不报概率碰撞。 |
| H-10 | P3 高 | **确认（改判为竞态）** | **P2（中置信）** | `notifyCompleted` 不 await `publishBasicNotification`、`cancelNotification` 也是 void → `scheduler` 的 await 不固定平台次序；同一 id 的 publish/cancel 竞态，可能撤回完成通知/旧轮撤新轮。**非“每次正常必消失”**。 |
| AG-1 | P1 | **待验证（机制可信，可见性未证）** | P2-P3 待设备 | ArkUI 观察粒度未设备证伪；不因 `export let` 自动判可见 bug。静态面 173 个 tokens 引用者仅 12 个订阅 darkMode 属实。 |
| AG-2 | P2 高 | **确认** | P2 | `onNewWant` 只写 AppStorage 不导航；唯一消费者 `ChatListPage`；前台栈顶非根页时丢弃。 |
| AG-3 | P1 高 | **确认** | P1 | `chatStorageDegraded` 零消费者 + `ensureStore` 失败每操作重试；RDB 失败→内存库→重启丢会话。 |
| AG-4 | P2 高 | **确认（修正计数）** | P2 | 74 注册 / 75 文件 / 直孤儿 **17**（非 22）；助手编辑子树为**传递**孤儿。 |
| AG-5 | P1 高 | **确认** | P1 | `markHostMounted` 全仓仅本文件内部引用，`open()` 必 5s 超时抛错。 |
| AG-6 | P0 | **确认但改判 P1** | P1 | 当前工作树确实编译失败（build.log 与现值逐行吻合）；按纠偏不作 P0，只作“构建阻断 ⇒ UI pending”前提。 |
| AG-7/8/9/11 | P3 | **确认（P3）** | P3 | 详见下。 |
| AG-10/12/13/14/15 | 无缺陷 | **确认** | — | 复核未见反例。 |

---

## 1. H-1 —— 证据分桶饥饿：**降级，原“常态/恒为 0”被反证**

### 关键反证（新 probe）

`probes-xag/probe-h1-normalize.ts`，真实 `evidence_pack.ts` + `article_plan.ts`，无 mock 逻辑：

```
fallbackPlan       n=6  cardsFor[OVERVIEW=6 NARRATIVE=0 ANALYSIS=0 EXTENDED_READING=0]
normalize(empty)   n=6  cardsFor[OVERVIEW=6 NARRATIVE=6 ANALYSIS=6 EXTENDED_READING=6]
normalize(ovOnly)  n=6  cardsFor[OVERVIEW=6 NARRATIVE=6 ANALYSIS=6 EXTENDED_READING=6]
normalize(all4)    n=6  cardsFor[OVERVIEW=6 NARRATIVE=6 ANALYSIS=6 EXTENDED_READING=6]
fallbackPlan       n=12 cardsFor[OVERVIEW=6 NARRATIVE=6 ANALYSIS=0 EXTENDED_READING=0]
normalize(empty)   n=12 cardsFor[OVERVIEW=6 NARRATIVE=6 ANALYSIS=6 EXTENDED_READING=6]
...
```

原报告 `probes-h/probe-evidence-pack.ts` 只喂了 `fallbackPlan`，因此得出了“卡片与 bucket 完全一致”的结论，把**一条降级路径**误推为“常态”。

### 上游真实消费链（亲读）

- 真实运行计划来自 `generateArticlePlan`：LLM 解析成功 → `normalizePlan`（`article_plan.ts:260`）；解析失败或抛错 → 直接返回 `fallbackPlan`（`:259`、`:261-263`）。**正常成功路径是 `normalizePlan`。**
- `normalizePlan` 的每段 `stageSourceIds` 经 `mergeWithFallbackIds(parsedIds, fallbackIds, allIds)`（`:189`, `:223-238`），其中 `allIds` 来自**四个 bucket 的并集**（`:175-178`）。不足 `STAGE_EVIDENCE_MIN=4` 时从**全局 allIds 补满至 `STAGE_EVIDENCE_MAX=6`**。
- `cardsFor` 的 `byId` 又由 `allCards(pack)` 跨全部 stage 汇总（`evidence_pack.ts:124-131,143-144`），因此 planned/forced 查找本就是**全局池**；`planned` 非空时不会回退到单段 bucket（`:156`）。这层全局 fallback 正是原报告漏看的保护。
- 现有测试已锁定该补满语义：`article_plan.test.ts:247-256`（“mergeWithFallbackIds fills to STAGE_EVIDENCE_MIN”）。
- writer 侧不自行取证据：`supervisor_loop.ts:238` 是 `cardsFor` 唯一消费点，结果进 `buildPrompt` 的 evidence 段（`:67`）；`section_writer_tools.ts` 不读 pack。所以“证据饥饿”只体现在 prompt 上，且只在 `planned` 为空 + bucket 为空时。

### 结论

- 饿死条件：`articlePlan === fallbackPlan(...)` **且** 来源数 ≤ 6（NARRATIVE≤0）/ ≤12（ANALYSIS、EXTENDED_READING=0）。即**规划 LLM 失败**的降级分支。
- `fallbackPlan.stageSourceIds` 取 `pack.cardsByStage[stage]`（`article_plan.ts:31-34`），而 `buildEvidencePack` 是**连续切片**（`evidence_pack.ts:109-115`），Android 的 `distributeStageCards/pickForStage` 是每段从**全量**按 `stageScore` 取 `target`（`DeepReadResearchHarness.kt:227-273`）→ 该分支确有 parity 缺口，但非“常态”。
- 原报告“最常见区间下 ANALYSIS/EXTENDED_READING 恒为 0”为**事实错误**；“现有测试恰好钉住错误行为”也只在 `evidence_pack.test.ts:97-107`（bucket 分布）成立，`article_plan.test.ts` 反而覆盖了正确补满。
- 最小修复不变（`fallbackPlan` 也应从全量池按 stage 相关度取），但优先级 P3；回归测试应改测 `generateArticlePlan→规划失败` 分支。

---

## 2. H-2 —— 预抓窗口取消：**确认，但只有“持久化中断态”与“契约外观”实证，无未处理拒绝**

- `SourcePrefetcher.collect(topicId,title,seedUrl,force)` 无 signal（`source_prefetcher.ts:105-112`）；`createRunContext` 直接 await（`run_manager.ts:91-97`）；`generateArticlePlan` 也不吃 signal（`:127`）。
- `runStage` 先落 `WRITING + section=RUNNING`（`run_manager.ts:231-237`），随后 `supervisor_loop.ts:258-262` 见 `signal.aborted` 抛 `Error('aborted')`；`run_manager.ts:208-210` 原样重抛；`scheduler.ts:138-161` 的 `try/finally` **无 catch** → `scheduler.run` 以 rejection 结束。
- probe（`probes-h/probe-abort-prefetch.mts`）复跑一致：
  ```
  saves = ["COLLECTING:-","WRITING:RUNNING"]
  result = {"threw":"Error: aborted"}   // 非 {ok:false,...,error:'aborted'}
  ```
- 反证/收窄：
  1. 唯一生产调用方 `DeepReadArticlePage.ets:175-187` 的 `await` 外层在 `:108-113` / `:297-302` 均有 `.catch`，并据 `this.cancelled` 显示“生成已取消” → **无未处理拒绝实证**；`runSection` 路径 `:341-353` 同样有 `.catch`。
  2. 残留 `COLLECTING`/`RUNNING` 的“中断态”当前**无任何消费者**：`grep detectInterrupted harmony/entry` 为空；相关 only 是 `DeepReadArticlePage.ets:201` 把 COLLECTING 显示为进度文案。用户重开文章会因 `isComplete=false` 重新 `generateStages`，不产生“假完成”。
- 结论：确认契约分叉（`Promise<RunResult>` 名不副实）+ 取消窗口不覆盖 prefetch/plan + 落中断态；用户影响主要为取消延迟（最坏 15s 预抓走完才退），P2 合理。修复：`scheduler.runImpl` 捕获 `isAbortError` 归一为 `{ok:false,error:'aborted',output:当前态}`；`collect`/`createRunContext` 接 signal。

---

## 3. H-4 —— SearchRegistry：**开关部分确认，竞态降为理论**

- `SearchRegistry.ets:344-377` 无条件 push `DuckDuckGoProvider/HackerNewsProvider/JinaProvider`，只对 key 源判 key；构造仅放 NewsNow 后 fire-and-forget `loadKeyedProviders()`（`:337-341`）；`enabled()` 只读缓存（`:380-382`）。装配点 `AppContainer.ets:129` 未传任何 settings。
- 上游对照（亲读 Android）：`DeepReadSourcePrefetcher.kt:82` 用 `settings.enabledDeepReadSearchServices()`，`:741-752` 以 `searchEnabledServiceIds` 过滤 + Bing/Jina fallback。**即 Android 尊重用户搜索源启用集合，鸿蒙 DeepRead 侧完全无视** → 开关缺口是真 parity gap（隐私/网络策略不一致、国内环境占预算）。
- 竞态：`source_prefetcher.ts:131` 同步读 `enabled()`。构造发生在 `getAppContainer()` 首次调用（应用启动早期多路径触发），实际 DeepRead 预抓要到用户进入文章并触发，窗口极小；且 `storage.get` 只 1-2 次 IO。无实例，降为 P3 理论项，保留“加 `ready()` 门”建议。
- 补充反证：`searchBuiltin*` 六个开关在聊天栈 `search_orchestrator.ts:178-191` 生效，但**没有任何消费方把这些设置传给 DeepRead registry**；属结构性双栈 settings 不统一，与热点源设置页（H-4 历史项）不同链。

---

## 4. H-10 —— 通知：**确认，但为“同 id publish/cancel 竞态”，非“每次必消失”（addendum 修订）**

> 本节已按主代理亲读纠正。原稿由 `probes-xag/probe-h10-notifier.mts` 的**调用顺序**（running→completed→cancel）误推为“每次正常成功完成通知必被撤回”；该推断默认了“调用顺序 = 平台落地顺序”，不成立。

### 关键事实（亲读源码）

- `NotificationNotifier.ets:155-159` `notifyCompleted` 是 `async`，但**只 `await buildArticleWantAgent`**；`publishBasicNotification(...)` **既未 `await` 也未 `return`**，其 Promise（`publishBasicNotification:112` 返回 `notificationManager.publish(...).catch(...)`）被丢弃。
- `:167-169` `cancelRunning` 是 `async`，但调用的 `cancelNotification(id)`（`:118-122`）是 **void fire-and-forget**，内部 `notificationManager.cancel(id).catch(...)` 不被等待。
- 故 `scheduler.ts:150` `await deps.notifier?.notifyCompleted(...)` 与 `:159` `await deps.notifier?.cancelRunning(...)` **只等到 wantAgent 构造**，两者返回时平台 publish 与 cancel 都还在飞行中；二者的**实际落地次序不被代码固定**。
- 三个入口（`notifyRunning:152` / `notifyCompleted:157` / `notifyFailed:163`）与 `cancelRunning:168` 共用同一 id `notificationIdFor(topicId)`。

### 竞态实证（真实 `.ets` + deferred mock，非转写）

`probes-xag/probe-h10-real.mjs`：用本地 TypeScript（`harmony/deepread/node_modules/typescript` 绝对路径）对**真实** `harmony/entry/src/main/ets/platform_impl/NotificationNotifier.ets` 做 `transpileModule`（该文件无 ArkUI struct/装饰器，可作普通 TS 编译），再经 `vm` + 自定义 `require` 注入 `@kit.NotificationKit` / `@kit.AbilityKit` / `@kit.PerformanceAnalysisKit` 等 mock；`wantAgent.getWantAgent`、`notificationManager.publish`、`notificationManager.cancel` 全部为 deferred，可**控制并观察**平台落地次序。调用真正的 `createNotificationNotifier()`：

```
=== A) 正常成功 (scheduler.ts:141/150/159 次序) ===
  [notifyRunning]   returned; queued now = ["publish:<id>"]
  [notifyCompleted] returned; queued now = ["publish:<id>","publish:<id>"]   // publish 尚未落地
  [cancelRunning]   returned; queued now = [...,"cancel:<id>"]              // cancel 尚未落地
  -> awaits 返回时 publish/cancel 都仍 pending（次序未被固定）
  platform settles [publish -> cancel] => visible = (none)          // 完成通知被撤回
  platform settles [cancel -> publish] => visible = 深度阅读完成     // cancel 空转,完成通知残留

=== B) REPLACE: 旧轮 A finally cancel vs 新轮 B notifyRunning(同 id) ===
  B running publish + A cancel queued = ["cancel:<id>","publish:<id>"]
  platform settles [publish -> cancel] => B running visible = (none)          // 新轮 ongoing 被撤
  platform settles [cancel -> publish] => B running visible = 正在深度阅读     // 新轮犹存
=== notificationIdFor(topic-1) = 1882569218（单一 id，四类通知共用） ===
```

证据成立：`await` 返回点不固定平台次序，同一 id 上 publish/cancel 构成真实竞态，两种落地次序均可复现。

> 说明：`probes-xag/probe-h10-race.mts` 是**手写控制流转写**（当时未直接驱动真实源码），**仅作说明用途，不作为证据**；H-10 的结论以上述真实 `.ets` probe 为准。

### 结论与措辞

- 确认缺陷成立：**同一通知 id 上 publish 与 cancel 无门控、且未串行化**，属真实竞态；两种次序都可能，能否观察到取决于 `notificationManager` 的实现与调度。
- 后果形态：①正常成功时完成通知**可能**被紧随的 `cancelRunning` 撤回（也可能残留）；②REPLACE 时旧轮的 `cancelRunning` **可能**抹掉新轮的 ongoing 通知（也可空转无害）。原报告“B 运行期间通知被抹掉”同理改为“可能”。
- 严重度：维持 P2，置信度降为**中**；实机最终可见性 = **待设备**，不作“每次正常必消失”的断言。
- 修复（不变）：`finally` 内 `cancelRunning` 与删除注册同门（仅 `current.token===job.token`）；`notifyCompleted` 的 publish 需 `await`/`return`（并让 `cancelNotification` 在需要确定性时被 await）；或完成态换独立 id。
- 附带：`notifyRunning:152`、`notifyFailed:163` 存在同样的“不 await publish”模式，`finally` 的 cancel 与它们同 id，属同一竞态家族。

---

## 5. AG 平台/路由复核

### AG-4 路由（修正计数）

静态复算（`main_pages.json` + 全仓字面量 `'pages/Name'`）：

- 注册 **74** 条、`pages/` 文件 **75** 个，差集 `pages/Index`（未注册，自陈无入口）→ 与原报告一致。
- **直孤儿 = 17**（其他文件对 `'pages/<Name>'` 零引用）：`AssistantImporter`、`AssistantListPage`、`AssistantPropertyEditor`、`DeepReadTemplateWorkbenchPage`、`HotListSourceSettingsPage`、`MemoryPage`、`PromptTemplatePage`、`SettingAgentExecutionPageFull`、`SettingAgentExtensionsPageFull`、`SettingAgentPermissionsPageFull`、`SettingExperimentalModelCouncilPageFull`、`SettingExperimentalSubAgentPageFull`、`SettingMcpPageFull`、`SettingModelPage`、`SettingSearchListCards`、`SettingSearchServiceEditorSheet`、`SkillDetailPage`。
- 原报告“22 个”是更宽松的口径（含仅注释/同名命中等），**应按 17 计**。
- **传递孤儿**（原报告此点正确）：`AssistantListPage` 直孤儿 → 其引用的 `AssistantDetailPage`（`AssistantListPage.ets:93,104`）不可达 → 再连带 `AssistantBasicPage/AssistantPromptPage/AssistantRequestPage/AssistantExtensionsPage/AssistantMemoryPage`（`AssistantDetailPage.ets:29-33`）整棵不可达。活链是 `ProfilePage.ets:510 → ChatAssistantsPage`（单体编辑器），故为死代码而非能力缺口。
- “经常量/重定向才是真孤儿”的核验：全仓无 `pushNamedRoute/NavPathStack/NavDestination/routerMap`，无“url 由变量计算”的动态跳转（5 处 `pushUrl({url: 变量})` 的实参均为字面量）；重定向壳（`SettingAgent*PageFull` 等）**本身无调用方**，其 `replaceUrl` 指向的真页另有活链入口 → 壳是真的死壳，符合 AG-4。
- 值得单列：`HotListSourceSettingsPage` 直孤儿——H-4 声称“已修”，但该页**无入口**，修复落在死代码上（不是回归，是“修了但不可达”）。

### AG-2 深链（确认）

- `EntryAbility.ets:84-116`：`onNewWant` 仅写 AppStorage；`module.json5` 未声明 `launchType` → 默认 `singleton`，被通知 want（`START_ABILITY`）拉起时走 `onNewWant`。
- 唯一消费者 `ChatListPage.ets:68,81,85-111`；`ChatPage` 全文无 `wantTopicId` 消费。前台且栈顶为 `ChatPage` 时根页不触发 `onPageShow` → 深链滞留 AppStorage 直到手回根页。确认 P2。

### AG-1 主题（待验证，非确认）

- 机制：tokens 为 `export let` 模块级可变（`tokens.ets:13-40`），`applyTheme/setAccentColor` 整体赋值（`:81-120`）；`SettingAppearancePage.setTheme/setAccent`（`:37-56`）无 `replaceUrl`，与 `tokens.ets:8` 注释“切换当前页经 replaceUrl 重建”不符。
- 静态面（复算）：import tokens 的 `.ets` **173** 个；声明 `@StorageProp('darkMode')` **12** 个；真读 `this.darkMode` **7** 个；声明 `accentColor` **27** 个。
- 但 ArkUI V1 是否“只重建依赖该变量的组件”还是“组件级 dirty 连带重建”，**无设备无法证**（原报告盲区 2 自陈）。按纠偏：不因 `export let` 自动判可见 bug → 标 **待验证**，机制可信、用户可见面未证。修复建议 (a) 仍成立。

### AG-3 / AG-5 / AG-6（确认）

- AG-3：`chatStorageDegraded` 全仓仅 `ChatRdbRepository.ets:178,182` 两处写入、**零读取**；`initializeStore` 失败返回 null（`:186-197`），`ensureStore` 的 `finally` 把 `initPromise` 置 null（`:188-198`）→ 失败后每次操作重试；写读落 `this.memory`（`:283-311`）。P1 数据丢失确认。
- AG-5：`markHostMounted` 定义 `WebMountSession.ets:109-111`，全仓命中仅本文件内部 4 处；`open()` `:139-154` 轮询 50×100ms 后 `throw new Error('WebMount host not mounted')`；`WebMountHost`（`:220-240`）无调用、无 `aboutToDisappear`，且会话为全局单例（`:208-216`）。P1 确认。
- AG-6：`build.log`（Sep13 14:55）与当前工作树逐行吻合：`SyncCryptoPort.ets:21` `createSymKeySync`、`:32` `CipherSpec`、`:90` `getRandomSync`，`WebMountOAuthSupport.ets:38` `getRandomSync`。本地 SDK d.ts 实测：`createSymKeySync` 0 次、`getRandomSync` 0 次、`CipherSpec` 非导出（仅 `CipherSpecItem`）、`generateRandomSync` 与 `createSymKeyGenerator` 各 1 次。这两个文件 `git status` 为 **M（未提交）**，即 HEAD 声称“修通 CompileArkTS”后工作树又被改坏 → 当前树不可编译。按纠偏 **P1 构建阻断**（非 P0），是“所有 UI 结论 pending”的前提。

### AG-7/8/9/11（P3 确认）

- AG-7：`EntryAbility.routeFromWant:95-99` 只取 `topicId/title/novel*`，无 `sourceUrl`；且 `NotificationNotifier` 三处调用 `buildArticleWantAgent(...,'')` 恒传空串（`:151,156,162`），双因。P3 确认。
- AG-8：`SettingAppearancePage.ets:39` `put(...)` 无 `void/await/catch`；`setAccent`（`:47-56`）有 catch → 写法不一致。P3 确认。
- AG-9：`PENDING_PROVIDER` 模块级一次性交接（`ChatProviderDetailPage.ets:27-31,59-79,144-153`），重建后空表单 + Save 静默 return。P3 确认（触发条件受限）。
- AG-11：`appBackgrounded` 唯一消费者 `ChatPage.ets:1820`（完成通知门），注释引用的 `Index` 已死 → 契约描述过期，非缺陷。P3 确认。

### AG-12/13/14/15（无缺陷，确认）

- AG-12 深链返回栈自洽；AG-13 权限声明与实现一致（`module.json5` 仅 INTERNET/GET_NETWORK_INFO/READ_CALENDAR，其余注释）；AG-14 `ChatPage.aboutToDisappear:656-701`/`onPageHide:767-775` 清理闭环；AG-15 假阳性清单（尤其 `@State` 原地 `push/splice` 会触发更新，不应报）复核未见反例。

---

## 6. 其余 H 项（同域，按纠偏收口）

- **H-3 假阳性（有意降级）**：实现见 `run_manager.ts:91-111`（空/异常 → `fallback-title`），即 `PARITY_DEBT.md` PD-001（`open`，理由“降级文 > 直接失败”，消除条件“G1 人工确认”）。按要求不得只因 Android hard-fail 判 bug。残留仅 `run_manager.ts:8` 头注释仍写“空 prefetch → hard-fail(P1-9)”，与现值矛盾 → P3 doc nit。
- **H-5 确认（低）**：`council/manager.ts:364,376,379` 只本地 `truncate`；`ModelCouncilRunner.ets:55-59` body 无 `max_tokens`。但 Android 同款设置名即 `outputBudgetChars`、`SettingExperimentalModelCouncilPage.kt` 也只透传持久化 → 非鸿蒙独有缺口，P3 成本问题。
- **H-6 确认（trivial）**：`manager.ts:201-217` 任意时刻都会 `listeners.set(runId, new Map())`，而终结清理在 `:292-304` 只跑一次；`CouncilRunPage.ets:76-85` 终结态进入会留一个小 map（闭包持页状态）。P3。
- **H-7 降级/不报**：`creation.ts:855-881` 两处 `Date.now()` 绕过 `nowMs` → 仅时钟注入一致性，无可见实例。
- **H-8 待验证**：`MemoryStore.ets:39-63` IO 异常 `return []`，读不在 `enqueueWrite`（`:76-80`）内；parse 损坏已有 `quarantineIfStillInvalid`（`AtomicJsonFile.ets:38-54`）。IO 失败→后续写以空覆盖的链路成立但无实例，P3 低置信。
- **H-9 假阳性/不报**：`NovelPolishWorkScheduler.ets:20-26` djb2 32 位；无碰撞实例，按要求不报。

---

## 7. 盲区与未验证（不隐藏）

1. 全部 UI 目视结论仍 pending（构建当前失败，见 AG-6），AG-1 的依赖粒度、AG-2/AG-7 的通知点开形态、AG-5 修复后行为、H-10 通知栏最终时序均需设备。
2. H-2 的“abort rejection 是否真被外部未处理”无实例（现有唯一调用方均 catch）。
3. H-4 免费外部源在目标网络的实际可达性/超时占比未实测。
4. H-1 的端到端影响量化（规划失败概率）未测；本报告只证“非常态”。
5. 本报告未运行 hvigor 构建（遵守“不装依赖/不改配置”，且构建非本 owner 结论所需）；AG-6 结论来自 build.log 与现值逐行比对 + SDK d.ts。

## 8. 建议修复批次（据本复核修订）

1. **P1 构建阻断**：`SyncCryptoPort.ets` / `WebMountOAuthSupport.ets` 按 SDK d.ts 改 `createSymKeyGenerator('AES256').convertKeySync`、`generateRandomSync`、`initSync(opMode,key,params)`（归 E/F）。
2. **P1 数据/工具**：AG-3 消费 `chatStorageDegraded` + 一次性失败标记；AG-5 补 `markHostMounted`/卸载复位。
3. **P2**：H-2 归一 abort 为 `RunResult` + signal 覆盖 prefetch/plan；**H-10 通知 token 门 + `notifyCompleted` await publish + 避免同 id 紧随 cancel**；AG-2 非根页 Want 导航；H-4 注入 `searchBuiltin*`/启用集合到 DeepRead registry；AG-4 死页登记清理。
4. **P3**：H-1 仅修 `fallbackPlan` 全局取卡（并纠正“常态”描述与测试）；H-5/H-6/H-8；AG-7/8/9；H-3 头注释对齐 PD-001。H-7/H-9 不报。
