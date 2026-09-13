# 专题 B/H — Chat 主链路与热路径深审报告

- 审查对象：当前工作树，分支 harmony-preview，HEAD `bdce551dd373f3d7e4c393b194997cae19289c40`。
- 约束：业务代码绝对只读；未调用真实模型/网络；未改配置/签名；本文件与 `probes/` 为本代理新增。
- 状态分级：`已阅读`（完整读过函数及上下文）/ `跨模块追踪`（多文件调用链核对）/ `隔离验证`（真实域逻辑 + mock 边界 probe，见 `probes/`）/ `仅定位` / `pending`。
- 上一轮（round3，2026-08-25）结论均在当前实现上重新确认，不沿用旧结论。

---

## 0. 覆盖清单（本次完整阅读，非仅 diff）

| 层 | 文件 | 状态 |
|---|---|---|
| 热路径骨架 | `harmony/chat/src/main/ets/chat/chat_turn.ts`（401 行，全文） | 已阅读+跨模块 |
| 交互 façade | `harmony/chat/src/main/ets/chat/interactive_turn_runtime.ts`（169 行，全文） | 已阅读 |
| 内核 façade | `harmony/chat/src/main/ets/chat/chat_kernel_host.ts`（170 行，全文） | 已阅读+隔离验证 |
| 工具循环 | `harmony/chat/src/main/ets/chat/tool_loop.ts`（463 行，全文） | 已阅读+隔离验证 |
| 工具执行 | `harmony/chat/src/main/ets/chat/tool_dispatcher.ts`（executeBatch:236-315 / execute:317-） | 已阅读+跨模块 |
| 工具合并 | `harmony/chat/src/main/ets/chat/tool_merge.ts`（全文） | 已阅读 |
| 审批/续跑 | `harmony/chat/src/main/ets/chat/tool_approval.ts`（全文） | 已阅读 |
| 上下文组装 | `harmony/chat/src/main/ets/chat/context_assembly.ts`（181 行，全文） | 已阅读+隔离验证 |
| 压缩纯逻辑 | `harmony/chat/src/main/ets/chat/context_compact.ts`（1117 行，全文） | 已阅读+隔离验证 |
| 压缩引擎 | `harmony/chat/src/main/ets/chat/context_engine.ts`（914 行，全文） | 已阅读 |
| 会话/节点 | `harmony/chat/src/main/ets/chat/conversation.ts`（241 行，全文） | 已阅读 |
| 消息域 | `harmony/deepread/src/main/ets/agent/message.ts`（256 行，全文，canonical）+ `harmony/chat/src/main/ets/chat/message.ts`（facade） | 已阅读 |
| 序列化 | `harmony/chat/src/main/ets/chat/serialize.ts`（255 行，全文） | 已阅读 |
| checkpoint | `harmony/chat/src/main/ets/chat/stream_checkpoint.ts`（152 行，全文） | 已阅读 |
| 重试 | `harmony/chat/src/main/ets/chat/generation_retry.ts`（180 行，全文） | 已阅读 |
| 再生/分支 | `harmony/chat/src/main/ets/chat/regenerate.ts`（323 行，全文） | 已阅读 |
| 流累加 | `harmony/chat/src/main/ets/chat/stream_accumulator.ts`（449 行，全文） | 已阅读 |
| 编辑/删除/停止 | `edit_message.ts` / `delete_message.ts` / `stop_generation.ts`（全文） | 已阅读 |
| 持久化端口 | `harmony/chat/src/main/ets/chat/persistence.ts`（348 行，全文） | 已阅读 |
| 队列 | `pending_queue.ts` / `pending_queue_store.ts`（全文） | 已阅读 |
| 分页 | `harmony/chat/src/main/ets/chat/timeline_load.ts`（全文） | 已阅读 |
| 页面 | `harmony/entry/src/main/ets/pages/ChatPage.ets`（4975 行；发送/编辑/删除/再生/分支/压缩/附件/队列/分页/生命周期段完整读，render 段仅定位） | 已阅读（核心路径） |
| 平台契约 | `ChatRdbRepository.ets`(save/getTail/page)、`EntryAbortController.ets`、`RcpHttpClient.ets`（调用契约侧） | 已阅读（调用契约；实现归 E） |
| Provider 契约 | `openai_chat_api.ts`、`openai_parse.ts`、`claude_parse.ts`、`google_parse.ts`（tool id/流契约） | 仅定位（实现归 C） |

