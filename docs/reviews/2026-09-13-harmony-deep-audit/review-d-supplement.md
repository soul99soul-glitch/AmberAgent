# 独立有界复核：D-11（文本「继续」批准）、S-5（cron 不可取消 / 删运行中 cron）、S-4（cleanup 授权语义）

复核者：独立 agent（非 D owner）。基线：`harmony-preview`，HEAD `bdce551dd373f3d7e4c393b194997cae19289c40`，当前工作树。
范围：仅这三项做有界复核；S6–S13 按主代理要求明确不计（理由见 §4）。
方法：亲读源 + 调用上下文；真实 `chat-domain` `.ts` 逻辑 + 真实 entry scheduler（转译 `AgentCronStore.ets`，注入假 `setTimeout`）+ 内存假端口。无网络/模型/文件/真实定时器副作用。
新增证据：`probes-df/probe-d-supplement.ts`（**28 断言全 PASS，exit 0**）。另独立重跑 `probes-d/topic-d-probe3.ts`（14 PASS，exit 0）与 D/F 其余 probe 一致。

结论：

| 项 | D 补充原判 | 本复核 | 定级 |
|---|---|---|---|
| D-11 文本「继续」宽批准 | P2 中高 | **确认（真实且非用户承诺）** | **P2 高置信**（处于 P2 上沿，见 §1.4） |
| S-5 cron 不可取消 / 删运行中 cron | P2 高（仅工具层） | **确认并扩展：删除也不停**，根因不同于 D-02 | **P2 高置信** |
| S-4 cleanup 授权语义 | P3（档位观察） | **确认非「授权契约破坏」**；但发现**缺终态门**（描述 vs 实现不一致） | **S4 本体 P3；新增缺门 P3** |

---

## 1. D-11：文本「继续」批准 pending 工具

### 1.1 触发链（亲读，行号 1-based）
1. 模型产生工具调用 → `dispatcher.resolveDecision` 返回 `ask` → `tool_loop.ts:309-315` 置该工具 `approvalState='pending'` 并 `break`，本轮结束。
2. 用户发送**纯文本** → `ChatPage.ets:3952-3958` 计算 `isPureText`/`answerText`，调用 `resolveIdleToolBlocker(conv, answerText, isPureText)`（:3958）。
3. `tool_approval.ts:168` `explicitApproval = isPureText && isToolApprovalContinuation(answer)`；命中后 `:185-188` 把末条 assistant 里**所有** `pending` 工具无条件改为 `{type:'approved'}`，`shouldResume=true`；`ChatPage.ets:3962-3977` 走 `runToolLoopContinuation`（resume 路径直接执行）。
4. `tool_permission.ts:182-184`：`approvalState !== 'auto'` → `allow('User already decided.')`。

### 1.2 匹配宽度
`tool_approval.ts:31-39`：`compact` 只去空白与中英标点（**不去后续正文**），命中词表（`:23-26`，含 `继续/确认/ok/yes/y/continue/approve` 等）或 `startsWith('继续')`/`startsWith('可以继续')`。故 `继续写首诗`、`继续吧` 均命中。

### 1.3 独立验证（`probe-d-supplement.ts`）
- `D11-1` 「继续写首诗」匹配；`D11-2` 「继续吧」匹配；`D11-3` 「今天天气不错」不匹配。
- `D11-4` 快捷语 seed「请继续上面的内容」**不**匹配（以「请」开头，词表外）→ supplement 的“快捷语非隐藏批准”判断成立。
- `D11-5` 真实 blocker 把 pending `wm_eval` 置 `approved`、`shouldResume=true`。
- `D11-6` 该 approved 的 mandatory+high `wm_eval` 在**所有自动批准开关关闭**下被 resolver `allow`（source=`approval_state`）。
- `D11-7` 反证：同一 mandatory 工具处 `auto` 态、单开关仍 `ask`（证明正常审批门本身有效，问题出在 blocker 越权改写）。
- `D11-8` 结构事实：`resolveIdleToolBlocker` **不接收 policy、不引用** `mandatoryApproval/alwaysAsk/PermissionDecisionResolver/toolInvocationPolicy` —— 批准是裸状态改写，绕过整个审批链。
- `D11-9/10` UI 只有 ✓/✕ 两个按钮（`AgentToolCapsule.ets:141-162`，accessibilityText `批准工具执行`/`拒绝工具执行`），**全仓无任何「回复继续即批准」引导文案**。

### 1.4 判定：P2 高置信（真实问题，非 pending；Android 同款不作反证）
- 是真实缺陷，且**不是用户承诺**：用户在待授权卡片上被明确引导只能点 ✓；`继续写首诗` 是自然聊天续写，UI 未把它定义为风险承诺。
- bypass 范围确认为：`mandatoryApproval`（wm_eval 等）、`alwaysAsk`（sms_send/contacts_write/call_phone direct）、`allowsAutoApproval:false`（workspace 写/cron 等）——因为 blocker 直接落 `approved`，不经过 resolver，任何 `resolve` 分支都拦不住。
- **与 Harmony 自身文案冲突**（构成“破坏承诺”，不只是设计选择）：
  - `SettingExperimentalWebMountPage.ets:429`「点击类操作(wm_click)与站点增删均需逐次批准」；
  - `SettingExperimentalWebMountPage.ets:263`「每次调用仍需逐次确认」；
  - `ChatAssistantLocalToolsPage.ets:100`「每次调用仍需要人工逐次确认，不可自动批准」。
  文本宽批准使这些承诺在「待授权 + 发送含继续语义的纯文本」场景下失效。
