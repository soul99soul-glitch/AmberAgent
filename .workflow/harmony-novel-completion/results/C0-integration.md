# C0 Integration

## Accepted

- UI review：共享 MessageRow 增加 host capability/slot 契约，禁止无效 Chat action。
- UI review：Timeline 成为唯一 gutter owner，冻结所有消息/卡片/输入器左右基线。
- UI review：三职责各有带标签的模型摘要/入口和失效重选状态。
- UI review：补齐候选、审稿、代笔、后台恢复的 durable UI 状态矩阵。
- UI review：主要交互以 44vp/AX 证据验收，暗色禁止浅色硬编码，长文本覆盖工具/审稿/diff。
- Logic review：唯一 canonical 富消息协议下沉到 DeepRead，Chat 重导出；旧 kind schema
  只迁移读取并停止生产，避免 HAR 环和第三套 schema。
- Logic review：state sync/next plan 移到 commit 前，全部 artifact 一次原子写入。
- Logic review：原子 run claim/lease/execution token 前移到 C5 workflow domain，C6 仅消费。
- Logic review：mutating Novel tool 增加 toolCallId/commandId 幂等 receipt。
- Logic review：明确 project-global 与完整 branch state 作用域，合同/事实/review/receipt 不串分支。

## Rejected

- 未接受“首轮就做多审稿席位/通用 event sourcing”等可选扩展；当前只实现三职责与
  确定性单 reviewer 链，后续只有真实产品合同才扩展。

## Verification Evidence

- Chat: 2012 passed, 0 failed。
- DeepRead: 657 passed, 0 failed。
- AGC build: BUILD SUCCESSFUL。
- Device `5MT0225B12013017`: install/launch/snapshot 成功。
- Screenshot: `/tmp/c0-baseline.jpeg`。

## Commit

本文件与 C0 计划纠偏同一提交；SHA 以该提交的 Git 记录为准。