测试基线实跑：`cd harmony/chat && npx tsx --test chat_turn/tool_loop/regenerate/interactive_turn_runtime/chat_kernel_host/stream_checkpoint/context_engine/context_compact/conversation` → 157 tests / 0 fail。

---

## 1. 候选发现

### B-1 [P1｜置信度 高] 并行无 id 工具：审批续跑时结果错配 + 被批准工具重复执行

- 位置：
  - `harmony/chat/src/main/ets/chat/tool_dispatcher.ts:239-315`（`executeBatch`）
  - `harmony/chat/src/main/ets/chat/tool_loop.ts:119-143`（`rewriteLastMessageTools`）
  - 触发入口：`tool_loop.ts:333-359`（`hasResumableTools` resume 路径 → `executeBatch` → `rewriteLastMessageTools`）
- 调用链：ChatPage `handleToolApproval`→`applyToolVerdict`(`ChatPage.ets:3371-3387`) → `resumeToolLoop`(`3391`) → `runToolLoopContinuation`(`tool_loop.ts:403-410`) → resume 分支 `toolsToProcess = pendingTools`(`335`) → `dispatcher.executeBatch`(`345`) → `rewriteLastMessageTools`(`357`)。
- 根因（两层复合）：
  1. `executeBatch` 用**输入对象引用**做配对：`pairs.push({input: remaining[i], result})`，最后再 `for (const tool of tools) { … while (pairs[pairCursor].input !== tool) pairCursor++ … }`（`296-314`）。当多个工具 `toolCallId === ''` 时，`tool.dispatcher` 不去重、按引用可区分，此处本身尚可；真正的错配来自第 2 层。
  2. `rewriteLastMessageTools` 的对位判定（`134-136`）：`const aligned = tp.toolCallId.length > 0 ? candidate.toolCallId === tp.toolCallId : candidate.toolCallId.length === 0;`，且 `cursor` 在**第一个空 id 处**即前进。当末条 assistant 上存在多个空 id 工具、其中只有一个进入本次 resume 批次时，`cursor` 从 0 起，第一个空 id 旧 part（未执行）与批次首个空 id 结果被判定 aligned（双 blank「位置即对位」），于是批次结果被写到错误的 part 上，真正被批准的工具 part 仍是未执行。
- 复合后果：`executeBatch` 已对被批准的 `pending`→`approved` 工具真正执行（副作用发生），但写回落到另一个 part；下次循环读到仍存在 `canResumeExecution` 的 pending 工具，**再次执行**（重复副作用）。
- 现有保护为何不成立：代码注释显式断言「双 blank:位置即对位」「并行无 id 工具也不按 '' 互相命中」（`tool_loop.ts:117-118`）——该断言只对「整批全部位对齐」成立，对 resume 子集不成立。
- 复现（隔离验证，真实 `runAgenticToolLoop` + 真实 `AgentToolDispatcher`）：
  `probes/probe_tool_pairing.mjs`，输出：
  - 双空 id：`execLog = ["B","B"]`，两个 part 都变成 `file_write`/`OUT_B`（`file_list` 结果丢失、`file_write` 执行两次）。
  - 对照（非空 id `call-a`/`call-b`）：`execLog = ["B","A"]`，配对正确。
  平台：`cd harmony/chat && npx tsx --tsconfig ./tsconfig.json <probe>`。
- 前提：末条 assistant 存在 ≥2 个 `toolCallId===''` 的工具且仅其中一个进入 resume 批次。可达来源：Claude `input_json_delta` 以空 id 参与合并（`claude_parse.ts:69-79`，最终 id 取决于 `tool_use` 块；provider 未回填 id 时保持空）；部分 OpenAI 兼容网关流式 `tool_calls` 省略 `id`（`openai_parse.ts:102` 直接取 `strOrNull(tc['id']) ?? ''`）。Google 有确定性 id（`google_parse.ts:62/211`），不触发。
- 最小修复：`rewriteLastMessageTools` 不再以「双 blank 位置对位」消费 `cursor`，改为按 `updatedTools` 里每个结果的**原始身份**回填；resume 路径可让 `executeBatch` 返回 `Array<{input, result}>` 保留 input 引用，或写回时直接用 `toolsToProcess`（`tool_loop.ts:335` 的 `pendingTools`）作为匹配基准。回归测试：双空 id + 单工具 approved 的 resume（现有 `tool_loop.test.ts` 只覆盖非空/单工具）。
- 历史关系：round3 未报此复合路径（round3 #16 只报 `tool_activity` 的 `Date.now()` id 碰撞；#42 只报 MCP 不可取消）。本轮为新发现。

