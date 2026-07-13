# ADR-0007: Novel creation owns project and branch state

**Status**: Accepted
**Date**: 2026-07-12

## Decision

「小说创作」是独立于普通聊天的一级领域。它拥有小说项目、项目设定、创作 Session、正文候选、章节、剧情分支、事件记录、当前状态摘要和章节版本。普通 `Conversation` 与 `IOSConversationStore` 保持不变，也不作为小说 Session 或小说状态的权威存储；小说状态同样不写入全局 Assistant、Memory、Lorebook 或 Workspace。

正文生成先产生小说创作 Session 中的候选内容。只有用户执行「收录正文」后，小说创作领域才提交章节内容、状态更新和可 Fork 的创作节点。现有 `MessageNode` sibling variant 继续表示普通聊天中的单条回复变体，不参与剧情分支。

## Rationale

把小说数据放进普通会话或全局 Lorebook 虽然接线更快，但无法隔离多个小说与平行剧情，也会让未采纳草稿、重新生成和旧会话快照污染人物经历及剧情状态。独立所有权让项目设定与分支状态分权，并使收录、撤销、Fork、导入导出和失败恢复拥有单一权威写入者。

## Consequences

- 小说上下文通过专用接口编译，并复用现有 provider 解析与流式协议；不复用普通聊天存储、Memory 注入或 Workspace 写入路径。
- 项目设定由分支共享，分支状态在 Fork 后独立。
- 讨论和未收录正文候选不改变小说状态。
- 第一版不支持自动合并剧情分支，也不允许多个 Session 并发写入同一分支。
- 第一版按单 iOS 进程、单共享 repository/module environment 运行；多个 repository 实例或多个进程不得同时写同一小说存储根。
