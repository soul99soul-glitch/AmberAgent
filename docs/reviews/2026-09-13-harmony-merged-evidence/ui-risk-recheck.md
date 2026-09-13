# Harmony UI 风险复核（F20/F25/F27/F28/F30/F34/F35/F39/F43/F44 + 新增 UI-X01）

日期：2026-09-13
范围：只读复核，未修改业务/真实文件/网络。复核对象为 `docs/reviews/2026-09-13-harmony-ui-ux-motion-review.md` 的 UI 条目，与 `docs/reviews/2026-09-13-harmony-deep-audit/`（R01–R27 / L01–L02）逐项去重。
证据状态：`静态确定`＝源码路径闭合；`静态确定·条件`＝前提收窄；`待设备`＝后果依赖运行期；`假阳性/设计债务`＝不成立或降级。

约定：
- 行号均以当前工作树（分支 `harmony-preview`）为准，1-based。
- 新增独立探针仅落在 `docs/reviews/2026-09-13-harmony-merged-evidence/`。**被测代码取自真实源**：用 TypeScript AST 从页面 `.ets` **原样抽取**目标方法/字段（`struct`→`class` 仅为解析做的等长替换，不改偏移；`build()` 等 ArkUI-only 成员排除），组装成 headless class 后运行；**不手写任何被测方法实现**（抽取器见 `probe-lib.cjs`，每份代码切片打印 sha256 前缀与 provenance 断言）。仅 mock 平台边界（fileIo / KV / WebView / 通知），**全程使用内存 mock 文件系统**，不触真实文件、设备、网络。日志措辞一律为「mock FS」而非「真实文件已删除」。

---

## 汇总

| 项 | 原级 | 复核判定 | 与 R/L 关系 | 备注 |
|---|---|---|---|---|
| F20 | P1 | **确认 P1** | 与 R10 同 `readShape` 根因，但**清理前无引用核验/无错误态**为独立子缺陷 | AST 抽取真实方法 → headless，mock FS 确认误删 |
| F25 | P2 | **确认 P2·条件** | 独立；与 R02/R03 同「备份链路」族但不同根因 | 取消语义待设备，代码分支确定 |
| F27 | P2 | **确认 P2，但纠正计费断言** | 独立 | `ai.generate`/`sendToConversation`/`createArtifact` 未实现，不重复计费 |
| F28 | P2 | **确认 P2** | 独立 | 无二次确认且不可恢复 |
| F30 | P1 | **确认 P1，收窄并发后果** | 与 R20 不同根因（R20＝取消不贯穿；F30＝重进隐式重跑） | 不把「5s 后同时写」当同根结论 |
| F34 | P2 | **确认 P2·条件** | 独立 | 唯一可达写入点是备份恢复 |
| F35 | P2 | **确认 P2·待设备** | 独立 | `assistants` 键确无锁 |
| F39 | P2 | **确认 P2，局部纠正** | 与 R22 同文件不同根因；与 R23 同一「前台 Want 只根页消费」根因 | sourceUrl 断链窗口已收窄 |
| F43 | P1 | **确认 P1** | **= R07 同根同一实现**，去重合并 | 独立探针复现 |
| F44 | P2 | **确认 P2** | 独立 | 文档 chip 无打开路径 |
| UI-X01（新） | — | **确认新增 P2·静态确定** | 独立于 F29 | 已完成文章重进不加载字号/字体/模板 |
| R11 抽屉子命题 | — | **拆除死抽屉子命题** | 修正 R11 表述 | `openDrawer` 零调用；compact 残留仍活跃 |

---

## F20 — 文件管理「清理孤立文件」登记库损坏后误删真实文件

**状态：确认 P1（静态确定·破坏范围待设备）。** 与 R10 共用 `readShape` 空快照根因，但「清理前无引用核验、无错误态、无确认」是可独立处置的 P1 子缺陷。

