# P4 结构状态、身份澄清和连续性修复实现

实现源码已冻结，交由独立 state reviewer 检查；本报告记录领域、实际 adapter payload 和静态验证，设备视觉与统一构建由主 agent 单独验收。

## 实际调用与持久数据

| 能力 | 实际调用链 | 持久结果和完成条件 |
| --- | --- | --- |
| 结构事件与人物经历 | `NovelStatePage → creation.readStructuredState/startStateRebuild/syncStructuredChapter → NovelStateService → NovelModelRunning → NovelInteractiveAdapter → provider` | `structuredState` 保存 protocol、事件、章节 ID、完整正文 digest、逐字 quote、entityRefs、身份澄清、逐章来源和 stale 标记。人物经历仅投影有效事件引用；标题/别名仅作为模型身份输入，不把字符串命中当作事件事实。读取 DTO 的 project、CAS 和 operation 来自同一 workspace snapshot。 |
| 显式状态救援和重试 | `creation.startStateRebuild/retryStateRebuild/cancelStateRebuild → state_rebuild.ts → repository.commitProject/completeStateOperation` | `stateOperation` 保存任务、冻结分支/CAS、所有来源正文 digest、身份输入、选中章节、预算分块范围、cursor、draft、模型职责、推理开关和错误。每块成功持久递进；坏 JSON 保留已完成块，冷读后的显式重试跳过它们；全部成功才把 draft 升为正式事实。 |
| 恢复普通创作 | `repository.completeStateOperation` 在原有项目锁与 CAS 内完成 | 仅完整 `stateRebuild` 成功同时重建既有确定性正文剧情指针、清理 needsSync/plotStale；单章 delta、失败或取消均不清理原写作门禁。手动编辑仍只做确定性提交，不调用模型。 |
| 作者身份澄清 | `creation.resolveIdentityClarification → commitMutation(identity_clarification)` | create 真正新增当前分支人物；merge 真正追加所选人物别名；ignore 保存作者明确忽略。候选与冻结 CAS 同事务核对，并用既有 branch material projection 保存；三种回答冷读后不重复问，且进入后续状态与创作上下文。 |
| 单条和全部连续性修复 | `Workspace → repairContinuityIssue/repairContinuityIssues → prepare/strict parse → mergeContinuityRepairs → createWorkspaceProposal → Operations approve/reject → repository.resolveProposal` | 每条重新核验审计原正文 digest、UTF16 范围和逐字 quote；批量在发模型前拒绝同章重叠，全部结果按原范围倒序合并，每章一个正文 patch，最终只有同一 frozen CAS 下一个待审批 proposal。未批准/拒绝不改正文；批准通过既有版本、剧情和 workspace 原子事务，每个改变的原章保存一个旧版本。 |
| 取消修复 | `cancelContinuityRepair → exact collector.cancel → model.cancel(runId)` | 释放当前订阅、计时器并等待该调用完成；取消第二请求时，第一请求已生成的文本仍不发布部分 proposal。页隐藏/退出已由 UI worker 接到真实取消 API。 |
| 专用推理开关 | `setStateReasoningEnabled → project setting → frozen taskOptions → adapter.makeProviderForOutputTokens → makeNovelTextGenerationParams` | 默认 false、实际持久化，仅 stateDelta/stateRebuild 使用 off/auto。state 请求剥离 model customBody 的推理覆盖字段，保留其他参数；普通写作/审稿仍使用其原配置。实际 DeepSeek、Claude、Gemini Flash payload 已测。共享 provider 对不支持关闭的模型保留原能力限制；官方 OpenAI 支持 none 的型号由主 agent 的独立 state-only helper 核实、接线和验收，不能概括为所有模型都彻底关闭推理。 |

`repository` 的实际存储路径已逐项修改：迁移 decode、branch snapshot 的显式 project records、checkout/head 安装和 native backup 都保留 `structuredState/stateOperation/stateSyncReasoningEnabled`。分支互相隔离；公共 workspace 的 `state/evidence.json` 严格解析与 reasoning 开关由合同 worker 接线，运行中任务仍属于 native runtime 状态。

