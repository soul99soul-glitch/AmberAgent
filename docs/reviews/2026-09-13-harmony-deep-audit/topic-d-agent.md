# 专题 D — Agent 工具 / cron / subagent 与授权

范围：tool_dispatcher / policy / permission / approval / exposure / registry / speculative runner、tool_loop 终止与预算、subagent_manager / runner / tool_scope、agent_cron / run_executor、entry AgentCronStore / AppContainer 接线 / SettingCronTasksPage，以及 F 的 `tool`/`session_grant` 授权部分。

基线：分支 harmony-preview，HEAD `bdce551dd373f3d7e4c393b194997cae19289c40`。业务代码只读；`docs/reviews/2026-09-13-harmony-deep-audit/` 内仅新增本文件与 `probes-d/`。Android 基准仓库同工作树（`app/`、`feature/`、`ai/`）可读，用于区分「移植偏差」与「双端同构」。

已先读：`reviewer-contract.md`、`coverage-initial.md`、`AGENTS.md`、`harmony-arkts` skill、`2026-08-25-harmony-review-round3{,-fix-plan}.md`。

**已确认前提：`AGENT_CRON_EXECUTION_AVAILABLE = true`（`harmony/entry/src/main/ets/platform_impl/AgentCronStore.ets:48`），执行器已接线（`AppContainer.ets:441-506`）。本报告不以「未接线」作为反证，所有 cron 结论均针对已接线后的真实控制流。**

---

## 1. 覆盖清单

| 文件 | 程度 | 备注 |
|---|---|---|
| `harmony/chat/src/main/ets/chat/tool.ts` | 全文 | AgentTool/默认值语义 |
| `chat/tool_policy.ts` | 全文 | policy 派生全文 + FR-1 probe |
| `chat/tool_permission.ts` | 全文 | 审批优先级链；FR-1 probe |
| `chat/tool_approval.ts` | 全文 | 续跑/陈旧工具 |
| `chat/tool_dispatcher.ts` | 全文 | execute/executeBatch/hook/retry/abort |
| `chat/tool_exposure.ts` `tool_registry.ts` | 全文 | 懒暴露/包装层 |
| `chat/tool_loop.ts` | 全文 | 终止/预算/审批/abort/写回 |
| `chat/agent_loop_budget.ts` | 全文 | 步数预算 |
| `chat/speculative_tool_runner.ts` | 全文 | 推测执行 |
| `chat/subagent_manager.ts` | 全文 | admission/超时/取消/grant/任务看板 |
| `chat/subagent_runner.ts` | 全文 | 子循环/approval_required/隔离 assistant |
| `chat/subagent_tool_scope.ts` `subagent_tools.ts` | 全文 | child scope/五件公共工具 |
| `chat/subagent_models.ts` `subagent_validator.ts` | 全文 | 模型/校验（含 NaN、clamp） |
| `chat/subagent_transcript.ts` | 定位+接缝 | JSONL/尾读协议 |
| `chat/agent_cron.ts` | 全文 | 表达式/Manager/四工具 |
| `chat/agent_cron_run_executor.ts` | 全文 | 执行器 Port |
| `chat/agent_task.ts` | 全文 | Store/Recovery/update 语义 |
| `chat/session_grant_store.ts` | 全文 | grant 本体 |
| `chat/tool_activity.ts` `builtin_ask_user_tool.ts` | 全文/全文 | 活动 store / ask_user |
| `entry/platform_impl/AgentCronStore.ets` | 全文 | entry 调度器 + KV/文件端口 |
| `entry/platform_impl/SubAgentStore.ets` | 全文 | transcript 端口（readTail 已改字节窗） |
| `entry/di/AppContainer.ets` | 定位+跨模块追踪（cron/subagent 段 397-701） | 接线 |
| `entry/entryability/EntryAbility.ets` | 全文 | 启动重排 |
| `entry/pages/ChatPage.ets` | 定位+跨模块追踪（buildToolLoop 2929-3151、审批 3336-3505、idle blocker 3950-3989、cron 工具缓存 912-919/3072） | 工具面 |
| `entry/pages/SettingCronTasksPage.ets` | 全文 | 列表/启停/立即运行/删除 |
| Android 基准：`SubAgentManager.kt` `AgentCronManager.kt` `AgentCronWorker.kt` `PermissionDecisionResolver.kt` `AgentToolDispatcher.kt` `WebMountInteractionTools.kt` `Tool.kt` `SubAgentDefinitions.kt` | 关键段 | 判同构/判偏差 |

