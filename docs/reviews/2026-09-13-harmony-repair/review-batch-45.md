# 独立复核 — batch 4/5（只读）

范围：`implementation-contract.md`、`batch-4-webmount.md`（R07/L01/L02）、`batch-4-deepread.md`（R20/R22/R24/UI-F30/F29/X01）、
`batch-5-components.md`（F09/F12/F15/F16/F17/F19/F44/R08）、`batch-5-settings.md`（F21/F23/R26/F35）、`batch-5-pages.md`（F07/F08/F12/F14/F32/F38/F41/F42/R08）。
基线 `bdce551dd`（harmony-preview）。

复核方式：**只读**读当前工作树 diff + 调用链反证 + 官方 SDK d.ts / 域层对照 + 现有 targeted 测试与隔离探针实跑；
**不因 probe 跑通即认安全**（probe 仅作交叉验证，结论以调用链与反证为准）。cwd 全用绝对路径，未改任何业务代码，未跑 hvigor/全仓 lint（按契约主代理统一）。
file docs 由别 owner 负责，未纳入；主代理 ChatPage 仍在接线中，本文件不因 missing helper/构建态重复报。

结论：**未发现相对基线 `bdce551dd` 可确证的 P1/P2 回归**；R07/L01/L02 的 fail-closed 主链、R20 取消贯穿、R22 token 归属、
UI-F30 不隐式 REPLACE、F16/F19 的「默认等价 + 未支持诚实」均成立。但本批**新引入的机制**内发现 **3 个 P2 缺口**（非基线回归：
基线无导航 gate / 完成通知总被 finally 撤 / 部分缓存本就全量重跑，故均为「修得不完整」而非「改坏」）+ 5 个 P3/残留。

---

## 一、结论速览

| # | 级别 | 标题 | 影响 |
|---|---|---|---|
| P2-1 | P2 | WebMount「设置页 revoke」不刷新实时 session gate、不复位当前页 | 站点撤销后，在下次工具调用前，页面内自主导航仍可能被旧 gate 放行（revoke 生效延迟到下一次页面工具）。新 gate 覆盖缺口，非基线回归 |
| P2-2 | P2 | UI-F30「继续生成」重跑全部 4 个 stage（非只补缺） | 部分/取消缓存「继续」会对每个 READY 段仍发起 LLM collectRun 并可能被模型改写；Android 只补缺段。`runManager` 既有语义，新增「继续」按钮放大暴露 |
| P2-3 | P2 | `notifyCompleted` 在 publish 失败时仍被判「已发布」 | `publishBasicNotification` 吞错 resolve → `completedPublished=true` → finally 不撤，「正在深度阅读」通知永久残留。R22 修得不完整，非基线回归 |
| P3-1 | P3 | `generateStages` 的 markCollecting `saveGuarded` 在 try 之外 | 极小竞态窗口内取消会以 rejection 而非统一 `{error:'aborted'}` 出栈（scheduler 兜住，直调 `run_manager.run` 不兜） |
| P3-2 | P3 | `back()/forward()` 不更新 `currentUrl` | 历史导航后到 onPageEnd 前，`guardPage`/revoke 复查读旧 URL（短暂窗口） |
| P3-3 | P3 | F16 ChatPage 默认 `showModelIcon=true` 且显示真实 modelId | 助手头部由品牌 `amber` 变为「monogram + 真实模型 id」；batch 文档「默认值与旧渲染一致」不成立（属 F16 意图，非缺陷） |
| P3-4 | P3 | `resolveNovelToolId` 对空 `toolCallId` 一律拒绝 | 空 id 工具（若存在）的自动恢复从「首个匹配」变「拒绝」，fail-closed 但可能丢恢复 |
| P3-5 | P3 | `ChatMessageTools` approved-but-未执行且 streaming=false 恒显示 Running | 旧挂起工具显示「运行中」，无终态（既有语义，非本批引入） |

---

## 二、WebMount（R07 / L01 / L02）

### 2.1 L02 URL 解析与授权边界（通过，独立反证）

`WebMountSites.ets:55-77` `checkWebMountUrl`：`url.URL.parseURL` → 仅 `http(s)` → `username/password` 任一非空拒绝 → hostname 小写去尾点；
`hostOf`（117-120）失败返回空串；`hostMatchesSite`（123-124）要求 `siteHost` 非空且相等/子域；`makeSiteGate`（140-154）预解析站点 hostname、回调内纯同步。

