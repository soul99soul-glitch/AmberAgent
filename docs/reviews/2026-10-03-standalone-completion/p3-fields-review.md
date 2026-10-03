# P3 独立审查：资料字段、公开交换与提案落地

结论：本审查范围可以关闭。资料字段、关系类别、提案采用和真实继承公开往返主链通过；本次定位的两个未指定字段语义缺陷及局部更新缺陷已由实施者修复，独立重跑原复现和 70 项聚焦测试通过，未发现剩余具体阻断问题。上下文预算和界面验收仍由各自独立审查负责。

本审查仅写入本报告，未改实现。范围为 models/material_fields/mutations/material_adoption/workspace_interop 及三个模型提案解析器，追到 creation、repository 与公开 ZIP service 的真实 caller。已读取 harmony-arkts。上下文预算和 UI 布局由其他独立审查负责，不在本报告宣称验收。

## 已修复的确定发现

**[P1，已关闭] 无字段 pending 提案经公开交换后变成显式清空并开启注入。**

可达链：`NovelWorkspaceService.exportWorkspaceZip → repository.publicExportPlan → buildNovelWorkspacePublicFiles → buildNovelWorkspaceImportPlan → repository.installWorkspacePlan → buildSettingProposalAdoption → creation.resolveSettingProposal → mutations.resolveSettingProposal → applyMaterialAdoption`。

`workspace_interop.materialPublicFields/materialLists` 为正式资料归一化 aliases/tags/customKind/injection，本来合理；pending inbox 复用这些 helper，导致没有指定字段的模型提案输出 `aliases: []`、`tags: []`、`injection: always`。global/branch inbox 解析又复用正式资料的 materialFields，将缺失 customName 补成空字符串。无字段提案原来应保留目标资料字段，公开包一次往返改变了这个含义。

独立复现使用真实 creation、file repository、公开文件生成/解析和新 repository 安装，不只检查 serializer。目标资料有别名舟哥、作者标签、自定义类别及 off，提案不指定这四个字段。导出前预览保留别名/off；导入后预览为空别名/always，采用后四个字段均被覆盖：

```json
{"originalFieldsUndefined":true,"beforePreview":{"aliases":["舟哥"],"injectionMode":"off"},"afterPreview":{"aliases":[],"injectionMode":"always"},"saved":{"aliases":[],"tags":[],"customKind":"","injectionMode":"always"}}
```

修复增加 inbox 的 proposalFields，并在 pending 导出时仅保留存在的 optional 字段；正式资料继续完整归一化。独立重跑上述原复现，现在保存别名舟哥、作者标签、自定义作者类别和 off。新增真实公开往返安装测试另证明显式 []/空类别/always 仍能清空并开启注入，不会被当成缺失。本发现关闭。

**[P2，已关闭] 提案来源 digest 无法识别缺失字段改为显式空值的 metadata-only 变更。**

`material_adoption.suggestionSourceDigest/settingProposalSourceDigest` 使用 `normalizeNovelMaterialFields`，把缺失 aliases/injectionMode 和显式 `[]/always` 计算为同一来源 digest。两者采用到既有目标时行为不同：缺失保留目标，显式值覆盖目标。

独立纯领域复现：先用无字段提案打开别名舟哥/off 目标预览，再将同一来源 proposal 的字段改为 `aliases: [], injectionMode: always`；旧 edit 调用 `resolveSettingProposal` 未拒绝，保存舟哥/off。重新打开的 edit 则为 []/always。这是 source metadata 的可复现领域校验缺口；本报告不假称已在界面触发同一变更。修复将两个来源 digest 改用 optionalNovelMaterialFields 保留字段 presence，target digest 继续按实际有效字段计算。独立原复现现在断言旧预览被拒绝；新增测试同时覆盖 suggestion 和 setting proposal。本发现关闭。

**[P2，已关闭] 部分字段更新清空其他已有资料字段。**

聚焦测试 `novel_material_fields` 的真实 `creation.upsertMaterial(..., {tags: ['新标签']})` 调用失败：已有 aliases 舟哥/Captain 被清为 []。原 `mutations.upsertMaterial` 在传 fields 时直接归一化该局部对象，未保留当前字段。修复先合并当前 metadata 与局部字段，再归一化；真实 creation/repository reload 的原红项现在通过，并保留 aliases、customKind 和 smart。实施者已在本审查首次测试前新增此回归，本审查未改代码。本发现关闭。

## 已验证的链路

