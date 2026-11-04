import { beginConversationCompaction } from './ConversationRunGuard.ets';
// Detached Chat dependencies: existing Entry loaders and chat-domain factories.
import { hilog } from '@kit.PerformanceAnalysisKit';
import type {
  AgentSoulState,
  ChatTurnDeps, ChatStreamProvider, CompactPolicy, CompactResult, Conversation,
  ConversationSummary, DisplaySetting, MemoryRecallRuntimeGate, MemoryRecallSelection,
  MemoryRecord, MemoryScope, MemoryProfileSelection, MemoryReadRepository,
  MessageTransformer, MiniAppRecord, PreparedContext, RecentChatItem,
  SubAgentRuntimeSetting, UIMessage, MessageChunk, StreamOpts, JevPreparedToolResults,
} from '@amber/chat-domain';
import {
  AGENT_SOUL_STORAGE_KEY, defaultAgentSoulState, parseAgentSoulState,
  asChatStreamProvider, buildAgentSoulPrompt, buildMemoryContext, resolveGenerativeWidgetInstruction,
  buildRecentChatsPrompt, conversationWithMessagesAsNodes, createDocumentTransformer,
  createMiniAppOutputTransformer, createMiniAppPromptTransformer,
  createPlaceholderTransformer, createTemplateTransformer, createReasoningLevelMetadataTransformer,
  createTimeReminderTransformer, currentMessages, dayOfWeekFullName, estimateContextWindow,
  javaLocalDateTimeString, loadDisplaySetting, loadMiniAppSetting, loadSearchPrefs,
  makeCompactPolicy, makeGenerationRetrySetting, makeSubAgentRuntimeSetting,
  makeTextGenerationParams, prepareContext, RECENT_CHATS_PROMPT_LIMIT,
  regexOutputTransformer, createThinkTagTransformer, toChatModel, toText,
  recallMemorySelections, memoryRecallScoreToDebugText, selectMemoryProfile, createMemoryCitationTransformer,
  annotateSmartUiWeatherBindings,
} from '@amber/chat-domain';
import type { ChatRuntime, ChatProviderRuntime, ChatProviderChoice, TaskPrompts } from '../di/ChatRuntimeContracts.ets';
import type {
  DetachedConversationContext, PreparedConversationSeed,
} from './ConversationRunService.ets';
import { loadAgentRuntimeSnapshot, systemLanguage } from './AgentRuntimePrefs.ets';
import type { JevNoulBatchResult } from './JevSupport.ets';
import type { OcrSeed } from './OcrSupport.ets';

import type { AbortSignalLike } from '@amber/deepread-domain';
import type {
  AgentTool, CompactStore, ConversationRepository, DocumentReaderDeps, JevContextRun, KeyValueStore,
  MiniAppRepository, OpenAIChatApi, PlaceholderValues, ProviderSetting, TaskModelReference,
  TerminalRuntime, MoshRuntime, PythonRuntime, SSHProfileStore, SkillMetadata, OutputMessageTransformer,
} from '@amber/chat-domain';
import type { MemoryLibraryOps, MemoryTopicStoreOps } from './MemoryStore.ets';

export interface ChatExecutionResources {
  getTerminalRuntime: () => Promise<TerminalRuntime>;
  getMoshRuntime: () => Promise<MoshRuntime | null>;
  getPythonRuntime: () => Promise<PythonRuntime | null>;
  getSSHProfileStore: () => Promise<SSHProfileStore>;
  listSkills: () => SkillMetadata[];
}

export interface ConversationPreparationPorts extends ChatExecutionResources {
  kv: KeyValueStore;
  resolveProviderRuntime: (assistantId: string) => Promise<ChatProviderRuntime | null>;
  ensureSearchSdk: () => void;
  initializeMcp: () => Promise<void>;
  readCompressReference: () => Promise<TaskModelReference>;
  resolveTaskChoice: (reference: TaskModelReference, fallback: ChatProviderChoice) => Promise<ChatProviderChoice | null>;
  loadTaskPrompts: () => Promise<TaskPrompts>;
  loadOcrSeed: (kv: KeyValueStore) => Promise<OcrSeed>;
  hydrateSubAgentSetting: () => Promise<SubAgentRuntimeSetting>;
  imageGenAvailable: () => Promise<boolean>;
  loadAgentTaskTools: () => Promise<AgentTool[]>;
  loadAgentCronTools: () => Promise<AgentTool[]>;
}