逐项反证（不依赖 probe）：
- `javascript:`/`data:`/`file:`/`blob:`/`ftp:` → `protocol` 非 http(s) → `ok:false` → gate 返回 false；`shouldBlockUrl` 对非 `about:` 且 gate=false 返回 **true=阻止**（`WebMountSession.ets:162-168`）。fail-closed 成立。
- `parseURL` 抛错（`http://`、含空格等）→ `try/catch` 返回 `ok:false`。成立。
- deceptive userinfo `https://github.com@evil.com/`：parseURL 的 `hostname=evil.com`、`username=github.com` 非空 → **拒绝**（即使 hostname 命中白名单也拒）。与 Android「userinfo 不构成绕过」一致。
- 尾点 `github.com.` → hostname 去尾点后相等；`evilgithub.com`/`github.com.evil.com` 不命中（`endsWith('.'+siteHost)` 精确）。成立。
- `Array.isArray(parsed)` 取代 `parsed.length>0`（`WebMountSites.ets:96-97`）：`"[]"` 为权威空表 → 用户清空后所有导航 fail-closed；`"null"/"{}"/坏 JSON` 仍重种。成立。

注入侧（L01）：`jsonString`（`WebMountTools.ets:58-60`）转义 U+2028/2029，`clickScript/typeScript/waitProbeJs` 全部以 `const x = <json 字面量>` 注入，脚本正文无 `target` 插值；`wm_click`（191-193）三审批字段 `needsApproval/allowsAutoApproval:false/mandatoryApproval` 与 `wm_eval` 对齐；`tool_policy.ts:280-282,407-411` 使 `wm_click` 为 sensitive + mandatory，普通自动批准无法绕过（仅「同时开两个高风险自动批准」可无人工）。成立。

### 2.2 R07 就绪/销毁/owner 解绑（通过）

- `markControllerAttached`（`WebMountSession.ets:178-183`）只在 `Web.onControllerAttached`（448-450）置 `hostMounted`；`open` → `ensureHost`（327-332）→ `waitForMount`（10s 超时 **reject**）→ `loadUrl` → `waitForPageEnd`（15s 超时 **reject**）。`SettleWaiter.settle/fail` 幂等 + 清 timer + 摘 abort 监听（240-256 / 285-301）。
- `markControllerDetached`（186-198）：`hostMounted/pageReady/uiContext/pendingExternalNav` 全复位，mount+page 等待者统一 `fail`。对称。
- `bindHooks`（74-83）返回 `{unbind}`，`unbind` 仅在 `this.hooks===bound` 时置 null → 旧 page 不会解掉新 page hooks。ChatPage 接线核对：`ChatPage.ets:669-670`（aboutToDisappear unbind）、`916-918`（重绑前先 unbind）、`1858`（type import 139）、`4624`（WebMountHost 常驻 Stack）。owner 语义成立。
- gate 准备时序：`wm_open`（`WebMountTools.ets:146-152`）**先** `setUrlGate` **后** `open`；`guardPage`（70-80）每个页面工具前重载站点→刷新 gate→查 `getUrl()`。同步 gate 无 async 缝隙。`wm_site_remove`（322-339）revoke 后立即换 gate 且对当前页复查、不授权即 `resetToBlank`。**工具路径 revoke 成立**。

### 2.3 P2-1 设置页 revoke 不刷新实时 session gate（真实缺口）

`SettingExperimentalWebMountPage.ets:228-233 removeSite`：

```ts
const next = this.sites.filter(s => s.id !== id);
saveWebMountSites(getChatKvStore(), next).then(() => { this.sites = next; }).catch(() => {});
```

全仓 `setUrlGate` 调用点仅 `WebMountTools.ets`（grep 已核）。因此**用户在设置页删除/撤销站点**（最常见 revoke 入口）时：
- `session.urlGate` 仍是旧快照 → `onLoadIntercept` 继续放行该主机；
- 当前已加载页面不被复位。

在「下一次页面工具调用」之前，页面内自主导航（meta refresh、`location` 由已注入脚本/页面自身触发）不会被 gate 拦。`guardPage` 只在该工具被调用时才刷新 gate，属事后收口。

