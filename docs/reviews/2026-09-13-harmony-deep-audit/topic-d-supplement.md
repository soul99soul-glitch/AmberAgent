# 专题 D 补充 — 高风险盲区深读 + D-11 复核

补充 `topic-d-agent.md` 中标为「未覆盖/仅定位」的高风险面：**AgentTaskScheduler / AgentTaskTools 六件 / tool_profile_filter / tool_merge**，并按要求**重开 D-11**（不再以「Android 同构」直接排除），回答「`继续写首诗` 是否构成用户承诺、是否 bypass mandatory」。

独立复核（review-df）正在复核主报告；本文不重复 D-01..D-10/D-12 的论证，只做增量与 D-11 修订。

新增隔离 probe：`probes-d/topic-d-probe3.ts`（14 项全通过，真实 `.ts` 逻辑 + 内存假端口，无网络/模型/文件副作用）。日志追加至 `probes-d/probe-output.log`。

---

## 1. D-11 修订：文本「继续」批准 —— 不是用户承诺，且确实 bypass mandatory/alwaysAsk

### 1.1 触发链（Harmony 实测）

1. 模型第 N 步产生工具调用，`dispatcher.resolveDecision` 返回 `ask`，`tool_loop.ts:309-315` 把该工具 `approvalState` 置 `pending` 并 `break` 等用户；生成本轮结束（`sending=false`）。
2. 用户在输入框发送**纯文本**消息。`ChatPage.sendParts`（`:3840-3862`）→ `runSendParts`（`:3864`）→ 在派发前调用 `resolveIdleToolBlocker(conv, answerText, isPureText)`（`:3958`）。
3. `tool_approval.ts:168` `explicitApproval = isPureText && isToolApprovalContinuation(answer)`；命中后 `:185-188` 把末条 assistant 消息里**所有** `pending` 工具无条件改为 `{ type:'approved' }`，`shouldResume=true`，随后 `runToolLoopContinuation` 走 resume 路径直接执行。
4. `tool_permission.ts:182-184`：`approvalState !== 'auto'` 即 `allow('User already decided.')`。

### 1.2 匹配宽度（`tool_approval.ts:31-39`）

- 词表 `TOOL_APPROVAL_CONTINUATION_WORDS`（`:23-26`）含 `继续/执行/确认/同意/批准/ok/yes/y/continue/goahead/approve/approved`；
- 另加 `compact.startsWith('继续')` 与 `startsWith('可以继续')`；
- `compact` 只去空白与中英标点（正则 `:34-35`），**不去除后续正文**。

因此纯文本 **`继续写首诗` → 批准**（probe D11b/D11c 实测），`继续吧` 亦批准（D11d）。

### 1.3 是否「用户承诺」？—— 否，UI 从未把打字定义为批准

Harmony 的 pending 呈现（实读）：

- `ChatMessageTools.ets:34-36`：`pending` → `ToolStatus.WaitingApproval`；`:66` 文案「待授权」。
- `AgentToolCapsule.ets:141-162`：唯一显式承诺入口是 **✕（拒绝，accessibilityText「拒绝工具执行」）与 ✓（批准，accessibilityText「批准工具执行」）** 两个按钮；`:81` 状态「待授权」。
- `ChatPage.ets:4364-4365/4393-4394/4425-4426`：✓ 才映射 `handleToolApproval(id, true)`，✕ 映射拒绝弹窗。

全仓检索**没有任何 UI 文案提示「回复继续即可批准」**（`grep 继续/待授权/批准` 于 `components/`、`pages/` 未见审批引导；`QuickMessagesPage.ets:24` 的快捷语「继续 / 请继续上面的内容」是通用聊天快捷语，且该 seed 文本以「请」开头、**不**命中词表）。即：用户在界面上看到的承诺动作是点 ✓；`继续写首诗` 是一个自然的聊天续写请求，UI 并未把它定义为对工具的风险承诺。→ **不构成用户承诺**。

