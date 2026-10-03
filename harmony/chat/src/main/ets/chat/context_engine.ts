import type { JevPreparedToolResults } from './jev_context.ts';
// context_engine.ts — 上下文压缩引擎(执行 + prepareContext 管线 + 存储 Port)
//
// Android 基准:
//   ConversationContextEngine.kt(prepareContext:112-286/compactConversation
//     :346-395/compactInternal:397-605/streamCompactSummary:674-729/
//     buildCompressionPrompt:731-773/withEffectiveMessages:775-791/
//     effectiveContextNextAction:794-806)
//   ConversationContextRepository.kt(invalidateCompacts:85-88/
//     copyValidCompactsToConversation:38-84)
//   PreparedContextEditor.kt(全文 205 行)
//   CompactSummaryNormalizer.kt(委托 D-054 payload 函数)
//   core/ai-prompts/.../CompressPrompt.kt(DEFAULT_COMPRESS_PROMPT 逐字)
//   StringUtils.kt applyPlaceholders(:37-44)
//
// 裁剪/偏差登记:
//   - compactMutex/_compactingConversations/生命周期 StateFlow → 调用方串行
//     (entry 单页面);onStream 回调替代 _summaryStreamFlow(30fps flush 裁剪,
//     每 chunk 回调)
//   - capabilitySnapshotBuilder.build(tools)(:303)— 工具能力快照,鸿蒙无工具
//     执行,跳过(工具循环落地时接入)
//   - tools 参数 promptOverhead 估算(:153)→ promptOverheadTokens 参数直入
//   - CancellationException → Error.name==='AbortError'(D-050 同口径)
//   - precompact 异步触发(engine:182-191 appScope.launch)→ launchPrecompact
//     回调;未提供时 floating promise + .catch(recordEvent)
//   - handoffPrompt(Agent-editable 配置)默认 '';locale 默认 'Chinese'

import type { UIMessage, UIMessagePart, UIMessagePartText, UIMessagePartTool } from './message.ts';
import { makeSystemMessage, makeUserMessage, isToolExecuted, toText } from './message.ts';
import type { MessageNode, Conversation } from './conversation.ts';
import { limitContext, nodeCurrentMessage, toMessageNode, currentMessages } from './conversation.ts';
import type { ChatStreamProvider } from './chat_turn.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type { JsonObject } from './json.ts';
import type {
  CompactPlan, CompactPolicy, CompactResult, ConversationCompact,
} from './context_compact.ts';
import {
  estimateTokens, estimateContextWindow, planCompaction, planForceCompaction,
  prepareMessagesWithCompacts, fitMessagesToTokenBudget, buildCompressionInput,
  selectCompactsForInjection, compactInjectionText, compactInjectionTextParts,
  normalizeCompactModelOutput, compactFallbackPayload, isHighQualityPayload,
  compactTimelineSummary, remapCoveredCompactIds, toolResultSummarize,
  compactSearchableText, estimateConversationInputTokens,
} from './context_compact.ts';

// ===== DEFAULT_COMPRESS_PROMPT(CompressPrompt.kt 逐字) =====

export const DEFAULT_COMPRESS_PROMPT: string = `You are a conversation compression assistant. Compress the following conversation into a structured continuation handoff.

Requirements:
1. Preserve key facts, decisions, and important context that would be needed to continue the conversation
2. Keep the summary in the same language as the original conversation
3. Target approximately {target_tokens} tokens
4. Return valid JSON only, with no Markdown fence and no prose before or after the JSON
5. The JSON must follow the schema requested in the additional context
6. Use {locale} language
7. Keep \`timeline_summary\` human-readable and keep \`handoff_markdown\` dense enough for another model to resume the task

{additional_context}

<conversation>
{content}
</conversation>`;

// ===== applyPlaceholders(StringUtils.kt:37-44) =====

export const applyPlaceholders = (template: string, pairs: [string, string][]): string => {
  let result: string = template;
  for (const [placeholder, replacement] of pairs) {
    result = result.split(`{${placeholder}}`).join(replacement);
  }
  return result;
};

// ===== buildCompressionPrompt(engine:731-773,模板逐字) =====

export interface CompressionPromptInput {
  basePrompt: string;
  content: string;
  targetTokens: number;
  additionalPrompt: string;
  sourceMessageIds: string[];
  previousCompacts: ConversationCompact[];
  coveredCompactIds: string[];
  payloadCreatedAt: number;
  handoffPrompt: string;
  locale: string;
}