隔离 probe（真实 `.ts` 逻辑 + 内存假端口，无网络/模型/文件副作用，`pass=15 fail=0` 与 `pass=8 fail=0`）：
- `probes-d/topic-d-probe.ts`
- `probes-d/topic-d-probe2.ts`

---

## 2. 候选发现

### D-01 [P1｜高置信] wm_click 审批降级：单开自动批准即可静默执行 WebView 注入面

> 归属：F 主报告（WebMount 本体）。本报告只出授权链证据，避免重复。

- 位置：`harmony/entry/src/main/ets/platform_impl/WebMountTools.ets:120-143`（wm_click 仅 `needsApproval: true`）；对比 `:196-206`（wm_eval 有 `allowsAutoApproval:false, mandatoryApproval:true`）。派生：`tool_policy.ts:473-479`；决策：`tool_permission.ts:188-195 / 196-198 / 256-259`。
- 前提：WebMount 全局或助手开关开启；用户在设置里只开「自动批准全部工具调用」。
- 用户影响：高危工具被静默批准并执行。实际外部写闭环成立 —— `wm_click` 的 `execute` 走到 `evalToPart(session, JS)`（`WebMountTools.ets:133-141`），与 `wm_eval` 同源注入汇；`:135` 的 `error: 'element not found: ${target}'` 在单引号字符串内直插未转义 `target`（`target` 经 `tool_policy` 判为 sensitive 但非 high）。→ 与 wm_eval 强制逐次人工的边界不一致。
- 根因：移植时漏写 `allowsAutoApproval:false, mandatoryApproval:true`，`makeAgentTool`（`tool.ts:62-63`）默认回落 `allowsAutoApproval=true / mandatoryApproval=false`，于是 `policy.autoApprovable` 保持 true（`tool_policy.ts:410-411`，且 category=webmount 不在 FAIL_CLOSED 类目，`requiresFailClosedAutoApproval` 返回 false）。
- 现有保护为何不成立：`alwaysAsk`（`tool_policy.ts:304-308`）不含 wm_click；单开关分支 `tool_permission.ts:256-258` 只看 `autoApprovable`。
- 反证/边界：`speculativeEligible=false`（`tool_policy.ts:356-357` risk_not_normal / mutates_state），无后台无审批执行；双开关（:188）与 Android 一致属刻意。
- 验证：probe P1a（policy=autoApprovable true/mandatory false）、P1b（单开关 → allow/source=settings）、P1d（wm_eval 单开关仍 ask）、P1e（双开关 unattended）。Android 基准 `WebMountInteractionTools.kt:94-96` 对 wm_click 设了 `allowsAutoApproval=false, mandatoryApproval=true` → 本项为**移植降级**，非双端同构。
- 历史：不在 round3 清单内（该轮未覆盖 wm_click 授权档位）。
- 最小修复：`WebMountTools.ets` wm_click 补 `allowsAutoApproval: false, mandatoryApproval: true`（与 Android 及 wm_eval 对齐）。回归测试：新增 `tool_permission` 单测断言 `resolve(wm_click, …, autoApproveTools=true, highRisk=false)` 返回 `ask`。

### D-02 [P2｜高置信] cron 无运行锁：重叠触发导致同一任务并发双执行 + runCount 丢失

