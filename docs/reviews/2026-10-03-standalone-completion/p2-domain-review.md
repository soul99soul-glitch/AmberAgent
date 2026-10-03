# P2 小说领域独立审查

审查日期：2026-10-03。审查范围为当前 `history_selection.ts`、`project_metadata.ts`、`repository.ts` 的历史/分支/元数据，`creation.ts` 的相关包装和停止运行，以及 A15 Reader 实际入口。只读实现，未修改领域或 UI 文件。

## 结论

多步撤回、精确 commit snapshot 分叉、原始种子不可变、项目元数据随重读/原生备份保留、历史 mutation 的 CAS 与 durable job 门禁已有实测闭环。首次审查的 R1/R2 均已修复并完成最终独立复审，当前领域与 A15 生产调用链通过。具体设备对齐/间距及最终包证据由阶段 UI 审查另列，本报告不替代设备验收。

## 已确认问题

### R1 — 停止后保存失败仍允许后续切换分支

- 触发：普通讨论运行中执行停止；`persistInterrupted` 的 `commitProject` 抛出写入失败；调用方 await `stopActiveRun` 后执行 `switchBranch`。
- 路径：`creation.stopActiveRun` → `interrupt` → `runGeneration` catch → `persistInterrupted` 失败 → emit failed → finally `doneResolve` → `stopActiveRun` 成功返回 → `switchBranch` 成功。
- 结果：当前 UI 虽收到“停止后内容未保存”，后续流程仍会切换离开原 owner；此次输入/partial 尚未持久。与计划中“停止 checkpoint 保存失败不得切 branch”不符。
- 独立复现：`/tmp/p2-stop-checkpoint-repro.mts` 使用真实 repository，只在 model 开始后注入 `commitProject` 写失败。结果 `stopRejected=false`、terminal `failed`、`switchedToMain=true`、当前 messages 为 0。
- 最小修复：记录本次 LiveRun 的停止持久错误，cleanup 后由 `stopActiveRun` 抛出，让现有 UI 的失败路径停留原分支。不要另造通用状态机。回归需覆盖失败时不执行切换/删除，以及正常停止仍等待终态 checkpoint。

**普通 turn 修复已复审通过。** 新增 `LiveRun.interruptedSaveError`，普通 `runGeneration` 停止持久失败记录该错误，`stopActiveRun` 等待 cleanup 后抛出。独立复现调整为 UI 实际顺序 `await stopActiveRun; await switchBranch`，现 `stopRejected=true`、`switchedToMain=false`。新增测试同时覆盖停止后切换、删除保持原分支并保留可见 unsavedMessages。

**同一停止入口的工具续接仍有遗漏。** `runToolContinuation` catch 当前只 emit failed，不记录停止持久错误。真实 pending `ask_user` 续接中，最后 checkpoint 在 cancel 时写失败，随后 `stopActiveRun` 仍成功返回并切分支。独立复现 `/tmp/p2-tool-stop-checkpoint-repro.mts` 输出 `stopRejected=false`、terminal failed 包含 `tool continuation checkpoint write failed`、`switchedToMain=true`。这是同一生产调用的另一 mode；需在该现有 catch 中完成部分稿保存/失败传播和 unsavedMessages，不引入第二套停止机制。

**最终复审：工具续接遗漏已关闭。** 现 `runToolContinuation` 的实际 checkpoint 捕获写入错误，只有停止中的 LiveRun 才记录 `interruptedSaveError`，原 catch 发出失败原因和 `live.messages` 的可见未保存内容；`stopActiveRun` 等待 cleanup 后抛出。原工具续接负向复现现为 `stopRejected=true`、`switchedToMain=false`，unsavedMessages 包含“未落盘的续接片段”。普通 turn 原复现也维持阻止切分支。两条生产 mode 没有另建停止机制。

### R2 — A15 Reader 仅恢复当前稿，没有版本关联分叉入口