export const buildCompressionPrompt = (input: CompressionPromptInput): string => {
  const previousCompactContext: string = input.previousCompacts
    .map(compactInjectionText)
    .join('\n\n');
  const coveredJson: string = input.coveredCompactIds.map((id: string): string => `"${id}"`).join(', ');
  const sourceJson: string = input.sourceMessageIds.map((id: string): string => `"${id}"`).join(', ');
  const structuredInstructions: string =
    'Return valid JSON only. Required schema:\n'
    + '{\n'
    + '  "schema_version": 2,\n'
    + '  "timeline_summary": "4-5 complete human-readable sentences in the user\'s language for the chat timeline.",\n'
    + '  "handoff_markdown": "Dense Markdown continuation handoff with sections: Goal, Constraints, Progress, Decisions, Current State, Next Steps, Critical Context, Relevant Files.",\n'
    + `  "covered_compact_ids": [${coveredJson}],\n`
    + `  "source_message_ids": [${sourceJson}],\n`
    + `  "created_at": ${input.payloadCreatedAt}\n`
    + '}\n'
    + '`covered_compact_ids`, `source_message_ids`, and `created_at` must exactly match the values above.\n'
    + 'Preserve concrete names, files, commands, errors, user preferences, rejected approaches, tool outcomes, and unresolved decisions.\n'
    + 'The timeline summary is for the human timeline; the handoff Markdown is what the next model will receive.\n'
    + '\n'
    + 'Agent-editable handoff instructions:\n'
    + `${input.handoffPrompt}\n`
    + '\n'
    + 'Previous compact handoffs to carry forward:\n'
    + `${previousCompactContext.trim().length > 0 ? previousCompactContext : 'None.'}`;
  const additionalContext: string = [structuredInstructions, input.additionalPrompt]
    .filter((s: string): boolean => s.trim().length > 0)
    .join('\n\n');
  return applyPlaceholders(input.basePrompt, [
    ['content', input.content],
    ['target_tokens', `${input.targetTokens}`],
    ['additional_context', additionalContext],
    ['locale', input.locale],
  ]);
};

// 质量重试附加提示(engine:502-505 逐字)
export const COMPACT_RETRY_SUFFIX: string =
  'Retry because the previous compaction did not satisfy the schema or the timeline summary was too short. Return valid JSON only. `timeline_summary` must contain 4-5 complete sentences, and `handoff_markdown` must contain the required sections.';

// ===== PreparedContextEditor(全文 205 行) =====

export interface ContextPreparationStepTrace {
  stage: string;
  reason: string;
  beforeTokens: number;
  afterTokens: number;
  savedTokens: number;
  changedMessages: number;
}

export interface ContextPreparationTrace {
  originalTokenEstimate: number;
  finalTokenEstimate: number;
  steps: ContextPreparationStepTrace[];
}

export const emptyContextPreparationTrace = (): ContextPreparationTrace => ({
  originalTokenEstimate: 0,
  finalTokenEstimate: 0,
  steps: [],
});

export interface PreparedContextEditResult {
  messages: UIMessage[];
  trace: ContextPreparationTrace;
}

const TRIM_TOOL_RESULT_AFTER_CHARS: number = 16000;
const TRIMMED_TOOL_RESULT_CHARS: number = 8000;
const CLEAR_TOOL_RESULT_AFTER_CHARS: number = 2000;

const CLEARABLE_TOOL_NAMES: string[] = [
  'file_list', 'file_read', 'file_search', 'tools_list', 'tool_search',
  'tool_policy_explain', 'conversation_context_status', 'conversation_search',
  'conversation_expand', 'agent_runtime_status', 'agent_task_list',
  'agent_task_read', 'mcp_list',
];
const SENSITIVE_SESSION_TOOLS: string[] = ['session_read', 'session_expand'];

const partsOutputChars = (parts: UIMessagePart[]): number =>
  parts.reduce((acc: number, part: UIMessagePart): number => {
    if (part.type === 'text') return acc + (part as UIMessagePartText).text.length;
    if (part.type === 'reasoning') {
      return acc + (part as unknown as { reasoning: string }).reasoning.length;
    }
    if (part.type === 'tool') {
      const t = part as UIMessagePartTool;
      return acc + t.input.length + partsOutputChars(t.output);
    }
    return acc + JSON.stringify(part).length;
  }, 0);

const partsContainsMultimodal = (parts: UIMessagePart[]): boolean =>
  parts.some((part: UIMessagePart): boolean => {
    if (part.type === 'image' || part.type === 'video'
      || part.type === 'audio' || part.type === 'document') return true;
    if (part.type === 'tool') return partsContainsMultimodal((part as UIMessagePartTool).output);
    return false;
  });

const partsLooksFailedOrDenied = (parts: UIMessagePart[]): boolean =>
  parts
    .filter((p: UIMessagePart): boolean => p.type === 'text')
    .some((p: UIMessagePart): boolean => {
      const text: string = (p as UIMessagePartText).text.toLowerCase();
      return text.includes('"status":"failed"') || text.includes('"status":"denied"')
        || text.includes('"approval_required"') || text.includes('"error"');
    });

const canEditPreparedResult = (tool: UIMessagePartTool): boolean =>
  isToolExecuted(tool)
  && tool.approvalState.type !== 'pending'
  && !partsContainsMultimodal(tool.output)
  && !partsLooksFailedOrDenied(tool.output);

const safeToClearPreparedResult = (tool: UIMessagePartTool): boolean =>
  CLEARABLE_TOOL_NAMES.includes(tool.toolName)
  || tool.toolName.startsWith('conversation_')
  || (tool.toolName.startsWith('session_') && !SENSITIVE_SESSION_TOOLS.includes(tool.toolName));

const trimToolResult = (tool: UIMessagePartTool): UIMessagePartTool => {
  if (!canEditPreparedResult(tool)) return tool;
  const outputChars: number = partsOutputChars(tool.output);
  if (outputChars <= TRIM_TOOL_RESULT_AFTER_CHARS) return tool;
  const preview: string = toolResultSummarize(tool.output, TRIMMED_TOOL_RESULT_CHARS);
  return {
    ...tool,
    output: [{
      type: 'text',
      text: JSON.stringify({
        status: 'trimmed_tool_result',
        tool_name: tool.toolName,
        tool_call_id: tool.toolCallId,
        original_output_chars: outputChars,
        preview,
      }),
      metadata: null,
    }],
  };
};

