# Council Room 合并审查报告

> 审查范围：`feature/council-host-orchestration-tools` 合并 `main` 分支的 7 个文件（`9548a2c7a`）
> 审查重点：逻辑闭环、调用链路完整性、竞态条件、边缘情况处理

---

## 1. 状态机完整性

### 状态定义与转换

```
IDLE → EXPLORING/DEBATING → FINALIZING → FINALIZED
       ↓                      ↓
       CANCELLED (cancel=true)  ← close() 强制终止
       FAILED (异常)
       INTERRUPTED (ask_user HITL 暂停)
```

- `CouncilRoomStatus.running` / `terminal` 扩展属性正确覆盖所有活跃/终态
- `close()` 终态判定：`FINALIZING || synthesis.isNotBlank() → FINALIZED`，否则也 `FINALIZED`（graceful close 兜底）
- `INTERRUPTED` 被 `resumeAfterUserAnswer` 正确翻转回 `statusForMode(room.mode)`

### 发现的问题

**问题 1：异常后 room 未标记 FAILED，可能卡在非终态**

`runAutoOrchestration` 的异常路径只 `Log.e` 然后协程死亡，没有把 room 标记为 `FAILED`。如果 `close()` 之前没调用，room 会卡在 `EXPLORING/DEBATING` 且 `autoRunConversationIds` 已移除，后续 `maybeStartAutoRun` 可以重新启动一个已损坏的 room。

位置：`CouncilRoomManager.kt:958-964`

```kotlin
.onFailure { error ->
    if (error !is CancellationException) {
        android.util.Log.e(TAG, "Council auto-run failed", error)
        // 缺失：标记 room 为 FAILED
    }
}
```

---

## 2. 调用链路完整性

### 主链路

```
userMessage → maybeStartAutoRun → runAutoOrchestration
    ↓
    runPreTopicResearch (FULL mode) → autoAssembleSeats → classifyMode → generateHostOpening
    ↓
    for round in 1..totalRounds:
        for guest in guestIds:
            generateGuestTurn → handleInterjections
        ↓
        runHostReviewTurn (FULL mode, round < totalRounds)
            ↓ ask_user? → INTERRUPTED → awaitUserAnswer → resumeAfterUserAnswer
    ↓
    handleInterjections → synthesize (inline, awaited)
```

### 发现的问题

**问题 2：`runHostToolTurn` 的 `AskUser` 处理与 `runHostReviewTurn` 的 `AskUser` 处理不一致**

`runHostToolTurn`（pre-topic research 阶段）处理 `AskUser` 时：
- 先 `completeMessage` 把消息标记为 COMPLETED
- 再 `mutate` 把 `kind = ASK_USER` 和 `status = INTERRUPTED`
- 然后 `awaitUserAnswer`

`runHostReviewTurn`（end-of-round review 阶段）处理 `AskUser` 时：
- 只返回 `question` 字符串
- **不**持久化 ASK_USER 消息
- 调用方 `runAutoOrchestration` 自己持久化

**风险**：`runHostToolTurn` 的 `completeMessage` + `mutate` 是两次写操作，如果 `mutate` 时 room 被 close，`awaitUserAnswer` 仍然会被调用，但 `pendingAskUser` 的 deferred 会被 `close()` 取消。room 状态是 COMPLETED 的 host message 但没有 `ASK_USER` kind，timeline 会显示为普通 host 消息而不是 answer card。

位置：`CouncilRoomManager.kt:1813-1849` 和 `1897-1903`

**问题 3：`resumeAfterUserAnswer` 的竞态窗口**

```kotlin
val deferred = jobsLock.withLock { pendingAskUser.remove(conversationId) }
    ?: return CouncilRoomOpResult.Err("no_pending_question", ...)
val result = mutate(conversationId) { ... }  // ← close() 可能在这里发生
// close() 会取消 runAutoOrchestration 的 job，但 deferred 仍有效
deferred.complete(answer)  // ← 恢复已取消的协程？不会，但代码路径混乱
return result
```

