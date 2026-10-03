import { beginJevContextRun } from './JevContextSupport.ets';
import {
  asChatStreamProvider, compactConversation, compactTimelineSummary, makeCompactPolicy,
  makeTextGenerationParams, newId, planForceCompaction, toChatModel,
} from '@amber/chat-domain';
import type { CompactPolicy, CompactResult, Conversation, ConversationCompact, ChatStreamProvider, UIMessage, JevPreparedToolResults } from '@amber/chat-domain';
import {
  prepareProviderApi, getChatKvStore, getCompactStore, loadTaskPrompts,
  readTaskModelReference, resolveChatRuntime, resolveTaskModelReference, TASK_COMPRESS_MODEL_KEY,
} from '../di/AppContainer.ets';
import type { ChatProviderChoice, ChatRuntime } from '../di/AppContainer.ets';
import { loadAgentRuntimeSnapshot } from './AgentRuntimePrefs.ets';

export interface ManualCompactionOutcome {
  status: 'completed' | 'skipped';
  message: string;
  summary: string;
  compacts: ConversationCompact[];
  contextWindowTokens: number | null;
}

const skippedMessage = (reason: string): string => {
  if (reason === 'already_compacted') return '较早的消息已压缩，没有需要新增压缩的历史。';
  if (reason === 'not_enough_new_history') return '可压缩的较早历史不足，近期对话会保留原文。';
  return '会话较短，无需压缩；最近一轮对话会保留原文。';
};

// Same task-model resolution and request parameters as automatic compaction.
// The service owns admission and serializes this operation with conversation writes.
export const compactConversationNow = async (conversation: Conversation): Promise<ManualCompactionOutcome> => {
  const settings = (await loadAgentRuntimeSnapshot(getChatKvStore())).contextCompaction;
  const policy: CompactPolicy = makeCompactPolicy({
    enabled: true, notifyOnly: false, precompactRatio: settings.precompactRatio,
    forceRatio: settings.forceRatio, keepRecentTurns: settings.keepRecentTurns,
    maxSummaryTokens: settings.maxSummaryTokens,
  });
  const existing: ConversationCompact[] = await getCompactStore().getCompacts(conversation.id);
  const plan = planForceCompaction(conversation.messageNodes, existing, policy, null);
  if (!plan.shouldCompact) return {
    status: 'skipped', message: skippedMessage(plan.reason), summary: '', compacts: existing, contextWindowTokens: null,
  };
  const runtime: ChatRuntime | null = await resolveChatRuntime(conversation.assistantId);
  if (runtime === null) throw new Error('请先配置可用的聊天模型。');
  const choice: ChatProviderChoice | null = await resolveTaskModelReference(
    await readTaskModelReference(TASK_COMPRESS_MODEL_KEY), runtime.resolvedChoice);
  if (choice === null) throw new Error('未配置可用的压缩模型。');
  const headers: Record<string, string> = {};
  for (const header of choice.model.customHeaders) {
    const name: string = header.name.trim();
    if (name.length > 0) headers[name] = header.value.trim();
  }
  const provider: ChatStreamProvider = asChatStreamProvider(await prepareProviderApi(choice.provider, headers),
    () => makeTextGenerationParams({ model: toChatModel(choice.model), customBody: choice.model.customBodies }));
  const jev = beginJevContextRun(conversation.id, `manual-compact:${newId()}`);
  const result: CompactResult = await compactConversation(conversation, policy,
    runtime.modelContextWindowTokens, 'manual_compact', '', true, {
      prepareToolResults: (source: UIMessage[], keep: number): Promise<JevPreparedToolResults> => jev.prepare(source, keep),
      provider, store: getCompactStore(), compressPrompt: (await loadTaskPrompts()).compressPrompt, newId,
    });
  if (result.status === 'failed') throw new Error(`压缩失败：${result.error ?? '未生成有效摘要'}`);
  const compacts: ConversationCompact[] = await getCompactStore().getCompacts(conversation.id);
  const saved: ConversationCompact | undefined = compacts.find((compact): boolean => compact.id === result.summaryId);
  const completed: boolean = result.status === 'completed';
  return {
    status: completed ? 'completed' : 'skipped',
    message: completed ? `已压缩 ${result.sourceMessageCount ?? 0} 条历史消息。` : skippedMessage(result.error ?? ''),
    summary: saved === undefined ? '' : compactTimelineSummary(saved.summary) ?? saved.summary,
    compacts, contextWindowTokens: runtime.modelContextWindowTokens,
  };
};