- 位置：`entry/platform_impl/AgentCronStore.ets:186-204`（`fire` 无「在跑」判定）、`:207-214`（`schedule` 只清定时器，不处理在跑的 `fire`）；`chat/agent_cron.ts:456-495`（`prepareTriggeredRun` 无 due/在跑校验，则重算并再次置 `Queued`）、`:583-593`（`replaceTasks` 读-改-写无互斥）、`:505-520`（`markRunStarted` 无状态门）。
- 前提：定时器触发的运行尚未结束时，用户点「立即运行」，或两次 `fire` 交错。
- 用户影响：同一 cron 任务并发执行两次 → 外部副作用（网络/文件/模型调用）重复、`runCount` 丢失更新、两次 `runChatTurn` 对同一会话并发 `save`（后写覆盖）。
- 根因：Android 用 `WorkManager.enqueueUniqueWork(workName, REPLACE)`（`AgentCronManager.kt:158/281`）以唯一工作名互斥；Harmony 用 `setTimeout` 仅能取消**待触发**定时器（`AgentCronStore.ets:178-184 clearTimer`），**不能取消在跑的 fire**，且 `AgentCronRunExecutor`（`AppContainer.ets:441-491`）无运行集。
- 现有保护为何不成立：`prepareTriggeredRun` 只对 `!manual && !enabled` 过滤（`agent_cron.ts:460`），不检查 `lastStatus==='Running'`；`markRunStarted` 无条件覆盖。
- 验证：probe P2（`markRunStarted` 后再次 `prepareTriggeredRun` 仍返回非 null，`runCount` 2、状态回落 Queued）、P2b（并发两次 → 两次执行但 `runCount` 仅 1 = lost update）、P10b（`fireDueCronTasks` 并发扫描同一 due → 2 次执行）。
- 反证：单定时器顺序触发时 `schedule` 会 `clearTimer` 旧定时器，**同一时刻只有一个待触发**，故仅重叠/手动路径可达；`rescheduleAll` 对 disabled 任务会 cancel（`agent_cron.ts:576-578`），不制造重复。
- 历史：round3 P2 已记「cron 手动/定时触发无运行锁」，fix-plan 以「执行器未接线」列为假阳性豁免。**该豁免前提现已消失（AVAILABLE=true 且已接线），故本项重新成立**。
- 最小修复：AgentCronSchedulerPort 增加 `inFlight: Map<workName, boolean>`；`fire` 首行若在跑则丢弃/排队（对齐 WorkManager REPLACE：取消在跑或拒绝新触发）。回归测试：同一 workName 连续两次 `runNow`，断言 executor 只被调用一次。

### D-03 [P2｜高置信] cron 执行体是无工具单轮生成，与 Android 全 agent 语义不一致

- 位置：`entry/di/AppContainer.ets:441-491`。`createEntryCronRunExecutor` 组装 `ChatTurnDeps`（`:474-482`）时**未传任何 tools/loop**，直接 `runChatTurn(conversation, task.prompt, deps)`（`:482`）。`runChatTurn`（`chat/chat_turn.ts:177`）是单次生成，无 `runChatTurnWithTools`、无审批、无 `ask_user`。
- 前提：任何 cron 任务。
- 用户影响：cron 任务无法调用工具（搜索/文件/记忆/cron 管理等），只能产出一段文本；「定时让 Agent 自动执行操作」的核心用例失效。Android `AgentCronWorker.doWork` 调 `ChatService.sendMessage(...)`（`AgentCronWorker.kt:59-63`），走完整工具循环 + 审批 + 取消，并包一层「这是 Cron 定时任务触发」提示（`:44-51`），Harmony 也未加该提示。
- 根因：接线时以最小 `ChatTurnDeps` 落实「执行体 = ChatTurn」，未接 `buildToolLoop` 等价物。
- 现有保护为何不成立：无 —— 代码路径本身即缺少工具面；`markRunCompleted` 会照常置成功，用户看不到「无工具」这一降级。
- 验证：静态调用链（`grep runCronTaskOnce/fireDueCronTasks` 在 entry 无引用；entry 只走 `runChatTurn`）；`chat/agent_cron_run_executor.ts` 的 `runCronTaskOnce`（真正含 notifier 的 Port 实现）**未被 entry 使用**，属死代码（`index.ts:385` 仅导出）。
- 历史：round3 将 cron 执行器整体列为「未接线 P1」。现状：**已接线但执行体降级为无工具单轮**——这是新结论，非旧结论复述。
- 最小修复：entry 执行器改用 `runChatTurnWithTools` + `buildCronToolLoop`（复用 ChatPage.buildToolLoop 的组装，含 cron/subagent 工具、审批 flags 按 agentRuntime 设置、`invocationContext:'cron'`）；补 cron 前缀提示。回归测试：mock provider 断言 cron 运行会发出带 `tools` 的请求。