- 触发：已有后章时在 Reader 预览前章历史版本。
- 实际入口：`NovelChapterReaderPage.confirmRestoreVersion` 只有“继续预览/恢复”；`restoreVersion` 只调用 `creation.restoreChapterVersion`。没有 Reader→版本对应 checkpoint 查找→`forkFromHistory` 的调用。
- 实际领域：`restoreChapterVersion` 可以改当前前章，repository 随后将后章剧情指针标 stale；通用历史 API 存在不证明 A15 用户链完成。
- 最小修复：仓库在当前分支可达历史中读真实 commit snapshot，按 selected version 的 chapter ID、title、content 精确匹配最新有效检查点，返回 head/章数/时间供 Reader 预览。Reader 添加关联该 head 的“从此版本另开分支”，明确展示恢复完整快照的范围，再调用现有 `forkFromHistory`。无匹配快照时显示不可用，不用 `createdAt` 猜 commit，不让用户自行寻找无关联历史。确认前/提交时保持当前分支 CAS 与来源资格。
- iOS 事实边界：当前 `NovelChapterViews.swift` 的直接恢复限制基于 factCompatibility，另有“作为手动改写”；本报告不声称 iOS 同一面板本来具有 fork 按钮。A15 fork 是本轮计划明确的鸿蒙完整化合同。

**领域修复已复审通过。** `repository.chapterVersionCheckpoint` 实际读当前分支可达 snapshot，验证 ledger/tree 后按 chapter ID/title/content 精确匹配，返回最新真实 checkpoint；可选 expectedCAS 防止 Reader 冻结表单漂移。creation 是薄包装。测试已覆盖完整旧正文/世界资料/对话分叉、无匹配、错误章 ID/标题、缺失版本、过期 CAS 不写入。Reader UI 尚在实施，待冻结后追完整 caller。

**最终复审：A15 实际用户链已关闭。** Reader 历史行 → `previewVersion` → 原子 workspace snapshot 获取冻结 CAS → `chapterVersionCheckpoint` → `NovelChapterVersionPreview` 显示明确的完整工作区范围/命名输入/分叉操作 → `confirmVersionFork` 确认 → `forkVersion` 以同 CAS 重查精确 checkpoint → creation `forkFromHistory(...,cas)` 透传 → repository CAS + 真实历史 snapshot 原子安装 → `router.back` → Workspace `onPageShow` 在 pageHiddenOnce 后 reload 当前分支。原分支保留；无匹配或查询失败显示原因且不给分叉入口；来源/确认漂移抛可见错误。fork 不依赖 createdAt 猜测。过期确认到最终仓库写入也已有真实测试。

## 通过的领域验证

- `history_selection.previousWorkspaceCheckpoint` 使用 undo 的 `restoredFromHead`，跳过讨论 checkpoint 和分支/项目元数据变更，第二次撤回不会在两个稿件之间来回切换。
- `restoreHistoryLocked` 校验当前 CAS、可达历史、snapshot 的项目 ID 和 tree digest；分叉读取所选真实旧 snapshot，包括正文、资料、对话及剧情状态；保留当前项目名称/模型/原 seed/项目润色偏好，原分支数据未被覆盖。
- 分支删除拒绝当前分支或主线；设主线保持唯一，已删除分支不能切入。
- `commitLocked` 调用 `assertNovelCreationMetadataImmutable`；原始 seed 和 creationMode 拒绝修改，项目润色偏好允许按 CAS 更新；seed 的调用者后续改动不会回写已创建项目。
- 原生备份恢复保留多步撤回 cursor 与 immutable 项目元数据。

## 检查记录

执行：

```sh
harmony/deepread/node_modules/.bin/tsx --test \
  harmony/deepread/src/test/novel_history_completion.test.ts \
  harmony/deepread/src/test/novel_project_setup.test.ts \
  harmony/deepread/src/test/novel_workspace_history_repository.test.ts \
  harmony/deepread/src/test/novel_workspace_history.test.ts
```

36/36 通过，输出 `/tmp/p2-domain-review-tests.log`。R1 的独立负向复现未被这 36 项覆盖，因此不能仅凭现有全绿判定停止闭环。未进行设备操作；具体 UI 对齐/间距由阶段 UI review 的截图与控件 bounds 验证。

修复后追加 `novel_version_checkpoint.test.ts` 并重跑同组，**42/42 通过**，输出 `/tmp/p2-domain-review-tests-fixed.log`。工具续接独立负向复现仍失败，不能据 42 项通过关闭 R1。

最终 sourcefreeze 后，重跑原两负向复现均阻止切分支；聚焦组再加 `novel_c5_tool_continuation.test.ts` 共 **46/46 通过**，输出 `/tmp/p2-domain-review-final.log`。46 项覆盖领域与生产服务链；Reader 入口和返回刷新已追源码，实际屏幕控件 bounds 仍由主 agent 的设备记录承担。
