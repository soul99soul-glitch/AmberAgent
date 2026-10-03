# P4 状态、身份与连续性修复独立审查

状态：**CLOSED，冻结源码与独立调用测试通过，无剩余已确认问题**。审查范围仅 backlog C03/C11/C13/E03/E04/C16；工具、章节合同和 UI 视觉细节由另 reviewer 负责。本 reviewer 未实施生产代码，只维护本报告；已读取 harmony-arkts。构建、云模型与实屏验收不由本报告代替。

## 冻结实现独立核查结果

- 独立运行 `novel_state_rebuild`、`novel_structured_state`、`novel_continuity_repair`：**47/47**。真实 file repository + creation 的 cold retry 跳过成功段、正文/分支漂移、取消/释放、部分失败不清创作门禁、三种身份 cold read 和后续真实输入均通过。
- 单条与批量修复统一走 `creation.repairContinuityIssues`，同一 workspace snapshot/CAS 准备所有原始范围；同章重复/重叠在模型前拒绝；所有输出完成后倒序合并，每章一个 write patch，最终一个现 CAS proposal。未批准及拒绝不改稿，批准沿原版本/剧情事务。
- 额外独立调用两章 batch 验证一个 proposal/two patches、批准后每章恰好一条旧版本；再在第二请求期间用仓库提交正文变更，冻结 CAS 拒绝发布，零部分 proposal 且作者改稿保留。没有新增生产代码或测试文件。
- 独立实际参数测试 `novel_request_config`、`novel_state_reasoning`：**8/8**。AppContainer.novelRuntimeConfig 的 makeProvider 实际先构造小说 params，再调用 applyNovelStateReasoning；adapter 将 taskOptions 送到这个 output-token provider。state off 的 DeepSeek/Claude/Gemini payload 验证通过；官方 OpenAI helper 只限显式 off、官方 host、reasoning ability 和已列型号/日期变体，Chat Completions / Responses 实际 builder 输出 none。ordinary、on、其他 host、未列模型和无能力模型保留对象身份；不泛称所有模型都能关闭推理。
- 追加独立运行 `novel_audit_pair_evidence`、`novel_continuity_repair`、`novel_state_rebuild`、`novel_structured_state`、`novel_continuity_audit`、`novel_audit_coverage`：**71/71**。canonical refs 的真实较早顺序、当前摘要和逐字范围验证通过；虚构 quote、旧 digest、同章、后章和缺章均不发布 issue；合法单章勘误保留无 canonical 路径。
- 额外真实调用 `/tmp/amber-p4-pair-callchain-review.mts`：provider 仅解析 actual 审计 prompt 的来源与目标范围，未从项目 fixture 偷取引用；审计核验 → 修复 actual JSON → 未批准正文不变 → 作者批准只改后章，早章零版本、后章一条旧版本。模型伪造 ordinal=999 归正为真实 1。修复请求期间仓库直接提交早章作者改稿，整个 frozen CAS 拒绝发布，零部分 proposal 且作者改稿保持。
- 远章输入精准修复后，同一组合独立重跑 **72/72**，日志 `/tmp/amber-p4-state-review-focused.log`。新增实际 provider 从第三章 request JSON 读取第一章已取证 quote 和非零偏移 17，直接返回成对引用并通过核验；原独立反例重跑 `remoteRangePresent:true`。生产只在审计中将 prune 后的早章事件投影为当前逐字范围，状态存储和通用上下文未改。
- Canonical UI 冻结后独立运行真实 ETS 方法/Builder 抽取 **22/22**（`/tmp/amber-p4-canonical-ui-review.log`）。Report 的早章章号/标题/quote/44vp 阅读按钮 → Workspace callback → 同一快照的分支、CAS.branch、字面范围、摘要与真实更早顺序验证 → Reader 参数链通过；陈旧摘要、错误范围和换分支都不导航。
- Reader 原子读取修复冻结后，独立运行 `novel_operations_state_ui`、`novel_revision_brief_ui`、`novel_version_fork` **37/37**（`/tmp/amber-p4-canonical-reader-review-final.log`）。覆盖实际 Reader.reload 的来源漂移、同正文换分支、合法同快照 focus、离页迟到；新读取只消费一次 repository 持锁 snapshot 的 project/status。额外复验后，当前正文与页面均为 `New author fact.`，旧证据退出 focus 并出现重新审校提示。成对修复端到端及运行期间早章 CAS 漂移反例再次通过。

独立额外调用结果：