### D-04 [P2｜高置信] subagent `approval_required` 映射为任务 RUNNING 且无恢复通道、cancel 无效

- 位置：`chat/subagent_runner.ts:376-386`（子代理命中待审批工具 → 返回 `status:'approval_required'`）；`chat/subagent_manager.ts:207-214`（`approval_required → RUNNING`）、`:593-608`（`finish` 仅对 `status==='running'` 收口 → approval_required 直接 return null）、`:445-454`（`cancel` 先 abort 再 `finish(cancelled)`，但因非 running 无效）、`toAgentTaskSnapshot`（`:735-762`）`cancelCapability = subAgentRunStatusRunning` → false。
- 前提：子代理被授予了需要审批的工具（如 screen/terminal/external_file/office/cloud 类目或 mutates/非 normal 风险，`tool_permission.ts:126-131`）。
- 用户影响：任务板永久 RUNNING、占并发额度（`admissionMutex` 计数，`:347-352`）；`subagent_cancel` 返回仍在 `approval_required`，模型无法自救；`AgentTaskStore.cancel` 因 `cancelCapability=false` 只写 error。父会话不提供任何审批续跑入口（无 `approval_required` 的 UI 操作）。
- 根因：待审批信号只存在于子代理单次返回里，manager 未持久化为可恢复态；`finish` 的 running 门把它排除在收口之外。
- 现有保护为何不成立：`capLiveState`/`wait` 都只读状态；`cancel` 的 abort 对已返回的 runner 无效。
- 验证：probe P6（runner 返回 approval_required 后：run=`approval_required`，task=`RUNNING`，`cancelCapability=false`；`cancel()` 后 run/task 均不变；`wait()` 亦不变）。
- 反证：Android `SubAgentManager.kt:182-190/311-334` 同构（TimeoutCancellation 才算 timed_out；`finish` 同样以 running 为门；cancel 同样 `job?.cancel()+finish`）→ **双端同构**，fix-plan 已判「产品语义」。但仍应视为真实可用性缺陷（非假阳性）。
- 最小修复：`subagent_manager.finish` 允许 `approval_required` 收口为终态（如 `INTERRUPTED`）或持久化为可恢复态并提供父侧 approve 续跑；至少让 `cancel()` 对 approval_required 生效。回归测试：runner 返回 approval_required → 断言 `cancel()` 后 run/task 为 `cancelled/CANCELLED` 且并发额度释放。

### D-05 [P2｜高置信] `autoApprovedToolNames`（run-trust）全链路未接线 → resolver 分支成死代码 + 行为缺失

- 位置：`chat/tool_permission.ts:249-252`（run_trust 分支）；`chat/tool_loop.ts:70-71/168`（选项与默认 `[]`）；`entry/pages/ChatPage.ets:3116-3150`（`buildToolLoop` 返回的 `ToolLoopOptions` **从未设置** `autoApprovedToolNames`）。
- 前提：用户在同一轮内已批准过某工具，期望后续调用不再询问（Android `trustedRunToolNames` / screen 会话信任集）。
- 用户影响：行为缺失 —— 已批准工具每步重复弹审批；同时该分支在生产不可达（死代码，掩盖真实审批路径）。
- 根因：`tool_approval.ts:11-12` 头注自认「服务层副作用（job cancel/trustedRunToolNames/审计）由调用方接线」，但 entry 未接线。
- 验证：静态 —— 全仓 grep `autoApprovedToolNames` 在 `.ets` 仅出现在 `AppContainer.ets:260`（DeepRead 客户端）与 `OpenAiCompatibleAiClient.ets`，与 Chat 工具循环无关；`ChatPage.ets` 无写入。probe P1c 显示只有**显式注入**白名单才 allow，故当前产品不可达。
- 历史：round3 #8 区域未列此项；本轮为新增（接线缺口）。
- 最小修复：在 `applyToolVerdict` 批准成功后收集 `approvedToolNames`，经 `resumeToolLoop` 传入 `buildToolLoop`。回归测试：批准一次后第二步同类工具不再 ask。