export const canClearPreparedToolResult = (tool: UIMessagePartTool): boolean =>
  canEditPreparedResult(tool) && safeToClearPreparedResult(tool) && partsOutputChars(tool.output) > CLEAR_TOOL_RESULT_AFTER_CHARS;

const clearToolResult = (tool: UIMessagePartTool): UIMessagePartTool => {
  if (!canEditPreparedResult(tool)) return tool;
  if (!safeToClearPreparedResult(tool)) return tool;
  const outputChars: number = partsOutputChars(tool.output);
  if (outputChars <= CLEAR_TOOL_RESULT_AFTER_CHARS) return tool;
  return {
    ...tool,
    output: [{
      type: 'text',
      text: JSON.stringify({
        status: 'cleared_tool_result',
        tool_name: tool.toolName,
        tool_call_id: tool.toolCallId,
        input_chars: tool.input.length,
        original_output_chars: outputChars,
        reason: 'Historical result was cleared from prepared context only. Original conversation storage is unchanged; call the tool again or expand history if exact output is needed.',
      }),
      metadata: null,
    }],
  };
};

interface EditorStageResult {
  messages: UIMessage[];
  trace: ContextPreparationStepTrace;
}

const editMessageTools = (
  message: UIMessage, index: number, messageCount: number, keepRecentMessages: number,
  transform: (t: UIMessagePartTool) => UIMessagePartTool,
): UIMessage => {
  if (index >= messageCount - Math.max(keepRecentMessages, 0)) return message;
  if (partsContainsMultimodal(message.parts)) return message;
  let changed: boolean = false;
  const parts: UIMessagePart[] = message.parts.map((part: UIMessagePart): UIMessagePart => {
    if (part.type === 'tool') {
      const next: UIMessagePartTool = transform(part as UIMessagePartTool);
      if (next !== part) changed = true;
      return next;
    }
    return part;
  });
  return changed ? { ...message, parts } : message;
};

const applyEditorStage = (
  stage: string, reason: string, messages: UIMessage[],
  transform: (m: UIMessage, i: number) => UIMessage,
): EditorStageResult => {
  const before: number = estimateTokens(messages);
  let changed: number = 0;
  const edited: UIMessage[] = messages.map((m: UIMessage, i: number): UIMessage => {
    const next: UIMessage = transform(m, i);
    if (next !== m) changed++;
    return next;
  });
  const after: number = estimateTokens(edited);
  return {
    messages: edited,
    trace: {
      stage, reason, beforeTokens: before, afterTokens: after,
      savedTokens: Math.max(before - after, 0), changedMessages: changed,
    },
  };
};

export const editPreparedContext = (
  messages: UIMessage[], keepRecentMessages: number, retainedToolCallIds: Set<string> = new Set(),
): PreparedContextEditResult => {
  const originalTokens: number = estimateTokens(messages);
  const trim: EditorStageResult = applyEditorStage(
    'trim', 'long historical tool results are trimmed before compaction planning',
    messages,
    (m: UIMessage, i: number): UIMessage =>
      editMessageTools(m, i, messages.length, keepRecentMessages,
        (tool): UIMessagePartTool => retainedToolCallIds.has(tool.toolCallId) ? tool : trimToolResult(tool)));
  const clear: EditorStageResult = applyEditorStage(
    'clear', 'retriable historical tool results are replaced with placeholders',
    trim.messages,
    (m: UIMessage, i: number): UIMessage =>
      editMessageTools(m, i, trim.messages.length, keepRecentMessages,
        (tool): UIMessagePartTool => retainedToolCallIds.has(tool.toolCallId) ? tool : clearToolResult(tool)));
  return {
    messages: clear.messages,
    trace: {
      originalTokenEstimate: originalTokens,
      finalTokenEstimate: estimateTokens(clear.messages),
      steps: [trim.trace, clear.trace],
    },
  };
};

// ===== CompactStore Port + memory 实现 =====

export interface CompactStore {
  getCompacts(conversationId: string): Promise<ConversationCompact[]>;
  insertCompact(compact: ConversationCompact): Promise<void>;
  deleteByConversation(conversationId: string): Promise<void>;
  recordEvent?(conversationId: string, eventType: string,
    summaryId: string | null, message: string): Promise<void>;
}

export interface MemoryCompactStore extends CompactStore {
  compacts: Map<string, ConversationCompact[]>;
  events: string[];
}

export const createMemoryCompactStore = (): MemoryCompactStore => {
  const compacts = new Map<string, ConversationCompact[]>();
  const events: string[] = [];
  return {
    compacts,
    events,
    getCompacts(conversationId: string): Promise<ConversationCompact[]> {
      return Promise.resolve(compacts.get(conversationId) ?? []);
    },
    insertCompact(compact: ConversationCompact): Promise<void> {
      const list: ConversationCompact[] = compacts.get(compact.conversationId) ?? [];
      compacts.set(compact.conversationId, [...list, compact]);
      return Promise.resolve();
    },
    deleteByConversation(conversationId: string): Promise<void> {
      compacts.delete(conversationId);
      return Promise.resolve();
    },
    recordEvent(conversationId: string, eventType: string,
      summaryId: string | null, message: string): Promise<void> {
      events.push(`${conversationId}|${eventType}|${summaryId ?? ''}|${message}`);
      return Promise.resolve();
    },
  };
};