对照 Android（`ChatMessageTools.kt:729-758`）：同样只有 deny/approve 两个 `WorkspaceIconButton`，同样无文本引导。故这是**双端共有的模糊同意缺陷**，而不是「Android 同构所以假阳性」。契约明确：Android 同款不构成反证。

### 1.4 是否 bypass mandatory / alwaysAsk？—— 是（probe 实证）

`resolveIdleToolBlocker` 直接改写 `approvalState`，**完全不经过 `PermissionDecisionResolver`**，因此：

| 工具类别 | 正常路径（点击 ✓ 之外） | 文本「继续」路径 |
|---|---|---|
| `mandatoryApproval`（如 wm_eval） | 仅「双开关无人值守」或逐次人工（`:201-216`） | 直接 approved → allow（probe D11c） |
| `alwaysAsk`（sms_send/contacts_write/call_phone direct） | 恒 `ask`，优先于双开关（`:196-198`） | 直接 approved → allow（probe D11d） |
| `allowsAutoApproval:false`（workspace 写、memory、cron、deepread 等，实仓 18+ 处） | 单开关也不能自动批准 | 直接 approved → allow |

probe 关键断言：
- D11b：`resolveIdleToolBlocker(conv, '继续写首诗', true)` → `shouldResume=true`、工具 `approvalState='approved'`、`canToolResumeExecution=true`。
- D11c：该 approved 的 wm_eval 在**所有自动批准开关关闭**下 `resolveDecision` → `allow`（source=`approval_state`）；对照新建 auto 态同工具 → `ask`。
- D11c2：resolver 对**任何**非 auto 状态（含 `pending`）都返回 allow —— pending 的兜底只存在于 `dispatcher.execute`（返回 null），单独调用 `resolveDecision` 会放行。
- D11d：sms_send（`policy.alwaysAsk=true`）经文本「继续吧」→ allow。
- D11e 反证：无关文本（「今天天气不错」）**不**批准，而是取消陈旧工具（`denied`）。
- D11f 反证：非纯文本（图文）消息即使含「继续」也不批准。

### 1.5 与产品承诺冲突（Harmony 自身文案）

UI 明确承诺逐次人工、不可自动批准：
- `SettingExperimentalWebMountPage.ets:263`：「每次调用仍需逐次确认」；
- `ChatAssistantLocalToolsPage.ets:100`：「每次调用仍需要人工逐次确认，不可自动批准」；
- `ChatAgentPermissionsPage.ets:48-49` 高风险开关确认文案亦以「逐次」为默认预期。

文本宽批准使这些承诺在「待授权 + 发送含继续语义的文本」场景下失效。

### 1.6 结论与修复建议

- **等级 P2（中高置信）**：模糊同意导致高危工具在无显式承诺下执行。前提：存在 pending 工具且用户发送以「继续」开头（或命中词表）的纯文本。
- **不新增 Harmony 回归定性**：与 Android 同构（`ChatService.kt:945-953` 逐字可比对），属双端共享缺陷；按契约「Android 同款不排除」，登记为真实问题并建议双端同步修。
- **最小修复（择一，改动小）**：
  1. 收紧词表/前缀：去掉 `continue` 之外的宽前缀兜底，仅精确命中词表；或
  2. **按风险分级**：`resolveIdleToolBlocker` 增加 policy 入参，对 `mandatoryApproval`/`alwaysAsk`/`allowsAutoApproval===false` 的 pending 工具，文本「继续」只置「可由用户在卡片点击」而不自动 approved（保留卡片 ✓ 路径）；或
  3. UI 侧在 pending 卡片旁明确提示「回复『继续』等同批准」，把隐式变为显式承诺。
- **回归测试**：`resolveIdleToolBlocker(convPending(wm_eval), '继续写首诗', true)` 断言 wm_eval 仍 `pending`（不 approved）；普通非 mandatory 工具仍允许文本续跑。

---

