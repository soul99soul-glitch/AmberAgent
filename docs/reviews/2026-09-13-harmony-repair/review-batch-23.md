# 独立复核 — batch 2/3（只读）

范围：`implementation-contract.md`、`batch-2-protocol.md`（R05/R06/R18）、`batch-2-tools.md`（R08/R15/R25/R14），
以及 batch 2 之外由其他 owner 正在改、但需核对的 Cron 无头工具循环（`AppContainer.createEntryCronRunExecutor`，R12/R13/R16/R17）与 R14 任务板取消面。

复核方式：读当前工作树 diff + 调用链反证 + SDK/Android 基准对照 + 现有 targeted 测试/一次性探针实跑（探针均在 `/tmp`，未落仓）。
基线 `bdce551dd`（harmony-preview）。**本文件只读产出，未改任何代码、未改业务、未跑 hvigor/全仓 lint。**
其他 agent 的暂时编译错误（`harmony/deepread/.../run_manager.ts` TS2554，属另一 owner）未重复修。

结论：R05/R06/R15/R18 机制成立且 fail-closed；R08/R14/R25 在**本批自身引入的状态映射交互**上留有一个真实破坏性缺口，另有 3 个 P2。

---

## 一、结论速览

| # | 级别 | 标题 | 影响 |
|---|---|---|---|
| P1-1 | P1 | R14 把 approval_required 映射为 INTERRUPTED 后，`cleanup` 可删除仍在运行子代理的任务板记录与 transcript | 破坏性删除活任务记录/文件（R25 目标被绕过） |
| P2-1 | P2 | 同源：任务板 `cancel` 对 approval_required 子代理**静默无效** | 用户点取消既不能终止也不能反馈 |
| P2-2 | P2 | R06 修复后，2xx「零 SSE 事件」分支的网关错误**正文丢失** | 用户只看到 `Failed to get response: 200`，无诊断 |
| P2-3 | P2 | R08 `locator` 定位失败时仍返回新对象，ChatPage 的“未生效”守卫不可达；定位到错误 part 时静默改错工具 | 可能静默批准/拒绝错误工具（fail-open） |
| P3 | P3 | Cron 无头循环、RcpHttpClient、kernel wrapper 若干边界 | 见第四节 |

RcpHTTP 方面明确回答协调问题：**done 不会盖掉非 2xx 错误**，且「早 settle status 0」在当前实现下不可达（证据见 2.5）。

---

## 二、分项复核

### 2.1 R05 — 结构化取消判据 + 不回退旧会话（通过）

`chat_kernel_host.ts:124-177`。实跑探针 `/tmp/probe-r05.ts`：

```
non-abort 'abort' text: threw (good) phase=failed     # name=Error、message 含 "abort" → 走 failed 并原样抛
bare AbortError name, signal not aborted: threw (good) phase=failed
```

- 取消判据 = `cancelled || signal.aborted`，不含 message 子串/name 猜测；错误正文含 `abort` 不再被吞成取消。用户 Stop 时（`ChatPage.requestStop` 同时 `kernelHost.cancel('user')` 且 `abortController.abort()`，`ChatPage.ets:1111-1122`），catch 返回 `lastPersistedConversation`，不会早于已落库的 user。成立。
- `trackingStore` 只实现 `save`：`ConversationStore`（`chat_turn.ts:70-72`）当前**只有 `save`**，`chat_turn/regenerate/tool_loop` 均只调 `save`，所以“wrapper 丢其它 port 方法”在当前契约下不是 bug。探针里 `extraPortMethod` 被丢只是我自造的额外方法。**登记为类型演进约束**（若 ConversationStore 增方法，此字面量 wrapper 会静默丢方法，建议改 `type ConversationStore` 显式实现或 `Object.create`）。

### 2.2 R06 — 200 非 SSE / 零有效输出不得静默成功（基本通过，正文丢失见 P2-2）

四个 Provider 均「先 `throw state.error` → `state.done` 直返 → 非 2xx 抛 `errorFromFailureBody` → 2xx 零事件抛」。实跑 `/tmp/probe-r06.ts`：

```
unrecognized event + DONE: SUCCESS chunks=1     # 事件被 SSE 解析到，但无 choices → 仍算成功（见下）
empty choices + DONE:      SUCCESS chunks=1
error payload:             threw rate limited
```