**精确行号（`harmony/entry/src/main/ets/pages/SettingFilesPage.ets`）**
- `reload` L111–128：逐 folder `repo.listByFolder`，单 folder 失败 `catch` 后 `step(i+1)` 继续（L125），最终 `this.rows=acc; this.loaded=true`（L117–119）。
- `cleanOrphans` L157–195：唯一守卫 `if (this.cleaning || !this.loaded) return;`（L159）；`known` **只**由 `this.rows` 构建（L163–164）；`fileIo.listFileSync` 列目录（L177）；`known.indexOf(rel) < 0` 即 `fileIo.unlinkSync`（L181–183）；触发是纯文本单击（L205–208），无 `AlertDialog`、无撤销。
- 对照：同页 `deleteRow` L137–153 有 `AlertDialog`，说明确认能力存在但此处未用。

**机制（真实方法转译探针确认）**
`ManagedFileStore.readShape` L44–71：`access` 失败或 `readText` 抛错 → `return emptyShape()`（L49/L53）；解析/结构校验失败 → `quarantineIfStillInvalid` 后 `return emptyShape()`（L62–70）。因此 corrupt/缺失/瞬时读失败三态都让 `listByFolder` 返回 `[]`，`loaded` 仍为 `true`。

**证据** `docs/reviews/2026-09-13-harmony-merged-evidence/probe-f20-clean-orphans.cjs`：
- **被测代码来源**：用 `probe-lib.cjs`（TypeScript AST）从 `SettingFilesPage.ets` 原样抽取 `aboutToAppear`/`reload`/`cleanOrphans` 方法体与 `rows`/`loaded`/`cleaning`/`repo` 字段、`FileRow` 接口、原 import 头，组装 headless `class SettingFilesPage`；抽取时断言切片含 `this.repo.listByFolder(f)`、`fileIo.unlinkSync(\`${base}/${rel}\`)`、`known.indexOf(rel) < 0`（provenance），并打印切片 sha256 前缀。依赖的 `ManagedFileStore.ets`/`managed_files.ts`/`AtomicJsonFile.ets` 均为**真实源** import/transpile。
- **安全边界**：`managed_files.json` 与 `upload/*.png` 都在**内存 mock 文件系统**中；下文所有「删除」均发生在该 mock FS，**未触及任何真实设备/应用文件**。
- T0：健康登记库 rows=2，守卫放行。
- T1：`managed_files.json` corrupt → 真实 `readShape` 隔离并返回空 → rows=0、loaded=true → 真实 `cleanOrphans()` 把 mock FS 中 `upload/a.png`、`upload/b.png`（仍被登记引用的条目）移除，mock upload 目录清空。
- T2：登记库缺失（`access=false` → 无 quarantine）→ mock FS 中 `upload/c.png` 同样被移除。
- T3：瞬时 `readText` 失败 → 清理前 rows=0 → mock FS 中 `upload/d.png` 同样被移除（印证与 R10 同根）。
- T4（反证对照）：健康登记库 → mock FS 中跟踪文件 `upload/e.png` 保留；证明破坏仅由空快照触发。
- T5：`cleanOrphans` 切片不含 `conversationFileUris`、不含 `.bad` 读取 → 引用来源仅 `this.rows`。
- 日志：`probe-f20-clean-orphans.log`（含 `[extract] cleanOrphans sha256/16: 8fb8c79007222e6b len 1354 | reload sha256/16: d2b12c3eeeddb40b len 617`）。

**无 fallback / 无引用核验（独立子缺陷）**
- `known` 仅来自 `this.rows`，从不扫描会话引用（`conversationFileUris`）也不读 `.bad` 隔离副本；即使文件仍被会话引用也照删。
- `reload` 无法区分「空库」与「读失败/损坏」：`loaded=true` 使守卫失效，`rows.length===0` 还会渲染「暂无托管文件」空态（L212–221），进一步误导。
- 真实写入方确实存在 `upload/` 受管文件：`Base64ImageSupport.ets` L60–93（`saveUploadImage` 写 `upload/{uuid}.png` 并 `trackUploadFile`）、`McpFilesStore.ets` L39–68。故**生产环境中**误删对象是真实受管附件；本探针仅在内存 mock FS 上复现同一代码路径，**未删除任何真实文件**。

**最小修复**：`reload` 区分「成功空库 / 读取或解析失败」；失败时置错误态并禁用清理；清理前用 `conversationFileUris` 做引用核验，且清理动作走 `AlertDialog` 列出将删数量与目录。