```json
{"multiChapterProposalCount":1,"patchCount":2,"oldVersionsPerChapter":[1,1]}
{"sourceDriftRejected":true,"partialProposals":0,"authorBodyPreserved":true}
```

## 本轮确认的精确问题

1. **C16 缺成对先文证据输入。** 当前 Native audit issue 是单 chapter quote/digest/range；prepare repair 输入只有 target sourceContent 与 summary/suggestion，没有核验的早章 canonical references。iOS `NovelContinuityAudit` 的 mapper 核对两侧真实证据，`NovelContinuityRepair.targetReference/canonicalReferences` 选择较晚章并将较早原文传进实际请求。Native 同样可以通过核验 quote 的第 1 章 issue 产生 patch，不能以目前提示词“保留先文事实”宣称两侧事实保护已完整。Root 已分派 owner 在现 audit issue/严格取证/repair input 加最小 pair references；合法无 pair 单章问题保留既有行为，不另建事件 graph。
2. **C11 全事件展示入口缺失。** `NovelStatePage` 仅遍历 experiences→experience.events，没有当前分支完整 state.events 时间线。合法 entityRefs:[] 的环境/情节事件能持久并进上下文，却没有页面展示；当前 iOS Compendium 事件时间线含所有当前分支事件。已交 UI worker 用现数据补展示，不新增存储。
3. **批量同章 diff 预览键重复。** `NovelOperationsPage` 的 preview ForEach key 为 chapterId:label；同章两条 repair previews 的二者完全相同，可能复用/漏展示。应仅将已有 start/end 纳入唯一键；事务倒序合并已通过，问题在预览调用表现。
4. **身份确认文案早于实际关联。** NovelStatePage create/merge 文案称立即关联已提取经历；creation 仅写新人物/alias+clarification，不改变既有 events.entityRefs，经历投影不会立即绑定旧 refs:[] 的事件。下一轮抽取才会用确认身份关联。已建议仅调整文案到后续状态提取含义，不按名字暗造事件引用。

第二次页面复查：上方第 2、3、4 项已关闭。新增 Timeline 使用已 prune 的当前分支 state.events，按有效章节顺序展示所有事件，包含 entityRefs:[]；重复预览键加入已有 range 和 index；identity 文案准确说明后续抽取用途。独立运行真实 ETS 方法/Builder 抽取的 `novel_operations_state_ui.test.cjs` **19/19**，包括上述三项及批量取消后不路由。这是源级调用证明，不替代另一 reviewer 的实屏视觉验收。

第 1 项的引用校验与修复事务已通过上述测试和额外真实调用。追加发现的窄输入残留也已关闭：原 `continuity_audit.planAuditBlocks` 只给紧邻前章尾 1000 字的 canonical sourceRange；远前章状态事件仅给 chapterId/digest/quote。独立三章反例先真实 parse/merge 第一章状态，quote 偏移 17，再捕获第三章 request，确认模型缺少必需 start/end 的输入。Owner 已只在审计输入投影当前已取证早章事件的精确范围；原反例变绿，新增实际请求驱动测试通过。能力范围为本块正文、邻接原文、作者事实与已取证早章事件，并不据全文字符覆盖率宣称穷尽全部远距离跨章矛盾。先文阅读入口也已完成生产 caller 与 Reader 二次校验复核。

阅读入口最后追加的实际问题已关闭：原 `NovelChapterReaderPage.reload` 先 `creation.open` 取 project，再 `workspaceStatus` 取 status；两次 await 之间同分支正文提交会用旧 project 验旧 digest/quote，保持 focused 且无过期提示。独立真实 ETS 反例先确认当前正文 `New author fact.`、页面 `Old fact.`、focus 为 true、notice 空。Owner 最小改成一次 `readWorkspaceSnapshot` 后，三项实际 Reader 回归及独立复验均通过，页面显示新稿、focus 退出且提示重新审校。没有新增重试或生命周期框架。

## E04 按生产能力核对的范围

当前 iOS NovelContinuityAuditReport 明确是 VM 临时诊断，退出项目重扫；repair report 也不落项目，repair executor 的 executeWithEvidence 输出没有单独形成持久 repair receipt。因此本轮没有从实际 caller 找到“审计冷重读/统一 prompt-version 浏览”的用户能力，不能据框架同名差异扩建存储。