**最小修法（不改架构）**：设置页 revoke 后同步刷新会话，或在 session 暴露一个供设置页调用的 `refreshSites()`：
```ts
import { getWebMountSession } from '../platform_impl/WebMountSession.ets';
// removeSite 成功回调内：
const session = getWebMountSession();
session.setUrlGate(makeSiteGate(next));
const cur = session.getUrl();
if (cur.length > 0 && !siteAllowsUrl(next, cur)) session.resetToBlank();
```
（`SettingExperimentalWebMountPage.ets` 属本批文件面；会话单一全局，风险低。）同类：`restoreMissingSeedSites` 后同样建议刷新 gate。

### 2.4 P3-2 `back()/forward()` 不更新 `currentUrl`

`WebMountSession.ets:364-376`：`controller.backward()` 后只置 `pageReady=true`，不更新 `currentUrl/touchActiveTab`。`onPageEnd` 到达后会 `markPageEnd` 修正，但窗口内 `guardPage`/`wm_site_remove` 的 `getUrl()` 复查读旧 URL。批内注释已承认「未等 pageEnd」，登记残留即可。

### 2.5 设备未验证（不声称通过）

`onControllerAttached` 真实时序、移出可视区 Web 的渲染/截图、`onLoadIntercept` 对 302 逐跳与 `about:blank` 放行、`window.open/_blank` 新窗口是否绕过主框架拦截（ArkWeb 无 `onWindowNew` 时的行为），均需模拟器实测。`screenshot` 与 `onDetach` 竞态（截图进行中被卸载）未加锁，`markControllerDetached` 清 `uiContext` 后截图抛「no UI context」属预期 fail-closed。

---

## 三、DeepRead（R20 / R22 / R24 / UI-F30 / F29 / X01）

### 3.1 R20 取消贯穿（通过）

链路：`scheduler.abort` → per-job `controller.signal` → `run/runSection(opts.signal)` → `generateStages` → `createRunContext` → `prefetcher.collect(...,signal)` → `SearchProvider.search(queries,signal)` / `HttpClient.fetch(req,{signal})`；规划期 `generateArticlePlan(...,signal)`。
- **判定收窄**：`isAborted = signal.aborted`；`isAbortErrorName = name==='AbortError'`；旧 `/abort|cancel/i` 宽判已删（`run_manager.ts:73-86`；`source_prefetcher.ts:290-296`）。业务 message 含 "cancel" 不再被吞。
- **withDeadline** 接 signal：已 abort 立即 reject；等待中监听 abort，`finally` 移除监听（`source_prefetcher.ts:260-289`）。`collect` 每个 await 窗口后复查 `signal.aborted` 返回 `[]`，不写 LRU。
- **saveGuarded** 每次 `repository.save` 前复查（`run_manager.ts:199-202`），删除 `COLLECTING/WRITING` 中途写。统一返回 `{ok:false,error:'aborted'}`。
- **entry SearchRegistry 已真接 signal**（batches 文档曾标「另一 owner 补」）：`SearchRegistry.ets` 各 provider `search(queries,signal?)` + `http.fetch(req,{signal})` + 循环 `isAborted` break；`NewsNowProvider.ets` 同步接线；domain Tavily（`search_provider.ts:24-76`）循环前/ catch 后 break 且取消时不把「零结果+lastError」抛成 provider 失败。**此项已闭合，不再是未完成项**。
- `HttpClient.fetch` opts.signal 既有；`RcpHttpClient.ets:47-100` abort → destroy + `AbortError`。

### 3.2 R22 通知所有权（通过，含 P2-3）

`NotificationNotifier.ets`：`claim(topicId,token)`；`cancelRunning(topic,token?)` 在 `owner!==runToken`（且有 owner）时 no-op（181-187）；`notify*` **await** `publishBasicNotification`。`scheduler.runImpl`（`scheduler.ts:140-177`）：`completedPublished` 仅成功分支置位，finally 仅 `owner && !completedPublished` 才撤。

REPLACE 反证：A 被 B 取代 → A.finally `current.token=B` → `owner=false` → 不删注册、不撤通知；B.notifyRunning claim B；B 完成 `notifyCompleted` claim B。旧 run 迟到 cancel 不撤新通知。成立。
取消归一：`catch { if (isAborted(controller.signal)) return aborted结果; throw }`（`scheduler.ts:161-166 / 210-215`），不宽判 message。成立。