- 定级理由：确定性代码路径 + 可自然触发 + 击穿强制审批，故不是 pending；但需要用户**主动发送**该文本，故判 **P2（高置信，P2 上沿）**。若威胁模型把「pending 敏感工具待授权」视为受保护状态，可上调 P1；本人建议 P2 并注明该边界。
- 契约说明：Android `ChatService.kt:945-953`/`:869-943` 逐字同构，但按审查契约「Android 同款不构成反证」，仍为真实缺陷，建议双端同步修。
- 最小修复（择一）：(a) blocker 增加 policy 入参，对 `mandatoryApproval`/`alwaysAsk`/`allowsAutoApproval===false` 的 pending 只提示而不自动 approved；(b) 收紧词表，去掉 `startsWith('继续')` 宽前缀与 `y/ok/yes` 裸词；(c) UI 在 pending 卡片显式声明「回复『继续』等同批准」。回归：`resolveIdleToolBlocker(convPending(wm_eval),'继续写首诗',true)` 断言 `wm_eval` 仍 `pending`；非强制工具仍允许文本续跑。

---

## 2. S-5：cron 无法取消 + 删除运行中 cron 不停

### 2.1 代码事实
- `agent_cron.ts:743` cron 快照**硬编码 `cancelCapability:false`**；`agent_task.ts:680-684` 的 `agent_task_cancel` 因此恒返回 `error='Task cannot be cancelled from AmberAgent.'`（probe3 D12d 一致）。
- cron 注册任务**不传 cancel 回调**：`agent_cron.ts:368` `agentTaskStore.register(snapshot)`（对比 subagent `subagent_manager.ts:378-384` 传 cancel）。
- `AgentCronRunExecutor = (taskId: string) => Promise<void>`（`AgentCronStore.ets:145`）**无 AbortSignal**；`AgentCronStore.ets:186-204` `fire` 无取消入口。
- `scheduler.cancel`（`AgentCronStore.ets:219-221`）**只 `clearTimer(workName)`**，不触及在跑的 `fire`。
- `agent_cron.ts:434-443` `deleteTask`：`replaceTasks` 删任务 + `scheduler.cancel(workName)` + `agentTaskStore.remove(id)`；**无任何 abort/executor 取消调用**。

### 2.2 独立验证（真实 manager + 真实 entry scheduler + 假时钟；`probe-d-supplement.ts`）
用受控 gate 让 `runExecutor` 模拟长时生成，在运行中删除任务：
- `S5-1` executor 真实启动；`S5-2` 快照 `cancelCapability=false`；`S5-3` 注册**未带** cancel 回调。
- `S5-4` `deleteTask` 返回 true 且任务从列表移除。
- `S5-5` 在跑的 run **未被打断**（executor 仍在 await）。
- `S5-7` 解除 gate 后 run **照常完成**（删除后模型继续）。
- `S5-8/S5-9` 已删任务**不被重排**、也**不被 `markRunCompleted` 复活**（该部件安全）。
- `S5-10` `deleteTask` 体内无 abort/executor-cancel；`S5-11` scheduler.cancel 体仅 `clearTimer`；`S5-12` executor 类型无 signal。

### 2.3 「模型继续并写新会话」——确认，但需前提
- entry 执行体（`AppContainer.ets:441-491`）在 `runExecutor` 内先 `listTasksSnapshot()` 找到任务并**捕获到局部 `task`**；随后 `runChatTurn` → `getChatRepository().save(out)`（:483）。删除任务只删**任务快照**（`agentTaskStore.remove`），**不删聊天会话**。
- 因此：**若删除发生在该 run 已启动（已过 `listTasksSnapshot`）之后**，模型继续跑完，并把结果 `save` 到 `task.conversationId` 对应会话（首次运行时会新建该会话，`assistantId:''`，见 `AppContainer.ets:454-467`）→ 一个**不隶属任何 cron 任务**的可见会话被写入。
- 前提边界（不夸大）：若删除发生在 executor 启动**之前**，`fire` 的 `prepareTriggeredRun` 返回 null（D 的 P4 已证），则不执行。故本项仅对“已启动的在跑 run”成立。
- 用户可用性：cron run 是 headless、无 ChatPage generation token，用户**没有任何停止入口**（工具 cancel 恒失败、删任务不停、无 AbortSignal）。

