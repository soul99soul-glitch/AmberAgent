# E7 独立逻辑与调用链审查

2026-09-28。**A 最终源码通过：lifecycle P1 和提交时钟 consumer P2 均已回喂并精准源关闭；无新增明确 P1/P2。B 仅引用 owner 已有受控证据；平台最终编译由 root 收口。C 设备验收待验证，不据源码宣称整阶段完成。**

本 reviewer 已读 harmony-arkts skill、E7 contract、E7-memory-topic-design 和实际 domain/platform/lifecycle 源。只读产品；仅写本报告，没有修改源码/测试，没有运行 tests/lint/build/sign 或设备操作，没有追加防御分支或测试矩阵。行号按本次收口源，函数名保留定位意义。

## 具体问题与修复复核

**P1：一次后台后主题聚合永久被前台门拒绝。** 修前 `EntryAbility` 仅 onCreate 调 coordinator.foreground，onBackground 调 background；实际 onForeground 只清 AppStorage.appBackgrounded。`MemoryTopicOps.ets:165` 的 background 把私有 inForeground=false，`:72–74` 的 checkActive 因此在实际 Home→返回后继续拒绝手动聚合，自动聚合也没有再次触发。触发不需要损坏数据或异常 provider。

已即时回喂 root。最终 `EntryAbility.ets:361–366` 在实际 onForeground 清 appBackgrounded 后调 `getMemoryTopicCoordinator().foreground()`；`:369–372` 保持后台关闭写门/取消；onCreate `:186–194` 已移除误触发，并先登记真实 abilityContext。`MemoryTopicOps.ets:161–165` 恢复前台位并按同 coordinator 触发一次自动 run。**源关闭，未构建/操作这一修前版本，也未把只读核对当后台实测。**

## Domain 真实链路

- **旧 JSON 与 source 不变：** `memory_models.ts:73–113` 加 topic kind/topicTitle/memberIds，并在 maker 默认 null/[]；`MemoryStore.ets:95–102` 的普通/guarded 读都 normalize 经 maker。topic grouping/prune 只替换 topic 对象，源记录的 content、identity、scope 和 provenance 保留；新 topic 来源会话保持 null，成员使用真实 ID。
- **有效 baseline/live：** `memory_topics.ts:23–25/41–58` 输入为已保存、active、非 core/topic、未过期源。`:61–96` 解真实 JSON，验证短非空 Unicode title、summary、正整数 ID、至少两条不同成员、normalized 同标题与跨组成员。`:125–202` 在 latest live 上比较 baseline 的 content/updatedAt/kind/scope/archive/expiry；删除或变更命中 suggestion 时报告 stale，不以模型等待前快照覆盖源。
- **幂等与成员保护：** title 大小写/空白归一匹配保同 ID；same summary/members 不改 updatedAt。历史成员只 union/distinct 仍有效且 baseline 未变的源；另一 active topic 已拥有的成员拒绝，不默默登记跨主题归属。聚合不调用源 merge/archive/delete。
- **摘要失效策略明确：** 源 content/kind/scope/archive/expiry 变更或删除时归档依赖 topic，保留 topic ID/title供后续同标题真实重生。任何成员减少都使原摘要失效，即使仍有两人；unavailable 成员移除，旧 topic 文档退出，源回到未归组索引。pin、lastUsedAt 不改事实摘要；模型晚提交仍严格拒 updatedAt 不同。对应 `memory_topics.ts:101–122`、`memory_write.ts:104–129/151–198`，与 domain result 记录一致。
- **ordinary topic 边界：** `memory_write` append/content/update/upsert/delete 拒创建或覆盖 topic，upsert也拒 source-shaped 覆盖已有 topic ID；Store updatePinned 同样拒 topic并保完整新字段。普通工具桶/summary 排除 topic；extractor 明确拒模型 kind=topic。frontmatter 编解码拒 topic，需真正首部 frontmatter；import 只接受原 .mem.md 格式、去重源；export 只导源并按实际源计数。Dream local/model/planner/applier 的 managed/merge/promote/archive/supersede/ignore均排 topic，原破坏性 DreamPlan 审批路径不与主题自动写入混合。
- **真实 projection：** `memory_documents.ts:37–66` 仅 index.md 与受控 numeric ID/slug topics路径，包含完整成员正文、实际分类/日期/会话/消息来源、实际 live覆盖与未归组记录；无 eligible 源返回空。没有用户任意磁盘路径、另一记忆真相或 embedding 系统。

## Platform、模型、取消与文件链路