| 范围 | 当前源码及验证结果 |
| --- | --- |
| 当前 iOS 字段定义 | NovelDomainModels 的 material record 存 aliases，revision 存 tags/injectionMode，kind 的 custom 关联自定义名称；NovelMaterialsView 实际编辑入口传 tags/aliases/customName/injection。三种注入值是 always/smart/off，relationship 为独立类别。 |
| iOS 公开格式 | NovelWorkspaceBackup.materialFields 输出 injection、customName、aliases，relationship 目录为 relationships；当前 iOS 公共包未输出 tags，importer 使用 tags:[]。鸿蒙自行保存和公共往返 tags 不等于声称 iOS 公共包包含 tags。 |
| 鸿蒙字段落盘 | makeNovelMaterial、creation.upsertMaterial、mutations、repository state record 保留 aliases/tags/customKind/injectionMode；归一化去空白与重复。实际 file repository reload 和有完整字段的 public roundtrip 测试通过。 |
| 三类模型 payload | parseSuggestions、parseSettingProposals、parseNovelQuickStart 都调用 parseNovelMaterialFields，识别 customKind/customName 和 injectionMode/injection；模型建议与 quick start 的实际 prompt 包含这四类字段和 relationship。 |
| 真正提案 caller | 收录章节后 creation.refreshMaterialSuggestions 调 analyzeChapterSuggestions，使用当前来源章节 digest；quick_start 的 generate 完成后 stageNovelQuickStart 持久化 pending proposal；显式工具 novel_ingest_setting_proposals 经 AppContainer port 调 creation.ingestSettingProposals。普通旧围栏不会自动成为落地修改。 |
| 作者采用 | NovelWorkspacePage 生成 source/target baseline，creation.resolveMaterialSuggestion/resolveSettingProposal 先核对 branch，再 commitMutation → mutations → applyMaterialAdoption。目标 digest 包含有效 metadata；已存在的 tags 或非空 aliases 变更、来源正文变化及缺失字段变为显式值均拒绝旧预览。无字段来源直接采用或公开往返后采用既有目标均能保留 off 和 aliases。 |
| 公共 base/override/hidden | global setting 导入为每分支 baseMaterials，branch setting 为 materialOverrides，harmonyDeleted 为 hiddenMaterialIds；真实 repo.publicExportPlan 读每分支 snapshot，正式共享资料不变成全分支 override。roundtrip 后安装到新仓库、更新共享 base、切支线及恢复继承测试通过。 |
| 私有兼容性边界 | material metadata 的已知 frontmatter 覆写只限 kind:material；章节/project 同名未知扩展仍保留。其他格式保留、UI 与上下文预算不在本审查重复覆盖。 |

## 验证

首次独立聚焦执行 `novel_material_fields`、`novel_material_adoption`、`novel_material_inheritance`、`novel_workspace_interop`、`novel_suggestion_engine`：**45/46**。唯一红项为上方部分字段局部更新；另外两项 optional-presence 缺陷由独立内联复现定位。

修复后独立重跑上述测试，并加入包含实际 setting parser 的 `novel_continuity_audit` 与真实 quick start runtime 的 `novel_p4_tasks`：**70/70**。这用于验证本范围的 quick start 提案链，不代表 P4 已整体完成。两项原独立复现现在通过：

```json
{"publicRoundtripSaved":{"aliases":["舟哥"],"tags":["作者标签"],"customKind":"作者类别","injectionMode":"off"},"metadataPresenceStaleRejected":true}
```

```bash
cd harmony/deepread
./node_modules/.bin/tsx --test src/test/novel_material_fields.test.ts src/test/novel_material_adoption.test.ts src/test/novel_material_inheritance.test.ts src/test/novel_workspace_interop.test.ts src/test/novel_suggestion_engine.test.ts
./node_modules/.bin/tsx --test src/test/novel_material_fields.test.ts src/test/novel_material_adoption.test.ts src/test/novel_material_inheritance.test.ts src/test/novel_workspace_interop.test.ts src/test/novel_suggestion_engine.test.ts src/test/novel_continuity_audit.test.ts src/test/novel_p4_tasks.test.ts
```

退出条件已满足：缺失字段 pending proposal 经真实公开导出/解析/安装后采用到 off/带别名目标仍保留字段，显式清空仍生效；两种来源提案缺失→显式值改变拒绝旧预览；局部资料字段更新保留未提交字段；聚焦回归全绿。本次未操作模拟器或真实模型，不宣称视觉验收或云端模型验收。