`close()` 在 `deferred = jobsLock.withLock { ... }` 和 `deferred.complete(answer)` 之间被调用时：
- `close()` 的 `askDeferred?.cancel()` 是 null（因为 deferred 已被 remove）
- `resumeAfterUserAnswer` 的 `deferred.complete(answer)` 成功
- 但 `awaitUserAnswer` 的协程已被 `close()` 的 job cancellation 杀死
- `deferred.await()` 抛出 `CancellationException`，被 `runAutoOrchestration` 的 `runCatching` 捕获

行为正确但代码路径混乱，建议 `mutate` 失败时跳过 `awaitUserAnswer`。

---

## 3. 竞态条件与并发安全

### 锁结构

- `jobsLock`：保护 job 注册表、closing gate、auto-run dedupe、ask_user deferred
- `Mutex`（per-conversation）：保护 room 状态变更（`mutate`/`mutatePreflight`）

### 评估

双重锁结构正确，`mutate` 串行化了所有写操作。`peekRoom` 不持有锁但读的是原子值，幂等检查安全。

### 发现的问题

**问题 4：`guestIds` 在循环期间失效**

`guestIds` 是循环开始时 `seeded.activeGuests.map { it.id }` 的快照。如果 `handleInterjections` 中的 `@mention` 触发了新 guest 的生成，这些新 guest **不会**在当前轮次发言。这是设计意图，但 `handleInterjections` 的 `generateGuestTurn` 是**额外**的 guest turn，不占用 `guestIds` 循环配额，所以不会重复。安全。

**问题 5：`handleInterjections` 的串行处理**

如果用户发送多条消息，`handleInterjections` 会逐条串行处理，每条都可能触发一次 guest turn。这可能导致用户消息积压，但 `watermark` 会逐条推进，不会丢失。安全。

---

## 4. 工具调用链路

### `AppCouncilHostToolProvider`

```
modelRunner.generateWithTools → streamText → MessageStreamAccumulator
    ↓
    toolCalls 检测 → executeBatch (autoApprove) → 循环下一轮
    ↓
    AskUser 拦截 → HostToolOutcome.AskUser
```

### 发现的问题

**问题 6：`ask_user` 工具的 `displayQuestion` 返回原始 JSON 字符串**

```kotlin
val askUser = toolCalls.firstOrNull { it.toolName == ASK_USER_TOOL_NAME }
if (askUser != null) {
    return HostToolOutcome.AskUser(
        rawPayload = askUser.input,
        displayQuestion = askUser.input,  // ← 原始 JSON 字符串！
    )
}
```

`askUser.input` 是模型生成的工具调用参数，通常是 JSON 如 `{"question": "你更关注什么？"}`。`displayQuestion` 直接用这个 JSON 字符串，用户会看到 `{"question":"..."}` 而不是问题本身。

位置：`AppCouncilHostToolProvider.kt:124-131`

**问题 7：`HOST_TOOL_NAMES` 保守，可能错过可用工具**

`HOST_TOOL_NAMES = setOf("search_web", "scrape_web", "time")`。如果用户配置了 `search_news` 等搜索工具但没有 `search_web`，`isAvailable` 返回 false，FULL mode 的 pre-topic research 被静默跳过。

位置：`AppCouncilHostToolProvider.kt:182`

---

## 5. UI 链路

### `CouncilTimelineTab` 与 `CouncilRoomVM`

### 发现的问题

**问题 8：`CouncilAskUserCard` 的 `answered` 状态是本地状态**

```kotlin
val answered = remember(msg.id) { mutableStateOf(false) }
```

这是本地状态，进程死亡/重组后丢失。如果用户回答了问题然后旋转屏幕，`answered` 重置为 false，answer card 重新显示输入框。但 `resumeAfterUserAnswer` 会追加一条 USER 消息，所以应该通过检查 `room.messages` 中是否有后续 USER 消息来判断是否已回答。

位置：`CouncilTimelineTab.kt:779-786`

**问题 9：`vm` 参数可为 null 时 `onAnswer` 无操作**