### D-06 [P2｜中置信] subagent history grant 由模型自供 `source_session_ids` 自动铸造并预批准，无用户确认边界

- 位置：`chat/subagent_manager.ts:291-320`（`isHistoryReader && task.sourceSessionIds.length>0` → `sessionAccessGrantStore.create(...)`，`needsApproval:false` 的 `copyToolForHistoryGrant`）；`chat/tool_permission.ts:217-228`（`subagent_history` 预批准）；`chat/session_grant_store.ts:50-83`（边界：TTL 30min、maxChars=outputBudgetChars*4 且封顶 120k、最多 24 会话）。
- 前提：父模型发起 `subagent_start`，`subagent_id='historian'`（或 allowlist 含 session_read/expand），并自填 `task.source_session_ids`。
- 用户影响：模型可在无用户逐个确认的情况下读取任务指定的任意历史会话内容（只要会话 id 存在）；grant 的 scope 完全由模型输入决定，而非用户选择。
- 根因：`sourceSessionIds` 是工具入参（`subagent_tools.ts:268-272`），直接成为 grant 的 `sessionIds`；`session_read` 侧只要 `grant_id` 非空即预批准。
- 现有保护边界（反证）：`session_grant_store` 有 TTL/字符/会话数上限；非 history reader 不铸造；admission 拒绝或初始化失败会 `revoke`（`subagent_manager.ts:368-373/400-403`）；grant 只在上一次 start 内有效。故**不是无界**，属有界授权。
- 历史：round3 #13「历史会话 grant 自动铸造无用户授权边界」，fix-plan 判「有界刻意设计」假阳性（且 Android 同构 `SubAgentManager.kt:94-108`）。
- 验证：静态（minter/consumer 双侧）+ probe 未覆盖（不构造真实会话内容，避免读用户数据）。
- 结论：维持「双端同构、有界刻意」，但登记为需产品确认的残余授权风险（P2）；若产品要求用户签发，最小修复是在 `subagent_start` 的 history 路径前置一次父会话审批（mandatoryApproval），而非改动 store 边界。**待产品确认项**。

### D-07 [P2｜中置信] `fireDueCronTasks` 无 claim/不清 due —— 接线即重复执行（潜伏）

- 位置：`chat/agent_cron_run_executor.ts:39-99`。`runCronTaskOnce` 注释明确「不调用 mark*」（`:38`），`fireDueCronTasks`（`:82-95`）对每个 due 任务直接执行、不改 `nextRunAtMs`；`isCronTaskDue`（`:98-99`）是纯判定。
- 前提：将来把「启动扫描 due」接上线（当前 entry 未调用，属导出死代码）。
- 用户影响：并发扫描同一 due → 双执行（probe P10b）；单次扫描后任务仍 due → 重复执行（probe P10c）。
- 验证：probe P10a/b/c 全通过（P10b 合计 2 次、P10c `isCronTaskDue` 仍 true 且再次 fire 命中）。
- 反证：当前 entry 不用它，故无现网影响；仅作为「接线前必须先补 claim」的登记。
- 最小修复：接线前改为 `prepareTriggeredRun` + `markRunStarted` 的原子 claim，按 `taskId` 去重（或给 `AgentCronManager` 加 `claimDue(id, now)`）。

### D-08 [P3｜高置信] cron 触发路径不校验 `nextRunAtMs`（Android 同构，当前不可达）

- 位置：`chat/agent_cron.ts:456-495`（manual=false 也重算并置 Queued，不比较 now/nextRunAtMs）。
- 验证：probe P3（1 月 1 日的年度任务在 9 月被 `prepareTriggeredRun` 成功置 Queued，`runCount=1`）。
- 反证：entry 仅在定时器到期（delay = nextRunAtMs-now，`agent_cron.ts:601`）或 manual runNow 时调用 `prepareTriggeredRun`；`rescheduleAll` 也不提前。故 366 天窗口内的非 due 调用在当前控制流下不可达（Android 同构）。
- 结论：降级为 P3 登记，非本轮实际缺陷。

