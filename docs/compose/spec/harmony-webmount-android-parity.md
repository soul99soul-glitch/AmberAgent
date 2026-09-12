---
feature: harmony-webmount-android-parity
status: delivered
updated: 2026-09-11
branch: harmony-preview
commits: (working tree; not committed)
---

# WebMount 对齐 Android（MVP 加深）

## Report

**What was built** — Session 逻辑 tab（open/select/close/list，main 不可关）；导航 back/forward/reload；`wm_wait`（url/title/selector/text + timeout 轮询）；extract 输出 password/token 脱敏为 `***`；click 已支持 ref/selector。新增 8 件工具接进 `createWebMountTools`。

**Verification** — chat tsc clean + **2074/2074 PASS**（含 webmount_wait 2 测）；deepread **767/767**；lint WebMountSession/Tools **0 defects**。

**Journey log** —
1. 单 WebView 下 tab 是逻辑簿记 + select 时 load 回放 url，不是真多进程。
2. wait 条件纯逻辑抽到 chat 域可单测；entry 只轮询 eval。
3. 脱敏在 extract JS 内做，避免敏感 value 进 tool 结果。

## [S1] Problem
单会话 8 工具，缺 wait/多 tab/导航，Agent 点完不知是否成功。

## [S2] Design
见 Report；站点授权边界不变。

## [S3] Out of Scope
飞书 OAuth；远程 desktop backend；视觉模型读图。

## Tasks
- [x] T1: tab 状态机 + wm_tab_* (covers: S2.1)
- [x] T2: wm_wait + 域单测 (covers: S2.1)
- [x] T3: wm_back/forward/reload (covers: S2.1)
- [x] T4: extract 脱敏 (covers: S2.1)
- [x] T5: ChatPage 工具集 + lint + 测试 (covers: S2.3)
