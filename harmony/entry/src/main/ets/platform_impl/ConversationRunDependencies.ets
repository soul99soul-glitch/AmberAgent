import { beginJevContextRun } from './JevContextSupport.ets';
import { beginConversationCompaction } from './ConversationRunGuard.ets';
// Detached Chat dependencies: existing Entry loaders and chat-domain factories.
import { hilog } from '@kit.PerformanceAnalysisKit';
import type {
  ChatTurnDeps, ChatStreamProvider, CompactPolicy, CompactResult, Conversation,
  ConversationSummary, DisplaySetting, MemoryRecallRuntimeGate, MemoryRecallSelection,
  MemoryRecord, MemoryScope, MemoryProfileSelection,
  MessageTransformer, MiniAppRecord, PreparedContext, RecentChatItem,
  SubAgentRuntimeSetting, UIMessage, MessageChunk, StreamOpts, JevPreparedToolResults,
} from '@amber/chat-domain';
import {
  asChatStreamProvider, buildAgentSoulPrompt, buildMemoryContext,
  buildRecentChatsPrompt, conversationWithMessagesAsNodes, createDocumentTransformer,
  createMiniAppOutputTransformer, createMiniAppPromptTransformer,
  createPlaceholderTransformer, createTemplateTransformer, createReasoningLevelMetadataTransformer,
  createTimeReminderTransformer, currentMessages, dayOfWeekFullName, estimateContextWindow,
  javaLocalDateTimeString, loadDisplaySetting, loadMiniAppSetting, loadSearchPrefs,
  makeCompactPolicy, makeGenerationRetrySetting, makeSubAgentRuntimeSetting,
  makeTextGenerationParams, prepareContext, RECENT_CHATS_PROMPT_LIMIT,
  regexOutputTransformer, createThinkTagTransformer, toChatModel, toText,
  recallMemorySelections, memoryRecallScoreToDebugText, selectMemoryProfile, createMemoryCitationTransformer,
} from '@amber/chat-domain';
import {
  prepareProviderApi, getAgentCronTools, getAgentTaskTools, getChatKvStore,
  getChatRepository, getCompactStore, hydrateSubAgentSetting, initMcpManager,
  readTaskModelReference, TASK_COMPRESS_MODEL_KEY, loadTaskPrompts, resolveChatRuntime, resolveTaskModelReference,
} from '../di/AppContainer.ets';
import type { ChatRuntime, TaskPrompts } from '../di/AppContainer.ets';
import type {
  DetachedConversationContext, PreparedConversationSeed,
} from './ConversationRunService.ets';
import { loadAgentRuntimeSnapshot, systemLanguage } from './AgentRuntimePrefs.ets';
import { createEntryBase64ImageTransformer } from './Base64ImageSupport.ets';
import { createEntryDocumentReader } from './DocumentSupport.ets';
import { imageGenToolAvailable } from './ImageGenTool.ets';
import { jevNoulBatch } from './JevSupport.ets';
import type { JevNoulBatchResult } from './JevSupport.ets';
import { getMemoryReadRepository, getMemoryLibraryOps, getMemoryTopicStoreOps } from './MemoryStore.ets';
import { getMiniAppPlatform, getMiniAppRepositorySync } from './MiniAppPlatform.ets';
import type { MiniAppPlatform } from './MiniAppPlatform.ets';
import { createEntryOcrTransformer, loadOcrSeed } from './OcrSupport.ets';
import type { OcrSeed } from './OcrSupport.ets';
import { createEntryPlaceholderValues } from './PlaceholderSupport.ets';
import { ensureSearchSdk } from './SearchSdkInit.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'Chat.RunDependencies';