### 2.4 判定：P2 高置信，**根因与 D-02 不同，不合并**
- D-02 根因 = 缺运行锁（重叠触发双执行）；S-5 根因 = **cron 任务无取消能力**（cancelCapability 硬编码 false + 无 abort 回调/信号 + deleteTask 只清 timer）。即使完全不重叠，S-5 依然发生。
- 当前影响有界（D-03：cron 运行体暂无工具，仅一次模型调用 + 写会话）；D-03 修好后工具面加入，误删运行中任务的副作用将放大。
- 最小修复：`toAgentTaskSnapshot` 在 `lastStatus==='Running'` 时置 `cancelCapability:true`，并在 `register` 注册取消回调（需执行体持有 AbortController）；`deleteTask` 先 abort 在跑 run 再清 timer；或显式定义产品语义（「仅停后续调度」vs「停当前生成」）并在 UI 说明。

---

## 3. S-4：`agent_task_cleanup` 授权语义

### 3.1 独立验证（`probe-d-supplement.ts`）
- `S4-1` 策略：`mutates=true / risk='sensitive' / needsApproval=true`。
- `S4-2/S4-3` 单开全局「自动批准」→ `allow`；全关 → `ask`（`autoApprovable=true, mandatoryApproval=false`）。
- `S4-4` `delete_private_output=true` 时 app-private transcript 被删；`S4-5` **workspace 文件永不删**；`S4-6` 默认 `false` 时不删。

### 3.2 判定：**不是「授权契约破坏」**，本体 P3；另发现缺终态门（新 P3）
- cleanup 是**有界**删除：只删 app-private 产物（`privateOutputFile:811-818` 用 `isPathInside(appFilesDir,path)` 限制），workspace 永不触及；默认不删产物。单开关自动批准与用户显式开启的全局「自动批准」一致，属档位设计观察（与 D-01 一类），**非** D-11 那种“绕过强制审批且违背逐次承诺”的契约破坏。故 S4 本体维持 P3。
- **新增（本复核）缺终态门**：tool 描述（`agent_task_tools.ts:219`）写「Remove a completed, failed, interrupted, or cancelled … snapshot」，但 `AgentTaskStore.cleanup`（`agent_task.ts:755-770`）**无 `status` 检查**——可删除 `RUNNING` 任务，且 `cancelCallbacks.delete(taskId)` 会**一并删掉取消回调**。后果：对被 cleanup 的运行中任务，看板条目消失且**再也无法取消**（subagent 尤其：其 `cancelCapability` 本为 true）。前提：模型调用 `agent_task_cleanup`（needsApproval，单开关可静默批准）。定级 **P3**（有界、需审批、需恰好对运行中任务调用），修法：cleanup 增加终态校验（非终态返回 `not_terminal`），或在删回调前先触发 cancel。

---

## 4. S6–S13：按主代理要求明确不计（附一句理由）

- S-1 `reconcileOnStartup` 死代码：`AgentTaskStore.create→loadSnapshots→recoverOnStartup` 仍生效，功能未丢；无用户效果。
- S-2 `AgentTaskAdapter` 未接线：仅接口声明，两端一致；无用户效果。
- S-3 `byType`/排序差异：展示契约，无缺陷。
- S-6 `agent_task_retry` 恒 no-op：属功能缺失（已并入 D-09），非新用户效果。
- S-7 `output_exists` 用陈旧持久化值：仅展示字段。
- S-8 只读三件在 minimal 保留：正确行为。
- S-9 `tool_search` profile 形参仅遥测：profile 隔离由「先过滤后建 registry」结构性保证（`ChatPage.ets:3102/3122` 同一 registry），非越权。
- S-10 `isAllowed` 无 default：类型穷尽，未来漏改时 fail-closed 安全方向。
- S-11 coding/mobile_control 含写工具：刻意设计，写入仍受审批门约束。
- S-12 `mergeTool` 非 replace 拼接：provider 协议层风险（转 C），且非法参数被 dispatcher 校验钩子拒绝（安全兜底）。
- S-13 `toolCallId` 采纳逻辑正确：无缺陷。
以上均无独立用户效果，明确不计入本轮缺陷。

---

## 5. 给主报告的处理建议

- **D-11**：作为独立真实缺陷计入，**P2 高置信**（P2 上沿；若视 pending 敏感工具为受保护态可判 P1）。与 Android 同构不构成反证；建议双端修 + UI 文案二选一。
- **S-5**：从 D 补充的“工具层不可取消”升级为“**删除运行中 cron 也不停、且无任何停止入口**”，**P2**，与 D-02 分列不同根因。
- **S-4**：本体维持 P3（档位观察，非契约破坏）；新增 `cleanup` 缺终态门 + 删取消回调为 **P3**，建议一并修。
- **S6–S13**：不计。

## 6. 盲区
- 未在线设备：UI 审批卡片实际交互、`resolveIdleToolBlocker` 在图文混合/流式并发下的真实时序未目测。
- S-5 的“模型继续并写会话”经源码链 + service 层 mock 证实（run 已启动前提）；未跑真实 entry 执行体（需真实 provider/RDB）。
- D-11 的“用户是否真的会把『继续写首诗』当作非批准”属产品/UX 判断，本复核只证明代码与承诺不一致。