### 3.3 P2-2「继续生成」重跑全部 stage（真实行为缺口，批内已登记）

`DeepReadArticlePage.ets:246-253 continueGeneration → runNow(pageToken,false)` → `scheduler.run(force=false)` → `run_manager.run`：缓存非 complete/非 sectionsReady → `generateStages(deps,...,STAGE_ORDER,...)`（`run_manager.ts:357`）**无条件 4 段**。`markRunning` 只对非 READY 置 RUNNING（`section_writer_tools.ts:580-589`），
而 `runStageSupervisorLoop`（`supervisor_loop.ts:258-284`）**每段都先 `collectRun`（一次 LLM 调用）**，`stageReady && supplementWritten` 才早返回；已 READY 段仍会进入 loop 发请求，且模型若再次调用写工具即会改写该段。后果：部分/取消缓存「继续」时，（a）对已 READY 段重复消耗 LLM/网络；（b）READY 段可能被新结果改写。

Android 只补 missing-stages。**最小修法**：给「继续」路径只传非 READY 段，例如在 `run` 内（或 `run_manager.run` 的 generateStages 调用处）：
```ts
const missing = STAGE_ORDER.filter(s => statusOf(withInferredSectionStates(cached ?? empty), s) !== 'READY');
if (missing.length > 0) return generateStages(deps, topicId, title, missing, ...);
```
（不改变既有首次/force 全量语义；批内文档已把它列为待主代理决策项。）

### 3.4 R24 / UI-F30 / UI-X01 / F29（通过）

- **R24**：`fallbackPlan` 先取本段 bucket，不足 `STAGE_EVIDENCE_MAX` 时按 `STAGE_ORDER` 汇总的全局池补满并去重，`requiredSourceIds` 取补满并集（`article_plan.ts:31-52`）。后段不再零证据。
- **UI-X01**：`loadDisplaySettings()` 上移到缓存早退之前（`DeepReadArticlePage.ets:137-139`），完整缓存路径也应用 fontScale/fontSerif/templateId，且不触发生成（163-169）。成立。
- **UI-F30**：`isRunning` → 仅订阅；完整缓存 → 仅展示；有进度痕迹 → `awaitingContinue` 显式二选一；空壳 → 首启（155-191）。`subscribeToUpdates` 先退订再订阅（206-220），running 订阅在 `cancelled||awaitingContinue` 下不假装在跑。`decideReentryAction`（`scheduler.ts:271-281`）与页面逻辑同序；未从 index 导出（批内已登记，页面内联无功能缺口）。
- **F29**：`CSS_EDITORIAL` 全部字号 `calc(px * var(--ds))`，默认 `--ds:1` 与原样式逐字等价；`renderEditorialSlantHtml` 只注入 `:root{--ds:<n>}`（`clamp(0.8,1.3)+toFixed(3)`）与预定义 `body.ds-serif` class，无字符串注入面；`javaScriptAccess(false)` 不变；zoom 经 `refreshTemplateWeb` 重 `loadData` 生效（`DeepReadArticlePage.ets:371-401,568-579`）。探针 `probe-batch4-template.mjs` 9/9，且我独立确认 script 正文无 `javascript:` 注入。

### 3.5 P3-1 markCollecting 守卫在 try 之外

`run_manager.ts:205-207` 的 `saveGuarded({...COLLECTING})` 位于 `try {`（209）之前。若取消恰好发生在 `createRunContext` 返回（非 aborted）与此处之间，`saveGuarded` 抛 `AbortError` 直接出栈。`scheduler.runImpl` 的 catch 以 signal 归一（161-166）可兜住；但直接调用 `run_manager.run` 的路径（测试/未来调用方）会拿到 rejection 而非 `{ok:false,error:'aborted'}`。建议把该写入移进 try，或先 `if (isAborted(signal)) return abortedResult();`。

---

## 四、共享组件与显示/技能设置（F09/F12/F15/F16/F17/F19/F44/R08）

