# 深读独立版闭环与调用链只读复核

审阅日期：2026-10-05。基线 HEAD `b2d054bd7a81c64795497098d54cf30ad049e04f`，以当前工作区实际源码为准；现有未提交修改未作为本轮变更范围。只写本报告，不修改生产代码、测试、安装或设置。本报告不借用此前构建或设备验收作为本轮运行证据。

结论：创建、配置、真实作业、采集/规划/四段校验、持久化完成、阅读库重开、取消/失败/续跑/重试与导出有真实调用链，未发现该主链上的 P0/P1 阻断。两个 P2 细节缺陷已用当前实际方法/显示表达式复现：多来源正文错归属、浏览器失败时虚报复制成功。硬件通知点击、真机后台保活和真实云端服务成功率未在本子任务中验证。

## 发现

### C-01 · P2：聚合热点的每张文本来源卡展开后重复显示所有来源正文

触发：在发现页打开有多个报道来源的聚合热点，再打开来源页，分别展开两个 `kind=text` 来源。

影响：卡片标题是单一媒体/来源，正文却被全局合并 `inputText` 替代，造成重复和内容错归属。生成所用输入本身未丢失，但用户核查证据时无法按卡片分辨对应来源。

调用证据：

- `harmony/deepread/src/main/ets/domain/discovery_input.ts:15` 对每个报道构造独立文本输入。
- `harmony/entry/src/main/ets/pages/DeepReadArticlePage.ets:368` 保存所有输入，369–370 行把各文本来源正文拼成全局 `inputText`。
- `harmony/entry/src/main/ets/pages/DeepReadSourcesPage.ets:222` 对任何 `kind=text` 来源优先显示全局 `inputText`，覆盖 `source.content`。宿主对应 148 行也存在相同表达式。

本轮实际表达式复现：从文件抽取未改动的 `source.kind === 'text' && this.inputText.length > 0 ? this.inputText.substring(0, 40000) : source.content`，给 A/B 两个不同来源及其合并 `inputText`，两次输出完全相同，均为 A+B，分别不等于对应来源正文。没有自造渲染方法，也没有创建真实文章。脚本 `/tmp/amber-closure-actual-method-repro-20261005.cjs`。

建议最小修正：来源卡展示自己的 `source.content`；若仍需保留 Composer 超长手动输入预览，明确限定到唯一对应手动文本来源，不对全部 `kind=text` 输入使用合并字段。补聚合热点至少两个来源的显示归属验证。

### C-02 · P2：打开原链接失败后显示“已复制链接”，实际未写剪贴板

触发：发现页长按热点 → 打开原链接，浏览器 `startAbility` 拒绝，或同步抛异常。

影响：用户按提示去粘贴时仍得到旧剪贴板内容，失败恢复路径没有闭环。

证据：`harmony/entry/src/main/ets/pages/BoardPage.ets:469` 的 `openOriginal` 只调用浏览器，478 / 481 行两个失败分支提示“已复制链接”，没有任何 pasteboard 操作。相邻 485 行 `shareTopic` 的复制实现不会被此方法调用。

本轮实际方法复现：从当前文件抽取整个 `openOriginal`，TypeScript 转译后注入拒绝 Promise 与同步抛异常的 `startAbility`，两条路径均收到“无法打开浏览器，已复制链接”，剪贴板写入次数均为 0。脚本同上。

建议最小修正：失败提示改为实际错误；如果产品需要复制作为后续路径，再明确调用已有剪贴板 API，只有成功后提示已复制。不应为一条文案增加泛化恢复层。

## 调用链表

证据级别：S = 当前源码逐段核对；T = 本轮现有窄测试实际运行；E = 本轮实际方法/表达式复现；D = 本轮设备操作。本子任务没有独占设备操作，故不使用 D。

