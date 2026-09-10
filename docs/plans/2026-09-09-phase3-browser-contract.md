# Phase 3 WebMount W08/W09 实施契约

**实施更新（2026-09-09）**：Phase2 已完成。本文保留设计依据；实际接口以 `WebMountSessionOwner.kt` 为准，分工以执行台账为准。W08 A 负责 owner/primitives/popup/DI，W08 C 负责页面/卡片/路由；W09 B 负责工具、回执、站点确认及父 run 接线。主任务统一构建并运行真实 WebView 验证。

## 现有可复用点

- `SessionHandle.kt:39-61` 和 `WebViewPool.kt:38-151` 已有 live WebView/session 生命周期；`WebViewPool` 的 LRU 会销毁 session，故 owner 必须把“可重开”元数据放在 pool 外。
- 工具已经经过 `WebMountPrimitiveShared.kt:19-50` 的 `WebMountDeps.track`，`ChatService.kt:3348-3365` 会以 `AgentToolActivityStore.withConversation` 包住每次 foreground tool execute。`LocalTools.kt:128` 已接收 `conversationId`，但 `:168-186` 调用 WebMount 时尚未把它传入。
- `ChatService.kt:3128-3132` 的 `createRunTools` 确实持有 `conversationId`、可选 `runId`；但 `AgentToolDispatcher.kt:281-287` 的 `Tool.execute` 只收 JSON，不能在工具内部推断 `runId`。AGENT lease 必须携带真实 `conversationId` 与 `runId`，沿现有 `LocalTools.getTools(..., conversationId)`/`WebMountPrimitiveTools.getTools(...)` 的窄参数传递，禁止新增全局 ThreadLocal。
- 当前 WebMount 可见入口是 `SessionHomePage.kt:233-240` → `Screen.SettingExperimentalWebMount`，注册在 `RouteActivity.kt:724-726`。`Screen.WebView`（`RouteActivity.kt:615-617`）创建独立 WebView，不能用于 pooled session 接管。复用 `ChatPage.kt:513-542,695-713` 的 conversation-scoped sandbox 位置和 `SandboxActivitySheet` 的卡片交互模式。
- 持久化沿 `UserSiteRegistry.kt:11-41,118-226` 的专用 `SharedPreferences` JSON 模式；不要为本阶段改 Room schema，也不要复用只存 3 秒的 `WebMountPageSnapshotCache`。

## 文件所有权

| 实现者 | 负责文件（可新增） | 明确不改 |
|---|---|---|
| **W08 A：owner / primitives / visible UI** | `feature/webmount/primitives/SessionHandle.kt`、`WebViewPool.kt`；新增同目录 `WebMountSessionOwner.kt`（元数据、owner/lease API、专用 prefs store）；`feature/webmount/login/WebMountLoginController.kt`（popup/人工接管）；新增 `feature/ui/pages/webmount/WebMountSessionPage.kt`、`feature/ui/components/webmount/WebMountTaskCard.kt`；`ChatPage.kt`/`ChatInput.kt` 的单一卡片接入点；`RouteActivity.kt` 新 route/entry；`WebMountModule.kt` wiring | W09 的 tool schema、receipt 字段含义、`ToolRegistry` approval policy |
| **W09 B：tools / receipts / site-confirm** | `WebMountPrimitiveShared.kt`（仅扩充 deps/owner gate）；`WebMountPrimitiveTools.kt`；`WebMountInteractionTools.kt`、`WebMountNavigationTools.kt`、`WebMountTabsTools.kt`、`WebMountSiteTools.kt`；`feature/tools/api/.../ToolRegistry.kt:394-405` site-add policy；receipt helper 放在上述 shared 文件或一个 `WebMountActionReceipt.kt` | W08 的 pool/lease 实现、session prefs、popup/login、Composable、`RouteActivity` |

A 先合入可编译的 owner API 和假数据/单测；B 只依赖该 API，不读取 prefs、不直接调用 `WebView.destroy`。如必须改 `LocalTools.kt` 传 `conversationId`，由 B 提交该一处窄 wiring，A 不改工具注册。