**去重**：R10 已覆盖「读失败/损坏当空库，随后成功写入覆盖原数据」；本题的**新增面**是「只读的破坏性清理在空快照上执行且无引用核验」，不与 R10 重复计数，建议在同一根因条目下作为独立子项登记。

---

## F25 — 备份导出「取消」被当作成功

**状态：确认 P2·条件（静态确定·平台取消语义待设备）。**

**精确行号（`harmony/entry/src/main/ets/pages/SettingBackupPage.ets`）**
- `doExport` L67–119；`docPicker.save` L99；`if (uris.length > 0) target = uris[0]` L101–102；`else` 落私有目录 `${filesDir}/backups` L103–107；随后无条件写文件 L108–111、`statusMsg='已导出…'` L112、`toast('备份已导出')` L113。
- 页面渲染：`statusMsg` **仅在** `if (this.preview !== null)` 分支内展示（L296–300），导出后 `preview===null`，故用户实际只看到 toast（L113），页面无路径回显。
- 恢复入口 `doPickRestore` L173–197 用 `DocumentViewPicker.select`（L177–180）浏览用户可见文档，无法到达应用私有 `filesDir/backups`。

**机制/反证**
- 代码分支确定：`save()` 返回空数组即走私有目录分支并报「已导出」；`save()` 在用户取消时是否 reject（而非 resolve `[]`）属运行期语义，未在设备确认 → 条件项。
- 附带项同报告：`restoreSettings` 逐会话 `repo.save`（L237–249）无事务，属 R03 已覆盖根因，不重复计数；`passphrase` 导出（L274 附近）/恢复（L204）复用同一 `@State`。
- SDK 声明核对（本地 `@ohos.file.picker.d.ts`）：`DocumentViewPicker.save(option?): Promise<Array<string>>`，返回已保存文档 URI 数组——与代码对返回值的处理一致。

**最小修复**：`uris.length===0` 视为取消，不弹成功；回退私有目录必须回显完整路径并注明「仅本应用可见」；恢复入口对私有目录给出可达路径或文案提示。

**去重**：与 R02（备份 DTO 丢分支）、R03（恢复无校验回滚）同属备份链路但根因不同，独立保留。

---

## F27 — MiniAppRunner 图片预取后整页 refresh，副作用执行两遍（纠正计费断言）

**状态：确认 P2，但**纠正**「`ai.generate` 等重复计费」的表述——这些 bridge 并未实现，不会计费。**

**精确行号（`harmony/entry/src/main/ets/pages/MiniAppRunnerPage.ets`）**
- `prefetchImagesAndReload` L226–272：一次性守卫 `if (this.prefetchStarted) return;` L230–231；无图直接 return L233；完成后 `if (fetched.count > 0) this.controller.refresh()` L264–268。
- 触发链：`Web.onControllerAttached` 首次 `loadData(shellHtml)` L533–538 → `.onPageEnd` L539–542 调 `prefetchImagesAndReload()`。

**纠正（关键）** `harmony/entry/src/main/ets/platform_impl/MiniAppBridgeImpl.ets`：
- `case 'ai.generate'` L526–529、`case 'host.sendToConversation'` L530–533、`case 'host.createArtifact'` L534–537：**均 `sandbox.require(...)` 后 `throw new MiniAppValidationException('not_available_on_harmony')`**，注释 L10–11 亦自述为「诚实错误…not implemented」。因此原报告「`ai.generate`/`sendToConversation`/`createArtifact` 可能重复提交、重复计费」为**不成立的假设**，应删除。
- 真正会在二次加载中重复执行的**已实现**副作用 bridge：`fetch` L393、`search` L409（外部网络/可能计费搜索）、`host.updateBoardSummary` L424（持久写）、`sharedStore.set` L449（持久写）、`storage.set` L364（沙箱写）。
- 因此缺陷成立面是：**页面二次加载导致小应用 onload 副作用重复执行、滚动/交互状态重置，以及网络型 bridge（fetch/search）重复发起**；不泛化为模型计费。

**最小修复**：预取完成前不发首帧，或仅在确有未命中图片时 reload 并提示；对小应用注入 shell 增加「已就绪」标志避免 onload 重复。

**去重**：独立，无对应 R 项。

---

## F28 — 小说工作区删除章节/资料无二次确认且不可恢复

**状态：确认 P2（静态确定）。**