- **F09**（通过）：`ChatMessageAvatar.ets` blink 改由 `shouldBlink()=role!=='user' && streaming` 经 `@Watch('onStreamingChange')` 对称启停，`aboutToDisappear` 清理并复位 `cursorOn=true`；静止行零定时器。`@Prop @Watch` 组合 V1 合法。
- **F12**（通过）：SubAgent/Council 删除墙钟轮播，阶段仅取 output 结构化 `phase/phase_label/stage/stage_label`（council 另支持数字 `round`），缺失只显示状态。未伪造进度。
- **F15**（通过）：复用 `toolActivityStatus/toolOutputJson`（`@amber/chat-domain` 已导出，`index.ts:1047` 面），删除正文 `includes(error/exception/failed)` 误判。默认 `streaming=false` 时：pending→WaitingApproval、denied→Cancelled、indicatesFailure→Failed、有 output→Succeeded。查证 `toolActivityStatus` 对 `!isToolExecuted && !loading` 返回 'running'（`tool_activity.ts:441-442`）→ P3-5 旧挂起工具恒 Running（既有语义）。
- **F16**（通过 + P3-3）：`MarkdownText` 新增 `codeBlockAutoCollapse/showLineNumbers` 默认 false；`RenderCodeBlock(block,bi)` 的 `bi` 来自 `ForEach(displayBlocks,(block,idx))`（`MarkdownText.ets:299-318`），`codeKey=${bi}:${lang}:${code.length}` 同块稳定；行号 `idx+1` 基于 slice 后下标，与 `splitTokensByLine` 行数一致（含尾空行），>10 行折叠显示 10 行 + 展开入口；`bodyFontSize()=round(15*ratio*10)/10`，ratio=1.0→15 与旧一致。`showAssistantBubble` 气泡默认 false。见 P3-3 关于 ChatPage 默认图标/模型名。
- **F17**（通过）：`headingShowsQuestion(qi)=questions.length===1 && qi===0`，单问题正文不重复；多问题仍逐条。
- **F19**（通过，诚实）：SkillsPage 移除 Slides 字体入口；`SettingSlidesFontPage` 加 `SLIDES_FONT_SUPPORTED=false`，不加载伪配置、setFamily/setSize no-op、去「完成」动作、正文明示未支持。路由仍在但无入口，可接受。
- **F44**（通过）：`ContentAttachmentChip` 可选 `openable/onOpen`，openable 才渲染 `›` 且用 `priorityGesture(TapGesture)` 独占点击；`AgentMessageRow` 仅 `capabilities.openDocument` 为真时对 document chip 传 `openable:true`，video/audio 保持纯展示。`openDocument` 为必填 capability，ChatPage/Novel 字面量均已补。
- **R08**（通过）：`AgentMessageRow.locatorFor` 先 `parts.indexOf`，group 复制体才用 `streamToolIndex` 作匹配键并要求**唯一**命中，`count!==1` 返回 undefined（拒歧义）；不把流内 index 直接当 partIndex。`step.tool` 走 indexOf；两处调用点（已提交 / 流式 tail）均透传 locator。Novel 侧 `resolveNovelToolId`（locator 精确取 part 的 toolCallId / 无 locator 要求全项目唯一）失败即拒绝并提示，绝不按 blank 等价批准。

---

## 五、设置页保存/脏返回/并发写（F21/F23/R26/F35）

- **F21**（通过）：SubAgent/Council `dirty/saving/saveError`；`onBackPress`（`SettingSubAgentPage.ets:56-62` / `SettingModelCouncilPage.ets:52-58`）脏/保存中→`requestBack()` 返回 true 消费；顶栏 `PageTitleBar` 传 `actionText/onAction/onBack`，`saveAndBack` await 成功才 `router.back()`，失败弹窗且草稿留页；`saveSetting():Promise<boolean>` 不再静默。**顶栏返回已走 requestBack，无绕过**（`PageTitleBar.onBack` 默认 `router.back()` 被显式覆盖）。`saveSetting` 序列化整份 @State，故「切行后触发保存点会一并落盘」，原「切行即丢」不成立。
- **F35**（通过）：`AssistantMutation.ets` `mutateAssistantInStore` 在 `sharedKvMutex.withLock(ASSISTANTS_KEY)` 内 load-latest→锁内 transform 目标条→原位回写；未命中返回 null 不改表；mutator 抛错不落盘。三页 toggle 改走 `mutateAssistant`，删除 load-modify-save；onChange 补 `.catch`。探针 20/20 并发保留 + 对照组复现丢更新。
- **F23**（通过）：空名称 `confirmDraft` 置 `nameError` 并停留，名称框错误描边 + 行内文案，输入去空白非空清错，`openCreate/openEdit` 复位。
- **R26**（通过）：`applyThemeMode` 单一入口；`setTheme` 乐观应用→await put→成功记 `persistedThemeMode`；失败且 `seq===themeSeq` 才回滚 + 错误条；旧失败晚到不回滚更新的选择。`aboutToAppear` 读回同步持久值。探针覆盖单失败回滚与竞态 guard。

