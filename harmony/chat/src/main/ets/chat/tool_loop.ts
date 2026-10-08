// tool_loop — agentic 工具循环(D-057)
//
// Android 基准: GenerationHandler.kt:161-391(generateText 主循环)
//   - pendingTools = 末条消息 canResumeExecution 工具(:164-166)
//   - 预算提示 + FINAL 隐藏工具(:169-186)
//   - 无 pending → 生成一步;新消息无工具调用 → break(:281-285)
//   - ASK 决策 → 置 Pending + permission_trace,emit,break 等用户(:288-339)
//   - resume 路径直接用 resumable 工具(:342-346)
//   - executeBatch → 空 break;结果**写回同一 assistant 消息**(不发 TOOL 消息,
//     :366-373)→ emit → steer 消息步间消费(:386-390)
//   - maxSteps = maxToolLoopSteps coerceIn(16, 512)(ChatService:1349-1352,
//     默认 DEFAULT_AGENT_MAX_TOOL_LOOP_STEPS=256,PreferencesStore.kt:268-270)
// 偏差登记:
//   - ToolExposureState 懒暴露与 SpeculativeToolRunner 已接线(D-062/D-063;
//     toolsForStep/prefetchedTools 非空路径见下方实现)
//   - Android 每步 generateInternal 内部 prepareContext → 本实现每步调
//     deps.prepareContextMessages(同语义位置);budget 提示经 dynamic system 块
//     (buildSystemPromptParts:726-736 现存子集)
//   - 每步 finishedAt 戳(:275-278)未加 — 与既有单跑路径同(accumulator 语义),
//     登记 PARITY_DEBT

import type { Conversation, MessageNode } from './conversation.ts';
import { currentMessages, nodeCurrentMessage, toMessageNode } from './conversation.ts';
import type { UIMessage, UIMessagePart, UIMessagePartTool } from './message.ts';
import { canToolResumeExecution, isToolExecuted, makeUIMessage } from './message.ts';
import { nowIso } from './ids.ts';
import type { ChatStreamProvider, ChatTurnDeps } from './chat_turn.ts';
import { generateAssistantOnce, runAppendUserMessage, runChatTurn } from './chat_turn.ts';
import type { RegenerateDeps, RegenerateSeed } from './regenerate.ts';
import { prepareRegenerateSeed, runRegenerateAt } from './regenerate.ts';
import { assembleInternalMessages, buildToolSystemPrompt } from './context_assembly.ts';
import { fitMessagesToTokenBudget } from './context_compact.ts';
import type { TransformerContext } from './transformer_pipeline.ts';
import {
  applyInputTransformers, applyVisualTransformersStreamingTail,
} from './transformer_pipeline.ts';
import type { VisionFallbackHook } from './vision_fallback.ts';
import type { ChatModel, ChatToolDefinition } from './provider_model.ts';
import type { AgentTool } from './tool.ts';
import { toChatToolDefinition } from './tool.ts';
import type { GenerationRetrySetting } from './generation_retry.ts';
import { makeGenerationRetrySetting } from './generation_retry.ts';
import type { ToolInvocationContext } from './tool_permission.ts';
import { permissionDecisionTraceToJson } from './tool_permission.ts';
import type { PermissionDecision } from './tool_permission.ts';
import type { AgentToolDispatcher, ToolBatchResultPair } from './tool_dispatcher.ts';
import { AgentToolDispatcher as DefaultDispatcher, defaultToolInvocationHooks } from './tool_dispatcher.ts';
import { buildAgentLoopBudgetPrompt, agentLoopShouldHideTools } from './agent_loop_budget.ts';

// ===== 环路守护(Android DefaultRunKernel duplicate_tool_call 对齐) =====
// 签名 = toolName+args JSON;出现次数:1 执行,2 跳过(structured reminder,
// 结果复用),≥3 停止本轮循环(写回 skipped 输出后 break,terminal 不伪装完成)。
// 观察类工具(读态随页面/文件/任务变化)豁免跨步重复(Android REPEATABLE_OBSERVATION_TOOLS);
// resume 路径(用户交互后重放)不经此守卫。
const GUARD_REPEATABLE_TOOLS: Set<string> = new Set([
  'tool_search', 'tools_list', 'tool_policy_explain', 'recipes_list', 'recipe_validate',
  'plugins_list', 'plugin_sdk', 'plugin_validate',
  'file_read', 'file_list', 'file_search',
  'terminal_job_wait', 'terminal_job_read', 'terminal_session_read',
  'terminal_mosh_session_read',
  'subagent_wait', 'subagent_read', 'model_council_wait', 'model_council_read',
  'js_cell_wait', 'webview_wait_for_load', 'webview_read', 'webview_find_text', 'webview_links',
  'wm_wait', 'wm_observe', 'wm_state', 'wm_extract', 'wm_get', 'wm_find', 'wm_network_inspect',
  'wm_screenshot', 'wm_visual_snapshot', 'wm_zcode_read',
]);