export interface ConversationTurnPorts {
  repository: ConversationRepository;
  compactStore: CompactStore;
  memoryRepository: MemoryReadRepository;
  memoryLibrary: MemoryLibraryOps;
  memoryTopics: MemoryTopicStoreOps;
  rerankContext: (purpose: string, query: string, keys: string[], candidates: Record<string, string>) => Promise<JevNoulBatchResult | null>;
  loadTaskPrompts: () => Promise<TaskPrompts>;
  prepareProviderApi: (provider: ProviderSetting, headers: Record<string, string>) => Promise<OpenAIChatApi>;
  beginJevContextRun: (conversationId: string, runId: string, signal?: AbortSignalLike) => JevContextRun;
  loadMiniApp: (id: string) => Promise<MiniAppRecord | null>;
  miniAppRepository: MiniAppRepository;
  createDocumentReader: () => DocumentReaderDeps;
  createPlaceholderValues: (modelId: string, modelName: string, nicknameOf: () => string) => PlaceholderValues;
  createOcrTransformer: (seed: OcrSeed, supportsImage: boolean) => MessageTransformer;
  createBase64ImageTransformer: () => OutputMessageTransformer;
}

const DOMAIN: number = 0x0001;
const TAG: string = 'Chat.RunDependencies';

const logProviderCompletion = (
  ctx: DetachedConversationContext, characters: number, finishReason: string, outcome: string,
): void => {
  // Metadata only: original message text stays in the existing conversation store.
  hilog.info(DOMAIN, TAG, '%{public}s', JSON.stringify({
    runId: ctx.runId, conversationId: ctx.conversation.id, characters, finishReason, outcome,
  }));
};

// Provider resolution is side-effect free for local execution resources. Preparation
// awaits the enabled resources before their tools can be exposed to the provider.
export const prepareChatRuntime = async (
  runtime: ChatProviderRuntime, ports: ChatExecutionResources,
): Promise<ChatRuntime> => {
  const assistant = runtime.assistant;
  const terminalEnabled: boolean = assistant.localTools.includes('terminal');
  const terminalRuntime: TerminalRuntime | null = terminalEnabled ? await ports.getTerminalRuntime() : null;
  const moshRuntime: MoshRuntime | null = terminalEnabled ? await ports.getMoshRuntime() : null;
  const pythonRuntime: PythonRuntime | null = assistant.localTools.includes('python') ? await ports.getPythonRuntime() : null;
  const sshProfiles: SSHProfileStore | null = terminalEnabled ? await ports.getSSHProfileStore() : null;
  return {
    api: runtime.api, resolvedChoice: runtime.resolvedChoice, assistant, params: runtime.params,
    contextMessageSize: runtime.contextMessageSize,
    modelContextWindowTokens: runtime.modelContextWindowTokens,
    modelSupportsImageInput: runtime.modelSupportsImageInput,
    modelAbilities: runtime.modelAbilities,
    allSkills: ports.listSkills(), terminalRuntime, pythonRuntime, moshRuntime, sshProfiles,
  };
};