---

### B-2 [P1｜置信度 高] 最终请求仍可超上下文窗：`promptOverheadTokens` 从未接线 + 发送边界 fit 永久保留 system

- 位置：
  - `harmony/entry/src/main/ets/pages/ChatPage.ets:2440-2481`（`buildPrepareContext` 未传 `promptOverheadTokens`）
  - `harmony/chat/src/main/ets/chat/chat_turn.ts:202-220`（`extraSystemBlocks` 拼接 + `finalTokenBudget` fit）
  - `harmony/chat/src/main/ets/chat/tool_loop.ts:226-245`（同步骤）
  - `harmony/chat/src/main/ets/chat/context_compact.ts:1065-1087`（`fitMessagesToTokenBudget` 恒保留 `role==='system'`）
  - `harmony/chat/src/main/ets/chat/context_engine.ts:657`（`overheadEstimate = deps.promptOverheadTokens ?? 0`）
- 调用链：`ChatPage.sendParts`/`runSendParts`(`3896-3949`) → `deps.prepareContextMessages = buildPrepareContext`(`3911`) → `prepareContext`(`context_engine.ts:624`) 规划时 `overheadEstimate=0`；随后 `chat_turn` 组装 `assembledBase`（静态 system + memory + recentChats + budget）→ `finalTokenBudget` 兜底 fit → provider。
- 根因：
  1. `promptOverheadTokens` 参数在 `PrepareContextDeps` 存在且被 `prepareContext` 使用（`context_engine.ts:584/657`），但全仓仅此两处出现，**entry 无任何调用点传入**（`grep -rn promptOverheadTokens entry/src chat/src` 仅命中 engine 自身）。Android 基准 `GenerationHandler.kt:432` 传 `promptOverheadTokens = estimateTokens(listOf(UIMessage.system(system)))`，Harmony 漏接。
  2. 结果：压缩规划/软预算完全不计静态 system、memory、agentSoul、tool prompt 的体量；这些后置块在 assemble 时才拼入，`forceRatio` 判断基于不含它们的估算。
  3. 兜底 `fitMessagesToTokenBudget` 只裁 `role !== 'system'` 的尾部（`context_compact.ts:1070-1086`），**system 块无条件保留**。当后置 system 块自身（如超长 agentSoul / memory 召回）合计已超预算时，fit 无法收敛。
- 现有保护为何不成立：round3 修复计划 P4 要求「ChatPage buildPrepareContext 传 overhead（含后置 system 块估算）」，当前实现未落地；`finalTokenBudget` 兜底注释自认「system 本身按 Android 语义保留不裁」，等价于把超窗责任推给用户。
- 复现（隔离验证，真实 `assembleInternalMessages`+`fitMessagesToTokenBudget`）：
  `probes/probe_budget_fit.mjs`（systemPrompt 60000 字符 + memory 块 20000 字符 + 预算 4000）：
  ```
  assembled estimate = 20005  budget = 4000
  after finalTokenBudget fit estimate = 20003   OVER BUDGET? true
  ```
  即「发送边界兜底 fit」后请求仍为预算的 ~5 倍。生产前提：长 agentSoul / 大 memory 召回 / 第三方模型窗口小（`contextWindowTokens` 由模型配置决定）。