现 Native 归档有 draft branch/sourceMessageIds/sourceDigest 并在 confirm 重核；材料提案有原来源+target digest；quick start 保留原 canonical JSON、稳定 source/index 和作者决定；审计 blocks 记录来源 digest/quote/range 并在修复前再核；repair proposal 持久保存 frozen CAS/sourceDigest/diff ranges 并复用版本历史。新 state operation 另存 protocol/task kind/source CAS、身份输入、targets/cursor、model target/reasoning；新 repair 输出严格核验 protocol/source/range。E04 可以据这些实际协议与凭据链判断各任务闭环，不能声称全 iOS 模板版本和历史 receipt 格式一致。C16 的 pair evidence 实际事实保护缺口现已独立验证关闭。

## 当前 iOS 生产能力与冻结后检查点

| 范围 | 已查当前 iOS caller 与实际行为 | 鸿蒙冻结后必须检查的闭环 |
| --- | --- | --- |
| C03 人物经历 | `NovelCompendiumView:86 → NovelCharacterPagesView`；人物详情 `relatedEvents:318` 只取当前分支 state.eventIDs，再用有效名字及 aliases 调 NovelCharacterEventMatcher。经历不是直接从所有历史事件搜字符串。 | 人物详情/经历入口读取当前分支已取证事件，使用有效资料标题与别名；事件有正文来源、chapter ID/digest/逐字证据，不能把确定性三行章节指针冒充结构事件。修改来源正文后旧事件不能继续表示当前事实，其他分支不能串入。 |
| C11 事件与逐章剧情 | `NovelCompendiumView:315` 接审计；`:320–341` 读取 branch.currentState summary/outline 和分支 events；NovelDomainModels 的 NovelStoryEventRecord 存 kind/summary/entityReferences，NovelChapterPlotModule 有 stale。 | 真正生成/保存事件的状态任务 → repository 持久化 → 重开/切分支 → 事件时间线和人物经历投影。空列表、失败和 stale 分别有真实语义；作者 authorPlot 仍保持原 owner，普通手改继续确定性提交。 |
| 身份澄清 | `NovelSessionView:2442–2488` 的 identity cards 调 associateCharacterAlias / ignoreCharacterIdentityMention / clarifyCharacterIdentityMention；`NovelSessionViewModel:1985 → NovelCreationViewModel:1822 → NovelBranchOperations:1013`，核对 idle、同步、active unresolved 名称、未回答，并追加新状态和 checkpoint。 | 状态任务产出的 unresolved 身份能在实际页面看到并选择既有人物/忽略/作者澄清的等效动作；提交核对当前 branch/source，保存后不再重复问，澄清进入后续状态/创作输入，不能只把 UI card 隐藏。人物别名复用已有资料路径；非人物实体不能无依据进入人物问答。 |
| C13 状态救援 | `NovelCreationViewModel.syncManualEdits:2938`、retryPending:3041 和 startManualStateSyncRetry:3059；automatic recovery 检查当前 branch pending，先确定性 relink 再走 pending/full rebuild；`NovelFactTransactionLifecycle:525–610` 每块输出重新验证来源并 commitChunk 落 durable progress，最终 finalize。 | 显式救援入口 → 真实 stateSync 模型请求 → 严格解析/取证 → CAS 提交与持久进度。失败能看到已完成/失败段并明确重试；重开继续同一任务已完成进度，不能把已完成段重新跑全本。来源正文/分支变化拒旧结果，取消不提交迟到结果，另一项目任务不受影响。仅清 plotStale/unresolved 不算模型救援完成。 |
| E03 专用推理设置 | NovelCreationSettingsView toggle → `NovelCreationModelPreferences:45` UserDefaults 默认 false；`NovelStructuredModelExecutor.prepare:185` 读取设置，task.parameters:900 仅 stateDelta/stateRebuild 消费 off/automatic，不改变创作、审稿。 | 实际小说设置保存/冷读默认关闭 → stateSync request → adapter → provider params 的完整链；开启/关闭各验证一次请求，普通生成/审稿参数保持自身设置。UI switch 和字段存在不能代替实际请求参数证明。 |
| E04 结构协议与凭据 | NovelPromptCatalog task kind/version；acceptedVersions:36 保留已发布历史版本，receipt 是不可变当时事实；NovelStructuredModelExecutor 执行生成 request evidence，NovelFactRequestReceipts 将 evidence 与 pending/attempt/input 绑定。 | 新状态/救援/修复请求具有固定 task kind、protocol version、来源输入凭据和模型职责；输出对应严格 schema；receipt/progress 重开可读，不使用“当前版本相等”误判旧记录损坏。范围限本轮实际新增任务，不要求复制 iOS 全部历史框架。 |
| C16 单条/全部修复 | `NovelContinuityAuditView:66/112` 先单条或全部确认 → ViewModel.startContinuityRepair:3210 / repairContinuity:3289 → DefaultNovelCreation.repairContinuity。Lifecycle 核对 project/branch/report staleness；NovelContinuityRepairPlanner 选择较后章节，较早正文保持；PatchApplier 只接受唯一 oldText、已允许 issueID、非重叠且真实改变的 patch。最后复用 saveManualEditWithPlot 版本提交。 | 审计报告单条/全部入口 → 带原始证据和 digest 的模型 patch → 现 CAS proposal → 作者确认 → 既有正文/剧情/版本事务。生成 proposal 阶段不得写正文；拒绝不写，接受后版本可撤回。较后正文改写对齐先文；只含单章证据或定位重复/重叠/不存在时不得自行选先文或整章重写。 |

