# P4 本章合同、后续走向、定向改稿与代笔作者要求

## 当前生产依据与范围

对照 iOS `NovelDomainModels.swift` 中 `NovelChapterPlanRecord` 与 `NovelUpcomingArcRecord`、`NovelGhostwritePipeline.swift` 作者返修要求及 `NovelWorkspaceBackup.swift` / Importer 公开 Markdown。合同采用生产字段 `outlinePlacement`、`goalAndConflict`、`mustHappen`、`mustNotHappen`、`endingHook`、`visibleFacts`；状态为 draft / confirmed，后续节拍 trim、去重、最多八条，每条最多 160 字符。合同列表项统一拆分换行，确保 UI / 模型数组 / 公开 Markdown 之间内容和形状一致。

新增小模块 `chapter_contract.ts` 保持领域变换和格式化独立。数据沿现有 branchSettings、commitProject/CAS、原生快照和现有 durable ghostwrite job 保存；未新增持久规划任务或生成器。

## 完整调用链

- `proposeChapterContract(id,guidance,CAS?,controller?)` 调用现有模型端口，toolProfile=none，无 checkpoint 写入。固定严格 JSON 指令放入 context 的 required instruction 段，经真实 adapter 发到 provider 后解析返回草稿及源 CAS。页面可取消真实 provider；作者保存 / 确认时用返回 CAS。模型提案未自动变成正式约束。
- `setChapterContract` / `clearChapterContract` / `setUpcomingArc` 使用现有分支变更事务和活动生成门禁。草稿不会进入上下文或启动代笔；确认后完整约束与内部 digest 同步到现有 thisChapterPlan 供冻结计划消费。公开工作区采用 iOS 六标题格式与 status，内部 FNV 摘要不冒充 iOS SHA256，导入根据规范内容重新计算。
- 真实代笔启动预览、repository 冻结、writer、联合 reviewer、原子收录都使用确认合同。后续 arc 同时冻结到既有 FrozenPlan 可选字段，并纳入冻结计划摘要，真实 writer / reviewer / 下一章 planner 均消费该字段；旧任务缺字段保持原摘要。成功收录清除已消费合同，既有多章任务继续沿审核下一章计划链。手写旧式计划修改会清除过期结构合同，后续方向修改也清除过期 arc。普通与历史新分支复制合同并重新绑定合同 id / branchId，源分支保持原值。
- `prepare_ghostwrite` 作者批准时写 confirmed 合同、arc、`suggestedChapterCount` (1..10)。既有启动页用章数初值，仍由作者显式开始。普通保存或清除本章合同、成功收录均清除该准备章数；该字段 native / public 均往返。
- `reviseChapter(id,chapterId,brief,CAS?)` 与扩展 `regenerateChapter(id,chapterId,brief='',CAS?)` 先检查源章节、CAS、写作门禁、模型配置，再进入现有 regenerate 候选生成。实际生成读取同一 snapshot 的原章和来源保护；开始前再次检查 CAS。初始错误直接拒绝供 sheet 显示，后续 provider 错误沿现有 run event。允许按作者要求改变事实修复矛盾，润色提示仍明确保情节与事实。旧章保留到作者手动采用候选。
- `reviseGhostwriteWithBrief(id,jobId,brief,CAS?,expectedCandidateDigest?)` 仅暂停 / 失败任务。复用既有 driver 收口、resume / retry；保存要求时同时校验 workspace CAS 与候选 digest。实际 writer 请求带作者要求和上一稿，只有成功新候选 checkpoint 才清要求，下一章不泄漏。保留真实 candidate attempt，不重置重写次数。原生任务备份保留完整 brief、来源、冻结计划与 CAS。

## 验证证据

`novel_chapter_contract.test.ts` 十项真实路径：draft/confirmed 注入、arc 规范化、native / public 安装深等、只读真实模型提案与取消、过期 CAS、实际定向重生成候选来源及原文不覆盖、确认合同进入真实 writer / reviewer / 收录、普通与历史 fork 分支绑定。包含多行数组 native / public 深等、已批准准备章数往返与普通保存后清除，另跑两章真实 writer → reviewer → planner → writer → reviewer，全部请求含冻结 arc。另覆盖导入→清除合同/arc→再次导出→导入，已消费 frontmatter 不复活已清除合同。

`novel_ghostwrite_author_brief.test.ts` 五项真实路径：失败来源改稿请求、暂停活动 provider 收口、旧 CAS 拒绝、同 CAS 候选已变的旧 digest 拒绝、完整原生备份重开。