// ===== invalidateCompacts(repository:85-88) =====

export const invalidateCompacts = async (
  store: CompactStore, conversationId: string, reason: string,
): Promise<void> => {
  await store.deleteByConversation(conversationId);
  if (store.recordEvent !== undefined) {
    await store.recordEvent(conversationId, 'compact_invalidated', null, reason);
  }
};

// ===== copyValidCompactsToConversation(repository:38-84) =====

export const copyValidCompactsToConversation = async (
  store: CompactStore, sourceConversationId: string,
  targetConversation: Conversation, newId: () => string, now: () => number,
  reason: string = 'conversation_forked_compacts_copied',
): Promise<number> => {
  const targetMessageIds: Set<string> = new Set<string>(
    targetConversation.messageNodes.map((n: MessageNode): string => nodeCurrentMessage(n).id));
  const targetLastIndex: number = targetConversation.messageNodes.length - 1;
  const eligible: ConversationCompact[] = (await store.getCompacts(sourceConversationId))
    .filter((c: ConversationCompact): boolean =>
      c.status === 'completed' && c.sourceEndIndex <= targetLastIndex
      && c.sourceMessageIds.length > 0
      && c.sourceMessageIds.every((id: string): boolean => targetMessageIds.has(id)));
  if (eligible.length === 0) return 0;
  const idMapping = new Map<string, string>();
  for (const c of eligible) idMapping.set(c.id, newId());
  const nowMs: number = now();
  for (const c of eligible) {
    await store.insertCompact({
      ...c,
      id: idMapping.get(c.id) as string,
      conversationId: targetConversation.id,
      summary: remapCoveredCompactIds(c.summary, idMapping),
      updatedAt: nowMs,
    });
  }
  if (store.recordEvent !== undefined) {
    await store.recordEvent(targetConversation.id, reason, null,
      `Copied ${eligible.length} compact summaries from fork parent`);
  }
  return eligible.length;
};

// ===== compactConversation(engine:346-605) =====

export interface CompactEngineDeps {
  abortSignal?: AbortSignalLike;
  provider: ChatStreamProvider;
  store: CompactStore;
  now?: () => number;
  newId?: () => string;
  handoffPrompt?: string;
  locale?: string;
  compressPrompt?: string;
  onStream?: (text: string) => void;
  prepareToolResults?: (messages: UIMessage[], keepRecentMessages: number) => Promise<JevPreparedToolResults>;
}

const streamCompactSummary = async (
  deps: CompactEngineDeps, prompt: string,
): Promise<string> => {
  const checkAborted = (): void => {
    if (!deps.abortSignal?.aborted) return;
    const error: Error = new Error('Context compaction was cancelled');
    error.name = 'AbortError';
    throw error;
  };
  checkAborted();
  let accumulated: string = '';
  await deps.provider.streamText([makeUserMessage(prompt)], (chunk): void => {
    const choice = chunk.choices[0];
    const parts: UIMessagePart[] = choice !== undefined
      ? (choice.delta !== null ? choice.delta.parts
        : (choice.message !== null ? choice.message.parts : []))
      : [];
    const deltaText: string = parts
      .filter((p: UIMessagePart): boolean => p.type === 'text')
      .map((p: UIMessagePart): string => (p as UIMessagePartText).text)
      .join('');
    if (deltaText.length > 0) {
      accumulated += deltaText;
      if (deps.onStream !== undefined) deps.onStream(accumulated);
    }
  }, deps.abortSignal === undefined ? undefined : { signal: deps.abortSignal });
  checkAborted();
  return accumulated.trim();
};

