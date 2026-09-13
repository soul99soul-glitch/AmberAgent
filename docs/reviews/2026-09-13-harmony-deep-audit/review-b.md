# review-b — 独立复核 B 候选（2026-09-13 harmony-deep-audit）

- 复核对象：当前工作树，分支 harmony-preview，HEAD `bdce551dd373f3d7e4c393b194997cae19289c40`。业务代码只读。
- 复核者：独立复核代理（topic-b owner = agent_37cc16de-b310-4981-b8d6-9cd03f97151f，本文件独立裁决，不改其结论）。
- 方法：只读全文阅读相关函数 + 调用链跨模块追踪；在 `probes-b/` 新建隔离合成 probe，import **真实** `.ts` 模块（`tsx --tsconfig harmony/chat/tsconfig.json`），仅 mock provider 传输与 tool execute 体；无真实网络、无凭据、无业务改动。
- 结论分级：CONFIRMED（确认）/ DOWNGRADE（降级，问题真实但级别降低）/ FALSE-POSITIVE（假阳性/不可达）/ PENDING（待设备或上游验证）。

## 裁决速览

| 候选 | 原始级别 | 独立裁决 | 核心结论 |
|---|---|---|---|
| B1 blank toolCallId 并行+审批错配重复执行 | P1 | **CONFIRMED（机制）/ 触发面收窄** | `rewriteLastMessageTools` 位置对位在「批次是未执行集合的真子集」时错位；真实可达路径 = `resolveIdleToolBlocker` 自由文本应答 ask_user（见 B1j 实证）。但「并行双 blank 一 pending → 重复执行」的构造态（源 probe S1）经 UI 不可达（审批按 id 全匹配），需非合规 provider 双 blank + 特定审批子集才成立。 |
| B2 memorySystemBlocks 最终预算仍不裁 system | P1 | **DOWNGRADE（P2/P3）** | `finalTokenBudget` 真实接线于 send/regenerate；`fitMessagesToTokenBudget` 确不裁 system，但 memory 块被 `maxPromptChars≤12000`（默认 2000）硬约束，单靠 memory 顶不出预算；仅超长用户自写 agentSoul/systemPrompt 可超预算，且属 round3 已登记 Android 同款策略。 |
| B3 isAbortError message 含 abort 误吞 → 丢 user | P1 | **CONFIRMED** | `chat_kernel_host.ts:166` 子串匹配；真实链路（kernel+runChatTurnWithTools）实证：非 AbortError 的网络错误含 "abort" → kernel resolve result=null 返回**入参原会话**（无 user 消息）→ ChatPage `finishGenerationCommon` 终态保存覆盖已持久化的 user 消息。 |
| B4 runSendEdit 无 generationToken 跨会话 | P2 | **DOWNGRADE（P3）** | 确实无 operationToken，但 `sending` 前置门 + 路由换会话触发 `aboutToDisappear`（pageAlive=false/token++）使下游 UI 全被 `isGenerationOperationActive` 拦下；未证出跨会话数据损坏，仅留下死页后台生成/错误提示。 |
| B5 regenerate/resume 双视觉变换 | P2 | **CONFIRMED（预览层）** | 真实 `runRegenerateAtWithTools`（tools 非空）引擎已 `applyVisualTransformersStreamingTail` 一次，ChatPage onUpdate 再套一次 → `say *foo*` 变 `say **foo**`；send 路径不再套（单次）→ 不对称。需用户配置非幂等 visual regex 才可达。 |

---

## B1 — blank toolCallId 与审批/写回错配

**证据与调用链**
- 写回：`harmony/chat/src/main/ets/chat/tool_loop.ts:119-143` `rewriteLastMessageTools`。注释自述「批次恒为消息工具的保序子集…按位置对齐消费即可」（:115-118），但实现是在**全部未执行 tool**上顺序推进 cursor 去对位 `updatedTools`：
  - `:131` 已执行跳过；`:132` cursor 越界跳过；`:134-136` **空白 id 时 `candidate.toolCallId.length === 0` 即视为对齐**。
- 批次来源：`:304-332` 生成步 `updatedTools`（全部未执行）/ `:333-336` resume 路 `pendingTools`（仅 `canToolResumeExecution`）。
- `tool_dispatcher.ts:239-315` `executeBatch` 返回**保序子集**（pending 返回 null 不产生对，:270-295；按输入序重排 :296-313）。
- 审批 id-only：`tool_approval.ts:86-111` `applyToolApprovalToConversation` 仅按 `part.toolCallId === toolCallId` 匹配，**无 index 回退**；空白 id 会命中**所有** blank tool。