本模块冻结验证：合同 10 + brief 5 +公开交换 11，共 26 项聚焦实际路径通过。在共享状态批量修复新测试落盘前，全 novel 456/456、全域 TypeScript noEmit 通过；最新批量修复 worker 新增测试尚处 RED 阶段，root 最终全域验证待其源实现冻结后执行。本章 models/creation/repository/prompt/context/public 等八文件 lint 0 errors / 4687 warnings（仓库纯 TS 跨文件类型分析告警）；新增冻结 arc 所涉 chapter_contract/ghostwrite/runner 三文件 lint 0 errors / 343 warnings。`git diff --check` 通过。证据日志 `/tmp/amber-p4-contract-all-novel.log`、`/tmp/amber-p4-contract-focused.log`、`/tmp/amber-p4-contract-tsc.log`、`/tmp/amber-p4-contract-lint-final.log`、`/tmp/amber-p4-contract-arc-lint.log`、`/tmp/amber-p4-contract-clear-lint.log`、`/tmp/amber-p4-brief-tests.log`、`/tmp/amber-p4-brief-lint.log`。

本 worker 未构建、签名、部署或操作设备。页面布局与实体 provider 接受度由 root 的 P4 独立 review / UI / 设备回路验证，不以领域测试代替。

## 实际签名设备反证后的生产指令修复

实体设备一键计划首次失败：实际 novel_interactive_adapter 在有 context 时清空 request.systemPrompt；原 caller 删除了 discussion instruction，却没有把 CHAPTER_CONTRACT_PROPOSAL_SYSTEM 放回 context，provider 原始 system 中只有资料与正文，模型因此返回普通文本。此前只检查 mock model request.systemPrompt 的证据不足，不能证明指令实际发送。

仅修 caller：context.sections 首部添加 required instruction = CHAPTER_CONTRACT_PROPOSAL_SYSTEM，移除原 discussion instruction，既有全局 adapter 不改。新增 `harmony/chat/src/test/novel_contract_proposal_adapter.test.ts` 跑真实 creation → interactive adapter → OpenAIChatApi → HttpClient 原始 POST body 与 SSE 返回；修前复现 invalid JSON，修后六个 JSON 字段和固定策划指令仅出现一次，资料及作者 guidance 保留，普通讨论与全局聊天指令不混入，不开放 tools，返回 draft 且 project/CAS 不写。

该生产补丁验证：27 adapter tests 与 15 合同/brief domain tests 通过，chat/deepread 两域 noEmit tsc 通过。证据 `/tmp/amber-p4-contract-raw-provider.log`、`/tmp/amber-p4-contract-raw-domain.log`、`/tmp/amber-p4-contract-raw-chat-tsc.log`、`/tmp/amber-p4-contract-raw-domain-tsc.log`、`/tmp/amber-p4-contract-raw-lint.log`。实体设备重验由 root 执行，此处不以测试代替实体结果。

## 签名设备复验与候选计数语义（只读查证）

root 已在实际签名设备重跑一键合同，模型建议成功填入草稿；`device/p4/p4-final-contract-model-success.json` 的实际 UI 树包含“建议已填入草稿。保存草稿或确认计划后才写入工作区。”。root 另核实际作者返修 writer 请求携 `P4_GHOST_AUTHOR_DIRECTION`、上一候选、冻结合同与 arc，随后 fixture 故意让 reviewer 失败，仍为同一 job / plan `nv_mure5vmw_9_aecilkxn:plan:4`。`p4-ghost-author-brief-same-job.json` 验证页面保留该计划和失败任务。上述只记录本范围设备证据，不关闭 root 的 P4 总 UI / 产品 gate。

页面候选仍显示“第 4 章 · 第 1 稿”的只读查证：`candidate.attempt` 是范围 0..2 的自动审稿定向重写轮次，并非作者返修总稿序号。`repository.reviseGhostwriteWithBrief` 将 resumeStage 设 writing、rewriteCount 设为现 candidate.attempt；`ghostwrite_runner.parseCandidate` 用 job.rewriteCount 填写新候选 attempt；`ghostwrite.applyGhostwriteReview` 仅在 rewriteRequired 时加一，且最多两次。新正文 checkpoint 同时要求 candidate.attempt 等于 job.rewriteCount。对于来源 attempt=0 的作者新写，成功新候选仍为 0 符合这套现有领域含义，不应为显示“第二稿”直接递增，否则会耗用自动修订预算和改变校验。

实际成功 checkpoint 会替换 candidate.content/digest 并清除 authorBrief；该 job 的 candidateId 是当前候选槽身份，runner 在已有候选时复用它，不用它当累计稿序号。UI `NovelGhostwriteCard` 和共享 `NovelChapterVerdictCard` 将 attempt+1 写成“第 N 稿”，会令作者新写后误以为仍是旧稿。这是显示语义问题，建议显示“自动修订 N 次”或移除累计稿次，不在本只读任务中修改计数或 UI。

iOS 当前 `NovelSessionViewModel.startGhostwriteRevision` 同样把 qualityAttemptIndex 设回 0，并独立清掉 candidateID、保留来源 receipt。因此质量重写轮次也不是跨作者返修累计稿号；Harmony 本轮不凭这个 UI 标记判断 writer 是否实际调用。