**精确行号（`harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets`）**
- 章节删除入口 L1711–1713（`删除` 文本 `.onClick(() => this.deleteChapter(this.selectedChapterId))`）；执行 `deleteChapter` L1155–1167（直接 `getNovelCreation().deleteChapter`，仅 `novelJobLocksWorkspace()` 前置返回 L1156，无 `AlertDialog`）。
- 资料删除入口 L1820–1822；执行 `deleteMaterial` L1205–1215（无确认）。
- 对照：`NovelProjectsPage.ets` 有确认叠层 L393–429（`confirmDeleteId`），证明确认范式存在但此页未用。
- 入口与「编辑」在阅读态仅隔 `margin({right:8})`（L1705–1713），误触面成立；版本历史不覆盖手工删除。

**最小修复**：复用 `ConfirmDialog`，文案含章节名/字数与不可撤销提示。

**去重**：独立，无对应 R 项。

---

## F30 — DeepRead 取消/离页后再进入隐式发起新一轮并 REPLACE 现行任务

**状态：确认 P1（静态确定·需设备确认一次）。收窄：不再持有原报告「5s 后新旧两轮同时写」的并发后果。**

**精确行号**
- `harmony/entry/src/main/ets/pages/DeepReadArticlePage.ets`：`cancelGeneration` L282–288（只 `this.cancelled=true; this.running=false; this.errorMsg='生成已取消'` + `scheduler.abort(topicId)`，**不落 COMPLETE**）；`loadArticle` L129–188，`cached` 非空且 `force=false` 时 L133–141 仅 `isComplete` 短路，否则继续；`scheduler.run(...)` L175–176（`force:false, seedUrl`）；首用门 L152–156；`cancelled/errorMsg` 为页内 `@State` 不持久化。页面**无** `scheduler.isRunning(` 调用。
- `harmony/deepread/src/main/ets/agent/run_manager.ts`：`run` L281–309，非 force 未完成即走 `generateStages`（L293–308）。
- `harmony/deepread/src/main/ets/agent/scheduler.ts`：REPLACE `runImpl` L117–161（L126–130 有在跑即 `abort` + `waitForAbort` + 注册新 job）；`waitForAbort` L108–115（5s 兜底）；`isRunning` L220 已导出。

**机制**
- 取消只 abort 当前 signal 并置页内状态；底层 `repository` 中该 topic 仍为未完成 phase（COLLECTING/…）。用户返回后再从历史/热榜/通知进入同一 `topicId`：新页面实例 `loadArticle` 读到未完成缓存 → 发新 `run`；`scheduler.runImpl` 对同 topic **REPLACE**。
- 取消态不持久化：`cancelGeneration` 未写 KV 意图，重进无法识别「上次已取消」。

**收窄（按协调复核）** 同一 topic 的 `withTopicLock`（L142/L182）仍串行，REPLACE 的 admission 段（L125–131）在 `withAdmissionLock` 内整体串行，故旧轮 abort 与新轮注册不会在 supervisor 之外并行写。因此**不把**原报告「`waitForAbort` 5s 超时后新旧两轮同时持有同 topicId、并发写同一缓存」当成本项已成立后果——该并发窗口在正常 signal 贯穿路径下不出现；仅登记为需独立构造的边界（原文亦自注「超出 UI 定级范围」）。

**与 R20 去重** R20＝**单轮内**取消未贯穿 `source_prefetcher` collect / article-plan（底层无 signal，取消后仍工作），是「取消不生效」；F30＝取消后**重新进入**隐式发起**新一轮**并 REPLACE，是「重进隐式重跑」。两者根因不同，不合并。回归锚 `scheduler_signal.test.ts` 5/5 pass（确证 REPLACE / `waitForAbort` / token guard 语义；不代表 UI 验证）。

**最小修复**：`loadArticle` 先查 `scheduler.isRunning(topicId)`（已导出）则复用订阅，仅无在跑轮次才发新 `run`；取消/中断后进入改为「继续生成」显式卡片或 KV 记录取消意图。

**去重**：与 R20 独立；不与 R18/R22 混淆。

---

## F34 — 助手绑定模型覆盖全局选择，模型菜单仍显示全局选择