- 附带不一致：`resumeToolLoop` 的 deps（`ChatPage.ets:3413-3457`）**未传** `finalTokenBudget`，而 `runSendParts`(`3898`) 与 `runRegenerate`(`1712`) 传了 → 审批续跑路径连兜底 fit 都没有。
- 最小修复：`buildPrepareContext` 按 Android 传入 `promptOverheadTokens`（静态 system + memory/agentSoul/tool prompt 估算）；`fitMessagesToTokenBudget` 增加「system 不可裁时明确失败/报 `ContextCompactionFailedError`」分支；`resumeToolLoop` deps 补 `finalTokenBudget`。回归测试：现有 `context_compact.test.ts` 锁定了「fit 保留超预算 system」的行为（round3 已列为假阳性「同款策略」），修时需同时改测试。
- 历史关系：round3 #19 同型（「token 预算拟合无法收敛超大 system/静态块」），round3-fix-plan 将其部分列为假阳性（保留 Android 同款 system 策略），并要求补「后置块预算」——后者未落地，故本条以「overhead 未接线 + 续跑路径无兜底」独立成立。

---

### B-3 [P2｜置信度 中] ChatKernelHost：非取消错误被误判为 cancelled，返回原始会话导致落库回退/吞错

- 位置：`harmony/chat/src/main/ets/chat/chat_kernel_host.ts:148-160`（catch 分支）+ `:165-166`（`isAbortError`）
- 调用链：`ChatPage.runViaKernel`(`3284-3307`) → `host.run` catch → 返回 `{conversation, result:null}` → `ChatPage.runSendParts` 的 `.then`(`3991-4008`)：`stopped = operationAbort.signal.aborted`（false）→ `finishGenerationCommon(out,false)`(`1166`) → `queueFinalConversationSave`(`1157-1164`) 落库。
- 根因：`isAbortError` 判据为 `e.name === 'AbortError' || e.message.indexOf('abort') >= 0`。任何 message 含子串 `abort` 的普通错误（如 HTTP 层 `stream failed: <底层错误文本>`，`RcpHttpClient.ets:280`，或服务端错误体）都会走 cancelled 分支；该分支 `return { conversation, result: null }` 返回的是**入参原始会话**（`chat_turn` 已先持久化含新 user 的会话），随后被 `queueFinalConversationSave` 覆盖写回 → 新 user 消息从持久化中回退；且错误被吞、无 errorMsg。
- 现有保护为何不成立：`RcpHttpClient` 对真实取消显式设 `name='AbortError'`（`RcpHttpClient.ets:89/238`），name 判据已足够；`message.indexOf('abort')` 是额外危险面。内核 `cancel()` 由 `requestStop` 显式调用（`ChatPage.ets:1114-1117`），真取消不依赖 message 文本。
- 复现（隔离验证，真实 `createChatKernelHost`）：`probes/probe_kernel_abort_substring.mjs`
  ```
  network msg contains "aborted" => RESOLVED (not thrown), phase= cancelled, result= null, conversation.nodes= 1
  msg contains "abort" lowercase => RESOLVED (not thrown), phase= cancelled
  normal provider error        => THREW, phase= failed
  ```
- 最小修复：删除 `message.indexOf('abort')` 判据（仅留 `name==='AbortError'`）；catch 的 cancelled 分支不应返回入参原始会话，应返回 `result?.conversation ?? conversation` 或改为 throw 让页面按错误处理。
- 前提：存在 message 含 `abort` 的非取消错误。置信度中（未在真机捕获到具体 provider 文本，标注 pending）。
- 历史关系：round3 P2 提到 `RcpHttpClient` abort Error 未设 `name='AbortError'` 会误分类——当前已修（`name` 已设）；本条是**反向**问题（name 已对，但额外子串判据过宽），round3 未覆盖。

---

### B-4 [P2｜置信度 高] 编辑重发的 `sending` 状态机无 generationToken 守卫，与发送路径不一致

