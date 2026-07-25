## Review

### 条目9 — Grok Web 裸 EOF 被当成成功：**确认成立**

- JS 端 reader 循环结束后无条件 `post("complete", {})`：`iosApp/iosApp/IOSGrokWebProvider.swift:525`(`while(true){ … if (result.done) break; … } … post("complete", {});` at 516-525)。
- Swift transport 对 complete 无条件成功：`IOSGrokWebProvider.swift:430` `case "complete": finishStream(throwing: nil)`。
- 是否收到协议终态只有 parser 知道：`IOSGrokWebProvider.swift:280`(`[DONE]`)、`:297`(`finalMetadata != nil || isSoftStop`),`isFinished` 仅用于让 `onLine` 返回 true 触发 abort(:596-597 `return frame.isFinished`),没有任何地方记录"曾收到终态帧"。
- `streamTokens`(:567-604)在 transport 正常返回后直接结束，不校验终态；与 PROJECT_STATE 已修复的 OpenAI/Claude"终态前断开即报错"(`OpenAIStreamTerminalState`)形成明确不对称。审计引用行号 272/495 与现状(parser ~272-303、streamScript ~495-526)吻合。

**修复方向评价**:合理。给 transport/streamTokens 增加"是否见过终态帧"标志，裸 EOF 抛错，与 OpenAI/Claude 的 honest-terminal 模式同构，改动小。

### 条目13 — 审批卡/ask_user 无法跨冷启动恢复：**确认成立**

- pending 全是内存私有变量：`ChatGenerationCoordinator.swift:415-422`(`private var pendingMemoryToolApproval … pendingAskUserToolApproval: ChatPendingToolApproval?`);`pauseForApproval`(:1608-1660)只写这些变量 + UI bindings + Watch publish，无任何持久化。
- `ChatPendingToolApproval` 结构(:329)含 providerSetting/params/runId 等完整 descriptor，但没有对应的持久存储。
- `IOSRunRecovery.recoverInterruptedRuns`(`IOSRunRecovery.swift:19-47`)只做 `listUnfinished` → `markInterrupted`;run 状态在 Chat 路径只写 `"running"`(`ChatGenerationCoordinator.swift:496/603`)，暂停审批时连 `"awaiting_permission"` 都不落库(grep 无 `awaiting` 写入点),冷启动后也没有任何代码从 interrupted run 或持久消息重建审批卡(`restorePending/rebuildPending` 在 Chat 路径不存在，仅 Novel/ToolEngine 有同名概念)。注释自称"re-show pending cards"但无实现。

**修复方向评价**:方向合理（持久化 pending descriptor 并在冷启动重建），但范围不小；可接受的中间态是恢复扫描时把悬挂 tool call 诚实收口为 cancelled/failed 而不是让卡片凭空消失。建议先落诚实终态，再做完整恢复。

### 条目14 — 五类批准后工具未登记 foregroundToolExecutionTask:**确认成立**