```kotlin
onAnswer = { answer -> vm?.resumeAfterUserAnswer(answer) }
```

如果 `vm == null`，用户点击提交没有反馈。但 `CouncilTimelineTab` 的调用方应该总是传 vm，除非在预览/测试。低风险。

---

## 6. 数据流与持久化

### `CouncilRoomStore` 契约（推断）

- `observeRoom` 返回 `StateFlow<CouncilRoom?>`，cold-load 从 SQLite
- `upsertRoom` 写入 SQLite 并更新 StateFlow
- `closeAndEvict` 原子地写入终态并移除内存缓存

### 评估

`mutate` 使用 `Mutex.withLock` 串行化所有写操作，`executor` 的 streaming callback 通过 `RoomMutationSink` 调用 `mutate`，所以所有写操作串行化。`peekRoom` 读的是原子值，幂等检查安全。

---

## 修复建议汇总

| 优先级 | 问题 | 位置 | 建议修复 |
|--------|------|------|----------|
| **高** | `askUser.input` 未解析 JSON | `AppCouncilHostToolProvider:130` | 解析 `question` 字段：`json.parseToJsonElement(askUser.input).jsonObject["question"]?.jsonPrimitive?.contentOrNull` |
| **高** | `answered` 本地状态丢失 | `CouncilTimelineTab:779-786` | 从 `room.messages` 推断：`room.messages.any { it.authorId == COUNCIL_ROOM_USER_ID && it.createdAtMs > msg.createdAtMs }` |
| **中** | 异常后 room 未标记 FAILED | `CouncilRoomManager:958-964` | `catch` 中 `mutateRoomOrNull(conversationId) { it.copy(status = CouncilRoomStatus.FAILED) }` |
| **中** | `mutate` 失败时仍 `awaitUserAnswer` | `CouncilRoomManager:1837-1848` | 检查 `mutate` 结果，失败时不 `await` |
| **低** | `HOST_TOOL_NAMES` 保守 | `AppCouncilHostToolProvider:182` | 扩展为所有搜索相关工具，或文档化限制 |

---

## 附录：关键代码路径

### `runAutoOrchestration` 主循环（`CouncilRoomManager.kt:986-1177`）

```kotlin
private suspend fun runAutoOrchestration(conversationId: Uuid) {
    val settings = settingsFlow.value
    val initial = peekRoom(conversationId) ?: return
    val totalRounds = initial.maxRounds.coerceIn(1, MAX_ROUNDS_CAP)

    // FULL mode: pre-topic research + roster assembly
    if (initial.activeGuests.isEmpty()) {
        runPreTopicResearch(conversationId, settings)
        autoAssembleSeats(conversationId, initial.objective, settings)
    }

    val seeded = peekRoom(conversationId) ?: return
    val guestIds = seeded.activeGuests.map { it.id }
    if (guestIds.isEmpty()) return

    // Interjection watermark
    val startWatermark = seeded.messages
        .filter { it.authorId == COUNCIL_ROOM_USER_ID }
        .maxOfOrNull { it.createdAtMs } ?: 0L
    jobsLock.withLock { interjectionWatermarks[conversationId] = startWatermark }

    // Auto-detect mode
    val userPickedMode = jobsLock.withLock { conversationId in userModeOverrideIds }
    if (!userPickedMode) {
        classifyMode(seeded, settings)?.let { detected ->
            mutateRoomOrNull(conversationId) { room ->
                if (room.mode == detected) room else room.copy(mode = detected, status = statusForMode(detected))
            }
        }
    }

    // Host opening
    val openingRoom = peekRoom(conversationId) ?: return
    if (!openingRoom.status.terminal) {
        resolveHostModelId(openingRoom, settings)?.let { hostModelId ->
            generateHostOpening(openingRoom, hostModelId, settings)
        }
    }

    for (round in 1..totalRounds) {
        val roundReady = mutateRoomOrNull(conversationId) { room ->
            room.copy(round = round, status = statusForMode(room.mode), ...)
        } ?: return

        for (gid in guestIds) {
            val current = peekRoom(conversationId) ?: return
            if (current.status.terminal) return
            val guest = current.participantById(gid) ?: continue
            if (guest.status == CouncilParticipantStatus.DISMISSED) continue
            executor.generateGuestTurn(...)
            handleInterjections(conversationId, settings)
        }

        // FULL mode end-of-round review
        if (settings.agentRuntime.modelCouncil.councilPowerMode == CouncilPowerMode.FULL && round < totalRounds) {
            val reviewRoom = peekRoom(conversationId) ?: return
            if (!reviewRoom.status.terminal) {
                val question = runHostReviewTurn(conversationId, reviewRoom, round, totalRounds, settings)
                if (question != null) {
                    // Persist ASK_USER message + INTERRUPTED + awaitUserAnswer
                    mutateRoomOrNull(conversationId) { r ->
                        r.copy(messages = r.messages + CouncilMessage(..., kind = CouncilMessageKind.ASK_USER), status = CouncilRoomStatus.INTERRUPTED)
                    } ?: return
                    awaitUserAnswer(conversationId)
                }
            }
        }
    }

    handleInterjections(conversationId, settings)

    val finalRoom = peekRoom(conversationId) ?: return
    if (finalRoom.status.terminal) return
    val hostModelId = resolveHostModelId(finalRoom, settings)
    if (hostModelId == null) {
        completeSynthesis(conversationId, "（无法生成综合结论...）", listOf("Host model not found"))
        return
    }
    val synthRoom = mutateRoomOrNull(conversationId) { room ->
        room.copy(mode = CouncilRoomMode.SYNTHESIZE, status = CouncilRoomStatus.FINALIZING, ...)
    } ?: return
    executor.generateSynthesis(...)
}
```