- 位置：`harmony/entry/src/main/ets/pages/ChatPage.ets:2312-2333`（`runSendEdit`）；对比 `runSendParts`(`3864-4008`) 使用 `operationToken = ++this.generationToken` + `isGenerationOperationActive`(`864-866`)。
- 根因：`runSendEdit` 在 `2321` 置 `this.sending=true`，`2323-2328` 的 `await` 链（`invalidateConvCompacts`→`save`）**无 token/pageAlive 守卫**，完成回调无条件 `this.sending=false; this.regenerate(nodeId)`（`2326-2327`）。若期间页面离开（`aboutToDisappear:656` 已 `generationToken++` 且 `sending` 被 abort 分支管理），仍会复位 `sending` 并启动 `regenerate`；`regenerate`→`runRegenerate` 会新开 `sending=true` 与网络生成。虽然有 `pageAlive` 在 `regenerate` 之外缺失，但 `runRegenerate` 只查 `conversation!==null && !sending`（`1686`），不查 `pageAlive`。
- 用户影响：编辑后立刻返回/切会话，可能出现「已 destroy 页面仍在运行生成」或状态错乱（`sending` 卡死/提前复位）。
- 现有保护为何不成立：`sendEdit`(`2301`) 只在入口查 `sending`，`runSendEdit` 的异步续段没有再查；与 round3 修复计划 P2「发送准备离页 token」精神一致但未覆盖编辑路径。
- 验证：静态证明（代码路径完整阅读）。未构造隔离 probe（依赖 ArkTS 页面组件，无法在 node 下实例化）→ 标注为跨模块追踪级证据。
- 最小修复：`runSendEdit` 捕获 `const token = ++this.generationToken`，异步回调统一 `if (!this.isGenerationOperationActive(token)) return;` 再复位/续跑；`runRegenerate` 入口补 `pageAlive` 守卫。
- 历史关系：round3 #76「ChatPage 若干异步回调缺守卫」列为 ◻，当前发送路径已修（`sendParts` token 化），编辑/再生路径仍缺。

---

### B-5 [P2｜置信度 高] 续跑/再生预览对同一消息二次执行 visual transform（非幂等）

- 位置：
  - 引擎单次转换：`chat_turn.ts:322/337-338` 与 `regenerate.ts:232-234`（`onUpdate(applyVisualTransformersStreamingTail(snapshot, deps.outputTransformers, ctx))`）
  - 页面二次转换：`ChatPage.ets:3441-3450`（`resumeToolLoop.onUpdate`）与 `:1743-1752`（`runRegenerate.onUpdate`）
  - 发送路径**未**二次转换：`ChatPage.ets:3929-3941`（`runSendParts.onUpdate` 只做 `msgs.slice(tailStart)`）
  - 转换器：`transformers.ts:187-203`（`regexOutputTransformer.visualTransformTail`）、`:109-134`（thinkTag）
- 根因：引擎已对 tail 应用 `deps.outputTransformers` 的 `visualTransformTail`；`resumeToolLoop`/`runRegenerate` 的 `onUpdate` 又把同一 `msgs` 交给 `applyVisualTransformersStreamingTail(msgs, [thinkTagTransformer, regexOutputTransformer], ctx)`。对非幂等正则（如 `foo→foo!`）会重复替换。
- 现有保护为何不成立：`sendParts` 路径没有这层重复，说明两条路径本应一致；`regexOutputTransformer` 无幂等保证（`replaceRegexes` 逐规则 `replace`）。
- 复现（隔离验证，真实 `regexOutputTransformer`+`applyVisualTransformersStreamingTail`）：`probes/probe_double_visual_transform.mjs`
  ```
  once  text = "foo!"
  twice text = "foo!!"
  idempotent = false
  ```
- 用户影响：审批续跑/重新生成时的流式预览与最终落库内容（`applyOnGenerationFinish` 下 regex 无 onGenerationFinish，落库为原始文本）出现差异，且预览被叠加变换，用户看到错误文本。
- 最小修复：`resumeToolLoop`/`runRegenerate` 的 `onUpdate` 不再二次 `applyVisualTransformersStreamingTail`（引擎已做），或改由引擎只回传 raw 快照、由页面统一转换（需与 `onRawFlushSnapshot` 语义对齐）。
- 历史关系：round3 未报。

---

## 2. 排除项 / 反证（本轮确认，勿再报为 bug）

