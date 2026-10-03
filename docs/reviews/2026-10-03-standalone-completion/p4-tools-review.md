# P4 专属小说工具与范围操作独立审查

日期：2026-10-03。状态：**passed after precise fixes**。本报告先依据最新 iOS 生产 caller 与 B06/B09/B10/B11 合同准备检查，再对冻结后的鸿蒙 catalog、creation、repository 和作者入口进行独立反例验证。reviewer 未实施本阶段功能，只写本报告与 `/tmp` 独立测试。

## 结论

当前工具/范围操作审查通过。独立复现发现的 R1–R4 已由 owner 精准修复，原反例和最终定向组均通过。真实生产 DI 注册 22 项小说工具（原 7 项加 15 项专属操作），专属写工具只生成既有 durable proposal，作者在现有范围预览中批准后才执行 repository 事务。没有仅靠 schema 或同名函数判定闭环。

## 已查证的当前 iOS 行为

独立产品的 `NovelApp/Sources/App/NovelAppToolHost.swift` 与宿主 `iosApp/ChatToolRuntime.swift` 都在存在 per-run `NovelProjectToolRunContext` 时逐一注册 `IOSNovelProjectToolExecutor.supportedToolNames`；web search 开关只影响搜索，不移除小说项目工具。executor 将写入转成现有 `DefaultNovelCreation.perform` transaction。

| 项目 | 实际语义与证据 |
| --- | --- |
| B06 专属目录 | executor 注册项目改名/润色偏好、upsert/clear upcoming arc、revise material、propose chapter plan、prepare ghostwrite、set chapter title、list/read/revise chapter、revert recent chapters、delete chapters、list/reject setting proposals 和 workspace 五工具。资料更新以显式 material ID 定位；默认/省略字段不能在鸿蒙破坏 P3 已有 aliases/tags/customKind/injectionMode。 |
| B09 按段改稿 | `NovelParagraphSelection.swift` 按空行分段，CRLF/CR 归一，段落范围为 1-based inclusive。`novel_read_chapter` 返回编号正文，省略目标取当前 working 最新章。`revisionApprovalPrompt` 冻结章 ID/段范围/旧正文/新正文；`applyRevision` 重读范围旧正文一致后调用 saveManualEdit。LiveModelAdapter 把该工具 pending approval 转成专属作者卡。 |
| B10 最近 N 章 | `NovelBranchOperations.recentChapterRevertPlan` 先取当前 working 的 suffix N 个 chapter IDs，再沿当前 branch checkpoint undo chain 查找到不再包含这些 IDs 的真实目标；产生 chapter IDs/title/ordinal、target checkpoint、head/working revision。审批后重新验证计划。实际 undoStepCount 可以大于 N，不能以 N 次普通 undo 代替用户“回退最近 N 章”的范围语义。 |
| B11 任意多章删除 | `resolveDeleteTargets` 根据 ids/working ordinals 定位并去重；支持中间章。`deleteApprovalPrompt` 显示准确章范围，审批后沿既有删除事务修改目录并标记后续剧情待同步。该操作保留剧情快照，区别于 suffix checkpoint 回退。 |
| 设定提案 | `listSettingProposals` / `rejectSettingProposals` 仅使用 `snapshot.activeSettingProposals(for: branchID)`；指定不存在或非当前分支 pending ID 必须拒绝，不清其它分支。 |

真实 iOS 审批接线已核对：`NovelLiveModelAdapter.swift` 对 revise/revert/delete 的 pending approval 分别调用 executor 的范围 prompt；不能仅凭 schema 中 `needsApproval` 判定作者确认已完成。

## 独立发现与修复复审

### R1 — 非主线修改项目偏好后公开导出旧值

真实触发：在非 main 分支通过 `novel_set_polish_preference` 提案并审批新偏好，然后公开导出/导入。`publicExportPlan` 只覆盖各 branch 的 name/branches，公开 serializer 读 inactive main 的旧 `polishPreference`，导入得到旧值。rename 项目名本身验证正确，未扩大改名实现。

修复已通过原独立反例：export plan 的每分支投影同时使用当前项目 `polishPreference`。项目名称及偏好经公开往返保留；普通分支切换仍携带当前项目全局值，未改为 branch 配置。

### R2 — generic material 相对路径误改项目共享版本

真实触发：从支线用既有 generic workspace write 写相对 `setting/world/world.md`。新 material 特判在 `workspaceToolPath` 之前执行，误认相对路径为全局 shared，实际污染 base 与其它分支；原 workspace 相对路径语义始终是当前 branch。

修复已通过：先按已有当前 branch 规则归一，再把真实 branch material 文件映射到 `scope: branch` 的既有专属 mutation。显式 shared 使用专属 scope。独立测试确认 base 和 main 内容不变。

### R3 — material 作者拒绝与批准重放遗漏

真实触发：generic material pending tool 经 `creation.continueTool` 拒绝时，`resolvePreparedWorkspaceWrite` 仍创建 generic material patch，被旧受控路径 validator 拒绝；终态 failed，重进 reconciliation 再次失败。首次批准能成功，但重放 `prepareWorkspaceWriteTool` 用专属提案空 patches 对比原 material patch，误报 proposal id 冲突。