export const prepareConversationSeed = async (
  conversation: Conversation, ports: ConversationPreparationPorts,
): Promise<PreparedConversationSeed> => {
  const provider: ChatProviderRuntime | null = await ports.resolveProviderRuntime(conversation.assistantId);
  if (provider === null) throw new Error('未配置可用的 Provider/模型');
  const runtime: ChatRuntime = await prepareChatRuntime(provider, ports);
  const kv = ports.kv;
  ports.ensureSearchSdk();
  await ports.initializeMcp().catch((e: Error): void => {
    hilog.warn(DOMAIN, TAG, 'MCP initialization failed: %{public}s', e.message);
  });
  const display: DisplaySetting = await loadDisplaySetting(kv);
  const compressReference = await ports.readCompressReference();
  // Keep the existing optional OCR/subagent gates when their hydration fails.
  const ocr: OcrSeed | null = await ports.loadOcrSeed(kv).catch((): null => {
    hilog.warn(DOMAIN, TAG, 'OCR settings unavailable for this run');
    return null;
  });
  let subAgent: SubAgentRuntimeSetting;
  try {
    subAgent = await ports.hydrateSubAgentSetting();
  } catch {
    subAgent = makeSubAgentRuntimeSetting();
    hilog.warn(DOMAIN, TAG, 'Subagent settings unavailable for this run');
  }
  const agentSoulPersona: AgentSoulState = parseAgentSoulState(await kv.get(AGENT_SOUL_STORAGE_KEY));
  return {
    runtime,
    agentRuntime: await loadAgentRuntimeSnapshot(kv),
    agentSoulPersona,
    compressChoice: await ports.resolveTaskChoice(compressReference, runtime.resolvedChoice),
    taskPrompts: await ports.loadTaskPrompts(),
    searchSettings: await loadSearchPrefs(kv),
    ocrSeed: ocr,
    miniAppSetting: await loadMiniAppSetting(kv),
    userNickname: display.userNickname,
    generativeWidgetsEnabled: display.enableGenerativeWidgets,
    webmountGlobalEnabled: (await kv.get('webmount_global_enabled')) === 'true',
    webmountEvalEnabled: (await kv.get('webmount_eval_enabled')) === 'true',
    imageGenConfigured: await ports.imageGenAvailable().catch((): boolean => false),
    subAgentEnabled: subAgent.enabled,
    subAgentMode: subAgent.mode,
    agentTaskTools: await ports.loadAgentTaskTools(),
    agentCronTools: await ports.loadAgentCronTools(),
    enableGenerationNotification: display.enableNotificationOnMessageGeneration,
  };
};