**状态：确认 P2·条件（静态确定·需构造数据）。触发前提确认为「备份恢复写入 `chatModelId`」。**

**精确行号**
- 运行时优先级：`harmony/entry/src/main/ets/di/AppContainer.ets` `resolveChatProviderChoice` L1003–1021，`assistant.chatModelId` 非空即优先返回并短路全局（L1010–1021）；发送路径 `resolveChatRuntime(conv.assistantId)` L1302–1318 传入 assistant。
- 展示/写入只认全局：`ChatPage.ets` `toggleModelMenu` L2814–2822（读 `getChatModelSelection`）、`selectModelFromMenu` L2840–2849（`saveChatModelSelection`）；`describeChatModel` L1226–1231 以**无参** `resolveChatProviderChoice()`（即全局，不含 assistant）产标签；`ChatModelPickerPage.ets` L60–74 只按全局 selection 标「当前」。
- 可达写入点核查：`ChatAssistantsPage.ets` 中 `chatModelId` 命中 **0** 处（无 UI 入口）；`AssistantBasicPage.ets` L96 虽写 `chatModelId`，但 `Assistant*` 整族为不可达孤岛（UI 报告 §3.2）；**唯一可达写入点**为 `SettingBackupPage.ets` `restoreSettings` L237–249 `saveAssistants(...)`。

**机制/后果**
- 打开绑定 `chatModelId` 的助手会话后，顶部菜单切模型只写全局键；发送实际走助手绑定模型 → 切换静默无效；菜单「当前」标记、上下文窗口、推理档位按全局（错）模型显示。
- 前提收窄：需先通过备份恢复导入带 `chatModelId` 的助手（Harmony 内无可达 UI 写入），故为条件项而非普遍可达。

**最小修复**：菜单/选择器的「当前」与写入与运行时同源（有绑定则以其展示），并提供「跟随全局选择」清除动作。

**去重**：独立。与 R02/R03 共享备份入口，但缺陷在模型解析优先级与 UI 展示不一致，不重复计数。

---

## F35 — 三个 ChatAssistant* 页 toggle 采用 load-modify-save，快速连点丢更新

**状态：确认 P2·待设备（静态确定：`assistants` 键确无串行锁）。已按协调要求查 `mutateAssistants` 锁，未照摘要。**

**精确行号**
- `ChatAssistantExtensionsPage.ets`：各 toggle 基于尚未更新的 `this.assistant` 算 ids（`toggleModeInjection` L84–92、`toggleLorebook` L94–102、`toggleQuickMessage` L105–113、`toggleSkill` L116–124）；`saveAssistant` L126–135 执行 `loadAssistants → list[index]=next → saveAssistants → this.assistant=next`。
- `ChatAssistantLocalToolsPage.ets` `toggleLocalTool` L135–149 + `saveAssistant` L151–160（同构）。
- `ChatAssistantMcpPage.ets` `toggleServer` L97–123（同构，`this.assistant=next` L122）。
- `assistants` 键写入无锁：`harmony/chat/src/main/ets/chat/kv_store.ts` `saveAssistants` L59–60 直接 `store.put(ASSISTANTS_KEY, ...)`，`loadAssistants` L63–67 直接 `get`；**无** `mutateAssistants` 包装（全仓无此符号）。

**锁现状核查（重要修正）** `KeyedAsyncMutex`（`AtomicJsonFile.ets` L60–72）与 `sharedKvMutex`（L74）**只**用于 `ExtensionPrefs.ets` L49/L60（`mode_injections`/`lorebooks`）与 `AgentRuntimePrefs.ets`/`PromptConfigStore.ets`（`agent_runtime`）。`assistants` 键**不在此列**，故 RMW 未串行化。窗口为「两次 toggle 的 `load`→`save` 交错覆盖」；具体丢更新概率/命中率需设备实测 → 待设备。

**最小修复**：写回纳入以 `ASSISTANTS_KEY` 为键的同一 KV 串行锁；或先本地乐观更新再落库、失败回滚。

**去重**：独立。与 R08（工具身份）、R19 无关。

---

## F39 — 通知深链：sourceUrl 断链窗口 + 前台无响应式消费 + 清键先于导航

**状态：确认 P2（静态确定·触发需设备）。第 1 点按报告已收窄，复核维持；第 2/3 点确认。**