export const prepareConversationSeed = async (
  conversation: Conversation,
): Promise<PreparedConversationSeed> => {
  const runtime: ChatRuntime | null = await resolveChatRuntime(conversation.assistantId);
  if (runtime === null) throw new Error('未配置可用的 Provider/模型');
  const kv = getChatKvStore();
  ensureSearchSdk();
  await initMcpManager().catch((e: Error): void => {
    hilog.warn(DOMAIN, TAG, 'MCP initialization failed: %{public}s', e.message);
  });
  const display: DisplaySetting = await loadDisplaySetting(kv);
  const compressReference = await readTaskModelReference(TASK_COMPRESS_MODEL_KEY);
  // Keep the existing optional OCR/subagent gates when their hydration fails.
  const ocr: OcrSeed | null = await loadOcrSeed(kv).catch((): null => {
    hilog.warn(DOMAIN, TAG, 'OCR settings unavailable for this run');
    return null;
  });
  let subAgent: SubAgentRuntimeSetting;
  try {
    subAgent = await hydrateSubAgentSetting();
  } catch {
    subAgent = makeSubAgentRuntimeSetting();
    hilog.warn(DOMAIN, TAG, 'Subagent settings unavailable for this run');
  }
  return {
    runtime,
    agentRuntime: await loadAgentRuntimeSnapshot(kv),
    compressChoice: await resolveTaskModelReference(compressReference, runtime.resolvedChoice),
    taskPrompts: await loadTaskPrompts(),
    searchSettings: await loadSearchPrefs(kv),
    ocrSeed: ocr,
    miniAppSetting: await loadMiniAppSetting(kv),
    userNickname: display.userNickname,
    webmountGlobalEnabled: (await kv.get('webmount_global_enabled')) === 'true',
    webmountEvalEnabled: (await kv.get('webmount_eval_enabled')) === 'true',
    imageGenConfigured: await imageGenToolAvailable().catch((): boolean => false),
    subAgentEnabled: subAgent.enabled,
    subAgentMode: subAgent.mode,
    agentTaskTools: await getAgentTaskTools(),
    agentCronTools: await getAgentCronTools(),
    enableGenerationNotification: display.enableNotificationOnMessageGeneration,
  };
};

const inputTransformers = (ctx: DetachedConversationContext): MessageTransformer[] => {
  const seed: PreparedConversationSeed = ctx.seed;
  const rt: ChatRuntime = seed.runtime;
  const list: MessageTransformer[] = [
    createTimeReminderTransformer({
      enabled: (): boolean => seed.agentRuntime.enableTimeReminder,
      formatDayOfWeek: (ms: number): string => dayOfWeekFullName(systemLanguage(), ms),
      formatLocalDateTime: javaLocalDateTimeString,
    }),
    createMiniAppPromptTransformer({
      enabled: (): boolean => seed.miniAppSetting.enabled,
      loadApp: (id: string): Promise<MiniAppRecord | null> =>
        getMiniAppPlatform().then((platform: MiniAppPlatform): Promise<MiniAppRecord | null> =>
          platform.repository.getById(id)),
    }),
    createTemplateTransformer(),
    createPlaceholderTransformer(createEntryPlaceholderValues(
      rt.params.model.modelId, rt.params.model.displayName, (): string => seed.userNickname)),
    createDocumentTransformer(createEntryDocumentReader()),
  ];
  if (seed.ocrSeed !== null) {
    list.push(createEntryOcrTransformer(seed.ocrSeed, rt.modelSupportsImageInput));
  }
  return list;
};

const latestUserQuery = (conversation: Conversation, fallback: string = ''): string => {
  const messages: UIMessage[] = currentMessages(conversation);
  for (let i: number = messages.length - 1; i >= 0; i--) {
    if (messages[i].role === 'user') return toText(messages[i]).substring(0, 300);
  }
  return fallback;
};

const extraSystemBlocks = async (ctx: DetachedConversationContext): Promise<string[]> => {
  if (!ctx.seed.agentRuntime.enableRecentChatsReference) return [];
  let recent: ConversationSummary[] = await getChatRepository().listRecent(RECENT_CHATS_PROMPT_LIMIT);
  if (recent.length > 1) {
    const query: string = latestUserQuery(ctx.conversation, ctx.conversation.title);
    const keys: string[] = [];
    const candidates: Record<string, string> = {};
    for (const summary of recent.slice(0, 12)) {
      keys.push(summary.id);
      candidates[summary.id] = summary.title.substring(0, 120);
    }
    const batch: JevNoulBatchResult | null = await jevNoulBatch('context_selection', query, keys, candidates);
    if (batch !== null && !batch.shadow) {
      const filtered: ConversationSummary[] = recent.filter(
        (summary: ConversationSummary): boolean => (batch.probabilities[summary.id] ?? 1) >= 0.5);
      if (filtered.length > 0) recent = filtered;
    }
  }
  const items: RecentChatItem[] = recent.map((summary: ConversationSummary): RecentChatItem => ({
    title: summary.title,
    lastChat: new Intl.DateTimeFormat(systemLanguage(), { dateStyle: 'medium' })
      .format(new Date(summary.updateAt)),
  }));
  const prompt: string = buildRecentChatsPrompt(items);
  return prompt.length > 0 ? [prompt] : [];
};

