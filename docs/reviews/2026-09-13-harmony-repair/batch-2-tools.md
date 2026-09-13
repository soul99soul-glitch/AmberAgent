# Batch 2B — 工具循环 / 审批 / 任务清理 / 子代理审批挂起

范围:R08(P2)、R15(P2)、R25(P3)、R14(P2)。仅改本批分配文件;未动
`ChatPage.ets` / `Index.ets` / `AppContainer.ets` / `chat|deepread` 的 `index.ts` /
协议解析与 provider 层(其他 owner 在改)。

## 修改文件

| 文件 | 修复 |
|---|---|
| `harmony/chat/src/main/ets/chat/tool_dispatcher.ts` | R08 |
| `harmony/chat/src/main/ets/chat/tool_loop.ts` | R08 |
| `harmony/chat/src/main/ets/chat/tool_approval.ts` | R08 / R15 |
| `harmony/chat/src/main/ets/chat/agent_task.ts` | R25 |
| `harmony/chat/src/main/ets/chat/subagent_manager.ts` | R14 |
| `harmony/chat/src/test/tool_dispatcher.test.ts` | 回归(既有,未改) |
| `harmony/chat/src/test/tool_loop.test.ts` | +1 R08 用例 |
| `harmony/chat/src/test/tool_approval.test.ts` | +3 R08/R15 用例、改 2 例 |
| `harmony/chat/src/test/agent_task.test.ts` | +1 R25 用例 |
| `harmony/chat/src/test/agent_task_tools.test.ts` | 改 1 例(终态前置) |
| `harmony/chat/src/test/subagent_manager.test.ts` | +1 R14 用例、改映射期望 |

## R08 — 空/重复 toolCallId 不再按 blank/子集位置猜

**根因**:旧 `rewriteLastMessageTools` 只吃结果数组,按位置游标 + 「双 blank 位置即对位」
配对;当批次是消息工具的真子集(如 resume 只含未执行工具)时,会把结果写到同 blank
的另一个 part。旧 `applyToolApprovalToConversation` 只按 id 匹配,blank 命中全部同 blank
part,单个批准=全批准。

**最小机制**:
- `tool_dispatcher.ts` 新增 `executeBatchPairs(): Promise<ToolBatchResultPair[]>` —
  返回 `{ input: 原 part 引用, result }`,复用/并行/顺序三路径均保持输入身份并保序。
  `executeBatch()` 保留为兼容壳(`pairs.map(p => p.result)`),既有调用方/测试不变。
- `tool_loop.ts` 写回改为 `rewriteLastMessageTools(conv, pairs: ToolBatchResultPair[])`,
  以**输入 part 对象身份**(`Map<part, result>`)定位;身份失配时仅当该 part 非空 id
  在批次内唯一才回退命中。审批写回同样构造 `tools[i] -> updatedTools[i]` 身份对。
  结果沿用 input 的 toolCallId(**不重写 provider / 不重写结果 id**),后到补全的真实
  id 仍由既有 provider merge 保留。
- `tool_approval.ts`:
  - 新增 `ToolApprovalPartLocator { messageId, partIndex }` 与
    `collectToolApprovalCandidates`。
  - `applyToolApprovalToConversation(conv, id, verdict, locator = null)`:空 id 或
    命中多处时**拒绝歧义、原样返回**(不再全批准);传 locator 时精确定位单个 part。

**回归**:`空白 id 双 part + 单批批准` 只改目标;真子集 resume 只写未执行 part;
重复真实 id 亦拒绝歧义(详见测试)。

## R15 — 自然语言不再批量批准

**根因**:`resolveIdleToolBlocker` 用 `isToolApprovalContinuation`(词表 + startsWith
「继续」)把纯文本新消息当批准,且改全部 pending;mandatory 工具也会被裸「继续」放行。

**最小机制**:删除自然语言批准分支。`resolveIdleToolBlocker` 仅在存在 **ask_user**
pending 且文本非空时置 `answered`(自由文本继续可用);其余非应答场景统一
`cancelToolForNewUserMessage`(陈旧工具取消,新消息正常派发),`shouldResume=false`、
`event='pending_tool_cancel'`。**明确批准仍走审批按钮途径**
(`applyToolApprovalToConversation` / `handleToolApproval`),不受影响。
`isToolApprovalContinuation` 保留为分类语义,不再用于放行批准(mandatory 也无法被
裸「继续」绕过)。

## R25 — cleanup 仅终态可清

**根因**:`AgentTaskStore.cleanup` 无终态门,可直接删 RUNNING/QUEUED(含子代理
approval_required 映射的 RUNNING)看板记录并删掉取消回调。

**最小机制**:`cleanup` 首行 `if (agentTaskStatusRunning(current.status)) return false;`
——覆盖 QUEUED/RUNNING 等一切非终态;COMPLETED/FAILED/CANCELLED/TIMED_OUT/
INTERRUPTED 仍可清。工具描述本已承诺「completed/failed/interrupted/cancelled」,行为现在
与文案一致。

## R14 — 子代理 approval_required 映射 INTERRUPTED 且可 cancel

**根因**:`statusToAgentTaskStatus('approval_required') = 'RUNNING'`,但 `finish` 只接受
`running` 状态收口,导致任务板停在 RUNNING、无续跑入口、cancel 无效。