- 真·非 SSE（正文无 `data:` 前缀）会被 `SseAssembler` 忽略 → `sawEvent=false` → 抛错。R06 的核心（不再静默成功）成立。
- 残留：被 SSE 成帧但语义为空的事件（`{"foo":"bar"}`、`choices:[]`）仍计入 `sawEvent`，整条流“成功但零可见内容”。与 batch 文档口径一致（“只有整条流零事件才判失败”），属可接受设计，但**普通发送仍无“零可见输出”兜底**；登记 P3，不算回归。
- `openai_request.ts:471` / `claude_request.ts:293` merge 后强制回写 `stream` 正确（对齐 Android `withForcedStream`）；`openai_responses_request.ts:310` 本已强制。

### 2.3 R15 — 自然语言不再批量批准（通过，无回归；我专门反证）

`tool_approval.ts:184-251`：`shouldResume = hasAskUserAnswer`，其余一律 `cancelToolForNewUserMessage`。实跑 `/tmp/probe-r15.ts`、`/tmp/probe-r15b.ts`：

```
denied-resumable  + 普通文本 : shouldResume=false event=pending_tool_cancel → 置 denied
approved-resumable+ "继续"   : shouldResume=false event=pending_tool_cancel → 置 denied
```

我一度怀疑「显式批准(approved)的未执行工具被新消息误取消」是本批回归，已用 `git show HEAD:.../tool_approval.ts` 对照旧实现否证：
旧代码 `explicitApproval && !isToolPending → skipStaleToolForContinuation`（也是 denied），普通消息同样 `cancelToolForNewUserMessage`。
即 **approved-idle 在旧实现下也从不经 idle 路径 resume**，本批只是改了 reason 文案，**不是回归**。R15 收紧正确且 fail-closed：
mandatory 工具不再被裸“继续”绕过；`ask_user` 自由文本应答保留（唯一合法 shouldResume）。

### 2.4 R18 — 协议终态立即收口（通过；状态 0 不可达）

`RcpHttpClient.ets:195-218`（`stopConsumption`/`emit`）、Providers 传 `shouldStop`。逐链反证：

- `dataReceive`（:274-283）仅当 `status!==null && 2xx` 才 `emit`，否则缓冲进 `pendingChunks`；`settleWhenReady`（:234）仅对 2xx flush 缓冲。因此 **`onChunk` 只可能在 status 为 2xx 时被调用**，`shouldStop()` 只可能在这些时刻为 true。
- `stopConsumption` resolve 的 `status !== null ? status : 0`（:202）在当前路径下 **status 必非 null，status 0 分支不可达**。batch-2 文档接口项 #2「提前 settle 时 status 可能为 0」是**过时描述**，不是缺陷。
- 非 2xx 正文始终缓冲、从不喂 parser；`done` 无机会置位，故 **不存在「done 盖掉非 2xx 错误」**。非 2xx 路径 `settleWhenReady` 返回解码后的 `body`（:237-243），Provider 正常抛 `errorFromFailureBody`。
- 错误收口：Provider 在 `onChunk` 内置 `state.error` → 返回 true → Port destroy + resolve；Provider 随后 `throw state.error`，错误可见。
- 重入/清理：`settled` 在 `fail`/`stopConsumption`/`settleWhenReady` 成功路径统一置位，`destroyOnce` 幂等；`notifyDataEnd` 有 `dataEndNotified` 一次性门，`stopConsumption` 与 `dataEnd` 处理器不会双触发 `onDataEnd`。未发现竞态残留。
- 设备未验证项（不声称）：在 `dataEnd` 回调栈内同步 `httpRequest.destroy()` 的 SDK 语义、`readTimeout` 是整响应还是空闲口径 —— 与 review-c C02 残余一致，需真机。

### 2.5 R08 — 工具身份（核心通过；locator 硬化见 P2-3）

- `tool_dispatcher.ts:247-326` `executeBatchPairs` 保输入引用并保序；`tool_loop.ts:122-162,340-384` 以 `Map<part, result>` 身份回写。逐路径核对引用链：
  - 生成步：`finalMessages` 的 part 经 `rewriteLastMessage`/`appendNode` 入 `conv` → `stepMessages=currentMessages(conv)` 读回的是**同一批引用** → `tools`/`updatedTools`/执行结果三者身份一致。
  - 审批写回构造 `{input: tools[i], result: updatedTools[i]}`，其中未变项 `updatedTools[i]===tools[i]`，`rewriteLastMessageTools` 的 `direct!==tp` 判等不会误报 changed。
  - resume 路径 `pendingTools` 直接来自 `currentMessages(conv)`，身份天然保持。
  结论：**跨 copy 引用失效不成立**——`currentMessages` 是裸取（`conversation.ts:88-89`），中间无 clone/序列化；`buildToolLoop` 每次仍基于 `this.conversation` 现取（`ChatPage.ets:2948`）。