interface ToolLoopGuardOutcome {
  toExecute: UIMessagePartTool[];
  skipped: UIMessagePartTool[];
  stopping: UIMessagePartTool | null;
}

const withGuardOutput = (
  tool: UIMessagePartTool, occurrence: number, sameBatch: boolean,
): UIMessagePartTool => ({
  ...tool,
  output: [{
    type: 'text',
    text: JSON.stringify({
      status: 'skipped',
      reason: 'duplicate_tool_call',
      occurrence,
      message: sameBatch
        ? 'Duplicate tool call in the same step; the first occurrence is already executing — do not repeat it.'
        : 'This exact tool call already ran with identical arguments; the previous result is reused. Do not call it again with the same input.',
    }),
    metadata: null,
  }],
});

const classifyToolCallDuplicates = (
  tools: UIMessagePartTool[], executedSignatures: Map<string, number>,
): ToolLoopGuardOutcome => {
  const toExecute: UIMessagePartTool[] = [];
  const skipped: UIMessagePartTool[] = [];
  let stopping: UIMessagePartTool | null = null;
  const batchSeen: Map<string, number> = new Map();
  for (const tool of tools) {
    const sig: string = `${tool.toolName}::${JSON.stringify(tool.input)}`;
    const batchCount: number = batchSeen.get(sig) ?? 0;
    const repeatable: boolean = GUARD_REPEATABLE_TOOLS.has(tool.toolName);
    const prior: number = repeatable ? 0 : (executedSignatures.get(sig) ?? 0);
    const occurrence: number = prior + batchCount + 1;
    if (occurrence === 1) {
      batchSeen.set(sig, batchCount + 1);
      toExecute.push(tool);
    } else if (occurrence === 2) {
      batchSeen.set(sig, batchCount + 1);
      skipped.push(withGuardOutput(tool, 2, batchCount > 0));
    } else {
      stopping = withGuardOutput(tool, occurrence, false);
      break;
    }
  }
  // 签名表合并:批内所有出现(含 skipped/stopping)都计入,防下一步重复泛滥
  batchSeen.forEach((count: number, sig: string): void => {
    executedSignatures.set(sig, (executedSignatures.get(sig) ?? 0) + count);
  });
  return { toExecute, skipped, stopping };
};
import type { ToolExposureState } from './tool_exposure.ts';
import { createToolExposureState } from './tool_exposure.ts';
import type { SpeculativeToolRunner } from './speculative_tool_runner.ts';
import { createSpeculativeToolRunner } from './speculative_tool_runner.ts';
import type { JsonObject } from './json.ts';
import type { RecipeLoopAdapter } from './recipes/runner.ts';
import type { PluginLoopAdapter } from './plugins/ports.ts';
import { executeRecipeBatch } from './recipe_loop.ts';

// ===== maxSteps 常量(PreferencesStore.kt:268-270) =====

export const MIN_AGENT_TOOL_LOOP_STEPS: number = 16;
export const MAX_AGENT_TOOL_LOOP_STEPS: number = 512;
export const DEFAULT_AGENT_MAX_TOOL_LOOP_STEPS: number = 256;

// ===== ToolLoopOptions(ChatService:1330-1360 调用面) =====