### D-09 [P3｜高置信] subagent 任务不 retryable（与 Android 偏差）

- 位置：`chat/subagent_manager.ts:750-755`：`retryable: false`（固定）。Android `SubAgentManager.kt:381-386`：`retryable = status == FAILED`。
- 影响：Harmony 下 FAILED 子代理在任务板不可重试（`AgentTaskStore.retry` 直接返回「Task is not retryable.」）。因 `retryCallbacks` 本就未注册，实际行为差异有限。
- 修复：`retryable` 改为 `run.status === 'failed'`。

### D-10 [P3｜中置信] dynamic subagent 上限分支不可达（双端同构）

- 位置：`chat/subagent_manager.ts:352-363`：第一个条件 `running >= runLimit` 直接 return，第二个 `dynamic && running >= runLimit` 与之完全相同 → 死分支。Android `SubAgentManager.kt:137-145` 的第二条件用 `dynamicRunning`，但 `dynamicRunning <= running`，故同样不可达。
- 结论：双端同构死代码，非缺陷；登记以免重复报告。

### D-11 [P3｜高置信] 审批续跑词表过宽 + dispatcher 对 `ask` 不拦截（双端同构，纵深防御）

- 位置 A：`chat/tool_approval.ts:31-39/168-198`。任何以「继续/可以继续」开头或命中词表（含 `ok/yes/y`）的用户消息都会把末条 assistant 里**全部** `pending` 工具置 `approved`（`:185-188`）；`compact` 只去标点，故「继续写首诗」也会触发。Android `ChatService.kt:945-953` 逐字同构（已比对）。
  - 影响：可能对一条 pending 的高危工具（如 `terminal_execute`、`wm_eval`）批一次非本意的「继续」。缓解：`alwaysAsk`（sms_send/contacts_write/call_phone direct）与 `mandatoryApproval` 工具…注意：`resolveIdleToolBlocker` 的批准是直接改 `approvalState`，**不经过 resolver**，因此 `alwaysAsk`/`mandatoryApproval` 的「逐次人工」语义在此路径被绕过（用户确实在「继续」，但语义是模糊的继续而非明确批准）。
- 位置 B：`chat/tool_dispatcher.ts:328-362`：`execute()` 只拦 `approvalState.denied/answered/pending` 与 `decision.action==='deny'`；`decision.action==='ask'` 会**落入 executeWithHooks 执行**。Android `AgentToolDispatcher.kt` 同构（已比对）。当前安全依赖调用方（tool_loop :301-331）预先把 ask 置 pending，故主路径安全；推测执行器（`speculative_tool_runner.ts:133-140`）虽直连 execute，但 `speculativeEligible` 要求 `needsApproval=false/mutates=false/risk=normal`，ask 不可达。
  - 结论：双端同构的宽容设计 + 调用方约定；登记为纵深防御建议（execute 收到 ask 应返回 null 或抛错），不列为本轮缺陷。

### D-12 [P3｜高置信] 启动恢复接线现状（历史项已修复，成对记录）

- `recoverOnStartup` 已按 `spec.enabled===false` 拒复活（`chat/agent_task.ts:412-424`），probe P5a/P5b 通过 → round3 #12「禁用 cron 重启后被复活」**已修复**。
- `AgentTaskStore.loadSnapshots`（`:790-809`）在 store 创建时逐条 `recoverOnStartup`；`EntryAbility.ets:81` 调 `rescheduleCronTasksOnStartup`（`AppContainer.ets:518-528`）→ `rescheduleAll`（`agent_cron.ts:560-580`，disabled 走 cancel）。
- 注意点：`reconcileOnStartup`（`:772-788`）全仓无调用者（导出但死代码）；cron 的 KV 内 `lastStatus:'Running'` 在崩溃后不会被纠正（task 文件层被纠正为 INTERRUPTED/QUEUED），属**双端同构的表/文件双源不一致**（Android DataStore vs AgentTaskStore 同样分裂）。登记，不列缺陷。
- 取消保护反证：删除任务后 `prepareTriggeredRun` 返回 null（probe P4）→ 即使定时器泄漏，executor 会 `throw new Error('Cron task not found')`（`AppContainer.ets:446-448`）被 `fire` 捕获进 `markRunFailed`；`markRunFailed` 对不存在任务为 KV no-op、`AgentTaskStore.update` 返回 null（已 remove）→ 安全。

