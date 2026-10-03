# P3 资料继承与仓库独立审查

审查日期：2026-10-03。只读当前实现；本报告由未实现该功能的 reviewer 编写。范围为 `material_inheritance.ts`、`repository.ts` 的 shared/override/hidden 存储、真实分支 head/checkout/history、公开/native 往返，以及 `creation.ts` 和生产 caller 的资料编辑/采用落盘。资料字段、注入计划、设备 UI 由其它 reviewer 独立检查。

## 结论

当前审查范围通过，未发现待修的资料继承或事务缺陷。共享资料继续使用既有 canonical workspace、commit snapshot 与 staging/root swap，没有第二 store 或 revision 数据库。资料编辑/采用修改有效数组后，实际仓库提交将其转换为当前分支 override/hidden；重读不会忽略作者修改。

## 实际调用与状态闭环

- `NovelWorkspacePage.saveMaterial` 根据作者选择调用 `creation.saveSharedMaterial` 或 `creation.upsertMaterial`，提交编辑器冻结的 CAS。共享编辑更新 `baseMaterials`，分支编辑经过 `commitLocked` → `withBranchMaterialEdits` 写 `materialOverrides`/`hiddenMaterialIds` → 同一 `installSnapshot`。不是仅修改页面列表。
- 章节建议/设定提案的作者确认从 `NovelWorkspacePage.confirmMaterialAdoption` → `creation.resolveMaterialSuggestion` / `resolveSettingProposal` → source/target digest 和 branch 校验 → mutations `applyMaterialAdoption` → `commitLocked`。真实更新目标和新增资料都成为当前分支 override，原共享版本及其它分支维持原值。
- `saveSharedMaterial` / `deleteSharedMaterial` 用现有 history mutation 的 receipt replay、CAS 和当前 durable job 门禁。`installSnapshot` 检测 base 改动，先验证完整 canonical tree，再检查所有活跃分支 durable job；逐分支更新有效资料、真实 branch snapshot、tree digest、commit snapshot 与 ledger head。
- 上述其它分支写入与当前分支的新 checkout、head、state、共享文件在一个 stage 汇总，最后只进行一次 root swap。stage 写失败不改 live，安装 stage 失败按现有路径还原 backup。独立故障注入验证所有 native workspace 文件逐字节不变，不存在部分分支先提交。
- `projectFromStoredState` 从真实分支有效资料文件重读，并核对 base 对应全局文件。共享变更不会把其它分支未记入 head 的外部正文/资料改动偷偷纳入新 commit；现有定向测试验证损坏分支拒绝且原文件保留。
- `migrateNovelProject` 保留 absent-base 的 legacy 标志。现有完整分支快照在只读 load/switch 时不被强制共享化，第一次显式共享编辑才按原基线提取旧分支独立修改、隐藏项和独有资料；旧分支内容保持。
- 普通新分支保留当前 base 和当前覆盖状态；历史 fork 读取真实旧 commit snapshot，把该 snapshot 的旧有效资料放到当前项目共享 base 下形成对应 override/hidden，保留历史内容且不回滚其它分支共享版本。随后显式恢复继承读取当前 base。undo 共享编辑则通过同一事务恢复 base 并同步活跃分支，显式 private override 保持。
- 生命周期为 deleted 的分支不参与共享传播，不改其保存的 branch snapshot；不能再 switch。历史 commit snapshot 自含旧 project，不依赖当前全局文件，native 全树校验/恢复仍通过。
- 公开往返保留真实共享 base、分支 override/hidden 和资料字段；导入后继续编辑 base/恢复继承已测试。native 往返同时保留 commit 与 undo 关系。

## 已核对的工具边界

当前 `AppContainer.novelWorkspaceToolPort.write` → `creation.workspaceWriteResult` → `repository.validateProposalTargets` 只允许已有章节、plot 和本章计划。资料路径会明确拒绝，不存在文件工具写有效数组后被继承层忽略的路径。现有资料提案采用通过上文的专门 production chain 完成；P4 若扩展 Agent 文件/专项改资料，应接同一个 repository mutation，不另写 store。本报告不把尚未开放的 generic 资料文件写入声称为已完成。

## 验证记录

```sh
harmony/deepread/node_modules/.bin/tsx --test \
  harmony/deepread/src/test/novel_material_inheritance.test.ts \
  harmony/deepread/src/test/novel_workspace_history_repository.test.ts \
  harmony/deepread/src/test/novel_project_setup.test.ts \
  /tmp/p3-inheritance-review-probes.mts
```

**39 / 39 通过**，日志 `/tmp/p3-inheritance-review-tests.log`。其中当前资料继承组 10 项、仓库历史/项目设置 24 项，reviewer 独立临时 probe 5 项：

1. 历史 fork 保留旧有效资料，并使用当前共享 base；恢复继承得到最新 base，原分支不回滚。
2. 删除共享项保留显式 private override、移除继承副本；deleted 分支 snapshot 逐字节保持、switch 拒绝、native tree 有效。
3. 真实 `creation.resolveSettingProposal` 与 `upsertMaterial` 生成真实 override，shared base 和其它分支不变。
4. 注入 stage branch snapshot 写失败，所有 native 文件与 CAS 不变。
5. 注入 stage→live rename 失败，所有 native 文件与 CAS 不变。

临时 probe 没有修改生产实现或现有测试。此报告未执行设备操作，不替代字段/注入/屏幕对齐与间距的独立 review。