**最小机制**(不造新审批 UI、不改协议):
- 新增 `isLiveRunStatus` = `running | approval_required`;`finish` 以此收口,故
  `cancel()` 对 approval_required 的 run 仍能终结为 cancelled(运行对象仍 live)。
- `statusToAgentTaskStatus('approval_required') = 'INTERRUPTED'`,任务板从裸 RUNNING
  变为**带原因的 INTERRUPTED**(`error` + `lastErrorCode='subagent_approval_required'`)。
- `TerminalTaskUpdate` / `scheduleTaskUpdate` 透传 `lastErrorCode`。

## 测试与校验

```
cd harmony/chat
npx tsx --test "src/test/**/*.test.ts"      # 2126 tests, 2126 pass, 0 fail
npx tsc --noEmit -p tsconfig.json           # 仅 1 个非本批错误(见下)
```

- 新增/改写用例:tool_loop +1(R08 真子集身份回写)、tool_approval +3(R08 歧义拒绝 +
  locator、R15 mandatory/续写句不批准)、agent_task +1(R25 非终态拒绝/终态可清)、
  subagent_manager +1(R14 approval_required→INTERRUPTED 有原因 + cancel 收口);
  共 6 个新用例,另改 3 个受影响旧例。
- 环境注记:`harmony/chat/src/test/agent_cron_regression.test.ts`(**本批之外、其他
  owner 新增的未跟踪文件**)存在 1 个 tsc 报错 `TS2345(124,41)`,与本批文件无关;
  已用 `git status` 确认其 untracked 归属。

## 共享接线需求(给主代理,我未动 ChatPage)

1. **R15(必须)**:`ChatPage.ets:3984` 调 `resolveIdleToolBlocker` 后现在总会走
   cancel 分支,这是期望行为,无需改调用面;但请确认旧的「继续→resume 批准」UI 文案/
   提示不再宣称自然语言可批准。审批按钮路径不变。
2. **R08 可选精确定位**:空白/重复 toolCallId 时,审批按钮若仍只传旧 id 会
   **静默 no-op**(歧义拒绝)。建议 `applyToolVerdict(toolCallId, verdict)` 增可选
   `locator: { messageId, partIndex }` 并透传给
   `applyToolApprovalToConversation(conv, id, verdict, locator)`(第 4 参,我已就绪)。
   审批组件若持有目标 part 所属 message 与 parts 下标即可接线;主流非空白 id 场景
   无需改动。
3. **R14**:`subagent_cancel` 工具/manager 路径已可对 approval_required 收口;任务板
   cancel(`AgentTaskStore.cancel`)对非 running 本就早退,无需改。

## 复核回修(2026-09-13 review-batch-23.md:P1-1 / P2-1 / P2-3)

**P1-1 + P2-1(R14×R25,同源)**:approval_required 任务板映射 INTERRUPTED 后,
`cancel` 的 `!agentTaskStatusRunning → early return` 使看板取消静默无效,
`cleanup` 只认 `agentTaskStatusRunning` 又可删活任务记录/transcript。修法:取消/存活
统一以 **`cancelCapability` + 活回调** 为单一判据。

- `subagent_manager.ts` `finish`:`cancelCapability = next.status === 'approval_required'`
  (其余终态 false)——live pending 保留看板取消能力。
- `agent_task.ts` `cancel`:去掉状态早退;无可取消能力时,运行中记 error、正常终态
  **no-op 原样返回**;成功取消 `update({status:'CANCELLED', cancelCapability:false})`,
  `update` 同步注销回调(避免残留回调让 cleanup 永久拒绝)。
- `agent_task.ts` `update`:`patch.cancelCapability === false` → `cancelCallbacks.delete`,
  终态封口自动清回调(completed 回调残留不再误判活跃)。
- `agent_task.ts` `cleanup`:在非终态门外**并列**加
  `current.cancelCapability && cancelCallbacks.has(taskId) → false`(明确可活跃才拒绝),
  终态无活回调仍可清(R25 原意保留)。

**P2-3(locator 硬校验)**:原 locator 路径无条件构造新 `Conversation`,ChatPage
`out===previous` 守卫不可达且可能改错 part。现命中前校验:messageId 唯一(重复→拒)、
part 存在且 `type==='tool'`、非空 `toolCallId` 须相符(空合法);任一不满足
**返回原 conversation 引用**,守卫可达。未改 ChatPage/组件。

回归(2 例,按要求):`subagent_manager.test.ts`「approval_required → 看板 cancel
经回调收口 CANCELLED;活跃期 cleanup=false」;`tool_approval.test.ts`「locator 未命中
/类型不符/重复 messageId → 原引用」。另同步修正既有映射用例的 `cancelCapability`
期望。

校验:`npx tsc --noEmit` 干净;targeted 116/116、cron+subagent 51/51 通过。

## 未解决点

- R08 的「输入边界拒绝或生成稳定身份」只做了**全程按 part 身份**这一半(足够修复
  错配/重复执行);合成稳定键(`stream_tool_index`)未做,属 provider/协议层,归其他
  owner。
- R15 仅收敛「自然语言批准」;是否在 UI 上显式列出待批准工具并提示「请点按钮」需
  ChatPage/组件 owner 决定。