**精确行号**
- 丢失 `sourceUrl`：`harmony/entry/src/main/ets/platform_impl/NotificationNotifier.ets` `buildArticleWantAgent` L35–62 声明并携带 `sourceUrl`（L44），但三处调用 L151/L156/L162 第三实参恒 `''`；`EntryAbility.ets` `routeFromWant` L89–116 只解析 `topicId`/`title`（L95–96）与 novel 三键，**无 sourceUrl 键**。
  - 纠正维持：完整且未过期缓存命中时不断链（`DeepReadArticlePage.loadArticle` L136–141 直接渲染缓存 return，不用 sourceUrl）；真实断链窗口为 (a) 部分完成（`generationComplete=false` → L175–176 重跑但 `sourceUrl=''` → `seedUrl:null`）与 (b) 缓存过期/清理（24h TTL）。缓存 `source_url` 字段唯一写入口 `AppContainer.ets` L186 `sourceUrl: previous?.sourceUrl ?? null` 首建恒 null → 死字段。`notifyFailed`（L161–165）无生产调用者（grep：仅 memory_dream / cron 各自独立 Notifier 端口），**不可达不计**。
- 前台无响应式消费：`ChatListPage.ets` 是唯一消费者（`onPageShow` L60–69、`aboutToAppear` L71–82 调 `consumeDeepLink` L85–111）；无 `@StorageLink/@StorageProp('wantTopicId')` 订阅（全仓 grep 仅 `AppStorage.get`）。栈顶非 ChatListPage 时只有栈顶页收 `onPageShow` → 深链悬挂。系统是否为同 ability 前台切换给栈顶补发 `onPageShow` 仍**待设备**。
- 清键先于导航：`consumeDeepLink` L104–105 先 `setOrCreate('wantTopicId','')`，再 `router.pushUrl` L109（未 await/未 catch）；pushUrl reject 时键已清。消费放在 `aboutToAppear`（L81）早于首帧路由就绪。

**去重**
- 第 2 点与 **R23**（非首页接收 Want 跳转滞留）**同一根因**（前台 Want 只由根页轮询消费），应合并叙述，避免双计。
- 第 3 点（清键先于导航）为 ChatListPage 侧独立实现问题。
- 第 1 点（sourceUrl 断链）与 **R22**（通知同 id 竞态）同在 `NotificationNotifier.ets` 但根因不同，不合并。

**最小修复**：wantAgent 传入真实 sourceUrl 并在 `routeFromWant`/`consumeDeepLink` 转发；ChatListPage 用 `@StorageLink('wantTopicId')` 响应式消费；先确认导航成功再清键。

---

## F43 — WebMount `wm_open` 5s 后确定性失败，站点浏览链不可用

**状态：确认 P1（静态确定·已独立复现）。与 deep-audit R07 为同一实现同一根因，必须去重合并为一条。**

**精确行号（`harmony/entry/src/main/ets/platform_impl/WebMountSession.ets`）**
- `markHostMounted` L109–111：全仓**仅有定义、零调用者**（grep `markHostMounted` 只命中本定义处）。
- `open` L139–154：`if (!this.hostMounted && this.hooks !== null)` → `hooks.onMountNeeded()`（L141）→ 50×100ms 轮询（L143–146 `throw 'WebMount host not mounted'`）；`hostMounted` 恒 false，**从未到达 `loadUrl`（L151）**。
- `WebMountHost` L220–240：仅 `onPageEnd`（L235–237），无 `.onAppear`/`.onControllerAttached` 回填挂载标记。
- 触发链：`ChatPage.ets` L875–877 `bindHooks({onMountNeeded: () => this.webmountHostVisible=true})`；工具组注册 L2971–2973；`WebMountTools.ets` `wm_open` L84–93 → `session.open(url)`（L92）。
- 影响范围：`wm_open` 是页面打开唯一入口，失败使 `wm_extract/wm_click/wm_type/wm_scroll/wm_screenshot/wm_eval/wm_wait/wm_read_page` 全部因无页不可用；`wm_tab_open`（不含 url）不受影响，`wm_state` 返回 `ready:false` 不抛。