export const compactConversation = async (
  conversation: Conversation, policy: CompactPolicy,
  modelContextWindowTokens: number | null,
  reason: string = 'manual_compact',
  additionalPrompt: string = '',
  force: boolean = false,
  deps: CompactEngineDeps,
): Promise<CompactResult> => {
  const now: () => number = deps.now ?? Date.now;
  try {
    const activeCompacts: ConversationCompact[] = await deps.store.getCompacts(conversation.id);
    const enabledPolicy: CompactPolicy = { ...policy, enabled: true };
    const plan: CompactPlan = force
      ? planForceCompaction(conversation.messageNodes, activeCompacts,
        enabledPolicy, modelContextWindowTokens)
      : planCompaction(conversation.messageNodes, activeCompacts,
        enabledPolicy, modelContextWindowTokens);
    if (!plan.shouldCompact) {
      if (deps.store.recordEvent !== undefined) {
        await deps.store.recordEvent(conversation.id, reason, null, `Skipped: ${plan.reason}`);
      }
      return {
        status: 'skipped',
        estimatedTokensBefore: plan.estimatedTokens,
        estimatedTokensAfter: plan.estimatedTokens,
        error: plan.reason,
      };
    }
    const nodes: MessageNode[] =
      conversation.messageNodes.slice(plan.sourceStartIndex, plan.sourceEndIndex + 1);
    const fullMessages: UIMessage[] = currentMessages(conversation);
    const preparedToolResults: JevPreparedToolResults | null = deps.prepareToolResults === undefined ? null
      : await deps.prepareToolResults(fullMessages, Math.max(policy.keepRecentTurns * 2, 4));
    const byId: Map<string, UIMessage> = new Map();
    if (preparedToolResults !== null) {
      // Compression has always consumed canonical source text. Only explicit Jev projection changes it.
      for (const message of preparedToolResults.messages) byId.set(message.id, message);
    }
    const contentToCompress: string = buildCompressionInput(nodes.map((node): UIMessage =>
      byId.get(nodeCurrentMessage(node).id) ?? nodeCurrentMessage(node)));
    const existingMessageIds: Set<string> = new Set<string>(
      conversation.messageNodes.map((n: MessageNode): string => nodeCurrentMessage(n).id));
    const previousCompacts: ConversationCompact[] =
      selectCompactsForInjection(activeCompacts, existingMessageIds);
    const coveredCompactIds: string[] = previousCompacts.map(
      (c: ConversationCompact): string => c.id);
    const previousCompactContext: string = previousCompacts
      .map(compactInjectionText)
      .join('\n\n');
    const payloadCreatedAt: number = now();
    const basePrompt: string = deps.compressPrompt ?? DEFAULT_COMPRESS_PROMPT;
    const handoffPrompt: string = deps.handoffPrompt ?? '';
    const locale: string = deps.locale ?? 'Chinese';
    const promptInput = (additional: string): CompressionPromptInput => ({
      basePrompt,
      content: contentToCompress,
      targetTokens: policy.maxSummaryTokens,
      additionalPrompt: additional,
      sourceMessageIds: plan.sourceMessageIds,
      previousCompacts,
      coveredCompactIds,
      payloadCreatedAt,
      handoffPrompt,
      locale,
    });
    const summary: string = await streamCompactSummary(deps, buildCompressionPrompt(promptInput(additionalPrompt)));
    let normalizedSummary: string | null = normalizeCompactModelOutput(
      summary, plan.sourceMessageIds, coveredCompactIds, payloadCreatedAt);
    if (normalizedSummary === null || !isHighQualityPayload(normalizedSummary)) {
      const retryAdditional: string = [additionalPrompt, COMPACT_RETRY_SUFFIX]
        .filter((s: string): boolean => s.trim().length > 0)
        .join('\n\n');
      const retrySummary: string =
        await streamCompactSummary(deps, buildCompressionPrompt(promptInput(retryAdditional)));
      normalizedSummary = normalizeCompactModelOutput(
        retrySummary, plan.sourceMessageIds, coveredCompactIds, payloadCreatedAt);
      if (normalizedSummary === null || !isHighQualityPayload(normalizedSummary)) {
        normalizedSummary = compactFallbackPayload(
          retrySummary.trim().length > 0 ? retrySummary : summary,
          plan.sourceMessageIds, coveredCompactIds, payloadCreatedAt,
          contentToCompress, previousCompactContext);
      }
    }
    const compactId: string = deps.newId !== undefined ? deps.newId() : `${now()}`;
    const compact: ConversationCompact = {
      id: compactId,
      conversationId: conversation.id,
      summary: normalizedSummary,
      level: 1,
      sourceStartIndex: plan.sourceStartIndex,
      sourceEndIndex: plan.sourceEndIndex,
      sourceMessageIds: plan.sourceMessageIds,
      tokenEstimate: estimateTokens([makeSystemMessage(
        compactInjectionTextParts(compactId, normalizedSummary, plan.sourceMessageIds))]),
      createdAt: payloadCreatedAt,
      updatedAt: now(),
      status: 'completed',
    };
    await deps.store.insertCompact(compact);
    if (deps.store.recordEvent !== undefined) {
      const count: number = plan.shouldCompact
        ? plan.sourceEndIndex - plan.sourceStartIndex + 1
        : 0;
      await deps.store.recordEvent(conversation.id, reason, compact.id,
        `Compacted ${count} messages`);
    }
    return {
      status: 'completed',
      summaryId: compact.id,
      sourceMessageCount: plan.sourceEndIndex - plan.sourceStartIndex + 1,
      estimatedTokensBefore: plan.estimatedTokens,
      estimatedTokensAfter: compact.tokenEstimate,
    };
  } catch (e) {
    const err: Error = e as Error;
    if (err.name === 'AbortError') throw err;
    if (deps.store.recordEvent !== undefined) {
      await deps.store.recordEvent(conversation.id, reason, null, err.message);
    }
    return { status: 'failed', error: err.message };
  }
};

// ===== ContextCompactionFailedException(engine:52-67) =====

export class ContextCompactionFailedError extends Error {
  public readonly phase: string;
  public readonly compactionReason: string;

  constructor(phase: string, compactionReason: string) {
    super(`Context compression failed [${phase}]: ${compactionReason}`);
    this.name = 'ContextCompactionFailedError';
    this.phase = phase;
    this.compactionReason = compactionReason;
  }
}

// ===== prepareContext(engine:112-286) =====

export interface PreparedContext {
  messages: UIMessage[];
  tokenEstimate: number;
  compressionApplied: boolean;
  summaryIds: string[];
  trace: ContextPreparationTrace;
}