---

## 3. 已核对：判定不成立 / 反证（勿重复报告）

1. **「双开关（autoApproveAllToolCalls + autoApproveHighRiskToolCalls）绕过 alwaysAsk 的 sms/contacts/call 属漏洞」** — 代码顺序 `tool_permission.ts:188-195` 先于 `:196-198` alwaysAsk、`:201-216` mandatory；Android `PermissionDecisionResolver.kt:119-135` 同序，注释明示双开=无人值守。**刻意产品语义，假阳性**（probe P1e 复核）。
2. **ask_user 可被自动批准** — 否。`tool_permission.ts:185-187` 在双开关（:188）之前恒判 ask；probe P8：单开关与双开关均 `ask`。
3. **「run-trust 可绕过 wm_click/敏感工具」** — 生产不可达（D-05，未接线）。仅显式注入白名单才生效，报告勿当活跃绕过。
4. **推测执行会执行待审批工具** — 否。`speculativeEligible` 要求 needsApproval=false/mutates=false/risk=normal 且类目不在阻断集（`tool_policy.ts:351-368`），ask 恒不可达。
5. **tool_loop 取消后仍有工具副作用** — 否。`executeBatch` 在 `:270-271` 每次 runOne 前查 aborted，`:290-294` 顺序分支每步后 break；`tool_loop.ts:340-344` 执行前二次检查；probe P9b（预置 abort → 0 次执行/0 结果）。
6. **toolCallId 同毫秒碰撞**（round3 #16）— 已修复：`tool_activity.ts:203` 用 `${toolName}_${newId()}`。
7. **subagent 超时永久 RUNNING**（round3 #8）— 已修复：`subagent_manager.ts:550-555` 用显式 timeoutFlag 区分超时/取消并 `finish(timed_out)`；probe P7a（run=timed_out/task=TIMED_OUT）。
8. **cancel 与 start 竞态**（round3 #9）— 已修复：controller 在 admission 后即建（`:338`），`launchRun` 启动前复查 aborted（`:529-530`）；probe P7b（恰好运行 1 次、终态 cancelled/CANCELLED）。
9. **启动恢复复活禁用 cron**（round3 #12）— 已修复，见 D-12。
10. **AgentTaskStore.update 无法显式清 error**（round3 #11）— 已修复：`agent_task.ts:606-611` `undefined=保留 / null=清除`。
11. **`AppContainer` subagent 设置缓存不刷新**（round3 #73）— 已修复：`AppContainer.ets:565-568` `refreshSubAgentSetting`；`updateSubAgentSetting` 同步写缓存（:359-361）。
12. **`SettingCronTasksPage` 多处 Promise 无 catch**（round3 P2）— 已修复：`:42-45/86-88/132-134/150-153` 均有 catch + toast。
13. **cron 手动/定时并发**（round3 fix-plan 假阳性）— **前提失效，重新成立为 D-02**（不是复述旧结论：旧豁免依据「未接线」已不成立）。
14. **`SubAgentStore.readTail` 全文件入内存/切 surrogate**（round3 #17）— 已修复：`SubAgentStore.ets:130-146` offset/length 字节窗 + UTF-8 续字节前移。
15. **dynamic 上限分支不可达**（round3 #15）— 确认双端不可达（D-10），非缺陷。

---

## 4. 待验证 / 盲区