- `byUniqueId` 回退仅在「非空 id 且本批内唯一」时启用，且要求目标 `!isToolExecuted`，不会串到上一轮已封口工具。合理。
- 我已确认**主代理已完成 batch-2 文档里“待接线”的 locator UI 链路**：`AgentMessageRow.ets:170-186 locatorFor` + `:357-361` 透传，`ChatPage.ets:4422-4427` 三处 `handleToolApproval/openDenyDialog/handleToolAnswer` 接受并转发 `locator`。此项**不再是未完成项**。

### 2.6 R25 / R14 — cleanup 门与 approval_required 收口（发现 P1 缺口）

`agent_task.ts:31-32`：`agentTaskStatusRunning = QUEUED | RUNNING`。
`subagent_manager.ts:211-222`：approval_required 运行对象仍 live（`isLiveRunStatus` 含它），任务板映射为 **INTERRUPTED**。
两处叠加产生交互缺口。实跑探针 `/tmp/probe-r14r25.ts`：

```
board cancel on INTERRUPTED: status=INTERRUPTED callbackInvoked=false   # 任务板取消静默无效
cleanup INTERRUPTED(approval_required) allowed=true record=null         # 可删活任务看板记录
```

- `AgentTaskStore.cancel`（:675-685）在 `!agentTaskStatusRunning(status)` 时**提前 return**，`cancelCallbacks` 虽仍在（`update` 不删回调），但永不调用 → R14 修好的 `manager.cancel` / `subagent_cancel` 工具可用，**看板取消按钮 / `agent_task_cancel` 工具路径仍不可用**。
- `AgentTaskStore.cleanup`（:758-774）门只认 `agentTaskStatusRunning`，而 approval_required 被本批改成 INTERRUPTED → **可 `cleanup(delete_private_output=true)` 删掉正在运行子代理的看板记录 + transcript 文件**，正是 R25 要消灭的行为，只是入口从 RUNNING 变成了 INTERRUPTED。
- 补充：`finish`（:634-640）对 approval_required 硬编码 `cancelCapability:false`，看板因此连取消按钮都不显示。

### 2.7 Cron 无头工具循环（main 刚接，只读核对）

`AppContainer.ets:444-539` + `AgentCronStore.ets:229-281`。方向与 fail-closed 正确：

- `invocationContext:'cron'` 在 `tool_permission.ts` 无特判（仅 `subagent` 有），`autoApproveTools/HighRisk=false` ⇒ 一切 `needsApproval/mandates` 工具 → `ask` → 循环置 pending break；执行器 `conversationHasPendingOrUnexecutedTools(out)` 抛错（:527-529）→ 不 notify 完成。**不会把待审批当成功**，符合 R13 约束。
- `signal` 贯穿 `ChatTurnDeps.abortSignal` → Provider；`signal.aborted` 在 append 后、组装后、run 后三处复查（:452/480/525）；`AgentCronStore.cancel` abort 在途 controller；`finalizeRun` 对已删除任务 `getTask→null` 时不 mark 不重排（:232-234），R17「删除即停当前、不复活」成立。
- `beginConversationRun` 保护（:453）与 `SettingBackupPage` 恢复互斥同源，方向正确。

## 三、应修 P1 / P2（含具体修法）

### P1-1 R14×R25：approval_required 收口后仍可被 cleanup 删记录（且看板不可取消）

**证据**：见 2.6 `/tmp/probe-r14r25.ts` 输出；代码 `agent_task.ts:675-685, 758-774`、`subagent_manager.ts:214-222, 634-640`。

**根因**：取消能力/存活态被拆成两套判据——运行对象用 `isLiveRunStatus`（含 approval_required），任务板用 `agentTaskStatusRunning`（不含 INTERRUPTED）。`cancelCapability` 这个既有契约字段被 `finish` 硬编码 false，导致两套判据无法对齐。

**最小修法（推荐，改 2 处 + 2 测试）**：统一以 `cancelCapability + 回调存在` 作为“可取消/不可清理”的单一判据。

1. `subagent_manager.ts` `finish` 的 `terminalTaskUpdate`：把 `cancelCapability: false` 改为
   ```ts
   // approval_required 运行对象仍 live 且 start() 已注册取消回调 → 看板保留取消能力(R14)
   cancelCapability: next.status === 'approval_required',
   ```
   （其余终态维持 false。）