export interface PrepareContextDeps extends CompactEngineDeps {
  launchPrecompact?: (run: () => Promise<CompactResult>) => void;
  promptOverheadTokens?: number;
  // D-110:force 压缩活动钩子(Android ConversationContextEngine.kt:365/:451
  //   PLANNING/COMPACTING → COMPLETED/FAILED 的 compactActive 观察面子集;
  //   precompact 路径由 launchPrecompact 包装方自跟踪)
  onCompactActiveChange?: (active: boolean) => void;
}

// 以消息列表重建会话节点(D-055 entry 接线用:prepareContextMessages 回调拿到的
//   基线消息 → 重建 messageNodes 供压缩规划;message id 保持。entry 侧 ArkTS
//   禁对象展开,故helper 置于 HAR 内)
export const conversationWithMessagesAsNodes = (
  conv: Conversation, messages: UIMessage[],
): Conversation => ({
  ...conv,
  messageNodes: messages.map((m: UIMessage): MessageNode => toMessageNode(m)),
});

// withEffectiveMessages(engine:775-791)
export const withEffectiveMessages = (  conversation: Conversation, effectiveMessages: UIMessage[],
): Conversation => {
  const byId = new Map<string, UIMessage>();
  for (const m of effectiveMessages) byId.set(m.id, m);
  return {
    ...conversation,
    messageNodes: conversation.messageNodes.map((node: MessageNode): MessageNode => {
      const edited: UIMessage | undefined = byId.get(nodeCurrentMessage(node).id);
      if (edited === undefined || edited === nodeCurrentMessage(node)) return node;
      return {
        ...node,
        messages: node.messages.map((m: UIMessage): UIMessage =>
          m.id === edited.id ? edited : m),
      };
    }),
  };
};

// 同会话自动 precompact 单飞:prepareContext 可被连续触发(快速发消息/工具循环),
// 无门时会并发生成两份摘要并互相覆盖 insertCompact
const precompactInFlight: Set<string> = new Set<string>();

