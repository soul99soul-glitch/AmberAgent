# DeepRead 运行闭环独立复审

审查时间：2026-10-03。起点 HEAD `5ea51f7a5`，审查对象是本轮工作区实际生产源码及测试；原有 dirty/untracked 工作全部保留。旧审查结论不作为本轮通过依据。已读 `AGENTS.md` 和 `.agents/skills/harmony-arkts/SKILL.md`。最初只读审查，随后主 agent 明确授权精准修复下述 DR-L1、DR-L2、DR-L3；本 agent 修改范围为三个生产 TS 文件、两个回归测试文件和本文档，未构建、签名、安装或操作设备。

本轮确认三个 P2，均已在授权范围内修复并通过回归。不能据现有测试宣称全部实机或在线提供商验收完成。

## DR-L1：旧版部分稿续跑丢失已推断的 READY 状态（P2，已修）

触发：存储文章有 `summary`、`timeline`、`analysis`，但 `sectionStates={}`；这种旧稿兼容形态是 `withInferredSectionStates()` 明确支持的输入。旧稿只缺扩展阅读时，`run()` 根据推断结果仅选择 `EXTENDED_READING`，而 `createRunContext()` 却将未推断的原始缓存交给 writer。

原定位：`harmony/deepread/src/main/ets/agent/run_manager.ts` 的 stage 选择分支（当前 435–450 行），writer 初始化（当前 179–185 行）。`DeepReadRunRepositoryAdapter` 和 `RdbRepository.parseOutput()` 不会自动补状态，因此生产路径可以保留该输入形态。

实际反例：直接调用当前真实 `run()`，提供旧稿、真实 writer/structured 合并器和只写扩展阅读的模型响应，得到 `calls=1, ok=true, generationComplete=false, sectionStates={EXTENDED_READING:READY}`。旧正文仍在，但第一次继续无法完整收口；第二次继续因非空状态不再推断前三段，可能重新生成它们，违反已完成段不重写的续跑约定。单段 `runSection(EXTENDED_READING)` 有相同问题。

最小修复：非 force writer 初始化同样调用 `withInferredSectionStates(cachedOutput)`，与 stage 选择保持一致。force 仍使用空 writer。回归测试覆盖继续与单段重试：只请求一段、一次完整落盘、前三段正文和模板快照不变。

## DR-L2：force 抓到更新网页，写作仍使用旧正文（P2，已修）

触发：Standalone 草稿已保存 ready web/search 来源；force 路径重新抓取同 URL，网站正文已经更新。`createRunContext()` 保留用户来源，并强制重新抓 URL；原 `mergeCollectedSources()` 对 ready 来源无条件跳过新正文，使这次成功抓取被丢弃。

定位：`run_manager.ts` 当前 133–158 行；`harmony/deepread/src/main/ets/domain/input_sources.ts` 当前 55–70 行。

实际反例：注入旧来源正文“旧的网页正文”，prefetch 返回同 URL 的“新的网页正文”；真实 `createRunContext(force=true)` 成功，但 `context.evidencePack.allSources[0].evidenceText` 仍是旧正文。这是成功结果被合并器丢弃，与网络可用性无关。

最小修复：来源合并新增默认关闭的 `refreshWeb` 参数，只有 force 传 true。成功重新读取的同 URL web/search 更新正文和对应 research metadata，保持输入 source ID；失败仍保留旧 ready 正文；本地 text/file 不因 URL 相同被替换或改写。普通继续的来源复用行为不变。输入数组采用副本，完整新稿落盘之前不改原缓存。

回归测试检查更新正文实际进入 evidence pack；输入 source ID 与最新 research source metadata 关联；同 URL 的 text/file 完整对象和原 `inputText` 保留；新抓取失败仍使用原 ready 正文。

## DR-L3：可选 Google 补充超时使已获得的免费来源一起丢失（P2，已修）

触发：免费引擎已经成功返回 1–2 个 hits，Google WebView 补充开启且单次导航需 11 秒。`free_aggregate` 等待 Google 后才返回已有 hits，而 prefetch 将整个 aggregate 当作一个 provider，10 秒截止即丢空。原生 `WebMountSession` 页面加载超时是 15 秒、宿主挂载超时是 10 秒，生产契约允许该窗口。

定位：`harmony/chat/src/main/ets/search/deepread_search_registry.ts:65` 到返回 hits 的分支，尤其当前 71 行等待 Google；`harmony/deepread/src/main/ets/research/source_prefetcher.ts:242` provider 的 10 秒 deadline/catch-empty。

实际探针使用真实 `createDeepReadSearchProviders()` 与 `createSourcePrefetcher()`，免费 provider 即刻给两个公开 URL，HTTP 端口能立即返回足量正文，Google 端口 11 秒返回空。结果：

```json
{"ms":10003,"freeHits":2,"googleCalls":1,"fetches":0,"sources":0,
 "issues":[{"url":"","title":"搜索：free_aggregate","error":"prefetch deadline exceeded"}]}
```

在外部来源已经成功返回时，可选补充层导致完整 bucket 丢失，进而首次深读无来源失败。现有“Google 抛错仍保留普通 hits”测试只覆盖即时拒绝，不覆盖晚于外层 deadline 的响应。