修复已通过真实 continuation 反例：prepare/execute/deny/reconcile 复用局部 `stageWorkspaceWriteProposal`，同一规范化 path、现 material 映射和 operation 参数匹配；generic 章/plot/计划路径保留原事务。独立验证拒绝完成且重开不报错、正文保持；批准完成及同 tool replay 成功、receipt 不重复写。

### R4 — shared 资料作者预览显示 private 源

真实触发：当前 branch 对资料存在 private override，再提出默认/shared revise material。执行修改 `baseMaterials`，但 review.oldText 优先取有效 private 内容；审批卡展示的原文不是被修改的共享源。

修复已通过：before/after 依据同一个 scope 取 base 或 effective，预览 label 和 summary 用现有文本明确“项目共享”/“本分支”。独立测试验证审批 oldText 等于真实共享旧源；采纳后 base 更新、当前 private override 保持。

## 最终核实的生产链与限制

- `AppContainer.novelWorkspaceToolPort.projectOperation` → `executeNovelProjectOperation` → `creation.executeProjectTool` → `proposeProjectOperation` → `repository.proposeProjectOperation`：只读立即返回，写入冻结 operation/review/CAS 到原 proposal store；dispatcher 可以自动执行“生成建议”，作品没有写入。实际作者批准由 `NovelOperationsPage.resolve` → `creation.resolveWorkspaceProposal` → `repository.resolveProposal` 完成，拒绝只结案。不能套用工具执行前审批的标志要求到此 staging 操作。
- `NovelOperationsPage.submit` → `NovelWorkspaceService.proposeManuscriptOperation` 传页面冻结 CAS、source digest 和原文范围；reader 编号与本阶段 parser 共用段落逻辑。中文、多行段、CRLF、emoji 的 UTF-16 offsets 按实际原文切片验证，替换只改选中范围，旧章存入现有 chapterVersions。
- `assertProposalCheckout` 只在同 branch、同 treeDigest、当前 head 到冻结 head 的**所有**祖先提交均为 `transcript_checkpoint` 时允许会话落盘后的作者审批；随后仍验证 operation sourceDigest、章节 digest 和选中 span。独立追加“普通同 tree commit 后再追加 transcript”反例仍拒绝，不能仅看最新提交标签。普通 metadata/正文/source 改动拒绝写入，陈旧同分支建议仍可拒绝结案。
- recent N 按当前非 discarded suffix 的 chapter IDs 找该 branch 可达的真实 snapshot，审批后一次恢复 snapshot/剧情状态。独立插入两章各 4 次正文编辑，回退 N=2 仍精确定位追加这两章前的 head；ledger 只新增一次恢复 commit。不可达/不足章数不改目录，不以 N 次 undo 冒充。
- 中间章和多章删除去重并在一个事务执行；独立删 c2 保留 c1/c3，plotStale=true、unresolvedFromOrdinal=2，native tree 校验通过。待确认章来源变化和外分支 target 拒绝，不部分删除。
- `workspaceProposals` 过滤 `expectedCas.branchId === activeBranchId`，包含本分支历史 accepted/rejected；pending count 只统计本分支 pending 加当前 branch snapshot 的 pending setting proposals。跨 branch approve/reject 无写入。专属 list/reject setting proposals 使用当前 branch 的 canonical 项目数组。
- 资料更新省略 P3 字段时保存 aliases/tags/customKind/injectionMode/enabled，kind 不得误改；shared/override 保存、generic 当前 branch 映射、重放及 native/public 往返均有实际回归。confirmed 合同不能由普通 draft 提案静默降级；prepare 仍通过作者批准后写合同，启动沿既有 durable runner。合同/状态完整性由对应 reviewer 另列。
- profile `none` 没有工具 catalog；`read_only` 明确保留原 workspace 四读工具和三个专属读工具。独立给 provider 返回未提供的 `novel_rename_project` 调用，dispatcher 不执行写 port，仍完成只读轮次。普通 workspace factory 未绑定 projectOperation 时维持原 7 项，新增专属目录是 opt-in。

## 最终验证

领域组：`novel_specialized_operations.test.ts`、`novel_workspace_history_repository.test.ts`、`novel_c5_tool_continuation.test.ts`、`novel_material_inheritance.test.ts`，再加 reviewer `/tmp/p4-tools-review-probes.mts`，**52 / 52 通过**；日志 `/tmp/p4-tools-review-domain-final.log`。

Chat 组在 `harmony/chat` 工作目录执行：`novel_project_operations.test.ts`、`novel_interactive_adapter.test.ts`，再加 reviewer `/tmp/p4-tools-profile-review-probes.mts`，**33 / 33 通过**；日志 `/tmp/p4-tools-review-chat-final.log`。包含实际 catalog→creation→repository→transcript→作者批准的测试，及实际 loop 的 profile 隔离。

reviewer 独立 10 项 probe（领域 8、profile 2）覆盖原 R1–R4、重复段落截断、recentN 与中间删除、完整祖先 transcript 限定和 profile 写入阻断。首次从仓库根运行 Chat adapter 测试因未选择 Chat tsconfig 的路径别名而失败，切到该模块官方 test 工作目录后通过；不计作产品缺陷。

未修改生产代码、未操作设备。最终设备/provider 生产 DI、布局与字号证明由主 agent 和 UI reviewer 的记录承担，本报告不将 mock/源码测试冒充实际云或屏幕验收。