**证据** `docs/reviews/2026-09-13-harmony-deep-audit/probe-f-mount.cjs`（真实 class 转译、加速时钟、无 SDK/UI/网络）输出：`actual session.open takes all 50 mount-wait ticks then rejects; loadUrl never invoked`。日志已转存 `probe-f43-webmount-mount.log`。deep-audit R07 另有 `review-df`/全仓 setter 搜索佐证。

**去重**：F43 ≡ R07（同文件同方法同根因）。合并报告应只保留一条 P1。安全面 **L01**（`wm_click` 注入）与 **L02**（userinfo/非 http scheme 白名单绕过）是独立缺陷，且其端到端利用被 R07 遮蔽——修复 R07 时必须一并修 L01/L02，不可只解锁挂载。

**最小修复**：在真实 controller 就绪的生命周期（`onControllerAttached`/`onAppear`）闭环回填 `markHostMounted`，并在销毁时对称复位；修复后需设备复测 `wm_open` 能真正加载并截图。

---

## F44 — 聊天文档附件缺少打开/预览路径

**状态：确认 P2（静态确定）。**

**精确行号**
- `harmony/entry/src/main/ets/components/ContentAttachmentChip.ets` `build()` L15–33：整段无 `onClick`/回调，纯展示。
- 调用侧 `harmony/entry/src/main/ets/components/AgentMessageRow.ets`：`video` L357–362、`audio` L363–369、`document` L370–376 均只传 `icon/label/sublabel`，无打开回调；`ContentAttachmentChip` 全仓仅此 3 处调用。
- 条件限定（维持报告）：音视频附件仅在「历史导入数据」下出现，当前代码路径不生产；核心是 `document` 附件无打开/预览路径。

**最小修复**：给 `ContentAttachmentChip` 增加可选 `onOpen` 回调并在 `AgentMessageRow` 接线到既有打开/预览路径；不可打开类型降级为纯展示并去可点暗示。

**去重**：独立。

---

## UI-X01（新增）— 已完成文章重新进入不加载字号/字体/模板设置

**状态：确认新增 P2（静态确定；行序事实，未做 UI 渲染/截图）。独立于 F29（F29 是 editorial_slant 模板忽略缩放）。**

**精确行号（`harmony/entry/src/main/ets/pages/DeepReadArticlePage.ets`）**
- `loadArticle` L129–188：缓存命中且 `force=false` 时，`applyOutput` 后若 `isComplete` 即 `this.running=false; return`（L133–141）——**早退发生在** `deepread_font_scale` / `deepread_font_mode` / `deepread_template_id` 读取（L144–150）**之前**。
- 全文无 `onPageShow`；显示设置只在 `aboutToAppear`（经 `loadArticle`）读取一次。`@State fontScale` 初值 `FONT_SCALE_DEFAULT`（L76）、`fontSerif=false`（L77）、`templateId='none'`（L69）。
- 后果：已完成文章重进 → 字号停留 1.0、字体停留非衬线、模板停留 none；用户偏好在此期间被静默忽略。原生分支 L515/L569/L695 与模板分支 L464 才消费 `fontScale/fontSerif/templateId`，故实际呈现与偏好不符。

**证据** `probe-ui-x01-deepread-cache-earlyreturn.cjs`：
- **被测代码来源**：用 `probe-lib.cjs`（TypeScript AST）从 `DeepReadArticlePage.ets` 原样抽取 `loadArticle`/`applyOutput`/`phaseLabel`/`progressLabel`/`stageLabel` 方法体与相关字段、`ArticleParams` 接口、原 import 头，组装 headless class；抽取时断言切片含 `isComplete(cached.output)` 与 `deepread_font_scale`，并断言**偏移顺序** `isComplete` 门(421) 早于字体读取(636)；打印 `loadArticle` sha256 前缀。依赖的 `domain/helpers.ts`/`platform/deep_read_progress.ts`/`domain/enums.ts` 为**真实源**。
- **安全边界**：仅 mock KV（instrumented `get`）/仓储/scheduler；无 UI 渲染、无设备、无网络、无真实文件。
- T1：完整缓存重进（force=false）→ KV 读取序列 `[]`，`fontScale=1.0 / serif=false / templateId='none'`。
- T2（对照）：部分缓存重进 → KV 读取 `['deepread_font_scale','deepread_font_mode','deepread_template_id','deepread_first_use_confirmed']`，偏好正确应用。
- 日志：`probe-ui-x01-deepread-cache-earlyreturn.log`（含 `[extract] loadArticle sha256/16: 5b79c94353aeac4c len 2930`）。