## 当前鸿蒙基础（本阶段前，不表示缺口已实现）

- `NovelWorkspacePage.syncWorkspacePlot → creation.syncPlot → repository.syncPlot` 现为从正文重建确定性指针；`resolveWorkspaceUnresolved → creation.resolveUnresolved` 是作者确认后续影响。保留这两条语义，新增模型状态救援需独立明确入口及真实调用。
- `creation.runContinuityAudit → continuity_audit.runContinuityAudit` 已做全文分块、chapterId/sourceDigest/逐字 quote 校验，报告含 coverage 与 invalidEvidenceCount。新增修复应消费已核验 issue，不能把 raw 模型未取证候选当审计证据。
- `plot_projection` 当前三行短指针和 stale 仍可复用；事件/状态 receipt 有自己的来源语义，不把这些指针重复命名成历史事实。

## 冻结后独立反例清单

| 检查 | 必须证明 | 状态 |
| --- | --- | --- |
| 状态真实主路径 | 通过 creation 的公开入口用真实 file repository 执行状态请求；provider port 捕获 task/model/版本/推理，输出已存在正文的 evidence；关闭重开后状态、事件与人物经历一致 | passed，真实 service 测试 |
| 无证据/旧证据 | 不存在 quote、错误 chapterId/digest、来源正文运行中改动、分支运行中切换均拒绝落当前事实；作者正文及 authorPlot 不变 | passed |
| 救援持久重试 | 至少两段，第二段失败，重建 creation/repository 后显式重试；第一段不再发模型请求，提交保留已成功进度且最终数据可重读 | passed，cold retry |
| 取消与忙碌 | 已真实开始的状态/修复请求停止后释放 runner；晚 snapshot/completed 不落盘，当前 ordinary/ghostwrite/polish 活跃门禁实际拒绝；只影响目标项目 | passed，第二条 repair 取消无半提案 |
| 身份问题收口 | 选择既有人物、忽略、作者澄清有各自实际写入；重开不再次显示已答身份，后续输入包含保留澄清；跨 branch/陈旧来源拒绝 | passed，三种操作与 cold read |
| 连续性审批 | 有核验证据的单条/全部请求能生成现 CAS proposal；未审批/拒绝不改正文，审批写版本与真实剧情事务；陈旧 CAS、重复/重叠 oldText、未授权 issueID 拒绝 | passed，既有严格范围协议不采用 issueID 字段 |
| 修复目标边界 | 早章事实保持，较后章节可修改；多 issue 同章定位一致；部分失败/无变化结果不报全成功，后续可重新审计 | passed，远章实际输入也已通过 |
| 推理与版本 | 设置默认 off、persist/reload、实际 adapter 请求 off/automatic；创作/审稿没有被状态设置误改。历史 receipt 不因当前 task version 前移而变损坏 | passed，参数范围见上方；历史范围按 E04 实际 caller 判断 |

## UI 入口交接（仅调用 checklist，视觉由另 reviewer 验收）

- 剧情页可达结构状态/事件/救援入口；实际成功、失败、进度和重试能返回到持久记录。
- 人物详情可达当前分支经历，身份问题有明确作者选择及保存错误反馈。
- 审计已取证单条及全部修复入口先确认生成 proposal；批准/拒绝入口对应现事务决定；修复后结果与过期报告提示一致。
- 小说设置的状态推理开关可达且读写实际持久值。

证据边界：已只读核查当前 iOS、鸿蒙生产 caller/backlog，并独立运行上述源码级、真实 repository/service 和捕获实际请求的 provider 测试。本 reviewer 没有执行设备或云模型测试；这些通过结果不证明真实模型输出质量、后台系统存活或实屏对齐。实屏验收归 root 与另 UI reviewer。
