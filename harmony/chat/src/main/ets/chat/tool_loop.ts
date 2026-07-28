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
//   - ToolExposureState 懒暴露(ToolSearch.kt)= P1(目录小时 toolsForStep=全量,
//     等价);SpeculativeToolRunner = P1(prefetchedTools 恒空)
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
import type { AgentToolDispatcher } from './tool_dispatcher.ts';
import { AgentToolDispatcher as DefaultDispatcher, defaultToolInvocationHooks } from './tool_dispatcher.ts';
import { buildAgentLoopBudgetPrompt, agentLoopShouldHideTools } from './agent_loop_budget.ts';
import type { ToolExposureState } from './tool_exposure.ts';
import { createToolExposureState } from './tool_exposure.ts';
import type { SpeculativeToolRunner } from './speculative_tool_runner.ts';
import { createSpeculativeToolRunner } from './speculative_tool_runner.ts';
import type { JsonObject } from './json.ts';

// ===== maxSteps 常量(PreferencesStore.kt:268-270) =====

export const MIN_AGENT_TOOL_LOOP_STEPS: number = 16;
export const MAX_AGENT_TOOL_LOOP_STEPS: number = 512;
export const DEFAULT_AGENT_MAX_TOOL_LOOP_STEPS: number = 256;

// ===== ToolLoopOptions(ChatService:1330-1360 调用面) =====

