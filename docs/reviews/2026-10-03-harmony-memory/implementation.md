# 鸿蒙记忆系统改造验收记录

参考线程：`f185dfae-184e-45fc-b071-6ea8c6b277f9`。从本机线程记录读取原文，并对照 iOS 实现；本轮覆盖该线程已实现的 P0/P1，不新增向量检索或 Embedding 服务。

## 行为与实现

| 能力 | 鸿蒙本轮行为 | 主要实现 |
| --- | --- | --- |
| 时间与生命周期 | 提示词带当前日期、记录日期、有效期；相对日期按用户消息日期解释；过期项不召回。短期转长期需要至少两次确认/实际引用，并在记录至少 14 天后发生强化；30 天无活动的无期限短期项进入归档建议，保留 Dream 原有审阅流程。 | `memory_lifecycle.ts`, `memory_prompt_builder.ts`, `memory_dream.ts` |
| 有依据的抽取与更正 | 仅最新用户消息可作为新增证据，助手上下文仅用于解指代。原话必须匹配；数字/拉丁词需有来源；支持 add/update/invalidate/confirm/noop。更正保存旧版本并关联 supersedes，自动流程保护核心与置顶项。 | `memory_extractor.ts`, `memory_extraction_actions.ts` |
| 并发提交 | 模型返回后及源文件提交前重新读取开关和会话；删除会话、编辑用户原话、切换分支、新增联网/MCP 工具污染均阻止旧响应写入。目标记忆版本变化时跳过旧动作，不退化为新增。 | `MemoryExtractionCommitGuard.ets`, `MemoryStore.ets`, `ConversationRunAuxiliary.ets` |
| 实际引用强化 | 召回只更新使用时间；只有本次生成中实际引用、且确实注入的记忆 ID 会强化。内部标记隐藏，工具多轮引用保留，同一来源重复确认不会重复计数。 | `memory_citations.ts`, `ConversationRunDependencies.ets`, `ConversationRunAuxiliary.ets` |
| 有界用户画像 | 至少三条有效长期用户偏好或无期限反馈来源，最多 12 项；文本受来源约束。所有来源通过 Jev 且当前作用域可召回时才注入，占用实际提示词预算。来源被编辑、过期、作废或移出范围后画像失效。索引文档展示画像及来源。 | `memory_profile.ts`, `MemoryOptimizationStore.ets`, `memory_documents.ts` |
| 同义去重 | 词法相似度只提名，模型确认同义后归档旧项并保留版本关系；核心/置顶项不自动合并。已恢复的版本对不会被再次自动归档。复用主题整理的一次模型调用和原有预算、充电、空闲、取消、权限门禁。 | `memory_semantic_dedup.ts`, `MemoryTopicOps.ets`, `MemoryStore.ets` |
| 管理与追溯 | 新增全部记忆页，支持活跃/已归档/全部与搜索、恢复；活跃数量不包含主题、过期、作废项。详情独立展示用户原话、日期、版本关系；变更记录默认最近 8 条并可展开。导入导出保留新元数据。 | `MemoryRecordsPage.ets`, `MemoryRecordPage.ets`, `MemoryChangeLog.ets`, `memory_frontmatter.ts` |

模型主题/画像/同义整理仍使用原有可选开关，默认关闭，没有悄悄开启后台模型调用。来源记录是权威数据，画像与 Markdown 是可重新生成的派生数据。自动更正保护核心/置顶项，比参考 iOS 的核心处理更保守，手动管理入口仍可编辑。

## 验证

- 记忆 TS/CJS 与 builtin memory tools：226/226 通过，包含中文抽取场景、原话校验、过期、更新历史、真实源文件队列、来源失效、画像文档、取消与撤销权限、提交前会话变更、引用幂等、恢复后不再合并。
- chat 域全量：2727/2727 通过。
- `npx tsc --noEmit`：通过。
- 改动相关 ArkTS lint：0 error；大范围检查有 1390 条 warning，最后 guard/辅助调用/抽取检查 0 error、162 warning。warning 主要为此独立检查器跨包解析得到的 any；最终 ArkTS 编译通过。
- 最终 Release 构建：`node scripts/harmony-inventory/build-harmony-app.mjs`，BUILD SUCCESSFUL，35.626 秒。
- `sign-hap.sh` 签名校验通过。覆盖安装使用项目既有 AGC 签名包 `harmony/entry/build/default/outputs/default/entry-default-signed.hap`，与模拟器已安装应用签名一致。
- 模拟器 `127.0.0.1:5555`、bundle `app.amber.deepread`：最终包覆盖安装与启动成功。未安装到实体设备。

## UI 证据

截图使用临时 Debug 验收数据，覆盖活跃 4 条、归档/过期 2 条、恢复后活跃 5 条，独立原话、supersedes、画像来源、最近 8 条变更记录和深色列表。

- [浅色活跃列表](active-light.jpeg)
- [归档和过期恢复入口](archive-light.jpeg)
- [恢复后列表](restored-light.jpeg)
- [详情与独立用户原话](detail-light.jpeg)
- [变更记录](changes-light.jpeg)
- [用户画像与来源文档](profile-document-light.jpeg)
- [深色列表](active-dark.jpeg)
- [最终安装包与恢复后的空列表](final-empty-light.jpeg)

验收前备份了三份源文件；恢复日志确认 `restore done`，实际界面回到原始 0 条活跃记忆。原来浅色设置已恢复。临时 fixture 模块和 EntryAbility 入口已删除，最终包不包含该入口。没有删除或替换用户真实记忆。

## 边界

真实模型供应商的抽取质量、长期画像质量、云端工具端到端未验证；模型响应在测试中受控，UI 使用验收数据，不能等同于真实供应商验收。未新增 P2 向量检索。

初始工作区已有大量未提交改动。本轮保留原有工作，最终安装包由整个当前工作区构建，不应解释为仅包含本轮记忆改动；未执行提交、推送或创建 PR。