**实测（新增 probe）**
- `probes-b/probe_b1j_reachability.mjs`（真实 `resolveIdleToolBlocker` + `runToolLoopContinuation` + `executeBatch`）：
  - 构造单步产出 `[file_list(auto,blank), ask_user(pending,blank)]`（非合规 provider 省略双 id），用户对 ask_user **敲入非续跑词自由文本**：
    - `resolveIdleToolBlocker` 结果 `[file_list:auto, ask_user:answered]`（`tool_approval.ts:181-195`：ask 命中 `hasAskUserAnswer`→answered；`file_list` 走 `:193-195 return part` 保持 auto）。
  - resume 批次 = `[ask_user:answered]`（file_list 非 resumable）→ `rewriteLastMessageTools` 把 **file_list 的 part 用 ask_user 输出覆盖**，`ask_user` part 原样保留未执行 → 循环下一轮再执行一次：
    - 实测 `execLog=[]`（answered 不真正执行），`final parts = [ask_user:"请用中文…", ask_user:"请用中文…"]`，file_list 结果丢失。
- `probes-b/probe_b1c_states.mjs` 源 probe 的构造态 S1 `[blank file_list auto, blank file_write approved]` 实测 `execLog=["file_write","file_write"]` + 两 part 均成 `OUT_file_write`（真实重复执行 + 错配）。但该态经 UI 审批不可直接产生：`applyToolApprovalToConversation('')` 会把**两个 blank 全设为 approved**（probe 实测 `["approved","approved"]`）。

**上游 ID 补齐核查（coordinator 指定）**
- `openai_parse.ts:97-108`：`toolCallId: strOrNull(tc['id']) ?? ''`。首 delta 常空白；`tool_merge.ts:100` `mergeTool` 采纳后到非空 id → **只有后续 delta 带 id 才补回**。`probe_b1b_id_backfill.mjs` 实测：首 delta blank + 次 delta id → 合并为 `id="call_real"`。若 provider 全程不给 id → 永久 blank。
- `claude_parse.ts:55-68`：`tool_use` 恒有 `id = strOrNull(id) ?? ''`；`input_json_delta`（:69-79）blank，靠 `:168-175` 注入 `stream_tool_index` 归并到同一 part（`tool_merge.ts:66-81`）。**Claude 正常流 id 不为空**；provider 违规省略 id 才 blank。
- `google_parse.ts:62`：`toolCallId: newId()` 恒非空。
- **审批是否按 index 恢复：否。** `applyToolApprovalToConversation`、`findToolNameInConversation` 均仅按 id；无 `stream_tool_index` 回退。这是 B1 的另一独立缺陷：blank id 下「批准一个=批准全部同 blank 工具」（probe S1b 实测 approved,approved），ask_user 答案会写到同 blank 的普通工具上。

**根因**：空白 id 时「位置即对位」的前提——批次=未执行集合全集——在 resume 子集（`canToolResumeExecution` 过滤）被打破；cursor 语义在全集和子集间不自洽。
**最小修复**：写回不按 id/位置猜，改为按**输入批次对象身份**回写（`executeBatch` 已在 `pairs[].input` 持有原 part 引用，可返回 `(input,result)` 对）；或对 blank id 生成合成稳定键（`stream_tool_index`）并全程用它。回归：`[auto,answered]`/`[auto,approved]` 双 blank + 一条非 resumable 的用例。
**历史**：round3 未覆盖 tool_loop 写回对位。

## B2 — memorySystemBlocks 与最终预算