- **round3 #4「并发发送基于同一旧会话快照」**：当前 `sendParts:3844-3848` 在进入异步 `ensureFullConversationForMutation` **之前**同步置 `this.sending=true` 并在已有 `sending` 时 early return；`runSendParts` 内赋值幂等。已修复（反证成立）。
- **round3 #5「appendActive 路径丢消息且失败不复位」**：`sendWithoutAnswer:3686-3691` 在清空输入后若 `appendActive` 则 `enqueuePendingParts(inputParts,false)`（不再静默丢弃）；`3692-3715` 的 `ensureFull` 失败分支显式复位 `appendActive`。已修复。
- **round3 #6「压缩规划与执行使用两份会话快照」**：`buildPrepareContext:2453-2458` 先 `conversationWithMessagesAsNodes(conv, messages)` 重建 `effConv`，再 `prepareContext(effConv,…)`；引擎内部 `compactConversation(conversation…)` 用的即该 `effConv`。规划与执行一致（反证成立）。
- **round3 #7「删除前面的 alternative 会切换当前分支」**：`delete_message.ts:42-47` 已按选中 message id 重定位 `selectIndex`。已修复（含注释说明双端共享 bug 的 Harmony 侧先修）。
- **round3 #1「usage-only chunk 被丢弃」**：`stream_accumulator.ts:268-277/287-293` 在 choice 判空前先 `mergeUsage`，`appendChunkToMessage:321-331` / `handleMessageChunk:435-440` 同。已修复。
- **round3 #2「图片 data URI 只认 PNG」**：`stream_accumulator.ts:199-206/393-395` 对 `data:` 前缀原样保留；`openai_parse.ts:127-131` 仍只剥 PNG 前缀但已不拼双前缀。显示层双前缀 bug 已消除（`openai_parse` 侧对非 PNG data URI 保留完整 URI 的取向未变，属另议）。
- **`checkpointConversationTail` 重复投影**：`conversation.ts:102-135 updateCurrentMessages` 同 id 原位替换、新 id 追加，`stream_checkpoint.test.ts:121-139` 锁定不重复 append。无重复节点问题。
- **`estimateTokens` CJK 权重**：`context_compact.ts:96-107` 以 UTF-16 码元迭代、CJK 计 4 字符再 `/4`，与注释声明的 Kotlin `Char.code` 语义一致，非 bug。
- **`applyStopGeneration` 无变化早退**：`stop_generation.ts:57-60` 引用比较 + `partsUnchanged`，避免无意义落库；无变化返回 null，`ChatPage.finalizeGenerationResult:1150-1153` 回退原会话。语义自洽。
- **跨会话切换的取消/落盘顺序**：`openDrawerConversation:2721-2725` 用 `router.replaceUrl` 重开页面 → 当前实例 `aboutToDisappear:656-701`：`pageAlive=false`→`generationToken++`→`abort()`→以 `lastRawSnapshot` 经 `checkpointRegenerateConversation`/`checkpointConversationTail` 投影 + `applyStopGeneration` 收口 → `persistConversationCheckpoint`（入 `checkpointTail` 串行）；生成 promise 的 `finishInactiveGeneration:1194-1202` 把引擎最终 `out` 经 `queueFinalConversationSave` 排到同一 `checkpointTail` 之后。**终态恒排在 checkpoint 之后**，未发现「旧 checkpoint 覆盖终态」或「切页丢回答」的可达路径（反证成立）。注：`runRegenerate` 的 `finishInactiveGeneration` 传入 `operationStopTargetNodeId`，assistant 中间节点停止收口正确。
- **`generationToken` 复用**：`aboutToDisappear:659` 与每次操作 `++this.generationToken` 共用同一计数器，旧操作 `isGenerationOperationActive` 恒 false，无 ABA 冲突（计数器单调递增）。

---

## 3. 待验证 / pending（有条件不能验证）

