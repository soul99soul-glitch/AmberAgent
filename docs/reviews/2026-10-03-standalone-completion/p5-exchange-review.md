# P5 独立 review：已收录候选克隆与交换边界

状态：**owner source freeze 后的独立源码／实际 repository 交换 review 通过，无未关闭领域发现。设备与系统保存／分享 gate 待 root 和 entry reviewer 汇总，本报告不宣布 P5 整期产品 gate 通过。** 本 reviewer 只写报告、只读生产实现；不修改 owner 源码，不推进 P6。已按 harmony-arkts skill 检查本范围调用链。

## 范围及已确认链路

- `collected_candidates.cloneCollectedMessage` 仅允许当前分支已收录的助手正文，排除 polish；深复制 canonical UIMessage 并生成新 message id，保留 immediate parent 和 root lineage；原收录记录不改。
- 新候选通过现有 `makeNovelCandidateProvenance` 绑定当前书稿／计划与 branch，Creation mutation 接收作者确认时源 CAS；之后仍经现有 `collect` 的 duplicate、branch、书稿／计划 stale 检查，不绕过收录链。克隆作为新的作者决定清除当前 ordinaryRun 恢复 owner，原消息、部分稿和原收录记录保留。
- `NovelWorkspacePage.confirmCloneCollected` 明确作者确认，再用冻结 CAS 调 Creation；reload 后只在新候选确实存在、仍是原项目／分支时打开原收录表单，创建和收录是两个明确动作。候选来源标签已接入。UI 视觉 gate 由 Workspace／entry reviewer 负责，本报告不重复其范围。
- 交换须用实际 public export → parse → repository install → reopen 及 native snapshot → import/install → reopen 验证新字段；public 内容交换不迁移普通 provider response cursor，same identity native 恢复应保留运行记录，fresh identity keepBoth／adopt 必须清除该运行 owner。

## 冻结后实际核验

| 项目 | 实际行为与反例 | 状态 |
|---|---|---|
| 再次收录 | 原正文与原收录记录保留、新 id／fresh provenance／root lineage、二次克隆 root 不漂移、重复收录拒绝；候选及 lineage 经 native 往返保留 | 通过：真实 Creation → commit → collect → native install/reopen；fresh 决定清 ordinaryRun 回归亦通过 |
| 克隆来源 | stale CAS 拒绝创建；创建后正文／计划变化拒绝收录；polish、非正文、其他分支不得克隆 | 通过：既有实际服务测试覆盖 stale CAS／后续正文修改；独立四项实际 Creation 反例验证拒绝 polish、discuss、foreign branch 和后续 arc／计划改变 |
| public 新字段 | 项目 polishPreference、结构合同／arc、结构事件／身份、分支 override 等实际 public 字段按真实格式往返；未知字段／字节保留。public 不迁移消息候选或运行任务；其 lineage／运行数据由 native 验证 | 通过：actual export → parse → install → switch 两个分支核字段；既有 interop/import 回归及未知字节保持通过 |
| 偏好边界 | 项目 polishPreference 不作为 branch 写作 preferences 的 fallback；两者分别往返 | 通过：删除 preferences.md 的真实导入仍保留 polishPreference，但 writing preferences 为空；包含偏好文件时二者独立深等。实际润色冻结／消费由 polish 独立 reviewer 检查 |
| native 身份 | 原身份运行 owner 精确保留；fresh identity 副本清除所有已定义 project snapshot 的 ordinaryRun，不可经分支／历史恢复重新出现原 provider cursor | 通过：独立 repository.copyNativeBackup 走 validateFullTree → copy → 再校验，10 条实际 known records 中 project／commit／branch／undo 全清；新项目切两个分支、从两条原历史 fork 均无旧游标；same identity cursor 精确保留 |
| 设备 | 已收录回复 → 作者确认 → 新候选 → 明确收录；实际保存／系统分享 | 待 root／entry reviewer 新包证据；领域回归不能冒充设备验收 |

## 已关闭发现与身份边界

本阶段明确范围内两项旧逻辑缺口已通知 owner 并局部修复：`workspace_interop` 曾用项目 polishPreference 回填缺失的分支 writing preferences；`native_backup_copy` 曾只重绑 projectId、保留普通响应 cursor／owner。修复分别删除错误 fallback，以及只清已定义 project records 的 ordinaryRun；没有把未知 JSON／字节做泛字符串替换。clone 的当前记录也清除旧恢复 owner，没有增加新任务状态机。

新身份 native copy 的分支／head／receipt 保持原有 workspace 内身份，projectId 沿已定义记录重绑；普通 provider responseId 不属于可跨书复用身份，必须清除。独立脚本确认原项目 native 文件集合未变，原文 messages 和 opaque bytes 保留。same identity native 恢复则继续精确保留 ordinaryRun 和 responseId。public serializer 只输出作者工作区：消息候选、运行记录、讨论归档及原始 quick-start seed 不属于当前 iOS public 交换，native 负责它们的完整迁移，未误宣称 public 是 runtime 备份。

## 独立验证及证据边界

- 冻结后独立重跑 `novel_p5_exchange.test.ts`、`novel_collected_clone.test.ts`、`novel_import_recovery.test.ts`、`novel_import_conflicts.test.ts`、`novel_workspace_interop.test.ts`：**22/22**，日志 `/tmp/amber-p5-exchange-review-focused-final.log`。新增 3 项实际 public/native install 与 2 项 clone 生产服务回归均包含在该运行中。
- `/tmp/amber-p5-exchange-review-clone-guards.ts` 四项独立实际 Creation 反例通过，日志 `/tmp/amber-p5-exchange-review-clone-guards-final.log`。除三类无权限克隆被拒绝且未新增消息，还检查 canonical id／createdAt 是新身份、原 parts 精确保持，以及 clone 后修改 arc 再收录拒绝、collectedChapterId 仍为空。
- `/tmp/amber-p5-exchange-review-native.ts` 穿过实际 repository.validateFullTree／copy／install，验证 source main 与另一个 branch 都有 ordinaryRun，10 条 known snapshots 全部清 owner；新身份在两个分支及两个历史 fork 无旧 responseId；原身份原记录深等恢复，原项目原始文件集合与未知字节保持。日志 `/tmp/amber-p5-exchange-review-native.log`。没有仅调用 copy helper 就把交易成功视为已验证。
- owner 冻结报告给出相关 **38 项 focused 通过**、deepread TypeScript 通过；独立读取 `/tmp/p5-exchange-lint.log`，scoped lint **0 error / 4,858 warn**，如实保留共享纯 TS 警告。

以上证明领域来源守卫、身份清理与真实公开／原生文件往返；没有用这些 Node 仓库测试冒充系统 ZIP picker、签名设备视觉或分享面板验收。UI 源码确认按钮链已核对，但本范围设备记录尚待 root 新包汇总。P5 整期 gate 由 root 在普通运行、润色、交换和 UI 各独立 review 完成后关闭。