export interface ToolLoopOptions {
  tools: AgentTool[];
  recipeAdapter?: RecipeLoopAdapter;
  pluginAdapter?: RecipeLoopAdapter;
  // E12:wm_run_goal 编排 adapter;同 RecipeExecutionPort 有序推进,不复制执行
  goalAdapter?: RecipeLoopAdapter;
  refreshTools?: () => Promise<AgentTool[]>;
  // Explicit page-launched package: execute/resume this part without requesting
  // model text, then return its persisted result or approval card.
  manualToolCallId?: string;
  // ChatService:1349-1352 coerceIn(16,512);默认 256
  maxSteps?: number;
  // settings.agentRuntime.autoApproveAllToolCalls || conversation.autoApproveToolCalls
  autoApproveTools?: boolean;
  autoApproveHighRiskTools?: boolean;
  // trustedRunToolNames[conversationId](screen 会话信任集)
  autoApprovedToolNames?: string[];
  invocationContext?: ToolInvocationContext;
  dispatcher?: AgentToolDispatcher;
  // Capture invocation identity before approval is persisted. Continuation uses
  // the saved part metadata; a rebuilt factory must not replace that identity.
  captureInvocationMetadata?: (part: UIMessagePartTool) => JsonObject | null;
  // 工具执行重试(settings.agentRuntime.generationRetry 复用);默认禁用
  toolRetrySetting?: GenerationRetrySetting;
  // 每步 provider 工厂(步内工具集可能因预算隐藏而变化)
  makeProviderForStep: (stepTools: ChatToolDefinition[]) => ChatStreamProvider;
  // steer 消息步间消费(:386-390;Android session.dequeueSteerPendingUserMessages)
  consumeSteerMessages?: () => Promise<UIMessage[]>;
  // D-063:推测执行(settings.agentRuntime.speculativeToolExecution,
  //   PreferencesStore.kt:226-229 默认 enabled=false/maxConcurrentTools=4);
  //   另需 assistant.streamOutput != false(GenerationHandler:187)
  speculativeEnabled?: boolean;
  speculativeMaxConcurrentTools?: number;
  // D-077a:tool system prompt 块(buildSystemPromptParts:739-741)所需模型;
  //   未提供 → 不产出 tool prompt 块(等价 Android tools 空列表)
  toolPromptModel?: ChatModel;
}

const appendNode = (conv: Conversation, msg: UIMessage): Conversation => ({
  ...conv,
  messageNodes: [...conv.messageNodes, toMessageNode(msg)],
  updateAt: nowIso(),
});

// 末条消息整体替换(accumulator snapshot 全量语义:生成步可能把
//   新内容就地合并进末条 assistant 消息 — Android messages 是 List,
//   snapshot 直接覆盖末元素;本实现按节点写回)
const rewriteLastMessage = (conv: Conversation, newMsg: UIMessage): Conversation => {
  const nodes: MessageNode[] = conv.messageNodes;
  if (nodes.length === 0) return conv;
  const lastNode: MessageNode = nodes[nodes.length - 1];
  const newNode: MessageNode = {
    ...lastNode,
    messages: lastNode.messages.map(
      (m: UIMessage, i: number): UIMessage => (i === lastNode.selectIndex ? newMsg : m)),
  };
  return {
    ...conv,
    messageNodes: [...nodes.slice(0, nodes.length - 1), newNode],
    updateAt: nowIso(),
  };
};

// 结果/审批态写回末条 assistant 消息(:366-373;非工具 part 不动)。
// 定位严格按 (input,result) 配对里的**输入 part 对象身份** — executeBatchPairs
// 保留原引用,审批 map 也以原 part 为 input。空 toolCallId 或批次只是真子集时,
// 不得用 '' 等价或位置游标猜,否则会把结果写到同 blank 的另一个工具上(R08)。
// 身份失配(理论上不发生)时,仅当该 part 非空 id 在本次批次内唯一才回退命中;
// 已执行 part 不参与回退。后到补全的真实 toolCallId 由 provider merge 保留,
// result 沿用 input 的 id,不在此重写(R08 "保留后到真实id merge")。
const rewriteLastMessageTools = (
  conv: Conversation, pairs: ToolBatchResultPair[],
): Conversation => {
  const nodes: MessageNode[] = conv.messageNodes;
  if (nodes.length === 0) return conv;
  const cur: UIMessage = nodeCurrentMessage(nodes[nodes.length - 1]);
  const byIdentity: Map<UIMessagePartTool, UIMessagePartTool> =
    new Map<UIMessagePartTool, UIMessagePartTool>();
  const idCounts: Map<string, number> = new Map<string, number>();
  for (const pair of pairs) {
    byIdentity.set(pair.input, pair.result);
    if (pair.input.toolCallId.length > 0) {
      idCounts.set(pair.input.toolCallId, (idCounts.get(pair.input.toolCallId) ?? 0) + 1);
    }
  }
  const byUniqueId: Map<string, UIMessagePartTool> = new Map<string, UIMessagePartTool>();
  for (const pair of pairs) {
    const id: string = pair.input.toolCallId;
    if (id.length > 0 && idCounts.get(id) === 1) byUniqueId.set(id, pair.result);
  }
  let changed: boolean = false;
  const parts: UIMessagePart[] = cur.parts.map((p: UIMessagePart): UIMessagePart => {
    if (p.type !== 'tool') return p;
    const tp: UIMessagePartTool = p as UIMessagePartTool;
    const direct: UIMessagePartTool | undefined = byIdentity.get(tp);
    if (direct !== undefined) {
      if (direct !== tp) changed = true;
      return direct;
    }
    if (!isToolExecuted(tp) && tp.toolCallId.length > 0) {
      const byId: UIMessagePartTool | undefined = byUniqueId.get(tp.toolCallId);
      if (byId !== undefined) {
        changed = true;
        return byId;
      }
    }
    return p;
  });
  if (!changed) return conv;
  return rewriteLastMessage(conv, { ...cur, parts });
};