const memoryReranker = (query: string):
  ((selections: MemoryRecallSelection[]) => Promise<MemoryRecallSelection[]>) =>
  async (selections: MemoryRecallSelection[]): Promise<MemoryRecallSelection[]> => {
    if (selections.length === 0) return selections;
    const keys: string[] = [];
    const candidates: Record<string, string> = {};
    for (const selection of selections.slice(0, 8)) {
      const key: string = `${selection.record.id}`;
      keys.push(key);
      candidates[key] = selection.record.content.substring(0, 120);
    }
    const batch: JevNoulBatchResult | null = await jevNoulBatch('memory_recall', query, keys, candidates);
    if (batch === null || batch.shadow) return selections;
    const kept: MemoryRecallSelection[] = selections.filter(
      (selection: MemoryRecallSelection): boolean =>
        (batch.probabilities[`${selection.record.id}`] ?? 1) >= 0.5);
    return kept;
  };

const memorySystemBlocks = async (ctx: DetachedConversationContext): Promise<string[]> => {
  const runtime = ctx.seed.agentRuntime;
  const gate: MemoryRecallRuntimeGate = {
    enableCoreMemory: runtime.enableCoreMemory,
    enableShortTermMemory: runtime.enableShortTermMemory,
    enableLongTermMemory: runtime.enableLongTermMemory,
    memoryRecall: runtime.memoryRecall,
  };
  const conversation: Conversation = ctx.conversation;
  const now: number = Date.now();
  let selections: MemoryRecallSelection[] = await recallMemorySelections(
    gate, currentMessages(conversation), getMemoryReadRepository(), now);
  try { selections = await memoryReranker(latestUserQuery(conversation))(selections); }
  catch (_error) { /* Keep local selections when the optional reranker fails. */ }
  const scopes: MemoryScope[] = [];
  if (gate.enableCoreMemory) scopes.push('core');
  if (gate.enableShortTermMemory) scopes.push('short_term');
  if (gate.enableLongTermMemory) scopes.push('long_term');
  const maxChars: number = Math.min(Math.max(gate.memoryRecall.maxPromptChars, 256), 12000);
  let profile: MemoryProfileSelection = { prompt: '', coveredIds: [] };
  if (selections.length > 0) {
    try {
      profile = selectMemoryProfile(await getMemoryTopicStoreOps().snapshotProfile(),
        await getMemoryLibraryOps().getAllRecords(), scopes,
        selections.map((selection: MemoryRecallSelection): number => selection.record.id), now, maxChars);
    } catch (_error) { /* Individual records remain usable if the optional profile is unavailable. */ }
  }
  const records: MemoryRecord[] = [];
  const details: Record<number, string> = {};
  let recordPrompt: string = '';
  for (const selection of selections) {
    if (profile.coveredIds.includes(selection.record.id)) continue;
    details[selection.record.id] = memoryRecallScoreToDebugText(selection.score);
    const candidate: string = buildMemoryContext(records.concat([selection.record]), gate.memoryRecall.debug, details, now);
    const separatorChars: number = profile.prompt.length > 0 && candidate.length > 0 ? 2 : 0;
    if (profile.prompt.length + separatorChars + candidate.length > maxChars) continue;
    records.push(selection.record);
    recordPrompt = candidate;
  }
  const injectedIds: number[] = profile.coveredIds.concat(records.map((record: MemoryRecord): number => record.id));
  for (const id of injectedIds) {
    if (!ctx.recalledMemoryIds.includes(id)) ctx.recalledMemoryIds.push(id);
  }
  const blocks: string[] = [];
  if (profile.prompt.length > 0) blocks.push(profile.prompt);
  if (recordPrompt.length > 0) blocks.push(recordPrompt);
  return blocks;
};

export const buildConversationCompressProvider = (seed: PreparedConversationSeed): ChatStreamProvider => {
  const rt: ChatRuntime = seed.runtime;
  const choice = seed.compressChoice;
  if (choice === null) return asChatStreamProvider(rt.api, () => rt.params);
  const headers: Record<string, string> = {};
  for (const header of choice.model.customHeaders) {
    const name: string = header.name.trim();
    if (name.length > 0) headers[name] = header.value.trim();
  }
  const params = makeTextGenerationParams({
    model: toChatModel(choice.model), customBody: choice.model.customBodies,
  });
  return {
    streamText: async (messages: UIMessage[], onChunk: (chunk: MessageChunk) => void,
      opts?: StreamOpts): Promise<void> => {
      const api = await prepareProviderApi(choice.provider, headers);
      await api.streamText(messages, params, onChunk, opts);
    },
  };
};