**接线核查**
- `ChatPage.ets:3898 / 1712` send/regenerate 均传 `finalTokenBudget: this.finalTokenBudgetOf(rt)`；`resumeToolLoop`（:3391-3480）**未传** `finalTokenBudget`（也不传 `extraSystemBlocks` 之外无差；memory 块在 :3427 仍传）。
- `finalTokenBudgetOf`（:2426-2430）= `max(trunc(window*forceRatio), 4000)`。
- `fitMessagesToTokenBudget`（`context_compact.ts:1065-1087`）：`:1070-1073` 先分离 system；`:1077` 候选恒含**全部 system**；因此 system 块（含 memory/工具 prompt 合并入的单个 system 消息，`context_assembly.ts:75-103`）**永不被裁**。
- memory 块实际大小：`memory_recall.ts:193` `maxChars = clamp(maxPromptChars,256,12000)`，默认 2000（`memory_models.ts:185-188`）；`memory_prompt_builder.ts` 逐条拼接。即 memory ≤ ~12k 字符 ≈ 3k token，**单靠 memory 无法把请求顶出 `forceRatio*window`**（除非极小窗模型，如 window=8000→budget 4000，加上 12k memory 字符仍可能超，但属边界）。
- 源 `probe_budget_fit.mjs` 用 `systemPrompt='S'.repeat(60000)` + memory 20000 字符构造；60000 字符静态 system 为用户自写超长 systemPrompt/agentSoul，非 memory 单独可达。

**裁决**：`fit` 不裁 system 属实，但（a）memory 有硬上限，（b）「超长静态 system 顶窗」= round3 fix-plan 明列**假阳性清单**「fit 保留超预算 system（Android 同款策略，只修后置块预算）」。故降级 P2/P3：保留「超长用户 prompt 可超预算请求」的登记项，不构成 memory 导致的 P1。盲区：`prepareContext` 的 `promptOverheadTokens` 在 ChatPage `buildPrepareContext` 未传（overhead=0），后置块估算依赖发送边界 fit；未在设备上量过真实 tokenizer。

## B3 — isAbortError 子串误吞 + 丢 user（CONFIRMED）

**根因链**
- `chat_kernel_host.ts:165-166`：`e.name === 'AbortError' || e.message.indexOf('abort') >= 0`（大小写敏感）。
- `:148-160` catch：命中即 `emit('cancelled')` 并 `return { conversation, result: null }`——**`conversation` 是 `run()` 的入参**，不含 `runChatTurnWithTools` 期间已持久化的 user 消息。
- `chat_turn.ts:187-188` `runChatTurn`（tool_loop `runChatTurnWithTools:393` 同）先 append + `store.save` user；`generateAssistantOnce` 抛错时 `runChatTurnWithTools` 直接传播，**未回滚也无返回**。
- `ChatPage.ets:3295-3306 runViaKernel`：`out.result===null` 时返回 `out.conversation`（=原会话）；`:3991-3997` `finishGenerationCommon(out,...)` → `:1179-1184` `queueFinalConversationSave(finalized)` + `this.conversation=finalized`。

**实测（`probes-b/probe_b3_chain.mjs`，真实 kernel + runChatTurnWithTools + store，仅 provider 抛错）**
```
[msg contains "aborted"  name=OpenAiStreamError] kernel RESOLVED phase=cancelled result=null
  engine 已保存 user: store nodes=2  ->  返回的 out.conversation nodes=1
  ChatPage 终态保存后 store nodes=1, texts=["pre-existing"]   # user 消息被抹掉
[generic 500] kernel THREW phase=failed -> ChatPage .catch 回读 store，user 保留 nodes=2
[named AbortError（真实停止）] 同上 resolve nodes=1（此时 UI 有 finish 收口，属预期路径）
```
**可达性**：上游 provider 抛出的错误 message 含子串 `abort`/`aborted` 即触发。候选真实来源：
- `RcpHttpClient.ets:88/237` `"${url} fetch aborted"`（取消，合法）；
- `OpenAiCompatibleAiClient` 部分网关错误正文/`OpenAiStreamError.message` 可能含 "abort"（如 "Request was aborted by upstream"），`generation_retry.ts:98` 仅按 `name==='AbortError'` 判 CANCELLED，故该错误会被当普通错误传播，却在此被 kernel 当取消。
- `AgentToolDispatcher` 的 `isAbortError`（`tool_dispatcher.ts:181-182`）只认 name，形成**同仓两套口径不一致**（kernel 子串 vs dispatcher name）。

**最小修复**：kernel 只认 `error.name === 'AbortError'`（或与 `cancelled` 标记联合），不要匹配 message；更稳妥在 `runChatTurnWithTools` 抛错前提供「含 user 的最新会话」或让 `run()` catch 返回 `lastPersistedConversation`。回归：provider 抛 `name!=='AbortError'` 且 message 含 "abort" 的用例，断言 user 消息仍在。