export const prepareContext = async (
  conversation: Conversation | null,
  policy: CompactPolicy,
  modelContextWindowTokens: number | null,
  messages: UIMessage[],
  contextMessageSize: number,
  deps: PrepareContextDeps,
): Promise<PreparedContext> => {
  const keepRecentMessages: number = Math.max(policy.keepRecentTurns * 2, 4);
  const preparedToolResults: JevPreparedToolResults = deps.prepareToolResults === undefined
    ? { messages, retainedToolCallIds: new Set() } : await deps.prepareToolResults(messages, keepRecentMessages);
  const editResult: PreparedContextEditResult = policy.enabled
    ? editPreparedContext(preparedToolResults.messages, keepRecentMessages, preparedToolResults.retainedToolCallIds)
    : {
      messages: preparedToolResults.messages,
      trace: {
        originalTokenEstimate: estimateTokens(messages),
        finalTokenEstimate: estimateTokens(preparedToolResults.messages),
        steps: [],
      },
    };
  const effectiveMessages: UIMessage[] = editResult.messages;
  const effectiveConversation: Conversation | null = conversation !== null
    ? withEffectiveMessages(conversation, effectiveMessages)
    : null;
  if (conversation === null || !policy.enabled) {
    const limited: UIMessage[] = limitContext(effectiveMessages, contextMessageSize);
    const estimate: number = estimateTokens(limited);
    return {
      messages: limited,
      tokenEstimate: estimate,
      compressionApplied: false,
      summaryIds: [],
      trace: { ...editResult.trace, finalTokenEstimate: estimate },
    };
  }
  const overheadEstimate: number = Math.max(deps.promptOverheadTokens ?? 0, 0);
  const compacts: ConversationCompact[] = await deps.store.getCompacts(conversation.id);
  const plan: CompactPlan = planCompaction(
    (effectiveConversation as Conversation).messageNodes,
    compacts, policy, modelContextWindowTokens, overheadEstimate);
  const shouldForce: boolean = plan.reason === 'force_threshold';
  if (plan.shouldCompact && !policy.notifyOnly) {
    if (shouldForce) {
      // D-110:PLANNING/COMPACTING → active(engine:365/:451;含失败终态复位)
      if (deps.onCompactActiveChange !== undefined) deps.onCompactActiveChange(true);
      try {
        const result: CompactResult = await compactConversation(
          conversation, policy, modelContextWindowTokens, 'auto_force', '', false, deps);
        if (result.status === 'failed') {
          throw new ContextCompactionFailedError('auto_force', result.error ?? result.status);
        }
      } finally {
        if (deps.onCompactActiveChange !== undefined) deps.onCompactActiveChange(false);
      }
    } else if (!precompactInFlight.has(conversation.id)) {
      precompactInFlight.add(conversation.id);
      const release = (): void => { precompactInFlight.delete(conversation.id); };
      // Background precompaction has an independent lifetime from this generation.
      const backgroundDeps: CompactEngineDeps = { ...deps, abortSignal: undefined };
      if (deps.launchPrecompact !== undefined) {
        try {
          deps.launchPrecompact((): Promise<CompactResult> =>
            compactConversation(
              conversation as Conversation, policy, modelContextWindowTokens,
              'auto_precompact', '', false, backgroundDeps).finally(release));
        } catch (e) {
          release(); // launcher 同步抛错/拒收回调时不泄漏门闩
          throw e instanceof Error ? e : new Error(String(e));
        }
      } else {
        const run: Promise<CompactResult> = compactConversation(
          conversation, policy, modelContextWindowTokens, 'auto_precompact', '', false, backgroundDeps)
          .finally(release);
        run.catch((e: Error): void => {
          if (deps.store.recordEvent !== undefined) {
            deps.store.recordEvent((conversation as Conversation).id,
              'auto_precompact', null, `floating precompact failed: ${e.message}`)
              .catch((): void => {});
          }
        });
      }
    }
  }
  let latestCompacts: ConversationCompact[] = await deps.store.getCompacts(conversation.id);
  const traceSteps: ContextPreparationStepTrace[] = [...editResult.trace.steps];
  const beforeCompactEstimate: number = estimateTokens(effectiveMessages) + overheadEstimate;
  let preparedMessages: UIMessage[] = prepareMessagesWithCompacts(
    effectiveMessages, latestCompacts, policy, contextMessageSize);
  const afterCompactEstimate: number = estimateTokens(preparedMessages) + overheadEstimate;
  if (latestCompacts.some((c: ConversationCompact): boolean => c.status === 'completed')) {
    traceSteps.push({
      stage: 'compact',
      reason: 'completed compact summaries replaced covered source messages',
      beforeTokens: beforeCompactEstimate,
      afterTokens: afterCompactEstimate,
      savedTokens: Math.max(beforeCompactEstimate - afterCompactEstimate, 0),
      changedMessages: latestCompacts.filter(
        (c: ConversationCompact): boolean => c.status === 'completed').length,
    });
  }
  const contextWindow: number = estimateContextWindow(modelContextWindowTokens);
  const softTotalBudget: number = Math.max(Math.trunc(contextWindow * policy.forceRatio), 4000);
  const targetMessageBudget: number = Math.max(softTotalBudget - overheadEstimate, 1000);
  let estimate: number = estimateTokens(preparedMessages) + overheadEstimate;
  if (policy.enabled && !policy.notifyOnly
    && estimate > Math.trunc(contextWindow * policy.forceRatio)) {
    const fitPolicy: CompactPolicy = {
      ...policy, keepRecentTurns: Math.max(Math.floor(policy.keepRecentTurns / 2), 2),
    };
    const result: CompactResult = await compactConversation(
      conversation, fitPolicy, modelContextWindowTokens,
      'auto_fit_model_window', '', true, deps);
    if (result.status === 'failed') {
      throw new ContextCompactionFailedError('auto_fit_model_window',
        result.error ?? result.status);
    }
    latestCompacts = await deps.store.getCompacts(conversation.id);
    const beforeFitCompactEstimate: number = estimateTokens(preparedMessages) + overheadEstimate;
    preparedMessages = prepareMessagesWithCompacts(
      effectiveMessages, latestCompacts, fitPolicy, contextMessageSize);
    estimate = estimateTokens(preparedMessages) + overheadEstimate;
    traceSteps.push({
      stage: 'compact',
      reason: 'forced compaction to fit model window',
      beforeTokens: beforeFitCompactEstimate,
      afterTokens: estimate,
      savedTokens: Math.max(beforeFitCompactEstimate - estimate, 0),
      changedMessages: latestCompacts.filter(
        (c: ConversationCompact): boolean => c.status === 'completed').length,
    });
  }
  if (estimate > Math.trunc(contextWindow * policy.forceRatio)) {
    const beforeFitEstimate: number = estimate;
    preparedMessages = fitMessagesToTokenBudget(preparedMessages, targetMessageBudget);
    estimate = estimateTokens(preparedMessages) + overheadEstimate;
    traceSteps.push({
      stage: 'fit',
      reason: 'prepared context still exceeded force ratio after editing/compaction',
      beforeTokens: beforeFitEstimate,
      afterTokens: estimate,
      savedTokens: Math.max(beforeFitEstimate - estimate, 0),
      changedMessages: 0,
    });
  }
  return {
    messages: preparedMessages,
    tokenEstimate: estimate,
    compressionApplied: latestCompacts.some(
      (c: ConversationCompact): boolean => c.status === 'completed'),
    summaryIds: latestCompacts
      .filter((c: ConversationCompact): boolean => c.status === 'completed')
      .map((c: ConversationCompact): string => c.id),
    trace: {
      originalTokenEstimate: editResult.trace.originalTokenEstimate,
      finalTokenEstimate: estimate,
      steps: traceSteps,
    },
  };
};

// ===== effectiveContextNextAction(engine:794-806) =====

export const effectiveContextNextAction = (
  policy: CompactPolicy, effectiveTokens: number, contextWindowTokens: number,
): string => {
  if (!policy.enabled) return 'disabled';
  const ratio: number = effectiveTokens / Math.max(contextWindowTokens, 1);
  if (ratio >= policy.forceRatio) return 'force_threshold';
  if (ratio >= policy.precompactRatio) return 'precompact_threshold';
  return 'below_threshold';
};

// ===== 检索/展开/状态(ConversationContextRepository.kt:95-134 + engine:623-666) =====
// D-058 随 conversation_* 工具落地(D-055 登记项);repository 的 DB 查询语义
//   转为纯函数(compacts 由调用方自 CompactStore 读取,conversation 直传)