const emitSnapshot = (conv: Conversation, deps: ChatTurnDeps, ctx: TransformerContext): void => {
  if (deps.onUpdate !== undefined) {
    deps.onUpdate(applyVisualTransformersStreamingTail(
      currentMessages(conv), deps.outputTransformers, ctx));
  }
};

// ===== 主循环(:161-391) =====
// mainClamp=true preserves the existing main-agent 16..512 behavior. Subagent
// invocation context selects the same core with the exact requested step budget.
const runToolLoopCore = async (
  conversation: Conversation, deps: ChatTurnDeps, loop: ToolLoopOptions,
  mainClamp: boolean,
): Promise<Conversation> => {
  let conv: Conversation = conversation;
  const rawSteps: number = loop.maxSteps ?? DEFAULT_AGENT_MAX_TOOL_LOOP_STEPS;
  // 环路守护状态(本轮 run 级;签名表跨步累积)
  const executedSignatures: Map<string, number> = new Map();
  let guardStopped: boolean = false;
  const maxSteps: number = mainClamp
    ? Math.min(Math.max(rawSteps, MIN_AGENT_TOOL_LOOP_STEPS), MAX_AGENT_TOOL_LOOP_STEPS)
    : Math.max(rawSteps, 0);
  const dispatcher: AgentToolDispatcher = loop.dispatcher
    ?? new DefaultDispatcher({ hooks: defaultToolInvocationHooks() });
  let autoApproveTools: boolean = loop.autoApproveTools ?? false;
  let autoApproveHighRiskTools: boolean = loop.autoApproveHighRiskTools ?? false;
  const autoApprovedToolNames: string[] = loop.autoApprovedToolNames ?? [];
  const invocationContext: ToolInvocationContext = loop.invocationContext ?? 'normal';
  const toolRetry: GenerationRetrySetting = loop.toolRetrySetting
    ?? makeGenerationRetrySetting({ enabled: false });
  const ctx: TransformerContext = { assistant: deps.assistant, processingStatus: deps.onRetryStatus };
  // D-062:懒暴露状态(GenerationHandler:159 — 循环开始前 from(tools))
  const toolExposure: ToolExposureState = createToolExposureState(loop.tools);
  let currentTools: AgentTool[] = loop.tools;

  for (let stepIndex = 0; stepIndex < maxSteps; stepIndex++) {
    if (deps.abortSignal !== undefined && deps.abortSignal.aborted) break;
    if (guardStopped) break;
    if (loop.refreshTools !== undefined) {
      currentTools = await loop.refreshTools();
      toolExposure.replaceTools(currentTools);
      autoApproveTools = loop.autoApproveTools ?? false;
      autoApproveHighRiskTools = loop.autoApproveHighRiskTools ?? false;
    }
    const recipeAdapter: RecipeLoopAdapter | undefined = loop.recipeAdapter;
    const pluginAdapter: PluginLoopAdapter | undefined = loop.pluginAdapter;
    const goalAdapter: RecipeLoopAdapter | undefined = loop.goalAdapter;
    const supportsPackage = (name: string): boolean => recipeAdapter?.supports(name) === true
      || pluginAdapter?.supports(name) === true || goalAdapter?.supports(name) === true;
    const baseMessages: UIMessage[] = currentMessages(conv);
    const lastMsg: UIMessage | undefined = baseMessages.length > 0
      ? baseMessages[baseMessages.length - 1]
      : undefined;
    // pendingTools(:164-166):末条消息 canResumeExecution 工具
    const manualPart: UIMessagePartTool | undefined = lastMsg?.parts.find(
      (part: UIMessagePart): boolean => part.type === 'tool'
        && (part as UIMessagePartTool).toolCallId === loop.manualToolCallId) as UIMessagePartTool | undefined;
    if (loop.manualToolCallId !== undefined && (manualPart === undefined
      || !((recipeAdapter?.supports(manualPart.toolName) === true && manualPart.metadata?.['recipe_manual'] === true)
        || (pluginAdapter?.supports(manualPart.toolName) === true && manualPart.metadata?.['plugin_manual'] === true)))) {
      throw new Error('Manual Recipe call is missing its persisted parent.');
    }
    if (manualPart !== undefined && (isToolExecuted(manualPart)
      || manualPart.approvalState.type === 'pending')) break;
    let recipeBatch: boolean = lastMsg !== undefined
      && lastMsg.parts.some((part: UIMessagePart): boolean =>
        part.type === 'tool' && supportsPackage((part as UIMessagePartTool).toolName));
    let pendingTools: UIMessagePartTool[] = lastMsg !== undefined
      ? lastMsg.parts.filter((p: UIMessagePart): boolean =>
        p.type === 'tool' && (canToolResumeExecution(p as UIMessagePartTool)
          || (loop.manualToolCallId !== undefined && (p as UIMessagePartTool).toolCallId === loop.manualToolCallId
            && (p as UIMessagePartTool).approvalState.type === 'auto'))) as UIMessagePartTool[]
      : [];
    if (recipeBatch && pendingTools.length > 0 && lastMsg !== undefined) {
      pendingTools = lastMsg.parts.filter((part: UIMessagePart): boolean =>
        part.type === 'tool' && !isToolExecuted(part as UIMessagePartTool)) as UIMessagePartTool[];
    }
    const hasResumableTools: boolean = pendingTools.length > 0;
    if (recipeBatch && !hasResumableTools && lastMsg !== undefined
      && lastMsg.parts.some((part: UIMessagePart): boolean => part.type === 'tool'
        && (part as UIMessagePartTool).approvalState.type === 'pending')) break;
    // D-062(:167):待恢复 pending 工具强制暴露(先于 toolsForStep)
    toolExposure.exposeToolNames(
      pendingTools.map((t: UIMessagePartTool): string => t.toolName));
    const budgetPrompt: string = buildAgentLoopBudgetPrompt(stepIndex, maxSteps);
    const hideTools: boolean = agentLoopShouldHideTools(stepIndex, maxSteps, hasResumableTools);
    // toolsInternal(:177-186):预算 FINAL 且无 resumable → 空;
    //   D-062 否则 toolsForStep()(懒模式 = 常驻+已暴露子集)
    const stepTools: AgentTool[] = hideTools ? [] : toolExposure.toolsForStep();
    const stepDefsMap = new Map<string, AgentTool>(
      stepTools.map((t: AgentTool): [string, AgentTool] => [t.name, t]));
    // D-063(:187-197):每步推测执行器 — enabled && streamOutput;
    //   maxConcurrentTools 默认 4(PreferencesStore.kt:228)
    const speculativeRunner: SpeculativeToolRunner | null =
      (loop.speculativeEnabled ?? false) && deps.assistant.streamOutput !== false
        ? createSpeculativeToolRunner({
          dispatcher,
          maxConcurrentTools: loop.speculativeMaxConcurrentTools ?? 4,
          invocationContext,
        })
        : null;

    let toolsToProcess: UIMessagePartTool[];

    // 有 approved/denied 工具待处理时跳过生成(:200-201)
    if (!hasResumableTools) {
      // 生成一步(每步 prepareContext,对齐 Android generateInternal 内部位置)
      // 空 parts 消息不进上下文(分支再生流式尾占位,runRegenerateAt:124-129 同构);
      //   baseMessages 保持原样供 accumulator 播种与写回下标对齐
      const contextInput: UIMessage[] = baseMessages.filter(
        (m: UIMessage): boolean => m.parts.length > 0);
      const preparedMessages: UIMessage[] = deps.prepareContextMessages !== undefined
        ? await deps.prepareContextMessages(contextInput)
        : contextInput;
      // D-077a:tool system prompt 块(每步重算,buildSystemPromptParts:739-741;
      //   工具集 = 本步暴露子集,与发往 provider 的 stepDefs 同口径)
      const toolPrompt: string = loop.toolPromptModel !== undefined
        ? buildToolSystemPrompt(stepTools, loop.toolPromptModel, preparedMessages)
        : '';
      const assembledBase: UIMessage[] = assembleInternalMessages({
        messages: preparedMessages,
        assistant: deps.assistant,
        agentSoul: deps.agentSoul ?? '',
        // D-077b:Android 动态块序 memory → loopBudget → generativeUi → recentChats
        //   (:726-736);D-085b memory 前置,budget 居中,deps 供给(recentChats)随后
        extraSystemBlocks: [
          ...(deps.memorySystemBlocks !== undefined ? await deps.memorySystemBlocks() : []),
          ...(budgetPrompt.length > 0 ? [budgetPrompt] : []),
          ...(deps.extraSystemBlocks !== undefined ? await deps.extraSystemBlocks() : []),
        ],
        toolPrompt,
        contextMessageSize: deps.prepareContextMessages !== undefined
          ? 0
          : (deps.contextMessageSize ?? 0),
      });
      // 工具 prompt 和物化后的文档/OCR/模板一起进入最终预算。
      const prepareInternalMessages = async (forceImageToText: boolean = false): Promise<UIMessage[]> => {
        const transformed: UIMessage[] = await applyInputTransformers(
          assembledBase, deps.inputTransformers, forceImageToText ? { ...ctx, forceImageToText: true } : ctx);
        return deps.finalTokenBudget !== undefined
          ? fitMessagesToTokenBudget(transformed, deps.finalTokenBudget) : transformed;
      };
      const internalMessages: UIMessage[] = await prepareInternalMessages();
      const visionFallback: VisionFallbackHook | undefined =
        deps.modelSupportsImageInput === undefined ? undefined : {
          modelSupportsImageInput: deps.modelSupportsImageInput,
          rebuildInternalMessages: (): Promise<UIMessage[]> => prepareInternalMessages(true),
        };
      const stepProvider: ChatStreamProvider = loop.makeProviderForStep(
        stepTools.map((t: AgentTool): ChatToolDefinition => toChatToolDefinition(t)));
      // D-063(:519-526/:540-543):raw 快照 → 推测观察(末条消息工具 × 暴露 defs)
      const stepDeps: ChatTurnDeps = speculativeRunner === null ? deps : {
        ...deps,
        onRawFlushSnapshot: (msgs: UIMessage[]): void => {
          if (deps.onRawFlushSnapshot !== undefined) deps.onRawFlushSnapshot(msgs);
          const last: UIMessage | undefined = msgs.length > 0
            ? msgs[msgs.length - 1]
            : undefined;
          const observed: UIMessagePartTool[] = last !== undefined
            ? last.parts.filter((p: UIMessagePart): boolean =>
              p.type === 'tool') as UIMessagePartTool[]
            : [];
          speculativeRunner.observe(observed, stepDefsMap);
        },
      };
      const finalMessages: UIMessage[] = await generateAssistantOnce(
        baseMessages, internalMessages, stepDeps, stepProvider, visionFallback);
      // Android messages = accumulator.snapshot() 全量覆盖:末条 base 消息
      //   (assistant 工具消息)可能被就地合并进新内容(text/tool 续写同一消息);
      //   引用不等 → 节点写回;新增消息 → 追加入列
      const lastBase: UIMessage = baseMessages[baseMessages.length - 1];
      const mergedLast: UIMessage = finalMessages[baseMessages.length - 1];
      if (mergedLast !== lastBase) {
        conv = rewriteLastMessage(conv, mergedLast);
      }
      const generated: UIMessage[] = finalMessages.slice(baseMessages.length);
      for (const msg of generated) {
        conv = appendNode(conv, msg);
      }
      if (mergedLast !== lastBase || generated.length > 0) {
        await deps.store.save(conv);
      }
      if (deps.abortSignal !== undefined && deps.abortSignal.aborted) break;

      const stepMessages: UIMessage[] = currentMessages(conv);
      const newLast: UIMessage = stepMessages[stepMessages.length - 1];
      const tools: UIMessagePartTool[] = newLast.parts.filter((p: UIMessagePart): boolean =>
        p.type === 'tool' && !isToolExecuted(p as UIMessagePartTool)) as UIMessagePartTool[];
      if (tools.length === 0) {
        // 无工具调用 → 结束(:281-285)
        break;
      }
      recipeBatch = newLast.parts.some(
        (part: UIMessagePart): boolean => part.type === 'tool'
          && supportsPackage((part as UIMessagePartTool).toolName));

      // 审批决策(:287-339):ASK → 置 Pending + permission_trace
      let hasPendingApproval: boolean = false;
      let changed: boolean = false;
      const updatedTools: UIMessagePartTool[] = [];
      for (const tool of tools) {
        if (recipeBatch) { updatedTools.push(tool); continue; }
        let invocation: UIMessagePartTool = tool;
        if (loop.captureInvocationMetadata !== undefined && tool.approvalState.type === 'auto') {
          const captured: JsonObject | null = loop.captureInvocationMetadata(tool);
          if (captured !== null) {
            invocation = { ...tool, metadata: { ...(tool.metadata ?? {}), ...captured } };
            changed = true;
          }
        }
        const toolDef: AgentTool | null = stepDefsMap.get(tool.toolName) ?? null;
        const decision: PermissionDecision = await dispatcher.resolveReviewedDecision(
          toolDef, invocation, autoApproveTools, autoApproveHighRiskTools,
          autoApprovedToolNames, invocationContext, deps.abortSignal);
        if (decision.action === 'ask') {
          hasPendingApproval = true;
          changed = true;
          const metadata: JsonObject = invocation.metadata !== null ? { ...invocation.metadata } : {};
          metadata['permission_trace'] = permissionDecisionTraceToJson(decision.trace);
          updatedTools.push({ ...invocation, approvalState: { type: 'pending' }, metadata });
          continue;
        }
        if (tool.approvalState.type === 'pending') hasPendingApproval = true;
        updatedTools.push(invocation);
      }
      if (deps.abortSignal?.aborted) break;

      if (changed) {
        // approval 写回同样按输入身份:tools[i] 是消息里的原 part,updatedTools[i]
        //   是改写后结果(未变则同一引用),空 id 也不互相命中(R08)。
        const approvalPairs: ToolBatchResultPair[] = tools.map(
          (tool: UIMessagePartTool, i: number): ToolBatchResultPair =>
            ({ input: tool, result: updatedTools[i] }));
        conv = rewriteLastMessageTools(conv, approvalPairs);
        await deps.store.save(conv);
        emitSnapshot(conv, deps, ctx);
      }
      // 有待审批 → break 等用户(:335-339)
      if (hasPendingApproval) {
        break;
      }
      toolsToProcess = updatedTools;
      // 环路守护:分类本步工具调用(1 执行 / 2 跳过 / ≥3 停止);
      //   跳过与停止项的结果已内嵌 skipped 输出,直接写回不执行
      const guardOutcome: ToolLoopGuardOutcome =
        classifyToolCallDuplicates(toolsToProcess, executedSignatures);
      toolsToProcess = guardOutcome.toExecute;
      if (guardOutcome.skipped.length > 0 || guardOutcome.stopping !== null) {
        const guardPairs: ToolBatchResultPair[] = guardOutcome.skipped.map(
          (t: UIMessagePartTool): ToolBatchResultPair => ({ input: t, result: t }));
        if (guardOutcome.stopping !== null) {
          const stopTool: UIMessagePartTool = guardOutcome.stopping;
          guardPairs.push({ input: stopTool, result: stopTool });
        }
        conv = rewriteLastMessageTools(conv, guardPairs);
        await deps.store.save(conv);
        emitSnapshot(conv, deps, ctx);
        if (guardOutcome.stopping !== null) guardStopped = true;
      }
    } else {
      // resume 路径(:342-346):用户交互后用 resumable 工具直接执行
      toolsToProcess = pendingTools;
    }

    // 执行(approved 执行/denied 处理)(:348-358)
    // abort 到达后不再执行任何工具副作用；未执行工具留给页面 stop 收口。
    if (deps.abortSignal !== undefined && deps.abortSignal.aborted) break;
    let executedPairs: ToolBatchResultPair[];
    if (recipeBatch) {
      const batch = await executeRecipeBatch(toolsToProcess, {
        adapter: recipeAdapter, pluginAdapter, goalAdapter, tools: currentTools, dispatcher,
        autoApproveTools, autoApproveHighRiskTools, invocationContext,
        autoApprovedToolNames, toolRetry,
        capture: loop.captureInvocationMetadata,
        signal: deps.abortSignal,
        save: async (previous: UIMessagePartTool, next: UIMessagePartTool): Promise<void> => {
          conv = rewriteLastMessageTools(conv, [{ input: previous, result: next }]);
          await deps.store.save(conv);
          emitSnapshot(conv, deps, ctx);
        },
      });
      executedPairs = batch.pairs;
      if (batch.paused) break;
    } else {
    const prefetchedTools: Map<string, UIMessagePartTool> = speculativeRunner !== null
      ? await speculativeRunner.reusableResults(toolsToProcess)
      : new Map<string, UIMessagePartTool>();
    if (deps.abortSignal !== undefined && deps.abortSignal.aborted) break;
    executedPairs = await dispatcher.executeBatchPairs(
      toolsToProcess, stepDefsMap,
      autoApproveTools, autoApproveHighRiskTools, autoApprovedToolNames,
      invocationContext, prefetchedTools, toolRetry, deps.abortSignal);
    }
    if (executedPairs.length === 0) {
      // 全部 pending 无结果(:360-363)
      break;
    }
    // D-062(:364):tool_search expanded_tools 步间暴露(先于写回)
    toolExposure.observeExecutedTools(
      executedPairs.map((pair: ToolBatchResultPair): UIMessagePartTool => pair.result));

    // 写回同一 assistant 消息(:366-373;按输入 part 身份定位)
    if (!recipeBatch) {
      conv = rewriteLastMessageTools(conv, executedPairs);
      await deps.store.save(conv);
      emitSnapshot(conv, deps, ctx);
    }
    if (loop.manualToolCallId !== undefined) break;

    // steer 消息步间消费(:386-390)
    const steerMessages: UIMessage[] = loop.consumeSteerMessages !== undefined
      ? await loop.consumeSteerMessages()
      : [];
    if (steerMessages.length > 0) {
      for (const m of steerMessages) {
        conv = appendNode(conv, m);
      }
      await deps.store.save(conv);
      emitSnapshot(conv, deps, ctx);
    }
  }
  return conv;
};