---

## 六、页面级（F32/F38/R08 Novel）

- **F32**（通过）：`doCollect` 成功 `tab=2; settingsSection=1`（`NovelWorkspacePage.ets:1098-1101`），定位资料建议所在二级页。
- **F38/自绘返回层级**（通过）：`onBackPress → closeTopOverlay()`；`handleBack()` 顶栏同路径；overlay 检查顺序（polish→branch→versions→chapterMenu→material→chapterEditor→collect）恰为渲染栈逆序（`NovelWorkspacePage.ets:2118-2166`）；章节/素材编辑脏则 `AlertDialog` 确认放弃，快照基线在 open 时正确建立（`chapterOriginal*`/`materialOriginal*`）。系统 sheet 由平台消费，无 overlay 返回 false 不二次拦截。
- **R08 Novel**（通过，P3-4）：两处调用点（已提交 / 流式 tail）均透传 locator；`continueNovelTool` 先 `resolveNovelToolId` 再继续，失败提示并拒绝。
- 关闭 `openDocument:false` 的本页字面量保留、未覆盖（用户已补）。

---

## 七、应修 P1/P2（主代理统一）

**P2-1 WebMount 设置页 revoke 不生效** — 修法见 2.3（`SettingExperimentalWebMountPage.removeSite` 成功后 `setUrlGate(makeSiteGate(next))` + 不授权则 `resetToBlank()`）。
**P2-2「继续生成」重跑全量** — 修法见 3.3（只传非 READY 段；不动 force/首启全量语义）。
**P2-3 完成通知发布失败误判已发布** — 修法：让 `publishBasicNotification` 失败时 **reject**（或返回 boolean），`notifyCompleted` 据此置 `completedPublished`；`notifyRunning` 同理可保留吞错但需区分。最小改法：
```ts
// publishBasicNotification: 去掉 .catch 或改为 rethrow
// scheduler.runImpl: const published = await deps.notifier?.notifyCompleted(...); completedPublished = published !== false;
```
若保持 Port 签名 `Promise<void>`，可在 `NotificationNotifierImpl.notifyCompleted` 内 `try { await publishBasicNotification(...); return true; } catch { return false; }` 并让 scheduler 读取返回值（需扩契约，属主代理统一）。

P3-1..P3-5 建议登记，不必强改。

---

## 八、测试与命令（只读实跑，未改业务）

```
cd /Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread
npx tsc --noEmit -p tsconfig.json            # 0 error
npx tsx --test "src/test/**/*.test.ts"       # 779 tests, 779 pass, 0 fail

# batch4/5 隔离探针（真实源码转译，仅 mock 平台边；交叉验证用，不作为安全结论依据）
# 均在 docs/reviews/2026-09-13-harmony-repair/probes/ 下
node probe-batch4-webmount-sites.cjs    # exit 0
node probe-batch4-webmount-tools.cjs    # exit 0
node probe-batch4-webmount-mount.cjs    # exit 0
node probe-batch4-notifier.mjs          # ALL PASS (7)
node probe-batch4-template.mjs          # ALL PASS (9)
node probe-batch5-settings.cjs          # PROBE OK
```
未跑：hvigor 构建、全仓 lint、`lint-arkts.sh` 对未改文件、真机/模拟器（按契约主代理统一）。

## 九、边界（不声称设备已验证）

- WebMount：`onControllerAttached`/`onDetach` 实际时序、`onLoadIntercept` 逐跳与 `_blank`/新窗口行为、离屏 Web 渲染与截图、revoke 实时性（P2-1 修后）。
- DeepRead：取消在真机 HTTP/LLM 的落地延迟、`awaitingContinue` 二选一在真机的呈现、模板 `--ds` 缩放视觉。
- UI-F16/F19/F44 与设置页脏返回的设备态（返回手势、AlertDialog 叠加、优先级手势）。
