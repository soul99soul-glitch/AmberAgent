# P4 专属小说工具与作者审批实现

2026-10-03。范围为 backlog B06/B09/B10/B11，以及现有工作区提案的当前分支投影。沿用项目锁、CAS、持久 proposals、现有快照/ledger/receipt；没有新增事务机或独立存储。

## 实际调用链

`AppContainer.novelWorkspaceToolPort.projectOperation` → chat `executeNovelProjectOperation` → `NovelCreation.executeProjectTool` → `repository.proposeProjectOperation`。写入动作只生成持久提案和摘要/原文/新稿/范围/来源 digest，不修改作品；作者确认经 `NovelWorkspaceService.resolveProposal` → `creation.resolveWorkspaceProposal` → `repository.resolveProposal`，在同一快照事务里采用并保存决定和 receipt。

chat 新 `novel_project_operations.ts` 提供与 iOS 实际目录对应的 15 个工具。已有 7 工具加专属目录共 22 个；只读 profile 只允许专属章节列表、章节阅读及设定提案列表。schema 保留范围和枚举，但执行器仍实际检查参数与领域限制。

## 完成行为

- 项目重命名、润色偏好；后续节拍设置/清除；本章 draft 合同提案与 confirmed 代笔准备。准备保留建议章数，并显示完整合同、章数与节拍。
- 资料新增/修改通过 canonical 材料事务，保留未指定的 aliases/tags/customKind/injectionMode，映射 iOS 类型。共享资料与本分支覆盖有明确审批文本。原素材类型不能被工具改名替换。
- 章节列表、带 1-based 段号的正文阅读与来源 digest；最长阅读正文 24k，重复段落不会越过可见截断范围重新出现。
- 标题修改不改变正文；按段替换冻结原文、整章 digest 和原始 UTF-16 范围，保留未选中的 CRLF/空白。采用后保留原章版本；过长新稿拒绝，不截断。
- 最近 N 章回退使用当前分支可达的真实快照，精确匹配保留章节 ID 前缀，作者确认只提交一次 restore；找不到真实边界则拒绝。多章删除按 ID/ordinal 明确选择并在一次审批事务里执行。
- 待采用设定列表为读取；拒绝候选仍生成作者提案。
- proposal 列表和 durable pending 计数按 activeBranchId 投影；其他分支的提案不能被当前分支拒绝或采用。
- 批准检查当前分支 durable job；共享资料修改也沿 P3 检查所有受影响分支的 durable job。

## 精确修复与独立审查

讨论工具产生提案后，保存 transcript 会推进 head。审批仅在 branch/treeDigest 一致、从当前 head 回到来源 head 的全部提交都是 `transcript_checkpoint` 时允许继续，同时再次检查操作来源 digest 与正文原文范围。正文、元数据、普通 no-op 提交、undo 或其他分支推进仍拒绝。旧 generic 审计正文 patch 采用也在同一事务里保存来源版本。

独立 reviewer 的四项实际反例均已修复，并纳入永久集成用例：

1. 非主线修改项目润色偏好后，公开导出必须投影当前项目偏好。switch/fork/undo 已保留当前项目偏好，补的是导出缺失投影。
2. 通用资料写入先走现 `workspaceToolPath`；相对 `setting/...` 解释为当前分支。共享写使用专属显式 scope。
3. 原素材工具准备、执行、拒绝、冷启动 reconcile 和重复确认曾混用 generic patches 与 operation。现统一 `stageWorkspaceWriteProposal`，按同一规范化路径与操作参数验证 replay，作者拒绝正常结案，批准重复执行只返回已有 receipt。
4. 当前分支有私有覆盖时，共享资料审批原文必须取 base；预览前后源均按 scope 选择，正文和摘要明确影响范围。

独立探针另覆盖长阅读重复段、多次修改后真实 recent-N 边界、中间章删除导致后续剧情失效。全部七项探针已通过。

## 验证证据

- `novel_specialized_operations.test.ts`：14 个真实领域集成场景，包括持久重读、范围精确性、分支/CAS/job门禁、完整 native 恢复、公开导入、实际工具批准/拒绝/重放。
- chat `novel_project_operations.test.ts`、既有 workspace tools / interactive adapter：40/40。包含真实 catalog → creation → repository → transcript → author approval 链路。
- 独立 review 探针：7/7；记录 `/tmp/amber-p4-review-probes.log`。
- 本次冻结点全小说领域测试 457/457；deepread/chat tsc 均通过；变更 lint 0 error（4561 既有 warning）；git diff --check 通过。根 agent 保存构建/设备证据。日志 `/tmp/amber-p4-tools-all-novel.log`、`/tmp/amber-p4-tools-tsc.log`、`/tmp/amber-p4-tools-chat-tsc.log`、`/tmp/amber-p4-tools-lint.log`。

本子任务未构建、安装或宣称设备 UI 验收；root 与 UI worker 负责这一验证阶段。