export interface ToolLoopOptions {
  tools: AgentTool[];
  // ChatService:1349-1352 coerceIn(16,512);默认 256
  maxSteps?: number;
  // settings.agentRuntime.autoApproveAllToolCalls || conversation.autoApproveToolCalls
  autoApproveTools?: boolean;
  autoApproveHighRiskTools?: boolean;
  // trustedRunToolNames[conversationId](screen 会话信任集)
  autoApprovedToolNames?: string[];
  invocationContext?: ToolInvocationContext;
  dispatcher?: AgentToolDispatcher;
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

// 结果写回末条 assistant 消息(:366-373;按 toolCallId 匹配,非工具 part 不动)
const rewriteLastMessageTools = (
  conv: Conversation, updatedTools: UIMessagePartTool[],
): Conversation => {
  const nodes: MessageNode[] = conv.messageNodes;
  if (nodes.length === 0) return conv;
  const cur: UIMessage = nodeCurrentMessage(nodes[nodes.length - 1]);
  const newMsg: UIMessage = {
    ...cur,
    parts: cur.parts.map((p: UIMessagePart): UIMessagePart => {
      if (p.type !== 'tool') return p;
      const tp: UIMessagePartTool = p as UIMessagePartTool;
      const hit: UIMessagePartTool | undefined = updatedTools.find(
        (t: UIMessagePartTool): boolean => t.toolCallId === tp.toolCallId);
      return hit ?? p;
    }),
  };
  return rewriteLastMessage(conv, newMsg);
};

const emitSnapshot = (conv: Conversation, deps: ChatTurnDeps, ctx: TransformerContext): void => {
  if (deps.onUpdate !== undefined) {
    deps.onUpdate(applyVisualTransformersStreamingTail(
      currentMessages(conv), deps.outputTransformers, ctx));
  }
};

// ===== 主循环(:161-391) =====

export const runAgenticToolLoop = async (
  conversation: Conversation, deps: ChatTurnDeps, loop: ToolLoopOptions,
): Promise<Conversation> => {
  let conv: Conversation = conversation;
  const rawSteps: number = loop.maxSteps ?? DEFAULT_AGENT_MAX_TOOL_LOOP_STEPS;
  const maxSteps: number = Math.min(Math.max(rawSteps, MIN_AGENT_TOOL_LOOP_STEPS), MAX_AGENT_TOOL_LOOP_STEPS);
  const dispatcher: AgentToolDispatcher = loop.dispatcher
    ?? new DefaultDispatcher({ hooks: defaultToolInvocationHooks() });
  const autoApproveTools: boolean = loop.autoApproveTools ?? false;
  const autoApproveHighRiskTools: boolean = loop.autoApproveHighRiskTools ?? false;
  const autoApprovedToolNames: string[] = loop.autoApprovedToolNames ?? [];
  const invocationContext: ToolInvocationContext = loop.invocationContext ?? 'normal';
  const toolRetry: GenerationRetrySetting = loop.toolRetrySetting
    ?? makeGenerationRetrySetting({ enabled: false });
  const ctx: TransformerContext = { assistant: deps.assistant, processingStatus: deps.onRetryStatus };
  // D-062:懒暴露状态(GenerationHandler:159 — 循环开始前 from(tools))
  const toolExposure: ToolExposureState = createToolExposureState(loop.tools);

  for (let stepIndex = 0; stepIndex < maxSteps; stepIndex++) {
    const baseMessages: UIMessage[] = currentMessages(conv);
    const lastMsg: UIMessage | undefined = baseMessages.length > 0
      ? baseMessages[baseMessages.length - 1]
      : undefined;
    // pendingTools(:164-166):末条消息 canResumeExecution 工具
    const pendingTools: UIMessagePartTool[] = lastMsg !== undefined
      ? lastMsg.parts.filter((p: UIMessagePart): boolean =>
        p.type === 'tool' && canToolResumeExecution(p as UIMessagePartTool)) as UIMessagePartTool[]
      : [];
    const hasResumableTools: boolean = pendingTools.length > 0;
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
      const assembled: UIMessage[] = assembleInternalMessages({
        messages: preparedMessages,
        assistant: deps.assistant,
        agentSoul: deps.agentSoul ?? '',
        extraSystemBlocks: budgetPrompt.length > 0 ? [budgetPrompt] : [],
        toolPrompt,
        contextMessageSize: deps.prepareContextMessages !== undefined
          ? 0
          : (deps.contextMessageSize ?? 0),
      });
      const internalMessages: UIMessage[] = await applyInputTransformers(
        assembled, deps.inputTransformers, ctx);
      // D-071a:视觉兜底重建闭包(逐步构建 — assembled 每步不同,
      //   chat_turn 同语义,GenerationHandler.kt:613/:648)
      const visionFallback: VisionFallbackHook | undefined =
        deps.modelSupportsImageInput === undefined ? undefined : {
          modelSupportsImageInput: deps.modelSupportsImageInput,
          rebuildInternalMessages: (): Promise<UIMessage[]> => applyInputTransformers(
            assembled, deps.inputTransformers, { ...ctx, forceImageToText: true }),
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

      const stepMessages: UIMessage[] = currentMessages(conv);
      const newLast: UIMessage = stepMessages[stepMessages.length - 1];
      const tools: UIMessagePartTool[] = newLast.parts.filter((p: UIMessagePart): boolean =>
        p.type === 'tool' && !isToolExecuted(p as UIMessagePartTool)) as UIMessagePartTool[];
      if (tools.length === 0) {
        // 无工具调用 → 结束(:281-285)
        break;
      }

      // 审批决策(:287-339):ASK → 置 Pending + permission_trace
      let hasPendingApproval: boolean = false;
      let changed: boolean = false;
      const updatedTools: UIMessagePartTool[] = tools.map((tool: UIMessagePartTool): UIMessagePartTool => {
        const toolDef: AgentTool | null = stepDefsMap.get(tool.toolName) ?? null;
        const decision: PermissionDecision = dispatcher.resolveDecision(
          toolDef, tool, autoApproveTools, autoApproveHighRiskTools,
          autoApprovedToolNames, invocationContext);
        if (decision.action === 'ask') {
          hasPendingApproval = true;
          changed = true;
          const metadata: JsonObject = tool.metadata !== null ? { ...tool.metadata } : {};
          metadata['permission_trace'] = permissionDecisionTraceToJson(decision.trace);
          return { ...tool, approvalState: { type: 'pending' }, metadata };
        }
        if (tool.approvalState.type === 'pending') {
          hasPendingApproval = true;
          return tool;
        }
        return tool;
      });

      if (changed) {
        conv = rewriteLastMessageTools(conv, updatedTools);
        await deps.store.save(conv);
        emitSnapshot(conv, deps, ctx);
      }
      // 有待审批 → break 等用户(:335-339)
      if (hasPendingApproval) {
        break;
      }
      toolsToProcess = updatedTools;
    } else {
      // resume 路径(:342-346):用户交互后用 resumable 工具直接执行
      toolsToProcess = pendingTools;
    }

    // 执行(approved 执行/denied 处理)(:348-358)
    // D-063(:356):prefetchedTools = runner.reusableResults(流终确认一致才复用)
    const prefetchedTools: Map<string, UIMessagePartTool> = speculativeRunner !== null
      ? await speculativeRunner.reusableResults(toolsToProcess)
      : new Map<string, UIMessagePartTool>();
    const executedTools: UIMessagePartTool[] = await dispatcher.executeBatch(
      toolsToProcess, stepDefsMap,
      autoApproveTools, autoApproveHighRiskTools, autoApprovedToolNames,
      invocationContext, prefetchedTools, toolRetry);
    if (executedTools.length === 0) {
      // 全部 pending 无结果(:360-363)
      break;
    }
    // D-062(:364):tool_search expanded_tools 步间暴露(先于写回)
    toolExposure.observeExecutedTools(executedTools);

    // 写回同一 assistant 消息(:366-373)
    conv = rewriteLastMessageTools(conv, executedTools);
    await deps.store.save(conv);
    emitSnapshot(conv, deps, ctx);

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