## 2. AgentTaskScheduler 深读（此前仅定位）

### 2.1 实现与接线

- 实现：`harmony/chat/src/main/ets/chat/agent_task_scheduler.ts:34-148`。全部方法都是 `AgentTaskStore` 的薄门面：`enqueue :43-57`（强制 QUEUED/QUEUED/ACTIVE）、`start :60-74`（RUNNING/ACTIVE/ACTIVE）、`complete :77-84`、`fail :87-95`、`cancel :98-100`、`retry :103-105`、`cleanup :108-110`、`reconcileOnStartup :113-115`、`list :118-120`、`read :123-125`、`status :128-147`（byType 用 Map 插入序，等价 Kotlin `LinkedHashMap`）。
- Android 基准 `feature/task/src/main/kotlin/app/amber/feature/task/AgentTaskScheduler.kt` **逐方法同构**（含 `AgentRuntimeStatus`/`AgentTaskAdapter` 字段与顺序）。→ 本层无移植偏差。
- 接线：`entry/.../AppContainer.ets:419-435`：`getAgentTaskScheduler()` 复用 `getAgentTaskStore()` Promise 单例（Koin single 等价）；`getAgentTaskTools()` 注入 `outputExists` = `createEntryAgentTaskFilePort().exists`。目录/文件端口 = `AgentCronStore.ets:55/70-138`（`filesDir/amberagent/tasks`）。

### 2.2 关键发现

**S-1 [P3｜高置信] `reconcileOnStartup` 在 entry 无调用者，但启动恢复仍发生（反证为主）**

- `AgentTaskScheduler.reconcileOnStartup :113-115` 与 `AgentTaskStore.reconcileOnStartup :772-788` 在 entry 无任何调用点（grep 确认）。
- 但 `AgentTaskStore.create`（`:534-540`）→ `loadSnapshots`（`:790-809`）对每个文件调用 `recoveryManager.recoverOnStartup`，与 `reconcileOnStartup` 走同一 RecoveryManager，且逐条回写。故**启动恢复语义未丢失**；差异仅：`reconcileOnStartup` 的「返回 + 按 updatedAtMs 排序」聚合视图无人消费。
- 结论：登记为死代码/接口冗余，非数据缺陷。**这是对主报告 D-12 注意点的补充反证**：不必因 `reconcileOnStartup` 未接线而怀疑恢复未生效。

**S-2 [P3｜高置信] `AgentTaskAdapter` 接口未接线（无 per-type 并发/取消适配器）**

- `agent_task_scheduler.ts:28-32` 定义 `AgentTaskAdapter { type, maxConcurrency, cancel }`，但全仓无实现、无注册、无消费（grep 确认）。Android 同文件同样只是接口声明。
- 实际取消能力来自 `AgentTaskStore.register(snapshot, cancel, retry)` 回调（subagent_manager.ts:378-384 注入；cron.ts:368 不注入）。→ 与 `AgentTaskAdapter.maxConcurrency` 相关的按类型限流**未实现**（Harmony 与 Android 一致，两端的并发限制实际由各自 manager 自行控制）。

**S-3 [P3｜中置信] `status().byType` 与 `list()` 的 running 优先排序无消费差异** — `list :654-668` running 优先、再 updatedAtMs 降序；`status :128-147` 重新遍历。无缺陷，仅记契约。

---

## 3. AgentTaskTools 六件深读（此前仅定位）

### 3.1 实现与入口

- 实现：`harmony/chat/src/main/ets/chat/agent_task_tools.ts`。六件顺序 `tools() :132-141`：`agent_task_list / read / cancel / retry / cleanup / runtime_status`。
- 审批档位（应重点核对）：`list :147-167`、`read :169-184`、`runtime_status :238-261` **无 needsApproval**（只读）；`cancel :186-199`、`retry :201-214`、`cleanup :216-236` 仅 `needsApproval: true`，**未设 `allowsAutoApproval:false`/`mandatoryApproval`**。
- Android 基准 `feature/tools/impl/.../AgentTaskTools.kt:76-120`：cancel/retry/cleanup 同样仅 `needsApproval = true`。→ 档位同构。
- 入口：`ChatPage.ets:911-915` 在 `aboutToAppear` 水合 `agentTaskToolsCache`，`:3066` 无条件挂入工具表（位置 = conversation_queue 之后、permissions_status 之前，对齐 Android `ChatService.kt:2299`）。工具真的可被模型调用。