export interface ContextSearchResult {
  source: string; // 'compact_summary' | 'message'
  id: string;
  preview: string;
  nodeIndex: number | null; // 仅 source='message'
}

// previewAround(repository:164-170 逐字):空白/未命中 → take(240);
//   命中 → index-120 .. index+qlen+120 窗口
export const previewAround = (text: string, query: string): string => {
  if (query.trim().length === 0) return text.slice(0, 240);
  const index: number = text.toLowerCase().indexOf(query.toLowerCase());
  if (index < 0) return text.slice(0, 240);
  const start: number = Math.max(index - 120, 0);
  const end: number = Math.min(index + query.length + 120, text.length);
  return text.substring(start, end);
};

// ASCII 折叠(SQL LIKE 大小写语义,persistence.ts 同口径)
const asciiLower = (s: string): string =>
  s.replace(/[A-Z]/g, (c: string): string => c.toLowerCase());

// search(repository:95-120):compact 摘要(searchableText LIKE 命中)→ 消息
//   节点扫描(currentMessage.toText() contains ignoreCase,带 nodeIndex);
//   消息结果取剩余额度,总量 take(limit)
export const searchConversationContext = (
  conversation: Conversation, compacts: ConversationCompact[],
  query: string, limit: number,
): ContextSearchResult[] => {
  const cappedLimit: number = Math.min(Math.max(limit, 1), 20);
  const compactResults: ContextSearchResult[] = [];
  if (query.trim().length > 0) {
    const needle: string = asciiLower(query);
    for (const c of compacts) {
      if (compactResults.length >= cappedLimit) break;
      const searchable: string = compactSearchableText(c.summary);
      if (asciiLower(searchable).includes(needle)) {
        compactResults.push({
          source: 'compact_summary',
          id: c.id,
          preview: previewAround(searchable, query),
          nodeIndex: null,
        });
      }
    }
  }
  const messageResults: ContextSearchResult[] = [];
  const remaining: number = Math.max(cappedLimit - compactResults.length, 0);
  if (remaining > 0 && query.trim().length > 0) {
    const needle: string = query.toLowerCase();
    const nodes: MessageNode[] = conversation.messageNodes;
    for (let i = 0; i < nodes.length && messageResults.length < remaining; i++) {
      const msg: UIMessage = nodeCurrentMessage(nodes[i]);
      const text: string = toText(msg);
      if (text.toLowerCase().includes(needle)) {
        messageResults.push({
          source: 'message',
          id: msg.id,
          preview: previewAround(text, query),
          nodeIndex: i,
        });
      }
    }
  }
  return [...compactResults, ...messageResults].slice(0, cappedLimit);
};

// expand(repository:122-134):compact id → sourceMessageIds 过滤 currentMessages;
//   否则消息 id 定位 ± radius(coerce 0..8)
export const expandConversationContext = (
  conversation: Conversation, compacts: ConversationCompact[],
  sourceId: string, radius: number,
): UIMessage[] => {
  const messages: UIMessage[] = currentMessages(conversation);
  const compact: ConversationCompact | undefined = compacts.find(
    (c: ConversationCompact): boolean => c.id === sourceId);
  if (compact !== undefined) {
    const ids: Set<string> = new Set<string>(compact.sourceMessageIds);
    return messages.filter((m: UIMessage): boolean => ids.has(m.id));
  }
  const index: number = messages.findIndex((m: UIMessage): boolean => m.id === sourceId);
  if (index < 0) return [];
  const r: number = Math.min(Math.max(radius, 0), 8);
  const start: number = Math.max(index - r, 0);
  const end: number = Math.min(index + r, messages.length - 1);
  return messages.slice(start, end + 1);
};

// status(engine:631-666):effective(注入替换后)为头条 + raw 对照;
//   偏差登记:compact 生命周期 StateFlow 未移植 → compact_lifecycle_status 恒
//   'idle',latest_lifecycle_compact_id 恒省略(Android 无记录时同形态)
export const conversationContextStatus = (
  conversation: Conversation, compacts: ConversationCompact[],
  policy: CompactPolicy, modelContextWindowTokens: number | null,
): JsonObject => {
  const plan: CompactPlan = planCompaction(
    conversation.messageNodes, compacts, policy, modelContextWindowTokens);
  const effectiveTokens: number = estimateConversationInputTokens(
    currentMessages(conversation), compacts);
  const rawPressureRatio: number = plan.estimatedTokens / plan.contextWindowTokens;
  const effectivePressureRatio: number = effectiveTokens / plan.contextWindowTokens;
  const lastCompact: ConversationCompact | undefined =
    compacts.length > 0 ? compacts[compacts.length - 1] : undefined;
  return {
    enabled: policy.enabled,
    notify_only: policy.notifyOnly,
    estimated_tokens: effectiveTokens,
    raw_tokens: plan.estimatedTokens,
    context_window_tokens: plan.contextWindowTokens,
    pressure_ratio: effectivePressureRatio,
    raw_pressure_ratio: rawPressureRatio,
    summary_count: compacts.filter(
      (c: ConversationCompact): boolean => c.status === 'completed').length,
    latest_status: lastCompact !== undefined ? lastCompact.status : 'none',
    compact_lifecycle_status: 'idle',
    next_action: effectiveContextNextAction(policy, effectiveTokens, plan.contextWindowTokens),
    raw_next_action: plan.reason,
  };
};
