# P4 独立 review：章节合同、未来方向、作者返修要求与重生成语义

状态：**本合同／arc／作者 brief／重生成范围独立 review 通过，无未关闭发现；签名设备“一键合同建议 → 草稿 → 作者确认”及 arc 编辑保存复验已独立核实并关闭。** 这是 P4 的独立领域审核，不代替 P4 全域检查或总 UI gate。本审核只写报告；实现修复由对应 owner 完成，未重复审核 tools 目录／剧情状态／UI 专属范围。已遵循本会话加载的 harmony-arkts skill。

## 当前 iOS 生产行为基线

核查当前本地 `amberagent-ios/iosApp/iosApp/NovelCreation` 源码及本轮 backlog D02–D05、D07、D10：

- `NovelDomainModels.swift` 的合同有 draft/confirmed 状态、outlinePlacement、goalAndConflict、mustHappen、mustNotHappen、endingHook、visibleFacts 和内容 digest；不是把 Markdown 文本存在即可称为结构合同。
- `NovelChapterPlanProposalLifecycle.swift` 的首次 `proposeNextChapterPlanDraft` 保存草稿，须作者确认；批内自动拟下一章使用明确的 `proposeAndConfirmNextChapterPlan`。二者不能混淆。
- `NovelSessionViewModel.swift` 的计划提案审批绑定 project、branch、head/working revision 与原 plan digest；审批保存 confirmed plan 后才启动代笔。生产代笔收录调用 `adjudicateAndCollectGhostwriteChapter`，其合同与候选绑定校验是真实链路；同名旧 `acceptChapterPlan` API 的存在不代表需要复制另一套候选审查任务机。
- `NovelUpcomingArcRecord` 最多 8 条，每条最多 160 字符，去空白并去重；它是未来软方向，不替代本章绑定合同。
- 作者介入返修的当前 caller 是 `startGhostwriteRevision(brief:)`：只有合适中断状态接受非空 brief，保留旧源稿，brief 进入实际 `writeUserText(receipt:revisionBrief:sourceDraft:)` 参数，并只消费一次。
- `NovelPromptCatalog.swift` 的整章润色严格保留事实；整章重生成明确允许修改事件、时间线、关系、动机、秘密和结果，用于修复矛盾，并保持章节在整体结构中的作用。两者均先产生候选，不能在生成完成时悄悄覆盖已保存正文。

## 冻结后的实际调用链

| Backlog | 必查链路与反例 | 状态 |
|---|---|---|
| D02 | draft → 作者明确确认 → confirmed；草稿不作为绑定合同发送，也不能作为代笔已确认计划 | 通过：纯领域及真实普通生成请求均验证草稿不注入；实际 startGhostwrite 拒绝草稿 |
| D03 | 实际模型产出结构提案 → 作者审批 → confirmed 持久化 → 普通整章 provider 接收到完整合同字段 | 通过：修复后真实 adapter → OpenAIChatApi → HttpClient POST 的 system 含唯一六字段策划指令，不混普通聊天、无 tools；只返回 draft + 源 CAS，不落合同；签名设备实际显示建议草稿并经作者确认，contract 类别真实 HTTP 收据与 UI 树一致；普通整章模型运行请求含已确认六字段 |
| D04 | 代笔 frozen plan 同当前 confirmed 合同内容／digest／CAS；确认后改合同、切分支或改正文须拒绝过期预览／候选 | 通过：真实 writer／joint reviewer 使用同一冻结全文和绑定；commit 校验候选、review、receipt、CAS、当前计划后原子收录；新建／历史分支 fork 重绑定合同，不修改源分支 |
| D05 | arc 8 条边界与 9 条反例；空行、重复、长项处理明确；只作为未来方向消费，不自动提升为 mustHappen | 通过：trim、去重、每条 160、最多 8；普通生成的 future_plan 明确不替代本章计划；代写首章／下一章 FrozenPlan 复制 arc 并纳入 digest，真实 writer／reviewer／planner 均消费 |
| D07 | 暂停／失败任务的非空作者 brief → 真实 rewrite 参数；旧候选和合同绑定保留，不另建任务机；运行中拒绝修改 | 通过：既有 job 保存 brief 并恢复 writing；请求含 brief 和上一稿（按 iOS 既有 12,000 字上限）；新稿 checkpoint 消费 brief，下一章不泄漏；旧 provider 先停止；同 CAS 下候选 digest 改变也拒绝旧 sheet |
| D10 | 实际 regenerate 请求明确可改事实；实际 polish 请求明确保事实；候选来源绑定和明确收录仍有效 | 通过：两类实际请求语义不同，均带源全文；生成只写消息候选，已保存原章保持不变；regenerate 预检和启动前两次检查源 CAS |
| 交换 | 原生备份往返保留 draft/confirmed、全部字段、digest、arc 与 brief 任务状态；公开工作区按实际 wire 格式保存结构字段及状态，不仅保留摘要 | 通过：实际 export → parse → install → reopen 深等；native 未消费 brief、冻结计划、来源候选深等；合同 public 使用六个实际 iOS 标题和 status，内部 digest 按规范化正文重算 |
| 交换反例 | 旧自由 Markdown 不被静默转成 confirmed；legacy 文本编辑不残留结构合同；复制／分支不破坏引用 | 通过：无 status 的结构 Markdown 默认 draft；旧文本修改清对应 contract／arc；native copy 保留既有分支身份，fork 则重绑定新 branchId；人工保存合同清除旧 prepared batch count |