- **同队列 guarded 提交：** `MemoryStore.ets:141–147/507–523` 共用已有 writeQueue。snapshotForTopics 使用真实 guarded read并返回当下prune的主题视图；最终 applyTopics 在队列获得执行权后重读 live，await beforeCommit，再按不早于入队时间的实际提交时钟判断 domain eligibility，然后保存。callback注入 `checkWriteGate`，在该队列内重查 feature/write/foreground/abort；没有让排队旧结果绕过关闭门。unchanged projection 同传该提交时钟，最后窄复查发现的遗漏已源关闭。
- **取消/timeout/迟到结果：** `MemoryTopicOps.ets:69–82/124–135/141–165` 用实际 EntryAbortController、provider CallOpts.signal 和有限 model timer；awaitAbortable 让 coordinator停止等待，后续写门再核 signal。超时或后台关闭后，旧 provider 即使迟到也不进入 decode/apply；不据此宣称硬终止所有传输。单 current.running admission，不与 Chat run/controller/chatGenerating绑定。取消只关闭本次主题请求，不改源。
- **新模型链真实：** 每次 run 重读 AgentRuntime；`MemoryDreamOps.ets:45–48` 每次重读 task IDs/current chat UUID，复用 daydreamModelCandidates→resolveTaskModelChain→实际 Provider/model/auth。最终 `MemoryTopicOps.ets:118–135` 真调用 buildProviderApi.generateText，透传选择模型的 customHeaders/customBodies、daydream reasoningLevel 和 signal，不写假结果或另加模型槽；模型缺失返回 missing_model。extraction enabled/recall enableLongTermMemory 不被误用为主题写许可。
- **显式 opt-in：** `AgentRuntimePrefs.ets:194–199/223–249` topicWriteEnabled 默认为 false，dreamModelEnabled 默认为 false、timeout默认120000；旧配置兼容。每次提交前双门重读，topic手动也保 feature/write/cancel门。现有 DreamPlan approval不改变。
- **源保存与派生失败：** `MemoryStore.ets:123–138` 先 await主 JSON原子保存、发布revision，再syncDocuments；失败抛带 sourceSaved/documentError/topicResult 的 `MemoryDocumentRefreshError`，不回滚已存源。unchanged分支只刷新projection、sourceSaved=false。coordinator `:149–151` 保留真实错误及计数，显式syncDocuments支持再试。普通源 CRUD/upsert/prune 后也走同保存链。
- **不毁文档：** list/read/sync先 guarded源读；blocked/io_error拒更新而不拿降空只读结果清文件。`MemoryDocumentStore.ets:31–43` 先逐个实际内容比较，仅变更才原子写；成功投影后才删除不存在的受控旧文件。unchanged不刷新mtime；真实空库可删旧派生文件。read路径只接受 index或受控topics路径。
- **Chat touch 边界：** `MemoryStore.ets:157–162` touch仍同既有源写队列，仅更新 lastUsedAt/writeAll，不调用docsync、不新增文档失败到Chat recall链。它仍可能等待该队列先前真正源/文档工作，本文不把它宣称成不等待任何IO；没有为这种已有串行语义新增另一源仓。
- **自动入口真实且有限：** 真实 onForeground→coordinator.foreground→run(false)；默认runOnlyOnIdle查 aggregate chatGenerating，runOnlyOnCharging查 batteryInfo实际ENABLE/FULL。来源不足/模型缺失在预算前退出；`reserveDailyBudget:90–105` 在共享KV锁内按当天计数、真实put后才请求model。手动跳环境/预算，仍保双门和前台/取消。没有 WorkManager/持续后台/充电变化自动唤醒的额外声明。

## B/C 证据范围

`results/E7-domain-result.md` 已记录唯一纯domain必要group 1/1 PASS，以及真实派生Markdown含嵌入frontmatter误导入的具体红→单点修复→绿，strict types 0和窄lint回执；本文只读核查相关真实源码与既有报告，没有重跑。该证据不代替真实 fileIo、provider、前后台、平台store或UI。

`results/E7-store-runtime-result.md` 最终平台报告已到：唯一直接加载实际 Entry store/coordinator/prefs/daydream/abort/atomic 与真实 domain、Node薄转发fileIO/KV的受控group **1 PASS/0 FAIL**，`/tmp/e7-platform-focused.log`。其范围包括水合、稳定ID/mtime、source成功后document失败、guarded读失败保文件、touch不接doc失败、实际模型headers/body/reasoning/signal入参、idle/charging/日预算、真实入队后取消、timeout迟到不写、background→foreground门；AppStorage/charging/DI/provider返回受控，真实网络和SDK不是其验收。五owned窄lint与Store最终lint No defects由owner执行，本文未重跑。

## 最终窄复查覆盖时点

已复查 actual compiler 回喂后的六 Store operation function-property signatures、typed ops factory、AbortSignalLike可选listener调用；没有改变实际 queue/port/admission语义。已复查新topic模型 customHeaders/customBodies透传，与当前选择模型一致。

**P2：guard后的当前eligibility已正确，unchanged document consumer漏用提交时钟。** 修前 apply 用 `Math.max(now,Date.now())`，但 changed=false分支仍将入队前now传syncProjection。明确触发为无已有topic、两条short_term源的expiresAt位于入队now与实际queue/gate结束之间：domain正确拒为stale/changed=false，随后index却用old now显示两条有效源。已即时回喂root与platform owner。最终 `MemoryStore.ets:518–521` 在guard后取得具名commitNow，apply与unchanged syncProjection同传；该具体路径不再留下已到期源文档。**源关闭。** Root仅授权在既有唯一group末尾加入这个受影响小case，owner回执 `/tmp/e7-commit-clock-red.log` **0/1**→clock green **1/1**；直接读raw文件确认index不存在，没有通过list刷新掩盖，本文未执行。

另一个reviewer的snapshot-expiry问题也在最终affected源只读复核：`MemoryStore.ets:509–512` 在guarded queue读取后只调用pure prune当前availability，保留全部源数组，返回失效主题已归档的视图；没有source/docs写入。对应owner回执 `/tmp/e7-snapshot-expiry-red.log` **0/1**→`/tmp/e7-platform-final-green.log` **1/1**，组内234ms/总447ms，rawJSON快照读取前后逐字不变。它沿同一个必要group，没有新增suite/矩阵。本文只读source及owner回执，不把它记作本reviewer新发现或执行。

首轮平台group是在factory/optionallistener/method签名与提交时钟窄修前执行，最新上述受影响group已覆盖最终source；这些变化在本文只读覆盖。root此前compile/sign成功的artifact早于最后clock/snapshot修复，最终冻结后的actual compile/sign仍由root独立收口。新增UI由独立reviewer审，**C设备当前无证据，保持pending**。本报告不扩到运行/视觉层，不要求重复既有绿色suite或矩阵。
