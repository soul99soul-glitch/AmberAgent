# P2 收口：长篇工具 + 边角

> Status: **COMPLETE (MVP ship)**  
> Date: 2026-07-31  
> Completes remaining items from parity plan M5/M6 (pragmatic MVP scope)

## Stages

| ID | Scope | Notes |
|----|--------|------|
| **P2-B** | 批量整章润色 | 串行 polish→adopt；漂移 skip |
| **P2-A** | 一致性审计 MVP | 单次整稿 audit JSON；报告 UI |
| **P2-Disc** | discardedAt | 废弃/恢复章；目录隐藏 |
| **P2-Keep** | keep-both 导入 | 冲突时第三选项；仅 remap projectID（对齐 iOS 简化） |
| **P2-Stream** | 流式完成闪烁弱化 | terminal 清 streaming 前短保持 |
| **Docs** | 状态/gap 全量回写 | 标 COMPLETE |

Closed in deferred pass (2026-07-31):
- LLM auto discussion distill (prefill sheet; user confirms)
- Multi-chunk manual sync (chapter-sized model passes; single commit)
- Undo archive cursor rewind + restore source candidate on undo head
- Stream quiet window unified (120ms)

Still out of scope (XL / non-goals):
- Full polish transaction ledger machine
- Durable multi-chunk resume ledger (`pendingOperations.manualSyncProgress`)
- Perfect iOS presentation pacer