const inputTransformers = (ctx: DetachedConversationContext, ports: ConversationTurnPorts): MessageTransformer[] => {
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
        ports.loadMiniApp(id),
    }),
    createTemplateTransformer(),
    createPlaceholderTransformer(ports.createPlaceholderValues(
      rt.params.model.modelId, rt.params.model.displayName, (): string => seed.userNickname)),
    createDocumentTransformer(ports.createDocumentReader()),
  ];
  if (seed.ocrSeed !== null) {
    list.push(ports.createOcrTransformer(seed.ocrSeed, rt.modelSupportsImageInput));
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

const extraSystemBlocks = async (ctx: DetachedConversationContext, ports: ConversationTurnPorts): Promise<string[]> => {
  const blocks: string[] = ctx.seed.generativeWidgetsEnabled
    ? [resolveGenerativeWidgetInstruction('declared-content-v1')] : [];
  if (!ctx.seed.agentRuntime.enableRecentChatsReference) return blocks;
  let recent: ConversationSummary[] = await ports.repository.listRecent(RECENT_CHATS_PROMPT_LIMIT);
  if (recent.length > 1) {
    const query: string = latestUserQuery(ctx.activeConversation(), ctx.conversation.title);
    const keys: string[] = [];
    const candidates: Record<string, string> = {};
    for (const summary of recent.slice(0, 12)) {
      keys.push(summary.id);
      candidates[summary.id] = summary.title.substring(0, 120);
    }
    const batch: JevNoulBatchResult | null = await ports.rerankContext('context_selection', query, keys, candidates);
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
  if (prompt.length > 0) blocks.push(prompt);
  return blocks;
};

const memoryReranker = (query: string, ports: ConversationTurnPorts):
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
    const batch: JevNoulBatchResult | null = await ports.rerankContext('memory_recall', query, keys, candidates);
    if (batch === null || batch.shadow) return selections;
    const kept: MemoryRecallSelection[] = selections.filter(
      (selection: MemoryRecallSelection): boolean =>
        (batch.probabilities[`${selection.record.id}`] ?? 1) >= 0.5);
    return kept;
  };

const memorySystemBlocks = async (ctx: DetachedConversationContext, ports: ConversationTurnPorts): Promise<string[]> => {
  const runtime = ctx.seed.agentRuntime;
  const gate: MemoryRecallRuntimeGate = {
    enableCoreMemory: runtime.enableCoreMemory,
    enableShortTermMemory: runtime.enableShortTermMemory,
    enableLongTermMemory: runtime.enableLongTermMemory,
    memoryRecall: runtime.memoryRecall,
  };
  const conversation: Conversation = ctx.activeConversation();
  const now: number = Date.now();
  const repository: MemoryReadRepository = ports.memoryRepository;
  let selections: MemoryRecallSelection[] = await recallMemorySelections(
    gate, currentMessages(conversation), repository, now);
  try { selections = await memoryReranker(latestUserQuery(conversation), ports)(selections); }
  catch (_error) { /* Keep local selections when the optional reranker fails. */ }
  const scopes: MemoryScope[] = [];
  if (gate.enableCoreMemory) scopes.push('core');
  if (gate.enableShortTermMemory) scopes.push('short_term');
  if (gate.enableLongTermMemory) scopes.push('long_term');
  const maxChars: number = Math.min(Math.max(gate.memoryRecall.maxPromptChars, 256), 12000);
  let profile: MemoryProfileSelection = { prompt: '', coveredIds: [] };
  if (selections.length > 0) {
    try {
      profile = selectMemoryProfile(await ports.memoryTopics.snapshotProfile(),
        await ports.memoryLibrary.getAllRecords(), scopes,
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
  if (injectedIds.length > 0) await repository.touchMemories(injectedIds);
  for (const id of injectedIds) {
    if (!ctx.recalledMemoryIds.includes(id)) ctx.recalledMemoryIds.push(id);
  }
  const blocks: string[] = [];
  if (profile.prompt.length > 0) blocks.push(profile.prompt);
  if (recordPrompt.length > 0) blocks.push(recordPrompt);
  return blocks;
};

export const buildConversationCompressProvider = (seed: PreparedConversationSeed, ports: ConversationTurnPorts): ChatStreamProvider => {
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
      const api = await ports.prepareProviderApi(choice.provider, headers);
      await api.streamText(messages, params, onChunk, opts);
    },
  };
};

const prepareMessages = (ctx: DetachedConversationContext, ports: ConversationTurnPorts):
  ((messages: UIMessage[]) => Promise<UIMessage[]>) => {
  const rt: ChatRuntime = ctx.seed.runtime;
  const jev = ports.beginJevContextRun(ctx.conversationId, ctx.runId, ctx.signal);
  const cc = ctx.seed.agentRuntime.contextCompaction;
  const policy: CompactPolicy = makeCompactPolicy({
    enabled: cc.enabled,
    notifyOnly: cc.notifyOnly,
    precompactRatio: cc.precompactRatio,
    forceRatio: cc.forceRatio,
    keepRecentTurns: cc.keepRecentTurns,
    maxSummaryTokens: cc.maxSummaryTokens,
  });
  const provider: ChatStreamProvider = buildConversationCompressProvider(ctx.seed, ports);
  let compactActiveCount: number = 0;
  const changeCompactCount = (change: number): void => {
    compactActiveCount = Math.max(0, compactActiveCount + change);
    ctx.publish({ compactActiveCount });
  };
  return async (messages: UIMessage[]): Promise<UIMessage[]> => {
    const effective: Conversation = conversationWithMessagesAsNodes(ctx.activeConversation(), messages);
    // Keep the original compress-prompt refresh; the provider/model remains this run's seed.
    const prompts: TaskPrompts = await ports.loadTaskPrompts();
    const prepared: PreparedContext = await prepareContext(effective, policy,
      rt.modelContextWindowTokens, messages, rt.contextMessageSize, {
        abortSignal: ctx.signal,
        prepareToolResults: (source: UIMessage[], keep: number): Promise<JevPreparedToolResults> => jev.prepare(source, keep),
        provider,
        store: ports.compactStore,
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
    return ctx.seed.generativeWidgetsEnabled
      ? annotateSmartUiWeatherBindings(prepared.messages) : prepared.messages;
  };
};

export const buildConversationTurnDeps = (ctx: DetachedConversationContext, ports: ConversationTurnPorts): ChatTurnDeps => {
  const seed: PreparedConversationSeed = ctx.seed;
  const rt: ChatRuntime = seed.runtime;
  const window: number = estimateContextWindow(rt.modelContextWindowTokens);
  return {
    assistant: rt.assistant,
    finalTokenBudget: Math.max(Math.trunc(window * seed.agentRuntime.contextCompaction.forceRatio), 4000),
    agentSoul: buildAgentSoulPrompt(
      seed.agentRuntime.agentSoulMarkdown,
      seed.agentSoulPersona ?? defaultAgentSoulState(),
    ),
    inputTransformers: inputTransformers(ctx, ports),
    modelSupportsImageInput: rt.modelSupportsImageInput,
    outputTransformers: [
      createThinkTagTransformer(),
      createReasoningLevelMetadataTransformer(rt.params.reasoningLevel, currentMessages(ctx.activeConversation())),
      ports.createBase64ImageTransformer(),
      createMiniAppOutputTransformer({
        enabled: (): boolean => seed.miniAppSetting.enabled,
        repository: ports.miniAppRepository,
      }),
      regexOutputTransformer,
      createMemoryCitationTransformer(ctx.runId, currentMessages(ctx.activeConversation()), (): number[] => ctx.recalledMemoryIds),
    ],
    provider: asChatStreamProvider(rt.api, () => rt.params),
    wrapStreamProvider: (provider: ChatStreamProvider): ChatStreamProvider => ({
      streamText: async (messages: UIMessage[], onChunk: (chunk: MessageChunk) => void,
        opts?: StreamOpts): Promise<void> => {
        let characters: number = 0;
        let finishReason: string = 'unknown';
        let outcome: string = 'finished';
        try {
          await provider.streamText(messages, (chunk: MessageChunk): void => {
            for (const choice of chunk.choices) {
              if (choice.finishReason !== null && choice.finishReason !== 'unknown') finishReason = choice.finishReason;
              const message: UIMessage | null = choice.delta ?? choice.message;
              if (message !== null) {
                for (const part of message.parts) {
                  if (part.type === 'text') characters += part.text.length;
                }
              }
            }
            onChunk(chunk);
          }, opts);
        } catch (error) {
          outcome = ctx.signal.aborted ? 'cancelled' : 'failed';
          throw error instanceof Error ? error : new Error(String(error));
        } finally {
          if (ctx.signal.aborted) outcome = 'cancelled';
          logProviderCompletion(ctx, characters, finishReason, outcome);
        }
      },
      generateText: provider.generateText === undefined ? undefined :
        async (messages: UIMessage[], opts?: StreamOpts): Promise<MessageChunk> => {
          let result: MessageChunk | null = null;
          let outcome: string = 'finished';
          try {
            result = await provider.generateText!(messages, opts);
            return result;
          } catch (error) {
            outcome = ctx.signal.aborted ? 'cancelled' : 'failed';
            throw error instanceof Error ? error : new Error(String(error));
          } finally {
            let characters: number = 0;
            let finishReason: string = 'unknown';
            if (result !== null) {
              for (const choice of result.choices) {
                if (choice.finishReason !== null && choice.finishReason !== 'unknown') finishReason = choice.finishReason;
                const message: UIMessage | null = choice.message ?? choice.delta;
                if (message !== null) {
                  for (const part of message.parts) {
                    if (part.type === 'text') characters += part.text.length;
                  }
                }
              }
            }
            logProviderCompletion(ctx, characters, finishReason, ctx.signal.aborted ? 'cancelled' : outcome);
          }
        },
    }),
    store: { save: (conversation: Conversation): Promise<void> => ctx.save(conversation) },
    contextMessageSize: rt.contextMessageSize,
    prepareContextMessages: prepareMessages(ctx, ports),
    memorySystemBlocks: (): Promise<string[]> => memorySystemBlocks(ctx, ports),
    extraSystemBlocks: (): Promise<string[]> => extraSystemBlocks(ctx, ports),
    abortSignal: ctx.signal,
    retrySetting: makeGenerationRetrySetting({
      enabled: seed.agentRuntime.generationRetry.enabled,
      maxRetries: seed.agentRuntime.generationRetry.maxRetries,
    }),
    onRetryStatus: (status: string | null): void => { ctx.publish({ retryStatus: status ?? '' }); },
  };
};
