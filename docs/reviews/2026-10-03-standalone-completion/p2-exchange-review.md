# P2 独立审查：导入、备份和旧稿恢复

结论：P2 的导入、备份、只读恢复和删除调用链可以关闭。首次审查复现的项目删除任务收口缺陷已修复；独立重跑原复现和普通生成、代写、润色门面回归均通过，未发现剩余阻断问题。

本审查未实施相关源码，仅新建本报告。范围为当前未提交工作树中的 `NovelWorkspaceService.ets`、`import_conflicts.ts`、`native_backup_copy.ts`、`native_backup.ts`，`repository.ts` 的导入/恢复/删除区域及 `creation.ts` 的可达入口。ETS 审查已读取 `harmony-arkts`。不以既有大面积脏改作本轮贡献，也未重置任何文件。

## 已修复的确定发现

**[P1，已关闭] 删除项目没有取消该项目的代写或润色任务。**

首次审查的可达链为 `NovelProjectsPage.confirmDelete → creation.delete → stopActiveRun → repository.deleteProject`。原 `stopActiveRun` 只读取 `projectRuns`；代写与润色分别由 `drivingGhostwriteJobs`、`drivingPolishJobs` 持有。原删除实现会把工作区移入 `.deleted`，却没有调用这两类任务已有的取消入口，也没有等待其真实驱动收口。

独立复现使用真实 `createFileNovelRepository`、`createNovelCreation`、`ghostwriteRunner`，模型 port 在 writing 阶段挂起，随后调用 `creation.delete`。结果：

```json
{"cancelledProviderCalls":0,"stageInDeletedProject":"writing","projectNoLongerLive":true}
```

这是已经触发的调用问题：删除后外部生成仍在执行；回收目录中的 job 仍标记 writing。后续“恢复删除的项目”再 `creation.open → recoverGhostwriteDriver/recoverPolishDriver`，可能重新驱动用户删除时尚未结束的任务。

修复后 `creation.delete:834–854` 先 `stopActiveRun`，沿现有 `cancelGhostwrite`、`cancelPolish` 及润色 effects 路径取消目标项目的非终态任务，再等待已有两类 driver map 中该项目的 Promise，最后 soft delete。普通生成中断 checkpoint 保存失败会抛出并保留项目和可见 partial；取消范围没有扩大到其他项目。未增加任务框架或重试。

独立重跑原模型挂起复现，结果已变为：

```json
{"cancelledProviderCalls":1,"stageInDeletedProject":"cancelled","projectNoLongerLive":true}
```

新增 `novel_delete_jobs.test.ts` 两类 held provider 测试实际启动 driver 和仓库 jobs，验证取消仅命中目标模型请求、订阅清理完成后删除返回、回收目录及恢复后的 durable stage 为 cancelled、恢复再 open 不重新启动、其他项目保持 writing；两项均通过。普通生成 checkpoint 落盘失败的删除测试也通过。本发现关闭。

## 已闭环的能力