关键生产链路：`NovelChapterContractPage` → `creation.proposeChapterContract/setChapterContract` → `chapter_contract.confirmedChapterPlanText` → 普通 `runGeneration` 的 system/context，或 `repository.startGhostwriteJob` 的 frozenPlan → writer → joint reviewer → `commitGhostwriteChapter` 原子收录并消费合同。作者 brief 的实际 caller `NovelWorkspacePage.submitGhostwriteBrief` 同时传入 sheet 源 CAS 和 candidate digest；`NovelChapterReaderPage.submitRevision` 将 chapterId、brief、源 CAS 传给 revise/regenerate。

`repository.commitGhostwriteChapter` 继续沿已有事务和收录链路工作；下一章计划沿已有批内自动计划行为推进，不增加另一套候选审查状态机。公开格式没有声称迁移原生任务，brief 的任务持久性由 native backup 验证。

## 独立发现与修复复查

发现并关闭一项公开交换差异：领域／模型 JSON 接受 `mustHappen: ["发现来信\\n读出邮戳"]`，原实现保存一个含换行的条目，公开 Markdown 导入后变为两个条目。语义和 digest 相同，但结构深等不成立。独立脚本 `/tmp/amber-p4-contract-review-wire.ts` 在实际 export → parse → install 上复现，red 证据 `/tmp/amber-p4-contract-review-wire.log`。

owner 精准修改 `chapter_contract.ts:31` 的既有 `normalizedLines`，将 LF／CRLF 按行归一化，与现有 UI 和 public parser 对齐，并在 native/public 实际往返测试覆盖多行及 CRLF。复查 `/tmp/amber-p4-contract-review-wire-green.log`：结构、status、id、branchId、digest、时间戳全部深等。没有新增格式载体或恢复状态机。

**arc 检查重新打开并关闭：** 初次报告只证明了普通生成的 future_plan 消费，没有验证代写实际请求；其后 owner 发现 ghostwriter 仅接收 FrozenPlan.content，遗漏后续 arc。因此初次 D05 的“通过”证据不足，已按这条真实断链重新检查。修复沿既有 FrozenPlan 增加可选 upcomingArc，首章和下一章冻结时复制数组，摘要包含 arc；旧任务缺字段时维持原摘要。writer、联合 reviewer、下一章 planner 的实际请求都显式消费冻结 arc，没有新增任务机。

独立 `/tmp/amber-p4-contract-review-arc.ts` 验证两章 5 个真实 model-running 请求及各阶段持久化 FrozenPlan：首章 writer／reviewer／planner 和下一章 writer／reviewer 均收到相同 arc；第二章冻结字段精确一致。另验证作者修改 arc 并启动新 job 后，旧 job 持久化 frozenPlan 保持深等；冻结数组独立于输入数组，改变 arc 会改变 plan digest。三项均通过，日志 `/tmp/amber-p4-contract-review-arc.log`。native 失败任务及未消费 brief 重开仍保留冻结 arc 的回归亦通过。

owner 后续自行发现并修复导入项目清除合同／arc 后，公开导出复用旧 frontmatter 可能复活 confirmed 合同的问题；新增实际双次公开往返测试在本次独立聚焦重跑通过。该修复仅消费已识别计划 status／元数据，未扩大未知字段清理范围。

**实际发送断链重新打开：** root 在签名设备“一键合同建议”验收发现返回普通正文并报 JSON 错误，截图 `device/p4/p4-contract-model-draft.jpeg`。先前本 reviewer 验证到 NovelModelRequest，并未验证 adapter 的最终发送策略；初次 D03 通过不能覆盖这一产品链路，结论已更正。`novel_interactive_adapter` 在 request.context 存在时将 assistant.systemPrompt 置空，由 context sections 构造实际系统消息；旧 caller 删除 discussion instruction 后，没有把 CHAPTER_CONTRACT_PROPOSAL_SYSTEM 放回 context，因此 request.systemPrompt 虽然有值，raw HTTP 实际没有策划／JSON 要求。

