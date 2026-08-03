# P1-C：讨论归档 MVP + 注入上下文可见

> Status: **COMPLETE (S1+S2 reviewed & fixed)**  
> Date: 2026-07-31  
> Parent: `docs/plans/2026-07-30-novel-android-ios-parity-plan.md` P1-C  
> Workflow: 阶段 → 子代理审核 → 修完再进下一阶段

---

## 目标

1. **注入可见**：用户能看到最近一次生成「吃到了」哪些上下文（资料 / 状态 / 会话 / token）。  
2. **讨论归档 MVP**：长讨论可归档为 decisionLog 资料 + 推进 archiveCursor；之后注入跳过已归档闲聊。

本阶段 **不做**：自动定时归档、完整 StructuredModelExecutor 多任务机、supersede 决定链、iOS 全量校验器对齐。

---

## 阶段

| 阶段 | 名称 | 依赖 | 审核重点 |
|------|------|------|----------|
| **S1** | 注入上下文只读面板 | 已有 injectionReceipts | 最新 receipt、token/section 可读、无假数据 |
| **S2** | 讨论归档 MVP | 无硬依赖 S1 | schema 兼容、cursor 推进、decisionLog 写入、注入跳过归档消息 |

```text
S1 ──审核──> S2 ──审核──> docs
```

---

## S1 — 注入面板

### 做

- VM：`latestInjectionReceipt()` 取当前分支最新 generation receipt（非 factTransaction）  
- UI：Composer 上方「本次上下文」可展开：prompt 版本、预估 tokens、sections 标签列表、资料 included/excluded 计数  
- 无 receipt 时文案「尚无生成记录」

### 不做

- 改 injection planner 算法  
- 预览全文正文（只 label + tokens，避免过长）

### 验收

| # | 项 |
|---|-----|
| I-1 | 生成一轮后可打开面板看到 sections / token |
| I-2 | 旧项目无 receipt 不崩 |

---

## S2 — 讨论归档 MVP

### Schema（向后兼容）

- `NovelMaterialKind.DecisionLog`  
- `NovelSessionRecord.archiveCursor` / `discussionArchives`（optional 默认空）  
- `NovelDiscussionArchiveRecord`  
- `NovelCheckpointKind.DiscussionArchive`  
- `NovelOperationKind.ArchiveDiscussion`  
- `NovelOutcome.DiscussionArchived`  

### 领域语义（对齐 iOS 简化）

- 取 cursor 后讨论消息（DiscussPlan + user/discussion）  
- 用户确认 summary + ≥1 decision（topic/content）  
- 每条 decision → 新 material `DecisionLog` + revision（injection Always）  
- 写入 branch.overrideRevisionIDs  
- 推进 archiveCursor；追加 archive 记录 + discussionArchive checkpoint（不推进 working 正文）  
- configRevision +1  

### 注入

- Session messages：仅注入 `sequence > archiveCursor`  
- DecisionLog materials：Always 注入  

### UI

- 创作菜单 / Composer：「归档讨论」  
- Sheet：摘要 + 决策列表（可增删改）→ 确认  
- Living「更多」：展示决策资料  

### 验收

| # | 项 |
|---|-----|
| A-1 | 归档后 cursor 前进；decisionLog 出现在资料 |
| A-2 | 再次生成 injection 不包含 cursor 前消息 |
| A-3 | 无新讨论时禁用/报错 |
| A-4 | 旧文档无字段可解码 |

---

## 状态板

| 阶段 | 实现 | 测试 | 审核 | 修复 | 完成 |
|------|------|------|------|------|------|
| S1 注入面板 | ☑ | ☑ | ☑ | ☑ | ☑ |
| S2 讨论归档 | ☑ | ☑ | ☑ | ☑ | ☑ |
| 文档 | ☑ | — | — | — | ☑ |