最小修复：aggregate 在免费收集开始前计时，复用本文件既有 `withinBudget()` 和 9 秒预算；每次 Google 只使用总预算剩余时间，耗尽即停止后续 query，及时返回已得 hits。保留原生 Google 的 exact request signal；在途请求继续由既有 prefetch child signal 的取消/最终释放处理，未新增 controller、重试或 fallback 结构。

跨层回归使用真实 aggregate 与 prefetch，mock clock 让免费源耗时 8 秒，Google 挂起，仅有 1 秒补充预算：修改前 9 秒仍未返回、10 秒丢桶；修改后 9 秒已经抓取两篇正文并返回，prefetch finally 精确取消原 Google 请求。另测免费源耗尽 9 秒后零 Google 调用。两例先 RED 2/2 fail，修改后 GREEN 2/2 pass。

## 当前闭环及证据范围

| 链路 | 当前代码依据 | 本轮证据 |
| --- | --- | --- |
| 真实 factory / 模型 | `AppContainer.getDeepReadScheduler()` 每轮解析 exact provider/model，`createChatDeepReadAiClient()` 使用该轮参数快照；有 tool 能力才选 tools，否则 structured | 实际 Entry factory 执行测试；Chat/Responses/Claude/Google 真协议序列化与工具往返测试；本地 HTTP SSE 的五调用四段写作测试 |
| 来源与选择 | `SearchRegistry.snapshot()` 捕获设置；selected enabled service 走实际服务；免费源与 native Google 走同 registry；用户正文和文件可直接作为 evidence | provider 测试验证实际 URL/请求字段；source prefetch 的真实 Entry controller/HTTP adapter 测试；DR-L3 跨层预算回归已通过 |
| 四阶段 / force | OVERVIEW→NARRATIVE→ANALYSIS→EXTENDED_READING；既有 ready 章节续跑保留；force 在完整新稿成功保存前保留旧稿 | focused tests 覆盖 tools/structured、invalid JSON correction、取消、force失败、保存失败；DR-L1/2 先 RED 后 GREEN |
| 取消 / 重入 | per-job signal、admission/topic locks、token 防迟到清理；配置失败首次存可重试状态、旧文章不被覆盖 | 实际 scheduler tests 验证信号贯穿、并发 replacement、迟到 factory、通知与活动释放 |
| 历史 / visible | `observeDeepReadLibrary()` 结合持久条目与 active jobs；HistoryPage `onPageShow/onPageHide` 对称订阅及清理 | 实际页面方法执行测试覆盖 hide 后旧回调、return 重订阅、无模型仍读历史、首稿前实际作业入库列表 |
| 模板快照 | composer/DI 采集文章快照；续跑优先原快照，显式新快照优先默认；reading/export 解析同一快照 | 实际模板 CRUD/选择测试；custom missing 回退明确提示；PDF preview 读取保存快照测试 |
| Repository / Workspace | 真实 save await upsert 后更新缓存；仅完整成功触发稳定 topic artifact；原子文件及 KV 状态各自收口 | 文件写入/rename失败保旧稿、queued save 内重读最新持久文章、upsert失败不触发artifact、KV失败单独报告的可执行测试 |
| 导出 | text/Markdown 来源为保存快照；PDF capability+当前文档就绪门控；picker/share有实例owner和活跃守卫 | 完整章节保留、短写、byteOffset、取消picker、关闭host、异步PDF、模板归属等可执行测试；未验证设备分享接收者和真实PDF视觉 |
| native Google owner | 每篇文章独立 `WebMountSession(false)`；绑定、open后、eval后检查 owner；解绑不动替换宿主；不发布Chat状态 | 实际ETS转译执行测试覆盖异步eval期间解绑、跨topic controller、取消、旧解绑、DOM抽取、Chat AppStorage隔离；未在线验证Google页面/验证码 |

文章页 force 时准确显示固定“重新生成中，原文章已保留”。scheduler 的阶段进度已发布，但文章页仅订阅 output/running，没有消费 active stage 进度；此处记录为展示细节，不夸大为错误 100% 文案，也未扩大修复范围。

## 验证记录

- 新增 `harmony/deepread/src/test/deepread_refresh_review.test.ts`：修改前 3 fail / 1 pass，修改后 4/4。
- DeepRead 相关 focused batch：62/62，包含新增回归、run manager、input sources、structured pipeline、scheduler signal/progress/start failure、真实 Entry source prefetch 与 writer contract；随后 DeepRead `tsc --noEmit` exit 0。
- 实际 Entry factory、Google、History、模板UI、搜索选择页面测试：33/33。
- Workspace artifact / history / template / export / generation activity / continue：88/88。
- Chat DeepRead provider、structured HTTP、search registry + 新增 `deepread_search_budget_review.test.ts`：27/27；新增搜索预算回归修改前 2 fail / 0 pass，修改后 2/2；Chat `tsc --noEmit` exit 0。实际 runtime Entry tests 经 `tsx --test` 运行 5/5。直接 `node --test` 运行后一组不能解析 `@amber/deepread-domain` alias，属于测试启动方式，换项目 tsx runner 后通过。
- 两个变动生产 TS 文件 scoped ArkTS lint：exit 0，0 error / 194 warn。未将 warning 数量当成全仓基线结论。
- 搜索 registry 单文件 scoped ArkTS lint：exit 0，0 error / 81 warn。
- 本轮未构建/签名/安装/启动应用；未执行实机UI、后台保活、远端provider或Google在线功能验收。