| 能力 | 实际链路与核查结果 |
| --- | --- |
| 公开/native ZIP 类型识别 | `NovelProjectsPage.importWorkspace → Service.inspectImport → codec.list`；存在原生 manifest 则走 `importNovelNativeBackup`，否则走公共格式校验和 import plan。真实 ZIP 测试使用共享 deflate/inflate codec，并验证 PK 字节。 |
| 拒绝冲突 | Service 默认 `reject`，相同项目在确认前拒绝；UI 冲突时仅提供保留两份/明确替换，不显示泛化“确认”。非冲突预览到确认间若出现同 ID 数据，仓库重新计算 sourceToken 并拒绝旧预览。 |
| 明确替换 | native/public 确认重新核对输入与 live/trash/deleted 的完整 token，合法文件写入 staging 后再 swap；旧目录完整进入 `.retained`，阶段提交失败能还原原树。 |
| 公开格式保留两份 | `copyNovelWorkspaceImportPlan` 使用已知公共 serializer 改项目身份和标题；重建合法 import plan。正文中的旧 ID 文本、章节 ID、支线以及未知 assets 字节保持；新 ID 安装仍经过实际仓库。 |
| native 保留两份 | `repository.copyNativeBackup` 在复制前后 `validateFullTree(..., true)`；`native_backup_copy` 仅改已知项目状态、ledger 引用的 commit/branch/undo 文件、jobs 的 projectId，以及 manifest 项目 ID。没有对所有 JSON 或字符串做全局替换。 |
| canonical digest/CAS | `snapshotTreeDigest` 排除 `.amber/`、manifest.yaml、project.md，项目身份变更不改变正文 treeDigest。新 native manifest 重建字节清单；commit/head/tree、branch 状态、undo、proposal expectedCas 和 job expectedCas 在完整验证中核对。测试保留 frozen plan、进度和 source CAS。 |
| 未知私有文件 | native 复制只触碰 ledger 指明的已知路径；测试中的 `.amber/commits/future-opaque.json` 非 UTF-8 字节保持不变。公共格式不输出宿主 `.amber`，不把两种交换格式混为全量备份。 |
| 导入目标漂移 | sourceToken 涵盖备份/plan 与当前 live/trash/deleted 字节；更改正文或未知文件后，旧预览不能替换。keepBoth 使用冻结导入输入和新项目身份，不需要停掉原项目任务。 |
| 活跃生成与导入 | creation install/restore 通过 `hasActiveProjectDriver` 拒绝同项目普通/代写/润色活跃 driver；仓库另外拒绝合法运行中 durable job。即使旧 jobs.json 损坏，真实 driver 门禁仍保护现有项目。检查预览不取消任何任务。 |
| 删除时间排序 | 新删除记录 `.amber/deleted-at.json`，restorePrevious 按实际 deletedAt 比较，不按作品 updatedAt 误认删除顺序。旧版未记录删除时间的数据不宣称能恢复真实时间。 |
| 损坏项目完整只读旧稿 | `NovelProjectsPage.openRecoveryText → NovelRecoveryPreviewPage → repository.readProjectRecovery → recoveryCandidate/validateFullTree`。预览页面没有变更操作，显示所选章节完整 content，没有 substring/maxLines 截断。测试使用 1,000 行旧正文，读前后损坏 live 字节完全相同；来源漂移则要求重新预览。 |

## 验证

- 领域 focused：`novel_import_conflicts`、`novel_import_recovery`、`novel_native_backup`、`novel_workspace_exchange`、`novel_repository`：**43/43**。
- 存储与应用服务门禁：`novel_storage_completion`、`novel_project_setup`：**23/23**。
- 实际 public/native ZIP service：`novel_import_service.test.cjs`：**2/2**。
- 首次审查聚焦测试 **68/68** 通过，并额外独立复现上方删除缺陷。
- 修复后独立重跑 `novel_delete_jobs`、`novel_creation`、`novel_creation_ghostwrite`、`novel_polish_creation`、`novel_project_setup`：**43/43**；原负向复现现明确断言一次取消和 durable cancelled，通过。

命令：

```bash
cd harmony/deepread
./node_modules/.bin/tsx --test src/test/novel_import_conflicts.test.ts src/test/novel_import_recovery.test.ts src/test/novel_native_backup.test.ts src/test/novel_workspace_exchange.test.ts src/test/novel_repository.test.ts
./node_modules/.bin/tsx --test src/test/novel_storage_completion.test.ts src/test/novel_project_setup.test.ts
./node_modules/.bin/tsx --test src/test/novel_delete_jobs.test.ts src/test/novel_creation.test.ts src/test/novel_creation_ghostwrite.test.ts src/test/novel_polish_creation.test.ts src/test/novel_project_setup.test.ts
```

```bash
TSX_TSCONFIG_PATH=/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/tsconfig.json node --import ./harmony/chat/node_modules/tsx/dist/loader.mjs --test harmony/entry/src/test/novel_import_service.test.cjs
```

本次为逻辑与源级 UI 调用检查，没有操作模拟器，因此不宣称弹层、长章正文、键盘或控件边界已视觉验收；交由阶段 UI 独立审查和主 agent 实际截图验证。