### 3.2 关键发现

**S-4 [P3｜高置信] `agent_task_cleanup` 在单开「自动批准」下可静默删除任务 transcript（真实可达）**

- probe D12f：`toolInvocationPolicy(agent_task_cleanup, {delete_private_output:true})` = `mutates=true / risk='sensitive' / needsApproval=true / autoApprovable=true / mandatoryApproval=false`。
- 因此仅开 `autoApproveAllToolCalls`（或会话级 `autoApproveToolCalls`）即可自动批准，进而删除 `filesDir/amberagent/subagents/runs/<runId>.jsonl` 等 app-private 产物（`agent_task.ts:755-770`）。
- 边界（反证，已 probe D12e）：`privateOutputFile :811-818` 用 `isPathInside(appFilesDir, path)` 限制，**workspace 文件永不删**；`delete_private_output` 默认 false。
- 定性：与 Android 同构（默认 `allowsAutoApproval=true`），非 Harmony 回归；但属「删除类操作却可被单开关静默批准」的档位设计观察，建议与 D-01 一并评估是否把 `*_cleanup` 提到 `allowsAutoApproval:false`。

**S-5 [P2｜高置信] `agent_task_cancel` 对 cron 任务恒不可用（cancelCapability=false）**

- probe D12d：cron 快照（`agent_cron.ts:735-752` 硬编码 `cancelCapability:false`）经 `agent_task_cancel` → 返回 `status='running'` 且 `error='Task cannot be cancelled from AmberAgent.'`（`agent_task.ts:680-684`）。
- 即：模型想通过看板停掉一个正在运行的 cron 任务，工具层无法做到；只能等它自己跑完（且按 D-02，期间还能被重复触发）。
- 边界：subagent run 的 cancelCapability 在 running 时为 true（`subagent_manager.ts:759`），可取消（probe D12c：callback 被调用 1 次，终态 cancelled）。
- 修复建议：cron 的 `toAgentTaskSnapshot` 在 `lastStatus==='Running'` 时置 `cancelCapability:true`，并注册一个取消回调（若能拿到在跑会话的 abort）。或至少在 `agent_cron` 提供 cancel 语义。

**S-6 [P2｜高置信] `agent_task_retry` 对 subagent / cron 恒为 no-op（retryable=false）**

- probe D12b：FAILED subagent 快照经 `agent_task_retry` → `status='failed'`、`last_error_code='retry_not_allowed'`。
- 根因：`subagent_manager.ts:750-755` 硬编码 `retryable:false`（Android `SubAgentManager.kt:381-386` 为 `retryable = status==FAILED`；cron 未设 retryPolicy → 默认不可重试）。→ Harmony subagent 缺失「失败重跑」能力，与 Android 有偏差（非纯同构）。
- 且即便 retryable=true，`retryCallbacks` 也无人注册（subagent_manager/cron 的 `register` 只传 cancel），会走 `retry_adapter_missing`（`agent_task.ts:715-722`）。→ 修复需同时补 retryable 与 retry 回调。
- 与主报告 D-09 一致，此处补工具层实测。

**S-7 [P3｜中置信] `output_exists` 对 `outputRef` 非空任务使用陈旧持久化值**

- `agent_task_tools.ts:77-82`：`outputRef !== null` 时直接用 `snapshot.outputRef.exists`，不调用 `outputExists` 端口重探；仅 `outputPath` 分支才实时探测。
- 影响：删除私有输出后，`agent_task_list/read` 的 `output_exists` 可能仍显示 true。低危（展示字段），注册即可。