## 窄接口（伪 Kotlin，保持在 primitives 包）

```kotlin
enum class WebMountOwner { NONE, AGENT, HUMAN }

data class WebMountSessionMetadata(
    val sessionId: String,
    val conversationId: String?, val runId: String?,
    val redactedUrl: String?, val title: String?, val status: String,
    val owner: WebMountOwner, val leaseExpiresAtMs: Long?,
    val needsReopen: Boolean, val lastActivityMs: Long,
)

interface WebMountSessionOwner {
    val sessions: StateFlow<List<WebMountSessionMetadata>>
    fun metadata(sessionId: String): WebMountSessionMetadata?
    suspend fun acquire(
        sessionId: String, actor: WebMountOwner,
        conversationId: String? = null, runId: String? = null,
    ): WebMountLeaseResult
    fun release(leaseId: String, reason: String)
    fun markActivity(sessionId: String, leaseId: String?, url: String?, title: String?)
}
```

`WebMountLeaseResult` 携带 `leaseId + SessionHandle` 或结构化失败（`owner_conflict`、`session_missing`、`needs_reopen`）。A 负责 CAS/lease、过期回收、进程重启后的 `needsReopen`；不增加额外 opt-in 开关。B 的每个读/写/导航 tool 必须先 `acquire(AGENT, ...)`，失败直接返回 `owner_conflict`，不得绕过 gate。UI 默认只读观看同一 pooled WebView；显式“接管”才申请 `HUMAN`，离开页面或点击“交还 Agent”释放 lease。重建必须显式 reopen，不能把空白 WebView 当作恢复成功。metadata 只持久化 session id、脱敏 origin/title/status/owner/lease/会话与可选 run 关联；不持久化 cookie、DOM、snapshot、live WebView 或 URL query secret。

## W09 返回协议与 site-confirm

每个有副作用的 action 返回统一 JSON：`session_id`、`action_id`、`dispatched`、`status`（`dispatched|verified|unknown|failed`）、`snapshot_id_before`、`snapshot_id_after`、`page_changed`、`goal_verified`、必要时 `owner_conflict`/`stale_target`。B 可复用 `WebMountInteractionTools.kt:449-489` 的前后语义状态和 `bridge.js:455-513` 的 `snapshot_id`；snapshot 过期时禁止静默重试或继续点击，返回 `stale_target` 让上层重新提取。

`wm_site_add` 必须在确认后写入 `UserSiteRegistry`：调整 `WebMountSiteTools.kt:196-255` 与 `ToolRegistry.kt:394-405`，使新增站点出现明确确认（`needsApproval=true`、`autoApprovable=false`，重复添加保持幂等）。B 不改变 site store 的持久化实现。

## 集成顺序与验收

1. Phase2 gate 通过后，A 先落 owner/metadata/store、pool popup/接管、session route/card，并提供 `StateFlow` 与 lease 单测；B 以该 API 接入 tool gate、receipt、site-confirm。
2. A 验收：进程杀死后 persistent session 仍显示卡片且标记 `needsReopen`；同一 session 不能同时被 HUMAN/AGENT 占用；从当前 Chat 卡片进入 `Screen.WebMountSession(sessionId)` 后使用原 pooled handle；设置页仍只负责 station/login 管理。
3. B 验收：所有会改变页面的工具都受 lease；旧 snapshot 返回 `stale_target` 且无副作用；receipt 区分已发出、已验证、未知与失败；可读页面或 DOM 变化本身不能证明业务目标成功；site add 每次未确认不得写入 registry。验证在实现阶段执行，本准备轮不运行测试。

## 当前统一验证

首轮生产编译捕获页面 icon 扩展 import 与 receipt helper 参数类型错误，已交原 owner 定点修复；未将中间失败计为通过。真实 WebView 用例在 `app/src/androidTest/java/app/amber/feature/webmount/WebMountParityDeviceTest.kt`，受控网页与设备命令见 `scripts/webmount-parity/README.md`。最终结果记录在执行台账。