owner 的窄修只在 `creation.proposeChapterContract` 的 context 首项加入 `required: true` 的策划 instruction，同时滤掉普通 discussion instruction；全局 adapter 策略未改。独立 `/tmp/amber-p4-contract-review-raw-http.ts` 使用真实 adapter → OpenAIChatApi → HttpClient 序列化，通过仅在测试 wrapper 删除 context instruction 重放旧 caller：raw POST 缺策划指令、返回普通正文导致 invalid JSON，CAS 不变；修复后的 caller 则 raw POST 有唯一策划指令和全部六个 JSON 字段、作者要求，且没有普通聊天系统提示或 tools，返回六字段 draft，workspace CAS 和已保存合同均未修改。两项通过，日志 `/tmp/amber-p4-contract-review-raw-http.log`。

**签名设备成功复验已独立核实，实际发送 finding 关闭：** 查看 [模型建议成功截图](device/p4/p4-final-contract-model-success.jpeg) 及对应 `.json` UI 树，实际出现“建议已填入草稿。保存草稿或确认计划后才写入工作区。”，并填入收束、POV 可见字段；[作者确认截图](device/p4/p4-final-contract-confirmed.jpeg) 和 UI 树出现“本章计划已确认，写整章时会使用此计划”，清除入口出现。成功填表路径会先解析并验证六个完整字段，结合已验证的真实 adapter 回归，证明不再以普通正文伪装结构建议。

独立核查 [请求收据](device/p4/request-receipts.json) 的 `category: contract`、`localLogLine: 18`，再读取本地实际 HTTP 日志 `/tmp/amber-standalone-provider-fixture.jsonl` 的对应第 18 条：`/v1/chat/completions` 的原始 system 以策划指令和六字段 JSON schema 开始，带当前资料／章节，user 为规划请求，`max_tokens: 4096`，没有 tools。该收据与成功设备 UI 行为相互印证，不只依赖 request mock。

另查看 [arc 键盘截图](device/p4/p4-final-arc-keyboard.jpeg)／UI 树和 [arc 保存截图](device/p4/p4-final-arc-saved.jpeg)／UI 树：键盘展开时两条输入可见、表单随键盘上移；收起后显示“后续走向已保存”“已填写 2 / 8 条”，两条正文和保存／清除入口完整，未发现本范围的重叠或错位。总 UI gate 与后续候选文案截图仍归 entry reviewer，未据这四张图替代全页审核。

## 验证与证据边界

- arc 修复及公开清除回归冻结后，独立重新运行 `novel_chapter_contract.test.ts` 与 `novel_ghostwrite_author_brief.test.ts`：**15/15 通过**（合同 10、brief 5），最终日志 `/tmp/amber-p4-contract-review-focused-arc-final.log`。包含真实服务／repository／model-running port 流程，而非只测同名 helper。
- 实际发送修复后再次独立重跑上述领域测试 **15/15**，日志 `/tmp/amber-p4-contract-review-domain-raw-final.log`；新 `novel_contract_proposal_adapter.test.ts` 连同既有 `novel_interactive_adapter.test.ts` **27/27**，日志 `/tmp/amber-p4-contract-review-raw-adapters-final.log`。新 adapter 回归使用原始 HTTP POST body 决定是否返回结构 JSON，覆盖了此前 request mock 无法检出的提示词丢失。
- 独立 `/tmp/amber-p4-contract-review-pipeline.ts` 四项反例均通过，日志 `/tmp/amber-p4-contract-review-pipeline.log`：模型提案等待期间改 arc 后，旧 CAS 确认拒绝且不落合同；draft 普通整章请求无合同、confirmed 请求含六字段；polish 实际源全文和保事实指令进入请求且原章不覆盖；异步模型预检期间 CAS 改变后，regenerate 在 provider start 前拒绝。
- owner 的全域 TypeScript 检查通过。scoped ArkTS lint `/tmp/amber-p4-contract-lint-final.log` 为 **0 error / 4,687 warn**，共享纯 TS 大文件分析警告如实保留；新增 arc 3 文件 lint `/tmp/amber-p4-contract-arc-lint.log` 为 **0 error / 343 warn**；brief lint `/tmp/amber-p4-brief-lint.log` 为 **0 error / 4,072 warn**。审核未擅自清理周边代码。
- root／owner 已提供 arc 冻结后的全 novel **456/456** 与 TypeScript 通过；该全域结果取代先前 447 项中 446 通过的中间状态。最后新增 public clear 第 10 项之后，本 reviewer 独立确认本范围 **15/15**；全 phase 的最终全域、构建与设备 gate 仍由 root 记录。

本 reviewer 的早期实际请求检查停在可观测的模型运行 port；最新合同提案检查进一步覆盖真实 adapter、OpenAI provider 序列化、raw HTTP body、签名设备请求收据及成功交互截图。签名设备使用本地 synthetic provider 和 fake key，证明 app UI → 服务 → adapter → 实际 HTTP → 六字段草稿 → 作者确认的产品调用链；没有声称真实云模型质量已验收。本范围设备 finding 已关闭，P4 总构建／设备／UI gate 仍由 root 汇总其余独立 review。