- **真实设备后台调度**：Harmony 用应用存活期 `setTimeout`，无 `WorkSchedulerExtensionAbility`。进程死后唤醒不可用（已在 `AgentCronStore.ets:46-48` 自认 P1）。本报告未验证前后台切换/低电量下 setTimeout 的漂移与暂停行为（无在线设备，`hdc list targets` 为空）。
- **KV RMW 串行化**：`replaceTasks` 的并发丢失更新（D-02/P2b）与 `getChatKvStore()` 的按键队列（round3 #71「平台层 RMW 无串行化」）重叠，归属 E；本报告只证明其放大 cron 双执行。
- **`AgentCronManager` 与 `AgentTaskStore` 双源一致性**：崩溃后 KV `lastStatus:'Running'` 与任务文件 `INTERRUPTED` 不一致（D-12 注意点），未在设备上验证 UI 呈现。
- **subagent history grant 的用户可见性**：grant 不落盘、无 UI 签发/审计入口，模型自供 scope（D-06）；需产品判定，未做内存外验证。
- **cron 会话绑定**：`createTask` 固定 `conversationId = newId()`（`agent_cron.ts:360`），entry 执行时该会话不存在则新建且 `assistantId:''`（`AppContainer.ets:454-466`）→ 永远走默认 runtime，不继承任务创建时的助手。Android 同为新 id，但经 `ChatService.sendMessage` 解析，语义略有差异；未在设备验证，列为待验证。
- 未覆盖：`AgentTaskScheduler`/`AgentTaskTools` 六件行为、`builtin_introspection_tools`（tool_search/tools_list 展开细节）、`tool_merge`/`tool_profile_filter`（仅定位）。属 B/其他专题或低风险。

---

## 5. 历史状态对照（本轮重新确认）

| round3 项 | 当前状态 | 证据 |
|---|---|---|
| #8 subagent 超时收口 | 已修复 | `subagent_manager.ts:550-555`；probe P7a |
| #9 cancel/start 竞态 | 已修复 | `:338/:529-530`；probe P7b |
| #10 approval_required→RUNNING 无恢复 | **仍存在**（双端同构） | D-04；probe P6 |
| #12 禁用 cron 重启复活 | 已修复 | `agent_task.ts:412-424`；probe P5a |
| #15 dynamic 上限不可达 | 同构死代码 | D-10 |
| #16 toolCallId 碰撞 | 已修复 | `tool_activity.ts:203` |
| #17 readTail 整文件/切字符 | 已修复 | `SubAgentStore.ets:130-146` |
| #11 error 清除语义 | 已修复 | `agent_task.ts:606-611` |
| #73 subagent 设置缓存 | 已修复 | `AppContainer.ets:565-568` |
| P2 cron 手动/定时并发（被判假阳性） | **豁免前提失效，重新成立** | D-02；probe P2/P2b/P10b |
| 双开关无人值守（假阳性 #1） | 确认刻意 | probe P1e/P8；Android :119-135 |
| grant 自动铸造（假阳性） | 确认有界刻意，登记残余风险 | D-06 |
| cron 执行器「未接线」 | 已接线但**执行体降级** | D-03 |

---

## 6. 最小修复优先级建议（只列 D 面，不含 F 主报告）

1. **D-01**（1 行）：wm_click 补 `allowsAutoApproval:false, mandatoryApproval:true`。
2. **D-02**（~15 行）：AgentCronSchedulerPort 加 workName 级 in-flight 门，`fire` 丢弃/替换在跑触发。
3. **D-03**（~40 行）：cron 执行器改走 `runChatTurnWithTools` + 组装 cron tool loop（含审批 flags、`invocationContext:'cron'`、cron 前缀提示）。
4. **D-04**（~20 行）：`finish` 允许 approval_required 终态化并释放并发；`cancel` 对 approval_required 生效。
5. **D-05**（~15 行）：`applyToolVerdict` 收集已批准工具名 → `buildToolLoop` 注入 `autoApprovedToolNames`。
6. D-06：待产品确认后再定（若需用户签发，走 mandatoryApproval 前置而非改 store 边界）。
7. D-07：接线 `fireDueCronTasks` 前必须先补原子 claim。
8. D-09：`retryable = (status==='failed')`。

每项回归测试建议已附在对应条目；D-01/D-04/D-05 可在 `harmony/chat` 纯逻辑层单测（无需设备），D-02/D-03/D-07 需 entry 层 mock 或设备目测。