2. `agent_task.ts`
   - `cancel`：删掉 `if (!agentTaskStatusRunning(current.status)) return current;` 这一行的“状态早退”，**只保留**下方的
     ```ts
     const callback = this.cancelCallbacks.get(taskId);
     if (!current.cancelCapability || callback === undefined) { /* 记 error 返回 */ }
     ```
     即“有没有取消能力”由 `cancelCapability` 回答，不再由 status 猜测。成功后 `update(..., { status:'CANCELLED', summary:'Cancellation requested.', cancelCapability:false })`，避免回调残留让后续 `cleanup` 永久拒绝。
   - `cleanup`：在 `agentTaskStatusRunning` 门旁**并列**加活取消门
     ```ts
     if (current.cancelCapability && this.cancelCallbacks.has(taskId)) return false;
     ```
     这样 approval_required（INTERRUPTED+RUNNING 回调）不可清；已 COMPLETED/FAILED 的 cron/subagent（`cancelCapability=false`）仍可清，R25 原意保留。
   - 对齐检查：`AgentCronManager.markRunCompleted/Failed/Cancelled` 已传 `cancelCapability:false`，`markRunSuspend` **未传**（见 P3-2），补 `cancelCapability:false` 后此修法对 cron 也自洽。
3. 回归（加在现有文件，一两例）：`subagent_manager.test.ts`「approval_required → 看板 cancelCapability=true → `agentTaskStore.cancel(runId)` 触发 manager.cancel → CANCELLED」；`agent_task.test.ts`「带活回调的 INTERRUPTED `cleanup` 返回 false；回调被显式撤销/终态清理后可清」。

### P2-1 与 P1-1 同源：看板取消对 approval_required 静默无效

由 P1-1 步骤 1+2 同时解决。补充断言：取消前后 `manager.snapshot(runId).status` 从 `approval_required` → `cancelled`（已有 `subagent_manager.test.ts:615-634` 工具路径用例，需再加“经 `AgentTaskStore.cancel`”的路径）。

### P2-2 R06：2xx 零事件时网关错误正文丢失

**证据**：`RcpHttpClient.ets:245-252` 成功路径 `resolve({ ..., body: '' })`；非 2xx 才在 :237-243 回传解码 body。2xx 的正文被当作 chunk 直接喂 parser（`dataReceive`），未留副本。Provider `openai_chat_api.ts:158-160`（claude:126-128 / google:183-185 / responses:198-200）在 `!sawEvent` 时 `errorFromFailureBody(resp.status, resp.body)`，此处 `resp.body === ''` ⇒ 用户只看到 `Failed to get response: 200`。batch-2 文档声称“网关 JSON 错误正文得以透出”，**与实际不符**。

**最小修法（Provider 侧有界原始捕获，不动 Port 内存模型）**：在每个 Provider `onChunk` 内累计前 N KB 原始文本（默认 8KB，够诊断），失败分支用它：

```ts
let rawDiagnostic = '';
const RAW_CAP = 8192;
// onChunk 开头:
if (rawDiagnostic.length < RAW_CAP) {
  rawDiagnostic += new util.TextDecoder/TextDecoder('utf-8').decode(bytes)  // 现有解码工具
    .slice(0, RAW_CAP - rawDiagnostic.length);
}
// 零事件分支:
throw errorFromFailureBody(resp.status, rawDiagnostic);
```
注意 ArkTS 侧用现有 `util.TextDecoder`（域层无平台依赖，改用现有 `decodeChunks` 风格工具或让 Port 暴露原始文本）；测试用现有 `openai_chat_api.test.ts` 的 fake（`finalBody` 可设）加一例：200 + 非 SSE 错误 JSON → 断言 thrown.message 含网关 `message` 字段而非仅 200。

### P2-3 R08：locator 定位失败仍返回新对象；可能改错 part

**证据**：`tool_approval.ts:115-145`：只要 `locator !== null` 就跳过唯一性门且**总是构造新 `Conversation` 对象**。实跑 `/tmp/probe-r08loc.ts`：

```
locator at text index -> sameConvRef=false stillPending=true
locator wrong messageId -> sameConvRef=false
```

`ChatPage.ets:3421-3425` 用 `out === previous` 判断“未生效并提示用户”，但 locator 路径永不相等 ⇒ 提示不可达；更糟的是若 `partIndex` 因显示层 parts 与存储层不一致而**偏移到另一个 tool part**，会静默改写错误工具，随后 `conversationHasPendingTools` 若因此清零会直接 `resumeToolLoop` 执行错工具（fail-open）。