export const runAgenticToolLoop = async (
  conversation: Conversation, deps: ChatTurnDeps, loop: ToolLoopOptions,
): Promise<Conversation> => runToolLoopCore(
  conversation, deps, loop, loop.invocationContext !== 'subagent');

// ===== 入口:带工具循环的发送(runChatTurn 同序:user 先落库再循环) =====

export const runChatTurnWithTools = async (
  conversation: Conversation, userInput: string | UIMessagePart[],
  deps: ChatTurnDeps, loop: ToolLoopOptions,
): Promise<Conversation> => {
  // 空工具集 → 单跑(chat_turn 行为逐字节一致)
  if (loop.tools.length === 0) {
    return runChatTurn(conversation, userInput, deps);
  }
  // user 消息入列 + 持久化(D-051 runAppendUserMessage 同语义;
  //   空输入 no-op 引用原样返回)
  const conv: Conversation = await runAppendUserMessage(conversation, userInput, deps.store);
  if (conv === conversation) {
    return conversation;
  }
  return runAgenticToolLoop(conv, deps, loop);
};

// ===== 入口:审批后续跑(ChatService.handleToolApproval → handleMessageComplete 语义) =====
// 不追加 user 消息,直接以当前会话进循环;末条 assistant 的 approved/denied
//   工具(canResumeExecution)走 resume 路径
export const runToolLoopContinuation = async (
  conversation: Conversation, deps: ChatTurnDeps, loop: ToolLoopOptions,
): Promise<Conversation> => {
  if (loop.tools.length === 0) {
    return conversation;
  }
  return runAgenticToolLoop(conversation, deps, loop);
};