## B4 — runSendEdit 无 generationToken

- `ChatPage.ets:2301-2309 sendEdit` → `:2312-2333 runSendEdit`：无 `operationToken`，异步链 `invalidateConvCompacts→save→regenerate(nodeId)`，期间 `sending` 仅时序置位。
- 反证：`:3391+ resumeToolLoop / :1685+ runRegenerate / :3864+ runSendParts` 均有 `++generationToken` 且回调经 `isGenerationOperationActive`（`:864-866` 含 `pageAlive`）过滤；`openDrawerConversation`（`:2721-2725`）切会话走 `router.replaceUrl` → 旧页 `aboutToDisappear`（`:656-659`）置 `pageAlive=false`、`generationToken++`、abort。
- 因此 runSendEdit 异步窗口内若换会话：旧页变死页，`regenerate` 后续 UI 写全被 token 拦下，落到 `finishInactiveGeneration`（只保存）；未观察到把 A 会话结果写入 B 会话的路径。残留问题：死页仍会后台跑一轮生成（无害但浪费）、`regenerate(nodeId)` 在新会话 nodeId 不存在时抛错仅进 errorMsg。**降级 P3**。盲区：需真机/ArkUI 生命周期证明 `aboutToDisappear` 在 `replaceUrl` 时必触发。

## B5 — regenerate/resume 双视觉变换（CONFIRMED 预览层）

- 引擎：`tool_loop.ts:145-150 emitSnapshot` → `applyVisualTransformersStreamingTail(currentMessages(conv), deps.outputTransformers, ctx)` 后才 `deps.onUpdate`；`chat_turn.ts:322/337` 同。
- ChatPage：send 路径 `:3929-3941` onUpdate 只 `msgs.slice`（**不**再套 → 单次，正确）；`resumeToolLoop :3441-3450` 与 `runRegenerate :1743-1752` onUpdate **再** `applyVisualTransformersStreamingTail(msgs,[thinkTag,regex])`（对引擎已变换结果二次 → 双次）。
- `regexOutputTransformer.visualTransformTail`（`transformers.ts:166-202`）非幂等；`thinkTag` 幂等。
- 实测 `probes-b/probe_b5_double_visual.mjs`（真实 `runRegenerateAtWithTools` + 真实 regex）：regex `foo→*foo*`，显示 `"say **foo**"`，正确单次 `"say *foo*"`；最终存库原文 `"say foo"`。
- 可达规则：需助手配置 `visualOnly=false, affectingScope=['assistant']` 的非幂等正则；默认无正则 → 不触发。**预览层**影响（存库不受影响，且最终行渲染未见 visual 应用 → 预览与终态本就不一致）。级别维持 P2（视觉/体验），非数据损坏。
- 修复：让 onUpdate 与 send 路径一致（信任引擎已变换，不再二次套），或由引擎在 onUpdate 前不套、统一由页面套一次。

## coordinator 指定复核项小结
- **真实 ID 补齐**：Claude 恒有 id；OpenAI 仅当后续 delta 带 id 才补（`tool_merge` 已实现）；Google 恒 newId。**全程无 id 的非合规 provider 会永久 blank**——这是 B1 的前置条件，非普遍。
- **审批是否按 index 恢复**：**否**，仅按 toolCallId；blank id 下审批/应答全匹配，是 B1 之外的独立缺陷（建议同时修）。
- **runRegenerate 入口 pageAlive 反证**：入口 `regenerate()`（`:1677-1683`）无显式 pageAlive，但下游 `isGenerationOperationActive` 含 `pageAlive`，onUpdate/onRaw/终态分支均受 token 门；未发现无守卫写 UI 的路径（反证成立）。死页仍跑引擎属 `finishInactiveGeneration` 既有语义。

## 新增材料
- `probes-b/probe_b1_tool_blankid_e2e.mjs`、`probe_b1b_id_backfill.mjs`、`probe_b1c_states.mjs`、`probe_b1d_anomaly.mjs`、`probe_b1e_instrument.mjs`、`probe_b1f_save_trace.mjs`、`probe_b1g_raw.mjs`、`probe_b1h_asst.mjs`、`probe_b1i_conv.mjs`、`probe_b1j_reachability.mjs`、`probe_b3_chain.mjs`、`probe_b5_double_visual.mjs`（均 import 真实模块；无网络/凭据）。