**限定**：结论为源码行序事实；不声称已截图或真实运行观感。

**最小修复**：把显示设置读取（L144–150）移到完整缓存早退（L137–140）之前，或补 `onPageShow` 重读并应用。

**去重**：与 F29 不同（F29＝模板模式下缩放按钮死控件）；与 F16（显示设置零消费）不同（此处是读取时机遗漏而非未接线）。

---

## R11 抽屉子命题 — 拆除死抽屉，保留 compact 残留

**状态：`openDrawer` 零调用 → 抽屉删除路径不可达；R11「抽屉删除只删 RDB 不 cleanup」应降为潜伏死路径。原清理 compact 残留仍为活跃问题。**

**精确行号（`harmony/entry/src/main/ets/pages/ChatPage.ets`）**
- `openDrawer` L2704–2714 为全仓**唯一**定义，`grep -rn "openDrawer\b"` 仅命中定义处，无调用方（无属性回调、无别名、无 button 绑定）。
- `drawerOpen` 置 true 仅 L2713（在 `openDrawer` 内）；其余 L778/L2717/L2722/L2743/L2764/L2769/L2774/L2779/L4881 均置 false 或读取。故 `if (this.drawerOpen)`（L4881）分支永不渲染，`drawerConversationList` L4164–4172 → `deleteDrawerConversation` L2727–2752 不可达。
- 因此 R11「`ChatPage.ets:2727-2752` 无 cleanup」为**死路径**，不应作为当前用户故障计数。

**活跃面（保留）**：所有**可达**会话删除入口均已 `cleanupDeletedConversation`：`ChatListPage.ets` L248–250、L267–268；`ChatAssistantsPage.ets` L331–332；`AssistantListPage.ets` L131–132。但 `cleanupDeletedConversation`（`ManagedFileStore.ets` L208–227）实现为 `deleteChatFiles + clearPendingQueueStorage + removeFavoritesOfConversation`，**不含** compact 清理（`EntryCompactStore.ets` `deleteByConversation` L53–59）。故「所有删除漏 compact 摘要」这一 privacy/孤儿残留子命题**仍活跃**（`getCompactStore().getCompacts` 消费于 ChatPage L1606/L2034/L2410/L2461，删除侧无对应清理）。

**去重/修正**：合并报告应把 R11 拆为——(a) 死抽屉路径（潜伏，不计当前）；(b) compact 摘要删除残留（活跃，P2/P3 隐私/孤儿数据）。

---

## 未展开核对（维持原报告判定，无新证据推翻）

- 原报告 §3.1 已驳回项、§3.2 死码维护债本次未重新逐条复核，沿用其结论；本报告只对分配到的 F 项与两项协调追加项给出独立判定。
- 无在线设备：`hdc list targets` 仍为空；本报告所有「待设备」限定不升级为「已截图证实」。
- 未修改 `2026-09-13-harmony-ui-ux-motion-review.md` 与 `2026-09-13-harmony-deep-audit/**`。

---

## 产物清单（均在 `docs/reviews/2026-09-13-harmony-merged-evidence/`）

- `probe-lib.cjs`（TypeScript AST 抽取器 + 通用 transpile loader；供两份探针复用）
- `probe-f20-clean-orphans.cjs` / `probe-f20-clean-orphans.log`（AST 抽取真实 `SettingFilesPage` 方法组装 headless；mock FS）
- `probe-ui-x01-deepread-cache-earlyreturn.cjs` / `probe-ui-x01-deepread-cache-earlyreturn.log`（AST 抽取真实 `DeepReadArticlePage.loadArticle` 等）
- `probe-f43-webmount-mount.log`（转存 deep-audit `probe-f-mount.cjs` 运行输出）
- `probe-f43-webmount-open.log`（转存 deep-audit `probe-f-webmount.cjs` 运行输出，含 L01/L02）
- `.probe-tmp/`（探针运行时生成在 evidence 目录内的 headless 组装文件；可随时删除，不含业务产物）
- `ui-risk-recheck.md`（本文件）
