---
feature: harmony-notify-cron-headless
status: delivered
updated: 2026-09-11
branch: harmony-preview
commits: (working tree; not committed)
---

# Harmony 通知接真 + Cron 无头运行

## Report

**What was built** — `AGENT_CRON_EXECUTION_AVAILABLE=true`；AppContainer 注入 `createEntryCronRunExecutor`（resolveChatRuntime → runChatTurn）；scheduler fire 状态机接通；`AgentCronTools` 四件重新暴露；启动 reschedule 不再跳过。通知：`notifyGenerationFailed` + `notifyCronRunning|Completed|Failed`。导出：ChatList 长按导出 Markdown 文件（DocumentViewPicker，失败回落 filesDir/exports）。WebMount 确认已在 ChatPage 接线。

**Verification** — chat tsc clean + **2072/2072 PASS**；deepread **767/767 PASS**；lint AppContainer/ChatList/NotificationNotifier/AgentCronStore **0 defects**。assemble 仍 PRE-EXISTING hvigor。

**Journey log** —
1. Cron 状态机在 scheduler；executor 只跑 ChatTurn，不重复 mark*。
2. 无头 run 依赖 resolveChatRuntime；无 Provider 时 throw + notifyFailed。
3. 导出文件先 picker，失败落应用目录。
4. WebMount/Search 卡在本轮前已存在——盘点优先。

## [S1] Problem

通知缺失败态；Cron 无头执行未接线。

## [S2] Design

scheduler fire → prepare → markStarted → executor(ChatTurn) → markCompleted/Failed → notify → scheduleNext。

## [S3] Out of Scope

WorkScheduler 进程死后唤醒；Push；通知渠道美化。

## Tasks

- [x] T1: 生成失败 + Cron 三态通知 (covers: S2.1)
- [x] T2: Cron executor 注入 + 到期 fire + 工具暴露 (covers: S2.2)
- [x] T3: AGENT_CRON_EXECUTION_AVAILABLE=true + 启动 reschedule (covers: S2.2)
- [x] T4: 全量测试 + lint (covers: S2.3)
- [x] T5: 导出 Markdown 文件 picker (covers: S2.1)