**S-8 [P3｜高置信] 只读三件在 `minimal` profile 下保留、写入三件被过滤（反证）**

- probe D12g：`filterToolProfile(allSix, 'minimal')` → 保留 `agent_runtime_status / agent_task_list / agent_task_read`，过滤 3 件并通过 `filteredCount` 反映。
- 说明严格 profile 下模型看不到 cancel/retry/cleanup。但注意：**默认 profile 是 `full`**（`assistant.ts:140`），S-4/S-5 的可达性以 full 为前提。

---

## 4. tool_profile_filter 深读（此前仅定位）

### 4.1 实现与入口

- 实现：`harmony/chat/src/main/ets/chat/tool_profile_filter.ts`。四组常量与 `isAllowed`（`:129-150`，`full/minimal/web_read/workspace_read/coding/mobile_control` 逐字）、`filterToolProfile`（`:153-179`，`full` 直通；否则白名单/类目过滤 + `filteredByCategory` 插入序）。
- Android 基准 `feature/tools/api/.../ToolProfileFilter.kt` 逐字同构（`toolCategory` 复用 `tool_policy.ts:179`，与 Android `ToolRegistry.kt:424-457` 对齐）。
- 入口：`ChatPage.ets:2943-3101` —— **先** `filterToolProfile(profiledTools, rt.assistant.toolProfile)`（`:2944`），**再** `createToolRegistry(profiledTools)`（`:3102`），`baseToolsCache = registry.tools() + introspection`（`:3106-3109`），最终 `tools = baseToolsCache + createToolSearchTool(registry, profile) + supervisorTools`（`:3120-3124`）。

### 4.2 关键发现

**S-9 [P3｜高置信] `tool_search` 的 `profile` 参数仅作 trace，不参与过滤 —— 但 profile 保证是结构性的（反证为主）**

- `builtin_introspection_tools.ts`：`searchPayload` 接收 `profile`（`:443-448`），但仅用于 trace 键 `profile/profile_filtered`（`:480-483`）；`searchIndex` 未按 profile 过滤。
- 初看像「tool_search 可暴露被 profile 过滤的工具」，**但实际不成立**：`createToolSearchTool` 收到的 `registry` 是**在 `filterToolProfile` 之后**构建的（`ChatPage.ets:3102` 与 `:3122` 同一 registry），故索引里本就没有被过滤工具。`tools_list`/`tool_policy_explain` 亦基于同一 registry。
- 结论：profile 隔离由「先过滤、后建 registry」结构保证，`profile` 形参是遥测冗余（Android 同构，`ToolSearch.kt` 相同）。**登记为可读性/误报点，非越权**。

**S-10 [P3｜高置信] `isAllowed` 无 default 分支返回 undefined**

- `:129-150` 的 `switch(profile)` 覆盖全部 6 个联合值（`assistant.ts:33-` 类型），TypeScript 联合穷尽；若未来新增 profile 而漏改，会静默返回 `undefined`（falsy）→ **该 profile 下所有非 minimal 工具被丢弃**（fail-closed，安全方向）。登记为维护性提示。

**S-11 [P3｜高置信] `coding`/`mobile_control` 按类目放行，含写工具（刻意）**

- `PROFILE_CODING_CATEGORIES`（`:114-119`）含 `workspace`，而 `file_write/file_edit/file_move` 的 category 均为 `workspace`（`tool_policy.ts:181-183`）；`mobile_control` 含 `system`（含 terminal/system 写）。
- 即「coding」「mobile_control」并非只读档位，模型可见写工具；写入仍受审批门约束（`tool_policy` 派生 needsApproval）。属 Android 同构刻意设计，非缺陷；报告此点以免被误读为只读档位。

---

## 5. tool_merge 深读（此前仅定位）

### 5.1 实现与入口