- **B-3 前提**：未在真机/真实 provider 捕获到「message 含 `abort` 的非取消错误」的具体文本。`RcpHttpClient` 的 `stream failed: ${String(e)}`（`:280`）底层的 `e` 文本依赖 ohos net.http 运行时，无法离线确证。标 P2 + pending（需真机日志）。
- **Provider 侧空 toolCallId 实况**：B-1 的触发前提是 provider 流式 tool delta 不回填 id。`openai_parse.ts:102` 与 `claude_parse.ts:56-79` 的静态契约已读；但具体第三方网关是否省略 id 未做真实抓包（禁止真实网络）。标注为「前提成立则可达」。
- **平台存储契约（归 E）**：`ChatRdbRepository.save` 的 `enqueueMutation`+`enqueueConversationFileMutation` 串行化与 `delete` 的 `deletedIds` 竞态语义仅从调用契约侧阅读，未验证 RDB 运行时。B 侧依赖的契约是「save 全量替换 nodes、按 id 串行」——当前实现签名满足（`ChatRdbRepository.ets:283-300`）。
- **ArkUI 渲染代价**：`streamingTail`/`regenTail` 高频刷新、`ForEach(rows)` key=`nodeId+darkMode`（`ChatPage.ets:4348-4415`）的实际重组成本无设备无法测量。高频流式成本仅做静态评估：`onUpdate` 每 flush（默认 50ms，`chat_turn.ts:260`）切片 + 尾变换；`onRawFlushSnapshot` 触发 checkpoint 判定（节流 10s/512 字符，`stream_checkpoint.ts:88-96`）。未发现明显每 chunk O(n²) 分配，但因 `applyVisualTransformersStreamingTail` 会 `[...messages]` 浅拷贝整数组（`transformer_pipeline.ts:151`），长会话每 50ms 一次全数组拷贝 + `rows` 重建（`buildRows` O(nodes)）为已知线性开销，标 pending 待设备评测。
- **manual compact 断链**：`/compact` 斜杠 → `compactCurrentConversation:2911-2921` 仅 `invalidateConvCompacts('manual_compact')`（删摘要），**不执行 `compactConversation`**；`CompressContextDialog.ets`（含 targetTokens/keepRecent/per-prompt 参数）全仓无引用（`grep CompressContextDialog entry/src` 仅组件自身）。Android 对应 `ChatPage.kt:714-719`→`handleCompressContext`→`compactConversation(force=true, reason='manual_compact_dialog')`。当前 Harmony `/compact` 与 UI 按钮语义为「下次发送时由引擎自动重压」，非「立即压缩」；对话框参数完全无消费者。属产品语义偏差 + 死代码，P2/P3，**待确认是否为刻意裁剪**（round3 未列此项），先标 pending 不升格为 bug。
- **附件**：`pickImages`(`2063-2095`) maxSelectNumber=3，`pickFiles`(`2135-2154`) 10MB/文件、base64 直存消息 part。`guardImagesForSend`(`3744-3772`) 走 `firstImageBlockingIssueForSend`（真实域逻辑）；样式/耗电未做设备验证。未发现契约级 bug。
- **分页**：`timeline_load` 纯逻辑与 `refreshRowsAfterConversationChange:1208-1249` 的窗口保持逻辑已读，未发现丢/重节点路径；`getConversationNodePage` 边界（offset≥total → 空窗 + totalNodeCount）与 `applyOlderPage`/`mergeOlderNodes` 去重一致。标「已阅读，无候选」。

---

## 4. 盲区声明

- 未运行真机/模拟器（`hdc list targets` 无设备，且合同禁止改设备），所有 UI 时序类结论为静态+隔离合成，非设备实测。
- 未调用真实模型/网络；所有 provider 行为按代码契约与合成 chunk 推断。
- RDB/文件系统运行时语义、ArkUI 实际布局/重组、ohos net.http 底层错误文本未验证。
- Provider 具体兼容性（C 专题）与存储实现（E 专题）不在本报告范围，仅审调用契约。

---

## 5. 报告目录产物

- `docs/reviews/2026-09-13-harmony-deep-audit/topic-b-chat.md`（本文件）
- `docs/reviews/2026-09-13-harmony-deep-audit/probes/probe_tool_pairing.mjs`（B-1，真实 runAgenticToolLoop+dispatcher）
- `docs/reviews/2026-09-13-harmony-deep-audit/probes/probe_budget_fit.mjs`（B-2，真实 assemble+fit）
- `docs/reviews/2026-09-13-harmony-deep-audit/probes/probe_kernel_abort_substring.mjs`（B-3，真实 createChatKernelHost）
- `docs/reviews/2026-09-13-harmony-deep-audit/probes/probe_double_visual_transform.mjs`（B-5，真实 regexOutputTransformer）
- `docs/reviews/2026-09-13-harmony-deep-audit/probes/_smoke.ts`（导入解析冒烟）

所有 probe 仅用绝对 import 指向仓库真实 `.ts`，mock 仅限 provider/store/provider 边界与合成初始会话；无真实 fetch/网络/环境凭据。
