---
feature: harmony-novel-proposal-audit-ui
status: delivered
updated: 2026-09-11
branch: harmony-preview
commits: (working tree; not committed)
---

# Novel 提案/审计 UI 卡片

## Report

**What was built** — 提案卡（`NovelSettingProposalCard` + 采纳/拒绝）本已存在。本周期补 **LLM 深度审计 UI**：章节目录栏「深度审计」→ `runContinuityAudit`；无 stateSync 模型时诚实提示；结果列表（severity 色点 + chapterRef + summary + suggestion）；loading/空/错误三态。

**Verification** — lint NovelWorkspacePage **0 defects**；deepread **775/775**；chat **2074/2074**。

**Journey log** —
1. 先盘点：提案 UI 与结构一致性检查已在，避免重做。
2. LLM 审计与结构检查分入口（「一致性」vs「深度审计」），语义不混。

## Tasks
- [x] T1 提案卡（既有）
- [x] T2 深度审计入口 + 结果列表
- [x] T3 全量测试 + lint