`buildNovelContext` 及连续性审校作者事实区只注入经正文来源校验后的结构事件和明确作者身份确认。修改早章会使该章及后章事件 stale；旧证据保留来源记录供历史阅读，但不继续成为当前创作事实。

## 实际 iOS 依据

- `NovelCreation/NovelStructuredOutput.swift` 的 state delta/rebuild 严格协议；`NovelStructuredModelExecutor` 的任务职责和 state-only reasoning。
- `NovelFactTransactionLifecycle.swift` 的逐块来源验证与持久进度；Harmony 保留既有 workspace 锁和事务，没有另建通用状态机。
- `NovelBranchOperations.swift` 的作者身份确认；`NovelCharacterPagesView` 的当前分支人物经历；本实现按事件 entityRefs 投影，并把 effective 人物 aliases 传入实际抽取请求。
- `NovelContinuityRepairPlanner/NovelContinuityPatchApplier` 的来源核对、范围补丁和作者版本提交；Harmony 使用现有 durable proposal 审批与 CAS。

## 验证

- `novel_state_rebuild.test.ts` **12/12**：实际 file repository + creation caller。覆盖坏第二块后的 cold retry、真实模型输入预算分块、手改不调用模型、完整救援后实际 ordinary writing、失败部分不清门禁、正文/分支漂移拒旧结果、cancel/unsubscribe、三种身份回答的实际材料与后续请求输入、修复 approve/reject/旧版本、批量原范围与一个 proposal、第二请求取消后零 proposal。
- `novel_structured_state.test.ts` **15/15**：严格 JSON、错误协议/来源/引文/实体拒绝，chunk idempotency、冷读历史证据、stale 投影和作者回答持续收口。
- `novel_continuity_repair.test.ts` **20/20**：严格 repair JSON、授权来源/范围、倒序合并与重叠拒绝、collector 精确取消、同步终态及晚事件释放。以上合计 **47/47**，日志 `/tmp/amber-p4-state-focused-green.log`。
- Chat 实际 adapter 与请求配置 **32/32**，日志 `/tmp/amber-p4-state-chat-green.log`；其中 task-only raw provider payload test 确实复现 customBody 将 off 覆盖为 enabled，修复后 default off / explicit on / ordinary preservation 全部通过。
- 全部 novel 领域测试 **470/470**，日志 `/tmp/amber-p4-state-all-novel-green.log`。
- DeepRead、Chat `tsc --noEmit` 通过，日志 `/tmp/amber-p4-state-tsc-domain.log`、`/tmp/amber-p4-state-tsc-chat.log`。
- 12 个生产变更文件 ArkTS lint **0 error**；现有纯 TS 模块的工具类型解析产生 warn。新增 repair collector 再 lint **0 error / 72 warn**。最后 batch 接线的 scoped lint 单列 `/tmp/amber-p4-state-final-lint.log`。

关键 red → green 日志：`/tmp/amber-p4-state-provider-red.log` → `...provider-green.log`；`/tmp/amber-p4-state-pending-red.log` → focused green；`/tmp/amber-p4-repair-batch-red.log` → focused green。反例修复均落在实际入口与存储事务，而非单纯隐藏 UI 错误。

尚待主 agent 的统一构建、设备真实 provider fixture 与视觉 review；以上测试不能代替真实模型抽取质量、设备后台存活或 UI 布局验收。

## C16 独立 review 后的成对证据修正

独立 reviewer 指出原接口只有目标单章引文，虽然能生成受 CAS 保护的正文提案，但没有把较早章事实交给修复模型，也没有禁止一条跨章矛盾改写先文。这是实际语义缺口，已补源码与真实调用测试。

当前 iOS 依据是 `NovelContinuityRepair.swift:59` 的 max-ordinal target 与 `:70` 的 earlier canonical references，`:157–185` 把早章原文作 Canonical Chapter 输入；`NovelContinuityAudit.swift:208–276` 则先把 references 映射实际章节并核验原文。Harmony 在现 issue 上新增可选 `canonicalReferences`，保留用户要求的合法单章措辞修复路径，不另造图或 runner。

