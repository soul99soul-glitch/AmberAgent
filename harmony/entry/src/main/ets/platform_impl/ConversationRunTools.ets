import { createEntryJevAutoApprovalReview } from './JevAutoApprovalSupport.ets';
// Per-run tool catalog. No callback below depends on a ChatPage instance.
import { fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type {
  AgentTool, AgentToolActivityStore, Assistant, ChatStreamProvider,
  ChatToolDefinition, Conversation, DeepReadOpenEvent,
  LocalToolOption, McpToolActivityPort, PendingUserMessage,
  PluginStore, RecipeStore, TerminalApprovalGuard, TextGenerationParams,
  ToolLoopOptions, UIMessage, UIMessagePart, UIMessagePartImage,
} from '@amber/chat-domain';
import {
  AgentToolDispatcher, asChatStreamProvider, createClipboardTool,
  createConversationContextTools, createConversationHistoryTools, createConversationQueueTools,
  createDeepReadOpenTool, createDeepReadPlaybookTools, createHealthSummaryTool,
  createMcpManagementTools, createMcpServerTools, createMemoryTools,
  createRunPlanUpdateTool, createSearchTools, createSkillTools, createTerminalApprovalGuard,
  createProviderManagementTools, PROVIDERS_KEY,
  currentMessages, defaultToolInvocationHooks,
  filterToolProfile, makeCompactPolicy, makeTextGenerationParams, patchAssistant,
} from '@amber/chat-domain';
import {
  getAgentPromptConfigTool, getAppContainer, getChatAssistants, getChatKvStore, getChatRepository, getCompactStore,
  getDeepReadPlaybookRepository, getMcpManager, getPermissionsStatusTool,
  getPluginStore, getRecipeStore, getSessionGrantStore, getSkillManager,
  getSkillWorkspacePort, getSubAgentTools, getWorkspaceManager,
} from '../di/AppContainer.ets';
import type { ChatRuntime, SubAgentToolsContext } from '../di/AppContainer.ets';
import { systemLanguage } from './AgentRuntimePrefs.ets';
import { mutateAssistant } from './AssistantMutation.ets';
import { sharedKvMutex } from './AtomicJsonFile.ets';
import { createAssistantLocalPrimitives } from './AssistantLocalPrimitives.ets';
import { guardConversationBrowserTools, assertConversationBrowserOwner } from './ConversationBrowserGuard.ets';
import { buildConversationCompressProvider } from './ConversationRunDependencies.ets';
import type { DetachedConversationContext, PreparedConversationSeed } from './ConversationRunService.ets';
import { inflateRawEntry } from './EntryDocumentParser.ets';
import { getChatCouncilTools } from './CouncilChatSupport.ets';
import { createEntryClipboardPort, sha256HexUtf8 } from './EntryLocalToolPorts.ets';
import { createRunToolCatalog, scopeRunTools } from './RunToolCatalog.ets';
import type { RunToolCatalogSnapshot } from './RunToolCatalog.ets';
import { createFeishuDocsTools } from './FeishuDocsTools.ets';
import { createEntryHealthReadPort } from './HealthReadPort.ets';
import { createImageGenTool } from './ImageGenTool.ets';
import type { EditSourceImage } from './ImageGenTool.ets';
import { createJevStatusTool, jevToolSearchReranker } from './JevSupport.ets';
import { getMcpSettingsStore } from './McpSettingsStore.ets';
import { getMemoryToolDeps } from './MemoryStore.ets';
import { createSoulTools } from './SoulTools.ets';
import { getWebMountSession } from './WebMountSession.ets';
import { createWebMountTools, guardPage } from './WebMountTools.ets';
import { createWebMountAdvancedTools } from './WebMountAdvancedTools.ets';
import { chooseJevWebCandidate } from './JevWebDecision.ets';
import {
  createWebMountGoalAdapter, createWebMountGoalTool, makeSystemMessage, makeUserMessage, toText,
} from '@amber/chat-domain';
import type {
  JsonObject, JevWebCandidate, MessageChunk, WebMountGoalCandidate, WebMountGoalDecision,
  WebMountGoalHostPorts, WebMountGoalRequest, WebMountObservation,
} from '@amber/chat-domain';

// E12(goal):主模型选择器 — 同一 parent runtime API/身份,只许选有限候选 ID 或 handback
const runMainModelGoalChoice = async (
  rt: ChatRuntime, request: WebMountGoalRequest, observation: WebMountObservation,
  candidates: WebMountGoalCandidate[], signal?: AbortSignalLike,
): Promise<WebMountGoalDecision> => {
  const lines: string[] = candidates.map(
    (candidate: WebMountGoalCandidate): string => `${candidate.id} [${candidate.kind}] ${candidate.description}`);
  const systemPrompt: string = 'You are selecting exactly one next browser action for a bounded goal. '
    + 'Answer with exactly one candidate id from the list, or the single word handback. Never invent new ids.';
  const userText: string = [
    `Goal: ${request.goal}`,
    `Completion signal (matched against URL/title/visible text): ${request.completionText}`,
    `Page: ${observation.title} — ${observation.url}`,
    `Visible summary: ${observation.text.substring(0, 600)}`,
    'Candidates:',
    ...lines,
  ].join('\n');
  const provider: ChatStreamProvider = asChatStreamProvider(rt.api, (): TextGenerationParams => makeTextGenerationParams({
    model: rt.params.model, temperature: 0, maxTokens: 64, tools: [],
  }));
  const prompt: UIMessage[] = [makeSystemMessage(systemPrompt), makeUserMessage(userText)];
  let answer: string = '';
  if (provider.generateText !== undefined) {
    const chunk: MessageChunk = await provider.generateText(prompt, { signal: signal });
    const choice = chunk.choices[0];
    answer = choice !== undefined
      ? (choice.message !== null ? toText(choice.message) : choice.delta !== null ? toText(choice.delta) : '') : '';
  } else {
    let latest: string = '';
    await provider.streamText(prompt, (chunk: MessageChunk): void => {
      const choice = chunk.choices[0];
      if (choice !== undefined && choice.delta !== null) latest = toText(choice.delta);
    }, { signal: signal });
    answer = latest;
  }
  const trimmed: string = answer.trim();
  if (/^cand-\d+$/.test(trimmed) && candidates.some(
    (candidate: WebMountGoalCandidate): boolean => candidate.id === trimmed)) {
    return { kind: 'action', candidateId: trimmed, reason: 'main_model_choice' };
  }
  return { kind: 'handback', candidateId: null,
    reason: trimmed === 'handback' ? 'main_model_handback' : 'invalid_decision' };
};

const mcpActivityPort = (store: AgentToolActivityStore): McpToolActivityPort => ({
  startTool: (toolName: string, title: string, inputPreview: string, runtime: string): string =>
    store.startTool(toolName, title, inputPreview, runtime),
  complete: (toolCallId: string, outputPreview: string): void => {
    store.complete(toolCallId, outputPreview);
  },
  fail: (toolCallId: string, error: Error): void => {
    store.fail(toolCallId, error);
  },
});

const setSkillEnabled = async (rt: ChatRuntime, name: string, enable: boolean): Promise<void> => {
  await mutateAssistant(rt.assistant.id, (assistant: Assistant): Assistant => {
    const skills: string[] = enable
      ? (assistant.enabledSkills.indexOf(name) >= 0
        ? assistant.enabledSkills : assistant.enabledSkills.concat([name]))
      : assistant.enabledSkills.filter((item: string): boolean => item !== name);
    return patchAssistant(assistant, { enabledSkills: skills });
  });
};

const isAllowedEditMime = (mime: string): boolean =>
  mime === 'image/png' || mime === 'image/jpeg' || mime === 'image/webp';

const editMimeExt = (mime: string): string =>
  mime === 'image/jpeg' ? 'jpg' : (mime === 'image/webp' ? 'webp' : 'png');

const editMimeByName = (name: string): string => {
  const dot: number = name.lastIndexOf('.');
  if (dot < 0) return 'application/octet-stream';
  const ext: string = name.substring(dot + 1).toLowerCase();
  if (ext === 'png') return 'image/png';
  if (ext === 'jpg' || ext === 'jpeg') return 'image/jpeg';
  if (ext === 'webp') return 'image/webp';
  return 'application/octet-stream';
};

const readUriBytes = (uri: string): Uint8Array => {
  const file: fileIo.File = fileIo.openSync(uri, fileIo.OpenMode.READ_ONLY);
  try {
    const stat: fileIo.Stat = fileIo.statSync(file.fd);
    if (stat.size > 10 * 1024 * 1024) throw new Error(`文件超过 10MB: ${stat.size} bytes`);
    const buffer: ArrayBuffer = new ArrayBuffer(stat.size);
    fileIo.readSync(file.fd, buffer, { offset: 0, length: stat.size });
    return new Uint8Array(buffer);
  } finally {
    fileIo.closeSync(file);
  }
};

const loadEditSourceBytes = async (url: string): Promise<EditSourceImage | null> => {
  try {
    if (url.startsWith('data:')) {
      const comma: number = url.indexOf(',');
      if (comma < 0) return null;
      const mime: string = url.substring(5, comma).split(';')[0];
      if (!isAllowedEditMime(mime)) return null;
      const bytes: Uint8Array = new util.Base64Helper().decodeSync(url.substring(comma + 1));
      return { bytes, mime, name: `source.${editMimeExt(mime)}` };
    }
    if (url.startsWith('file://')) {
      const mime: string = editMimeByName(url);
      if (!isAllowedEditMime(mime)) return null;
      const bytes: Uint8Array = readUriBytes(url);
      const name: string = url.substring(url.lastIndexOf('/') + 1);
      return { bytes, mime, name: name.length > 0 ? name : `source.${editMimeExt(mime)}` };
    }
    return null;
  } catch {
    return null;
  }
};

const resolveEditSourceImage = async (ctx: DetachedConversationContext): Promise<EditSourceImage | null> => {
  const messages: UIMessage[] = currentMessages(ctx.conversation);
  for (let index: number = messages.length - 1; index >= 0; index--) {
    if (messages[index].role !== 'user') continue;
    for (const part of messages[index].parts) {
      if (part.type !== 'image') continue;
      const resolved: EditSourceImage | null = await loadEditSourceBytes((part as UIMessagePartImage).url);
      if (resolved !== null) return resolved;
    }
  }
  return null;
};

const prepareSubAgentTools = async (ctx: DetachedConversationContext): Promise<void> => {
  ctx.subAgentTools = [];
  if (!ctx.seed.subAgentEnabled) return;
  try {
    const subAgentContext: SubAgentToolsContext = {
      parentConversationId: ctx.conversationId,
      parentToolsProvider: (): AgentTool[] => ctx.baseTools,
      parentRuntime: ctx.seed.runtime,
      parentAgentRuntime: ctx.seed.agentRuntime,
    };
    ctx.subAgentTools = await getSubAgentTools(subAgentContext);
  } catch (error) {
    const message: string = error instanceof Error ? error.message : String(error);
    hilog.warn(0x0001, 'Chat.RunTools', 'prepareSubAgentTools failed: %{public}s', message);
  }
};

const warnIfToolAbilityMissing = (ctx: DetachedConversationContext): void => {
  const rt: ChatRuntime = ctx.seed.runtime;
  if (rt.modelAbilities.indexOf('tool') >= 0) return;
  if (!ctx.seed.searchSettings.enableWebSearch
    && getMcpManager().getAllAvailableTools([...rt.assistant.mcpServers]).length === 0) return;
  ctx.publish({ errorMsg: '当前模型未开启工具调用，请在设置内启用模型的工具能力，再使用搜索/MCP等功能' });
};

export const buildConversationToolLoop = async (ctx: DetachedConversationContext): Promise<ToolLoopOptions> => {
  const seed: PreparedConversationSeed = ctx.seed;
  const rt: ChatRuntime = seed.runtime;
  const conversation: Conversation = ctx.conversation;
  const conversationProvider: () => Promise<Conversation> = (): Promise<Conversation> =>
    Promise.resolve(ctx.conversation);
  const localOptions: LocalToolOption[] = rt.assistant.localTools;
  const terminalGuard: TerminalApprovalGuard | null = localOptions.indexOf('terminal') >= 0
    && rt.terminalRuntime !== null && rt.sshProfiles !== null
    ? createTerminalApprovalGuard(rt.terminalRuntime, rt.sshProfiles, rt.moshRuntime ?? undefined) : null;
  const webmountEnabled: boolean = seed.webmountGlobalEnabled || localOptions.indexOf('webmount') >= 0;
  const runtime = seed.agentRuntime;
  const compact = runtime.contextCompaction;
  // E12:浏览器 owner 断言惰性求值 — 循环构建时该会话未必持有可见页面;
  //   首次实际页面操作时绑定租约,离页后租约失效即拒绝(E6 语义保持)
  const ownerAssertion = (() => {
    let cached: (() => void) | null = null;
    return (): void => {
      if (cached === null) cached = assertConversationBrowserOwner(ctx.conversationId);
      cached();
    };
  })();
  const goalHostPorts: WebMountGoalHostPorts = {
    observe: (signal?: AbortSignalLike): Promise<WebMountObservation> =>
      getWebMountSession().observeDocument(signal),
    assertUsable: async (): Promise<void> => {
      ownerAssertion();
      const blocked: string | null = await guardPage(getWebMountSession(), getChatKvStore(),
        localOptions.indexOf('webmount') >= 0, true);
      if (blocked !== null) throw new Error(blocked);
    },
    choose: async (
      request: WebMountGoalRequest, observation: WebMountObservation,
      candidates: WebMountGoalCandidate[], signal?: AbortSignalLike,
    ): Promise<WebMountGoalDecision> => {
      if (request.decisionSource === 'jev') {
        // E11 有限候选接缝:OFF/SHADOW 即 handback,不自动降级主模型
        const jevCandidates: JevWebCandidate[] = candidates.map((candidate: WebMountGoalCandidate): JevWebCandidate => ({
          id: candidate.id,
          toolName: candidate.part.toolName,
          input: JSON.parse(candidate.part.input) as JsonObject,
          description: candidate.description,
        }));
        const jev = await chooseJevWebCandidate({
          sessionId: observation.token.sessionId,
          revision: `${observation.token.documentId}:${observation.token.revision}`,
          pageSummary: `${observation.title} ${observation.text.substring(0, 500)}`.substring(0, 600),
        }, jevCandidates, signal);
        return { kind: jev.kind === 'action' ? 'action' : 'handback',
          candidateId: jev.candidateId, reason: jev.reason };
      }
      return runMainModelGoalChoice(rt, request, observation, candidates, signal);
    },
    now: (): number => Date.now(),
  };
  const goalAdapter = createWebMountGoalAdapter(goalHostPorts);
  const councilTools: AgentTool[] = await getChatCouncilTools();
  const profiledTools: AgentTool[] = guardConversationBrowserTools(filterToolProfile([
    ...councilTools,
    ...createAssistantLocalPrimitives({ assistant: rt.assistant, conversationId: ctx.conversationId,
      workspace: getWorkspaceManager(), activityStore: ctx.activityStore,
      terminalRuntime: rt.terminalRuntime, moshRuntime: rt.moshRuntime,
      sshProfiles: rt.sshProfiles, pythonRuntime: rt.pythonRuntime }),
    ...(webmountEnabled ? createWebMountTools(getWebMountSession(),
      seed.webmountEvalEnabled || localOptions.indexOf('webmount_eval') >= 0,
      localOptions.indexOf('webmount') >= 0) : []),
    ...(webmountEnabled ? createWebMountAdvancedTools({
      session: getWebMountSession(),
      assistantEnabled: localOptions.indexOf('webmount') >= 0,
      assertOwner: ownerAssertion,
    }) : []),
    ...(webmountEnabled ? [createWebMountGoalTool()] : []),
    ...(webmountEnabled ? createFeishuDocsTools(localOptions.indexOf('webmount') >= 0) : []),
    ...createMcpManagementTools({ settings: getMcpSettingsStore(), manager: getMcpManager(),
      skills: getSkillManager(), assistantIds: [...rt.assistant.mcpServers] }),
    ...createProviderManagementTools({ store: getChatKvStore(), http: getAppContainer().httpClient,
      withLock: (action: () => Promise<UIMessagePart[]>): Promise<UIMessagePart[]> =>
        sharedKvMutex.withLock(PROVIDERS_KEY, action) }),
    ...createMcpServerTools({ manager: getMcpManager(), activity: mcpActivityPort(ctx.activityStore),
      assistantIds: [...rt.assistant.mcpServers] }),
    ...((runtime.enableCoreMemory || runtime.enableShortTermMemory || runtime.enableLongTermMemory)
      ? createMemoryTools(getMemoryToolDeps(
        new Intl.DateTimeFormat(systemLanguage(), { dateStyle: 'medium' }).format(new Date()))) : []),
    ...createConversationContextTools({
      conversationProvider,
      compactStore: getCompactStore(),
      policy: makeCompactPolicy({ enabled: compact.enabled, notifyOnly: compact.notifyOnly,
        precompactRatio: compact.precompactRatio, forceRatio: compact.forceRatio,
        keepRecentTurns: compact.keepRecentTurns, maxSummaryTokens: compact.maxSummaryTokens }),
      modelContextWindowTokens: rt.modelContextWindowTokens,
      compactEngineDeps: { provider: buildConversationCompressProvider(seed), store: getCompactStore(),
        compressPrompt: seed.taskPrompts.compressPrompt },
    }),
    ...(seed.imageGenConfigured ? [createImageGenTool({ conversationId: ctx.conversationId,
      sourceImageResolver: (): Promise<EditSourceImage | null> => resolveEditSourceImage(ctx) })] : []),
    ...createConversationHistoryTools({ repository: getChatRepository(),
      currentConversationProvider: conversationProvider, grantStore: getSessionGrantStore() }),
    ...createConversationQueueTools({
      pendingProvider: (): Promise<PendingUserMessage[]> => Promise.resolve(ctx.pending()),
      cancelOne: (messageId: string): Promise<void> => ctx.cancelPending(messageId),
      clearAll: (): Promise<void> => ctx.clearPending(),
    }),
    ...seed.agentTaskTools,
    getPermissionsStatusTool(), createJevStatusTool(), ...seed.agentCronTools,
    createRunPlanUpdateTool(), createHealthSummaryTool(createEntryHealthReadPort()),
    ...createSoulTools(),
    ...(localOptions.indexOf('clipboard') >= 0 ? [createClipboardTool(createEntryClipboardPort())] : []),
    getAgentPromptConfigTool(), ...createDeepReadPlaybookTools(getDeepReadPlaybookRepository()),
    createDeepReadOpenTool({
      bus: { hasCollectors: (): boolean => ctx.hasNavigationCollector(),
        emit: (event: DeepReadOpenEvent): void => ctx.emitDeepRead(event) },
      sha256Hex: sha256HexUtf8,
    }),
    ...(seed.searchSettings.enableWebSearch ? createSearchTools(seed.searchSettings) : []),
  ], rt.assistant.toolProfile).tools, ctx.conversationId);
  await prepareSubAgentTools(ctx);
  warnIfToolAbilityMissing(ctx);
  const recipeStore: RecipeStore = await getRecipeStore();
  const pluginStore: PluginStore = await getPluginStore();
  const catalog = async (): Promise<RunToolCatalogSnapshot> => {
    // Skill management changes this assistant during the loop. Refresh only its
    // skill catalog; the provider, model and all other run settings stay captured.
    const latestAssistant: Assistant | undefined = (await getChatAssistants()).find(
      (assistant: Assistant): boolean => assistant.id === rt.assistant.id);
    const skillTools: AgentTool[] = createSkillTools({
      enabledSkills: latestAssistant?.enabledSkills ?? [],
      allSkills: getSkillManager().listSkills(),
      skillManager: getSkillManager(),
      setSkillEnabled: (name: string, enable: boolean): Promise<void> => setSkillEnabled(rt, name, enable),
      workspace: getSkillWorkspacePort(),
      inflateRaw: inflateRawEntry,
    });
    return createRunToolCatalog({
      primitives: [...profiledTools, ...skillTools], profile: rt.assistant.toolProfile,
      conversationId: ctx.conversationId, recipeStore, pluginStore, reranker: jevToolSearchReranker(),
    });
  };
  let snapshot: RunToolCatalogSnapshot = await catalog();
  ctx.baseTools = snapshot.baseTools;
  const tools: AgentTool[] = scopeRunTools([...snapshot.tools, ...ctx.subAgentTools], ctx.conversationId, ctx.activityStore);
  const options: ToolLoopOptions = {
    dispatcher: new AgentToolDispatcher({ autoApprovalReview: createEntryJevAutoApprovalReview(currentMessages(ctx.conversation),
      (): UIMessage[] => currentMessages(ctx.conversation)),
      hooks: terminalGuard === null
      ? defaultToolInvocationHooks() : [...defaultToolInvocationHooks(), terminalGuard.hook] }),
    captureInvocationMetadata: terminalGuard === null ? undefined : terminalGuard.captureInvocationMetadata,
    tools,
    recipeAdapter: snapshot.recipeAdapter,
    pluginAdapter: snapshot.pluginAdapter,
    goalAdapter: webmountEnabled ? goalAdapter : undefined,
    refreshTools: async (): Promise<AgentTool[]> => {
      snapshot = await catalog();
      ctx.baseTools = snapshot.baseTools;
      options.recipeAdapter = snapshot.recipeAdapter;
      options.pluginAdapter = snapshot.pluginAdapter;
      return scopeRunTools([...snapshot.tools, ...ctx.subAgentTools], ctx.conversationId, ctx.activityStore);
    },
    makeProviderForStep: (definitions: ChatToolDefinition[]): ChatStreamProvider =>
      asChatStreamProvider(rt.api, (): TextGenerationParams => makeTextGenerationParams({
        model: rt.params.model, temperature: rt.params.temperature, topP: rt.params.topP,
        maxTokens: rt.params.maxTokens, tools: definitions, reasoningLevel: rt.params.reasoningLevel,
        customBody: rt.params.customBody,
      })),
    consumeSteerMessages: (): Promise<UIMessage[]> => ctx.consumeSteer(),
    toolPromptModel: rt.params.model,
    autoApproveTools: runtime.autoApproveAllToolCalls || conversation.autoApproveToolCalls,
    autoApproveHighRiskTools: runtime.autoApproveHighRiskToolCalls,
    speculativeEnabled: runtime.speculativeToolExecution.enabled,
    speculativeMaxConcurrentTools: runtime.speculativeToolExecution.maxConcurrentTools,
    maxSteps: runtime.maxToolLoopSteps,
  };
  return options;
};