| 功能 | 实际入口 → 下游 | 核对结果 | 证据 |
|---|---|---|---|
| 手动主题、文本、链接创建 | `DeepReadComposerSheet.createArticle` (122–167) → `sourceInputs` / `captureDeepReadTemplate` → `getRepository().upsert` → `onCreated` → Article | 输入与模板快照先提交，再移交文章页运行；路由失败保留 `pendingTopicId`，重按仅打开已保存稿，不重复创建 | S、T |
| 文件创建 | Composer `pickFiles` (83–101) → `DocumentViewPicker` → `importDeepReadSource` → `readSelectedBytes` / 文本解码 / PDF / DOCX → `makeInputSource` → 同创建链 | 选择时读取许可内文件并保存提取文本；20 MiB 限制；扫描 PDF 无文本失败来源可见；取消/销毁 token 阻止迟到 UI 更新 | S；设备文件授权及扫描 PDF 未本轮操作 |
| 热点、随机阅读 | `BoardPage.randomItems` (308) / `randomRead` (333) / `openArticle` (460) → `discoveryArticleParams` → Article `saveDiscoveryInputs` (365) → 同 scheduler | 同一候选域携带真实来源元数据和 seed URLs；Board 没有伪造作业/先启动再延时跳页。多来源展示存在 C-01 | S、T、E |
| 首次确认与文章重开 | Article `loadArticle` (285–363) → KV 首用确认 → `getDeepReadScheduler` → 真实 `isRunning` / 完整缓存 / 显式继续判定 | 未确认不生成；完整缓存直接读；已活动作业只订阅；部分/失败/中断稿需要显式继续，不隐式 replace | S、T |
| 独立版模型设置 | `SettingDeepReadPage.saveStandaloneReading` (827) → `saveDeepReadModelSelection` → task pair KV → `resolveDeepReadRuntimeCore` (AppContainer 1802) | 固定 provider+model 精确解析，失效显式报错；自动选择读当前聊天模型；草稿选择未点击保存不影响新运行；服务目录不是旧三字段客户端 | S、T |
| 搜索设置与采集 | `DeepReadSettingsForm` (112–131) → `updateSearchPrefs` 合并目录/保留凭据 → `SearchRegistry.snapshot` (411) → `source_prefetcher.collect` (182–) | 按稳定 service ID 保存选择；运行开始捕获搜索快照；cacheKey 含快照，设置变化不误用旧抓取缓存；免费源/配置服务/Google fallback 走同 registry | S、T；所有真实搜索服务未联网逐一验收 |
| 模板配置 | Composer / Article / `AppContainer.createRunManager` (265–333) → `captureDeepReadTemplate` → output.templateSnapshot → reader/export resolve | 文稿以持久快照解释；继续沿用已有快照，明确新生成可取新设置；预览不代替真实保存 | S、T |
| 作业所有权 | Article `runNow` (440) → scheduler `executeJob` (147–) → admission/topic lock → per-job abort controller → onRunActivity | 实际 token 持有运行注册；旧 finally 不删除新 job；配置/采集阶段也入 activeRuns；running 不从历史 phase 假推 | S、T |
| provider 调用 | `AppContainer.createRunManager` → `prepareProviderRuntime` → `createChatDeepReadAiClient` → actual api.generate / signal / tools或structured | 模型、auth、headers、params 为每轮 runtime 快照；能力有 tool 走工具写段，否则 structured JSON，非无工具即功能缺失 | S、T；真实云端认证/输出质量未本轮调用 |
| 采集、规划、四段写入 | `run_manager.createRunContext` (104–) → prefetch snapshot/merge → evidence pack → `generateArticlePlan` → `generateStages` (208–) → supervisor / structured writer → section_writer_tools | 无有效正文不按标题假造研究；空输入失败入可重试历史；四段内容校验达 READY；JSON 不合法不当完整稿；READY 段续跑保留 | S、T |
| 完成落盘与盖章 | run_manager `saveGuarded` → `DeepReadRunRepositoryAdapter.save` (44) → RDB `upsert`/`saveEntry` (243,302) → writer finished save → `onProgress(COMPLETE)` → Article `celebrateObservedRun` (247) | RDB 完成提交后才发布 COMPLETE；盖章再次确认已保存四段完整稿；save 拒绝真实上抛；重生成失败保留旧稿，Workspace 为次级产物不能逆转文章事实 | S、T |
| 阅读库 | RDB `observeHistory` (394) + scheduler `observeActiveRuns` → `observeDeepReadLibrary` / `queryDeepReadLibraryRows` → History `reopen` (309) → Article | 无缓存但真实活动 job 可显示；历史失败/完整/未完成分开；状态按真实 activeIds 优先；过期不是删除已有文章 | S、T |
| 取消/失败/继续/单段重试 | Article `cancelGeneration` (542) / `continueGeneration` (471) / `retryFirstFailure` (666) → scheduler.abort/run/runSection → run_manager missing stages / guarded persistence | signal 贯穿采集和 provider；取消不回滚已经成功提交的完成稿；继续仅补非 READY 段；单段重试与 full run 共用 activity/通知收口 | S、T |
| 来源核对 | Article toolbar → Sources `repository.observe`+初读 version guard → `openSource` / disclosure | 跟随已持久来源变化、开链接显式失败；正文归属 C-01；发现页原链接恢复提示 C-02 | S、E |
| 文本/Markdown/PDF 导出 | toolbar `openExport` → ExportPanel.loadSnapshot → repo.get → deepReadToText/Markdown/renderCapturedTemplate → ExportFiles picker/share/PDF | 导出固定已提交快照，部分稿允许文本与 Markdown，PDF 要完整稿；PDF API14 门控且 API12 明示不可用；文件选择/分享 lifecycle 防迟到操作 | S、T；实际系统文件分享/打印未本轮设备验证 |
| 通知点击与冷启动路由 | scheduler notifier → `NotificationNotifier.buildArticleWantAgent` → EntryAbility.publishWantRoute → ready root → `routePendingWant` → Article | bundle 来自 ProductIdentity；稳定 topic 与完整 title 贯通；pending seq 防迟到清新 Want；路由失败保留 pending。尚需真实多通知点击验收 | S、T |
| 后台、中断恢复 | `setDeepReadGenerationActivity` → Shared generation tracker / BackgroundGenerationKeepAlive；EntryAbility 前后台；冷启动 Article 重开 | 真实运行 token 注册保活，实际收到的字节更新进度；不宣称跨进程持续运行；进程死后靠已存输入/分段显式续跑，而非 daemon | S、T；真机长时间后台及系统回收未测 |

