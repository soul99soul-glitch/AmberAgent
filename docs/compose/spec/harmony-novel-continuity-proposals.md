---
feature: harmony-novel-continuity-proposals
status: delivered
updated: 2026-09-11
branch: harmony-preview
commits: (working tree; not committed)
---

# Novel 连续性审计 + 设定提案卡

## Report

**What was built** — deepread：`runContinuityAudit`（stateSync 模型 + 严格 JSON issues）、`parseSettingProposals`（fenced/裸 JSON）、`NovelCreation.ingestSettingProposals`（同 source pending 替换 + `proposal_create` mutation）。chat：`novel_continuity_audit` / `novel_ingest_setting_proposals` 两工具；entry AppContainer port 接 NovelCreation。

**Verification** — deepread tsc + **775/775**；chat tsc + **2074/2074**；lint AppContainer 0 defects。

**Journey log** —
1. 结构 checkConsistency 与 LLM audit 分离。
2. needsApproval 必须显式 allowsAutoApproval=false，否则默认 auto 可批。
3. mutation kind 扩 `proposal_create`。

## Tasks
- [x] T1 audit 域层+测
- [x] T2 proposal 解析/入库+测
- [x] T3 Agent 工具 + AppContainer 接线
- [x] T4 全量验证