- 实际审校 JSON 带 `{chapterId,sourceDigest,quote,start,end}` 早章引用。输入为已有邻接前文补充真实章 ID、完整正文 digest 和 UTF16 sourceRange；完成状态救援后，作者事实区也含已验证的早章事件引文和来源。
- `runContinuityAudit` 逐项核验早章存在、未废弃、完整正文 digest 和逐字范围。引用必须属于真实章节顺序中更早的一章；标题和章号重新从正文派生，模型伪造的 ordinal/title 不能改变修复方向。坏引用整条 issue 丢弃并增加 invalidEvidenceCount，不降格成无前文的普通问题。
- `prepareContinuityRepair` 使用同一个冻结 workspace 的章节清单再次核验引用，把真实 canonicalReferences 送入修复请求，提示明确先文不可修改。成对引用缺失来源、空数组、同章／后章引用和旧正文都在模型前拒绝。响应仍只能修改目标章原范围；最终同一 CAS 审批保证任何来源后续变化都不能覆盖作者新稿。
- 新真实链测试覆盖审校输入 → 输出保留两个来源 → 核验 → 修复请求 canonical 原文 → pending proposal → 审批，仅较晚章改变且仅晚章新增旧版本；倒置 pair 和早章正文已变均拒绝调用模型。

最新验证：`novel_audit_pair_evidence.test.ts` **4/4**；repair 协议 **25/25**（新增 5 项有真实 red → green 日志）；全 novel **479/479**，DeepRead 类型检查通过。audit + creation 最后 scoped ArkTS lint **0 error**；repair 模块最后 lint **0 error / 77 warn**。日志 `/tmp/amber-p4-audit-pair-green.log`、`/tmp/amber-p4-canonical-all-novel.log`、`/tmp/amber-p4-canonical-tsc-domain.log`、`/tmp/amber-p4-canonical-audit-lint.log`、`/tmp/amber-p4-continuity-repair-canonical-{red,green,tsc,lint}.log`。

输入边界仍按实际记录：尚无结构状态时，分块审校的原文邻接为上一章尾部 1000 字；不能把有全章来源 digest 说成模型看过全本。设备 fixture 对更远先文的验证需使用实际发送的已取证状态事实，不能依靠 fixture 预知章节原文冒充模型输入。

### 远章结构事件的精确范围补齐

独立 reviewer 的实际三章反例发现：第 1 章事件引文在 UTF16 偏移 17，第 3 章请求原本只有该事件 quote/id/digest，没有前缀或范围，模型无法可靠生成 canonicalReferences 的强制 start/end。这一缺口现仅在审校输入构建中修复：先 prune 当前结构状态，再选择真实章节顺序中早于目标章的事件，用当前正文 `indexOf(event.quote)` 派生 start/end 和 sourceRange，随章 ID、正文 digest、引文及真实标题/章号放进“已取证早章事件来源” JSON。没有改事件存储，也没有改通用创作上下文。

新增实际模型 port 测试从生产第 3 章请求解析该 JSON 后回传 pair，范围 17 不来自测试 oracle。reviewer 原脚本重跑 `remoteRangePresent:true`，独立组合 **72/72**，领域 C16 已关闭。实际审校来源边界为邻接尾文 + 有效已取证早章事件，不能泛称穷尽全部远距矛盾。

最后验证：pair actual tests **5/5**；全 novel **480/480**；DeepRead/Chat 类型检查均通过；新增 audit scoped ArkTS lint **0 error / 202 warn**；限定文件 diff check 通过。日志 `/tmp/amber-p4-audit-remote-range-green.log`、`/tmp/amber-p4-remote-range-all-novel.log`、`/tmp/amber-p4-remote-range-tsc-{domain,chat}.log`、`/tmp/amber-p4-remote-range-lint.log`。source 再次冻结，构建与设备阶段由主 agent 继续。