## 本轮验证

运行 11 个现有窄测试文件，**114 tests / 114 pass / 0 fail / 0 skipped**，7.5 秒完成。日志 `/tmp/amber-closure-review-tests-20261005.log`。

测试文件：`run_manager.test.ts`、`scheduler_signal.test.ts`、`scheduler_start_failure.test.ts`、`scheduler_progress.test.ts`、`input_sources_run.test.ts`、`deepread_library.test.ts`、`deepread_export_panel.test.cjs`、`deepread_export_files.test.cjs`、`deepread_want_route.test.cjs`、`deepread_model_selection.test.cjs`、`deepread_activity_navigation.test.cjs`。测试使用注入式 mock，不写生产 RDB、KV、账号或真实云端。没有通过删除/放宽断言来得到结果。

另运行 `/tmp/amber-closure-actual-method-repro-20261005.cjs`，当前源码 method/expression 的三个案例均确认上述 P2 问题（浏览器拒绝、同步抛异常、多来源正文）。

## 已排除的可疑点与证据边界

1. 通知 requestCode 固定 991 不能直接套 Android PendingIntent 规则报错。官方 [OpenHarmony PendingWantManager](https://github.com/openharmony/ability_ability_runtime/blob/master/services/abilitymgr/src/pending_want_manager.cpp#L304) 在 key 匹配时比较 requestCode 并调用 Want.IsEquals；官方 [Want.IsEquals](https://github.com/openharmony/ability_ability_base/blob/OpenHarmony-5.0-Release/interfaces/kits/native/want/src/want.cpp) 包含 parameters 比较，5.0 / 5.0.1 / 6.0 / master 均核实。因此不同 topicId 的参数不同，不能仅凭固定 991 断言相互覆写。本机 API24 实际 WantAgent identity 未直接测。
2. 通知发布失败不会把已保存文章判失败；scheduler 对终态通知失败仍撤运行通知，测试覆盖。running 通知采用现有 best-effort，不因无通知授权堵生成。
3. `DeepReadRunConfig` / `ChatDeepReadDraft` 的旧字段辅助并不是 standalone 实际运行入口；真实链走服务商目录 runtime，不因存在旧帮助类即判默认 API Key 路由。
4. 完成文章、Workspace 自动副本、通知投递是三个独立结果；副本/通知失败可恢复，不应混报“文章未保存”。
5. 本子任务是代码链路与注入测试审阅，不能据此宣称 API12 真机、所有云端 provider、文件分享、无掉帧、真实马达/摇动和后台系统回收全部验收通过。