const prepareMessages = (ctx: DetachedConversationContext):
  ((messages: UIMessage[]) => Promise<UIMessage[]>) => {
  const rt: ChatRuntime = ctx.seed.runtime;
  const jev = beginJevContextRun(ctx.conversationId, ctx.runId, ctx.signal);
  const cc = ctx.seed.agentRuntime.contextCompaction;
  const policy: CompactPolicy = makeCompactPolicy({
    enabled: cc.enabled,
    notifyOnly: cc.notifyOnly,
    precompactRatio: cc.precompactRatio,
    forceRatio: cc.forceRatio,
    keepRecentTurns: cc.keepRecentTurns,
    maxSummaryTokens: cc.maxSummaryTokens,
  });
  const provider: ChatStreamProvider = buildConversationCompressProvider(ctx.seed);
  let compactActiveCount: number = 0;
  const changeCompactCount = (change: number): void => {
    compactActiveCount = Math.max(0, compactActiveCount + change);
    ctx.publish({ compactActiveCount });
  };
  return async (messages: UIMessage[]): Promise<UIMessage[]> => {
    const effective: Conversation = conversationWithMessagesAsNodes(ctx.conversation, messages);
    // Keep the original compress-prompt refresh; the provider/model remains this run's seed.
    const prompts: TaskPrompts = await loadTaskPrompts();
    const prepared: PreparedContext = await prepareContext(effective, policy,
      rt.modelContextWindowTokens, messages, rt.contextMessageSize, {
        abortSignal: ctx.signal,
        prepareToolResults: (source: UIMessage[], keep: number): Promise<JevPreparedToolResults> => jev.prepare(source, keep),
        provider,
        store: getCompactStore(),
        compressPrompt: prompts.compressPrompt,
        launchPrecompact: (run: () => Promise<CompactResult>): void => {
          const release: () => void = beginConversationCompaction(ctx.conversationId);
          changeCompactCount(1);
          run().catch((e: Error): void => {
            hilog.warn(DOMAIN, TAG, 'precompact failed: %{public}s', e.message);
          }).finally((): void => { changeCompactCount(-1); release(); });
        },
        onCompactActiveChange: (active: boolean): void => { changeCompactCount(active ? 1 : -1); },
      });
    return prepared.messages;
  };
};

export const buildConversationTurnDeps = (ctx: DetachedConversationContext): ChatTurnDeps => {
  const seed: PreparedConversationSeed = ctx.seed;
  const rt: ChatRuntime = seed.runtime;
  const window: number = estimateContextWindow(rt.modelContextWindowTokens);
  return {
    assistant: rt.assistant,
    finalTokenBudget: Math.max(Math.trunc(window * seed.agentRuntime.contextCompaction.forceRatio), 4000),
    agentSoul: buildAgentSoulPrompt(seed.agentRuntime.agentSoulMarkdown),
    inputTransformers: inputTransformers(ctx),
    modelSupportsImageInput: rt.modelSupportsImageInput,
    outputTransformers: [
      createThinkTagTransformer(),
      createReasoningLevelMetadataTransformer(rt.params.reasoningLevel, currentMessages(ctx.conversation)),
      createEntryBase64ImageTransformer(),
      createMiniAppOutputTransformer({
        enabled: (): boolean => seed.miniAppSetting.enabled,
        repository: getMiniAppRepositorySync(),
      }),
      regexOutputTransformer,
      createMemoryCitationTransformer(ctx.runId, currentMessages(ctx.conversation), (): number[] => ctx.recalledMemoryIds),
    ],
    provider: asChatStreamProvider(rt.api, () => rt.params),
    store: { save: (conversation: Conversation): Promise<void> => ctx.save(conversation) },
    contextMessageSize: rt.contextMessageSize,
    prepareContextMessages: prepareMessages(ctx),
    memorySystemBlocks: (): Promise<string[]> => memorySystemBlocks(ctx),
    extraSystemBlocks: (): Promise<string[]> => extraSystemBlocks(ctx),
    abortSignal: ctx.signal,
    retrySetting: makeGenerationRetrySetting({
      enabled: seed.agentRuntime.generationRetry.enabled,
      maxRetries: seed.agentRuntime.generationRetry.maxRetries,
    }),
    onRetryStatus: (status: string | null): void => { ctx.publish({ retryStatus: status ?? '' }); },
  };
};