**最小修法**：
1. `applyToolApprovalToConversation`：locator 路径内记录 `matched`，映射完成后 `if (locator !== null && !matched) return conv;`（同时要求命中项 `part.type==='tool'`，非 tool 命中不算 matched）。这样 ChatPage 既有 `out===previous` 守卫即可生效。
2. `AgentMessageRow.locatorFor`：当 `t.toolCallId` 非空时，除 `indexOf` 外再校验候选 part 的 `toolCallId===t.toolCallId`；不等则回退 `streamToolIndex` 唯一匹配，仍不唯一返回 `undefined`（现状已是 undefined → 拒绝歧义，保持）。
3. 测试：`tool_approval.test.ts` 加「locator 指向 text 下标 / 错误 messageId → 返回原引用」；`AgentMessageRow` 层若可测，加「indexOf 命中但 toolCallId 不符 → 回退 stream index」。

## 四、P3 与残留（不改也可上线，登记为主）

1. **RcpHttpClient `emit` 返回语义**：`stopConsumption` 恒返回 false，`dataReceive` 忽略 `emit` 返回值 —— 无副作用，仅可读性。
2. **`markRunSuspend` 未清 `cancelCapability`**（`agent_cron.ts:625-634`）：`update` 不传该字段则保留 `markRunStarted` 置的 true，看板 COMPLETED 却仍标可取消。补 `cancelCapability:false`（P1-1 修法已依赖）。
3. **Cron mutating 工具恒 stall 是设计**：无 `cron` 特判权限 ⇒ 需要工具的任务每轮都 pending→FAILED。建议在 `cron_task_create` 文案或执行器里明示“定时任务不支持需审批工具”，避免用户误以为任务能跑；后续若在 ChatPage 里批准该 pending，会以 `invocationContext:'normal'` 续跑，语义与 cron 不同，需产品确认。
4. **Cron maxSteps 被主代理 clamp**：`runAgenticToolLoop` 对非 subagent 走 `mainClamp=true`（`tool_loop.ts:401-404`），`execution.maxToolLoopSteps` 会被抬到 ≥16。若希望 cron 精确预算，需要独立 context 分支或注释登记。
5. **Cron 失败轮会累积 prompt**：pending→抛错前 user prompt 已由 `runAppendUserMessage` 落库；重复失败会在同一会话叠加“定时任务…”prompt。可在失败路径回滚/去重（非本批范围）。
6. **子代理 admission 只计 running**（`subagent_manager.ts:357-359`，用 `subAgentRunStatusRunning` 仅 'running'）：approval_required 不占并发额度，可堆积多个挂起子代理。与 R14 复核结论一致，登记。
7. **`skipStaleToolForContinuation` / `isToolApprovalContinuation` 已无生产调用**（仅测试/导出），可保留但建议注释“仅分类语义”。
8. **kernel wrapper 非泛型**：见 2.1，会话 store 方法扩展时需同步。
9. **设备验证候选（不声称通过）**：R18 `destroy()` 流中语义/readTimeout 口径；GCM 空 authTag（batch-0 P1-a）；RDB 降级 GC（review-batch-01 P2-3）。

## 五、测试与命令（只读实跑，未改业务）

```
cd harmony/chat
npx tsx --test src/test/tool_approval.test.ts src/test/tool_loop.test.ts \
  src/test/subagent_manager.test.ts src/test/agent_task.test.ts src/test/chat_kernel_host.test.ts
#   86 tests / 86 pass / 0 fail
npx tsx --test src/test/openai_chat_api.test.ts src/test/openai_responses_api.test.ts \
  src/test/claude_request.test.ts src/test/openai_request.test.ts \
  src/test/key_roulette_wiring.test.ts src/test/tool_dispatcher.test.ts
#   103 tests / 103 pass / 0 fail
npx tsx --test src/test/agent_cron.test.ts src/test/agent_cron_run_executor.test.ts \
  src/test/agent_cron_regression.test.ts
#   28 tests / 28 pass / 0 fail
npx tsc --noEmit -p tsconfig.json
#   仅 2 个非本批错误: ../deepread/.../run_manager.ts(114,92)/(148,125) TS2554（其他 owner，未重复修）

一次性反证探针（/tmp，不入仓）：probe-r05 / probe-r06 / probe-r15 / probe-r15b / probe-r14r25 / probe-r08loc
```

未跑：hvigor 构建、`lint-arkts.sh`、全仓 lint、真机/模拟器（按契约由主代理统一）。