- Search/WebMount/Workspace/iSH/MCP 五个 finisher 直接 `await toolRuntime.finish*Approval(...)`，无登记：`ChatGenerationCoordinator.swift:1683-1697`(search)、:1699-1713(webMount)、:1715-1729(workspace)、:1731-1745(ish)、:1747-1761(mcp)。
- 对照：Council 正确登记 `foregroundToolExecutionToken/Task`(:1770-1773)；首个工具执行点也登记(:1570-1574)。
- Stop(:693 `cancelForegroundToolExecutions()`）与后台交接（:819）只取消已登记 task；未登记的五个 await 不会被取消，工具副作用（搜索、MCP 调用等）继续完成，而 `guard currentRunId == pending.runId else { return }` 使其结果被丢弃。`foregroundToolExecutionTask` 定义在 :425-426。memory 审批是同步本地写，不在此列，与审计口径一致。

**修复方向评价**:合理且低风险——直接复用 Council 已有的 token+Task 登记模板套到五个 finisher 即可，无新抽象。

### 条目15 — BG expiration 先交还系统任务再保存终态：**确认成立**

- `IOSChatBackgroundGenerationCoordinator.swift:474-486`:expiration handler 内顺序为 `claimSystemTaskCompletion()` → `self.finish(requestId:)`(:477;`finish` 默认 `shouldRemovePayload: true`，见 :1160-1172，先删 payload)→ `backgroundTask.setTaskCompleted(success: false)`(:478)→ 之后才 `case .persistFailure: await self.persistExpirationFailure(...)`(:480-486)。
- `persistExpirationFailure`(:767-794）是真正的 durable 写（`saveBackgroundCompletion` + `recordRun "failed"`)，位于 `setTaskCompleted` 之后，系统可在其完成前挂起进程；且 payload 已被 `finish` 无条件删除，`persistExpirationFailure` 里 `if didSave { removePayload }` 形同虚设，保存失败时重试材料已丢失。
- 部分缓解存在：冷启动 `IOSRunRecovery` 会把残留 "running" run 标 interrupted（诚实状态），但会话内失败气泡与重试 payload 不可恢复，durable terminal 缺口成立。审计引用 :462 与现状 handler 起点（:463-464）吻合。

**修复方向评价**:合理——把 `persistFailure` 的 await 保存挪到 `finish/setTaskCompleted` 之前（expiration handler 的短窗口内先做落盘），保存失败保留 payload。需注意 Apple 对 expiration handler 快速返回的要求，保存须轻量；现有 `terminateInFlightSave`/`resolveExpiredInFlightSave`(:617-626)说明 owner 框架已具备，重排顺序即可。

### 条目16 — 后台多工具轮过期丢失已完成前轮：**确认成立**

- 每轮开始清空快照：`IOSChatBackgroundGenerationCoordinator.swift:546-548` `onAssistantTurnStarted: { assistantTextSnapshot.replace(with: "") }`（审计引用 :540 即此回调区）。
- 过期路径只取当前 partial:`persistExpirationFailure(job:requestId:rawMessage:partialAssistantText: assistantTextSnapshot.text)`(:483-486),`preservedGeneratedSuffix` 用默认值 `[]`。
- `failedMessages`(:1094-1112）支持 `preservedGeneratedSuffix`，正常 `fail(...)` 路径也有该参数（:672-676)，唯独 expiration 路径不传；前几轮已执行工具的 assistant/toolCall/tool output 消息只存在于 engine 工作副本，不落 displayMessages，过期后全部消失，重试会从原始 uploadMessages 重放 → 已执行工具副作用可重复。

**修复方向评价**:合理。与 `assistantTextSnapshot` 同构地维护"已完成轮次 suffix 快照"（每轮结束追加），expiration 时传入现有 `preservedGeneratedSuffix` 参数即可，无需新状态机。

### 条目17 — Watch 报失败后操作仍可能迟到执行：**确认成立**

- 实时发送失败时排队 + 同时报失败：`iosApp/SharedWatch/WatchConnectivityBridge.swift:190-193`——`sendMessage` 的 errorHandler 内 `_ = transport.transferUserInfo(message)`(:190）紧接着 `reportActionFailure(request, message: "发送到 iPhone 失败…")`(:192)。transferUserInfo 是可靠排队通道，手机稍后收到会真正执行（`apply` → `handleWatchAction`,:233-244)。
- 不可达分支：`WatchConnectivityBridge.swift:195-196` 只排队不立即报错；超时任务(:205-213）稍后报"iPhone 响应超时"，而排队动作仍可能在超时后送达执行。用户看到失败重试 → 同一意图产生两个 request 动作。
- 手机端无 TTL 检查：`WatchTaskActionRequest.createdAt`(`WatchTaskModels.swift:94`）存在，但 `WatchTaskCoordinator.handleWatchAction`(`WatchTaskCoordinator.swift:152`）及整个 bridge 只校验 runId/decisionId(:210-217),grep 无任何 `timeIntervalSince/stale/TTL` 检查。
- PROJECT_STATE 2026-07-24 修的"action 失败/超时按 requestId 解除 sending"不覆盖本条：sending 态清理≠阻止排队投递。

**修复方向评价**:合理。errorHandler 报失败后不应再 transferUserInfo（或改为"已排队"语义而非失败）；手机端按 `createdAt` 加 TTL 丢弃过期动作，最好再按 requestId 去重，既防迟到又防用户重试造成的重复副作用。

---

### 总体结论

六条全部以当前工作区代码复核**成立**，PROJECT_STATE 记录的 2026-07-18~24 修复（OpenAI/Claude 终态、后台/Watch owner 收口、ask_user 一等 pending、Bool 回包、backgroundToolExecutors 等）均未覆盖这六条；审计引用行号与现状基本吻合（±几行偏移）。修复建议方向全部合理，且多数可复用既有机制（Council 登记模板、`preservedGeneratedSuffix` 参数、OpenAIStreamTerminalState 模式），不需要新状态机。