// ===== 入口:分支再生 + 工具循环(ChatService.regenerateAtMessage:1078-1118) =====
// user 节点:截断先持久化(prepareRegenerateSeed),生成节点自然追加 — 与
//   runAgenticToolLoop 直跑同构
// assistant 节点:基底 = 目标节点之前;追加空 assistant 占位节点作流式尾
//   (runRegenerateAt:124-129 占位同构 — 否则新流会被 accumulator 并入基底
//   末条 assistant),循环产出合入占位节点;完成后占位消息并入目标节点
//   alternatives(分支追加不覆盖,selectIndex 指向新分支),其余生成节点
//   (steer 等)插入目标之后
// 登记偏差:assistant 分支再生中途持久化形态 = 基底+占位节点(追加形态),
//   完成后改写为分支形态;Android 窗口化 merge 依附其分页架构
//   (PARITY_DEBT 既登,扁平节点模型下等价语义)
export const runRegenerateAtWithTools = async (
  conversation: Conversation, nodeId: string, deps: RegenerateDeps, loop: ToolLoopOptions,
): Promise<Conversation> => {
  if (loop.tools.length === 0) {
    return runRegenerateAt(conversation, nodeId, deps);
  }
  const seedInfo: RegenerateSeed = await prepareRegenerateSeed(conversation, nodeId, deps.store);
  if (seedInfo.isUserNode) {
    return runAgenticToolLoop(seedInfo.seed, deps, loop);
  }
  const placeholder: MessageNode = toMessageNode(makeUIMessage('assistant', []));
  const working: Conversation = {
    ...seedInfo.seed,
    messageNodes: [...seedInfo.seed.messageNodes, placeholder],
    updateAt: nowIso(),
  };
  const out: Conversation = await runAgenticToolLoop(working, deps, loop);
  const target: MessageNode = conversation.messageNodes[seedInfo.nodeIndex];
  const placeholderMsg: UIMessage = nodeCurrentMessage(out.messageNodes[seedInfo.nodeIndex]);
  const extraNodes: MessageNode[] = out.messageNodes.slice(seedInfo.nodeIndex + 1);
  // 占位无产出(abort 即刻/无 chunk,runRegenerateAt !sawChunk 同语义)→ 目标节点不变
  const mergedTarget: MessageNode = placeholderMsg.parts.length > 0
    ? {
      ...target,
      messages: [...target.messages, placeholderMsg],
      selectIndex: target.messages.length,
    }
    : target;
  const merged: Conversation = {
    ...out,
    messageNodes: [
      ...conversation.messageNodes.slice(0, seedInfo.nodeIndex),
      mergedTarget,
      ...extraNodes,
      ...conversation.messageNodes.slice(seedInfo.nodeIndex + 1),
    ],
    updateAt: nowIso(),
  };
  await deps.store.save(merged);
  return merged;
};