- 实现：`harmony/chat/src/main/ets/chat/tool_merge.ts`。`findToolMergeTarget :66-82`（排除已执行 `isToolExecuted`；非空 id 先按 id、失败回退 `streamToolIndex`；空 id 仅按 index；均无 → 末个未执行；blank+index 找不到 → null 新建）、`mergeTool :85-109`（默认 append `toolName/input`；`stream_tool_args_replace` → 整体替换；空 input 不覆盖；后到非空 id 采纳；replace 标记合并即剥离）、`withoutStreamArgsReplace :55-63`。
- 入口（provider 解析层）：`stream_accumulator.ts:242/247`（单 tool 合并）与 `:414/418`（并行 tool 列表合并），以及元数据打标 `claude_parse.ts:173` / `google_parse.ts:211`（`withStreamToolIndex`）、`openai_responses_parse.ts:402/565`（`withStreamArgsReplace`）。

### 5.2 关键发现

**S-12 [P3｜中置信] `mergeTool` 对非 replace 路径无条件字符串拼接 input，依赖 provider 不重复发出累积 args**

- `:89-90` `input = target.input + incoming.input`。若某 provider 在 final message 中再次发全量 args 而未打 `stream_tool_args_replace`，会得到 `{...}{...}` 之类非法 JSON，进而被 `tool_dispatcher` 的参数校验钩子拒绝（`tool_dispatcher.ts:132-137`）→ 工具不执行，安全兜底。
- 属 provider 协议层风险（C 专题），此处只标注接缝：`mergeTool` 不做 JSON 合法性探测。openai_responses 已用 replace 标记规避（`:402/565`）；claude/google 用 index 分组。
- 反证：`findToolMergeTarget` 排除已执行工具（`:72`）避免多轮 tool 循环共用同一 assistant 消息时把新一轮 delta 串进上一轮 —— 该保护存在且正确（注释 `:70-72`）。

**S-13 [P3｜高置信] `mergeTool` 的 `toolCallId` 采纳逻辑正确（后到非空优先）** — `:100`；与 OpenAI 流式真实 id 晚到一致。无缺陷。

---

## 6. 补充覆盖状态与盲区更新

| 面 | 状态 | 结论摘要 |
|---|---|---|
| `agent_task_scheduler.ts` | 已全文阅读 + 接线追踪 | 薄门面，与 Android 同构；`reconcileOnStartup`/`AgentTaskAdapter` 为未消费接口（S-1/S-2） |
| `agent_task_tools.ts` 六件 | 已全文阅读 + entry 接线 + probe | 档位与 Android 同构；cleanup 单开关可静默删私有输出（S-4）；cron cancel 恒不可用（S-5）；retry 恒 no-op + 无回调（S-6，与 D-09 一致） |
| `tool_profile_filter.ts` | 已全文阅读 + 入口 | 与 Android 逐字；profile 隔离结构性成立（S-9）；coding/mobile_control 含写工具（S-11，刻意） |
| `tool_merge.ts` | 已全文阅读 + 调用点 | 排除已执行 + replace 语义正确；非 replace 拼接依赖 provider 契约（S-12，转 C） |
| D-11 | 已修订 | 文本「继续」非用户承诺；bypass mandatory/alwaysAsk/allowsAutoApproval=false；P2，双端共享，需产品/UI 决策 |

新增待验证：
- cron 运行中「取消」产品预期（S-5）：Harmony 无 WorkManager `cancelUniqueWork` 等价物，需产品定义是「停会话生成」还是「仅停止后续调度」。
- `agent_task_retry` 若补齐 retryable/回调，其「新起隔离 run」的语义（Android 注释）在 Harmony 是否可复用 subagent_manager.start 的 task 重放。
- S-12 在真实 Claude/Google 流式下的 args 重复发送频率（无真实模型调用，不验证）。

盲区（未变）：无在线设备；未验证 UI 实际布局/暗色下的审批卡片；未做真实后台调度。