### `close()` 清理逻辑（`CouncilRoomManager.kt:1944-2026`）

```kotlin
suspend fun close(conversationId: Uuid, cancel: Boolean = false): CouncilRoomOpResult {
    jobsLock.withLock { closingConversationIds.add(conversationId) }
    try {
        // Graceful close: wait for synthesis
        if (!cancel) {
            val room = peekRoom(conversationId)
            if (room?.status == CouncilRoomStatus.FINALIZING) {
                val synthesisJob = jobsLock.withLock { synthesisJobs[conversationId] }
                if (synthesisJob != null && synthesisJob.isActive) {
                    val timeoutMs = room.totalTimeoutMs.coerceAtLeast(1_000L)
                    val completed = withTimeoutOrNull(timeoutMs) { synthesisJob.join() } != null
                    if (!completed) Log.w(TAG, "Graceful close timed out")
                }
            }
        }

        // Cancel all jobs
        val (guestJobs, synthesisJob, askDeferred) = jobsLock.withLock {
            val guests = generationJobs.remove(conversationId) ?: emptyList()
            val synth = synthesisJobs.remove(conversationId)
            val ask = pendingAskUser.remove(conversationId)
            Triple(guests, synth, ask)
        }
        guestJobs.forEach { it.cancel() }
        synthesisJob?.cancel()
        askDeferred?.cancel()

        // Persist terminal state + evict
        val mutex = lockFor(conversationId)
        val result = mutex.withLock {
            val flow = store.observeRoom(conversationId)
            val room = flow.value ?: return@withLock Err("not_found", ...)
            if (room.status.terminal) {
                store.closeAndEvict(room)
                return@withLock Ok(room)
            }
            val nextStatus = when {
                cancel -> CouncilRoomStatus.CANCELLED
                room.status == CouncilRoomStatus.FINALIZING || room.synthesis.isNotBlank() -> CouncilRoomStatus.FINALIZED
                else -> CouncilRoomStatus.FINALIZED
            }
            val updated = room.copy(status = nextStatus, finishedAtMs = nowMs(), updatedAtMs = nowMs())
            updateTaskStatus(updated)
            store.closeAndEvict(updated)
            Ok(updated)
        }
        locksLock.withLock { locks.remove(conversationId) }
        return result
    } finally {
        jobsLock.withLock { closingConversationIds.remove(conversationId) }
    }
}
```
